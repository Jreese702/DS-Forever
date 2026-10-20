package com.jrees.wowcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE GHOST'S CORPSE RUN (CorpseRoute; the user: "when im dead I want the absolute quickest path back to my body to show"): the
 * corpse leg ignores danger (shorter through a camp than the living route round it), keeps the ground's walls, takes no transport,
 * leads the pin / arrow to its next checkpoint while a ghost and is dropped on resurrect
 */
class CorpseRouteTest {
    private val H = TransportRoutes.HORDE
    private val flat: (Double, Double) -> Float = { _, _ -> 1f }

    private fun node(id: Int, x: Double, y: Double) = SafePath.Node(id, x, y, SafePath.POINT)
    private fun road(a: Int, b: Int) = SafePath.Edge(a, b, SafePath.ROAD, SafePath.OK, "auto", 0f, 1f)
    private fun section(nodes: List<SafePath.Node>, edges: List<SafePath.Edge>, links: List<ShippedNet.Link> = emptyList()) =
        ShippedNet.section(SafePath.Net(1411, 1, "off-device", nodes, edges, emptyList()), H, 10, "test", links)

    /** the short road x 0 (y 0 .. 1000) through a camp, and a road round it out to world x 300 and back */
    private fun twoWays() = section(
        listOf(node(0, 0.0, 0.0), node(1, 0.0, 1000.0), node(2, 300.0, 0.0), node(3, 300.0, 1000.0)),
        listOf(road(0, 1), road(0, 2), road(2, 3), road(3, 1)))
    private val camp: (Double, Double) -> Float = { x, y -> if (x in -50.0..50.0 && y in 300.0..700.0) 4f else 0f }
    private val from = doubleArrayOf(0.0, -100.0); private val to = doubleArrayOf(0.0, 1100.0)

    // ---------------------------------------------------------------- the shipped network

    @Test fun theGhostLegIgnoresDangerAndTakesTheShorterWayThroughTheCamp() {
        val living = ShippedRoute.plan(twoWays(), flat, camp, from, to)
        assertTrue(living.why, living.reached)
        assertTrue("the living route goes round the camp", living.pts.any { it[0] > 250.0 })
        val ghost = CorpseRoute.plan(twoWays(), flat, from, to)
        assertTrue(ghost.why, ghost.reached)
        assertTrue("the ghost walks straight through it: ${ghost.pts.map { it.toList() }}", ghost.pts.all { it[0] < 100.0 })
        assertTrue("shorter: ${ghost.yd} < ${living.yd}", ghost.yd < living.yd)
        assertFalse("no detour cap", ghost.capped)
        assertEquals(0.0, ghost.danger, 1e-9)
    }

    @Test fun theGhostLegKeepsTheWalls() {
        // a wall between the corpse-runner and the road: world x 70 .. 80, y -60 .. 110 (a cliff: the attach walk goes round it)
        fun wall(x: Double, y: Double) = x in 70.0..80.0 && y in -60.0..110.0
        val ground: (Double, Double) -> Float = { x, y -> if (wall(x, y)) Terrain.WALL else 1f }
        val road = section(listOf(node(0, 0.0, 0.0), node(1, 0.0, 1000.0)), listOf(road(0, 1)))
        val r = CorpseRoute.plan(road, ground, doubleArrayOf(120.0, 50.0), doubleArrayOf(-150.0, 900.0))
        assertTrue(r.why, r.reached)
        for ((a, b) in r.pts.zipWithNext()) for (q in 0..200) {
            val x = a[0] + (b[0] - a[0]) * q / 200; val y = a[1] + (b[1] - a[1]) * q / 200
            assertFalse("crosses the wall at ($x, $y)", wall(x, y))
        }
    }

    @Test fun theGhostLegTakesNoTransport() {
        // a road to (0, 600); a one-way portal from there to (300, 900) on a second road; the walk round by (1200, 600) the other way
        val sec = section(
            listOf(node(0, 0.0, 0.0), node(1, 0.0, 600.0), node(2, 300.0, 900.0), node(3, 300.0, 1600.0), node(4, 1200.0, 600.0)),
            listOf(road(0, 1), road(2, 3), road(1, 4), road(4, 2)),
            listOf(ShippedNet.Link("portal", "", true, 15.0, listOf(doubleArrayOf(0.0, 600.0), doubleArrayOf(300.0, 900.0)))))
        val goal = doubleArrayOf(300.0, 1640.0); val start = doubleArrayOf(0.0, -40.0)
        val living = ShippedRoute.plan(sec, flat, null, start, goal)
        assertTrue(living.why, living.reached)
        assertTrue("the living route takes the portal", living.pts.none { it[0] > 1000.0 })
        val ghost = CorpseRoute.plan(sec, flat, start, goal)
        assertTrue(ghost.why, ghost.reached)
        assertTrue("the ghost walks round: ${ghost.pts.map { it.toList() }}", ghost.pts.any { it[0] > 1000.0 })
        // the links a ghost walks: stairs, decks, doors, swims - never a portal, an updraft or any one-way link
        fun link(kind: String, oneWay: Boolean = false) = ShippedNet.Link(kind, "", oneWay, 10.0, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0)))
        assertTrue(CorpseRoute.walkable(link("stairs"))); assertTrue(CorpseRoute.walkable(link("deck"))); assertTrue(CorpseRoute.walkable(link("swim")))
        assertFalse(CorpseRoute.walkable(link("portal", true))); assertFalse(CorpseRoute.walkable(link("updraft", true)))
        assertFalse(CorpseRoute.walkable(link("portal"))); assertFalse(CorpseRoute.walkable(link("boat"))); assertFalse(CorpseRoute.walkable(link("stairs", true)))
    }

    // ---------------------------------------------------------------- the danger grid (no network)

    /** a 1000 x 1000 yd map of 25 yd cells */
    private fun grid(danger: (Int, Int) -> Float, wall: (Int, Int) -> Boolean = { _, _ -> false }): DangerGrid {
        val n = 40
        val d = FloatArray(n * n) { danger(it % n, it / n) }
        val t = FloatArray(n * n) { if (wall(it % n, it / n)) Terrain.WALL else 1f }
        return DangerGrid(n, n, d, 1000.0, 1000.0, t)
    }

    @Test fun theGridGhostLegIgnoresDanger() {
        // a camp across the straight line's middle
        val g = grid({ i, j -> if (i in 17..23 && j in 15..25) 4f else 0f })
        val a = doubleArrayOf(0.2, 0.5); val b = doubleArrayOf(0.8, 0.5)
        val living = g.route(a, b, DangerGrid.strength("high"))
        assertTrue("the living route bends round the camp", living.size > 2)
        val ghost = CorpseRoute.gridLeg(g, a, b)
        assertEquals("the ghost goes straight through", 2, ghost.size)
        assertTrue(DangerGrid.yards(ghost, 1000.0, 1000.0) < DangerGrid.yards(living, 1000.0, 1000.0))
    }

    @Test fun theGridGhostLegKeepsTheWalls() {
        // a cliff along x 500 .. 525 yd from y 150 yd down: the only way past is round its top end
        val wallAt = { i: Int, j: Int -> i == 20 && j >= 6 }
        val g = grid({ _, _ -> 0f }, wallAt)
        val a = doubleArrayOf(0.3, 0.8); val b = doubleArrayOf(0.7, 0.8)
        assertEquals("k 0 without groundOnly is the old straight line", 2, g.route(a, b, 0.0).size)
        val ghost = CorpseRoute.gridLeg(g, a, b)
        assertTrue("bent round the cliff: ${ghost.map { it.toList() }}", ghost.size > 2)
        for ((p, q) in ghost.zipWithNext()) for (s in 0..400) {
            val x = p[0] + (q[0] - p[0]) * s / 400; val y = p[1] + (q[1] - p[1]) * s / 400
            // inside the cliff (a line grazing its corner, 1 yd or less, is not through it)
            assertFalse("through the cliff at ($x, $y)", x * 1000 in 501.0..524.0 && y * 1000 > 151.0)
        }
    }

    // ---------------------------------------------------------------- the run: pin, arrow, resurrect

    @Test fun whileAGhostThePinAndArrowGoToTheCorpseLegsNextCheckpoint() {
        val run = CorpseRoute.Run()
        assertFalse(run.see(true, 0.6, 0.4))
        assertArrayEquals("no leg yet: straight at the corpse", doubleArrayOf(0.6, 0.4), run.next(0.6, 0.4), 1e-12)
        val cp = doubleArrayOf(0.5, 0.2)
        run.leg = listOf(doubleArrayOf(0.4, 0.4), cp, doubleArrayOf(0.6, 0.4))
        assertTrue(run.bent)
        assertArrayEquals("the leg's next checkpoint", cp, run.next(0.6, 0.4), 1e-12)
        // a corpse off this map (another map, another continent): no leg - the straight arrow, as today
        assertFalse(CorpseRoute.wanted(true, 1.4, 0.4)); assertFalse(CorpseRoute.wanted(true, Double.NaN, Double.NaN))
        assertFalse(CorpseRoute.wanted(false, 0.6, 0.4)); assertTrue(CorpseRoute.wanted(true, 0.6, 0.4))
    }

    @Test fun theLegIsDroppedTheMomentThePlayerIsAliveAgain() {
        val run = CorpseRoute.Run()
        run.see(true, 0.6, 0.4)
        run.leg = listOf(doubleArrayOf(0.4, 0.4), doubleArrayOf(0.5, 0.2), doubleArrayOf(0.6, 0.4))
        assertTrue("resurrected: drop", run.see(false, Double.NaN, Double.NaN))
        assertNull(run.leg); assertFalse(run.bent); assertFalse(run.ghost)
        assertFalse("dropped once, not on every document after", run.see(false, Double.NaN, Double.NaN))
        // a plan landing after the resurrect is never kept
        run.leg = listOf(doubleArrayOf(0.4, 0.4), doubleArrayOf(0.5, 0.2), doubleArrayOf(0.6, 0.4))
        assertNull(run.leg)
        // the corpse leaving this map drops it too
        run.see(true, 0.6, 0.4); run.leg = listOf(doubleArrayOf(0.4, 0.4), doubleArrayOf(0.6, 0.4))
        assertTrue(run.see(true, 1.5, 0.4)); assertNull(run.leg)
    }

    @Test fun thePlanLineSaysDangerOff() {
        assertEquals("safepath ghost: corpse on map 1411: 412 yd (danger off)", CorpseRoute.said(1411, 412.7))
        assertEquals("safepath ghost: corpse on map 1411: 412 yd (danger off; shipped network, 3 bends)", CorpseRoute.said(1411, 412.7, "shipped network, 3 bends"))
    }
}
