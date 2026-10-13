package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

/**
 * THE TALENTS ROOM (spells session), a page of the Spellbook room: UI Art's SPEC (wow-companion-design/talents/SPEC.md), built
 * with its kit calls and its numbers. The user's picks 2026-09-30: "A, one school at a time", "See and learn".
 *
 * The school tabs across the top, ONE school's talents on its own picture at finger size (84 px on a 160 x 116 pitch, six rows at
 * once, a longer school scrolls), the chosen talent's detail on the right with its one action pair, Undo / Reset / Apply at the foot,
 * and the two boxes (Apply, Undo) over the room. Every name, rank and text is the game's (the addon's `talents` / `talentsel`).
 *
 * WHO DECIDES WHAT: the addon holds the selection and the school on screen (a touch pick is a no-action tap it echoes; Add / Remove
 * wait for that echo, the pad owner's guard); the controller's place and its boxes are the addon's (`talentsctl`); a box opened by
 * TOUCH is this view's own until its A (the one game action) or its B. Taps go out through [onTap] (row, column).
 */
class TalentsView(
    context: Context,
    private val onTap: (row: String, col: Int) -> Unit,
) : View(context), BLeadsHome {

    private var tree: TalentsModel.Tree? = null
    private var detail: TalentsModel.Detail? = null
    private var ctl: TalentsModel.Ctl? = null
    /** the controller drives this page (the Spells room's guest holds it, on the Talents page) */
    var held = false
        set(v) { field = v; invalidate() }

    /** RC3 (Spells' condition, TalentsCtl.press's leaveB): not while the Apply / Undo box is up (B cancels it) */
    override fun bLeadsHome(): Boolean = held && ctl?.box == null

    private var school = 1                      // shown; the addon's echo (`sch`) wins when it comes
    private var touched: Int? = null            // a talent a finger chose, until the addon echoes it
    private var touchBox: String? = null        // "apply" / "undo" opened by touch
    private var applying = false                // an Apply sent by touch, until the game's verdict
    private var scroll = 0f

    // ------------------------------------------------------------------------------------------ the documents

    fun update(t: TalentsModel.Tree) {
        tree = t
        if (touched != null && TalentsModel.find(t, touched!!) == null) touched = null
        clampScroll()
        invalidate()
    }

    fun onDetail(d: TalentsModel.Detail) {
        detail = d
        d.school?.let { school = it }
        if (touched != null && d.id == touched) touched = null
        followCursor()
        invalidate()
    }

    fun onCtl(c: TalentsModel.Ctl) {
        ctl = c
        invalidate()
    }

    fun onAck(a: TalentsModel.Ack) {
        if (a.op == "applied" || (a.op == "apply" && !a.ok)) applying = false
        invalidate()
    }

    /** the page comes up or goes: nothing a finger left half done stays */
    fun reset() {
        touchBox = null; touched = null; applying = false
        invalidate()
    }

    // ------------------------------------------------------------------------------------------ the numbers (SPEC 2)

    private val size = 84f
    private val split = 700f
    private fun nx(col: Int) = 112f + col * 160f
    private fun ny(row: Int) = 230f + row * 116f - scroll
    private fun ground() = RectF(10f, 158f, split - 10f, height - 6f)
    // NO DOOR (the user 2026-09-30: Talents has its own button on the bar): the school tabs span the row again (SPEC 2 as first written)
    private fun tabRect(i: Int): RectF {
        val tw = (width - 40f) / 3f
        return RectF(12f + i * (tw + 8f), 72f, 12f + i * (tw + 8f) + tw, 146f)
    }
    private val specRects = listOf(RectF(330f, 8f, 500f, 54f), RectF(512f, 8f, 762f, 54f))
    private fun x0() = split + 26f
    private fun x1() = width - 22f
    private fun addRect() = RectF(x0(), 620f, x0() + 245f, 720f)
    private fun removeRect() = RectF(x1() - 245f, 620f, x1(), 720f)
    private fun fy() = height - 118f
    private fun undoRect() = RectF(x0(), fy(), x0() + 100f, fy() + 100f)
    private fun resetRect() = RectF(x0() + 112f, fy(), x0() + 212f, fy() + 100f)
    private fun applyRect() = RectF(x1() - 260f, fy(), x1(), fy() + 100f)

    private fun rows(): Int = tree?.let { TalentsModel.rows(it, school) } ?: 0
    private fun maxScroll(): Float = ((rows() - 6).coerceAtLeast(0) * 116f)
    private fun clampScroll() { scroll = scroll.coerceIn(0f, maxScroll()) }

    /** keep the cursor's row in view (SPEC 6: "The grid scrolls to keep the cursor's row in view") */
    private fun followCursor() {
        val t = tree ?: return
        val n = cursorId()?.let { TalentsModel.find(t, it) } ?: return
        val r = n.row ?: return
        val top = r * 116f
        if (top < scroll) scroll = top
        if (top > scroll + 5 * 116f) scroll = top - 5 * 116f
        clampScroll()
    }

    // ------------------------------------------------------------------------------------------ what may be done now

    /** the game's talent button is still disabled (no talent point earned): the whole page is greyed and answers nothing */
    private fun low() = tree?.usable == false
    private fun busy() = applying || (ctl?.busy == true)
    private fun blocked() = tree?.combat == true || tree?.editable == false || busy()
    private fun cursorId(): Int? = touched ?: detail?.id
    private fun cursorNode(): TalentsModel.Node? = tree?.let { t -> cursorId()?.let { TalentsModel.find(t, it) } }
    /** Add / Remove wait for the echo of the talent a finger chose (the pad owner's guard) */
    private fun echoedHere(): Boolean = touched == null && detail?.id != null && detail?.school == school
    private fun canAdd(): Boolean = !blocked() && !low() && echoedHere() && cursorNode()?.canAdd == true
    private fun canRemove(): Boolean = !blocked() && echoedHere() && cursorNode()?.pending == '+'
    private fun staged() = tree?.staged == true
    private fun canUndo() = !blocked() && staged()
    private fun canReset() = !blocked() && !low() && (tree?.pool?.spent ?: 0) > 0
    private fun canApply() = !blocked() && staged()
    private fun box(): String? = ctl?.box ?: touchBox

    private fun style(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }
    /** the glyph of a face button by the game's position: 5 left (X), 6 top (Y), 7 right (B), 8 bottom (A) */
    private fun glyph(pos: Int) = SpellsModel.combo(SpellsModel.PadSlot(0, 1, 1, pos), style()).button

    private fun state(ok: Boolean) = if (ok) UiKit.Button.UP else UiKit.Button.DISABLED

    // ------------------------------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Theme.ink)
        UiKit.header(c, w, "Talents")
        val t = tree
        val specGroups = t?.specGroups ?: 1
        val focus = if (held && !low()) ctl?.focus else null
        val specAt = ((ctl?.spec ?: 1) - 1).coerceIn(0, 1)
        UiKit.specTab(c, specRects[0], "Primary", active = (t?.specGroup ?: 1) == 1, cursor = focus == "specs" && specAt == 0)
        UiKit.specTab(c, specRects[1], "Secondary", active = t?.specGroup == 2, locked = specGroups < 2, cursor = focus == "specs" && specAt == 1)
        UiKit.talentPool(c, w - 16f, 31f, if (low()) 0 else t?.pool?.have ?: 0)

        for (i in 0 until 3) {
            val s = t?.schools?.getOrNull(i)
            UiKit.schoolTab(c, tabRect(i), s?.let { Icons.get(it.icon) }, s?.name ?: "", if (low()) 0 else s?.spent ?: 0, i + 1 == school,
                cursor = focus == "schools" && (ctl?.tab ?: 1) == i + 1)
        }

        val g = ground()
        UiKit.talentGround(c, g, (t?.cls ?: "").lowercase(), TalentsModel.schoolPicture(t?.cls, school) ?: "")
        if (t == null) {
            UiKit.searching(c, g.centerX(), g.centerY() - 120f, "Waiting for the game")
        } else {
            c.save(); c.clipRect(g)
            for ((a, b, lit) in TalentsModel.arrows(t, school)) {
                UiKit.talentArrow(c, nx(a.col!!), ny(a.row!!), nx(b.col!!), ny(b.row!!), size, lit)
            }
            val cursor = cursorId()
            for (n in TalentsModel.school(t, school)) {
                val cy = ny(n.row!!)
                // SPEC 2: "Draw a talent only while its square is inside ground"
                if (cy - size / 2f < g.top || cy + size / 2f > g.bottom) continue
                val st = when {
                    low() -> UiKit.Talent.LOCKED
                    n.pending == '+' -> UiKit.Talent.STAGED
                    else -> when (n.state) {
                        TalentsModel.State.FULL -> UiKit.Talent.FULL
                        TalentsModel.State.PART -> UiKit.Talent.PART
                        TalentsModel.State.OPEN -> UiKit.Talent.OPEN
                        TalentsModel.State.LOCKED -> UiKit.Talent.LOCKED
                        TalentsModel.State.GREY -> UiKit.Talent.GREY
                    }
                }
                // the room's ONE cursor: on the grid only (UI Art's sample: not while the tabs or the foot hold it), none below level 10
                val chosen = n.id == cursor && !low() && (focus == null || focus == "grid")
                UiKit.talentNode(c, nx(n.col!!), cy, size, Icons.get(n.icon), if (low()) 0 else n.rank, n.max, st, chosen)
            }
            c.restore()
            val rs = rows()
            if (rs > 6) UiKit.scrollBar(c, RectF(split - 30f, 170f, split - 16f, h - 18f), scroll / (rs * 116f), (scroll + 6 * 116f) / (rs * 116f))
        }

        UiKit.pane(c, RectF(split, 158f, w, h), right = true)
        UiKit.paneDivider(c, split, 158f, h)
        if (t != null && !low()) detailPane(c)
        foot(c, focus)
        if (low()) lockedCover(c, w, h)

        when (box()) {
            "apply" -> applyBox(c, w, h)
            "undo" -> undoBox(c, w, h)
        }
    }

    /**
     * NOT OPEN YET (the user 2026-09-30, level 6 Shaman: "they should just be Greyed out until its actually available in the game otherwise
     * its confusing"): the page under the header greyed as the game's dialogs grey what is behind them, and the game's own line in the middle.
     * No cursor is drawn and no touch answers; the controller's presses are swallowed by the addon, B still leads Home.
     */
    private fun lockedCover(c: Canvas, w: Float, h: Float) {
        UiKit.cover(c, RectF(0f, 62f, w, h), 0.7f)
        val t = tree
        val line = t?.lockText ?: "This feature becomes available at level ${t?.unlockLevel ?: 10}."
        val p = UiKit.bodyPaint(34f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        while (p.measureText(line) > w - 120f && p.textSize > 24f) p.textSize -= 1f
        UiKit.tex("talent_lock")?.let { c.drawBitmap(it, null, RectF(w / 2f - 27f, h / 2f - 120f, w / 2f + 27f, h / 2f - 42f), Paint(Paint.FILTER_BITMAP_FLAG)) }
        c.drawText(line, w / 2f, h / 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, width: Float, p: Paint): Float {
        val sl = StaticLayout.Builder.obtain(s, 0, s.length, TextPaint(p), width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
        c.save(); c.translate(x, y); sl.draw(c); c.restore()
        return y + sl.height
    }

    private fun detailPane(c: Canvas) {
        val x0 = x0(); val x1 = x1()
        val d = detail
        val n = cursorNode()
        if (d == null || d.id == null || n == null) return
        Icons.get(n.icon)?.let { c.drawBitmap(it, null, RectF(x0, 178f, x0 + 80f, 258f), Paint(Paint.FILTER_BITMAP_FLAG)) }
        val pn = UiKit.bodyPaint(30f, Color.WHITE)
        val name = TextUtils.ellipsize(n.name ?: "", pn, x1 - x0 - 100f, TextUtils.TruncateAt.END).toString()
        c.drawText(name, x0 + 100f, 200f - (pn.ascent() + pn.descent()) / 2f, pn)
        val notApplied = if (n.pending == '+') n.rank - (n.active ?: 0) else 0
        val pr = UiKit.bodyPaint(22f, if (notApplied > 0) Color.rgb(30, 255, 0) else Theme.gold)
        val rank = "Rank ${n.rank}/${n.max}" + if (notApplied > 0) "  ($notApplied not applied yet)" else ""
        c.drawText(rank, x0 + 100f, 236f - (pr.ascent() + pr.descent()) / 2f, pr)
        // the texts are the selected talent's, from the addon's echo: shown once it is THIS talent's
        if (d.id == n.id) {
            var y = 288f
            d.text?.let { y = text(c, it, x0, y, x1 - x0, UiKit.bodyPaint(24f)) + 18f }
            d.next?.let {
                y = text(c, "Next rank:", x0, y, x1 - x0, UiKit.bodyPaint(23f, Theme.gold)) + 4f
                y = text(c, it, x0, y, x1 - x0, UiKit.bodyPaint(24f)) + 18f
            }
            // an unmet requirement in the game's red (its talent tooltip's), a met one dim
            for (need in d.needs) y = text(c, need.text, x0, y, x1 - x0,
                UiKit.bodyPaint(21f, if (need.met) Theme.dim else Color.rgb(255, 32, 32))) + 6f
        }
        UiKit.button(c, addRect(), "Add Point", glyph(8), press.state("add", canAdd()))
        UiKit.button(c, removeRect(), "Remove", glyph(6), press.state("remove", canRemove()))
        if (tree?.combat == true) {
            val p = UiKit.bodyPaint(22f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText("Not in combat", (x0 + x1) / 2f, 748f, p)
        }
    }


    private fun foot(c: Canvas, focus: String?) {
        UiKit.iconButton(c, undoRect(), "talent_undo", press.state("undo", canUndo()))
        UiKit.iconButton(c, resetRect(), "talent_reset", press.state("reset", canReset()))
        UiKit.button(c, applyRect(), "Apply", glyph(5), press.state("apply", canApply()))
        if (focus == "foot") UiKit.focus(c, listOf(undoRect(), resetRect(), applyRect())[((ctl?.foot ?: 1) - 1).coerceIn(0, 2)])
    }

    private fun boxButtons(r: RectF): Pair<RectF, RectF> {
        val cx = width / 2f
        val top = r.bottom - 50f - 111f
        return RectF(cx - 14f - 380f, top, cx - 14f, top + 111f) to RectF(cx + 14f, top, cx + 14f + 380f, top + 111f)
    }

    private fun applyBoxRect(lines: Int) = RectF((width - 1040f) / 2f, 150f, (width + 1040f) / 2f, 150f + 250f + lines * 52f + 150f)
    private fun undoBoxRect() = RectF((width - 1040f) / 2f, 230f, (width + 1040f) / 2f, 600f)

    /** the Apply box (SPEC 4): one line per changed talent in the tree's order, at most 6 then "and n more", the points */
    private fun applyBox(c: Canvas, w: Float, h: Float) {
        val t = tree ?: return
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val all = TalentsModel.changes(t).sortedWith(compareBy(
            { TalentsModel.find(t, it.id)?.school ?: 9 }, { TalentsModel.find(t, it.id)?.row ?: 99 }, { TalentsModel.find(t, it.id)?.col ?: 9 }))
        val shown = all.take(6)
        val lines = shown.size + if (all.size > 6) 1 else 0
        val r = applyBoxRect(lines)
        UiKit.dialog(c, r)
        val pt = UiKit.bodyPaint(36f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText("Apply these talent changes?", w / 2f, r.top + 100f, pt)
        var y = r.top + 170f
        val pl = UiKit.bodyPaint(28f, Theme.text)
        val pv = UiKit.numberPaint(28f, Color.rgb(30, 255, 0)).apply { textAlign = Paint.Align.RIGHT }
        for (ch in shown) {
            val name = TextUtils.ellipsize(ch.name, pl, r.width() - 340f - 160f, TextUtils.TruncateAt.END).toString()
            c.drawText(name, r.left + 170f, y - (pl.ascent() + pl.descent()) / 2f, pl)
            c.drawText("${ch.from}  >  ${ch.to}", r.right - 170f, y - (pv.ascent() + pv.descent()) / 2f, pv)
            y += 52f
        }
        if (all.size > 6) {
            c.drawText("and ${all.size - 6} more", r.left + 170f, y - (pl.ascent() + pl.descent()) / 2f, pl)
            y += 52f
        }
        val spent = all.sumOf { it.to - it.from }.coerceAtLeast(0)
        val left = t.pool?.have ?: 0
        val pd = UiKit.bodyPaint(24f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        c.drawText("$spent ${if (spent == 1) "point" else "points"} spent, $left left unspent", w / 2f, y + 18f, pd)
        val (a, b) = boxButtons(r)
        UiKit.button(c, a, "Apply", glyph(8), press.state("boxA"))
        UiKit.button(c, b, "Cancel", glyph(7), press.state("boxB"))
    }

    /** the Undo box, in the game's own words (TALENT_FRAME_CONFIRM_CLOSE, CONTINUE / CANCEL) */
    private fun undoBox(c: Canvas, w: Float, h: Float) {
        val t = tree ?: return
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val r = undoBoxRect()
        UiKit.dialog(c, r)
        val pt = UiKit.bodyPaint(32f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText("You will lose any pending changes if you continue.", w / 2f, r.top + 110f, pt)
        val names = t.nodes.filter { it.pending != null }.mapNotNull { it.name }.joinToString(", ")
        val pd = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        c.drawText(TextUtils.ellipsize(names, pd, r.width() - 120f, TextUtils.TruncateAt.END).toString(), w / 2f, r.top + 168f, pd)
        val (a, b) = boxButtons(r)
        UiKit.button(c, a, "Continue", glyph(8), press.state("boxA"))
        UiKit.button(c, b, "Cancel", glyph(7), press.state("boxB"))
    }

    // ------------------------------------------------------------------------------------------ touch

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    // PRESS FEEDBACK (the user 2026-10-07, Interact's recipe): a button a finger holds is DOWN, and stays DOWN 160 ms after the tap;
    // the controller's press pushes in the button it works AT ONCE (TalentsCtl's keys: on the grid A Add Point, Y Remove; on the foot
    // A the chosen one; in a box A its yes, B Cancel). Display only.
    private val press = PressFeedback(this).also { it.padTarget = { key -> padButton(key) } }

    private fun padButton(key: String): String? {
        val c = ctl ?: return null
        if (!held || low()) return null
        if (box() != null) return when (key) { "PAD1" -> "boxA"; "PAD2" -> "boxB"; else -> null }
        return when (c.focus) {
            "grid" -> when (key) { "PAD1" -> if (canAdd()) "add" else null; "PAD4" -> if (canRemove()) "remove" else null; else -> null }
            "foot" -> if (key != "PAD1") null else when (c.foot) {
                1 -> if (canUndo()) "undo" else null
                2 -> if (canReset()) "reset" else null
                else -> if (canApply()) "apply" else null
            }
            else -> null
        }
    }

    /** the button under a finger (a box's two, or the page's five when they can act), or null */
    private fun buttonAt(x: Float, y: Float): String? {
        val t = tree ?: return null
        if (low()) return null
        box()?.let { which ->
            val n = TalentsModel.changes(t).size
            val r = if (which == "apply") applyBoxRect(n.coerceAtMost(6) + if (n > 6) 1 else 0) else undoBoxRect()
            val (a, b) = boxButtons(r)
            return if (a.contains(x, y)) "boxA" else if (b.contains(x, y)) "boxB" else null
        }
        return when {
            addRect().contains(x, y) && canAdd() -> "add"
            removeRect().contains(x, y) && canRemove() -> "remove"
            undoRect().contains(x, y) && canUndo() -> "undo"
            resetRect().contains(x, y) && canReset() -> "reset"
            applyRect().contains(x, y) && canApply() -> "apply"
            else -> null
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastY = e.y; dragging = false; press.finger(buttonAt(e.x, e.y)); return true }
            MotionEvent.ACTION_MOVE -> {
                if (box() == null && !low() && ground().contains(downX, downY) && (dragging || kotlin.math.abs(e.y - downY) > 24f)) {
                    dragging = true
                    scroll -= e.y - lastY
                    clampScroll()
                    invalidate()
                }
                if (dragging || kotlin.math.abs(e.x - downX) > 24f || kotlin.math.abs(e.y - downY) > 24f) press.finger(null)
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val on = buttonAt(e.x, e.y)
                press.lift(!dragging && on != null)
                if (!dragging) tap(e.x, e.y)
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { press.finger(null); dragging = false; return true }
        }
        return super.onTouchEvent(e)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        press.setActive(isShown)
    }

    override fun onDetachedFromWindow() {
        press.setActive(false)
        super.onDetachedFromWindow()
    }

    private fun tap(x: Float, y: Float) {
        val t = tree ?: return
        if (low()) return                       // greyed: nothing on the page answers
        // a box over the room: only its two buttons answer
        box()?.let { which ->
            val r = if (which == "apply") applyBoxRect(TalentsModel.changes(t).size.coerceAtMost(6) + if (TalentsModel.changes(t).size > 6) 1 else 0)
                    else undoBoxRect()
            val (a, b) = boxButtons(r)
            if (a.contains(x, y)) {
                if (which == "apply") { applying = true; onTap(TalentsModel.ROW_ACTIONS, TalentsModel.PAD_APPLY) }
                else onTap(TalentsModel.ROW_ACTIONS, TalentsModel.PAD_UNDO)
                touchBox = null
            } else if (b.contains(x, y)) {
                if (ctl?.box != null) onTap(TalentsModel.ROW_ACTIONS, TalentsModel.PAD_CLOSE_BOX)
                touchBox = null
            }
            invalidate()
            return
        }
        for (i in 0 until 3) if (tabRect(i).contains(x, y) && i < t.schools.size) {
            if (school != i + 1) { school = i + 1; scroll = 0f; touched = null }
            onTap(TalentsModel.ROW_ACTIONS, TalentsModel.PAD_SCHOOL_1 + i)
            invalidate()
            return
        }
        val g = ground()
        if (g.contains(x, y)) {
            for (n in TalentsModel.school(t, school)) {
                val cx = nx(n.col!!); val cy = ny(n.row!!)
                if (x in cx - size / 2f - 20f..cx + size / 2f + 20f && y in cy - size / 2f - 16f..cy + size / 2f + 16f) {
                    val cell = TalentsModel.cell(n.col, n.row) ?: return
                    touched = n.id
                    onTap(TalentsModel.ROW_CELLS, cell)
                    invalidate()
                    return
                }
            }
            return
        }
        when {
            addRect().contains(x, y) && canAdd() -> onTap(TalentsModel.ROW_ACTIONS, TalentsModel.PAD_ADD)
            removeRect().contains(x, y) && canRemove() -> onTap(TalentsModel.ROW_ACTIONS, TalentsModel.PAD_REMOVE)
            undoRect().contains(x, y) && canUndo() -> touchBox = "undo"
            resetRect().contains(x, y) && canReset() -> onTap(TalentsModel.ROW_ACTIONS, TalentsModel.PAD_RESET)
            applyRect().contains(x, y) && canApply() -> touchBox = "apply"
        }
        invalidate()
    }
}
