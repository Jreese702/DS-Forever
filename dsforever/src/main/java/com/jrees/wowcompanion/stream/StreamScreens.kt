package com.jrees.wowcompanion.stream

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** the look of Streaming's own screens on the top display (plain views: they show before any game art exists) */
internal object StreamLook {
    const val INK = 0xFFF2E6C9.toInt()
    const val DIM = 0xFFB9AE95.toInt()
    const val GOLD = 0xFFE8B84A.toInt()
    const val BAD = 0xFFFF8A73.toInt()
    const val PANEL = 0xEE15120E.toInt()
    const val BOX = 0xFF2A241C.toInt()

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun text(ctx: Context, size: Float, color: Int = INK, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = GOLD) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; setTextColor(INK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        background = rounded(BOX, dp(ctx, 10f).toFloat(), dp(ctx, 2f))
        setPadding(dp(ctx, 18f), dp(ctx, 8f), dp(ctx, 18f), dp(ctx, 8f))
        setOnClickListener { onClick() }
    }

    fun row(ctx: Context, vararg v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        for (x in v) addView(x, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(ctx, 8f), dp(ctx, 6f), dp(ctx, 8f), dp(ctx, 6f))
        })
    }
}

/**
 * "ENTER THE CODE SHOWN IN DS FOREVER DESKTOP", design B "Two steps" (the user's picks 2026-11-18): six boxes for the secret, a touch
 * number pad, the PC it pairs with; beside the pad "Other network" (a globe), which opens a second, smaller row of boxes under the six for
 * Desktop's network code ([PairCode]) - also opened by itself, cursor in it and "PC not found" under it, when Pair finds no PC
 * ([notFound]). The same words and globe as Desktop's. The controller ([CodeEntry]): D-pad up turns the selected digit, down goes below
 * the boxes (the button, A presses it; or the open row), left / right move, A = Pair, B = back, X = delete, LB / RB = the previous /
 * next PC found. Stream quality and Back stand beside the pad: under it the panel was taller than the top screen (the PR #534 fix).
 */
class PairingView(ctx: Context, private val onPair: (String) -> Unit, private val onBack: () -> Unit,
                  private val onQuality: () -> Unit, private val onPcStep: (Int) -> Unit) : LinearLayout(ctx) {
    val entry = CodeEntry()
    private val boxes = List(CodeEntry.LENGTH) { StreamLook.text(ctx, 32f, bold = true) }
    private val pcLine = StreamLook.text(ctx, 16f, StreamLook.DIM)
    private val message = StreamLook.text(ctx, 16f, StreamLook.BAD)
    private val title = StreamLook.text(ctx, 24f, StreamLook.GOLD, bold = true)
    /** "Other network" over the row (the globe + the words, quiet) */
    private val otherLabel = StreamLook.text(ctx, 16f, StreamLook.DIM, bold = true)
    /** the row: groups of 3 small boxes, at most 5 groups a line */
    private val otherRows = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.START }
    private val otherCells = ArrayList<TextView>()
    private val otherButton: Button
    @Volatile var busy = false
        set(v) { field = v; post { alpha = if (v) 0.6f else 1f } }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        background = StreamLook.rounded(StreamLook.PANEL, StreamLook.dp(ctx, 18f).toFloat())
        val p = StreamLook.dp(ctx, 16f); setPadding(p, p, p, p)
        title.text = "Enter the code shown in DS Forever Desktop"
        addView(title)
        addView(pcLine)
        // top-aligned: a vertically centred row puts a box with unequal margins half its margin gap lower, past the row's edge (the
        // bottom stroke was cut)
        val boxRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP }
        boxes.forEachIndexed { i, b ->
            b.setOnClickListener { entry.select(i); refresh() }
            boxRow.addView(b, LayoutParams(StreamLook.dp(ctx, 50f), StreamLook.dp(ctx, 60f)).apply {
                setMargins(StreamLook.dp(ctx, if (i == 3) 18f else 5f), StreamLook.dp(ctx, 10f), StreamLook.dp(ctx, 5f), StreamLook.dp(ctx, 2f))
            })
        }
        addView(boxRow)
        val glyph = StreamLook.dp(ctx, 18f)
        otherLabel.text = com.jrees.wowcompanion.Tr.t(PairCode.OTHER_NETWORK)
        otherLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(Globe(StreamLook.DIM, glyph, StreamLook.dp(ctx, 1.4f).toFloat()), null, null, null)
        otherLabel.compoundDrawablePadding = StreamLook.dp(ctx, 8f)
        addView(otherLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = StreamLook.dp(ctx, 6f) })
        addView(otherRows, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = StreamLook.dp(ctx, 4f) })
        // the touch pad: 1-9, then delete / 0 / pair; Other network, Stream quality and Back in a column beside it
        val pad = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        for (r in 0 until 3) pad.addView(keyRow(ctx, *Array(3) { c -> digitKey(ctx, r * 3 + c + 1) }))
        pad.addView(keyRow(ctx, StreamLook.button(ctx, "Delete") { if (!busy) { entry.backspace(); refresh() } }, digitKey(ctx, 0),
            StreamLook.button(ctx, "Pair  (A)") { submit() }))
        otherButton = StreamLook.button(ctx, com.jrees.wowcompanion.Tr.t(PairCode.OTHER_NETWORK)) { if (!busy) { entry.openOther(); message.text = ""; refresh() } }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(Globe(StreamLook.INK, glyph, StreamLook.dp(ctx, 1.6f).toFloat()), null, null, null)
            compoundDrawablePadding = StreamLook.dp(ctx, 10f)
        }
        val side = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        for (b in listOf(otherButton, StreamLook.button(ctx, "Stream quality (Y)") { onQuality() }, StreamLook.button(ctx, "Back (B)") { onBack() }))
            side.addView(b, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(StreamLook.dp(ctx, 8f), StreamLook.dp(ctx, 6f), StreamLook.dp(ctx, 8f), StreamLook.dp(ctx, 6f)) })
        val body = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        body.addView(pad)
        body.addView(side, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = StreamLook.dp(ctx, 16f) })
        addView(body)
        addView(message)
        refresh()
    }

    /** the pad's rows: [StreamLook.row] with tighter gaps (four rows + the code must fit the top screen's 468 dp) */
    private fun keyRow(ctx: Context, vararg v: View) = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        for (x in v) addView(x, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(StreamLook.dp(ctx, 8f), StreamLook.dp(ctx, 4f), StreamLook.dp(ctx, 8f), StreamLook.dp(ctx, 4f))
        })
    }

    private fun digitKey(ctx: Context, d: Int) = StreamLook.button(ctx, "  $d  ") { if (!busy) { entry.type(d); message.text = ""; refresh() } }

    fun setPc(text: String) = post { pcLine.text = text; refresh() }
    fun setMessage(text: String) = post { message.text = text }
    /** a new start; [keepOther] keeps the Other network row and its digits (a wrong secret: the address is still right) */
    fun reset(keepOther: Boolean = false) { entry.clear(keepOther); post { message.text = ""; refresh() } }

    /** Pair found no PC on this network: the Other network row opens by itself, the cursor in it, "PC not found" under it */
    fun notFound() = post {
        busy = false
        if (!entry.otherOpen || entry.otherCursor < 0) entry.openOther()
        message.text = com.jrees.wowcompanion.Tr.t(PairCode.NOT_FOUND)
        refresh()
    }

    private fun submit() {
        if (busy) return
        val other = entry.other.joinToString("")
        val typed = entry.digits.filter { it >= 0 }.joinToString("") + other
        val check = CodeEntry.validate(typed)
        val code = entry.code
        if (code == null) { message.text = CodeEntry.message(if (check is CodeEntry.Check.Ok || check is CodeEntry.Check.BadAddress) CodeEntry.Check.TooShort else check) ?: ""; return }
        if (check !is CodeEntry.Check.Ok) { message.text = com.jrees.wowcompanion.Tr.t(CodeEntry.message(check) ?: ""); return }
        onPair(code)
    }

    private fun refresh() {
        boxes.forEachIndexed { i, b ->
            val d = entry.digits[i]
            b.text = if (d < 0) "" else d.toString()
            b.background = StreamLook.rounded(StreamLook.BOX, StreamLook.dp(context, 10f).toFloat(),
                StreamLook.dp(context, if (i == entry.cursor) 3f else 1f), if (i == entry.cursor) StreamLook.GOLD else StreamLook.DIM)
        }
        val open = entry.otherOpen
        // the PC line gives way to the Other network row (the row's code says where the PC is; with both the panel is taller than
        // the top screen)
        pcLine.visibility = if (open || pcLine.text.isNullOrEmpty()) GONE else VISIBLE
        otherLabel.visibility = if (open) VISIBLE else GONE
        otherRows.visibility = if (open) VISIBLE else GONE
        otherButton.visibility = if (open) GONE else VISIBLE
        otherButton.background = StreamLook.rounded(StreamLook.BOX, StreamLook.dp(context, 10f).toFloat(),
            StreamLook.dp(context, if (entry.onButton) 4f else 2f), StreamLook.GOLD)
        if (open) drawOther()
    }

    /** the row's boxes: [CodeEntry.otherBoxes] of them, groups of 3, 5 groups a line, the lines left-aligned to each other */
    private fun drawOther() {
        val ctx = context
        val n = entry.otherBoxes
        if (otherCells.size != n) {
            otherRows.removeAllViews(); otherCells.clear()
            var line: LinearLayout? = null
            for (i in 0 until n) {
                if (i % 15 == 0) line = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.START or Gravity.TOP }.also { otherRows.addView(it) }
                val cell = StreamLook.text(ctx, 20f, bold = true).apply { includeFontPadding = false }
                cell.setOnClickListener { if (!busy) { entry.select(CodeEntry.LENGTH + i); refresh() } }
                val first = i % 3 == 0 && i % 15 != 0
                line!!.addView(cell, LayoutParams(StreamLook.dp(ctx, 30f), StreamLook.dp(ctx, 36f)).apply {
                    setMargins(StreamLook.dp(ctx, if (first) 10f else 3f), StreamLook.dp(ctx, 3f), StreamLook.dp(ctx, 3f), StreamLook.dp(ctx, 3f))
                })
                otherCells.add(cell)
            }
        }
        val digits = entry.other
        otherCells.forEachIndexed { i, c ->
            val sel = i == entry.otherCursor
            c.text = digits.getOrNull(i)?.toString() ?: ""
            c.background = StreamLook.rounded(StreamLook.BOX, StreamLook.dp(ctx, 7f).toFloat(), StreamLook.dp(ctx, if (sel) 3f else 1f),
                if (sel) StreamLook.GOLD else StreamLook.DIM)
        }
    }

    /** a controller key while this screen shows; true = used */
    fun pad(keyCode: Int): Boolean {
        if (busy && keyCode != android.view.KeyEvent.KEYCODE_BUTTON_B) return true
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> entry.up()
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> entry.down()
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> entry.left()
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> entry.right()
            android.view.KeyEvent.KEYCODE_BUTTON_X -> entry.backspace()
            // A on the Other network button presses it; anywhere else it pairs
            android.view.KeyEvent.KEYCODE_BUTTON_A -> { if (entry.onButton) entry.openOther() else { submit(); return true } }
            android.view.KeyEvent.KEYCODE_BUTTON_B -> { onBack(); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_Y -> { onQuality(); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> { onPcStep(-1); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> { onPcStep(1); return true }
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> entry.type(keyCode - android.view.KeyEvent.KEYCODE_0)
            else -> return false
        }
        message.text = ""
        refresh()
        return true
    }

    /** the "Other network" mark, the same globe Desktop draws (CodeBoxes.Globe): a circle, a meridian, three parallels */
    private class Globe(color: Int, private val sizePx: Int, private val stroke: Float) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; this.color = color
        }
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; val i = stroke / 2
            val r = android.graphics.RectF(b.left + i, b.top + i, b.right - i, b.bottom - i)
            c.drawOval(r, p)
            c.drawOval(android.graphics.RectF(r.left + r.width() * 0.28f, r.top, r.right - r.width() * 0.28f, r.bottom), p)
            c.drawLine(r.left, r.centerY(), r.right, r.centerY(), p)
            c.drawLine(r.left + r.width() * 0.13f, r.top + r.height() * 0.25f, r.right - r.width() * 0.13f, r.top + r.height() * 0.25f, p)
            c.drawLine(r.left + r.width() * 0.13f, r.top + r.height() * 0.75f, r.right - r.width() * 0.13f, r.top + r.height() * 0.75f, p)
        }
        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

/** connecting / a problem: a title, the reason, and what the player can do (A Retry, X Pair again, Y Stream quality, B Back) */
internal class StatusView(ctx: Context, onRetry: () -> Unit, onPairAgain: () -> Unit, onQuality: () -> Unit, onBack: () -> Unit) : LinearLayout(ctx) {
    private val title = StreamLook.text(ctx, 26f, StreamLook.GOLD, bold = true)
    private val detail = StreamLook.text(ctx, 17f)
    private val note = StreamLook.text(ctx, 14f, StreamLook.DIM)
    private val buttons = StreamLook.row(ctx, StreamLook.button(ctx, "Retry (A)", onRetry), StreamLook.button(ctx, "Pair again (X)", onPairAgain),
        StreamLook.button(ctx, "Stream quality (Y)", onQuality), StreamLook.button(ctx, "Back (B)", onBack))

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        background = StreamLook.rounded(StreamLook.PANEL, StreamLook.dp(ctx, 18f).toFloat())
        val p = StreamLook.dp(ctx, 24f); setPadding(p, p, p, p)
        addView(title); addView(detail); addView(note); addView(buttons)
    }

    fun show(t: String, d: String, n: String = "", withButtons: Boolean = true) = post {
        title.text = t; detail.text = d; note.text = n; buttons.visibility = if (withButtons) View.VISIBLE else View.GONE
    }
}

/** "Stream quality": one row per setting; the controller's up / down picks a row, left / right changes it, A or B closes */
internal class QualityView(ctx: Context, private var q: StreamQuality, private val onChange: (StreamQuality) -> Unit,
                           private val onClose: () -> Unit,
                           /** this Thor shows the stream's size at 120 fps ([StreamRate.can120]) */
                           private val can120: (StreamQuality) -> Boolean = { false },
                           /** Test connection ([TestView]); null = not offered */
                           private val onTest: (() -> Unit)? = null) : LinearLayout(ctx) {
    private var row = 0
    private val rows = List(5) { StreamLook.text(ctx, 20f) }
    private val note120 = StreamLook.text(ctx, 14f, StreamLook.DIM)

    init {
        q = StreamQuality.forDevice(q, q.fps != 120 || can120(q))
        orientation = VERTICAL; gravity = Gravity.CENTER
        background = StreamLook.rounded(StreamLook.PANEL, StreamLook.dp(ctx, 18f).toFloat())
        val p = StreamLook.dp(ctx, 24f); setPadding(p, p, p, p)
        addView(StreamLook.text(ctx, 24f, StreamLook.GOLD, bold = true).apply { text = "Stream quality" })
        rows.forEachIndexed { i, t ->
            t.setPadding(0, StreamLook.dp(ctx, 8f), 0, StreamLook.dp(ctx, 8f))
            t.setOnClickListener { row = i; change(1) }
            addView(t)
        }
        addView(note120)
        addView(StreamLook.text(ctx, 14f, StreamLook.DIM).apply { text = "Defaults: HEVC, 1080p 60, 20 Mbps." })
        addView(StreamLook.row(ctx, *listOfNotNull(StreamLook.button(ctx, "Defaults") { q = StreamQuality(); onChange(q); refresh() },
            onTest?.let { t -> StreamLook.button(ctx, com.jrees.wowcompanion.Tr.t(TestView.TITLE) + " (X)") { t() } },
            StreamLook.button(ctx, "Done (B)") { onClose() }).toTypedArray()))
        refresh()
    }

    private fun change(by: Int) {
        q = when (row) {
            0 -> q.copy(codec = StreamQuality.step(StreamQuality.Codec.values().toList(), q.codec, by)).let { StreamQuality.forDevice(it, it.fps != 120 || can120(it)) }
            1 -> q.copy(height = StreamQuality.step(StreamQuality.HEIGHTS, q.height, by)).let { StreamQuality.forDevice(it, it.fps != 120 || can120(it)) }
            2 -> StreamQuality.withFps(q, StreamQuality.step(StreamQuality.offeredFps(can120(q)), q.fps, by))
            3 -> q.copy(bitrateKbps = StreamQuality.step(StreamQuality.BITRATES_KBPS, q.bitrateKbps, by))
            else -> q.copy(audioOnThor = !q.audioOnThor)
        }
        onChange(q); refresh()
    }

    private fun refresh() {
        val v = listOf("Video: ${q.codec.label}", "Resolution: ${q.height}p", "Frame rate: ${q.fps} fps", "Bit rate: ${q.bitrateKbps / 1000} Mbps",
            "Sound: ${if (q.audioOnThor) "on the Thor" else "on the PC"}")
        rows.forEachIndexed { i, t -> t.text = (if (i == row) "‹  " else "") + v[i] + (if (i == row) "  ›" else ""); t.setTextColor(if (i == row) StreamLook.GOLD else StreamLook.INK) }
        // the 120 fps note and the Wi-Fi one above 50 Mbps (the user's pick 2026-11-23), each its own words for Tr
        val notes = listOfNotNull(StreamQuality.NOTE_120.takeIf { q.fps == 120 }, StreamQuality.wifiNote(q)).map { com.jrees.wowcompanion.Tr.t(it) }
        note120.text = notes.joinToString("  ·  ")
        note120.visibility = if (notes.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun pad(keyCode: Int): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> row = (row + rows.size - 1) % rows.size
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> row = (row + 1) % rows.size
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { change(-1); return true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { change(1); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_BUTTON_B -> { onClose(); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_X -> { onTest?.invoke(); return onTest != null }
            else -> return false
        }
        refresh(); return true
    }
}

/**
 * "Test connection": one row per [ConnTest.Step] (pass / fail / skipped / not run), the first failure's reason and fix under the rows,
 * Run again (A) and Done (B). Words through Tr.t; a line's numbers stay as they are ([trHead]).
 */
internal class TestView(ctx: Context, private val onRun: () -> Unit, private val onClose: () -> Unit) : LinearLayout(ctx) {
    private val title = StreamLook.text(ctx, 24f, StreamLook.GOLD, bold = true)
    private val rows = ConnTest.Step.values().associateWith { StreamLook.text(ctx, 17f).apply { gravity = Gravity.START } }
    private val reason = StreamLook.text(ctx, 17f, StreamLook.BAD)
    private val fix = StreamLook.text(ctx, 16f)
    private val runBtn = StreamLook.button(ctx, com.jrees.wowcompanion.Tr.t("Run again") + " (A)") { onRun() }
    @Volatile var running = false
        private set

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        background = StreamLook.rounded(StreamLook.PANEL, StreamLook.dp(ctx, 18f).toFloat())
        val p = StreamLook.dp(ctx, 24f); setPadding(p, p, p, p)
        title.text = com.jrees.wowcompanion.Tr.t(TITLE)
        addView(title)
        for (t in rows.values) addView(t, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = StreamLook.dp(ctx, 4f) })
        addView(reason); addView(fix)
        addView(StreamLook.row(ctx, runBtn, StreamLook.button(ctx, com.jrees.wowcompanion.Tr.t("Done") + " (B)") { onClose() }))
        clear()
    }

    /** every row waiting, as a run starts */
    fun clear() = post {
        running = true
        runBtn.isEnabled = false
        for ((s, t) in rows) { t.text = "·  " + com.jrees.wowcompanion.Tr.t(s.label); t.setTextColor(StreamLook.DIM) }
        reason.text = ""; fix.text = ""
        reason.visibility = View.GONE; fix.visibility = View.GONE
    }

    /** the step under way */
    fun working(s: ConnTest.Step) = post { rows[s]?.let { it.text = "…  " + com.jrees.wowcompanion.Tr.t(s.label); it.setTextColor(StreamLook.GOLD) } }

    fun result(r: ConnTest.Result) = post {
        val t = rows[r.step] ?: return@post
        val mark = when (r.state) { ConnTest.State.PASS -> "✓"; ConnTest.State.FAIL -> "✗"; ConnTest.State.SKIP -> "–"; ConnTest.State.NOT_RUN -> "·" }
        t.text = "$mark  " + com.jrees.wowcompanion.Tr.t(r.step.label) + if (r.line.isNotEmpty()) ":  " + trHead(r.line) else ""
        t.setTextColor(when (r.state) { ConnTest.State.PASS -> StreamLook.INK; ConnTest.State.FAIL -> StreamLook.BAD; else -> StreamLook.DIM })
        if (r.state == ConnTest.State.FAIL) {
            reason.text = trHead(r.line); fix.text = trHead(r.fix)
            reason.visibility = View.VISIBLE; fix.visibility = if (r.fix.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    fun done() = post { running = false; runBtn.isEnabled = true }

    fun pad(keyCode: Int): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_BUTTON_A -> { if (!running) onRun(); true }
        android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_BACK -> { onClose(); true }
        else -> false
    }

    companion object {
        const val TITLE = "Test connection"

        /** the words before a line's first number or bracket translated, the rest kept ("No answer on port" + " 53010") */
        fun trHead(line: String): String {
            val (head, rest) = ConnTest.splitHead(line)
            return com.jrees.wowcompanion.Tr.t(head) + rest
        }
    }
}

internal fun FrameLayout.centered(v: View, widthDp: Float) {
    addView(v, FrameLayout.LayoutParams(StreamLook.dp(context, widthDp), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
}

