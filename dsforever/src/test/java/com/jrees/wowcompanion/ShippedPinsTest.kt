package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game pin on the shipped route (ShippedPins; MapView.routePin): the checkpoints are the route's bends and the named checkpoints it
 * passes, the passed ones dropped for good, the 30 yd replan's 1.10 hysteresis, and the pin's log words ("(shipped network)", the
 * "may be out of date" mark)
 */
class ShippedPinsTest {
    private fun p(x: Double, y: Double) = doubleArrayOf(x, y)
    // world (x north, y west) shown on a 1000 x 1000 yd map with the map's x = world y / 1000, y = world x / 1000
    private val onMap: (DoubleArray) -> DoubleArray = { w -> doubleArrayOf(w[1] / 1000.0, w[0] / 1000.0) }

    @Test fun aNamedCheckpointOnAStraightStretchIsPutInAndABendTakesItsName() {
        val route = listOf(p(0.0, 0.0), p(0.0, 400.0), p(300.0, 400.0))
        val named = listOf("Razor Hill" to p(3.0, 200.0), "the crossroads" to p(2.0, 402.0), "far away" to p(150.0, 100.0))
        val leg = ShippedPins.checkpoints(route, named)
        assertEquals(4, leg.pts.size)
        assertEquals(listOf(null, "Razor Hill", "the crossroads", null), leg.names)
        assertEquals(200.0, leg.pts[1][1], 1e-6); assertEquals(0.0, leg.pts[1][0], 1e-6)   // on the stretch, not off it
        assertEquals("\"Razor Hill\"", leg.nextWords())
        // no names: the bends alone
        val bare = ShippedPins.checkpoints(route, emptyList())
        assertEquals("bend 1 of 1", bare.nextWords())
        // the goal's own spot is never named
        assertNull(ShippedPins.checkpoints(route, listOf("town" to p(300.0, 401.0))).names.last())
    }

    @Test fun thePassedCheckpointsAreDroppedForGood() {
        val leg = ShippedPins.checkpoints(listOf(p(0.0, 0.0), p(0.0, 200.0), p(200.0, 200.0), p(200.0, 600.0)), listOf("A" to p(0.0, 100.0)))
        assertEquals(listOf(null, "A", null, null, null), leg.names)
        // at the start: nothing passed, the same leg
        assertSame(leg, ShippedPins.dropPassed(leg, p(0.0, 5.0), onMap, 1000.0, 1000.0))
        // past "A" (within 25 yd of it): it is dropped, the bend at (0, 200) leads
        val past = ShippedPins.dropPassed(leg, p(0.0, 110.0), onMap, 1000.0, 1000.0)
        assertEquals(4, past.pts.size); assertEquals(200.0, past.pts[1][1], 1e-6); assertNull(past.names[1])
        assertEquals("bend 1 of 2", past.nextWords())
        // the first point stays the plan's start
        assertEquals(0.0, past.pts[0][1], 1e-6)
        assertEquals(90.0 + 200.0 + 400.0, ShippedPins.restYd(past, p(0.0, 110.0)), 1e-6)
    }

    @Test fun theReplanTakesANewWayOnlyWhenTenPercentShorter() {
        assertFalse(ShippedPins.takeNew(500.0, 480.0, strayed = false))
        assertTrue(ShippedPins.takeNew(500.0, 450.0, strayed = false))
        assertTrue("strayed: the new way always", ShippedPins.takeNew(500.0, 499.0, strayed = true))
        assertTrue("nothing kept", ShippedPins.takeNew(null, 900.0, strayed = false))
        assertEquals(30.0, ShippedPins.REPLAN_YD, 0.0); assertEquals(1.10, SafePathV2.HYSTERESIS, 0.0)
    }

    @Test fun thePinsWordsSayTheShippedNetworkAndTheStaleMark() {
        val leg = ShippedPins.checkpoints(listOf(p(0.0, 0.0), p(0.0, 400.0), p(300.0, 400.0)), listOf("Razor Hill" to p(0.0, 200.0)))
        assertEquals("checkpoint: \"Razor Hill\" on the way to the Razormane camp (shipped network)", ShippedPins.pinWhy(leg, "the Razormane camp", null))
        assertEquals("checkpoint: \"Razor Hill\" on the way to q (shipped network); may be out of date (exported for build a, game is b)",
            ShippedPins.pinWhy(leg, "q", "exported for build a, game is b"))
    }

    @Test fun aSectionsNamedCheckpointsAreItsListedNamedNodes() {
        val nodes = listOf(SafePath.Node(0, 0.0, 0.0, SafePath.POINT), SafePath.Node(1, 0.0, 100.0, SafePath.POINT, name = "Town"),
            SafePath.Node(2, 0.0, 200.0, SafePath.POINT, name = "Not listed"))
        val net = SafePath.Net(1411, 1, "off-device", nodes, listOf(SafePath.Edge(0, 1, SafePath.ROAD, SafePath.OK, "auto", 0f, 1f),
            SafePath.Edge(1, 2, SafePath.ROAD, SafePath.OK, "auto", 0f, 1f)), emptyList())
        val ways = listOf(ShippedNet.Way(ShippedNet.F_ROAD, 100.0), ShippedNet.Way(ShippedNet.F_ROAD, 100.0))
        assertEquals(listOf("Town"), ShippedPins.named(ShippedNet.Section(TransportRoutes.HORDE, 10, "t", net, ways, checkpoints = listOf(1))).map { it.first })
        assertEquals(listOf("Town", "Not listed"), ShippedPins.named(ShippedNet.Section(TransportRoutes.HORDE, 10, "t", net, ways)).map { it.first })
    }
}
