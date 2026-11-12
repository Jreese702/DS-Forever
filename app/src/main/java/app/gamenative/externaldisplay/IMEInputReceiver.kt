package app.gamenative.externaldisplay

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Invisible view that receives IME (soft keyboard) input from Android system keyboard
 * and forwards it to XServer as keyboard events for the game.
 * 
 * This is needed when the system keyboard is pinned to the external display
 */
class IMEInputReceiver(
    context: Context,
    private val xServer: XServer,
    private val displayContext: Context = context,
) : FrameLayout(context) {

    val screenWidth: Int get() = xServer.screenInfo.width.toInt()
    val screenHeight: Int get() = xServer.screenInfo.height.toInt()

    suspend fun clickAt(x: Int, y: Int) {
        xServer.injectPointerMove(x, y)
        delay(60)
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        delay(80)
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        delay(60)
    }

    fun movePointerTo(x: Int, y: Int) {
        xServer.injectPointerMove(x, y)
    }

    companion object {
        private val SHIFTED_SYMBOLS = setOf(
            ')', '!', '@', '#', '$', '%', '^', '&', '*', '(',
            '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~',
        )

        /** a key held this long (two of the game's frames at 60 fps), Shift leads / trails the key by [SHIFT_LEAD_MS], [KEY_GAP_MS] between keys */
        const val KEY_HOLD_MS = 35L
        const val SHIFT_LEAD_MS = 20L
        const val KEY_GAP_MS = 25L
    }

    private var imeSessionActive = false

    /**
     * DS FOREVER (the user, 2026-11-11: "when using the login keyboard symbols do not work", all of them): every shifted character
     * went Shift down, key down, SHIFT UP, key up, all in the same instant, so the game read the key with Shift already up. Now
     * each character is a real key stroke in order (Shift held over the key's press AND release) with a gap the game's frame can
     * see, on one worker thread so the order of characters and backspaces is kept and the keyboard's thread never sleeps.
     */
    private val typer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ime-typer").apply { isDaemon = true } }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onCheckIsTextEditor(): Boolean {
        val isEditor = imeSessionActive && hasFocus()
        Timber.d("IMEInputReceiver: onCheckIsTextEditor called - returning $isEditor")
        return isEditor
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        if (!imeSessionActive || !hasFocus()) {
            Timber.d(
                "IMEInputReceiver: onCreateInputConnection ignored (active=$imeSessionActive, hasFocus=${hasFocus()})",
            )
            return BaseInputConnection(this, false)
        }

        Timber.d("IMEInputReceiver: onCreateInputConnection called!")
        // Disable autocomplete/suggestions so each key commits immediately
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or 
                             EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                             EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or 
                              EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                              EditorInfo.IME_ACTION_NONE
        
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // issue #160 review: never the text itself (a debug build logged a typed password here); its length only
                Timber.d("IMEInputReceiver: commitText called (${text?.length ?: 0} chars)")
                text?.let(::typeText)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                Timber.d("IMEInputReceiver: deleteSurroundingText called")
                if (beforeLength > 0) typer.execute { repeat(beforeLength) { backspace() } }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    typer.execute { backspace() }
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Timber.d("IMEInputReceiver: onWindowFocusChanged: $hasWindowFocus")
        if (hasWindowFocus && imeSessionActive && !hasFocus()) {
            post { requestFocus() }
        }
    }

    private data class KeyDispatch(
        val keyCode: Int,
        val requiresShift: Boolean,
    )

    fun typeText(text: CharSequence) {
        val chars = text.toString()
        typer.execute { chars.forEach(::sendCharacterToGame) }
    }

    private fun backspace() {
        xServer.injectKeyPress(com.winlator.xserver.XKeycode.KEY_BKSP, 0)
        pause(KEY_HOLD_MS)
        xServer.injectKeyRelease(com.winlator.xserver.XKeycode.KEY_BKSP)
        pause(KEY_GAP_MS)
    }

    private fun pause(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

    private fun keyEvent(action: Int, keyCode: Int, meta: Int) = KeyEvent(0L, 0L, action, keyCode, 0, meta)

    private fun sendCharacterToGame(char: Char) {
        val keyDispatch = charToKeyDispatch(char)
        if (keyDispatch == null) {
            Timber.w("IMEInputReceiver: a character with no key was skipped")    // never the character itself: it may be a password's
            return
        }
        val kb = xServer.keyboard
        if (keyDispatch.requiresShift) {
            val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            kb.onKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0))   // Shift down first (meta 0: no synthetic Shift on top)
            pause(SHIFT_LEAD_MS)
            kb.onKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, keyDispatch.keyCode, shiftMeta))
            pause(KEY_HOLD_MS)
            kb.onKeyEvent(keyEvent(KeyEvent.ACTION_UP, keyDispatch.keyCode, shiftMeta))
            pause(SHIFT_LEAD_MS)
            kb.onKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))           // ...and up only after the key
        } else {
            kb.onKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, keyDispatch.keyCode, 0))
            pause(KEY_HOLD_MS)
            kb.onKeyEvent(keyEvent(KeyEvent.ACTION_UP, keyDispatch.keyCode, 0))
        }
        pause(KEY_GAP_MS)
    }

    private fun charToKeyDispatch(char: Char): KeyDispatch? {
        val isUppercaseLetter = char in 'A'..'Z'
        val normalizedChar = if (isUppercaseLetter) char.lowercaseChar() else char

        val keyCode = when (normalizedChar) {
            'a' -> KeyEvent.KEYCODE_A
            'b' -> KeyEvent.KEYCODE_B
            'c' -> KeyEvent.KEYCODE_C
            'd' -> KeyEvent.KEYCODE_D
            'e' -> KeyEvent.KEYCODE_E
            'f' -> KeyEvent.KEYCODE_F
            'g' -> KeyEvent.KEYCODE_G
            'h' -> KeyEvent.KEYCODE_H
            'i' -> KeyEvent.KEYCODE_I
            'j' -> KeyEvent.KEYCODE_J
            'k' -> KeyEvent.KEYCODE_K
            'l' -> KeyEvent.KEYCODE_L
            'm' -> KeyEvent.KEYCODE_M
            'n' -> KeyEvent.KEYCODE_N
            'o' -> KeyEvent.KEYCODE_O
            'p' -> KeyEvent.KEYCODE_P
            'q' -> KeyEvent.KEYCODE_Q
            'r' -> KeyEvent.KEYCODE_R
            's' -> KeyEvent.KEYCODE_S
            't' -> KeyEvent.KEYCODE_T
            'u' -> KeyEvent.KEYCODE_U
            'v' -> KeyEvent.KEYCODE_V
            'w' -> KeyEvent.KEYCODE_W
            'x' -> KeyEvent.KEYCODE_X
            'y' -> KeyEvent.KEYCODE_Y
            'z' -> KeyEvent.KEYCODE_Z
            '0', ')' -> KeyEvent.KEYCODE_0
            '1', '!' -> KeyEvent.KEYCODE_1
            '2', '@' -> KeyEvent.KEYCODE_2
            '3', '#' -> KeyEvent.KEYCODE_3
            '4', '$' -> KeyEvent.KEYCODE_4
            '5', '%' -> KeyEvent.KEYCODE_5
            '6', '^' -> KeyEvent.KEYCODE_6
            '7', '&' -> KeyEvent.KEYCODE_7
            '8', '*' -> KeyEvent.KEYCODE_8
            '9', '(' -> KeyEvent.KEYCODE_9
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\n' -> KeyEvent.KEYCODE_ENTER
            '\t' -> KeyEvent.KEYCODE_TAB
            '-', '_' -> KeyEvent.KEYCODE_MINUS
            '=', '+' -> KeyEvent.KEYCODE_EQUALS
            '[', '{' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']', '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\', '|' -> KeyEvent.KEYCODE_BACKSLASH
            ';', ':' -> KeyEvent.KEYCODE_SEMICOLON
            '\'', '"' -> KeyEvent.KEYCODE_APOSTROPHE
            ',', '<' -> KeyEvent.KEYCODE_COMMA
            '.', '>' -> KeyEvent.KEYCODE_PERIOD
            '/', '?' -> KeyEvent.KEYCODE_SLASH
            '`', '~' -> KeyEvent.KEYCODE_GRAVE
            else -> null
        } ?: return null

        val requiresShift = isUppercaseLetter || char in SHIFTED_SYMBOLS

        return KeyDispatch(keyCode = keyCode, requiresShift = requiresShift)
    }

    /**
     * Focus, then show. [onResult] (DS Forever's sign-in, p-auth-keyboard) gets InputMethodManager's answer (RESULT_SHOWN, ...) or
     * null when there is no IMM: the caller decides whether a fallback is needed, so a keyboard that opened is never toggled shut.
     */
    fun showKeyboard(onResult: ((Int?) -> Unit)? = null) {
        post {
            imeSessionActive = true
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            val imm = displayContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm == null) { onResult?.invoke(null); return@post }
            val answer = onResult?.let { cb ->
                object : android.os.ResultReceiver(handler) {
                    override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) = cb(resultCode)
                }
            }
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT, answer)
            Timber.d("IMEInputReceiver: Requested to show keyboard")
        }
    }

    fun hideKeyboard() {
        post {
            val imm = displayContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
            clearFocus()
            imeSessionActive = false
            isFocusable = false
            isFocusableInTouchMode = false
            Timber.d("IMEInputReceiver: Requested to hide keyboard")
        }
    }
}
