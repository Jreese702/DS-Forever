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
            KeyEvent.KEYCODE_BUTTON_A -> "PAD1"
            // B, and a pad whose B arrives as Android's Back (b-setup-report-cursor: "it needs a way to back out of it")
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> "PAD2"
            KeyEvent.KEYCODE_BUTTON_X -> "PAD3"; KeyEvent.KEYCODE_BUTTON_Y -> "PAD4"
            KeyEvent.KEYCODE_BUTTON_L1 -> "PADLSHOULDER"; KeyEvent.KEYCODE_BUTTON_R1 -> "PADRSHOULDER"
            KeyEvent.KEYCODE_BUTTON_L2 -> "PADLTRIGGER"; KeyEvent.KEYCODE_BUTTON_R2 -> "PADRTRIGGER"
            KeyEvent.KEYCODE_BUTTON_START -> "PADFORWARD"
            else -> null
        }

        /**
         * The words box's lines with where each starts in the text (issue #11: "cant go back to previously entered text to fix missed
         * typo": the caret needs to know where it is drawn and where a tap lands). Words wrap at spaces as SetupPanel.wrap does.
         */
        fun wrapIndexed(text: String, width: Float, measure: (String) -> Float): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            var lineStart = 0
            var line = ""
            var i = 0
            while (true) {
                val end = text.indexOf(' ', i).let { if (it < 0) text.length else it }
                val word = text.substring(i, end)
                if (i == lineStart) line = word
                else {
                    val next = "$line $word"
                    if (measure(next) > width) { out += lineStart to line; lineStart = i; line = word } else line = next
                }
                if (end >= text.length) break
                i = end + 1
            }
            out += lineStart to line
            return out
        }

        /** the keyboard typed: [prefix] is the new text before the caret; what was after the caret stays (issue #11) */
        fun edit(words: String, caret: Int, prefix: String, max: Int): Pair<String, Int> {
            val tail = words.substring(caret.coerceIn(0, words.length))
            val w = (prefix + tail).take(max)
            return w to minOf(prefix.length, w.length)
        }

        /** the caret's line in [lines] (the last line it can be on) */
        fun caretLine(lines: List<Pair<Int, String>>, caret: Int): Int =
            lines.indexOfLast { (start, _) -> start <= caret }.coerceAtLeast(0)

        /** the text index a tap at [x] (from the line's left) lands on, in [line] */
        fun caretAt(line: Pair<Int, String>, x: Float, measure: (String) -> Float): Int {
            val (start, t) = line
            var best = 0; var bestD = Float.MAX_VALUE
            for (j in 0..t.length) { val d = kotlin.math.abs(measure(t.substring(0, j)) - x); if (d < bestD) { bestD = d; best = j } }
            return start + best
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
    /** Send report: the host builds and saves it off the main thread with its notice on Home (the page closes at once) */
    var onSend: (String) -> Unit = {}

    private var words = ""
    /** where the keyboard types (issue #11: LB / RB move it, a tap places it); the keyboard edits the text BEFORE it */
    private var caret = 0
    private var shownLines: List<Pair<Int, String>> = emptyList()
    private var shownFrom = 0
    private var lineX = 0f
    private var lineTop = 0f
    private var status = ""
    private var busy = false
    private var focus = Model.Target.WORDS
    /** the keyboard's slide (0 = down, 1 = up), Chat's own motion; the words box and the buttons ride above it */
    private var kbK = 0f
    private var kbAnim: android.animation.ValueAnimator? = null
    /** with the keyboard up: the cursor is on the keyboard (true) or on the buttons above it (D-pad up from its top row) */
    private var inKeys = true
    private val boxes = HashMap<Model.Target, RectF>()

    init {
        setWillNotDraw(false)
        // the keyboard sees only the text before the caret; what is after it stays (typing and Back work mid-text; issue #11)
        kb.text = { words.substring(0, caret.coerceIn(0, words.length)) }
        kb.onText = { t ->                                                 // every key: the buttons light up as soon as the rule is met
            Model.edit(words, caret, t, MAX_WORDS).let { (w, c) -> words = w; caret = c }
            invalidate()
        }
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
        caret = words.length
        status = ""; focus = Model.Target.WORDS
        preview.visibility = View.GONE
        tick.run()
        if (sendNow) send() else if (!ReportFlow.canSend(words)) openKeyboard()
        invalidate()
    }

    fun close() {
        if (!isOpen) return
        // backing out keeps what was typed as the unsent draft (ReportFlow.description; a send clears it), even across a restart
        if (words.isNotBlank()) ReportFlow.description = words.trim()
        isOpen = false; visibility = View.GONE
        kbAnim?.cancel(); kbK = 0f; kb.visibility = View.GONE; preview.visibility = View.GONE
        ui.removeCallbacks(tick)
        onClosed()
    }

    private val tick = object : Runnable {
        override fun run() { if (!isOpen) return; invalidate(); ui.postDelayed(this, 1000) }
    }

    private fun openKeyboard() { kb.open(); inKeys = true; slide(1f) }
    private fun closeKeyboard() { slide(0f); inKeys = true; focus = if (ReportFlow.canSend(words)) Model.Target.SEND else Model.Target.WORDS; invalidate() }
    private val kbUp get() = kb.visibility == View.VISIBLE && kbK > 0f

    /** the keyboard slides up (1) or down (0) with Chat's curve; the page lays itself out above it on every frame */
    private fun slide(to: Float) {
        kbAnim?.cancel()
        if (to > 0f) kb.visibility = View.VISIBLE
        kbAnim = android.animation.ValueAnimator.ofFloat(kbK, to).apply {
            duration = SLIDE_MS
            interpolator = Motion.curve()
            addUpdateListener { kbK = it.animatedValue as Float; kb.translationY = (1f - kbK) * kb.height.coerceAtLeast(1); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (kbK == 0f) kb.visibility = View.GONE; invalidate() }
            })
            start()
        }
    }

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
        if (kbUp) { keysWithBoard(k); return }
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

    /**
     * The keyboard is up: on the keyboard every key types, except D-pad up from its top row, which goes to the buttons above it
     * (Record / Send report, or the words box while nothing is said yet); there left / right walk them, A presses, down goes back to
     * the keyboard, B closes the keyboard.
     */
    private fun keysWithBoard(k: String) {
        if (inKeys) {
            if (k == "PADDUP" && kb.keys.row == 0) {
                inKeys = false
                focus = if (ReportFlow.canSend(words)) Model.Target.RECORD else Model.Target.WORDS
                invalidate(); return
            }
            // LB / RB move the caret (the keyboard passes them on); everything else types
            when (k) {
                "PADLSHOULDER" -> { caret = (caret - 1).coerceAtLeast(0); invalidate() }
                "PADRSHOULDER" -> { caret = (caret + 1).coerceAtMost(words.length); invalidate() }
                else -> kb.press(k)
            }
            return
        }
        val row = if (ReportFlow.canSend(words)) listOf(Model.Target.RECORD, Model.Target.SEND) else listOf(Model.Target.WORDS)
        val i = row.indexOf(focus).coerceAtLeast(0)
        when (k) {
            "PADDLEFT" -> focus = row[(i - 1 + row.size) % row.size]
            "PADDRIGHT" -> focus = row[(i + 1) % row.size]
            "PADDDOWN" -> inKeys = true
            "PAD1" -> if (focus == Model.Target.WORDS) inKeys = true else { val t = focus; closeKeyboard(); focus = t; press(t) }
            "PAD2" -> closeKeyboard()
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
    /** Send report (the user after batch16): the page closes at once and Home's notice follows the build ("Compiling your report…") */
    private fun send() {
        if (!ReportFlow.canSend(words)) { status = "Say what happened first."; openKeyboard(); return }
        DiagLog.stopRecording()
        val w = words.trim()
        close()
        onSend(w)
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
        // the on-screen Back (touch): the keyboard, then the preview, then the page, as B does
        if (e.actionMasked == MotionEvent.ACTION_UP && backBox.contains(e.x, e.y)) {
            when {
                kbUp -> closeKeyboard()
                preview.visibility == View.VISIBLE -> { preview.visibility = View.GONE; invalidate() }
                else -> close()
            }
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_UP && preview.visibility != View.VISIBLE) {
            val live = Model.live(ReportFlow.canSend(words))
            // a tap in the words box while typing places the caret there (issue #11)
            val wordsBox = boxes[Model.Target.WORDS]
            if (kbUp && wordsBox != null && wordsBox.contains(e.x, e.y) && shownLines.isNotEmpty() && words.isNotEmpty()) {
                val i = ((e.y - lineTop) / LINE_H).toInt().coerceIn(0, shownLines.size - 1)
                caret = Model.caretAt(shownLines[i], e.x - lineX) { textPaint.measureText(it) }.coerceIn(0, words.length)
                inKeys = true
                invalidate()
                return true
            }
            boxes.entries.firstOrNull { it.key in live && it.value.contains(e.x, e.y) }?.key?.let { t ->
                val up = kbUp
                if (up && t != Model.Target.WORDS) closeKeyboard()
                focus = t
                if (!(up && t == Model.Target.WORDS)) press(t)
            }
        }
        return true                                         // the page owns every touch while it is open
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        UiKit.cover(c, RectF(0f, 0f, w, height.toFloat()), 0.9f)
        // the rest layout (box 110..310, buttons under it) and, with the keyboard up, the same pieces right above the board
        val k = if (kb.visibility == View.VISIBLE) kbK else 0f
        val boardTop = height - ChatKeyboard.fullHeight().toFloat()
        val upButtonsTop = boardTop - 16f - BUTTON_H
        val upBoxBottom = upButtonsTop - 20f
        val upBoxTop = maxOf(20f + BACK_H + 12f, upBoxBottom - BOX_UP_H)      // under the Back button
        val boxTop = lerp(110f, upBoxTop, k)
        val boxBottom = lerp(110f + BOX_H, upBoxBottom, k)
        val buttonsTop = lerp(110f + BOX_H + 30f, upButtonsTop, k)
        if (boxTop > 80f) c.drawText("Send a problem report", PAD, 70f, UiKit.titlePaint(44f))
        drawBack(c)
        if (preview.visibility == View.VISIBLE) {
            UiKit.bodyPaint(24f, Theme.textDim).let { c.drawText("Everything the report holds. B goes back.", PAD, 100f, it) }
            drawBack(c)
            return
        }
        val can = ReportFlow.canSend(words)
        boxes.clear()
        // the words: the box the keyboard types into
        val body = UiKit.bodyPaint(30f)
        val box = RectF(PAD, boxTop, w - PAD, boxBottom)
        boxes[Model.Target.WORDS] = box
        UiKit.border(c, box)
        val shown = words.ifEmpty { "What were you doing when it went wrong?" }
        body.color = if (words.isEmpty()) Theme.textDim else Theme.text
        var y = box.top + 46f
        val fits = ((box.height() - 20f) / LINE_H).toInt().coerceAtLeast(1)
        if (words.isEmpty()) {
            for (line in SetupPanel.wrap(shown, body, box.width() - 40f).takeLast(fits)) { c.drawText(line, box.left + 20f, y, body); y += LINE_H }
            shownLines = emptyList()
        } else {
            // the lines around the caret, and the caret itself while typing (issue #11)
            textPaint.set(body)
            val lines = Model.wrapIndexed(words, box.width() - 40f) { body.measureText(it) }
            val cl = Model.caretLine(lines, caret)
            val from = (cl - fits + 1).coerceIn(0, maxOf(0, lines.size - fits)).let { if (cl < it) cl else it }
            shownLines = lines.subList(from, minOf(lines.size, from + fits)); shownFrom = from
            lineX = box.left + 20f; lineTop = box.top + 46f - 32f
            for ((idx, line) in shownLines.withIndex()) {
                c.drawText(line.second, lineX, y, body)
                if (k > 0f && from + idx == cl) {
                    val cx = lineX + body.measureText(line.second.substring(0, (caret - line.first).coerceIn(0, line.second.length)))
                    pCaret.color = Theme.gold
                    c.drawRect(cx, y - 30f, cx + 3f, y + 6f, pCaret)
                }
                y += LINE_H
            }
            if (k > 0f) UiKit.bodyPaint(18f, Theme.textDim).let { hint ->
                hint.textAlign = Paint.Align.RIGHT
                c.drawText("LB / RB move the cursor, or tap", box.right - 14f, box.bottom - 10f, hint)
            }
        }
        // Record and Send: above the keyboard while it is up (the user: "push up a bit above the keyboard")
        var top = buttonsTop
        val half = (w - PAD * 2 - GAP) / 2f
        val rec = RectF(PAD, top, PAD + half, top + BUTTON_H)
        val send = RectF(rec.right + GAP, top, w - PAD, top + BUTTON_H)
        boxes[Model.Target.RECORD] = rec; boxes[Model.Target.SEND] = send
        fun state(t: Model.Target) = if (!can || busy) UiKit.Button.DISABLED else UiKit.Button.UP
        UiKit.button(c, rec, Model.recordLabel(DiagLog.recording(), DiagLog.recordLeftMs()), state(Model.Target.RECORD))
        UiKit.button(c, send, "Send report", state(Model.Target.SEND))
        if (k > 0f) {                                          // the board below: no link or line; the cursor only off the keys
            if (!inKeys) boxes[focus]?.let { UiKit.focus(c, inset(it)) }
            return
        }
        top = rec.bottom + 46f
        // the small link
        val link = UiKit.bodyPaint(26f, Theme.gold).apply { isUnderlineText = true }
        c.drawText("Show what's included", PAD, top, link)
        boxes[Model.Target.INCLUDED] = RectF(PAD - 8f, top - 34f, PAD + link.measureText("Show what's included") + 8f, top + 12f)
        // the line under it: what happened, or why the buttons wait
        val note = status.ifEmpty {
            when {
                !can -> "Say what happened first (a few words)."
                ReportUpload.enabled -> "Sends a report with names and chat removed to the DS Forever team. ${Glyphs.name("b")} closes.\n" + StartupDiag.INCLUDED
                else -> "Logins, names and chat are taken out. ${Glyphs.name("b")} closes.\n" + StartupDiag.INCLUDED
            }
        }
        val small = UiKit.bodyPaint(24f, Theme.textDim)
        var ny = top + 54f
        for (part in note.split('\n')) for (line in SetupPanel.wrap(part, small, w - PAD * 2)) { c.drawText(line, PAD, ny, small); ny += 34f }
        boxes[focus]?.let { UiKit.focus(c, inset(it)) }
    }

    private fun inset(r: RectF) = RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f)

    /** the on-screen Back, top right (touch only; B does the same) */
    private val backBox = RectF()
    private val textPaint = Paint()
    private val pCaret = Paint().apply { style = Paint.Style.FILL }
    private fun drawBack(c: Canvas) {
        backBox.set(width - PAD - BACK_W, 20f, width - PAD, 20f + BACK_H)
        UiKit.button(c, backBox, "Back", UiKit.Button.UP)
    }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private const val PAD = 40f
        private const val GAP = 24f
        private const val BOX_H = 200f
        /** the words box above the keyboard: three lines */
        private const val BOX_UP_H = 150f
        /** Chat's input slide (ChatView.INPUT_MS) */
        private const val SLIDE_MS = 240L
        private const val BACK_W = 200f
        private const val LINE_H = 40f
        private const val BACK_H = 70f
        private const val BUTTON_H = 90f
        private const val MAX_WORDS = 1500
    }
}
