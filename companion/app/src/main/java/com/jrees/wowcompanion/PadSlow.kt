package com.jrees.wowcompanion

/**
 * WHICH ROOM HOLDS THE MAIN THREAD ON A PRESS (the 1.0.29 key-lag review). PadFeed posts every PadHub delivery so the game gets the
 * press first, but the rooms' subscribers still run on the main thread: heavy work in one of them sits in front of the NEXT input event
 * (often the same button's release, so the game reads a long hold). PadHub times each subscriber's delivery and DirectPad each room's
 * onKey; one that takes [SLOW_MS] or more is noted here with its name ("HomeView", "DirectPad:chat"). One line at most every
 * [EVERY_MS]: "pad slow: DirectPad:chat 23 ms (3 slow, worst 41 ms by CastView since the last line)" to logcat (WCKEY), lat.log and the
 * problem report's diagnostic log, all queued (never written here). [note] is pure (the clock passed in); [line] is the run's total.
 * Main thread only.
 */
object PadSlow {
    const val SLOW_MS = 8L
    const val EVERY_MS = 5_000L

    private var lastAt = Long.MIN_VALUE / 2
    private var since = 0
    private var sinceWorst = 0L
    private var sinceWho = ""
    @Volatile var total = 0L; private set
    @Volatile var worst = 0L; private set
    @Volatile var worstWho = ""; private set

    /** a delivery to [who] took [ms]; the line to log now, or null (quick, or rate-limited) */
    fun note(who: String, ms: Long, now: Long): String? {
        if (ms < SLOW_MS) return null
        total++; if (ms > worst) { worst = ms; worstWho = who }
        since++; if (ms > sinceWorst) { sinceWorst = ms; sinceWho = who }
        if (now - lastAt < EVERY_MS) return null
        val l = "pad slow: $who $ms ms" + if (since > 1) " ($since slow, worst $sinceWorst ms by $sinceWho since the last line)" else ""
        lastAt = now; since = 0; sinceWorst = 0L; sinceWho = ""
        return l
    }

    /** time [block] as a delivery to [who] (the main thread's work for one subscriber) */
    inline fun timed(who: () -> String, block: () -> Unit) {
        val t0 = System.nanoTime()
        try { block() } finally {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            if (ms >= SLOW_MS) report(who(), ms)
        }
    }

    fun report(who: String, ms: Long) {
        val line = note(who, ms, android.os.SystemClock.uptimeMillis()) ?: return
        runCatching { android.util.Log.i("WCKEY", line) }
        LatLog.mark("padslow", line)
        runCatching { DiagLog.event("pad", line) }
    }

    /** the problem report's line */
    fun line(): String = if (total == 0L) "Pad deliveries over $SLOW_MS ms: none this run" else
        "Pad deliveries over $SLOW_MS ms: $total this run, worst $worst ms ($worstWho)"

    /** tests: a fresh run */
    fun reset() { lastAt = Long.MIN_VALUE / 2; since = 0; sinceWorst = 0L; sinceWho = ""; total = 0L; worst = 0L; worstWho = "" }

    /** a subscriber's name from its callback's class: "com.x.HomeView$..." -> "HomeView" */
    fun nameOf(f: Any?): String = f?.javaClass?.name?.substringBefore('$')?.substringAfterLast('.') ?: "?"
}
