package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * THE TAP-TO-CAST BAR ON HOME (GitHub #43; the logic is [CastStripModel]): Home's module well, in it the bar's slots, each the spell's
 * icon inside the controller buttons' ring in its square form (the kit's `ring_square`, the HUD ring edge every Home module wears,
 * [UiKit.ringBox]; the drawn ring without the picture). Under the chat (LEFT) or under the minimap (RIGHT): A SCROLL BAR (cb5, the user
 * 2026-11-13: "I dont want them shown as pages, I want it to be a literal scroll bar that can stop in any position"; the picks: 4 in
 * view, "4 with an arrow on the sides indicating if there are abilities that way"): all 6 slots on one strip at BOTH's size, 4 in view
 * spread evenly, clipped to the well; the strip follows the finger and stops wherever it is let go (a light fling with friction, no
 * snapping); a small gold arrow at an edge (fading with the scroll) where slots are hidden that way. Spread under both (BOTH): all 6,
 * no scroll. [side]. No page text (labels + icons only: nothing written at all). A finger on a slot is ONE tap through [onTap] (the
 * host's TapBridge on the add-on's "castbar" row; the slot index 0..5 under the finger at the scroll it is at). A finger that moves more
 * than [CastStripModel.TAP_SLOP_PX] never casts; a finger that catches a fling stops it and casts nothing. The scroll is kept for the
 * app's run ([CastStripModel.Live.pos]). A slot waiting for a fight's end is drawn dimmed and takes no tap; the cooldown is the game's
 * dark sweep from the add-on's `castcd` on every slot drawn.
 */
class CastStripView(context: Context, private val onTap: (Int) -> Unit) : View(context) {

    private var bar: CastStripModel.Bar? = null
    private var cds: Map<Int, PadMapModel.FaceCd> = emptyMap()
    private var cdAt = 0L
    private var downAt = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    /** the finger caught a running fling: its release casts nothing */
    private var caught = false
    private var dragX = 0f
    private var dragFrom = 0f
    private val sampleT = ArrayList<Long>()
    private val sampleX = ArrayList<Float>()
    private var pressedSlot = -1
    private var pressedUntil = 0L
    private var flingFrom = 0f
    private var flingV = 0f
    private var flingAt = -1L

    /** the place Home laid the bar in (its slots in view and spread) */
    var side: CastStripModel.Side = CastStripModel.Side.LEFT
        set(v) { if (field != v) { field = v; flingAt = -1L; invalidate() } }

    /** cb5: the strip's scroll now (px, 0 = slot 1 leftmost), kept for the app's run as slots' shares in [CastStripModel.Live.pos] */
    var scroll: Float
        get() = CastStripModel.fromPos(side, width.toFloat(), CastStripModel.Live.pos)
        set(v) {
            val p = CastStripModel.toPos(side, width.toFloat(), v)
            if (p != CastStripModel.Live.pos) { CastStripModel.Live.pos = p; invalidate() }
        }

    /** a fling is running */
    val flinging: Boolean get() = flingAt >= 0L

    fun update(b: CastStripModel.Bar) { bar = b; invalidate() }

    /** a `castcd` document: the whole state (a slot not in it has none) */
    fun onCd(m: Map<Int, PadMapModel.FaceCd>) { cds = m; cdAt = SystemClock.elapsedRealtime(); invalidate() }

    /** slot [i]'s box (0..5) in this view's px at the scroll now */
    fun slotRect(i: Int): RectF {
        val b = CastStripModel.slotBox(side, i, width.toFloat(), height.toFloat(), scroll)
        return RectF(b.l, b.t, b.r, b.b)
    }

    private val pIcon = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pWell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pArrow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPath = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        UiKit.module(c, RectF(0f, 0f, w, h))
        val now = SystemClock.elapsedRealtime()
        stepFling(SystemClock.uptimeMillis())
        val o = scroll
        val (wl, wr) = CastStripModel.window(w)
        c.save()
        c.clipRect(wl, 0f, wr, h)                                                       // the strip scrolls inside the well
        val sweeping = drawSlots(c, o, now)
        c.restore()
        val (al, ar) = CastStripModel.arrows(side, w, o)
        if (al > 0f) edge(c, true, al, w, h)
        if (ar > 0f) edge(c, false, ar, w, h)
        if (sweeping || now < pressedUntil || flinging) postInvalidateDelayed(FRAME_MS)
    }

    /** the slots in view at scroll [o]; true = a cooldown sweep runs on one of them */
    private fun drawSlots(c: Canvas, o: Float, now: Long): Boolean {
        val b = bar
        var sweeping = false
        for (i in CastStripModel.inView(side, width.toFloat(), height.toFloat(), o)) {
            val r = slotRect(i)
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
                    ringPath.reset(); ringPath.addRoundRect(r, 4f, 4f, Path.Direction.CW)
                    UiKit.ringEdge(c, ringPath, UiKit.RING_W, lit = down, alpha = alpha)
                }
            }
        }
        return sweeping
    }

    private val ringPath = Path()

    /** cb5: the edge where slots are hidden: the well darkens toward it and a small gold arrow points that way, at [k] (0..1) strength */
    private fun edge(c: Canvas, left: Boolean, k: Float, w: Float, h: Float) {
        val (wl, wr) = CastStripModel.window(w)
        val a = CastStripModel.arrowBox(side, left, w, h)
        val fadeW = (a.r - a.l) + 2f * (if (left) a.l - wl else wr - a.r) + 10f
        val x0 = if (left) wl else wr; val x1 = if (left) wl + fadeW else wr - fadeW
        pFade.shader = LinearGradient(x0, 0f, x1, 0f, Color.argb((210 * k).toInt(), Color.red(Theme.ink), Color.green(Theme.ink),
            Color.blue(Theme.ink)), Color.argb(0, Color.red(Theme.ink), Color.green(Theme.ink), Color.blue(Theme.ink)), Shader.TileMode.CLAMP)
        c.drawRect(minOf(x0, x1), CastStripModel.PAD / 2f, maxOf(x0, x1), h - CastStripModel.PAD / 2f, pFade)
        pFade.shader = null
        arrowPath.reset()
        val cy = (a.t + a.b) / 2f
        if (left) { arrowPath.moveTo(a.r, a.t); arrowPath.lineTo(a.l, cy); arrowPath.lineTo(a.r, a.b) }
        else { arrowPath.moveTo(a.l, a.t); arrowPath.lineTo(a.r, cy); arrowPath.lineTo(a.l, a.b) }
        arrowPath.close()
        val al = (255 * k).toInt()
        pArrow.style = Paint.Style.FILL; pArrow.color = Theme.gold; pArrow.alpha = al
        c.drawPath(arrowPath, pArrow)
        pArrow.style = Paint.Style.STROKE; pArrow.strokeJoin = Paint.Join.ROUND; pArrow.strokeWidth = 2.5f
        pArrow.color = Color.argb((200 * k).toInt(), 120, 72, 0)
        c.drawPath(arrowPath, pArrow)
    }

    /** the fling at [nowMs] (uptimeMillis): the strip goes on, slowing, and stops on its own or at an end */
    private fun stepFling(nowMs: Long) {
        if (flingAt < 0L) return
        val t = (nowMs - flingAt).toFloat()
        val w = width.toFloat()
        scroll = CastStripModel.flingAt(side, w, flingFrom, flingV, t)
        if (CastStripModel.flingDone(side, w, flingFrom, flingV, t)) flingAt = -1L
    }

    private fun sample(t: Long, x: Float) {
        sampleT.add(t); sampleX.add(x)
        while (sampleT.size > 2 && t - sampleT[0] > 2 * CastStripModel.VELOCITY_MS) { sampleT.removeAt(0); sampleX.removeAt(0) }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val w = width.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                caught = flinging
                if (caught) { stepFling(ev.eventTime); flingAt = -1L }                 // a finger on a fling: it stops there, no cast
                downAt = CastStripModel.slotAt(side, ev.x, ev.y, w, height.toFloat(), scroll)
                downX = ev.rawX; downY = ev.rawY; moved = false
                sampleT.clear(); sampleX.clear(); sample(ev.eventTime, ev.rawX)
                if (!caught && downAt >= 0 && CastStripModel.tapCell(bar, downAt) != null) {
                    pressedSlot = downAt; pressedUntil = SystemClock.elapsedRealtime() + PRESS_MS; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                sample(ev.eventTime, ev.rawX)
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (!moved && CastStripModel.leftTap(dx, dy)) {
                    moved = true; pressedUntil = 0L
                    dragX = ev.rawX; dragFrom = scroll                                  // the strip follows from here (no jump)
                    if (CastStripModel.scrolls(side)) parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                if (moved && CastStripModel.scrolls(side)) scroll = CastStripModel.dragScroll(side, w, dragFrom, ev.rawX - dragX)
                return true
            }
            MotionEvent.ACTION_UP -> {
                sample(ev.eventTime, ev.rawX)
                if (moved) {
                    val v = CastStripModel.velocity(sampleT, sampleX)
                    if (CastStripModel.scrolls(side) && CastStripModel.flings(v)) {
                        flingFrom = scroll; flingV = v; flingAt = ev.eventTime; invalidate()
                    }
                } else if (!caught) {
                    val i = CastStripModel.slotAt(side, ev.x, ev.y, w, height.toFloat(), scroll)
                    if (i >= 0 && i == downAt && CastStripModel.tapCell(bar, i) != null) onTap(i)
                }
                downAt = -1; moved = false; caught = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downAt = -1; moved = false; caught = false; pressedUntil = 0L
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** off-device renders and tests: run the fling to [nowMs] (uptimeMillis) */
    fun stepFlingForTest(nowMs: Long) { stepFling(nowMs); invalidate() }

    companion object {
        const val DIM = 90
        const val RING_SCALE = 0.75f
        const val PRESS_MS = 160L
        const val FRAME_MS = 16L
    }
}
