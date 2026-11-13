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

    /** the open question is put away (unanswered: it comes back) when a fight starts or Home is left */
    fun hideNow(open: Boolean, fight: Boolean, onHome: Boolean): Boolean = open && (fight || !onHome)

    /** the answer: Yes = the bar under both columns, hidden in a fight (the user's defaults); No = left Off. Asked for good either way */
    fun answer(yes: Boolean) {
        if (yes) {
            HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
            HomeSettings.castBarSide = CastStripModel.Side.BOTH
        }
        HomeSettings.castBarAsked = true
    }

    /** the question's words (English; the host translates) */
    const val TITLE = "Try the new tap-to-cast bar?"
}
