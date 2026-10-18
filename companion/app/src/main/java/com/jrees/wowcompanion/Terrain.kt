package com.jrees.wowcompanion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * THE GROUND'S SLOPE FOR THE ROUTE (b-route-terrain; the user on test45: "It's trying to push me along the mountains ... instead of
 * sticking to a safe path on the road" - their pick: slope from the game's own heights, the walked cells cheaper, roads after a
 * probe). The art harvest's [STEP] reads each zone's root ADTs on the device (the game's terrain: per 33 yd chunk 145 heights, 9 x 9
 * of them 4.17 yd apart; a chunk's position is its north-west corner, rows running south and columns east - measured on the PC
 * client, Elwynn's azeroth_32_49) and keeps, per 25 yd cell of the zone's map, the AVERAGE slope of the ground in it
 * (terrain/<uiMap>.bin: built on the device, never shipped). The route's cost multiplies by [factor]: flat 1, a climb costs more,
 * a cliff is a wall. Cells the player has walked ([Walked]) cost less: people walk the roads.
 */
object Terrain {
    const val CELL_YD = 25.0
    private const val S = 100.0 / 3.0 / 8.0                // the yards between two of a chunk's outer heights
    private const val TILE_V = 128                          // a tile's outer heights a side (16 chunks x 8), the 129th is the next tile's 0th
    const val NO_DATA = 255

    /** the route's cost for a cell of [slope] degrees ([NO_DATA]: 1, unknown ground costs as flat) */
    fun factor(slope: Int): Float = when {
        // measured on Elwynn's real heights: its rolling forest averages 20-25 degrees, walked every day; a climb begins past that
        slope == NO_DATA || slope < 25 -> 1f
        slope < 40 -> 1f + (slope - 25) / 15f * 2f            // a climb: up to 3x
        slope < 50 -> 3f + (slope - 40) / 10f * 9f            // a steep bank: up to 12x
        else -> WALL                                           // a cliff: a wall (DangerGrid.route never steps on one)
    }
    /** a cliff's factor: the route's A* never enters such a cell (test51: "the pathing needs to really factor walkable terrain") */
    const val WALL = 60f
    /** a climb and steeper (a steep bank, a cliff): no way out of a map over it (the edge toward the quest, b-route-multihop) */
    const val STEEP = 3f

    // ---------------------------------------------------------------- one zone's grid (built by the harvest, read by the route)

    /** a zone's slopes: its world rect ([t] / [b] world X north, [l] / [r] world Y west), [nx] x [ny] cells of [slope] degrees */
    class Grid(val t: Double, val b: Double, val l: Double, val r: Double, val nx: Int, val ny: Int, val slope: ByteArray) {
        /** the slope at world ([wx], [wy]), [NO_DATA] off the grid */
        fun at(wx: Double, wy: Double): Int {
            val i = floor((l - wy) / (l - r) * nx).toInt(); val j = floor((t - wx) / (t - b) * ny).toInt()
            return if (i in 0 until nx && j in 0 until ny) slope[j * nx + i].toInt() and 0xFF else NO_DATA
        }

        fun write(f: File) {
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC); o.writeDouble(t); o.writeDouble(b); o.writeDouble(l); o.writeDouble(r); o.writeInt(nx); o.writeInt(ny); o.write(slope)
            }
        }

        companion object {
            const val MAGIC = 0x54524E31                       // "TRN1"
            fun read(f: File): Grid? = runCatching {
                DataInputStream(f.inputStream().buffered()).use { i ->
                    if (i.readInt() != MAGIC) return null
                    val t = i.readDouble(); val b = i.readDouble(); val l = i.readDouble(); val r = i.readDouble()
                    val nx = i.readInt(); val ny = i.readInt()
                    if (nx !in 1..4096 || ny !in 1..4096) return null
                    val s = ByteArray(nx * ny); i.readFully(s)
                    Grid(t, b, l, r, nx, ny, s)
                }
            }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- the game's heights

    /** one root ADT's outer heights, [TILE_V] x [TILE_V] (rows south, columns east from the tile's north-west corner) */
    class Tile(val h: FloatArray)

    /**
     * a root ADT ([adt]): each MCNK's index (ix, iy), its base height (position z at 0x68 + 8) and its MCVT (145 floats: 9 outer,
     * 8 inner, ...); the outer ones kept. Null when it holds no chunk.
     */
    fun tile(adt: ByteArray): Tile? {
        val h = FloatArray(TILE_V * TILE_V) { Float.NaN }
        var o = 0; var any = false
        while (o + 8 <= adt.size) {
            val tag = tag(adt, o); val n = Db2.le32(adt, o + 4)
            if (n < 0 || o + 8 + n > adt.size) break
            if (tag == "MCNK" && n >= 128) {
                val d = o + 8
                val ix = Db2.le32(adt, d + 4); val iy = Db2.le32(adt, d + 8)
                val z = java.lang.Float.intBitsToFloat(Db2.le32(adt, d + 0x68 + 8))
                var p = d + 128
                while (p + 8 <= d + n) {
                    val t2 = tag(adt, p); val n2 = Db2.le32(adt, p + 4)
                    if (n2 < 0 || p + 8 + n2 > d + n) break
                    if (t2 == "MCVT" && n2 >= 145 * 4 && ix in 0..15 && iy in 0..15) {
                        var k = 0
                        for (row in 0 until 17) {
                            if (row % 2 == 0) {
                                val vr = row / 2
                                for (vc in 0 until 9) {
                                    val gr = iy * 8 + vr; val gc = ix * 8 + vc
                                    val v = z + java.lang.Float.intBitsToFloat(Db2.le32(adt, p + 8 + k * 4))
                                    if (gr < TILE_V && gc < TILE_V) h[gr * TILE_V + gc] = v
                                    k++
                                }
                            } else k += 8
                        }
                        any = true
                    }
                    p += 8 + n2
                }
            }
            o += 8 + n
        }
        return if (any) Tile(h) else null
    }

    private fun tag(b: ByteArray, o: Int) = String(charArrayOf(b[o + 3].toInt().toChar(), b[o + 2].toInt().toChar(), b[o + 1].toInt().toChar(), b[o].toInt().toChar()))

    /**
     * a root ADT's holes (each MCNK's: the high-res 8 x 8 mask at 0x14 when its flags hold 0x10000, else the low-res 4 x 4 at 0x3C,
     * each bit 2 x 2 cells): where the terrain opens into a cave and its ground is not there to walk. A [TILE_V] x [TILE_V] grid of
     * cells like [water]'s; null when the tile has none.
     */
    fun holes(adt: ByteArray): BooleanArray? {
        val out = BooleanArray(TILE_V * TILE_V); var any = false
        var o = 0
        while (o + 8 <= adt.size) {
            val n = Db2.le32(adt, o + 4); if (n < 0 || o + 8 + n > adt.size) break
            if (tag(adt, o) == "MCNK" && n >= 128) {
                val d = o + 8
                val fl = Db2.le32(adt, d); val ix = Db2.le32(adt, d + 4); val iy = Db2.le32(adt, d + 8)
                if (ix in 0..15 && iy in 0..15) for (r in 0 until 8) for (c in 0 until 8) {
                    val hole = if (fl and 0x10000 != 0) (adt[d + 0x14 + r].toInt() shr c) and 1 != 0
                        else (((adt[d + 0x3C].toInt() and 0xFF) or ((adt[d + 0x3D].toInt() and 0xFF) shl 8)) shr ((r / 2) * 4 + c / 2)) and 1 != 0
                    if (hole) { out[(iy * 8 + r) * TILE_V + ix * 8 + c] = true; any = true }
                }
            }
            o += 8 + n
        }
        return if (any) out else null
    }

    /**
     * THE WATER OF A ROOT ADT (b-safepath-water; the user: "Pathing shouldn't be in water"): its MH2O - per MCNK (in MCNK order) its
     * liquid instances, each over a w x h block of the chunk's 8 x 8 cells from (x, y), the cells there (its bitmap, when it has one)
     * at its surface height (the instance's highest). A [TILE_V] x [TILE_V] grid like [Tile.h] (row south, column east) of the
     * surface over each cell, NaN = dry; null when the tile has no water at all.
     */
    fun water(adt: ByteArray): FloatArray? {
        var o = 0
        while (o + 8 <= adt.size) {
            val n = Db2.le32(adt, o + 4)
            if (n < 0 || o + 8 + n > adt.size) return null
            if (tag(adt, o) == "MH2O") break
            o += 8 + n
        }
        if (o + 8 > adt.size) return null
        val base = o + 8; val end = base + Db2.le32(adt, o + 4)
        val out = FloatArray(TILE_V * TILE_V) { Float.NaN }
        var any = false
        for (c in 0 until 256) {
            val hp = base + c * 12
            if (hp + 12 > end) break
            val inst = Db2.le32(adt, hp); val layers = Db2.le32(adt, hp + 4)
            if (inst <= 0 || layers <= 0 || layers > 8) continue
            val cx = c % 16; val cy = c / 16
            for (l in 0 until layers) {
                val ip = base + inst + l * 24
                if (ip + 24 > end) break
                val top = java.lang.Float.intBitsToFloat(Db2.le32(adt, ip + 8))
                val xo = adt[ip + 12].toInt() and 0xFF; val yo = adt[ip + 13].toInt() and 0xFF
                val w = adt[ip + 14].toInt() and 0xFF; val hh = adt[ip + 15].toInt() and 0xFF
                val bm = Db2.le32(adt, ip + 16)
                if (top.isNaN() || w == 0 || hh == 0 || xo + w > 8 || yo + hh > 8) continue
                for (y in 0 until hh) for (x in 0 until w) {
                    val bit = y * w + x
                    if (bm != 0) { val bp = base + bm + bit / 8; if (bp >= end || (adt[bp].toInt() shr (bit % 8)) and 1 == 0) continue }
                    val k = (cy * 8 + yo + y) * TILE_V + (cx * 8 + xo + x)
                    if (out[k].isNaN() || top > out[k]) out[k] = top
                    any = true
                }
            }
        }
        return if (any) out else null
    }

    /** the root ADTs of a continent's WDT (MAID's first uint32 of each 32-byte row), by MapHarvest.key(col, row) */
    fun maidRoot(wdt: ByteArray): Map<Int, Int> {
        var o = 0
        while (o + 8 <= wdt.size) {
            val n = Db2.le32(wdt, o + 4)
            if (n < 0 || o + 8 + n > wdt.size) break
            if (tag(wdt, o) == "MAID") {
                val out = HashMap<Int, Int>()
                for (i in 0 until n / 32) { val fid = Db2.le32(wdt, o + 8 + i * 32); if (fid != 0) out[MapHarvest.key(i % 64, i / 64)] = fid }
                return out
            }
            o += 8 + n
        }
        return emptyMap()
    }

    /**
     * a zone's grid: [rect]'s cells, each the average slope (degrees) of the ground's edges in it; [height] = the outer height at
     * a GLOBAL vertex (row from the world's north edge, column from its west edge, [S] yards apart), NaN where no tile is read
     */
    fun grid(rect: MapHarvest.Rect, height: (Int, Int) -> Float): Grid {
        val nx = max(1, ((rect.l - rect.r) / CELL_YD).toInt()); val ny = max(1, ((rect.t - rect.b) / CELL_YD).toInt())
        val cw = (rect.l - rect.r) / nx; val ch = (rect.t - rect.b) / ny
        val top = 32.0 * MapHarvest.ADT_YD
        val out = ByteArray(nx * ny)
        for (j in 0 until ny) for (i in 0 until nx) {
            val wx0 = rect.t - j * ch; val wy0 = rect.l - i * cw
            val r0 = ((top - wx0) / S).toInt(); val r1 = ((top - (wx0 - ch)) / S).toInt()
            val c0 = ((top - wy0) / S).toInt(); val c1 = ((top - (wy0 - cw)) / S).toInt()
            var sum = 0.0; var n = 0
            for (gr in r0..r1) for (gc in c0..c1) {
                val h = height(gr, gc); if (h.isNaN()) continue
                val hs = height(gr, gc + 1); if (!hs.isNaN()) { sum += deg(hs - h); n++ }
                val hd = height(gr + 1, gc); if (!hd.isNaN()) { sum += deg(hd - h); n++ }
            }
            out[j * nx + i] = (if (n == 0) NO_DATA else min(89, (sum / n).toInt())).toByte()
        }
        return Grid(rect.t, rect.b, rect.l, rect.r, nx, ny, out)
    }

    private fun deg(dh: Float) = Math.toDegrees(atan(kotlin.math.abs(dh) / S))

    // ---------------------------------------------------------------- the harvest step

    /** the obj0 ADTs of a continent's WDT (MAID's second uint32 of each 32-byte row), by MapHarvest.key(col, row) */
    fun maidObj0(wdt: ByteArray): Map<Int, Int> {
        var o = 0
        while (o + 8 <= wdt.size) {
            val n = Db2.le32(wdt, o + 4)
            if (n < 0 || o + 8 + n > wdt.size) break
            if (tag(wdt, o) == "MAID") {
                val out = HashMap<Int, Int>()
                for (i in 0 until n / 32) { val fid = Db2.le32(wdt, o + 8 + i * 32 + 4); if (fid != 0) out[MapHarvest.key(i % 64, i / 64)] = fid }
                return out
            }
            o += 8 + n
        }
        return emptyMap()
    }

    /** a zone's building cover grid ([rect]'s cells like [grid]): each cell the number of its 3 x 3 samples a room covers, 0..9 */
    fun buildingGrid(rect: MapHarvest.Rect, feet: List<Buildings.Foot>): Grid {
        val nx = max(1, ((rect.l - rect.r) / CELL_YD).toInt()); val ny = max(1, ((rect.t - rect.b) / CELL_YD).toInt())
        val cw = (rect.l - rect.r) / nx; val ch = (rect.t - rect.b) / ny
        val near = feet.filter { it.wx1 >= rect.b && it.wx0 <= rect.t && it.wy1 >= rect.r && it.wy0 <= rect.l }
        val out = ByteArray(nx * ny)
        if (near.isNotEmpty()) for (j in 0 until ny) for (i in 0 until nx) {
            val wx = rect.t - (j + 0.5) * ch; val wy = rect.l - (i + 0.5) * cw
            out[j * nx + i] = Math.round(Buildings.coverWorld(near, wx, wy, ch / 2, cw / 2) * 9).toInt().toByte()
        }
        return Grid(rect.t, rect.b, rect.l, rect.r, nx, ny, out)
    }

    /**
     * "terrain" (v2, BEFORE PLAY; the user on test56: "I need the path building to work without having played there yet"): every zone's
     * slope grid (terrain/<uiMap>.bin) AND its building cover (terrain/<uiMap>.bld: the rooms of EVERY placed WMO, [WorldBuildings]), for
     * all the zones of every continent in the recipe (Forever's lands too): the player's zone first, then by distance on its continent,
     * then the rest; again after a game update (the harvest's stamp). Resumable per zone (terrain/made.txt, per game build). Each root /
     * obj0 ADT read once while near (an LRU), each WMO root once. terrain/coverage.json says how far it got; the log says the pace.
     */
    val STEP = object : ArtHarvest.Step {
        override val id = "terrain"
        override val version = 2
        override val beforePlay = true
        override val weight = 60
        override fun run(job: ArtHarvest.Job) {
            val g = job.game
            val t0 = System.nanoTime()
            var bytes = 0L
            fun read(fid: Int): ByteArray? = g.read(fid)?.also { bytes += it.size }
            val rects = MapHarvestSteps.zoneRects(job).filterValues { MapHarvest.tilesUnder(it) != null }
            check(rects.isNotEmpty()) { "the worldmap recipe has no zone rects" }
            val mapRows = (g.db2(MapHarvest.DB_MAP) ?: error("Map.db2 unreadable: ${g.lastReadWhy}")).rows().associateBy { it.id }
            val pass = Pass({ read(it) }) { c -> mapRows[c]?.let { read(it.int(MapHarvest.MAP_WDT)) } }
            val made = job.file("terrain/made.txt")
            val done = HashSet<String>()
            val head = g.buildKey + " v2"
            runCatching { made.readLines() }.getOrNull()?.let { if (it.firstOrNull() == head) done.addAll(it.drop(1)) }
            if (done.isEmpty()) made.writeText(head + "\n")
            // the player's zone first, then by distance on its continent, then the rest (before Play the first time: the recipe's order)
            val n = MapHarvestSteps.near
            val nearC = n?.let { rects[it.first]?.c }
            val order = rects.entries.sortedWith(compareBy({ it.value.c != nearC }, { e ->
                if (n == null || e.value.c != nearC) 0.0 else hypot((e.value.t + e.value.b) / 2 - n.second, (e.value.l + e.value.r) / 2 - n.third)
            }))
            var built = 0
            for ((i, e) in order.withIndex()) {
                val (map, rect) = e.key to e.value
                if (map.toString() in done) continue
                job.progress(i, order.size, "")
                val (slopes, cover) = pass.zone(rect) ?: continue
                slopes.write(job.file("terrain/$map.bin"))
                cover.write(job.file("terrain/$map.bld"))
                files.drop(map); buildings.drop(map)
                made.appendText("$map\n"); done.add(map.toString()); built++
                writeCoverage(job, done.size, order.size, pass.placed.size, pass.rooms, bytes, t0)
            }
            job.progress(order.size, order.size)
            val sec = (System.nanoTime() - t0) / 1e9
            writeCoverage(job, done.size, order.size, pass.placed.size, pass.rooms, bytes, t0)
            android.util.Log.i("WCMAP", "terrain: $built zones built (${done.size} of ${order.size}) in ${"%.1f".format(sec)} s, read " +
                "${bytes / 1_000_000} MB (${"%.0f".format(bytes / 1e6 / sec.coerceAtLeast(0.001))} MB/s); buildings: ${pass.placed.size} " +
                "placements with ${pass.rooms} rooms as walls, ${pass.wmoRoots} WMO roots read")
        }
    }

    /**
     * THE PASS'S WORK, one zone at a time (the step's core; pure but for [read]: a file by its fileDataID, [wdtOf]: a continent's WDT):
     * the zone's slope grid and its building cover, each root / obj0 ADT read once while near (an LRU), each WMO root once
     */
    class Pass(private val read: (Int) -> ByteArray?, private val wdtOf: (Int) -> ByteArray?) {
        private val roots = HashMap<Int, Map<Int, Int>>(); private val objs = HashMap<Int, Map<Int, Int>>()
        private val cache = object : LinkedHashMap<Long, Tile?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Tile?>?) = size > TILE_CACHE
        }
        private val wmo = HashMap<Int, List<WorldBuildings.Group>>()
        private val tileFeet = object : LinkedHashMap<Long, List<Pair<Int, List<Buildings.Foot>>>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Pair<Int, List<Buildings.Foot>>>>?) = size > TILE_CACHE
        }
        private val tileWalks = HashMap<Long, List<Pair<Int, List<Buildings.Foot>>>>()
        /** the placements with rooms as walls (continent, unique id), and their rooms */
        val placed = HashSet<Long>(); var rooms = 0; private set
        val wmoRoots get() = wmo.size
        /** the last zone's walkway boxes (outdoor groups, cities whole): the log and the tests */
        var lastWalks = 0; private set
        private val top = 32.0 * MapHarvest.ADT_YD

        private fun continent(c: Int): Boolean {
            if (c in roots) return roots[c]!!.isNotEmpty()
            val wdt = wdtOf(c)
            roots[c] = wdt?.let { maidRoot(it) } ?: emptyMap(); objs[c] = wdt?.let { maidObj0(it) } ?: emptyMap()
            return roots[c]!!.isNotEmpty()
        }
        private fun tileOf(c: Int, tc: Int, tr: Int): Tile? {
            if (tr !in 0..63 || tc !in 0..63) return null
            val key = (c.toLong() shl 32) or MapHarvest.key(tc, tr).toLong()
            return if (cache.containsKey(key)) cache[key]
                else roots[c]?.get(MapHarvest.key(tc, tr))?.let { fid -> read(fid)?.let { tile(it) } }.also { cache[key] = it }
        }
        private fun height(c: Int, gr: Int, gc: Int): Float {
            if (gr < 0 || gc < 0) return Float.NaN
            val t = tileOf(c, gc / TILE_V, gr / TILE_V) ?: return Float.NaN
            return t.h[(gr % TILE_V) * TILE_V + (gc % TILE_V)]
        }
        private fun ground(c: Int, wx: Double, wy: Double): Double = height(c, ((top - wx) / S).toInt(), ((top - wy) / S).toInt()).toDouble()
        private fun feetOf(c: Int, tc: Int, tr: Int): List<Pair<Int, List<Buildings.Foot>>> {
            val key = (c.toLong() shl 32) or MapHarvest.key(tc, tr).toLong()
            tileFeet[key]?.let { return it }
            val obj = objs[c]?.get(MapHarvest.key(tc, tr))?.let { read(it) }
            val ps = obj?.let { WorldBuildings.placements(it) } ?: emptyList()
            val out = ps.map { p ->
                val groups = wmo.getOrPut(p.fid) { read(p.fid)?.let { WorldBuildings.groups(it) } ?: emptyList() }
                p.uid to WorldBuildings.feet(p, groups) { wx, wy -> ground(c, wx, wy) }
            }
            tileWalks[key] = ps.map { p -> p.uid to WorldBuildings.walkways(p, wmo[p.fid] ?: emptyList()) }
            if (tileWalks.size > TILE_CACHE) tileWalks.remove(tileWalks.keys.first())
            tileFeet[key] = out
            return out
        }

        /** zone [rect]'s slope grid and building cover; null when its continent has no terrain */
        fun zone(rect: MapHarvest.Rect): Pair<Grid, Grid>? {
            if (!continent(rect.c)) return null
            val slopes = grid(rect) { gr, gc -> height(rect.c, gr, gc) }
            // every placement on its tiles (a building over two tiles is in both: its unique id once)
            val seen = HashSet<Int>(); val feet = ArrayList<Buildings.Foot>(); val walks = ArrayList<Buildings.Foot>()
            for (k in MapHarvest.tilesUnder(rect) ?: emptyList()) {
                val key = (rect.c.toLong() shl 32) or k.toLong()
                for ((uid, fs) in feetOf(rect.c, MapHarvest.colOf(k), MapHarvest.rowOf(k))) {
                    if (!seen.add(uid)) continue
                    feet.addAll(fs)
                    tileWalks[key]?.firstOrNull { it.first == uid }?.let { walks.addAll(it.second) }
                    if (fs.isNotEmpty() && placed.add((rect.c.toLong() shl 32) or (uid.toLong() and 0xFFFFFFFFL))) rooms += fs.size
                }
            }
            // a WMO's outdoor groups are walked on: level there, whatever the terrain under them (a city's streets, a dock, a bridge)
            lastWalks = walks.size
            val level = buildingGrid(rect, walks)
            for (i in slopes.slope.indices) if ((level.slope[i].toInt() and 0xFF) >= 5) slopes.slope[i] = 0
            return slopes to buildingGrid(rect, feet)
        }
    }
    private const val TILE_CACHE = 160

    private fun writeCoverage(job: ArtHarvest.Job, zones: Int, total: Int, buildings: Int, rooms: Int, bytes: Long, t0: Long) {
        val o = org.json.JSONObject().put("zones", zones).put("total", total).put("buildings", buildings).put("rooms", rooms)
            .put("mb", bytes / 1_000_000).put("s", (System.nanoTime() - t0) / 1_000_000_000)
        job.writeBytes("terrain/coverage.json", o.toString().toByteArray())
    }

    /** the harvest's coverage ({zones, total, buildings, rooms}); null = none yet. Read on the route thread every few seconds while asked
     *  ([coverageNow]); the UI reads only the last one read */
    fun coverage(): org.json.JSONObject? = runCatching { org.json.JSONObject(ArtHarvest.file("terrain/coverage.json").readText()) }.getOrNull()
    @Volatile private var cov: org.json.JSONObject? = null
    @Volatile private var covAt = Long.MIN_VALUE / 2
    fun coverageNow(): org.json.JSONObject? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - covAt >= 3_000L) { covAt = now; RouteWork.run({ coverage() ?: org.json.JSONObject() }) { cov = it } }
        return cov?.takeIf { it.has("total") }
    }
    /** "Terrain 42 of 60 zones · buildings 4,851" (Settings > Performance), or how far it is */
    fun coverageLine(): String {
        val c = coverageNow() ?: return "Not prepared yet: it is made from your game files before Play"
        val z = c.optInt("zones"); val t = c.optInt("total")
        return "Terrain ${z} of ${t} zones · buildings ${"%,d".format(c.optInt("buildings"))}" + (if (z < t) " (preparing)" else "")
    }

    // ---------------------------------------------------------------- read back at runtime

    private val grids = HashMap<Int, Grid?>()
    /** zone [map]'s grid (null until the harvest built it) */
    // (test56's ANRs: kept in memory once read, a missing one remembered a while, never read on the UI thread: RouteWork.Files)
    val files = RouteWork.Files { map -> Grid.read(ArtHarvest.file("terrain/$map.bin")) }
    fun of(map: Int): Grid? = files.get(map)
    /** zone [map]'s building cover (0..9 of a cell's samples a room covers), null until the harvest built it (RouteWork.Files) */
    val buildings = RouteWork.Files { map -> Grid.read(ArtHarvest.file("terrain/$map.bld")) }
    fun buildingsOf(map: Int): Grid? = buildings.get(map)

    // ---------------------------------------------------------------- the walked cells (b)

    /**
     * THE PLAYER'S OWN WALKED GROUND (the user's pick b): every 25 yd world cell the player has stood in (position ticks, current only)
     * on each continent, kept on the device (walked.bin); a walked cell costs [WALKED] of its slope's cost. People walk the roads.
     */
    object Walked {
        const val WALKED = 0.7f
        private val cells = HashSet<Long>()
        private var file: File? = null
        private var dirty = 0
        private var savedAt = 0L
        fun key(cont: Int, wx: Double, wy: Double): Long =
            (cont.toLong() shl 40) or ((floor(wx / CELL_YD).toLong() and 0xFFFFF) shl 20) or (floor(wy / CELL_YD).toLong() and 0xFFFFF)

        @Synchronized fun init(f: File) {
            if (file != null) return
            file = f
            runCatching { DataInputStream(f.inputStream().buffered()).use { i -> repeat(i.readInt()) { cells.add(i.readLong()) } } }
        }
        @Synchronized fun walked(cont: Int, wx: Double, wy: Double): Boolean = key(cont, wx, wy) in cells
        /** one current position: its cell walked; written out now and then */
        @Synchronized fun note(cont: Int, wx: Double, wy: Double, nowMs: Long) {
            if (cont < 0 || wx.isNaN() || wy.isNaN()) return
            if (cells.add(key(cont, wx, wy))) dirty++
            if (dirty > 0 && nowMs - savedAt > 60_000L) save(nowMs)
        }
        @Synchronized private fun save(nowMs: Long) {
            val f = file ?: return
            savedAt = nowMs; dirty = 0
            runCatching {
                f.parentFile?.mkdirs()
                DataOutputStream(f.outputStream().buffered()).use { o -> o.writeInt(cells.size); for (c in cells) o.writeLong(c) }
            }
        }
        @Synchronized fun count() = cells.size
        @Synchronized fun clear() { cells.clear() }
    }
}
