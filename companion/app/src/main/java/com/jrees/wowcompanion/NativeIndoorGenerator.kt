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
    override val identity: String get() = baseIdentity()

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

    override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> = catalogue(yield, null)

    /**
     * 1525 ([IndoorCatalogueMemo], the user's "Reuse what didn't change"): with a [memo], a WDT's tiles, a file's placements, a root's
     * units and a unit's input files are the library's earlier answers for the same content key (the file is not read at all), asked
     * again only for a file whose content key changed. The list is the same as without it: every answer is a pure function of that one
     * file's bytes and the numbers in its key, and each unit's digest is made below from the live content keys of the files it reads
     */
    override fun catalogue(yield: IndoorGenerator.Yield, memo: IndoorCatalogueMemo?): List<IndoorBakeStore.Work> {
        val maps = (game.db2(MAP_TABLE) ?: error("Map.db2 unreadable: ${game.lastReadWhy}")).rows(setOf(0))
        instances = dungeonsAndRaids(maps)
        // a file's content key for the memory (null without one, or not known: computed, never kept)
        fun ck(fid: Int): String? = if (memo == null) null else game.contentKey(fid)?.takeIf { it.isNotEmpty() }?.let { hex(it) }
        fun json(text: String?, where: String): String? = text?.takeIf {
            runCatching { JSONObject(it) }.onFailure { e -> Log.w(TAG, "$where: ${e.message}") }.isSuccess
        }
        // (root, doodad set) -> placements (continent = map id)
        val placed = LinkedHashMap<Pair<Int, Int>, MutableList<JSONObject>>()
        var skipped = 0
        for (map in maps.sortedBy { it.id }) {
            val wdtFid = map.int(MAP_WDT)
            if (wdtFid <= 0 || map.int(MAP_INSTANCE_TYPE) !in INSTANCE_TYPES) continue
            val wdtKey = ck(wdtFid)
            var wdt: ByteArray? = null
            var wdtRead = false
            fun wdtBytes(): ByteArray? {
                if (!wdtRead) {
                    wdtRead = true
                    wdt = game.read(wdtFid)
                    if (wdt == null) Log.w(TAG, "map ${map.id}: WDT $wdtFid unreadable: ${game.lastReadWhy}")
                }
                return wdt
            }
            val tiles = (if (memo != null) memo.ints("tiles", wdtKey, "") { wdtBytes()?.let { objectTiles(map.id, it) } }
                else wdtBytes()?.let { objectTiles(map.id, it) }) ?: continue
            val files: List<Pair<Int, Boolean>> = if (tiles.isEmpty()) listOf(wdtFid to true) else tiles.map { it to false }
            for ((fid, isWdt) in files) {
                yield.point()
                fun answer(): String? {
                    val bytes = (if (isWdt) wdtBytes() else game.read(fid)) ?: return null
                    return json(runCatching { IndoorGeneratorNative.placements(bytes, isWdt) }
                        .onFailure { Log.w(TAG, "placements in $fid: ${it.message}") }.getOrNull(), "placements in $fid")
                }
                val text = (if (memo != null) memo.text(if (isWdt) "wdtplaces" else "places", if (isWdt) wdtKey else ck(fid), "") { answer() }
                    else answer()) ?: continue
                val found = runCatching { JSONObject(text) }.getOrNull() ?: continue
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
            // the root's bytes only when an answer is not known for its content key (without a memory: read first, as always)
            var rootRead = false
            var rootBytes: ByteArray? = null
            fun root(): ByteArray? { if (!rootRead) { rootRead = true; rootBytes = game.read(rootFid) }; return rootBytes }
            if (memo == null && root() == null) continue
            val rootKey = ck(rootFid)
            // one placement per MODF unique id (the same record appears in every ADT tile the building overlaps)
            val unique = places.distinctBy { it.optInt("continent") to it.optLong("uid") }
            val profile = profileFor(rootFid)
            // recomputed live at every catalogue run from the shipped list and the root's CASC content key; nothing persisted
            val verdict = verdictFor(rootFid, game.contentKey(rootFid)?.let { hex(it) })
            fun unitsNow(): String? {
                val r = root() ?: return null
                return json(runCatching { IndoorGeneratorNative.units(r, rootFid, dset, profile.workTile, profile.roomSpan) }
                    .onFailure { Log.w(TAG, "root $rootFid set $dset: ${it.message}") }.getOrNull(), "root $rootFid set $dset")
            }
            val unitsText = (if (memo != null) memo.text("units", rootKey, "$rootFid|$dset|${profile.workTile}|${profile.roomSpan}") { unitsNow() }
                else unitsNow()) ?: continue
            val found = runCatching { JSONObject(unitsText) }.getOrNull() ?: continue
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
                fun inputsNow(): IntArray? {
                    val r = root() ?: return null
                    return runCatching { IndoorGeneratorNative.inputs(r, rootFid, gis, dset, profile.portalHops, profile.clip) }
                        .onFailure { Log.w(TAG, "unit $key: ${it.message}") }.getOrNull()
                }
                val inputs = (if (memo != null) memo.ints("inputs", rootKey,
                    "$rootFid|${gis.joinToString(",")}|$dset|${profile.portalHops}|${profile.clip}") { inputsNow() } else inputsNow()) ?: continue
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
        Log.i(TAG, "catalogue: ${out.size} buildings from ${placed.size} placed roots; $skipped placements skipped" +
            (memo?.let { "; from memory ${it.hits}, read ${it.misses}" } ?: ""))
        return out
    }

    /** a WDT's object tiles ([IndoorGeneratorNative.objectTiles]), or null (logged) */
    private fun objectTiles(map: Int, wdt: ByteArray): IntArray? = runCatching { IndoorGeneratorNative.objectTiles(wdt) }
        .onFailure { Log.w(TAG, "map $map: ${it.message}") }.getOrNull()

    /** 1525: the scheduler's listing memory ([memory]): a listing inside [produce] (a cached catalogue) uses it too */
    @Volatile private var memo: IndoorCatalogueMemo? = null
    override fun memory(memo: IndoorCatalogueMemo?) { this.memo = memo }

    /** 2026-11-24: the scheduler's "let go now" (Play, the game, a stop): the input fetches before a building start no more downloads */
    @Volatile private var letGoNow: () -> Boolean = { false }
    override fun letGo(check: () -> Boolean) { letGoNow = check }

    override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
        // (a cached catalogue: the units listed once in this session, from the memory; its answers let go after)
        if (work.key !in units) { val m = memo; try { catalogue(yield, m) } finally { m?.release() } }
        val unit = units[work.key] ?: error("building ${work.key} is not in this game build's catalogue")
        // its input files fetched together, several at a time, when many are missing (after a game update; issue #10)
        // (2026-11-24: Play or the game asks the run to let go: no further download starts, the next yield point ends the building)
        if (unit.inputs.isNotEmpty()) game.prefetch(unit.inputs.toList(), letGo = { runCatching { letGoNow() }.getOrDefault(false) })
        yield.point()
        var started = 0L
        var attempt: String? = null
        var midStops = 0
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
                attempt = try {
                    IndoorGeneratorNative.generate(unit.spec.toString(), GameSource(game), NativeYield(guarded), out.path)
                } catch (e: IllegalStateException) {
                    // D1 (2026-11-23): the builder's own rule (too big, no floors, a budget) is settled, anything else is tried again
                    if (IndoorGenerator.isRule(e.message)) throw IndoorGenerator.Rejected(e.message!!.trim()) else throw e
                }
                break
            } catch (e: LowMemory) {
                // the T3 parser and the threshold tuning read this exact line
                Log.w(TAG, "memory guard: stopped (MemAvailable ${e.availableMiB} MiB, phase ${e.phase}) ${work.key}, retry in ${GUARD_BACKOFF_MS / 1000} s")
                out.listFiles()?.forEach { it.deleteRecursively() }
                lowMemoryStops++
                // "Give up after 3 tries" (2026-11-23): a building that runs the device out of memory every time no longer loops here
                // for good; given up for this run (a failure that may pass: no rule), it is counted as one failed run
                if (e.phase == "mid" && ++midStops >= GUARD_MAX_MID_STOPS)
                    throw IllegalStateException("low memory: the building stopped $midStops times mid-build (MemAvailable ${e.availableMiB} MiB)")
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
        // issue #12: the full report (several KB a building) flooded logcat and pushed everything else out of a problem report's
        // window; the summary line above is the one the parsers read. The whole report only in debug builds
        if (BuildConfig.DEBUG) Log.d(TAG, "built ${work.key} in $ms ms: $report")
    }

    override fun close() { units.clear() }

    /** the dungeons and raids of the last Map.db2 read ([catalogue]), or read now (a cached catalogue: one small table) */
    @Volatile private var instances: Set<Int>? = null

    override fun instanceMaps(): Set<Int>? = instances ?: runCatching {
        dungeonsAndRaids(game.db2(MAP_TABLE)?.rows(setOf(0)) ?: return null)
    }.onFailure { Log.w(TAG, "Map.db2: ${it.message}") }.getOrNull()?.also { instances = it }

    private fun dungeonsAndRaids(maps: List<Db2.Row>): Set<Int> =
        maps.filter { it.int(MAP_INSTANCE_TYPE) in DUNGEON_TYPES }.mapTo(HashSet()) { it.id }

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
        /** the user's pick (2026-11-23) "Build last, leave out of count": dungeons (1) and raids (2); a battleground is counted */
        private val DUNGEON_TYPES = setOf(1, 2)
        /**
         * a low-memory stop in the MIDDLE of a building (the building itself needs more than the device has: an 8 GB Thor) is tried this
         * many times in a run, then given up as a failure that may pass (it counts as one failed run: IndoorDeferrals.MAX_TRIES of them
         * settle it). A stop before it starts is the device's state, not the building's: it waits as before
         */
        const val GUARD_MAX_MID_STOPS = 3
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

        /**
         * the builder's identity without an open generator (the step's finished stamp is checked before the game files open): the
         * algorithm and the native library's own version line; the step adds the library's bytes (IndoorSchedulerStep.builderKey)
         */
        fun baseIdentity(): String =
            "a$ALGORITHM|" + (runCatching { if (IndoorGeneratorNative.available) IndoorGeneratorNative.version() else "no-lib" }.getOrNull() ?: "no-lib")

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
