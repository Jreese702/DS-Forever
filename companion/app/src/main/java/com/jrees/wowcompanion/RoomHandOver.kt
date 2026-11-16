package com.jrees.wowcompanion

/**
 * EVERY ROUTE THE SAME (the user 2026-10-01: "it should act the EXACT same for all rooms and be no different"; picked "Every room opens
 * the game's own window behind it, as Professions does"). The hand-overs that lived on the touch path only (pickRoom) until then -
 * Settings, Bags, the Spellbook and its Talents room, Character - are decided here for EVERY route that changes the room (a touch, the
 * bar cursor, a game window's jump, a whisper, the map pin, the Spells overlay, the trainer lend); MainActivity's show() sends them.
 * The room left lets go before the new one takes (2026-09-30 20:11: Settings -> Map sent Map's hold before Settings' free, "busy").
 * Pure (tested in home_test). Bags', Settings' and Character's own taps decide for themselves whether a tap is due (BagsView's doors /
 * state.ctlOn, SettingsPad.held, CharGrid.enterCell / leaveCell); the Spellbook's are guarded here (its enterRoom / leaveRoom are not).
 */
object RoomHandOver {
    enum class Kind { SETTINGS, BAGS, SPELLBOOK, TALENTS, CONTROLLER, CHARACTER, MAP, OTHER }

    enum class Tap { SETTINGS_LEAVE, BAGS_LEAVE, SPELLS_CLOSE, TALENTS_CLOSE, CONTROLLER_CLOSE, CHARACTER_CLOSE, MAP_LEAVE,
                     SETTINGS_ENTER, BAGS_ENTER, SPELLS_OPEN, TALENTS_OPEN, CONTROLLER_OPEN, CHARACTER_OPEN, MAP_ENTER }

    /** who holds now: the Spellbook's guest, whether on its Talents page, and whether on the Controller room's page ([croom]: the
     *  CONTROLLER ROOM, 2026-10-05, is the same "spells" guest on its own page, Book.lua b17 `croom`; no game window) */
    class Held(val spells: Boolean, val talentsPage: Boolean, val croom: Boolean = false)

    private fun spells(k: Kind) = k == Kind.SPELLBOOK || k == Kind.TALENTS || k == Kind.CONTROLLER

    /** the let-go taps for leaving [was] for [r] ([same] = the very same room: nothing). Between the Spellbook and its Talents the
     *  controller stays (one guest, two pages: Book.lua b3s `pg`) */
    fun leave(was: Kind, r: Kind, same: Boolean, h: Held): List<Tap> {
        if (same) return emptyList()
        val out = ArrayList<Tap>()
        if (was == Kind.SETTINGS) out.add(Tap.SETTINGS_LEAVE)
        if (was == Kind.BAGS) out.add(Tap.BAGS_LEAVE)
        if (!spells(r) && h.spells) {
            if (was == Kind.SPELLBOOK) out.add(Tap.SPELLS_CLOSE)
            else if (was == Kind.TALENTS) out.add(Tap.TALENTS_CLOSE)
            else if (was == Kind.CONTROLLER) out.add(Tap.CONTROLLER_CLOSE)
        }
        // Character's own view picks the cell or none (CharGrid.leaveCell: the window's close while it is open or the room holds)
        if (was == Kind.CHARACTER) out.add(Tap.CHARACTER_CLOSE)
        // the Map room's own view picks the cell or none (MapRoomPad.leaveCol: the game's close while its map shows, else the free;
        // nothing once the add-on let go itself, e.g. the player's B closed the game's map)
        if (was == Kind.MAP) out.add(Tap.MAP_LEAVE)
        return out
    }

    /**
     * A ROOM LEFT STOPS ASKING AT ONCE (the user, 2026-11-16: "after opening the bags from the Dpad select, and then pressing be to close
     * has a weird behavior where it reopens again. this is not new"). The rooms whose own view asks again later - Bags' take check
     * (TAKE_CHECK_MS), Character's hold re-ask and its window still to open, Settings' hold re-ask (HoldAsk) - end those asks the moment
     * the room is left, on EVERY route: the B route puts the room's close cells off ([leave], B_LEAVE_MS, so they do not race the game's
     * B) and a fight's leave skips them, and until now the asks lived in those cells. 1476 measured: B 1.08 s after Bags opened, the
     * game's B let go, then Bags' check 1.5 s after the open saw no hold and took the controller for Bags again; the take's document
     * brought the room back and the put-off close freed the hold it had just taken (the room up with nobody holding). No tap here: only
     * the view's own pending asks go; the close cells still wait (or are skipped) as before.
     */
    enum class Stop { BAGS_TAKE, CHARACTER_HOLD, SETTINGS_HOLD }

    /** the asks that end now, as the room [was] is left ([same] = the very same room: nothing) */
    fun stops(was: Kind, same: Boolean): List<Stop> = if (same) emptyList() else when (was) {
        Kind.BAGS -> listOf(Stop.BAGS_TAKE)
        Kind.CHARACTER -> listOf(Stop.CHARACTER_HOLD)
        Kind.SETTINGS -> listOf(Stop.SETTINGS_HOLD)
        else -> emptyList()
    }

    /**
     * The B route's put-off leave of [madeFor] meets show() of a room: [same] = that very room is shown again (the game's own bag key
     * or Character window inside B_LEAVE_MS). Its close cells would free the hold the room has again (1476: bmvq 30 right after the
     * room came back), so they are dropped (true). Not for the rooms without hand-over taps ([Kind.OTHER]): what waits there is the
     * room's own leave (Professions' leftByB / modeSent book-keeping), which its enter needs first; it runs as before.
     */
    fun dropPutOff(madeFor: Kind, same: Boolean): Boolean = same && madeFor != Kind.OTHER

    /** the take taps for coming to [r] from [was]. Opened through the game's own window the room holds already and nothing is tapped
     *  (the trainer lend too); between the two Spells pages the page moves, unless the game's own button moved it already */
    fun enter(was: Kind, r: Kind, same: Boolean, h: Held): List<Tap> {
        if (same) return emptyList()
        return when (r) {
            Kind.SETTINGS -> listOf(Tap.SETTINGS_ENTER)
            Kind.BAGS -> listOf(Tap.BAGS_ENTER)
            // from the Controller room the book is opened anew (Spells: "Opening the Spellbook ends the Controller room addon-side")
            Kind.SPELLBOOK -> if (!h.spells || (was == Kind.TALENTS && h.talentsPage) || h.croom) listOf(Tap.SPELLS_OPEN) else emptyList()
            Kind.TALENTS -> if (!h.spells || !h.talentsPage) listOf(Tap.TALENTS_OPEN) else emptyList()
            // the Controller room's page (Spells: "enterRoom() on EVERY route in"; no game window, so nothing opens it but this),
            // unless it is on that page already
            Kind.CONTROLLER -> if (!h.spells || !h.croom) listOf(Tap.CONTROLLER_OPEN) else emptyList()
            // Character's own view picks the cell or none (CharGrid.enterCell: none while the game's window is open and the room holds)
            Kind.CHARACTER -> listOf(Tap.CHARACTER_OPEN)
            // the Map room asks for its hold (Map.lua "map", re-asked by HoldAsk); the game's map opens behind it once the hold is
            // real (MapRoomPad.wantOpen), or holds already when the GAME opened it (M): the hold then answers "already held"
            Kind.MAP -> listOf(Tap.MAP_ENTER)
            Kind.OTHER -> emptyList()
        }
    }

    /**
     * THE BAR'S HAND-OVER WITHOUT A GAP (the user 2026-10-06: the room takes over BEFORE the bar lets go; AGENT_LOG b79 "NO-GAP").
     * Home's bar cursor holds the controller as the guest "pad:bar", and EVERY room's take pre-empts it (capture v7 out of a fight,
     * v12 in one while the router is in room mode: the bar hears lost "taken"). So a pick into another room no longer frees the bar
     * first: the room's own hold takes over. The bar frees itself only when (a) a take is QUEUED behind it (the interact doc's `wait`,
     * e.g. a fight outside room mode): its free grants that take at the next document; or (b) [MAX_MS] after the pick no current
     * document has named another holder (the room's hold never landed): the free goes out then, late (b76's safety, made longer so
     * the rooms' own re-asks, HoldAsk 4 x 0.7 s, and Professions' window-first entry, AWAIT_MAX 1.5 s, come first). Pure (home_test).
     */
    object Bar {
        const val HOLDER = "pad:bar"
        const val MAX_MS = 2_500L
        enum class Step { WAIT, DONE, FREE }

        /** a room change while the cursor is up: true = keep the bar's hold for the new room's take to pre-empt. Home (no take of its
         *  own), the room already on screen (nothing takes) and a bar that does not really hold (asked, or only queued) free at once */
        fun keep(toHome: Boolean, same: Boolean, asked: Boolean, holds: Boolean): Boolean = !toHome && !same && asked && holds

        /** a CURRENT interact document during the hand-over: its `holder` ("" = nobody) and doc.wait's `for` ("" = none) */
        fun onDoc(holder: String, waitFor: String): Step = when {
            holder != HOLDER -> Step.DONE
            waitFor.isNotEmpty() && waitFor != HOLDER -> Step.FREE
            else -> Step.WAIT
        }

        /** [MAX_MS] after the pick: [docSince] = a current document was read since the pick, [holds] = the last one named the bar */
        fun onTimeout(docSince: Boolean, holds: Boolean): Step = if (docSince && !holds) Step.DONE else Step.FREE
    }
}
