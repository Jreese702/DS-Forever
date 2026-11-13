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
 * THE TAP-TO-CAST BAR ON HOME (GitHub #43; the logic is [CastStripModel]): Home's module well, in it ONE row of 6 slots (cb3, the user
 * 2026-11-13: "the buttons inside of it are too small ... the bar itself should have 6 slots"), each the spell's icon inside the
 * controller buttons' ring in its square form (the kit's `ring_square`, the HUD ring edge every Home module wears, [UiKit.ringBox]; the
 * drawn ring without the picture). Under the chat (LEFT), under the minimap (RIGHT) or spread under both (BOTH): [side]. No page text
 * (labels + icons only: nothing written at all). A finger on a slot is ONE tap through [onTap] (the host's TapBridge on the add-on's
 * "castbar" row). A sideways SWIPE on a LEFT / RIGHT bar moves it to the other side ([onSwipe]; the finger's slot is then not cast):
 * the bar follows the finger a little while it moves, and Home slides it over. A slot waiting for a fight's end is drawn dimmed and
 * takes no tap; the cooldown is the game's dark sweep from the add-on's `castcd`.
 */
class CastStripView(context: Context, private val onTap: (Int) -> Unit, private val onSwipe: (CastStripModel.Side) -> Unit = {}) : View(context) {

    private var bar: CastStripModel.Bar? = null
    private var cds: Map<Int, PadMapModel.FaceCd> = emptyMap()
    private var cdAt = 0L
    private var downAt = -1
    private var downX = 0f
    private var downY = 0f
    private var downMs = 0L
    private var moved = false
    private var pressedSlot = -1
    private var pressedUntil = 0L

    /** the place Home laid the bar in (its slots' spread) */
    var side: CastStripModel.Side = CastStripModel.Side.LEFT
        set(v) { if (field != v) { field = v; invalidate() } }

    fun update(b: CastStripModel.Bar) { bar = b; invalidate() }

    /** a `castcd` document: the whole state (a slot not in it has none) */
    fun onCd(m: Map<Int, PadMapModel.FaceCd>) { cds = m; cdAt = SystemClock.elapsedRealtime(); invalidate() }

    /** slot [i]'s box in this view's px */
    fun slotRect(i: Int): RectF {
        val b = CastStripModel.slotBox(side, i, width.toFloat(), height.toFloat())
        return RectF(b.l, b.t, b.r, b.b)
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
        val m = CastStripModel.GAP / 2f
        x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m } } ?: -1

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = slotAt(ev.x, ev.y); downX = ev.rawX; downY = ev.rawY; downMs = SystemClock.uptimeMillis(); moved = false
                if (downAt >= 0 && CastStripModel.tapCell(bar, downAt) != null) {
                    pressedSlot = downAt; pressedUntil = SystemClock.elapsedRealtime() + PRESS_MS; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (!moved && CastStripModel.leftTap(dx, dy)) { moved = true; pressedUntil = 0L; invalidate() }
                // the bar follows the finger a little toward the other side only (a hint that it moves), never off its column
                if (moved && (side == CastStripModel.Side.LEFT || side == CastStripModel.Side.RIGHT)) {
                    val toward = if (side == CastStripModel.Side.LEFT) dx.coerceIn(0f, FOLLOW_MAX) else dx.coerceIn(-FOLLOW_MAX, 0f)
                    translationX = toward * FOLLOW
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                val to = CastStripModel.swipeTo(side, dx, dy, SystemClock.uptimeMillis() - downMs)
                translationX = 0f
                if (to != null) onSwipe(to)
                else if (!moved) {
                    val i = slotAt(ev.x, ev.y)
                    if (i >= 0 && i == downAt && CastStripModel.tapCell(bar, i) != null) onTap(i)
                }
                downAt = -1; moved = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { downAt = -1; moved = false; translationX = 0f; return true }
        }
        return super.onTouchEvent(ev)
    }

    companion object {
        const val DIM = 90
        const val RING_SCALE = 0.75f
        const val PRESS_MS = 160L
        const val FRAME_MS = 33L
        /** while the finger moves: the bar goes this share of the finger's way, at most [FOLLOW_MAX] of it */
        const val FOLLOW = 0.35f
        const val FOLLOW_MAX = 240f
    }
}
