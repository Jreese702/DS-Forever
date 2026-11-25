package com.jrees.wowcompanion

/**
 * EDIT HOME'S TOP-SCREEN PREVIEW (the user on test 1530, 2026-11-25: "When choosing the top screen options in combat it should also
 * display the top screen ui so that its easy to visualize. Likewise the party mode should show a full party on the top screen so its easy
 * to visualize"). While Edit Home is LIVE (open, not parked by a fight or another room) the top screen previews the tab being edited:
 *  - IN COMBAT with Combat bars = Top screen: the Wings as a Top fight lays them (ActionBar nbar23), with Cast bar = Top screen our top
 *    cast bar above the HUD (a sample cast) and, with the Swing timer on, its swing line; no Top pick = nothing (the band stays below);
 *  - PARTY MODE: a FULL party - you and four placeholder rows "Party 1" .. "Party 4" under the HUD as Party mode lays them (Hub hub79,
 *    Trail prd25), solo or not, with the Wings clear of it when its Combat bars = Top screen (and its cast bar / swing as above);
 *  - OUT OF COMBAT (and edit mode closed, parked, or away): none - the real top screen.
 * It follows the picks as they change (the draft's page, never the saved flags: CompanionHost's top / cast / swing checks still send
 * nothing while edit mode is live). THE CHANNEL: Book b85's page-3 codes ([PadMapModel.P3_PV_OFF] 32 none, 33-35 In combat, 36-39 Party
 * mode), the add-on's `pv` in the padmap's dpad state says what it shows (0 none; null = an add-on without it: nothing is sent), re-sent
 * every [RESEND_MS] until it does - TopCombatBar's pattern. The add-on side is display only and in memory only: refused in a fight,
 * dropped the moment one starts (it never comes back by itself: the fight parks edit mode, and its end sends it again), gone after a lease
 * (PadMap PV_TTL) and at a /reload. Top screen / Cast bar Top need the add-on's `ct` / `cc` (TopCombatBar.previewed's rule). Pure.
 */
object EditPreview {
    const val OFF = PadMapModel.P3_PV_OFF
    const val RESEND_MS = TopCombatBar.RESEND_MS
    /** the sends of one wish before it waits for the next change (CompanionHost's MIRROR_TRIES pattern) */
    const val TRIES = 4
    /** the add-on's lease is PadMap's PV_TTL (900 s): a preview still wanted is sent again after [RENEW_MS] so it never lapses mid-edit */
    const val RENEW_MS = 600_000L
    /** THE BATCH SPACED (the review: codes 37 / 38 / 39 tap a cell the page-3 prefix 39, 38, 37 just tapped, inside TapPad's 0.25 s
     *  repeat guard, so they were swallowed): the preview's four taps go [GAP_MS] apart (TapSpacing.MIN_MS), the batch [BATCH_MS] long */
    const val GAP_MS = TapSpacing.MIN_MS
    const val BATCH_MS = 3 * GAP_MS

    /** [tab]'s page [l] in a live edit session ([tab] null = none), [swing] the app's Swing timer, [ct] / [cc] the add-on's Wings / cast
     *  bar words (null = the add-on cannot show them) -> the code to show */
    fun code(tab: HomeTab?, l: HomeLayout?, swing: Boolean, ct: Int?, cc: Int?): Int {
        if (tab == null || l == null || tab == HomeTab.OUT) return OFF
        val wings = l.fight == HomeLayout.FightBand.TOP && ct != null
        val cast = wings && l.fightCast == HomeLayout.FightCast.TOP && cc != null
        val sub = when { !wings -> 0; !cast -> 1; !swing -> 2; else -> 3 }
        return when (tab) {
            HomeTab.COMBAT -> if (wings) OFF + sub else OFF
            else -> OFF + 4 + sub
        }
    }

    /** what code [c] shows (the add-on's words) */
    data class Look(val party: Boolean, val wings: Boolean, val cast: Boolean, val swing: Boolean)
    fun look(c: Int): Look? = when (c) {
        33 -> Look(false, true, false, false); 34 -> Look(false, true, true, false); 35 -> Look(false, true, true, true)
        36 -> Look(true, false, false, false); 37 -> Look(true, true, false, false); 38 -> Look(true, true, true, false)
        39 -> Look(true, true, true, true)
        else -> null
    }

    sealed class Act {
        /** the add-on shows it already */
        object Matched : Act()
        /** wait: an add-on without it (no `pv`) or no padmap yet, a fight (the add-on drops it by itself), a send just went */
        object Wait : Act()
        /** tap code [code] */
        data class Send(val code: Int) : Act()
    }

    /** [want] the code to show, [pv] the add-on's (0 = none), [inFight] Home shows a fight, [sinceSendMs] since this wish's last send */
    fun decide(want: Int, pv: Int?, inFight: Boolean, sinceSendMs: Long?): Act {
        if (pv == null) return Act.Wait
        val shows = if (pv in OFF + 1..PadMapModel.P3_PV_LAST) pv else OFF
        // (a preview still wanted is renewed before the add-on's lease ends)
        if (shows == want) return if (want != OFF && !inFight && sinceSendMs != null && sinceSendMs >= RENEW_MS) Act.Send(want) else Act.Matched
        if (inFight || (sinceSendMs != null && sinceSendMs < RESEND_MS)) return Act.Wait
        return Act.Send(want)
    }

    /** the taps for [code] */
    fun cells(code: Int): List<Pair<String, Int>> = PadMapModel.previewCells(code)
}
