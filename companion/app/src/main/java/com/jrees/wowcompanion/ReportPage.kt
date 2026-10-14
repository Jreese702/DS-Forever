package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "SEND A PROBLEM REPORT", INSIDE THE BOTTOM SCREEN (board f-problem-report; the user: "can the report menu be part of the bottom app
 * UI instead of tabbing out"). Drawn in the rooms' look over the Settings room (or the setup screen before Play); typing with Chat's
 * own keyboard ([ChatKeyboard], the board the player knows: D-pad, A types, B closes), by controller or touch.
 * - the words are REQUIRED ([ReportFlow.canSend]) before Record 5 minutes or Send report;
 * - Record 5 minutes keeps the words with the recording and goes back to Settings; Send report saves the file and opens GitHub's
 *   pre-filled issue on the top screen (no browser: it says where the file is);
 * - "Show what's included" shows exactly what the file holds (already through [Redact]);
 * - B closes the keyboard, then the preview, then the page.
 * The controller: in the game the Settings room holds it (SettingsPad's guest pad: the game ignores it) and passes each press here
 * ([padKey]); before Play the launcher's keys come through SetupBridge ([key]).
 */
class ReportPage(c: Context) : FrameLayout(c) {

    /** the page's pure rules (the off-device tests drive them) */
    object Model {
        enum class Target { WORDS, RECORD, SEND, INCLUDED }

        /** the targets the cursor walks, in order; RECORD / SEND only when [canSend] */
        fun live(canSend: Boolean): List<Target> =
            if (canSend) listOf(Target.WORDS, Target.RECORD, Target.SEND, Target.INCLUDED) else listOf(Target.WORDS, Target.INCLUDED)

        /** a launcher key (before Play) as the guest pad's name, or null */
        fun keyName(code: Int): String? = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> "PADDUP"; KeyEvent.KEYCODE_DPAD_DOWN -> "PADDDOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "PADDLEFT"; KeyEvent.KEYCODE_DPAD_RIGHT -> "PADDRIGHT"
            KeyEvent.KEYCODE_BUTTON_A -> "PAD1"; KeyEvent.KEYCODE_BUTTON_B -> "PAD2"
            KeyEvent.KEYCODE_BUTTON_X -> "PAD3"; KeyEvent.KEYCODE_BUTTON_Y -> "PAD4"
            KeyEvent.KEYCODE_BUTTON_L1 -> "PADLSHOULDER"; KeyEvent.KEYCODE_BUTTON_R1 -> "PADRSHOULDER"
            KeyEvent.KEYCODE_BUTTON_L2 -> "PADLTRIGGER"; KeyEvent.KEYCODE_BUTTON_R2 -> "PADRTRIGGER"
            KeyEvent.KEYCODE_BUTTON_START -> "PADFORWARD"
            else -> null
        }

        fun recordLabel(recording: Boolean, leftMs: Long): String =
            if (recording) (leftMs / 1000).let { "Recording %d:%02d - Stop".format(it / 60, it % 60) } else "Record 5 minutes"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val kb = ChatKeyboard(c, swap = false).apply { visibility = View.GONE; sendWord = "Done" }
    private val previewText = TextView(c).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Theme.text); setPadding(28, 20, 28, 20) }
    private val preview = ScrollView(c).apply { visibility = View.GONE; addView(previewText) }

    var isOpen = false; private set
    /** the host: the page closed (B or done); Record 5 minutes started (back to Settings); open GitHub with this URL on the top screen */
    var onClosed: () -> Unit = {}
    var openUrl: (String) -> Boolean = { false }

    private var words = ""
    private var status = ""
    private var busy = false
    private var focus = Model.Target.WORDS
    private val boxes = HashMap<Model.Target, RectF>()

    init {
        setWillNotDraw(false)
        kb.text = { words }
        kb.onText = { t -> words = t.take(MAX_WORDS); invalidate() }
        kb.onSend = { closeKeyboard() }
        kb.onClose = { closeKeyboard() }
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = 110 })
        addView(kb, LayoutParams(LayoutParams.MATCH_PARENT, ChatKeyboard.fullHeight(), android.view.Gravity.BOTTOM))
        visibility = View.GONE
    }

    /** opens the page; [sendNow] (Settings' "Send report" while recording) sends at once with the words kept with the recording */
    fun open(sendNow: Boolean = false) {
        ReportFlow.init(context)
        isOpen = true; visibility = View.VISIBLE
        words = ReportFlow.description.ifEmpty { words }
        status = ""; focus = Model.Target.WORDS
        preview.visibility = View.GONE
        tick.run()
        if (sendNow) send() else if (!ReportFlow.canSend(words)) openKeyboard()
        invalidate()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false; visibility = View.GONE
        kb.visibility = View.GONE; preview.visibility = View.GONE
        ui.removeCallbacks(tick)
        onClosed()
    }

    private val tick = object : Runnable {
        override fun run() { if (!isOpen) return; invalidate(); ui.postDelayed(this, 1000) }
    }

    private fun openKeyboard() { kb.open(); kb.visibility = View.VISIBLE; invalidate() }
    private fun closeKeyboard() { kb.visibility = View.GONE; focus = if (ReportFlow.canSend(words)) Model.Target.SEND else Model.Target.WORDS; invalidate() }

    // ------------------------------------------------------------------ input

    /** a launcher key before Play (SetupBridge): true = the page's (every key while it is open) */
    fun key(code: Int, down: Boolean): Boolean {
        if (!isOpen) return false
        if (down) Model.keyName(code)?.let { padKey(it) }
        return true
    }

    /** one controller press by the guest pad's name (PADDUP .. PAD4, the shoulders, the triggers, PADFORWARD) */
    fun padKey(k: String) {
        if (!isOpen) return
        if (kb.visibility == View.VISIBLE) { kb.press(k); return }
        if (preview.visibility == View.VISIBLE) {
            when (k) {
                "PAD2", "PAD1" -> { preview.visibility = View.GONE; invalidate() }
                "PADDUP" -> preview.smoothScrollBy(0, -400)
                "PADDDOWN" -> preview.smoothScrollBy(0, 400)
            }
            return
        }
        val live = Model.live(ReportFlow.canSend(words))
        val i = live.indexOf(focus).coerceAtLeast(0)
        when (k) {
            "PADDUP", "PADDLEFT" -> focus = live[(i - 1 + live.size) % live.size]
            "PADDDOWN", "PADDRIGHT" -> focus = live[(i + 1) % live.size]
            "PAD1" -> press(live.getOrElse(i) { live[0] })
            "PAD2" -> close()
        }
        invalidate()
    }

    private fun press(t: Model.Target) {
        if (busy) return
        when (t) {
            Model.Target.WORDS -> openKeyboard()
            Model.Target.RECORD -> if (DiagLog.recording()) { DiagLog.stopRecording(); status = "Recording stopped. Send the report when you're ready." }
                else {
                    ReportFlow.description = words.trim()
                    DiagLog.startRecording()
                    status = "Recording. Reproduce the problem; it is saved by itself after 5 minutes."
                    ui.postDelayed({ close() }, 1500)
                }
            Model.Target.SEND -> send()
            Model.Target.INCLUDED -> make { r -> previewText.text = r.preview; preview.scrollTo(0, 0); preview.visibility = View.VISIBLE; status = "" }
        }
        invalidate()
    }

    /** Send report: the recording stops, the file is saved, GitHub's pre-filled issue opens on the top screen */
    private fun send() {
        if (!ReportFlow.canSend(words)) { status = "Say what happened first."; openKeyboard(); return }
        DiagLog.stopRecording()
        make { r ->
            val f = ProblemReport.save(context, r)
            if (f == null) { status = "The report could not be saved."; return@make }
            val opened = openUrl(ProblemReport.issueUrl(r, words, ProblemReport.facts(context), f.path, Redact.names()))
            status = ProblemReport.sentMessage(f.path, opened)
            ReportFlow.description = ""
            DiagLog.event("report", "sent by the player (${if (opened) "GitHub opened" else "no browser"})")
        }
    }

    private fun make(then: (ProblemReport.Report) -> Unit) {
        if (busy) return
        busy = true; status = "Making the report…"; invalidate()
        val description = words; val ctx = context.applicationContext
        Thread({
            val r = runCatching { ProblemReport.collect(ctx, description) }
            ui.post {
                busy = false
                r.onSuccess { status = ""; then(it) }.onFailure { status = "The report could not be made: ${it.message}" }
                invalidate()
            }
        }, "problem-report").start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && kb.visibility != View.VISIBLE && preview.visibility != View.VISIBLE) {
            val live = Model.live(ReportFlow.canSend(words))
            boxes.entries.firstOrNull { it.key in live && it.value.contains(e.x, e.y) }?.let { focus = it.key; press(it.key) }
        }
        return true                                         // the page owns every touch while it is open
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        UiKit.cover(c, RectF(0f, 0f, w, height.toFloat()), 0.9f)
        val title = UiKit.titlePaint(44f)
        c.drawText("Send a problem report", PAD, 70f, title)
        if (preview.visibility == View.VISIBLE) {
            UiKit.bodyPaint(24f, Theme.textDim).let { c.drawText("Everything the report holds. B goes back.", PAD, 100f, it) }
            return
        }
        val can = ReportFlow.canSend(words)
        boxes.clear()
        // the words: the box the keyboard types into
        val body = UiKit.bodyPaint(30f)
        val box = RectF(PAD, 110f, w - PAD, 110f + BOX_H)
        boxes[Model.Target.WORDS] = box
        UiKit.border(c, box)
        val shown = words.ifEmpty { "What were you doing when it went wrong?" }
        body.color = if (words.isEmpty()) Theme.textDim else Theme.text
        var y = box.top + 46f
        for (line in SetupPanel.wrap(shown + if (kb.visibility == View.VISIBLE) "_" else "", body, box.width() - 40f).takeLast(4)) {
            c.drawText(line, box.left + 20f, y, body); y += 40f
        }
        if (kb.visibility == View.VISIBLE) { if (focus == Model.Target.WORDS) UiKit.focus(c, inset(box)); return }   // the board below
        // Record and Send
        var top = box.bottom + 30f
        val half = (w - PAD * 2 - GAP) / 2f
        val rec = RectF(PAD, top, PAD + half, top + BUTTON_H)
        val send = RectF(rec.right + GAP, top, w - PAD, top + BUTTON_H)
        boxes[Model.Target.RECORD] = rec; boxes[Model.Target.SEND] = send
        fun state(t: Model.Target) = if (!can || busy) UiKit.Button.DISABLED else UiKit.Button.UP
        UiKit.button(c, rec, Model.recordLabel(DiagLog.recording(), DiagLog.recordLeftMs()), state(Model.Target.RECORD))
        UiKit.button(c, send, "Send report", state(Model.Target.SEND))
        top = rec.bottom + 46f
        // the small link
        val link = UiKit.bodyPaint(26f, Theme.gold).apply { isUnderlineText = true }
        c.drawText("Show what's included", PAD, top, link)
        boxes[Model.Target.INCLUDED] = RectF(PAD - 8f, top - 34f, PAD + link.measureText("Show what's included") + 8f, top + 12f)
        // the line under it: what happened, or why the buttons wait
        val note = status.ifEmpty { if (!can) "Say what happened first (a few words)." else "Logins, names and chat are taken out. B closes." }
        val small = UiKit.bodyPaint(24f, Theme.textDim)
        var ny = top + 54f
        for (part in note.split('\n')) for (line in SetupPanel.wrap(part, small, w - PAD * 2)) { c.drawText(line, PAD, ny, small); ny += 34f }
        boxes[focus]?.let { UiKit.focus(c, inset(it)) }
    }

    private fun inset(r: RectF) = RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f)

    companion object {
        private const val PAD = 40f
        private const val GAP = 24f
        private const val BOX_H = 200f
        private const val BUTTON_H = 90f
        private const val MAX_WORDS = 1500
    }
}
