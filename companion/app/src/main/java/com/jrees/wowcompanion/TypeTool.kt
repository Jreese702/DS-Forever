package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * f-trackpad-keyboard (a player: "User is trying to use edit mode and cannot name his edits"; the user: "we need to add a trackpad/
 * Keyboard mode ... one function with tabs that goes back and forth to a track pad (with mouse clicks) or a keyboard using our same art
 * style, the keyboard can be reused here"). What may reach the GAME from it, pure: the key-safety ruling (Alt+F4 once closed the game)
 * allows letters, digits and space typed, Backspace and Enter - never Alt, Ctrl, an F-key, Escape, Tab or any system key.
 */
object GameTyping {
    /** a character the tool may type into the game's focused text box */
    fun allowed(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' '

    /** what the keyboard's text change means for the game: [back] Backspaces, then [type] typed (only allowed characters) */
    data class Ops(val back: Int, val type: String)

    /** [old] -> [new]: the common start stays, the rest of [old] is erased, the rest of [new] typed (a dropped character is skipped) */
    fun diff(old: String, new: String): Ops {
        var i = 0
        while (i < old.length && i < new.length && old[i] == new[i]) i++
        return Ops(old.length - i, new.substring(i).filter { allowed(it) })
    }

    /** the only X keys besides typed characters: Enter, Backspace (InputSink's US-layout X keycodes) */
    const val X_ENTER = 36
    const val X_BACKSPACE = 22
}

/**
 * THE KEYBOARD & TRACKPAD TOOL (the pause wheel's D-pad Left; Home's layer while it is open). Two tabs, LB / RB or a finger:
 *   * TRACKPAD: the bottom screen moves the game's mouse (relative, like a laptop pad); a quick tap = a left click; Left click / Right
 *     click buttons. The controller: A left click, X right click, the D-pad nudges the pointer.
 *   * KEYBOARD: Chat's own keyboard (ChatKeyboard, its art and rules); every letter goes to the GAME's focused text box as a real key
 *     (InputSink, the launcher's X server), Start / the Enter key = Enter, Backspace = Backspace. Nothing else is ever sent.
 * B closes it (back to Home). Nothing is bound, saved or set. [onClose] = Home takes the layer away.
 */
class TypeToolView(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {
    private var tab = 0                       // 0 TRACKPAD, 1 KEYBOARD
    private var typed = ""
    private val head = Head(context)
    private val pad = Pad(context)
    private val kb = ChatKeyboard(context, swap = false).apply {
        sendWord = "Enter"
        text = { typed }
        onText = { new -> val ops = GameTyping.diff(typed, new); InputSink.gameKeys(ops.back, ops.type); typed = new; head.invalidate() }
        onSend = { InputSink.gameEnter(); typed = ""; head.invalidate() }
        onClose = { this@TypeToolView.onClose() }
    }
    private var lastN = -1

    init {
        setBackgroundColor(Theme.ink)
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, HEAD_H))
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = HEAD_H })
        addView(kb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = HEAD_H + 90 })
        showTab(0)
    }

    fun open() { typed = ""; lastN = -1; kb.open(); showTab(tab); invalidate(); if (tab == 0) InputSink.gameCentre() }

    private fun showTab(t: Int) {
        tab = t
        pad.visibility = if (t == 0) View.VISIBLE else View.GONE
        kb.visibility = if (t == 1) View.VISIBLE else View.GONE
        head.invalidate()
    }

    /** one press from Home's guest "home" while the tool holds the controller ({"k": key, "n": count}) */
    fun onPad(msg: JSONObject) {
        if (msg.has("lost")) return
        val n = msg.optInt("n", -1)
        if (n >= 0 && n == lastN) return
        lastN = n
        val k = msg.optString("k")
        when (k) {
            "PADLSHOULDER" -> { if (tab == 1) showTab(0); return }
            "PADRSHOULDER" -> { if (tab == 0) showTab(1); return }
            "PAD2" -> { onClose(); return }
        }
        if (tab == 1) { kb.press(k); return }
        when (k) {
            "PAD1" -> InputSink.gameClick(right = false)
            "PAD3" -> InputSink.gameClick(right = true)
            "PADDUP" -> InputSink.gameMove(0, -NUDGE); "PADDDOWN" -> InputSink.gameMove(0, NUDGE)
            "PADDLEFT" -> InputSink.gameMove(-NUDGE, 0); "PADDRIGHT" -> InputSink.gameMove(NUDGE, 0)
        }
    }

    /** the tabs (the game's tab look) and, on the keyboard, what has been typed into the game since it opened */
    private inner class Head(c: Context) : View(c) {
        private fun tabRect(i: Int) = RectF(width / 2f - 360f + i * 370f, 18f, width / 2f - 10f + i * 370f, 98f)
        override fun onDraw(c: Canvas) {
            UiKit.tab(c, tabRect(0), "Trackpad", tab == 0)
            UiKit.tab(c, tabRect(1), "Keyboard", tab == 1)
            UiKit.pad(c, "${glyphSet()}_lshoulder", tabRect(0).left + 40f, tabRect(0).centerY(), 44f)
            UiKit.pad(c, "${glyphSet()}_rshoulder", tabRect(1).right - 40f, tabRect(1).centerY(), 44f)
            val p = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            val line = if (tab == 1) (if (typed.isEmpty()) "Typing into the game's text box" else "Typed: $typed")
                else "Move the pointer on the pad. Tap to click."
            c.drawText(line, width / 2f, 140f, p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) for (i in 0..1) if (tabRect(i).contains(e.x, e.y)) { showTab(i); break }
            return true
        }
    }

    private fun glyphSet() = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr"; CastSettings.ButtonLayout.PLAYSTATION -> "shp"; else -> "rev"
    }

    /** the pad itself: a well, Left click / Right click under it */
    private inner class Pad(c: Context) : View(c) {
        private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f
        private var downAt = 0L; private var onButton = -1; private var accX = 0f; private var accY = 0f
        private var moves = 0; private var sumX = 0; private var sumY = 0
        private fun well() = RectF(40f, 70f, width - 40f, height - 170f)
        private fun button(i: Int): RectF { val w = (width - 100f) / 2f; val l = 40f + i * (w + 20f); return RectF(l, height - 150f, l + w, height - 40f) }
        override fun onDraw(c: Canvas) {
            UiKit.module(c, well())
            val p = UiKit.bodyPaint(24f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText("Trackpad", well().centerX(), well().centerY(), p)
            val g = glyphSet()
            UiKit.button(c, button(0), "Left click", if (g == "shp") "shp_cross" else "${g}_a", if (onButton == 0) UiKit.Button.DOWN else UiKit.Button.UP)
            UiKit.button(c, button(1), "Right click", if (g == "shp") "shp_square" else "${g}_x", if (onButton == 1) UiKit.Button.DOWN else UiKit.Button.UP)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onButton = (0..1).firstOrNull { button(it).contains(e.x, e.y) } ?: -1
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; downAt = SystemClock.uptimeMillis(); accX = 0f; accY = 0f
                    moves = 0; sumX = 0; sumY = 0
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (onButton < 0) {
                    accX += (e.x - lastX) * SPEED; accY += (e.y - lastY) * SPEED
                    lastX = e.x; lastY = e.y
                    val dx = accX.toInt(); val dy = accY.toInt()
                    if (dx != 0 || dy != 0) { InputSink.gameMove(dx, dy); accX -= dx; accY -= dy; moves++; sumX += dx; sumY += dy }
                }
                MotionEvent.ACTION_UP -> {
                    if (moves > 0) InputSink.dragNote(moves, sumX, sumY)
                    val b = onButton; onButton = -1
                    if (b >= 0 && button(b).contains(e.x, e.y)) InputSink.gameClick(right = b == 1)
                    else if (b < 0 && SystemClock.uptimeMillis() - downAt < TAP_MS && hypot(e.x - downX, e.y - downY) < TAP_SLOP) InputSink.gameClick(right = false)
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { onButton = -1; invalidate() }
            }
            return true
        }
    }

    companion object {
        private const val HEAD_H = 160
        /** pad px -> game pointer px (the top screen is larger than the pad) */
        private const val SPEED = 1.6f
        private const val NUDGE = 24
        private const val TAP_MS = 220L
        private const val TAP_SLOP = 14f
    }
}
