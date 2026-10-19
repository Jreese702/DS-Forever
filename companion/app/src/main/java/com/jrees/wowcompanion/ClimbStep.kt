package com.jrees.wowcompanion

import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * "climb" (SafePath v2 routing; E:/safepath-work/docs/DEVICE_INTEGRATION_PLAN.md section 2): each zone's ground lattice
 * (terrain/<uiMap>.clb, CLB3: the steps, the game's own faces, the 45-48 degree short steps, the floors a walker is on and the rooms no
 * line cuts through) made on the device from the player's own game files by the very code the off-device harness runs (ZoneInputs,
 * Climb.build). Nothing about a zone is typed into the app.
 *
 * Only while Settings > Map > "SafePath v2 routing" is On (default Off). DETACHED from the harvest: its own low-priority thread, never
 * before Play. TIER 0 FIRST: the zone the player is in (MapHarvestSteps.near) and the zones whose rect, 450 yd wider, meets it; then a
 * zone a route asks for ([want]); then the rest of that continent, nearest first, pausing while the player walks. RESUMABLE per zone
 * (terrain/climb_made.txt lists the zones made); INVALIDATED as a whole when the game build or the rules ([Climb.RULES_VERSION]) or
 * this step's [version] change (the list's header), so a lattice is never read against other rules (CLB3 says its rules too).
 */
object ClimbStep : ArtHarvest.Step {
    override val id = "climb"
    override val version = 1
    override val beforePlay = false
    override val weight = 10
    override val detached = true
    override fun begin(ctx: Context) = start(ctx)
    override fun run(job: ArtHarvest.Job) = start(job.ctx)

    const val MADE = "terrain/climb_made.txt"
    /** the zones a route asked for, first after tier 0 */
    private val wanted = LinkedHashSet<Int>()
    @Volatile private var thread: Thread? = null
    @Volatile private var again = false
    @Volatile private var appCtx: Context? = null
    /** bumped as each zone's lattice is written (a route that found none plans again) */
    @Volatile var zonesMade = 0; private set
    /** what it did last (Settings, the problem report): "3 of 8 zones near you made, ..." */
    @Volatile var line: String = "Off"; private set

    /** a route needs zone [map]'s lattice: made next (the setting On) */
    fun want(ctx: Context, map: Int) {
        synchronized(wanted) { if (!wanted.add(map)) return }
        start(ctx)
    }
    /** Settings turned SafePath v2 routing On */
    fun settingChanged(ctx: Context) { if (Settings.safePathV2) start(ctx) }

    fun start(context: Context) = synchronized(this) {
        val ctx = context.applicationContext
        appCtx = ctx
        if (thread != null) { again = true; return }
        thread = Thread({
            try {
                do { again = false; runCatching { body(ctx) }.onFailure { android.util.Log.w("WCMAP", "climb: ${it.message}", it); line = "Failed: ${it.message}" } }
                while (synchronized(this) { again.also { if (!it) thread = null } })
            } finally { synchronized(this) { if (thread === Thread.currentThread()) thread = null } }
        }, "safepath-climb").apply { priority = Thread.MIN_PRIORITY; isDaemon = true; start() }
    }

    /** the zones to make, in order: tier 0 (the player's zone and its neighbours), the asked-for ones, then the rest of the continent */
    fun order(rects: Map<Int, MapHarvest.Rect>, near: Triple<Int, Double, Double>?, asked: Collection<Int>): List<Int> {
        val here = near?.first?.let { rects[it] }
        fun meets(a: MapHarvest.Rect, b: MapHarvest.Rect, pad: Double) = a.c == b.c &&
            a.b - pad <= b.t && a.t + pad >= b.b && a.r - pad <= b.l && a.l + pad >= b.r
        val tier0 = if (here == null) emptyList() else listOf(near.first) +
            rects.filter { (m, r) -> m != near.first && meets(here, r, SafePath.GRID_PAD_YD) }.keys.sortedBy { m -> rects[m]!!.let { r ->
                hypot((r.t + r.b) / 2 - near.second, (r.l + r.r) / 2 - near.third) } }
        val rest = if (here == null) emptyList() else rects.filter { (m, r) -> r.c == here.c && m !in tier0 }.keys.sortedBy { m -> rects[m]!!.let { r ->
            hypot((r.t + r.b) / 2 - near.second, (r.l + r.r) / 2 - near.third) } }
        return (tier0 + asked.filter { it in rects } + rest).distinct()
    }

    /** how many zones of tier 0 to make before the rest waits for the player standing still */
    const val TIER0_MAX = 12

    private fun body(ctx: Context) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        Settings.init(ctx); ArtHarvest.init(ctx)
        if (!Settings.safePathV2) { line = "Off"; return }
        val rects = rects(ctx)
        val game = GameFiles.open(ctx) ?: run { line = "Waiting: ${GameFiles.lastWhy ?: "the game files could not be read"}"; return }
        game.use { g ->
            val made = File(ArtHarvest.rootDir ?: return, MADE).also { it.parentFile?.mkdirs() }
            val head = "${g.buildKey} rules ${Climb.RULES_VERSION} v$version"
            val done = HashSet<Int>()
            runCatching { made.readLines() }.getOrNull()?.let { ls -> if (ls.firstOrNull() == head) ls.drop(1).mapNotNullTo(done) { it.trim().toIntOrNull() } }
            if (done.isEmpty()) made.writeText(head + "\n")
            val mapRows = (g.db2(MapHarvest.DB_MAP) ?: error("Map.db2 unreadable: ${g.lastReadWhy}")).rows().associateBy { it.id }
            if (Terrain.lethal.isEmpty()) g.db2(Terrain.DB_LIQUID_TYPE)?.rows(strings = setOf(0))?.associate { it.id to it.str(0) }?.let { Terrain.lethal = Terrain.lethalLiquids(it) }
            val capitals = CityExits.CITIES.mapNotNull { c -> rects[c]?.let { r -> doubleArrayOf(r.t, r.b, r.l, r.r) } }
            var tier0Left = TIER0_MAX
            // what the device has now for the zones near the player (the WCMAP log: SafePathDiag)
            SafePathDiag.coverage(ArtHarvest.rootDir, order(rects, MapHarvestSteps.near, emptyList()).take(TIER0_MAX))
            while (true) {
                if (!Settings.safePathV2) { line = "Off"; return }
                val asked = synchronized(wanted) { wanted.toList() }
                val todo = order(rects, MapHarvestSteps.near, asked).filter { it !in done }
                val map = todo.firstOrNull() ?: break
                val isTier0 = tier0Left > 0 || map in asked
                // past tier 0 and what routes asked for: only while the player stands still
                if (!isTier0) { var waited = 0; while (IndoorSchedulerStep.playerMoving() && waited < 600) { Thread.sleep(1000); waited++ } }
                val rect = rects[map]!!
                val wdt = mapRows[rect.c]?.let { g.read(it.int(MapHarvest.MAP_WDT)) }
                val t0 = System.nanoTime()
                val grid = wdt?.let { make(GameSource(g, rect, Terrain.maidRoot(it), Terrain.maidObj0(it)), rect, Terrain.lethal, capitals) }
                if (grid != null) {
                    grid.write(File(ArtHarvest.rootDir, "terrain/$map.clb"))
                    Climb.files.drop(map)
                }
                zonesMade++
                made.appendText("$map\n"); done.add(map)
                synchronized(wanted) { wanted.remove(map) }
                tier0Left--
                val ms = (System.nanoTime() - t0) / 1_000_000
                line = "${done.size} of ${rects.count { it.value.c == rect.c }} zones on this continent; last: $map in ${ms / 1000.0} s"
                SafePathDiag.harvestStep(id, map, ms, why = if (grid == null) "no terrain" else null)
                android.util.Log.i("WCMAP", "climb: zone $map ${if (grid == null) "has no terrain" else "${grid.nx} x ${grid.ny} vertices, ${grid.rooms?.size ?: 0} rooms"} in $ms ms")
            }
            line = "Ready: ${done.size} zones"
            SafePathDiag.coverage(ArtHarvest.rootDir, order(rects, MapHarvestSteps.near, emptyList()).take(TIER0_MAX), force = true)
        }
    }

    /**
     * zone [rect]'s lattice from its files ([src]), as the harness makes it (ZoneInputs.Lattice: the covered holes level, the cells'
     * middles, the walkways' floors at their own height, the rooms no line cuts through) - with the rooms kept by tile for the file
     */
    fun make(src: ZoneInputs.Source, rect: MapHarvest.Rect, lethal: Set<Int>, capitals: List<DoubleArray>): Climb.Grid {
        val zi = ZoneInputs(src, rect, lethal, capitals)
        val zl = zi.Lattice(zi.heightV(Double.NaN))
        return zl.cg.also { it.rooms = zl.rooms() }
    }

    private fun rects(ctx: Context): Map<Int, MapHarvest.Rect> {
        val o = runCatching { ctx.assets.open("harvest/worldmap.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } }.getOrNull()
            ?.optJSONObject("rects") ?: return emptyMap()
        val out = LinkedHashMap<Int, MapHarvest.Rect>()
        for (k in o.keys()) { val a = o.optJSONArray(k) ?: continue; if (a.length() < 5) continue
            out[k.toIntOrNull() ?: continue] = MapHarvest.Rect(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optDouble(4)) }
        return out.filterValues { MapHarvest.tilesUnder(it) != null }
    }

    /**
     * a zone's files from the game by fileDataID: its continent's WDT's root and obj0 ADTs ([roots], [objs]: tile key -> fileDataID), the
     * WMO roots and groups (a root's GFID order) and the doodads' models, each kept while near (the zone reads them again and again)
     */
    class GameSource(private val g: GameFiles, rect: MapHarvest.Rect, private val roots: Map<Int, Int>, private val objs: Map<Int, Int>) : ZoneInputs.Source {
        private fun <K, V> lru(n: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > n }
        private val adts = lru<Int, ByteArray?>(24); private val obj = lru<Int, ByteArray?>(24)
        private val wmos = lru<Int, ByteArray?>(96); private val groups = lru<Long, ByteArray?>(64); private val m2s = lru<Int, ByteArray?>(64)
        private val pad = SafePath.GRID_PAD_YD
        private val tiles: List<IntArray> = run {
            val org = 32 * MapHarvest.ADT_YD
            val c0 = Math.floor((org - (rect.l + pad)) / MapHarvest.ADT_YD).toInt().coerceIn(0, 63); val c1 = Math.floor((org - (rect.r - pad)) / MapHarvest.ADT_YD).toInt().coerceIn(0, 63)
            val r0 = Math.floor((org - (rect.t + pad)) / MapHarvest.ADT_YD).toInt().coerceIn(0, 63); val r1 = Math.floor((org - (rect.b - pad)) / MapHarvest.ADT_YD).toInt().coerceIn(0, 63)
            (c0..c1).flatMap { c -> (r0..r1).map { r -> intArrayOf(c, r) } }.filter { objs.containsKey(MapHarvest.key(it[0], it[1])) }
        }
        override fun adt(c: Int, r: Int): ByteArray? { if (c !in 0..63 || r !in 0..63) return null; val k = MapHarvest.key(c, r)
            return if (adts.containsKey(k)) adts[k] else roots[k]?.let { g.read(it) }.also { adts[k] = it } }
        override fun obj0(c: Int, r: Int): ByteArray? { if (c !in 0..63 || r !in 0..63) return null; val k = MapHarvest.key(c, r)
            return if (obj.containsKey(k)) obj[k] else objs[k]?.let { g.read(it) }.also { obj[k] = it } }
        override fun wmo(fid: Int): ByteArray? = if (wmos.containsKey(fid)) wmos[fid] else g.read(fid).also { wmos[fid] = it }
        override fun wmoGroup(fid: Int, index: Int): ByteArray? {
            val k = (fid.toLong() shl 16) or index.toLong()
            if (groups.containsKey(k)) return groups[k]
            val gf = wmo(fid)?.let { WorldBuildings.groupFiles(it) } ?: return null
            return gf.getOrNull(index)?.takeIf { it != 0 }?.let { g.read(it) }.also { groups[k] = it }
        }
        override fun m2(fid: Int): ByteArray? = if (m2s.containsKey(fid)) m2s[fid] else g.read(fid).also { m2s[fid] = it }
        override fun objTiles(): List<IntArray> = tiles
    }
}
