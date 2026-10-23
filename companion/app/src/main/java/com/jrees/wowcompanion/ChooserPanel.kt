package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * THE START-UP CHOOSER ON THE BOTTOM SCREEN (S1 "Chooser", 2026-10-23; STREAMING_PLAN.md 1.1): two large options, LOCAL and STREAMING,
 * each with an "Add home-screen shortcut" button under it ([StartMode] has the rules, [ModeBridge] the live route). It also draws
 * Streaming's placeholder page (until S2's link covers it: [ModeBridge.streamCovers]).
 *
 * Controller first, as [SetupPanel]: the D-pad moves the gold box ([UiKit.focus], never an outline alone) between the two cards and
 * their shortcut buttons ([StartMode.move]), A presses the one in the box (the A glyph on a boxed button), B on the Streaming page goes
 * back to the chooser. Touch presses any. The keys come through [ModeBridge.key] (the launcher's activity has them before Play).
 */
class ChooserPanel(context: Context) : View(context) {
    private enum class Page { NONE, CHOOSER, STREAM }

    private var page = Page.NONE
    private var cell = StartMode.startCell(null)
    private var pressed: Any? = null
    /** the last shortcut request's line ("" = none) */
    private var note = ""
    private val area = RectF()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    /** what a touch can press, as last drawn: a chooser cell, or BACK on the Streaming page */
    private val hits = ArrayList<Pair<RectF, Any>>()
    private object Back

    private val refresh: () -> Unit = { post { update() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ModeBridge.onChange(refresh)
        ModeBridge.keyHandler = { code, down -> key(code, down) }
        update()
    }

    override fun onDetachedFromWindow() {
        ModeBridge.offChange(refresh)
        if (ModeBridge.keyHandler != null) ModeBridge.keyHandler = null
        super.onDetachedFromWindow()
    }

    fun update() {
        val r = ModeBridge.route
        val was = page
        page = when {
            r == StartMode.Route.Chooser -> Page.CHOOSER
            r is StartMode.Route.Into && r.mode == StartMode.Mode.STREAM && ModeBridge.streamCovers && !LauncherHooks.gameUp() -> Page.STREAM
            else -> Page.NONE
        }
        // the chooser comes (back): the box starts on the remembered pick's card, no old line
        if (page == Page.CHOOSER && was != Page.CHOOSER) { cell = StartMode.startCell(ModeBridge.rememberedNow()); note = "" }
        visibility = if (page == Page.NONE) GONE else VISIBLE
        contentDescription = when (page) {
            Page.CHOOSER -> "DS Forever: choose Local or Streaming"
            Page.STREAM -> "DS Forever Streaming: connect to DS Forever Desktop"
            Page.NONE -> null
        }
        invalidate()
    }

    /** the chooser or the Streaming page shows (CompanionHost: the pad and B belong to it then) */
    val active: Boolean get() = visibility == VISIBLE

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        Scene.draw(c, area)
        hits.clear()
        when (page) {
            Page.CHOOSER -> drawChooser(c)
            Page.STREAM -> drawStream(c)
            Page.NONE -> Unit
        }
    }

    private fun drawChooser(c: Canvas) {
        val cx = width / 2f
        val title = UiKit.titlePaint(56f).apply { textAlign = Paint.Align.CENTER }
        c.drawText("How do you want to play?", cx, MARGIN + 62f, title)
        val top = MARGIN + 110f
        val noteH = LINE_H
        val btnTop = height - MARGIN - noteH - BUTTON_H
        val colW = (width - MARGIN * 2 - GAP * 2) / 2f
        for ((i, mode) in StartMode.Mode.values().withIndex()) {
            val left = MARGIN + i * (colW + GAP * 2)
            val card = RectF(left, top, left + colW, btnTop - GAP * 2)
            val btn = RectF(left, btnTop, left + colW, btnTop + BUTTON_H)
            val cardCell = StartMode.Cell(mode, StartMode.Row.CARD)
            val btnCell = StartMode.Cell(mode, StartMode.Row.SHORTCUT)
            hits += card to cardCell
            hits += btn to btnCell
            if (cell == cardCell) box(c, card)
            drawCard(c, card, mode, pressed == cardCell)
            if (cell == btnCell) box(c, btn)
            val state = if (pressed == btnCell) UiKit.Button.DOWN else UiKit.Button.UP
            if (cell == btnCell) UiKit.button(c, btn, "Add home-screen shortcut", StartView.GLYPH_A, state)
            else UiKit.button(c, btn, "Add home-screen shortcut", state)
        }
        if (note.isNotEmpty()) UiKit.outlined(c, note, cx, height - MARGIN - 12f, 30f, Theme.text)
    }

    /** one option: the game's window ground and gold border, the mode's name, its picture and one plain line */
    private fun drawCard(c: Canvas, r: RectF, mode: StartMode.Mode, down: Boolean) {
        val k = if (down) 0.97f else 1f
        val d = RectF(r.centerX() - r.width() * k / 2f, r.centerY() - r.height() * k / 2f, r.centerX() + r.width() * k / 2f, r.centerY() + r.height() * k / 2f)
        pFill.style = Paint.Style.FILL
        pFill.color = Color.argb(if (down) 245 else 230, 18, 14, 10)
        c.drawRoundRect(d, 14f, 14f, pFill)
        UiKit.border(c, d)
        val title = UiKit.titlePaint(64f).apply { textAlign = Paint.Align.CENTER }
        c.drawText(mode.title, d.centerX(), d.top + 96f, title)
        // the picture, in the middle of what is left
        val body = UiKit.bodyPaint(30f).apply { textAlign = Paint.Align.CENTER }
        val lines = SetupPanel.wrap(blurb(mode), body, d.width() - PAD * 2)
        val textH = lines.size * 40f
        val picTop = d.top + 130f
        val picBottom = d.bottom - PAD - textH - 20f
        val s = minOf(d.width() - PAD * 2, picBottom - picTop) * 0.8f
        if (s > 40f) picture(c, mode, d.centerX(), (picTop + picBottom) / 2f, s)
        var y = d.bottom - PAD - textH + 30f
        for (l in lines) { c.drawText(l, d.centerX(), y, body); y += 40f }
    }

    private fun blurb(mode: StartMode.Mode) = when (mode) {
        StartMode.Mode.LOCAL -> "WoW Forever runs on this device."
        StartMode.Mode.STREAM -> "WoW runs on your PC. DS Forever Desktop streams it here."
    }

    /** our own vectors in the kit's gold: LOCAL = this two-screen handheld, STREAMING = a PC screen sending its picture */
    private fun picture(c: Canvas, mode: StartMode.Mode, cx: Float, cy: Float, s: Float) {
        pLine.color = Theme.gold; pLine.strokeWidth = maxOf(4f, s / 40f)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(70, 255, 209, 0)
        when (mode) {
            StartMode.Mode.LOCAL -> {
                val w = s * 0.62f; val h = s * 0.42f
                val topR = RectF(cx - w / 2f, cy - h - s * 0.02f, cx + w / 2f, cy - s * 0.02f)
                val botR = RectF(cx - w / 2f, cy + s * 0.04f, cx + w / 2f, cy + s * 0.04f + h)
                for (r in listOf(topR, botR)) { c.drawRoundRect(r, s * 0.05f, s * 0.05f, pFill); c.drawRoundRect(r, s * 0.05f, s * 0.05f, pLine) }
                // the bottom half's pad: a cross and two buttons
                val py = botR.centerY(); val pl = botR.left + w * 0.2f; val arm = h * 0.18f
                c.drawLine(pl - arm, py, pl + arm, py, pLine); c.drawLine(pl, py - arm, pl, py + arm, pLine)
                pFill.color = Theme.gold
                c.drawCircle(botR.right - w * 0.2f, py - arm * 0.6f, arm * 0.35f, pFill)
                c.drawCircle(botR.right - w * 0.2f - arm, py + arm * 0.4f, arm * 0.35f, pFill)
            }
            StartMode.Mode.STREAM -> {
                val w = s * 0.7f; val h = s * 0.46f
                val scr = RectF(cx - w / 2f, cy - h * 0.75f, cx + w / 2f, cy + h * 0.25f)
                c.drawRoundRect(scr, s * 0.03f, s * 0.03f, pFill); c.drawRoundRect(scr, s * 0.03f, s * 0.03f, pLine)
                c.drawLine(cx, scr.bottom, cx, scr.bottom + s * 0.1f, pLine)
                c.drawLine(cx - s * 0.14f, scr.bottom + s * 0.1f, cx + s * 0.14f, scr.bottom + s * 0.1f, pLine)
                // the picture leaving it: three arcs to the right
                for ((i, rad) in listOf(0.1f, 0.18f, 0.26f).withIndex()) {
                    pLine.alpha = 255 - i * 60
                    val ax = scr.right + s * 0.02f
                    c.drawArc(RectF(ax - rad * s, scr.centerY() - rad * s, ax + rad * s, scr.centerY() + rad * s), -40f, 80f, false, pLine)
                }
                pLine.alpha = 255
            }
        }
    }

    private fun drawStream(c: Canvas) {
        val w = (width - MARGIN * 2).coerceAtMost(CARD_W)
        val card = RectF((width - w) / 2f, MARGIN + 60f, (width + w) / 2f, height - MARGIN - 60f)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(235, 18, 14, 10)
        c.drawRoundRect(card, 14f, 14f, pFill)
        UiKit.border(c, card)
        val title = UiKit.titlePaint(56f).apply { textAlign = Paint.Align.CENTER }
        c.drawText("Streaming", card.centerX(), card.top + PAD + 52f, title)
        val body = UiKit.bodyPaint(30f).apply { textAlign = Paint.Align.CENTER }
        var y = card.top + PAD + 120f
        val text = "Connect to DS Forever Desktop. Type the code it shows on your PC on the top screen."
        for (l in SetupPanel.wrap(text, body, card.width() - PAD * 2)) { c.drawText(l, card.centerX(), y, body); y += 40f }
        if (ModeBridge.streamNote.isNotEmpty()) {
            y += 20f
            val dim = UiKit.bodyPaint(28f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
            for (l in SetupPanel.wrap(ModeBridge.streamNote, dim, card.width() - PAD * 2)) { c.drawText(l, card.centerX(), y, dim); y += 38f }
        }
        picture(c, StartMode.Mode.STREAM, card.centerX(), (y + card.bottom - PAD - BUTTON_H) / 2f + 20f,
            minOf(card.width() * 0.5f, (card.bottom - PAD - BUTTON_H - y) * 0.8f).coerceAtLeast(0f))
        val btn = RectF(card.left + PAD, card.bottom - PAD - BUTTON_H, card.right - PAD, card.bottom - PAD)
        hits += btn to Back
        box(c, btn)
        UiKit.button(c, btn, "Back to the chooser", UiKit.backGlyph(), if (pressed == Back) UiKit.Button.DOWN else UiKit.Button.UP)
    }

    /** the gold box, as SetupPanel's: before the kit's highlight art exists (a fresh install) a plain warm wash under the gold edge */
    private fun box(c: Canvas, r: RectF) {
        if (UiKit.tex("highlight") == null) {
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(110, 226, 170, 80)
            c.drawRoundRect(RectF(r.left - 5f, r.top - 5f, r.right + 5f, r.bottom + 5f), 8f, 8f, pFill)
        }
        UiKit.focus(c, RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f))
    }

    // ------------------------------------------------------------------ input

    private fun act(what: Any) {
        when (what) {
            Back -> ModeBridge.back("the Streaming page's Back")
            is StartMode.Cell -> when (val p = StartMode.press(what)) {
                is StartMode.Press.Pick -> ModeBridge.pick(p.mode)
                is StartMode.Press.AddShortcut -> { note = ModeBridge.addShortcut(context, p.mode); invalidate() }
            }
        }
    }

    /** one key from a game controller (the launcher's activity, before Play; true = the panel's) */
    fun key(code: Int, down: Boolean): Boolean {
        if (visibility != VISIBLE) return false
        if (code !in KEYS) return false
        if (!down) return true
        when (page) {
            Page.CHOOSER -> when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> cell = StartMode.move(cell, StartMode.Dir.LEFT)
                KeyEvent.KEYCODE_DPAD_RIGHT -> cell = StartMode.move(cell, StartMode.Dir.RIGHT)
                KeyEvent.KEYCODE_DPAD_UP -> cell = StartMode.move(cell, StartMode.Dir.UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> cell = StartMode.move(cell, StartMode.Dir.DOWN)
                KeyEvent.KEYCODE_BUTTON_A -> act(cell)
                // B on the chooser: nothing to go back to (swallowed: never an accidental exit of the app)
            }
            Page.STREAM -> if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B) act(Back)
            Page.NONE -> return false
        }
        invalidate()
        return true
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
            MotionEvent.ACTION_UP -> {
                val was = pressed; pressed = null; invalidate()
                if (was != null && was == hit) {
                    if (was is StartMode.Cell) cell = was
                    act(was)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true          // the whole screen is the chooser's while it shows (nothing under it is pressed by accident)
    }

    companion object {
        const val MARGIN = 40f
        const val PAD = 36f
        const val GAP = 18f
        const val CARD_W = 980f
        const val BUTTON_H = SetupPanel.BUTTON_H
        const val LINE_H = 56f
        private val KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B)
    }
}
