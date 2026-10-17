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
 * The game itself ([LauncherHooks]): busy from its start until its first quiet document (at most [STARTING_MS]); the bake stops
 * when a game that was up goes away. Before Play (no game) the device is idle, so it bakes.
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

    /** busy until the first quiet position after the game starts, but no longer than this */
    private const val STARTING_MS = 5 * 60_000L
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
    @Volatile private var busy = false
    /** the game start whose loading has ended (its first quiet document) */
    @Volatile private var quietSince = 0L
    @Volatile private var stopping = false

    /**
     * Every live document from the add-on (it sends "pos" only while the player moves, a full snapshot every 15 s even when
     * standing still): the game is there, and whether it is busy (a fight, a loading or setup screen).
     */
    fun noteAlive(gameBusy: Boolean) {
        busy = gameBusy
        if (!gameBusy) quietSince = LauncherHooks.gameStartSec
    }

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
        try {
            val game = GameFiles.open(ctx) ?: run {
                publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.DEFERRED, 0, 0,
                    note = GameFiles.lastWhy ?: "the game files could not be read"))
                return
            }
            game.use { g ->
                factory.open(g).use { gen ->
                    val root = ArtHarvest.root(ctx)
                    // the app install a deferral is good for: a new APK (even a test build with the same version) retries once
                    val app = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.longVersionCode}/${it.lastUpdateTime}" } }
                        .getOrDefault("?")
                    val scheduler = IndoorScheduler(IndoorBakeStore(root), root, g.buildKey, gen, AndroidSignals(ctx), dataDir(ctx),
                        onStatus = { publishStatus(ctx, it) }, log = { Log.i(TAG, it) }, app = app)
                    val outcome = scheduler.run()
                    if (!Settings.indoorBuild) offInSettings(ctx)
                    Log.i(TAG, "interiors $outcome; ${scheduler.publishes} publications, max ${scheduler.maxPublishMs} ms")
                    if (outcome == IndoorScheduler.Outcome.COMPLETE) ArtHarvest.markDetachedDone(ctx, this, g.buildKey)
                }
            }
        } catch (e: Throwable) {
            // optional: logged and shown on its own row, never in the harvest's state. Throwable, not Exception: an Error
            // (out of memory in a big building) escaping this thread would kill the whole app (Track D's finding)
            Log.w(TAG, "interiors failed", e)
            val s = status
            publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.DEFERRED, s?.ready ?: 0, s?.total ?: 0,
                s?.deferred ?: 0, "failed: ${e.message ?: e.javaClass.simpleName}; retried next start"))
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

        override fun busy(): Boolean {
            if (!LauncherHooks.gameUp()) return false            // no game: the device is ours
            val start = LauncherHooks.gameStartSec
            val loading = start != 0L && quietSince != start && now() < start * 1000 + STARTING_MS
            return busy || loading
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

        /** a game that was up during this run has gone: stop (the next companionUp / harvest start resumes) */
        private var sawGame = false
        override fun stop(): Boolean {
            val up = LauncherHooks.gameUp()
            if (up) sawGame = true
            return stopping || (sawGame && !up) || !Settings.indoorBuild      // Off in Settings: ends at the next yield point
        }
        override fun now(): Long = System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}
