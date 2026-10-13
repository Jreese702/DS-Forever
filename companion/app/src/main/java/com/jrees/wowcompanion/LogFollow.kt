package com.jrees.wowcompanion

/**
 * WHICH GAME'S LOG TO READ (2026-10-02: GameNative stays the main game, and the user also tries the standalone "WoW Forever" app,
 * flipping between the two - "One companion that follows whichever you play"). Only one game runs at a time, and a running client writes its
 * General.log all the time (the engine's chatter, the addon's heartbeat), while the other one's log stands still. So LogTail
 * follows the log written MOST RECENTLY: it switches away from the one it reads only when another was written at least
 * [SWITCH_S] seconds later - never on a tie, never to a missing file. Pure (tested in home_test).
 */
object LogFollow {
    /** seconds another log must be newer by before the reader switches to it */
    const val SWITCH_S = 2L

    /** [mtimes] = each candidate's last-modified time in seconds, -1 = missing / unreadable. Returns the path to read. */
    fun pick(current: String, mtimes: Map<String, Long>): String {
        val now = mtimes[current] ?: -1L
        val best = mtimes.filter { it.value >= 0 }.maxByOrNull { it.value } ?: return current
        return if (best.key != current && best.value >= now + SWITCH_S) best.key else current
    }

    /** parses the root shell's answer "WCMT:<t1>:<t2>:..." against [paths], in order; null when it does not fit */
    fun parse(line: String, paths: List<String>): Map<String, Long>? {
        val parts = line.trim().removePrefix("WCMT:").split(':')
        if (!line.trim().startsWith("WCMT:") || parts.size != paths.size) return null
        val out = LinkedHashMap<String, Long>()
        for ((i, p) in paths.withIndex()) out[p] = parts[i].trim().toLongOrNull() ?: return null
        return out
    }
}
