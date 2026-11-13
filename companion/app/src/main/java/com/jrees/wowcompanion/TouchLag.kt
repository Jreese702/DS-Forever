package com.jrees.wowcompanion

import java.util.concurrent.atomic.AtomicLong

/**
 * HOW LATE THE TOP SCREEN'S TOUCHES REACH THE MAIN THREAD, AND HOW LONG EACH TAKES THERE (issue #230, 1.0.28: "DS Forever stopped
 * responding", Input dispatching timed out, "Waited 5000ms for MotionEvent"). The ANR's main thread was RUNNABLE in the touchpad's
 * pointer move (TouchpadView.handleFingerMove -> XServer.injectPointerMoveDelta -> Pointer.getX), not waiting on a lock or a file, and
 * MemWatch's main-thread beat (2.5 s) logged no stall before the kill. So either the touches reached the main thread late while it
 * still answered its beat, or each touch was slow (the X server's lock), or the beat itself was late. This says which: every touch the
 * touchpad takes is [note]d with its age on arrival (now - the event's own time) and its handling time; MemWatch's line carries the
 * worst of each since the line before ([take]), and one bad touch is logged at once (DiagLog, at most every [LOG_EVERY_MS]).
 * Only times: nothing of where the finger was.
 */
object TouchLag {
    /** a touch this late on arrival, or this slow to handle, is logged at once */
    const val LATE_MS = 1_000L
    const val SLOW_MS = 250L
    const val LOG_EVERY_MS = 5_000L

    private val maxLate = AtomicLong(0)
    private val maxSlow = AtomicLong(0)
    private val count = AtomicLong(0)
    @Volatile private var loggedAt = Long.MIN_VALUE / 2

    /** the main thread's beat age now (MemWatch), for the at-once line */
    @Volatile var beatAge: () -> Long = { -1L }
    /** where the at-once line goes (tests: a list) */
    @Volatile var sink: (String) -> Unit = { l -> runCatching { DiagLog.event("touch", l) }; runCatching { android.util.Log.w("WCTOUCH", l) } }

    /** one touch: [eventTime] the event's own uptime stamp, [start] when the touchpad got it, [end] when it was done (all uptime ms) */
    @JvmStatic fun note(action: Int, eventTime: Long, start: Long, end: Long) {
        val late = (start - eventTime).coerceAtLeast(0)
        val slow = (end - start).coerceAtLeast(0)
        count.incrementAndGet()
        raise(maxLate, late); raise(maxSlow, slow)
        if ((late >= LATE_MS || slow >= SLOW_MS) && end - loggedAt >= LOG_EVERY_MS) {
            loggedAt = end
            sink(line(action, late, slow, runCatching { beatAge() }.getOrDefault(-1L)))
        }
    }

    /** "top screen touch (move) reached the main thread 3.2 s late, handled in 12 ms; main beat 0.4 s old" (pure: the tests) */
    fun line(action: Int, lateMs: Long, slowMs: Long, beatMs: Long): String =
        "top screen touch (${actionName(action)}) reached the main thread ${secs(lateMs)} late, handled in $slowMs ms" +
            if (beatMs >= 0) "; main beat ${secs(beatMs)} old" else ""

    /** MemWatch's part: "touch late max 3.2 s, slow max 40 ms (n 120)" since the last call, "" when no touch or nothing notable */
    fun take(): String {
        val n = count.getAndSet(0); val late = maxLate.getAndSet(0); val slow = maxSlow.getAndSet(0)
        return summary(n, late, slow)
    }
    /** pure (the tests): only a window with a touch at least [SUMMARY_MS] late or slow says anything */
    fun summary(n: Long, lateMs: Long, slowMs: Long): String =
        if (n == 0L || (lateMs < SUMMARY_MS && slowMs < SUMMARY_MS)) "" else "touch late max ${secs(lateMs)}, slow max $slowMs ms (n $n)"
    const val SUMMARY_MS = 100L

    private fun raise(a: AtomicLong, v: Long) { while (true) { val c = a.get(); if (v <= c || a.compareAndSet(c, v)) return } }
    private fun secs(ms: Long) = "${ms / 100 / 10.0} s"
    private fun actionName(a: Int) = when (a and 0xff) { 0 -> "down"; 1 -> "up"; 2 -> "move"; 3 -> "cancel"; 5 -> "pointer down"; 6 -> "pointer up"; else -> "action $a" }

    /** tests */
    internal fun reset() { maxLate.set(0); maxSlow.set(0); count.set(0); loggedAt = Long.MIN_VALUE / 2 }
}
