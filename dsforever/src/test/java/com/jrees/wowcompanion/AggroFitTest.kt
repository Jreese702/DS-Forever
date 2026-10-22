package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** the shared aggro radius rule (AggroFit: the route's live discs; Interact's tint mirrors it), fitted and learning from the pulls */
class AggroFitTest {
    @org.junit.Before fun fresh() { AggroFit.reset() }
    @org.junit.After fun done() { AggroFit.reset() }

    // ---- "Fit it" (2026-10-22): the shared rule, fitted to test58's pulls and learning from the device's own
    private var t = 0.0
    private fun pull(lv: Int, plv: Int, lo: Double, hi: Double, n: Int? = 500) = AggroDoc.Pull(1, n, null, lv, plv, lo, hi, t++)

    @Test fun theFittedRuleFallsFasterUnderThePlayersLevel() {
        assertEquals(2.2, AggroFit.kBelow(), 1e-9)                       // the prior, test58's vote
        assertEquals(20.0, AggroFit.radius(14, 14), 1e-9)                // equal level: the base
        assertEquals(23.0, AggroFit.radius(17, 14), 1e-9)                // above: classic's yard a level
        assertEquals(13.4, AggroFit.radius(11, 14), 1e-9)                // 3 under: 20 - 3 x 2.2
        assertEquals(9.0, AggroFit.radius(9, 14), 1e-9)                  // 5 under (test58's mobs 8-10 against 13-14: measured ~5..10)
        assertEquals(5.0, AggroFit.radius(4, 14), 1e-9)                  // the floor
        assertEquals(30.0, AggroFit.radius(-1, 20), 1e-9)                // a skull: the player's level + 10
        assertEquals(45.0, AggroFit.radius(80, 20), 1e-9)                // the cap
    }

    @Test fun thePullsUnderThePlayersLevelTeachTheSlopeOutliersOutvotedWithinBounds() {
        // test58's ten pulls as measured (mobs 9 against 14: 5 under): 10..10 x2, 5..8, 8..10 x3, 10..15, 15..20, 0..5, 30..35
        for ((lo, hi) in listOf(10.0 to 10.0, 10.0 to 10.0, 5.0 to 8.0, 8.0 to 10.0, 8.0 to 10.0, 10.0 to 15.0, 15.0 to 20.0, 8.0 to 10.0, 0.0 to 5.0, 30.0 to 35.0))
            AggroFit.add(pull(9, 14, lo, hi))
        // the vote: k in [(20 - hi) / 5, (20 - lo) / 5] - the most at k = 2.0 (10..10 x2, 8..10 x3, 10..15); 30..35 (a link) outvoted
        assertEquals(2.0, AggroFit.kBelow(), 1e-9)
        assertEquals(10.0, AggroFit.radius(9, 14), 1e-9)
        assertEquals(20.0, AggroFit.radius(14, 14), 1e-9)               // the base untouched: no pull near the level
        assertTrue(AggroFit.words(), AggroFit.words().contains("2.00 yd a level under (10 pulls"))
        // pulls that would say k = 6 (radius 0 at 5 under... wider than any bound): held at K_MAX
        AggroFit.reset()
        repeat(AggroFit.MIN_PULLS) { AggroFit.add(pull(16, 20, 0.0, 1.0)) }   // 4 under, r 0..1: k 4.75..5
        assertEquals(AggroFit.K_MAX, AggroFit.kBelow(), 1e-9)
    }

    @Test fun pullsNearTheLevelTeachTheBaseNotTheSlope() {
        // test70: a few pulls teach nothing (4 had moved the base to 11 yd)
        repeat(4) { AggroFit.add(pull(14, 14, 10.0, 11.0)) }
        assertEquals(20.0, AggroFit.base(500, null), 1e-9)
        AggroFit.reset()
        repeat(AggroFit.MIN_PULLS) { AggroFit.add(pull(14, 14, 22.0, 24.0)) }
        assertEquals(22.0, AggroFit.base(500, null), 1e-9)
        assertEquals(AggroFit.K_BELOW_PRIOR, AggroFit.kBelow(), 1e-9)
        assertEquals(22.0 - 2 * AggroFit.K_BELOW_PRIOR, AggroFit.radius(12, 14, 500), 1e-9)
    }
}
