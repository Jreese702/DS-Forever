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

    /** b-safepath-review: the user's corrections - approve, remove, draw, join, an exit, their walk - each undone in turn */
    @Test fun theReviewCorrectsAndUndoes() {
        val net = SafePath.draft(input(listOf(SafePath.Fixed(800.0, 600.0, SafePath.FLIGHT, "Westwatch")), eastRoad))
        val e = SafePath.Edit(net)
        val drafts = e.edges.count { it.state == SafePath.DRAFT }
        assertTrue(drafts > 0)
        e.setState(0, SafePath.REMOVED)
        assertEquals(SafePath.REMOVED, e.edges[0].state); assertEquals("user", e.edges[0].src)
        e.approveAll()
        assertEquals(1, e.edges.count { it.state == SafePath.REMOVED })            // a removed way stays removed
        assertTrue(e.edges.none { it.state == SafePath.DRAFT })
        val f = e.nodes.single { it.kind == SafePath.FLIGHT }.id
        val p = e.draw(f, 900.0, 650.0)
        assertTrue(e.edges.any { it.a == f && it.b == p && it.state == SafePath.OK })
        e.exit(p, 7, "Northland")
        assertEquals("Northland", e.exits.single { it.node == p }.name)
        assertEquals(SafePath.EXIT, e.node(p)!!.kind)
        // the walk: its ends joined to the nodes near them
        val walk = listOf(doubleArrayOf(800.0, 600.0), doubleArrayOf(700.0, 600.0), doubleArrayOf(600.0, 650.0), doubleArrayOf(500.0, 700.0))
        assertTrue(e.addWalk(walk))
        assertTrue(e.edges.any { it.kind == SafePath.ROAD && it.src == "user" && (it.a == f || it.b == f) })
        // undone, step by step, back to the draft
        repeat(5) { assertTrue(e.undo()) }
        assertTrue(!e.undo())
        assertEquals(drafts, e.edges.count { it.state == SafePath.DRAFT })
        assertEquals(net.nodes.size, e.nodes.size); assertEquals(net.exits.size, e.exits.size)
    }

    @Test fun theLastWalkIsTheLatestRunOnTheMap() {
        val t0 = 10_000_000L
        SafePath.noteWalk(5, 0.0, 0.0, t0); SafePath.noteWalk(5, 20.0, 0.0, t0 + 1000)
        SafePath.noteWalk(5, 20.0, 3.0, t0 + 2000)                                   // under 8 yd: not kept
        SafePath.noteWalk(5, 400.0, 0.0, t0 + 200_000)                               // after a pause: a new walk
        SafePath.noteWalk(5, 420.0, 0.0, t0 + 201_000)
        SafePath.noteWalk(6, 0.0, 0.0, t0 + 202_000)                                 // another map
        val w = SafePath.lastWalk(5)
        assertEquals(2, w.size); assertEquals(400.0, w[0][0], 0.0); assertEquals(420.0, w[1][0], 0.0)
    }
}
