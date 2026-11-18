package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #533 (mummigast): a switch the game never answers is retried 1.5 s apart three times, then parked (the switches after it still go)
 * with exactly ONE probe tap every 30 s - not a fresh burst of quick taps once the park runs out (the PR review) - and only the part's
 * own answer resets it, never another tap's on the same row.
 */
class TapBackoffTest {
    private val cols = mapOf("PLAYER_SHOW" to 0, "PLAYER_HIDE" to 1, "TARGET_SHOW" to 2, "TARGET_HIDE" to 3, "NP_OFF" to 4, "NP_ON" to 5,
        "PRD_OFF" to 6, "PRD_ON" to 7)
    private val want = mapOf(GameUi.SW_PLAYER to false, GameUi.SW_TARGET to false, GameUi.SW_NP to true, GameUi.SW_PRD to true)

    @Test fun threeQuickThenOneProbeEvery30s() {
        val b = TapBackoff()
        var sig: Any? = "stuck"
        val taps = mutableListOf<Long>()
        // the game never answers: the reconcile runs every 250 ms for 95 s
        for (t in 0L..95_000L step 250L) {
            b.answered { sig }
            if ("p" in b.parked(t)) continue
            if (b.due("p", t)) { b.tapped("p", sig, t); taps.add(t) }
        }
        // 0, 1.75, 3.5 s (1.5 s apart, on the 250 ms ticks), then one probe 30 s after the last tap, each 30 s on
        assertEquals(listOf(0L, 1_750L, 3_500L, 33_500L, 63_500L, 93_500L), taps)
    }

    @Test fun parkedOnlyAfterTheLastTapsRetryWindow() {
        val b = TapBackoff()
        b.tapped("p", "x", 0); b.tapped("p", "x", 1_600); b.tapped("p", "x", 3_200)
        assertFalse("the third tap's answer may still come: not skipped yet", "p" in b.parked(4_000))
        assertFalse(b.due("p", 4_000))
        assertTrue("then parked: the switches after it go", "p" in b.parked(5_000))
        assertTrue("its probe", b.due("p", 33_200))
        assertFalse("out of the park for the probe", "p" in b.parked(33_200))
    }

    @Test fun onlyThePartsOwnAnswerResetsIt() {
        val b = TapBackoff()
        b.tapped("p", "x", 0); b.tapped("p", "x", 2_000); b.tapped("p", "x", 4_000)
        b.answered { if (it == "p") "x" else "moved" }                // another part's answer (or the row's count) moved: no reset
        assertEquals(3, b.taps("p"))
        b.answered { "answered" }                                     // the part's own word / set moved: forgotten, tapped at once
        assertEquals(0, b.taps("p"))
        assertTrue(b.due("p", 4_001))
    }

    @Test fun clearOnAFreshLogin() {
        val b = TapBackoff()
        repeat(3) { b.tapped("p", "x", it * 2_000L) }
        b.clear()
        assertTrue(b.due("p", 4_100))
    }

    @Test fun swNextSkipPassesTheParkedPartOver() {
        // the player frame and the target frame both waiting (never set since the reload); the player frame parked: the target goes
        val s = GameUi.Sw("uisw", cols, false, false, true, true, mapOf("np" to true, "prd" to true))
        assertEquals(GameUi.SW_PLAYER to false, GameUi.swNext(s) { want[it]!! })
        assertEquals(GameUi.SW_TARGET to false, GameUi.swNextSkip(s, setOf(GameUi.SW_PLAYER)) { want[it]!! })
        // every waiting part parked: nothing (the HUD steps may go meanwhile)
        assertNull(GameUi.swNextSkip(s, setOf(GameUi.SW_PLAYER, GameUi.SW_TARGET)) { want[it]!! })
    }

    @Test fun aPartsSignatureIsItsWordAndSetFlag() {
        val s = GameUi.Sw("uisw", cols, false, false, true, true, mapOf("np" to true))
        assertEquals(false to null, GameUi.swSig(s, GameUi.SW_PLAYER))
        assertEquals(false to true, GameUi.swSig(s.copy(set = mapOf("player" to true)), GameUi.SW_PLAYER))
        assertNull(GameUi.swSig(null, GameUi.SW_PLAYER))
    }
}
