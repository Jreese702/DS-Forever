package com.jrees.wowcompanion

import java.util.concurrent.atomic.AtomicLong

/**
 * HOW LATE EACH OF OUR WINDOWS' TOUCHES REACH THE MAIN THREAD, AND HOW LONG EACH TAKES THERE (issue #230, 1.0.28: "DS Forever stopped
 * responding", Input dispatching timed out, "Waited 5000ms for MotionEvent"). The ANR's main thread was RUNNABLE in the touchpad's
 * pointer move (TouchpadView.handleFingerMove -> XServer.injectPointerMoveDelta -> Pointer.getX), not waiting on a lock or a file, and
 * MemWatch's main-thread beat (2.5 s) logged no stall before the kill.
 *
 * Review (#230): the ANR named the stuck window "app.dsforever.companion" (the package only). An activity's window is named after its
 * component ("pkg/cls"); a Presentation with no title falls back to the package. So the stuck window was most likely one of our
 * Presentations (the bottom screen's HostPresentation is the likely one), not the top screen's activity whose stack was caught. Hence:
 * every one of our Presentations gets its own title ([WinTitle]) so the next ANR line names the window, and touches are [note]d per
 * window ([TOP], [BOTTOM]) with their age on arrival (now - the event's own time) and handling time; MemWatch's line carries the worst
 * of each window since the line before ([take]), and one bad touch is logged at once (DiagLog, at most every [LOG_EVERY_MS] per window).
 * An event that is never handled never reaches [note]; the window title is what names that case. Only times: nothing of where the
 * finger was.
 */
object TouchLag {
    /** a touch this late on arrival, or this slow to handle, is logged at once */
    const val LATE_MS = 1_000L
    const val SLOW_MS = 250L
    const val LOG_EVERY_MS = 5_000L
    const val SUMMARY_MS = 100L

    const val TOP = "top"
    const val BOTTOM = "bottom"

    private class Win {
        val maxLate = AtomicLong(0)
        val maxSlow = AtomicLong(0)
        val count = AtomicLong(0)
        @Volatile var loggedAt = Long.MIN_VALUE / 2
    }
    private val wins = linkedMapOf(TOP to Win(), BOTTOM to Win())

    /** the main thread's beat age now (MemWatch), for the at-once line */
    @Volatile var beatAge: () -> Long = { -1L }
    /** where the at-once line goes (tests: a list) */
    @Volatile var sink: (String) -> Unit = { l -> runCatching { DiagLog.event("touch", l) }; runCatching { android.util.Log.w("WCTOUCH", l) } }

    /** the top screen's touchpad (TouchpadView.java): [eventTime] the event's own uptime stamp, [start]/[end] around its handling */
    @JvmStatic fun note(action: Int, eventTime: Long, start: Long, end: Long) = note(TOP, action, eventTime, start, end)

    /** one touch in window [win] ([TOP] or [BOTTOM]; anything else counts as [TOP]) */
    @JvmStatic fun note(win: String, action: Int, eventTime: Long, start: Long, end: Long) {
        val tag = if (win == BOTTOM) BOTTOM else TOP
        val w = wins.getValue(tag)
        val late = (start - eventTime).coerceAtLeast(0)
        val slow = (end - start).coerceAtLeast(0)
        w.count.incrementAndGet()
        raise(w.maxLate, late); raise(w.maxSlow, slow)
        if ((late >= LATE_MS || slow >= SLOW_MS) && end - w.loggedAt >= LOG_EVERY_MS) {
            w.loggedAt = end
            sink(line(tag, action, late, slow, runCatching { beatAge() }.getOrDefault(-1L)))
        }
    }

    /** "top screen touch (move) reached the main thread 3.2 s late, handled in 12 ms; main beat 0.4 s old" (pure: the tests) */
    fun line(action: Int, lateMs: Long, slowMs: Long, beatMs: Long): String = line(TOP, action, lateMs, slowMs, beatMs)
    fun line(win: String, action: Int, lateMs: Long, slowMs: Long, beatMs: Long): String =
        "$win screen touch (${actionName(action)}) reached the main thread ${secs(lateMs)} late, handled in $slowMs ms" +
            if (beatMs >= 0) "; main beat ${secs(beatMs)} old" else ""

    /** MemWatch's part: "touch late max 3.2 s, slow max 40 ms (n 120)" per window since the last call, "" when nothing notable */
    fun take(): String = wins.entries.mapNotNull { (tag, w) ->
        val n = w.count.getAndSet(0); val late = w.maxLate.getAndSet(0); val slow = w.maxSlow.getAndSet(0)
        summary(n, late, slow).takeIf { it.isNotEmpty() }?.let { if (tag == TOP) it else "$tag $it" }
    }.joinToString(", ")

    /** pure (the tests): only a window with a touch at least [SUMMARY_MS] late or slow says anything */
    fun summary(n: Long, lateMs: Long, slowMs: Long): String =
        if (n == 0L || (lateMs < SUMMARY_MS && slowMs < SUMMARY_MS)) "" else "touch late max ${secs(lateMs)}, slow max $slowMs ms (n $n)"

    private fun raise(a: AtomicLong, v: Long) { while (true) { val c = a.get(); if (v <= c || a.compareAndSet(c, v)) return } }
    private fun secs(ms: Long) = "${ms / 100 / 10.0} s"
    private fun actionName(a: Int) = when (a and 0xff) { 0 -> "down"; 1 -> "up"; 2 -> "move"; 3 -> "cancel"; 5 -> "pointer down"; 6 -> "pointer up"; else -> "action $a" }

    /** tests */
    internal fun reset() { wins.values.forEach { it.maxLate.set(0); it.maxSlow.set(0); it.count.set(0); it.loggedAt = Long.MIN_VALUE / 2 } }
}

/**
 * Issue #230 review: the window titles of our Presentations, so an "Input dispatching timed out (… is not responding)" line names the
 * window. Without one a Presentation is named by the package alone, the same for all of them. Not shown on screen.
 */
object WinTitle {
    const val BOTTOM = "DSF bottom"
    const val TOP_INPUT = "DSF top input"
    const val GAME = "DSF game"
    val ALL = listOf(BOTTOM, TOP_INPUT, GAME)

    /** sets [title] on [w] before it is added (call from the Presentation's onCreate) */
    @JvmStatic fun apply(w: android.view.Window?, title: String) {
        val win = w ?: return
        runCatching { win.attributes = win.attributes.also { it.title = title } }
    }
}
