package com.jrees.wowcompanion

/**
 * MOVABLE HOME PHASE 3: THE COMBAT BAR ON THE TOP SCREEN (SPEC.txt section 11, final_07 / final_07b / final_10; the user's rulings
 * 2026-11-20: D1 "In fights: Top screen" is OPT-IN - the band's Grow stays the default and Same size stays; D7 the "Wings").
 *
 * With the layout's In fights = Top screen ([HomeLayout.FightBand.TOP]) live ([live]) the bottom band SLIDES UP off the bottom screen's
 * top edge in a fight while the chat / map columns glide up into the freed strip (the user, 2026-11-22: "It should be a slick animation
 * looking like it slides up to the top screen"; [TopSlide], [HomeLayoutSolver.away]) and back down after it - the slide is the cue (the
 * old 1.5 s "up" plate is gone). THE B RULE (the user, 2026-11-23: "The controller bar top grow is working off of being in combat again
 * instead of our combat ruls where we press B and it grows back downwards"): Top screen follows OUR takeover exactly as Grow does - up on
 * the takeover, B on Home brings the band back down even mid-fight (with Grow's red reminder), the next attack / harmful press re-arms
 * it ([bShrinks], [touchKeepsGrow], HomeView's padmap `fight` sync). On the top screen the add-on's Nintendo bar slides in upward from
 * the screen's bottom edge as two halves around our HUD (ActionBar nbar7 / nbar11 / nbar14, the bottom band's size, shape and ring
 * look), by itself, on the router's own fight switch - which B gives back in a fight too (PadMap m188 fightSw), so both sides go down.
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
    /** [l] as the bottom screen lays it now: Top screen kept only when [live], else Grow; Cast bar in fights = Top screen kept only
     *  while the add-on SAYS so ([cc] 1, PadMap m187: its own bar above the HUD then, ActionBar nbar18), else the bottom screen's strip -
     *  a cast bar never left on neither screen. feat/top-castbar (the user's reversal 2026-11-24): the 1523 rule on the GAME's own swing
     *  timer (`gsw`, "Keep ours on the bottom": the strip kept for the swing alone while the game's bar had the cast) is gone - the swing
     *  goes where the cast bar goes (the add-on draws its swing line over our top bar; PadMap m190 stopped reading the game's setting) */
    fun honoured(l: HomeLayout, ct: Int?, cc: Int? = null): HomeLayout {
        val r = if (wanted(l) && ct != 1) l.copy(fight = HomeLayout.FightBand.GROW) else l
        // the 1528 audit: with the Wings on, the add-on's word both ways - Top screen -> Bottom screen switched MID-FIGHT left the add-on's
        // own bar up until the fight's end (castCheck sends nothing in a fight) while the strip took the line at once: both screens
        if (r.fight == HomeLayout.FightBand.TOP && cc == 1) return if (r.fightCast == HomeLayout.FightCast.TOP) r else r.copy(fightCast = HomeLayout.FightCast.TOP)
        return if (r.fightCast == HomeLayout.FightCast.TOP && cc != 1) r.copy(fightCast = HomeLayout.FightCast.BOTTOM) else r
    }

    /**
     * WHERE THE BAND'S CAST LINE IS (feat/top-castbar; the user, 2026-11-24: "I only want to see it on the top screen when everything else
     * is also on the top screen during or engaging in combat. Not with stuff like dienchanting or opening shit, that can stay on the bottom
     * screen"; then "I kind of want the option for the cast bar and swing timer to appear on the bottom screen if everything else is on
     * top"): [l] the record as the page lays it ([honoured] / [previewed]), [away] the page laid with the band above it (the takeover's
     * Top screen: TopSlide), [sliding] a Top-screen slide running (either way).
     * - [Line.STRIP]: Cast bar in fights = Bottom screen (the default) in the takeover - the fight strip across the bottom screen's top
     *   carries the band's WHOLE line, the cast AND the swing (HomeLayoutSolver.hasFightStrip), away and all through both slides.
     * - [Line.TOP]: Cast bar in fights = Top screen in the takeover (away: from the up-slide's first frame, so a cast that starts the
     *   takeover never shows on the bottom screen after it) - nothing of the line on the bottom screen: the add-on's own bar above the HUD
     *   has the cast and the swing (ActionBar nbar18, on the Wings' same switch); the rooms' bar leaves it out too.
     * - [Line.BAND]: everything else - out of the takeover (disenchanting, opening, gathering, crafting, the Hearthstone: the band's own
     *   line on the bottom screen, whatever the setting), Grow / Same size, and the way down (the band brings the line back with it).
     * The swing never in two places: wherever the cast is, the swing is.
     */
    enum class Line { BAND, STRIP, TOP }
    fun lineAt(l: HomeLayout, away: Boolean, sliding: Boolean): Line = when {
        HomeLayoutSolver.hasFightStrip(l) && (away || sliding) -> Line.STRIP
        l.fight == HomeLayout.FightBand.TOP && l.fightCast == HomeLayout.FightCast.TOP && away -> Line.TOP
        else -> Line.BAND
    }
    /**
     * the 1519 audit: edit mode's FIGHT PREVIEW shows "what a fight would" - the first fight after Done. A character that never used Top
     * screen has the add-on's flag off (`ct` 0): the menu offers Top screen (only `ct` null greys it), and CompanionHost.topCheck sends
     * nothing while edit mode is live, so `ct` turns 1 only after Done (within a resend). The preview therefore keeps Top screen whenever
     * the add-on HAS the flag ([ct] not null); with no flag (no key, an older add-on) the Wings cannot show, so Grow as [honoured]
     */
    fun previewed(l: HomeLayout, ct: Int?, cc: Int? = null): HomeLayout {
        val r = if (wanted(l) && ct == null) l.copy(fight = HomeLayout.FightBand.GROW) else l
        // (the same rule for the cast bar's screen: Top screen previewed whenever the add-on HAS the flag, cc not null)
        return if (r.fightCast == HomeLayout.FightCast.TOP && cc == null) r.copy(fightCast = HomeLayout.FightCast.BOTTOM) else r
    }

    // ---------------------------------------------------------------- the cast bar in a Top-screen fight (the user's ruling 2026-11-23)
    /**
     * CAST BAR IN FIGHTS (the user, 2026-11-23: "When the controller moves to the top, the cast bar should still show across the top of
     * the bottom screen. We can add a setting for if the combat bars move to the top screen to have the cast bar be on the top or the
     * bottom. If its on the top we should really just turn back on the cast bar for the top screen dynamically instead of trying to remake
     * ours and position it just above the health hud. Same thing with the swing timer"): only with In fights = Top screen.
     * BOTTOM (the default): our cast bar and swing timer run across the top of the bottom screen in the fight (HomeLayoutSolver's fight
     * strip), the Wings draw no swing line. TOP (feat/top-castbar, the user's reversal 2026-11-24: "I want to build our own that appears
     * right above the HUD"): in the takeover nothing of the line on the bottom screen ([lineAt]); the add-on draws OUR OWN cast bar right
     * above the HUD block with the swing line(s) over it (ActionBar nbar18; the 1523 path that showed the game's own casting bar - Hub
     * hub76 - is gone, hub78), out of the takeover the band's own line as ever. THE CHANNEL: a second per-character flag beside `ct`
     * (PadMap m187 ctcb, `cc` in the padmap), page-3 codes 24 (Bottom) / 25 (Top) (Book b77), sent as `ct` is: out of combat, re-sent
     * until `cc` says it ([decide] with `cc`)
     */
    fun castWanted(l: HomeLayout): Boolean = wanted(l) && l.fightCast == HomeLayout.FightCast.TOP
    /** the taps for "Cast bar in fights" [top] */
    fun castCells(top: Boolean): List<Pair<String, Int>> = PadMapModel.fightCastCells(top)
    /**
     * THE SWING WITH OUR TOP BAR (feat/top-castbar): the add-on draws the swing over our top cast bar, so the app's Settings > Home "Swing
     * timer" goes to it too - a third per-character flag (PadMap m190 ctsw, `cs` in the padmap; none = on), page-3 codes 26 (off) / 27
     * (on) (Book b78), sent as `cc` is and only while it matters ([castWanted]: Bottom screen paints the swing on this screen, by the same
     * setting). null = nothing to send ([decide]'s Matched)
     */
    fun swingWanted(l: HomeLayout, swingOn: Boolean): Boolean? = if (castWanted(l)) swingOn else null
    /** the taps for the top bar's swing [on] */
    fun swingCells(on: Boolean): List<Pair<String, Int>> = PadMapModel.fightSwingCells(on)

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

    /** B on Home with the takeover on: shrinks it - in and out of a fight, Top screen too (the user's 11-23 ruling, "our combat ruls
     *  where we press B and it grows back downwards": the band slides back down with Grow's red reminder, the add-on's Wings leave on
     *  the router's own give-back). Out of a fight (an attack armed) the faces are given back too, as ever. Kept as a function so the
     *  call sites read the rule (8d6153b9's "unless Top screen and the fight is on" is gone) */
    @Suppress("UNUSED_PARAMETER")
    fun bShrinks(l: HomeLayout, fighting: Boolean, ct: Int?): Boolean = true
    /**
     * A FINGER KEEPS THE GROW (the 11-22 review, kept by the 11-23 B rule): a touch that opens a Home module in Top screen's takeover
     * ([live]) never minimizes the grow - a finger never reaches the add-on's give-back (`wc-given`), so the Wings stay up on the top
     * screen and the band must go back above the page when the module closes (HomeView's re-takeover). B, by contrast, shrinks
     * ([bShrinks]): the add-on's secure B gives the buttons back at the same press
     */
    fun touchKeepsGrow(l: HomeLayout, ct: Int?): Boolean = live(l, ct)

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
