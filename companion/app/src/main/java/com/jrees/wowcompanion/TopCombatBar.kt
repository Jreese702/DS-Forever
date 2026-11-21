package com.jrees.wowcompanion

/**
 * MOVABLE HOME PHASE 3: THE COMBAT BAR ON THE TOP SCREEN (SPEC.txt section 11, final_07 / final_07b / final_10; the user's rulings
 * 2026-11-20: D1 "In fights: Top screen" is OPT-IN - the band's Grow stays the default and Same size stays; D7 the "Wings").
 *
 * With the layout's In fights = Top screen ([HomeLayout.FightBand.TOP]) the bottom band keeps its REST box in a fight (the solver's
 * big() is GROW only: only the band's pictures switch to the In-combat layer, today's Same size path), a 1.5 s "up" plate shows at the
 * clock's spot when the fight starts, and B in the fight never shrinks anything (nothing grew; no red reminder). On the top screen the
 * add-on shows its Nintendo bar as two halves around our HUD (ActionBar nbar7), by itself, on the router's own fight switch.
 *
 * THE CHANNEL: the add-on's per-character flag (PadMap m179 WowCompanionSpellsDB.ctop; its `ct` in the padmap's dpad state) set with
 * padtab 39, 38, 37 then 11 (off) / 12 (on) (Book b75). The app is the source of truth: OUT OF COMBAT ONLY (a fight waits; the add-on
 * switches by itself in fights, so the app sends nothing then), re-sent every [RESEND_MS] until `ct` says it, and re-asserted at each
 * login (the first padmap of a run is checked like any other) - ToolbarMirror's pattern. Pure: CompanionHost keeps the clock and taps.
 */
object TopCombatBar {
    /** the gap between two sends of the same wish (the add-on answers a tap with a padmap within a second) */
    const val RESEND_MS = 5_000L
    /** the bottom band's "up" cue (SPEC section 10: a 1.5 s plate at the clock spot, 120 in / 200 out) */
    const val CUE_IN_MS = 120L
    const val CUE_OUT_MS = 200L
    const val CUE_MS = 1_500L

    /** the layout asks for the top-screen bar */
    fun wanted(l: HomeLayout): Boolean = l.fight == HomeLayout.FightBand.TOP

    /**
     * the 1515 merge's review (SPEC P5, "the pictures never lie"): the bottom screen honours Top screen only while the add-on SAYS its
     * Wings are on - [ct] the current padmap's flag is 1. With no key (the add-on asks, or the question's "Later" kept it keyless for
     * the login: whoKey() nil, no `ct`), an add-on without the flag, or a flag not confirmed yet, the Wings cannot show, so the band
     * grows as Grow does (the default), no "up" cue, and B shrinks as ever
     */
    fun live(l: HomeLayout, ct: Int?): Boolean = wanted(l) && ct == 1
    /** [l] as the bottom screen lays it now: Top screen kept only when [live], else Grow */
    fun honoured(l: HomeLayout, ct: Int?): HomeLayout = if (wanted(l) && ct != 1) l.copy(fight = HomeLayout.FightBand.GROW) else l

    sealed class Act {
        /** `ct` already says the layout's choice */
        object Matched : Act()
        /** wait: no padmap yet, an older add-on (no `ct`: nothing to send), a fight, or a send just went */
        object Wait : Act()
        /** tap the flag [on] */
        data class Send(val on: Boolean) : Act()
    }

    /** [want] the layout's choice; [ct] the CURRENT padmap's flag (null = an add-on without it, or no padmap yet); [inFight] Home shows a
     *  fight (queued: sent after it); [sinceSendMs] since the last send of this wish (null = none) */
    fun decide(want: Boolean, ct: Int?, inFight: Boolean, sinceSendMs: Long?): Act {
        if (ct == null) return Act.Wait
        if ((ct == 1) == want) return Act.Matched
        if (inFight || (sinceSendMs != null && sinceSendMs < RESEND_MS)) return Act.Wait
        return Act.Send(want)
    }

    /** the taps for [on] */
    fun cells(on: Boolean): List<Pair<String, Int>> = PadMapModel.combatTopCells(on)

    /** the "up" cue starts: the fight signal Home grows on came ([was] false -> [now] true) with In fights = Top screen, the add-on's
     *  Wings on ([live]: [ct] the current padmap's flag) */
    fun cueStarts(was: Boolean, now: Boolean, l: HomeLayout, ct: Int?): Boolean = !was && now && live(l, ct)

    /** the cue's alpha at [ms] since it started (0 after [CUE_MS]) */
    fun cueAlpha(ms: Long): Float = when {
        ms < 0 || ms >= CUE_MS -> 0f
        ms < CUE_IN_MS -> ms / CUE_IN_MS.toFloat()
        ms < CUE_MS - CUE_OUT_MS -> 1f
        else -> (CUE_MS - ms) / CUE_OUT_MS.toFloat()
    }

    /** B on Home with the takeover on: shrinks it unless In fights = Top screen and the fight is on (nothing grew). Out of a fight (an
     *  attack armed) B keeps today's meaning there too: the takeover goes and the faces are given back (the add-on's wings follow) */
    fun bShrinks(l: HomeLayout, fighting: Boolean, ct: Int?): Boolean = !(live(l, ct) && fighting)

    /** the Settings band's status for the In fights tile: Top screen chosen but the add-on has no flag yet */
    fun status(l: HomeLayout, ct: Int?, haveDpad: Boolean): String? =
        if (wanted(l) && haveDpad && ct == null) "Needs the newer Spells add-on" else null
}
