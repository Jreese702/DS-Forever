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
    private var wasRouting = false
    /** the quest the route is for at the last look, and whether a switch to another quest still waits for its SET / CLEAR */
    private var forQuest = 0
    private var switched = false

    /**
     * One look at the route at [nowMs]: [want] = the pin's place on the player's map (0..1), null = no pin; [more] = the checkpoints
     * after it (cp19: the world marker draws them too; the first [MORE_MAX] go); [quest] = the focused quest the route is for (0 =
     * unknown / none). Returns the row's columns to tap, in order (empty = nothing to send).
     *
     * A SWITCH TO ANOTHER QUEST (fix/focus-latency, test50: "it just took like 6 seconds"): while the pin stands, the add-on's focus
     * only renames the quest the pin stands for (Quests.lua OPS.track), so the game's arrow keeps pointing at the OLD quest's way until
     * this moves the pin or clears it. The pace ([SET_EVERY_MS]) and the grace ([CLEAR_GRACE_MS]) are there against a route that
     * churns or flickers, not against the player's own choice: the first look at a new quest's route SETs (or CLEARs, giving the game's
     * super-track to the new quest) at once.
     */
    fun tick(want: DoubleArray?, nowMs: Long, more: List<DoubleArray> = emptyList(), routing: Boolean = false, quest: Int = 0): List<Int> {
        if (quest > 0 && quest != forQuest) { if (forQuest != 0) switched = true; forQuest = quest }
        // ag5 (test45: the app routed for minutes with no pin up, and the add-on's live aggro layer never started): while the app ROUTES
        // the focused quest, a KEEP every KEEP_EVERY_MS even with no pin (Map.lua: a KEEP within its KEEP_S = routing); one CLEAR when
        // the routing stops with no pin up
        fun routeKeep(): List<Int> {
            if (routing) { wasRouting = true; if (nowMs - keepAt >= KEEP_EVERY_MS) { keepAt = nowMs; return listOf(KEEP) } }
            else if (wasRouting && !active) { wasRouting = false; return listOf(CLEAR) }
            return emptyList()
        }
        if (want == null) {
            if (!active) { switched = false; return routeKeep() }
            // test48: not cleared at the first empty look (a route flickering for a moment); only after CLEAR_GRACE_MS of none
            // (a new quest's direct route: at once, see above)
            if (noneSince == Long.MIN_VALUE) noneSince = nowMs
            if (!switched && nowMs - noneSince < CLEAR_GRACE_MS) return emptyList()
            switched = false
            active = false; sentX = Double.NaN; sentY = Double.NaN; noneSince = Long.MIN_VALUE; sentMore = emptyList()
            // (the CLEAR ends the add-on's routing too: still routing, the KEEP right behind it)
            if (routing) { keepAt = nowMs; return listOf(CLEAR, KEEP) }
            wasRouting = false
            return listOf(CLEAR)
        }
        noneSince = Long.MIN_VALUE
        if (nowMs < refusedUntil) return routeKeep()
        val further = more.take(MORE_MAX)
        val moved = !active || abs(want[0] - sentX) > MOVE || abs(want[1] - sentY) > MOVE
        // A PASS (cp26, the user's "always show the next two markers"): the new pin stands where the first further flag stood - the
        // player walked past a checkpoint and the route advanced; it moves at once (the pace is against churn and re-plans only)
        val advance = active && sentMore.isNotEmpty() && abs(want[0] - sentMore[0][0]) <= MOVE && abs(want[1] - sentMore[0][1]) <= MOVE
        if (moved && (switched || advance || nowMs - setAt >= SET_EVERY_MS)) {
            switched = false
            active = true; sentX = want[0]; sentY = want[1]; setAt = nowMs; keepAt = nowMs
            sentMore = further; moreAt = nowMs
            // (cp20: the pin's own ground height after its x / y, as the further flags'; a SET drops the old flags in the add-on)
            return listOf(SET) + digits(want[0]) + digits(want[1]) + zDigits(want.getOrElse(2) { Double.NaN }) + moreCols(further)
        }
        if (!moved) switched = false                     // the new quest's pin stands where the old one did: nothing owed
        // the pin stays, the checkpoints after it changed (a new leg): dropped and sent again, at the pin's own pace
        if (active && !moved && changed(further, sentMore) && nowMs - moreAt >= SET_EVERY_MS) {
            sentMore = further; moreAt = nowMs; keepAt = nowMs
            return listOf(MORE, MORE) + moreCols(further)
        }
        if (active && nowMs - keepAt >= KEEP_EVERY_MS) { keepAt = nowMs; return listOf(KEEP) }
        return emptyList()
    }

    // each further flag: 38, x (2 digits), y (2 digits), and its ground height against the player's (2 digits, [zDigits]; Interact's
    // "Try to ground them all" - an older add-on reads the 4 and drops the 2)
    private fun moreCols(pts: List<DoubleArray>): List<Int> =
        pts.flatMap { listOf(MORE) + digits(it[0]) + digits(it[1]) + zDigits(it.getOrElse(2) { Double.NaN }) }
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

        /** the step of a further flag's height (yards) and the offset that makes 0 the player's own height: n = dz / Z_STEP + Z_ZERO */
        const val Z_STEP = 0.25
        const val Z_ZERO = 648
        /** n for "height unknown" */
        const val Z_UNKNOWN = 1295

        /** a flag's ground height [dz] (yards, relative to the player; NaN unknown) as two base-36 digits: about +/-162 yd in 0.25 yd */
        fun zDigits(dz: Double): List<Int> {
            val n = if (dz.isNaN()) Z_UNKNOWN else ((dz / Z_STEP).roundToInt() + Z_ZERO).coerceIn(0, Z_UNKNOWN - 1)
            return listOf(n / 36, n % 36)
        }

        /** a 0..1 coordinate as two base-36 digits (1296 steps across; Map.lua divides by 1295) */
        fun digits(v: Double): List<Int> {
            val n = (v.coerceIn(0.0, 1.0) * 1295).roundToInt()
            return listOf(n / 36, n % 36)
        }
    }
}
