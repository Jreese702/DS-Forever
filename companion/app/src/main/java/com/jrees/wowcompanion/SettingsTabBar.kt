package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * THE SETTINGS ROOM'S TITLE AND TAB ROW (board f-settings-tabs), fixed above the page: "Settings", then one tab per
 * [SettingsTabs.Tab] in the game's tab look (UiKit.tab, standing on the page) with the shoulder glyphs on the row's OUTSIDES (L far
 * left, R far right: the house rule, UiKit.tabEnds). The room's controller cursor lands HERE ([cursor]: the chosen tab wears the
 * tab cursor); a tap on a tab picks it ([onPick]).
 *
 * EVERY LABEL FITS (the user on test40: "In settings the tabs seem way too small for the text of each one"): each tab is as wide as
 * its label at the game's font (UiKit.tab's 28 px) plus its caps ([TAB_PAD] a side), the tabs [TAB_GAP] apart (the old row gave
 * every tab one equal share and a 60 px LB / RB gap between EACH pair, though this row's L / R stand on its outsides). Room to
 * spare is shared out (each tab up to [TAB_MAX_W]); a row that cannot fit SCROLLS, as the game's own tab rows do ([layout]): the
 * chosen tab glides into view with [FADE] px of the next one showing, the cut edges fade, and L / R stay on the row's outsides.
 */
class SettingsTabBar(context: Context, private val onPick: (SettingsTabs.Tab) -> Unit) : View(context) {
    var tab: SettingsTabs.Tab = SettingsTabs.Tab.HOME
        set(v) { if (field != v) { field = v; invalidate() } }
    /** the controller's cursor is on the tab row (on [tab]) */
    var cursor = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 44f; color = Theme.gold; typeface = Typeface.DEFAULT_BOLD }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFade = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tabs: List<RectF> = emptyList()
    // the strip's scroll (0 = the first tab at the row's left): gliding from [offFrom] to [offTo] since [offT0]
    private var offFrom = 0f
    private var offTo = 0f
    private var offT0 = 0L
    private var offSeen = false
    private var downX = 0f
    private var downY = 0f

    /** the tabs as last drawn, in this view's coordinates (the room's tests and the cursor) */
    fun tabRects(): List<RectF> = tabs.map { RectF(it) }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), BAR_H.toInt())
    }

    override fun onDraw(c: Canvas) {
        UiKit.asTitle(pTitle)
        c.drawColor(Theme.ink)
        c.drawText("Settings", 28f, TITLE_BASE, pTitle)
        val all = SettingsTabs.Tab.values()
        val room = UiKit.TAB_END_W
        val left = 16f + room; val right = width - 16f - room
        val lay = layout(all.map { it.label }, right - left, tab.ordinal)
        // the strip's glide toward keeping the chosen tab in view
        val now = android.os.SystemClock.uptimeMillis()
        if (!offSeen) { offSeen = true; offFrom = lay.offset; offTo = lay.offset; offT0 = now }
        else if (lay.offset != offTo) { offFrom = offset(now); offTo = lay.offset; offT0 = now }
        val off = offset(now)
        tabs = lay.xs.map { (x0, x1) -> RectF(left + x0 - off, TAB_TOP, left + x1 - off, TAB_TOP + TAB_H) }
        val scrolls = lay.total > right - left + 0.5f
        c.save()
        if (scrolls) c.clipRect(left, 0f, right, BAR_H)
        for ((i, t) in all.withIndex()) UiKit.tab(c, tabs[i], t.label, t == tab, cursor = cursor && t == tab)
        // the cut edges fade into the bar: there is more that way
        if (scrolls) {
            if (off > 0.5f) fade(c, left, left + FADE, true)
            if (off < lay.total - (right - left) - 0.5f) fade(c, right - FADE, right, false)
        }
        c.restore()
        // L / R on the row's outsides, whatever the strip shows
        if (tabs.isNotEmpty()) UiKit.tabEnds(c, RectF(left, TAB_TOP, left + 1f, TAB_TOP + TAB_H), RectF(right - 1f, TAB_TOP, right, TAB_TOP + TAB_H), room)
        if (now - offT0 < GLIDE_MS) postInvalidateOnAnimation()
        // the cursor's A (the user: the cursor "appears with the A glyph"): A goes into this tab's page
        if (cursor) tabs.getOrNull(tab.ordinal)?.let { r ->
            val gs = A_GLYPH
            UiKit.pad(c, UiKit.okGlyph(), r.left - 0.35f * gs + gs / 2f, r.top - 0.2f * gs + gs / 2f, gs)
        }
        pFill.color = Theme.bronzeDim
        c.drawRect(0f, BAR_H - 1.5f, width.toFloat(), BAR_H, pFill)
    }

    private fun offset(now: Long): Float {
        val t = ((now - offT0).toFloat() / GLIDE_MS).coerceIn(0f, 1f)
        val k = 1f - (1f - t) * (1f - t) * (1f - t)          // ease out
        return offFrom + (offTo - offFrom) * k
    }

    private fun fade(c: Canvas, x0: Float, x1: Float, fromLeft: Boolean) {
        val solid = Theme.ink; val clear = Theme.ink and 0x00FFFFFF
        pFade.shader = LinearGradient(x0, 0f, x1, 0f, if (fromLeft) solid else clear, if (fromLeft) clear else solid, Shader.TileMode.CLAMP)
        c.drawRect(x0, TAB_TOP - 4f, x1, TAB_TOP + TAB_H + 4f, pFade)
        pFade.shader = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                val room = UiKit.TAB_END_W
                val i = tabs.indexOfFirst { it.contains(e.x, e.y) && e.x >= 16f + room && e.x <= width - 16f - room }
                if (i >= 0) onPick(SettingsTabs.Tab.values()[i])
            }
        }
        return true
    }

    /** the tabs' places along the strip ([xs]: each tab's start / end from the strip's left), their [total] and the scroll that
     *  shows the chosen one ([offset], 0 when all fit) */
    class Layout(val xs: List<Pair<Float, Float>>, val total: Float, val offset: Float)

    companion object {
        /**
         * the strip for [labels] in [avail] px with tab [chosen] open: natural widths ([width]), the spare room shared out up to
         * [TAB_MAX_W] each; too wide = a scroll that keeps [chosen] whole with [FADE] px to spare either side (pure: the test's)
         */
        fun layout(labels: List<String>, avail: Float, chosen: Int, width: (String) -> Float = ::labelW): Layout {
            if (labels.isEmpty()) return Layout(emptyList(), 0f, 0f)
            var ws = labels.map { maxOf(TAB_MIN_W, width(it) + 2f * TAB_PAD) }
            val natural = ws.sum() + TAB_GAP * (labels.size - 1)
            if (natural < avail) {
                val extra = (avail - natural) / labels.size
                ws = ws.map { minOf(it + extra, maxOf(it, TAB_MAX_W)) }      // never past TAB_MAX_W (unless its label needs more)
            }
            val xs = ArrayList<Pair<Float, Float>>()
            var x = 0f
            for (w in ws) { xs.add(x to x + w); x += w + TAB_GAP }
            val total = x - TAB_GAP
            var off = 0f
            if (total > avail) {
                val (c0, c1) = xs[chosen.coerceIn(0, xs.size - 1)]
                // as far left as it may be with the chosen tab whole and FADE of room past it on both sides
                off = (c1 + FADE - avail).coerceAtLeast(0f)
                if (c0 - FADE < off) off = c0 - FADE
                off = off.coerceIn(0f, total - avail)
            }
            return Layout(xs, total, off)
        }

        /** a label's width as UiKit.tab draws it */
        fun labelW(s: String): Float = UiKit.bodyPaint(28f).measureText(s)

        const val TAB_PAD = 26f          // a label's room to its tab's ends (the tab art's caps)
        const val TAB_GAP = 8f
        const val TAB_MIN_W = 110f
        const val FADE = 44f
        const val GLIDE_MS = 220L
        const val TITLE_BASE = 56f
        const val TAB_TOP = 78f
        const val TAB_H = 64f
        const val TAB_MAX_W = 220f
        const val BAR_H = 158f
        const val SLOP = 24f
        const val A_GLYPH = 40f
    }
}
