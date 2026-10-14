package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * THE REPORT'S NOTICE ON HOME (board f-problem-report; the user after batch16: Send "should close this menu and take the player back
 * home with a notice that says something like 'report compiling' and an okay or dismiss button"). A card over Home: "Compiling your
 * report…" while it is built off the main thread, then "Report ready" with Open issue / OK, or "Couldn't save the report: <why>" with
 * OK. Touch, or the controller (Home's pad copy: A presses, left / right switch, B is OK); the game's A is held back while it asks.
 */
class ReportNotice(c: Context) : View(c) {

    sealed class State {
        object Compiling : State()
        object Sending : State()
        data class Sent(val number: Int, val url: String) : State()
        data class SendFailed(val why: String, val path: String, val url: String) : State()
        data class Ready(val path: String, val url: String) : State()
        data class Failed(val why: String) : State()
    }

    /** the words and buttons for a state (pure: the tests read it) */
    data class Card(val title: String, val text: String, val buttons: List<String>)

    companion object {
        const val OPEN = "Open issue"
        const val OK = "OK"
        fun card(s: State): Card = when (s) {
            State.Compiling -> Card("Compiling your report…", "You can keep playing.", emptyList())
            State.Sending -> Card("Sending your report…", "You can keep playing.", emptyList())
            is State.Sent -> Card("Report sent: issue #${s.number}", "Thank you. The DS Forever team can see it now.", listOf(OK))
            is State.SendFailed -> Card("Couldn't send the report", "${s.why}. Saved to Documents/DS Forever/reports.", listOf(OPEN, OK))
            is State.Ready -> Card("Report ready", "Saved in Documents/DS Forever/reports. Open the GitHub issue to post it.", listOf(OPEN, OK))
            is State.Failed -> Card("Couldn't save the report", s.why, listOf(OK))
        }
    }

    var state: State? = null
        private set
    /** the host: Open issue (the URL on the top screen); OK / dismiss */
    var onOpen: (State.Ready) -> Unit = {}
    var onDismiss: () -> Unit = {}
    /** Couldn't send > Open issue: the pre-filled issue in the browser */
    var onOpenUrl: (String) -> Unit = {}

    private var focus = 0
    private val boxes = ArrayList<Pair<RectF, String>>()

    init { visibility = GONE }

    fun show(s: State) { state = s; focus = 0; visibility = VISIBLE; invalidate() }
    fun hide() { state = null; visibility = GONE }
    /** it asks for a press (Ready / Failed): the game's A is held back meanwhile */
    val asking: Boolean get() = state != null && state !is State.Compiling && state !is State.Sending

    private fun pressed(label: String) {
        val s = state ?: return
        if (label == OPEN && s is State.Ready) onOpen(s)
        if (label == OPEN && s is State.SendFailed) onOpenUrl(s.url)
        hide(); onDismiss()
    }

    /** one press by the guest pad's name; true = the notice's */
    fun padKey(k: String): Boolean {
        val s = state ?: return false
        val buttons = card(s).buttons
        if (buttons.isEmpty()) return false
        when (k) {
            "PADDLEFT" -> focus = (focus - 1 + buttons.size) % buttons.size
            "PADDRIGHT" -> focus = (focus + 1) % buttons.size
            "PAD1" -> pressed(buttons[focus.coerceIn(0, buttons.size - 1)])
            "PAD2" -> pressed(OK)
            else -> return false
        }
        invalidate()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (state == null) return false
        val hit = boxes.firstOrNull { it.first.contains(e.x, e.y) }
        if (e.actionMasked == MotionEvent.ACTION_UP) hit?.let { pressed(it.second) }
        return hit != null || e.y >= cardTop                   // only the card takes touches; Home stays usable above it
    }

    private var cardTop = Float.MAX_VALUE

    override fun onDraw(c: Canvas) {
        val s = state ?: return
        val card = card(s)
        val w = width.toFloat()
        val cw = minOf(w - 80f, 920f)
        val left = (w - cw) / 2f
        val h = 150f + (if (card.buttons.isEmpty()) 0f else 100f)
        val bottom = height - Strip.HEIGHT - 24f
        val r = RectF(left, bottom - h, left + cw, bottom)
        cardTop = r.top
        UiKit.cover(c, r, 0.92f)
        UiKit.border(c, r)
        val title = UiKit.titlePaint(36f).apply { textAlign = android.graphics.Paint.Align.CENTER }
        c.drawText(card.title, r.centerX(), r.top + 56f, title)
        val body = UiKit.bodyPaint(24f, Theme.textDim).apply { textAlign = android.graphics.Paint.Align.CENTER }
        var y = r.top + 96f
        for (line in SetupPanel.wrap(card.text, body, cw - 60f).take(2)) { c.drawText(line, r.centerX(), y, body); y += 32f }
        boxes.clear()
        if (card.buttons.isNotEmpty()) {
            val bw = 260f; val gap = 24f
            var x = r.centerX() - (card.buttons.size * bw + (card.buttons.size - 1) * gap) / 2f
            for ((i, label) in card.buttons.withIndex()) {
                val b = RectF(x, r.bottom - 96f, x + bw, r.bottom - 24f)
                boxes += b to label
                if (i == focus) UiKit.button(c, b, label, StartView.GLYPH_A, UiKit.Button.UP) else UiKit.button(c, b, label, UiKit.Button.UP)
                if (i == focus) UiKit.focus(c, RectF(b.left - 6f, b.top - 6f, b.right + 6f, b.bottom + 6f))
                x += bw + gap
            }
        }
    }
}
