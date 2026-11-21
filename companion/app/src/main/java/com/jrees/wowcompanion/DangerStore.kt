package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * THE DANGER LAYER'S DATA (b-danger-layer): QuestieDB's hostile spawns (QuestieNpcs, read from the toc the app installed) filed by
 * the uiMap they are on, through the game's own area -> map table (UiMapAssignment, written by the art harvest's [AREAS_STEP] as
 * harvest/uimap_area.json; measured on the PC client: one map per area, Elwynn 12 -> 1429, Westfall 40 -> 1436, Stormwind 1519 ->
 * 1453). Read once in the background (about 60,000 spawns; 160 ms on the PC), again when QuestieDB or the table changes; until then
 * no danger (the route as before).
 */
object DangerStore {
    @Volatile private var byMap: Map<Int, List<QuestieNpcs.Spawn>> = emptyMap()
    @Volatile private var stamp = ""
    @Volatile private var loading = false
    private var checkedAt = 0L
    @Volatile private var lastNote = ""

    /** the hostile spawns on uiMap [map] (empty until read; a map let go under pressure is read again: [dropped]) */
    fun spawns(map: Int): List<QuestieNpcs.Spawn> {
        asked(map)
        byMap[map]?.let { return it }
        if (map in dropped) wantAgain = true                       // the next [check] reads QuestieDB again
        return emptyList()
    }

    // ---------------------------------------------------------------- memory work #56 (D2, the user's yes 2026-11-19)
    /**
     * QUESTIEDB'S SPAWNS UNDER PRESSURE: about 60,000 spawns for every map (5 - 10 MB), kept all session and in no memory line. Now
     * counted ([bytes], "danger") and, at Android's running-low or worse (MemWatch's trims and reliefs), only the maps asked for last
     * ([recent]: the player's zone and the few a route or the map drew) stay; a map let go is read again when it is asked for - by the
     * first [check] [REREAD_AFTER_MS] after the trim (never at once: the trims come while the game loads, and a read is a spike of its
     * own). The trim does not bump [version]: the routes' danger grids already built stay as they are. Any thread.
     */
    const val KEEP_MAPS = 8
    const val REREAD_AFTER_MS = 60_000L
    @Volatile private var droppedAt = 0L
    private val recent = LinkedHashSet<Int>()
    @Volatile private var dropped: Set<Int> = emptySet()
    @Volatile private var wantAgain = false
    @Volatile private var watched = false
    private fun asked(map: Int) = synchronized(recent) { recent.remove(map); recent.add(map); while (recent.size > KEEP_MAPS) recent.remove(recent.first()) }
    /** the maps a trim at [level] keeps of [have] ([recentMaps] the newest last): null = nothing goes (below running-low). Pure */
    fun keepAt(level: Int, have: Set<Int>, recentMaps: List<Int>): Set<Int>? =
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) null
        else if (ShippedNets.severe(level)) emptySet() else recentMaps.filter { it in have }.toSet()
    /** a map let go at [at] may be read again at [now] (pure) */
    fun rereadDue(now: Long, at: Long): Boolean = now - at >= REREAD_AFTER_MS
    /** a spawn's bytes in memory (the object, its two strings), estimated */
    const val SPAWN_BYTES = 120L
    fun bytes(): Long = byMap.values.sumOf { it.size * SPAWN_BYTES + 16L }
    /** the trim: the bytes let go */
    fun letGo(level: Int): Long {
        val have = byMap
        val keep = keepAt(level, have.keys, synchronized(recent) { recent.toList() }) ?: return 0L
        val gone = have.filterKeys { it !in keep }
        if (gone.isEmpty()) return 0L
        byMap = have.filterKeys { it in keep }
        dropped = dropped + gone.keys
        droppedAt = runCatching { android.os.SystemClock.elapsedRealtime() }.getOrDefault(0L)
        return gone.values.sumOf { it.size * SPAWN_BYTES + 16L }
    }
    private fun watch() {
        if (watched) return
        watched = true
        MemWatch.source("danger") { bytes() }
        MemWatch.onTrim("danger") { level -> letGo(level) }
    }

    /** the off-device renders: [spawns] on uiMap [map], as if read */
    fun seed(map: Int, spawns: List<QuestieNpcs.Spawn>) { byMap = byMap + (map to spawns); version++ }

    /** a change counter for the grids built from it */
    @Volatile var version = 0; private set

    /** ask now and then (cheap): reads again in the background when QuestieDB or the area table changed */
    fun check(ctx: Context, onReady: () -> Unit) {
        watch()
        // Light 8 (LIGHT8_PLAN.md change 4): while the game loads the re-read waits (LoadHold: at most 90 s), then runs; off Light 8 never
        if (LoadHold.hold(LoadHold.DANGER) { check(ctx, onReady) }) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (loading || now - checkedAt < 10_000L) return
        if (wantAgain && rereadDue(now, droppedAt)) { wantAgain = false; stamp = "" }   // a map let go under pressure was asked for: read again
        checkedAt = now
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                val addons = File(WowProduct.flavorDir(GameFiles.gameRoot(app)), "Interface/AddOns")
                val toc = QuestieNpcs.toc(addons)
                val areas = ArtHarvest.file(AREAS_FILE)
                val st = "${toc?.path}:${toc?.lastModified()}:${toc?.length()}:${areas.lastModified()}:${areas.length()}"
                if (toc == null || !areas.isFile) {
                    val m = "danger: no data yet (QuestieDB ${toc != null}, area table ${areas.isFile})"
                    if (m != lastNote) { lastNote = m; android.util.Log.i("WCMAP", m) }
                }
                else if (st != stamp) {
                    val t0 = System.nanoTime()
                    val map = parseAreas(JSONObject(areas.readText()))
                    val spawns = QuestieNpcs.read(toc)
                    byMap = spawns.groupBy { map[it.area] ?: 0 }.filterKeys { it != 0 }
                    dropped = emptySet()
                    stamp = st; version++
                    android.util.Log.i("WCMAP", "danger: ${spawns.size} hostile spawns on ${byMap.size} maps read in " +
                        "${(System.nanoTime() - t0) / 1_000_000} ms")
                    android.os.Handler(android.os.Looper.getMainLooper()).post(onReady)
                }
            } catch (t: Throwable) {
                android.util.Log.w("WCMAP", "danger: not read: ${t.message}")
            } finally { loading = false }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** {"<area>": uiMap, ...} -> the map */
    fun parseAreas(o: JSONObject): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (k in o.keys()) { val a = k.toIntOrNull() ?: continue; val m = o.optInt(k, 0); if (a != 0 && m != 0) out[a] = m }
        return out
    }

    const val AREAS_FILE = "uimap_area.json"
    const val DB_UIMAP_ASSIGNMENT = 1957219

    /**
     * "areas", in the background: the game's UiMapAssignment as area -> uiMap (fields, measured on the PC client: 4 the uiMap, 7 the
     * area; area 0 is the continents' own rows and is left out; an area named twice keeps its first)
     */
    val AREAS_STEP = object : ArtHarvest.Step {
        override val id = "areas"
        override val version = 1
        override val beforePlay = false
        override val weight = 1
        override fun run(job: ArtHarvest.Job) {
            val t = job.game.db2(DB_UIMAP_ASSIGNMENT) ?: error(job.game.lastReadWhy ?: "UiMapAssignment unreadable")
            val o = JSONObject()
            for (r in t.rows()) {
                val area = r.int(7); val map = r.int(4)
                if (area != 0 && map != 0 && !o.has(area.toString())) o.put(area.toString(), map)
            }
            android.util.Log.i("WCMAP", "danger: the area table: ${o.length()} areas")
            job.writeBytes(AREAS_FILE, o.toString().toByteArray())
        }
    }
}
