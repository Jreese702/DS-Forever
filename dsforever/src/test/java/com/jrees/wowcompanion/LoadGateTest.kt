package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-19 (a Thor log): the login's top-screen taps, the one restore tap among them, went while its loading screen was still up
 * and were lost. [LoadGate] holds them until the add-on's loading-screen end (Core.lua core1), at most its max.
 */
class LoadGateTest {
    private val settle = GameUiPolicy.SETTLE_MS
    private val after = 1_000L

    @Test fun theLoginsLoadingScreenHoldsTheTapsUntilItsEnd() {
        val g = LoadGate().apply { reports = true }
        val hello = 100_000L
        g.hello(hello)                                   // a current hello: the add-ons load under the loading screen
        assertTrue(g.loading(hello + 20_000L))
        assertTrue(g.settleAt(hello + 20_000L, hello, settle, after) > hello + 1_000_000L)   // not yet, however long it takes
        g.end(hello + 25_000L)                           // the add-on's load off
        assertFalse(g.loading(hello + 25_000L))
        assertEquals(hello + 26_000L, g.settleAt(hello + 25_100L, hello, settle, after))
    }

    @Test fun aShortLoadStillWaitsTheOldSettle() {
        val g = LoadGate()
        val hello = 100_000L
        g.start(hello); g.end(hello + 500L)
        assertEquals(hello + settle, g.settleAt(hello + 600L, hello, settle, after))
    }

    @Test fun aLostEndNeverBlocksForLong() {
        val g = LoadGate(maxMs = 60_000L)
        val hello = 100_000L
        g.start(hello)
        assertTrue(g.loading(hello + 59_999L))
        assertFalse(g.loading(hello + 60_000L))
        assertEquals(hello + settle, g.settleAt(hello + 60_000L, hello, settle, after))
    }

    @Test fun aZoneLoadingScreenHoldsTapsToo() {
        val g = LoadGate()
        val hello = 100_000L
        g.start(hello); g.end(hello + 5_000L)
        g.start(hello + 300_000L)                        // Core.lua's load on: a zone
        assertTrue(g.loading(hello + 301_000L))
        g.end(hello + 310_000L)
        assertEquals(hello + 311_000L, g.settleAt(hello + 310_100L, hello, settle, after))
    }

    @Test fun addOnsThatNeverReportLoadingScreensKeepTheOldWait() {
        val g = LoadGate()                               // no load document seen yet: an add-on set before core1 never sends an end
        val hello = 100_000L
        g.hello(hello)
        assertFalse(g.loading(hello + 1_000L))
        assertEquals(hello + settle, g.settleAt(hello + 1_000L, hello, settle, after))
    }

    @Test fun anEndWithoutAStartIsIgnored() {
        val g = LoadGate()
        g.end(5_000L)
        assertEquals(0L, g.overAt())
        assertEquals(1_000L + settle, g.settleAt(2_000L, 1_000L, settle, after))
    }

    @Test fun anOldEndBeforeThisHelloDoesNotCount() {
        val g = LoadGate()
        g.start(1_000L); g.end(2_000L)                   // the last load
        val hello = 500_000L                            // a hello whose own end has not come yet would be loading; this one
        assertEquals(hello + settle, g.settleAt(hello + 10L, hello, settle, after))   // never started one (no start): hello rule
    }
}
