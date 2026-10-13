package com.jrees.wowcompanion

/**
 * THE DEFAULT FOCUS (the user, 2026-10-06: "I want the focused quest to default to the closest tracked quest if there is
 * "No focused quest""). When the game has no focused quest, the companion SHOWS the nearest tracked quest on the player's
 * map as the focus (Home's card, the list's top spot, the gold look, the rim arrow). It never sets the game's focus: a quest
 * the player focuses wins and stays, and A on the default focuses it for real. Pure, tested off-device (tools/map_test).
 */
object FocusDefault {
    /** a tracked quest on the player's map and how far it is from the player, in yards */
    data class Cand(val qid: Int, val yd: Double)

    /** another quest takes over only when it is nearer by BOTH this share and this many yards (no flicker between two) */
    const val SHARE = 0.25
    const val MARGIN_YD = 25.0

    /**
     * the quest to show as focused: [current] stays while it is a candidate and not clearly beaten; else the nearest. With NO tracked
     * quest on the player's map (the user 2026-10-07 in Stormwind, ten quests tracked in Elwynn / Westfall: "I am still seeing "No
     * focused quest" while having quests tracked, and its not defaulting to any"): the FIRST tracked quest in the list's own order
     * ([tracked]), [current] kept while it is still tracked. 0 = none (nothing tracked)
     */
    fun pick(cands: List<Cand>, current: Int, tracked: List<Int> = emptyList()): Int {
        val best = cands.filter { !it.yd.isNaN() }.minByOrNull { it.yd }
            ?: return if (current != 0 && current in tracked) current else tracked.firstOrNull() ?: 0
        val cur = cands.firstOrNull { it.qid == current && !it.yd.isNaN() } ?: return best.qid
        val clearlyNearer = best.yd < cur.yd * (1.0 - SHARE) && cur.yd - best.yd > MARGIN_YD
        return if (clearlyNearer) best.qid else cur.qid
    }
}
