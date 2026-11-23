package com.jrees.wowcompanion

/**
 * MOVABLE HOME PHASE 3: THE COMBAT BAR ON THE TOP SCREEN (SPEC.txt section 11, final_07 / final_07b / final_10; the user's rulings
 * 2026-11-20: D1 "In fights: Top screen" is OPT-IN - the band's Grow stays the default and Same size stays; D7 the "Wings").
 *
 * With the layout's In fights = Top screen ([HomeLayout.FightBand.TOP]) live ([live]) the bottom band SLIDES UP off the bottom screen's
 * top edge in a fight while the chat / map columns glide up into the freed strip (the user, 2026-11-22: "It should be a slick animation
 * looking like it slides up to the top screen"; [TopSlide], [HomeLayoutSolver.away]) and back down after it - the slide is the cue (the
 * old 1.5 s "up" plate is gone) - and B in the fight never shrinks anything (no red reminder). On the top screen the add-on's Nintendo
 * bar slides in upward from the screen's bottom edge as two halves around our HUD (ActionBar nbar7 / nbar11, the bottom band's ring
 * look), by itself, on the router's own fight switch.
 *
 * THE CHANNEL: the add-on's per-character flag (PadMap m179 WowCompanionSpellsDB.ctop; its `ct` in the padmap's dpad state) set with
 * padtab 39, 38, 37 then 22 (off) / 23 (on) (Book b76; PadMapModel.P3_TOP_OFF / P3_TOP_ON). The app is the source of truth: OUT OF COMBAT ONLY (a fight waits; the add-on
 * switches by itself in fights, so the app sends nothing then), re-sent every [RESEND_MS] until `ct` says it, and re-asserted at each
 * login (the first padmap of a run is checked like any other) - ToolbarMirror's pattern. Pure: CompanionHost keeps the clock and taps.
 */
object TopCombatBar {
    /** the gap between two sends of the same wish (the add-on answers a tap with a padmap within a second) */
    const val RESEND_MS = 5_000L

    /** the layout asks for the top-screen bar */
    fun wanted(l: HomeLayout): Boolean = l.fight == HomeLayout.FightBand.TOP

    /**
     * the 1515 merge's review (SPEC P5, "the pictures never lie"): the bottom screen honours Top screen only while the add-on SAYS its
     * Wings are on - [ct] the current padmap's flag is 1. With no key (the add-on asks, or the question's "Later" kept it keyless for
     * the login: whoKey() nil, no `ct`), an add-on without the flag, or a flag not confirmed yet, the Wings cannot show, so the band
     * grows as Grow does (the default), and B shrinks as ever
     */
    fun live(l: HomeLayout, ct: Int?): Boolean = wanted(l) && ct == 1
    /** [l] as the bottom screen lays it now: Top screen kept only when [live], else Grow */
    fun honoured(l: HomeLayout, ct: Int?): HomeLayout = if (wanted(l) && ct != 1) l.copy(fight = HomeLayout.FightBand.GROW) else l
    /**
     * the 1519 audit: edit mode's FIGHT PREVIEW shows "what a fight would" - the first fight after Done. A character that never used Top
     * screen has the add-on's flag off (`ct` 0): the menu offers Top screen (only `ct` null greys it), and CompanionHost.topCheck sends
     * nothing while edit mode is live, so `ct` turns 1 only after Done (within a resend). The preview therefore keeps Top screen whenever
     * the add-on HAS the flag ([ct] not null); with no flag (no key, an older add-on) the Wings cannot show, so Grow as [honoured]
     */
    fun previewed(l: HomeLayout, ct: Int?): HomeLayout = if (wanted(l) && ct == null) l.copy(fight = HomeLayout.FightBand.GROW) else l

    /**
     * PARTY MODE KEEPS THE TOP SCREEN (the user's ruling C, 11-22: "It should stay at the top in party mode but the party bars should fill
     * the area"): with Top screen [live] and Party mode on ([partyOn]: PartyModel.onLive), the FIGHT ([fighting]: the add-on's fight word,
     * never an attack armed before the pull - Party mode has no takeover, so the app would not move then) slides the band up as any Top
     * fight does, the add-on's Wings carry the Party-mode layer on the top screen (ActionBar nbar12: the same fight-only switch), and the
     * party module grows up into the freed strip ([HomeLayoutSolver.away], PartyLayout.Mode.FIGHT_TOP). Nothing is sent in a fight: the
     * add-on switches by itself, as ever
     */
    fun partyTop(l: HomeLayout, ct: Int?, partyOn: Boolean, fighting: Boolean): Boolean = live(l, ct) && partyOn && fighting

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

    /** B on Home with the takeover on: shrinks it unless In fights = Top screen and the fight is on (nothing grew). Out of a fight (an
     *  attack armed) B keeps today's meaning there too: the takeover goes and the faces are given back (the add-on's wings follow) */
    fun bShrinks(l: HomeLayout, fighting: Boolean, ct: Int?): Boolean = !(live(l, ct) && fighting)

    /**
     * THE TAP-TO-CAST BAR STAYS IN A TOP-SCREEN FIGHT (the 11-22 check of the slide's shots: the bar under the chat was gone in the
     * fight - Settings > Tap-to-cast bar > Show in combat Off, its default, slid it down behind the room bar). Tap-to-cast is a fight
     * tool, and with Top screen the band leaves the bottom screen in a fight, so the bar is the bottom screen's one fight control: it
     * STAYS - seen and tappable - with its column, wherever its dock puts it (under / above the chat or the map, across, Split), whatever
     * Show in combat says. WHAT IT SHOWS (the 11-22 review): the layer the player's Show in combat arms, as in any fight - Show on, its
     * In-combat layer (CastStripModel.Bar.show: the add-on built the fight cells); Show off (the default), THE BAR'S OWN SLOTS: the
     * In-combat layer stays unarmed (the add-on builds no wc-f- cells while Show in combat is off, and the room locks that layer:
     * cb-hidden), so CastStripModel.shownSlot / tapCell fall through to each slot's own entry - what is drawn is what is cast. Keeping the
     * bar never arms that layer behind the player's back: Show in combat IS the add-on's castBarShow (D4), the Controller room's In combat
     * check box and its lock, which ControllerView re-asserts from the app's setting. [l] the record as the page lays it
     * (TopCombatBar.honoured: Top screen only while the add-on's Wings are on). PARTY MODE TOO (the 1519 audit): ruling C sends the
     * band up in Party mode's fight as well ([partyTop]), so the bar stays there whatever Show in combat or Bar in Party mode says (Bar in
     * Party mode still decides its Party layer: CastStripModel.shownSlot). Out of the fight nothing hides it anyway (hiddenIn needs the
     * fight). Grow and Same size keep Show in combat as it is.
     */
    fun stripStays(l: HomeLayout): Boolean = wanted(l)
    /** the tap-to-cast bar's fight rule for [l]: STAY when [stripStays], else the player's [setting] */
    fun stripCombat(l: HomeLayout, setting: CastStripModel.Combat): CastStripModel.Combat =
        if (stripStays(l)) CastStripModel.Combat.STAY else setting

    /** the Settings band's status for the In fights tile: Top screen chosen but the add-on has no flag yet */
    fun status(l: HomeLayout, ct: Int?, haveDpad: Boolean): String? =
        if (wanted(l) && haveDpad && ct == null) "Needs the newer Spells add-on" else null
}
