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
 * The game itself ([LauncherHooks], [PlayGate]; issue #108 cluster, 2026-11-11): never beside the game on its own - a run lets the game
 * files go the moment the game is up or launching (or Play is pressed: [LauncherHooks.playPressed], [letGoForPlay]), waits without them,
 * and bakes again once the game has closed. While streaming it bakes (the game is on the PC).
 *
 * THE USER'S RULING OF 2026-11-24 ("The indoor maps just never builds. I want it to show the bar and build on the first screen if people
 * wait there and I also want a button in game to force building in game"): it builds on DS Forever's own first screen too (the start
 * screen with its checks, the chooser) - before the first Play and after a game ([PlayGate.indoorWait]) - and Play stops it at once; and
 * Settings > Performance > Indoor maps > Build now ([buildNow], "Stop": [stopBuildNow]) lets it build DURING PLAY, only while the player
 * is idle ([IndoorIdle.Beside]: standing still, out of a fight, not loading or flying, the memory there), pausing the moment they move.
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
        // (2026-11-24: it builds on the first screen too - no status yet is the moment before the builder's first word)
        if (s == null) return Readiness("$head: $STARTING", false)
        val n = if (s.total > 0) "${s.ready}/${s.total}" else ""
        val ready = if (n.isEmpty()) "" else "$n ready"
        val text = when (s.phase) {
            // 1525: after a game update, what the carry-over kept ("Updated for the new game build: 1,600 kept, 253 to rebuild")
            IndoorScheduler.Phase.CATALOGUE -> carriedLine(s) ?: "Listing buildings"
            // (the settled ones are no retry: "can't be mapped" once finished)
            IndoorScheduler.Phase.BAKING -> (if (n.isEmpty()) "Building" else "Building $n") +
                (s.deferred - s.rejected).let { if (it > 0) " - $it to retry" else "" }
            IndoorScheduler.Phase.PAUSED -> carriedLine(s) ?: listOf(ready, cap(s.note.ifEmpty { "paused" })).filter { it.isNotEmpty() }.joinToString(" - ")
            // D1 (the user's pick, 2026-11-23): "Finished: 1,632 maps, 221 places can't be mapped"
            // the user's "Build last, leave out of count" (2026-11-23): the dungeons and raids go on under the finished row
            IndoorScheduler.Phase.DONE -> finished(s.ready, s.rejected) + if (s.dungeons > 0) DUNGEONS_BUILDING else ""
            IndoorScheduler.Phase.DEFERRED, IndoorScheduler.Phase.STOPPED ->
                listOf(ready, s.note).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { "Stopped" }
        }
        return Readiness("$head: $text", s.phase == IndoorScheduler.Phase.DONE)
    }

    private fun cap(t: String) = t.replaceFirstChar { it.uppercaseChar() }

    /** the row before the builder's first status (es / fr: the i18n's plain words) */
    const val STARTING = "Starting"

    /**
     * 1525 (the user's pick, 2026-11-24, "Reuse what didn't change"): the row after a game update's carry-over, terse: what it kept and
     * what is left to build now (the count goes down as the bake builds the rest); null without a carry-over for this build. es / fr:
     * the i18n templates
     */
    internal fun carriedLine(s: IndoorScheduler.Status): String? {
        if (s.keptFor.isEmpty() || s.kept < 0) return null
        return carried(s.kept, (s.total - s.ready - s.rejected).coerceAtLeast(0))
    }

    /** "Updated for the new game build: 1,600 kept, 253 to rebuild" (nothing left: the first half alone) */
    internal fun carried(kept: Int, rebuild: Int): String =
        "$UPDATED${count(kept)} kept" + if (rebuild > 0) ", ${count(rebuild)} to rebuild" else ""
    const val UPDATED = "Updated for the new game build: "

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
        // 1525: the carry-over's line stays while the game build is the one it was for ([key]: "<game build>|<builder>")
        val keep = s != null && key != null && s.keptFor.isNotEmpty() && s.kept >= 0 && key.startsWith(s.keptFor + "|")
        return IndoorScheduler.Status(IndoorScheduler.Phase.PAUSED, s?.ready ?: 0, s?.total ?: 0, s?.deferred ?: 0, why, rejected = s?.rejected ?: 0,
            kept = if (keep) s!!.kept else -1, keptFor = if (keep) s!!.keptFor else "")
    }

    /**
     * the status the waits and failures start from (on the builder's thread): [status], or - when it has no count (1524's device read:
     * status.json PAUSED 0/0 after the game update, written by an older version and kept by every wait since) - the count of the
     * buildings on disk (IndoorScheduler.countsOnDisk: the cached listing and the receipts)
     */
    private fun countedStatus(ctx: Context): IndoorScheduler.Status? {
        val s = status(ctx)
        if (s != null && s.total > 0) return s
        val c = runCatching {
            val build = GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second ?: return s
            IndoorScheduler.countsOnDisk(ArtHarvest.root(ctx), dataDir(ctx), build)
        }.getOrNull() ?: return s
        return (s ?: IndoorScheduler.Status(IndoorScheduler.Phase.PAUSED, 0, 0)).copy(ready = c.ready, total = c.total)
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

    /** 1525: the carry-over (the art run's thread) and the builder's thread both write status.json: one at a time */
    private val statusLock = Any()

    private fun publishStatus(ctx: Context, s: IndoorScheduler.Status) {
        if (status?.phase != s.phase || status?.note != s.note) DiagLog.event("interiors", "${s.phase} ${s.ready}/${s.total} ${s.note}".trim())
        // 2026-11-24: finished (every counted building built or settled) ends Build now - the dungeons go on after the game, as before
        if (s.phase == IndoorScheduler.Phase.DONE && armed) disarm("finished")
        synchronized(statusLock) {
            status = s
            runCatching {
                val f = File(dataDir(ctx), IndoorScheduler.STATUS_FILE); val tmp = File(f.path + ".part")
                tmp.writeText(s.toJson().toString()); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
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
    fun noteAlive(gameBusy: Boolean, fight: Boolean = gameBusy) {
        val t = System.currentTimeMillis()
        this.gameBusy = gameBusy
        aliveAt = t
        if (fighting && !fight) fightEndAt = t
        fighting = fight
    }

    /** 2026-11-24 (Build now in game): the add-on's last live document (epoch ms; 0 = none), and its word on a fight and when one ended */
    @Volatile private var aliveAt = 0L
    @Volatile private var fighting = false
    @Volatile private var fightEndAt = 0L
    /** the last position was on a flight (the add-on's `onTaxi` on the cheap tick) */
    @Volatile private var taxi = false

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
        taxi = map.optInt("onTaxi", 0) == 1
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
    fun stop() { stopping = true; nudge(); runCatching { IndoorKeepAlive.update(null, why = "stopped") } }

    // ------------------------------------------------------------------ 2026-11-24: Build now in game, and Play

    /**
     * BUILD NOW (the user, 2026-11-24: "I also want a button in game to force building in game"; picks: Settings > Performance > Indoor
     * maps, "Only while I'm idle"). Armed until the build is finished ([publishStatus]: DONE), the builder's thread ends (nothing left this
     * run, a failure, Off) or [stopBuildNow]. In this process only: a /reload keeps it (the process lives on), an app restart does not -
     * the restart may be Android's kill while it built beside the game, and building beside the game is the player's call each session.
     */
    @Volatile var armed = false
        private set

    fun buildNow(context: Context) {
        if (!Settings.indoorBuild) return
        if (!armed) { armed = true; Log.i(TAG, "interiors: Build now pressed: it builds while the player is idle") }
        for (l in listeners) runCatching { l() }
        start(context)
        nudge()
    }

    fun stopBuildNow() {
        if (!armed) return
        disarm("Stop pressed")
        nudge()
    }

    private fun disarm(why: String) {
        armed = false
        runCatching { Log.i(TAG, "interiors: Build now off ($why)") }
        for (l in listeners) runCatching { l() }
    }

    /** the builder builds beside the game now (Build now; the keep-alive's [IndoorKeepAlive.State.beside]) */
    val buildingBeside: Boolean get() = armed && status?.let { it.phase == IndoorScheduler.Phase.BAKING && it.note == PlayGate.IDLE_BUILDING } == true

    /** waits the builder's naps short ([nudge]: Play, Build now, Stop, a stop): a press is seen within a step, not a poll later */
    private val nudges = java.util.concurrent.Semaphore(0)
    internal fun nudge() = nudges.release()
    private fun nap(ms: Long) { if (nudges.tryAcquire(ms, java.util.concurrent.TimeUnit.MILLISECONDS)) nudges.drainPermits() }

    /** Play pressed ([LauncherHooks.playPressed]): the builder lets the game files go at its next step */
    internal fun playPressed() { nudge(); if (filesOpen) runCatching { Log.i(TAG, "interiors: Play pressed: letting the game files go") } }
    /** the launch stopped before the game came up: the builder may build on the start screen again */
    internal fun playStopped(why: String) { nudge(); runCatching { Log.i(TAG, "interiors: the launch stopped ($why): it builds on the start screen again") } }

    /** the builder holds the game files now (between its open and its close) */
    @Volatile internal var filesOpen = false
        private set
    /** when it last let them go (epoch ms) */
    @Volatile private var closedAt = 0L

    /** how long Play waits for the builder to let go of the game files, at most (the launch goes on after it whatever happened) */
    const val PLAY_WAIT_MS = 1_000L

    /**
     * PLAY'S BOUNDED WAIT (2026-11-24: "Pressing Play must not be delayed"): right before the launch, after Play's own steps (the art, the
     * add-ons, the build info), Play waits until the builder has let go of the game files - CascLib's ~500 MB of index tables - for at
     * most [maxMs]; the builder was asked at the press itself. The ms waited; the log line has how long after Play the files went.
     */
    fun letGoForPlay(maxMs: Long = PLAY_WAIT_MS): Long {
        val t0 = System.currentTimeMillis()
        nudge()
        while (filesOpen && System.currentTimeMillis() - t0 < maxMs) runCatching { Thread.sleep(10) }
        val waited = System.currentTimeMillis() - t0
        val press = LauncherHooks.playPressAtMs
        runCatching {
            if (filesOpen) Log.w(TAG, "interiors: Play waited $waited ms and goes on; the builder is still letting go of the game files")
            else Log.i(TAG, "interiors: Play waited $waited ms; the game files " +
                if (press > 0L && closedAt >= press) "were let go ${closedAt - press} ms after Play" else "were not open")
        }
        return waited
    }
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
                val ended = synchronized(this) { (thread === Thread.currentThread()).also { if (it) { thread = null; working = false } } }
                // 2026-11-24: nothing more for this run (finished, a failure, Off, a stop): Build now goes with it
                if (ended && armed) disarm("the builder ended")
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
                val s = countedStatus(ctx)
                val w = waitStatus(s, why, finishKey(ctx))
                if (w != s) publishStatus(ctx, w) else if (status == null) status = w
                Log.i(TAG, "interiors: waiting ($why), the game files not open")
            }
            nap(WAIT_POLL_MS)
        }
    }

    /** one run with the game files open; the outcome, or null after a failure (logged and shown) */
    private fun runOnce(ctx: Context, factory: IndoorGenerator.Factory, signals: AndroidSignals): IndoorScheduler.Outcome? {
        try {
            val game = GameFiles.open(ctx) ?: run {
                // fix 2: a failure keeps the count on the row (never 0/0)
                publishStatus(ctx, failedStatus(countedStatus(ctx), GameFiles.lastWhy ?: "the game files could not be read", finishKey(ctx)))
                return null
            }
            filesOpen = true
            // 2026-11-24: a damaged read beside the running game (Build now; the game may write its Data then) is not marked as damage
            game.besideGame = { LauncherHooks.gameUp() && !PlayGate.streamingNow() }
            return try { game.use { g ->
                factory.open(g).use { gen ->
                    val root = ArtHarvest.root(ctx)
                    // 1.0.22 (the user: "the indoor maps are just not completing ... it does not progress" - 1616/1837, all 221 left DEFERRED by
                    // the generator's own rules, and every new APK re-tried them all: ~10 min of full CPU a start for nothing): a deferral
                    // holds until the GAME build or the BUILDER changes. D1 (2026-11-23): the builder is its algorithm, its native library's
                    // version line and the library's own bytes ([builderKey]; it was the constant "gen", so a new generator never re-armed
                    // them), not the APK: a new APK with the same library keeps them
                    val app = builderKey(ctx)
                    // 1525: the listing's memory across game updates (an unchanged file is neither read nor parsed again)
                    val scheduler = IndoorScheduler(IndoorBakeStore(root), root, g.buildKey, gen, signals, dataDir(ctx),
                        onStatus = { publishStatus(ctx, it) }, log = { Log.i(TAG, it) }, app = app,
                        memo = IndoorCatalogueMemo.forBuilder(dataDir(ctx), app, gen.algorithm))
                    val outcome = scheduler.run()
                    if (!Settings.indoorBuild) offInSettings(ctx)
                    Log.i(TAG, "interiors $outcome; ${scheduler.publishes} publications, max ${scheduler.maxPublishMs} ms; wake lock taken " +
                        "${scheduler.wakeAcquires}x, let go ${scheduler.wakeReleases}x")
                    // D1: finished (every building built or turned down by the builder's rule): stamped for this build and builder, the
                    // step no longer re-runs until one of them changes
                    if (outcome == IndoorScheduler.Outcome.COMPLETE) ArtHarvest.markDetachedDone(ctx, this, g.buildKey, app)
                    outcome
                }
            } } finally {
                filesOpen = false
                closedAt = System.currentTimeMillis()
                LauncherHooks.playPressAtMs.takeIf { it > 0L && closedAt >= it }?.let { Log.i(TAG, "interiors: the game files closed ${closedAt - it} ms after Play") }
            }
        } catch (e: Throwable) {
            // optional: logged and shown on its own row, never in the harvest's state. Throwable, not Exception: an Error
            // (out of memory in a big building) escaping this thread would kill the whole app (Track D's finding)
            Log.w(TAG, "interiors failed", e)
            publishStatus(ctx, failedStatus(runCatching { countedStatus(ctx) }.getOrNull(), "failed: ${e.message ?: e.javaClass.simpleName}; retried next start",
                runCatching { finishKey(ctx) }.getOrNull()))
            return null
        }
    }

    // ------------------------------------------------------------------ 1525: the carry-over after a game update

    /**
     * THE CARRY-OVER (1525; the user's pick, 2026-11-24: "Reuse what didn't change": "Right after an update, check each building's files.
     * Ones that didn't change are carried over straight away, and only changed buildings get rebuilt. Most maps show again within
     * minutes."). Run by [IndoorCarryStep] on the art run's own thread, with the game files the art run already has open (no second
     * open: ~500 MB), right after a game update or at the next start - never beside the game (the art run's own pause: [job]'s
     * checkpoint, at every one of the listing's yield points). [IndoorScheduler.carryOver] lists the new build's buildings (with the
     * listing's memory: content keys for every unchanged file, no reads) and publishes every unchanged one under the new build at once.
     * Nothing is built here: the bake builds the changed ones as before (near you first, dungeons last). True = done (or nothing to do);
     * false = it failed and is tried again at the art run's next pass.
     *
     * Review of 1525: it no longer gives way to a builder run that merely has the game files open. While the art run works, the builder
     * waits at its next yield point ("waiting: game art", [PlayGate.indoorWait]) and lets its files go after a minute, so such a run is
     * never listing or building beside this one; skipping here stamped the carry-over done and left every indoor map hidden until the
     * art run had finished (all the icon packs). A builder that has already listed and published this build makes [IndoorScheduler.carryDue]
     * false; the publications are one at a time ([IndoorBakeStore.PUBLISH_LOCK]).
     */
    internal fun carryOver(job: ArtHarvest.Job): Boolean {
        val ctx = job.ctx
        Settings.init(ctx)
        if (!Settings.indoorBuild) { Log.i(TAG, "carry-over: off in Settings"); return true }
        val factory = factory ?: run { Log.i(TAG, "carry-over: no builder in this build"); return true }
        val root = job.out
        val dir = dataDir(ctx)
        val build = job.game.buildKey
        if (!IndoorScheduler.carryDue(root, dir, build)) { Log.i(TAG, "carry-over: nothing to carry for $build"); return true }
        val app = builderKey(ctx)
        return try {
            val r = factory.open(job.game).use { gen ->
                IndoorScheduler(IndoorBakeStore(root), root, build, gen, CarrySignals { activeBuildOf(ctx) }, dir, onStatus = { publishStatus(ctx, it) },
                    log = { Log.i(TAG, it) }, app = app, memo = IndoorCatalogueMemo.forBuilder(dir, app, gen.algorithm),
                    checkpoint = { job.checkpoint() }).carryOver()
            }
            // nothing left at all (the dungeons too): stamped finished for this build and builder, no bake run needed
            if (r != null && r.published && r.finished && r.dungeons == 0) ArtHarvest.markDetachedDone(ctx, this, build, app)
            r == null || r.published
        } catch (e: InterruptedException) {
            throw e                                                // the art run's own stops (the game, the update, a required step)
        } catch (e: IndoorGenerator.Stopped) {
            // Settings' Off while it listed: the row says so; the builder lists and publishes once it is on again
            offInSettings(ctx); true
        } catch (e: Throwable) {
            Log.w(TAG, "carry-over failed", e)
            publishStatus(ctx, failedStatus(runCatching { countedStatus(ctx) }.getOrNull(), "failed: ${e.message ?: e.javaClass.simpleName}",
                runCatching { finishKey(ctx) }.getOrNull()))
            false
        }
    }

    /**
     * the carry-over's signals: it waits for nothing itself (the art run pauses for the game), and ends for Settings' Off. [build]: the
     * game folder's build now (review of 1525: without it the carry-over's "never over a newer build" check, asked under the store's
     * lock with the publication, always said yes - a game update while it listed could publish the old build over the new one's)
     */
    internal class CarrySignals(private val build: () -> String?) : IndoorScheduler.Signals {
        override fun position(): IndoorBakeQueue.Position? = live
        override fun busy(): Boolean = false
        override fun hot(): Boolean = false
        override fun stop(): Boolean = !Settings.indoorBuild
        override fun now(): Long = System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
        override fun activeBuild(): String? = runCatching { build() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** the game folder's active build now (.build.info, a small file; null = not known): the builder's and the carry-over's check */
    private fun activeBuildOf(ctx: Context): String? =
        runCatching { GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second?.takeIf { it.isNotEmpty() } }.getOrNull()

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

        /**
         * the game up or launching (never while streaming: the game is on the PC), or Play pressed (2026-11-24: from the press itself):
         * the run lets the game files go at once - unless the player pressed Build now and the game is up ([beside])
         */
        override fun gameRunning(): Boolean =
            PlayGate.pressHeld(LauncherHooks.playPressAtMs, LauncherHooks.gameStartSec, now()) || (PlayGate.gameActiveNow() && !beside())

        /** 2026-11-24: Build now, with this device's game up (not streaming, not a launch still under way) */
        override fun beside(): Boolean = armed && LauncherHooks.gameUp() && !streaming()

        private var memAt = Long.MIN_VALUE
        private var memValue: Long? = null
        /** MemAvailable, read at most every [IndoorScheduler.MEMORY_EVERY_MS] (asked at every yield point beside the game) */
        private fun memAvailable(t: Long): Long? {
            if (t - memAt >= IndoorScheduler.MEMORY_EVERY_MS) { memAt = t; memValue = NativeIndoorGenerator.memAvailable() }
            return memValue
        }

        /** 2026-11-24: why it may not build beside the game now (the user's "Only while I'm idle"); the inputs as [IndoorIdle.Beside] reads them */
        override fun besideReason(): String? {
            val t = now()
            val small = runCatching { DeviceTier.lowMemCuts() }.getOrDefault(true)
            val since = { at: Long -> if (at == 0L) Long.MAX_VALUE else (t - at).coerceAtLeast(0L) }
            return IndoorIdle.Beside.reason(IndoorIdle.Beside.Now(
                inWorld = GameLife.worldNow, calmMs = GameLife.calmMs(t), loading = gameBusy && !fighting, aliveAgoMs = since(aliveAt),
                fight = fighting, sinceFightMs = if (fighting) 0L else since(fightEndAt),
                sinceMoveMs = synchronized(motion) { motion.sinceMove(t) }, taxi = taxi,
                memAvailable = memAvailable(t), filesOpen = filesOpen, small = small,
                pressure = IndoorIdle.Beside.pressure(small, MemWatch.pressureAt, GameLife.startedAtMs, t),
                artRunning = ArtHarvest.workingNow(),
                // the files were let go once already in this game (a walk of a minute, a load): the open again waits longer
                reopen = closedAt > 0L && GameLife.startedAtMs > 0L && closedAt >= GameLife.startedAtMs))
        }

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
            // 2026-11-24 (the user: "build on the first screen if people wait there"): no longer only after a game and unseen; never while
            // the game's own files are written (its update, an install, Repair)
            return PlayGate.indoorWait(streaming(), ArtHarvest.workingNow(), PlayGate.gameActiveNow(),
                filesBusy = runCatching { LauncherHooks.filesBusy() }.getOrDefault(false))
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

        /** 1525: the game folder's build now (.build.info, a small file: asked once a building and at each publication, never cached) */
        override fun activeBuild(): String? = activeBuildOf(ctx)

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
        /** 2026-11-24: a nap Play, Build now or Stop cut short ([nudge]) */
        override fun sleep(ms: Long) = nap(ms)
    }
}

/**
 * 1525: THE CARRY-OVER AS AN ART STEP (the user's pick, 2026-11-24: "Reuse what didn't change"). A background step of the art run, right
 * after the maps (ArtHarvest.carryAfterMaps): a game update makes every step due, so it runs right after the update (or at the next start,
 * or when the game has closed - the art run's own rules), with the game files the art run has open. [IndoorSchedulerStep.carryOver] does
 * the work. Its stamp's inputs hold the game build, so the art run's "unchanged files" shortcut never re-stamps it for a new build; a
 * failed carry-over is stamped with "failed" (stale: tried again at the next pass) and never fails the art run.
 */
object IndoorCarryStep : ArtHarvest.Step {
    override val id = "interiorscarry"
    override val version = 1
    override val beforePlay = false
    override val weight = 1
    override fun inputs(ctx: Context, build: String): String = inputKey(build)
    override fun run(job: ArtHarvest.Job) {
        val ok = IndoorSchedulerStep.carryOver(job)
        job.inputKey = if (ok) inputKey(job.game.buildKey) else inputKey(job.game.buildKey) + "|failed"
    }

    /** the stamp's inputs for [build] */
    internal fun inputKey(build: String) = "carry|$build"
}
