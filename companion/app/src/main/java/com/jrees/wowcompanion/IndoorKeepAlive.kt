package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log

/**
 * THE INDOOR MAPS' QUIET SERVICE (issue 568, the user's ruling 2026-11-23: "a quiet notification-backed foreground service runs ONLY
 * while the indoor-maps builder builds").
 *
 * Why: since 1.0.38 the builder may run after a game, while DS Forever is minimized or the screen is off - exactly when Android counts
 * a DS Forever with no service as a CACHED app (importance 400). Android's background CPU check (ActivityManagerService
 * checkExcessivePowerUsage, every 5 min, for processes at PROCESS_STATE_HOME or below) then kills it for the builder's 74-94 % of a core:
 * 57 distinct "EXCESSIVE_RESOURCE_USAGE (importance 400) ... excessive cpu X during ~300000 dur=D limit=L" exits in the saved reports,
 * every one cached, every limit the one for its time in the background (25 % in the first 10 min, 10 % to 15 min, 2 % after). A
 * foreground service lifts the process to PROCESS_STATE_FOREGROUND_SERVICE (importance 125), which that check never looks at, and
 * keeps it out of the cached-app freezer too.
 *
 * When: exactly while the builder builds with DS Forever's screen not shown (minimized, the screen off; 2026-11-24: before the first
 * game too, as the builder builds then now) - and while it builds beside the game with Build now (the game minimized: the same check
 * would count the game's own host as a cached app). On DS Forever's own screen it is in front anyway: no service. Android 12+ lets an app start a foreground service only
 * while it is in front (or a few seconds after it was), so it is asked for at the moment DS Forever leaves the front - the launcher's
 * leave hint, pause and stop ([shown] false) - and by the builder's own status changes right after (a refusal is not asked again
 * until the next such moment). It stops when DS Forever's screen is shown again (the builder waits then), when the build is done or
 * fails (the builder's thread ends), when a game starts (Play, the launch, the game screen), while streaming, when Settings > Indoor
 * maps > Build in the background is Off, and at the app's exit. LIGHTER MODE (the
 * 8 / 12 GB Thors, or the setting): Android's own critical memory warnings while it runs stand it down until the next game's close, so a
 * short device gets DS Forever's memory back the way it did before. The builder's own rules (never beside the game, heat, art first)
 * are unchanged: this only keeps Android from killing it.
 *
 * The service itself is the launcher's ([service]: app.gamenative.IndoorBuildService, a silent low-importance notification "Building
 * indoor maps" with its progress). No new permission: FOREGROUND_SERVICE and FOREGROUND_SERVICE_SPECIAL_USE are already declared.
 * Pure parts ([wanted], [standsDown], [mayAsk]) are what the off-device tests drive (IndoorKeepAliveTest).
 */
object IndoorKeepAlive {
    private const val TAG = "WCINTERIORS"

    /** the launcher's foreground service; null = none (the companion app on its own): nothing is asked of Android */
    interface Service {
        /** asks Android for the service; false = refused (Android 12+: DS Forever not in front) */
        fun start(ctx: Context, ready: Int, total: Int): Boolean
        /** the notification's progress (only while running) */
        fun progress(ctx: Context, ready: Int, total: Int)
        fun stop(ctx: Context)
    }
    @Volatile var service: Service? = null

    // ------------------------------------------------------------------ pure

    /**
     * [setting]: Build in the background; [hasWork]: the builder's thread runs with buildings to make (not stamped, not stopping);
     * [gameActive]: the game is up, launching or held by Play; [streaming]: the game runs on the PC; [shown]: DS Forever's screen is
     * shown (in front: no service needed); [stoodDown]: lighter mode's stand-down since the last close; [beside]: 2026-11-24, the builder
     * builds beside the running game now (Build now, the player idle). 2026-11-24: no "a game has closed in this run" any more - the
     * builder builds before the first game too (the user's "build on the first screen if people wait there")
     */
    data class State(val setting: Boolean, val hasWork: Boolean, val gameActive: Boolean, val streaming: Boolean,
                     val shown: Boolean, val stoodDown: Boolean = false, val beside: Boolean = false, val exiting: Boolean = false)

    /** [State.exiting] (feat/exit-now): DS Forever's own exit runs - no foreground service is asked for as the process ends */
    fun wanted(s: State): Boolean =
        s.setting && s.hasWork && (!s.gameActive || s.beside) && !s.streaming && !s.shown && !s.stoodDown && !s.exiting

    /** lighter mode only: Android's running-critical warning or onLowMemory ([MemWatch.LOW_MEMORY]) */
    fun standsDown(lowMemory: Boolean, level: Int): Boolean =
        lowMemory && (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == MemWatch.LOW_MEMORY)

    /** an in-front moment always asks; any other only while no refusal came since the last in-front moment */
    fun mayAsk(front: Boolean, refusedSinceFront: Boolean): Boolean = front || !refusedSinceFront

    /** the notification's progress is refreshed at most this often (and only when the count moved) */
    const val PROGRESS_EVERY_MS = 5_000L
    fun progressDue(lastReady: Int, ready: Int, lastAtMs: Long, nowMs: Long): Boolean = ready != lastReady && nowMs - lastAtMs >= PROGRESS_EVERY_MS

    /** what [Machine.next] asks of the service */
    enum class Op { NONE, START, STOP, PROGRESS }

    /**
     * The service's on / off bookkeeping, pure (the tests replay an evening through it): [next] for each moment, [started] with
     * Android's answer to a [Op.START], [lost] when the service ended by itself.
     */
    class Machine {
        @Volatile var running = false
            private set
        /** Android refused a start since the last in-front moment */
        var refused = false
            private set
        private var lastReady = -1
        private var lastAt = 0L

        fun next(want: Boolean, front: Boolean, ready: Int, nowMs: Long): Op {
            if (front) refused = false
            return when {
                want && !running -> if (mayAsk(front, refused)) Op.START else Op.NONE
                !want && running -> { running = false; Op.STOP }
                want && running -> if (progressDue(lastReady, ready, lastAt, nowMs)) { lastReady = ready; lastAt = nowMs; Op.PROGRESS } else Op.NONE
                else -> Op.NONE
            }
        }

        /** Android's answer to a start; true = the first refusal since the last in-front moment (logged once) */
        fun started(ok: Boolean, ready: Int, nowMs: Long): Boolean {
            if (ok) { running = true; lastReady = ready; lastAt = nowMs; return false }
            return (!refused).also { refused = true }
        }

        /** the service ended by itself; true = it was counted as running */
        fun lost(): Boolean = running.also { running = false }
    }

    // ------------------------------------------------------------------ live

    private val machine = Machine()
    val running: Boolean get() = machine.running
    @Volatile private var stoodDown = false
    /** DS Forever's launcher screen is shown (MainActivity resumed); true until its first leave */
    @Volatile var screenShown = true
        private set
    @Volatile private var appCtx: Context? = null
    private var trimHooked = false

    private fun state(): State = State(
        setting = Settings.indoorBuild, hasWork = IndoorSchedulerStep.hasWork, gameActive = PlayGate.gameActiveNow(),
        streaming = PlayGate.streamingNow(), shown = screenShown, stoodDown = stoodDown, beside = IndoorSchedulerStep.buildingBeside,
        exiting = IndoorSchedulerStep.exiting || ExitNow.underWay)

    /**
     * DS Forever's launcher screen shown again (MainActivity.onResume: off, the builder waits) or leaving it (onUserLeaveHint, onPause,
     * onStop: the last moments Android lets the service start before the builder builds)
     */
    fun shown(context: Context?, shown: Boolean, why: String) { screenShown = shown; update(context, front = true, why = why) }

    /** the game closed (in front, normally): a new chance after lighter mode's stand-down */
    fun gameClosed(context: Context?) { stoodDown = false; update(context, front = true, why = "the game closed") }

    /** a game is starting (Play, a launch step, the game screen): it goes at once */
    fun gameStarting() = update(null, front = false, why = "a game is starting")

    /** the builder's status changed, its thread ended, or Settings' switch moved; [front]: DS Forever is (just) in front */
    @Synchronized fun update(context: Context?, front: Boolean = false, why: String) {
        context?.applicationContext?.let { appCtx = it }
        val ctx = appCtx ?: return
        val svc = service ?: return
        val want = runCatching { wanted(state()) }.getOrDefault(false)
        val s = IndoorSchedulerStep.status
        val ready = s?.ready ?: 0; val total = s?.total ?: 0
        val now = System.currentTimeMillis()
        when (machine.next(want, front, ready, now)) {
            Op.START -> {
                hookTrim()
                val ok = runCatching { svc.start(ctx, ready, total) }.getOrDefault(false)
                if (machine.started(ok, ready, now)) say("keep-alive refused by Android ($why): DS Forever was not in front; asked again when it is")
                else if (ok) say("keep-alive on ($why)")
            }
            Op.STOP -> { runCatching { svc.stop(ctx) }; say("keep-alive off ($why)") }
            Op.PROGRESS -> runCatching { svc.progress(ctx, ready, total) }
            Op.NONE -> {}
        }
    }

    /** the launcher's service could not go to the foreground after all (its own start failed): counted as off */
    fun lost(why: String) { if (synchronized(this) { machine.lost() }) say("keep-alive lost ($why)") }

    private fun hookTrim() {
        if (trimHooked) return
        trimHooked = true
        MemWatch.onTrim("indoor keep-alive") { level ->
            if (running && standsDown(runCatching { DeviceTier.lowMemory() }.getOrDefault(false), level)) {
                stoodDown = true
                update(null, why = "lighter mode: Android ${MemWatch.trimName(level)}")
            }
            0L
        }
    }

    private fun say(line: String) {
        Log.i(TAG, "interiors: $line")
        runCatching { DiagLog.event("interiors", line) }
    }
}
