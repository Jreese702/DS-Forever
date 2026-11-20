package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * THE CONTROLLER ROOM (the user 2026-10-05: "the controller page really seems to be important so I feel like it should actually be a home
 * tab instead of a spellbook tab"; Home puts it on the bottom bar in Chat's place).
 *
 * p9 (the user 2026-10-07: "add tabs, one for the Wow Defaults, one for DS and one for In combat"; UI Art's SHEET_controller_tabs):
 * THREE TABS across the top (LB / RB, the game's tab look) in place of the old toggles row, each with its own switches "as drawn":
 *   * WoW Defaults: the bar-binding page ([PadTabPage]): ZL / ZR / ZL + ZR, the buttons, A Choose / Change, X Move;
 *   * DS: what DS Forever adds: the "DS WoW toolbar" switch and the plain D-pad's four places (left Chat, up the Radial, right Map, down
 *     the Home bar); the Radial's Hold / Tap on its page (an APP setting, Home's HomeSettings.radialTap);
 *   * In combat: the "Combat controls" and "Start attack on cast" switches over [PadTabPage]'s combat page.
 * The tab and the controller's cursor are the addon's (PadTabCtl p9, `padtabctl`); a finger's tab is one padtab pick (Book b22).
 * p16 a FOURTH tab, "Spell radial" (the user's picks 2026-10-07: "A: You choose" -- up to 8 per page, 3 pages, filled in the Controller room like buttons): [PadTabPage] draws it (UI Art: the wheel itself, its page
 * plates, the slice on the right page); padtab 10 opens it (Book b29).
 * p17 (the user 2026-10-07: "the tabs at the top of the controller menu should have the arrows side to side when highlighted, and be changed by the Dpad. when opening the room the cursor should start on the top left tab"): the room opens with the cursor on the tab row, on WoW Defaults (PadTabCtl p17); the
 * highlighted tab wears the game's gold arrows on both sides (UiKit.moveArrows, no words) in place of the L / R glyphs; LB / RB still work.
 *
 * p34 (the user 2026-11-10: "consolidate the Controller menu. move the DS wow toolbar check box to the Wow defaults tab, when its selected
 * replace those buttons with the DS WoW toolbar buttons, when uncheck it goes back to the normal D-pad skills"): FOUR tabs; the DS tab is
 * gone (PadTabCtl p34 keeps it in its TABS, never shown, so the padtab numbers stay); WoW Defaults carries the "DS WoW toolbar" switch and,
 * while it is on, [PadTabPage] draws the toolbar's Chat / Radial / Map / Home bar on the bare D-pad. The Item wheel's Tap / Hold and
 * A / Release rows live in Settings > Controller (they were mirrored here).
 * THE ADDON'S SIDE (Book b17 / b22): [enterRoom] taps the `spells` row's "croom" column: the addon takes the controller by touch (no game
 * window: the game has none for this page) and the room's half (PadTabCtl) gets every press; B at the top leaves to Home. A on the DS tab's
 * Up counts in `spellsctl.tn` (the app flips the radial's way once per count).
 * A finger: a switch is ONE tap (its own on / off column, idempotent; refused in a fight with one red pulse, as Settings' row).
 * m41 S1 THE CLEAN-UP (the user's pick 2026-10-09: "a 'Clean up this character' button in the Controller room's DS tab lists exactly what
 * goes; one yes per character"): under the D-pad while PadMap's plan (`padclean`) has something; a box names every button and macro first.
 * m42 THE SHARED ONES (the user's answer 2026-10-09 "Last, separately"): the account-wide DS macros get their own "Delete" button beside
 * it, once no checked character still holds one; its own box names them and any character not checked yet.
 * p53 THE CAST BAR TAB (the user, 2026-11-13: "in the controller menu we need a Tab for the cast bar if its enabled, where people can
 * assign anything from the 5 tabs"): a tab after Party mode, shown while Settings' Tap-to-cast bar is not Off and the add-on's bar
 * takes every kind ([CastStripModel.ctlTab]); [PadTabPage] draws it. The add-on is told the setting (padtab 39 then 30 / 31) on every
 * room open and every change, so its LB / RB step onto the tab only while it shows; a finger on the tab is padtab 39 then 29.
 * The one-time try prompt's Yes ([openOnCastBar], the user: "if they press yes on the prompt to try the tap to cast bar, we should
 * open them to the spell bind room that you are making so they can add spells to it"): the next open lands on that tab, slot 1.
 * p55 CONTROLLER | TAP TO CAST (the user, 2026-11-19: "move the tap to cast bar in the controller room to make more sense. In the wow
 * default room ... two tabs under it, one for controller and one for tap to cast bar. ... In the combat tab we have a controller tab and
 * a tap to cast tab (in this one it should be under the check boxes though)"; the approved proposal D1-D10; PadTabCtl p55): FOUR top
 * tabs again (the Cast bar tab is gone, D1); WoW Defaults and In combat each have a SUB-TAB row, Controller | Tap to cast ([SUBS]):
 * WoW Defaults' right under the top tabs, In combat's under its check boxes; the page's left side stands [SUB_DY] lower under it. The
 * sub-tab under the controller's cursor wears the gold arrows (landing opens, the add-on's rule); a finger's sub-tab is one pick
 * (Controller: its padtab tab, Tap to cast: padtab 39 then 29 / 1). Each top tab remembers its sub-tab while the room is open ([subWant]
 * and the add-on's `top`); a fresh open lands on WoW Defaults > Controller. In combat's THIRD check box, "Show tap-to-cast", IS
 * HomeSettings.castBarCombat (D4: ticked = STAY), the same setting as Settings > Bottom screen's "Show in combat" row (D5b): a finger
 * flips it here, the controller's A is counted by the add-on (`cbsn`) and flipped here; every change is told to the add-on (padtab 39
 * then 2 / 3, [cbSetting]). Show off: In combat > Tap to cast is dimmed with a lock. The bar Off in Settings: both Tap to cast sub-tabs
 * dimmed, their pages say "Bar is off" with "Turn on" (a finger, or the add-on's `cbon` count for the controller's A: D2). The try
 * prompt's Yes opens WoW Defaults > Tap to cast, slot 1. B one level back (the add-on's), so B leads Home only from the top tabs.
 *
 * p56 SITUATIONS FIRST (the user, 2026-11-20, Option C, `ctldesign/optionC_*`; PadTabCtl p56, ActionBar cb15): ONE row of big
 * situation tabs, each with its picture ([SituationPage.Icon]): Out of combat (the old "WoW Defaults") / In combat / Party mode /
 * Spell radial, LB / RB on the outsides. NO SUB-TABS: every situation's page holds both devices - its settings as a compact row of
 * chips (Out of combat: DS WoW toolbar; In combat: Combat controls, Start attack on cast, Show tap-to-cast = castBarCombat; Party
 * mode: Bar in Party mode = HomeSettings.castBarPartyShow, on by default, told to the add-on as padtab 39, 38 then 2 / 3), then the
 * controller at 0.8 and the situation's tap-to-cast bar under it ([PadTabPage] draws both; Party mode's controller is
 * [PartyTabPage]'s, its bar the page's "pbar"). One cursor (the add-on's) walks the chips, the controller and the bar; the try
 * prompt's Yes still opens Out of combat's bar, slot 1.
 */
class ControllerView(
    context: Context,
    /** taps on spells' own pad rows (no-action picks, then at most ONE action), in order; true = the taps went out */
    private val onPadCells: (List<Pair<String, Int>>) -> Boolean,
    /** a spell's Bind column (the page: the spell goes onto the chosen button) */
    onBind: (SpellsModel.Entry) -> Unit = {},
    /** another view reads the controller now (one reader at a time): the page does not read the triggers then */
    otherReader: () -> Boolean = { false },
) : View(context), BLeadsHome {

    private var book: SpellsModel.Book? = null
    private var ctl: SpellsModel.Ctl? = null
    private var map: PadMapModel.Map? = null
    private var tabCtl: PadMapModel.Ctl? = null
    private var busyUntil = 0L
    private var flash: String? = null
    private var flashUntil = 0L
    private var downSw = -1
    private var downTab = -1
    /** m41 S1: the clean-up's plan for this character (PadMap's `padclean`); its button shows only while there is something */
    private var clean: PadMapModel.Clean? = null
    /** the box a finger opened: "cleanup" / "cleanshared" (the controller's own is [tabCtl]'s box of that kind) */
    private var askKind: String? = null
    /** a finger down on: 1 the Clean up button, 4 the shared macros' Delete, 2 the box's yes, 3 its Cancel */
    private var downClean = 0
    private val pPlate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.raised }

    init {
        CastSettings.init(context)
        HomeSettings.init(context)
        RadialMeta.init(context)
        // p55: on the main looper, not the view's queue: a room view not attached (Home or Settings on show) still tells the add-on at
        // once (Show in combat arms the bar's in-combat layer, the bar Off its pages)
        HomeSettings.onChange { MAIN.post { cbSetting(false); invalidate() } }   // the radial's way (Hold / Tap); p53: the bar's setting
        CastStripModel.Live.onChange { MAIN.post { cbSetting(false); invalidate() } }    // p53: the Cast bar tab's slots
    }

    private val page = PadTabPage(this, { cells -> onPadCells(cells) }, { e -> onBind(e) }, otherReader, cursorOnTabs = { false },
        cellsOf = { e -> book?.let { SpellsModel.bindCells(it, e) } },       // b-druid-forms-bind: Book b38's two taps
        // p34: the toolbar's four show on the bare D-pad as the switch SHOWS (a finger's flip at the tap)
        toolbarShown = { map?.dpad?.let { swShown("dpad", it.want == true) } == true },
        onTurnOn = { turnOn() })                                            // p55 (D2)
    /** PARTY MODE's tab (PARTY_BUILD.md 4.4): the 5th tab, its own page */
    private val partyPage = PartyTabPage(this, { cells -> onPadCells(cells) }, { id ->
        book?.let { b -> SpellsModel.tabSpells(b).firstOrNull { it.spellId == id }?.let { SpellsModel.bindCells(b, it) } } })

    /** Party mode's documents changed (partycfg, party): the tab redraws */
    fun partyChanged() { if (tab() == "party") invalidate() }
    /** partyack: a refusal said on the page */
    fun onPartyAck(a: PartyModel.Ack) { partyPage.onAck(a); invalidate() }

    /** true while the addon holds the controller for THIS room */
    val held: Boolean get() = ctl?.on == true && ctl?.croom == true

    /** RC3 (Spells' condition, PadTabCtl.press's leaveB): not while its chooser, a box or a carried button is up (B cancels those) */
    override fun bLeadsHome(): Boolean = held && ctl?.mode == "book" &&
        tabCtl?.let { it.on && (it.chooser != null || it.box != null || it.carry != null || it.focus != "tabs") } != true   // p55: B one level back

    // ---------------------------------------------------------------- Home's calls

    /** Into the room (every route: the bar, touch): the addon takes the controller and opens the page. True when the tap went out. An
     *  older addon without the "croom" column: the Controller tab's own touch-open (the page lit, Down enters it). */
    fun enterRoom(): Boolean {
        val col = book?.win?.get("croom")
        // p53: the bar's setting first (a no-action pick); p55: Show in combat too (the add-on's lock of In combat > Tap to cast)
        val cbOn = barOn()
        val show = showOn()
        val pshow = HomeSettings.castBarPartyShow
        val pre = CastStripModel.ctlSettingCells(cbOn) + CastStripModel.showCells(show) + CastStripModel.partyShowCells(pshow)
        // the try prompt's Yes: WoW Defaults > Tap to cast, slot 1, after the open (the room's own open route: B goes back as from any tab)
        val toCb = col != null && castBarWanted && cbTabOn()
        val after = if (toCb) CastStripModel.ctlTabCells() + (PadMapModel.ROW to PadMapModel.colPos(1)) else emptyList()
        val cells = if (col != null) pre + ("spells" to col) + after
                    else (if (page.needsLayout()) listOf(page.layoutCell()) else emptyList()) + (PadMapModel.ROW to PadMapModel.COL_OPENED)
        val sent = onPadCells(cells)
        if (sent && col != null) { cbSent = cbOn; showSent = show; pshowSent = pshow }
        if (sent && col != null) castBarWanted = false                    // once: this open (on the tab when it shows)
        if (sent && col != null) { lastCbsn = null; lastCbon = null; lastCbpn = null }       // p56: the counts start again
        if (sent && col != null) page.showTabLocal(if (toCb) "castbar" else PadMapModel.TABS[0])      // p17: it opens on WoW Defaults, shown at the tap
        Log.i(TAG, "controller room enter: ${cells.last()} ${if (sent) "tapped" else "not sent"}")
        page.setActive(isShown)
        return sent
    }

    /** Out of the room (a finger elsewhere, Home's bar): the hold let go, NO game action (the spells row's close). */
    fun leaveRoom(): Boolean {
        val col = book?.win?.get("close") ?: SPELLS_CLOSE_CELL
        val sent = onPadCells(listOf("spells" to col))
        askKind = null
        Log.i(TAG, "controller room leave: spells $col ${if (sent) "tapped" else "not sent"}")
        page.setActive(false)
        return sent
    }

    /** p53: the try prompt's Yes: the next room open lands on the Cast bar tab, slot 1 (once) */
    private var castBarWanted = false
    fun openOnCastBar() { castBarWanted = true }
    /** the Yes's open is still owed (tests) */
    val castBarOwed: Boolean get() = castBarWanted
    /** p53: the Tap to cast sub-tabs are there (the add-on's bar takes every kind); p55 (D2): the bar Off no longer hides them */
    private fun cbTabOn(): Boolean = CastStripModel.ctlTab(CastStripModel.Live.bar)
    /** the bar is on in Settings (not Off) */
    private fun barOn(): Boolean = !CastStripModel.barOff(HomeSettings.castBarSide)
    /** p55 (D4): Show in combat = castBarCombat STAY */
    private fun showOn(): Boolean = CastStripModel.showOf(HomeSettings.castBarCombat)
    /** what the add-on was last told (null = nothing yet): the bar on, Show in combat */
    private var cbSent: Boolean? = null
    private var showSent: Boolean? = null
    private var pshowSent: Boolean? = null
    /** p53: the setting to the add-on when it changed since it was last told ([force]: always); p55: Show in combat too (39 then 2 / 3) */
    private fun cbSetting(force: Boolean) {
        val on = barOn()
        if (force || cbSent != on) { if (onPadCells(CastStripModel.ctlSettingCells(on))) cbSent = on }
        val show = showOn()
        if (force || showSent != show) { if (onPadCells(CastStripModel.showCells(show))) showSent = show }
        val ps = HomeSettings.castBarPartyShow                              // p56: Bar in Party mode (39, 38 then 2 / 3)
        if (force || pshowSent != ps) { if (onPadCells(CastStripModel.partyShowCells(ps))) pshowSent = ps }
    }
    /** p55 (D2): "Turn on" (a finger on the dimmed page, or the add-on's `cbon` for the controller's A): the bar on, both columns */
    private fun turnOn() {
        if (CastStripModel.barOff(HomeSettings.castBarSide)) HomeSettings.castBarSide = CastStripModel.turnOnSide
    }
    /** p55: the add-on's counts last seen (Show tap-to-cast presses, Turn on presses); null = none yet this open */
    private var lastCbsn: Int? = null
    private var lastCbon: Int? = null
    private var lastCbpn: Int? = null

    /** HOME'S ONE RULE FOR EVERY ROOM: let go of the shared capture exactly as leaving does, whatever this view's flags say. */
    fun freeAgain(): Boolean = leaveRoom()

    // ---------------------------------------------------------------- data in (the same feeds as the Spellbook room)

    fun update(b: SpellsModel.Book) { book = b; invalidate() }

    private var lastTn: Int? = null
    private var lastPn: Int? = null

    fun onCtl(c: SpellsModel.Ctl) {
        // A on the DS tab's Radial (Book b20 / b22 "rmode") -- an APP setting, so the addon only counts the presses (tn); each new count flips
        // it once (the count starts at 0 on entering the room, so an old count after an app restart flips nothing)
        val tn = c.tn
        if (c.croom && tn != null) {
            val was = lastTn
            if (was != null && tn > was) repeat(tn - was) { HomeSettings.radialTap = !HomeSettings.radialTap }
            lastTn = tn
        }
        // b33: X there flips the wheels' PICK half (Home 2026-10-09: open Tap / Hold + pick A / Release, one pair for every wheel)
        val pn = c.pn
        if (c.croom && pn != null) {
            val was = lastPn
            if (was != null && pn > was) repeat(pn - was) { HomeSettings.radialPickA = !HomeSettings.radialPickA }
            lastPn = pn
        }
        ctl = c
        if (c.note == "croom-nopad" || c.note == "take-refused-busy") {
            busyUntil = SystemClock.uptimeMillis() + BUSY_MS
            postDelayed({ invalidate() }, BUSY_MS + 50)
        } else if (c.on) busyUntil = 0L
        invalidate()
    }

    fun onPadMap(m: PadMapModel.Map) { map = m; page.onMap(m); invalidate() }
    fun onPadTabCtl(c: PadMapModel.Ctl) {
        // p55: the controller's A on "Show tap-to-cast" (counted: the app's setting flips once per count) and on "Turn on"
        c.cbsn?.let { n -> val was = lastCbsn; if (was != null && n > was) repeat(n - was) { HomeSettings.castBarCombat = CastStripModel.combatFor(!showOn()) }; lastCbsn = n }
        c.cbon?.let { n -> val was = lastCbon; if (was != null && n > was) turnOn(); lastCbon = n }
        // p56: A on "Bar in Party mode" (counted: the app's setting flips once per count)
        c.cbpn?.let { n -> val was = lastCbpn; if (was != null && n > was) repeat(n - was) { HomeSettings.castBarPartyShow = !HomeSettings.castBarPartyShow }; lastCbpn = n }
        tabCtl = c; page.onCtl(c); partyPage.onCtl(c); invalidate()
    }
    /** m41 S1: the clean-up's plan (at login, on the box's request, after a run) */
    fun onClean(c: PadMapModel.Clean) {
        clean = c
        if (askKind == "cleanup" && c.n == 0 || askKind == "cleanshared" && !sharedOk()) askKind = null
        invalidate()
    }

    fun onPadAck(a: PadMapModel.Ack) {
        page.onAck(a)
        if (a.op == "cleanup" || a.op == "cleanshared") {               // m41 S1 / m42: the run's answer, said plainly
            askKind = null
            say(when {
                a.ok -> if (a.op == "cleanup") "Cleaned up" else "The shared DS macros are deleted"
                a.why == "combat" -> "Not in a fight: try again after it"
                a.why == "in use" -> "A character still has one on a button"
                else -> "The game did not take it"
            })
        }
        // the switches' own answers (PadMap's ops): a refusal said plainly, as the page says its own
        if (!a.ok && a.op in TOGGLE_OPS) {
            swWant.clear()                                              // the flip shown at the tap goes back at once
            say(when (a.why) { "combat" -> "Not in a fight: try again after it"; "paused" -> PAUSED_SAY; else -> "The game did not take it" })
        }
    }

    // ---------------------------------------------------------------- the tabs and their switches

    private fun tab(): String = page.currentView()
    /** p55: the top tab the page belongs to (the Tap to cast pages are sub-tabs) */
    private fun topTab(): String = PadMapModel.topOf(tab())
    /** party_design.md B: the Party mode tab shows only while Party mode is allowed (QOL > Party, on by default; PadTabCtl p37 steps over it too) */
    private val TAB_VIEWS: List<String> get() = ALL_TAB_VIEWS.filter { v ->
        when (v) { "party" -> GameUi.partyModeOn(); else -> true } }
    /** p55: the taps that open [v] by touch (one pick; the add-on's echo follows); p56: a situation's bar too */
    private fun viewCells(v: String): List<Pair<String, Int>> = when (v) {
        "castbar" -> CastStripModel.ctlTabCells()                                // p53: padtab 39 then 29
        "cbfight" -> CastStripModel.ctlFightCells()                              // p55: padtab 39 then 1
        "pbar" -> CastStripModel.ctlPartyCells()                                 // p56: padtab 39, 38 then 1
        "party" -> PartyTaps.tab(PartyModel.live.cfg) ?: listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(PadMapModel.tabOf(v)))
        else -> listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(PadMapModel.tabOf(v)))
    }
    /** the page (tests) */
    fun page(): PadTabPage = page
    /** the tabs on show (tests: p53's Cast bar tab only while the bar is not Off) */
    fun tabViews(): List<String> = TAB_VIEWS
    private val TAB_NAMES: List<String> get() = TAB_VIEWS.map { ALL_TAB_NAMES[ALL_TAB_VIEWS.indexOf(it)] }
    /** the four tabs (p16), the gaps ONLY round the chosen one, where its LB / RB glyphs stand; they move over when the tab changes (the user
     *  after b118: "the spacing should only be between the currently selected tab. swaping between tabs should animate this spacing";
     *  UI Art art-kit 494b1830). Party mode (the 5th tab): the row from the screen edges (the row keeps its L / R room itself) */
    private val gaps = UiKit.TabGaps(vertical = false, gap = 50f, rest = 14f)      // p56: bigger tabs, the gold arrows still fit the gaps
    private fun tabRect(i: Int, w: Float): RectF =
        gaps.row(TAB_NAMES.size, 0f, w, TAB_TOP, TAB_H, 268f, shownTab(tab()), this)[i]     // p56: four big situations, each with its picture
    /** p34: a tab's place in the row the room SHOWS (the DS tab is gone: an old "ds" shows as WoW Defaults) */
    private fun shownTab(view: String): Int = TAB_VIEWS.indexOf(PadMapModel.topOf(view)).coerceAtLeast(0)      // p55: a sub-view's top tab
    /** p34: the add-on's tab number (PadTabCtl's TABS, 1-based, the DS tab still counted) as a place in the shown row */
    private fun shownTcur(tcur: Int): Int = shownTab(PadMapModel.TABS.getOrElse(tcur - 1) { "normal" })

    /** issue #136 (2026-11-11): the DS WoW toolbar switch flipped here (the new choice, the tap sent): the host keeps it as the player's */
    var onDpadPick: ((Boolean, Boolean) -> Unit)? = null

    /** a switch: label, state as SHOWN (null = this addon cannot switch it yet), the column that sets the OTHER state, its key */
    private class Sw(val label: String, val on: Boolean?, val col: Int?, val key: String,
                     /** greyed out on purpose (not an older add-on): what a tap says */
                     val held: String? = null,
                     /** p56: the chip's picture */
                     val icon: SituationPage.Icon? = null)

    /** LOCAL FIRST (the user on b116, 2026-10-07: "the button push is delayed a moment after I push it, its working but it feels slow and laggy"): a finger's flip shows AT THE TAP;
     *  the addon's map takes over when it agrees, or after [ECHO_MS] (a refusal) */
    private val swWant = HashMap<String, Pair<Boolean, Long>>()
    private fun swShown(key: String, doc: Boolean): Boolean {
        val want = swWant[key] ?: return doc
        if (want.first == doc || SystemClock.uptimeMillis() >= want.second) { swWant.remove(key); return doc }
        return want.first
    }

    private fun switches(): List<Sw> {
        val m = map
        return when (topTab()) {
            // p34 (the user 2026-11-10: "move the DS wow toolbar check box to the Wow defaults tab"): WoW Defaults' own switch
            "normal" -> {
                val d = m?.dpad
                val dOn = d?.let { swShown("dpad", it.want == true) }
                listOf(Sw("DS WoW toolbar", dOn, d?.let { if (dOn == true) it.colOff else it.colOn }, "dpad"))
            }
            // p56: Party mode's own chip, Bar in Party mode (the app's setting: no add-on column)
            "party" -> if (cbTabOn()) listOf(Sw("Bar in Party mode", HomeSettings.castBarPartyShow, SHOW_COL, "cbpshow", icon = SituationPage.Icon.PARTY)) else emptyList()
            "combat" -> {
                val cc = m?.faceCc?.let { swShown("cc", it) }
                val sa = m?.attack
                val saOn = sa?.let { swShown("sa", it.on) }
                listOf(Sw("Combat controls", cc, cc?.let { if (it) m.faceCcColOff else m.faceCcColOn }, "cc", icon = SituationPage.Icon.FIGHT),
                       // b-start-attack-row (the user 2026-10-13): PAUSED in the add-on (m40) = greyed out, unticked, "coming back later"
                       if (sa?.paused == true) Sw("Start attack on cast", false, null, "sa", held = PAUSED_SAY)
                       else Sw("Start attack on cast", saOn, sa?.let { if (saOn == true) it.colOff else it.colOn }, "sa")) +
                       // p55 (D4 / D5c): Show tap-to-cast IS castBarCombat (the app's own setting: no add-on column, [SHOW_COL])
                       (if (cbTabOn()) listOf(Sw("Show tap-to-cast", showOn(), SHOW_COL, "cbshow", icon = SituationPage.Icon.TAP)) else emptyList())
            }
            else -> emptyList()
        }
    }

    /** p56 THE CHIPS (Option C): the situation's settings in ONE compact row on the left page's top, each a dark plate with its
     *  picture, its word and the game's check box; each as wide as its word (one line at 22 px), shared out when the row is full (the
     *  word then takes two lines, never cut off) */
    private fun swRect(i: Int, n: Int): RectF = chipRects(switches())[i]
    private fun chipFixed(s: Sw) = (if (s.icon != null) 52f else 16f) + CHIP_BOX + 18f
    private fun chipRects(sw: List<Sw>): List<RectF> {
        if (sw.isEmpty()) return emptyList()
        val p = UiKit.bodyPaint(22f)
        val nat = sw.map { p.measureText(Tr.t(it.label)) }
        val room = SituationPage.CHIP_X1 - SituationPage.CHIP_X0 - (sw.size - 1) * SituationPage.CHIP_GAP - sw.sumOf { chipFixed(it).toDouble() }.toFloat()
        val k = if (nat.sum() <= room) 1f else room / nat.sum()
        var x = SituationPage.CHIP_X0
        return sw.mapIndexed { i, s ->
            val wd = chipFixed(s) + nat[i] * k
            RectF(x, SituationPage.CHIP_TOP, x + wd, SituationPage.CHIP_TOP + SituationPage.CHIP_H).also { x += wd + SituationPage.CHIP_GAP }
        }
    }

    private fun say(s: String) { flash = s; flashUntil = SystemClock.uptimeMillis() + 2500L; postDelayed({ invalidate() }, 2550L); invalidate() }

    // ---------------------------------------------------------------- the old DS tab's clean-up (m45 / p24: never shown; p34: no DS tab)

    /** m41 S1: the Clean up button, the left page's bottom row under Map (UI Art: the shared one's twin, mirrored round the D-pad) */
    private fun cleanRect(h: Float) = RectF(450f, h - 128f, 700f, h - 16f)
    private fun cleanBoxRect(w: Float, h: Float) = RectF(70f, 172f, w - 70f, h - 12f)
    private fun cleanYesRect(w: Float, r: RectF) = RectF(w / 2f - 14f - 380f, r.bottom - 161f, w / 2f - 14f, r.bottom - 50f)
    private fun cleanNoRect(w: Float, r: RectF) = RectF(w / 2f + 14f, r.bottom - 161f, w / 2f + 14f + 380f, r.bottom - 50f)
    /** m42: the shared macros' Delete, the left page's bottom row under Chat (the same size as Clean up) */
    private fun sharedRect(h: Float) = RectF(40f, h - 128f, 290f, h - 16f)
    // m45 / p24 (the user after test14: "I dont understand why we have a cleanup button"; pick "Remove silently, start blank"): NO
    // clean-up UI anywhere - no Clean up button, no shared Delete, no box, no page text. PadMap removes our own old macros by itself.
    private fun cleanN(): Int = 0
    private fun sharedOk(): Boolean = false
    private fun ctlBoxKind(): String? = tabCtl?.takeIf { it.on }?.box?.kind?.takeIf { it == "cleanup" || it == "cleanshared" }
    /** the box shown now: the controller's, else a finger's while it still has something to do */
    private fun boxKind(): String? = if (tab() != "ds") null
        else ctlBoxKind() ?: askKind?.takeIf { it == "cleanup" && cleanN() > 0 || it == "cleanshared" && sharedOk() }
    private fun cleanBoxShown(): Boolean = boxKind() != null
    private fun glyph(p: Int) = SpellsModel.combo(SpellsModel.PadSlot(0, 1, 1, p), padStyle()).button
    private fun who(): String = clean?.who?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: "this character"

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // feat/beautify (themes 8.4): the room stands on RoomGround.controller (the rock), not a flat page
        RoomGround.controller(c, android.graphics.RectF(0f, 0f, w, h))
        UiKit.header(c, w, "Controller")
        val now = SystemClock.uptimeMillis()
        val note = when {
            now < busyUntil -> "The controller is with another room. Touch still works."
            now < flashUntil -> flash
            else -> null
        }
        note?.let {
            val pb = UiKit.bodyPaint(24f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
            val room = w - 24f - 300f                                 // right of the "Controller" title
            while (pb.textSize > 17f && pb.measureText(it) > room) pb.textSize -= 1f
            c.drawText(Tr.t(it), w - 24f, 31f - (pb.ascent() + pb.descent()) / 2f, pb)
        }
        // THE TABS (p9), the game's tab look, LB / RB in the chosen tab's gaps
        val cur = shownTab(tab())
        val k = tabCtl?.takeIf { it.on }
        // p56: four big situation tabs, each with its picture beside its word (smaller to fit, never cut off; the picture stays)
        for (i in TAB_NAMES.indices) {
            val r = tabRect(i, w)
            UiKit.tab(c, r, "", i == cur, cursor = k != null && k.focus == "tabs" && shownTcur(k.tcur) == i)
            val (size, two) = tabSize(w)
            val p = UiKit.bodyPaint(size, if (i == cur) android.graphics.Color.WHITE else Theme.gold).apply { textAlign = Paint.Align.LEFT }
            val lines = tabLines(Tr.t(TAB_NAMES[i]), p, tabRoom(r), two)
            val tw = lines.maxOf { p.measureText(it) }
            val x0 = r.centerX() - (TAB_ICON + 10f + tw) / 2f
            val cy = r.centerY() + 6f
            Icons2.draw(c, SituationPage.iconOf(TAB_VIEWS[i]), x0 + TAB_ICON / 2f, cy, TAB_ICON, lit = i == cur || k?.focus == "tabs" && shownTcur(k.tcur) == i)
            val lh = size * 1.02f
            for ((j, ln) in lines.withIndex())
                c.drawText(ln, x0 + TAB_ICON + 10f, cy + (j - (lines.size - 1) / 2f) * lh - (p.ascent() + p.descent()) / 2f, p)
        }
        // L and R on the row's outsides, always (the user, 15 Oct), the chooser open too (PadTabCtl p30: LB / RB stay the room's tabs)
        gaps.badges(c)
        // p17: the cursor on the tab row: the game's gold arrows on both sides of its tab (no wrap: none past the ends), in the gaps
        if (k != null && k.focus == "tabs" && !page.chooserOpen() && !partyPage.chooserOpen(k))
            shownTcur(k.tcur).let { t -> UiKit.moveArrows(c, tabRect(t, w), left = t > 0, right = t < TAB_NAMES.size - 1, words = false) }

        UiKit.bookPage(c, RectF(8f, 158f, SPLIT, h - 4f))
        UiKit.bookPage(c, RectF(SPLIT, 158f, w - 8f, h - 4f), right = true)
        UiKit.bookFold(c, SPLIT, 166f, h - 12f)
        if (topTab() == "party") {                                          // p56: Party mode's controller (its page), then its bar (this page's)
            SituationPage.section(c, "Controller", SituationPage.Icon.PAD, SituationPage.CTL_HEAD_Y, lit = tab() == "party")
            partyPage.draw(c, w, h, tabCtl, right = tab() == "party")
        }
        page.draw(c, w, h, book)
        drawSwitches(c, k)
        if (cleanBoxShown()) drawCleanBox(c, w, h)
    }

    /** p56: ONE size for every situation's word, all alike: one line at 30 down to 24; a language whose words are longer (es / fr)
     *  takes two lines at up to 26 rather than going small (big and clear, never cut off) */
    private fun tabRoom(r: RectF) = r.width() - TAB_ICON - 10f - 26f
    private fun tabSize(w: Float): Pair<Float, Boolean> {
        val p = UiKit.bodyPaint(30f)
        fun fits(size: Float, two: Boolean) = TAB_NAMES.indices.all { i ->
            p.textSize = size
            tabLines(Tr.t(TAB_NAMES[i]), p, tabRoom(tabRect(i, w)), two).all { p.measureText(it) <= tabRoom(tabRect(i, w)) } }
        var size = 30f
        while (size > 24f && !fits(size, false)) size -= 1f
        if (fits(size, false)) return size to false
        size = 26f
        while (size > 16f && !fits(size, true)) size -= 1f
        return size to true
    }
    /** the word on one line, or (two) split at the space that best halves it when one line does not fit */
    private fun tabLines(word: String, p: Paint, room: Float, two: Boolean): List<String> {
        if (!two || p.measureText(word) <= room || !word.contains(' ')) return listOf(word)
        val cuts = word.indices.filter { word[it] == ' ' }
        val at = cuts.minByOrNull { kotlin.math.max(p.measureText(word.substring(0, it)), p.measureText(word.substring(it + 1))) } ?: return listOf(word)
        return listOf(word.substring(0, at), word.substring(at + 1))
    }

    /** p56 THE CHIPS: a dark plate (an edge, a soft shadow), the chip's picture, its word, the game's check box; the cursor's gold box */
    private fun drawSwitches(c: Canvas, k: PadMapModel.Ctl?) {
        val sw = switches()
        val rects = chipRects(sw)
        for ((i, t) in sw.withIndex()) {
            val r = rects[i]
            c.drawRoundRect(RectF(r.left, r.top + 3f, r.right, r.bottom + 3f), 10f, 10f, pChipShadow)
            c.drawRoundRect(r, 10f, 10f, pChip)
            c.drawRoundRect(r, 10f, 10f, pChipEdge)
            val enabled = t.col != null
            var tx = r.left + 14f
            t.icon?.let { Icons2.draw(c, it, r.left + 10f + 17f, r.centerY(), 34f, lit = enabled); tx = r.left + 52f }
            val boxCx = r.right - 8f - CHIP_BOX / 2f
            UiKit.check(c, boxCx, r.centerY(), CHIP_BOX, t.on == true, enabled = enabled)
            val p = UiKit.bodyPaint(22f, if (enabled) Theme.text else Theme.dim)
            drawSwitchLabel(c, Tr.t(t.label), RectF(tx, r.top + 3f, boxCx - CHIP_BOX / 2f - 6f, r.bottom - 3f), p)
        }
        if (k != null && k.focus == "switches") sw.indices.firstOrNull { it == k.sw - 1 }?.let { UiKit.focus(c, rects[it], onPaper = true) }
    }

    /** p55 (the coordinator's ask: never cut off): one line at 26 px down to 20, else two lines (a word that is too long for the
     *  line on its own, or a third line, takes the font down further, to 15 px), centred in [r] */
    private fun drawSwitchLabel(c: Canvas, word: String, r: RectF, p: Paint) {
        val tw = r.width().coerceAtLeast(10f)
        var size = 22f
        while (size > 19f && p.apply { textSize = size }.measureText(word) > tw) size -= 1f
        p.textSize = size
        if (p.measureText(word) <= tw) {
            c.drawText(word, r.left, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
            return
        }
        var lay: StaticLayout
        size = 20f
        while (true) {
            val tp = TextPaint(p).apply { textSize = size }
            lay = StaticLayout.Builder.obtain(word, 0, word.length, tp, tw.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0.92f).setIncludePad(false).build()
            val widest = (0 until lay.lineCount).maxOfOrNull { lay.getLineWidth(it) } ?: 0f
            if ((lay.lineCount <= 2 && widest <= tw + 0.5f && lay.height <= r.height()) || size <= 15f) break
            size -= 1f
        }
        c.save(); c.translate(r.left, r.centerY() - lay.height / 2f); lay.draw(c); c.restore()
    }

    private fun padStyle(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }

    // ---------------------------------------------------------------- touch

    private var padDown = false
    /** p56: the finger is on Party mode's own controller / right page (its page takes it) */
    private var partyDown = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        // m41 S1: the clean-up box over everything: its two buttons only
        if (cleanBoxShown()) {
            val r = cleanBoxRect(w, h)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downClean = if (cleanYesRect(w, r).contains(ev.x, ev.y)) 2 else if (cleanNoRect(w, r).contains(ev.x, ev.y)) 3 else 0
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val was = downClean; downClean = 0
                    if (was == 2 && cleanYesRect(w, r).contains(ev.x, ev.y)) cleanRun(boxKind())
                    else if (was == 3 && cleanNoRect(w, r).contains(ev.x, ev.y)) cleanCancel()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { downClean = 0; invalidate() }
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downClean = when {
                tab() != "ds" -> 0
                cleanN() > 0 && cleanRect(h).contains(ev.x, ev.y) -> 1
                sharedOk() && sharedRect(h).contains(ev.x, ev.y) -> 4
                else -> 0
            }
            if (downClean != 0) invalidate()
            downTab = TAB_NAMES.indices.firstOrNull { tabRect(it, w).let { r -> RectF(r.left, 62f, r.right, 158f) }.contains(ev.x, ev.y) } ?: -1
            val sw = switches()
            val rects = chipRects(sw)
            downSw = sw.indices.firstOrNull { rects[it].let { r -> RectF(r.left, r.top - 8f, r.right, r.bottom + 8f) }.contains(ev.x, ev.y) } ?: -1
            padDown = downTab < 0 && downSw < 0 && ev.y > 158f
            // p56: on Party mode's page the bar (and the bar's right page) is this page's, the rest Party mode's own
            partyDown = padDown && topTab() == "party" && !(SituationPage.barHit(ev.x, ev.y) > 0 || (tab() == "pbar" && ev.x >= SPLIT))
            if (partyDown && tab() == "pbar") {                              // p56: from the bar onto Party mode's controller: its tab first
                onPadCells(viewCells("party")); page.showTabLocal("party")
            }
        }
        if (padDown && partyDown) {
            partyPage.onTouch(ev, tabCtl)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) padDown = false
            return true
        }
        if (padDown) {
            page.onTouch(ev, w, h, book)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) padDown = false
            return true
        }
        if (ev.actionMasked != MotionEvent.ACTION_UP) return true
        // a tab: shown at once, the addon told (one padtab pick)
        if (downTab >= 0 && tabRect(downTab, w).let { r -> RectF(r.left, 62f, r.right, 158f) }.contains(ev.x, ev.y)) {
            val v = TAB_VIEWS[downTab]
            // p56: a situation opens on its controller; the Party mode tab: partycfg's own column
            page.showTabLocal(v)
            val cells = viewCells(v)
            val sent = onPadCells(cells)
            Log.i(TAG, "tab '$v': ${cells.joinToString { "${it.first} ${it.second}" }} ${if (sent) "tapped" else "not sent"}")
            downTab = -1; invalidate(); return true
        }
        // a switch: ONE tap on its own column
        val sw = switches()
        if (downSw >= 0) {
            val t = sw.getOrNull(downSw)
            if (t != null && swRect(downSw, sw.size).contains(ev.x, ev.y)) {
                if (t.key == "cbshow") {                                    // p55 (D4): the app's own setting (the add-on is told by cbSetting)
                    HomeSettings.castBarCombat = CastStripModel.combatFor(!showOn())
                    Log.i(TAG, "switch 'Show tap-to-cast' -> ${if (showOn()) "on" else "off"}")
                } else if (t.key == "cbpshow") {                            // p56: Bar in Party mode, the app's own (told by cbSetting)
                    HomeSettings.castBarPartyShow = !HomeSettings.castBarPartyShow
                    Log.i(TAG, "switch 'Bar in Party mode' -> ${if (HomeSettings.castBarPartyShow) "on" else "off"}")
                } else if (t.col != null) {
                    val sent = onPadCells(listOf("spells" to t.col))
                    Log.i(TAG, "switch '${t.label}' -> ${if (t.on == true) "off" else "on"}: spells ${t.col} ${if (sent) "tapped" else "not sent"}")
                    if (sent && t.on != null && map?.combat == false) { swWant[t.key] = !t.on to SystemClock.uptimeMillis() + ECHO_MS; invalidate() }
                    // issue #136 (2026-11-11): the toolbar's flip is the player's choice in the app too (kept, re-sent until the game says it)
                    if (t.key == "dpad" && t.on != null) onDpadPick?.invoke(!t.on, sent)
                } else say(t.held ?: "Update the game's add-on to switch this here")
            }
            downSw = -1; return true
        }
        // m41 S1 / m42: Clean up or the shared macros' Delete: a fresh list (a pick), then the box that names it all
        if (downClean == 1 || downClean == 4) {
            val was = downClean; downClean = 0
            val hit = tab() == "ds" && (if (was == 1) cleanN() > 0 && cleanRect(h).contains(ev.x, ev.y)
                                        else sharedOk() && sharedRect(h).contains(ev.x, ev.y))
            if (hit) {
                if (map?.combat == true) say("Not in a fight: try again after it")
                else {
                    askKind = if (was == 1) "cleanup" else "cleanshared"
                    val sent = onPadCells(listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_CLEAN_LIST))
                    Log.i(TAG, "$askKind box: padtab ${PadMapModel.COL_CLEAN_LIST} ${if (sent) "tapped" else "not sent"}")
                }
            }
            invalidate(); return true
        }
        return true
    }

    /** THE ONE ACTION: the add-on runs the clean-up or the shared macros' delete (out of combat; it refuses in a fight and says so) */
    private fun cleanRun(kind: String?) {
        val col = if (kind == "cleanshared") PadMapModel.COL_CLEAN_SHARED else PadMapModel.COL_CLEAN_RUN
        val sent = onPadCells(listOf(PadMapModel.ROW_PADTAB to col))
        askKind = null
        Log.i(TAG, "$kind run: padtab $col ${if (sent) "tapped" else "not sent"}")
    }

    /** Cancel: nothing done; the controller's own box is closed by its touch cancel (PadTabCtl p20 / p21) */
    private fun cleanCancel() {
        if (ctlBoxKind() != null) onPadCells(listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_CANCEL))
        askKind = null
    }

    /** what the clean-up does, every button and every macro by name ("a confirmation that names the thing") */
    private fun cleanText(cl: PadMapModel.Clean?): String {
        if (cl == null) return "Reading what an older DS Forever build left on this character..."
        fun where(s: PadMapModel.CleanSlot) = page.comboWord(s.set, s.pos) + if (s.page > 1) " (page ${s.page})" else ""
        fun cmdName(s: PadMapModel.CleanSlot) = map?.commands?.firstOrNull { it.key == s.cmd }?.name ?: s.what?.removePrefix("DS ") ?: s.cmd ?: "?"
        val out = ArrayList<String>()
        val back = cl.slots.filter { it.kind != "command" && it.back != null }
        if (back.isNotEmpty()) out += "Back on your buttons: " + back.joinToString(", ") { "${it.back} (${where(it)})" } + "."
        val empty = cl.slots.filter { it.kind != "command" && it.back == null }
        if (empty.isNotEmpty()) out += "Emptied: " + empty.joinToString(", ") { "${it.what ?: "a DS macro"} (${where(it)})" } + "."
        val kept = cl.slots.filter { it.kind == "command" }
        if (kept.isNotEmpty()) out += "Kept as DS Forever commands, without a macro: " + kept.joinToString(", ") { "${cmdName(it)} (${where(it)})" } + "."
        val fights = cl.slots.filter { it.fight != null }                   // m43: an old combat button keeps its fight spell
        if (fights.isNotEmpty()) out += "Kept as fight spells, without a macro: " + fights.joinToString(", ") { "${it.fight} (${where(it)})" } + "."
        if (cl.char.isNotEmpty()) out += "${who()}'s macros deleted: " + cl.char.joinToString(", ") + "."
        if (cl.shared.names.isNotEmpty()) out += "The shared DS macros (${cl.shared.names.joinToString(", ")}) stay for now: they get their own " +
            "step once no character uses them."
        out += "Nothing else is touched: your own macros, spells and keys stay as they are."
        return out.joinToString("\n\n")
    }

    /** m42: the shared macros' box text: each by name, and any character not checked since this update */
    private fun sharedText(cl: PadMapModel.Clean?): String {
        val sh = cl?.shared ?: return "Reading the shared DS macros..."
        val out = ArrayList<String>()
        out += "Deleted from your General Macros, shared by all your characters: " + sh.names.joinToString(", ") + "."
        out += "None of the characters checked has one on a button now."
        if (sh.unchecked.isNotEmpty()) out += "Not checked since this update: " + sh.unchecked.joinToString(", ") { it.substringBefore('-') } +
            ". A button there that holds one of these would lose it; log in once with them first to check."
        out += "Nothing else is touched: your own macros stay as they are."
        return out.joinToString("\n\n")
    }

    /** the box (the game's dialog, as the page's boxes): the question, everything by name, Clean up / Cancel */
    private fun drawCleanBox(c: Canvas, w: Float, h: Float) {
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val r = cleanBoxRect(w, h)
        val inner = UiKit.dialog(c, r)
        val pt = UiKit.bodyPaint(34f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        val shared = boxKind() == "cleanshared"
        c.drawText(Tr.t(if (shared) "Delete the shared DS macros?" else "Clean up ${who()}?"), w / 2f, inner.top + 40f, pt)
        val yes = cleanYesRect(w, r)
        val textTop = inner.top + 76f; val textBottom = yes.top - 24f
        val text = if (shared) sharedText(clean) else cleanText(clean)
        var size = 26f
        var lay: StaticLayout
        while (true) {
            // smaller to fit, down to 24 px (UI Art: every name readable), never past the box
            lay = StaticLayout.Builder.obtain(Tr.span(text), 0, Tr.span(text).length, TextPaint(UiKit.bodyPaint(size, Theme.text)), (inner.width() - 40f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
            if (lay.height <= textBottom - textTop || size <= 24f) break
            size -= 1f
        }
        c.save(); c.clipRect(inner.left, textTop, inner.right, textBottom); c.translate(inner.left + 20f, textTop); lay.draw(c); c.restore()
        UiKit.button(c, yes, if (shared) "Delete" else "Clean up", glyph(8), if (downClean == 2) UiKit.Button.DOWN else UiKit.Button.UP)
        UiKit.button(c, cleanNoRect(w, r), "Cancel", glyph(7), if (downClean == 3) UiKit.Button.DOWN else UiKit.Button.UP)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        page.setActive(isShown)
    }

    override fun onDetachedFromWindow() {
        page.setActive(false)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "ControllerView"
        private const val SPLIT = 740f
        private const val SPELLS_CLOSE_CELL = 1
        private const val BUSY_MS = 4000L
        private const val ECHO_MS = 1500L
        /** b-start-attack-row: what the paused start-attack switch says (a tap, or the controller's A and the add-on's "paused") */
        private const val PAUSED_SAY = "Start attack on cast is coming back later"
        private val TOGGLE_OPS = setOf("dpad-on", "dpad-off", "cc-on", "cc-off", "attack")
        private val PBMP = Paint(Paint.FILTER_BITMAP_FLAG)
        private val MAIN = android.os.Handler(android.os.Looper.getMainLooper())
        /** p16 the room's tabs, in PadTabCtl's order; p34 (the user 2026-11-10: "consolidate the Controller menu"): the DS tab is gone (its
         *  switch on WoW Defaults, its four places on WoW Defaults' bare D-pad; the Item wheel's Tap / Hold in Settings > Controller) */
        /** p55 (D1): the Cast bar tab is gone from the top row: its page is WoW Defaults > Tap to cast */
        /** p56 (Option C): the situations in the user's order; "WoW Defaults" is no tab name any more (Out of combat) */
        private val ALL_TAB_VIEWS = listOf("normal", "combat", "party", "radial")
        private val ALL_TAB_NAMES = listOf("Out of combat", "In combat", "Party mode", "Spell radial")
        /** p56: the situation tabs' row and their pictures */
        const val TAB_TOP = 76f
        const val TAB_H = 72f
        private const val TAB_ICON = 40f
        /** p56: the chips' check box */
        private const val CHIP_BOX = 36f
        private val pChip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(240, 30, 24, 19) }
        private val pChipEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = android.graphics.Color.rgb(96, 80, 58) }
        private val pChipShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(90, 0, 0, 0) }
        /** p55: the Show tap-to-cast switch has no add-on column (the app's own setting): any non-null value marks it switchable */
        private const val SHOW_COL = -1
        /** the tab's word, measured for the swords' room (UiKit.tab's size) */
        private val PTABW by themed { UiKit.bodyPaint(28f, Theme.text) }
        /** the clean-up buttons' small lines stay this far from the Home bar's frame */
        private const val TEXT_INSET = 24f
    }
}
