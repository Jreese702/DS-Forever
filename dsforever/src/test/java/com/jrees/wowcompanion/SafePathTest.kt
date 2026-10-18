package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** b-safepath-net (a): the draft from made-up grids - a road's centre line, its exit at the border, a spur to a flight master */
class SafePathTest {
    // a zone 2000 x 1000 yd: world x 1000 (top) .. 0, world y 1000 (left) .. -1000; its west half (y > 0) zone 1, the east half zone 2
    private fun input(fixed: List<SafePath.Fixed> = emptyList(), road: (Double, Double) -> Boolean) = SafePath.Input(1, 0, 1000.0, 0.0, 1000.0, -1000.0,
        road, { _, _ -> 1f }, { _, wy -> if (wy > 0) 1 else 2 }, fixed, { if (it == 2) "Eastland" else "" })

    /** a 75 yd wide road along world x 500, from the west edge into the east half */
    private val eastRoad = { wx: Double, wy: Double -> abs(wx - 500) < 38 && wy < 900 }

    @Test fun aRoadCrossingTheBorderIsAnExit() {
        val net = SafePath.draft(input(road = eastRoad))
        assertEquals(1, net.exits.size)
        val x = net.exits[0]
        assertEquals(2, x.toMap); assertEquals("Eastland", x.name); assertEquals("walk", x.how)
        // at the border (world y 0), on the road's line
        val n = net.nodes.first { it.id == x.node }
        assertTrue("exit at y ${n.wy}", abs(n.wy) < 30); assertTrue("exit at x ${n.wx}", abs(n.wx - 500) < 30)
        // the road on this zone's ground only, one straight line: its points within the simplifying tolerance of x 500
        assertTrue(net.edges.all { it.kind == SafePath.ROAD })
        assertTrue(net.nodes.all { it.wy > -30 })
        assertTrue(net.nodes.all { abs(it.wx - 500) < 40 })
    }

    @Test fun aFlightMasterJoinsByASpur() {
        val fm = SafePath.Fixed(800.0, 600.0, SafePath.FLIGHT, "Westwatch")
        val net = SafePath.draft(input(listOf(fm), eastRoad))
        val f = net.nodes.single { it.kind == SafePath.FLIGHT }
        assertEquals("Westwatch", f.name)
        assertTrue(net.edges.any { it.kind == SafePath.SPUR && (it.a == f.id || it.b == f.id) })
        // a point on the other zone's ground is not this zone's
        val far = SafePath.draft(input(listOf(SafePath.Fixed(800.0, -600.0, SafePath.FLIGHT, "Eastwatch")), eastRoad))
        assertTrue(far.nodes.none { it.kind == SafePath.FLIGHT })
    }

    @Test fun aRoadEndingShortOfTheBorderGoesOnWhereTheNeighboursRoadDoes() {
        // the road stops 100 yd short of the border (a bridge), the neighbour's road goes on 100 yd past it
        val gap = { wx: Double, wy: Double -> abs(wx - 500) < 38 && wy < 900 && (wy > 100 || wy < -100) }
        val net = SafePath.draft(input(road = gap))
        assertEquals(1, net.exits.size)
        // with no road beyond, no exit: the road just ends
        val none = SafePath.draft(input(road = { wx, wy -> abs(wx - 500) < 38 && wy < 900 && wy > 100 }))
        assertEquals(0, none.exits.size)
    }

    @Test fun theDraftRoundTrips() {
        val net = SafePath.draft(input(listOf(SafePath.Fixed(800.0, 600.0, SafePath.FLIGHT, "Westwatch")), eastRoad), "stamp")
        val back = SafePath.Net.fromJson(JSONObject(net.toJson().toString()))!!
        assertEquals(net.nodes.size, back.nodes.size); assertEquals(net.edges.size, back.edges.size); assertEquals(net.exits.size, back.exits.size)
        assertEquals("stamp", back.built)
        assertEquals("Westwatch", back.nodes.single { it.kind == SafePath.FLIGHT }.name)
        val a = net.nodes[0]; val b = back.nodes[0]
        assertTrue(hypot(a.wx - b.wx, a.wy - b.wy) < 0.1)
    }

    @Test fun theTownsAreWhereTheSidesCreaturesGather() {
        val town = List(6) { SafePath.Npc(100.0 + it * 10, 200.0, if (it == 0) "Banker" else "Warrior Trainer") }
        val lone = listOf(SafePath.Npc(900.0, -900.0))
        val hubs = SafePath.hubs(town + lone, { _, _ -> "Razor Hill" })
        assertEquals(1, hubs.size); assertEquals("Razor Hill", hubs[0].name)
        assertEquals(125.0, hubs[0].wx, 1.0)
        // a capital's district: the ground says only the zone's name, so its creatures name it
        assertEquals("Bank", SafePath.hubs(town, { _, _ -> "Orgrimmar" }, "Orgrimmar")[0].name)
        assertEquals("Warrior trainers", SafePath.roleName(town.drop(1)))
    }
}
