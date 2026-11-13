package com.jrees.wowcompanion

/**
 * WHAT THE GAME DOES BEFORE ITS LOGIN SCREEN, SAID (issues #132 / #42 / #76 / #57, 2026-11-12 triage): "stuck on waiting for game. 20+
 * mins" (#132, a 2.6 GB direct install) had the client receiving 170-366 MB a minute for the whole wait and never reading login.txt -
 * it was still downloading its own data - while "no log in fields" (#57) sat at 0 bytes with the window up. The per-second DSNET lines
 * show it only when the report's log is not cut, so the sign-in log now says it: one line a minute while the client receives at least
 * [BUSY_MB] MB a minute before its first read of login.txt (and before the world), and the totals for the report's "Before the login
 * screen" line and the unread file's deletion. Pure: the off-device tests drive it.
 */
internal class PreLoginTraffic(private val minuteMs: Long = 60_000L, private val phase: String = "before the login screen") {
    var total = 0L; private set
    /** the bytes of the last whole minute (0 until one has passed) */
    var lastMinute = 0L; private set
    private var minute = 0L
    private var minuteStart = -1L

    /** a new client run */
    fun reset() { total = 0L; lastMinute = 0L; minute = 0L; minuteStart = -1L }

    /** one second's received bytes at [nowMs]; returns the minute's log line when a minute with at least [BUSY_MB] MB ends */
    fun add(rx: Long, nowMs: Long): String? {
        val r = maxOf(rx, 0L)
        if (minuteStart < 0) minuteStart = nowMs
        var line: String? = null
        if (nowMs - minuteStart >= minuteMs) {
            lastMinute = minute
            if (minute >= BUSY_MB * MB) line = "$phase: the game received ${mb(minute)} MB in the last minute " +
                "(${mb(total)} MB since it started): it is downloading"
            minute = 0L; minuteStart = nowMs
        }
        minute += r; total += r
        return line
    }

    /**
     * Is the game downloading right now (issue #132, the user's pick 2026-11-12 "Wait for login screen")? The last whole minute or the
     * minute so far reached [BUSY_MB] MB. Only meaningful while [counts] holds: the caller ANDs it ([downloading]).
     */
    val busy: Boolean get() = lastMinute >= BUSY_MB * MB || minute >= BUSY_MB * MB

    companion object {
        /** before the login screen and receiving at least [BUSY_MB] MB a minute: the game is fetching its files */
        fun downloading(counts: Boolean, busy: Boolean): Boolean = counts && busy

        /**
         * THE UNREAD FILE'S CAP (the user's pick 2026-11-12, "Wait for login screen": "Keep the file until the game reaches its login
         * screen (still deleted 20 s after its first read), with a hard cap of 30 minutes"). Returns how long to wait before asking
         * again, or 0 = delete now. [lifeMs] (5 min) runs from the write, or from the last moment the game was seen downloading before
         * its login screen ([holdFrom], 0 = never): while it downloads the cap waits, and the login screen gets the full [lifeMs] once the
         * download ends. [HARD_CAP_MS] from the write always deletes. A re-login file never downloads (the world came first), so its
         * cap is unchanged.
         */
        fun capWait(nowMs: Long, writtenAt: Long, holdFrom: Long, lifeMs: Long, downloading: Boolean): Long {
            val age = nowMs - writtenAt
            if (age >= HARD_CAP_MS - SLACK_MS) return 0L
            val hardLeft = HARD_CAP_MS - age
            if (downloading) return minOf(RECHECK_MS, hardLeft)
            val left = lifeMs - (nowMs - maxOf(writtenAt, holdFrom))
            return if (left <= SLACK_MS) 0L else minOf(left, hardLeft)
        }

        /** the hard cap of an unread login.txt, whatever the game does (the user's pick 2026-11-12) */
        const val HARD_CAP_MS = 30 * 60_000L
        /** while the game downloads past the cap, asked again this often */
        const val RECHECK_MS = 60_000L
        /** a handler a few ms early is on time */
        private const val SLACK_MS = 1_000L

        /** the start screen's plain waiting line, and the one while the game fetches its files before its login screen */
        const val WAITING_LINE = "Waiting for the game."
        const val DOWNLOADING_LINE = "Game downloading"
        /** the start screen's line for [downloading]: only the plain waiting line swaps, any other line stays */
        fun waitingLine(line: String, downloading: Boolean): String = when {
            downloading && line == WAITING_LINE -> DOWNLOADING_LINE
            !downloading && line == DOWNLOADING_LINE -> WAITING_LINE
            else -> line
        }

        /**
         * May a second of heavy traffic mean "past the login screen" (NativeLogin)? After the client's read, yes; while a written file
         * waits for its read, no. Without a read, issue #132 (2026-11-11, a 2.6 GB direct install): the client downloading its own data
         * said "past the login screen (6028576 B/s)" 27 s BEFORE the game's window appeared, and again (3185058 B/s) right after an unread
         * file was deleted at its cap. So without a read: only once the window is up, never after an unread file's cap, and [graceS]
         * after the game screen came up ([sinceStartS], -1 = not up). The world's documents stay the hard backstop.
         */
        fun trafficIsSignIn(consumed: Boolean, active: Boolean, windowSeen: Boolean, expiredUnread: Boolean, sinceStartS: Long, graceS: Long): Boolean = when {
            consumed -> true
            active -> false
            !windowSeen || expiredUnread -> false
            else -> sinceStartS >= graceS
        }

        /**
         * Is this second's traffic still "before the login screen"? Not after the client's first read, not once the run is past the
         * login screen (the no-file grace path: 'Play without' signs in by hand and the loading screen streams data), not in the world
         * (review of 5f61774f). A sign-in typed by hand after an unread file's cap sets neither until the world, so that loading screen
         * still counts: the deletion line says the cap came first.
         */
        fun counts(read: Boolean, pastLogin: Boolean, reachedWorld: Boolean): Boolean = !read && !pastLogin && !reachedWorld

        /**
         * DIAGNOSTICS ONLY (issue #215, 1.0.27: "lower screen no lpnger showing just a akground grapoc"; 8.7 GB of data, past the login
         * screen at 18:31:26, then 0.2-3.7 MB/s every second for 4 min and never the world): the window after the client's read or
         * past its login screen, before the world. [counts]'s download line never covers it; what the bottom screen drew then is not
         * in the report, so NativeLogin logs it once a busy minute here. Nothing on screen changes.
         */
        fun countsAfter(read: Boolean, pastLogin: Boolean, reachedWorld: Boolean): Boolean = (read || pastLogin) && !reachedWorld

        const val BUSY_MB = 10L
        private const val MB = 1L shl 20
        fun mb(bytes: Long): Long = bytes / MB
    }
}
