package com.jrees.wowcompanion

import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * INTERIORS, THE 7th HARVEST STEP (optional, background; Track C 2026-10-12). Registered in [ArtHarvest.STEPS] as a DETACHED
 * step: [begin] only starts this object's own low-priority thread and returns, so the harvest worker is never held for the hours
 * a whole catalogue can take (the icon step's retries need that worker). It never touches [ArtHarvest.state], so it can never
 * block Play; its status is [status] (Environment Readiness shows it as optional). It stamps itself per game build
 * ([ArtHarvest.markDetachedDone]) only when every building is done; a stop or a deferred building leaves it pending, and the
 * next [ArtHarvest.start] resumes it.
 *
 * Feeds (CompanionHost): [noteAlive] from every live document (with whether the game is busy: a fight, a loading or setup
 * screen), [notePosition] from every "pos" map document (the add-on's inst / wx / wy); [companionUp] / [stop] with the companion.
 * The game itself ([LauncherHooks], [PlayGate]; issue #108 cluster, 2026-11-11): NEVER beside the game on any Thor - a run lets the game
 * files go the moment the game is up or launching, waits without them, and bakes again once the game has closed (not before the first
 * game of the run: the user's 2026-11-07 "sluggish before I ever press update"). While streaming it bakes (the game is on the PC).
 */
object IndoorSchedulerStep : ArtHarvest.Step {
    private const val TAG = "WCINTERIORS"
    override val id = "interiors"
    override val version = 1
    override val beforePlay = false
    override val weight = 1
    override val detached = true

    /** Track B's generator; null until it lands (the step then waits, "generator not in this build") */
    @Volatile internal var factory: IndoorGenerator.Factory? = null

    private const val SAVE_POSITION_MS = 30_000L
    private const val HOT_EVERY_MS = 2_000L

    // ------------------------------------------------------------------ status (Environment Readiness)

    @Volatile internal var status: IndoorScheduler.Status? = null
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** called (on the bake thread) whenever [readiness] may have changed */
    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun offChange(l: () -> Unit) { listeners.remove(l) }

    /** the launcher's optional row: never a blocker, so [done] only picks the tick */
    data class Readiness(val label: String, val done: Boolean)

    fun readiness(ctx: Context): Readiness = readiness(status(ctx))

    internal fun readiness(s: IndoorScheduler.Status?): Readiness {
        val head = "Indoor Maps (Optional)"
        if (s == null) return Readiness("$head: built in the background after Play", false)
        val count = if (s.total > 0) "${s.ready} / ${s.total} ready" else "starting"
        val tail = when (s.phase) {
            IndoorScheduler.Phase.CATALOGUE -> " - listing buildings"
            IndoorScheduler.Phase.BAKING -> if (s.deferred > 0) " - ${s.deferred} to retry" else ""
            IndoorScheduler.Phase.PAUSED -> " - paused (${s.note})"
            IndoorScheduler.Phase.DONE -> ""
            IndoorScheduler.Phase.DEFERRED, IndoorScheduler.Phase.STOPPED -> if (s.note.isEmpty()) "" else " - ${s.note}"
        }
        return Readiness("$head: $count$tail", s.phase == IndoorScheduler.Phase.DONE)
    }

    private fun dataDir(ctx: Context) = File(ArtHarvest.root(ctx), "interiors_sched").also { it.mkdirs() }

    /** the last status, from memory or (before this process ran the step) from disk */
    internal fun status(ctx: Context): IndoorScheduler.Status? = status ?: runCatching {
        IndoorScheduler.Status.of(JSONObject(File(dataDir(ctx), "status.json").readText()))
    }.getOrNull()

    private fun publishStatus(ctx: Context, s: IndoorScheduler.Status) {
        if (status?.phase != s.phase || status?.note != s.note) DiagLog.event("interiors", "${s.phase} ${s.ready}/${s.total} ${s.note}".trim())
        status = s
        runCatching {
            val f = File(dataDir(ctx), "status.json"); val tmp = File(f.path + ".part")
            tmp.writeText(s.toJson().toString()); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        for (l in listeners) runCatching { l() }
    }

    // ------------------------------------------------------------------ feeds

    @Volatile private var live: IndoorBakeQueue.Position? = null
    /** the add-on's last word on a fight / loading / setup screen (the bake no longer runs beside the game: kept for the feed's shape) */
    @Volatile internal var gameBusy = false
        private set
    @Volatile private var stopping = false
    /** while it may not build, how often it looks again (the game files closed meanwhile) */
    private const val WAIT_POLL_MS = 5_000L

    /**
     * Every live document from the add-on (it sends "pos" only while the player moves, a full snapshot every 15 s even when
     * standing still): the game is there, and whether it is busy (a fight, a loading or setup screen).
     */
    fun noteAlive(gameBusy: Boolean) { this.gameBusy = gameBusy }

    /** standing still or moving (b-interiors-stutter): fed by every position, asked by the bake's gate */
    private val motion = IndoorIdle.Motion()

    /** the player is walking now (the bake's own gate; the road harvest waits on it too: b-route-roads) */
    fun playerMoving(): Boolean = LauncherHooks.gameUp() && synchronized(motion) { motion.moving(System.currentTimeMillis()) }

    /** a "pos" map document: the add-on's `inst` (continent / instance map), `wx`, `wy` */
    fun notePosition(map: JSONObject) {
        if (!map.has("inst") || !map.has("wx") || !map.has("wy")) return
        val c = map.optInt("inst", -1); val x = map.optDouble("wx", Double.NaN); val y = map.optDouble("wy", Double.NaN)
        if (c >= 0 && x.isFinite() && y.isFinite()) {
            val p = IndoorBakeQueue.Position(c, x, y)
            live = p
            synchronized(motion) { motion.note(p, System.currentTimeMillis()) }
        }
    }

    /**
     * The companion came up (with the launcher, before Play: CompanionHost.onCreate): (re)starts the step, which stopped when the
     * companion last went away. The game's own start and end come from [LauncherHooks] (the game screen's attach / detach).
     */
    fun companionUp(context: Context) = start(context)

    /** stop cleanly (the companion is closing): the bake ends at its next yield point; finished buildings are kept */
    fun stop() { stopping = true }
    override fun stopDetached() = stop()

    // ------------------------------------------------------------------ running

    @Volatile private var appCtx: Context? = null

    /** Settings' "Indoor maps" switch turned back on: start again (Off stops the bake at its next yield point by itself) */
    fun settingChanged() { if (Settings.indoorBuild) appCtx?.let { start(it) } }

    @Volatile private var thread: Thread? = null
    /** a start that arrived while the thread was still winding down from a stop: it runs once more */
    @Volatile private var again = false

    override fun begin(ctx: Context) = start(ctx)
    override fun run(job: ArtHarvest.Job) = start(job.ctx)

    fun start(context: Context) = synchronized(this) {
        stopping = false
        if (thread != null) { again = true; return }
        val ctx = context.applicationContext
        appCtx = ctx
        thread = Thread({
            try {
                do {
                    again = false
                    body(ctx)
                // decided under the lock [start] takes: a later start sees null and makes a new thread
                } while (synchronized(this) { (again && !stopping).also { if (!it) thread = null } })
            } finally { synchronized(this) { if (thread === Thread.currentThread()) thread = null } }
        }, "interiors-bake").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun body(ctx: Context) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST) }
        Settings.init(ctx)
        if (!Settings.indoorBuild) { offInSettings(ctx); return }
        // stamped for this game build: nothing to do (no catalogue rehash)
        if (runCatching { this !in ArtHarvest.pending(ctx, beforePlay = false) }.getOrDefault(false)) return
        val factory = factory ?: run {
            publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.DEFERRED, 0, 0, note = "generator not in this build"))
            return
        }
        // 2026-11-08 (the native heap profile on the Thor: CascNative.open from here held 499 MB for the whole session while it sat
        // "paused (game busy)"; on the 8 / 12 GB Thors it is ALWAYS busy, so they carried it for nothing - the 1.0.17 low-memory
        // kills): the game files are opened only when it may build, and let go again after a long pause
        val signals = AndroidSignals(ctx)
        while (true) {
            if (!waitUntilItMayBuild(ctx, signals)) return
            val outcome = runOnce(ctx, factory, signals) ?: return
            if (outcome != IndoorScheduler.Outcome.RELEASED) return
        }
    }

    /** holding nothing, until it may build (true) or it never will this session / it was stopped (false) */
    private fun waitUntilItMayBuild(ctx: Context, signals: AndroidSignals): Boolean {
        var said = false
        while (true) {
            if (stopping) return false
            if (!Settings.indoorBuild) { offInSettings(ctx); return false }
            val why = IndoorScheduler.pauseReason(signals) ?: return true
            if (!said) {
                said = true
                val s = status
                publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.PAUSED, s?.ready ?: 0, s?.total ?: 0, s?.deferred ?: 0, why))
                Log.i(TAG, "interiors: waiting ($why), the game files not open")
            }
            runCatching { Thread.sleep(WAIT_POLL_MS) }
        }
    }

    /** one run with the game files open; the outcome, or null after a failure (logged and shown) */
    private fun runOnce(ctx: Context, factory: IndoorGenerator.Factory, signals: AndroidSignals): IndoorScheduler.Outcome? {
        try {
            val game = GameFiles.open(ctx) ?: run {
                publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.DEFERRED, 0, 0,
                    note = GameFiles.lastWhy ?: "the game files could not be read"))
                return null
            }
            return game.use { g ->
                factory.open(g).use { gen ->
                    val root = ArtHarvest.root(ctx)
                    // the app install a deferral is good for: a new APK (even a test build with the same version) retries once
                    // 1.0.22 (the user: "the indoor maps are just not completing ... it does not progress" - 1616/1837, all 221 left DEFERRED by
                    // the generator's own rules, and every new APK re-tried them all: ~10 min of full CPU a start for nothing): a deferral now
                    // holds until the GAME build or the GENERATOR (its algorithm number, part of each deferral's identity) changes
                    val app = "gen"
                    val scheduler = IndoorScheduler(IndoorBakeStore(root), root, g.buildKey, gen, signals, dataDir(ctx),
                        onStatus = { publishStatus(ctx, it) }, log = { Log.i(TAG, it) }, app = app)
                    val outcome = scheduler.run()
                    if (!Settings.indoorBuild) offInSettings(ctx)
                    Log.i(TAG, "interiors $outcome; ${scheduler.publishes} publications, max ${scheduler.maxPublishMs} ms")
                    if (outcome == IndoorScheduler.Outcome.COMPLETE) ArtHarvest.markDetachedDone(ctx, this, g.buildKey)
                    outcome
                }
            }
        } catch (e: Throwable) {
            // optional: logged and shown on its own row, never in the harvest's state. Throwable, not Exception: an Error
            // (out of memory in a big building) escaping this thread would kill the whole app (Track D's finding)
            Log.w(TAG, "interiors failed", e)
            val s = status
            publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.DEFERRED, s?.ready ?: 0, s?.total ?: 0,
                s?.deferred ?: 0, "failed: ${e.message ?: e.javaClass.simpleName}; retried next start"))
            return null
        }
    }

    /** Settings' switch is Off: nothing builds; the row says so and keeps the count of what is ready */
    private fun offInSettings(ctx: Context) {
        val s = status(ctx)
        publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.STOPPED, s?.ready ?: 0, s?.total ?: 0, s?.deferred ?: 0, "off in Settings"))
        Log.i(TAG, "interiors: off in Settings")
    }

    /** thermal status MODERATE+ or headroom >= 0.9 pauses; it resumes only at LIGHT or below and headroom < 0.8 (no flapping) */
    internal fun warm(was: Boolean, thermalStatus: Int, headroom: Float): Boolean =
        if (was) thermalStatus > PowerManager.THERMAL_STATUS_LIGHT || headroom >= 0.8f
        else thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || headroom >= 0.9f

    private class AndroidSignals(private val ctx: Context) : IndoorScheduler.Signals {
        private val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        private val saved = File(File(ArtHarvest.root(ctx), "interiors_sched"), "position.json")
        private var savedAt = 0L
        private var warm = false
        private var cpuHot = false
        private val cpu = IndoorIdle.CpuThermal()
        /** [hot] is asked at every yield point: two binder calls and a sysfs read each time were a CPU cost of their own */
        private var hotAt = Long.MIN_VALUE
        private var hotValue = false
        private var hotLogged: String? = null

        override fun position(): IndoorBakeQueue.Position? {
            val p = live
            if (p == null) return runCatching {
                val o = JSONObject(saved.readText())
                IndoorBakeQueue.Position(o.optInt("c", -1), o.optDouble("x"), o.optDouble("y"))
            }.getOrNull()
            // the last known place survives a restart, so the next start bakes near it first
            if (now() - savedAt >= SAVE_POSITION_MS) {
                savedAt = now()
                runCatching { saved.writeText(JSONObject().put("c", p.continent).put("x", p.x).put("y", p.y).toString()) }
            }
            return p
        }

        private fun streaming() = (InputSink.sink as? TapSink)?.route == "stream"

        /** the game up or launching (never while streaming: the game is on the PC): the run lets the game files go at once */
        override fun gameRunning(): Boolean = !streaming() && PlayGate.gameActiveNow()

        override fun busy(): Boolean {
            // 2026-11-07 (the user: the app "is sluggish as fuck before I ever press update"; the Thor's CPU: interiors-bake 74 % on the
            // start screen): no game yet = the player is on DS Forever's own screens before Play, so the bake waits
            // 1.0.22 (the user: "My indoor maps are not completing for some reason they have been stuck"): while STREAMING the game runs
            // on the PC and the Thor only shows video, so it builds then too; an art run first
            // 2026-11-11 (issues #108 #109 #100 #92 #128: CascNative.open and the bake beside the game on the 16 GB Thor Max, +630 MB native
            // in 5 s, then Android's kill): never while the game is up or launching on ANY model (it was only the 8 / 12 GB ones, 1.0.17's
            // "Pause while playing, always"); it builds once the game has closed. An art run (icons too) still goes first
            return PlayGate.indoorWait(streaming(), ArtHarvest.state is ArtHarvest.State.Running, PlayGate.gameActiveNow(),
                playedThisRun = LauncherHooks.gameStartSec != 0L) != null
        }

        /** the player moved in the last few seconds while the game runs (before Play there is no player: never "moving") */
        override fun moving(): Boolean = LauncherHooks.gameUp() && synchronized(motion) { motion.moving(now()) }

        /**
         * Battery saver, Android's thermal status / headroom, and (b-interiors-stutter) the CPU's own temperature: on the Thor the
         * first two never fired at 86-95 C on cpuss-*, so the sysfs reading is the real cut-off. Re-read at most every [HOT_EVERY_MS];
         * each change of the answer is logged with its inputs.
         */
        override fun hot(): Boolean {
            val t = now()
            if (t - hotAt < HOT_EVERY_MS) return hotValue
            hotAt = t
            val pm = power
            val saver = pm?.isPowerSaveMode == true
            val st = pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            val head = if (pm != null && android.os.Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10).takeIf { it.isFinite() } ?: 0f else 0f
            warm = warm(warm, st, head)
            val c = cpu.maxC()
            cpuHot = IndoorIdle.Heat.hot(cpuHot, c)
            hotValue = saver || warm || cpuHot
            val line = "saver=$saver status=$st headroom=$head cpu=${c ?: "unreadable"} -> ${if (hotValue) "pause" else "ok"}"
            if (hotLogged == null || hotValue != hotLogged!!.endsWith("pause")) { hotLogged = line; Log.i(TAG, "heat: $line") }
            return hotValue
        }

        override fun memory(): Pair<Long, Long> {
            val rt = Runtime.getRuntime()
            return (rt.totalMemory() - rt.freeMemory()) to android.os.Debug.getNativeHeapAllocatedSize()
        }

        /** 2026-11-11: a game that closed no longer stops the bake: that is when it builds now ([gameRunning] lets the files go) */
        override fun stop(): Boolean = stopping || !Settings.indoorBuild      // Off in Settings: ends at the next yield point
        override fun now(): Long = System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}
