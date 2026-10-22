package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "no room may ever stay open without a working B": the host's guard decides when Home reads B itself */
class ExitGuardTest {
    @Test fun whatNeedsTheController() {
        assertTrue(ExitGuard.needs(roomIsHome = false, homeLayer = false, wheelOpen = false, mapModuleGuarded = false))   // any room
        assertTrue(ExitGuard.needs(true, homeLayer = true, wheelOpen = false, mapModuleGuarded = false))                    // a Home module / list / tool
        assertFalse(ExitGuard.needs(true, homeLayer = false, wheelOpen = false, mapModuleGuarded = false))                  // plain Home
        assertFalse(ExitGuard.needs(false, false, wheelOpen = true, mapModuleGuarded = false))                              // the wheels read it themselves
        assertFalse(ExitGuard.needs(true, true, wheelOpen = false, mapModuleGuarded = true))                                // the map module: its own guard
    }

    @Test fun onlyWhenNobodyHoldsItForTheGrace() {
        assertFalse(ExitGuard.on(true, "pad:settings", 60_000))                       // held: the room's own B works
        assertFalse(ExitGuard.on(true, "", ExitGuard.GRACE_MS - 1))                  // a hand-over gap is no reason
        assertTrue(ExitGuard.on(true, "", ExitGuard.GRACE_MS))                       // refused in a fight / lost: Home reads B
        assertFalse(ExitGuard.on(false, "", 60_000))
    }

    @Test fun theHoldIsAskedAgainOnlyOutOfTheFight() {
        assertFalse(ExitGuard.retry(true, inFight = true, sinceAskMs = 60_000))
        assertFalse(ExitGuard.retry(true, inFight = false, sinceAskMs = ExitGuard.RETRY_MS - 1))
        assertTrue(ExitGuard.retry(true, inFight = false, sinceAskMs = ExitGuard.RETRY_MS))
        assertFalse(ExitGuard.retry(false, inFight = false, sinceAskMs = 60_000))
    }
}
