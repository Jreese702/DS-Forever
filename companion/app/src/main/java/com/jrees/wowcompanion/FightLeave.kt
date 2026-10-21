package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * CAPTURE v9'S MID-FIGHT LEAVE (Interact's design after the 10:47 / 11:03 failures: no frame in a fight, the room's keys are secure
 * bindings, and the ONLY way to hand the pad back in a fight is one secure "leave" button that clears them and closes the room's game
 * window). While a room holds through a fight the interact document carries `fightLeave` = {row, col}: every leave the APP starts (a
 * touch on the Home disc or another room, the bar cursor's pick) taps that cell ONCE instead of the room's own release taps - the same
 * way for every room, however it was opened (the user: "it should act the EXACT same for all rooms"). B in the room is the add-on's own.
 * Pure state (tested in home_test); MainActivity sends the tap and posts the one retry.
 */
class FightLeave {
    /** the cell to tap while a room holds through a fight; null = no such hold (out of a fight, or the old release) */
    var cell: Pair<String, Int>? = null
        private set
    /** who holds (the room's capture id), from the same document */
    var holder: String? = null
        private set
    private var tappedFor: String? = null
    private var retried = false

    /** every CURRENT interact document */
    fun note(msg: JSONObject) {
        val f = msg.optJSONObject("fightLeave")
        val row = f?.optString("row", "") ?: ""
        val col = f?.optInt("col", -1) ?: -1
        cell = if (row.isNotEmpty() && col >= 0) row to col else null
        holder = msg.optString("holder", "").ifEmpty { null }
        windowOpen = WINDOW_KEYS.any { msg.has(it) }
        if (cell == null) { tappedFor = null; retried = false }
    }

    /** a leave the app starts: the cell to tap now, or null (no room holds through a fight - leave the old way) */
    fun leaveNow(): Pair<String, Int>? {
        val c = cell ?: return null
        tappedFor = holder; retried = false
        return c
    }

    /** RETRY_MS after a leave tap: the cell to tap ONCE more if the same room still holds through the fight (the first tap was lost to
     *  the padstray sink: the click is idempotent - no bindings left, the window already closed), else null */
    fun retryNow(): Pair<String, Int>? {
        val c = cell ?: return null
        if (retried || tappedFor == null || holder != tappedFor) return null
        retried = true
        return c
    }

    /**
     * THE HELD NPC WINDOW GONE IN A FIGHT (RC5, the user on RC4: "after the first one died, my spells werent doing anything on the second
     * guy"; Spells' read of the log: the loot window closed by itself in the next fight, "npc" kept holding in room mode and every face
     * press went to Interact's pad for 9 s, until B). The capture's room mode can only be let go by a hardware click in a fight, so the app
     * taps [cell] ONCE when a current document says "npc" holds through the fight with NO window in it (the window keys InteractView reads);
     * [retryNow] then covers a lost tap. Once per hold: a window coming back (or the hold ending) starts a new one.
     */
    private var orphaned = false
    /** the last current document had one of [WINDOW_KEYS] */
    private var windowOpen = false
    private fun windowless() = cell != null && holder == NPC && !windowOpen

    /** after [note]: true = the held window is gone (a NEW episode): check it again [SETTLE_MS] later ([orphanSettled]) */
    fun orphanSeen(): Boolean {
        if (!windowless()) { orphaned = false; return false }
        if (orphaned) return false
        orphaned = true
        return true
    }

    /** [SETTLE_MS] after [orphanSeen] (Interact: gossip -> quest briefly reads windowless): the cell to tap if it STILL holds so, else null */
    fun orphanSettled(): Pair<String, Int>? {
        if (!orphaned || !windowless()) return null
        tappedFor = holder; retried = false
        return cell
    }

    companion object {
        const val RETRY_MS = 450L
        const val NPC = "npc"
        /** Interact 2026-10-09: the next window of a chain lands a server round trip later */
        const val SETTLE_MS = 300L
        /** an interact document's window keys (InteractView.update's kinds) */
        val WINDOW_KEYS = listOf("quest", "merchant", "trainer", "stable", "petition", "book", "mail", "auction", "taxi", "bank", "loot", "gossip")
    }
}
