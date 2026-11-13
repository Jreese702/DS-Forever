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
        HomeSettings.onChange { post { cbSetting(false); invalidate() } }   // the radial's way (Hold / Tap); p53: the bar's setting
        CastStripModel.Live.onChange { post { cbSetting(false); invalidate() } }    // p53: the Cast bar tab's slots
    }

    private val page = PadTabPage(this, { cells -> onPadCells(cells) }, { e -> onBind(e) }, otherReader, cursorOnTabs = { false },
        cellsOf = { e -> book?.let { SpellsModel.bindCells(it, e) } },       // b-druid-forms-bind: Book b38's two taps
        // p34: the toolbar's four show on the bare D-pad as the switch SHOWS (a finger's flip at the tap)
        toolbarShown = { map?.dpad?.let { swShown("dpad", it.want == true) } == true })
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
        tabCtl?.let { it.on && (it.chooser != null || it.box != null || it.carry != null) } != true

    // ---------------------------------------------------------------- Home's calls

    /** Into the room (every route: the bar, touch): the addon takes the controller and opens the page. True when the tap went out. An
     *  older addon without the "croom" column: the Controller tab's own touch-open (the page lit, Down enters it). */
    fun enterRoom(): Boolean {
        val col = book?.win?.get("croom")
        // p53: the bar's setting first (a no-action pick: the add-on's LB / RB step onto the Cast bar tab only while it shows)
        val cbOn = cbTabOn()
        val pre = CastStripModel.ctlSettingCells(cbOn)
        // the try prompt's Yes: the Cast bar tab, slot 1, after the open (the room's own open route: B goes back as from any tab)
        val toCb = col != null && castBarWanted && cbOn
        val after = if (toCb) CastStripModel.ctlTabCells() + (PadMapModel.ROW to PadMapModel.colPos(1)) else emptyList()
        val cells = if (col != null) pre + ("spells" to col) + after
                    else (if (page.needsLayout()) listOf(page.layoutCell()) else emptyList()) + (PadMapModel.ROW to PadMapModel.COL_OPENED)
        val sent = onPadCells(cells)
        if (sent && col != null) cbSent = cbOn
        if (sent && col != null) castBarWanted = false                    // once: this open (on the tab when it shows)
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
    /** p53: the Cast bar tab shows (Settings' bar not Off, the add-on's bar takes every kind) */
    private fun cbTabOn(): Boolean = CastStripModel.ctlTab(HomeSettings.castBarSide, CastStripModel.Live.bar)
    /** what the add-on was last told (null = nothing yet) */
    private var cbSent: Boolean? = null
    /** p53: the setting to the add-on when it changed since it was last told ([force]: always) */
    private fun cbSetting(force: Boolean) {
        val on = cbTabOn()
        if (!force && cbSent == on) return
        if (onPadCells(CastStripModel.ctlSettingCells(on))) cbSent = on
    }

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
    fun onPadTabCtl(c: PadMapModel.Ctl) { tabCtl = c; page.onCtl(c); partyPage.onCtl(c); invalidate() }
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
    /** party_design.md B: the Party mode tab shows only while Party mode is allowed (QOL > Party, on by default; PadTabCtl p37 steps over it too) */
    private val TAB_VIEWS: List<String> get() = ALL_TAB_VIEWS.filter { v ->
        when (v) { "party" -> GameUi.partyModeOn(); "castbar" -> cbTabOn(); else -> true } }              // p53: the Cast bar's own rule
    /** the page (tests) */
    fun page(): PadTabPage = page
    /** the tabs on show (tests: p53's Cast bar tab only while the bar is not Off) */
    fun tabViews(): List<String> = TAB_VIEWS
    private val TAB_NAMES: List<String> get() = TAB_VIEWS.map { ALL_TAB_NAMES[ALL_TAB_VIEWS.indexOf(it)] }
    /** the four tabs (p16), the gaps ONLY round the chosen one, where its LB / RB glyphs stand; they move over when the tab changes (the user
     *  after b118: "the spacing should only be between the currently selected tab. swaping between tabs should animate this spacing";
     *  UI Art art-kit 494b1830). Party mode (the 5th tab): the row from the screen edges (the row keeps its L / R room itself) */
    private val gaps = UiKit.TabGaps(vertical = false)
    private fun tabRect(i: Int, w: Float): RectF =
        gaps.row(TAB_NAMES.size, 0f, w, 76f, 70f, 260f, shownTab(tab()), this)[i]     // UI Art: four fit as words
    /** p34: a tab's place in the row the room SHOWS (the DS tab is gone: an old "ds" shows as WoW Defaults) */
    private fun shownTab(view: String): Int = TAB_VIEWS.indexOf(view).coerceAtLeast(0)
    /** p34: the add-on's tab number (PadTabCtl's TABS, 1-based, the DS tab still counted) as a place in the shown row */
    private fun shownTcur(tcur: Int): Int = shownTab(PadMapModel.TABS.getOrElse(tcur - 1) { "normal" })

    /** issue #136 (2026-11-11): the DS WoW toolbar switch flipped here (the new choice, the tap sent): the host keeps it as the player's */
    var onDpadPick: ((Boolean, Boolean) -> Unit)? = null

    /** a switch: label, state as SHOWN (null = this addon cannot switch it yet), the column that sets the OTHER state, its key */
    private class Sw(val label: String, val on: Boolean?, val col: Int?, val key: String,
                     /** greyed out on purpose (not an older add-on): what a tap says */
                     val held: String? = null)

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
        return when (tab()) {
            // p34 (the user 2026-11-10: "move the DS wow toolbar check box to the Wow defaults tab"): WoW Defaults' own switch
            "normal", "ds" -> {
                val d = m?.dpad
                val dOn = d?.let { swShown("dpad", it.want == true) }
                listOf(Sw("DS WoW toolbar", dOn, d?.let { if (dOn == true) it.colOff else it.colOn }, "dpad"))
            }
            "combat" -> {
                val cc = m?.faceCc?.let { swShown("cc", it) }
                val sa = m?.attack
                val saOn = sa?.let { swShown("sa", it.on) }
                listOf(Sw("Combat controls", cc, cc?.let { if (it) m.faceCcColOff else m.faceCcColOn }, "cc"),
                       // b-start-attack-row (the user 2026-10-13): PAUSED in the add-on (m40) = greyed out, unticked, "coming back later"
                       if (sa?.paused == true) Sw("Start attack on cast", false, null, "sa", held = PAUSED_SAY)
                       else Sw("Start attack on cast", saOn, sa?.let { if (saOn == true) it.colOff else it.colOn }, "sa"))
            }
            else -> emptyList()
        }
    }

    /** the switches on the left page's top (where the views row was), the game's check box on a plate */
    private fun swRect(i: Int, n: Int): RectF {
        val l0 = 40f; val r0 = SPLIT - 40f; val gap = 16f
        val each = if (n <= 1) 420f else (r0 - l0 - (n - 1) * gap) / n
        val l = l0 + i * (each + gap)
        return RectF(l, 196f, l + each, 268f)
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
        c.drawColor(Theme.ink)
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
        // p34: four tabs (the DS tab folded into WoW Defaults): each label set smaller to fit its tab (UI Art: "WoW Defaults" about 26)
        for (i in TAB_NAMES.indices) {
            val r = tabRect(i, w)
            UiKit.tab(c, r, "", i == cur, cursor = k != null && k.focus == "tabs" && shownTcur(k.tcur) == i)
            val p = UiKit.bodyPaint(28f, if (i == cur) android.graphics.Color.WHITE else Theme.gold).apply { textAlign = Paint.Align.CENTER }
            while (p.textSize > 20f && p.measureText(TAB_NAMES[i]) > r.width() - 24f) p.textSize -= 1f
            c.drawText(Tr.t(TAB_NAMES[i]), r.centerX(), r.centerY() + 8f - (p.ascent() + p.descent()) / 2f, p)
        }
        // the In combat tab carries the game's gold crossed swords (UI Art's sheet): the fight set at a glance, where the narrower four-tab
        // row leaves it room beside the word
        UiKit.tex("combat_swords")?.let { sw ->
            val ci = TAB_VIEWS.indexOf("combat")
            val r = tabRect(ci, w)
            if ((r.width() - PTABW.measureText(TAB_NAMES[ci])) / 2f >= 64f)
                c.drawBitmap(sw, null, RectF(r.left + 20f, r.centerY() - 16f, r.left + 56f, r.centerY() + 18f), PBMP)
        }
        // party_design.md B: the Party mode tab carries the Select glyph (its way in), as In combat carries the swords
        TAB_VIEWS.indexOf("party").takeIf { it >= 0 }?.let { pi ->
            val r = tabRect(pi, w)
            if ((r.width() - PTABW.measureText(TAB_NAMES[pi])) / 2f >= 56f)
                UiKit.pad(c, Glyphs.translate("rev_minus", CastSettings.buttonLayout), r.left + 36f, r.centerY() + 1f, 34f)
        }
        // L and R on the row's outsides, always (the user, 15 Oct), the chooser open too (PadTabCtl p30: LB / RB stay the room's tabs)
        gaps.badges(c)
        // p17: the cursor on the tab row: the game's gold arrows on both sides of its tab (no wrap: none past the ends), in the gaps
        if (k != null && k.focus == "tabs" && !page.chooserOpen() && !partyPage.chooserOpen(k))
            shownTcur(k.tcur).let { t -> UiKit.moveArrows(c, tabRect(t, w), left = t > 0, right = t < TAB_NAMES.size - 1, words = false) }

        UiKit.bookPage(c, RectF(8f, 158f, SPLIT, h - 4f))
        UiKit.bookPage(c, RectF(SPLIT, 158f, w - 8f, h - 4f), right = true)
        UiKit.bookFold(c, SPLIT, 166f, h - 12f)
        when (tab()) {
            "party" -> partyPage.draw(c, w, h, tabCtl)
            else -> page.draw(c, w, h, book)
        }
        drawSwitches(c, k)
        if (cleanBoxShown()) drawCleanBox(c, w, h)
    }

    private fun drawSwitches(c: Canvas, k: PadMapModel.Ctl?) {
        val sw = switches()
        for ((i, t) in sw.withIndex()) {
            val r = swRect(i, sw.size)
            c.drawRoundRect(r, 8f, 8f, pPlate)
            val p = UiKit.bodyPaint(26f, if (t.col != null) Theme.text else Theme.dim)
            UiKit.check(c, r.right - 42f, r.centerY(), 58f, t.on == true, enabled = t.col != null)
            while (p.textSize > 18f && 20f + p.measureText(t.label) + 84f > r.width()) p.textSize -= 1f
            c.drawText(Tr.t(t.label), r.left + 20f, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
        }
        if (k != null && k.focus == "switches") sw.indices.firstOrNull { it == k.sw - 1 }?.let { UiKit.focus(c, swRect(it, sw.size), onPaper = true) }
    }

    private fun padStyle(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }

    // ---------------------------------------------------------------- touch

    private var padDown = false

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
            downSw = sw.indices.firstOrNull { swRect(it, sw.size).let { r -> RectF(r.left, r.top - 8f, r.right, r.bottom + 8f) }.contains(ev.x, ev.y) } ?: -1
            padDown = downTab < 0 && downSw < 0 && ev.y > 173f
        }
        if (padDown && tab() == "party") {
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
            val n = PadMapModel.tabOf(v)                                    // p34: the add-on's tab number (the DS tab still counted)
            page.showTabLocal(v)
            // the Party mode tab: partycfg's own column when it came (PartyTaps.tab), else Book b47's padtab 26
            val cells = (if (v == "party") PartyTaps.tab(PartyModel.live.cfg) else null)
                ?: if (v == "castbar") CastStripModel.ctlTabCells()                     // p53: padtab 39 then 29
                else listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(n))
            val sent = onPadCells(cells)
            Log.i(TAG, "tab '$v': padtab ${PadMapModel.colTab(n)} ${if (sent) "tapped" else "not sent"}")
            downTab = -1; invalidate(); return true
        }
        // a switch: ONE tap on its own column
        val sw = switches()
        if (downSw >= 0) {
            val t = sw.getOrNull(downSw)
            if (t != null && swRect(downSw, sw.size).contains(ev.x, ev.y)) {
                if (t.col != null) {
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
        /** p16 the room's tabs, in PadTabCtl's order; p34 (the user 2026-11-10: "consolidate the Controller menu"): the DS tab is gone (its
         *  switch on WoW Defaults, its four places on WoW Defaults' bare D-pad; the Item wheel's Tap / Hold in Settings > Controller) */
        private val ALL_TAB_VIEWS = listOf("normal", "combat", "radial", "party", "castbar")
        private val ALL_TAB_NAMES = listOf("WoW Defaults", "In combat", "Spell radial", "Party mode", "Cast bar")
        /** the tab's word, measured for the swords' room (UiKit.tab's size) */
        private val PTABW by themed { UiKit.bodyPaint(28f, Theme.text) }
        /** the clean-up buttons' small lines stay this far from the Home bar's frame */
        private const val TEXT_INSET = 24f
    }
}
