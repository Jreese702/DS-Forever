package com.jrees.wowcompanion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.text.InputType
import android.util.TypedValue
import android.app.Activity
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * THE BATTLE.NET LOGIN PROMPT (LATER 29): the email and the password, typed on the Android keyboard (touch), with an eye that shows
 * the password while it is being typed. "Save and log in" (the user's pick) seals it into [HomeLoginStore] and signs in at once;
 * Cancel leaves the saved login as it was. It always opens EMPTY: the gear overwrites, it never shows what is saved.
 *
 * Sits in the upper half of the bottom screen so the keyboard, which rises from the bottom, never covers the fields or the buttons.
 */
@SuppressLint("ViewConstructor")
class HomeLoginPrompt(ctx: Context, private val onSaved: (HomeLoginStore.Login) -> Unit) : FrameLayout(ctx) {

    private val email: EditText = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "Email")
    private val password: EditText = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "Password")
    private var shown = false
    private var error = ""
    private val panel = Panel(ctx)

    init {
        visibility = GONE
        isClickable = true
        setWillNotDraw(false)
        val box = FrameLayout(ctx)
        box.addView(panel, LayoutParams(BOX_W, BOX_H))
        box.addView(email, at(FIELD_X, EMAIL_Y, FIELD_W, FIELD_H))
        box.addView(password, at(FIELD_X, PASS_Y, FIELD_W - EYE_W - 16, FIELD_H))
        // NO EXTRACT / NO FULLSCREEN: the bottom screen is landscape, and a landscape keyboard otherwise takes the whole screen with
        // its own text box (seen on the emulator 2026-10-05), hiding the prompt; with these it only rises over the lower half
        // NO PERSONALIZED LEARNING: the keyboard must not remember what is typed here (the review)
        val flags = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        password.imeOptions = EditorInfo.IME_ACTION_DONE or flags
        email.imeOptions = EditorInfo.IME_ACTION_NEXT or flags
        addView(box, LayoutParams(BOX_W, BOX_H, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = BOX_TOP })
        // never offered to an autofill service as a login pair (our own copy is the only one; the review)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    fun open() {
        email.setText(""); password.setText(""); error = ""; shown = false
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.typeface = UiKit.body                    // setting the input type resets it to monospace
        // NO SCREENSHOTS while it is open (and no frame of it in Recents): the review - captures of the bottom screen are routine
        (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        visibility = VISIBLE
        panel.invalidate()
        email.requestFocus()
        post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(email, 0) }
    }

    fun close() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        email.setText(""); password.setText("")
        visibility = GONE
        (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** B / Back closes it (and goes no further: the companion stays on the bottom screen; the review) */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (isOpen && (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) close()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    val isOpen get() = visibility == VISIBLE

    override fun onDraw(c: Canvas) { UiKit.cover(c, RectF(0f, 0f, width.toFloat(), height.toFloat())) }

    private fun save() {
        val e = email.text.toString().trim()
        val p = password.text.toString()
        val why = HomeLogin.untypable(e)?.let { "Email: $it" } ?: HomeLogin.untypable(p)?.let { "Password: $it" }
        if (why != null) { error = why; panel.invalidate(); return }
        val login = HomeLoginStore.Login(e, p)
        if (!HomeLoginStore.save(context, login)) { error = "The login could not be saved on this device"; panel.invalidate(); return }
        close()
        onSaved(login)
    }

    private fun eye() {
        shown = !shown
        val at = password.selectionEnd
        password.inputType = InputType.TYPE_CLASS_TEXT or
            if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.typeface = UiKit.body
        password.setSelection(at.coerceIn(0, password.text.length))
        panel.invalidate()
    }

    private fun field(type: Int, hint: String): EditText = EditText(context).apply {
        inputType = type
        this.hint = hint
        setSingleLine()
        typeface = UiKit.body
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 38f)
        setTextColor(Theme.text); setHintTextColor(Theme.dim)
        setBackgroundColor(Theme.ink)
        setPadding(24, 0, 24, 0)
        gravity = Gravity.CENTER_VERTICAL
        setOnEditorActionListener { _, action, _ ->
            when (action) {
                EditorInfo.IME_ACTION_NEXT -> { password.requestFocus(); true }
                EditorInfo.IME_ACTION_DONE -> { save(); true }
                else -> false
            }
        }
    }

    private fun at(x: Int, y: Int, w: Int, h: Int) = LayoutParams(w, h).apply { leftMargin = x; topMargin = y }

    /** the frame, the title, the eye, the note and the two buttons, drawn with the kit; the fields sit on top of it */
    private inner class Panel(ctx: Context) : View(ctx) {
        private val hits = ArrayList<Pair<RectF, String>>()
        private var pressed: String? = null

        override fun onDraw(c: Canvas) {
            hits.clear()
            UiKit.dialog(c, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            val cx = width / 2f
            c.drawText("Battle.net login", cx, 70f, UiKit.titlePaint(46f).apply { textAlign = android.graphics.Paint.Align.CENTER })
            val eyeR = RectF((FIELD_X + FIELD_W - EYE_W).toFloat(), PASS_Y.toFloat(), (FIELD_X + FIELD_W).toFloat(), (PASS_Y + FIELD_H).toFloat())
            UiKit.button(c, eyeR, if (shown) "Hide" else "Show", if (pressed == "eye") UiKit.Button.DOWN else UiKit.Button.UP)
            hits += eyeR to "eye"
            val note = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = android.graphics.Paint.Align.CENTER }
            c.drawText("Kept on this device only, encrypted.", cx, NOTE_Y, note)
            if (error.isNotEmpty())
                c.drawText(error, cx, NOTE_Y + 36f, UiKit.bodyPaint(28f, Theme.warn).apply { textAlign = android.graphics.Paint.Align.CENTER })
            val save = RectF(cx - 20f - SAVE_W, BUTTONS_Y, cx - 20f, BUTTONS_Y + 111f)
            val cancel = RectF(cx + 20f, BUTTONS_Y, cx + 20f + CANCEL_W, BUTTONS_Y + 111f)
            UiKit.button(c, save, "Save and log in", if (pressed == "save") UiKit.Button.DOWN else UiKit.Button.UP)
            UiKit.button(c, cancel, "Cancel", if (pressed == "cancel") UiKit.Button.DOWN else UiKit.Button.UP)
            hits += save to "save"; hits += cancel to "cancel"
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
                MotionEvent.ACTION_UP -> {
                    val was = pressed; pressed = null; invalidate()
                    if (was != null && was == hit) when (hit) { "save" -> save(); "cancel" -> close(); "eye" -> eye() }
                }
                MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
            }
            return true
        }
    }

    private companion object {
        // px on the bottom screen (1240 x 1080); the box ends at 600 so the keyboard (from ~630 down) never covers it
        const val BOX_W = 940; const val BOX_H = 580; const val BOX_TOP = 20
        // fields and the eye 111 px high = 48 dp, the touch minimum (the review)
        const val FIELD_X = 60; const val FIELD_W = 820; const val FIELD_H = 111
        const val EMAIL_Y = 96; const val PASS_Y = 222
        const val EYE_W = 170
        const val NOTE_Y = 380f
        const val BUTTONS_Y = 440f
        const val SAVE_W = 400f; const val CANCEL_W = 300f
    }
}
