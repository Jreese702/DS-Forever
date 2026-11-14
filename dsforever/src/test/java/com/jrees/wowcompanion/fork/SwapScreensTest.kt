package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.ScreenSwap
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Bottom
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Move
import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #232 SWAP SCREENS (the user, 2026-11-14: build it, untested - no such device here): which display gets the game and which
 * DS Forever, off-device. Off must be exactly the Thor's arrangement (#196 included); On swaps them, display 0 taking DS Forever as
 * an activity (it takes no Presentation).
 */
class SwapScreensTest {
    private val MAIN = 0                                               // Display.DEFAULT_DISPLAY: the Thor's top screen
    private val thorBottom = Screen(4, builtIn = true, name = "Built-in Screen")
    private val tv = Screen(5, builtIn = false, name = "HDMI Screen")
    private val addOn = Screen(2, builtIn = false, name = "USB-C Screen")    // a dual-screen add-on on a USB-C port

    @After fun clean() = ScreenSwap.reset()

    // ---- Off: the Thor, unchanged

    @Test fun offTheGameStaysOnDisplay0() {
        assertEquals(MAIN, DsForeverScreen.gameScreen(listOf(thorBottom), swap = false))
        assertEquals(MAIN, DsForeverScreen.gameScreen(listOf(tv, thorBottom), swap = false, current = 4))
        assertEquals(MAIN, DsForeverScreen.gameScreen(emptyList(), swap = false))
    }

    @Test fun offDsForeverIsThePresentationPickScreenGives() {
        for (screens in listOf(listOf(thorBottom), listOf(tv, thorBottom), listOf(tv), emptyList(), listOf(thorBottom, thorBottom.copy(id = 3))))
            for (current in listOf(null, 4, 5)) {
                val want = DsForeverScreen.pickScreen(screens, MAIN, current)?.let { Bottom(it, activity = false) }
                assertEquals("$screens / $current", want, DsForeverScreen.bottomScreen(screens, MAIN, swap = false, current = current))
            }
        assertEquals(Bottom(4, false), DsForeverScreen.bottomScreen(listOf(tv, thorBottom), MAIN, swap = false, current = null))
    }

    @Test fun offResumeMovesAsBefore() {
        // the same answers as ResumeDisplayTest with no target given
        for (d in listOf(0, 4)) for (g in listOf(true, false)) for (since in listOf(1_000L, Long.MAX_VALUE))
            assertEquals(DsForeverScreen.decide(d, g, since), DsForeverScreen.decide(d, g, since, MAIN))
    }

    // ---- On: swapped

    @Test fun onTheGameGoesToTheSecondScreen() {
        assertEquals(4, DsForeverScreen.gameScreen(listOf(thorBottom), swap = true))
        assertEquals(2, DsForeverScreen.gameScreen(listOf(addOn), swap = true))          // the add-on is the only other screen: it is the one
    }

    /** #196 still holds: a TV never takes the game from the built-in second screen, listed first or plugged in later */
    @Test fun onATvNeverTakesTheGame() {
        assertEquals(4, DsForeverScreen.gameScreen(listOf(tv, thorBottom), swap = true))
        assertEquals(4, DsForeverScreen.gameScreen(listOf(tv, thorBottom), swap = true, current = 4))
    }

    @Test fun onWithASingleScreenNothingMoves() {
        assertEquals(MAIN, DsForeverScreen.gameScreen(emptyList(), swap = true))
        assertNull(DsForeverScreen.bottomScreen(emptyList(), MAIN, swap = true, current = null))
    }

    @Test fun onDsForeverGoesToDisplay0AsTheActivity() {
        assertEquals(Bottom(MAIN, activity = true), DsForeverScreen.bottomScreen(listOf(thorBottom), game = 4, swap = true, current = null))
        // a TV changes nothing (it is never DS Forever's either)
        assertEquals(Bottom(MAIN, activity = true), DsForeverScreen.bottomScreen(listOf(tv, thorBottom), game = 4, swap = true, current = MAIN))
        // display 0 listed as a presentation display (no device does this): a Presentation there after all
        assertEquals(Bottom(MAIN, activity = false), DsForeverScreen.bottomScreen(listOf(Screen(MAIN, true, "main"), thorBottom), 4, true, null))
    }

    /** the second screen gone (unplugged) while the game ran on it: it is back on display 0, DS Forever on whatever is left */
    @Test fun onSecondScreenGoneTheGameIsOnDisplay0Again() {
        assertNull(DsForeverScreen.bottomScreen(emptyList(), MAIN, swap = true, current = MAIN))
        assertEquals(Bottom(5, false), DsForeverScreen.bottomScreen(listOf(tv), MAIN, swap = true, current = null))
    }

    @Test fun onResumeMovesTheLauncherToTheSecondScreen() {
        // the icon tapped on display 0's home screen: no game -> started again there; a game running -> the same task moved
        assertEquals(Move.RELAUNCH, DsForeverScreen.decide(MAIN, gameRunning = false, sinceLastMoveMs = Long.MAX_VALUE, target = 4))
        assertEquals(Move.BRING_TO_TOP, DsForeverScreen.decide(MAIN, gameRunning = true, sinceLastMoveMs = Long.MAX_VALUE, target = 4))
        assertEquals(Move.NONE, DsForeverScreen.decide(4, gameRunning = true, sinceLastMoveMs = Long.MAX_VALUE, target = 4))
        assertEquals(Move.NONE, DsForeverScreen.decide(MAIN, gameRunning = true, sinceLastMoveMs = 1_000L, target = 4))   // just moved
    }

    // ---- the activity's start guard

    @Test fun theActivityIsNotStartedInALoop() {
        val now = 100_000L
        assertTrue(DsForeverScreen.startAllowed(emptyList(), now))
        assertTrue(DsForeverScreen.startAllowed(listOf(now - 1_000, now - 500), now))
        assertFalse(DsForeverScreen.startAllowed(listOf(now - 3_000, now - 1_000, now - 500), now))
        // old starts do not count
        assertTrue(DsForeverScreen.startAllowed(listOf(now - DsForeverScreen.START_GUARD_MS, now - 20_000, now - 500), now))
    }

    // ---- the Settings row's Restart (ScreenSwap)

    @Test fun restartRowOnlyWhenTheChoiceIsNotTheOneInUse() {
        val p = ScreenSwap::pending
        assertEquals(ScreenSwap.Pending.NONE, p(false, false, false, true))
        assertEquals(ScreenSwap.Pending.NONE, p(true, true, true, true))
        assertEquals(ScreenSwap.Pending.RESTART, p(true, false, false, true))
        assertEquals(ScreenSwap.Pending.RESTART, p(false, true, false, true))
        // the game up: a restart would end it, so only "after the game closes"
        assertEquals(ScreenSwap.Pending.AFTER_GAME, p(true, false, true, true))
        // no launcher (nothing latched, no restart): nothing
        assertEquals(ScreenSwap.Pending.NONE, p(true, null, false, true))
        assertEquals(ScreenSwap.Pending.NONE, p(true, false, false, false))
    }

    @Test fun theChoiceAppliesFromAStart() {
        assertNull(ScreenSwap.inUseNow)
        ScreenSwap.take(false)
        assertEquals(false, ScreenSwap.inUseNow)
        // saved On later: still Off in this run until the restart takes it
        assertEquals(ScreenSwap.Pending.RESTART, ScreenSwap.pending(true, ScreenSwap.inUseNow, false, true))
        ScreenSwap.take(true)
        assertEquals(ScreenSwap.Pending.NONE, ScreenSwap.pending(true, ScreenSwap.inUseNow, false, true))
    }

    // ---- the problem report

    @Test fun theReportNamesTheDisplays() {
        val thor = ScreenSwap.placement(MAIN, 4, asActivity = false, seen = "4:in:Built-in Screen")
        assertEquals("game on display 0, DS Forever on display 4 (presentation); presentation displays 4:in:Built-in Screen", thor)
        assertTrue(ScreenSwap.note(thor))
        assertFalse("the same placement is noted once", ScreenSwap.note(thor))
        ScreenSwap.take(false); ScreenSwap.offered = true
        assertEquals("Swap screens: saved off, in use off; $thor", ScreenSwap.line(false))
        val swapped = ScreenSwap.placement(4, MAIN, asActivity = true, seen = "4:in:Built-in Screen, 5:ext:HDMI Screen")
        assertTrue(ScreenSwap.note(swapped))
        ScreenSwap.take(true)
        assertEquals("Swap screens: saved on, in use on; game on display 4, DS Forever on display 0 (activity); " +
            "presentation displays 4:in:Built-in Screen, 5:ext:HDMI Screen", ScreenSwap.line(true))
        ScreenSwap.offered = false
        assertTrue(ScreenSwap.line(true).contains("(no second screen)"))
        assertEquals("game on display 0, DS Forever on no screen; presentation displays none", ScreenSwap.placement(MAIN, null, false, ""))
    }
}
