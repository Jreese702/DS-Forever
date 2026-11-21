package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * THE DEVICE'S SIDE OF [ShippedCross]: the shipped NET1 files the APK carries (assets `safepath/net/<map>.bin`, [ShippedNet]) as one
 * slim [ShippedCross.Zone] a map and a faction (the cross-zone graph), and QuestieDB's portal objects (QuestieObjects, from the toc the
 * app installed) for TravelLinks' portals. [maps] is known as soon as the assets are listed ([ready]); the graph itself is read only
 * when a cross-zone plan first asks for it ([zones], on the route thread). A file from another game build (ShippedNets.load: the
 * game's Build Key against the file's) or rules version is marked stale ("may be out of date") and still used. Nothing here is typed
 * game data: the files are our own computed network; the portals are the player's own QuestieDB's.
 *
 * MEMORY WORK #56 (D1, the user's yes 2026-11-19). Before: every one of the 45 files (35 MB compressed, 3 - 36 MB each once read) was
 * read at the app's start - a ~100 MB start-up spike - and 4 whole zones more were kept for the legs, never freed. Now:
 *  - nothing at the start but the assets' list;
 *  - the graph ([Index]: the zones cut to their exits and starts, ShippedCross.slim; ~1 MB for all 45) is read on the first
 *    cross-zone plan, from its own small cache file ([CACHE_FILE], written the first time the files are read for this install) or,
 *    without one, from the files ONE AT A TIME;
 *  - the legs' whole zones come from ShippedNets' one cache (the in-zone route's, which keeps the player's zone and its neighbours);
 *  - [release] (Android's pressure, ShippedNets.letGo) lets the graph go: read again from the cache file when next asked;
 *  - [bytes] is the memory line's.
 */
object ShippedCrossStore {
    @Volatile var maps: Set<Int> = emptySet(); private set
    /** the cross-zone graph and the portals, read when first asked ([index]); null = not read now */
    @Volatile private var index: Index? = null
    /** the assets are listed: [maps] is known (the graph is read when first asked) */
    @Volatile var ready = false; private set
    /** bumped when the zones or the portals change (the caller's cache key); never by a read that gives the same graph again */
    @Volatile var version = 0; private set
    /** the game's Build Key, when the host knows it (else read from .build.info, as ShippedNets) */
    @Volatile var buildKey: String? = null
    @Volatile private var started = false
    @Volatile private var appCtx: Context? = null
    /** the graph's identity last read (its stamp and the game's build): a read that differs bumps [version] */
    @Volatile private var lastContent: String? = null
    /** how many times the graph was read (from the cache file or the files): the tests, the log */
    @Volatile var reads = 0; private set

    /** the graph: faction -> map -> slim zone, QuestieDB's portal objects and the area table, its bytes estimated */
    class Index(val zones: Map<Int, Map<Int, ShippedCross.Zone>>, val portalObjs: List<QuestieObjects.Obj>, val areas: Map<Int, Int>) {
        val bytes: Long = zones.values.sumOf { m -> m.values.sumOf { ShippedNets.sectionBytes(it.section) + 48L } } +
            portalObjs.sumOf { 96L + it.spawns.values.sumOf { l -> 16L + l.size * 36L } } + areas.size * 48L
    }

    /** start (once; cheap): the assets' list on the route thread. The graph is NOT read here (memory work #56: no start-up spike) */
    fun init(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        appCtx = app
        ShippedNets.init(app)                                   // the legs' zones: the one shared cache
        RouteWork.run({
            maps = listIds(runCatching { app.assets.list(ShippedNet.ASSET_DIR)?.toList() }.getOrNull().orEmpty())
            ready = true
            "shipped cross: ${maps.size} shipped nets listed (the graph is read on the first cross-zone plan)"
        }) { said -> android.util.Log.i("WCMAP", said) }
    }

    /** "1411.bin", ... -> their maps */
    fun listIds(names: List<String>): Set<Int> = names.mapNotNull { it.removeSuffix(".bin").takeIf { n -> n != it }?.toIntOrNull() }.toSet()

    /**
     * the graph, read now when it is not in memory (the route thread; on the UI thread it is never read there: null, and a read is
     * asked of the route thread)
     */
    private fun index(): Index? {
        index?.let { return it }
        val app = appCtx ?: return null
        if (!ready) return null
        // Light 8 (LIGHT8_PLAN.md change 4): the graph of every zone waits while the game loads; a plan made meanwhile is made again
        if (holdBump()) return null
        if (RouteWork.onMain()) { RouteWork.run({ index(); "" }) {}; return null }
        synchronized(this) {
            index?.let { return it }
            val t0 = System.nanoTime()
            val game = buildKey ?: runCatching { GameFiles.activeBuild(GameFiles.gameRoot(app))?.second }.getOrNull()
            val stamp = stampOf(runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L), maps)
            val cache = File(app.cacheDir, CACHE_FILE)
            var from = "the cache file"
            val packs = readCache(cache, stamp) ?: run {
                from = "the shipped files"
                slimPacks(maps, { p -> runCatching { app.assets.open(p) }.getOrNull() }) { android.util.Log.i("WCMAP", "shipped cross: $it") }
                    .also { runCatching { writeCache(cache, stamp, it) } }
            }
            var portals: List<QuestieObjects.Obj> = emptyList(); var areas: Map<Int, Int> = emptyMap()
            // QuestieDB's portals ("... Portal to <place>"): the objects only; their maps and places are put on at plan time
            runCatching {
                val addons = File(WowProduct.flavorDir(GameFiles.gameRoot(app)), "Interface/AddOns")
                QuestieNpcs.toc(addons)?.let { toc -> portals = QuestieObjects.read(toc) { QuestieObjects.portalPlace(it) != null } }
                val af = ArtHarvest.file(DangerStore.AREAS_FILE)
                if (af.isFile) areas = DangerStore.parseAreas(JSONObject(af.readText()))
            }
            val (zs, stale) = zonesOf(packs, game)
            val ix = Index(zs, portals, areas)
            index = ix; reads++
            val content = "$stamp|$game|${portals.size}|${areas.size}"
            if (lastContent != null && lastContent != content) version++
            lastContent = content
            android.util.Log.i("WCMAP", "shipped cross: the graph of ${packs.size} shipped nets (${zs.values.sumOf { it.size }} sections" +
                (if (stale.isNotEmpty()) "; may be out of date: ${stale.sorted()}" else "") + ") from $from, ${portals.size} QuestieDB portal " +
                "objects, ${ix.bytes / 1024} KB, read in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return ix
        }
    }

    /**
     * LIGHT 8'S HOLD (LoadHold, the 8 GB Thors only): true = the cross-zone data waits now; when the hold ends [version] goes up, so a
     * cross-zone plan made meanwhile (no graph, or straight legs for a held zone) is made again. Off Light 8 always false
     */
    fun holdBump(): Boolean = LoadHold.hold(LoadHold.SAFEPATH, "cross") { version++ }

    /** memory pressure (ShippedNets.letGo): the graph goes (read again from the cache file when next asked); the bytes let go */
    fun release(): Long {
        val ix = index ?: return 0L
        index = null
        return ix.bytes
    }
    /** the memory line's: the graph's bytes now (0 = not in memory) */
    fun bytes(): Long = index?.bytes ?: 0L
    /** the graph is in memory now (the tests, Settings) */
    val loaded: Boolean get() = index != null

    // ---------------------------------------------------------------- the graph's pure parts (the tests)

    /** the cache file in the app's cache folder (Android may clear it: then the files are read again) */
    const val CACHE_FILE = "safepath_cross_slim.json.gz"
    const val CACHE_FORMAT = 1
    /** what the cache file must say to be used: this install (the APK's update time), the rules, the maps shipped */
    fun stampOf(apkStamp: Long, ids: Collection<Int>): String = "v$CACHE_FORMAT:$apkStamp:${Climb.RULES_VERSION}:${ids.sorted().joinToString(",")}"

    /** each map's file read ONE AT A TIME ([open]: an asset's path -> its stream) and cut to the graph's slim sections; an unreadable one left out */
    fun slimPacks(ids: Collection<Int>, open: (String) -> InputStream?, log: (String) -> Unit): List<ShippedNet.Pack> =
        ids.sorted().mapNotNull { m ->
            ShippedNets.load(m, open, null, log)?.pack?.let { p -> ShippedNet.Pack(p.header, p.sections.map { ShippedCross.slim(it) }) }
        }

    /** [packs] into [f] under [stamp] (gzip JSON; temp + rename) */
    fun writeCache(f: File, stamp: String, packs: List<ShippedNet.Pack>) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        GZIPOutputStream(tmp.outputStream().buffered()).use { o ->
            o.write(JSONObject().put("stamp", stamp).put("packs", JSONArray(packs.map { it.toJson() })).toString().toByteArray(Charsets.UTF_8))
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** the packs in [f] when it was written under [stamp], else null (no file, another install, unreadable) */
    fun readCache(f: File, stamp: String): List<ShippedNet.Pack>? = runCatching {
        if (!f.isFile) return null
        val o = JSONObject(GZIPInputStream(f.inputStream().buffered()).use { it.readBytes() }.toString(Charsets.UTF_8))
        if (o.optString("stamp") != stamp) return null
        val a = o.getJSONArray("packs")
        (0 until a.length()).map { ShippedNet.Pack.fromJson(a.getJSONObject(it)) ?: error("pack $it") }
    }.getOrNull()

    /** the packs as faction -> map -> zone, a zone stale when its build is not [game]'s or its rules are not this app's; and the stale maps */
    fun zonesOf(packs: List<ShippedNet.Pack>, game: String?): Pair<Map<Int, Map<Int, ShippedCross.Zone>>, List<Int>> {
        val byFaction = HashMap<Int, HashMap<Int, ShippedCross.Zone>>()
        val stale = ArrayList<Int>()
        for (p in packs) {
            val m = p.header.map
            val old = ShippedNets.staleWhy(p.header, game) != null || p.header.rules != Climb.RULES_VERSION
            if (old) stale.add(m)
            for (s in p.sections) byFaction.getOrPut(s.faction) { HashMap() }[m] = ShippedCross.Zone(m, s, old)
        }
        return byFaction to stale
    }

    // ---------------------------------------------------------------- the planner's calls (the route thread)

    /** the shipped zones for [faction] (TransportRoutes.HORDE / ALLIANCE); the graph is read now when it is not in memory */
    fun zones(faction: Int): Collection<ShippedCross.Zone> = index()?.zones?.get(faction)?.values ?: emptyList()
    fun zone(faction: Int, map: Int): ShippedCross.Zone? = index()?.zones?.get(faction)?.get(map)
    fun stale(faction: Int): Set<Int> = zones(faction).filter { it.stale }.map { it.map }.toSet()

    /** QuestieDB's portals as TravelLinks', on their maps ([toWorld]: map, x, y in 0..1 -> world) and to their places' maps ([names]) */
    fun portals(toWorld: (Int, Double, Double) -> DoubleArray?, names: Map<Int, String>): List<TravelLinks.Portal> {
        val ix = index() ?: return emptyList()
        val a = ix.areas
        return QuestieObjects.portals(ix.portalObjs, { a[it] }, toWorld) { TravelLinks.placeMap(it, names) }
    }

    /** the in-zone legs' yards on the shipped network of [faction] (ShippedRoute.plan, the device's in-zone route, over level ground and the
     *  network's own danger: another zone's danger grid is not built for a plan); null = no net there or no way on it (then straight).
     *  The whole zone from ShippedNets' one cache (memory work #56: no second cache of 4 whole zones) */
    fun legYards(faction: Int) = legYards(faction, { m -> zone(faction, m) != null }) { m -> ShippedNets.files.get(m)?.pack }

    /** [legYards] over [has] (a shipped zone for the side) and [whole] (the zone's whole pack, loaded when asked): pure (the tests) */
    fun legYards(faction: Int, has: (Int) -> Boolean, whole: (Int) -> ShippedNet.Pack?) = ShippedCross.LegYards { map, a, b, toExit ->
        if (!has(map)) return@LegYards null
        val sec = whole(map)?.let { ShippedNets.sectionOf(it, faction) } ?: return@LegYards null
        val res = ShippedRoute.plan(sec, { _, _ -> Float.NaN }, null, a, b, toExit = toExit)
        if (res.reached) res.yd else null
    }
}
