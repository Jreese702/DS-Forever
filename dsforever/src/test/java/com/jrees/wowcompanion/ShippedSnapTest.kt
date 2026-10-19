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
        assertEquals(listOf(1, 3), r.snaps.map { it.node })
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
