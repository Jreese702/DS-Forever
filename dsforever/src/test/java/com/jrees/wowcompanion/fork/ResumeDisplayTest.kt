package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.fork.DsForeverScreen.Companion.Move
import org.junit.Assert.assertEquals
import org.junit.Test

/** b-resume-wrong-display (the user: tab out and back = the game on the bottom screen), off-device: what a resume does */
class ResumeDisplayTest {
    private val TOP = 0          // Display.DEFAULT_DISPLAY: the Thor's top screen, the game's
    private val BOTTOM = 4       // the Thor's bottom screen

    @Test fun onTheTopScreenNothing() {
        assertEquals(Move.NONE, DsForeverScreen.decide(TOP, gameRunning = true, sinceLastMoveMs = Long.MAX_VALUE))
        assertEquals(Move.NONE, DsForeverScreen.decide(TOP, gameRunning = false, sinceLastMoveMs = Long.MAX_VALUE))
    }

    @Test fun aRunningGameBroughtBackOnTheBottomGoesToTheTopWithoutFinishing() =
        assertEquals(Move.BRING_TO_TOP, DsForeverScreen.decide(BOTTOM, gameRunning = true, sinceLastMoveMs = Long.MAX_VALUE))

    @Test fun noGameRelaunchesOnTheTop() =
        assertEquals(Move.RELAUNCH, DsForeverScreen.decide(BOTTOM, gameRunning = false, sinceLastMoveMs = Long.MAX_VALUE))

    @Test fun everyResumeNotJustOnceButNeverALoop() {
        assertEquals(Move.NONE, DsForeverScreen.decide(BOTTOM, gameRunning = true, sinceLastMoveMs = 1_000))     // just moved
        assertEquals(Move.BRING_TO_TOP, DsForeverScreen.decide(BOTTOM, gameRunning = true, sinceLastMoveMs = DsForeverScreen.MOVE_GUARD_MS))
    }
}
