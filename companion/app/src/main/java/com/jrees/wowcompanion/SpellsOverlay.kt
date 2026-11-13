package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * What the Spellbook brings over the WHOLE screen (UI Art's SPEC 4): the game's "Bind to Action Bar" box. It lies over the rooms and the
 * bar and UNDER the NPC windows (an NPC window covers everything).
 *
 * THE BIND BOX shows while the addon's Bind box is open (`spellsctl` mode "bind", then "result" for the answer). It is a dialog: it
 * takes every touch while it is up. The answer to the press comes from the addon (`spellsack`); B cancels on the controller.
 * The two groups of buttons are the Cast room's own drawing of that set ([drawSet], cast session `f5a0baa`), with the button that
 * took the spell lit; until the Cast room has its first layout they are stand-ins in the same rectangles.
 *
 * THE "New spell" CARD IS GONE (the user, 2026-11-12: "along with the Keep/undo I feel like the pop up toasts that appear before that are
 * redundant and should be scrapped"; the pick: scrap ONLY the 4 s card per learned spell): Home's one-at-a-time prompt
 * (CompanionHost.newSpellAsk) asks about each learned spell; the box's green "Bound to" line, its "Put back" line and every refusal stay.
 *
 * Book b57 / PadMap m157 L3 / R3 (the user, 2026-11-13: "looking good, except people also want to be able to bind any of their spells
 * and stuff to these, so any of those 5 controller tabs should be able to bind here"): on the Normal tab, bare or ZL, the box draws the
 * two sticks beside the trigger chips (what each holds now, from the padmap's `sticks`); pressing one puts the spell on it, lit as any
 * button. ZR / ZL + ZR or the In combat tab: no stick tiles (the add-on answers "layer" / "stick-normal" and the box keeps waiting).
 */
class SpellsOverlay(
    context: Context,
    /** the Cast room's own drawing of one trigger set (CastView.drawSet, read-only); false = it has no layout yet */
    private val drawSet: (c: Canvas, set: Int, dpad: RectF, face: RectF, lit: Int?, litIcon: android.graphics.Bitmap?,
                          fight: ((Int) -> Int?)?) -> Boolean =
        { _, _, _, _, _, _, _ -> false },
    /** another controller reader is running (the Cast view's): then this box does not start a second one (home's finding) */
    private val otherReader: () -> Boolean = { false },
    /** b37: one tap on the `spells` row's column (the Bind box's Normal / In combat) */
    private val onTap: (Int) -> Unit = {},
    /** GitHub #43 cb3: the Bind box's Cast bar tab and its slots (padtab 39 then 26 / 20-25, 27-28 for slots 7-8: Book b56 / b57) */
    private val onCastBarTaps: (List<Pair<String, Int>>) -> Unit = {},
) : View(context) {

    private var book: SpellsModel.Book? = null
    private var ctl: SpellsModel.Ctl? = null

    // the Bind box
    private var boxSpell: Int? = null               // the spell the open box is for
    private var result: SpellsModel.BindResult? = null
    private var resultAt = 0L
    private var lt = false; private var rt = false
    /** b37 (b-bind-combat-toggle): a finger's Normal / In combat, shown AT THE TAP until the addon's `bf` agrees or 1.5 s pass */
    private var fightWant: Boolean? = null
    private var fightUntil = 0L
    private var downFight = -1
    private fun fightShown(): Boolean {
        val doc = ctl?.bindFight == true
        val want = fightWant ?: return doc
        if (want == doc || SystemClock.uptimeMillis() >= fightUntil) { fightWant = null; return doc }
        return want
    }
    /** batch5 (the user: tabs "above where it says 'no triggers held' (that text can be removed)"): the two tabs where that line stood,
     *  side by side in the box's middle; [i] 0 Normal, 1 In combat, 2 Cast bar (cb3: only while [three]) */
    private fun fightRect(i: Int, w: Float): RectF {
        // the user on test21: "the tabs for in combat vs not should be at the top, then the forms under that ... and the skill under that"
        val cy = 84f + 62f
        if (three()) {
            val tw = 290f; val gap = 12f
            val l = w / 2f - (3 * tw + 2 * gap) / 2f + i * (tw + gap)
            return RectF(l, cy - 38f, l + tw, cy + 38f)
        }
        val l = w / 2f - 330f + i * 336f
        return RectF(l, cy - 38f, l + 324f, cy + 38f)
    }

    /**
     * GitHub #43 cb3 THE CAST BAR TAB (the user, 2026-11-13: "to assign to the bar, in the bind menu, where there is the L and R tabs, we
     * will add a third tab if this setting is turned on, showing the 6 slots on that bar"): Normal | In combat | Cast bar while the
     * setting is not Off and the add-on takes a slot pick (ActionBar cb3, Book b56). On it the box shows the bar's 6 slots: the add-on's
     * cursor (`bcb`, the D-pad left / right) or a finger picks one, A or the tap puts the spell there (padtab 39 then 20-25)
     * cb6 (the user, 2026-11-13: "8 total, 4 per side"): the tab shows all 8 in ONE row, two groups of 4 (the chat's 1-4, the minimap's
     * 5-8) a wider gap apart; the D-pad left / right walks all 8 (Book b57), slots 7-8 by padtab 39 then 27-28
     */
    private fun three(): Boolean = CastStripModel.bindTab(HomeSettings.castBarSide, CastStripModel.Live.bar)
    /** a finger's Cast bar tab, shown AT THE TAP until the add-on's `bcb` agrees or 1.5 s pass (as [fightWant]) */
    private var cbWant: Boolean? = null
    private var cbUntil = 0L
    private fun cbShown(): Boolean {
        if (!three()) return false
        val doc = ctl?.bindCb != null
        val want = cbWant ?: return doc
        if (want == doc || SystemClock.uptimeMillis() >= cbUntil) { cbWant = null; return doc }
        return want
    }
    /** the tab lit: 0 Normal, 1 In combat, 2 Cast bar */
    private fun tabShown(): Int = when { cbShown() -> 2; fightShown() -> 1; else -> 0 }
    /** the Cast bar tab, by a finger or RB: its two taps, shown at once */
    private fun pickCb() {
        onCastBarTaps(CastStripModel.tabCells())
        cbWant = true; cbUntil = SystemClock.uptimeMillis() + 1500L
        invalidate()
    }
    /** leaving the Cast bar tab (Normal / In combat by LB or a finger): shown at once, the add-on's bf-on / bf-off leave it (Book b56) */
    private fun leaveCb() { cbWant = false; cbUntil = SystemClock.uptimeMillis() + 1500L }
    /** slot [i]'s box on the Cast bar tab: one row of 8 where the controller's sets stand on the other tabs ([cy] the box's top + 104);
     *  cb6: two groups of 4 (Home's two sides) [CB_MID] apart, the row centred in the box (1000 px of the Thor's 1120 px box) */
    private fun cbSlotRect(i: Int, w: Float, cy: Float): RectF {
        val s = CB_SLOT; val gap = CB_GAP
        val n = CastStripModel.SLOTS; val half = CastStripModel.VISIBLE
        val rowW = n * s + (n - 2) * gap + CB_MID
        val l = w / 2f - rowW / 2f + i * (s + gap) + (if (i >= half) CB_MID - gap else 0f)
        val t = cy + 340f + (132f - s) / 2f
        return RectF(l, t, l + s, t + s)
    }
    private var downSlot = -1
    /** LB / RB as the app's own reader sees them (on the Thor the game never hears LB: it is its emulated Ctrl): a NEW press moves the tab */
    private var lbWas = false
    private var rbWas = false
    private fun bumpers(lb: Boolean, rb: Boolean) {
        val lbNew = lb && !lbWas; val rbNew = rb && !rbWas
        lbWas = lb; rbWas = rb
        if (!lbNew && !rbNew) return
        if (ctl?.mode != "bind") return
        // LB = one tab left, RB = one tab right (Normal | In combat | Cast bar, cb3); no wrap
        val from = tabShown()
        val to = CastStripModel.stepTab(from, if (rbNew) 1 else -1, three())
        if (to == from) return
        if (to == 2) pickCb() else { if (from == 2) leaveCb(); pickFight(to == 1) }
    }
    /** ONE tap on the addon's column for [fight] (no-action, idempotent), shown at once */
    private fun pickFight(fight: Boolean) {
        val cols = fightCols() ?: return
        onTap(if (fight) cols.first else cols.second)
        fightWant = fight; fightUntil = SystemClock.uptimeMillis() + 1500L
        invalidate()
    }
    /** the addon can take the pair by touch (its columns are on the row) */
    private fun fightCols(): Pair<Int, Int>? {
        val win = book?.win ?: return null
        return (win["bfon"] ?: return null) to (win["bfoff"] ?: return null)
    }
    private var pad: PadInput? = null

    init { CastSettings.init(context); HomeSettings.init(context); CastStripModel.Live.onChange { postInvalidate() } }

    private fun style(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }

    // ---------------------------------------------------------------- data in

    fun onBook(b: SpellsModel.Book) {
        book = b
        tick()
    }

    fun onCtl(c: SpellsModel.Ctl) {
        val was = boxOpen()
        ctl = c
        if (c.on && c.bindOpen) {
            // a box that opens afresh forgets the last answer
            if (boxSpell != c.binding || c.note == "bind-wait") { boxSpell = c.binding; result = null }
        }
        if (!c.on) { boxSpell = null; result = null }
        // b42: B took the bind back: the box waits for a button again, and says so for a moment
        if (c.on && c.note == "bind-undone") { result = null; undoneAt = SystemClock.uptimeMillis() }
        if (c.on && c.note?.startsWith("bind-undo-") == true) { undoFail = c.note.removePrefix("bind-undo-"); undoFailAt = SystemClock.uptimeMillis() }
        if (boxOpen() && !was) startPad() else if (!boxOpen() && was) stopPad()
        tick()
    }

    /** b42: when B last took a bind back (the box's line says so for [SHOW_MS]); a refused undo and when */
    private var undoneAt = 0L
    private var undoFail: String? = null
    private var undoFailAt = 0L

    /** batch8: the add-on's padmap (Spells' PadMap): each button's In combat spell, for the box's In combat tab */
    private var padMap: PadMapModel.Map? = null
    fun onPadMap(m: PadMapModel.Map) { padMap = m; if (boxOpen()) invalidate() }

    /** issues #18 / #20 / #31: when the app's own reader last saw LT / RT held (uptime ms; 0 = not since the reader started). The ack
     *  reaches the app after the press, often after the trigger is let go, so the log line reads the LATCH, not the state now */
    private var ltAt = 0L; private var rtAt = 0L
    fun triggerRead(now: Long = SystemClock.uptimeMillis()): SpellsModel.AppTrig =
        if (pad == null) SpellsModel.AppTrig(readerOn = false, ltAgoMs = null, rtAgoMs = null)
        else SpellsModel.AppTrig(true, if (lt) 0L else if (ltAt > 0) now - ltAt else null, if (rt) 0L else if (rtAt > 0) now - rtAt else null)

    fun onBind(r: SpellsModel.BindResult) {
        result = r; resultAt = SystemClock.uptimeMillis()
        if (boxSpell == null) boxSpell = r.spellId
        tick()
    }

    /** the box is up: the addon's box waits, or its answer shows */
    fun boxOpen(): Boolean {
        val c = ctl ?: return false
        return c.on && (c.mode == "bind" || c.mode == "result" || c.mode == "confirm")
    }

    private fun startPad() {
        if (pad != null || otherReader()) return
        pad = PadInput(onPad = { l, r, lb, rb ->
            val t = SystemClock.uptimeMillis(); if (lt || l) ltAt = t; if (rt || r) rtAt = t   // held now, or just let go: the last held moment
            lt = l; rt = r; bumpers(lb, rb); invalidate() }, onStatus = { _, _ -> }).also {
            it.triggerSwap = CastSettings.triggerSwap
            it.start()
        }
    }

    private fun stopPad() {
        pad?.stop(); pad = null; lt = false; rt = false; ltAt = 0L; rtAt = 0L
    }

    // everything that moves on its own: the answer's 1.5 s
    private fun tick() {
        val now = SystemClock.uptimeMillis()
        visibility = if (boxOpen()) VISIBLE else GONE
        invalidate()
        if ((result != null && now - resultAt < SHOW_MS) || now - undoneAt < SHOW_MS) postDelayed({ tick() }, 50)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (boxOpen()) bindBox(c, w, h)
    }

    /** the game's "Bind to Action Bar": its box and words, the controller's buttons for the set held (SPEC 4) */
    private fun bindBox(c: Canvas, w: Float, h: Float) {
        formHits.clear()                                                // b45: only the row drawn this frame takes a finger
        val b = book
        val e = boxSpell?.let { id -> b?.let { SpellsModel.find(it, id) } }
        UiKit.cover(c, RectF(0f, 0f, w, h), 0.75f)
        val box = RectF(60f, 84f, w - 60f, 934f)
        UiKit.dialog(c, box)
        UiKit.dialogHeader(c, w / 2f, box.top - 34f, "Bind to Action Bar")
        val cy = box.top + 104f
        val st = style()
        val now = SystemClock.uptimeMillis()
        // the answer: while the addon shows it (mode "result"), or 1.5 s for one that keeps the box waiting (reserved, unsure)
        val confirming = ctl?.mode == "confirm"
        // b43: the form row (In combat, a character with forms) takes the box's top line: the instruction moves down under it
        val rowOn = !confirming && padMap?.forms?.isNotEmpty() == true && (fightShown() || padMap?.stance != null)
        // the box, top to bottom (the user on test21): the tabs, the form row, the spell, then ONE line for an answer (no instruction)
        val spellTop = box.top + (if (rowOn) 186f else 128f)
        val ly = box.top + 296f
        val r = result?.takeIf { it.spellId == boxSpell && (ctl?.mode == "result" || confirming || now - resultAt < SHOW_MS) }

        // the line: the instruction, or the answer
        if (r != null && r.ok && r.pad != null) {
            val p = UiKit.bodyPaint(32f, Color.rgb(30, 255, 0)).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(Tr.t(if (r.fight) "In combat on" else "Bound to"), w / 2f - 60f, ly - (p.ascent() + p.descent()) / 2f, p)
            drawCombo(c, SpellsModel.combo(r.pad, st), w / 2f - 40f, ly, 64f, Theme.text, true)
        } else if (r != null && !r.ok) {
            val (text, colour) = when (r.why) {
                "combat" -> "Not while in combat" to Theme.unusable
                "reserved" -> "That button cannot be changed" to Theme.unusable
                "bind" -> "A game key rides that button: clear it first" to Theme.unusable        // b37: In combat
                "dpad bar" -> "The DS toolbar has the plain D-pad: hold a trigger" to Theme.unusable
                // Book b57: a stick with ZR / ZL + ZR, or on the In combat tab
                "layer" -> "Only on a button with no trigger, or with ${SpellsModel.triggerNames(st).first}" to Theme.warn
                "stick-normal" -> "L3 / R3: on the Normal tab" to Theme.warn
                "unsure" -> "Press it again" to Theme.warn
                "form-unknown" -> "Shift into that form once so the game shows its bar" to Theme.warn       // b44
                // PadMap m146 / Book b52 SMART RANGE: that button's box is on (the spell went to its half)
                "full" -> "All Character Macros are in use" to Theme.unusable
                "taken" -> "A macro of yours has that name" to Theme.unusable
                "long" -> "Too long for one macro" to Theme.unusable
                "same" -> "Already the other half" to Theme.unusable
                "swing" -> "Melee needs a next-swing attack" to Theme.unusable
                else -> "That did not work" to Theme.unusable
            }
            val p = UiKit.bodyPaint(30f, colour).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(text), w / 2f, ly - (p.ascent() + p.descent()) / 2f, p)
        } else if (now - undoneAt < SHOW_MS) {
            // b42: B put back what was there: the box waits for a button again
            val p = UiKit.bodyPaint(30f, Theme.warn).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t("Put back what was there. Press another button"), w / 2f, ly - (p.ascent() + p.descent()) / 2f, p)
        }

        // the spell
        if (e != null) {
            val pn = UiKit.bodyPaint(40f, Theme.gold)
            val bx = w / 2f - (pn.measureText(e.name) + 104f) / 2f
            UiKit.slot(c, Icons.get(e.icon), bx, spellTop, 84f)
            c.drawText(Tr.t(e.name), bx + 104f, spellTop + 42f - (pn.ascent() + pn.descent()) / 2f, pn)
        }

        // which set: the triggers held now (the app's own read-only controller reader, as the Cast room's)
        // once a button took the spell, the box shows THAT set with the button lit (SPEC 4: "ZR held" in the bound picture)
        val bound = r?.takeIf { it.ok }?.pad
        val set = bound?.bar ?: when { lt && rt -> 4; rt -> 3; lt -> 2; else -> 1 }
        // b-bind-combat-toggle, batch5 (the user's pick: "tabs ... instead of minus to flip make it l and r to toggle that tab left or right
        // and have the glyphs on the outsides of the buttons still in them"): Normal | In combat where "No trigger held" stood (removed),
        // the game's tab look, LB inside the left tab's outer edge and RB inside the right one's; LB / RB (the app's reader) or a tap. While
        // the answer shows, the tab it went on stays lit
        val f = if (r != null && r.ok) r.fight else fightShown()
        val cbOn = (r == null || !r.ok) && !confirming && cbShown()
        val nTabs = if (three()) 3 else 2
        UiKit.tab(c, fightRect(0, w), "Normal", !f && !cbOn)
        UiKit.tab(c, fightRect(1, w), "In combat", f && !cbOn)
        if (nTabs == 3) UiKit.tab(c, fightRect(2, w), "Cast bar", cbOn)
        if (r == null || !r.ok) {
            UiKit.pad(c, "${st.prefix}_lshoulder", fightRect(0, w).left + 40f, fightRect(0, w).centerY(), 44f)
            UiKit.pad(c, "${st.prefix}_rshoulder", fightRect(nTabs - 1, w).right - 40f, fightRect(nTabs - 1, w).centerY(), 44f)
        }
        // cb3: the Cast bar tab: the bar's 8 slots (cb6; their spells), the cursor's slot lit and showing the box's spell where it would go,
        // the slot it is on already glowing green (picking that one takes it off)
        if (cbOn) {
            val bar = CastStripModel.Live.bar
            val at = (ctl?.bindCb ?: 1) - 1
            val here = e?.let { bar?.slotOf(it.actionId, it.spellId) } ?: -1
            for (i in 0 until CastStripModel.SLOTS) {
                val sr = cbSlotRect(i, w, cy)
                val sl = bar?.slots?.getOrNull(i)
                val bmp = if (i == at && here != i) e?.let { Icons.get(it.icon) } else sl?.icon?.let { Icons.get(it) ?: UiKit.icon(it) }
                UiKit.slot(c, bmp, sr.left, sr.top, sr.width())
                if (i == here) UiKit.glow(c, RectF(sr).apply { inset(-10f, -10f) }, Color.rgb(60, 255, 60), 0.9f)
                if (i == at) UiKit.focus(c, RectF(sr).apply { inset(-6f, -6f) })
            }
            UiKit.hints(c, box.right - 80f, box.bottom - 92f, UiKit.okGlyph() to "Place", faceGlyph(st, "b") to "Back")
            return
        }

        // the D-pad's four and the face buttons' four: the Cast room's own drawing of that set; stand-ins until it has a layout
        val dpad = RectF(w / 2f - 270f - 240f, cy + 236f, w / 2f - 270f + 240f, cy + 236f + 340f)
        val face = RectF(w / 2f + 290f - 240f, cy + 236f, w / 2f + 290f + 240f, cy + 236f + 340f)
        val lit = r?.takeIf { it.ok }?.pad?.takeIf { it.bar == set }?.pos ?: -1
        val icon = e?.let { Icons.get(it.icon) }
        // batch8 (the user: "when on the in combat tab the spells should all change to show what's bound for in combat"): with In combat
        // on, each button shows its IN COMBAT spell for that set (the Controller room's In combat tab: the overlay's fight spell, a base X /
        // Y / A's; the gold swords on it; none = an empty slot), from the add-on's padmap; Normal shows the game's bar as before
        // Home's own drawing either way (tracking for the user: "identical look, only the spells and swords change"): CastView.drawSet
        // with each button's fight spell; the stand-ins only until the Cast room has its first layout
        val m = padMap
        // b43: a form chosen (the form row, Select) shows that form's own fight spells on the bare set (B never); none = an empty slot
        val form = ctl?.bindForm?.takeIf { f && set == 1 && m?.forms?.any { fo -> fo.id == it } == true }
        // b44 (the user's pick A): Normal, a form chosen, the set the game lays its form bar over = that form's own bar (empty = empty)
        val nForm = ctl?.bindForm?.takeIf { !f && m?.stance?.set == set && m.forms.any { fo -> fo.id == it } }
        val fight: ((Int) -> Int?)? = if (f && m != null) { p ->
            if (form != null && p != 7) PadMapModel.formFight(m, form, p)?.icon else fightIcon(m, set, p)
        } else if (nForm != null && m != null) { p -> m.formBars.firstOrNull { it.form == nForm && it.pos == p }?.icon }
        else null
        if (m != null && m.forms.isNotEmpty() && (f || m.stance != null)) formRow(c, w, box, m, b, st)
        val drawn = try { drawSet(c, set, dpad, face, lit.takeIf { it in 1..8 }, icon, fight) } catch (_: Throwable) { false }
        if (!drawn) standIns(c, dpad, face, lit, icon, st)

        // the way back: B (the user on batch4: "instead of showing the - button as back when no triggers are held it should show B"; a
        // plain B backs out, Book b39); the "-" flips Normal / In combat. The triggers' glyphs stand bare ("the text on the ZL and ZR can be
        // removed")
        // batch5 (the user: "remove the ZR/ZL glyphs in the bottom right and the B button glyph and text when holding a trigger at all"):
        // no trigger glyphs here; B / Back only while no trigger is held (with one, B is a place for the spell)
        // batch7 (the user: "we need to add ZL and ZR as glyphs to the bottom middle that get lit up when held like on the home screen"):
        // Home's trigger chips (CastView's strip: UiKit.triggerChip, its glyph set and size), lit while the app's reader sees them held
        val tset = when (CastSettings.buttonLayout) {
            CastSettings.ButtonLayout.XBOX -> "gen"; CastSettings.ButtonLayout.PLAYSTATION -> "shp"; else -> "rev"
        }
        val tcy = box.bottom - 92f
        UiKit.triggerChip(c, RectF(w / 2f - 64f - 42f, tcy - 28f, w / 2f - 64f + 42f, tcy + 28f), "${tset}_ltrigger", lt)
        UiKit.triggerChip(c, RectF(w / 2f + 64f - 42f, tcy - 28f, w / 2f + 64f + 42f, tcy + 28f), "${tset}_rtrigger", rt)
        if (!f && (set == 1 || set == 2)) sticks(c, w, tcy, m, set, lit, icon)        // b57: L3 / R3 beside the chips
        val back = faceGlyph(st, "b")
        if (confirming && r != null && r.ok) {
            // b42 (the user: "a confirm and it closes, b removes it and let's you keep binding"): the game's own question look (its
            // StaticPopup), over the box's foot: Keep / Undo
            val qb = RectF(w / 2f - 330f, box.bottom - 196f, w / 2f + 330f, box.bottom - 24f)
            UiKit.dialog(c, qb)
            val qp = UiKit.bodyPaint(32f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            val failed = undoFail?.takeIf { now - undoFailAt < SHOW_MS }
            val q = when (failed) { null -> "Keep this binding?"; "combat" -> "Can't undo in combat. Keep it?"; else -> "That couldn't be undone. Keep it?" }
            c.drawText(Tr.t(q), qb.centerX(), qb.top + 64f, qp)
            UiKit.hints(c, qb.centerX() + 180f, qb.bottom - 46f, UiKit.okGlyph() to "Keep", back to "Undo")
            if (failed != null) postInvalidateDelayed(250L)
            return
        }
        if (r != null && r.ok) UiKit.hints(c, box.right - 80f, box.bottom - 92f, back to "Close")
        else if (!lt && !rt) UiKit.hints(c, box.right - 80f, box.bottom - 92f, back to "Back")
    }

    /**
     * b43 (the user on test20: "We need to add this to the actual bind pop up in the spell book", "We can use the select button to cycle these
     * tabs"): the In combat tab's FORM ROW along the box's top: the class's icon (the caster's set), then each form's own icon; the chosen one
     * lit; Select (its glyph first) cycles them (Book b43). An In combat bind goes to the chosen form's own set
     */
    private fun formRow(c: Canvas, w: Float, box: RectF, m: PadMapModel.Map, b: SpellsModel.Book?, st: SpellsModel.PadStyle) {
        val list = listOf<PadMapModel.Form?>(null) + m.forms.take(9)
        formHits.clear()
        val chosen = ctl?.bindForm ?: 0
        val sz = 46f; val step = 54f
        val total = 56f + list.size * step
        var x = w / 2f - total / 2f
        val cy = box.top + 142f                                         // under the Normal / In combat tabs
        UiKit.pad(c, selectGlyph(st), x + 22f, cy, 40f)
        x += 56f
        val classBmp = b?.lines?.firstOrNull { it.index == 1 }?.let { Icons.get(it.icon) }
        for (fo in list) {
            val r = RectF(x, cy - sz / 2f, x + sz, cy + sz / 2f)
            UiKit.choice(c, r, if (fo == null && classBmp == null) "C" else "", (fo?.id ?: 0) == chosen)
            (if (fo == null) classBmp else Icons.get(fo.icon))?.let { bmp ->
                val i = sz - 10f
                c.drawBitmap(bmp, null, RectF(r.centerX() - i / 2f, r.centerY() - i / 2f, r.centerX() + i / 2f, r.centerY() + i / 2f), null)
            }
            if ((fo?.id ?: 0) == chosen) UiKit.focus(c, RectF(r).apply { inset(-3f, -3f) })     // the chosen set, plainly lit
            formHits.add(RectF(r).apply { inset(-5f, -5f) })
            x += step
        }
    }

    /** Book b57: stick [p]'s tile, beside the trigger chips on their line [cy] (L3 left of ZL, R3 right of ZR) */
    fun stickRect(p: Int, w: Float, cy: Float): RectF {
        val cx = if (p == PadMapModel.POS_L3) w / 2f - STICK_DX else w / 2f + STICK_DX
        return RectF(cx - STICK_S / 2f, cy - STICK_S / 2f, cx + STICK_S / 2f, cy + STICK_S / 2f)
    }

    /** Book b57: L3 / R3 in [set] (1 / 2): what each holds now (the padmap's sticks), the one that took the spell lit with it */
    private fun sticks(c: Canvas, w: Float, cy: Float, m: PadMapModel.Map?, set: Int, lit: Int, litIcon: android.graphics.Bitmap?) {
        for (p in PadMapModel.STICKS) {
            val r = stickRect(p, w, cy)
            val isLit = p == lit
            val sl = m?.let { PadMapModel.slot(it, set, p) }?.takeIf { it.kind != PadMapModel.Kind.NONE }
            UiKit.slot(c, if (isLit) litIcon else sl?.let { Icons.get(it.icon) }, r.left, r.top, r.width())
            if (isLit) UiKit.glow(c, RectF(r).apply { inset(-8f, -8f) }, Color.rgb(60, 255, 60), 0.9f)
            UiKit.pad(c, PadMapModel.stickGlyph(p), r.right - 8f, r.top + 8f, 32f)
        }
    }

    /** b45: the form row's places as drawn (index = 0 the class icon, k the k-th form), for a finger */
    private val formHits = ArrayList<RectF>()

    /** empty slots with the glyphs, where the Cast room's drawing goes, for as long as it has none */
    private fun standIns(c: Canvas, dpad: RectF, face: RectF, lit: Int, icon: android.graphics.Bitmap?, st: SpellsModel.PadStyle) {
        cross(c, dpad, intArrayOf(1, 2, 3, 4), arrayOf("gen_left", "gen_up", "gen_right", "gen_down"), lit, icon)
        // face buttons by PHYSICAL place (left, top, right, bottom), as the Cast room draws them: on a Nintendo pad the game hears the
        // button printed Y (left) as its top key, X (top) as its left key, A (right) as its bottom key, B (bottom) as its right key
        // f-abxy-split: the places follow the ABXY LOCATION; each wears its ACTION's glyph in the chosen set (faceGlyph)
        val positions = if (CastSettings.faceSwap) intArrayOf(6, 5, 8, 7) else intArrayOf(5, 6, 7, 8)
        val keys = Array(4) { mapOf(5 to "x", 6 to "y", 7 to "b", 8 to "a").getValue(positions[it]) }
        val faceGlyphs = Array(4) { faceGlyph(st, keys[it]) }
        cross(c, face, positions, faceGlyphs, lit, icon)
    }

    /** batch8: button [p]'s IN COMBAT spell icon in [set] (PadTabPage's In combat rule: the overlay's fight spell, a base X / Y / A's) */
    private fun fightIcon(m: PadMapModel.Map, set: Int, p: Int): Int? {
        val sl = PadMapModel.slot(m, set, p) ?: return null
        return when {
            sl.kind == PadMapModel.Kind.COMBAT && sl.ov -> sl.combat?.icon
            sl.fight != null && PadMapModel.isFace(sl) -> sl.fight.icon
            else -> null
        }
    }

    /** the Select button in the chosen set's look (UI Art: Nintendo minus, Xbox View, PlayStation Share / Create) */
    private fun selectGlyph(st: SpellsModel.PadStyle) = when (st) {
        SpellsModel.PadStyle.REV -> "rev_minus"
        SpellsModel.PadStyle.LTR -> "ltr_view"
        SpellsModel.PadStyle.SHP -> "shp_share"
    }

    private fun faceGlyph(st: SpellsModel.PadStyle, key: String) =
        if (st == SpellsModel.PadStyle.SHP) "shp_" + mapOf("x" to "square", "y" to "triangle", "b" to "circle", "a" to "cross").getValue(key)
        else "${st.prefix}_$key"

    /** four slots as a cross: left, top, right, bottom. [positions] = the game's position of each; [lit] = the one that took the spell */
    private fun cross(c: Canvas, r: RectF, positions: IntArray, glyphs: Array<String>, lit: Int, icon: android.graphics.Bitmap?) {
        val s = 124f
        val cx = r.centerX(); val cy = r.centerY()
        val at = arrayOf(floatArrayOf(cx - 1.5f * s - 8f, cy - s / 2f), floatArrayOf(cx - s / 2f, cy - s - 12f),
            floatArrayOf(cx + s / 2f + 8f, cy - s / 2f), floatArrayOf(cx - s / 2f, cy + 12f))
        for (i in 0..3) {
            val x = at[i][0]; val y = at[i][1]
            val isLit = positions[i] == lit
            UiKit.slot(c, if (isLit) icon else null, x, y, s)
            if (isLit) UiKit.glow(c, RectF(x - 10f, y - 10f, x + s + 10f, y + s + 10f), Color.rgb(60, 255, 60), 0.9f)
            UiKit.pad(c, glyphs[i], x + s - 14f, y + 14f, 50f)
        }
    }

    /** a combination with 0, 1 or 2 triggers (LT + RT: the first trigger drawn 62 px before the combination, SPEC 2.4) */
    private fun drawCombo(c: Canvas, combo: SpellsModel.Combo, x0: Float, cy: Float, size: Float, colour: Int, shadow: Boolean): Float {
        var x = x0
        val trigger = when (combo.triggers.size) {
            2 -> { UiKit.pad(c, combo.triggers[0], x + size / 2f, cy, size); x += 62f; combo.triggers[1] }
            1 -> combo.triggers[0]
            else -> null
        }
        return UiKit.padCombo(c, trigger, combo.button, x, cy, size, colour, shadow)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (boxOpen()) {                                            // a dialog: every touch is its
            // b37: the Normal / In combat pair, while the box waits: ONE tap on its column (no-action, idempotent)
            val w = width.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downFight = (0 until if (three()) 3 else 2).firstOrNull { fightRect(it, w).contains(ev.x, ev.y) } ?: -1
                    downSlot = if (cbShown()) (0 until CastStripModel.SLOTS).firstOrNull { cbSlotRect(it, w, CB_CY).contains(ev.x, ev.y) } ?: -1 else -1
                }
                MotionEvent.ACTION_UP -> {
                    val i = downFight; downFight = -1
                    val k0 = downSlot; downSlot = -1
                    val cols = fightCols()
                    // cb3: a finger on a Cast bar slot: the box's spell goes there (padtab 39 then 20 + slot, Book b56)
                    if (k0 >= 0 && cbSlotRect(k0, w, CB_CY).contains(ev.x, ev.y) && ctl?.mode == "bind")
                        CastStripModel.slotCells(k0)?.let { onCastBarTaps(it) }
                    if (i == 2 && fightRect(2, w).contains(ev.x, ev.y) && ctl?.mode == "bind") pickCb()
                    else if (i in 0..1 && fightRect(i, w).contains(ev.x, ev.y) && cols != null && ctl?.mode == "bind") {
                        if (cbShown()) leaveCb()                            // bf-on / bf-off leave the Cast bar tab (Book b56)
                        pickFight(i == 1)
                    }
                    // b45 (a finger picks a form): the row's place k -> the spells row's "vo<k>" (Book b45 reads a lone one as the form)
                    val k = formHits.indexOfFirst { it.contains(ev.x, ev.y) }
                    if (k >= 0 && ctl?.mode == "bind") book?.win?.get("vo$k")?.let { onTap(it) }
                }
                MotionEvent.ACTION_CANCEL -> { downFight = -1; downSlot = -1 }
            }
            return true
        }
        return false                                                // no box: the room's
    }

    override fun onDetachedFromWindow() {
        stopPad()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val SHOW_MS = 1500L
        /** the box's top (84) + 104: the line the Cast bar tab's slots stand from ([cbSlotRect]) */
        private const val CB_CY = 84f + 104f
        /** cb6: the Cast bar tab's slot, the gap in a group of 4 and the gap between the two groups (8 in one row) */
        private const val CB_SLOT = 110f
        private const val CB_GAP = 14f
        private const val CB_MID = 36f
        /** Book b57b: the stick tiles' side and their centres' distance from the box's middle (the chips reach 106 from it) */
        const val STICK_S = 64f
        const val STICK_DX = 158f
    }
}
