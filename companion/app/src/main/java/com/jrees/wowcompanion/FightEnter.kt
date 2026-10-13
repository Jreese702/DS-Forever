package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * A ROOM OR A WINDOW TAKES THE CONTROLLER IN A FIGHT (SPEC_COMBAT items 9 / 10, the user's sign-off 2026-10-01: "Opening a room during a
 * fight -> the room takes the controller, same as out of combat"; loot / NPC windows likewise). Bindings cannot change in a fight, so a
 * room's own take there stays PENDING (Interact's capture on Spells' router) until ONE secure tap lands: the interact document then
 * carries `enter` = {row, col, for, n} - `for` = the capture id waiting (the id the room passed to capture.take, or "npc" for an NPC /
 * loot window), `n` steps for each new pending enter. The app taps it ONCE per appearance when that room is on screen (or Interact's
 * window actually shows: never a hidden or late window), and once more after RETRY_MS if the same appearance is still pending and the
 * holder is not yet that id (the first tap lost to the park point). Pure (tested in home_test); MainActivity sends the taps.
 */
class FightEnter {
    class Want(val row: String, val col: Int, val forId: String, val n: Int)

    /** the pending enter; null = none */
    var want: Want? = null
        private set
    /** who holds now (the capture id), from the same document */
    var holder: String? = null
        private set
    private var tappedN = -1
    private var retried = false

    /** every CURRENT interact document */
    fun note(msg: JSONObject) {
        val e = msg.optJSONObject("enter")
        val row = e?.optString("row", "") ?: ""
        val col = e?.optInt("col", -1) ?: -1
        val forId = e?.optString("for", "") ?: ""
        val n = e?.optInt("n", -1) ?: -1
        want = if (row.isNotEmpty() && col >= 0 && forId.isNotEmpty() && n >= 0) Want(row, col, forId, n) else null
        holder = msg.optString("holder", "").ifEmpty { null }
        // no enter pending any more (taken, given up, or the add-on reloaded and counts from 1 again): the next one is new
        if (want == null) { tappedN = -1; retried = false }
    }

    /** [id] is on screen now (its room shown, or Interact's window up): the cell to tap ONCE for this appearance, else null */
    fun enterNow(id: String): Pair<String, Int>? {
        val w = want ?: return null
        if (w.forId != id || w.n == tappedN) return null
        tappedN = w.n; retried = false
        return w.row to w.col
    }

    /** RETRY_MS after a tap: once more while the same appearance is still pending for [id] and [id] does not hold yet, else null */
    fun retryNow(id: String): Pair<String, Int>? {
        val w = want ?: return null
        if (retried || w.forId != id || w.n != tappedN || holder == id) return null
        retried = true
        return w.row to w.col
    }

    companion object {
        const val RETRY_MS = 450L
    }
}
