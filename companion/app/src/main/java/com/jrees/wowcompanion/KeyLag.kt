package com.jrees.wowcompanion

import android.util.Log
import android.view.KeyEvent

/**
 * HOW LATE A PAD KEY REACHES THE GAME (the user, 1.0.29 test build: "insane input lag on my face buttons"). The launcher's game screen
 * calls [atGame] just before it hands a gamepad key to the game's handler (WinHandler): the time from the event's own time (Android's
 * KeyEvent.getEventTime, uptime ms) to that moment is what DS Forever's work in front of the game cost, plus the main thread's queue.
 * Presses (a key down, no repeat) and RELEASES (a key up) are counted apart: a release held back behind the rooms' work reads as a long
 * hold to the game, and the press alone would never show it (the 1.0.29 review). Every [WINDOW] events of a kind, or at once for one over
 * [SLOW_MS] (at most one line per kind per [EVERY_MS]), one line: "keylag over 32 presses: avg 3 ms, max 11 ms, 0 over 100 ms" (or
 * "keylag over 32 releases: ...") to logcat (WCKEY), lat.log and the problem report's diagnostic log (both written on the background
 * writer, never here). [line] is the run's total for the report's settings.
 * Main thread only (the launcher's input callback). [note] / [noteUp] are pure enough for the tests.
 */
object KeyLag {
    const val WINDOW = 32
    const val SLOW_MS = 100L
    const val EVERY_MS = 10_000L

    /** one kind's window and run totals */
    private class Stat(val one: String, val many: String) {
        var n = 0; var sum = 0L; var max = 0L; var slow = 0
        var lastAt = Long.MIN_VALUE / 2
        @Volatile var count = 0L
        @Volatile var total = 0L
        @Volatile var worst = 0L
        @Volatile var slowTotal = 0L

        fun note(lagMs: Long, now: Long): String? {
            val lag = lagMs.coerceAtLeast(0L)
            n++; sum += lag; if (lag > max) max = lag; if (lag >= SLOW_MS) slow++
            count++; total += lag; if (lag > worst) worst = lag; if (lag >= SLOW_MS) slowTotal++
            if (n < WINDOW && lag < SLOW_MS) return null
            if (now - lastAt < EVERY_MS) return null
            val l = "keylag over $n ${if (n == 1) one else many}: avg ${sum / n} ms, max $max ms, $slow over $SLOW_MS ms"
            lastAt = now; n = 0; sum = 0L; max = 0L; slow = 0
            return l
        }

        fun totals(): String = "$count $many, avg ${total / count} ms, max $worst ms, $slowTotal over $SLOW_MS ms"
        fun reset() { n = 0; sum = 0L; max = 0L; slow = 0; lastAt = Long.MIN_VALUE / 2; count = 0L; total = 0L; worst = 0L; slowTotal = 0L }
    }

    private val downs = Stat("press", "presses")
    private val ups = Stat("release", "releases")

    val presses: Long get() = downs.count
    val worst: Long get() = downs.worst
    val releases: Long get() = ups.count
    val worstRelease: Long get() = ups.worst

    /** a gamepad key about to go to the game's handler */
    fun atGame(e: KeyEvent, now: Long = android.os.SystemClock.uptimeMillis()) {
        val line = when {
            e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 -> note(now - e.eventTime, now)
            e.action == KeyEvent.ACTION_UP -> noteUp(now - e.eventTime, now)
            else -> null
        } ?: return
        Log.i(TAG, line)
        LatLog.mark("keylag", line)
        runCatching { BgAppend.EXEC.execute { runCatching { DiagLog.event("pad", line) } } }
    }

    /** one press [lagMs] late; the line to log now, or null (rate-limited) */
    fun note(lagMs: Long, now: Long): String? = downs.note(lagMs, now)

    /** one release [lagMs] late; the line to log now, or null (rate-limited, apart from the presses) */
    fun noteUp(lagMs: Long, now: Long): String? = ups.note(lagMs, now)

    /** the problem report's line: the run's presses (and releases) as the game screen handed them to the game */
    fun line(): String = if (downs.count == 0L && ups.count == 0L) "Key lag to the game: no gamepad key press this run" else
        "Key lag to the game: " + listOfNotNull(downs.takeIf { it.count > 0 }?.totals(), ups.takeIf { it.count > 0 }?.totals()).joinToString("; ") +
            LatLog.dropped().let { if (it > 0) " (lat.log dropped $it lines)" else "" }

    /** tests: a fresh run */
    fun reset() { downs.reset(); ups.reset() }

    private const val TAG = "WCKEY"
}
