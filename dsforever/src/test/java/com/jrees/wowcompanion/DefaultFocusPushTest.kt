package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultFocusPushTest {
    @Test fun theDefaultIsPushedOnceAndNotWhileTheGameHasAFocus() {
        val p = DefaultFocusPush()
        assertEquals(7, p.tick(7, 0, 0))
        assertEquals("not again at once", 0, p.tick(7, 0, 1_000))
        assertEquals("the game has a focus", 0, p.tick(0, 7, 2_000))
        assertEquals("none", 0, p.tick(0, 0, 3_000))
    }

    @Test fun aFocusThePlayerClearedIsNotForcedBack() {
        val p = DefaultFocusPush()
        assertEquals(7, p.tick(7, 0, 0))
        assertEquals(0, p.tick(0, 7, 1_000))                      // the game took it
        assertEquals("cleared by the player: shown, not pushed", 0, p.tick(7, 0, 100_000))
        assertEquals("another default is pushed", 9, p.tick(9, 0, 101_000))
    }

    @Test fun aPushTheGameNeverTookIsTriedAgainLater() {
        val p = DefaultFocusPush()
        assertEquals(7, p.tick(7, 0, 0))
        assertEquals(0, p.tick(7, 0, DefaultFocusPush.RETRY_MS - 1))
        assertEquals(7, p.tick(7, 0, DefaultFocusPush.RETRY_MS))
    }

    /** fix/desk-click-switch: never an endless retry (test 1497: a push every 30 s the game never took) */
    @Test fun aPushTheGameNeverTakesIsGivenUpAfterThreeAndSaidOnce() {
        val p = DefaultFocusPush()
        val said = ArrayList<Int>(); p.onGiveUp = { said += it }
        val r = DefaultFocusPush.RETRY_MS
        assertEquals(7, p.tick(7, 0, 0)); assertEquals(7, p.tick(7, 0, r)); assertEquals(7, p.tick(7, 0, 2 * r))
        assertEquals(0, p.tick(7, 0, 3 * r)); assertEquals(0, p.tick(7, 0, 10 * r))
        assertEquals(listOf(7), said)
        assertEquals("another quest still goes", 9, p.tick(9, 0, 10 * r))
    }
}
