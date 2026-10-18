package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot

/**
 * b-route-climb: the hard rule (the user: "I cant walk up walls no matter how much you seem to think I can") on made-up ground -
 * a step of 45 degrees or more is a wall no route crosses, a basin is entered by its walkable opening, pockets the cliffs wall apart
 * join only through a cave, the mobs a cost and never a wall
 */
class ClimbTest {
    private val org = 32.0 * MapHarvest.ADT_YD
    private val s = Climb.STEP

    /** a lattice over world x 200 .. -200, y 200 .. -200 from [h] (world x, y -> height) */
    private fun grid(h: (Double, Double) -> Double) = Climb.build(200.0, -200.0, 200.0, -200.0, { gr, gc -> h(org - gr * s, org - gc * s).toFloat() })

    /** a pit 30 yd deep, 60 yd round world (0, 0), walled all round but for a 20 degree ramp out to the east (world y below 0) */
    private fun basin(wx: Double, wy: Double): Double {
        val r = hypot(wx, wy)
        return when {
            r < 60 -> -30.0
            abs(wx) < 15 && wy < 0 && r < 140 -> -30.0 + (r - 60) * 30.0 / 80.0
            else -> 0.0
        }
    }

    private fun assertWalkable(g: Climb.Grid, pts: List<DoubleArray>) {
        for ((q, ab) in pts.zipWithNext().withIndex())
            assertTrue("segment $q (${ab.first.toList()} -> ${ab.second.toList()}) crosses a wall", Climb.walkable(g, ab.first, ab.second))
    }

    @Test fun aStepOf45DegreesIsAWall() {
        assertEquals(Climb.WALL, Climb.classOf((s * 1.001).toFloat()))        // just over 45 degrees
        assertEquals(Climb.STEEP, Climb.classOf((s * 0.95).toFloat()))
        assertEquals(Climb.LEVEL, Climb.classOf((s * 0.3).toFloat()))
        assertEquals(Climb.WALL, Climb.classOf(Float.NaN))
    }

    @Test fun aBasinIsEnteredByItsOpeningNeverOverItsRim() {
        val g = grid(::basin)
        val from = doubleArrayOf(0.0, 150.0); val goal = doubleArrayOf(0.0, 0.0)
        assertFalse("the straight walk climbs the rim", Climb.walkable(g, from, goal))
        val leg = Climb.route(g, from, goal)
        assertNotNull(leg); leg!!
        assertTrue(leg.reached)
        assertWalkable(g, leg.pts)
        // round to the east and down the ramp
        assertTrue(leg.pts.any { abs(it[0]) < 15 && it[1] < -70 })
    }

    @Test fun pocketsWalledApartJoinOnlyThroughACave() {
        // a 50 yd cliff along world y 0 the whole way: the west low, the east high
        val g = grid { _, wy -> if (wy > 0) 0.0 else 50.0 }
        val from = doubleArrayOf(0.0, 100.0); val goal = doubleArrayOf(0.0, -100.0)
        assertFalse(Climb.route(g, from, goal)!!.reached)
        // a tunnel's floor from a mouth on the low side to one on the high side
        val way = (0..10).map { doubleArrayOf(20.0, 12.0 - 2.4 * it, 5.0 * it) }
        val leg = Climb.route(g, from, goal, links = listOf(Climb.Link(way, 40.0)))!!
        assertTrue(leg.reached)
        assertTrue(leg.pts.any { Climb.inCave(it) })
        assertWalkable(g, leg.pts)
    }

    @Test fun theMobsAreACostNeverAWall() {
        val g = grid { _, _ -> 0.0 }
        val from = doubleArrayOf(0.0, 180.0); val goal = doubleArrayOf(0.0, -100.0)
        // the goal deep in a camp (the grid's most, 4) 120 yd round it
        val camp = { x: Double, y: Double -> if (hypot(x - goal[0], y - goal[1]) < 120) 4f else 0f }
        val leg = Climb.route(g, from, goal, camp, DangerGrid.strength("high"))!!
        assertTrue(leg.reached)
        val yd = leg.pts.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }.sum()
        assertTrue("$yd yd: in through the camp, not round it forever", yd < 1.2 * hypot(goal[0] - from[0], goal[1] - from[1]))
        // a camp on the way, not at the goal: walked round
        val onTheWay = { x: Double, y: Double -> if (hypot(x, y - 40) < 50) 4f else 0f }
        val round = Climb.route(g, from, goal, onTheWay, DangerGrid.strength("high"))!!
        assertTrue(round.reached)
        assertTrue(round.pts.zipWithNext().none { (a, b) -> (0..20).any { t -> val f = t / 20.0; onTheWay(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f) > 0f } })
    }

    @Test fun aNetworkWayOverACliffIsLeftOut() {
        val g = grid(::basin)
        val net = SafePath.Net(1, 0, "", listOf(SafePath.Node(1, 0.0, 150.0, "road"), SafePath.Node(2, 0.0, -150.0, "road")),
            listOf(SafePath.Edge(1, 2, SafePath.ROAD)), emptyList())
        val way = SafePath.routeOn(net, 0.0, 190.0, 0.0, -190.0, { a, b -> Climb.route(g, a, b)?.pts },
            walkable = { a, b -> Climb.walkable(g, a, b) })
        assertNotNull(way); way!!
        assertWalkable(g, way)
        assertTrue(hypot(way.last()[0], way.last()[1] + 190) < 1.0)
    }

    // ---------------------------------------------------------------- the caves' own floors

    private fun chunk(o: ByteArrayOutputStream, tag: String, body: ByteArray) {
        o.write(tag.reversed().toByteArray())
        o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array()); o.write(body)
    }
    /** a group file: MVER, then MOGP (its 0x44 header, then MOPY / MOVI / MOVT) of [tris] (three corners each), all collision faces */
    private fun group(tris: List<List<FloatArray>>, mpy2: Boolean = false): ByteArray {
        val inner = ByteArrayOutputStream()
        inner.write(ByteArray(0x44))
        if (mpy2) chunk(inner, "MPY2", ByteArray(tris.size * 4) { if (it % 4 == 0) 0x08 else 0 })        // the newer groups' faces
        else chunk(inner, "MOPY", ByteArray(tris.size * 2) { if (it % 2 == 0) 0x08 else 0 })
        val idx = ByteBuffer.allocate(tris.size * 6).order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until tris.size * 3) idx.putShort(i.toShort())
        chunk(inner, "MOVI", idx.array())
        val v = ByteBuffer.allocate(tris.size * 36).order(ByteOrder.LITTLE_ENDIAN); for (t in tris) for (c in t) for (x in c) v.putFloat(x)
        chunk(inner, "MOVT", v.array())
        val o = ByteArrayOutputStream()
        chunk(o, "MVER", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(17).array())
        chunk(o, "MOGP", inner.toByteArray())
        return o.toByteArray()
    }
    private fun f(x: Number, y: Number, z: Number) = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())

    @Test fun aCaveFloorIsItsWalkableCollisionFaces() {
        val floor = listOf(f(0, 0, 0), f(1, 0, 0), f(0, 1, 0))
        val wall = listOf(f(0, 0, 0), f(1, 0, 0), f(0, 0, 1))
        assertEquals(9, Caves.floorTris(group(listOf(floor, wall))).size)
        assertEquals(9, Caves.floorTris(group(listOf(floor, wall), mpy2 = true)).size)
    }

    @Test fun aTunnelJoinsItsTwoMouths() {
        // a floor strip 2 yd wide along the WMO's own x, 0 .. 40, at height 0; the terrain meets it at both ends, a hill over its middle
        val tris = ArrayList<List<FloatArray>>()
        for (x in 0 until 40 step 2) {
            tris.add(listOf(f(x, -1, 0), f(x + 2, -1, 0), f(x, 1, 0))); tris.add(listOf(f(x + 2, -1, 0), f(x + 2, 1, 0), f(x, 1, 0)))
        }
        val p = WorldBuildings.Placement(1, 1, 0.0, 0.0, 0.0, 0.0, 1.0)
        val cave = Caves.cave(p, listOf(group(tris)), { wx, _ -> if (wx > -3 || wx < -37) 0f else 50f }, { _, _ -> true })
        assertEquals(2, cave.mouths.size)
        assertEquals(1, cave.ways.size)
        assertTrue("${cave.ways[0].yd}", abs(cave.ways[0].yd - 40) < 3)
    }

    @Test fun anAdtHoleIsReadFromItsChunk() {
        // one MCNK (index 0, 0), its low-res holes 0x0001: the chunk's first 2 x 2 cells
        val hdr = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN); hdr.putShort(0x3C, 1)
        val o = ByteArrayOutputStream(); chunk(o, "MCNK", hdr.array())
        val h = Terrain.holes(o.toByteArray())!!
        assertTrue(h[0] && h[1] && h[128] && h[129])
        assertFalse(h[2] || h[256])
    }
}
