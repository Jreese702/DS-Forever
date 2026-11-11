package com.jrees.wowcompanion

/**
 * THE DS WOW TOOLBAR'S CHOICE, the app's side (issue #136, 2026-11-11: "Cannot disable DsWoW toolbar from settings"). What the host does
 * with each current padmap's `dpad` word for one character. Pure: CompanionHost keeps the stores and does the taps.
 *
 * The causes it closes:
 *  - the Settings switch never cleared the once-per-character prompt's "owed" flag, so while the prompt was owed every new padmap ran the
 *    prompt path again, and its default ON tap (b139) turned the toolbar back on;
 *  - a switch made while the game was not taking taps (closed, starting, a refused tap) was saved in the app but never sent again, and the
 *    next run's add-on word (its OLD answer) then overwrote it;
 *  - an add-on whose saved file lost the choice (no clean exit: the game's SavedVariables are written only then) won over the app's.
 *
 * The rules: an EXPLICIT choice (the Settings row, the prompt's Yes / No) is [pending] until the add-on's `want` says the same; while it
 * is pending it is re-sent (never in a fight, at most once per [RESEND_MS]) and the prompt never runs. Once confirmed, the add-on's word
 * is followed again inside a run (the Controller room's switch, the in-game Book), but at the FIRST word of each game run the app's kept
 * choice wins over a different add-on word (the add-on's file is the older one).
 */
object DpadChoice {
    /** the gap between two sends of the same pending choice (the add-on answers a tap with a padmap within a second) */
    const val RESEND_MS = 5_000L

    sealed class Act {
        /** nothing to do */
        object None : Act()
        /** wait for a later padmap (a fight, or a send just went) */
        object Wait : Act()
        /** the add-on has the pending choice now: no longer pending */
        object Confirm : Act()
        /** keep the add-on's word as the choice (changed in the game or in the Controller room) */
        data class Adopt(val want: Boolean) : Act()
        /** tap [on] (cols on / off) and keep the choice pending until the add-on says it */
        data class Send(val on: Boolean, val why: String) : Act()
        /** the once-per-character prompt's path (its own checks: the setup, the start screen, a fight) */
        object Ask : Act()
    }

    /**
     * [addonWant] the padmap's `want` (null = never asked in this game); [stored] the app's kept choice (null = none); [pending] that
     * choice is the player's and the add-on has not said it yet; [owed] the prompt is owed and was never answered; [firstOfRun] the first
     * current word for this character in this game run; [sinceSendMs] since the last send of it (null = none this run).
     */
    fun decide(addonWant: Boolean?, stored: Boolean?, pending: Boolean, owed: Boolean, firstOfRun: Boolean, inFight: Boolean,
               sinceSendMs: Long?): Act {
        if (pending && stored != null) {
            if (addonWant == stored) return Act.Confirm
            if (inFight || (sinceSendMs != null && sinceSendMs < RESEND_MS)) return Act.Wait
            return Act.Send(stored, "your choice is not in the game yet (the game says ${addonWant ?: "nothing"})")
        }
        // an owed prompt: our own default ON tap is not an answer (b139)
        if (owed) return Act.Ask
        if (stored != null && firstOfRun && addonWant != stored) {
            if (inFight) return Act.Wait
            return Act.Send(stored, if (addonWant == null) "the game has no answer for this character"
                else "the game's saved file says $addonWant (an older one: no clean exit?)")
        }
        if (addonWant != null) return if (addonWant != stored) Act.Adopt(addonWant) else Act.None
        if (stored != null) return Act.None
        return Act.Ask
    }

    /** what the Settings row shows: the player's pending choice over the add-on's word (the switch shows what was picked at once) */
    fun shown(addonWant: Boolean?, stored: Boolean?, pending: Boolean): Boolean? = if (pending && stored != null) stored else addonWant
}
