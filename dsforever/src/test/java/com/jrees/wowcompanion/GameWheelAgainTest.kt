package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GitHub #306 (the user's pick 2026-11-16: the game's own pet / spell wheel "still shown ~0.5 s after our class wheel lets go and no room
 * holds the pad": "make it visible (alpha 1) and close it once more the safe way"): the add-on's "gamewheel" document (ActionBar gws2)
 * gets ONE more tap on the shut cell, only while that cell is built, never over our own class wheel, at most once a second.
 */
class GameWheelAgainTest {
    @Test fun theShutCellIsTappedOnceMore() {
        assertEquals(24 to "sent", GameWheelAgain.decide(24, classHolding = false, now = 10_000L, lastAt = null))
        assertEquals(24 to "sent", GameWheelAgain.decide(24, classHolding = false, now = 10_000L, lastAt = 8_000L))
    }

    @Test fun notWithoutABuiltCellNorOverOurWheelNorTwiceASecond() {
        assertNull("no col: the cell is not built as the shut", GameWheelAgain.decide(null, false, 10_000L, null).first)
        assertNull(GameWheelAgain.decide(-1, false, 10_000L, null).first)
        assertNull("our class wheel holds again: its own open / close taps shut the game's", GameWheelAgain.decide(24, true, 10_000L, null).first)
        val (col, why) = GameWheelAgain.decide(24, false, 10_500L, 10_000L)
        assertNull(col)
        assertEquals("one already sent this second", why)
    }

    @Test fun theWheelsAreNamedForTheLog() {
        assertEquals("GamepadPetActionFlyout", GameWheelAgain.name(1))
        assertEquals("GamepadSpellFlyout", GameWheelAgain.name(3))
        assertEquals("wheel 9", GameWheelAgain.name(9))
    }
}
