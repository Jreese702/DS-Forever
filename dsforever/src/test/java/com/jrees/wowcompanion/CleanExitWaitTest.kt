package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** the wait after the clean exit's Exit tap (test12's install: "still running after 35 s" with no word on why) */
class CleanExitWaitTest {
    @Test fun noAnswerFromTheExitCellTapsOnceMore() {
        assertEquals(CleanExit.Wait.On, CleanExit.waitAfterTap(2_000L, acked = false, retapped = false, countLeft = null))
        assertEquals(CleanExit.Wait.Retap, CleanExit.waitAfterTap(4_000L, acked = false, retapped = false, countLeft = null))
        assertEquals(CleanExit.Wait.On, CleanExit.waitAfterTap(5_000L, acked = false, retapped = true, countLeft = null))
    }

    @Test fun theGamesOwnCountdownIsWaitedOut() {
        assertEquals(CleanExit.Wait.On, CleanExit.waitAfterTap(40_000L, acked = true, retapped = false, countLeft = 3))
        val w = CleanExit.waitAfterTap(60_000L, acked = true, retapped = false, countLeft = 3)
        assertTrue(w is CleanExit.Wait.GiveUp && w.why.contains("stopped at 3s"))
    }

    @Test fun theGiveUpSaysWhy() {
        val never = CleanExit.waitAfterTap(36_000L, acked = false, retapped = true, countLeft = null)
        assertTrue(never is CleanExit.Wait.GiveUp && never.why.contains("never answered"))
        val noCount = CleanExit.waitAfterTap(36_000L, acked = true, retapped = false, countLeft = null)
        assertTrue(noCount is CleanExit.Wait.GiveUp && noCount.why.contains("no QUIT countdown"))
    }
}
