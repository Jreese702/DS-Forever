package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * OCEAN ONLY (the user, 2026-11-17, on 1.0.34 test 1485: "The water in stormwind looks AWFUL I don't know why we painted any of the actual
 * maps and not just only the surrounding ocean"): only the OPEN SEA round and between the continents gets the depth. A continent's
 * [Field] holds the ocean alone ([oceanMask]: its water eroded by [OCEAN_ERODE] cells, flooded from the ADTs the continent lacks, grown
 * back) - lakes, rivers, Stormwind's canals, Loch Modan, Blackrock's black are land to it - and a repaint changes only water near the
 * field's ocean that is joined to it; with no field (yet) nothing is repainted. Every other pixel is the game's own art.
 * And the field's sea picture is the continent itself at 8 px an ADT (its land and lakes the art's colour, the ocean by depth): it lies
 * under the terrain pictures and the tiles, so a picture still being made shows the land, never the shallows' teal in its shape (the
 * zoom-out glitch: "When zoomed out all of the way on the map the land masses glitch and disappear and all I see is teal in the shape of
 * the land").
 *
 * WATER WITH DEPTH ON HOME'S TERRAIN (the user, 2026-11-17: "I dont love it being one shade of teal. Can we give the water depth?"; the
 * pick: "Shore-to-deep gradient" - light turquoise shallows hugging every coast, fading to a deep navy-teal further out, a soft bright
 * line at the shoreline; every continent, zoomed in and out).
 *
 * The minimap's own water is near-black on most of Kalimdor, blue on parts of Eastern Kingdoms, a different shade per ADT (the squares
 * off Westfall). Each picture's water is found by its colour ([weights]: near-black, or blue/teal over red) and REPAINTED by its distance
 * to land, once, when the picture is decoded or made (MinimapTiles' close tiles, TerrainOverview's levels): baked into the RGB_565
 * picture itself, never a filter or a pass per frame, no extra picture per tile.
 *
 * NO SEAMS: a picture alone does not know the land just past its edge, so the distance would restart at every tile's border (shallows
 * along the borders out at sea). Each continent has ONE [Field]: its land / water at 16 px an ADT (33 yd; TerrainOverview's level 4
 * pictures), its distance to land by a two-pass chamfer, built once per minimap build off the UI thread and kept on the disk (bytes,
 * not pictures). A picture's own distance (fine, from its own pixels) is seeded with the field's, so across a border both sides agree to
 * the field's 33 yd; the bright shoreline is the picture's own (it hugs the coast at any zoom). The field's [Field.sea] (8 px an ADT)
 * is the water where a continent has no ADT at all, drawn under the tiles, so the gradient runs on past the last tile and ends in [DEEP]
 * - the same colour as the open sea round every box (no hard edge where the boxes end).
 *
 * REVIEW 3 (straight cuts at the coast, a black fringe, a dark block off Westfall, a line in the open sea): ONE reading of the art at
 * every scale ([darkW], [seabedW], [tealW] - the strict and lenient readings of full and coarse pictures disagreed on the coastal
 * fringe); the fringe's share smoothed and blended (no blocky staircase, no black band); a repaint reads its NEIGHBOURS' cells past
 * its edges ([MARGIN_CELLS]: every smoothing, opening, gate and shoreline sees across a border as the neighbour does); the field's
 * thin land opened away and the world's edge strip repainted (three Kalimdor ADTs); "black water near the field's water" bilinear,
 * not per 33 yd cell.
 *
 * REVIEW 4 (1.0.34 test 1485: Goldshire's blue roofs repainted as turquoise pools): bright teal is water only inside water that is
 * mostly darker ([BRIGHT_S]); a small piece of all-bright "water" seen whole is a roof and keeps the art's colours.
 *
 * Until a continent's field is built (the first time only: then it is read from the disk) pictures are made with their own distance
 * alone ("provisional": TerrainOverview keys them apart, MinimapTiles drops them when the field lands) and redrawn when it is ready.
 *
 * Memory: the fields (bytes, ~0.4-0.7 MB a continent with their 1-bit "water near" and sea pictures, at most [FIELDS_MAX]) and the
 * repaint's scratch (~0.65 MB, one set, the repaint is serialised; a neighbour decoded for its margin is freed at once - 0.5 MB at
 * most, briefly); MemWatch's "waterdepth". The picture caches' budgets are unchanged.
 */
object WaterDepth {
    const val ADT_YD = 533.33333f
    /** a field's cells an ADT (TerrainOverview's level 4: 256 px for 16 ADTs) */
    const val FIELD_PX = 16
    const val FIELD_LEVEL = 4
    /** the sea round a continent's ADTs, in ADTs (the gradient reaches [DEEP] inside it) */
    const val MARGIN = 3
    /** a field's units: a third of a cell (the chamfer's 3-4 steps) */
    const val UNIT_YD = ADT_YD / FIELD_PX / 3f
    private const val FIELDS_MAX = 3

    // ---------------------------------------------------------------- the palette (sits with the game's map art: turquoise to navy-teal)
    /** the open sea, far from any coast, and round every continent's box */
    val DEEP = Color.rgb(16, 48, 74)            // (exact in RGB_565: the pictures' far sea and the fill meet with no edge)
    private val STOPS_YD = floatArrayOf(0f, 90f, 360f, 800f, 1400f)
    private val STOPS = arrayOf(
        intArrayOf(78, 170, 164),     // the shallows: turquoise
        intArrayOf(56, 146, 152),
        intArrayOf(32, 106, 128),     // mid-teal
        intArrayOf(21, 70, 98),
        intArrayOf(16, 48, 74))       // deep navy-teal (= DEEP)
    private val FOAM = intArrayOf(190, 230, 218)
    /** the shoreline's line: full within [FOAM_IN] yd of land, gone at [FOAM_OUT] */
    private const val FOAM_IN = 3f
    private const val FOAM_OUT = 15f
    private const val FOAM_MAX = 0.5f
    /** black areas narrower than this (yd) are not water: the crevices between Mount Hyjal's boulders */
    private const val BLACK_OPEN_YD = 12f
    private const val TEAL_OPEN_YD = 8f
    /** land narrower than this (yd) makes no shallows of its own (only its shoreline): about the field's cell */
    private const val LAND_OPEN_YD = 36f
    /** the repaint fades out over this far into the land (yd) */
    private const val GATE_YD = 12f
    /** the dark water's share is smoothed over this (yd, a box's half-width): the coastal fringe's clumps */
    private const val FRINGE_YD = 10f
    /** land narrower than this (yd) in the water is water: a speck */
    private const val SPECK_YD = 6f
    /** BLUE ROOFS ARE NOT WATER (review of 1.0.34 test 1485: Goldshire's Lion's Pride Inn, the farmhouses round it, Stormwind's and
     *  Elwynn's blue roofs came out as turquoise pools with a shoreline). A cell's teal this bright (R+G+B of its mean; the minimap's
     *  roofs (64, 96, 128) .. (96, 128, 176), its lakes and seas (16, 48, 48) .. (31, 65, 93), under 200 on all of Eastern Kingdoms)
     *  is water only as part of water that is mostly darker ([ROOF_SHARE]): a lake's light shallows (Loch Modan) stay, a roof on the
     *  grass - all bright, its shaded edge a sliver - goes back to the art. Only a piece of water no bigger than [ROOF_MAX_YD2] (the
     *  biggest roof on both continents ~3200 yd2; Loch Modan's lake, about half light shallows, far more) and seen whole (not reaching
     *  past the cells a repaint reads: a lake's corner is not judged by its corner) */
    private const val BRIGHT_S = 220
    private const val ROOF_SHARE = 0.5f
    private const val ROOF_MAX_YD2 = 7000f
    /** the world's edge strip (yd) and how far from the field's land it must be to go (yd) */
    private const val EDGE_BAND_YD = 40f
    private const val EDGE_SEA_YD = 25f
    /** a picture's land counts for the gradient only this near the field's own land (yd) */
    private const val FIELD_KNOWS_YD = 50f
    /** the colour table's step (yd); 256 steps reach past the deep colour */
    private const val LUT_STEP_YD = 6f
    private val LUT = IntArray(256)
    init {
        for (i in LUT.indices) {
            val d = i * LUT_STEP_YD
            var k = STOPS_YD.size - 1
            for (j in 1 until STOPS_YD.size) if (d < STOPS_YD[j]) { k = j; break }
            val a = STOPS[k - 1]; val b = STOPS[k]
            // straight between the stops (a smoothstep in each made flat rings at every stop)
            val t = ((d - STOPS_YD[k - 1]) / (STOPS_YD[k] - STOPS_YD[k - 1])).coerceIn(0f, 1f)
            LUT[i] = Color.rgb((a[0] + (b[0] - a[0]) * t).toInt(), (a[1] + (b[1] - a[1]) * t).toInt(), (a[2] + (b[2] - a[2]) * t).toInt())
        }
    }
    /** the water's colour [d] yd from land (no shoreline) */
    fun colour(d: Float): Int = LUT[(d / LUT_STEP_YD).toInt().coerceIn(0, 255)]

    // ---------------------------------------------------------------- what is water (the minimap's own colours)
    /** dark-water weight (0..1), ONE reading at every scale (review 3: a strict reading for the full tiles and a lenient one for the
     *  coarse pictures disagreed on the coastal fringe, so a full tile's coast stopped on a straight line where a coarser picture's
     *  began): any dark pixel whose green or blue is not under its red - Kalimdor's open sea ((4, 6, 8), (0, 4, 0) in RGB_565), the
     *  seabed fading into it at every coast (the dark-green fringe (8, 25, 11), Westfall's (8, 24, 16)), a lossy copy's smear between
     *  two seas ((9, 17, 9), (14, 16, 14)) or its noise ((8, 4, 0)). Full from [DARK_FULL] down, none from [DARK_NONE] up: the
     *  fringe's outer green ((16, 41, 14), 71) half, the dark woods ((19, 45, 16), R+G+B 80) under a fifth: land; Elwynn's darkest (94) none. Mount Hyjal's
     *  crevices and Blackrock Mountain's black read as water here: the openings and the field's flood (black water must reach the
     *  open sea) take them out */
    fun darkW(r: Int, g: Int, b: Int): Float {
        val s = r + g + b
        val hue = if (s <= 24) 1f else ((maxOf(g, b) - r + 8) / 6f).coerceIn(0f, 1f)
        return ((DARK_NONE - s) / (DARK_NONE - DARK_FULL)).coerceIn(0f, 1f) * ((24 - r) / 8f).coerceIn(0f, 1f) * hue
    }
    private const val DARK_FULL = 44f
    private const val DARK_NONE = 84f
    /** the seabed's glow round an island (review 3: south of Westfall, ADTs (29, 54) and (30, 54) have a green sea - (8, 32, 8),
     *  (16, 40, 16) - next to a blue one, and it stayed as it was on a straight line where the blue was repainted): green over red,
     *  blue not under red, not bright. Land's greens have red over blue (the dark woods (19, 45, 16), Elwynn's (40, 60, 20)) */
    fun seabedW(r: Int, g: Int, b: Int): Float {
        val s = r + g + b
        return ((b - r + 4) / 8f).coerceIn(0f, 1f) * ((g - r - 6) / 8f).coerceIn(0f, 1f) * ((150 - s) / 40f).coerceIn(0f, 1f)
    }
    /** the dark readings (water that must reach the open sea: the field's flood, [Field.waterNear]) */
    fun darkSeaW(r: Int, g: Int, b: Int): Float = maxOf(darkW(r, g, b), seabedW(r, g, b))
    /** a pixel's water weight: the larger of the readings */
    fun waterW(r: Int, g: Int, b: Int): Float = maxOf(darkSeaW(r, g, b), tealW(r, g, b))
    /** blue / teal water weight (0..1): blue at least near green, red well under both (Stormwind's harbour (31, 65, 93), Elwynn's lakes
     *  (24, 48, 58)); not the dark-green woods ((19, 45, 16): blue far under green), grey rock or snow (red near the others) */
    fun tealW(r: Int, g: Int, b: Int): Float {
        val mx = maxOf(g, b).toFloat()
        // (blue from 0.62 of green: Westfall's dark sea (8, 20, 20) is (8, 20, 16) once decoded to RGB_565)
        val t1 = (b - 0.62f * g) / (0.1f * g + 6f) + 0.5f
        val t2 = (0.72f * mx - r) / (0.1f * mx + 4f) + 0.5f
        val t3 = (360 - (r + g + b)) / 40f
        // (and some colour at all: a near-black grey ((13, 12, 8) -> (8, 12, 8) in RGB_565) is rock, not water)
        val t4 = (mx - r - 3f) / 5f
        return minOf(minOf(t1, t2), minOf(t3, t4)).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- a continent's field
    class Field(val cont: Int, val c0: Int, val r0: Int, val w: Int, val h: Int, val d: ByteArray, val stamp: String) {
        /** the water where the continent has no ADT: [w] / 2 x [h] / 2 (8 px an ADT), its top-left at ADT ([c0], [r0]) */
        @Volatile var sea: Bitmap? = null
        val bytes: Long get() = d.size.toLong() + nearBytes + (sea?.byteCount?.toLong() ?: 0L)
        /** bit per cell: water within [NEAR_CELLS] cells (made with the field, 1 bit a cell) */
        private val near = LongArray((w * h + 63) / 64).also { bits ->
            val r = NEAR_CELLS
            val rowAny = BooleanArray(w * h)
            for (y in 0 until h) {
                var last = -100000                                // the last water cell's x on this row
                // (two sweeps: water within r to the left, then to the right)
                for (x in 0 until w) { if (d[y * w + x].toInt() != 0) last = x; if (x - last <= r) rowAny[y * w + x] = true }
                last = 100000
                for (x in w - 1 downTo 0) { if (d[y * w + x].toInt() != 0) last = x; if (last - x <= r) rowAny[y * w + x] = true }
            }
            for (x in 0 until w) {
                var last = -100000
                for (y in 0 until h) { if (rowAny[y * w + x]) last = y; if (y - last <= r) { val i = y * w + x; bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63)) } }
                last = 100000
                for (y in h - 1 downTo 0) { if (rowAny[y * w + x]) last = y; if (last - y <= r) { val i = y * w + x; bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63)) } }
            }
        }
        private fun nearAt(x: Int, y: Int): Float {
            if (x < 0 || y < 0 || x >= w || y >= h) return 1f
            val i = y * w + x
            return if ((near[i ushr 6] ushr (i and 63)) and 1L != 0L) 1f else 0f
        }
        /** how much the field has water within [NEAR_CELLS] cells (100 yd) of ADT point ([x], [y]), 0..1, bilinear (review 3: a yes /
         *  no per 33 yd cell cut the coastal fringe on straight lines): the black there is the open sea's, not Blackrock's */
        fun waterNear(x: Double, y: Double): Float {
            val gx = (x - c0) * FIELD_PX - 0.5; val gy = (y - r0) * FIELD_PX - 0.5
            val ix = kotlin.math.floor(gx).toInt(); val iy = kotlin.math.floor(gy).toInt()
            val fx = (gx - ix).toFloat(); val fy = (gy - iy).toFloat()
            val a = nearAt(ix, iy) * (1 - fx) + nearAt(ix + 1, iy) * fx
            val b = nearAt(ix, iy + 1) * (1 - fx) + nearAt(ix + 1, iy + 1) * fx
            return a * (1 - fy) + b * fy
        }
        val nearBytes: Long get() = near.size * 8L
        /** the field's cell at ADT point ([x], [y]) is the open sea (outside the field: the open sea) */
        fun ocean(x: Double, y: Double): Boolean {
            val gx = kotlin.math.floor((x - c0) * FIELD_PX).toInt(); val gy = kotlin.math.floor((y - r0) * FIELD_PX).toInt()
            if (gx < 0 || gy < 0 || gx >= w || gy >= h) return true
            return d[gy * w + gx].toInt() != 0
        }
        /** the distance to land (yd) at ADT units ([x] = col, [y] = row, fractional); far outside the field */
        fun yd(x: Double, y: Double): Float {
            val gx = (x - c0) * FIELD_PX - 0.5; val gy = (y - r0) * FIELD_PX - 0.5
            if (gx < 0.0 || gy < 0.0 || gx > w - 1.0 || gy > h - 1.0) return FAR
            val ix = gx.toInt(); val iy = gy.toInt()
            val fx = (gx - ix).toFloat(); val fy = (gy - iy).toFloat()
            val i = iy * w + ix
            val ix1 = if (ix + 1 < w) 1 else 0; val iy1 = if (iy + 1 < h) w else 0
            val a = (d[i].toInt() and 0xFF) * (1 - fx) + (d[i + ix1].toInt() and 0xFF) * fx
            val b = (d[i + iy1].toInt() and 0xFF) * (1 - fx) + (d[i + iy1 + ix1].toInt() and 0xFF) * fx
            // from a land cell's middle to its edge: half a cell less
            return maxOf(0f, (a * (1 - fy) + b * fy - 1.5f) * UNIT_YD)
        }
    }
    const val FAR = 1.0e6f
    /** black water counts within this many field cells (100 yd) of the field's water */
    const val NEAR_CELLS = 3
    /** the ocean's channels: water more than this many cells (33 yd each) from land on every side carries the open sea ([oceanMask]) */
    const val OCEAN_ERODE = 2
    private const val SEA = 0xFF.toByte()
    private const val BLACK_WATER = 0xFE.toByte()
    /** the field's disk copy's version (a new classification = made again) */
    private const val VERSION = 15

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "waterdepth").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val fields = object : LinkedHashMap<Int, Field>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Field>?) = size > FIELDS_MAX
    }
    private val building = HashSet<Int>()
    /** continents with no field to build (no terrain, a read error): their pictures are final with their own distance */
    private val none = HashSet<Int>()
    private val clients = HashMap<Int, WeakReference<View>>()
    private val listeners = ArrayList<(Int) -> Unit>()
    init {
        MemWatch.source("waterdepth") { bytes() }
    }
    /** the fields, their sea pictures and the repaint's scratch */
    @Synchronized fun bytes(): Long = fields.values.sumOf { it.bytes } + scratchBytes()

    /** told (on the field's thread) when continent [cont]'s field is ready */
    @Synchronized fun listen(l: (Int) -> Unit) { listeners.add(l) }

    /** continent [cont]'s field, or null while it is built (once, off the UI thread; [client] redrawn when it is) or when there is none */
    @Synchronized fun field(cont: Int, client: View? = null): Field? {
        fields[cont]?.let { f -> val st = TerrainOverview.stampNow(); if (st == null || f.stamp == st) return f; fields.remove(cont) }
        if (cont in none) return null
        if (client != null) clients[cont] = WeakReference(client)
        if (building.add(cont)) exec.execute { finish(cont, runCatching { load(cont) ?: build(cont) }.getOrNull()) }
        return null
    }
    /** pictures made now are final (the field is there, or there will be none) */
    @Synchronized fun ready(cont: Int): Boolean = field(cont) != null || cont in none

    private fun finish(cont: Int, f: Field?) {
        val ls: List<(Int) -> Unit>
        val cl: View?
        synchronized(this) {
            building.remove(cont)
            if (f != null) fields[cont] = f else none.add(cont)
            ls = ArrayList(listeners)
            cl = clients.remove(cont)?.get()
        }
        for (l in ls) runCatching { l(cont) }
        cl?.postInvalidate()
    }

    /** the minimap step made more (MinimapTiles.refresh): a continent with none is looked at again (a field of an older build goes by
     *  its stamp, [field]) */
    @Synchronized fun refresh() { none.clear() }

    /** the tests: build / read continent [cont]'s field now on this thread */
    fun fieldNow(cont: Int): Field? {
        synchronized(this) { fields[cont]?.let { return it } }
        val f = runCatching { load(cont) ?: build(cont) }.getOrNull()
        synchronized(this) { if (f != null) fields[cont] = f else none.add(cont) }
        return f
    }

    private fun file(cont: Int, stamp: String): File? = TerrainOverview.dir?.let { File(it, "terrain-ov/$stamp/depth/$cont-v$VERSION.bin") }

    private fun bbox(cont: Int): IntArray? {
        val s = TerrainOverview.index() ?: run { TerrainOverview.load(); TerrainOverview.index() } ?: return null
        val t = s[cont] ?: return null
        if (t.isEmpty()) return null
        var c0 = 64; var c1 = -1; var r0 = 64; var r1 = -1
        for (k in t) { val c = k shr 8; val r = k and 0xFF; c0 = minOf(c0, c); c1 = maxOf(c1, c); r0 = minOf(r0, r); r1 = maxOf(r1, r) }
        return intArrayOf(c0 - MARGIN, r0 - MARGIN, (c1 - c0 + 1 + 2 * MARGIN) * FIELD_PX, (r1 - r0 + 1 + 2 * MARGIN) * FIELD_PX)
    }

    private fun load(cont: Int): Field? {
        val bb = bbox(cont) ?: return null
        val stamp = TerrainOverview.stampNow() ?: return null
        val f = file(cont, stamp) ?: return null
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                val c0 = inp.readInt(); val r0 = inp.readInt(); val w = inp.readInt(); val h = inp.readInt()
                if (c0 != bb[0] || r0 != bb[1] || w != bb[2] || h != bb[3]) return@use null
                val d = ByteArray(w * h); inp.readFully(d)
                // (its sea picture - the land in it is the art's - kept beside it; none: made again with the field)
                val sea = seaFile(cont, stamp)?.takeIf { it.isFile }?.let { sf -> android.graphics.BitmapFactory.decodeFile(sf.path,
                    android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) } ?: return@use null
                if (sea.width != w / 2 || sea.height != h / 2) { sea.recycle(); return@use null }
                Field(cont, c0, r0, w, h, d, stamp).also { it.sea = sea }
            }
        }.getOrNull()
    }

    /** the field from TerrainOverview's level-4 pictures (made if not on the disk yet; their own cache) */
    private fun build(cont: Int): Field? {
        val bb = bbox(cont) ?: return null
        val stamp = TerrainOverview.stampNow() ?: return null
        val c0 = bb[0]; val r0 = bb[1]; val w = bb[2]; val h = bb[3]
        val d = ByteArray(w * h)
        java.util.Arrays.fill(d, 0xFF.toByte())
        val core = ByteArray(w * h)
        // (the art's own colour per cell: the sea picture's land - the continent itself, never the shallows' teal in its shape)
        val art = IntArray(w * h)
        val n = TerrainOverview.span(FIELD_LEVEL)
        val tile = TerrainOverview.TILE
        val row = IntArray(tile)
        val wt = FloatArray(tile * tile)
        val pix = IntArray(tile * tile)
        var any = false
        for (oc in 0 until 64 / n) for (or in 0 until 64 / n) {
            if (!TerrainOverview.has(cont, FIELD_LEVEL, oc, or)) continue
            val b = TerrainOverview.makeRaw(cont, FIELD_LEVEL, oc, or) ?: continue
            any = true
            for (py in 0 until tile) {
                b.getPixels(row, 0, tile, 0, py, tile, 1)
                for (px in 0 until tile) {
                    val p = row[px]
                    pix[py * tile + px] = p
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                    val tw = tealW(r, g, bl)
                    // (black-led water negative: it must reach the open sea, below)
                    wt[py * tile + px] = if (tw >= 0.5f) tw else -darkSeaW(r, g, bl)
                }
            }
            b.recycle()
            for (py in 0 until tile) {
                val gy = or * tile + py - r0 * FIELD_PX
                if (gy !in 0 until h) continue
                for (px in 0 until tile) {
                    val gx = oc * tile + px - c0 * FIELD_PX
                    if (gx !in 0 until w) continue
                    // land unless the 3 x 3 round it is water (at most one pixel not: a lossy copy's noise in a sea): a coast's pixel
                    // counts as land, so the field is never FARTHER from land than a picture's own distance (it seeds them: an
                    // overestimate would be a seam); an ADT the continent lacks is sea. (Review 3: the mean of the weights was stricter
                    // than the pictures' reading - Westfall's greenish dark sea, weight 0.6, was land here and so not water there)
                    var k = 0; var wet = 0; var black = 0
                    for (yy in maxOf(0, py - 1)..minOf(tile - 1, py + 1)) for (xx in maxOf(0, px - 1)..minOf(tile - 1, px + 1)) {
                        val v = wt[yy * tile + xx]; k++; if (kotlin.math.abs(v) >= 0.5f) wet++; if (v <= -0.5f) black++ }
                    val col = (oc * tile + px) / FIELD_PX; val rw = (or * tile + py) / FIELD_PX
                    if (TerrainOverview.hasAdt(cont, col, rw)) d[gy * w + gx] = if (wet < k - 1) 0 else if (black * 2 > k) BLACK_WATER else 0xFF.toByte()
                    // (the pixel's own reading, kept apart for the thin-land opening below: 1 = land)
                    if (TerrainOverview.hasAdt(cont, col, rw) && kotlin.math.abs(wt[py * tile + px]) < 0.5f) core[gy * w + gx] = 1
                    if (TerrainOverview.hasAdt(cont, col, rw)) art[gy * w + gx] = pix[py * tile + px]
                }
            }
        }
        if (!any) return null
        // land thinner than two cells (66 yd) is not the field's: the flat ground strip along the world's edge in three Kalimdor ADTs
        // ((75, 72, 46), 28 yd wide: a straight line in the open sea with its own shallows - review 3), a lossy smear between two seas.
        // The pixels' own land opened (eroded and grown back by a cell); the field's land-biased land kept only within a cell of what
        // stays. Small rocks lose their halo of shallows (the pictures keep their shoreline): no halo to stop at a tile's border
        openBytes(core, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i].toInt() != 0) continue
            var near = false
            for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) { for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) if (core[yy * w + xx].toInt() == 2) { near = true; break }; if (near) break }
            if (!near) d[i] = BLACK_WATER          // (water if it touches the sea: the flood below)
        }
        // OCEAN ONLY (the user, 2026-11-17: "The water in stormwind looks AWFUL I don't know why we painted any of the actual maps and not
        // just only the surrounding ocean"): the field's water is the OPEN SEA only - water joined to the sea where the continent has no
        // ADT through water at least [OCEAN_ERODE] cells from land on every side (a channel ~165 yd wide or more). Stormwind's canals, its
        // lakes, Loch Modan, rivers, Blackrock's black: not joined so (or only through a gap narrower than that) - land to the field, so
        // the repaint leaves them as the game draws them. The joined core grown back over the water it came from, by [OCEAN_ERODE] + 1
        // cells: the ocean reaches its coast again, but not up a narrow inlet past that
        val ocean = oceanMask(d, w, h) { x, y -> !TerrainOverview.hasAdt(cont, c0 + x / FIELD_PX, r0 + y / FIELD_PX) }
        for (i in 0 until w * h) d[i] = if (ocean[i]) SEA else 0
        chamferBytes(d, w, h)
        file(cont, stamp)?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp" + Thread.currentThread().id)
                DataOutputStream(tmp.outputStream().buffered()).use { o -> o.writeInt(c0); o.writeInt(r0); o.writeInt(w); o.writeInt(h); o.write(d) }
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
        return Field(cont, c0, r0, w, h, d, stamp).also { f -> f.sea = sea(f, art); f.sea?.let { saveSea(cont, stamp, it) } }
    }

    /**
     * The ocean of a field's water ([d]: 0 = land, anything else water): eroded by [OCEAN_ERODE] cells (a 3-4 chamfer), the core flooded
     * (4-connected) from the cells where [open] (no ADT: the open sea), then grown back over the water by [OCEAN_ERODE] + 1 cells
     * (8-connected steps, through water only). Pure (the tests).
     */
    fun oceanMask(d: ByteArray, w: Int, h: Int, open: (Int, Int) -> Boolean): BooleanArray {
        val n = w * h
        val e = ByteArray(n) { if (d[it].toInt() != 0) 0xFF.toByte() else 0 }
        chamferBytes(e, w, h)
        val core = BooleanArray(n) { (e[it].toInt() and 0xFF) > 3 * OCEAN_ERODE }
        val ocean = BooleanArray(n)
        val q = IntArray(n)
        var head = 0; var tail = 0
        for (i in 0 until n) if (core[i] && open(i % w, i / w)) { ocean[i] = true; q[tail++] = i }
        while (head < tail) {
            val i = q[head++]; val x = i % w; val y = i / w
            if (x > 0 && core[i - 1] && !ocean[i - 1]) { ocean[i - 1] = true; q[tail++] = i - 1 }
            if (x < w - 1 && core[i + 1] && !ocean[i + 1]) { ocean[i + 1] = true; q[tail++] = i + 1 }
            if (y > 0 && core[i - w] && !ocean[i - w]) { ocean[i - w] = true; q[tail++] = i - w }
            if (y < h - 1 && core[i + w] && !ocean[i + w]) { ocean[i + w] = true; q[tail++] = i + w }
        }
        // grown back: rings of 8-connected steps over the water (the queue reused: the last ring, then the next one after it)
        var r0 = 0; var r1 = tail
        for (step in 0..OCEAN_ERODE) {
            tail = 0
            val ring = q.copyOfRange(r0, r1)
            for (i in ring) {
                val x = i % w; val y = i / w
                for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (!ocean[j] && d[j].toInt() != 0) { ocean[j] = true; q[tail++] = j }
                }
            }
            r0 = 0; r1 = tail
            if (tail == 0) break
        }
        return ocean
    }

    private fun seaFile(cont: Int, stamp: String): File? = file(cont, stamp)?.let { File(it.path.removeSuffix(".bin") + "-sea.png") }
    private fun saveSea(cont: Int, stamp: String, b: Bitmap) {
        val f = seaFile(cont, stamp) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp" + Thread.currentThread().id)
            tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /** [a]'s 1s opened by a 3 x 3 square: those kept are 2 (an erosion, then grown back by a cell) */
    private fun openBytes(a: ByteArray, w: Int, h: Int) {
        val e = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (a[y * w + x].toInt() == 0) continue
            var all = true
            for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) { for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) if (a[yy * w + xx].toInt() == 0) { all = false; break }; if (!all) break }
            if (all) e[y * w + x] = 1
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (a[y * w + x].toInt() == 0) continue
            var any = false
            for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) { for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) if (e[yy * w + xx].toInt() != 0) { any = true; break }; if (any) break }
            if (any) a[y * w + x] = 2
        }
    }

    /** the 3-4 chamfer on bytes (saturating at 255: past ~2.8 km the sea is all deep) */
    internal fun chamferBytes(d: ByteArray, w: Int, h: Int) {
        fun v(i: Int) = d[i].toInt() and 0xFF
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var m = v(i); if (m == 0) continue
            if (x > 0) m = minOf(m, v(i - 1) + 3)
            if (y > 0) {
                m = minOf(m, v(i - w) + 3)
                if (x > 0) m = minOf(m, v(i - w - 1) + 4)
                if (x < w - 1) m = minOf(m, v(i - w + 1) + 4)
            }
            d[i] = minOf(m, 255).toByte()
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var m = v(i); if (m == 0) continue
            if (x < w - 1) m = minOf(m, v(i + 1) + 3)
            if (y < h - 1) {
                m = minOf(m, v(i + w) + 3)
                if (x < w - 1) m = minOf(m, v(i + w + 1) + 4)
                if (x > 0) m = minOf(m, v(i + w - 1) + 4)
            }
            d[i] = minOf(m, 255).toByte()
        }
    }

    /** the field's picture of its continent at 8 px an ADT: the ocean by its depth, every other cell (land, and the water that is not the
     *  ocean: lakes, canals) the art's own colour. Under the tiles: the sea past the last ADT, and the continent itself wherever a terrain
     *  picture is not there yet (the zoom-out glitch, 2026-11-17: "all I see is teal in the shape of the land" - its land cells were the
     *  shallows' colour, shown alone while the coarser pictures were still being made) */
    private fun sea(f: Field, art: IntArray): Bitmap? = runCatching {
        val sw = f.w / 2; val sh = f.h / 2
        val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565)
        val line = IntArray(sw)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var r = 0; var g = 0; var bl = 0; var land = 0
                for (yy in 0..1) for (xx in 0..1) {
                    val cx = 2 * x + xx; val cy = 2 * y + yy
                    val i = cy * f.w + cx
                    val c = if (f.d[i].toInt() == 0) { land++; art[i] } else colour(f.yd(f.c0 + (cx + 0.5) / FIELD_PX, f.r0 + (cy + 0.5) / FIELD_PX))
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF
                }
                val c = Color.rgb(r / 4, g / 4, bl / 4)
                line[x] = if (land == 0 && c == DEEP) c else dither(c, x, y)
            }
            b.setPixels(line, 0, sw, 0, y, sw, 1)
        }
        b
    }.getOrNull()

    // ---------------------------------------------------------------- the repaint
    private const val GRID_MAX = 128
    private var band: IntArray? = null
    private var gRows: IntArray? = null
    private var gBlack: FloatArray? = null
    private var gTeal: FloatArray? = null
    private var gMask: FloatArray? = null
    private var gLine: FloatArray? = null
    private var gRamp: FloatArray? = null
    private var gShare: FloatArray? = null
    private var gBlur: FloatArray? = null
    /** per cell: [KIND_NONE] / [KIND_BRIGHT] / [KIND_DARK] water, [KIND_ROOF] (bright teal that is not water: [BRIGHT_S]); the flood's queue */
    private var gKind: ByteArray? = null
    private var gQueue: IntArray? = null
    private var gSeen: BooleanArray? = null
    private const val KIND_NONE: Byte = 0
    private const val KIND_BRIGHT: Byte = 1
    private const val KIND_DARK: Byte = 2
    private const val KIND_SEEN = 4
    private const val KIND_ROOF: Byte = 8
    private fun scratchBytes(): Long = listOf(gBlack, gTeal, gMask, gLine, gRamp, gShare, gBlur).sumOf { (it?.size ?: 0) * 4L } + (band?.size ?: 0) * 4L +
        (gRows?.size ?: 0) * 4L + (gKind?.size ?: 0) + (gQueue?.size ?: 0) * 4L + (gSeen?.size ?: 0)
    /** a cell's kind from its mean colour and readings ([BRIGHT_S]) */
    private fun kind(r: Int, g: Int, b: Int, tw: Float, mw: Float): Byte =
        if (mw < 0.5f) KIND_NONE else if (tw >= 0.5f && r + g + b >= BRIGHT_S && darkSeaW(r, g, b) < 0.5f) KIND_BRIGHT else KIND_DARK

    /** the tests: what the last repaint did ([0] water cells, [1] the field was used) */
    @Volatile var lastWater = 0
    /** the tests: repaints so far (a frame must make none) */
    @Volatile var repaints = 0
    @Volatile var lastFinal = false

    /** a repaint's neighbour picture: [bmp] at the picture's own scale, its top-left at the neighbour's px ([ox], [oy]) (a part along
     *  the shared edge is enough); or [sea] (the continent has no ADT there); or neither (not to be had: the picture's
     *  own edge cells stand in) */
    class Nb(val bmp: Bitmap?, val sea: Boolean, val ox: Int = 0, val oy: Int = 0)
    /** the cells a repaint reads past each edge of its picture (its neighbours'): every smoothing, opening, gate and shoreline reach
     *  sees across the border as the neighbour does - the same pixels, the same cells - so both sides decide alike (review 3: the
     *  fringe's soft edge met a tile's border on a straight cut, one side water to its edge, the other land) */
    const val MARGIN_CELLS = 12

    /**
     * Repaint the water of picture [b] (mutable RGB_565, square) of continent [cont], which covers ADTs [x0] .. [x0] + [span] (columns)
     * and [y0] .. [y0] + [span] (rows). [nb]: its neighbour [dx], [dy] (-1..1), given the px strip wanted along the shared edge ([Nb]).
     * True = final (the field was used, or the continent has none); false = provisional. Any thread (serialised); never the UI
     * thread's frame.
     */
    fun recolour(b: Bitmap, cont: Int, x0: Double, y0: Double, span: Double, nb: ((Int, Int, Int) -> Nb)? = null): Boolean =
        synchronized(paintLock) { repaints++; recolourLocked(b, cont, x0, y0, span, nb) }
    /** the repaint's own lock (the UI thread's [ready] / [field] never wait on a repaint) */
    private val paintLock = Any()

    /** MinimapTiles' tile ([col], [row]) of continent [cont], decoded at any sample: repainted with its eight neighbours read past its
     *  edges, each decoded at the same sample (the same px, so the same cells as it reads itself) one at a time and freed at once
     *  (a BitmapRegionDecoder strip would be less, but a region read wrong is a seam: the whole picture, ~0.5 MB at full size, briefly) */
    fun recolourTile(b: Bitmap, cont: Int, col: Int, row: Int): Boolean {
        val sample = maxOf(1, 512 / maxOf(1, b.width))                      // (the minimap tiles are 512 px)
        return recolour(b, cont, col.toDouble(), row.toDouble(), 1.0) { dx, dy, _ ->
            if (!TerrainOverview.hasAdt(cont, col + dx, row + dy)) Nb(null, true)
            else {
                val f = ArtHarvest.file("minimap/$cont/${col + dx}_${row + dy}.webp")
                Nb(runCatching { if (f.canRead()) android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = sample }) else null }.getOrNull(), false)
            }
        }
    }

    private fun recolourLocked(b: Bitmap, cont: Int, x0: Double, y0: Double, span: Double, nb: ((Int, Int, Int) -> Nb)?): Boolean {
        val field = field(cont)
        val final = field != null || cont in none
        lastFinal = final; lastWater = 0
        if (!b.isMutable) return final
        // OCEAN ONLY: which water is the open sea only the field knows - none (yet): the art as it is (a provisional picture is made
        // again when the field lands; a continent with no field keeps the game's water)
        if (field == null) return final
        val W = b.width; val H = b.height
        val gw = minOf(W, GRID_MAX); val cp = W / gw; val gh = H / cp
        if (gw <= 0 || gh <= 0) return final
        // the cells: this picture's gw x gh and MG more each side (its neighbours'); GW x GH in all, (MG, MG) this picture's first
        val MG = MARGIN_CELLS
        val GW = gw + 2 * MG; val GH = gh + 2 * MG
        val n = GW * GH
        val bandA = band?.takeIf { it.size >= W * cp } ?: IntArray(W * cp).also { band = it }
        val gMin = (GRID_MAX + 2 * MARGIN_CELLS) * (GRID_MAX + 2 * MARGIN_CELLS)
        fun grid(a: FloatArray?): FloatArray = if (a != null && a.size >= n) a else FloatArray(maxOf(n, gMin))
        val blk = grid(gBlack).also { gBlack = it }; val tea = grid(gTeal).also { gTeal = it }
        val m = grid(gMask).also { gMask = it }; val dl = grid(gLine).also { gLine = it }; val dr = grid(gRamp).also { gRamp = it }
        val sh = grid(gShare).also { gShare = it }; val bs = grid(gBlur).also { gBlur = it }
        java.util.Arrays.fill(blk, 0, n, 0f); java.util.Arrays.fill(tea, 0, n, 0f); java.util.Arrays.fill(m, 0, n, 0f)
        val kd = gKind?.takeIf { it.size >= n } ?: ByteArray(maxOf(n, gMin)).also { gKind = it }
        java.util.Arrays.fill(kd, 0, n, KIND_NONE)
        // pass A: each cell's mean colour, read: its teal share, and its water share (the larger reading: Westfall's sea mixes both
        // once in RGB_565, (8, 16, 16) teal and (0, 16, 8) black - their mean is water). By the mean, as the neighbours' cells are read
        // (decoded small: the same mean), so both sides of a border read alike
        java.util.Arrays.fill(sh, 0, n, 0f); java.util.Arrays.fill(bs, 0, n, 0f); java.util.Arrays.fill(dl, 0, n, 0f)
        val inv = 1f / (cp * cp)
        for (cy in 0 until gh) {
            b.getPixels(bandA, 0, W, 0, cy * cp, W, cp)
            for (yy in 0 until cp) for (x in 0 until W) {
                val p = bandA[yy * W + x]
                val i = (cy + MG) * GW + x / cp + MG
                sh[i] += ((p shr 16) and 0xFF) * inv; bs[i] += ((p shr 8) and 0xFF) * inv; dl[i] += (p and 0xFF) * inv
            }
        }
        for (cy in 0 until gh) for (cx in 0 until gw) {
            val i = (cy + MG) * GW + cx + MG
            val r = (sh[i] + 0.5f).toInt(); val g = (bs[i] + 0.5f).toInt(); val bl = (dl[i] + 0.5f).toInt()
            tea[i] = tealW(r, g, bl); m[i] = maxOf(darkSeaW(r, g, bl), tea[i]); kd[i] = kind(r, g, bl, tea[i], m[i])
        }
        // the margin: the neighbours' cells, read as this picture's are (the mean of their cp x cp px); an ADT the continent lacks is open
        // sea; none to be had: the edge repeated
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nbr = nb?.invoke(dx, dy, MG * cp)
            val nbm = nbr?.bmp
            val xs = if (dx < 0) 0 until MG else if (dx > 0) MG + gw until GW else MG until MG + gw
            val ys = if (dy < 0) 0 until MG else if (dy > 0) MG + gh until GH else MG until MG + gh
            for (Y in ys) for (X in xs) {
                val cx = X - MG; val cy = Y - MG
                val i = Y * GW + X
                // (the neighbour's px of this cell, in its part)
                val px0 = (cx - dx * gw) * cp - (nbr?.ox ?: 0); val py0 = (cy - dy * gh) * cp - (nbr?.oy ?: 0)
                if (nbm != null && px0 >= 0 && py0 >= 0 && px0 + cp <= nbm.width && py0 + cp <= nbm.height) {
                    var sr = 0; var sg = 0; var sb = 0
                    for (yy in 0 until cp) for (xx in 0 until cp) { val p = nbm.getPixel(px0 + xx, py0 + yy); sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
                    val r = (sr * inv + 0.5f).toInt(); val g = (sg * inv + 0.5f).toInt(); val bl = (sb * inv + 0.5f).toInt()
                    tea[i] = tealW(r, g, bl); m[i] = maxOf(darkSeaW(r, g, bl), tea[i]); kd[i] = kind(r, g, bl, tea[i], m[i])
                } else if (nbr?.sea == true) { tea[i] = 0f; m[i] = 1f; kd[i] = KIND_DARK }
                else { val j = (cy.coerceIn(0, gh - 1) + MG) * GW + cx.coerceIn(0, gw - 1) + MG; tea[i] = tea[j]; m[i] = m[j]; kd[i] = kd[j] }
            }
            nbm?.recycle()
        }
        // blue roofs ([BRIGHT_S]): each piece of water (8-connected, the margin's cells too, so both sides of a border decide alike) that
        // is mostly bright teal is not water - its cells land, and pass B leaves their pixels as the art has them
        run {
            val cYd = (span * ADT_YD / gw).toFloat()
            val maxCells = (ROOF_MAX_YD2 / (cYd * cYd)).toInt()
            val q = gQueue?.takeIf { it.size >= n } ?: IntArray(maxOf(n, gMin)).also { gQueue = it }
            for (s0 in 0 until n) {
                if (kd[s0] != KIND_BRIGHT && kd[s0] != KIND_DARK) continue
                var head = 0; var tail = 0; var dark = 0; var whole = true
                q[tail++] = s0; kd[s0] = (kd[s0] + KIND_SEEN).toByte()
                while (head < tail) {
                    val i = q[head++]
                    if (kd[i].toInt() == KIND_DARK + KIND_SEEN) dark++
                    val x = i % GW; val y = i / GW
                    if (x == 0 || y == 0 || x == GW - 1 || y == GH - 1) whole = false
                    for (yy in maxOf(0, y - 1)..minOf(GH - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(GW - 1, x + 1)) {
                        val j = yy * GW + xx
                        if (kd[j] == KIND_BRIGHT || kd[j] == KIND_DARK) { kd[j] = (kd[j] + KIND_SEEN).toByte(); q[tail++] = j }
                    }
                }
                if (whole && tail <= maxCells && dark < ROOF_SHARE * tail) for (k in 0 until tail) { val i = q[k]; m[i] = 0f; tea[i] = 0f; kd[i] = KIND_ROOF }
            }
        }
        val cellYd = (span * ADT_YD / gw).toFloat()
        val cellA = span / gw
        // a cell's ADT position (its middle)
        fun ax(i: Int) = x0 + ((i % GW) - MG + 0.5) * cellA
        fun ay(i: Int) = y0 + ((i / GW) - MG + 0.5) * cellA
        // OCEAN ONLY (the user, 2026-11-17: "The water in stormwind looks AWFUL I don't know why we painted any of the actual maps and not
        // just only the surrounding ocean"): water - teal or dark - only where the field has OCEAN near (its water is the open sea alone:
        // Stormwind's canals, lakes, rivers, Blackrock's black are land to it), and only a piece of water (8-connected, the margin's cells
        // too) that holds a cell of the field's ocean: a lake or a canal a few yards from the sea, not joined to it, keeps the game's art
        for (i in 0 until n) if (m[i] > 0f) m[i] *= field.waterNear(ax(i), ay(i))
        run {
            val q = gQueue?.takeIf { it.size >= n } ?: IntArray(maxOf(n, gMin)).also { gQueue = it }
            val seen = gSeen?.takeIf { it.size >= n } ?: BooleanArray(maxOf(n, gMin)).also { gSeen = it }
            java.util.Arrays.fill(seen, 0, n, false)
            for (s0 in 0 until n) {
                if (m[s0] <= 0f || seen[s0]) continue
                var head = 0; var tail = 0; var sea = false
                q[tail++] = s0; seen[s0] = true
                while (head < tail) {
                    val i = q[head++]
                    if (!sea && field.ocean(ax(i), ay(i))) sea = true
                    val x = i % GW; val y = i / GW
                    for (yy in maxOf(0, y - 1)..minOf(GH - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(GW - 1, x + 1)) {
                        val j = yy * GW + xx
                        if (m[j] > 0f && !seen[j]) { seen[j] = true; q[tail++] = j }
                    }
                }
                if (!sea) for (k in 0 until tail) m[q[k]] = 0f
            }
        }
        for (i in 0 until n) sh[i] = m[i]
        // the dark water's share smoothed over FRINGE_YD (review 3: Kalimdor's coastal fringe - seabed fading from green to black in
        // clumps - read cell by cell left a blocky staircase of dark land and flat water; smoothed, the fringe's edge follows the coast
        // and blends). Teal water keeps its own cells (a river a few cells wide stays). Coarse pictures: a cell is wider than that, none
        boxBlur(sh, bs, dl, GW, GH, (FRINGE_YD / cellYd).toInt())
        for (i in 0 until n) {
            val v = if (tea[i] >= 0.5f) sh[i] else bs[i]
            dl[i] = if (v >= 0.5f) 1f else 0f; dr[i] = if (v >= 0.5f && tea[i] >= 0.5f) 1f else 0f
            if (tea[i] >= 0.5f) bs[i] = sh[i]                     // (bs: the share pass B blends by)
        }
        // water narrower than BLACK_OPEN_YD is not water unless it is teal (an opening: erode from the land, then grow back): the dark
        // crevices between boulders; teal narrower than TEAL_OPEN_YD neither (the blue-grey specks in dark rock). A black speck inside
        // a teal sea stays (Westfall's mix). (At least a cell: a lone water pixel in a small picture is a speck in the coastal fringe)
        open(dl, GW, GH, maxOf(1.0f, BLACK_OPEN_YD / 2f / cellYd), blk, tea)
        open(dr, GW, GH, maxOf(1.0f, TEAL_OPEN_YD / 2f / cellYd), blk, tea)
        for (i in 0 until n) m[i] = if (dl[i] > 0f || dr[i] > 0f) 1f else 0f
        // land narrower than SPECK_YD in the water is water (a speck, a lossy copy's smear): repainted whole, no ring of shoreline round
        // it. tea from here: -1 = such a cell (forced), else 0
        for (i in 0 until n) tea[i] = 1f - m[i]
        open(tea, GW, GH, maxOf(1.0f, SPECK_YD / 2f / cellYd), dl, dr)
        for (i in 0 until n) if (m[i] <= 0f && tea[i] <= 0f) { m[i] = 1f; tea[i] = -1f } else tea[i] = 0f
        // the world's edge (review 3): three Kalimdor ADTs end in a strip of flat ground ((75, 72, 46), 28 yd) along their side that faces
        // no ADT - a straight line in the open sea. Land within EDGE_BAND_YD of such a side that the field has as sea is water, repainted
        // whole (a real coast there is the field's land: kept)
        if (field != null) {
            val band = EDGE_BAND_YD / ADT_YD
            for (i in 0 until n) {
                if (m[i] > 0f) continue
                val px = ax(i); val py = ay(i)
                val col = kotlin.math.floor(px).toInt(); val row = kotlin.math.floor(py).toInt()
                val fx = px - col; val fy = py - row
                val nearSide = (fx < band && !TerrainOverview.hasAdt(cont, col - 1, row)) || (fx > 1 - band && !TerrainOverview.hasAdt(cont, col + 1, row)) ||
                    (fy < band && !TerrainOverview.hasAdt(cont, col, row - 1)) || (fy > 1 - band && !TerrainOverview.hasAdt(cont, col, row + 1))
                if (nearSide && field.yd(px, py) > EDGE_SEA_YD) { m[i] = 1f; tea[i] = -1f }
            }
        }
        // the gate's reach: 1 on the water cells, fading to 0 over GATE_YD into the land, so a coast's pixels next to the water are read
        // one by one (Kalimdor's black-green fringe edge) and the repaint ends softly, not on a cell's edge (review 3); deeper in the
        // land nothing is
        val gateCells = maxOf(1, kotlin.math.ceil(GATE_YD / cellYd).toInt())
        // nothing to repaint: no water cell in this picture or within the gate's reach of it
        var water = 0; var nearby = false
        for (Y in MG - gateCells - 1 until MG + gh + gateCells + 1) for (X in MG - gateCells - 1 until MG + gw + gateCells + 1) {
            if (m[Y * GW + X] <= 0f) continue
            if (X in MG until MG + gw && Y in MG until MG + gh) water++ else nearby = true
        }
        lastWater = water
        if (water == 0 && !nearby) return final
        // the gradient's land: without what the field's 33 yd cells can not see (a rock offshore, a wreck): it keeps its shoreline but
        // makes no halo of shallows, which would stop where the field's land does (blk: the opened land)
        for (i in 0 until n) blk[i] = 1f - m[i]
        open(blk, GW, GH, LAND_OPEN_YD / 2f / cellYd, dl, dr)
        // the distances (yd): the shoreline's from the cells' own land; the gradient's seeded with the field's
        for (i in 0 until n) dl[i] = if (m[i] > 0f) FAR else 0f
        chamfer(dl, GW, GH, cellYd)
        for (i in 0 until n) {
            val fy = field?.yd(ax(i), ay(i))
            // (and only land the field knows, within FIELD_KNOWS_YD of its own: the field seeds the pictures; what it does not know is
            // land with a shoreline and no halo)
            dr[i] = if (blk[i] > 0f && (fy == null || fy <= FIELD_KNOWS_YD)) 0f else fy ?: FAR
        }
        chamfer(dr, GW, GH, cellYd)
        for (i in 0 until n) blk[i] = if (m[i] > 0f) 0f else FAR
        chamfer(blk, GW, GH, 1f)
        for (i in 0 until n) blk[i] = (1f - blk[i] / (gateCells + 1)).coerceIn(0f, 1f)
        // pass B, row by row: each pixel's water share - a fifth its own reading, the rest its cells' smoothed share (the coastal fringe
        // blends from the seabed into the shallows, review 3) - to its depth colour
        val rows = gRows?.takeIf { it.size >= W } ?: IntArray(W).also { gRows = it }
        for (py in 0 until H) {
            val cy = py / cp + MG
            var active = false
            for (Y in cy - 1..cy + 1) { for (X in MG - 1..MG + gw) if (blk[Y * GW + X] > 0f) { active = true; break }; if (active) break }
            if (!active) continue
            b.getPixels(rows, 0, W, 0, py, W, 1)
            var changed = false
            val gy = (py + 0.5f) / cp - 0.5f + MG
            val iy = gy.toInt(); val fy = gy - iy; val iy1 = iy + 1
            for (x in 0 until W) {
                val gx = (x + 0.5f) / cp - 0.5f + MG
                val ix = gx.toInt(); val fx = gx - ix; val ix1 = ix + 1
                val a00 = iy * GW + ix; val a01 = iy * GW + ix1; val a10 = iy1 * GW + ix; val a11 = iy1 * GW + ix1
                val gate = lerp2(blk[a00], blk[a01], blk[a10], blk[a11], fx, fy)
                if (gate <= 0.01f) continue
                val p = rows[x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                // (a blue roof's cell: the art's own)
                if (kd[cy * GW + x / cp + MG] == KIND_ROOF) continue
                val forced = tea[cy * GW + x / cp + MG] < 0f
                val wpx = if (forced) gate else {
                    val share = lerp2(bs[a00], bs[a01], bs[a10], bs[a11], fx, fy).coerceIn(0f, 1f)
                    val pw = waterW(r, g, bl)
                    // (a pixel that is plainly water is all water: the open sea up to a sharp coast is not dimmed by the blend)
                    val plain = ((pw - 0.7f) / 0.25f).coerceIn(0f, 1f)
                    // (eased in: water by more than half is all water, so the shallows and their bright line reach the coast)
                    val t = (((plain + (1f - plain) * (0.2f * pw + 0.8f * share)) - 0.15f) / 0.45f).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t) * gate
                }
                if (wpx <= 0.01f) continue
                val dRamp = lerp2(dr[a00], dr[a01], dr[a10], dr[a11], fx, fy)
                val dLine = lerp2(dl[a00], dl[a01], dl[a10], dl[a11], fx, fy)
                val c = colour(dRamp)
                var cr = ((c shr 16) and 0xFF).toFloat(); var cg = ((c shr 8) and 0xFF).toFloat(); var cb = (c and 0xFF).toFloat()
                val foam = FOAM_MAX * ((FOAM_OUT - dLine) / (FOAM_OUT - FOAM_IN)).coerceIn(0f, 1f)
                if (foam > 0f) { cr += (FOAM[0] - cr) * foam; cg += (FOAM[1] - cg) * foam; cb += (FOAM[2] - cb) * foam }
                val o = Color.rgb((r + (cr - r) * wpx).toInt().coerceIn(0, 255), (g + (cg - g) * wpx).toInt().coerceIn(0, 255),
                    (bl + (cb - bl) * wpx).toInt().coerceIn(0, 255))
                rows[x] = dither(o, x, py)
                changed = true
            }
            if (changed) b.setPixels(rows, 0, W, 0, py, W, 1)
        }
        return final
    }

    /** a morphological opening of the 0/1 mask [a] with radius [r] cells (erode, then grow back; outside the grid counts as the
     *  feature, so it is not eroded from that side): what is narrower than 2 r goes. [t1], [t2]: scratch */
    private fun open(a: FloatArray, w: Int, h: Int, r: Float, t1: FloatArray, t2: FloatArray) {
        if (r < 0.75f) return
        val n = w * h
        for (i in 0 until n) t1[i] = if (a[i] > 0f) FAR else 0f
        chamfer(t1, w, h, 1f)
        for (i in 0 until n) t2[i] = if (t1[i] > r) 0f else FAR
        chamfer(t2, w, h, 1f)
        for (i in 0 until n) if (a[i] > 0f && t2[i] > r + 0.01f) a[i] = 0f
    }

    /** [src] box-blurred into [dst] (half-width [r] cells, edges clamped), [t]: scratch. r < 1: a copy */
    private fun boxBlur(src: FloatArray, dst: FloatArray, t: FloatArray, w: Int, h: Int, r: Int) {
        if (r < 1) { System.arraycopy(src, 0, dst, 0, w * h); return }
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            for (x in -r..r) sum += src[o + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                t[o + x] = sum / (2 * r + 1)
                sum += src[o + (x + r + 1).coerceAtMost(w - 1)] - src[o + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += t[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / (2 * r + 1)
                sum += t[(y + r + 1).coerceAtMost(h - 1) * w + x] - t[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    /** bilinear (a FAR cell makes its side far: deep, no shoreline) */
    private fun lerp2(a: Float, b: Float, c: Float, d: Float, fx: Float, fy: Float): Float {
        val top = a + (b - a) * fx; val bot = c + (d - c) * fx
        return top + (bot - top) * fy
    }

    /** two-pass chamfer on floats (step [s] a cell, s * 1.4142 a diagonal); 0 = a source */
    internal fun chamfer(d: FloatArray, w: Int, h: Int, s: Float) {
        val dg = s * 1.4142f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]; if (v == 0f) continue
            if (x > 0) v = minOf(v, d[i - 1] + s)
            if (y > 0) {
                v = minOf(v, d[i - w] + s)
                if (x > 0) v = minOf(v, d[i - w - 1] + dg)
                if (x < w - 1) v = minOf(v, d[i - w + 1] + dg)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]; if (v == 0f) continue
            if (x < w - 1) v = minOf(v, d[i + 1] + s)
            if (y < h - 1) {
                v = minOf(v, d[i + w] + s)
                if (x < w - 1) v = minOf(v, d[i + w + 1] + dg)
                if (x > 0) v = minOf(v, d[i + w - 1] + dg)
            }
            d[i] = v
        }
    }

    /** 4x4 ordered dither into RGB_565's steps (8 red / blue, 4 green): no bands in the gradient */
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    fun dither(c: Int, x: Int, y: Int): Int {
        if (c == DEEP) return c
        val t = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f
        // in RGB_565's own steps, and back as Android expands them (q << 3 | q >> 2), unbiased; DEEP (exact in RGB_565) as it is: the
        // pictures' far sea is the fill's colour to the bit, no edge where they meet
        val r5 = minOf(31, (((c shr 16) and 0xFF) * 31f / 255f + t).toInt())
        val g6 = minOf(63, (((c shr 8) and 0xFF) * 63f / 255f + t).toInt())
        val b5 = minOf(31, ((c and 0xFF) * 31f / 255f + t).toInt())
        return Color.rgb((r5 shl 3) or (r5 shr 2), (g6 shl 2) or (g6 shr 4), (b5 shl 3) or (b5 shr 2))
    }
}
