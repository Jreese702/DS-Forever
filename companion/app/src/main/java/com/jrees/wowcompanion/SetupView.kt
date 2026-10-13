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
 * THE SELF-SETUP'S SCREEN (Home, 2026-10-02; decisions in CompanionSetup, the work in SetupRunner). Over everything, the start screen
 * included. Two faces:
 *  - THE PROMPT: one game dialog, what the game lacks and what will happen ("The game closes, ... and the game starts again"), and two
 *    buttons, Set up / Not now. TOUCH ONLY: the controller drives the game at the same time, and an A meant for this would also press
 *    whatever the game shows (Enter World on the character screen).
 *  - THE WORK: the game's scene, the loading swirl and ONE line ("Closing the game…" / "Installing the companion…" / "Starting the
 *    game…"; the user: "it should say installing or something while it does that"); a failure says why in one line, with an OK.
 */
class SetupView(context: Context) : View(context) {

    var onSetUp: () -> Unit = {}
    var onNotNow: () -> Unit = {}
    var onDismiss: () -> Unit = {}

    private enum class Face { PROMPT, WORK, FAILED }
    private var face = Face.PROMPT
    private var title = ""
    private var bullets: List<String> = emptyList()
    private var footer = ""
    private var note = ""             // under the prompt's text (the fight refusal)
    private var line = ""
    private var pressed: String? = null
    private val hits = ArrayList<Pair<RectF, String>>()
    private val area = RectF()
    private val swirl = RectF()

    /** the setup's prompt: [Set up] -> onSetUp, [Not now] -> onNotNow */
    fun prompt(p: CompanionSetup.Prompt) = ask(p, "Set up", "Not now", { onSetUp() }, { onNotNow() })

    /**
     * ANY one-question prompt in the same look (the D-pad controls' "Use DS Forever D-pad controls?", 2026-10-02): a title, the
     * bullets, one closing line and two buttons, [yes] -> [onYes], [no] -> [onNo]. Touch only, as the setup's.
     */
    fun ask(p: CompanionSetup.Prompt, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
        face = Face.PROMPT; title = p.title; bullets = p.bullets; footer = p.footer; note = ""
        yesLabel = yes; noLabel = no; yesAct = onYes; noAct = onNo
        visibility = VISIBLE; invalidate()
    }
    /** b130 (the user, 2026-10-07: "When loading in on a new charactor can we put button glyphs on the setup prompt? A for yes b for no?"): the buttons wear A / B and the controller presses them - only while the app holds the controller for this
     *  prompt (MainActivity's setupPad), so an A never also reaches the game */
    var padOn = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** one controller press while [padOn]: A = the yes button, B = the no button (each shows pushed in a moment) */
    fun padKey(k: String) {
        if (!padOn || visibility != VISIBLE || face != Face.PROMPT) return
        val hit = when (k) { "PAD1" -> "setup"; "PAD2" -> "later"; else -> return }
        pressed = hit; invalidate()
        postDelayed({
            if (pressed == hit) { pressed = null; invalidate() }
            if (hit == "setup") yesAct() else noAct()
        }, 120L)
    }

    private fun aGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_a"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_cross"
        else -> "rev_a"
    }

    private var yesLabel = "Set up"
    private var noLabel = "Not now"
    private var yesAct: () -> Unit = {}
    private var noAct: () -> Unit = {}
    fun refuse(why: String) { note = why; invalidate() }
    fun work(l: String) { face = Face.WORK; line = l; visibility = VISIBLE; invalidate() }
    fun failed(l: String) { face = Face.FAILED; line = l; visibility = VISIBLE; invalidate() }
    fun close() { visibility = GONE }

    override fun onDraw(c: Canvas) {
        hits.clear()
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f
        if (face == Face.PROMPT) { drawPrompt(c, w, h, cx); return }
        area.set(0f, 0f, w, h)
        Scene.draw(c, area)
        // the start screen's loading swirl, in the same place (StartView's constants, UI Art's spec)
        val lineY = h - StartView.LINE_UP
        val cy = h - StartView.SWIRL_UP
        val r = StartView.SWIRL_D / 2f
        swirl.set(cx - r, cy - r, cx + r, cy + r)
        if (face == Face.WORK) {
            if (UiKit.swirlLoader(c, swirl, SystemClock.uptimeMillis(), StartView.STYLE)) postInvalidateDelayed(StartView.FRAME_MS)
            UiKit.outlined(c, line, cx, lineY, 34f, Theme.text)
        } else {
            UiKit.outlined(c, line, cx, cy, 34f, Theme.text)
            button(c, RectF(cx - 150f, lineY - 70f, cx + 150f, lineY + 41f), "OK", "ok")
        }
    }

    private fun drawPrompt(c: Canvas, w: Float, h: Float, cx: Float) {
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val tp = UiKit.bodyPaint(42f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        val lp = UiKit.bodyPaint(32f, Theme.text)                       // the bullets: left-aligned, one change each
        val dp = UiKit.bodyPaint(32f, Theme.gold)                       // the bullet mark
        val fp = UiKit.bodyPaint(28f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        val np = UiKit.bodyPaint(30f, Color.rgb(255, 120, 100)).apply { textAlign = Paint.Align.CENTER }
        val textW = BOX_W - 140f
        val tl = wrap(title, tp, textW)
        val bl = bullets.map { wrap(it, lp, textW - BULLET_IN) }
        val fl = if (footer.isEmpty()) emptyList() else wrap(footer, fp, textW)
        val nl = if (note.isEmpty()) emptyList() else wrap(note, np, textW)
        var ch = tl.size * 56f + 20f + bl.sumOf { it.size } * 44f + bl.size * 6f + 18f + fl.size * 38f + 40f + 111f
        if (nl.isNotEmpty()) ch += 16f + nl.size * 42f
        val boxH = ch + 2 * 50f + 36f
        val top = maxOf(30f, (h - boxH) / 2f)
        val box = RectF(cx - BOX_W / 2f, top, cx + BOX_W / 2f, top + boxH)
        UiKit.dialog(c, box)
        var y = box.top + 50f + 18f + 42f
        for (l in tl) { c.drawText(l, cx, y, tp); y += 56f }
        y += 20f
        val left = box.left + 70f
        for (lines in bl) {
            c.drawText("•", left, y, dp)
            for (l in lines) { c.drawText(l, left + BULLET_IN, y, lp); y += 44f }
            y += 6f
        }
        y += 18f
        for (l in fl) { c.drawText(l, cx, y, fp); y += 38f }
        if (nl.isNotEmpty()) { y += 16f; for (l in nl) { c.drawText(l, cx, y, np); y += 42f } }
        val by = box.bottom - 50f - 111f
        button(c, RectF(cx - 20f - 300f, by, cx - 20f, by + 111f), yesLabel, "setup")
        button(c, RectF(cx + 20f, by, cx + 20f + 300f, by + 111f), noLabel, "later")
    }

    private fun button(c: Canvas, r: RectF, label: String, hit: String) {
        val st = if (pressed == hit) UiKit.Button.DOWN else UiKit.Button.UP
        val glyph = if (!padOn || face != Face.PROMPT) null else when (hit) { "setup" -> aGlyph(); "later" -> UiKit.backGlyph(); else -> null }
        if (glyph != null) UiKit.button(c, r, label, glyph, st) else UiKit.button(c, r, label, st)
        hits.add(r to hit)
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in s.split(' ')) {
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(t) <= maxW || cur.isEmpty()) cur = t else { out.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
            MotionEvent.ACTION_UP -> {
                val was = pressed; pressed = null; invalidate()
                if (was != null && was == hit) when (hit) {
                    "setup" -> yesAct()
                    "later" -> noAct()
                    "ok" -> onDismiss()
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true      // it takes every touch while it shows: nothing under it is pressed by accident
    }

    private companion object {
        const val BOX_W = 900f
        const val BULLET_IN = 34f    // the bullet text's start in from the bullet
    }
}
