package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b-route-multihop (test51: from Elwynn an Ironforge quest went north over the mountains): the continent's known ways chained.
 * The zones' world rectangles are the game's own (the PC client's UiMap data: [top, bottom, left, right] in world yards).
 */
class MultiHopTest {
    private val rects = mapOf(
        1429 to doubleArrayOf(-7939.583, -10254.166, 1535.4166, -1935.4166),   // Elwynn Forest
        1453 to doubleArrayOf(-7995.8301, -9154.1699, 1722.92, -14.584),       // Stormwind City
        1455 to doubleArrayOf(-4569.2412, -5096.8457, -713.5914, -1504.2164),  // Ironforge
        1426 to doubleArrayOf(-3877.0833, -7160.4165, 1802.0833, -3122.9165),  // Dun Morogh
        1433 to doubleArrayOf(-8575.0, -10022.916, -1681.25, -3852.084),        // Redridge Mountains
        1436 to doubleArrayOf(-9400.0, -11733.333, 3016.6665, -483.3333),       // Westfall
    )
    private fun zonesAt(wx: Double, wy: Double) = rects.filter { (_, r) -> wx in r[1]..r[0] && wy in r[3]..r[2] }.keys
    private fun reaches(gx: Double, gy: Double): (Int, Double, Double) -> Boolean {
        val goal = zonesAt(gx, gy)
        return { _, wx, wy -> zonesAt(wx, wy).any { it in goal } }
    }

    // Goldshire; Stormwind's gate (both sides, learned on foot); the tram's Stormwind end (the measured table: 69.0, 31.0) into
    // Ironforge's centre; a learned way east into Redridge (the decoy toward the mountains)
    private val gx = -9060.0; private val gy = 440.0
    private val tram = doubleArrayOf(-7995.8301 - 0.31 * (-7995.8301 + 9154.1699), 1722.92 - 0.69 * (1722.92 + 14.584))
    private val hops = listOf(
        MultiHop.Hop(1429, gx, gy, 1453, gx, gy, 0.0, "To Stormwind City", "gate-in"),
        MultiHop.Hop(1453, gx + 5, gy, 1429, gx + 5, gy, 0.0, "To Elwynn Forest", "gate-out"),
        MultiHop.Hop(1453, tram[0], tram[1], 1455, -4833.0, -1108.9, CityExits.TRAM_YD, "Deeprun Tram to Ironforge", "tram"),
        MultiHop.Hop(1429, -9300.0, -1900.0, 1433, -9300.0, -1900.0, 0.0, "To Redridge Mountains", "redridge"),
    )

    @Test fun elwynnToIronforgeGoesByTheGateAndTheTram() {
        val ifQuest = doubleArrayOf(-4900.0, -1000.0)       // inside Ironforge
        val p = MultiHop.plan(1429, -9460.0, 60.0, ifQuest[0], ifQuest[1], hops, reaches(ifQuest[0], ifQuest[1]))!!
        assertEquals(listOf("gate-in", "tram"), p.hops.map { it.key })
        assertEquals("To Stormwind City, then Deeprun Tram to Ironforge", p.describe())
    }

    @Test fun aDunMorohQuestPastIronforgeStillTakesTheTram() {
        // Kharanos, in Dun Morogh's rectangle (the tram's far end in Ironforge lies in it too)
        val q = doubleArrayOf(-5600.0, -480.0)
        val p = MultiHop.plan(1429, -9460.0, 60.0, q[0], q[1], hops, reaches(q[0], q[1]))!!
        assertEquals("tram", p.hops.last().key)
    }

    @Test fun noKnownChainIsNoPlanNotAWalkOverTheMountains() {
        val q = doubleArrayOf(-4900.0, -1000.0)
        assertNull(MultiHop.plan(1429, -9460.0, 60.0, q[0], q[1], hops.filter { it.key != "tram" }, reaches(q[0], q[1])))
    }

    @Test fun aQuestAWalkAwayNeedsNoChain() {
        // Westfall's rectangle holds Goldshire's west too: a walk, the one-map choice stands
        val q = doubleArrayOf(-10500.0, 1000.0)
        val p = MultiHop.plan(1429, -9460.0, 60.0, q[0], q[1], hops, reaches(q[0], q[1]))!!
        assertTrue(p.hops.isEmpty())
    }

    @Test fun theHopCapHolds() {
        val q = doubleArrayOf(-4900.0, -1000.0)
        assertNull(MultiHop.plan(1429, -9460.0, 60.0, q[0], q[1], hops, reaches(q[0], q[1]), maxHops = 1))
        assertNotNull(MultiHop.plan(1429, -9460.0, 60.0, q[0], q[1], hops, reaches(q[0], q[1]), maxHops = 2))
    }

    @Test fun theEdgeSlidesToAPassAndNeverOverAMountain() {
        // the north border is a mountain except a pass at x 0.78..0.82 (1000 x 800 yd map)
        val pass: (Double, Double) -> Boolean = { x, y -> y > 0.2 || x in 0.78..0.82 }
        val p = EdgeWalk.slide(0.5, 0.0, 1000.0, 800.0, 600.0, pass)!!
        assertTrue(p[0] in 0.78..0.82); assertEquals(0.0, p[1], 1e-9); assertEquals(300.0, p[2], 1e-9)
        assertNull(EdgeWalk.slide(0.5, 0.0, 1000.0, 800.0, 600.0) { _, y -> y > 0.2 })
        assertEquals(0.0, EdgeWalk.slide(0.5, 0.0, 1000.0, 800.0, 600.0) { _, _ -> true }!![2], 1e-9)
        // a west border runs north - south
        val w = EdgeWalk.slide(0.0, 0.5, 1000.0, 800.0, 600.0) { x, y -> x > 0.2 || y > 0.6 }!!
        assertEquals(0.0, w[0], 1e-9); assertTrue(w[1] > 0.6)
    }
}
