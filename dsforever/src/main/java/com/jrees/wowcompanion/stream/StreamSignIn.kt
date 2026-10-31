package com.jrees.wowcompanion.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.jrees.wowcompanion.ChatKeyboard
import com.jrees.wowcompanion.GameTyping
import com.jrees.wowcompanion.InputSink
import com.jrees.wowcompanion.TapSink
import com.jrees.wowcompanion.TrackpadView
import com.jrees.wowcompanion.Theme
import com.jrees.wowcompanion.UiKit

/** Battle.net's sign-in form on the PC's screen (Desktop's BnetForm): [email] null = the account is remembered; points are shares (x, y) */
data class BnetFormAt(val email: Pair<Float, Float>?, val password: Pair<Float, Float>, val button: Pair<Float, Float>)

/**
 * SIGNING IN TO THE PC'S BATTLE.NET WITH THE SAVED LOGIN (the user's ruling 2026-10-30: "why on earth cant we just use the encrypted
 * credentials that the user saves?"; replaces 2026-10-28). ONE attempt: nothing saved -> the bottom screen's form once; Desktop asked
 * whether its sign-in window is in front and where the form is; the login typed into it through the stream. An old Desktop, a form it
 * does not know, a Thor that cannot type the login, or no sign-in after [TYPED_MS]: MANUAL, Battle.net's window and the keyboard. So a
 * wrong password is tried once, and a verify step or an authenticator code lands with the player. Pure: StreamTop gives it the world.
 */
class StreamSignIn(
    private val hasLogin: () -> Boolean,
    private val formOpen: () -> Boolean,
    private val openForm: () -> Unit,
    private val ask: () -> Unit,
    private val type: (BnetFormAt) -> Unit,
    private val now: () -> Long,
) {
    enum class Phase { IDLE, FORM, ASKING, TYPED, MANUAL }

    var phase = Phase.IDLE; private set
    private var since = 0L

    /** the curtain stays down over Battle.net: this is doing the sign-in (or the bottom's form asks for the login) */
    val working get() = phase == Phase.FORM || phase == Phase.ASKING || phase == Phase.TYPED

    private fun go(p: Phase) { phase = p; since = now() }

    /** on every word from Desktop and each second while [working]; [needed] = the stream is up and Battle.net is signed out */
    fun tick(needed: Boolean) {
        if (!needed) { phase = Phase.IDLE; return }
        val waited = now() - since
        when (phase) {
            Phase.IDLE -> if (hasLogin()) { go(Phase.ASKING); ask() } else { go(Phase.FORM); openForm() }
            Phase.FORM -> if (hasLogin()) { go(Phase.ASKING); ask() } else if (waited > FORM_GRACE_MS && !formOpen()) go(Phase.MANUAL)
            Phase.ASKING -> if (waited > ASK_MS) go(Phase.MANUAL)
            Phase.TYPED -> if (waited > TYPED_MS) go(Phase.MANUAL)
            Phase.MANUAL -> {}
        }
    }

    /** Desktop's answer: [form] null = not safe to type */
    fun answer(form: BnetFormAt?) {
        if (phase != Phase.ASKING) return
        if (form == null) { go(Phase.MANUAL); return }
        go(Phase.TYPED); type(form)
    }

    /** the typing could not start */
    fun failed() { if (phase == Phase.TYPED) go(Phase.MANUAL) }

    companion object {
        const val FORM_GRACE_MS = 3_000L
        const val ASK_MS = 5_000L
        const val TYPED_MS = 20_000L
    }
}

/** the controller over the sign-in keyboard: Android's keys and hat / trigger edges -> the keyboard's button names */
class SignInPad {
    private var hat = ""; private var lt = false; private var rt = false

    fun name(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "PADDUP"; KeyEvent.KEYCODE_DPAD_DOWN -> "PADDDOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "PADDLEFT"; KeyEvent.KEYCODE_DPAD_RIGHT -> "PADDRIGHT"
        KeyEvent.KEYCODE_BUTTON_A -> "PAD1"; KeyEvent.KEYCODE_BUTTON_B -> "PAD2"
        KeyEvent.KEYCODE_BUTTON_X -> "PAD3"; KeyEvent.KEYCODE_BUTTON_Y -> "PAD4"
        KeyEvent.KEYCODE_BUTTON_L1 -> "PADLSHOULDER"; KeyEvent.KEYCODE_BUTTON_R1 -> "PADRSHOULDER"
        KeyEvent.KEYCODE_BUTTON_L2 -> "PADLTRIGGER"; KeyEvent.KEYCODE_BUTTON_R2 -> "PADRTRIGGER"
        KeyEvent.KEYCODE_BUTTON_START -> "PADFORWARD"
        else -> null
    }

    /** what newly went down */
    fun axes(hx: Float, hy: Float, l: Float, r: Float): List<String> {
        val out = ArrayList<String>()
        val h = when { hx < -0.5f -> "PADDLEFT"; hx > 0.5f -> "PADDRIGHT"; hy < -0.5f -> "PADDUP"; hy > 0.5f -> "PADDDOWN"; else -> "" }
        if (h != hat && h.isNotEmpty()) out += h
        hat = h
        val nl = if (lt) l > 0.30f else l > 0.35f
        val nr = if (rt) r > 0.30f else r > 0.35f
        if (nl && !lt) out += "PADLTRIGGER"
        if (nr && !rt) out += "PADRTRIGGER"
        lt = nl; rt = nr
        return out
    }
}

/**
 * THE BOTTOM SCREEN WHILE BATTLE.NET NEEDS THE PLAYER (the user, 2026-10-30: "I need the keyboard on the bottom screen to sign in to
 * Battle.net"): Chat's keyboard, every key straight to the focused box on the PC. What is typed may be a password: NOTHING of it is kept
 * here, the keyboard writes onto a one-character stand-in ([HELD]) and each change goes out as one key; only a count of dots shows.
 * X / the Tab key = next box, Start / the Enter key = Enter. It goes by itself when Battle.net is done.
 *
 * Tabs at the top (the user, 2026-10-30: "when the keyboard is open give me a toggle at the top to like swap to track pad mode"): Keyboard
 * and Trackpad, a finger or LB / RB. Close or B = [onClose]. The waiting screen's Keyboard button opens it too.
 */
class SignInBoard(c: Context, private val onClose: () -> Unit) : FrameLayout(c) {
    private var count = 0
    private var tab = 0                       // 0 KEYBOARD, 1 TRACKPAD
    private val dots = Dots(c)
    private val head = Head(c)
    private val pad = TrackpadView(c)
    private val kb = ChatKeyboard(c, signIn = true).apply {
        sendWord = "Enter"; swapWord = "Tab"; fillHeight = BOARD_H
        text = { HELD }
        onText = { new ->
            // a local game: only what the key-safety ruling allows (GameTyping); the stream's Battle.net takes any character
            if (new.length < HELD.length) { InputSink.signInBack(); count = (count - 1).coerceAtLeast(0) }
            else if (toPc() || GameTyping.allowed(new.last())) { InputSink.signInChar(new.last()); count++ }
            dots.invalidate()
        }
        onSend = { InputSink.gameEnter(); clear() }
        onSwap = { tab() }
    }

    init {
        setBackgroundColor(Theme.ink)
        isClickable = true
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(kb, LayoutParams(LayoutParams.MATCH_PARENT, BOARD_H, Gravity.BOTTOM))
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = HEAD_H })
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, HEAD_H))
        kb.open()
        show(0)
    }

    private fun show(t: Int) {
        tab = t
        dots.visibility = if (t == 0) View.VISIBLE else View.GONE
        kb.visibility = if (t == 0) View.VISIBLE else View.GONE
        pad.visibility = if (t == 1) View.VISIBLE else View.GONE
        InputSink.gamePointer(t == 1)                  // a local game hides its cursor in gamepad mode
        head.invalidate()
    }

    override fun onDetachedFromWindow() { InputSink.gamePointer(false); super.onDetachedFromWindow() }

    /** Tab goes only to the stream, never a local game (the key-safety ruling) */
    private fun toPc() = (InputSink.sink as? TapSink)?.route == "stream"
    private fun clear() { count = 0; dots.invalidate() }
    private fun tab() { if (toPc()) { InputSink.signInTab(); clear() } }

    /** one controller press */
    fun press(button: String) {
        when {
            button == "PAD2" -> onClose()
            button == "PADLSHOULDER" -> show(0)
            button == "PADRSHOULDER" -> show(1)
            tab == 1 -> pad.press(button)
            button == "PAD3" -> tab()
            else -> kb.press(button)
        }
    }

    private inner class Head(c: Context) : View(c) {
        private fun tabRect(i: Int) = RectF(width / 2f - 360f + i * 370f, 18f, width / 2f - 10f + i * 370f, 98f)
        private fun close() = RectF(width - 40f - CLOSE_W, 18f, width - 40f, 98f)
        override fun onDraw(g: Canvas) {
            UiKit.tab(g, tabRect(0), "Keyboard", tab == 0)
            UiKit.tab(g, tabRect(1), "Trackpad", tab == 1)
            UiKit.pad(g, "${TrackpadView.glyphSet()}_lshoulder", tabRect(0).left + 40f, tabRect(0).centerY(), 44f)
            UiKit.pad(g, "${TrackpadView.glyphSet()}_rshoulder", tabRect(1).right - 40f, tabRect(1).centerY(), 44f)
            UiKit.button(g, close(), "Close")
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) when {
                close().contains(e.x, e.y) -> onClose()
                tabRect(0).contains(e.x, e.y) -> show(0)
                tabRect(1).contains(e.x, e.y) -> show(1)
            }
            return true
        }
    }

    private inner class Dots(c: Context) : View(c) {
        override fun onDraw(g: Canvas) {
            if (count == 0) return
            val p = UiKit.bodyPaint(44f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            g.drawText("•".repeat(count.coerceAtMost(32)), width / 2f, (height - BOARD_H + HEAD_H) / 2f + 16f, p)
        }
    }

    companion object {
        private const val HELD = "x"
        private const val BOARD_H = 780
        private const val HEAD_H = 116
        private const val CLOSE_W = 160f
    }
}
