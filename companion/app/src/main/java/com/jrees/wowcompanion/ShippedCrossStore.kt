package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * THE DEVICE'S SIDE OF [ShippedCross]: the shipped NET1 files the APK carries (assets `safepath/net/<map>.bin`, [ShippedNet]), read once
 * on the route thread into one [ShippedCross.Zone] a map and a faction, and QuestieDB's portal objects (QuestieObjects, from the toc the
 * app installed) for TravelLinks' portals. [maps] is known as soon as the assets are listed; until the files are read [ready] is false
 * and the caller keeps its old way. A file from another rules version is marked stale ("may be out of date") and still used; [buildKey]
 * (when the host gives it: the game's Build Key) marks a game-build mismatch the same way. Nothing here is typed game data: the files are
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
    /** the game's Build Key, when the host knows it (GameFiles.buildKey); null = not compared */
    @Volatile var buildKey: String? = null
    @Volatile private var started = false

    /** start reading (once; cheap after): the assets' list, then every file and QuestieDB's portals on the route thread */
    fun init(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        RouteWork.run({ load(app) }) { said -> android.util.Log.i("WCMAP", said) }
    }

    private fun load(app: Context): String {
        val t0 = System.nanoTime()
        val ids = runCatching { app.assets.list(ShippedNet.ASSET_DIR)?.toList() }.getOrNull().orEmpty()
            .mapNotNull { it.removeSuffix(".bin").takeIf { n -> n != it }?.toIntOrNull() }.toSet()
        maps = ids
        val byFaction = HashMap<Int, HashMap<Int, ShippedCross.Zone>>()
        val stale = ArrayList<Int>(); val bad = ArrayList<Int>()
        for (m in ids) {
            val pack = runCatching { app.assets.open(ShippedNet.assetPath(m)).use { ShippedNet.read(it) } }.getOrNull()
            if (pack == null) { bad.add(m); continue }
            val old = pack.header.rules != Climb.RULES_VERSION || buildKey?.let { it.isNotEmpty() && it != pack.header.build } == true
            if (old) stale.add(m)
            for (s in pack.sections) byFaction.getOrPut(s.faction) { HashMap() }[m] = ShippedCross.Zone(m, s, old)
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

    /** the in-zone legs' yards on the shipped network of [faction] (SafePath.route, as the device's in-zone leg); null = no net there */
    fun legYards(faction: Int) = ShippedCross.LegYards { map, a, b, toExit ->
        val net = zone(faction, map)?.net ?: return@LegYards null
        val w = SafePath.route(net, a[0], a[1], b[0], b[1], toExit) ?: return@LegYards null
        var yd = 0.0
        for (i in 1 until w.size) yd += hypot(w[i][0] - w[i - 1][0], w[i][1] - w[i - 1][1])
        yd
    }
}
