package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-route-city-exits (test20 in Stormwind): the real ways out of a city, learned and from the approved table */
class CityExitsTest {
    private val ax = 1000.0; private val ay = 700.0
    private val gate = CityExits.Exit(1453, "Stormwind Gates", x = 0.715, y = 0.90)
    private val tram = CityExits.Exit(1453, "Deeprun Tram", x = 0.690, y = 0.310, toMap = 1455, rideYd = CityExits.TRAM_YD)
    // the tram's far end in Stormwind's coordinates: Ironforge is far north
    private val ways = listOf(CityExits.Way(gate, 0.715, 0.90, 0.715, 0.90), CityExits.Way(tram, 0.690, 0.310, 0.5, -3.0))

    @Test fun westfallLeavesByTheGateNotTheEdgeNorTheTram() {
        val w = CityExits.pick(0.5, 0.5, doubleArrayOf(-0.8, 2.2), ways, ax, ay)!!
        assertEquals("Stormwind Gates", w.exit.name)
    }

    @Test fun ironforgeTakesTheTram() {
        val w = CityExits.pick(0.5, 0.5, doubleArrayOf(0.55, -3.1), ways, ax, ay)!!
        assertEquals("Deeprun Tram", w.exit.name)
    }

    @Test fun theRouteUsesTheWayBeforeTheEdge() {
        val r = CursorRoute.plan(0.5, 0.5, ax, ay, emptyList(), doubleArrayOf(-0.8, 2.2), null, null, emptyList(),
            ways = ways, inCity = true, wayName = { it.name }) { _, _ -> false }!!
        assertEquals(CursorRoute.Kind.EXIT, r.kind); assertEquals("Stormwind Gates", r.label); assertEquals(0.715, r.x, 1e-9)
    }

    @Test fun aCityFlightOnlyWhenKnownAndConnected() {
        val nodes = listOf(CursorRoute.Node(0.66, 0.62, 2, "Stormwind"), CursorRoute.Node(-0.7, 2.1, 12, "Sentinel Hill"))
        val no = CursorRoute.plan(0.5, 0.5, ax, ay, emptyList(), doubleArrayOf(-0.8, 2.2), null, null, nodes, ways = ways, inCity = true,
            wayName = { it.name }) { _, _ -> false }!!
        assertEquals(CursorRoute.Kind.EXIT, no.kind)                       // not shown connected: never assumed
        val yes = CursorRoute.plan(0.5, 0.5, ax, ay, emptyList(), doubleArrayOf(-0.8, 2.2), null, null, nodes, ways = ways, inCity = true,
            wayName = { it.name }) { a, b -> setOf(a, b) == setOf(2, 12) }!!
        assertEquals(CursorRoute.Kind.FLIGHT, yes.kind); assertEquals("Flight to Stormwind", yes.label)
    }

    private class Mem : CityExits.Learner.Store {
        val m = HashMap<String, String>()
        override fun get(k: String) = m[k]
        override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v }
    }

    @Test fun walkingOutIsLearnedFlyingIsNot() {
        val l = CityExits.Learner(Mem())
        assertNull(l.onDoc(1453, 0, -8960.0, 520.0, false, 0L))
        val e = l.onDoc(1429, 0, -8975.0, 525.0, false, 1_000L)
        assertNotNull(e); assertEquals(1453, e!!.map); assertEquals(1429, e.toMap); assertTrue(!e.transport)
        assertEquals(1, l.of(1453).size)
        // a flight across the same border teaches nothing
        l.onDoc(1453, 0, -8000.0, 400.0, true, 2_000L)
        assertNull(l.onDoc(1429, 0, -8010.0, 410.0, true, 3_000L))
        // another continent (an instance, the tram): nothing comparable
        l.onDoc(1453, 0, -8960.0, 520.0, false, 4_000L)
        assertNull(l.onDoc(1455, 369, 10.0, 10.0, false, 30_000L))
        // the same crossing again replaces, not adds
        l.onDoc(1453, 0, -8961.0, 521.0, false, 40_000L); l.onDoc(1429, 0, -8975.0, 525.0, false, 41_000L)
        assertEquals(1, l.of(1453).size)
    }
}
