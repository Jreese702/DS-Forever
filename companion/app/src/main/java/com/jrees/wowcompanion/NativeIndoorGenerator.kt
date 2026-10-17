package com.jrees.wowcompanion

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Track B's [IndoorGenerator]: the catalogue of every placed building in this game build and one building per [produce], both
 * from the user's own game files ([GameFiles]) through libinteriors.so ([IndoorGeneratorNative]).
 *
 * Catalogue: Map.db2 -> each map's WDT -> its obj ADTs (or the WDT itself for a WMO-only map) -> MODF placements -> per placed
 * root and doodad set, the PC catalogue's work units (building_catalogue.entries, 150 yd work tiles, identical keys). A unit's
 * input digest covers its spec and the CASC content key of every file it reads, so a game update re-bakes only what changed.
 *
 * Cave mode: the PC renderer asks whether the WMO's listfile path is under /dungeon/; the phone has no listfile. The user's
 * ruling (2026-10-12) ships that answer as numbers ([IndoorGeneratorDungeonRoots]); the integrator binds each verdict to the
 * root's observed content key, so only a root whose live content key matches reuses it and every other root gets the native
 * geometric rule. The verdict, its source and the list's provenance build are part of every unit's spec and input digest.
 */
internal class NativeIndoorGenerator(
    private val game: GameFiles,
    private val verdictFor: (Int, String?) -> IndoorGeneratorDungeonRoots.Verdict = IndoorGeneratorDungeonRoots::verdict,
    /** the device's available memory in bytes (null = unknown: no guard); tests replace it */
    private val availableMemory: () -> Long? = ::memAvailable,
    /** the memory guard's backoff sleep (ms); tests replace it */
    private val sleep: (Long) -> kotlin.Unit = Thread::sleep
) : IndoorGenerator {
    override val algorithm: Int = ALGORITHM

    private class Unit(val spec: JSONObject, val inputs: IntArray = IntArray(0))
    // JNI looks up read by name; keep a named implementation instead of a shrinker-generated SAM.
    private class GameSource(private val game: GameFiles) : IndoorGeneratorNative.Source {
        override fun read(fid: Int): ByteArray? = game.read(fid)
    }
    /** the last produced building's native report (JSON: time-free diagnostics, dungeon_rule, storeys, zones) */
    @Volatile var lastReport: String? = null
        private set
    private val units = HashMap<String, Unit>()

    init { check(IndoorGeneratorNative.available) { "libinteriors.so is not in this build" } }

    override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
        val maps = (game.db2(MAP_TABLE) ?: error("Map.db2 unreadable: ${game.lastReadWhy}")).rows(setOf(0))
        // (root, doodad set) -> placements (continent = map id)
        val placed = LinkedHashMap<Pair<Int, Int>, MutableList<JSONObject>>()
        var skipped = 0
        for (map in maps.sortedBy { it.id }) {
            val wdtFid = map.int(MAP_WDT)
            if (wdtFid <= 0 || map.int(MAP_INSTANCE_TYPE) !in INSTANCE_TYPES) continue
            val wdt = game.read(wdtFid)
            if (wdt == null) {
                Log.w(TAG, "map ${map.id}: WDT $wdtFid unreadable: ${game.lastReadWhy}")
                continue
            }
            val tiles = runCatching { IndoorGeneratorNative.objectTiles(wdt) }
                .onFailure { Log.w(TAG, "map ${map.id}: ${it.message}") }.getOrNull() ?: continue
            val files: List<Pair<Int, Boolean>> = if (tiles.isEmpty()) listOf(wdtFid to true) else tiles.map { it to false }
            for ((fid, isWdt) in files) {
                yield.point()
                val bytes = (if (isWdt) wdt else game.read(fid)) ?: continue
                val found = runCatching { JSONObject(IndoorGeneratorNative.placements(bytes, isWdt)) }
                    .onFailure { Log.w(TAG, "placements in $fid: ${it.message}") }.getOrNull() ?: continue
                skipped += found.optJSONArray("skipped")?.length() ?: 0
                val list = found.optJSONArray("placements") ?: continue
                for (i in 0 until list.length()) {
                    val p = list.optJSONObject(i) ?: continue
                    // MapView draws unit-scale yaw placements only (pack_preview.place_record)
                    if (Math.abs(p.optDouble("scale") - 1.0) > 1e-6) { skipped++; continue }
                    p.put("continent", map.id)
                    placed.getOrPut(p.optInt("fid") to p.optInt("dset")) { ArrayList() }.add(p)
                }
            }
        }
        val out = ArrayList<IndoorBakeStore.Work>()
        units.clear()
        for ((rootSet, places) in placed) {
            yield.point()
            val (rootFid, dset) = rootSet
            val root = game.read(rootFid) ?: continue
            // one placement per MODF unique id (the same record appears in every ADT tile the building overlaps)
            val unique = places.distinctBy { it.optInt("continent") to it.optLong("uid") }
            val profile = profileFor(rootFid)
            // recomputed live at every catalogue run from the shipped list and the root's CASC content key; nothing persisted
            val verdict = verdictFor(rootFid, game.contentKey(rootFid)?.let { hex(it) })
            val found = runCatching { JSONObject(IndoorGeneratorNative.units(root, rootFid, dset, profile.workTile, profile.roomSpan)) }
                .onFailure { Log.w(TAG, "root $rootFid set $dset: ${it.message}") }.getOrNull() ?: continue
            for (key in found.keys()) {
                val unit = found.optJSONObject(key) ?: continue
                val gis = (unit.optJSONArray("gis") ?: continue).let { a -> IntArray(a.length()) { a.optInt(it, -1) } }
                val groupFids = unit.optJSONArray("group_fids") ?: continue
                // the COMPLETE effective profile travels in the spec: the digest, the input discovery and produce all read it
                val spec = JSONObject().put("key", key).put("root", rootFid).put("seed", JSONArray(gis.toList())).put("dset", dset)
                    .put("profile", profile.id).put("portal_hops", profile.portalHops).put("context_only", profile.contextOnly)
                    .put("clip", profile.clip).put("portal_owned_rooms", profile.portalOwnedRooms).put("tile_size", profile.recastTile)
                    .put("group_fids", groupFids).put("placements", JSONArray(unique))
                if (profile.portalOwnedRooms) spec.put("room_span", profile.roomSpan).put("room_height", profile.roomHeight)
                verdict.dungeon?.let { spec.put("dungeon", it).put("dungeon_rule", DUNGEON_RULE_LIST) }
                spec.put("dungeon_source", verdict.source).put("dungeon_provenance", IndoorGeneratorDungeonRoots.PROVENANCE_BUILD)
                // a malformed unit is reported and left out (never produced, sealed or published); the catalogue goes on
                val inputs = runCatching { IndoorGeneratorNative.inputs(root, rootFid, gis, dset, profile.portalHops, profile.clip) }
                    .onFailure { Log.w(TAG, "unit $key: ${it.message}") }.getOrNull() ?: continue
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(spec.toString().toByteArray(Charsets.UTF_8))
                for (fid in inputs) {
                    val ckey = game.contentKey(fid) ?: ByteArray(0)
                    digest.update("\n$fid:".toByteArray(Charsets.UTF_8))
                    digest.update(ckey)
                }
                val box = unit.optJSONArray("box") ?: continue
                val locations = unique.map { worldBox(it, box) }
                units[key] = Unit(spec, inputs)
                out.add(IndoorBakeStore.Work(key, hex(digest.digest()), algorithm, locations))
            }
        }
        Log.i(TAG, "catalogue: ${out.size} buildings from ${placed.size} placed roots; $skipped placements skipped")
        return out
    }

    override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
        if (work.key !in units) catalogue(yield)
        val unit = units[work.key] ?: error("building ${work.key} is not in this game build's catalogue")
        // its input files fetched together, several at a time, when many are missing (after a game update; issue #10)
        if (unit.inputs.isNotEmpty()) game.prefetch(unit.inputs.toList())
        var started = 0L
        var attempt: String? = null
        while (true) {
            started = System.nanoTime()
            try {
                memoryGuard(START_MIN_AVAILABLE, "start")
                var checked = started
                val guarded = IndoorGenerator.Yield {
                    val now = System.nanoTime()
                    if (now - checked >= GUARD_INTERVAL_NS) { checked = now; memoryGuard(RUN_MIN_AVAILABLE, "mid") }
                    yield.point()
                }
                // named, @Keep callback objects: native code finds read()/point() by name, which R8 must not strip (IndoorGeneratorNative.kt)
                attempt = IndoorGeneratorNative.generate(unit.spec.toString(), GameSource(game), NativeYield(guarded), out.path)
                break
            } catch (e: LowMemory) {
                // the T3 parser and the threshold tuning read this exact line
                Log.w(TAG, "memory guard: stopped (MemAvailable ${e.availableMiB} MiB, phase ${e.phase}) ${work.key}, retry in ${GUARD_BACKOFF_MS / 1000} s")
                out.listFiles()?.forEach { it.deleteRecursively() }
                lowMemoryStops++
                backoff(yield)
            }
        }
        val report = attempt!!
        lastReport = report
        val ms = (System.nanoTime() - started) / 1_000_000
        // logcat cuts an entry at ~4 KB and a city report is longer: the fields the T3 parser needs first, on a line of their own
        val full = runCatching { JSONObject(report) }.getOrNull()
        val summary = JSONObject().put("ms", ms)
        for (name in SUMMARY_FIELDS) full?.let { if (it.has(name)) summary.put(name, it.get(name)) }
        Log.i(TAG, "summary ${work.key}: $summary")
        Log.i(TAG, "built ${work.key} in $ms ms: $report")
    }

    override fun close() { units.clear() }

    /** low-memory stops so far (each one a backoff and a fresh attempt at the same building) */
    @Volatile var lowMemoryStops = 0
        private set

    private class LowMemory(val availableMiB: Long, val phase: String) : RuntimeException("low memory ($phase)")

    /**
     * Low device memory (the game is running) stops the building before the system kills the app: the attempt is abandoned
     * (the native yield propagates the exception; the staging folder is emptied), the step backs off for [GUARD_BACKOFF_MS]
     * and tries the same building again. Not a deferral (that holds until the next game or app update) and not [Stopped] (that
     * ends the step until the next start): low memory is transient. The largest buildings take ~470 MiB of native heap on
     * the PC (4M-span ruling); the thresholds are tuned from the device log's MemAvailable.
     */
    private fun memoryGuard(minimum: Long, phase: String) {
        val available = availableMemory() ?: return
        if (available < minimum) throw LowMemory(available shr 20, phase)
    }

    /** waits out the backoff in 1 s slices through the scheduler's yield: a pause blocks, a stop ends the wait at once */
    private fun backoff(yield: IndoorGenerator.Yield) {
        var left = GUARD_BACKOFF_MS
        while (left > 0) {
            yield.point()
            val slice = minOf(1000L, left)
            sleep(slice)
            left -= slice
        }
        yield.point()
    }

    /** One generation profile: everything that decides a unit's selection and output. */
    data class Profile(
        val id: String, val workTile: Double, val roomSpan: Double, val roomHeight: Double, val portalHops: Int,
        val contextOnly: Boolean, val clip: Boolean, val portalOwnedRooms: Boolean, val recastTile: Double
    )

    private fun worldBox(place: JSONObject, box: JSONArray): IndoorBakeStore.Location {
        val o = place.optJSONArray("origin") ?: error("placement without origin")
        val c = Math.cos(place.optDouble("yaw")); val s = Math.sin(place.optDouble("yaw"))
        var x0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (lx in listOf(box.optDouble(0), box.optDouble(1))) for (ly in listOf(box.optDouble(2), box.optDouble(3))) {
            val wx = o.optDouble(0) - lx * c + ly * s
            val wy = o.optDouble(1) - lx * s - ly * c
            x0 = minOf(x0, wx); x1 = maxOf(x1, wx); y0 = minOf(y0, wy); y1 = maxOf(y1, wy)
        }
        return IndoorBakeStore.Location(place.optInt("continent"), x0, x1, y0, y1)
    }

    companion object {
        /** Bump when unchanged inputs would now give different output (it is IndoorBakeStore.Work.algorithm). */
        const val ALGORITHM = 3
        /** the report's "dungeon_rule" when IndoorGeneratorDungeonRoots answered (else "geometry-modal-plane-share<0.40") */
        const val DUNGEON_RULE_LIST = "id-list"
        /** the compact per-building log line (WCINTERIORS "summary <key>: {...}"), before the full report */
        private val SUMMARY_FIELDS = listOf("dungeon_rule", "dungeon", "storeys", "recast_peak", "loader_contract")
        /** a building starts only with this much available (the largest needs ~470 MiB of native heap) */
        const val START_MIN_AVAILABLE = 1024L shl 20
        /** below this mid-build the system is about to kill background apps: stop first */
        const val RUN_MIN_AVAILABLE = 384L shl 20
        private const val GUARD_INTERVAL_NS = 200_000_000L
        /** how long a low-memory stop waits before trying the building again */
        const val GUARD_BACKOFF_MS = 60_000L

        /** MemAvailable from /proc/meminfo (no permission needed), null where it cannot be read (the PC tests) */
        fun memAvailable(): Long? = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.times(1024)
            }
        }.getOrNull()
        private const val TAG = "WCINTERIORS"
        private const val MAP_TABLE = 1349477          // Map.db2 (MapHarvest.DB_MAP)
        private const val MAP_INSTANCE_TYPE = 8        // 0 world, 1 dungeon, 2 raid, 3 battleground (checked on the PC build)
        private const val MAP_WDT = 21                 // WdtFileDataID (MapHarvest.MAP_WDT)
        private val INSTANCE_TYPES = setOf(0, 1, 2, 3)
        /** The legacy profile of the non-city references (inn, Deadmines, mines, Darnassus, Thunder Bluff): whole portal
         *  components in 150 yd work tiles, one portal hop, Recast 60 yd tiles. */
        val LEGACY = Profile("legacy-r1", workTile = 150.0, roomSpan = 0.0, roomHeight = 0.0, portalHops = 1, contextOnly = false,
            clip = false, portalOwnedRooms = false, recastTile = 60.0)
        /** CITY_RULE.md revision 1 (integrator ruling 2026-10-12): bounded portal room blocks, portal-owned floors, boundary-portal
         *  access, one context-only hop, clipped to the block, Recast 60 yd tiles; continuous/cave plans rejected. */
        val CITY_ROOM = Profile("city-room-r1", workTile = 0.0, roomSpan = 120.0, roomHeight = 80.0, portalHops = 1,
            contextOnly = true, clip = true, portalOwnedRooms = true, recastTile = 60.0)
        /** The room profile's roots (integrator ruling): Stormwind, Ironforge, Undercity, Orgrimmar. Every other root is legacy. */
        val CITY_ROOTS = setOf(7962574, 7801267, 7675285, 7964229)
        fun profileFor(rootFid: Int): Profile = if (rootFid in CITY_ROOTS) CITY_ROOM else LEGACY

        val factory = IndoorGenerator.Factory { NativeIndoorGenerator(it) }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
