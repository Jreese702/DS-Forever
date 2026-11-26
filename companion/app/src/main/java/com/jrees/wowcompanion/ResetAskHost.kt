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
 * THE RESET'S YES / NO POP-UP over the Settings room (the user, 2026-11-19: "Every button asks first in a real Yes/No pop-up that holds
 * the pad"): 1503's wheel confirm drawn the same way (the cover, the game's dialog box, the question and one line, two buttons with their
 * pad glyphs) and holding the same way - while it is up [PopupHold.wheel] holds every pad button from the game at Android's key dispatch,
 * and [PopupHold.GRACE_MS] past it (the hold race); releases always pass. The room hands it every press while it is open ([key]); a touch
 * on a button picks it. The model is [ResetAsk]; the Yes goes to [onYes].
 */
class ResetAskHost(context: Context) : View(context) {
    var onYes: (ResetPlan.Button, Boolean) -> Unit = { _, _ -> }
    /** closed (a Yes or a No): the room puts the cursor back on the button */
    var onClosed: (ResetPlan.Button) -> Unit = {}
    /** the hold's clock (the tests) */
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    var ask: ResetAsk? = null
        private set
    val isOpen: Boolean get() = ask != null
    private val boxes = arrayOf(RectF(), RectF())
    private var downX = 0f
    private var downY = 0f

    init {
        visibility = GONE
        context.atHostEnd(Theme.onChange { postInvalidate() })
    }

    fun open(b: ResetPlan.Button, whoKnown: Boolean) {
        ask = ResetAsk(b, whoKnown)
        PopupHold.wheel.up()
        visibility = VISIBLE
        bringToFront()
        invalidate()
    }

    /** the room changed under it (another tab, the room left): closed as a No */
    fun cancel() = close()

    private fun close() {
        val a = ask ?: return
        ask = null
        PopupHold.wheel.down(clock())
        visibility = GONE
        onClosed(a.button)
    }

    private fun out(o: ResetAsk.Out) {
        val a = ask ?: return
        when (o) {
            is ResetAsk.Out.Yes -> { close(); onYes(a.button, o.all) }
            ResetAsk.Out.Closed -> close()
            ResetAsk.Out.None -> invalidate()
        }
    }

    /** one pad press while open: always taken (nothing else in the room moves meanwhile) */
    fun key(k: String): Boolean {
        val a = ask ?: return false
        out(a.key(k))
        return true
    }

    /** the two buttons' boxes on a [w] x [h] view (the tests) */
    fun buttonBoxes(w: Float, h: Float): List<RectF> {
        val (box, _) = frame(w, h)
        val bw = 330f; val bh = 100f
        val a = RectF(box.centerX() - 14f - bw, box.bottom - 36f - bh, box.centerX() - 14f, box.bottom - 36f)
        val b = RectF(box.centerX() + 14f, a.top, box.centerX() + 14f + bw, a.bottom)
        return listOf(a, b)
    }

    private fun frame(w: Float, h: Float): Pair<RectF, Float> {
        val bw = minOf(900f, w - 60f); val bh = 330f
        return RectF(w / 2f - bw / 2f, h * 0.36f - bh / 2f, w / 2f + bw / 2f, h * 0.36f + bh / 2f) to bh
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (ask == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.abs(e.x - downX) > 24f || kotlin.math.abs(e.y - downY) > 24f) return true
                val bs = buttonBoxes(width.toFloat(), height.toFloat())
                val k = bs.indexOfFirst { it.contains(e.x, e.y) }
                if (k >= 0) out(ask!!.pick(k))
            }
        }
        return true                                   // the room under it gets no touch while it asks
    }

    override fun onDraw(c: Canvas) {
        val a = ask ?: return
        val w = width.toFloat(); val h = height.toFloat()
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val (box, _) = frame(w, h)
        val inn = UiKit.dialog(c, box)
        c.drawText(Tr.t(a.title), inn.centerX(), inn.top + 60f, UiKit.bodyPaint(38f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
        c.drawText(Tr.t(a.line), inn.centerX(), inn.top + 112f, UiKit.bodyPaint(28f, Color.WHITE).apply { textAlign = Paint.Align.CENTER })
        val bs = buttonBoxes(w, h)
        val words = a.choices
        for (k in 0..1) {
            boxes[k].set(bs[k])
            // the confirm: Yes = A, No = B (1503's); the scope: A on the lit one (B closes, it picks neither)
            val glyph = if (k == a.lit) UiKit.okGlyph() else if (k == 1 && a.step == ResetAsk.Step.CONFIRM) UiKit.backGlyph() else ""
            val state = if (!a.enabled(k)) UiKit.Button.DISABLED else UiKit.Button.UP
            if (glyph.isNotEmpty()) UiKit.button(c, bs[k], words[k], glyph, state) else UiKit.button(c, bs[k], words[k], state)
        }
        // the lit choice wears the room's cursor (the golden box) on the scope step, where Left / Right move it
        if (a.step == ResetAsk.Step.SCOPE) UiKit.focus(c, RectF(bs[a.lit]).apply { inset(-6f, -6f) })
    }
}
