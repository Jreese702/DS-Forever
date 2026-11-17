package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * THE SMART Y LIST AS A SUB-PAGE (the beautify design, section 4: Smart Y's tile band carries On / Off and "List", which opens the list
 * here; 0.4: a sub-page is drawn in the tab body under a 64 px strip - the kit's left arrow chip and the title - never a dialog over
 * the room). The list moves AS IT IS: it is the SettingsView slice of the Smart Y block, its rows, its 11-11 per-row buttons and the
 * Manners row unchanged (the user's 11-11 ruling, not redesigned here).
 *
 * B one step back (the room's pad) closes it with the cursor back on the Smart Y tile; L / R step the tab and leave it closed. The back
 * chip is a touch target only, never a pad stop (B already does it - critic C9); the page lands on its first content stop.
 */
class SmartYPage(context: Context, hub: SettingsView) : LinearLayout(context) {
    /** the list: Smart Y's block as SettingsView draws it (a slice of the room's view, wired through it) */
    val list: SettingsView = hub.slice(setOf(SettingsTabs.Block.SMART_Y))
    /** the strip: the back chip and the title */
    val strip = SubPageStrip(context, "Smart Y") { onBack?.invoke() }
    /** the back chip was touched (the room closes the page) */
    var onBack: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        addView(strip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** the page's pad stops: the list's (the back chip is not one) */
    fun focusTargets(): List<RectF> = list.focusTargets()

    /** the first content stop (the page lands there), in the list's coordinates; null = none drawn yet */
    fun firstStop(): RectF? = list.focusTargets().sortedWith(compareBy({ it.top }, { it.left })).firstOrNull()
}

/**
 * A SUB-PAGE'S STRIP (the design's 0.4): 64 px, the kit's `move_arrow_left` chip (our drawn arrow without the kit) then the title in Friz
 * 30, the theme's gold. The chip takes a tap (back); the pad never stops on it.
 */
class SubPageStrip(context: Context, private val title: String, private val onBack: () -> Unit) : View(context) {
    private val pTitle by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Theme.gold } }
    private val pB = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var downX = 0f
    private var downY = 0f

    init { Theme.onChange { postInvalidate() } }

    /** the back chip's box (touch only) */
    fun chip(): RectF = RectF(16f, 6f, 16f + CHIP, 6f + CHIP)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), H.toInt())

    override fun onDraw(c: Canvas) {
        UiKit.asBody(pTitle)
        c.drawColor(Theme.ink)
        val r = chip()
        val art = UiKit.tex("move_arrow_left")
        if (art != null) {
            val h = r.height() * 0.8f; val w = h * art.width / art.height
            c.drawBitmap(art, null, RectF(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f), pB)
        } else {
            pS.color = Theme.gold; pS.strokeWidth = 4f
            c.drawLine(r.centerX() + 9f, r.centerY() - 14f, r.centerX() - 7f, r.centerY(), pS)
            c.drawLine(r.centerX() - 7f, r.centerY(), r.centerX() + 9f, r.centerY() + 14f, pS)
        }
        pTitle.color = Theme.gold
        c.drawText(Tr.t(title), r.right + 14f, H / 2f - (pTitle.ascent() + pTitle.descent()) / 2f, pTitle)
        pS.color = Theme.bronzeDim; pS.strokeWidth = 1f
        c.drawLine(16f, H - 1f, width - 16f, H - 1f, pS)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) <= SLOP && Math.abs(e.y - downY) <= SLOP) {
                val r = chip(); if (RectF(r.left - 8f, 0f, r.right + 200f, H).contains(e.x, e.y)) onBack()
            }
        }
        return true
    }

    companion object {
        const val H = 64f
        const val CHIP = 52f
        const val SLOP = 24f
    }
}
