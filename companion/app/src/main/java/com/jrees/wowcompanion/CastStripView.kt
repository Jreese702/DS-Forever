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
 * THE TAP-TO-CAST BAR ON HOME (GitHub #43; the logic is [CastStripModel]): Home's module well, under it 12 slots in 2 rows of 6, each the
 * spell's icon inside the controller buttons' ring in its square form (the kit's `ring_square`, the HUD ring edge every Home module wears,
 * [UiKit.ringBox]; the drawn ring without the picture). No page text (labels + icons only: nothing written at all). A finger on a slot is
 * ONE tap through [onTap] (the host's TapBridge on the add-on's "castbar" row). A slot waiting for a fight's end is drawn dimmed and takes
 * no tap; the cooldown is the game's dark sweep from the add-on's `castcd`.
 */
class CastStripView(context: Context, private val onTap: (Int) -> Unit) : View(context) {

    private var bar: CastStripModel.Bar? = null
    private var cds: Map<Int, PadMapModel.FaceCd> = emptyMap()
    private var cdAt = 0L
    private var downAt = -1
    private var pressedSlot = -1
    private var pressedUntil = 0L

    fun update(b: CastStripModel.Bar) { bar = b; invalidate() }

    /** a `castcd` document: the whole state (a slot not in it has none) */
    fun onCd(m: Map<Int, PadMapModel.FaceCd>) { cds = m; cdAt = SystemClock.elapsedRealtime(); invalidate() }

    /** slot [i]'s box in this view's px */
    fun slotRect(i: Int): RectF {
        val (r, col) = CastStripModel.gridOf(i)
        val s = side()
        val gw = s * CastStripModel.PER_ROW + GAP * (CastStripModel.PER_ROW - 1)
        val x0 = (width - gw) / 2f
        val y0 = (height - (2 * s + GAP)) / 2f
        val x = x0 + col * (s + GAP); val y = y0 + r * (s + GAP)
        return RectF(x, y, x + s, y + s)
    }

    private fun side(): Float {
        val byH = (height - 2 * PAD - GAP) / 2f
        val byW = (width - 2 * PAD - GAP * (CastStripModel.PER_ROW - 1)) / CastStripModel.PER_ROW
        return minOf(MAX_SIDE, byH, byW).coerceAtLeast(8f)
    }

    private val pIcon = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pWell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        UiKit.module(c, RectF(0f, 0f, w, h))
        val now = SystemClock.elapsedRealtime()
        val b = bar
        var sweeping = false
        for (i in 0 until CastStripModel.SLOTS) {
            val r = slotRect(i)
            val s = b?.slots?.getOrNull(i)
            val live = CastStripModel.tapCell(b, i) != null
            val alpha = when { s?.id == null -> 110; live -> 255; else -> DIM }
            val down = i == pressedSlot && now < pressedUntil
            UiKit.pressed(c, r, down) {
                pWell.color = Theme.ink; pWell.alpha = 230
                c.drawRoundRect(r, 4f, 4f, pWell)
                val icon = s?.icon?.let { UiKit.icon(it) }
                if (icon != null) {
                    val inset = r.width() * 0.07f
                    pIcon.alpha = alpha
                    c.drawBitmap(icon, null, RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), pIcon)
                    pIcon.alpha = 255
                }
                cds[i]?.let { cd ->
                    val left = CastStripModel.sweepLeft(cd, cdAt, now)
                    if (left > 0f && s?.id != null) {
                        pSweep.color = Color.BLACK; pSweep.alpha = if (cd.gcd) 120 else 165
                        c.drawArc(r, -90f, -360f * left, true, pSweep)        // the game's sweep: the dark part shrinks clockwise
                        sweeping = true
                    }
                }
                if (!UiKit.ringBox(c, r, RING_SCALE, lit = down, alpha = alpha)) {
                    ringPath.reset(); ringPath.addRoundRect(r, 4f, 4f, android.graphics.Path.Direction.CW)
                    UiKit.ringEdge(c, ringPath, UiKit.RING_W, lit = down, alpha = alpha)
                }
            }
        }
        if (sweeping || now < pressedUntil) postInvalidateDelayed(FRAME_MS)
    }

    private val ringPath = android.graphics.Path()

    private fun slotAt(x: Float, y: Float): Int = (0 until CastStripModel.SLOTS).firstOrNull { slotRect(it).let { r ->
        x >= r.left - GAP / 2f && x <= r.right + GAP / 2f && y >= r.top - GAP / 2f && y <= r.bottom + GAP / 2f } } ?: -1

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = slotAt(ev.x, ev.y)
                if (downAt >= 0 && CastStripModel.tapCell(bar, downAt) != null) {
                    pressedSlot = downAt; pressedUntil = SystemClock.elapsedRealtime() + PRESS_MS; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val i = slotAt(ev.x, ev.y)
                if (i >= 0 && i == downAt && CastStripModel.tapCell(bar, i) != null) onTap(i)
                downAt = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> { downAt = -1; return true }
        }
        return super.onTouchEvent(ev)
    }

    companion object {
        /** the strip's height on Home (two slots of [MAX_SIDE], the gap and the well's padding) */
        const val HEIGHT = 2 * 56f + 6f + 2 * 10f
        const val MAX_SIDE = 56f
        const val GAP = 6f
        const val PAD = 10f
        const val DIM = 90
        const val RING_SCALE = 0.75f
        const val PRESS_MS = 160L
        const val FRAME_MS = 33L
    }
}
