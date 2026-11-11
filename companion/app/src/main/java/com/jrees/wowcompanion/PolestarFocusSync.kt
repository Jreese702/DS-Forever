package com.jrees.wowcompanion

/**
 * THE POLESTAR ROW IS THE GAME'S FOCUS TOO (ps3; the user, 2026-11-11: "using the polestar objective tracker should behave like any
 * other quest that gets focused, and should probably be the default selection if its enabled when logging in"). While MapView's
 * light blue row holds, the game's focus follows Polestar's step through the one focus path (HomeView.focusQuest / unfocusQuest,
 * the user's 10-19 focus-sync exception, [DefaultFocusPush]):
 *  - a step on a TRACKED quest of the log: that quest is focused;
 *  - a step with NO quest in the log (accept / go to): the game's focus is cleared (the user's ruling, 2026-11-11);
 *  - a step on a logged but untracked quest: the game is left as it is ([LEAVE]; tracking it would be a new game write).
 * The game auto-super-tracks a quest on an objective update, and at a turn-in the map's focus goes to none before the log drops the
 * quest, so a drift is only answered once it has lasted [SETTLE_MS], and at most once per [RETRY_MS] per target. Never released by
 * the game: only the app's own explicit picks drop the row (MapView.psRelease). A send the host refused (no fresh quest list, a
 * fight, a waiting tap) is not counted ([sent] false): tried again [SETTLE_MS] later, [MAX_REFUSED] times at most per [RETRY_MS]. Pure.
 */
class PolestarFocusSync {
    sealed class Act {
        object None : Act()
        data class Focus(val q: Int) : Act()
        /** clear the game's focus, which is quest [q] now */
        data class Unfocus(val q: Int) : Act()
    }

    private var want = Int.MIN_VALUE           // the step's target the sync follows (MIN: none yet)
    private var driftAt = -1L                  // when the game's focus was first seen off the target (-1: it agrees)
    private var triedAt = Long.MIN_VALUE / 2   // the last Act handed out (the floor between tries)
    private val sentAt = HashMap<Int, Long>()  // target -> when a send for it went out (0 = the unfocus)
    private var pushed = 0                     // the last quest a Focus went out for (its focus check may still retry it)
    private var cancel = 0
    private var refused = 0                    // refusals in a row for the target (MAX_REFUSED: counted as a send, the cap holds)

    /**
     * [holds] the light blue row holds; [stepQ] the step's tracked quest (> 0), 0 for a step with no quest in the log, [LEAVE] for
     * one on a logged but untracked quest; [game] the game's focus (0 none); at [now] ms: what to send.
     */
    fun tick(holds: Boolean, stepQ: Int, game: Int, now: Long): Act {
        val target = if (holds) stepQ else Int.MIN_VALUE
        if (target != want) {                                     // a new step (or the row let go): follow it afresh
            if (pushed > 0 && pushed != target) { cancel = pushed; pushed = 0 }
            want = target; driftAt = -1L; triedAt = Long.MIN_VALUE / 2; refused = 0
        }
        if (!holds || target < 0) return Act.None
        if (game == target) { driftAt = -1L; return Act.None }
        if (driftAt < 0) driftAt = now
        if (now - driftAt < SETTLE_MS) return Act.None            // the game's auto-track, a turn-in's order: wait it out
        if (now - triedAt < SETTLE_MS) return Act.None
        val at = sentAt[target]
        if (at != null && now - at < RETRY_MS) return Act.None    // at most once per RETRY_MS per target
        triedAt = now
        return if (target > 0) Act.Focus(target) else Act.Unfocus(game)
    }

    /** the host's answer for the Act of [target] (0 = an unfocus): [ok] = it went out (or the game already had it) */
    fun sent(target: Int, ok: Boolean, now: Long) {
        if (target != want) return
        if (!ok && ++refused < MAX_REFUSED) return                // a passing refusal: again SETTLE_MS later
        refused = 0
        sentAt[target] = now                                      // (a lasting one, "no tap column": waits RETRY_MS as a send would)
        if (ok && target > 0) pushed = target
    }

    /** the player picked the row again (MapView.psSetPicked(true)): the per-target cap is for the game's auto-track only, never for
     *  the player's own pick, so it is lifted (a none-gap keeps it) */
    fun picked() { sentAt.clear(); triedAt = Long.MIN_VALUE / 2; refused = 0 }

    /** a quest whose pushed focus the step has moved on from (its focus check is to stop retrying it), once; 0 none */
    fun takeCancel(): Int = cancel.also { cancel = 0 }

    companion object {
        const val SETTLE_MS = 2_000L
        const val MAX_REFUSED = 5
        const val RETRY_MS = DefaultFocusPush.RETRY_MS
        /** stepQ for a step on a logged but untracked quest: the game's focus is left as it is */
        const val LEAVE = -1
        /** how long a Polestar none-gap (taxi, a step change, a GetWaypoint gap) still counts as the row holding */
        const val GAP_MS = 30_000L
        /** how long after the game goes live the row is waited for before the first pstep (no Polestar: the default push again) */
        const val FIRST_MS = 20_000L

        /**
         * The default focus push waits (MapView.pushDefault, ps3): Polestar mode with the row picked, and a step now, or a none-gap
         * shorter than [GAP_MS] (ms since the last pstep WITH a step: [sinceStepMs], null = none yet), or the first [FIRST_MS] of a live game.
         */
        fun waits(polestarMode: Boolean, picked: Boolean, hasStep: Boolean, sinceStepMs: Long?, sinceLiveMs: Long): Boolean =
            polestarMode && picked && (hasStep || (sinceStepMs != null && sinceStepMs < GAP_MS) || sinceLiveMs < FIRST_MS)
    }
}
