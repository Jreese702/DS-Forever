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
 * THE ADDON'S SIDE (Book b17 / b22): [enterRoom] taps the `spells` row's "croom" column: the addon takes the controller by touch (no game
 * window: the game has none for this page) and the room's half (PadTabCtl) gets every press; B at the top leaves to Home. A on the DS tab's
 * Up counts in `spellsctl.tn` (the app flips the radial's way once per count).
 * A finger: a switch is ONE tap (its own on / off column, idempotent; refused in a fight with one red pulse, as Settings' row).
 * m41 S1 THE CLEAN-UP (the user's pick 2026-10-09: "a 'Clean up this character' button in the Controller room's DS tab lists exactly what
 * goes; one yes per character"): under the D-pad while PadMap's plan (`padclean`) has something; a box names every button and macro first.
 * m42 THE SHARED ONES (the user's answer 2026-10-09 "Last, separately"): the account-wide DS macros get their own "Delete" button beside
 * it, once no checked character still holds one; its own box names them and any character not checked yet.
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
    /** the DS tab's place a finger chose (1-4); the controller's (`dpos`) wins while the room holds */
    private var dsSel = 2
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
        HomeSettings.onChange { postInvalidate() }      // the radial's way (Hold / Tap) can change in Home's Settings too
    }

    private val page = PadTabPage(this, { cells -> onPadCells(cells) }, { e -> onBind(e) }, otherReader, cursorOnTabs = { false })

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
        val cells = if (col != null) listOf("spells" to col)
                    else (if (page.needsLayout()) listOf(page.layoutCell()) else emptyList()) + (PadMapModel.ROW to PadMapModel.COL_OPENED)
        val sent = onPadCells(cells)
        if (sent && col != null) page.showTabLocal(PadMapModel.TABS[0])      // p17: it opens on WoW Defaults, shown at the tap
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
    fun onPadTabCtl(c: PadMapModel.Ctl) { tabCtl = c; if (c.on) dsSel = c.dpos; page.onCtl(c); invalidate() }
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
    /** the four tabs (p16), the gaps ONLY round the chosen one, where its LB / RB glyphs stand; they move over when the tab changes (the user
     *  after b118: "the spacing should only be between the currently selected tab. swaping between tabs should animate this spacing";
     *  UI Art art-kit 494b1830) */
    private val gaps = UiKit.TabGaps(vertical = false)
    private fun tabRect(i: Int, w: Float): RectF =
        gaps.row(TAB_NAMES.size, 60f, w - 60f, 76f, 70f, 260f, PadMapModel.tabOf(tab()) - 1, this)[i]     // UI Art: four fit as words

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
            "ds" -> {
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

    // ---------------------------------------------------------------- the DS tab's places

    private class DsPlace(val word: String, val art: String?, val what: String, val wheel: Boolean = false)
    private val dsPlaces = listOf(
        DsPlace("Chat", "ricon_chat", "Opens the Chat room."),
        // b-radial-icon: the toolbar's own mini wheel (CastView's D-pad Up), not a potion
        DsPlace("Radial", null, "Opens the radial of the consumables in your bags.", wheel = true),
        DsPlace("Map", "ricon_map", "Opens Home's map."),
        DsPlace("Home bar", "home_live_base", "Puts the bar's cursor on the Home swirl: pick a room, A opens it."),
    )
    private fun dsCentre(p: Int): Pair<Float, Float> {
        val cx = SPLIT / 2f; val cy = 560f
        return when (p) { 1 -> cx - 150f to cy; 2 -> cx to cy - 150f; 3 -> cx + 150f to cy; else -> cx to cy + 150f }
    }
    private fun dsSquare(p: Int) = dsCentre(p).let { (x, y) -> RectF(x - 52f, y - 52f, x + 52f, y + 52f) }
    /** the wheels' two settings on the Radial's page (Home 2026-10-09): open Tap | Hold above, pick A | Release below; [i] 0 left, 1 right */
    private fun openRect(i: Int, w: Float, h: Float) = choiceRect(i, w, h - 300f, h - 189f)
    private fun pickRect(i: Int, w: Float, h: Float) = choiceRect(i, w, h - 141f, h - 30f)
    private fun choiceRect(i: Int, w: Float, t: Float, b: Float) =
        if (i == 0) RectF(SPLIT + 40f, t, (SPLIT + w) / 2f - 8f, b) else RectF((SPLIT + w) / 2f + 8f, t, w - 56f, b)
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
            c.drawText(it, w - 24f, 31f - (pb.ascent() + pb.descent()) / 2f, pb)
        }
        // THE TABS (p9), the game's tab look, LB / RB in the chosen tab's gaps
        val cur = PadMapModel.tabOf(tab())
        val k = tabCtl?.takeIf { it.on }
        for (i in TAB_NAMES.indices) UiKit.tab(c, tabRect(i, w), TAB_NAMES[i], i + 1 == cur, cursor = k != null && k.focus == "tabs" && k.tcur == i + 1)
        // the In combat tab carries the game's gold crossed swords (UI Art's sheet): the fight set at a glance, where the narrower four-tab
        // row leaves it room beside the word
        UiKit.tex("combat_swords")?.let { sw ->
            val r = tabRect(2, w)
            if ((r.width() - PTABW.measureText(TAB_NAMES[2])) / 2f >= 64f)
                c.drawBitmap(sw, null, RectF(r.left + 20f, r.centerY() - 16f, r.left + 56f, r.centerY() + 18f), PBMP)
        }
        // p17: the cursor on the tab row: the game's gold arrows on both sides of its tab (no wrap: none past the ends), in the gaps
        if (k != null && k.focus == "tabs" && !page.chooserOpen())
            UiKit.moveArrows(c, tabRect(k.tcur - 1, w), left = k.tcur > 1, right = k.tcur < TAB_NAMES.size, words = false)

        UiKit.bookPage(c, RectF(8f, 158f, SPLIT, h - 4f))
        UiKit.bookPage(c, RectF(SPLIT, 158f, w - 8f, h - 4f), right = true)
        UiKit.bookFold(c, SPLIT, 166f, h - 12f)
        if (tab() == "ds") drawDs(c, w, h) else page.draw(c, w, h, book)
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
            c.drawText(t.label, r.left + 20f, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
        }
        if (k != null && k.focus == "switches") sw.indices.firstOrNull { it == k.sw - 1 }?.let { UiKit.focus(c, swRect(it, sw.size), onPaper = true) }
    }

    /** the DS tab: the plain D-pad's four places on the left page; the chosen one on the right (the Radial: its Hold / Tap) */
    private fun drawDs(c: Canvas, w: Float, h: Float) {
        val on = map?.dpad?.let { swShown("dpad", it.want == true) } == true
        val k = tabCtl?.takeIf { it.on }
        val sel = (if (k != null) k.dpos else dsSel).coerceIn(1, 4)
        val pi = UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim }
        // m38 (the user 2026-10-08, Q7: Start follows this switch): the line names Start too. UI Art: wrapped onto TWO lines at 24 px (dim ink
        // smaller than that reads poorly on the Thor), as menuRow wraps a long label; the first baseline a little higher so the second
        // clears the D-pad's Up square
        val line = if (on) "On: the plain D-pad opens these and Start the pause wheel, in fights too."
                   else "Off: the plain D-pad and Start are the game's own (WoW Defaults)."
        val pl = TextPaint(UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim })
        val lineLay = StaticLayout.Builder.obtain(line, 0, line.length, pl, (SPLIT - 64f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1f)
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(40f, 300f + pl.ascent()); lineLay.draw(c); c.restore()
        val pw = UiKit.inkPaint(26f).apply { textAlign = Paint.Align.CENTER }
        for (p in 1..4) {
            val r = dsSquare(p)
            val d = dsPlaces[p - 1]
            val art = artOf(d)
            UiKit.spell(c, art, r.left, r.top, r.width(), if (on) UiKit.Spell.KNOWN else UiKit.Spell.LEVEL)
            if (d.wheel) UiKit.miniWheel(c, r.centerX(), r.centerY(), r.width() * 0.4f)
            else if (art == null) c.drawText(d.word.take(1), r.centerX(), r.centerY() + 10f, pw)
            c.drawText(d.word, r.centerX(), r.bottom + 58f, pw)
        }
        if (k == null || k.focus == "dpad") UiKit.slotFocus(c, dsSquare(sel))
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        // m41 S1: the clean-up button, only while an older build's things are left on this character
        val n = cleanN()
        if (n > 0) {
            val r = cleanRect(h)
            val said = "$n ${if (n == 1) "thing" else "things"} an older DS build left here"
            val pt = TextPaint(UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim })
            // the line clear of the Home bar's frame (the button's width less its side next to it)
            val sl = StaticLayout.Builder.obtain(said, 0, said.length, pt, (r.width() - TEXT_INSET).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(0f, 1f).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate(r.left + TEXT_INSET, r.top - 8f - sl.height); sl.draw(c); c.restore()
            UiKit.button(c, r, "Clean up", glyph(8), if (downClean == 1) UiKit.Button.DOWN else UiKit.Button.UP)
            if (k != null && k.focus == "cleanup") UiKit.focus(c, r, onPaper = true)
        }
        if (sharedOk()) {
            // m42: the shared DS macros' own button, once no checked character holds one
            val r = sharedRect(h)
            val cnt = clean?.shared?.names?.size ?: 0
            val said = "$cnt shared DS ${if (cnt == 1) "macro" else "macros"} no character uses"
            val pt = TextPaint(UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim })
            val sl = StaticLayout.Builder.obtain(said, 0, said.length, pt, (r.width() - TEXT_INSET).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(0f, 1f).setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate(r.left, r.top - 8f - sl.height); sl.draw(c); c.restore()
            UiKit.button(c, r, "Delete", glyph(8), if (downClean == 4) UiKit.Button.DOWN else UiKit.Button.UP)
            if (k != null && k.focus == "shared") UiKit.focus(c, r, onPaper = true)
        }
        if (sharedOk() && k != null && k.focus == "shared") {
            val sh = clean!!.shared
            val pn = UiKit.inkPaint(40f, heading = true)
            c.drawText("Shared macros", x0 + 8f, top + 30f - pn.ascent() - 4f, pn)
            val text = "${sh.names.joinToString(", ")}: an older DS Forever build made ${if (sh.names.size == 1) "it" else "them"} in your General " +
                "Macros, shared by all your characters. No checked character has one on a button now. Delete removes only these; a box " +
                "names them first."
            val lay = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(UiKit.inkPaint(24f)), (x1 - x0 - 16f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
            c.save(); c.translate(x0 + 8f, top + 100f); lay.draw(c); c.restore()
            return
        }
        if (n > 0 && k != null && k.focus == "cleanup") {
            // the right page: what the clean-up is (the box names every button and macro before the yes)
            val pn = UiKit.inkPaint(40f, heading = true)
            c.drawText("Clean up", x0 + 8f, top + 30f - pn.ascent() - 4f, pn)
            val text = "An older DS Forever build put macros on ${who()}'s buttons. Clean up puts your spells back, keeps a DS command as one " +
                "without a macro, and deletes only DS Forever's own macros. A box names everything first."
            val lay = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(UiKit.inkPaint(24f)), (x1 - x0 - 16f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
            c.save(); c.translate(x0 + 8f, top + 100f); lay.draw(c); c.restore()
            return
        }
        // the right page: the chosen place
        val d = dsPlaces[sel - 1]
        UiKit.spell(c, artOf(d), x0 + 36f, top + 16f, 96f, if (on) UiKit.Spell.KNOWN else UiKit.Spell.LEVEL)
        if (d.wheel) UiKit.miniWheel(c, x0 + 36f + 48f, top + 16f + 48f, 96f * 0.4f)
        val pn = UiKit.inkPaint(40f, heading = true)
        c.drawText(d.word, x0 + 158f, top + 30f - pn.ascent() - 4f, pn)
        c.drawText("D-pad " + arrayOf("Left", "Up", "Right", "Down")[sel - 1], x0 + 160f, top + 120f, pi)
        val text = d.what + if (!on) " The toolbar is off now." else ""
        val pd = TextPaint(UiKit.inkPaint(24f))
        val lay = StaticLayout.Builder.obtain(text, 0, text.length, pd, (x1 - x0 - 16f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
        c.save(); c.translate(x0 + 8f, top + 170f); lay.draw(c); c.restore()
        if (sel == 2) {
            // THE ITEM & SPELL WHEELS' BEHAVIOUR (Home 2026-10-09: open Tap / Hold + pick A / Release, defaults Tap + A; Settings > Controller
            // has the same two rows). The controller: A flips open, X flips pick. The pause wheel (Start) has its OWN pair (HomeSettings.pauseTap /
            // pausePickA): the user's pick 2026-10-09 "Settings only" -- said in one line here, not mirrored
            val tap = HomeSettings.radialTap
            val pickA = HomeSettings.radialPickA
            val ph = UiKit.inkPaint(26f, heading = true)
            val note = "The pause wheel (Start) has its own pair in Settings > Controller."
            val nl = StaticLayout.Builder.obtain(note, 0, note.length, TextPaint(UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim }),
                (x1 - x0 - 16f).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f, 1f).setMaxLines(2).build()
            c.save(); c.translate(x0 + 8f, h - 392f - nl.height); nl.draw(c); c.restore()
            c.drawText("Item and spell wheels", x0 + 8f, h - 352f, ph)
            c.drawText("Wheels: open", x0 + 8f, h - 312f, pi)
            UiKit.choice(c, openRect(0, w, h), "Tap", tap)
            UiKit.choice(c, openRect(1, w, h), "Hold", !tap)
            c.drawText("Wheels: pick", x0 + 8f, h - 153f, pi)
            UiKit.choice(c, pickRect(0, w, h), "Press A", pickA)                 // UI Art: a lone "A" reads as a glyph
            UiKit.choice(c, pickRect(1, w, h), "Release", !pickA)
            if (k != null && k.focus == "dpad") {
                inkHint(c, x1, h - 320f, glyph(8), "Change")
                inkHint(c, x1, h - 161f, glyph(5), "Change")
            }
        }
    }

    /** a controller hint on the parchment (UI Art: the page's ink for the word, never the dark ground's white; the glyph as the kit draws it) */
    private fun inkHint(c: Canvas, right: Float, cy: Float, glyph: String, word: String) {
        val p = UiKit.inkPaint(24f)
        val x = right - p.measureText(word)
        c.drawText(word, x, cy - (p.ascent() + p.descent()) / 2f, p)
        UiKit.pad(c, glyph, x - 30f, cy, 40f)
    }

    private fun artOf(d: DsPlace) = d.art?.let { UiKit.tex(it) }

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
            padDown = downTab < 0 && downSw < 0 && ev.y > 173f && tab() != "ds"
        }
        if (padDown) {
            page.onTouch(ev, w, h, book)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) padDown = false
            return true
        }
        if (ev.actionMasked != MotionEvent.ACTION_UP) return true
        // a tab: shown at once, the addon told (one padtab pick)
        if (downTab >= 0 && tabRect(downTab, w).let { r -> RectF(r.left, 62f, r.right, 158f) }.contains(ev.x, ev.y)) {
            val v = PadMapModel.TABS[downTab]
            page.showTabLocal(v)
            val sent = onPadCells(listOf(PadMapModel.ROW_PADTAB to PadMapModel.colTab(downTab + 1)))
            Log.i(TAG, "tab '$v': padtab ${PadMapModel.colTab(downTab + 1)} ${if (sent) "tapped" else "not sent"}")
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
        // the DS tab: a place (the app's own pick), the Radial's Hold / Tap (the app's setting)
        if (tab() == "ds") {
            (1..4).firstOrNull { dsSquare(it).let { r -> RectF(r.left - 12f, r.top - 12f, r.right + 12f, r.bottom + 40f) }.contains(ev.x, ev.y) }?.let {
                dsSel = it; invalidate(); return true
            }
            val sel = (tabCtl?.takeIf { it.on }?.dpos ?: dsSel)
            if (sel == 2) {
                when {
                    openRect(0, w, h).contains(ev.x, ev.y) -> { HomeSettings.radialTap = true; invalidate() }
                    openRect(1, w, h).contains(ev.x, ev.y) -> { HomeSettings.radialTap = false; invalidate() }
                    pickRect(0, w, h).contains(ev.x, ev.y) -> { HomeSettings.radialPickA = true; invalidate() }
                    pickRect(1, w, h).contains(ev.x, ev.y) -> { HomeSettings.radialPickA = false; invalidate() }
                }
            }
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
        c.drawText(if (shared) "Delete the shared DS macros?" else "Clean up ${who()}?", w / 2f, inner.top + 40f, pt)
        val yes = cleanYesRect(w, r)
        val textTop = inner.top + 76f; val textBottom = yes.top - 24f
        val text = if (shared) sharedText(clean) else cleanText(clean)
        var size = 26f
        var lay: StaticLayout
        while (true) {
            // smaller to fit, down to 24 px (UI Art: every name readable), never past the box
            lay = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(UiKit.bodyPaint(size, Theme.text)), (inner.width() - 40f).toInt())
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
        /** p16 the room's tabs, in PadTabCtl's order */
        private val TAB_NAMES = listOf("WoW Defaults", "DS", "In combat", "Spell radial")
        /** the tab's word, measured for the swords' room (UiKit.tab's size) */
        private val PTABW = UiKit.bodyPaint(28f, Theme.text)
        /** the clean-up buttons' small lines stay this far from the Home bar's frame */
        private const val TEXT_INSET = 24f
    }
}
