package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The exits checked on the ground (SafePath.Ground, checkExits, groundExits) on made-up land: zone 1 the west (world y > 0), zone 2
 * the east. A false exit on a ridge goes, a real one stays; a crossing is local; ground exits only where a player would cross, one per
 * open stretch of border; a tunnel's far end is one exit.
 */
class ExitGroundTest {
    private val org = 32.0 * MapHarvest.ADT_YD
    private val s = Climb.STEP
    private val zoneAt: (Double, Double) -> Int = { _, wy -> if (wy > 0) 1 else 2 }

    /** a lattice over world x [top] .. -[top], y 300 .. -300 from [h] (world x, y -> height) */
    private fun grid(top: Double = 300.0, h: (Double, Double) -> Double) =
        Climb.build(top, -top, 300.0, -300.0, { gr, gc -> h(org - gr * s, org - gc * s).toFloat() })
    private fun ground(g: Climb.Grid, links: List<Climb.Link> = emptyList()) = SafePath.Ground(g, 1, zoneAt, { _, _ -> true }, links)
    private fun node(id: Int, x: Double, y: Double, kind: String = SafePath.POINT) = SafePath.Node(id, x, y, kind)
    private fun edge(a: Int, b: Int, kind: String = SafePath.ROAD) = SafePath.Edge(a, b, kind)

    @Test fun aRidgeExitGoesARealOneStays() {
        // flat land, and a plateau 60 yd up with sheer sides over the border (world x 150..250, y -60..60): a ridge
        val g = grid { wx, wy -> if (wx in 150.0..250.0 && abs(wy) <= 60) 60.0 else 0.0 }
        val net = SafePath.Net(1, 0, "", listOf(node(0, 0.0, 200.0, SafePath.HUB), node(1, 100.0, 100.0, SafePath.JUNCTION), node(2, 0.0, 2.0, SafePath.EXIT),
            node(3, 200.0, 30.0), node(4, 200.0, 2.0, SafePath.EXIT), node(5, 230.0, 2.0, SafePath.EXIT)),
            listOf(edge(0, 1), edge(1, 2), edge(1, 3), edge(3, 4), edge(3, 5)),
            listOf(SafePath.Exit(2, 2, "walk", "East"), SafePath.Exit(4, 2, "walk", "East"), SafePath.Exit(5, 2, "bridge", "East")))
        val seen = HashMap<Int, SafePath.Crossing>()
        val out = SafePath.checkExits(net, ground(g)) { x, c -> seen[x.node] = c }
        assertTrue("the real crossing walkable", seen[2]!!.walkable)
        assertFalse("the ridge's crossing not", seen[4]!!.walkable)
        // a drafted bridge whose far side is no ground of the neighbour's (on the plateau) goes too
        assertEquals(listOf(2), out.exits.map { it.node })
        assertTrue(out.edges.none { it.a == 4 || it.b == 4 })
        assertTrue(out.edges.any { it.a == 1 && it.b == 2 })
    }

    @Test fun aCrossingIsLocal() {
        // a cliff along the border the whole way but for one opening: near the exit, or 560 yd away at the far end
        fun land(open: Double) = grid { wx, wy -> if (abs(wy) <= 6 && abs(wx - open) > 20) 100.0 else 0.0 }
        assertTrue(ground(land(250.0)).check(250.0, 10.0, 2).walkable)
        val far = ground(land(-280.0)).check(280.0, 10.0, 2)
        assertTrue("the zone itself reached", far.ownShare > 0.9)
        assertFalse("the neighbour only by a long loop: no crossing", far.walkable)
    }

    /** a 12 yd cliff band along the border (world y -6..6) with openings at [gaps] (world x middle to half-width), and a cliff
     *  across the east's ground at world x 0 when [eastSplit] */
    private fun border(gaps: List<Pair<Double, Double>>, eastSplit: Boolean = false) = grid(500.0) { wx, wy ->
        when {
            abs(wy) <= 6 && gaps.none { (m, w) -> abs(wx - m) <= w } -> 100.0
            eastSplit && wy < -6 && abs(wx) <= 6 -> 100.0
            else -> 0.0
        }
    }
    private val hub = SafePath.Net(1, 0, "", listOf(node(0, 0.0, 200.0, SafePath.HUB)), emptyList(), emptyList())

    @Test fun aGroundExitWhereACorridorCrosses() {
        val wide = SafePath.groundExits(hub, ground(border(listOf(400.0 to 20.0))), { "East" })
        assertEquals(1, wide.exits.size)
        val n = wide.nodes.first { it.id == wide.exits[0].node }
        assertTrue("at the corridor: ${n.wx},${n.wy}", abs(n.wx - 400) <= 20 && n.wy > 0 && n.wy < 15)
        assertEquals("walk", wide.exits[0].how); assertEquals("ground", n.src)
        assertTrue(wide.edges.any { it.kind == SafePath.TRAIL && (it.a == n.id || it.b == n.id) })
        // too narrow to be a way (8 yd), into a capital, onto land off the neighbour's map: none
        assertEquals(0, SafePath.groundExits(hub, ground(border(listOf(400.0 to 4.0)))).exits.size)
        assertEquals(0, SafePath.groundExits(hub, ground(border(listOf(400.0 to 20.0))), capitals = setOf(2)).exits.size)
        assertEquals(0, SafePath.groundExits(hub, ground(border(listOf(400.0 to 20.0))), onMap = { _, x, _ -> x < 0 }).exits.size)
    }

    @Test fun oneGroundExitPerOpenStretchOfBorder() {
        // two corridors 700 yd apart onto one open stretch of the east: one exit; with a cliff between them on the east's side, two
        val gaps = listOf(380.0 to 20.0, -320.0 to 20.0)
        assertEquals(1, SafePath.groundExits(hub, ground(border(gaps))).exits.size)
        assertEquals(2, SafePath.groundExits(hub, ground(border(gaps, eastSplit = true))).exits.size)
        // a road's exit already on the stretch: no ground exit beside it
        val road = SafePath.Net(1, 0, "", listOf(node(0, 0.0, 200.0, SafePath.HUB), node(1, 380.0, 8.0, SafePath.EXIT)), listOf(edge(0, 1)),
            listOf(SafePath.Exit(1, 2, "walk", "East")))
        assertEquals(1, SafePath.groundExits(road, ground(border(gaps))).exits.size)
    }

    @Test fun aTunnelsFarEndIsOneExit() {
        // a tunnel whose floor meets the terrain twice at its far end, 28 yd apart: one exit into the east there
        val near = doubleArrayOf(0.0, 100.0, 0.0)
        val ways = listOf(Caves.Way(listOf(near, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(10.0, -100.0, 0.0)), 200.0),
            Caves.Way(listOf(near, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(-18.0, -100.0, 0.0)), 200.0))
        val cave = Caves.Cave(1, 1, 0.0, 0.0, listOf(near, ways[0].b, ways[1].b), ways)
        val net = SafePath.withCaves(SafePath.Net(1, 0, "", listOf(node(0, 0.0, 200.0, SafePath.HUB), node(1, 0.0, 150.0)), listOf(edge(0, 1)), emptyList()),
            listOf(cave), zoneAt = zoneAt, nameOf = { "East" })
        assertEquals(1, net.exits.size); assertEquals("tunnel", net.exits[0].how)
        assertTrue(net.nodes.first { it.id == net.exits[0].node }.wy < -90)
    }

    @Test fun anExitOnTheFarBankOfADeepRiverIsNotACrossing() {
        // a river 12 yd wide and deep along world y -20 (the east's ground), its far bank the east's; the exit on the far bank (Duskwood's
        // Westfall exits): the neighbour all round it, its own zone only the long way round - no crossing. On the near bank: one.
        val g = grid { _, _ -> 0.0 }
        val deep = { _: Double, wy: Double -> abs(wy + 20) > 6 }
        val gr = SafePath.Ground(g, 1, zoneAt, deep)
        assertFalse(gr.check(0.0, -40.0, 2).ownNear)
        assertFalse(gr.check(0.0, -40.0, 2).walkable)
        assertTrue(gr.check(0.0, 4.0, 2).ownNear)
    }

    @Test fun aFalseExitHidesNoRealCrossing() {
        // a drafted gate exit on the plateau (false: kept by groundExits, not verified) 300 yd from a real corridor: the corridor still gets its ground exit
        val g = grid(500.0) { wx, wy -> when {
            wx in 250.0..350.0 && abs(wy) <= 60 -> 60.0
            abs(wy) <= 6 && abs(wx + 40) > 20 -> 100.0
            else -> 0.0 } }
        val net = SafePath.Net(1, 0, "", listOf(node(0, 0.0, 200.0, SafePath.HUB), node(1, 300.0, 2.0, SafePath.EXIT)), listOf(edge(0, 1)),
            listOf(SafePath.Exit(1, 2, "gate", "East")))
        val out = SafePath.groundExits(net, ground(g))
        assertTrue(out.exits.any { x -> out.nodes.first { it.id == x.node }.let { it.src == "ground" && abs(it.wx + 40) <= 20 } })
    }

    @Test fun aStripOfTheZoneOverARiverIsNotItsMainGround() {
        // the zone's own strip west of a deep river (world y -60..-40, x < 0: Duskwood's west bank), joined to its main ground only by a loop
        // round the river's head 480 yd north through the neighbour's ground: an exit on the strip is no crossing
        val zone = { wx: Double, wy: Double -> if (wy > 0 || (wy > -60 && wy < -40 && wx < 0)) 1 else 2 }
        val dry = { wx: Double, wy: Double -> !(wy > -30 && wy < -10 && wx < 470) }
        val gr = SafePath.Ground(grid(500.0) { _, _ -> 0.0 }, 1, zone, dry)
        assertFalse(gr.onMain(-200.0, -55.0))
        assertFalse(gr.check(-200.0, -55.0, 2).walkable)
        assertTrue(gr.check(-200.0, 4.0, 2).ownNear)
    }
}
