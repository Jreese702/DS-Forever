package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * THE SETUP'S BOTTOM SCREEN (the user's fresh-install test 2026-10-12, Track C): Play, plus ONE pop-up at a time from [SetupBridge]
 * ([SetupFlow.screen]). Before Play it covers the start screen (the game's own scene behind it); while the game runs it shows only the
 * sign-in pop-ups, over a dimmed ground, and is gone otherwise.
 *
 * Controller first: the D-pad moves the gold box ([UiKit.focus], the user's cursor look: never a gold outline alone) over the enabled
 * buttons, A presses the one in the box (its A glyph on it); touch presses any. The keys come through [SetupBridge.key] before Play
 * (the launcher's activity has them) and [pad] while the game runs (PadFeed's copy).
 */
class SetupPanel(context: Context) : View(context) {
    private var screen: SetupFlow.Screen? = null
    private val buttons = ArrayList<Pair<RectF, SetupFlow.Button>>()
    private var focus = 0
    private var pressed = -1
    private val area = RectF()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val refresh: () -> Unit = { post { update() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SetupBridge.onChange(refresh)
        SetupBridge.keyHandler = { code, down -> if (visibility == VISIBLE) key(code, down) else false }
        update()
    }

    override fun onDetachedFromWindow() {
        SetupBridge.offChange(refresh)
        removeCallbacks(tick)
        SetupBridge.keyHandler = null
        super.onDetachedFromWindow()
    }

    /** the game screen's attach / detach has no listener: look again every second while the launcher's setup exists */
    private val tick = Runnable { update() }

    /** re-read the bridge (its listener, the game screen's attach / detach) */
    fun update() {
        removeCallbacks(tick)
        if (SetupBridge.inputs != null && isAttachedToWindow) postDelayed(tick, 1000L)
        val s = SetupBridge.screen()
        val was = screen?.prompt?.id
        screen = s
        if (s?.prompt?.id != was) focus = 0          // a new pop-up: the box starts on its first button
        visibility = if (s != null && (s.prompt != null || s.play != null)) VISIBLE else GONE
        invalidate()
    }

    /** the pop-up shows (CompanionHost: the pad and B belong to it then) */
    val active: Boolean get() = visibility == VISIBLE

    override fun onDraw(c: Canvas) {
        val s = screen ?: return
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        if (s.play != null) Scene.draw(c, area)
        else { pFill.color = Color.argb(200, 6, 5, 4); c.drawRect(area, pFill) }
        buttons.clear()
        val cx = width / 2f
        var bottom = height - MARGIN
        // Play and its line, at the foot
        s.play?.let { play ->
            if (s.line.isNotEmpty()) UiKit.outlined(c, s.line, cx, bottom - 6f, 30f, Theme.text)
            if (s.line.isNotEmpty()) bottom -= LINE_H
            val r = RectF(cx - PLAY_W / 2f, bottom - PLAY_H, cx + PLAY_W / 2f, bottom)
            buttons += r to play
            bottom = r.top - GAP * 2
        }
        // the one pop-up, above Play
        cardRect.setEmpty()
        s.prompt?.let { p -> drawPrompt(c, p, bottom) }
        // "Report a problem" (board f-problem-report): small, in the bottom-left corner, last in the box's order
        s.report?.let { rb ->
            val r = RectF(MARGIN, height - MARGIN - REPORT_H, MARGIN + REPORT_W, height - MARGIN.toFloat())
            buttons += r to rb
        }
        // the box's order: the pop-up's buttons first, Play last (a new pop-up starts the box on its first button)
        if (s.play != null && buttons.size > 1) {
            val report = buttons.lastOrNull()?.takeIf { it.second.action == SetupFlow.Action.REPORT }?.also { buttons.removeAt(buttons.size - 1) }
            if (buttons.size > 1) buttons.add(buttons.removeAt(0))
            report?.let { buttons.add(it) }
        }
        // the buttons, the box under the focused one
        val live = buttons.indices.filter { buttons[it].second.enabled }
        if (live.isNotEmpty()) focus = focus.coerceIn(0, live.size - 1)
        buttons.forEachIndexed { i, (r, b) ->
            val boxed = live.isNotEmpty() && live[focus] == i
            if (boxed) box(c, r)
            val state = when { !b.enabled -> UiKit.Button.DISABLED; pressed == i -> UiKit.Button.DOWN; else -> UiKit.Button.UP }
            if (boxed) UiKit.button(c, r, b.label, StartView.GLYPH_A, state) else UiKit.button(c, r, b.label, state)
        }
    }

    /** the pop-up's card as last drawn (a touch inside it is the panel's) */
    private val cardRect = RectF()

    private fun drawPrompt(c: Canvas, p: SetupFlow.Prompt, bottom: Float) {
        val w = (width - MARGIN * 2).coerceAtMost(CARD_W)
        val left = (width - w) / 2f
        val title = UiKit.titlePaint(44f)
        val body = UiKit.bodyPaint(30f)
        val lines = wrap(p.text, body, w - PAD * 2)
        val h = PAD + 52f + 16f + lines.size * 40f + GAP + p.buttons.size * (BUTTON_H + GAP) + PAD
        val card = RectF(left, (bottom - h).coerceAtLeast(MARGIN), left + w, bottom)
        cardRect.set(card)
        pFill.color = Color.argb(235, 18, 14, 10)
        c.drawRoundRect(card, 14f, 14f, pFill)
        UiKit.border(c, card)
        var y = card.top + PAD + 44f
        title.textAlign = Paint.Align.CENTER
        c.drawText(p.title, card.centerX(), y, title)
        y += 16f
        body.textAlign = Paint.Align.CENTER
        for (l in lines) { y += 40f; c.drawText(l, card.centerX(), y, body) }
        y += GAP + 8f
        for (b in p.buttons) {
            val r = RectF(card.left + PAD, y, card.right - PAD, y + BUTTON_H)
            buttons += r to b
            y += BUTTON_H + GAP
        }
    }

    /** the gold box; before the kit's highlight art exists (a fresh install) a plain warm wash under the same gold edge */
    private fun box(c: Canvas, r: RectF) {
        if (UiKit.tex("highlight") == null) {
            pFill.color = Color.argb(110, 226, 170, 80)
            c.drawRoundRect(RectF(r.left - 5f, r.top - 5f, r.right + 5f, r.bottom + 5f), 8f, 8f, pFill)
        }
        UiKit.focus(c, RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f))
    }

    // ------------------------------------------------------------------ input

    private fun live(): List<Int> = buttons.indices.filter { buttons[it].second.enabled }

    /** one key from a game controller (before Play: the launcher's activity; true = the panel's) */
    fun key(code: Int, down: Boolean): Boolean {
        if (visibility != VISIBLE) return false
        val handled = code in KEYS
        if (!handled || !down) return handled
        val live = live()
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> if (live.isNotEmpty()) { focus = (focus - 1 + live.size) % live.size; invalidate() }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> if (live.isNotEmpty()) { focus = (focus + 1) % live.size; invalidate() }
            KeyEvent.KEYCODE_BUTTON_A -> live.getOrNull(focus)?.let { SetupBridge.press(buttons[it].second.action) }
        }
        return true
    }

    /** the pad while the game runs (PadFeed's copy through PadInput; the game gets the same presses): only while a pop-up shows */
    fun pad(down: Int): Boolean {
        if (visibility != VISIBLE || screen?.prompt == null) return false
        for ((slot, code) in PAD_KEYS) if (PadInput.isHeld(down, slot)) return key(code, true)
        return false
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = buttons.indexOfFirst { it.first.contains(e.x, e.y) && it.second.enabled }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
            MotionEvent.ACTION_UP -> {
                val was = pressed; pressed = -1; invalidate()
                if (was >= 0 && was == hit) {
                    live().indexOf(hit).takeIf { it >= 0 }?.let { focus = it }
                    SetupBridge.press(buttons[hit].second.action)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        // before Play every touch is the panel's (nothing under it is pressed by accident); in the game the CARD's, buttons or not (the
        // user before 1.0.7: a tap on a pop-up must never fall through to Home, whose pad cells act in the game)
        return screen?.play != null || hit >= 0 || (screen?.prompt != null && cardRect.contains(e.x, e.y))
    }

    companion object {
        const val MARGIN = 40f
        const val PAD = 36f
        const val GAP = 18f
        const val CARD_W = 980f
        const val BUTTON_H = 111f        // 48 dp on the Thor's bottom screen, the touch minimum (StartView's row)
        const val PLAY_W = 560f
        const val PLAY_H = 130f
        const val REPORT_W = 300f
        const val REPORT_H = 76f
        const val LINE_H = 52f
        private val KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A)
        private val PAD_KEYS = listOf(PadInput.SLOT_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP, PadInput.SLOT_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            PadInput.SLOT_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT, PadInput.SLOT_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            PadInput.SLOT_FACE_BOTTOM to KeyEvent.KEYCODE_BUTTON_A)

        /** words to lines no wider than [w] (a word wider than a line stands alone) */
        fun wrap(text: String, p: Paint, w: Float): List<String> {
            val out = ArrayList<String>()
            var cur = ""
            for (word in text.split(' ')) {
                val next = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isNotEmpty() && p.measureText(next) > w) { out += cur; cur = word } else cur = next
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
    }
}
