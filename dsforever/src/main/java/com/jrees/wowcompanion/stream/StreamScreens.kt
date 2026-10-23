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
 * "ENTER THE CODE SHOWN IN DS FOREVER DESKTOP": six boxes, a touch number pad, the PC it pairs with. The controller: D-pad up / down
 * turns the selected digit, left / right moves, A = Pair, B = back, X = delete, LB / RB = the previous / next PC found.
 */
internal class PairingView(ctx: Context, private val onPair: (String) -> Unit, private val onBack: () -> Unit,
                           private val onQuality: () -> Unit, private val onPcStep: (Int) -> Unit) : LinearLayout(ctx) {
    val entry = CodeEntry()
    private val boxes = List(CodeEntry.LENGTH) { StreamLook.text(ctx, 34f, bold = true) }
    private val pcLine = StreamLook.text(ctx, 16f, StreamLook.DIM)
    private val message = StreamLook.text(ctx, 16f, StreamLook.BAD)
    private val title = StreamLook.text(ctx, 24f, StreamLook.GOLD, bold = true)
    @Volatile var busy = false
        set(v) { field = v; post { alpha = if (v) 0.6f else 1f } }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        background = StreamLook.rounded(StreamLook.PANEL, StreamLook.dp(ctx, 18f).toFloat())
        val p = StreamLook.dp(ctx, 22f); setPadding(p, p, p, p)
        title.text = "Enter the code shown in DS Forever Desktop"
        addView(title)
        addView(pcLine)
        val boxRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        boxes.forEachIndexed { i, b ->
            b.setOnClickListener { entry.select(i); refresh() }
            boxRow.addView(b, LayoutParams(StreamLook.dp(ctx, 52f), StreamLook.dp(ctx, 66f)).apply {
                setMargins(StreamLook.dp(ctx, if (i == 3) 18f else 5f), StreamLook.dp(ctx, 14f), StreamLook.dp(ctx, 5f), StreamLook.dp(ctx, 10f))
            })
        }
        addView(boxRow)
        // the touch pad: 1-9, then delete / 0 / pair
        for (r in 0 until 3) addView(StreamLook.row(ctx, *Array(3) { c -> digitKey(ctx, r * 3 + c + 1) }))
        addView(StreamLook.row(ctx, StreamLook.button(ctx, "Delete") { if (!busy) { entry.backspace(); refresh() } }, digitKey(ctx, 0),
            StreamLook.button(ctx, "Pair  (A)") { submit() }))
        addView(message)
        addView(StreamLook.row(ctx, StreamLook.button(ctx, "Stream quality (Y)") { onQuality() }, StreamLook.button(ctx, "Back (B)") { onBack() }))
        refresh()
    }

    private fun digitKey(ctx: Context, d: Int) = StreamLook.button(ctx, "  $d  ") { if (!busy) { entry.type(d); message.text = ""; refresh() } }

    fun setPc(text: String) = post { pcLine.text = text }
    fun setMessage(text: String) = post { message.text = text }
    fun reset() { entry.clear(); post { message.text = ""; refresh() } }

    private fun submit() {
        if (busy) return
        val code = entry.code
        if (code == null) { message.text = CodeEntry.message(CodeEntry.validate(entry.digits.filter { it >= 0 }.joinToString(""))) ?: ""; return }
        onPair(code)
    }

    private fun refresh() {
        boxes.forEachIndexed { i, b ->
            val d = entry.digits[i]
            b.text = if (d < 0) "" else d.toString()
            b.background = StreamLook.rounded(StreamLook.BOX, StreamLook.dp(context, 10f).toFloat(),
                StreamLook.dp(context, if (i == entry.cursor) 3f else 1f), if (i == entry.cursor) StreamLook.GOLD else StreamLook.DIM)
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
            android.view.KeyEvent.KEYCODE_BUTTON_A -> { submit(); return true }
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
                           private val onClose: () -> Unit) : LinearLayout(ctx) {
    private var row = 0
    private val rows = List(5) { StreamLook.text(ctx, 20f) }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        background = StreamLook.rounded(StreamLook.PANEL, StreamLook.dp(ctx, 18f).toFloat())
        val p = StreamLook.dp(ctx, 24f); setPadding(p, p, p, p)
        addView(StreamLook.text(ctx, 24f, StreamLook.GOLD, bold = true).apply { text = "Stream quality" })
        rows.forEachIndexed { i, t ->
            t.setPadding(0, StreamLook.dp(ctx, 8f), 0, StreamLook.dp(ctx, 8f))
            t.setOnClickListener { row = i; change(1) }
            addView(t)
        }
        addView(StreamLook.text(ctx, 14f, StreamLook.DIM).apply { text = "Applies from the next connection. Defaults: HEVC, 1080p 60, 20 Mbps." })
        addView(StreamLook.row(ctx, StreamLook.button(ctx, "Defaults") { q = StreamQuality(); onChange(q); refresh() }, StreamLook.button(ctx, "Done (B)") { onClose() }))
        refresh()
    }

    private fun change(by: Int) {
        q = when (row) {
            0 -> q.copy(codec = StreamQuality.step(StreamQuality.Codec.values().toList(), q.codec, by))
            1 -> q.copy(height = StreamQuality.step(StreamQuality.HEIGHTS, q.height, by))
            2 -> q.copy(fps = StreamQuality.step(StreamQuality.FPS, q.fps, by))
            3 -> q.copy(bitrateKbps = StreamQuality.step(StreamQuality.BITRATES_KBPS, q.bitrateKbps, by))
            else -> q.copy(audioOnThor = !q.audioOnThor)
        }
        onChange(q); refresh()
    }

    private fun refresh() {
        val v = listOf("Video: ${q.codec.label}", "Resolution: ${q.height}p", "Frame rate: ${q.fps} fps", "Bit rate: ${q.bitrateKbps / 1000} Mbps",
            "Sound: ${if (q.audioOnThor) "on the Thor" else "on the PC"}")
        rows.forEachIndexed { i, t -> t.text = (if (i == row) "‹  " else "") + v[i] + (if (i == row) "  ›" else ""); t.setTextColor(if (i == row) StreamLook.GOLD else StreamLook.INK) }
    }

    fun pad(keyCode: Int): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> row = (row + rows.size - 1) % rows.size
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> row = (row + 1) % rows.size
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { change(-1); return true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { change(1); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_BUTTON_B -> { onClose(); return true }
            else -> return false
        }
        refresh(); return true
    }
}

internal fun FrameLayout.centered(v: View, widthDp: Float) {
    addView(v, FrameLayout.LayoutParams(StreamLook.dp(context, widthDp), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
}

