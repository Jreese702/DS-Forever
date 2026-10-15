package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * THE SETTINGS ROOM'S TITLE AND TAB ROW (board f-settings-tabs), fixed above the page: "Settings", then one tab per
 * [SettingsTabs.Tab] in the game's tab look (UiKit.tab, standing on the page) with the shoulder glyphs on the row's OUTSIDES (L far
 * left, R far right: the house rule, UiKit.tabEnds). The room's controller cursor lands HERE ([cursor]: the chosen tab wears the
 * tab cursor); a tap on a tab picks it ([onPick]).
 */
class SettingsTabBar(context: Context, private val onPick: (SettingsTabs.Tab) -> Unit) : View(context) {
    var tab: SettingsTabs.Tab = SettingsTabs.Tab.HOME
        set(v) { if (field != v) { field = v; invalidate() } }
    /** the controller's cursor is on the tab row (on [tab]) */
    var cursor = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 44f; color = Theme.gold; typeface = Typeface.DEFAULT_BOLD }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tabs: List<RectF> = emptyList()
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
        tabs = UiKit.tabRow(all.size, 16f + room, width - 16f - room, TAB_TOP, TAB_H, TAB_MAX_W)
        for ((i, t) in all.withIndex()) UiKit.tab(c, tabs[i], t.label, t == tab, cursor = cursor && t == tab)
        if (tabs.isNotEmpty()) UiKit.tabEnds(c, tabs.first(), tabs.last(), room)
        // the cursor's A (the user: the cursor "appears with the A glyph"): A goes into this tab's page
        if (cursor) tabs.getOrNull(tab.ordinal)?.let { r ->
            val gs = A_GLYPH
            UiKit.pad(c, UiKit.okGlyph(), r.left - 0.35f * gs + gs / 2f, r.top - 0.2f * gs + gs / 2f, gs)
        }
        pFill.color = Theme.bronzeDim
        c.drawRect(0f, BAR_H - 1.5f, width.toFloat(), BAR_H, pFill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                val i = tabs.indexOfFirst { it.contains(e.x, e.y) }
                if (i >= 0) onPick(SettingsTabs.Tab.values()[i])
            }
        }
        return true
    }

    companion object {
        const val TITLE_BASE = 56f
        const val TAB_TOP = 78f
        const val TAB_H = 64f
        const val TAB_MAX_W = 220f
        const val BAR_H = 158f
        const val SLOP = 24f
        const val A_GLYPH = 40f
    }
}
