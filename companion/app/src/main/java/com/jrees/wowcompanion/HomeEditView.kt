package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MOVABLE HOME, PHASE 2: EDIT MODE'S CHROME (SPEC.txt sections 3, 4, 7, 8; final_02 .. final_06, final_09). A child of [HomeView] over every
 * part, the frame's size: Home stays LIVE and FULL SIZE under it (D6) - what you edit is what you get, touch targets keep their size.
 *
 *  - every widget that is not selected is dimmed (black 70 / 255) with a soft dashed ink frame and its terse tag; the riders inside a
 *    widget (the clock in the band, the quest card on the map) a lighter inner frame; the party module (Party mode) a dashed frame only
 *    (it follows the chat: never a stop);
 *  - the selected widget wears the GOLDEN BOX (the app's cursor look: a thick gold box with its glow, never a thin outline), at once;
 *    lifted, the kit's gold arrows point at the docks it can reach, and a finger's drop well shows the dock under it (red: refused);
 *  - the divider's handle between the columns, the red X chip on a selected removable widget ("Remove?" first);
 *  - a widget's menu over the other column, the Layouts sheet, the Add tray, the Save / Discard / Keep editing prompt;
 *  - THE EDIT RIBBON in the XP bar's place (drawn by the Strip through [drawRibbon]): "Edit Home", the layout chip "< Classic >" with the
 *    kit's gold arrows ("Edited" once something changed), "+" while something is off Home, the hints one word each.
 * Every input goes to [HomeEditModel]; its effects go back to the host ([Host.run]).
 */
class HomeEditView(context: Context, private val host: Host) : View(context) {

    /** what the chrome needs of Home */
    interface Host {
        val model: HomeEditModel?
        /** the chrome's alpha (its 120 ms fade in, 200 out, the fight's park) */
        val chromeAlpha: Float
        /** every part's box now in Home's px (the lifted widget at its laid place: [liftedOffset] moves it) */
        fun parts(): Map<HomePart, RectF>
        /** the lifted widget's drag offset now (px), and its lift (0 .. 1: the 1.03 scale and the shadow) */
        fun liftedOffset(): Pair<Float, Float>
        fun liftK(): Float
        /** the clock block's box in Home's px (null = none drawn: the thin row, a fight) */
        fun clockBox(): RectF?
        /** the bars each widget has in the state now, for [l] (the drop well's box) */
        fun solve(l: HomeLayout): Map<HomePart, HomeLayoutSolver.Box>
        val geo: HomeEditModel.Geo
        fun run(fx: List<HomeEditModel.Fx>)
        fun menuRows(s: EditStop): List<MenuRow>
        /** a picture of [l] at [w] x [h] (PreviewStage), null = drawn schematic */
        fun thumb(l: HomeLayout, w: Int, h: Int): Bitmap?
        fun slots(): List<HomeLayout?>
        /** the room bar's height (the ribbon sits in its top TRACKS px) */
        val stripH: Int
        /** a red pulse on a widget's edge (a refusal, a fight): the widget and when */
        val flash: Pair<EditStop, Long>?
        /** phase 4: the room bar under Home (null = none: no Room bar stop) */
        fun bar(): Strip? = null
    }

    init { isClickable = true }

    /** Home draws this view itself, LAST (over its own paint: HomeView.dispatchDraw), so a redraw of it is a redraw of Home */
    override fun invalidate() { super.invalidate(); (parent as? View)?.invalidate() }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(14f, 9f), 0f)
    private val dashFine = DashPathEffect(floatArrayOf(8f, 7f), 0f)
    private val path = Path()

    // ---------------------------------------------------------------- where everything is

    /** the widgets' boxes as drawn (CAST = the bar's first half; [castBoxes] both), in Home's px */
    fun widgetBoxes(): Map<EditStop, RectF> {
        val p = host.parts()
        val out = LinkedHashMap<EditStop, RectF>()
        p[HomePart.BARS]?.let { out[EditStop.BAND] = RectF(it) }
        host.clockBox()?.let { out[EditStop.CLOCK] = RectF(it) }
        p[HomePart.CHAT]?.let { out[EditStop.CHAT] = RectF(it) }
        p[HomePart.MAP]?.let { out[EditStop.MAP] = RectF(it) }
        p[HomePart.QUESTS]?.let { out[EditStop.QUEST] = RectF(it) }
        castBoxes(p).takeIf { it.isNotEmpty() }?.let { bs -> out[EditStop.CAST] = RectF(bs[0]).apply { for (b in bs) union(b) } }
        return out
    }

    /** the tap-to-cast bar's box(es) on the page (none while it is slid away) */
    fun castBoxes(p: Map<HomePart, RectF> = host.parts()): List<RectF> {
        val h = height.toFloat().takeIf { it > 0f } ?: 935f
        return listOfNotNull(p[HomePart.STRIP], p[HomePart.CAST2]).filter { it.top < h - 1f }.map { RectF(it) }
    }

    /** the divider's handle: centred in the 8 px between the columns, halfway down them */
    fun dividerBox(p: Map<HomePart, RectF> = host.parts()): RectF? {
        val c = p[HomePart.CHAT] ?: return null; val m = p[HomePart.MAP] ?: return null
        val left = if (c.left < m.left) c else m; val right = if (left === c) m else c
        val x = (left.right + right.left) / 2f
        val top = max(left.top, right.top); val bottom = min(left.bottom, right.bottom)
        val cy = (top + bottom) / 2f
        return RectF(x - HANDLE_W / 2f, cy - HANDLE_H / 2f, x + HANDLE_W / 2f, cy + HANDLE_H / 2f)
    }

    /** the cursor's stops in Home's px (the band's box for the D-pad is its part left of the clock, so Right reaches the clock) */
    fun stops(): Map<EditStop, HomeLayoutSolver.Box> {
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        val wb = widgetBoxes()
        for ((s, r) in wb) {
            val b = if (s == EditStop.BAND && wb[EditStop.CLOCK] != null) RectF(r.left, r.top, wb[EditStop.CLOCK]!!.left, r.bottom) else r
            out[s] = HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom)
        }
        // the divider's D-pad stop is the columns' top (reached from the band above it; Left / Right there step the width)
        dividerBox()?.let { d -> val top = listOfNotNull(host.parts()[HomePart.CHAT], host.parts()[HomePart.MAP]).minOfOrNull { it.top } ?: d.top
            // only the gap's middle: never straight above / below a column (Up from the chat goes to the band, not here)
            out[EditStop.DIVIDER] = HomeLayoutSolver.Box(d.centerX() - 2f, top, d.centerX() + 2f, top + 40f) }
        val rib = ribbon(width.toFloat().takeIf { it > 0f } ?: 1240f)
        val y0 = height.toFloat()
        out[EditStop.CHIP] = HomeLayoutSolver.Box(rib.chip.left, y0 + rib.chip.top, rib.chip.right, y0 + rib.chip.bottom)
        if (host.model?.plusShown == true) out[EditStop.PLUS] = HomeLayoutSolver.Box(rib.plus.left, y0 + rib.plus.top, rib.plus.right, y0 + rib.plus.bottom)
        // phase 4: the room bar's rooms' row, under the ribbon (Down from the chip or the map; Left / Right there walk its rooms)
        if (host.bar() != null) out[EditStop.ROOMS] = HomeLayoutSolver.Box(8f, y0 + Strip.TRACKS, (width.toFloat().takeIf { it > 0f } ?: 1240f) - 8f, y0 + Strip.BAR)
        return out
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val m = host.model ?: return
        val a = host.chromeAlpha
        if (a <= 0.01f) return
        val save = if (a < 0.99f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * a).toInt()) else -1
        drawWidgets(c, m)
        if (m.menu != null) drawMenu(c, m)
        when (m.sheet) {
            HomeEditModel.Sheet.LAYOUTS -> drawLayouts(c, m)
            HomeEditModel.Sheet.ADD -> drawTray(c, m)
            null -> {}
        }
        if (m.prompt) drawPrompt(c, m)
        if (save >= 0) c.restoreToCount(save)
        val fl = host.flash
        if (fl != null && SystemClock.uptimeMillis() - fl.second < HomeEditModel.FLASH_MS) postInvalidateOnAnimation()
        if (m.drag != null) postInvalidateOnAnimation()
    }

    private fun drawWidgets(c: Canvas, m: HomeEditModel) {
        val p = host.parts()
        val wb = widgetBoxes()
        val sel = m.cursor
        val lifted = m.lifted
        val (ox, oy) = host.liftedOffset()
        val clock = wb[EditStop.CLOCK]
        val card = wb[EditStop.QUEST]
        val cardOnMap = m.draft.card == HomeLayout.CardDock.ON_MAP
        val lk = host.liftK()
        // the lifted widget lies over everything (its depth): the chrome of the others is never drawn on it - only round it
        val liftBoxes = lifted?.let { l -> (if (l == EditStop.CAST) castBoxes(p) else listOfNotNull(wb[l]))
            .map { RectF(it).apply { offset(ox, oy); grow(this, lk) } } } ?: emptyList()
        val under = c.save()
        for (b in liftBoxes) c.clipOutRect(b)
        for (b in liftBoxes) if (lk > 0f) shadow(c, b, lk)
        // 1. the dim over every widget that is not the selected one (black 70): the riders are dimmed with their host unless picked
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(DIM, 0, 0, 0)
        for ((s, r) in wb) {
            if (s == EditStop.CLOCK || (s == EditStop.QUEST && cardOnMap)) continue          // riders: inside their host's dim
            if (s == lifted) continue
            val keep = when (s) {
                EditStop.BAND -> if (sel == EditStop.CLOCK) clock else if (sel == EditStop.BAND) r else null
                EditStop.MAP -> if (sel == EditStop.QUEST && cardOnMap) card else if (sel == EditStop.MAP) r else null
                else -> if (sel == s) r else null
            }
            if (keep === r) continue
            if (s == EditStop.CAST) { for (b in castBoxes(p)) dimRect(c, b, null) } else dimRect(c, r, keep)
        }
        // the party module (Party mode): its dashed frame only, never a stop (it follows the chat)
        p[HomePart.PARTY]?.let { frame(c, it, light = false); tag(c, it, "Party", false, Corner.TOP_RIGHT) }
        // 2. the dashed ink frames and the tags (where they hide nothing: the band's on its foot, the map's bottom left, the card's top
        // right, the bar's on its top edge); a rider moves with its lifted host
        for ((s, r) in wb) {
            val rider = s == EditStop.CLOCK || (s == EditStop.QUEST && cardOnMap)
            val moves = s == lifted || (rider && s == EditStop.QUEST && lifted == EditStop.MAP)
            val boxes = if (s == EditStop.CAST) castBoxes(p) else listOf(r)
            for (b in boxes) {
                val bb = if (moves) RectF(b).apply { offset(ox, oy) } else b
                if (s != sel) frame(c, bb, light = rider)
            }
            tags += Triple(s, if (moves) RectF(boxes.first()).apply { offset(ox, oy) } else boxes.first(), s == sel)
        }
        // the divider's handle
        dividerBox(p)?.let { d -> handle(c, d, sel == EditStop.DIVIDER || m.drag?.stop == EditStop.DIVIDER) }
        // 3. a finger's drop well: the dock under it (gold; red = refused, it would break a size rule)
        m.drag?.let { d -> d.cand?.let { cand -> wellFor(d.stop, cand)?.forEach { well(c, it, d.bad) } } }
        // the others' tags, still round the lifted widget
        val liftedTags = tags.filter { (s, _, _) -> s == lifted || (lifted == EditStop.MAP && s == EditStop.QUEST && cardOnMap) }
        for ((s, r, gold) in tags) if (s !in liftedTags.map { it.first }) tag(c, r, s.tag, gold, CORNERS[s] ?: Corner.TOP_LEFT)
        c.restoreToCount(under)
        tags.retainAll(liftedTags)
        // 4. the golden box on the selected widget (riding the lifted one), the arrows to the docks a lifted one reaches
        val selBoxes = when (sel) {
            EditStop.CAST -> castBoxes(p)
            EditStop.DIVIDER, EditStop.CHIP, EditStop.PLUS -> emptyList()
            else -> listOfNotNull(wb[sel])
        }
        for (b0 in selBoxes) {
            val b = if (sel == lifted) RectF(b0).apply { offset(ox, oy); grow(this, lk) } else b0
            goldBox(c, b)
        }
        if (lifted != null && m.drag == null) selBoxes.firstOrNull()?.let { b0 ->
            val b = RectF(b0).apply { offset(ox, oy) }
            val canL = m.nextDock(lifted, m.draft, -1, true) != null
            val canR = m.nextDock(lifted, m.draft, 1, true) != null
            if (canL || canR) UiKit.moveArrows(c, RectF(b.left, b.centerY() - 40f, b.right, b.centerY() + 40f), canL, canR, words = false)
        }
        // 5. the red X chip on a selected removable widget, "Remove?" after its first tap
        if (sel.removable && lifted == null && m.menu == null && m.sheet == null && !m.prompt) selBoxes.lastOrNull()?.let { b ->
            val x = removeChip(b)
            closeChip(c, x)
            if (m.removeAsk == sel) pill(c, removePill(b), "Remove?")
        }
        // the tags last (the golden box never hides its widget's name)
        for ((s, r, gold) in tags) tag(c, r, s.tag, gold, CORNERS[s] ?: Corner.TOP_LEFT)
        tags.clear()
        // a refusal / a fight: the widget's edge red for FLASH_MS
        host.flash?.let { (s, at) ->
            val t = SystemClock.uptimeMillis() - at
            if (t in 0 until HomeEditModel.FLASH_MS) (if (s == EditStop.CAST) castBoxes(p) else listOfNotNull(wb[s])).forEach {
                edgeColour(c, it, Color.rgb(220, 40, 30), (255 * (1f - t.toFloat() / HomeEditModel.FLASH_MS)).toInt())
            }
        }
    }

    private fun grow(r: RectF, k: Float) {
        val s = 1f + (HomeEditModel.LIFT_SCALE - 1f) * k
        val dw = r.width() * (s - 1f) / 2f; val dh = r.height() * (s - 1f) / 2f
        r.inset(-dw, -dh)
    }

    /** the well(s) [stop] would take in [l] */
    private fun wellFor(stop: EditStop, l: HomeLayout): List<RectF>? {
        val b = host.solve(l)
        fun r(x: HomeLayoutSolver.Box) = RectF(x.l, x.t, x.r, x.b)
        return when (stop) {
            EditStop.CHAT -> b[HomePart.CHAT]?.let { listOf(r(it)) }
            EditStop.MAP -> b[HomePart.MAP]?.let { listOf(r(it)) }
            EditStop.QUEST -> b[HomePart.QUESTS]?.let { listOf(r(it)) }
            EditStop.CAST -> listOfNotNull(b[HomePart.STRIP], b[HomePart.CAST2]).map { r(it) }
            else -> null
        }
    }

    private fun dimRect(c: Canvas, r: RectF, keep: RectF?) {
        val s = c.save()
        if (keep != null) c.clipOutRect(keep)
        c.drawRoundRect(r, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        c.restoreToCount(s)
    }

    /** the soft dashed ink frame (a rider's lighter and finer) */
    private fun frame(c: Canvas, r: RectF, light: Boolean) {
        val o = RectF(r).apply { inset(if (light) 4f else 2f, if (light) 4f else 2f) }
        pLine.pathEffect = if (light) dashFine else dash
        pLine.strokeWidth = if (light) 2f else 3f
        pLine.color = Theme.bronze; pLine.alpha = if (light) 110 else 170
        c.drawRoundRect(o, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
        pLine.pathEffect = null
    }

    private val tags = ArrayList<Triple<EditStop, RectF, Boolean>>()
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, BOTTOM_CENTRE, FOOT_LEFT, TOP_EDGE }
    /** where each widget's tag stands (clear of what it shows) */
    private val CORNERS = mapOf(EditStop.BAND to Corner.BOTTOM_LEFT, EditStop.CLOCK to Corner.BOTTOM_RIGHT, EditStop.CHAT to Corner.TOP_LEFT,
        EditStop.MAP to Corner.BOTTOM_LEFT, EditStop.QUEST to Corner.TOP_RIGHT, EditStop.CAST to Corner.TOP_EDGE)

    /** the widget's name on a small plate (gold on the selected one) at [at] */
    private fun tag(c: Canvas, r: RectF, word: String, gold: Boolean, at: Corner = Corner.TOP_LEFT) {
        if (word.isEmpty()) return
        val p = UiKit.bodyPaint(TAG_SIZE, if (gold) Theme.gold else Theme.text)
        val t = Tr.t(word)
        val w = p.measureText(t) + 26f
        val x = when (at) {
            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> r.right - 12f - w
            Corner.BOTTOM_CENTRE -> r.centerX() - w / 2f
            Corner.FOOT_LEFT, Corner.TOP_EDGE -> r.left + 22f
            else -> r.left + 12f
        }
        val y = when (at) {
            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> r.bottom - 12f - TAG_H
            Corner.BOTTOM_CENTRE -> r.bottom - 6f - TAG_H
            Corner.FOOT_LEFT -> r.bottom - TAG_H / 2f
            Corner.TOP_EDGE -> r.top - TAG_H / 2f
            else -> r.top + 10f
        }
        val b = RectF(x, y, x + w, y + TAG_H)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 225
        c.drawRoundRect(b, 8f, 8f, pFill)
        pLine.strokeWidth = 2f; pLine.color = if (gold) Theme.gold else Theme.bronze; pLine.alpha = 255
        c.drawRoundRect(b, 8f, 8f, pLine)
        c.drawText(t, b.left + 13f, b.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * THE GOLDEN BOX (the app's cursor, the user's ruling: "a thick gold box with a glow, never a gold outline"; visible at once): a wide
     * soft gold glow round the widget, a 7 px gold box, the gold washed a little into its edge
     */
    private fun goldBox(c: Canvas, r: RectF) {
        val o = RectF(r).apply { inset(-4f, -4f) }
        pGlow.color = Theme.focusA(170); pGlow.strokeWidth = 16f
        c.drawRoundRect(o, UiKit.MODULE_R + 4f, UiKit.MODULE_R + 4f, pGlow)
        // the inner wash: a gold edge band fading inward
        val s = c.save()
        c.clipRect(r)
        pLine.strokeWidth = 22f; pLine.color = Theme.focusA(46)
        c.drawRoundRect(r, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
        c.restoreToCount(s)
        pLine.strokeWidth = 7f; pLine.color = Theme.focusA(255)
        c.drawRoundRect(o, UiKit.MODULE_R + 4f, UiKit.MODULE_R + 4f, pLine)
        pLine.strokeWidth = 2f; pLine.color = Color.argb(150, 90, 50, 0)
        c.drawRoundRect(RectF(o).apply { inset(-4.5f, -4.5f) }, UiKit.MODULE_R + 8f, UiKit.MODULE_R + 8f, pLine)
    }

    /** the deep shadow under a lifted widget (its translationZ is above the others) */
    private fun shadow(c: Canvas, r: RectF, k: Float) {
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb((140 * k).toInt(), 0, 0, 0)
        pFill.maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(r).apply { offset(10f, 16f) }, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        pFill.maskFilter = null
    }

    private fun edgeColour(c: Canvas, r: RectF, col: Int, alpha: Int) {
        pLine.strokeWidth = 6f; pLine.color = col; pLine.alpha = alpha
        c.drawRoundRect(RectF(r).apply { inset(-2f, -2f) }, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
    }

    /** the drop well: the dock filling gold (red when the drop would break a size rule) */
    private fun well(c: Canvas, r: RectF, bad: Boolean) {
        pFill.style = Paint.Style.FILL
        pFill.color = if (bad) Color.argb(70, 220, 40, 30) else Theme.focusA(60)
        c.drawRoundRect(r, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        pLine.pathEffect = dash; pLine.strokeWidth = 4f
        pLine.color = if (bad) Color.rgb(220, 40, 30) else Theme.focusA(230)
        c.drawRoundRect(RectF(r).apply { inset(3f, 3f) }, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
        pLine.pathEffect = null
    }

    /** the divider's handle: a slim plate with three studs (gold under the cursor or a finger) */
    private fun handle(c: Canvas, r: RectF, lit: Boolean) {
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 235
        c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, pFill)
        pLine.strokeWidth = 3f; pLine.color = if (lit) Theme.focusA(255) else Theme.bronze; pLine.alpha = 255
        c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, pLine)
        pFill.color = if (lit) Theme.gold else Theme.bronze
        for (i in -1..1) c.drawCircle(r.centerX(), r.centerY() + i * 18f, 3.5f, pFill)
        if (lit) goldBox(c, RectF(r).apply { inset(-2f, -2f) })
    }

    fun removeChip(b: RectF): RectF = RectF(b.right - CHIP_R * 2f + 8f, b.top - 8f, b.right + 8f, b.top + CHIP_R * 2f - 8f)
    fun removePill(b: RectF): RectF {
        val x = removeChip(b)
        return RectF(x.left - 170f, x.top + 4f, x.left - 8f, x.bottom - 4f)
    }

    /** the red X chip (the kit's close button look) */
    private fun closeChip(c: Canvas, r: RectF) {
        UiKit.closeX(c, r.centerX(), r.centerY(), r.width())
    }

    private fun pill(c: Canvas, r: RectF, word: String) {
        pFill.style = Paint.Style.FILL; pFill.color = Color.rgb(120, 18, 12); pFill.alpha = 240
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pFill)
        pLine.strokeWidth = 2.5f; pLine.color = Theme.gold; pLine.alpha = 255
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pLine)
        val p = UiKit.bodyPaint(26f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(word), r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    // ---------------------------------------------------------------- the menu (over the OTHER column or area)

    /** where [s]'s menu opens: over the other column (the band's and the clock's over the columns, the bar's over the columns above it) */
    fun menuBox(s: EditStop, rows: Int): RectF {
        val p = host.parts()
        val w = width.toFloat(); val h = height.toFloat()
        val g = UiKit.MODULE_GAP
        val chat = p[HomePart.CHAT]; val map = p[HomePart.MAP]
        val bars = p[HomePart.BARS]
        val top = (bars?.bottom ?: 226f) + g
        val bottom0 = max(chat?.bottom ?: (h - g), map?.bottom ?: (h - g))
        val wantH = MENU_HEAD + rows * MENU_ROW + 24f
        fun col(r: RectF?) = r?.let { RectF(it.left, top, it.right, min(bottom0, top + max(wantH, 0f))) }
        val cardHost = if (host.model?.draft?.card == HomeLayout.CardDock.ABOVE_CHAT) map else chat
        val box = when (s) {
            EditStop.CHAT -> col(map)
            EditStop.MAP -> col(chat)
            EditStop.QUEST -> col(cardHost)
            EditStop.ROOMS -> RectF(g + 8f, max(top, h - g - wantH), w - g - 8f, h - g)
            EditStop.CAST -> castBoxes(p).let { bs ->
                val bt = bs.minOfOrNull { it.top } ?: (h - g)
                RectF(g + 100f, max(top, bt - g - wantH), w - g - 100f, bt - g)
            }
            else -> RectF(w / 2f - 380f, top, w / 2f + 380f, min(h - g, top + wantH))
        } ?: RectF(g, top, w / 2f, h - g)
        return box
    }

    private fun drawMenu(c: Canvas, m: HomeEditModel) {
        val s = m.menu ?: return
        val rows = host.menuRows(s)
        val box = menuBox(s, rows.size)
        menuBoxes.clear()
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 245
        c.drawRoundRect(box, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        UiKit.module(c, box)
        val tp = UiKit.titlePaint(40f, Theme.gold)
        c.drawText(Tr.t(HomeWidgetMenus.title(s)), box.left + 28f, box.top + 56f, tp)
        UiKit.paneRule(c, box.left + 20f, box.top + MENU_HEAD - 12f, box.right - 20f)
        var y = box.top + MENU_HEAD
        for ((i, r) in rows.withIndex()) {
            val rr = RectF(box.left + 16f, y, box.right - 16f, y + MENU_ROW - 10f)
            pFill.color = Theme.raised; pFill.alpha = 220
            c.drawRoundRect(rr, 8f, 8f, pFill)
            val lp = UiKit.bodyPaint(28f, if (r.enabled) Theme.text else Theme.dim)
            val btns = ArrayList<RectF>()
            when (r.kind) {
                MenuRow.Kind.BUTTON -> {
                    // a row named apart from its button (the room bar's "Order" [Reset]) shows its name as the other rows do
                    if (r.label != r.words.first()) c.drawText(Tr.t(r.label), rr.left + 18f, rr.centerY() - (lp.ascent() + lp.descent()) / 2f, lp)
                    val b = RectF(rr.right - 300f, rr.top + 8f, rr.right - 10f, rr.bottom - 8f)
                    UiKit.button(c, b, r.words.first(), UiKit.okGlyph())
                    btns += b
                }
                MenuRow.Kind.STEP -> {
                    c.drawText(Tr.t(r.label), rr.left + 18f, rr.centerY() - (lp.ascent() + lp.descent()) / 2f, lp)
                    val bw = 64f
                    val plus = RectF(rr.right - 10f - bw, rr.top + 8f, rr.right - 10f, rr.bottom - 8f)
                    val minus = RectF(plus.left - 150f - bw, plus.top, plus.left - 150f, plus.bottom)
                    UiKit.choice(c, minus, r.words[0], true); UiKit.choice(c, plus, r.words[1], true)
                    val vp = UiKit.numberPaint(30f, Theme.text).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(r.value ?: "", (minus.right + plus.left) / 2f, rr.centerY() - (vp.ascent() + vp.descent()) / 2f, vp)
                    btns += minus; btns += plus
                }
                MenuRow.Kind.SEG -> {
                    c.drawText(Tr.t(r.label), rr.left + 18f, rr.centerY() - (lp.ascent() + lp.descent()) / 2f, lp)
                    val sizes = r.words.map { max(70f, UiKit.bodyPaint(26f).measureText(Tr.t(it)) + 34f) }
                    var x = rr.right - 10f - sizes.sum() - 6f * (sizes.size - 1)
                    for ((k, word) in r.words.withIndex()) {
                        val b = RectF(x, rr.top + 8f, x + sizes[k], rr.bottom - 8f)
                        UiKit.choice(c, b, word, k == r.picked, r.enabled, textSize = 26f)
                        btns += b
                        x += sizes[k] + 6f
                    }
                }
            }
            if (i == m.menuRow) UiKit.focus(c, rr)
            menuBoxes += btns
            menuRowBoxes += rr
            y += MENU_ROW
        }
        lastMenuBox.set(box)
    }
    /** the last drawn menu's buttons per row (touch) */
    private val menuBoxes = ArrayList<List<RectF>>()
    private val menuRowBoxes = ArrayList<RectF>()
    private val lastMenuBox = RectF()

    // ---------------------------------------------------------------- the Layouts sheet (final_09)

    private class SheetItem(val name: String, val layout: HomeLayout?, val available: Boolean)

    private fun sheetItems(m: HomeEditModel): List<SheetItem> {
        val presets = m.flips.filter { !it.name.startsWith(HomeEditModel.SLOT_MARK) }
            .map { SheetItem(it.name, it.make(m.saved), it.available) }
        val slots = host.slots().mapIndexed { i, l -> SheetItem("${i + 1}", l, l != null) }
        return presets + slots + SheetItem("All characters", null, true)
    }

    /** the sheet's item boxes (the frame inset by the module gap: 3 thumbnails a row, then Mine's slots, then All characters) */
    private fun sheetBoxes(m: HomeEditModel): List<RectF> {
        val w = width.toFloat(); val h = height.toFloat()
        val out = ArrayList<RectF>()
        val n = m.flips.count { !it.name.startsWith(HomeEditModel.SLOT_MARK) }
        val cols = HomeEditModel.SHEET_COLS
        val left = 36f; val right = w - 36f
        val cw = (right - left - (cols - 1) * 34f) / cols
        val th = cw * (h / w)
        var y = 96f
        for (i in 0 until n) {
            val col = i % cols; if (col == 0 && i > 0) y += th + 58f
            out += RectF(left + col * (cw + 34f), y, left + col * (cw + 34f) + cw, y + th)
        }
        y += th + 66f
        val sw = (right - left - 150f - 3 * 22f) / 4f
        for (i in 0 until HomePresets.SLOTS) out += RectF(left + 150f + i * (sw + 22f), y, left + 150f + i * (sw + 22f) + sw, y + 64f)
        out += RectF(left + 150f + 3 * (sw + 22f), y, right, y + 64f)
        return out
    }

    private val thumbs = HashMap<String, Bitmap?>()
    private val thumbWait = HashSet<String>()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /** the sheet opened: its pictures made once (PreviewStage), kept while edit mode lasts */
    fun dropThumbs() { thumbs.clear(); thumbWait.clear() }

    private fun drawLayouts(c: Canvas, m: HomeEditModel) {
        val w = width.toFloat(); val h = height.toFloat()
        val full = RectF(UiKit.MODULE_GAP, UiKit.MODULE_GAP, w - UiKit.MODULE_GAP, h - UiKit.MODULE_GAP)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 250
        c.drawRoundRect(full, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        UiKit.module(c, full)
        c.drawText(Tr.t("Layouts"), full.left + 26f, full.top + 64f, UiKit.titlePaint(52f, Theme.gold))
        val items = sheetItems(m)
        val boxes = sheetBoxes(m)
        val nPresets = m.flips.count { !it.name.startsWith(HomeEditModel.SLOT_MARK) }
        for ((i, it) in items.withIndex()) {
            val b = boxes.getOrNull(i) ?: continue
            val l = it.layout
            when {
                i < nPresets && l != null -> {
                    val key = HomeLayoutJson.json(l)
                    // the pictures are made one a frame after the sheet opens (the schematic stands in meanwhile): no hitch on its open
                    val bmp = thumbs[key]
                    if (!thumbs.containsKey(key) && thumbWait.isEmpty() && thumbWait.add(key)) ui.post {
                        thumbs[key] = host.thumb(l, b.width().toInt(), b.height().toInt()); thumbWait.remove(key); invalidate() }
                    val save = if (!it.available) c.saveLayerAlpha(b.left, b.top, b.right, b.bottom + 56f, 110) else -1
                    if (bmp != null) c.drawBitmap(bmp, null, b, pBmp) else schematic(c, b, l)
                    UiKit.module(c, b, edgeOnly = true)
                    if (m.draft.samePlace(l)) UiKit.check(c, b.right - 28f, b.top + 28f, 38f, true)
                    val np = UiKit.bodyPaint(32f, if (i == m.sheetSel) Theme.gold else Theme.text).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(Tr.t(it.name), b.centerX(), b.bottom + 40f, np)
                    if (save >= 0) c.restoreToCount(save)
                    if (i == m.sheetSel) goldBox(c, b)
                }
                i < nPresets + HomePresets.SLOTS -> {
                    if (i == nPresets) {
                        val lp = UiKit.bodyPaint(34f, Theme.dim)
                        c.drawText(Tr.t("Mine"), full.left + 26f, b.centerY() - (lp.ascent() + lp.descent()) / 2f, lp)
                    }
                    if (l == null) {
                        pLine.pathEffect = dash; pLine.strokeWidth = 2.5f; pLine.color = Theme.bronze; pLine.alpha = 200
                        c.drawRoundRect(b, 10f, 10f, pLine); pLine.pathEffect = null
                        val pp = UiKit.bodyPaint(30f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
                        c.drawText(it.name, b.centerX(), b.centerY() - (pp.ascent() + pp.descent()) / 2f, pp)
                    } else UiKit.choice(c, b, it.name, chosen = m.draft.samePlace(l), textSize = 30f)
                    if (i == m.sheetSel) UiKit.focus(c, b)
                }
                else -> {
                    UiKit.choice(c, b, it.name, chosen = m.allChars, textSize = 28f)
                    if (i == m.sheetSel) UiKit.focus(c, b)
                }
            }
        }
        sheetHit.clear(); sheetHit.addAll(boxes)
    }
    private val sheetHit = ArrayList<RectF>()

    /** the drawn stand-in for a layout's picture: its boxes from the solver, each a module well */
    private fun schematic(c: Canvas, r: RectF, l: HomeLayout) {
        val s = HomeLayoutSolver.places(l, HomeLayoutSolver.State(card = true, cardH = 118f))
        val k = r.width() / 1240f
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; c.drawRect(r, pFill)
        for ((p, b) in s.boxes) {
            if (p == HomePart.AURAS) continue
            val rr = RectF(r.left + b.l * k, r.top + b.t * k, r.left + b.r * k, r.top + b.b * k)
            pFill.color = when (p) { HomePart.MAP -> Color.rgb(86, 98, 54); HomePart.QUESTS -> Color.rgb(70, 54, 22); HomePart.BARS -> Color.rgb(30, 26, 20)
                HomePart.STRIP, HomePart.CAST2 -> Color.rgb(40, 52, 90); else -> Theme.panel }
            c.drawRoundRect(rr, 6f, 6f, pFill)
        }
    }

    // ---------------------------------------------------------------- the Add tray ("+")

    private fun trayBoxes(m: HomeEditModel): List<RectF> {
        val w = width.toFloat(); val h = height.toFloat()
        val n = m.removed.size
        val bw = 520f; val bh = 84f
        val top = h - 40f - n * (bh + 16f) - 90f
        return (0 until n).map { i -> RectF(w / 2f - bw / 2f, top + 80f + i * (bh + 16f), w / 2f + bw / 2f, top + 80f + i * (bh + 16f) + bh) }
    }

    private fun drawTray(c: Canvas, m: HomeEditModel) {
        val boxes = trayBoxes(m)
        if (boxes.isEmpty()) return
        val panel = RectF(boxes.first().left - 30f, boxes.first().top - 76f, boxes.first().right + 30f, boxes.last().bottom + 26f)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 245
        c.drawRoundRect(panel, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        UiKit.module(c, panel)
        c.drawText(Tr.t("Add"), panel.left + 24f, panel.top + 52f, UiKit.titlePaint(40f, Theme.gold))
        for ((i, s) in m.removed.withIndex()) {
            val b = boxes[i]
            UiKit.button(c, b, if (s == EditStop.CAST) "Tap-to-cast bar" else "Quest card", UiKit.okGlyph())
            if (i == m.sheetSel) UiKit.focus(c, b)
        }
        trayHit.clear(); trayHit.addAll(boxes)
    }
    private val trayHit = ArrayList<RectF>()

    // ---------------------------------------------------------------- the prompt (LayoutView's: Save highlighted)

    private val promptHit = ArrayList<RectF>()
    private fun drawPrompt(c: Canvas, m: HomeEditModel) {
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(170, 0, 0, 0); c.drawRect(full, pFill)
        val box = RectF(full.centerX() - 380f, full.centerY() - 150f, full.centerX() + 380f, full.centerY() + 120f)
        UiKit.pane(c, box)
        val tp = UiKit.titlePaint(36f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t("Unsaved changes"), box.centerX(), box.top + 70f, tp)
        val words = HomeEditModel.PROMPT
        val bw = (box.width() - 4 * 24f) / 3f
        promptHit.clear()
        for ((i, wd) in words.withIndex()) {
            val b = RectF(box.left + 24f + i * (bw + 24f), box.bottom - 110f, box.left + 24f + i * (bw + 24f) + bw, box.bottom - 30f)
            if (i == m.promptSel) UiKit.button(c, b, wd, UiKit.okGlyph()) else UiKit.button(c, b, wd)
            if (i == m.promptSel) UiKit.focus(c, b)
            promptHit += b
        }
    }

    // ---------------------------------------------------------------- the edit ribbon (in the XP bar's place, drawn by the Strip)

    class Ribbon(val title: RectF, val chip: RectF, val arrowL: RectF, val arrowR: RectF, val plus: RectF)

    /** the ribbon's pieces in the Strip's px (its top [Strip.TRACKS]) for a bar [w] wide */
    fun ribbon(w: Float): Ribbon {
        val h = Strip.TRACKS.toFloat()
        val tp = UiKit.titlePaint(RIBBON_TITLE)
        val title = RectF(14f, 0f, 14f + tp.measureText(Tr.t("Edit Home")), h)
        val np = UiKit.bodyPaint(RIBBON_TEXT)
        val name = host.model?.chipName ?: ""
        val nameW = max(150f, np.measureText(Tr.t(name)) + 16f)
        val chip = RectF(title.right + 18f, 2f, title.right + 18f + 30f + nameW + 30f, h - 2f)
        val arrowL = RectF(chip.left, chip.top, chip.left + 34f, chip.bottom)
        val arrowR = RectF(chip.right - 34f, chip.top, chip.right, chip.bottom)
        val plus = RectF(chip.right + 14f, 2f, chip.right + 14f + 46f, h - 2f)
        return Ribbon(title, chip, arrowL, arrowR, plus)
    }

    /** the Strip draws the ribbon in its tracking bar's place ([top] its top, [g0] .. [g1] the disc's gap) */
    fun drawRibbon(c: Canvas, w: Float, top: Float, g0: Float, g1: Float) {
        val m = host.model ?: return
        val a = host.chromeAlpha
        if (a <= 0.01f) return
        val h = Strip.TRACKS.toFloat()
        val save = c.saveLayerAlpha(0f, top, w, top + h, (255 * a).toInt())
        c.translate(0f, top)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 255
        c.drawRect(0f, 0f, w, h, pFill)
        pLine.strokeWidth = 2f; pLine.color = Theme.gold; pLine.alpha = 200
        c.drawLine(0f, 1f, g0, 1f, pLine); c.drawLine(g1, 1f, w, 1f, pLine)
        c.drawLine(0f, h - 1f, g0, h - 1f, pLine); c.drawLine(g1, h - 1f, w, h - 1f, pLine)
        val r = ribbon(w)
        val tp = UiKit.titlePaint(RIBBON_TITLE, Theme.gold)
        c.drawText(Tr.t("Edit Home"), r.title.left, h / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
        // the layout chip: its value between the kit's gold arrows (the tabs-open-on-landing look)
        pFill.color = Theme.raised; c.drawRoundRect(r.chip, 6f, 6f, pFill)
        pLine.strokeWidth = 2f; pLine.color = if (m.cursor == EditStop.CHIP) Theme.gold else Theme.bronze; pLine.alpha = 255
        c.drawRoundRect(r.chip, 6f, 6f, pLine)
        arrow(c, r.arrowL, true); arrow(c, r.arrowR, false)
        val np = UiKit.bodyPaint(RIBBON_TEXT, if (m.cursor == EditStop.CHIP) Theme.gold else Theme.text).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(m.chipName), r.chip.centerX(), h / 2f - (np.ascent() + np.descent()) / 2f, np)
        if (m.cursor == EditStop.CHIP && m.sheet == null) UiKit.focus(c, r.chip)
        if (m.plusShown) {
            pFill.color = Theme.raised; c.drawRoundRect(r.plus, 6f, 6f, pFill)
            pLine.color = if (m.cursor == EditStop.PLUS) Theme.gold else Theme.bronze; c.drawRoundRect(r.plus, 6f, 6f, pLine)
            val pp = UiKit.bodyPaint(30f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            c.drawText("+", r.plus.centerX(), h / 2f - (pp.ascent() + pp.descent()) / 2f, pp)
            if (m.cursor == EditStop.PLUS && m.sheet == null) UiKit.focus(c, r.plus)
        }
        // the hints, one word each, right of the disc
        hints(c, w - 14f, h / 2f, hintsFor(m))
        c.restoreToCount(save)
    }

    private fun arrow(c: Canvas, r: RectF, left: Boolean) {
        val pic = UiKit.tex(if (left) "move_arrow_left" else "move_arrow_right")
        val s = r.height() * 0.8f
        val d = RectF(r.centerX() - s / 2f, r.centerY() - s / 2f, r.centerX() + s / 2f, r.centerY() + s / 2f)
        if (pic != null) { c.drawBitmap(pic, null, d, pBmp); return }
        path.reset()
        if (left) { path.moveTo(d.right - 6f, d.top + 4f); path.lineTo(d.left + 6f, d.centerY()); path.lineTo(d.right - 6f, d.bottom - 4f) }
        else { path.moveTo(d.left + 6f, d.top + 4f); path.lineTo(d.right - 6f, d.centerY()); path.lineTo(d.left + 6f, d.bottom - 4f) }
        path.close()
        pFill.color = Theme.gold; c.drawPath(path, pFill)
    }

    /** the hints for the state: glyph names (the chosen set's) and one word each */
    fun hintsFor(m: HomeEditModel): List<Pair<String, String>> {
        val a = UiKit.okGlyph(); val b = UiKit.backGlyph(); val x = UiKit.xGlyph(); val y = UiKit.yGlyph()
        return when {
            m.prompt -> listOf(a to "OK", b to "Back")
            m.sheet == HomeEditModel.Sheet.LAYOUTS -> listOf(a to "Use", x to "Save here", b to "Back")
            m.sheet == HomeEditModel.Sheet.ADD -> listOf(a to "Add", b to "Back")
            m.menu != null -> listOf(a to "Change", b to "Back")
            m.lifted == EditStop.ROOMS -> listOf(DPAD to "Move", a to "Drop", b to "Put back")
            m.lifted != null -> listOf(DPAD to "Side", a to "Drop", b to "Put back")
            m.cursor == EditStop.CHIP -> listOf(DPAD to "Layout", a to "Layouts", b to "Done")
            m.cursor == EditStop.PLUS -> listOf(a to "Add", b to "Done")
            m.cursor == EditStop.DIVIDER -> listOf(DPAD to "Width", b to "Done")
            m.cursor == EditStop.ROOMS -> listOf(a to "Move", x to "Options", b to "Done")
            m.cursor == EditStop.BAND || m.cursor == EditStop.CLOCK ->
                (if (m.cursor == EditStop.BAND) listOf(a to "Options", y to "Size") else listOf(a to "Options")) + (b to "Done")
            else -> listOf(a to "Move", x to "Options", y to "Size", b to "Done")
        }
    }

    private fun hints(c: Canvas, right: Float, cy: Float, pairs: List<Pair<String, String>>) {
        val p = UiKit.bodyPaint(RIBBON_TEXT, Theme.text)
        var x = right
        for ((glyph, word) in pairs.reversed()) {
            val t = Tr.t(word)
            x -= p.measureText(t)
            c.drawText(t, x, cy - (p.ascent() + p.descent()) / 2f, p)
            x -= 6f + GLYPH / 2f
            if (glyph == DPAD) {
                UiKit.pad(c, "gen_right", x, cy, GLYPH); x -= GLYPH + 2f
                UiKit.pad(c, "gen_left", x, cy, GLYPH)
            } else UiKit.pad(c, glyph, x, cy, GLYPH)
            x -= GLYPH / 2f + 18f
        }
    }

    /** a finger on the ribbon (Strip px): the chip's arrows flip, the chip opens the sheet, "+" the tray. True = the ribbon's */
    fun ribbonTouch(x: Float, y: Float, up: Boolean): Boolean {
        val m = host.model ?: return false
        if (y < 0f || y > Strip.TRACKS) return false
        val r = ribbon(width.toFloat().takeIf { it > 0f } ?: 1240f)
        if (!up) return r.chip.contains(x, y) || (m.plusShown && r.plus.contains(x, y))
        when {
            r.arrowL.contains(x, y) -> host.run(m.flip(-1))
            r.arrowR.contains(x, y) -> host.run(m.flip(1))
            r.chip.contains(x, y) -> host.run(m.tap(EditStop.CHIP))
            m.plusShown && r.plus.contains(x, y) -> host.run(m.tap(EditStop.PLUS))
            else -> return false
        }
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- phase 4: the room bar (drawn by the Strip, in its px)

    /**
     * THE ROOM BAR'S CHROME (final_08; D3: reorder only), in two layers the Strip calls: 0 under the lifted room and the disc - not
     * selected: the dim (black 70) and the dashed ink frame as every other widget; selected: a gold edge round the rooms' row (the bar is
     * the widget); 1 over them - the golden box on the room under the bar's own cursor (lifted: round the carried picture, with the kit's
     * gold arrows to the sides it can still go) and the "Room bar" tag. The chrome's fade rides [Host.chromeAlpha].
     */
    fun drawBar(c: Canvas, layer: Int) {
        val m = host.model ?: return
        val bar = host.bar() ?: return
        val a = host.chromeAlpha
        if (a <= 0.01f || m.parked) return
        val row = bar.buttonsBox().apply { inset(4f, 4f) }
        val save = if (a < 0.99f) c.saveLayerAlpha(0f, 0f, bar.width.toFloat(), bar.height.toFloat(), (255 * a).toInt()) else -1
        val sel = m.cursor == EditStop.ROOMS && m.menu == null && m.sheet == null && !m.prompt
        val onBar = m.cursor == EditStop.ROOMS
        if (layer == 0) {
            if (!onBar) {
                pFill.style = Paint.Style.FILL; pFill.color = Color.argb(DIM, 0, 0, 0)
                c.drawRect(bar.buttonsBox(), pFill)
                frame(c, row, light = false)
            } else {
                pLine.strokeWidth = 3f; pLine.color = Theme.focusA(210); pLine.pathEffect = null
                c.drawRoundRect(row, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
            }
        } else {
            val room = m.draft.rooms.getOrNull(m.barSel)?.let { n -> Room.values().firstOrNull { it.name == n } }
            if (onBar && room != null) bar.roomDrawn(room)?.let { b ->
                val box = RectF(b).apply { inset(-10f, -8f) }
                if (sel || m.lifted == EditStop.ROOMS) UiKit.focus(c, box)
                if (m.lifted == EditStop.ROOMS && m.drag == null) {
                    val i = m.draft.rooms.indexOf(room.name)
                    UiKit.moveArrows(c, box, left = i > 0, right = i < m.draft.rooms.size - 1, words = false)
                }
            }
            // the tag while the bar is not the widget picked (picked, its menu's title names it; the tag would hide a room)
            if (!onBar) tag(c, row, EditStop.ROOMS.tag, false, Corner.BOTTOM_LEFT)
        }
        if (save >= 0) c.restoreToCount(save)
    }

    /** a finger on the rooms' row while editing (the Strip's px): a tap selects a room (the selected one: the bar's menu), a drag
     *  carries it - the others make way after the hover, the drop settles it (the model's drag, its x the bar's) */
    private var barDownX = 0f
    private var barDownY = 0f
    private var barDownSlot = -1
    private var barDragging = false
    fun barTouch(e: MotionEvent): Boolean {
        val m = host.model ?: return false
        val bar = host.bar() ?: return false
        if (m.parked) return false
        val x = e.x; val y = e.y
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                barDownX = x; barDownY = y; barDragging = false
                barDownSlot = if (m.prompt || m.sheet != null) -1 else bar.slotAt(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = barDownSlot
                if (!barDragging && i >= 0 && abs(x - barDownX) > HomeEditModel.DRAG_SLOP) {
                    bar.slotBox(i)?.let { b ->
                        barDragging = true
                        host.run(m.barDragStart(i, barDownX, barDownY, HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom)))
                    }
                }
                if (barDragging) host.run(m.dragMove(x, y, now, host.geo))
            }
            MotionEvent.ACTION_UP -> {
                if (barDragging) { host.run(m.dragEnd(x, y, now, host.geo)); barDragging = false }
                else if (m.prompt) {}
                else if (m.sheet != null) m.closeSheet()
                else if (barDownSlot >= 0 && abs(x - barDownX) <= TAP_SLOP && abs(y - barDownY) <= TAP_SLOP) host.run(m.barTap(barDownSlot))
                barDownSlot = -1
            }
            MotionEvent.ACTION_CANCEL -> { if (barDragging) host.run(m.dragCancel()); barDragging = false; barDownSlot = -1 }
        }
        invalidate(); bar.invalidate()
        return true
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var downStop: EditStop? = null
    private var dragging = false
    /** a gesture that came in already lifted (the entry hold): it drags at once */
    private var carried = false

    /** the entry hold's touch goes on as this view's: the held widget is lifted and follows the finger (one swap gesture) */
    fun carry(s: EditStop, x: Float, y: Float) {
        val m = host.model ?: return
        val b = widgetBoxes()[s] ?: return
        downX = x; downY = y; downStop = s; dragging = true; carried = true
        host.run(m.dragStart(s, x, y, HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom)))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = touch(e)

    fun touch(e: MotionEvent): Boolean {
        val m = host.model ?: return false
        if (m.parked) return false                                          // parked (a fight): Home's own touches (touch-cast) go on
        val x = e.x; val y = e.y
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; dragging = false; carried = false
                downStop = if (m.menu == null && m.sheet == null && !m.prompt) stopAt(x, y) else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> { if (dragging) host.run(m.dragCancel()); dragging = false; downStop = null }
            MotionEvent.ACTION_MOVE -> {
                val s = downStop
                if (!dragging && s != null && (s.movable || s == EditStop.DIVIDER) &&
                    (abs(x - downX) > HomeEditModel.DRAG_SLOP || abs(y - downY) > HomeEditModel.DRAG_SLOP)) {
                    val b = if (s == EditStop.DIVIDER) dividerBox() else widgetBoxes()[s]
                    if (b != null) {
                        dragging = true
                        host.run(m.dragStart(s, downX, downY, HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom)))
                    }
                }
                if (dragging) host.run(m.dragMove(x, y, now, host.geo))
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) { host.run(m.dragEnd(x, y, now, host.geo)); dragging = false }
                else if (abs(x - downX) <= TAP_SLOP && abs(y - downY) <= TAP_SLOP) tapAt(m, x, y)
                downStop = null
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) host.run(m.dragCancel()); dragging = false; downStop = null }
        }
        invalidate()
        return true
    }

    /** the widget under a finger (the card and the bar before the columns, the clock before the band, the divider's handle first) */
    fun stopAt(x: Float, y: Float): EditStop? {
        dividerBox()?.let { if (RectF(it).apply { inset(-18f, -10f) }.contains(x, y)) return EditStop.DIVIDER }
        val wb = widgetBoxes()
        for (s in listOf(EditStop.QUEST, EditStop.CAST, EditStop.CLOCK, EditStop.CHAT, EditStop.MAP, EditStop.BAND)) {
            if (s == EditStop.CAST) { if (castBoxes().any { it.contains(x, y) }) return s; continue }
            if (wb[s]?.contains(x, y) == true) return s
        }
        return null
    }

    private fun tapAt(m: HomeEditModel, x: Float, y: Float) {
        if (m.prompt) { promptHit.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { host.run(m.promptPick(it)) }; return }
        when (m.sheet) {
            HomeEditModel.Sheet.LAYOUTS -> {
                val i = sheetHit.indexOfFirst { it.contains(x, y) }
                if (i >= 0) host.run(m.sheetUse(i) { host.slots().getOrNull(it) }) else m.closeSheet()
                return
            }
            HomeEditModel.Sheet.ADD -> {
                val i = trayHit.indexOfFirst { it.contains(x, y) }
                if (i >= 0) host.run(m.addBack(m.removed.getOrNull(i))) else m.closeSheet()
                return
            }
            null -> {}
        }
        if (m.menu != null) {
            if (lastMenuBox.contains(x, y)) {
                for ((i, bs) in menuBoxes.withIndex()) {
                    val k = bs.indexOfFirst { RectF(it).apply { inset(-4f, -6f) }.contains(x, y) }
                    if (k >= 0) { host.run(m.menuTouch(i, k, host.menuRows(m.menu!!))); return }
                }
                menuRowBoxes.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { m.menuRow = it }
                return
            }
            m.closeMenu()
            return
        }
        // the red X chip, then its pill
        val sel = m.cursor
        if (sel.removable) widgetBoxes()[sel]?.let { b ->
            val last = if (sel == EditStop.CAST) castBoxes().lastOrNull() ?: b else b
            if (RectF(removeChip(last)).apply { inset(-10f, -10f) }.contains(x, y) ||
                (m.removeAsk == sel && removePill(last).contains(x, y))) { host.run(m.removeChip(sel)); return }
        }
        val s = stopAt(x, y)
        if (s == null) { m.cancelRemove(); return }
        host.run(m.tap(s))
    }

    companion object {
        /** the dim over a widget that is not selected (SPEC section 4: alpha 70 / 255 black) */
        const val DIM = 70
        const val TAG_SIZE = 24f
        const val TAG_H = 36f
        const val HANDLE_W = 18f
        const val HANDLE_H = 92f
        const val CHIP_R = 26f
        const val MENU_HEAD = 92f
        const val MENU_ROW = 84f
        const val TAP_SLOP = 24f
        const val RIBBON_TITLE = 26f
        const val RIBBON_TEXT = 22f
        const val GLYPH = 28f
        /** the D-pad's hint: Left and Right glyphs side by side */
        const val DPAD = "dpad"
    }
}
