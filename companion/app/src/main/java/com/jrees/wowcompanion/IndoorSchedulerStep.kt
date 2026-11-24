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
 * block Play; its status is [status] (Environment Readiness shows it as optional). It stamps itself per game build and builder
 * ([ArtHarvest.markDetachedDone], [builderKey]) once every building is built or turned down by the builder's own rule (D1, the user's
 * pick 2026-11-23: "Finished: N maps, M places can't be mapped", the row green); a stop or a failure that may pass leaves it pending,
 * and the next [ArtHarvest.start] resumes it.
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

    /** the launcher's optional row: never a blocker, so [done] only picks the tick (D1: finished - the row turns green) */
    data class Readiness(val label: String, val done: Boolean)

    fun readiness(ctx: Context): Readiness = readiness(status(ctx))

    /**
     * #568 / #573 (2026-11-20): the row says what is happening, tersely ("Building 120/1853", "120/1853 ready - Waiting: game running",
     * "Builds when minimized or screen off"); it no longer reads "paused (game busy)" on the Ready screen with the game closed
     */
    internal fun readiness(s: IndoorScheduler.Status?): Readiness {
        val head = "Indoor Maps (Optional)"
        if (s == null) return Readiness("$head: ${cap(PlayGate.AFTER_PLAY)}", false)
        val n = if (s.total > 0) "${s.ready}/${s.total}" else ""
        val ready = if (n.isEmpty()) "" else "$n ready"
        val text = when (s.phase) {
            IndoorScheduler.Phase.CATALOGUE -> "Listing buildings"
            // (the settled ones are no retry: "can't be mapped" once finished)
            IndoorScheduler.Phase.BAKING -> (if (n.isEmpty()) "Building" else "Building $n") +
                (s.deferred - s.rejected).let { if (it > 0) " - $it to retry" else "" }
            IndoorScheduler.Phase.PAUSED -> listOf(ready, cap(s.note.ifEmpty { "paused" })).filter { it.isNotEmpty() }.joinToString(" - ")
            // D1 (the user's pick, 2026-11-23): "Finished: 1,632 maps, 221 places can't be mapped"
            // the user's "Build last, leave out of count" (2026-11-23): the dungeons and raids go on under the finished row
            IndoorScheduler.Phase.DONE -> finished(s.ready, s.rejected) + if (s.dungeons > 0) DUNGEONS_BUILDING else ""
            IndoorScheduler.Phase.DEFERRED, IndoorScheduler.Phase.STOPPED ->
                listOf(ready, s.note).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { "Stopped" }
        }
        return Readiness("$head: $text", s.phase == IndoorScheduler.Phase.DONE)
    }

    private fun cap(t: String) = t.replaceFirstChar { it.uppercaseChar() }

    /** the finished row's terse tail while the dungeons and raids still build (es / fr: the i18n templates) */
    const val DUNGEONS_BUILDING = " + dungeons building"

    /**
     * the status of a build stamped finished (for this game build and builder): the last status's counts as DONE; null when there is
     * none (nothing to show: the row's "after Play" line, never a made-up "Finished")
     */
    internal fun finishedStatus(s: IndoorScheduler.Status?): IndoorScheduler.Status? = when {
        s == null || s.total <= 0 -> null
        s.phase == IndoorScheduler.Phase.DONE -> s
        else -> IndoorScheduler.Status(IndoorScheduler.Phase.DONE, s.ready, s.total, s.rejected, list = s.list, rejected = s.rejected,
            dungeons = s.dungeons, finishedFor = s.finishedFor)
    }

    /**
     * what the row shows while the step waits to build ([why]): a row finished for this very game build and builder ([key]: the
     * scheduler's finishKey; null = not known) stays finished - the dungeons and raids build after it (2026-11-23); anything else waits
     * with the last count (fix 2: never 0/0)
     */
    internal fun waitStatus(s: IndoorScheduler.Status?, why: String, key: String?): IndoorScheduler.Status {
        if (s != null && key != null && s.finishedFor.isNotEmpty() && s.finishedFor == key) return finishedStatus(s) ?: s
        return IndoorScheduler.Status(IndoorScheduler.Phase.PAUSED, s?.ready ?: 0, s?.total ?: 0, s?.deferred ?: 0, why, rejected = s?.rejected ?: 0)
    }

    /** a failure before or around a run ([note]): a row finished for this build and builder stays finished; else the count with it */
    internal fun failedStatus(s: IndoorScheduler.Status?, note: String, key: String?): IndoorScheduler.Status {
        if (s != null && key != null && s.finishedFor.isNotEmpty() && s.finishedFor == key) return finishedStatus(s) ?: s
        return IndoorScheduler.Status(IndoorScheduler.Phase.DEFERRED, s?.ready ?: 0, s?.total ?: 0, s?.deferred ?: 0, note = note, rejected = s?.rejected ?: 0)
    }

    /** Settings' Off: the row says so with the count, and keeps what it was finished for (On again: finished again) */
    internal fun offStatus(s: IndoorScheduler.Status?): IndoorScheduler.Status =
        IndoorScheduler.Status(IndoorScheduler.Phase.STOPPED, s?.ready ?: 0, s?.total ?: 0, s?.deferred ?: 0, "off in Settings",
            rejected = s?.rejected ?: 0, dungeons = s?.dungeons ?: 0, finishedFor = s?.finishedFor.orEmpty())

    /** the finishKey of the installed game build and this builder, before the game files open (null: no game build found) */
    private fun finishKey(ctx: Context): String? = runCatching {
        GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second?.let { IndoorScheduler.finishKey(it, builderKey(ctx)) }
    }.getOrNull()

    /** D1's finished line: the maps built and the places the builder's own rule cannot map (none: the second half left out) */
    internal fun finished(ready: Int, rejected: Int): String =
        "Finished: ${count(ready)} map${if (ready == 1) "" else "s"}" +
            if (rejected > 0) ", ${count(rejected)} place${if (rejected == 1) "" else "s"} can't be mapped" else ""

    /** 1632 -> "1,632" (the user's own spelling of the line; the same digits in every language, as the other counts) */
    internal fun count(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

    private fun dataDir(ctx: Context) = File(ArtHarvest.root(ctx), "interiors_sched").also { it.mkdirs() }

    /** the last status, from memory or (before this process ran the step) from disk (fix 2: also the start-up waits' count) */
    internal fun status(ctx: Context): IndoorScheduler.Status? = status ?: runCatching {
        IndoorScheduler.Status.of(JSONObject(File(dataDir(ctx), IndoorScheduler.STATUS_FILE).readText()))
    }.getOrNull()

    private fun publishStatus(ctx: Context, s: IndoorScheduler.Status) {
        if (status?.phase != s.phase || status?.note != s.note) DiagLog.event("interiors", "${s.phase} ${s.ready}/${s.total} ${s.note}".trim())
        status = s
        runCatching {
            val f = File(dataDir(ctx), IndoorScheduler.STATUS_FILE); val tmp = File(f.path + ".part")
            tmp.writeText(s.toJson().toString()); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        for (l in listeners) runCatching { l() }
        // issue 568: the quiet service follows the builder (on while it has work after a game, off for a game, Off, the end)
        runCatching { IndoorKeepAlive.update(ctx, why = "${s.phase.name.lowercase()} ${s.note}".trim()) }
    }

    // ------------------------------------------------------------------ feeds

    @Volatile private var live: IndoorBakeQueue.Position? = null
    /** the add-on's last word on a fight / loading / setup screen (the bake no longer runs beside the game: kept for the feed's shape) */
    @Volatile internal var gameBusy = false
        private set
    @Volatile private var stopping = false
    /** the thread is past its "nothing to do" checks (Off, stamped, no generator) with buildings to make: [IndoorKeepAlive] */
    @Volatile private var working = false
    /** issue 568: the builder has work now (its thread runs past the checks, and no stop is asked) */
    val hasWork: Boolean get() = working && !stopping && thread != null
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

    /**
     * a "pos" map document: the add-on's `cont` (UnitPosition's map id: the catalogue's continent), `wx`, `wy`. Fix 6 (2026-11-23):
     * this read `inst` as the continent since Track C, but the add-on sends `inst` as a BOOLEAN (IsInInstance, Map.lua) and the map id
     * as `cont`, so "build what's near me first" never had a position
     */
    fun notePosition(map: JSONObject) {
        val p = positionOf(map) ?: return
        live = p
        synchronized(motion) { motion.note(p, System.currentTimeMillis()) }
    }

    /** the position [notePosition] keeps from a "pos" document, or null (no continent or no finite world position) */
    internal fun positionOf(map: JSONObject): IndoorBakeQueue.Position? {
        if (!map.has("cont") || !map.has("wx") || !map.has("wy")) return null
        val c = (map.opt("cont") as? Number)?.toDouble() ?: return null
        if (c != Math.floor(c) || c < 0 || c > Int.MAX_VALUE) return null
        val x = map.optDouble("wx", Double.NaN); val y = map.optDouble("wy", Double.NaN)
        return if (x.isFinite() && y.isFinite()) IndoorBakeQueue.Position(c.toInt(), x, y) else null
    }

    /** the last live position (the tests) */
    internal fun livePosition(): IndoorBakeQueue.Position? = live

    /**
     * WHO STARTS AND STOPS THE STEP (#568 / #573, 2026-11-20). Since 1.0.25 the bake may build only while nobody looks at DS Forever
     * (after a game: the screen off or the launcher not in front, [AndroidSignals.quiet]), but exactly then the launcher hides the
     * bottom screen (MainActivity's onPause / onStop / leave hint -> DsForeverScreen.hide -> CompanionHost.onDestroy), and that
     * stopped the step; it came back only with the bottom screen, i.e. with the launcher in front again. The two never overlapped,
     * so the indoor maps never built. Now the step belongs to the process, not to the bottom screen: the screen going away leaves it
     * running (in-process, on its own low-priority thread, as the art harvest's worker; DS Forever has no service). It stops only
     * for Settings' Off ([Signals.stop]), the app's own exit ([appExit]) and a game update ([ArtHarvest.restart]); the game's start
     * makes it let go of everything at once ([PlayGate.gameActiveNow]: the game files and the generator closed, the thread waits with
     * nothing open, never building while WoW runs). Pure: the tests drive [on].
     */
    internal enum class Event { COMPANION_UP, COMPANION_GONE, GAME_CLOSED, APP_EXIT }
    internal enum class Action { START, KEEP, STOP }

    internal fun on(e: Event): Action = when (e) {
        Event.COMPANION_UP, Event.GAME_CLOSED -> Action.START
        Event.COMPANION_GONE -> Action.KEEP                  // #568: was a stop
        Event.APP_EXIT -> Action.STOP
    }

    private fun handle(e: Event, context: Context?) {
        when (on(e)) {
            Action.START -> if (context != null && factory != null) start(context)
            Action.STOP -> stop()
            Action.KEEP -> Log.i(TAG, "interiors: ${e.name.lowercase()}, the step keeps running")
        }
        // issue 568: the game's close is an in-front moment: the quiet service may be asked for now (the builder's thread also asks
        // once it has looked at its work); the app's exit ends it
        when (e) {
            Event.GAME_CLOSED -> runCatching { IndoorKeepAlive.gameClosed(context) }
            Event.APP_EXIT -> runCatching { IndoorKeepAlive.update(context, why = "the app's exit") }
            else -> {}
        }
    }

    /**
     * The companion came up (with the launcher, before Play: CompanionHost.onCreate): starts the step if it is not running (a running
     * one goes on). The game's own start and end come from [LauncherHooks] (the game screen's attach / detach).
     */
    fun companionUp(context: Context) = handle(Event.COMPANION_UP, context)

    /** the bottom screen went (CompanionHost.onDestroy: the launcher went to the background or the screen turned off): it goes on */
    fun companionGone() = handle(Event.COMPANION_GONE, null)

    /** the game screen went (DsForeverScreen.detachGame): a step that ended meanwhile starts again; a waiting one sees it itself */
    fun gameClosed(context: Context) = handle(Event.GAME_CLOSED, context)

    /**
     * DS Forever closes for good (MainActivity, right before the process ends). The app's own close is no failure of the building being
     * made: its kill marker goes ("Give up after 3 tries" counts only kills it did not ask for: Android's, a crash)
     */
    fun appExit() {
        appCtx?.let { c -> runCatching { File(dataDir(c), IndoorDeferrals.ATTEMPT_FILE).delete() } }
        handle(Event.APP_EXIT, null)
    }

    /** stop cleanly: the bake ends at its next yield point; finished buildings are kept */
    fun stop() { stopping = true; runCatching { IndoorKeepAlive.update(null, why = "stopped") } }
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
        // issue 568 review: a thread that ended normally leaves [working] set (its loop clears [thread] first); the new one has not
        // looked at its work yet, so the quiet service is not asked for until it has
        working = false
        thread = Thread({
            try {
                do {
                    again = false
                    body(ctx)
                // decided under the lock [start] takes: a later start sees null and makes a new thread
                } while (synchronized(this) { (again && !stopping).also { if (!it) thread = null } })
            } finally {
                synchronized(this) { if (thread === Thread.currentThread()) { thread = null; working = false } }
                // issue 568: the build is done, failed, stopped or has nothing to do: the quiet service goes
                runCatching { IndoorKeepAlive.update(ctx, why = "the builder ended") }
            }
        }, "interiors-bake").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun body(ctx: Context) {
        working = false
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST) }
        Settings.init(ctx)
        if (!Settings.indoorBuild) { offInSettings(ctx); return }
        // stamped for this game build AND this builder (D1: finished; ArtHarvest.pending compares the stamp's inputs with [inputs]):
        // nothing to do (no catalogue rehash). A new builder or game build re-arms the rule's rejections
        if (runCatching { this !in ArtHarvest.pending(ctx, beforePlay = false) }.getOrDefault(false)) {
            // the row keeps its finished line (fix 2: from disk when this process has not run the step). Review of 2026-11-23: the
            // stamp says finished, so the row does too - Settings' switch turned Off and On again left "off in Settings" on it for good
            val s = status(ctx)
            val done = finishedStatus(s)
            if (done != null && done != s) publishStatus(ctx, done) else if (s != null && status == null) status = s
            return
        }
        val factory = factory ?: run {
            publishStatus(ctx, IndoorScheduler.Status(IndoorScheduler.Phase.DEFERRED, 0, 0, note = "generator not in this build"))
            return
        }
        working = true
        runCatching { IndoorKeepAlive.update(ctx, why = "the builder has work") }
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
        var said: String? = null
        while (true) {
            if (stopping) return false
            if (!Settings.indoorBuild) { offInSettings(ctx); return false }
            val why = IndoorScheduler.pauseReason(signals) ?: return true
            // #568: each new reason shows (it kept the first: "game busy" on the Ready screen, still after the player had minimized)
            if (why != said) {
                said = why
                // fix 2 (2026-11-23): the count from disk when this process has not run the step yet (it published 0/0 at every start)
                // 2026-11-23: finished for this game build and builder (the dungeons and raids still to build): the row stays finished
                val s = status(ctx)
                val w = waitStatus(s, why, finishKey(ctx))
                if (w != s) publishStatus(ctx, w) else if (status == null) status = w
                Log.i(TAG, "interiors: waiting ($why), the game files not open")
            }
            runCatching { Thread.sleep(WAIT_POLL_MS) }
        }
    }

    /** one run with the game files open; the outcome, or null after a failure (logged and shown) */
    private fun runOnce(ctx: Context, factory: IndoorGenerator.Factory, signals: AndroidSignals): IndoorScheduler.Outcome? {
        try {
            val game = GameFiles.open(ctx) ?: run {
                // fix 2: a failure keeps the count on the row (never 0/0)
                publishStatus(ctx, failedStatus(status(ctx), GameFiles.lastWhy ?: "the game files could not be read", finishKey(ctx)))
                return null
            }
            return game.use { g ->
                factory.open(g).use { gen ->
                    val root = ArtHarvest.root(ctx)
                    // 1.0.22 (the user: "the indoor maps are just not completing ... it does not progress" - 1616/1837, all 221 left DEFERRED by
                    // the generator's own rules, and every new APK re-tried them all: ~10 min of full CPU a start for nothing): a deferral
                    // holds until the GAME build or the BUILDER changes. D1 (2026-11-23): the builder is its algorithm, its native library's
                    // version line and the library's own bytes ([builderKey]; it was the constant "gen", so a new generator never re-armed
                    // them), not the APK: a new APK with the same library keeps them
                    val app = builderKey(ctx)
                    val scheduler = IndoorScheduler(IndoorBakeStore(root), root, g.buildKey, gen, signals, dataDir(ctx),
                        onStatus = { publishStatus(ctx, it) }, log = { Log.i(TAG, it) }, app = app)
                    val outcome = scheduler.run()
                    if (!Settings.indoorBuild) offInSettings(ctx)
                    Log.i(TAG, "interiors $outcome; ${scheduler.publishes} publications, max ${scheduler.maxPublishMs} ms; wake lock taken " +
                        "${scheduler.wakeAcquires}x, let go ${scheduler.wakeReleases}x")
                    // D1: finished (every building built or turned down by the builder's rule): stamped for this build and builder, the
                    // step no longer re-runs until one of them changes
                    if (outcome == IndoorScheduler.Outcome.COMPLETE) ArtHarvest.markDetachedDone(ctx, this, g.buildKey, app)
                    outcome
                }
            }
        } catch (e: Throwable) {
            // optional: logged and shown on its own row, never in the harvest's state. Throwable, not Exception: an Error
            // (out of memory in a big building) escaping this thread would kill the whole app (Track D's finding)
            Log.w(TAG, "interiors failed", e)
            publishStatus(ctx, failedStatus(status(ctx), "failed: ${e.message ?: e.javaClass.simpleName}; retried next start",
                runCatching { finishKey(ctx) }.getOrNull()))
            return null
        }
    }

    /** Settings' switch is Off: nothing builds; the row says so and keeps the count of what is ready */
    private fun offInSettings(ctx: Context) {
        publishStatus(ctx, offStatus(status(ctx)))
        Log.i(TAG, "interiors: off in Settings")
    }

    // ------------------------------------------------------------------ the builder's identity (D1)

    @Volatile private var builderKeyCache: String? = null

    /**
     * the builder a rule-rejection and the finished stamp hold for: [NativeIndoorGenerator.baseIdentity] (its algorithm, the library's
     * version line) and the first 16 hex of the SHA-256 of libinteriors.so as installed (the launcher extracts its native libraries:
     * extractNativeLibs). A new library re-arms them; a new APK with the same library does not (1.0.22's ~10 min a start)
     */
    internal fun builderKey(ctx: Context): String = builderKeyCache ?: run {
        val lib = runCatching { File(ctx.applicationInfo.nativeLibraryDir, "libinteriors.so") }.getOrNull()
        val digest = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            lib!!.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
            md.digest().take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.getOrDefault("unread")
        IndoorScheduler.builderKey(NativeIndoorGenerator.baseIdentity(), digest).also { if (digest != "unread") builderKeyCache = it }
    }

    /** the stamp's inputs (ArtHarvest.pending / unchanged): the builder, so a new one re-arms a finished build */
    override fun inputs(ctx: Context, build: String): String? = runCatching { builderKey(ctx) }.getOrNull()

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

        private fun streaming() = PlayGate.streamingNow()

        /** the game up or launching (never while streaming: the game is on the PC): the run lets the game files go at once */
        override fun gameRunning(): Boolean = PlayGate.gameActiveNow()

        /**
         * Review of this fix (2026-11-11): after a game, nobody is looking at DS Forever's launcher screen - the device screen is off, or
         * the launcher is not the window in front (the user went to the home screen). The 2026-11-07 ruling's sluggishness was on that
         * very screen, so the bake builds there only then
         */
        private fun quiet(): Boolean = power?.isInteractive == false || !LauncherHooks.inFront()

        override fun busy(): Boolean = busyReason() != null

        override fun busyReason(): String? {
            // 2026-11-07 (the user: the app "is sluggish as fuck before I ever press update"; the Thor's CPU: interiors-bake 74 % on the
            // start screen): no game yet = the player is on DS Forever's own screens before Play, so the bake waits
            // 1.0.22 (the user: "My indoor maps are not completing for some reason they have been stuck"): while STREAMING the game runs
            // on the PC and the Thor only shows video, so it builds then too; an art run first
            // 2026-11-11 (issues #108 #109 #100 #92 #128: CascNative.open and the bake beside the game on the 16 GB Thor Max, +630 MB native
            // in 5 s, then Android's kill): never while the game is up or launching on ANY model (it was only the 8 / 12 GB ones, 1.0.17's
            // "Pause while playing, always"); it builds once the game has closed, while nobody looks at the launcher screen ([quiet]).
            // An art run (icons too) still goes first - one that really works, not one parked for the game ([ArtHarvest.workingNow])
            return PlayGate.indoorWait(streaming(), ArtHarvest.workingNow(), PlayGate.gameActiveNow(),
                playedThisRun = LauncherHooks.gameStartSec != 0L, quiet = quiet())
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

        /**
         * D4 (the user's pick, 2026-11-23: "Keep it awake while building"): a PARTIAL wake lock (the CPU only; the screen still goes
         * off), held by the scheduler only while a building is being made, with its time limit; WAKE_LOCK is already declared
         * (dsforever's manifest, the stream's Wi-Fi lock): no new permission. Not reference counted: one release always lets it go
         */
        private val lock: IndoorScheduler.WakeLock? by lazy {
            val wl = runCatching {
                power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSForever:indoor-maps")?.apply { setReferenceCounted(false) }
            }.getOrNull() ?: return@lazy null
            object : IndoorScheduler.WakeLock {
                override fun acquire(timeoutMs: Long) = wl.acquire(timeoutMs)
                override fun release() { if (wl.isHeld) wl.release() }
            }
        }
        override fun wakeLock(): IndoorScheduler.WakeLock? = lock

        /** 2026-11-11: a game that closed no longer stops the bake: that is when it builds now ([gameRunning] lets the files go) */
        override fun stop(): Boolean = stopping || !Settings.indoorBuild      // Off in Settings: ends at the next yield point
        override fun now(): Long = System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}
