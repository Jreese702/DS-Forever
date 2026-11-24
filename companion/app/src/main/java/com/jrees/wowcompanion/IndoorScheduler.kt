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
 *   re-bake; a building the builder's own rule turns down is settled until the game build or the builder changes (D1, 2026-11-23),
 *   one that fails otherwise is deferred for this run and retried by the next, and settled too once it failed on
 *   [IndoorDeferrals.MAX_TRIES] runs (the user's "Give up after 3 tries"; a run killed inside a building counts as a failed run);
 * - dungeons and raids last (the user's "Build last, leave out of count", 2026-11-23): a building placed only on a dungeon or raid map
 *   ([IndoorGenerator.instanceMaps]) is built after every other one and left out of the numbers: the run is FINISHED ([Phase.DONE],
 *   [Status.finishedFor]) once every other building is settled, and the dungeons go on building under that finished row;
 * - publish: batched (every publish rehashes the whole catalogue), but at once for the building the player is near;
 * - storage: [IndoorSchedulerCleanup] after the first and the last publication;
 * - a game update (1525, the user's pick 2026-11-24: "Reuse what didn't change"): [carryOver], run by the art harvest right after the
 *   update ([IndoorCarryStep], the game files it already has open), lists the buildings of the new build (cheaply: [IndoorCatalogueMemo])
 *   and publishes every unchanged one under the new build at once; the bake then builds only the changed and the new ones. A building's
 *   signature holds no game build, so its receipt is the same; a rule turn-down of unchanged inputs stays settled ([IndoorDeferrals]).
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
    /** 1525: the listing's memory across game updates (null = none: every file read and parsed again) */
    private val memo: IndoorCatalogueMemo? = null,
    /** 1525: asked at every yield point before anything else (the carry-over's: the art run's own stop, which throws) */
    private val checkpoint: () -> Unit = {},
) {
    interface Signals {
        /** the player's latest position, or the last saved one; null = unknown (key order) */
        fun position(): IndoorBakeQueue.Position?
        /** the game is fighting / loading / starting: wait */
        fun busy(): Boolean
        /** why [busy] holds it, shown as it is (#568: [PlayGate.indoorWait]'s reasons); null = not busy */
        fun busyReason(): String? = if (busy()) PlayGate.GAME_BUSY else null
        /**
         * The game is up or being launched ([PlayGate], issue #108 cluster, 2026-11-11: the bake opened and held the game files beside
         * the game on the 16 GB Thor and Android killed DS Forever): the run lets the game files go AT ONCE, not after a long pause
         */
        fun gameRunning(): Boolean = false
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
        /** D4: the CPU's wake lock, held only while a building is being made (null = none: the tests, the PC) */
        fun wakeLock(): WakeLock? = null
        /**
         * 1525: the game folder's active build now (cheap; null = not known): a run whose build is no longer the game's publishes nothing
         * more (a game update meanwhile: its carry-over publishes the new build, never overwritten by the old one) and stops
         */
        fun activeBuild(): String? = null
        /**
         * 2026-11-24, BUILD NOW IN GAME (the user's "I also want a button in game to force building in game", "Only while I'm idle"): the
         * game is up and the player pressed Build now, so [gameRunning] does not let the files go; it builds beside the game only while
         * [besideReason] is null, and a building under way is cancelled ([IndoorGenerator.Cancelled]) the moment the player moves or a
         * fight starts (after [IndoorIdle.Beside.CANCEL_GRACE_MS])
         */
        fun beside(): Boolean = false
        /** why it may not build beside the game now ([IndoorIdle.Beside.reason]); null = the player is idle. Asked only while [beside] */
        fun besideReason(): String? = null
    }

    /**
     * D4 (the user's pick, 2026-11-23: "Keep it awake while building"): Android's partial wake lock, so a screen that turns off does not
     * put the CPU to sleep in the middle of a building. [acquire] with a hard time limit (re-armed for each building, so one that hangs
     * holds it at most [WAKE_LIMIT_MS]); [release] at once on a pause, the end, a stop or a failure. Never asked while the game runs.
     */
    interface WakeLock {
        fun acquire(timeoutMs: Long)
        fun release()
    }

    enum class Phase { CATALOGUE, BAKING, PAUSED, DONE, DEFERRED, STOPPED }
    enum class Outcome { COMPLETE, DEFERRED, STOPPED, RELEASED }
    /**
     * [now]: the building being built, as Settings shows it (IndoorProgress.nowText: "one 30 yd from you"; "" = none);
     * [list]: the deferred buildings with their reasons, "key: why" (f-map-tracker, Settings > Map's progress row), at most
     * [IndoorProgress.LIST_MAX]; [rejected]: of [deferred], the buildings the builder's own rule turned down (settled: D1's "can't be
     * mapped", 2026-11-23).
     */
    data class Status(val phase: Phase, val ready: Int, val total: Int, val deferred: Int = 0, val note: String = "",
                      val now: String = "", val list: List<String> = emptyList(), val rejected: Int = 0,
                      /** the dungeons and raids still to build (left out of [ready] / [total]: the user's pick, 2026-11-23) */
                      val dungeons: Int = 0,
                      /** "<game build>|<builder>" the finished row holds for ("" = not finished): kept by the waits, the failures, Off */
                      val finishedFor: String = "",
                      /** 1525: the buildings the carry-over kept for the new game build [keptFor] (-1 / "" = none: the row's "Updated ...") */
                      val kept: Int = -1,
                      val keptFor: String = "") {
        fun toJson(): JSONObject = JSONObject().put("phase", phase.name).put("ready", ready).put("total", total)
            .put("deferred", deferred).put("note", note).put("now", now).put("list", org.json.JSONArray(list)).put("rejected", rejected)
            .put("dungeons", dungeons).put("finished_for", finishedFor).put("kept", kept).put("kept_for", keptFor)
        /** the same without the carry-over's line (another game build's) */
        fun withoutCarry(): Status = if (keptFor.isEmpty() && kept < 0) this else copy(kept = -1, keptFor = "")
        companion object {
            fun of(o: JSONObject): Status? = runCatching {
                val a = o.optJSONArray("list")
                Status(Phase.valueOf(o.optString("phase")), o.optInt("ready"), o.optInt("total"), o.optInt("deferred"), o.optString("note"),
                    o.optString("now"), if (a == null) emptyList() else List(a.length()) { a.optString(it) }, o.optInt("rejected"),
                    o.optInt("dungeons"), o.optString("finished_for"), o.optInt("kept", -1), o.optString("kept_for"))
            }.getOrNull()
        }
    }

    /**
     * 1525: what [carryOver] did for the new game build. [kept]: the counted buildings shown under it at once; [rebuild]: the counted
     * ones to build (changed, new, or a failure tried again); [rejected]: the builder's turn-downs that stay settled; [dungeons]: the
     * dungeons and raids still to build (out of the count); [published]: the publication went through (false: it failed, e.g. a full
     * disk - the old one stays readable and the bake publishes later)
     */
    data class Carried(val kept: Int, val rebuild: Int, val total: Int, val rejected: Int, val dungeons: Int, val published: Boolean) {
        val finished: Boolean get() = rebuild == 0
    }

    /** measured each run for the storage-policy report (the publish cost Codex flagged) */
    var lastPublishMs = 0L; private set
    var maxPublishMs = 0L; private set
    var publishes = 0; private set

    private var status = Status(Phase.CATALOGUE, 0, 0)
    /** this run's id: one failure per run counts once toward [IndoorDeferrals.MAX_TRIES] */
    private val runId = "r" + java.util.UUID.randomUUID().toString().take(8)
    /** the game build and builder a finished row holds for ([Status.finishedFor]) */
    private val finishKey = finishKey(build, app)
    /** 1525: the carry-over's count for this very build ([Status.kept]): every status of this run carries it */
    private var carried: Int = -1
    private fun emit(s: Status) {
        val t = if (carried >= 0 && s.keptFor.isEmpty()) s.copy(kept = carried, keptFor = build) else s
        status = t; runCatching { onStatus(t) }
    }

    /** blocks while busy or hot; throws [IndoorGenerator.Stopped] on stop */
    private val yieldPoint = IndoorGenerator.Yield { gate() }

    private val wake: WakeLock? = runCatching { signals.wakeLock() }.getOrNull()
    /** D4: the lock is held now ([awake] / [asleep] log each change with its reason) */
    var awakeNow = false; private set
    /** D4: how many times it was taken and let go (the tests) */
    var wakeAcquires = 0; private set
    var wakeReleases = 0; private set

    /** D4: a building is about to be made: the CPU held awake, the time limit re-armed. Never while the game runs */
    private fun awake(why: String) {
        val w = wake ?: return
        if (signals.gameRunning()) { asleep("the game is running"); return }
        if (runCatching { w.acquire(WAKE_LIMIT_MS) }.onFailure { log("wake lock: not taken ($why): ${it.message}") }.isFailure) return
        if (!awakeNow) { awakeNow = true; wakeAcquires++; log("wake lock acquired: $why (at most ${WAKE_LIMIT_MS / 60_000} min a building)") }
    }

    /** D4: let go at once (a pause, the end, a stop, a failure) */
    private fun asleep(why: String) {
        val w = wake ?: return
        if (!awakeNow) return
        awakeNow = false; wakeReleases++
        runCatching { w.release() }
        log("wake lock released: $why")
    }

    private var heapPeak = 0L
    private var nativePeak = 0L

    private var memoryAt = Long.MIN_VALUE

    /**
     * Every yield point of the generator comes here (many per second in a big building), so it stays cheap: the memory sample at
     * most every [MEMORY_EVERY_MS] (it boxed a pair at every point: GC churn on the Thor, b-interiors-stutter). A move pauses at
     * the next point, mid-building; the building waits with its work so far and goes on when the player stands still again.
     */
    /** 2026-11-24: when a reason to cancel the building under way beside the game was first seen (0 = none now) */
    private var cancelSeenAt = 0L
    /** 2026-11-24: the run lets the game files go at once (Play, the game, a load, low memory): no last publication, no stats walk */
    @Volatile private var hurry = false

    /** the row's note while it builds: beside the game, "building while you're idle" */
    private fun buildingNote(): String = if (runCatching { signals.beside() }.getOrDefault(false)) PlayGate.IDLE_BUILDING else ""

    private fun gate() {
        checkpoint()
        val t = signals.now()
        if (t - memoryAt >= MEMORY_EVERY_MS) {
            memoryAt = t
            signals.memory()?.let { (heap, native) -> heapPeak = maxOf(heapPeak, heap); nativePeak = maxOf(nativePeak, native) }
        }
        var paused = false
        var pausedAt = 0L
        var said: String? = null
        while (true) {
            if (signals.stop()) throw IndoorGenerator.Stopped()
            if (signals.gameRunning()) { hurry = true; asleep("the game is running"); log("the game is running: the game files let go at once"); throw IndoorGenerator.Released() }
            val why = pauseReason(signals)
            if (why == null) { cancelSeenAt = 0L; break }
            // 2026-11-24: a game update / install / Repair writing the game's files, or beside the game a load or low memory: let go now
            if (letGoAtOnce(why)) {
                hurry = true
                asleep("paused: $why")
                if (status.phase != Phase.DONE) emit(status.copy(phase = Phase.PAUSED, note = why, now = ""))
                log("paused: $why: the game files let go at once")
                throw IndoorGenerator.Released()
            }
            // 2026-11-24 (Build now in game, "pauses the moment you move"): beside the game the building under way is not held in memory
            // through a walk or a fight: it gets a moment to finish, then it is cancelled (not counted) and built again once idle
            if (marked != null && runCatching { signals.beside() }.getOrDefault(false)) {
                val seen = signals.now()
                if (cancelSeenAt == 0L) cancelSeenAt = seen
                if (seen - cancelSeenAt < IndoorIdle.Beside.CANCEL_GRACE_MS) return
                cancelSeenAt = 0L
                asleep("cancelled: $why")
                throw IndoorGenerator.Cancelled(why)
            }
            // a kill while it waits is not the building's (the player, the device, the game): its kill marker goes until it resumes
            if (!paused) { paused = true; pausedAt = signals.now(); asleep("paused: $why"); marked?.let { (d, _) -> d.attempted() } }
            // #568: a new reason shows at once (the row kept the first one: "game busy" after the player had minimized)
            // 2026-11-23: a finished row stays finished while the dungeons build (a pause is no news there)
            if (why != said) { said = why; if (status.phase != Phase.DONE) emit(status.copy(phase = Phase.PAUSED, note = why)); log("paused: $why") }
            // 2026-11-08 (the native heap profile: the open game files are ~500 MB of index tables, held through every pause): a pause
            // longer than RELEASE_AFTER_MS lets them go; the step waits without them and opens them again when it may build
            if (signals.now() - pausedAt >= RELEASE_AFTER_MS) throw IndoorGenerator.Released()
            signals.sleep(PAUSE_POLL_MS)
        }
        if (paused) {
            if (status.phase != Phase.DONE) emit(status.copy(phase = Phase.BAKING, note = buildingNote()))
            log("resumed")
            if (building) awake("resumed")
            marked?.let { (d, w) -> d.attempting(w, runId, signals.now()) }
        }
    }

    /** D4: the run is making a building or listing them (a pause in between lets the lock go, a resume takes it again) */
    private var building = false
    /** the building being made now and its record (its kill marker: [IndoorDeferrals.attempting]); null between buildings */
    private var marked: Pair<IndoorDeferrals, IndoorBakeStore.Work>? = null

    fun run(): Outcome {
        dataDir.mkdirs()
        runCatching { generator.memory(memo) }
        // 2026-11-24: the input fetches before a building ask too (Play, the game, a stop: no further download)
        runCatching { generator.letGo { runCatching { signals.stop() || signals.gameRunning() }.getOrDefault(true) } }
        var outcome: Outcome? = null
        try {
            return runInner().also { outcome = it }
        } finally {
            // D4: whatever ended it (the end, a stop, a release, a failure thrown past runInner), the lock goes with the run
            building = false
            asleep(outcome?.let { "the run ended (${it.name.lowercase()})" } ?: "the run failed")
            // (letting go for Play or the game: the game files close first, the storage walk waits for the next run)
            if (!hurry) runCatching { stats() }
        }
    }

    /**
     * THE STATS LINE (Track D's T3 parser, tools/interiors_device/parse_log.py): memory and storage from the one approved log read.
     * key=value; written at the end of every run and every [STATS_EVERY] buildings.
     */
    private fun stats() {
        val generations = File(artRoot, "interiors/snapshots").listFiles()?.count { it.isDirectory && it.name.startsWith("g") } ?: 0
        log("stats: store_bytes=${IndoorSchedulerCleanup.size(File(artRoot, "interiors"))} heap_peak_kb=${heapPeak / 1024} " +
            "native_peak_kb=${nativePeak / 1024} buildings=${status.ready} total=${status.total} deferred=${status.deferred} dungeons=${status.dungeons} " +
            "generations=$generations publishes=$publishes publish_failures=$publishFailures publish_max_ms=$maxPublishMs")
    }

    private fun runInner(): Outcome {
        try {
            // review of 2026-11-23 (fix 2's other half): the listing starts from the last run's count (status.json), so a pause or a
            // kill while it lists (minutes after a game update) never leaves 0/0 on disk, the row or the problem report
            val last = lastStatus()
            // 1525: the carry-over's count for this build stays on the row while it builds the rest (another build's goes)
            if (last != null && last.keptFor == build && last.kept >= 0) carried = last.kept
            // a row finished for this very game build and builder stays finished while the dungeons go on (another one lists again)
            if (last != null && last.phase == Phase.DONE && last.finishedFor == finishKey) emit(last.copy(now = ""))
            else emit(Status(Phase.CATALOGUE, last?.ready ?: 0, last?.total ?: 0, last?.deferred ?: 0, rejected = last?.rejected ?: 0))
            gate()
            // D4: listing the buildings is building work too (once per game update): the CPU held awake for it
            building = true
            awake("listing the buildings")
            val listed = try { catalogue() } finally { building = false }
            val catalogue = listed.works
            // T3 run 2: deferrals persist with their input identity (game build, app, algorithm + key + input digest)
            val deferrals = IndoorDeferrals(File(dataDir, "deferred.json"), build, app)
            deferrals.prune(catalogue)
            // "Give up after 3 tries": a run killed inside a building (the marker it left) is that run's failure
            deferrals.killed(catalogue)?.let { (work, run) ->
                if (store.completed(work) != null) return@let
                val e = deferrals.defer(work, KILLED, signals.now(), run = run)
                log("deferred ${work.key}: $KILLED")
                log("deferral ${work.key}: ${triesText(e.tries)}")
            }
            // the dungeons and raids: built last, left out of the numbers and of "finished"
            val dungeons = catalogue.filter { instanceOnly(it, listed.instances) }.mapTo(HashSet()) { it.signature }
            fun counted(w: IndoorBakeStore.Work) = w.signature !in dungeons
            val skip = catalogue.filter { deferrals.skip(it) && store.completed(it) == null }.mapTo(HashSet()) { it.signature }
            val rearmed = catalogue.count { deferrals.entry(it) != null && !deferrals.skip(it) }
            if (skip.isNotEmpty() || rearmed > 0)
                log("deferred from before: ${skip.size} settled (the builder's rule or ${IndoorDeferrals.MAX_TRIES} failed runs, skipped), " +
                    "$rearmed tried again (a failure that may pass, or the game or the builder changed)")
            if (listed.instances == null) log("dungeons and raids: not known (every building counts)")
            else log("dungeons and raids: ${dungeons.size} of ${catalogue.size} buildings, built last and not counted")
            // D1: this run's outcomes - settled (the builder's rule, or the last of 3 failed runs) and the failures that may pass
            val settledNow = HashSet<String>()
            val failedNow = HashSet<String>()
            val queue = IndoorBakeQueue(store, build, catalogue, skip, last = dungeons, onEach = { gate() })
            fun settled(w: IndoorBakeStore.Work) = w.signature in skip || w.signature in settledNow
            val total = catalogue.count(::counted)
            fun ready() = catalogue.count { counted(it) && queue.isReady(it) }
            fun rejected() = catalogue.count { counted(it) && !queue.isReady(it) && settled(it) }
            // still to build or tried again: of the counted ones (finished at 0), of the dungeons ([Status.dungeons])
            fun open(world: Boolean) = catalogue.count { counted(it) == world && !queue.isReady(it) && !settled(it) }
            // Settings' deferred list (f-map-tracker): the deferrals on record for this catalogue, with their reasons
            fun deferredList(): List<String> = deferrals.reasons().take(IndoorProgress.LIST_MAX).map { (k, why) -> IndoorProgress.deferredLine(k, why) }
            var list = deferredList()
            // the row now: FINISHED once no counted building is open, else building (its numbers: the counted ones only)
            fun progress(now: String = ""): Status = if (open(true) == 0)
                Status(Phase.DONE, ready(), total, rejected(), list = list, rejected = rejected(), dungeons = open(false), finishedFor = finishKey)
            else Status(Phase.BAKING, ready(), total, queue.deferred(::counted), note = buildingNote(), now = now, list = list, rejected = rejected())
            // the sweep FIRST (a run whose publications fail still trims), then unchanged buildings are visible at once
            cleanup(catalogue)
            publish(queue, catalogue)
            emit(progress())
            var lastPublish = signals.now()
            var unpublished = 0
            var baked = 0
            try {
                while (true) {
                    gate()
                    // 1525: the game moved to another build meanwhile: this run's buildings are the old build's; its carry-over takes over
                    if (buildChanged()) { log("the game is now another build: this run stops"); throw IndoorGenerator.Stopped() }
                    val here = signals.position()
                    val pick = queue.next(here) ?: break
                    // Settings' "building now": how far the nearest-first pick is from the player
                    emit(progress(IndoorProgress.nowText(if (here == null) null else kotlin.math.sqrt(queue.distance(pick, here)))))
                    val t0 = signals.now()
                    // 2026-11-24: made beside the game (Build now): a failure that may pass is not counted then (the game's memory, its
                    // files being written: the device's state beside it, not the building's) - tried again after the game
                    val besideAtStart = runCatching { signals.beside() }.getOrDefault(false)
                    building = true
                    awake("building ${pick.key}")
                    // a kill from here to the end of the building leaves the marker: the next run counts it (IndoorDeferrals.killed)
                    if (!signals.stop()) { deferrals.attempting(pick, runId, t0); marked = deferrals to pick }
                    val done = try {
                        runCatching { queue.runOne(here, publish = false) { w, dir -> generator.produce(w, dir, yieldPoint) } }
                    } finally { building = false; marked = null; deferrals.attempted() }
                    val failure = done.exceptionOrNull()
                    // 2026-11-24 (Build now in game): the player moved or a fight started: given up, nothing counted, built again when idle
                    if (failure is IndoorGenerator.Cancelled) {
                        queue.putBack(pick)
                        log("cancelled ${pick.key} after ${signals.now() - t0} ms: ${failure.message?.substringAfter(": ")} (not counted; built again when idle)")
                        emit(progress())
                        continue
                    }
                    failure?.let { e ->
                        // 2026-11-11: a release mid-building is not that building's failure (it was deferred for good, "paused long")
                        if (e is IndoorGenerator.Stopped || e is IndoorGenerator.Released) throw e
                        // D1: the builder's rule is settled until the game or the builder changes; anything else is tried again next
                        // run, and settled on its 3rd failed run (the user's "Give up after 3 tries", 2026-11-23)
                        val rule = e is IndoorGenerator.Rejected
                        // (the line's shape is the T3 parsers'; its kind on a line of its own)
                        log("deferred ${pick.key}: ${e.message}")
                        if (!rule && (besideAtStart || runCatching { signals.beside() }.getOrDefault(false))) {
                            log("deferral ${pick.key}: failed beside the game: tried again after it (not counted)")
                            failedNow.add(pick.signature)
                            return@let
                        }
                        val entry = deferrals.defer(pick, e.message ?: e.javaClass.simpleName, signals.now(), rule, run = runId)
                        val gaveUp = !rule && entry.tries >= IndoorDeferrals.MAX_TRIES
                        log("deferral ${pick.key}: ${if (rule) "settled (the builder's rule)" else triesText(entry.tries)}")
                        if (rule || gaveUp) settledNow.add(pick.signature) else failedNow.add(pick.signature)
                        list = deferredList()
                    } ?: run {
                        if (deferrals.entry(pick) != null) { deferrals.clear(pick); list = deferredList() }
                        unpublished++; baked++
                        log("baked ${pick.key} in ${signals.now() - t0} ms")
                        if (baked % STATS_EVERY == 0) runCatching { stats() }
                    }
                    emit(progress())
                    // The building the player stands at shows at once; the rest in batches.
                    val near = done.isSuccess && here != null && queue.distance(pick, here) <= NEAR_YD * NEAR_YD
                    if (unpublished > 0 && (near || signals.now() - lastPublish >= PUBLISH_EVERY_MS)) {
                        publish(queue, catalogue); unpublished = 0; lastPublish = signals.now()
                    }
                    signals.sleep(BREATHER_MS)
                }
            } finally {
                // Finished buildings are kept on disk anyway; this makes them visible before a stop or the end. 2026-11-24: not while it
                // lets go for Play or the game (the game files close first; the next run publishes them before anything else)
                if (unpublished > 0 && !hurry) runCatching { publish(queue, catalogue) }
            }
            cleanup(catalogue)
            // D1 (the user's pick, 2026-11-23: "Done when all mappable built"): FINISHED once every counted building is built or settled
            // (the builder's rule, or 3 failed runs); only a counted failure that may pass keeps it open (tried again next run). A
            // dungeon still open leaves the row finished but the step pending (not stamped: [Outcome.DEFERRED]), so it goes on next time
            val openWorld = catalogue.count { counted(it) && it.signature in failedNow }
            val end = if (openWorld > 0)
                Status(Phase.DEFERRED, ready(), total, queue.deferred(::counted), "$openWorld to retry", list = deferredList(), rejected = rejected())
            else progress()
            emit(end)
            log("finish line: ${ready()} built, ${rejected()} can't be mapped (the builder's rule or ${IndoorDeferrals.MAX_TRIES} failed runs), " +
                "$openWorld to retry; dungeons and raids (not counted): ${catalogue.count { !counted(it) && queue.isReady(it) }} built, " +
                "${open(false)} open")
            return if (failedNow.isNotEmpty()) Outcome.DEFERRED else Outcome.COMPLETE
        } catch (e: IndoorGenerator.Stopped) {
            asleep("stopped")
            // a finished row stays finished (the dungeons go on next time)
            if (status.phase != Phase.DONE) emit(status.copy(phase = Phase.STOPPED, note = "resumes next start", now = ""))
            log("stopped")
            return Outcome.STOPPED
        } catch (e: IndoorGenerator.Released) {
            asleep("paused long")
            log("paused long: the game files let go")
            return Outcome.RELEASED
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
            // 1525: never over a newer game build's publication (decided under the store's lock, with the publication itself)
            if (queue.publishCompleted { !buildChanged() } < 0) { log("publish skipped: the game is now another build"); return }
            // fix 5 (2026-11-23): the game's indoor art index (the wmominimap step) is collected again when this set of roots grows
            runCatching { writeRoots(File(dataDir, ROOTS_FILE), store.lastPublished) }
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

    /** a failed publication's staging folder (the store writes the indexes there first); one publisher at a time (the store's lock) */
    private fun dropStaging() = synchronized(IndoorBakeStore.PUBLISH_LOCK) {
        File(artRoot, "interiors/snapshots").listFiles()?.filter { it.name.startsWith(".pending_") }?.forEach { it.deleteRecursively() }
    }

    /** the sweep, under the store's lock, and never with an old game build's catalogue (1525: it would drop the new one's receipts) */
    private fun cleanup(catalogue: List<IndoorBakeStore.Work>) = runCatching {
        synchronized(IndoorBakeStore.PUBLISH_LOCK) {
            if (buildChanged()) return@runCatching
            val r = IndoorSchedulerCleanup.sweep(artRoot, catalogue, now = signals.now())
            if (r.removed > 0) log("cleanup: $r")
        }
    }.onFailure { log("cleanup failed: ${it.message}") }

    /** 1525: the game folder's build is known and is not this run's */
    private fun buildChanged(): Boolean = runCatching { signals.activeBuild() }.getOrNull().let { it != null && it != build }

    /** the last status on disk; a 0/0 one (1524's device read: written by an older version, kept by every wait) counted from disk */
    private fun lastStatus(): Status? {
        val last = runCatching { Status.of(JSONObject(File(dataDir, STATUS_FILE).readText())) }.getOrNull()
        if (last != null && last.total > 0) return last
        val c = countsOnDisk(artRoot, dataDir, build) ?: return last
        return (last ?: Status(Phase.CATALOGUE, 0, 0)).copy(ready = c.ready, total = c.total, rejected = 0, deferred = 0)
    }

    /**
     * 1525: THE CARRY-OVER AFTER A GAME UPDATE (the user's pick, 2026-11-24, "Reuse what didn't change"). Due ([carryDue]) when the
     * published buildings are another build's (MapView shows none of them until a publication for the game's build exists): the
     * buildings of this build are listed ([catalogue]: the memory makes that a matter of content keys for every unchanged file; cached,
     * so the bake that follows does not list again), the deferrals are carried to it ([IndoorDeferrals.prune]: the builder's turn-downs
     * of unchanged inputs stay settled), and ONE publication makes every unchanged building visible under the new build - the store's
     * own publication (staging + atomic pointer: never a half-published generation; a failure, e.g. a full disk, leaves the old one).
     * Nothing is built here. The status says "Updated for the new game build: N kept, M to rebuild" (FINISHED when nothing is left).
     * Null when not due. A stop ([checkpoint], [Signals.stop]) goes to the caller: nothing of a half listing is kept but the memory.
     */
    fun carryOver(): Carried? {
        dataDir.mkdirs()
        runCatching { generator.memory(memo) }
        if (!carryDue(artRoot, dataDir, build)) { log("carry-over: nothing to carry (published for this build, or nothing built yet)"); return null }
        val last = lastStatus()?.withoutCarry()
        emit(Status(Phase.CATALOGUE, last?.ready ?: 0, last?.total ?: 0, last?.deferred ?: 0, rejected = last?.rejected ?: 0))
        gate()
        val t0 = signals.now()
        val listed = catalogue()
        val works = listed.works
        val deferrals = IndoorDeferrals(File(dataDir, "deferred.json"), build, app)
        deferrals.prune(works)
        val dungeons = works.filter { instanceOnly(it, listed.instances) }.mapTo(HashSet()) { it.signature }
        fun counted(w: IndoorBakeStore.Work) = w.signature !in dungeons
        val skip = works.filter { deferrals.skip(it) && store.completed(it) == null }.mapTo(HashSet()) { it.signature }
        val queue = IndoorBakeQueue(store, build, works, skip, last = dungeons, onEach = { gate() })
        cleanup(works)
        val before = publishes
        publish(queue, works)
        val published = publishes > before
        val total = works.count(::counted)
        val kept = works.count { counted(it) && queue.isReady(it) }
        val rejected = works.count { counted(it) && !queue.isReady(it) && it.signature in skip }
        val rebuild = total - kept - rejected
        val openDungeons = works.count { !counted(it) && !queue.isReady(it) && it.signature !in skip }
        val list = deferrals.reasons().take(IndoorProgress.LIST_MAX).map { (k, why) -> IndoorProgress.deferredLine(k, why) }
        // the row: what is visible now (a failed publication shows nothing new: no "Updated" line, the count as before)
        if (published) carried = kept
        emit(if (published && rebuild == 0)
            Status(Phase.DONE, kept, total, rejected, list = list, rejected = rejected, dungeons = openDungeons, finishedFor = finishKey)
        else Status(Phase.PAUSED, kept, total, queue.deferred(::counted), if (published) CARRIED_NOTE else PUBLISH_FAILED_NOTE,
            list = list, rejected = rejected, dungeons = openDungeons))
        log("carry-over for the new game build $build: $kept kept, $rebuild to rebuild, $rejected can't be mapped (unchanged), " +
            "dungeons and raids (not counted): ${works.count { !counted(it) && queue.isReady(it) }} kept, $openDungeons to build; " +
            "${if (published) "published" else "NOT published"} in ${signals.now() - t0} ms" +
            (memo?.let { "; listing from memory ${it.hits}, read ${it.misses}" } ?: ""))
        return Carried(kept, rebuild, total, rejected, openDungeons, published)
    }

    /** the catalogue and its dungeon and raid maps ([IndoorGenerator.instanceMaps]; null = not known) */
    internal class Listed(val works: List<IndoorBakeStore.Work>, val instances: Set<Int>?)

    /** the generator's list for this build, cached with its dungeon maps: it runs once per game update */
    private fun catalogue(): Listed {
        val file = File(dataDir, "catalogue-$build.json")
        readCatalogue(file)?.let { cached ->
            if (cached.algorithm == generator.algorithm) {
                if (cached.instances != null) return Listed(cached.works, cached.instances)
                // a cache from before 2026-11-23: its dungeon maps asked once (one small table) and kept with it
                val inst = runCatching { generator.instanceMaps() }.getOrNull()
                if (inst != null) runCatching { writeCatalogue(file, cached.algorithm, cached.works, inst) }
                return Listed(cached.works, inst)
            }
        }
        // 1525: the memory across game updates (an unchanged file is neither read nor parsed again); what a stopped listing learned is kept
        val list = try { generator.catalogue(yieldPoint, memo) } catch (e: Throwable) { memo?.let { runCatching { it.save(complete = false) } }; throw e }
        // 1525: an empty listing (the game files not readable yet) is never cached nor published: it would hide every building of the build
        if (list.isEmpty()) { memo?.let { runCatching { it.save(complete = false) } }; throw IllegalStateException(EMPTY_LISTING) }
        memo?.let { m -> if (!m.save(complete = true)) log("the listing's memory was not saved"); m.release() }
        val inst = runCatching { generator.instanceMaps() }.getOrNull()
        writeCatalogue(file, generator.algorithm, list, inst)
        // one cached catalogue: the active build's
        dataDir.listFiles()?.filter { it.name.startsWith("catalogue-") && it.name != file.name }?.forEach { it.delete() }
        return Listed(list, inst)
    }

    companion object {
        /** why the bake waits now, the cheapest test first; null = it may go on */
        internal fun pauseReason(s: Signals): String? = when {
            s.gameRunning() -> PlayGate.GAME_RUNNING
            // 2026-11-24: Build now in game - only while the player is idle (and the device cool)
            s.beside() -> s.besideReason() ?: if (s.hot()) PlayGate.DEVICE_WARM else null
            else -> s.busyReason() ?: when {
                s.moving() -> PlayGate.PLAYER_MOVING
                s.hot() -> PlayGate.DEVICE_WARM
                else -> null
            }
        }

        /** 2026-11-24: a wait that lets the game files go at once, not after [RELEASE_AFTER_MS] (the game's files written; a load or
         *  low memory beside the game) */
        internal fun letGoAtOnce(why: String?): Boolean = why == PlayGate.FILES_BUSY || IndoorIdle.Beside.letGoNow(why)

        const val MEMORY_EVERY_MS = 1_000L
        const val PAUSE_POLL_MS = 2_000L
        /** a pause this long lets the game files go (they are ~500 MB of index tables in memory) */
        const val RELEASE_AFTER_MS = 60_000L
        const val BREATHER_MS = 250L
        const val PUBLISH_EVERY_MS = 60_000L
        const val NEAR_YD = 300.0
        const val STATS_EVERY = 25
        /** D4: the wake lock's hard limit per building (re-armed at each one; a building that hangs lets it lapse) */
        const val WAKE_LIMIT_MS = 10 * 60_000L
        /** fix 5: the published buildings' roots (interiors_sched/roots.json), the wmominimap step's input */
        const val ROOTS_FILE = "roots.json"
        /** the last status as the step publishes it (interiors_sched/status.json, IndoorSchedulerStep.publishStatus) */
        const val STATUS_FILE = "status.json"

        /** the reason a run killed inside a building leaves (IndoorDeferrals.killed): a failure that may pass */
        const val KILLED = "stopped mid-build: the app was closed or ran out of memory"

        /** 1525: the carry-over's note (Settings' progress line: "1600 / 1853 ready · updated for the new game build"); es / fr plain */
        const val CARRIED_NOTE = "updated for the new game build"
        /** 1525: the carry-over's publication failed (a full disk): the bake publishes later */
        const val PUBLISH_FAILED_NOTE = "publish failed: tried again later"

        /** 1525: a listing that found no building at all */
        const val EMPTY_LISTING = "no buildings listed: the game files are not readable yet"

        /**
         * 1525: a carry-over is due for [build]: buildings were published before (receipts on disk) and the publication is another
         * build's, or this build's buildings were never listed (its catalogue not cached). Cheap: two small files and a folder listing
         */
        fun carryDue(artRoot: File, dataDir: File, build: String): Boolean {
            val pointer = runCatching { JSONObject(File(artRoot, "interiors/current.json").readText()) }.getOrNull() ?: return false
            if (pointer.optString("build") == build && File(dataDir, "catalogue-$build.json").isFile) return false
            return File(artRoot, "interiors/receipts").listFiles()?.any { it.isFile && it.name.endsWith(".json") } == true
        }

        /** 1525: a count from disk (the cached catalogue of [build], else the newest one; its buildings with a receipt): never 0/0 */
        internal data class Counts(val ready: Int, val total: Int)

        internal fun countsOnDisk(artRoot: File, dataDir: File, build: String): Counts? = runCatching {
            val file = File(dataDir, "catalogue-$build.json").takeIf { it.isFile }
                ?: dataDir.listFiles()?.filter { it.isFile && it.name.startsWith("catalogue-") && it.name.endsWith(".json") }
                    ?.maxByOrNull { it.lastModified() } ?: return null
            val cached = readCatalogue(file) ?: return null
            val counted = cached.works.filter { !instanceOnly(it, cached.instances) }
            if (counted.isEmpty()) return null
            val receipts = File(artRoot, "interiors/receipts")
            Counts(counted.count { File(receipts, it.signature + ".json").isFile }, counted.size)
        }.getOrNull()

        /** the deferral line of a failure that may pass, by its count of failed runs (the T3 parsers read "tried again next run") */
        internal fun triesText(tries: Int): String = if (tries >= IndoorDeferrals.MAX_TRIES) "can't be mapped ($tries failed runs)"
            else "tried again next run (failed run $tries of ${IndoorDeferrals.MAX_TRIES})"

        /** the game build and builder a finished row holds for (IndoorSchedulerStep asks it before the game files open) */
        fun finishKey(build: String, builder: String): String = "$build|$builder"

        /** placed only on dungeon or raid maps ([instances] null: not known, so never) */
        internal fun instanceOnly(work: IndoorBakeStore.Work, instances: Set<Int>?): Boolean =
            instances != null && work.locations.isNotEmpty() && work.locations.all { it.continent in instances }

        /** D1: the builder's identity a rule-rejection and the finished stamp hold for ([libDigest]: the native library's bytes) */
        fun builderKey(identity: String, libDigest: String): String = "gen|$identity|lib:$libDigest"

        /** fix 5: the roots of the published buildings' keys ("<rootFid>_d<set>_..."), sorted, written atomically */
        internal fun writeRoots(file: File, keys: Collection<String>) {
            val roots = keys.mapNotNullTo(java.util.TreeSet()) { it.substringBefore('_').toIntOrNull() }
            val tmp = File(file.path + ".part")
            tmp.writeText(JSONObject().put("roots", JSONArray(roots.toList())).toString())
            Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

        /** fix 5: the roots [writeRoots] wrote (empty when none were) */
        fun readRoots(file: File): Set<Int> = runCatching {
            val a = JSONObject(file.readText()).optJSONArray("roots") ?: return emptySet()
            (0 until a.length()).mapNotNullTo(HashSet()) { a.optInt(it, -1).takeIf { r -> r > 0 } }
        }.getOrDefault(emptySet())

        internal data class Cached(val algorithm: Int, val works: List<IndoorBakeStore.Work>, val instances: Set<Int>?)

        internal fun readCatalogue(file: File): Cached? = runCatching {
            val o = JSONObject(file.readText())
            require(o.optInt("schema") == 1)
            val a = o.optJSONArray("works") ?: error("no works")
            val inst = o.optJSONArray("instances")?.let { arr -> (0 until arr.length()).mapTo(HashSet()) { arr.getInt(it) } }
            Cached(o.optInt("algorithm"), (0 until a.length()).map { i ->
                val w = a.optJSONObject(i) ?: error("bad work")
                val locs = w.optJSONArray("locations") ?: error("no locations")
                // Work / Location re-check every field (digest format, finite numbers)
                IndoorBakeStore.Work(w.optString("key"), w.optString("inputs"), w.optInt("algorithm"), (0 until locs.length()).map { j ->
                    val l = locs.optJSONArray(j) ?: error("bad location")
                    require(l.length() == 5)
                    IndoorBakeStore.Location(l.optInt(0, -1), l.optDouble(1), l.optDouble(2), l.optDouble(3), l.optDouble(4))
                })
            }, inst)
        }.getOrNull()

        internal fun writeCatalogue(file: File, algorithm: Int, list: List<IndoorBakeStore.Work>, instances: Set<Int>? = null) {
            val works = JSONArray()
            for (w in list) {
                val locs = JSONArray()
                for (l in w.locations) locs.put(JSONArray().put(l.continent).put(l.xmin).put(l.xmax).put(l.ymin).put(l.ymax))
                works.put(JSONObject().put("key", w.key).put("inputs", w.inputs).put("algorithm", w.algorithm).put("locations", locs))
            }
            val tmp = File(file.path + ".part")
            val o = JSONObject().put("schema", 1).put("algorithm", algorithm).put("works", works)
            if (instances != null) o.put("instances", JSONArray(instances.sorted()))
            tmp.writeText(o.toString())
            Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}
