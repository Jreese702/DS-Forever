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

    /**
     * "terrain", in the background: every zone's slope grid (terrain/<uiMap>.bin), the player's zone first (MapHarvestSteps.near),
     * resumable (terrain/made.txt, per game build). Reads the root ADTs only (about 0.5 MB a tile), each tile once while it is near.
     */
    val STEP = object : ArtHarvest.Step {
        override val id = "terrain"
        override val version = 1
        override val beforePlay = false
        override val weight = 60
        override fun run(job: ArtHarvest.Job) {
            val g = job.game
            val rects = MapHarvestSteps.zoneRects(job).filterValues { MapHarvest.tilesUnder(it) != null }
            check(rects.isNotEmpty()) { "the worldmap recipe has no zone rects" }
            val mapRows = (g.db2(MapHarvest.DB_MAP) ?: error("Map.db2 unreadable: ${g.lastReadWhy}")).rows().associateBy { it.id }
            val roots = HashMap<Int, Map<Int, Int>>()
            for (c in rects.values.map { it.c }.toSortedSet()) {
                val wdt = mapRows[c]?.let { g.read(it.int(MapHarvest.MAP_WDT)) } ?: continue
                roots[c] = maidRoot(wdt)
            }
            val made = job.file("terrain/made.txt")
            val done = HashSet<String>()
            runCatching { made.readLines() }.getOrNull()?.let { if (it.firstOrNull() == g.buildKey) done.addAll(it.drop(1)) }
            if (done.isEmpty()) made.writeText(g.buildKey + "\n")
            // the player's zone first, then by distance on its continent, then the rest
            val n = MapHarvestSteps.near
            val nearC = n?.let { rects[it.first]?.c }
            val order = rects.entries.sortedWith(compareBy({ it.value.c != nearC }, { e ->
                if (n == null || e.value.c != nearC) 0.0 else hypot((e.value.t + e.value.b) / 2 - n.second, (e.value.l + e.value.r) / 2 - n.third)
            }))
            val cache = object : LinkedHashMap<Long, Tile?>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Tile?>?) = size > 48
            }
            var built = 0
            for ((i, e) in order.withIndex()) {
                val (map, rect) = e.key to e.value
                if (map.toString() in done) continue
                val root = roots[rect.c] ?: continue
                val grid = grid(rect) { gr, gc ->
                    val tr = gr / TILE_V; val tc = gc / TILE_V
                    if (tr !in 0..63 || tc !in 0..63) return@grid Float.NaN
                    val key = (rect.c.toLong() shl 32) or MapHarvest.key(tc, tr).toLong()
                    val tile = if (cache.containsKey(key)) cache[key] else root[MapHarvest.key(tc, tr)]?.let { fid -> g.read(fid)?.let { tile(it) } }.also { cache[key] = it }
                    tile?.h?.get((gr % TILE_V) * TILE_V + (gc % TILE_V)) ?: Float.NaN
                }
                grid.write(job.file("terrain/$map.bin"))
                made.appendText("$map\n"); done.add(map.toString()); built++
                job.progress(i + 1, order.size, "")
            }
            android.util.Log.i("WCMAP", "terrain: $built zone slope grids built (${done.size} of ${order.size})")
        }
    }

    // ---------------------------------------------------------------- read back at runtime

    private val grids = HashMap<Int, Grid?>()
    /** zone [map]'s grid (null until the harvest built it) */
    // (test56's ANRs: kept in memory once read, a missing one remembered a while, never read on the UI thread: RouteWork.Files)
    val files = RouteWork.Files { map -> Grid.read(ArtHarvest.file("terrain/$map.bin")) }
    fun of(map: Int): Grid? = files.get(map)

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
