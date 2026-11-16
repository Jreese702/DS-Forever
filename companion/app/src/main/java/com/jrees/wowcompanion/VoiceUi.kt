package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * VOICE'S PIECES ON A BOX (the design, built as the user picked 2026-11-16): a focused voice box shows two glyphs on its right, A with
 * a keyboard icon and X with a microphone icon, and a finger on the microphone does what X does. X shows whenever Voice typing is not
 * Off and the device has a microphone ([micShown]); otherwise the box keeps its A alone, as before. The icons are drawn in code in the
 * kit's line style (the keyboard's backspace / enter keys), so no game art is needed.
 */
object VoiceUi {
    @Volatile private var micMemo: Boolean? = null

    /** X is voice's on a voice box now (the setting, and a microphone on this device) */
    fun micShown(ctx: Context): Boolean {
        val mic = micMemo ?: VoiceEngine.hasMic(ctx).also { micMemo = it }
        return Voice.showsMic(Settings.voiceTyping, mic)
    }

    /** the off-device renders and tests: the device's microphone as given (null = ask the device again) */
    fun micForTest(has: Boolean?) { micMemo = has }

    private val line by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } }
    private val fill by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val path by lazy { Path() }

    /** a microphone [size] px tall centred at ([cx], [cy]): the capsule, its cradle and stand; [crossed] = struck through (no permission) */
    fun mic(c: Canvas, cx: Float, cy: Float, size: Float, colour: Int, crossed: Boolean = false, alpha: Int = 255) {
        val s = size
        line.color = colour; line.alpha = alpha; line.strokeWidth = maxOf(2f, s * 0.085f)
        fill.color = colour; fill.alpha = alpha
        val w = s * 0.34f; val top = cy - s * 0.48f; val bot = cy + s * 0.10f
        c.drawRoundRect(RectF(cx - w / 2f, top, cx + w / 2f, bot), w / 2f, w / 2f, fill)
        path.reset()
        val cw = s * 0.30f
        path.addArc(RectF(cx - cw, cy - s * 0.22f, cx + cw, cy + s * 0.26f), 0f, 180f)
        c.drawPath(path, line)
        c.drawLine(cx, cy + s * 0.26f, cx, cy + s * 0.42f, line)
        c.drawLine(cx - s * 0.18f, cy + s * 0.44f, cx + s * 0.18f, cy + s * 0.44f, line)
        if (crossed) {
            line.color = Theme.unusable; line.alpha = alpha; line.strokeWidth = maxOf(2.5f, s * 0.10f)
            c.drawLine(cx - s * 0.42f, cy - s * 0.46f, cx + s * 0.42f, cy + s * 0.46f, line)
        }
        line.alpha = 255; fill.alpha = 255
    }

    /** a keyboard [size] px wide centred at ([cx], [cy]): its outline and three rows of keys */
    fun keyboard(c: Canvas, cx: Float, cy: Float, size: Float, colour: Int, alpha: Int = 255) {
        val w = size; val h = size * 0.62f
        line.color = colour; line.alpha = alpha; line.strokeWidth = maxOf(2f, size * 0.06f)
        fill.color = colour; fill.alpha = alpha
        val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        c.drawRoundRect(r, h * 0.16f, h * 0.16f, line)
        val k = h * 0.14f
        for (row in 0..1) for (col in 0..4) {
            val x = r.left + w * (0.17f + col * 0.165f); val y = r.top + h * (0.30f + row * 0.24f)
            c.drawRect(x - k / 2f, y - k / 2f, x + k / 2f, y + k / 2f, fill)
        }
        c.drawRect(r.left + w * 0.30f, r.top + h * 0.72f, r.right - w * 0.30f, r.top + h * 0.72f + k, fill)
        line.alpha = 255; fill.alpha = 255
    }

    /** where a box's glyphs were drawn: A's (the keyboard) and X's (the microphone, null when voice is not offered) */
    data class Glyphs(val keys: RectF, val mic: RectF?)

    /**
     * The focused box's glyphs, ending at [right] and centred on [cy], [h] px tall: (A) keyboard, then (X) microphone when [voice].
     * Returns their rects (the touch boxes: a finger on the microphone = X). Draws nothing for a zero height. [onPaper]: the box is the
     * letter's parchment (Send Mail), where the light keyboard and the gold microphone vanish: both in the parchment's ink there.
     */
    fun boxGlyphs(c: Canvas, right: Float, cy: Float, h: Float, voice: Boolean, alpha: Int = 255, onPaper: Boolean = false): Glyphs {
        val g = h
        val icon = h * 0.82f
        val pairW = g + 6f + icon
        val micR = if (voice) RectF(right - pairW, cy - h / 2f - 6f, right, cy + h / 2f + 6f) else null
        val keysRight = if (voice) right - pairW - 18f else right
        val keysR = RectF(keysRight - pairW, cy - h / 2f - 6f, keysRight, cy + h / 2f + 6f)
        UiKit.pad(c, "rev_a", keysR.left + g / 2f, cy, g, alpha)
        keyboard(c, keysR.right - icon / 2f, cy, icon, if (onPaper) Theme.parchmentInk else Theme.text, alpha)
        if (micR != null) {
            UiKit.pad(c, "rev_x", micR.left + g / 2f, cy, g, alpha)
            mic(c, micR.right - icon / 2f, cy, icon, if (onPaper) Theme.parchmentInk else Theme.gold, alpha = alpha)
        }
        return Glyphs(keysR, micR)
    }

    /** X with the microphone alone, ending at [right] (a box whose keyboard is already up: A types there); its touch box */
    fun micGlyph(c: Canvas, right: Float, cy: Float, h: Float, alpha: Int = 255): RectF {
        val icon = h * 0.82f
        val r = RectF(right - (h + 6f + icon), cy - h / 2f - 6f, right, cy + h / 2f + 6f)
        UiKit.pad(c, "rev_x", r.left + h / 2f, cy, h, alpha)
        mic(c, r.right - icon / 2f, cy, icon, Theme.gold, alpha = alpha)
        return r
    }

    /** the room [boxGlyphs] takes at the box's right for a [h] px glyph (the box's text stops before it) */
    fun glyphsWidth(h: Float, voice: Boolean): Float = (h + 6f + h * 0.82f) * (if (voice) 2 else 1) + (if (voice) 18f else 0f)

    /** a dark disc for the medallion */
    internal val DISC = Color.rgb(14, 10, 6)
}
