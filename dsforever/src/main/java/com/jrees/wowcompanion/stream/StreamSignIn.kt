package com.jrees.wowcompanion.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.jrees.wowcompanion.ChatKeyboard
import com.jrees.wowcompanion.InputSink
import com.jrees.wowcompanion.TapSink
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
        KeyEvent.KEYCODE_BUTTON_A -> "PAD1"; KeyEvent.KEYCODE_BUTTON_X -> "PAD3"; KeyEvent.KEYCODE_BUTTON_Y -> "PAD4"
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
 * X / the Tab key = next box, Start / the Enter key = Enter. It goes by itself when Battle.net is done; B does nothing.
 */
class SignInBoard(c: Context) : FrameLayout(c) {
    private var count = 0
    private val dots = Dots(c)
    private val kb = ChatKeyboard(c, signIn = true).apply {
        sendWord = "Enter"; swapWord = "Tab"; fillHeight = BOARD_H
        text = { HELD }
        onText = { new ->
            if (toPc()) {
                if (new.length < HELD.length) { InputSink.signInBack(); count = (count - 1).coerceAtLeast(0) }
                else { InputSink.signInChar(new.last()); count++ }
                dots.invalidate()
            }
        }
        onSend = { if (toPc()) { InputSink.gameEnter(); clear() } }
        onSwap = { tab() }
    }

    init {
        setBackgroundColor(Theme.ink)
        isClickable = true
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(kb, LayoutParams(LayoutParams.MATCH_PARENT, BOARD_H, Gravity.BOTTOM))
        kb.open()
    }

    /** only ever the stream, never a local game */
    private fun toPc() = (InputSink.sink as? TapSink)?.route == "stream"
    private fun clear() { count = 0; dots.invalidate() }
    private fun tab() { if (toPc()) { InputSink.signInTab(); clear() } }

    /** one controller press */
    fun press(button: String) { if (button == "PAD3") tab() else kb.press(button) }

    private inner class Dots(c: Context) : View(c) {
        override fun onDraw(g: Canvas) {
            if (count == 0) return
            val p = UiKit.bodyPaint(44f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            g.drawText("•".repeat(count.coerceAtMost(32)), width / 2f, (height - BOARD_H) / 2f + 16f, p)
        }
    }

    companion object {
        private const val HELD = "x"
        private const val BOARD_H = 780
    }
}
