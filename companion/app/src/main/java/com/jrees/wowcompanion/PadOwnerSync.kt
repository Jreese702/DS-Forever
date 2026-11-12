package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * WHO HAS THE PAD IN A FIGHT, KEPT IN STEP (combat_nav_design.md Step 3, Design 1's reconciler; the user, 2026-11-11: "everything on the
 * bottom screen breaks in combat. If I am in combat and press B I should be able to fully navigate everything like normal").
 *
 * In a fight Interact's current document says `fightMode` - "room" (Spells' router delivers every press to us) or "game" - read back from
 * the router's own wc-mode, plus the two secure cells that flip it: `fightEnter` (always, while armed) and `fightLeave` (while a hold is
 * on). The app follows ONLY that word, level-triggered:
 *  - the bottom screen wants the pad (a room, a Home module, the bar cursor, a radial, the pause or target radial, InteractView,
 *    DialogView) and the mode is "game" -> tap ENTER;
 *  - nothing wants it and the mode is "room" -> tap LEAVE.
 * Each change of want gets at most [TRIES] taps, [RETRY_MS] apart; then it is STUCK once (a red cue and a log line) until the want or
 * the mode changes. Out of a fight (no fightMode) and while the game menu has the pad (`gmLock`, the user's decision 1) it does nothing.
 * Pure (PadOwnerSyncTest); CompanionHost sends the taps.
 */
class PadOwnerSync {
    enum class Act { NONE, ENTER, LEAVE, STUCK }
    data class Step(val act: Act, val cell: Pair<String, Int>? = null)

    /** "room" / "game" in a fight; null out of one */
    var mode: String? = null
        private set
    var enterCell: Pair<String, Int>? = null
        private set
    var leaveCell: Pair<String, Int>? = null
        private set
    /** the game menu was open at the pull and still shows: the game has the pad, the bottom screen is locked */
    var gmLock = false
        private set
    /** who holds (the capture id), from the same document */
    var holder: String? = null
        private set

    private var wantRoom: Boolean? = null
    private var tries = 0
    private var lastTapAt = 0L
    private var stuckSaid = false

    /** every CURRENT interact document (a replayed one says nothing about now: ignored) */
    fun note(msg: JSONObject, current: Boolean = true) {
        if (!current) return
        val m = msg.optString("fightMode", "").ifEmpty { null }
        if (m != mode) { tries = 0; stuckSaid = false; lastTapAt = 0L }
        mode = m
        enterCell = cellOf(msg.optJSONObject("fightEnter")) ?: cellOf(msg.optJSONObject("enter"))
        leaveCell = cellOf(msg.optJSONObject("fightLeave"))
        gmLock = msg.optBoolean("gmLock", false)
        holder = msg.optString("holder", "").ifEmpty { null }
        if (mode == null) { wantRoom = null; tries = 0; stuckSaid = false }
    }

    /** the router delivers to us now (room mode in a fight) */
    fun roomMode(): Boolean = mode == ROOM

    /** one look: what to tap now, given whether the bottom screen wants the pad */
    fun step(wantsPad: Boolean, nowMs: Long): Step {
        val m = mode ?: return Step(Act.NONE)
        if (gmLock) return Step(Act.NONE)
        if (wantRoom != wantsPad) { wantRoom = wantsPad; tries = 0; stuckSaid = false; lastTapAt = 0L }
        val goal = if (wantsPad) ROOM else GAME
        if (m == goal) { tries = 0; stuckSaid = false; return Step(Act.NONE) }
        if (tries > 0 && nowMs - lastTapAt < RETRY_MS) return Step(Act.NONE)      // the last tap's document is still on its way
        if (tries >= TRIES) {
            if (stuckSaid) return Step(Act.NONE)
            stuckSaid = true
            return Step(Act.STUCK)
        }
        val cell = (if (wantsPad) enterCell else leaveCell) ?: return Step(Act.NONE)
        tries++
        lastTapAt = nowMs
        return Step(if (wantsPad) Act.ENTER else Act.LEAVE, cell)
    }

    companion object {
        const val ROOM = "room"
        const val GAME = "game"
        const val RETRY_MS = 450L
        const val TRIES = 3

        private fun cellOf(o: JSONObject?): Pair<String, Int>? {
            val row = o?.optString("row", "") ?: ""
            val col = o?.optInt("col", -1) ?: -1
            return if (row.isNotEmpty() && col >= 0) row to col else null
        }

        /**
         * DirectPad's gate for a surface of the app (combat_nav Step 3): shown, and the capture holds for it - or, in a fight, the router
         * is in room mode (the presses are ours whoever the add-on names: the in-place swap or a stray ENTER's "app" holder).
         */
        fun directLive(shown: Boolean, holderMatch: Boolean, roomMode: Boolean): Boolean = shown && (holderMatch || roomMode)

        /** InteractView's own fight-loot reader (fightLootPress) runs only while the game has the pad: in room mode every press already
         *  reaches the add-on's loot cursor through the router, so reading it here too would act on the same press twice */
        fun appReadsLoot(fightNav: Boolean, fightMode: String?): Boolean = fightNav && fightMode != ROOM
    }
}
