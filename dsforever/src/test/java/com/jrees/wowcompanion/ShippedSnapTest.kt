package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * THE ATTACH SNAP (ShippedSnap; DEVICE_INTEGRATION_PLAN.md SHIPPED NETWORK step 3, the parity's walls and deep water on the attach
 * legs): the nearest node within 60 yd over the ground read fine - never through a building's room, a cliff, a steep bank, deep water
 * or lava (a narrow stream waded), the straight clear snap preferred, an end in a building out by its door, and the long attach only
 * with no node near (its why said)
 */
class ShippedSnapTest {
    private val flat: (Double, Double) -> Float = { _, _ -> 1f }
    private val p0 = doubleArrayOf(0.0, 0.0)
    /** the snaps that lead (not the far fallback) */
    private fun lead(r: ShippedSnap.Result) = r.snaps.filter { it.cost < ShippedSnap.FAR_COST }.map { it.node }

    /** water over world x -100..100, y -100..100 by [depth] (NaN dry) */
    private fun water(depth: (Double, Double) -> Float) = SafePath.Water.build(100.0, -100.0, 100.0, -100.0) { x, y -> depth(x, y) }
    private fun room(x0: Double, x1: Double, y0: Double, y1: Double): (Double, Double) -> Boolean = { x, y -> x in x0..x1 && y in y0..y1 }
    /** every point of [pts]'s lines, read every half yard, clear of [ground]'s walls except within [skip] yd of the first */
    private fun clear(pts: List<DoubleArray>, ground: ShippedSnap.Ground, skip: Double = 0.0): Boolean {
        for ((a, b) in pts.zipWithNext()) {
            val n = (hypot(b[0] - a[0], b[1] - a[1]) * 2).toInt().coerceAtLeast(1)
            for (i in 0..n) { val t = i.toDouble() / n; val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
                if (hypot(x - pts[0][0], y - pts[0][1]) > skip && hypot(x - pts.last()[0], y - pts.last()[1]) > ShippedSnap.END_YD &&
                    ground.wall(x, y) != null) return false }
        }
        return true
    }

    @Test fun theNearestNodeStraightAndClearIsTaken() {
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(30.0, 0.0), 2 to doubleArrayOf(0.0, 50.0), 3 to doubleArrayOf(70.0, 0.0)), ShippedSnap.Ground(flat))
        assertTrue(r.ok)
        assertEquals(1, r.best!!.node)
        assertTrue(r.best!!.straight)
        assertEquals(2, r.best!!.pts.size)
        assertEquals(30.0, r.best!!.yd, 1e-6)
        assertFalse("70 yd is past the 60 yd snap", r.snaps.any { it.node == 3 })
        assertEquals(listOf(1), lead(r))
    }

    @Test fun onTheShippedLatticeAFaceTheCoarseGroundMissesIsWalkedRound() {
        // a 20 yd ridge across world x 9..14 (north of the end), level ground both sides, a gap at y 30..40; the coarse ground reads it
        // all level
        val org = 32.0 * MapHarvest.ADT_YD
        val lat = Climb.build(100.0, -100.0, 100.0, -100.0, { gr, gc ->
            val wx = org - gr * Climb.STEP; val wy = org - gc * Climb.STEP
            if (wx in 9.0..14.0 && wy !in 30.0..40.0) 20f else 0f })
        val nodes = listOf(1 to doubleArrayOf(25.0, 0.0))
        // the coarse ground alone: straight over the cliff
        assertTrue(ShippedSnap.snap(p0, nodes, ShippedSnap.Ground(flat)).best!!.straight)
        // the lattice: round by the gap, every piece walkable on it
        val r = ShippedSnap.snap(p0, nodes, ShippedSnap.Ground(flat), reach = 60.0, walkYd = 120.0, lattice = lat)
        assertTrue(r.why, r.ok); val b = r.best!!
        assertFalse(b.straight); assertEquals(1, b.node)
        for ((u, v) in b.pts.zipWithNext()) assertTrue("${u.toList()} -> ${v.toList()}", Climb.walkable(lat, u, v))
        assertTrue("round by the gap: ${b.pts.map { it.toList() }}", b.pts.any { it[1] in 28.0..42.0 })
        // the near-goal walk on it: the same
        val d = ShippedSnap.directWalk(p0, doubleArrayOf(25.0, 0.0), ShippedSnap.Ground(flat), lattice = lat)!!
        assertFalse(d.straight); for ((u, v) in d.pts.zipWithNext()) assertTrue(Climb.walkable(lat, u, v))
    }

    @Test fun anEndTheLatticeCannotWalkFromGetsAnUncheckedPointer() {
        // the end in a pit: a 20 yd ring wall round it (radius 12..18 yd), no gap; nodes outside
        val org = 32.0 * MapHarvest.ADT_YD
        val lat = Climb.build(150.0, -150.0, 150.0, -150.0, { gr, gc ->
            val d = hypot(org - gr * Climb.STEP, org - gc * Climb.STEP); if (d in 12.0..18.0) 20f else 0f })
        val nodes = listOf(1 to doubleArrayOf(40.0, 0.0), 2 to doubleArrayOf(0.0, -70.0))
        val g = ShippedSnap.Ground(flat)
        val at = ShippedSnap.attacher(g).attach(ShippedRoute.AttachView(nodes, emptyList(), flat, null, 0.0, 200.0, lattice = lat), p0)
        assertTrue(at.why, at.unchecked); assertFalse(at.snapped)
        assertEquals(1, at.joins.size); assertEquals(1, at.joins[0].node)          // the nearest node
        assertEquals(2, at.joins[0].pts.size)                                       // a plain straight pointer
        assertTrue(at.why!!, at.why!!.startsWith("unchecked"))
        // a lattice it can walk from: checked as ever
        val open = Climb.build(150.0, -150.0, 150.0, -150.0, { _, _ -> 0f })
        assertFalse(ShippedSnap.attacher(g).attach(ShippedRoute.AttachView(nodes, emptyList(), flat, null, 0.0, 200.0, lattice = open), p0).unchecked)
    }

    @Test fun aThinWallTheCoarseGridMissesIsNeverCrossed() {
        // a building's wall 2 yd thick across the way to the nearer node (the 25 yd grid reads only cell middles: never sees it)
        val g = ShippedSnap.Ground(flat, room = room(14.0, 16.0, -30.0, 30.0))
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(30.0, 0.0), 2 to doubleArrayOf(-45.0, 0.0)), g)
        assertTrue(r.ok)
        assertEquals("the clear straight node, not the one behind the wall", 2, r.best!!.node)
        assertTrue(r.best!!.straight)
        // the node behind the wall: reached only round its end, never through it
        r.snaps.firstOrNull { it.node == 1 }?.let { s -> assertFalse(s.straight); assertTrue(clear(s.pts, g)); assertTrue(s.yd > 30.0) }
        assertNotNull(ShippedSnap.lineWall(p0, doubleArrayOf(30.0, 0.0), g))
    }

    @Test fun deepWaterIsNeverSnappedThroughButANarrowStreamIsWaded() {
        // a river 10 yd wide, 4 yd deep, across x = 10..20 for all y: no snap over it, none round it within the walk
        val river = ShippedSnap.Ground(flat, water { x, _ -> if (x in 10.0..20.0) 4f else Float.NaN })
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(40.0, 0.0)), river)
        assertFalse(r.ok)
        assertTrue(r.why, r.why.contains("deep water"))
        // a stream 4 yd across and 2 yd deep (the narrow-stream rule: 2.2 yd, 6 yd): waded straight
        val stream = ShippedSnap.Ground(flat, water { x, _ -> if (x in 14.0..18.0) 2f else Float.NaN })
        val s = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(40.0, 0.0)), stream)
        assertTrue(s.why, s.ok); assertTrue(s.best!!.straight)
        assertTrue("a wade costs more than dry ground", s.best!!.cost > 40.0)
        // as narrow but 5 yd deep: deeper than the narrow stream's 2.2 - refused
        val deepNarrow = ShippedSnap.Ground(flat, water { x, _ -> if (x in 14.0..18.0) 5f else Float.NaN })
        assertFalse(ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(40.0, 0.0)), deepNarrow).ok)
        // lava (WTR2 keeps it deeper than any wade, 99): refused
        val lava = ShippedSnap.Ground(flat, water { x, _ -> if (x in 14.0..18.0) 99f else Float.NaN })
        val l = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(40.0, 0.0)), lava)
        assertFalse(l.ok); assertTrue(l.why, l.why.contains("deep water"))
        assertTrue("its middle said as lava", (13..19).any { lava.wall(it.toDouble(), 0.0) == "deep water or lava" })
    }

    @Test fun aPondIsWalkedRoundAndTheStraightClearNodeStillPreferred() {
        // a pond 10 yd across, 5 deep, in the straight way to node 1 (30 yd): node 1 only round it; node 2 (31 yd) straight and clear
        val pond = ShippedSnap.Ground(flat, water { x, y -> if (hypot(x - 15.0, y) <= 5.0) 5f else Float.NaN })
        val one = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(30.0, 0.0)), pond)
        assertTrue(one.why, one.ok)
        assertFalse(one.best!!.straight)
        assertTrue(clear(one.best!!.pts, pond))
        val both = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(30.0, 0.0), 2 to doubleArrayOf(0.0, 31.0)), pond)
        assertEquals("the straight clear snap wins over a nearer bent one", 2, both.best!!.node)
    }

    @Test fun aCliffOrASteepBankIsAWall() {
        val cliff: (Double, Double) -> Float = { x, _ -> if (x in 10.0..20.0) Terrain.WALL else 1f }
        assertFalse(ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(40.0, 0.0)), ShippedSnap.Ground(cliff)).ok)
        val bank: (Double, Double) -> Float = { x, _ -> if (x in 10.0..20.0) 12f else 1f }
        assertEquals("a steep bank", ShippedSnap.Ground(bank).wall(15.0, 0.0))
        // a climb (3x) is no wall, only dearer
        val climb: (Double, Double) -> Float = { x, _ -> if (x in 10.0..20.0) 3f else 1f }
        val c = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(40.0, 0.0)), ShippedSnap.Ground(climb))
        assertTrue(c.ok); assertEquals(60.0, c.best!!.cost, 1.0)
    }

    @Test fun anEndInsideABuildingLeavesByItsDoor() {
        // the player in an inn 10 x 10 yd: the walls within 15 yd of them passed, the node outside reached
        val g = ShippedSnap.Ground(flat, room = room(-5.0, 5.0, -5.0, 5.0))
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(30.0, 0.0)), g)
        assertTrue(r.why, r.ok)
        // but another building farther off is still a wall
        val g2 = ShippedSnap.Ground(flat, room = { x, y -> (x in -5.0..5.0 && y in -5.0..5.0) || (x in 18.0..22.0 && y in -60.0..60.0) })
        val r2 = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(30.0, 0.0)), g2)
        assertFalse(r2.ok)
    }

    @Test fun aSnapFarPastTheNearestIsDropped() {
        // the network carries the way on, not a long walk over the grass past its nearest node
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(10.0, 0.0), 2 to doubleArrayOf(0.0, 50.0), 3 to doubleArrayOf(-30.0, 0.0)), ShippedSnap.Ground(flat))
        assertEquals("only the nearest clear line (and a tie) leads", listOf(1), lead(r))
        assertEquals("the rest only as the far fallback", listOf(1, 3, 2), r.snaps.map { it.node })
        val two = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(10.0, 0.0), 3 to doubleArrayOf(-10.3, 0.0)), ShippedSnap.Ground(flat))
        assertEquals(setOf(1, 3), lead(two).toSet())
        // the nearest walled off: the nearest CLEAR line leads (no farther one tried past it + 5 yd)
        val g = ShippedSnap.Ground(flat, room = room(4.0, 6.0, -3.0, 3.0))
        val w = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(10.0, 0.0), 2 to doubleArrayOf(0.0, 30.0), 3 to doubleArrayOf(0.0, -50.0)), g)
        assertEquals(listOf(2), lead(w))
        assertTrue(w.best!!.straight)
    }

    @Test fun anEndOnAFloorSnapsToItsFloorFirst() {
        // the start over a cave's pocket: a floor node 13 yd off (R_FLOOR), a ground node 8 yd off over the steep bank under the floor
        val bank: (Double, Double) -> Float = { x, _ -> if (x in -20.0..20.0) 24f else 1f }
        val g = ShippedSnap.Ground(bank)
        val rm = ShippedRoute.RM_BASE
        val nodes = listOf(rm + 1 to doubleArrayOf(0.0, 13.0), rm + 2 to doubleArrayOf(8.0, 0.0), rm + 3 to doubleArrayOf(0.0, 3.0))
        val r = ShippedSnap.snap(p0, nodes, g, floor = setOf(rm + 1, rm + 3))
        assertTrue(r.why, r.ok); assertTrue(r.why, r.why.startsWith("on a floor"))
        assertTrue(lead(r).all { it == rm + 1 || it == rm + 3 })
        assertTrue("the ground node over the steep bank: no fallback either", r.snaps.none { it.node == rm + 2 })
        // the same end not on a floor (its nearest roadmap node a ground one): the ground's rules
        val off = ShippedSnap.snap(p0, nodes, g, floor = setOf(rm + 1))
        assertFalse(off.why, off.why.startsWith("on a floor"))
        // a floor over deep water is still no snap through the water
        val wet = ShippedSnap.Ground(flat, water { x, y -> if (x in -10.0..10.0 && y in 4.0..10.0) 5f else Float.NaN })
        val w = ShippedSnap.snap(p0, listOf(rm + 1 to doubleArrayOf(0.0, 13.0), rm + 3 to doubleArrayOf(0.0, 2.0)), wet, floor = setOf(rm + 1, rm + 3))
        assertEquals(listOf(rm + 3), lead(w))
    }

    @Test fun theFallbacksAreRankedLevelFirstAndNoneOverSteepGround() {
        // node 1 the lead (10 yd); the rest only as fallbacks: 3 (40 yd, level) before 2 (30 yd, over a climb); 4 over a rock field: none
        val ground: (Double, Double) -> Float = { x, y -> if (y > 5.0 && x in -5.0..5.0) 3f else if (y < -5.0 && x in -5.0..5.0) 6f else 1f }
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(10.0, 0.0), 2 to doubleArrayOf(0.0, 30.0), 3 to doubleArrayOf(-40.0, 0.0),
            4 to doubleArrayOf(0.0, -35.0)), ShippedSnap.Ground(ground))
        assertEquals(listOf(1, 3, 2), r.snaps.map { it.node })
        assertEquals(listOf(true, false, false), r.snaps.map { it.lead })
        assertTrue(r.snaps[2].cost > r.snaps[1].cost && r.snaps[1].cost > ShippedSnap.FAR_COST)
    }

    @Test fun aCitysStreetNodesAreSnappedToOnlyFromAnEndOnThatFloor() {
        // a street node 4 yd off (a floor's inside node, not in the ground's nodes) and a ground node 13 yd off
        val streets = listOf(7 to doubleArrayOf(4.0, 0.0))
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(0.0, 13.0)), ShippedSnap.Ground(flat), floor = setOf(7), floorNodes = streets)
        assertEquals(listOf(7), lead(r)); assertTrue(r.why, r.why.startsWith("on a floor"))
        // off the floor (the ground node nearer): the ground's, the street node never offered
        val off = ShippedSnap.snap(doubleArrayOf(0.0, 12.0), listOf(1 to doubleArrayOf(0.0, 13.0)), ShippedSnap.Ground(flat), floor = setOf(7), floorNodes = streets)
        assertEquals(listOf(1), off.snaps.map { it.node })
    }

    @Test fun noNodeWithin60YdSaysSo() {
        val r = ShippedSnap.snap(p0, listOf(1 to doubleArrayOf(80.0, 0.0)), ShippedSnap.Ground(flat))
        assertFalse(r.ok)
        assertEquals("no node within 60 yd (the nearest 80 yd)", r.why)
    }

    @Test fun theGroundAsOneCostFoldsTheWaterAndTheRoomsIn() {
        val g = ShippedSnap.Ground(flat, water { x, _ -> if (x in 10.0..20.0) 4f else if (x in 28.0..36.0) 1f else Float.NaN }, room(50.0, 52.0, -5.0, 5.0))
        assertEquals(Terrain.WALL, g.asCost(15.0, 0.0))
        assertEquals(SafePath.FORD_COST.toFloat(), g.asCost(32.0, 0.0), 1e-3f)
        assertEquals(Terrain.WALL, g.asCost(51.0, 0.0))
        assertEquals(1f, g.asCost(0.0, 0.0))
        assertNull(g.wall(0.0, 0.0))
    }

    // ---------------------------------------------------------------- through ShippedRoute (the seam)

    private val H = TransportRoutes.HORDE
    private fun node(id: Int, x: Double, y: Double) = SafePath.Node(id, x, y, SafePath.POINT)
    private fun road(a: Int, b: Int) = SafePath.Edge(a, b, SafePath.ROAD, SafePath.OK, "auto", 0f, 1f)
    /** a road along x = 40, y 0 .. 1000, nodes every 50 yd */
    private fun road40(): ShippedNet.Section {
        val nodes = (0..20).map { node(it, 40.0, it * 50.0) }
        return ShippedNet.section(SafePath.Net(1411, 1, "off-device", nodes, (0 until 20).map { road(it, it + 1) }, emptyList()), H, 10, "test")
    }

    @Test fun theRouteSnapsRoundAWallTheOldGroundCouldNotSee() {
        // a wall 2 yd thick between the player (0, 0) and the road at x = 40, from y -30 to 30: the coarse ground (flat) misses it
        val g = ShippedSnap.Ground(flat, room = room(19.0, 21.0, -30.0, 30.0))
        val r = ShippedRoute.plan(road40(), g.asCost, null, p0, doubleArrayOf(40.0, 1000.0), attacher = ShippedSnap.attacher(g))
        assertTrue(r.why, r.reached)
        assertTrue(r.why, r.snapped)
        val leg = r.pts.takeWhile { it[0] < 40.0 - 1e-6 } + listOf(r.pts.first { it[0] >= 40.0 - 1e-6 })
        assertTrue("the snap never crosses the wall", clear(leg, g))
    }

    @Test fun aWaysNearestPointIsASnapAndTheCutWalksTheWaysOwnPoints() {
        // a walked way bending round (40,0) -> (40,30) -> (0,60): its nearest point to the player (10, 50) is on its second stretch
        val way = ShippedRoute.WayView(3, listOf(doubleArrayOf(40.0, 0.0), doubleArrayOf(40.0, 30.0), doubleArrayOf(0.0, 60.0)), 80.0)
        val r = ShippedSnap.snap(doubleArrayOf(10.0, 50.0), listOf(1 to doubleArrayOf(40.0, 0.0)), ShippedSnap.Ground(flat), ways = listOf(way))
        val b = r.best!!
        assertTrue(b.cut); assertEquals(3, b.way); assertTrue(b.lead)
        assertTrue("nearer than the node", b.yd < 10.0)
        assertTrue("along its second stretch", b.t > 30.0 / 80.0 && b.t < 1.0)
        // the router's split of a way at a cut: its own points either side
        val (before, after) = ShippedRoute.splitAt(way.line, 50.0)
        assertEquals(listOf(40.0, 40.0), before.map { it[0] }); assertEquals(listOf(0.0), after.map { it[0] })
    }

    @Test fun theRouteCutIntoAWayFollowsItsBends() {
        // a road (0,0) -> (0,1000) and the player beside its middle: the cut, then along the road, never straight to a far end
        val r = ShippedRoute.plan(road40(), flat, null, doubleArrayOf(20.0, 525.0), doubleArrayOf(40.0, 1000.0))
        assertTrue(r.why, r.reached)
        assertTrue(r.why, r.attachFromYd < 21.0)
        assertTrue("on the road from the cut on", r.pts.drop(1).all { it[0] == 40.0 })
    }

    @Test fun theNearGoalWalkKeepsToLevelGroundOrIsNone() {
        val to = doubleArrayOf(0.0, 100.0)
        // level: the straight line
        val flatWalk = ShippedSnap.directWalk(p0, to, ShippedSnap.Ground(flat))!!
        assertTrue(flatWalk.straight); assertEquals(100.0, flatWalk.yd, 1e-6)
        // a climb band (3x: a 37 degree slope cell, what a 70 degree face 24 yd long reads as on the 25 yd grid) across the way, with a
        // gap at x 40..50: round it, never over it
        val band: (Double, Double) -> Float = { x, y -> if (y in 45.0..55.0 && x !in 40.0..50.0) 3f else 1f }
        val round = ShippedSnap.directWalk(p0, to, ShippedSnap.Ground(band))!!
        assertFalse(round.straight)
        for ((a, b) in round.pts.zipWithNext()) for (q in 0..50) { val x = a[0] + (b[0] - a[0]) * q / 50; val y = a[1] + (b[1] - a[1]) * q / 50
            assertTrue("on the band at $x,$y", band(x, y) <= 1f) }
        // a cliff's band (1.6) right across: none - the net's way only
        val shut: (Double, Double) -> Float = { _, y -> if (y in 45.0..55.0) 1.6f else 1f }
        assertNull(ShippedSnap.directWalk(p0, to, ShippedSnap.Ground(shut)))
    }

    @Test fun aNearGoalTheWalkCannotReachCleanGoesByTheNet() {
        // the goal 100 yd up the road's side, a climb band between: the router's direct walk (the snap's) refuses it, the road carries it
        val shut: (Double, Double) -> Float = { _, y -> if (y in 545.0..555.0) 3f else 1f }
        val g = ShippedSnap.Ground(shut)
        val r = ShippedRoute.plan(road40(), g.asCost, null, doubleArrayOf(20.0, 500.0), doubleArrayOf(20.0, 600.0), attacher = ShippedSnap.attacher(g))
        assertTrue(r.why, r.reached)
        assertFalse(r.why, r.why.startsWith("the ground's own walk"))
        assertTrue("never over the band", r.pts.none { it[1] in 545.0..555.0 && it[0] < 35.0 })
    }

    @Test fun anIsolatedNodeIsNeverTheSnap() {
        // a roadmap node 2 yd from the player that no way leaves: left out of the snap's nodes, the road's node taken
        val rm = ShippedRoute.Roadmap(mapOf(ShippedRoute.RM_BASE to doubleArrayOf(2.0, 0.0)), emptyList())
        val r = ShippedRoute.plan(road40(), flat, null, p0, doubleArrayOf(40.0, 1000.0), roadmap = rm)
        assertTrue(r.why, r.reached)
        assertEquals(40.0, r.pts[1][0], 1e-6); assertEquals(0.0, r.pts[1][1], 1e-6)
    }

    @Test fun theAttacherFallsBackToTheLongAttachAndSaysWhy() {
        // the road 150 yd off (no node within 60): the long attach, logged
        val nodes = listOf(node(0, 150.0, -500.0), node(1, 150.0, 500.0))
        val sec = ShippedNet.section(SafePath.Net(1411, 1, "off-device", nodes, listOf(road(0, 1)), emptyList()), H, 10, "test")
        val log = ArrayList<String>()
        val g = ShippedSnap.Ground(flat)
        val r = ShippedRoute.plan(sec, g.asCost, null, p0, doubleArrayOf(150.0, 500.0), attacher = ShippedSnap.attacher(g) { log.add(it) })
        assertTrue(r.why, r.reached)
        assertFalse(r.snapped)
        assertTrue(log.toString(), log.any { it.contains("no node within 60 yd") && it.contains("the long attach") })
        // 250 yd off: no route, the why both the snap's and the long attach's
        val far = ShippedRoute.plan(sec, g.asCost, null, doubleArrayOf(-100.0, 0.0), doubleArrayOf(150.0, 500.0), attacher = ShippedSnap.attacher(g))
        assertFalse(far.reached)
        assertTrue(far.why, far.why.contains("no snap: no node within 60 yd"))
    }
}
