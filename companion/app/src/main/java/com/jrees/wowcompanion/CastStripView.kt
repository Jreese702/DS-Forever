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
 * THE TAP-TO-CAST BAR ON HOME (GitHub #43; the logic is [CastStripModel]): Home's module well, in it the bar's slots, each the spell's
 * icon inside the controller buttons' ring in its square form (the kit's `ring_square`, the HUD ring edge every Home module wears,
 * [UiKit.ringBox]; the drawn ring without the picture). Under the chat (LEFT) or under the minimap (RIGHT): THREE at a time (cb4, the
 * user 2026-11-13: "make them only show 3 abilities at a time for left or right, and swipe to the other 3. they should be the same size
 * as the both option"), slots 1-3 or 4-6, two page dots in the well's foot; spread under both (BOTH): all 6. [side]. No page text
 * (labels + icons only: nothing written at all). A finger on a slot is ONE tap through [onTap] (the host's TapBridge on the add-on's
 * "castbar" row; the slot index 0..5, page 2's first = 3). A sideways SWIPE on a LEFT / RIGHT bar flips to the other three, either way:
 * the page follows the finger, the other page comes in beside it, and the release slides it home ([onFlip]; the finger's slot is then
 * not cast). The swipe never moves the bar (the side is set in Settings only). A slot waiting for a fight's end is drawn dimmed and takes
 * no tap; the cooldown is the game's dark sweep from the add-on's `castcd` on every slot drawn.
 */
class CastStripView(context: Context, private val onTap: (Int) -> Unit, private val onFlip: (Int) -> Unit = {}) : View(context) {

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
    /** the shown page's offset now (the finger's drag, or the slide home); 0 = at rest */
    private var offset = 0f
    private var slideFrom = 0f
    private var slideAt = -1L

    /** the place Home laid the bar in (its slots' count and spread) */
    var side: CastStripModel.Side = CastStripModel.Side.LEFT
        set(v) { if (field != v) { field = v; offset = 0f; slideAt = -1L; invalidate() } }

    /** cb4: the LEFT / RIGHT page shown (0 = slots 1-3, 1 = 4-6); kept for the app's run in [CastStripModel.Live.page] */
    var page: Int
        get() = CastStripModel.Live.page.coerceIn(0, 1)
        set(v) { if (CastStripModel.Live.page != v) { CastStripModel.Live.page = v; invalidate() } }

    fun update(b: CastStripModel.Bar) { bar = b; invalidate() }

    /** a `castcd` document: the whole state (a slot not in it has none) */
    fun onCd(m: Map<Int, PadMapModel.FaceCd>) { cds = m; cdAt = SystemClock.elapsedRealtime(); invalidate() }

    /** place [k]'s box in this view's px (at rest) */
    fun slotRect(k: Int): RectF {
        val b = CastStripModel.slotBox(side, k, width.toFloat(), height.toFloat())
        return RectF(b.l, b.t, b.r, b.b)
    }

    private val pIcon = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pWell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        UiKit.module(c, RectF(0f, 0f, w, h))
        val now = SystemClock.elapsedRealtime()
        if (slideAt >= 0L) {
            val t = (now - slideAt).toFloat() / CastStripModel.SLIDE_MS
            offset = CastStripModel.slideAt(slideFrom, t)
            if (t >= 1f) { offset = 0f; slideAt = -1L }
        }
        var sweeping = false
        val n = CastStripModel.perPage(side)
        c.save()
        c.clipRect(CastStripModel.PAD / 2f, 0f, w - CastStripModel.PAD / 2f, h)       // the pages slide inside the well
        sweeping = drawPage(c, page, offset, n, now) or sweeping
        if (offset != 0f && CastStripModel.pages(side) > 1)
            sweeping = drawPage(c, 1 - page, CastStripModel.otherOffset(offset, w), n, now) or sweeping
        c.restore()
        CastStripModel.dots(side, w, h)?.let { (y, xs) ->
            for ((p, x) in xs.withIndex()) {
                pDot.color = if (p == page) Theme.gold else Theme.dim
                pDot.alpha = if (p == page) 235 else 140
                c.drawCircle(x, y, CastStripModel.DOT_R, pDot)
            }
        }
        if (sweeping || now < pressedUntil || slideAt >= 0L) postInvalidateDelayed(FRAME_MS)
    }

    /** the [n] slots of page [pg] moved [dx]; true = a cooldown sweep runs on one of them */
    private fun drawPage(c: Canvas, pg: Int, dx: Float, n: Int, now: Long): Boolean {
        val b = bar
        var sweeping = false
        for (k in 0 until n) {
            val i = CastStripModel.slotIndex(side, pg, k)
            if (i < 0) continue
            val r = slotRect(k).apply { offset(dx, 0f) }
            if (r.right < 0f || r.left > width) continue
            val s = b?.slots?.getOrNull(i)
            val live = CastStripModel.tapCell(b, i) != null
            val alpha = when { s?.id == null -> 110; live -> 255; else -> DIM }
            val down = i == pressedSlot && now < pressedUntil
            UiKit.pressed(c, r, down) {
                pWell.color = Theme.ink; pWell.alpha = 230
                c.drawRoundRect(r, 4f, 4f, pWell)
                // the spell's icon from icons.pak ([Icons], as the Spellbook draws it); the kit's fid_ art only for what the pack lacks
                val icon = s?.icon?.let { Icons.get(it) ?: UiKit.icon(it) }
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
        return sweeping
    }

    private val ringPath = android.graphics.Path()

    /** the slot index (0..5) under a finger at rest, or -1 */
    private fun slotAt(x: Float, y: Float): Int {
        val k = (0 until CastStripModel.perPage(side)).firstOrNull { slotRect(it).let { r ->
            val m = CastStripModel.GAP / 2f
            x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m } } ?: return -1
        return CastStripModel.slotIndex(side, page, k)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (slideAt >= 0L) { slideAt = -1L; offset = 0f }                     // a finger during the slide: the page is home
                downAt = slotAt(ev.x, ev.y); downX = ev.rawX; downY = ev.rawY; downMs = SystemClock.uptimeMillis(); moved = false
                if (downAt >= 0 && CastStripModel.tapCell(bar, downAt) != null) {
                    pressedSlot = downAt; pressedUntil = SystemClock.elapsedRealtime() + PRESS_MS; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (!moved && CastStripModel.leftTap(dx, dy)) { moved = true; pressedUntil = 0L; invalidate() }
                // cb4: the page follows the finger, the other three come in beside it (only where there are two pages)
                if (moved && CastStripModel.pages(side) > 1) { offset = CastStripModel.dragOffset(dx, width.toFloat()); invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                val to = CastStripModel.flipTo(side, page, dx, dy, SystemClock.uptimeMillis() - downMs)
                if (to != null) {
                    slideFrom = CastStripModel.flipStart(offset.takeIf { it != 0f } ?: (if (dx < 0f) -0.001f else 0.001f), width.toFloat())
                    page = to
                    onFlip(to)
                    slide()
                } else {
                    if (offset != 0f) { slideFrom = offset; slide() }                 // not far enough: back home
                    if (!moved) {
                        val i = slotAt(ev.x, ev.y)
                        if (i >= 0 && i == downAt && CastStripModel.tapCell(bar, i) != null) onTap(i)
                    }
                }
                downAt = -1; moved = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downAt = -1; moved = false
                if (offset != 0f) { slideFrom = offset; slide() }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun slide() { slideAt = SystemClock.elapsedRealtime(); offset = slideFrom; invalidate() }

    /** off-device renders: the slide frozen at [o] px (the shown page's offset; the other page beside it) */
    fun freezeOffsetForTest(o: Float) { slideAt = -1L; offset = o; invalidate() }

    companion object {
        const val DIM = 90
        const val RING_SCALE = 0.75f
        const val PRESS_MS = 160L
        const val FRAME_MS = 33L
    }
}
