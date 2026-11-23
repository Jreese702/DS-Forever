package com.jrees.wowcompanion

/**
 * THE MAP'S CLAIM ON THE CONTROLLER, AND SO ON THE STICKS (#584: "when the map is open, moving the cursor with the left stick also
 * moves the character"). Pure (no Android types): the unit tests run it off-device.
 *
 * The map's cursor is the app's own (MapStickDriver reads the sticks: a read-only subscription), while the game hears the same stick
 * unless the add-on catches it (Map.lua mr7 / mr8: an invisible frame that eats the stick, shown only while the Map room's "map", or
 * Home's guest "pad:home" with the map controls' op 3, holds the shared capture). #584's log (1.0.37): the Map room opened 0.26 s after
 * the Quests room closed, no hold answer ever came, the exit guard said "MAP has no controller hold" 2 s later, and for 25 s the room's
 * reader moved the cursor while the character walked. The rule since: THE CURSOR MOVES ONLY WHILE THE MAP HOLDS THE CONTROLLER -
 *  - [held]: the add-on said so (mapwin `held` and not `pend`, a hold ack that holds, or a press that reached the map: only the holder
 *    gets presses); a "pending" / queued take (a fight's ENTER, another holder first) is not held;
 *  - and the stick catch shows (mapwin `stick`), once the add-on has shown it at all ([catchSeen]: a client or an add-on that cannot
 *    catch the stick never says it; then the hold alone, as before);
 *  - else the stick is the game's, as it is without the map: the cursor stays where it is.
 * Every ask and every answer is logged ([log]: the WCMAP lines the next report carries). A hold that drops while the map shows is asked
 * again: at once ([lost] true) unless the drop names a holder or a choice that must be left alone ([WAIT_WHYS]), and the host's exit
 * guard asks again whenever the capture has named nobody for its grace ([nobody]).
 */
class MapHoldClaim(private val who: String, private val log: (String) -> Unit = {}) {
    enum class St { OFF, ASKING, PENDING, HELD, REFUSED, UNANSWERED, LOST }

    var st = St.OFF
        private set
    /** the add-on's stick catch shows now (mapwin `stick`) */
    var caught = false
        private set
    /** the add-on has shown the stick catch at least once this run: from then on the cursor waits for it */
    var catchSeen = false
        private set
    /** the drops asked again at once since the show ([MAX_DROPS] at most: a hold that keeps dropping is left to the exit guard) */
    var drops = 0
        private set
    /** the last refusal's or loss's reason (the tests, the note) */
    var why: String? = null
        private set
    /** the exit guard's re-asks since the show or the last hold ([guard]; [MAX_GUARD_ASKS] at most: never a hold cell tapped all visit) */
    var guardAsks = 0
        private set
    /** the guard's "left at that" said once (the log and the note), until the next show or hold */
    private var guardSaid = false

    val shown get() = st != St.OFF
    val held get() = st == St.HELD

    /** the cursor (and the right stick's zoom, the list's stick steps) may move: held, and the stick caught when the add-on catches it */
    fun cursorLive(needCatch: Boolean = true): Boolean = st == St.HELD && catchOk(needCatch)
    /** the stick catch is not in the way: shown, or never shown by this add-on (it cannot), or not asked for ([needCatch] false) */
    fun catchOk(needCatch: Boolean = true): Boolean = !needCatch || !catchSeen || caught

    /** the map came up: a fresh claim, asked now */
    fun shown() {
        st = St.ASKING; drops = 0; why = null; guardAsks = 0; guardSaid = false
        log("$who: claim on, the hold asked (the cursor waits for it)")
    }

    /** the map went: nothing claimed; the sticks are the game's */
    fun hidden() {
        if (st != St.OFF) log("$who: claim off (${st.name.lowercase()})")
        st = St.OFF; drops = 0
    }

    /** ask [n] of [max] (0 = no fixed number) went out ([sent]) or could not ([how]: why not, the route) */
    fun asked(n: Int, max: Int, sent: Boolean, how: String) {
        if (st == St.OFF) return
        log("$who: hold ask ${if (max > 0) "$n/$max" else "$n"} ${if (sent) "sent" else "not sent"} ($how)")
    }

    /** the round's asks all went out and nothing answered: the cursor keeps waiting; the exit guard asks again while nobody holds */
    fun roundOver(asks: Int) {
        if (st != St.ASKING && st != St.REFUSED) return
        if (st == St.ASKING) st = St.UNANSWERED
        log("$who: no hold after $asks asks (${why ?: "no answer"}): the stick stays the game's, the cursor waits")
    }

    /**
     * the add-on's answer to a hold ask ([ok], [why]; [waits] = ok but queued behind another holder or a fight's ENTER).
     * True = keep asking in this round (a refusal outside a fight: the next ask may land); false = the round is answered.
     */
    fun answer(ok: Boolean, why: String?, waits: Boolean = false): Boolean {
        if (st == St.OFF) { log("$who: hold answer after the claim ended (ok $ok${why?.let { ", $it" } ?: ""}): ignored"); return false }
        val w = why?.ifEmpty { null }
        when {
            ok && (waits || w == "pending") -> {
                if (st != St.HELD) st = St.PENDING
                log("$who: hold answered: ${if (w == "pending") "pending (the fight's ENTER)" else "queued"} - not held yet, the cursor waits")
                return false
            }
            ok -> {
                val was = st
                st = St.HELD; this.why = null; heldAgain()
                log("$who: hold answered: held${if (was == St.HELD) " (already)" else ""}${catchLine()}")
                return false
            }
            else -> {
                if (st == St.HELD) { log("$who: hold answer refused (${w ?: "?"}) while held: kept"); return false }
                st = St.REFUSED; this.why = w ?: "refused"
                log("$who: hold refused (${this.why}): the stick stays the game's")
                return w != "combat"
            }
        }
    }

    /**
     * the add-on's state document (Map.lua's mapwin: [held], [pend]). True = the hold DROPPED while the map shows (it said held, now
     * not, with no loss said): ask again.
     */
    fun win(held: Boolean, pend: Boolean): Boolean {
        if (st == St.OFF) return false
        val active = held && !pend
        if (active) {
            if (st != St.HELD) { st = St.HELD; why = null; heldAgain(); log("$who: held (the add-on's state)${catchLine()}") }
            return false
        }
        if (held && pend) {
            if (st != St.PENDING) { val was = st; st = St.PENDING; if (was == St.HELD) log("$who: hold now pending (the add-on's state): the cursor waits") }
            return false
        }
        if (st == St.HELD || st == St.PENDING) {
            st = St.LOST; why = "dropped"
            log("$who: hold dropped (the add-on's state says not held): the cursor waits")
            return dropAgain()
        }
        return false
    }

    /** a press reached the map: only the holder gets presses (also after a "pending" answer) */
    fun press() {
        if (st == St.OFF || st == St.HELD) return
        st = St.HELD; why = null; heldAgain()
        log("$who: held (a press arrived)${catchLine()}")
    }

    /** the hold was lost ([reason]: the capture's); true = ask again now */
    fun lost(reason: String?): Boolean {
        if (st == St.OFF) return false
        val r = reason?.ifEmpty { null } ?: "lost"
        val was = st
        st = St.LOST; why = r
        if (r in WAIT_WHYS) {
            log("$who: hold lost ($r${if (was != St.HELD) ", was ${was.name.lowercase()}" else ""}): not asked again at once (the exit guard asks when nobody holds)")
            return false
        }
        log("$who: hold lost ($r): the cursor waits")
        return dropAgain()
    }

    /** the add-on let the map's hold go on request ("free" / "leave" answered): not held */
    fun freed() {
        if (st == St.OFF || st == St.LOST) return
        st = St.LOST; why = "freed"
        log("$who: hold freed: the cursor waits")
    }

    /**
     * the host's exit guard: the capture has named NOBODY for its grace while the map shows. True = ask again now (the guard paces
     * these: its RETRY_MS, out of a fight). A claim that believed it held is not held any more.
     */
    fun nobody(): Boolean {
        if (st == St.OFF) return false
        if (st == St.HELD || st == St.PENDING) { st = St.LOST; why = "nobody holds"; log("$who: the capture names nobody: not held, the hold asked again") }
        else log("$who: the capture names nobody (${st.name.lowercase()}): the hold asked again")
        return true
    }

    /** what the exit guard's tick does for this claim ([guard]) */
    enum class Guard {
        /** nothing shows */ OFF,
        /** a round of asks is still out: left to finish (its end may still say the note), never restarted */ ROUND,
        /** refused for a reason another ask will not change ([RETRY_WHYS]): not asked again, the note */ FIXED,
        /** [MAX_GUARD_ASKS] re-asks and still no hold (a queued take that never came): not asked again, the note */ SPENT,
        /** ask again now (a new round) */ ASK
    }

    /**
     * the host's exit guard (paced by ExitGuard.RETRY_MS, out of a fight, the capture naming NOBODY while the map shows): ask again,
     * or not. [roundAsking] = the map's own round of asks is still going (its asks, its last answer's wait). Bounded (#584's review):
     * at most [MAX_GUARD_ASKS] re-asks until a hold lands, none while a round is out, none after a refusal that will not change.
     */
    fun guard(roundAsking: Boolean): Guard {
        if (st == St.OFF) return Guard.OFF
        if (roundAsking) return Guard.ROUND
        if (st == St.REFUSED && why !in RETRY_WHYS) {
            if (!guardSaid) { guardSaid = true; log("$who: hold refused (${why ?: "?"}): not asked again by the exit guard, the stick stays the game's") }
            return Guard.FIXED
        }
        if (guardAsks >= MAX_GUARD_ASKS) {
            if (!guardSaid) {
                guardSaid = true
                if (st != St.HELD && st != St.REFUSED) st = St.UNANSWERED
                log("$who: no hold after $guardAsks exit-guard asks (${why ?: st.name.lowercase()}): left at that, the stick stays the game's, the cursor waits")
            }
            return Guard.SPENT
        }
        nobody()
        guardAsks++
        return Guard.ASK
    }

    /** a new round of asks starts ([reason]: who wants it) */
    fun again(reason: String) {
        if (st == St.OFF) return
        if (st != St.HELD) st = St.ASKING
        log("$who: the hold asked again ($reason)")
    }

    /**
     * the add-on's stick catch showed ([on]) or hid (mapwin `stick`: one catch for the room's "map" and Home's "pad:home"). Followed
     * always (a catch already up when the map opens says nothing new); it counts as seen ([catchSeen]) only while this map shows, so
     * the room's catch never makes Home's controls wait for one, nor the other way round.
     */
    fun stick(on: Boolean) {
        if (on && st != St.OFF) catchSeen = true
        if (on == caught) return
        caught = on
        if (st != St.OFF) log("$who: the game's stick catch ${if (on) "on: the stick is the map's" else "off: the stick is the game's"}${if (held) " (held)" else ""}")
    }

    /** a hold landed: the exit guard's count starts again (a later loss, an NPC window's, may be asked for again) */
    private fun heldAgain() { guardAsks = 0; guardSaid = false }

    private fun dropAgain(): Boolean {
        if (drops >= MAX_DROPS) { log("$who: the hold dropped $drops times since the map opened: left to the exit guard"); return false }
        drops++
        return true
    }

    private fun catchLine(): String = when {
        caught -> ", the stick caught"
        catchSeen -> ", the stick catch not shown yet: the cursor waits for it"
        else -> ""
    }

    companion object {
        /** drops asked again at once, per show */
        const val MAX_DROPS = 3
        /**
         * losses that are not asked again at once: the player's own B / leave, a holder that took the place in the queue ("taken"),
         * an NPC window, a fight (its own pending ENTER route). The exit guard asks again once the capture names nobody.
         */
        val WAIT_WHYS = setOf("B", "left", "taken", "npc", "combat")
        /** the exit guard's re-asks per show until a hold lands */
        const val MAX_GUARD_ASKS = 3
        /**
         * refusals another ask may change: another holder ("busy": the guard asks only once the capture names nobody) and a fight
         * (the guard asks only out of one). Any other ("no controller capture", "no way in", "bad spec" ...) stays: not asked again.
         */
        val RETRY_WHYS = setOf("busy", "combat")
    }
}
