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
    @After fun reset() { InputSink.sink = null; InputSink.pause = { Thread.sleep(it) } }

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

    /** keys and waits in one list: a wait is (-ms, false) */
    private fun recordWithWaits(): Rec { val r = Rec(); InputSink.sink = r; InputSink.pause = { ms -> synchronized(r.keys) { r.keys += -ms.toInt() to false } }; return r }

    @Test fun theShiftLeadsAndOutlastsTheKeyWithTheTopKeyboardsTiming() {
        val r = recordWithWaits()
        assertTrue(InputSink.typeText("@"))
        val lead = -InputSink.SHIFT_LEAD_MS.toInt(); val hold = -InputSink.KEY_HOLD_MS.toInt()
        assertEquals(listOf(50 to true, lead to false, 11 to true, hold to false, 11 to false, lead to false, 50 to false, -12 to false), r.keys)
    }

    /** the board's text changes for typing [s] on a local game, as SignInBoard.onText gets them (the stand-in "x" + one character each) */
    private fun boardOps(s: String, toPc: Boolean = false) = s.map { GameTyping.signInOp("x", "x$it", toPc) }

    @Test fun theBoardSendsAnEmailsAtSignAndDotOnALocalGame() {
        // the #160 / #180 bug site: with the old filter (GameTyping.allowed) '@' and '.' were Drop here
        assertEquals("a@b.c".map { GameTyping.SignInOp.Send(it) }, boardOps("a@b.c"))
        assertEquals(GameTyping.SignInOp.Back, GameTyping.signInOp("x", "", toPc = false))
        assertEquals(GameTyping.SignInOp.Drop, GameTyping.signInOp("x", "x\t", toPc = false))
        assertEquals(GameTyping.SignInOp.Drop, GameTyping.signInOp("x", "xé", toPc = false))
        assertEquals(GameTyping.SignInOp.Send('é'), GameTyping.signInOp("x", "xé", toPc = true))
    }

    @Test fun anEmailTypedOnTheBoardReachesALocalGameAsShiftTwoAndPeriod() {
        val r = Rec(); InputSink.sink = r; InputSink.pause = {}
        for (op in boardOps("a@b.c")) assertTrue(InputSink.typeText((op as GameTyping.SignInOp.Send).c.toString()))
        // a, Shift+2 (@), b, period, c
        assertEquals(listOf(38 to true, 38 to false, 50 to true, 11 to true, 11 to false, 50 to false, 56 to true, 56 to false,
            60 to true, 60 to false, 54 to true, 54 to false), r.keys)
    }

    @Test fun theSignInBoardDecidesThroughSignInOp() {
        // guards the one-line wiring: the board must not go back to the pause wheel's filter
        val f = listOf("dsforever/src/main/java/com/jrees/wowcompanion/stream/StreamSignIn.kt", "src/main/java/com/jrees/wowcompanion/stream/StreamSignIn.kt")
            .map { java.io.File(it) }.first { it.exists() }
        val board = f.readText().substringAfter("class SignInBoard(").substringBefore("\nclass ")
            .lines().map { it.substringBefore("//") }.filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")
        val onText = board.substringAfter("onText = {").substringBefore("onSend =")
        assertTrue(onText.contains("GameTyping.signInOp(HELD, new, toPc())"))
        assertTrue(onText.contains("InputSink.signInChar(op.c)"))
        assertFalse(board.contains("GameTyping.allowed"))
        assertFalse(board.contains("gameKeys("))
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
