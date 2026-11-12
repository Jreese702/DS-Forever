package com.jrees.wowcompanion

/**
 * WHAT THE GAME DOES BEFORE ITS LOGIN SCREEN, SAID (issues #132 / #42 / #76 / #57, 2026-11-12 triage): "stuck on waiting for game. 20+
 * mins" (#132, a 2.6 GB direct install) had the client receiving 170-366 MB a minute for the whole wait and never reading login.txt -
 * it was still downloading its own data - while "no log in fields" (#57) sat at 0 bytes with the window up. The per-second DSNET lines
 * show it only when the report's log is not cut, so the sign-in log now says it: one line a minute while the client receives at least
 * [BUSY_MB] MB a minute before its first read of login.txt (and before the world), and the totals for the report's "Before the login
 * screen" line and the unread file's deletion. Pure: the off-device tests drive it.
 */
internal class PreLoginTraffic(private val minuteMs: Long = 60_000L) {
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
            if (minute >= BUSY_MB * MB) line = "before the login screen: the game received ${mb(minute)} MB in the last minute " +
                "(${mb(total)} MB since it started): it is downloading"
            minute = 0L; minuteStart = nowMs
        }
        minute += r; total += r
        return line
    }

    companion object {
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

        const val BUSY_MB = 10L
        private const val MB = 1L shl 20
        fun mb(bytes: Long): Long = bytes / MB
    }
}
