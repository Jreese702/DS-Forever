package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * SETTINGS > PERFORMANCE > STREAM SETTINGS IN LOCAL (the user's ruling, 2026-11-17: "Stream settings tile: ALWAYS show it on the
 * Performance tab; in Local mode (streaming not set up) it is dimmed, and A opens its page, which shows one terse line on how to set
 * streaming up (labels + icons only, terse text rule)"). The page over the tab body, as the Theme maker's host ([ThemeMakerHost]): the
 * back chip and the title in a 64 px strip, the Stream settings picture large (PC to the two-screen handheld), and [LINE] under it.
 * Nothing to press: B (or the back chip) closes it and the cursor is back on the tile; L / R close it and step the tab.
 */
class StreamHelpHost(context: Context) : View(context) {
    companion object {
        const val TITLE = "Stream settings"
        /** the one line (the terse text rule: what to do, no explanation) */
        const val LINE = "Choose Streaming at start, then pair with DS Forever Desktop"
        /** the back chip (touch only: B does the same), ThemeMakerPage's */
        val BACK = RectF(16f, 8f, 72f, 56f)
        /** the picture's size (the Performance band's large picture) */
        const val PIC_W = 438f
        const val PIC_H = 220f
    }

    /** the page closed (B, the back chip): the room puts the cursor back on the Stream settings tile */
    var onClosed: () -> Unit = {}
    private var downX = 0f
    private var downY = 0f

    val isOpen: Boolean get() = visibility == VISIBLE

    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    init {
        visibility = GONE
        Theme.onChange { postInvalidate() }
    }

    fun open() { visibility = VISIBLE; invalidate() }

    /** closes the page; false = it was not open */
    fun close(): Boolean {
        if (!isOpen) return false
        visibility = GONE
        onClosed()
        return true
    }

    /** one pad press while the page shows: true = taken (B closes; L / R close it and the room steps the tab; the rest do nothing) */
    fun key(k: String): Boolean {
        if (!isOpen) return false
        when (k) {
            "PAD2" -> close()
            "PADLSHOULDER", "PADRSHOULDER" -> { visibility = GONE; return false }
        }
        return true
    }

    /** the picture's box and the line's baseline on a [w] x [h] page (the tests) */
    fun pictureBox(w: Float, h: Float): RectF {
        val top = 64f + maxOf(24f, (h - 64f - PIC_H - 90f) / 2f)
        return RectF(w / 2f - PIC_W / 2f, top, w / 2f + PIC_W / 2f, top + PIC_H)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        RoomGround.settings(c, RectF(0f, 0f, w, h))
        UiKit.asBody(pTitle); UiKit.asBody(pLine)
        strip(c, w)
        val pic = pictureBox(w, h)
        pF.color = Color.rgb(14, 16, 18); c.drawRoundRect(pic, 10f, 10f, pF)
        PerformancePictures.draw(c, context, PerformanceTileModel.STREAM, pic, PerformanceTileModel.State(), 0f, big = true)
        pS.color = Color.argb(200, 0, 0, 0); pS.strokeWidth = 2f
        c.drawRoundRect(pic, 10f, 10f, pS)
        if (!UiKit.ringBox(c, RectF(pic.left - 6f, pic.top - 6f, pic.right + 6f, pic.bottom + 6f), 0.8f)) {
            pS.color = Theme.bronze; pS.strokeWidth = 3f; c.drawRoundRect(RectF(pic.left - 4f, pic.top - 4f, pic.right + 4f, pic.bottom + 4f), 12f, 12f, pS)
        }
        pLine.color = Theme.text
        c.drawText(fitText(pLine, Tr.t(LINE), w - 64f, 30f, 20f), w / 2f, pic.bottom + 64f, pLine)
    }

    /** [s] at the largest size from [size] down to [min] that fits [maxW], else cut with an ellipsis (the tiles' fitText) */
    private fun fitText(p: Paint, s: String, maxW: Float, size: Float, min: Float): String {
        p.textSize = size
        while (p.measureText(s) > maxW && p.textSize > min) p.textSize -= 1f
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    /** the 64 px strip: the back chip (the kit's left arrow) and the title (ThemeMakerPage's) */
    private fun strip(c: Canvas, w: Float) {
        val b = BACK
        val arrow = UiKit.tex("move_arrow_left")
        if (arrow != null) c.drawBitmap(arrow, null, b, pBmp)
        else {
            pS.color = Theme.gold; pS.strokeWidth = 5f
            path.reset(); path.moveTo(b.right - 14f, b.top + 10f); path.lineTo(b.left + 12f, b.centerY()); path.lineTo(b.right - 14f, b.bottom - 10f)
            c.drawPath(path, pS)
        }
        pTitle.textSize = 30f; pTitle.color = Theme.gold
        c.drawText(Tr.t(TITLE), b.right + 14f, 44f, pTitle)
        pF.color = Theme.bronzeDim
        c.drawRect(16f, 64f, w - 16f, 65.5f, pF)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) <= GameUiSection.SLOP && Math.abs(e.y - downY) <= GameUiSection.SLOP &&
                BACK.contains(e.x, e.y)) close()
        }
        return true
    }
}
