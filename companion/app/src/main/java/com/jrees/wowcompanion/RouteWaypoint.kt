package com.jrees.wowcompanion

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * THE GAME'S PIN ON THE ROUTE'S NEXT WAY (b-route-waypoint; the user's pick 2026-10-17: "(b), the game user waypoint", an explicit
 * exception to leave-no-trace for this only). While the focused quest's route goes through a way (the tram, a learned gate, a flight
 * master, the game's waypoint, the map's edge), the game's own pin and super-track stand on it, so the top screen's arrow points there
 * too; it moves as the route advances and goes (the quest's own super-track back) when the route becomes direct, on unfocus, when the
 * app stops (Map.lua's KEEP timeout), at log out, and at the next log in after a crash. Pure: which taps of Map.lua's row "uwp".
 */
class RouteWaypoint {
    private var active = false
    private var sentX = Double.NaN; private var sentY = Double.NaN
    private var setAt = Long.MIN_VALUE / 2; private var keepAt = Long.MIN_VALUE / 2
    private var refusedUntil = Long.MIN_VALUE / 2
    private var noneSince = Long.MIN_VALUE
    private var sentMore: List<DoubleArray> = emptyList(); private var moreAt = Long.MIN_VALUE / 2

    /**
     * One look at the route at [nowMs]: [want] = the pin's place on the player's map (0..1), null = no pin; [more] = the checkpoints
     * after it (cp19: the world marker draws them too; the first [MORE_MAX] go). Returns the row's columns to tap, in order (empty =
     * nothing to send).
     */
    fun tick(want: DoubleArray?, nowMs: Long, more: List<DoubleArray> = emptyList()): List<Int> {
        if (want == null) {
            if (!active) return emptyList()
            // test48: not cleared at the first empty look (a route flickering for a moment); only after CLEAR_GRACE_MS of none
            if (noneSince == Long.MIN_VALUE) noneSince = nowMs
            if (nowMs - noneSince < CLEAR_GRACE_MS) return emptyList()
            active = false; sentX = Double.NaN; sentY = Double.NaN; noneSince = Long.MIN_VALUE; sentMore = emptyList()
            return listOf(CLEAR)
        }
        noneSince = Long.MIN_VALUE
        if (nowMs < refusedUntil) return emptyList()
        val further = more.take(MORE_MAX)
        val moved = !active || abs(want[0] - sentX) > MOVE || abs(want[1] - sentY) > MOVE
        if (moved && nowMs - setAt >= SET_EVERY_MS) {
            active = true; sentX = want[0]; sentY = want[1]; setAt = nowMs; keepAt = nowMs
            sentMore = further; moreAt = nowMs
            return listOf(SET) + digits(want[0]) + digits(want[1]) + moreCols(further)    // (a SET drops the old ones in the add-on)
        }
        // the pin stays, the checkpoints after it changed (a new leg): dropped and sent again, at the pin's own pace
        if (active && !moved && changed(further, sentMore) && nowMs - moreAt >= SET_EVERY_MS) {
            sentMore = further; moreAt = nowMs; keepAt = nowMs
            return listOf(MORE, MORE) + moreCols(further)
        }
        if (active && nowMs - keepAt >= KEEP_EVERY_MS) { keepAt = nowMs; return listOf(KEEP) }
        return emptyList()
    }

    private fun moreCols(pts: List<DoubleArray>): List<Int> = pts.flatMap { listOf(MORE) + digits(it[0]) + digits(it[1]) }
    private fun changed(a: List<DoubleArray>, b: List<DoubleArray>): Boolean =
        a.size != b.size || a.indices.any { abs(a[it][0] - b[it][0]) > MOVE || abs(a[it][1] - b[it][1]) > MOVE }

    /** the add-on's answer ({"type":"routewp"}): a refused SET (the player's own pin, no pins here) is not tried again for a while */
    fun onAnswer(op: String, ok: Boolean, nowMs: Long) {
        if (op == "set" && !ok) { active = false; sentX = Double.NaN; sentY = Double.NaN; sentMore = emptyList(); refusedUntil = nowMs + REFUSED_MS }
        if (op == "clear") { active = false; sentX = Double.NaN; sentY = Double.NaN; sentMore = emptyList() }
    }

    companion object {
        const val SET = 36; const val KEEP = 37; const val CLEAR = 39
        const val MORE = 38                  // cp19: a further checkpoint (4 digits after it); MORE, MORE drops them
        const val MORE_MAX = 2
        const val ROW = "uwp"
        const val MOVE = 0.004               // of the map: a smaller change is not worth a new pin
        const val SET_EVERY_MS = 5_000L      // test48: a pin moves at most every 5 s (it was 2: it churned)
        const val CLEAR_GRACE_MS = 8_000L    // no target this long before the pin is cleared
        const val KEEP_EVERY_MS = 20_000L    // well inside Map.lua's 60 s KEEP_S
        const val REFUSED_MS = 60_000L

        /** a 0..1 coordinate as two base-36 digits (1296 steps across; Map.lua divides by 1295) */
        fun digits(v: Double): List<Int> {
            val n = (v.coerceIn(0.0, 1.0) * 1295).roundToInt()
            return listOf(n / 36, n % 36)
        }
    }
}
