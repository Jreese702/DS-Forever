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
 *
 * OUT OF A FIGHT (Interact i152, the user 2026-11-12: "I died and then my jump input just stopped working"): the router in room mode with no
 * fight and nobody holding (doc.routerMode "room" + doc.routerLeave) routes every press to the add-on's empty sink - the reconciler taps that
 * LEAVE ([TRIES] / [RETRY_MS], then STUCK once), whatever the bottom screen shows. And a dead player's DEATH pop-up alone never asks for an
 * ENTER (the tap that, landing just after dying ended the fight, left the router in room mode).
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
    /** the fight began in the game menu (Interact i148's doc.gmFight): it stays the game's to its end, the menu shown or not - PadMap
     *  stepped aside at the pull, so an ENTER would only make every press act in the app AND in the game (review of 2026-11-11) */
    var gmFight = false
        private set
    /** who holds (the capture id), from the same document */
    var holder: String? = null
        private set

    /** an NPC / loot window let go with B in this fight (Interact's N.npcLeft): it no longer wants the pad until it closes */
    var npcLeft = false
        private set
    private var backN = 0
    private var backSeen = 0
    /** i151's doc.fightHome: a room let go with B at its top level in this fight (n counts, who = the capture id that asked) */
    private var homeN = 0
    private var homeSeen = 0
    private var homeWho: String? = null

    /** i152: out of a fight the router is in room mode with nobody holding (doc.routerMode "room" with doc.routerLeave) */
    var outRoom = false
        private set
    private var outLeave: Pair<String, Int>? = null
    private var outTries = 0
    private var outLastAt = 0L
    private var outStuckSaid = false
    /** i152's doc.dead: the player is dead or a ghost */
    var dead = false
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
        gmFight = msg.optBoolean("gmFight", false)
        holder = msg.optString("holder", "").ifEmpty { null }
        npcLeft = msg.optBoolean("npcLeft", false)
        backN = msg.optInt("fightBack", 0)
        msg.optJSONObject("fightHome").let { h -> homeN = h?.optInt("n", 0) ?: 0; homeWho = h?.optString("who", "")?.ifEmpty { null } }
        if (mode == null) { wantRoom = null; tries = 0; stuckSaid = false; backN = 0; backSeen = 0; homeN = 0; homeSeen = 0; homeWho = null }
        dead = msg.optBoolean("dead", false)
        outLeave = if (mode == null && msg.optString("routerMode", "") == ROOM) cellOf(msg.optJSONObject("routerLeave")) else null
        outRoom = outLeave != null
        if (!outRoom) { outTries = 0; outLastAt = 0L; outStuckSaid = false }
    }

    /**
     * combat_nav Step 4 (the user, 2026-11-11: "If I am looting ... my dpad should not stop working"): B at a held window's top level in
     * a fight reaches the add-on as PAD2 (never the game's close from our code), which steps `fightBack`; the app answers with ONE LEAVE
     * tap, whose secure click closes the window. The cell to tap once per step, else null.
     */
    fun backAsked(): Pair<String, Int>? {
        if (mode != ROOM || backN <= backSeen) return null
        backSeen = backN
        return leaveCell
    }

    /**
     * B GOES HOME FROM EVERY ROOM IN A FIGHT (the user, 2026-11-12: "ALL ROOMS SHOULD ALWAYS GO HOME WITH B EVEN IN COMBAT"; on 1429 B in
     * the Bags room did nothing after the pull): a room whose own B at its top level lets go through the capture (Bags, Character, the
     * book's Spellbook / Talents / Controller, Calendar, Group Finder, Social, Quests) is answered "tap leave" in a fight, so Interact i151
     * asks the app to go Home instead (doc.fightHome). The capture id that asked, once per ask, else null; Home's LEAVE ([step]) follows.
     */
    fun homeAsked(): String? {
        if (mode == null || homeN <= homeSeen) return null
        homeSeen = homeN
        return homeWho ?: ""
    }

    /** the router delivers to us now (room mode in a fight) */
    fun roomMode(): Boolean = mode == ROOM

    /**
     * one look: what to tap now, given whether the bottom screen wants the pad; [deathOnly] = its only want is DialogView's DEATH pop-up
     * (with a dead player that is never an ENTER: the game answers its own pop-up)
     */
    fun step(wantsPad: Boolean, nowMs: Long, deathOnly: Boolean = false): Step {
        val m = mode ?: return outOfAFight(nowMs)
        if (gmLock || gmFight) return Step(Act.NONE)
        @Suppress("NAME_SHADOWING") val wantsPad = wantsPad && !(deathOnly && dead)
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

    /** i152: out of a fight, room mode with nobody holding is never right - its LEAVE, [TRIES] taps [RETRY_MS] apart, then STUCK once */
    private fun outOfAFight(nowMs: Long): Step {
        val cell = outLeave ?: return Step(Act.NONE)
        if (outTries > 0 && nowMs - outLastAt < RETRY_MS) return Step(Act.NONE)
        if (outTries >= TRIES) {
            if (outStuckSaid) return Step(Act.NONE)
            outStuckSaid = true
            return Step(Act.STUCK)
        }
        outTries++
        outLastAt = nowMs
        return Step(Act.LEAVE, cell)
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

        /**
         * i151's ask goes Home from the room on screen [roomName] (a Room name) when the capture id [who] holds for that room
         * (HomeBack.roomsOf). Never from Home, never for a wheel, the bar cursor or a Home module (they close themselves: no room to
         * leave), and never from ANOTHER room the player opened since (a late ask of the room left behind names a different one)
         */
        fun fightHomeGoes(who: String, roomName: String): Boolean = roomName != "HOME" && roomName in HomeBack.roomsOf(who)

        /** InteractView's own fight-loot reader (fightLootPress) runs only while the game has the pad: in room mode every press already
         *  reaches the add-on's loot cursor through the router, so reading it here too would act on the same press twice */
        fun appReadsLoot(fightNav: Boolean, fightMode: String?): Boolean = fightNav && fightMode != ROOM
    }
}
