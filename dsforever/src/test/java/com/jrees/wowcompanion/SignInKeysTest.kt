package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issues #160 / #180 / #174 / #190 (1.0.25-1.0.26: "the @ signs still dont work when I try to login"): the waiting screen's sign-in
 * keyboard dropped every character but letters, digits and space on a local game, so an email could never be typed.
 */
class SignInKeysTest {
    @After fun reset() { InputSink.sink = null }

    private class Rec : InputSink.Sink {
        val keys = ArrayList<Pair<Int, Boolean>>()
        override fun key(xKeycode: Int, down: Boolean) { synchronized(keys) { keys += xKeycode to down } }
        override fun tap(fx: Float, fy: Float) {}
    }

    @Test fun anEmailAndPasswordSymbolsAreSentOnALocalGame() {
        for (c in "player.name+wow@example.com!#\$%^&*()_-=[]{};:'\",.<>/?|\\`~ ") assertTrue("'$c'", GameTyping.signInSends(c, toPc = false))
    }

    @Test fun controlAndNonAsciiCharactersAreNeverSentToALocalGame() {
        for (c in listOf('\t', '\n', '\r', 27.toChar(), 127.toChar(), 0.toChar(), 'é', 'ß', '€')) assertFalse("${c.code}", GameTyping.signInSends(c, toPc = false))
    }

    @Test fun theStreamStillTakesAnyCharacter() = assertTrue(GameTyping.signInSends('é', toPc = true))

    @Test fun everySignInCharacterIsOneKeyWithAtMostShift() {
        // never Alt (64 / 108), Ctrl (37 / 105), Escape (9), Tab (23), Enter (36), an F-key (67-76, 95, 96)
        val forbidden = setOf(64, 108, 37, 105, 9, 23, 36) + (67..76) + setOf(95, 96)
        for (code in 32..126) {
            val c = code.toChar()
            assertTrue(GameTyping.signInAllowed(c))
            val k = InputSink.charKey(c)
            assertNotNull("'$c' has no key", k)
            k!!
            assertFalse("'$c' -> ${k.first}", k.first in forbidden)
        }
    }

    @Test fun theAtSignIsShiftAndTwo() = assertEquals(11 to true, InputSink.charKey('@'))

    @Test fun aShiftedCharacterHoldsShiftOverTheWholeKey() {
        val r = Rec(); InputSink.sink = r
        assertTrue(InputSink.typeText("a@"))
        assertEquals(listOf(38 to true, 38 to false, 50 to true, 11 to true, 11 to false, 50 to false), r.keys)
    }

    @Test fun theToolKeyboardRulingIsUnchanged() {
        // TypeTool (the pause wheel's Keyboard) keeps the key-safety ruling's letters, digits and space
        assertFalse(GameTyping.allowed('@')); assertTrue(GameTyping.allowed('a'))
    }

    @Test fun theReportLineNamesNoTypedCharacter() {
        assertEquals("sign-in: keyboard (bottom) closed, shifted keys sent, not sent 0", GameTyping.boardClosedLine(true, 0))
        assertEquals("sign-in: keyboard (bottom) closed, shifted keys none, not sent 2", GameTyping.boardClosedLine(false, 2))
    }
}
