package com.jrees.wowcompanion

import android.view.KeyEvent

/**
 * OUR POP-UPS NEVER SEND INPUT TO THE GAME (the user before 1.0.7: "When clicking on the pop up boxes, it shouldn't send inputs to the
 * game"). While one of the bottom screen's pop-ups asks for a press with the game up (the sign-in pop-ups with buttons, the report's
 * notice on Home), every pad button they use is held back from the game: A, B and the D-pad (keys, and the hat axis the Thor's D-pad
 * really sends). Only presses: a release always passes, so nothing can stick down in the game. It all comes back the moment the pop-up
 * goes, and while our own login form is open (its own text fields). DS Forever's own pad copy is never affected. Pure: tested.
 */
object PopupHold {
    /** the pad buttons the pop-ups use */
    val HELD_KEYS = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /** the sign-in states whose pop-up has buttons in the game */
    private val SIGN_IN_ASKS = setOf(SetupFlow.SignIn.FAILED, SetupFlow.SignIn.RESTART, SetupFlow.SignIn.TYPING,
        SetupFlow.SignIn.AUTH, SetupFlow.SignIn.CHECK)

    fun holds(signIn: SetupFlow.SignIn, gameUp: Boolean, loginFormOpen: Boolean, noticeAsking: Boolean): Boolean =
        gameUp && !loginFormOpen && (noticeAsking || signIn in SIGN_IN_ASKS)

    /** a key press the game must not get now ([holding] = [holds]); releases always pass */
    fun holdsKey(holding: Boolean, keyCode: Int, down: Boolean): Boolean = holding && down && keyCode in HELD_KEYS

    /** a D-pad sent as the hat axis (the Thor): held while it points anywhere; its return to centre always passes */
    fun holdsHat(holding: Boolean, hatX: Float, hatY: Float): Boolean = holding && (hatX != 0f || hatY != 0f)
}
