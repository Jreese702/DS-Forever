package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE TO THE ADD-ON (SingleScreen; the user's pick for "While playing", 2026-11-25: the game's HUD and our Nintendo bar, in
 * fights the Wings and our top cast bar - the cast bar for every cast out of fights too, as no bottom screen carries it now). The add-on
 * shows the Wings and our top cast bar in fights while the character's saved flags say Top screen (PadMap m179 / m187); single screen mode
 * must not write those (they are the player's two-screen choice, Edit Home's), so the app tells the add-on the MODE instead: Book b86's
 * FOURTH page (padtab 39, 38, 0, then [PadMapModel.P4_SINGLE_OFF] 0 / [PadMapModel.P4_SINGLE_ON] 1), PadMap m199 holds it IN MEMORY only
 * (a lease of PadMap SS_TTL 900 s; gone at a /reload), its `ss` in the padmap's dpad state says it (null = an older add-on: nothing is
 * sent, nothing shows). While it is on, PadMap's combatTopOn / combatCastTopOn answer true for ActionBar (the Wings, our top bar in
 * fights) and ActionBar nbar25 shows our top cast bar for every cast of the player out of fights too; the padmap's own `ct` / `cc` keep
 * saying the SAVED flags (Home's two-screen logic stays honest).
 *
 * EditPreview's pattern: want = [SingleScreen.inUse] (every host runs it: the two-screen host sends OFF while `ss` says 1); sent out of
 * combat only, re-sent every [RESEND_MS] until `ss` says it (at most [TRIES] a wish), renewed every [RENEW_MS] (before the lease ends);
 * the four taps [GAP_MS] apart (TapSpacing.MIN_MS: code 0 right after the prefix's cell 0 would fall in TapPad's 0.25 s repeat guard).
 * THE REVIEW (2026-11-25): a lease this host did not send (a new host: every switch, every launcher resume) is renewed at once out of a
 * fight - the old host's lease may lapse before RENEW_MS from the new host's first sighting; PadMap m200 says a lapsed lease at once.
 * Pure.
 */
object SingleTop {
    const val OFF = PadMapModel.P4_SINGLE_OFF
    const val ON = PadMapModel.P4_SINGLE_ON
    /** re-sent this often until the add-on's `ss` says it (TopCombatBar's 5 s) */
    const val RESEND_MS = TopCombatBar.RESEND_MS
    /** the sends of one wish before it waits for the next change */
    const val TRIES = 6
    /** PadMap's SS_TTL is 900 s: a mode still on is sent again after [RENEW_MS] */
    const val RENEW_MS = 600_000L
    /** the add-on's lease (PadMap m199 R.SS_TTL): SingleTopTest keeps the renewal well inside it */
    const val ADDON_TTL_MS = 900_000L
    const val GAP_MS = TapSpacing.MIN_MS
    const val BATCH_MS = 3 * GAP_MS

    sealed class Act {
        /** the add-on has it already */
        object Matched : Act()
        /** wait: an add-on without it (no `ss`), a fight, a send just went */
        object Wait : Act()
        /** tap the mode [on] */
        data class Send(val on: Boolean) : Act()
    }

    /** [want] the mode (SingleScreen.inUse), [ss] the add-on's (null = an older add-on), [inFight] a fight now, [sinceSendMs] since this
     *  host's last send of this wish (null = none yet: a mode shown on would be a lease this host never sent - renewed now) */
    fun decide(want: Boolean, ss: Int?, inFight: Boolean, sinceSendMs: Long?): Act {
        if (ss == null) return Act.Wait
        val shows = ss == 1
        if (shows == want) return if (want && !inFight && (sinceSendMs == null || sinceSendMs >= RENEW_MS)) Act.Send(true) else Act.Matched
        if (inFight || (sinceSendMs != null && sinceSendMs < RESEND_MS)) return Act.Wait
        return Act.Send(want)
    }

    /** how long from [now] until a mode still on is looked at again: [RENEW_MS] after this host's send [since]; none of its own yet
     *  (null: in a fight, a lease it did not send) = [RESEND_MS] (renewed once the fight ends) */
    fun renewDelay(now: Long, since: Long?): Long = if (since == null) RESEND_MS else maxOf(1_000L, since + RENEW_MS - now)

    /** the taps for the mode [on] */
    fun cells(on: Boolean): List<Pair<String, Int>> = PadMapModel.singleCells(on)
}
