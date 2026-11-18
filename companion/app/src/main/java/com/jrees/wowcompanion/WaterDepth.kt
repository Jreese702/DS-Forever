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
 * Until a continent's field is built (the first time only: then it is read from the disk) pictures are made with their own distance
 * alone ("provisional": TerrainOverview keys them apart, MinimapTiles drops them when the field lands) and redrawn when it is ready.
 *
 * Memory: the fields (bytes, ~0.3 MB a continent, at most [FIELDS_MAX]), their sea pictures (~0.15 MB each) and the repaint's scratch
 * (~0.4 MB, one set, the repaint is serialised); MemWatch's "waterdepth". The picture caches' budgets are unchanged.
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
    /** land narrower than this (yd) in the water is water: a speck */
    private const val SPECK_YD = 6f
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
    /** black-water weight (0..1): Kalimdor's open sea, near-black with no red ((4, 6, 8), (5, 5, 5); (0, 4, 8), (0, 4, 0) once decoded to
     *  RGB_565); not the warm black of Mount Hyjal's crevices ((13, 12, 8) -> (8, 12, 8), (18, 17, 8)) nor the dark-green coastal fringe
     *  ((8, 25, 11)); Eastern Kingdoms' dark sea ((8, 17, 16)) is [tealW]'s */
    fun blackW(r: Int, g: Int, b: Int, coarse: Boolean = false): Float =
        if (coarse) blackCoarse(r, g, b)
        else ((30 - (r + g + b)) / 10f).coerceIn(0f, 1f) * ((12 - r) / 6f).coerceIn(0f, 1f) * ((b - r + 3) / 3f).coerceIn(0f, 1f)
    /** the same in a picture of 6 yd a pixel and coarser (TerrainOverview's levels, the field, a tile at an eighth), and a pixel read
     *  one by one where the cells say water: any near-black whose green or blue is not under its red. There a crevice is averaged
     *  into its boulders (no black left), and the lossy WebP copies' colour smears at a sharp edge ((9, 17, 9), (14, 16, 14) between
     *  two seas: the outline round the world's ADTs) must not be read as land, nor a lossy copy's noise in a black sea ((8, 4, 0)).
     *  Blackrock Mountain's black reads as water here too: black water counts only where it is the open sea's (the field's flood) */
    private fun blackCoarse(r: Int, g: Int, b: Int): Float {
        val s = r + g + b
        val hue = if (s <= 24) 1f else ((maxOf(g, b) - r + 8) / 6f).coerceIn(0f, 1f)
        return ((52 - s) / 12f).coerceIn(0f, 1f) * ((24 - r) / 8f).coerceIn(0f, 1f) * hue
    }
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
        val bytes: Long get() = d.size.toLong() + (sea?.byteCount?.toLong() ?: 0L)
        /** the field has water within 2 cells (66 yd) of ADT point ([x], [y]): the black there is the open sea's, not Blackrock's */
        fun waterNear(x: Double, y: Double): Boolean {
            val cx = ((x - c0) * FIELD_PX).toInt(); val cy = ((y - r0) * FIELD_PX).toInt()
            for (yy in cy - 2..cy + 2) for (xx in cx - 2..cx + 2) {
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) return true
                if (d[yy * w + xx].toInt() != 0) return true
            }
            return false
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
    private const val SEA = 0xFF.toByte()
    private const val BLACK_WATER = 0xFE.toByte()
    /** the field's disk copy's version (a new classification = made again) */
    private const val VERSION = 10

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
                Field(cont, c0, r0, w, h, d, stamp).also { it.sea = sea(it) }
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
        val n = TerrainOverview.span(FIELD_LEVEL)
        val tile = TerrainOverview.TILE
        val row = IntArray(tile)
        val wt = FloatArray(tile * tile)
        var any = false
        for (oc in 0 until 64 / n) for (or in 0 until 64 / n) {
            if (!TerrainOverview.has(cont, FIELD_LEVEL, oc, or)) continue
            val b = TerrainOverview.makeRaw(cont, FIELD_LEVEL, oc, or) ?: continue
            any = true
            for (py in 0 until tile) {
                b.getPixels(row, 0, tile, 0, py, tile, 1)
                for (px in 0 until tile) {
                    val p = row[px]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                    val tw = tealW(r, g, bl)
                    // (black-led water negative: it must reach the open sea, below)
                    wt[py * tile + px] = if (tw >= 0.5f) tw else -blackW(r, g, bl, true)
                }
            }
            b.recycle()
            for (py in 0 until tile) {
                val gy = or * tile + py - r0 * FIELD_PX
                if (gy !in 0 until h) continue
                for (px in 0 until tile) {
                    val gx = oc * tile + px - c0 * FIELD_PX
                    if (gx !in 0 until w) continue
                    // land unless clearly water (the 3 x 3 mean: a lossy copy's noise in a sea averages out): a coast's mixed pixel counts
                    // as land, so the field is never FARTHER from land than a picture's own distance (it seeds them: an overestimate
                    // would be a seam); an ADT the continent lacks is sea
                    var sum = 0f; var k = 0; var black = 0
                    for (yy in maxOf(0, py - 1)..minOf(tile - 1, py + 1)) for (xx in maxOf(0, px - 1)..minOf(tile - 1, px + 1)) {
                        val v = wt[yy * tile + xx]; sum += kotlin.math.abs(v); k++; if (v < 0f) black++ }
                    val col = (oc * tile + px) / FIELD_PX; val rw = (or * tile + py) / FIELD_PX
                    if (TerrainOverview.hasAdt(cont, col, rw)) d[gy * w + gx] = if (sum / k < 0.85f) 0 else if (black * 2 > k) BLACK_WATER else 0xFF.toByte()
                }
            }
        }
        if (!any) return null
        // black water is water only where it reaches the open sea (the ADTs the continent lacks, the margin, any teal water): Blackrock
        // Mountain's black is land. Sweeps until nothing changes
        var changed = true; var sweeps = 0
        while (changed && sweeps++ < 400) {
            changed = false
            for (pass in 0..1) for (j in 0 until w * h) {
                val i = if (pass == 0) j else w * h - 1 - j
                if (d[i] != BLACK_WATER) continue
                val x = i % w; val y = i / w
                if ((x > 0 && d[i - 1] == SEA) || (x < w - 1 && d[i + 1] == SEA) || (y > 0 && d[i - w] == SEA) || (y < h - 1 && d[i + w] == SEA)) {
                    d[i] = SEA; changed = true
                }
            }
        }
        for (i in 0 until w * h) if (d[i] == BLACK_WATER) d[i] = 0
        chamferBytes(d, w, h)
        file(cont, stamp)?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp" + Thread.currentThread().id)
                DataOutputStream(tmp.outputStream().buffered()).use { o -> o.writeInt(c0); o.writeInt(r0); o.writeInt(w); o.writeInt(h); o.write(d) }
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
        return Field(cont, c0, r0, w, h, d, stamp).also { it.sea = sea(it) }
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

    /** the field's sea picture: its water colour at 8 px an ADT (its land cells' too: under the tiles, seen only where none is drawn) */
    private fun sea(f: Field): Bitmap? = runCatching {
        val sw = f.w / 2; val sh = f.h / 2
        val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565)
        val line = IntArray(sw)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val yd = f.yd(f.c0 + (x + 0.5) * 2.0 / FIELD_PX, f.r0 + (y + 0.5) * 2.0 / FIELD_PX)
                line[x] = dither(colour(yd), x, y)
            }
            b.setPixels(line, 0, sw, 0, y, sw, 1)
        }
        b
    }.getOrNull()

    // ---------------------------------------------------------------- the repaint
    private const val GRID_MAX = 128
    private var band: IntArray? = null
    private var gBlack: FloatArray? = null
    private var gTeal: FloatArray? = null
    private var gMask: FloatArray? = null
    private var gLine: FloatArray? = null
    private var gRamp: FloatArray? = null
    private fun scratchBytes(): Long = listOf(gBlack, gTeal, gMask, gLine, gRamp).sumOf { (it?.size ?: 0) * 4L } + (band?.size ?: 0) * 4L

    /** the tests: what the last repaint did ([0] water cells, [1] the field was used) */
    @Volatile var lastWater = 0
    /** the tests: repaints so far (a frame must make none) */
    @Volatile var repaints = 0
    @Volatile var lastFinal = false

    /**
     * Repaint the water of picture [b] (mutable RGB_565, square) of continent [cont], which covers ADTs [x0] .. [x0] + [span] (columns)
     * and [y0] .. [y0] + [span] (rows). True = final (the field was used, or the continent has none); false = provisional. Any thread
     * (serialised); never the UI thread's frame.
     */
    fun recolour(b: Bitmap, cont: Int, x0: Double, y0: Double, span: Double): Boolean =
        synchronized(paintLock) { repaints++; recolourLocked(b, cont, x0, y0, span) }
    /** the repaint's own lock (the UI thread's [ready] / [field] never wait on a repaint) */
    private val paintLock = Any()

    private fun recolourLocked(b: Bitmap, cont: Int, x0: Double, y0: Double, span: Double): Boolean {
        val field = field(cont)
        val final = field != null || cont in none
        lastFinal = final; lastWater = 0
        if (!b.isMutable) return final
        val W = b.width; val H = b.height
        val gw = minOf(W, GRID_MAX); val cp = W / gw; val gh = H / cp
        if (gw <= 0 || gh <= 0) return final
        val n = gw * gh
        val bandA = band?.takeIf { it.size >= W * cp } ?: IntArray(W * cp).also { band = it }
        fun grid(a: FloatArray?): FloatArray = if (a != null && a.size >= n) a else FloatArray(maxOf(n, GRID_MAX * GRID_MAX))
        val blk = grid(gBlack).also { gBlack = it }; val tea = grid(gTeal).also { gTeal = it }
        val m = grid(gMask).also { gMask = it }; val dl = grid(gLine).also { gLine = it }; val dr = grid(gRamp).also { gRamp = it }
        java.util.Arrays.fill(blk, 0, n, 0f); java.util.Arrays.fill(tea, 0, n, 0f); java.util.Arrays.fill(m, 0, n, 0f)
        // (TerrainOverview's levels are all lossy re-encodings: the coarse reading, whatever their scale)
        val coarse = span > 1.0 || span * ADT_YD / W >= 6.0
        // pass A: each cell's black / teal share, and its water share (a pixel's larger of the two: Westfall's sea mixes both once in
        // RGB_565, (8, 16, 16) teal and (0, 16, 8) black, a cell of each half is still all water)
        val inv = 1f / (cp * cp)
        for (cy in 0 until gh) {
            b.getPixels(bandA, 0, W, 0, cy * cp, W, cp)
            for (yy in 0 until cp) for (x in 0 until W) {
                val p = bandA[yy * W + x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                val i = cy * gw + x / cp
                val bw = blackW(r, g, bl, coarse); val tw = tealW(r, g, bl)
                tea[i] += tw * inv; m[i] += maxOf(bw, tw) * inv
            }
        }
        val cellYd = (span * ADT_YD / gw).toFloat()
        // water narrower than BLACK_OPEN_YD is not water unless it is teal (an opening: erode from the land, then grow back; outside the
        // picture counts as water, a sea across the border is not eroded from that side): the dark crevices between boulders; teal
        // narrower than TEAL_OPEN_YD neither (the blue-grey specks in dark rock). A black speck inside a teal sea stays (Westfall's mix)
        val cellA = span / gw
        for (i in 0 until n) {
            // black-led water only where the field has water near (Blackrock Mountain's black is land; no field yet: as read)
            val blackLed = m[i] >= 0.5f && tea[i] < 0.5f
            if (blackLed && field != null && !field.waterNear(x0 + ((i % gw) + 0.5) * cellA, y0 + ((i / gw) + 0.5) * cellA)) m[i] = 0f
            dl[i] = if (m[i] >= 0.5f) 1f else 0f; dr[i] = if (m[i] >= 0.5f && tea[i] >= 0.5f) 1f else 0f
            m[i] = dl[i]                                          // (m: the water before the opening)
        }
        // (at least a cell - a radius under 1 removes nothing: a lone water pixel in a small picture is a speck in the coastal fringe)
        val rBlack = maxOf(1.0f, BLACK_OPEN_YD / 2f / cellYd)
        open(dl, gw, gh, rBlack, blk, tea)
        open(dr, gw, gh, maxOf(1.0f, TEAL_OPEN_YD / 2f / cellYd), blk, tea)
        // the opening sees only this picture: an inlet of open sea that narrows to this picture's edge would go here and stay in the
        // neighbour (a seam: Durotar's coast, where the fringe fades from green to black); so within its reach of the edge the water
        // stays water (a crevice there is not: the strict black reading has none of a crevice's warm (8, 12, 8))
        // (only water that runs to the edge: a speck near it is still a speck). tea: 1 = such a cell
        val edge = kotlin.math.ceil(2f * rBlack).toInt() + 1
        java.util.Arrays.fill(tea, 0, n, 0f)
        for (k in 0 until edge) {
            // a ring k cells in from the edge: kept when water and touching the kept ring outside it (or the edge itself)
            for (cy in 0 until gh) for (cx in 0 until gw) {
                val ring = minOf(minOf(cx, gw - 1 - cx), minOf(cy, gh - 1 - cy))
                if (ring != k) continue
                val i = cy * gw + cx
                if (m[i] <= 0f) continue
                var ok = k == 0
                if (!ok) for (dy in -1..1) { for (dx in -1..1) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until gw && y in 0 until gh && tea[y * gw + x] > 0f &&
                        minOf(minOf(x, gw - 1 - x), minOf(y, gh - 1 - y)) == k - 1) { ok = true; break }
                }; if (ok) break }
                if (ok) tea[i] = 1f
            }
        }
        var water = 0
        for (i in 0 until n) {
            m[i] = if (dl[i] > 0f || dr[i] > 0f || tea[i] > 0f) 1f else 0f
            if (m[i] > 0f) water++
        }
        lastWater = water
        if (water == 0) return final
        // land narrower than SPECK_YD in the water is water (a speck, a lossy copy's smear along a picture's edge): repainted whole, no
        // ring of shoreline round it. tea is free now: -1 = such a cell, 1 = land, 0 = water
        for (i in 0 until n) tea[i] = 1f - m[i]
        open(tea, gw, gh, maxOf(1.0f, SPECK_YD / 2f / cellYd), dl, dr)
        for (i in 0 until n) if (m[i] <= 0f && tea[i] <= 0f) { m[i] = 1f; tea[i] = -1f }
        // the gradient's land: without what the field's 33 yd cells can not see (a rock offshore, a wreck): it keeps its shoreline but
        // makes no halo of shallows, which would stop at this picture's border - a seam (blk is free now: the opened land)
        for (i in 0 until n) blk[i] = 1f - m[i]
        open(blk, gw, gh, LAND_OPEN_YD / 2f / cellYd, dl, dr)
        // the distances (yd): the shoreline's from this picture's own land; the gradient's seeded with the field's
        for (i in 0 until n) dl[i] = if (m[i] > 0f) FAR else 0f
        chamfer(dl, gw, gh, cellYd)
        val cell = span / gw
        for (cy in 0 until gh) for (cx in 0 until gw) {
            val i = cy * gw + cx
            val fy = field?.yd(x0 + (cx + 0.5) * cell, y0 + (cy + 0.5) * cell)
            // (and only land the field knows, within FIELD_KNOWS_YD of its own: the field seeds the neighbours, which do not see this)
            dr[i] = if (blk[i] > 0f && (fy == null || fy <= FIELD_KNOWS_YD)) 0f else fy ?: FAR
        }
        chamfer(dr, gw, gh, cellYd)
        // the gate: the water cells grown by one (blk is free again), so a coast's pixels in the cells next to the water are read one by
        // one (Kalimdor's black-green fringe edge); deeper in the land nothing is
        for (cy in 0 until gh) for (cx in 0 until gw) {
            val i = cy * gw + cx
            blk[i] = if (m[i] > 0f || (cx > 0 && m[i - 1] > 0f) || (cx < gw - 1 && m[i + 1] > 0f) || (cy > 0 && m[i - gw] > 0f) ||
                (cy < gh - 1 && m[i + gw] > 0f)) 1f else 0f
        }
        // pass B: each pixel's water share (its own colour, gated by the cells' mask: no speck of a dark crevice) to its depth colour
        for (cy in 0 until gh) {
            // a band with no water cell near: untouched
            var near = false
            for (yy in maxOf(0, cy - 1)..minOf(gh - 1, cy + 1)) { for (cx in 0 until gw) if (m[yy * gw + cx] > 0f) { near = true; break }; if (near) break }
            if (!near) continue
            b.getPixels(bandA, 0, W, 0, cy * cp, W, cp)
            var changed = false
            for (yy in 0 until cp) {
                val py = cy * cp + yy
                val gy = ((py + 0.5f) / cp - 0.5f).coerceIn(0f, gh - 1f)
                val iy = minOf(gy.toInt(), gh - 2).coerceAtLeast(0); val fy = if (gh > 1) gy - iy else 0f
                val iy1 = if (gh > 1) iy + 1 else iy
                for (x in 0 until W) {
                    val gx = ((x + 0.5f) / cp - 0.5f).coerceIn(0f, gw - 1f)
                    val ix = minOf(gx.toInt(), gw - 2).coerceAtLeast(0); val fx = if (gw > 1) gx - ix else 0f
                    val ix1 = if (gw > 1) ix + 1 else ix
                    val a00 = iy * gw + ix; val a01 = iy * gw + ix1; val a10 = iy1 * gw + ix; val a11 = iy1 * gw + ix1
                    val gate0 = lerp2(blk[a00], blk[a01], blk[a10], blk[a11], fx, fy)
                    if (gate0 <= 0.2f) continue
                    val k = yy * W + x
                    val p = bandA[k]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                    val gate = ((gate0 - 0.2f) / 0.5f).coerceIn(0f, 1f)
                    val forced = tea[minOf(py / cp, gh - 1) * gw + minOf(x / cp, gw - 1)] < 0f
                    // (a pixel by the lenient reading: where the cells say water, any near-black is water - the black-green edge of
                    // Kalimdor's coastal fringe, (0, 16, 0), (4, 12, 4); the cells' mask keeps the crevices in land out)
                    val wpx = if (forced) gate else maxOf(blackW(r, g, bl, true), tealW(r, g, bl)) * gate
                    if (wpx <= 0.01f) continue
                    val dRamp = lerp2(dr[a00], dr[a01], dr[a10], dr[a11], fx, fy)
                    val dLine = lerp2(dl[a00], dl[a01], dl[a10], dl[a11], fx, fy)
                    val c = colour(dRamp)
                    var cr = ((c shr 16) and 0xFF).toFloat(); var cg = ((c shr 8) and 0xFF).toFloat(); var cb = (c and 0xFF).toFloat()
                    val foam = FOAM_MAX * ((FOAM_OUT - dLine) / (FOAM_OUT - FOAM_IN)).coerceIn(0f, 1f)
                    if (foam > 0f) { cr += (FOAM[0] - cr) * foam; cg += (FOAM[1] - cg) * foam; cb += (FOAM[2] - cb) * foam }
                    val o = Color.rgb((r + (cr - r) * wpx).toInt().coerceIn(0, 255), (g + (cg - g) * wpx).toInt().coerceIn(0, 255),
                        (bl + (cb - bl) * wpx).toInt().coerceIn(0, 255))
                    bandA[k] = dither(o, x, py)
                    changed = true
                }
            }
            if (changed) b.setPixels(bandA, 0, W, 0, cy * cp, W, cp)
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
