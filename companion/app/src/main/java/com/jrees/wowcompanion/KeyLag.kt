package com.jrees.wowcompanion

import android.util.Log
import android.view.KeyEvent

/**
 * HOW LATE A PAD KEY REACHES THE GAME (the user, 1.0.29 test build: "insane input lag on my face buttons"). The launcher's game screen
 * calls [atGame] just before it hands a gamepad key to the game's handler (WinHandler): the time from the event's own time (Android's
 * KeyEvent.getEventTime, uptime ms) to that moment is what DS Forever's work in front of the game cost, plus the main thread's queue.
 * Presses only (a key down, no repeat). Every [WINDOW] presses, or at once for a press over [SLOW_MS] (at most one line per
 * [EVERY_MS]), one line: "keylag over 32 presses: avg 3 ms, max 11 ms, 0 over 100 ms" to logcat (WCKEY), lat.log and the problem
 * report's diagnostic log (both written on the background writer, never here). [line] is the run's total for the report's settings.
 * Main thread only (the launcher's input callback). [note] is pure enough for the tests.
 */
object KeyLag {
    const val WINDOW = 32
    const val SLOW_MS = 100L
    const val EVERY_MS = 10_000L

    private var n = 0
    private var sum = 0L
    private var max = 0L
    private var slow = 0
    private var lastAt = Long.MIN_VALUE / 2
    // the run's totals
    @Volatile var presses = 0L; private set
    @Volatile private var total = 0L
    @Volatile var worst = 0L; private set
    @Volatile private var slowTotal = 0L

    /** a gamepad key about to go to the game's handler */
    fun atGame(e: KeyEvent, now: Long = android.os.SystemClock.uptimeMillis()) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        val line = note(now - e.eventTime, now) ?: return
        Log.i(TAG, line)
        LatLog.mark("keylag", line)
        runCatching { BgAppend.EXEC.execute { runCatching { DiagLog.event("pad", line) } } }
    }

    /** one press [lagMs] late; the line to log now, or null (rate-limited) */
    fun note(lagMs: Long, now: Long): String? {
        val lag = lagMs.coerceAtLeast(0L)
        n++; sum += lag; if (lag > max) max = lag; if (lag >= SLOW_MS) slow++
        presses++; total += lag; if (lag > worst) worst = lag; if (lag >= SLOW_MS) slowTotal++
        if (n < WINDOW && lag < SLOW_MS) return null
        if (now - lastAt < EVERY_MS) return null
        val l = "keylag over $n ${if (n == 1) "press" else "presses"}: avg ${sum / n} ms, max $max ms, $slow over $SLOW_MS ms"
        lastAt = now; n = 0; sum = 0L; max = 0L; slow = 0
        return l
    }

    /** the problem report's line: the run's presses as the game screen handed them to the game */
    fun line(): String = if (presses == 0L) "Key lag to the game: no gamepad key press this run" else
        "Key lag to the game: $presses presses, avg ${total / presses} ms, max $worst ms, $slowTotal over $SLOW_MS ms" +
            LatLog.dropped().let { if (it > 0) " (lat.log dropped $it lines)" else "" }

    /** tests: a fresh run */
    fun reset() { n = 0; sum = 0L; max = 0L; slow = 0; lastAt = Long.MIN_VALUE / 2; presses = 0L; total = 0L; worst = 0L; slowTotal = 0L }

    private const val TAG = "WCKEY"
}
