package com.jrees.wowcompanion

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the user before 1.0.7: "When clicking on the pop up boxes, it shouldn't send inputs to the game" (off-device) */
class PopupHoldTest {
    private val S = SetupFlow.SignIn.AUTH

    @Test fun ourPopUpsHoldThePadFromTheGame() {
        for (st in listOf(SetupFlow.SignIn.FAILED, SetupFlow.SignIn.RESTART, SetupFlow.SignIn.TYPING, SetupFlow.SignIn.AUTH, SetupFlow.SignIn.CHECK))
            assertTrue(st.name, PopupHold.holds(st, gameUp = true, loginFormOpen = false, noticeAsking = false))
        assertTrue(PopupHold.holds(SetupFlow.SignIn.NONE, gameUp = true, loginFormOpen = false, noticeAsking = true))   // the report's notice
        for (st in listOf(SetupFlow.SignIn.NONE, SetupFlow.SignIn.SIGNING, SetupFlow.SignIn.RESTARTING))                 // nothing asks
            assertFalse(st.name, PopupHold.holds(st, gameUp = true, loginFormOpen = false, noticeAsking = false))
        assertFalse(PopupHold.holds(S, gameUp = false, loginFormOpen = false, noticeAsking = true))                     // no game
        assertFalse(PopupHold.holds(S, gameUp = true, loginFormOpen = true, noticeAsking = true))                       // our login form
    }

    @Test fun aBAndTheDpadAreHeldPressesOnly() {
        for (k in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertTrue(PopupHold.holdsKey(holding = true, keyCode = k, down = true))
            assertFalse(PopupHold.holdsKey(holding = true, keyCode = k, down = false))      // a release always passes
            assertFalse(PopupHold.holdsKey(holding = false, keyCode = k, down = true))      // the pop-up went: everything back
        }
        assertFalse(PopupHold.holdsKey(holding = true, keyCode = KeyEvent.KEYCODE_BUTTON_X, down = true))   // not one the pop-ups use
    }

    @Test fun theHatIsHeldWhileItPointsAndItsCentringPasses() {
        assertTrue(PopupHold.holdsHat(holding = true, hatX = -1f, hatY = 0f))
        assertTrue(PopupHold.holdsHat(holding = true, hatX = 0f, hatY = 1f))
        assertFalse(PopupHold.holdsHat(holding = true, hatX = 0f, hatY = 0f))
        assertFalse(PopupHold.holdsHat(holding = false, hatX = 1f, hatY = 0f))
    }
}
