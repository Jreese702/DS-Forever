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

    init {
        androidx.core.view.ViewCompat.addAccessibilityAction(this, SPEAKER_LABEL) { _, _ ->
            screen?.report?.takeIf { it.enabled }?.let { SetupBridge.press(it.action); true } ?: false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SetupBridge.onChange(refresh)
        SetupBridge.keyHandler = keys
        update()
    }

    /** this panel's hold on the pad, released only while it is still this panel's (see ChooserPanel.keys: a rebuilt host's old panel
     *  cleared the new one's, and A on Update did nothing) */
    private val keys: (Int, Boolean) -> Boolean = { code, down -> if (visibility == VISIBLE) key(code, down) else false }

    override fun onDetachedFromWindow() {
        SetupBridge.offChange(refresh)
        removeCallbacks(tick)
        if (SetupBridge.keyHandler === keys) SetupBridge.keyHandler = null
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
        contentDescription = if (s?.report != null) "DS Forever start-up. $SPEAKER_LABEL" else null
        invalidate()
    }

    /** the pop-up shows (CompanionHost: the pad and B belong to it then) */
    val active: Boolean get() = visibility == VISIBLE

    override fun onDraw(c: Canvas) {
        val s = screen ?: return
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        // before Play: the loading reveal's bottom part (2026-10-28), moving; in the game: the dimmed ground under the sign-in pop-ups
        if (s.play != null) { LoadingScene.draw(c, area, top = false); postInvalidateDelayed(LoadingScene.FRAME_MS) }
        else { pFill.color = Color.argb(200, 6, 5, 4); c.drawRect(area, pFill) }
        buttons.clear()
        val cx = width / 2f
        var bottom = height - MARGIN
        // Play and its line, at the foot. In the start-up chooser's route the checks run here first (the user, 2026-10-28: the chooser
        // comes after them): their line and pop-ups, no Play (the chooser's Local is the way in)
        s.play?.let { play ->
            // the status line, always there while the setup has no pop-up of its own (the user, 2026-10-28: "so it's clear it isn't
            // stuck"); with a pop-up only the step itself, as before
            val status = if (s.prompt == null) SetupFlow.status(s.line, SetupBridge.checksPass(), LoadingScene.now()) else s.line
            if (status.isNotEmpty()) UiKit.outlined(c, status, cx, bottom - 6f, 30f, Theme.text)
            if (status.isNotEmpty()) bottom -= LINE_H
            if (ModeBridge.route == StartMode.Route.Chooser) return@let
            val r = RectF(cx - PLAY_W / 2f, bottom - PLAY_H, cx + PLAY_W / 2f, bottom)
            buttons += r to play
            bottom = r.top - GAP * 2
        }
        // the one pop-up, above Play
        cardRect.setEmpty()
        s.prompt?.let { p -> drawPrompt(c, p, bottom) }
        // "Report a problem" (board f-problem-report) is no longer drawn here: the user, 2026-10-28, "make the report button consistent
        // with the other junk" - a kit button at the foot of the top screen's checks ([LoadingTopView]). Select / View still opens it.
        // the box's order: the pop-up's buttons first, Play last (a new pop-up starts the box on its first button)
        if (s.play != null && buttons.size > 1 && buttons.first().second.action == SetupFlow.Action.PLAY) buttons.add(buttons.removeAt(0))
        // the swirl, top left (S1 of the streaming phase): back to the start-up chooser, as B does; touch, before Play only
        swirlRect.setEmpty()
        if (s.play != null && ModeBridge.canGoBack()) drawSwirl(c)
        // the buttons, the box under the focused one
        val live = buttons.indices.filter { SetupFlow.padTarget(buttons[it].second) }      // "Report a problem" is touch only
        if (live.isNotEmpty()) focus = focus.coerceIn(0, live.size - 1)
        // b-login-a-input (the user, 15 Oct: the login screen's cursor, glyph and A "cause issues with inputting on both screens"): in
        // the game (no Play) the sign-in pop-up has no gold box and no A glyph, and takes no pad presses; its buttons are touch only
        val padUi = SetupFlow.padDriven(s)
        // the press shows the same by the pad as by a finger (StartPress: pushed in, then the spinner while it is under way)
        val now = android.os.SystemClock.uptimeMillis()
        val busy = SetupBridge.inputs?.busy != null
        buttons.forEachIndexed { i, (r, b) ->
            val boxed = padUi && live.isNotEmpty() && live[focus] == i
            if (boxed) box(c, r)
            // the pressed button keeps its own look while its action runs (the page greys its buttons while busy: not this one)
            val waiting = feedback.waiting(b.action, now, busy)
            val state = when {
                feedback.down(b.action, now) || pressed == i -> UiKit.Button.DOWN
                waiting -> UiKit.Button.UP
                !b.enabled -> UiKit.Button.DISABLED
                else -> UiKit.Button.UP
            }
            if (boxed) UiKit.button(c, r, b.label, StartView.GLYPH_A, state, waiting) else UiKit.button(c, r, b.label, state, waiting = waiting)
        }
        if (feedback.animating(now, busy)) postInvalidateDelayed(LoadingScene.FRAME_MS)
    }

    /** the last press, by the pad or a finger (its pushed-in look, then its wait) */
    private val feedback = StartPress()

    /** a button pressed (pad or touch): its look, then its action */
    private fun act(i: Int) {
        val a = buttons[i].second.action
        feedback.press(a, android.os.SystemClock.uptimeMillis())
        invalidate()
        SetupBridge.press(a)
    }

    /** the swirl's square as last drawn (empty = not shown) */
    private val swirlRect = RectF()
    private var swirlDown = false
    private val pSwirl = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** the bar Home button's face (the kit's stone, or a drawn disc before the kit exists), the B glyph on its corner */
    private fun drawSwirl(c: Canvas) {
        swirlRect.set(MARGIN, MARGIN, MARGIN + SPEAKER, MARGIN + SPEAKER)
        val r = if (swirlDown) RectF(swirlRect).apply { inset(width() * 0.03f, height() * 0.03f) } else swirlRect
        val face = UiKit.tex("home_stone_rough")
        if (face != null) c.drawBitmap(face, null, r, pSwirl)
        else {
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(215, 18, 14, 10)
            c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, pFill)
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 4f; pFill.color = Theme.gold
            c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f - 3f, pFill)
            c.drawArc(RectF(r.centerX() - r.width() * 0.25f, r.centerY() - r.height() * 0.25f, r.centerX() + r.width() * 0.25f,
                r.centerY() + r.height() * 0.25f), 0f, 270f, false, pFill)
            pFill.style = Paint.Style.FILL
        }
        UiKit.pad(c, UiKit.backGlyph(), r.right - 14f, r.bottom - 14f, 44f)
    }

    /** the pop-up's card as last drawn (a touch inside it is the panel's) */
    private val cardRect = RectF()

    private fun drawPrompt(c: Canvas, p: SetupFlow.Prompt, bottom: Float) {
        val w = (width - MARGIN * 2).coerceAtMost(CARD_W)
        val left = (width - w) / 2f
        val title = UiKit.titlePaint(44f)
        val body = UiKit.bodyPaint(30f)
        val lines = p.text.split('\n').flatMap { wrap(Tr.t(it), body, w - PAD * 2) }      // 2026-11-07: Spanish / French (Tr)
        val h = PAD + 52f + 16f + lines.size * 40f + GAP + p.buttons.size * (BUTTON_H + GAP) + PAD
        val card = RectF(left, (bottom - h).coerceAtLeast(MARGIN), left + w, bottom)
        cardRect.set(card)
        pFill.color = Color.argb(235, 18, 14, 10)
        c.drawRoundRect(card, 14f, 14f, pFill)
        UiKit.border(c, card)
        var y = card.top + PAD + 44f
        title.textAlign = Paint.Align.CENTER
        c.drawText(Tr.t(p.title), card.centerX(), y, title)
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

    private fun live(): List<Int> = buttons.indices.filter { SetupFlow.padTarget(buttons[it].second) }

    /** one key from a game controller (before Play: the launcher's activity; true = the panel's) */
    fun key(code: Int, down: Boolean): Boolean {
        if (visibility != VISIBLE) return false
        val handled = code in KEYS
        if (!handled || !down) return handled
        val live = live()
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> if (live.isNotEmpty()) { focus = (focus - 1 + live.size) % live.size; invalidate() }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> if (live.isNotEmpty()) { focus = (focus + 1) % live.size; invalidate() }
            KeyEvent.KEYCODE_BUTTON_A -> live.getOrNull(focus)?.let { act(it) }
            // the report's own key (the cursor never lands on it: b-setup-report-cursor; its button is on the top screen)
            KeyEvent.KEYCODE_BUTTON_SELECT -> screen?.report?.takeIf { it.enabled }?.let {
                LoadingTopView.reportPress.press(LoadingTopView.REPORT, android.os.SystemClock.uptimeMillis())
                SetupBridge.press(it.action)
            }
        }
        return true
    }

    /** the pad while the game runs (PadFeed's copy through PadInput; the game gets the same presses): only while a pop-up shows */
    fun pad(down: Int): Boolean {
        // b-login-a-input: never in the game's login screen (A goes to the game, or is held back at the code prompt: PopupHold)
        if (visibility != VISIBLE || screen?.prompt == null || !SetupFlow.padDriven(screen!!)) return false
        for ((slot, code) in PAD_KEYS) if (PadInput.isHeld(down, slot)) return key(code, true)
        return false
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // the swirl: back to the start-up chooser
        if (!swirlRect.isEmpty && (swirlDown || (e.actionMasked == MotionEvent.ACTION_DOWN && swirlRect.contains(e.x, e.y)))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> swirlDown = true
                MotionEvent.ACTION_UP -> { swirlDown = false; if (swirlRect.contains(e.x, e.y)) ModeBridge.back("the swirl") }
                MotionEvent.ACTION_CANCEL -> swirlDown = false
            }
            invalidate()
            return true
        }
        val hit = buttons.indexOfFirst { it.first.contains(e.x, e.y) && it.second.enabled }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
            MotionEvent.ACTION_UP -> {
                val was = pressed; pressed = -1; invalidate()
                if (was >= 0 && was == hit) {
                    live().indexOf(hit).takeIf { it >= 0 }?.let { focus = it }
                    act(hit)
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
        /** the swirl's square (back to the chooser): 48 dp on the Thor's bottom screen (the touch minimum, as BUTTON_H) */
        const val SPEAKER = 111f
        /** the report's accessibility label (the button itself is on the top screen's checks since 2026-10-28) */
        const val SPEAKER_LABEL = "Report a problem: send DS Forever a problem report (the button under the top screen's checks, or Select)"
        const val LINE_H = 52f
        private val KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT)
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
