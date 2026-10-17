package com.jrees.wowcompanion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * LEARNED ROAD TEXTURES (b-route-roads; the user: "Yes, build it"). The game names no roads, and its ground-effect table knows too
 * few of the layers to tell them apart (the a2 probe, Elwynn: 90% of road layers and 75% of the rest unknown to it). So the device
 * LEARNS them: the art harvest's [STEP] reads each zone's tex0 ADTs and keeps, per 25 yd cell, the ground texture that shows most
 * (groundtex/<uiMap>.bin: fileDataIDs only, built on the device, never shipped); [learn] then finds the textures that cover the
 * player's walked cells (Terrain.Walked) far more often than they cover those zones as a whole - the roads they walk - and every
 * cell where one of those shows most, walked or not, costs [ROAD] of its ground in the route.
 */
object Roads {
    const val CELL_YD = 25.0
    /** a road cell's share of its ground's cost */
    const val ROAD = 0.6f
    /** a texture is a road when it covers this many walked cells at least ... */
    const val MIN_WALKED = 12
    /** ... and its share of the walked cells is this many times its share of the zones' cells */
    const val LIFT = 2.5

    // ---------------------------------------------------------------- one zone's grid: the dominant texture per cell

    class Grid(val c: Int, val t: Double, val b: Double, val l: Double, val r: Double, val nx: Int, val ny: Int, val tex: IntArray) {
        /** the texture showing most at world ([wx], [wy]), 0 none / off the grid */
        fun at(wx: Double, wy: Double): Int {
            val i = floor((l - wy) / (l - r) * nx).toInt(); val j = floor((t - wx) / (t - b) * ny).toInt()
            return if (i in 0 until nx && j in 0 until ny) tex[j * nx + i] else 0
        }
        /** a cell's middle in world yards: (wx, wy) */
        fun centre(i: Int, j: Int) = doubleArrayOf(t - (j + 0.5) * (t - b) / ny, l - (i + 0.5) * (l - r) / nx)

        fun write(f: File) {
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC); o.writeInt(c); o.writeDouble(t); o.writeDouble(b); o.writeDouble(l); o.writeDouble(r); o.writeInt(nx); o.writeInt(ny)
                for (v in tex) o.writeInt(v)
            }
        }
        companion object {
            const val MAGIC = 0x47545831                       // "GTX1"
            fun read(f: File): Grid? = runCatching {
                DataInputStream(f.inputStream().buffered()).use { i ->
                    if (i.readInt() != MAGIC) return null
                    val c = i.readInt()
                    val t = i.readDouble(); val b = i.readDouble(); val l = i.readDouble(); val r = i.readDouble()
                    val nx = i.readInt(); val ny = i.readInt()
                    if (nx !in 1..4096 || ny !in 1..4096) return null
                    Grid(c, t, b, l, r, nx, ny, IntArray(nx * ny) { i.readInt() })
                }
            }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- the game's ground textures (tex0 ADTs)

    /** one chunk's layers: their textures (fileDataIDs) and alpha maps (64 x 64, layer 0 none) */
    class Chunk(val tex: IntArray, val alpha: Array<ByteArray?>)
    /** one tex0 tile's 16 x 16 chunks (null where a chunk has no layers) */
    class Tile(val chunks: Array<Chunk?>)

    /**
     * a tex0 ADT ([b]): MDID (the textures' fileDataIDs), then per MCNK its MCLY (16 bytes a layer: texture index, flags, the
     * alpha's offset in MCAL, the ground effect) and MCAL (per layer after the first: 4096 bytes, or 2048 at 4 bits, or RLE when
     * flag 0x200 is set). Chunks come in order, row by row.
     */
    fun tile(b: ByteArray): Tile? {
        var mdid = IntArray(0)
        val chunks = arrayOfNulls<Chunk>(256)
        var o = 0; var idx = 0
        while (o + 8 <= b.size) {
            val tag = tag(b, o); val n = Db2.le32(b, o + 4)
            if (n < 0 || o + 8 + n > b.size) break
            when (tag) {
                "MDID" -> mdid = IntArray(n / 4) { Db2.le32(b, o + 8 + it * 4) }
                "MCNK" -> {
                    if (idx < 256) chunks[idx] = chunk(b, o + 8, o + 8 + n, mdid)
                    idx++
                }
            }
            o += 8 + n
        }
        return if (idx > 0) Tile(chunks) else null
    }

    private fun chunk(b: ByteArray, from: Int, to: Int, mdid: IntArray): Chunk? {
        var mcly = -1; var mclyN = 0; var mcal = -1; var mcalN = 0
        var p = from
        while (p + 8 <= to) {
            val t = tag(b, p); val n = Db2.le32(b, p + 4)
            if (n < 0 || p + 8 + n > to) break
            if (t == "MCLY") { mcly = p + 8; mclyN = n }
            if (t == "MCAL") { mcal = p + 8; mcalN = n }
            p += 8 + n
        }
        if (mcly < 0 || mclyN < 16) return null
        val layers = mclyN / 16
        val tex = IntArray(layers) { k -> Db2.le32(b, mcly + k * 16).let { i -> if (i in mdid.indices) mdid[i] else 0 } }
        val alpha = arrayOfNulls<ByteArray>(layers)
        for (k in 1 until layers) {
            if (mcal < 0) break
            val flags = Db2.le32(b, mcly + k * 16 + 4)
            if (flags and 0x100 == 0) continue                  // the layer uses no alpha map
            val ofs = Db2.le32(b, mcly + k * 16 + 8)
            val next = if (k + 1 < layers) Db2.le32(b, mcly + (k + 1) * 16 + 8) else mcalN
            if (ofs < 0 || ofs >= mcalN) continue
            alpha[k] = decodeAlpha(b, mcal + ofs, minOf(mcalN, max(next, ofs)) - ofs, flags and 0x200 != 0)
        }
        return Chunk(tex, alpha)
    }

    /** an alpha map: RLE (compressed), 8 bits (4096 bytes) or 4 bits (2048 bytes) -> 4096 values 0..255 */
    fun decodeAlpha(b: ByteArray, at: Int, len: Int, compressed: Boolean): ByteArray {
        val out = ByteArray(4096)
        if (compressed) {
            var i = at; var w = 0
            while (w < 4096 && i < at + len && i < b.size) {
                val c = b[i++].toInt() and 0xFF
                val count = c and 0x7F
                if (c and 0x80 != 0) { val v = if (i < b.size) b[i++] else 0; repeat(count) { if (w < 4096) out[w++] = v } }
                else repeat(count) { if (w < 4096 && i < b.size) out[w++] = b[i++] }
            }
        } else if (len >= 4096) System.arraycopy(b, at, out, 0, 4096)
        else if (len >= 2048) for (k in 0 until 2048) {
            val v = b[at + k].toInt() and 0xFF
            out[2 * k] = ((v and 0x0F) * 17).toByte(); out[2 * k + 1] = ((v shr 4) * 17).toByte()
        }
        return out
    }

    /** the texture showing at a chunk's alpha pixel ([px], [py] 0..63): each layer laid over the ones before it */
    fun showing(c: Chunk, px: Int, py: Int): Int {
        var best = 0; var bestW = -1f
        for (k in c.tex.indices) {
            var w = if (k == 0) 1f else ((c.alpha[k]?.get(py * 64 + px)?.toInt() ?: 0) and 0xFF) / 255f
            for (m in k + 1 until c.tex.size) w *= 1f - ((c.alpha[m]?.get(py * 64 + px)?.toInt() ?: 0) and 0xFF) / 255f
            if (w > bestW) { bestW = w; best = c.tex[k] }
        }
        return best
    }

    private fun tag(b: ByteArray, o: Int) = String(charArrayOf(b[o + 3].toInt().toChar(), b[o + 2].toInt().toChar(), b[o + 1].toInt().toChar(), b[o].toInt().toChar()))

    /** the tex0 ADTs of a continent's WDT (MAID's 4th uint32 of each 32-byte row), by MapHarvest.key(col, row) */
    fun maidTex0(wdt: ByteArray): Map<Int, Int> {
        var o = 0
        while (o + 8 <= wdt.size) {
            val n = Db2.le32(wdt, o + 4)
            if (n < 0 || o + 8 + n > wdt.size) break
            if (tag(wdt, o) == "MAID") {
                val out = HashMap<Int, Int>()
                for (i in 0 until n / 32) { val fid = Db2.le32(wdt, o + 8 + i * 32 + 12); if (fid != 0) out[MapHarvest.key(i % 64, i / 64)] = fid }
                return out
            }
            o += 8 + n
        }
        return emptyMap()
    }

    private const val CHUNK_YD = 100.0 / 3.0
    /**
     * a zone's grid: per 25 yd cell of [rect], the texture showing most over 3 x 3 points in it; [tileAt] = the tex0 tile (col, row)
     * of the continent, null where none
     */
    fun grid(rect: MapHarvest.Rect, tileAt: (Int, Int) -> Tile?): Grid {
        val nx = max(1, ((rect.l - rect.r) / CELL_YD).toInt()); val ny = max(1, ((rect.t - rect.b) / CELL_YD).toInt())
        val cw = (rect.l - rect.r) / nx; val ch = (rect.t - rect.b) / ny
        val top = 32.0 * MapHarvest.ADT_YD
        val out = IntArray(nx * ny)
        val count = HashMap<Int, Int>()
        for (j in 0 until ny) for (i in 0 until nx) {
            count.clear()
            for (sj in 0 until 3) for (si in 0 until 3) {
                val wx = rect.t - (j + (sj + 0.5) / 3) * ch; val wy = rect.l - (i + (si + 0.5) / 3) * cw
                val gx = (top - wx) / MapHarvest.ADT_YD; val gy = (top - wy) / MapHarvest.ADT_YD   // tile row / col, fractional
                val row = floor(gx).toInt(); val col = floor(gy).toInt()
                val t = tileAt(col, row) ?: continue
                val inTileX = (gx - row) * MapHarvest.ADT_YD; val inTileY = (gy - col) * MapHarvest.ADT_YD
                val cy = (inTileX / CHUNK_YD).toInt().coerceIn(0, 15); val cx = (inTileY / CHUNK_YD).toInt().coerceIn(0, 15)
                val c = t.chunks[cy * 16 + cx] ?: continue
                val py = ((inTileX - cy * CHUNK_YD) / CHUNK_YD * 64).toInt().coerceIn(0, 63)
                val px = ((inTileY - cx * CHUNK_YD) / CHUNK_YD * 64).toInt().coerceIn(0, 63)
                val tx = showing(c, px, py)
                if (tx != 0) count[tx] = (count[tx] ?: 0) + 1
            }
            out[j * nx + i] = count.maxByOrNull { it.value }?.key ?: 0
        }
        return Grid(rect.c, rect.t, rect.b, rect.l, rect.r, nx, ny, out)
    }

    // ---------------------------------------------------------------- the harvest step

    /**
     * "groundtex", in the background: every zone's texture grid (groundtex/<uiMap>.bin), the player's zone first, resumable per
     * game build; the heavier tex0 read (about 1.3 MB a tile) WAITS while the player walks (the interiors bake's own gate).
     */
    val STEP = object : ArtHarvest.Step {
        override val id = "groundtex"
        override val version = 1
        override val beforePlay = false
        override val weight = 80
        override fun run(job: ArtHarvest.Job) {
            val g = job.game
            val rects = MapHarvestSteps.zoneRects(job).filterValues { MapHarvest.tilesUnder(it) != null }
            check(rects.isNotEmpty()) { "the worldmap recipe has no zone rects" }
            val mapRows = (g.db2(MapHarvest.DB_MAP) ?: error("Map.db2 unreadable: ${g.lastReadWhy}")).rows().associateBy { it.id }
            val tex0 = HashMap<Int, Map<Int, Int>>()
            for (c in rects.values.map { it.c }.toSortedSet()) mapRows[c]?.let { g.read(it.int(MapHarvest.MAP_WDT)) }?.let { tex0[c] = maidTex0(it) }
            val made = job.file("groundtex/made.txt")
            val done = HashSet<String>()
            runCatching { made.readLines() }.getOrNull()?.let { if (it.firstOrNull() == g.buildKey) done.addAll(it.drop(1)) }
            if (done.isEmpty()) made.writeText(g.buildKey + "\n")
            val n = MapHarvestSteps.near
            val nearC = n?.let { rects[it.first]?.c }
            val order = rects.entries.sortedWith(compareBy({ it.value.c != nearC }, { e ->
                if (n == null || e.value.c != nearC) 0.0 else hypot((e.value.t + e.value.b) / 2 - n.second, (e.value.l + e.value.r) / 2 - n.third)
            }))
            val cache = object : LinkedHashMap<Long, Tile?>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Tile?>?) = size > 12
            }
            var built = 0
            for ((i, e) in order.withIndex()) {
                val (map, rect) = e.key to e.value
                if (map.toString() in done) continue
                val maid = tex0[rect.c] ?: continue
                val grid = grid(rect) { col, row ->
                    if (col !in 0..63 || row !in 0..63) return@grid null
                    val key = (rect.c.toLong() shl 32) or MapHarvest.key(col, row).toLong()
                    if (cache.containsKey(key)) return@grid cache[key]
                    // walking: wait (the game's frames come first), then read
                    var waited = 0
                    while (IndoorSchedulerStep.playerMoving() && waited < 600) { Thread.sleep(1000); waited++ }
                    maid[MapHarvest.key(col, row)]?.let { fid -> g.read(fid)?.let { tile(it) } }.also { cache[key] = it }
                }
                grid.write(job.file("groundtex/$map.bin"))
                made.appendText("$map\n"); done.add(map.toString()); built++
                job.progress(i + 1, order.size, "")
                Roads.version++
            }
            android.util.Log.i("WCMAP", "roads: $built zone texture grids built (${done.size} of ${order.size})")
        }
    }

    // ---------------------------------------------------------------- learning, and the road test at runtime

    /** a change counter: a grid was built or the roads were learned again */
    @Volatile var version = 0; private set
    @Volatile private var roads: Set<Int> = emptySet()
    @Volatile private var learnedAtWalked = -1
    private val grids = HashMap<Int, Grid?>()

    /** zone [map]'s texture grid (null until the harvest built it) */
    // (test56's ANRs: a missing grid was opened again for every cell of the route's grid, on the UI thread, under this object's lock;
    // now each zone's grid is read once off the UI thread and kept, a missing one remembered a while: RouteWork.Files)
    val files = RouteWork.Files { map -> Grid.read(ArtHarvest.file("groundtex/$map.bin")) }
    fun of(map: Int): Grid? = files.get(map)

    /** the texture at world ([wx], [wy]) of [map] is a learned road */
    fun isRoad(map: Int, wx: Double, wy: Double): Boolean { val g = of(map) ?: return false; val t = g.at(wx, wy); return t != 0 && t in roads }

    /**
     * which textures are roads: over the zones with walked cells ([zones]: each zone's grid and its continent), a texture is a road
     * when it shows in at least [MIN_WALKED] walked cells and its share of the walked cells is [LIFT] times its share of all their
     * cells. Pure: the grids and the walked test in, the roads out (with the walked cells counted).
     */
    fun learn(zones: List<Pair<Grid, Int>>, walked: (Int, Double, Double) -> Boolean): Pair<Set<Int>, Int> {
        val all = HashMap<Int, Int>(); val onWalked = HashMap<Int, Int>()
        var nAll = 0; var nWalked = 0
        for ((g, cont) in zones) {
            var any = false
            val local = HashMap<Int, Int>(); val localWalked = HashMap<Int, Int>(); var lw = 0
            for (j in 0 until g.ny) for (i in 0 until g.nx) {
                val t = g.tex[j * g.nx + i]; if (t == 0) continue
                local[t] = (local[t] ?: 0) + 1
                val c = g.centre(i, j)
                if (walked(cont, c[0], c[1])) { localWalked[t] = (localWalked[t] ?: 0) + 1; lw++; any = true }
            }
            if (!any) continue
            for ((t, k) in local) { all[t] = (all[t] ?: 0) + k; nAll += k }
            for ((t, k) in localWalked) onWalked[t] = (onWalked[t] ?: 0) + k
            nWalked += lw
        }
        if (nWalked == 0 || nAll == 0) return emptySet<Int>() to 0
        val out = onWalked.filter { (t, k) -> k >= MIN_WALKED && (k.toDouble() / nWalked) >= LIFT * ((all[t] ?: 0).toDouble() / nAll) }.keys
        return out to nWalked
    }

    /** learn again in the background when the walked cells grew by [every]; [onLearned] on the main thread with the log line said */
    fun relearn(every: Int, onLearned: () -> Unit) {
        val w = Terrain.Walked.count()
        if (learnedAtWalked >= 0 && w - learnedAtWalked < every) return
        learnedAtWalked = w
        Thread {
            val dir = ArtHarvest.file("groundtex")
            val zones = (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
                val map = f.name.removeSuffix(".bin").toIntOrNull() ?: return@mapNotNull null
                val g = of(map) ?: return@mapNotNull null
                g to g.c
            }
            val (r, n) = learn(zones) { c, wx, wy -> Terrain.Walked.walked(c, wx, wy) }
            roads = r; version++
            android.util.Log.i("WCMAP", "roads: learned ${r.size} textures from $n walked cells")
            android.os.Handler(android.os.Looper.getMainLooper()).post(onLearned)
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** how many cells of [map]'s grid are roads now */
    fun roadCells(map: Int): Int = of(map)?.tex?.count { it != 0 && it in roads } ?: 0
}
