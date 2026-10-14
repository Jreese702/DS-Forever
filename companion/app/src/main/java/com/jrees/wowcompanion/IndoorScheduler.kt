package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * THE INTERIORS BAKE LOOP (Art Harvest Phase 2, the optional 7th step; Track C 2026-10-12). Pure Kotlin: the Android side
 * ([IndoorSchedulerStep]) supplies [Signals] (live position, game busy, thermal, stop) and runs [run] on a low-priority thread.
 *
 * - catalogue: [IndoorGenerator.catalogue] once per game build, cached in [dataDir] (catalogue-<build>.json);
 * - nearest-first: [IndoorBakeQueue], one building per call, re-asked for the nearest after EVERY building, so moving or
 *   walking into a building re-prioritises at the next pick (a building in progress is never preempted);
 * - throttle: before each building and at every [IndoorGenerator.Yield] point it waits while the game is busy or the device
 *   hot, and leaves with [Outcome.STOPPED] on a stop or when the game went away; a breather between buildings;
 * - resume: the store's receipts are per input digest, so finished buildings survive kills and game updates; changed inputs
 *   re-bake; a building that fails is deferred for this run and retried by the next;
 * - publish: batched (every publish rehashes the whole catalogue), but at once for the building the player is near;
 * - storage: [IndoorSchedulerCleanup] after the first and the last publication.
 */
internal class IndoorScheduler(
    private val store: IndoorBakeStore,
    private val artRoot: File,
    private val build: String,
    private val generator: IndoorGenerator,
    private val signals: Signals,
    private val dataDir: File,
    private val onStatus: (Status) -> Unit = {},
    private val log: (String) -> Unit = {},
    /** the app install a deferral is good for (version + install time): a new APK tries deferred buildings once more */
    private val app: String = "",
) {
    interface Signals {
        /** the player's latest position, or the last saved one; null = unknown (key order) */
        fun position(): IndoorBakeQueue.Position?
        /** the game is fighting / loading / starting: wait */
        fun busy(): Boolean
        /** the player moved within the last few seconds (the user's "only when standing still"): wait */
        fun moving(): Boolean = false
        /** the device is warm (thermal status or headroom) or in battery saver: wait */
        fun hot(): Boolean
        /** stop cleanly: app closing, or the game was seen and has gone */
        fun stop(): Boolean
        fun now(): Long
        fun sleep(ms: Long)
        /** (java heap used, native heap allocated) in bytes, sampled at most every [MEMORY_EVERY_MS] for the stats line; null = not measured */
        fun memory(): Pair<Long, Long>? = null
    }

    enum class Phase { CATALOGUE, BAKING, PAUSED, DONE, DEFERRED, STOPPED }
    enum class Outcome { COMPLETE, DEFERRED, STOPPED }
    data class Status(val phase: Phase, val ready: Int, val total: Int, val deferred: Int = 0, val note: String = "") {
        fun toJson(): JSONObject = JSONObject().put("phase", phase.name).put("ready", ready).put("total", total)
            .put("deferred", deferred).put("note", note)
        companion object {
            fun of(o: JSONObject): Status? = runCatching {
                Status(Phase.valueOf(o.optString("phase")), o.optInt("ready"), o.optInt("total"), o.optInt("deferred"), o.optString("note"))
            }.getOrNull()
        }
    }

    /** measured each run for the storage-policy report (the publish cost Codex flagged) */
    var lastPublishMs = 0L; private set
    var maxPublishMs = 0L; private set
    var publishes = 0; private set

    private var status = Status(Phase.CATALOGUE, 0, 0)
    private fun emit(s: Status) { status = s; runCatching { onStatus(s) } }

    /** blocks while busy or hot; throws [IndoorGenerator.Stopped] on stop */
    private val yieldPoint = IndoorGenerator.Yield { gate() }

    private var heapPeak = 0L
    private var nativePeak = 0L

    private var memoryAt = Long.MIN_VALUE

    /**
     * Every yield point of the generator comes here (many per second in a big building), so it stays cheap: the memory sample at
     * most every [MEMORY_EVERY_MS] (it boxed a pair at every point: GC churn on the Thor, b-interiors-stutter). A move pauses at
     * the next point, mid-building; the building waits with its work so far and goes on when the player stands still again.
     */
    private fun gate() {
        val t = signals.now()
        if (t - memoryAt >= MEMORY_EVERY_MS) {
            memoryAt = t
            signals.memory()?.let { (heap, native) -> heapPeak = maxOf(heapPeak, heap); nativePeak = maxOf(nativePeak, native) }
        }
        var paused = false
        while (true) {
            if (signals.stop()) throw IndoorGenerator.Stopped()
            val why = pauseReason(signals) ?: break
            if (!paused) { paused = true; emit(status.copy(phase = Phase.PAUSED, note = why)); log("paused: $why") }
            signals.sleep(PAUSE_POLL_MS)
        }
        if (paused) { emit(status.copy(phase = Phase.BAKING, note = "")); log("resumed") }
    }

    fun run(): Outcome {
        dataDir.mkdirs()
        try {
            return runInner()
        } finally {
            runCatching { stats() }
        }
    }

    /**
     * THE STATS LINE (Track D's T3 parser, tools/interiors_device/parse_log.py): memory and storage from the one approved log read.
     * key=value; written at the end of every run and every [STATS_EVERY] buildings.
     */
    private fun stats() {
        val generations = File(artRoot, "interiors/snapshots").listFiles()?.count { it.isDirectory && it.name.startsWith("g") } ?: 0
        log("stats: store_bytes=${IndoorSchedulerCleanup.size(File(artRoot, "interiors"))} heap_peak_kb=${heapPeak / 1024} " +
            "native_peak_kb=${nativePeak / 1024} buildings=${status.ready} total=${status.total} deferred=${status.deferred} " +
            "generations=$generations publishes=$publishes publish_failures=$publishFailures publish_max_ms=$maxPublishMs")
    }

    private fun runInner(): Outcome {
        try {
            emit(Status(Phase.CATALOGUE, 0, 0))
            gate()
            val catalogue = catalogue()
            // T3 run 2: deferrals persist with their input identity (game build, app, algorithm + key + input digest)
            val deferrals = IndoorDeferrals(File(dataDir, "deferred.json"), build, app)
            deferrals.prune(catalogue)
            val skip = catalogue.filter { deferrals.skip(it) && store.completed(it) == null }.mapTo(HashSet()) { it.signature }
            val rearmed = catalogue.count { deferrals.entry(it) != null && !deferrals.skip(it) }
            if (skip.isNotEmpty() || rearmed > 0)
                log("deferred from before: ${skip.size} skipped (same input), $rearmed tried again (the game, the generator or the app changed)")
            val queue = IndoorBakeQueue(store, build, catalogue, skip)
            // the sweep FIRST (a run whose publications fail still trims), then unchanged buildings are visible at once
            cleanup(catalogue)
            publish(queue, catalogue)
            emit(Status(Phase.BAKING, queue.ready, queue.total))
            var lastPublish = signals.now()
            var unpublished = 0
            var baked = 0
            try {
                while (true) {
                    gate()
                    val here = signals.position()
                    val pick = queue.next(here) ?: break
                    val t0 = signals.now()
                    val done = runCatching { queue.runOne(here, publish = false) { w, dir -> generator.produce(w, dir, yieldPoint) } }
                    done.exceptionOrNull()?.let { e ->
                        if (e is IndoorGenerator.Stopped) throw e
                        log("deferred ${pick.key}: ${e.message}")
                        deferrals.defer(pick, e.message ?: e.javaClass.simpleName, signals.now())
                    } ?: run {
                        deferrals.clear(pick)
                        unpublished++; baked++
                        log("baked ${pick.key} in ${signals.now() - t0} ms")
                        if (baked % STATS_EVERY == 0) runCatching { stats() }
                    }
                    emit(Status(Phase.BAKING, queue.ready, queue.total, queue.deferred))
                    // The building the player stands at shows at once; the rest in batches.
                    val near = done.isSuccess && here != null && queue.distance(pick, here) <= NEAR_YD * NEAR_YD
                    if (unpublished > 0 && (near || signals.now() - lastPublish >= PUBLISH_EVERY_MS)) {
                        publish(queue, catalogue); unpublished = 0; lastPublish = signals.now()
                    }
                    signals.sleep(BREATHER_MS)
                }
            } finally {
                // Finished buildings are kept on disk anyway; this makes them visible before a stop or the end.
                if (unpublished > 0) runCatching { publish(queue, catalogue) }
            }
            cleanup(catalogue)
            val end = if (queue.deferred > 0)
                Status(Phase.DEFERRED, queue.ready, queue.total, queue.deferred, "${queue.deferred} deferred until the game or the app is updated")
            else Status(Phase.DONE, queue.ready, queue.total)
            emit(end)
            return if (queue.deferred > 0) Outcome.DEFERRED else Outcome.COMPLETE
        } catch (e: IndoorGenerator.Stopped) {
            emit(status.copy(phase = Phase.STOPPED, note = "resumes next start"))
            log("stopped")
            return Outcome.STOPPED
        }
    }

    /**
     * T3 overnight (D's full-catalogue report §2: 53 generations / 268 MB, then every publish failed for good with "interiors index
     * budget exceeded"): EVERY publication is followed by the sweep, failed or not, so a long run keeps a bounded number of
     * generations (KEEP_GENERATIONS plus those still inside the readers' grace); a failed one leaves no staging folder behind and
     * does not stop the bake (finished buildings stay on disk for the next publication that fits).
     */
    private fun publish(queue: IndoorBakeQueue, catalogue: List<IndoorBakeStore.Work>) {
        val t0 = signals.now()
        try {
            queue.publishCompleted()
            lastPublishMs = signals.now() - t0
            maxPublishMs = maxOf(maxPublishMs, lastPublishMs)
            publishes++
        } catch (e: IndoorGenerator.Stopped) {
            throw e
        } catch (e: Exception) {
            publishFailures++
            log("publish failed: ${e.message}")
            dropStaging()
        } finally {
            cleanup(catalogue)
        }
    }

    var publishFailures = 0; private set

    /** a failed publication's staging folder (the store writes the indexes there first); this scheduler is the only publisher */
    private fun dropStaging() {
        File(artRoot, "interiors/snapshots").listFiles()?.filter { it.name.startsWith(".pending_") }?.forEach { it.deleteRecursively() }
    }

    private fun cleanup(catalogue: List<IndoorBakeStore.Work>) = runCatching {
        val r = IndoorSchedulerCleanup.sweep(artRoot, catalogue, now = signals.now())
        if (r.removed > 0) log("cleanup: $r")
    }.onFailure { log("cleanup failed: ${it.message}") }

    /** the generator's list for this build, cached: it runs once per game update */
    private fun catalogue(): List<IndoorBakeStore.Work> {
        val file = File(dataDir, "catalogue-$build.json")
        readCatalogue(file)?.let { cached ->
            if (cached.first == generator.algorithm) return cached.second
        }
        val list = generator.catalogue(yieldPoint)
        writeCatalogue(file, generator.algorithm, list)
        // one cached catalogue: the active build's
        dataDir.listFiles()?.filter { it.name.startsWith("catalogue-") && it.name != file.name }?.forEach { it.delete() }
        return list
    }

    companion object {
        /** why the bake waits now, the cheapest test first; null = it may go on */
        internal fun pauseReason(s: Signals): String? = when {
            s.busy() -> "game busy"
            s.moving() -> "player moving"
            s.hot() -> "device warm"
            else -> null
        }

        const val MEMORY_EVERY_MS = 1_000L
        const val PAUSE_POLL_MS = 2_000L
        const val BREATHER_MS = 250L
        const val PUBLISH_EVERY_MS = 60_000L
        const val NEAR_YD = 300.0
        const val STATS_EVERY = 25

        internal fun readCatalogue(file: File): Pair<Int, List<IndoorBakeStore.Work>>? = runCatching {
            val o = JSONObject(file.readText())
            require(o.optInt("schema") == 1)
            val a = o.optJSONArray("works") ?: error("no works")
            o.optInt("algorithm") to (0 until a.length()).map { i ->
                val w = a.optJSONObject(i) ?: error("bad work")
                val locs = w.optJSONArray("locations") ?: error("no locations")
                // Work / Location re-check every field (digest format, finite numbers)
                IndoorBakeStore.Work(w.optString("key"), w.optString("inputs"), w.optInt("algorithm"), (0 until locs.length()).map { j ->
                    val l = locs.optJSONArray(j) ?: error("bad location")
                    require(l.length() == 5)
                    IndoorBakeStore.Location(l.optInt(0, -1), l.optDouble(1), l.optDouble(2), l.optDouble(3), l.optDouble(4))
                })
            }
        }.getOrNull()

        internal fun writeCatalogue(file: File, algorithm: Int, list: List<IndoorBakeStore.Work>) {
            val works = JSONArray()
            for (w in list) {
                val locs = JSONArray()
                for (l in w.locations) locs.put(JSONArray().put(l.continent).put(l.xmin).put(l.xmax).put(l.ymin).put(l.ymax))
                works.put(JSONObject().put("key", w.key).put("inputs", w.inputs).put("algorithm", w.algorithm).put("locations", locs))
            }
            val tmp = File(file.path + ".part")
            tmp.writeText(JSONObject().put("schema", 1).put("algorithm", algorithm).put("works", works).toString())
            Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}
