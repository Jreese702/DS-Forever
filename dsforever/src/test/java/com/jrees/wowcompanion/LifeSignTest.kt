package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * fix/stream-cursor (the user, 2026-11-19, streaming: "the cursor keeps randomly showing on the game"; taps.log: `iact:1 (desktop post)`
 * every 5 s under "Resurrect now?"): on a stream the dialogs' sign of life is ONE tap per newly drawn set (the add-on's stay cell), never
 * the periodic one; Local is unchanged.
 */
class LifeSignTest {
    private val every = 5_000L

    @Test fun localSignsAtOnceThenEveryFiveSeconds() {
        assertEquals(1 to false, LifeSign.pick(fresh = true, sinceMs = 60_000, everyMs = every, desk = false, aliveCol = 1, stayCol = 7))
        assertNull(LifeSign.pick(fresh = false, sinceMs = 4_000, everyMs = every, desk = false, aliveCol = 1, stayCol = 7))
        assertEquals(1 to false, LifeSign.pick(fresh = false, sinceMs = 5_000, everyMs = every, desk = false, aliveCol = 1, stayCol = 7))
    }

    @Test fun streamSignsOncePerDrawnSetWithTheStayCell() {
        assertEquals(7 to true, LifeSign.pick(fresh = true, sinceMs = 60_000, everyMs = every, desk = true, aliveCol = 1, stayCol = 7))
        // never again while the same set shows, however long
        assertNull(LifeSign.pick(fresh = false, sinceMs = 5_000, everyMs = every, desk = true, aliveCol = 1, stayCol = 7))
        assertNull(LifeSign.pick(fresh = false, sinceMs = 600_000, everyMs = every, desk = true, aliveCol = 1, stayCol = 7))
    }

    @Test fun streamWithAnOlderAddOnSaysAliveOnceOnly() {
        assertEquals(1 to false, LifeSign.pick(fresh = true, sinceMs = 60_000, everyMs = every, desk = true, aliveCol = 1, stayCol = -1))
        assertNull(LifeSign.pick(fresh = false, sinceMs = 60_000, everyMs = every, desk = true, aliveCol = 1, stayCol = -1))
    }

    @Test fun neverTwoSignsWithinASecond() {
        assertNull(LifeSign.pick(fresh = true, sinceMs = 400, everyMs = every, desk = true, aliveCol = 1, stayCol = 7))
        assertNull(LifeSign.pick(fresh = true, sinceMs = 400, everyMs = every, desk = false, aliveCol = 1, stayCol = 7))
    }
}
