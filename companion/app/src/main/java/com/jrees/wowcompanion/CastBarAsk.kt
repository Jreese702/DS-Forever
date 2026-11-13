package com.jrees.wowcompanion

/**
 * THE ONE-TIME "TRY THE NEW TAP-TO-CAST BAR?" QUESTION (the user, 2026-11-13: "For this new install, I want it to have a one time pop
 * up asking people if they want to try the new touch input bar. if they select yes then assign it as both with the default, and hide in
 * combat as the default."). Pure rules; the host (CompanionHost.castBarAsk) shows it with the prompt look ([SetupView.ask]).
 *  - asked ONCE per install, app-wide ([HomeSettings.castBarAsked]); either answer ends it for good
 *  - never to a player who already set the bar themselves (Settings > Bottom > Tap-to-cast bar): marked asked, never shown
 *  - only on Home with the game in the world and nothing else up (no room, no module, no other prompt, not the start screen), never
 *    in a fight; a fight that starts while it is open hides it ([hideNow]) and it comes back after
 */
object CastBarAsk {
    enum class Act { NONE, MARK, WAIT, ASK }

    /** how long Home must have stood ready (in the world, nothing else up, no fight) before it asks: never the moment the world loads
     *  or a fight ends */
    const val SETTLE_MS = 4_000L

    /** Home can ask now: the game in the world, Home showing with nothing of its own or another prompt up, no fight */
    fun ready(inWorld: Boolean, onHome: Boolean, busy: Boolean, fight: Boolean): Boolean = inWorld && onHome && !busy && !fight

    /** what to do now: NONE (asked already), MARK (the player chose the bar themselves: never ask), WAIT (not now), ASK; [readyForMs]
     *  how long [ready] has held without a break (0 = not ready) */
    fun decide(asked: Boolean, chosen: Boolean, side: CastStripModel.Side, readyForMs: Long): Act = when {
        asked -> Act.NONE
        chosen || side != CastStripModel.Side.OFF -> Act.MARK
        readyForMs < SETTLE_MS -> Act.WAIT
        else -> Act.ASK
    }

    /** nothing else holds the controller: the capture's holder is none, or the question's OWN hold of Home's guest "home"
     *  ([ownHold]: CompanionHost.setupPad; its "pad:home" landing must not count as Home left and put the question away) */
    fun capFree(holder: String, ownHold: Boolean): Boolean = holder.isEmpty() || (ownHold && holder == "pad:home")

    /** the open question is put away (unanswered: it comes back) when a fight starts or Home is left */
    fun hideNow(open: Boolean, fight: Boolean, onHome: Boolean): Boolean = open && (fight || !onHome)

    /** Yes OPENS THE CAST BAR TAB (the user, 2026-11-13: "if they press yes on the prompt to try the tap to cast bar, we should open
     *  them to the spell bind room that you are making so they can add spells to it"): the Controller room on its Cast bar tab, slot 1,
     *  by the room's own open (B one step back, then Home). Now when Home is free ([owed] the Yes's open not done yet, on Home, no fight,
     *  nothing holding the controller, nothing else up); else as soon as it is, once */
    fun openTabNow(owed: Boolean, onHome: Boolean, fight: Boolean, capFree: Boolean, busy: Boolean): Boolean =
        owed && onHome && !fight && capFree && !busy

    /** the answer: Yes = the bar under both columns, hidden in a fight (the user's defaults); No = left Off. Asked for good either way */
    fun answer(yes: Boolean) {
        if (yes) {
            HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
            HomeSettings.castBarSide = CastStripModel.Side.BOTH
        }
        HomeSettings.castBarAsked = true
    }

    // ---------------------------------------------------------------- the picture's spells (the user, 2026-11-13: "make sure that this
    // pop up uses realistic visuals instead of the renders"): the real Home draws the bar, with real spell icons

    /** a few of the game's own spell icons (fileIDs) for a player with no bar and no Spellbook word yet */
    val GENERIC_ICONS = listOf(135824, 136048, 136026, 136052, 136051, 136086, 136102, 136015)

    /** the bar the picture shows: the player's own when it has a spell; else their first known spells ([known]: spell id to icon
     *  fileID, the Spellbook's order); else [generic] icons. [has] = the icon can be drawn on this device (only those are used) */
    fun pictureBar(live: CastStripModel.Bar?, known: List<Pair<Int, Int>>, has: (Int) -> Boolean,
                   generic: List<Int> = GENERIC_ICONS): CastStripModel.Bar {
        if (live != null && live.slots.any { it.id != null && it.icon != null }) return live
        val n = CastStripModel.SLOTS
        val picks = known.filter { it.second > 0 && has(it.second) }.take(n).ifEmpty { generic.filter(has).map { it to it }.take(n) }
            .ifEmpty { generic.take(n).map { it to it } }
        // a cell of its own (as a built slot: drawn lit, not waiting); the picture's Home never taps (the stage has no tap pad)
        val slots = (0 until n).map { i -> picks.getOrNull(i)?.let { CastStripModel.Slot(i, it.first, it.second, i, false) }
            ?: CastStripModel.Slot(i, null, null, null, false) }
        return CastStripModel.Bar(slots, null, false, "ask", null, n)
    }

    /** the question's words (English; the host translates) */
    const val TITLE = "Try the new tap-to-cast bar?"
}
