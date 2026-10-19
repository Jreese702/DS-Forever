package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * THE DEVICE'S SIDE OF [ShippedCross]: the shipped NET1 files the APK carries (assets `safepath/net/<map>.bin`, [ShippedNet]), read once
 * on the route thread into one [ShippedCross.Zone] a map and a faction, and QuestieDB's portal objects (QuestieObjects, from the toc the
 * app installed) for TravelLinks' portals. [maps] is known as soon as the assets are listed; until the files are read [ready] is false
 * and the caller keeps its old way. A file from another game build (ShippedNets.load: the game's Build Key against the file's) or rules
 * version is marked stale ("may be out of date") and still used. Nothing here is typed game data: the files are
 * our own computed network; the portals are the player's own QuestieDB's.
 */
object ShippedCrossStore {
    @Volatile var maps: Set<Int> = emptySet(); private set
    @Volatile private var zones: Map<Int, Map<Int, ShippedCross.Zone>> = emptyMap()      // faction -> map -> zone
    @Volatile private var portalObjs: List<QuestieObjects.Obj> = emptyList()
    @Volatile private var areas: Map<Int, Int> = emptyMap()
    @Volatile var ready = false; private set
    /** bumped when the zones or the portals change (the caller's cache key) */
    @Volatile var version = 0; private set
    /** the game's Build Key, when the host knows it (else read from .build.info, as ShippedNets) */
    @Volatile var buildKey: String? = null
    @Volatile private var started = false
    @Volatile private var appCtx: Context? = null
    /** the whole files for the legs (a few kept: the zones of the chain being planned; read on the route thread). Its own, not
     *  ShippedNets.files: reading other zones there would push the player's own zone out of the in-zone route's three */
    private val full = RouteWork.Files(KEEP_FULL) { map -> appCtx?.let { c -> ShippedNets.load(map, { p -> runCatching { c.assets.open(p) }.getOrNull() }, null, {}) } }
    const val KEEP_FULL = 4

    /** start reading (once; cheap after): the assets' list, then every file and QuestieDB's portals on the route thread */
    fun init(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        appCtx = app
        RouteWork.run({ load(app) }) { said -> android.util.Log.i("WCMAP", said) }
    }

    private fun load(app: Context): String {
        val t0 = System.nanoTime()
        val ids = runCatching { app.assets.list(ShippedNet.ASSET_DIR)?.toList() }.getOrNull().orEmpty()
            .mapNotNull { it.removeSuffix(".bin").takeIf { n -> n != it }?.toIntOrNull() }.toSet()
        maps = ids
        val byFaction = HashMap<Int, HashMap<Int, ShippedCross.Zone>>()
        val stale = ArrayList<Int>(); val bad = ArrayList<Int>()
        // the game's Build Key (.build.info's) against each file's, as the in-zone route's store (ShippedNets): a mismatch still used
        val game = buildKey ?: runCatching { GameFiles.activeBuild(GameFiles.gameRoot(app))?.second }.getOrNull()
        for (m in ids) {
            val got = ShippedNets.load(m, { p -> runCatching { app.assets.open(p) }.getOrNull() }, game, { android.util.Log.i("WCMAP", "shipped cross: $it") })
            if (got == null) { bad.add(m); continue }
            val old = got.stale != null || got.pack.header.rules != Climb.RULES_VERSION
            if (old) stale.add(m)
            for (s in got.pack.sections) byFaction.getOrPut(s.faction) { HashMap() }[m] = ShippedCross.Zone(m, ShippedCross.slim(s), old)
        }
        zones = byFaction
        // QuestieDB's portals ("... Portal to <place>"): the objects only; their maps and places are put on at plan time
        runCatching {
            val addons = File(WowProduct.flavorDir(GameFiles.gameRoot(app)), "Interface/AddOns")
            QuestieNpcs.toc(addons)?.let { toc -> portalObjs = QuestieObjects.read(toc) { QuestieObjects.portalPlace(it) != null } }
            val af = ArtHarvest.file(DangerStore.AREAS_FILE)
            if (af.isFile) areas = DangerStore.parseAreas(JSONObject(af.readText()))
        }
        ready = true; version++
        return "shipped cross: ${ids.size - bad.size} shipped nets (${zones.values.sumOf { it.size }} sections" +
            (if (stale.isNotEmpty()) "; may be out of date: ${stale.sorted()}" else "") + (if (bad.isNotEmpty()) "; unreadable: ${bad.sorted()}" else "") +
            "), ${portalObjs.size} QuestieDB portal objects, read in ${(System.nanoTime() - t0) / 1_000_000} ms"
    }

    /** the shipped zones for [faction] (TransportRoutes.HORDE / ALLIANCE) */
    fun zones(faction: Int): Collection<ShippedCross.Zone> = zones[faction]?.values ?: emptyList()
    fun zone(faction: Int, map: Int): ShippedCross.Zone? = zones[faction]?.get(map)
    fun stale(faction: Int): Set<Int> = zones(faction).filter { it.stale }.map { it.map }.toSet()

    /** QuestieDB's portals as TravelLinks', on their maps ([toWorld]: map, x, y in 0..1 -> world) and to their places' maps ([names]) */
    fun portals(toWorld: (Int, Double, Double) -> DoubleArray?, names: Map<Int, String>): List<TravelLinks.Portal> {
        val a = areas
        return QuestieObjects.portals(portalObjs, { a[it] }, toWorld) { TravelLinks.placeMap(it, names) }
    }

    /** the in-zone legs' yards on the shipped network of [faction] (ShippedRoute.plan, the device's in-zone route, over level ground and the
     *  network's own danger: another zone's danger grid is not built for a plan); null = no net there or no way on it (then straight) */
    fun legYards(faction: Int) = ShippedCross.LegYards { map, a, b, toExit ->
        if (zone(faction, map) == null) return@LegYards null
        val sec = full.get(map)?.let { ShippedNets.sectionOf(it.pack, faction) } ?: return@LegYards null
        val res = ShippedRoute.plan(sec, { _, _ -> Float.NaN }, null, a, b, toExit = toExit)
        if (res.reached) res.yd else null
    }
}
