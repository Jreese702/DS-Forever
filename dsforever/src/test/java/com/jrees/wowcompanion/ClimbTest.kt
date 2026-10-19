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

    @Test fun aRidgeOnTheMiddleVerticesIsAWall() {
        // the outer vertices all level; a band of cells (world y 20 .. -20) whose middle vertices stand 6 yd up: the game's triangles
        // there rise 6 yd in 2 yd (over 70 degrees), a ridge the outer vertices alone never show
        fun ridge(gr: Int, gc: Int) = if (abs(org - (gc + 0.5) * s) < 20 && abs(org - (gr + 0.5) * s) < 150) 6f else 0f
        val flat = Climb.build(200.0, -200.0, 200.0, -200.0, { _, _ -> 0f })
        val g = Climb.build(200.0, -200.0, 200.0, -200.0, { _, _ -> 0f }, centre = ::ridge)
        val from = doubleArrayOf(0.0, 100.0); val goal = doubleArrayOf(0.0, -100.0)
        assertTrue("the outer vertices alone: level", Climb.walkable(flat, from, goal))
        assertFalse("the triangles: a wall", Climb.walkable(g, from, goal))
        val leg = Climb.route(g, from, goal)!!
        assertTrue(leg.reached)
        assertWalkable(g, leg.pts)
        assertTrue("round the ridge's end", leg.pts.any { abs(it[0]) >= 145 })
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

    @Test fun aDraftedWayOverACliffIsRemoved() {
        val g = grid(::basin)
        val net = SafePath.Net(1, 0, "", listOf(SafePath.Node(1, 0.0, 150.0, "road"), SafePath.Node(2, 0.0, -150.0, "road"),
            SafePath.Node(3, 150.0, 150.0, "road")), listOf(SafePath.Edge(1, 2, SafePath.TRAIL), SafePath.Edge(1, 3, SafePath.ROAD)), emptyList())
        fun leg(a: DoubleArray, b: DoubleArray) = Climb.route(g, a, b)?.takeIf { it.reached }?.pts?.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }?.sum()
        val out = SafePath.dropWalled(net, { a, b -> Climb.walkable(g, a, b) }, ::leg)
        assertEquals(SafePath.REWALK, out.edges[0].state)                       // over the pit, its rim walked round on level ground
        assertEquals(SafePath.DRAFT, out.edges[1].state)                        // along the level ground
        // a cliff with no way round: removed
        val cliff = grid { _, wy -> if (wy > 0) 0.0 else 50.0 }
        val net2 = SafePath.Net(1, 0, "", listOf(SafePath.Node(1, 0.0, 100.0, "road"), SafePath.Node(2, 0.0, -100.0, "road")), listOf(SafePath.Edge(1, 2, SafePath.TRAIL)), emptyList())
        val out2 = SafePath.dropWalled(net2, { a, b -> Climb.walkable(cliff, a, b) }) { a, b ->
            Climb.route(cliff, a, b)?.takeIf { it.reached }?.pts?.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }?.sum() }
        assertEquals(SafePath.REMOVED, out2.edges[0].state)
    }

    @Test fun aRoadOverOneLipIsWalkedRoundIt() {
        // a lip 40 yd long (a short ridge on the middle vertices) across a road: the ground's leg goes round its end, hardly longer
        fun lip(gr: Int, gc: Int) = if (abs(org - (gc + 0.5) * s) < 6 && abs(org - (gr + 0.5) * s) < 20) 6f else 0f
        val g = Climb.build(200.0, -200.0, 200.0, -200.0, { _, _ -> 0f }, centre = ::lip)
        val net = SafePath.Net(1, 0, "", listOf(SafePath.Node(1, 0.0, 100.0, "road"), SafePath.Node(2, 0.0, -100.0, "road")),
            listOf(SafePath.Edge(1, 2, SafePath.ROAD)), emptyList())
        val out = SafePath.dropWalled(net, { a, b -> Climb.walkable(g, a, b) }) { a, b ->
            Climb.route(g, a, b)?.takeIf { it.reached }?.pts?.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }?.sum() }
        assertEquals(SafePath.REWALK, out.edges[0].state)
        // and a route rides it, walked round the lip
        val way = SafePath.routeOn(out, 0.0, 190.0, 0.0, -190.0, { a, b -> Climb.route(g, a, b)?.pts }, walkable = { a, b -> Climb.walkable(g, a, b) })!!
        assertWalkable(g, way)
    }

    @Test fun aThinRoadShowsInItsCell() {
        // a 2 x 1 grid: cell 0's most-shown texture is grass (1); a road (7) shows thinly in it; cell 1 is all grass
        val rg = Roads.Grid(0, 50.0, 0.0, 50.0, 0.0, 2, 1, intArrayOf(1, 1), intArrayOf(1, 7), longArrayOf(0b11L, 0b01L))
        assertTrue(rg.shows(25.0, 40.0, setOf(7)))
        assertFalse(rg.shows(25.0, 10.0, setOf(7)))
        assertTrue(rg.shows(25.0, 10.0, setOf(1)))
        // kept through its file (GTX2)
        val f = java.io.File.createTempFile("gtx", ".bin"); f.deleteOnExit()
        rg.write(f)
        val back = Roads.Grid.read(f)!!
        assertTrue(back.shows(25.0, 40.0, setOf(7)))
        assertFalse(back.shows(25.0, 10.0, setOf(7)))
    }

    // ---------------------------------------------------------------- the caves' own floors

    private fun chunk(o: ByteArrayOutputStream, tag: String, body: ByteArray) {
        o.write(tag.reversed().toByteArray())
        o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array()); o.write(body)
    }
    /** a group file: MVER, then MOGP (its 0x44 header, then MOPY / MOVI / MOVT) of [tris] (three corners each), all collision faces */
    private fun group(tris: List<List<FloatArray>>, mpy2: Boolean = false, mliq: ByteArray? = null): ByteArray {
        val inner = ByteArrayOutputStream()
        inner.write(ByteArray(0x44))
        if (mliq != null) chunk(inner, "MLIQ", mliq)
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

    /** a group's own lava (its MLIQ: Searing Gorge's Cauldron) leaves no floor under it; a floor over it, or under water, stays */
    @Test fun noFloorUnderAGroupsOwnLava() {
        // one liquid tile 4.17 yd square from the model's origin, its surface at 5; tile type 2 (magma), or 0 (water)
        fun mliq(type: Int): ByteArray {
            val b = ByteBuffer.allocate(30 + 4 * 8 + 1).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(2); b.putInt(2); b.putInt(1); b.putInt(1); b.putFloat(0f); b.putFloat(0f); b.putFloat(5f); b.putShort(0)
            repeat(4) { b.putInt(0); b.putFloat(5f) }
            b.put(type.toByte()); return b.array()
        }
        val under = listOf(f(0, 0, 0), f(2, 0, 0), f(0, 2, 0))
        val over = listOf(f(0, 0, 9), f(2, 0, 9), f(0, 2, 9))
        assertEquals(9, Caves.floorTris(group(listOf(under, over), mliq = mliq(2))).size)
        assertEquals(18, Caves.floorTris(group(listOf(under, over), mliq = mliq(0))).size)
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

    @Test fun aPortalGoesOneWay() {
        // the 50 yd cliff along y 0 again; a portal from the low side to the high side, one way
        val g = grid { _, wy -> if (wy > 0) 0.0 else 50.0 }
        val portal = Climb.Link(listOf(doubleArrayOf(20.0, 20.0, 0.0), doubleArrayOf(20.0, -20.0, 50.0)), 15.0, oneWay = true)
        assertTrue(Climb.route(g, doubleArrayOf(0.0, 100.0), doubleArrayOf(0.0, -100.0), links = listOf(portal))!!.reached)
        assertFalse(Climb.route(g, doubleArrayOf(0.0, -100.0), doubleArrayOf(0.0, 100.0), links = listOf(portal))!!.reached)
    }

    @Test fun aStairsTreadsAreOneFloor() {
        // three treads 2 yd deep, each 1 yd up and 0.5 yd on from the last (the risers no floor): one way up them
        val tris = ArrayList<List<FloatArray>>()
        for (k in 0 until 3) { val x0 = k * 2.5f; val z = k.toFloat()
            tris.add(listOf(f(x0, -1, z), f(x0 + 2, -1, z), f(x0, 1, z))); tris.add(listOf(f(x0 + 2, -1, z), f(x0 + 2, 1, z), f(x0, 1, z))) }
        val fl = Caves.floor(listOf(WorldBuildings.Placement(1, 1, 0.0, 0.0, 0.0, 0.0, 1.0) to listOf(group(tris))))
        assertEquals(1, fl.piece.toSet().size)
        val bottom = (0 until fl.size).minByOrNull { fl.zs[it] }!!; val top = (0 until fl.size).maxByOrNull { fl.zs[it] }!!
        assertNotNull(fl.path(bottom, top))
    }

    @Test fun anAdtHoleIsReadFromItsChunk() {
        // one MCNK (index 0, 0), its low-res holes 0x0001: the chunk's first 2 x 2 cells
        val hdr = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN); hdr.putShort(0x3C, 1)
        val o = ByteArrayOutputStream(); chunk(o, "MCNK", hdr.array())
        val h = Terrain.holes(o.toByteArray())!!
        assertTrue(h[0] && h[1] && h[128] && h[129])
        assertFalse(h[2] || h[256])
    }

    /** b-safepath-water: a walkway's DECK (a bridge over a trench 20 yd deep across the whole lattice) is the ground at its own floor's
     *  height - walked over in both directions, never left over its sides (a step off it drops to the trench's bed: a wall, on every
     *  side), and the trench is crossed nowhere else */
    @Test fun aBridgesDeckIsLevelBothWaysAndNeverStepsOffItsSides() {
        fun trench(wx: Double, wy: Double) = if (abs(wy) < 30) -20.0 else 0.0
        val onDeck: (Double, Double) -> Boolean = { x, y -> abs(x) <= 5.0 && abs(y) <= 40.0 }
        val bare = grid(::trench)
        // the deck at the banks' height: its floor 0 over the trench's 20 yd deep bed
        val g = Climb.build(200.0, -200.0, 200.0, -200.0, { gr, gc -> trench(org - gr * s, org - gc * s).toFloat() }, deck = { x, y -> if (onDeck(x, y)) 0f else Float.NaN })
        assertFalse(Climb.route(bare, doubleArrayOf(0.0, 60.0), doubleArrayOf(0.0, -60.0))!!.reached)
        for ((a, b) in listOf(doubleArrayOf(0.0, 60.0) to doubleArrayOf(0.0, -60.0), doubleArrayOf(0.0, -60.0) to doubleArrayOf(0.0, 60.0))) {
            val leg = Climb.route(g, a, b)!!
            assertTrue("crossed by the bridge", leg.reached)
            assertWalkable(g, leg.pts)
            for (p in leg.pts) if (abs(p[1]) < 30) assertTrue("over the trench off the deck at ${p.toList()}", abs(p[0]) <= 5.5)
        }
        // the deck's edge vertices over the trench: their steps off it are walls on every side (north, south, east of the deck's end)
        for (j in 0 until g.ny) for (i in 0 until g.nx) {
            val x = g.wx(j); val y = g.wy(i)
            if (!onDeck(x, y) || abs(y) >= 30) continue
            for ((di, dj) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = g.wx(j + dj); val ny = g.wy(i + di)
                if (!onDeck(nx, ny) && abs(ny) < 30) assertEquals("a step off the deck at ${x.toInt()},${y.toInt()} by ($di, $dj)", Climb.WALL, g.step(i, j, di, dj))
            }
        }
    }

    /** the floor a walker is on (Climb.floorLayers): a bridge's road deck from its approaches, never the arches under it, nor a roof
     *  over the ground; a tunnel's floor only from its mouth, never from the hillside over it */
    @Test fun aWalkerIsOnTheFloorItReaches() {
        val s = Climb.STEP
        // the ground: a gorge 40 yd deep between x 40 and 120 (banks at 0 yd); the world rect 0..160 x 0..40
        val gr0 = Math.floor((32 * MapHarvest.ADT_YD - 160.0) / s).toInt()
        fun gx(gr: Int) = 32 * MapHarvest.ADT_YD - gr * s
        val ground = { gr: Int, _: Int -> val x = gx(gr); if (x > 40 && x < 120) -40f else 0f }
        // the bridge over it (y 10..30): its road deck at 0.5 yd all along, arches at -15 under its middle, a parapet 2.5 yd up along
        // its edges (y 10..12); a roof at 6 yd over the bank at x 140..150
        val floors = { x: Double, y: Double ->
            val out = ArrayList<Double>()
            if (y in 10.0..30.0 && x in 30.0..130.0) { out.add(0.5); if (x in 50.0..110.0) out.add(-15.0); if (y < 12.0) out.add(3.0) }
            if (x in 140.0..150.0) out.add(6.0)
            out.toDoubleArray() }
        val lay = Climb.floorLayers(160.0, 0.0, 40.0, 0.0, floors, ground)
        assertEquals("the road deck over the gorge", 0.5f, lay[Climb.vkeyAt(80.0, 20.0)]!!, 0.01f)
        assertEquals("from its approach", 0.5f, lay[Climb.vkeyAt(35.0, 20.0)]!!, 0.01f)
        assertTrue("a roof over the bank is no ground", lay[Climb.vkeyAt(145.0, 20.0)] == null)
        // a tunnel: its floor at -20 under a hill (ground 30) from x 40 to 120, its mouths where the floor meets the ground (x < 40, x > 120)
        val hill = { gr: Int, _: Int -> val x = gx(gr); if (x > 40 && x < 120) 30f else -20f }
        val tunnel = { x: Double, y: Double -> if (y in 10.0..30.0 && x in 30.0..130.0) doubleArrayOf(-20.0) else DoubleArray(0) }
        val t = Climb.floorLayers(160.0, 0.0, 40.0, 0.0, tunnel, hill)
        assertEquals("at its mouth", -20f, t[Climb.vkeyAt(35.0, 20.0)]!!, 0.01f)
        assertTrue("under the hill no layer: the cave's link walks it", t[Climb.vkeyAt(80.0, 20.0)] == null)
        // supports under a deck, both meeting the ground: the deck, the higher, is the walker's (Riverglades' Farholde causeway)
        val flat = { _: Int, _: Int -> 0f }
        val stacked = { x: Double, y: Double -> if (y in 10.0..30.0 && x in 20.0..140.0) doubleArrayOf(0.4, -0.3) else DoubleArray(0) }
        assertEquals(0.4f, Climb.floorLayers(160.0, 0.0, 40.0, 0.0, stacked, flat)[Climb.vkeyAt(80.0, 20.0)]!!, 0.01f)
        // a ruin's floor 3 yd under the grass is no layer (Azshara)
        val ruin = { x: Double, y: Double -> if (y in 10.0..30.0 && x in 20.0..140.0) doubleArrayOf(-3.0) else DoubleArray(0) }
        assertTrue(Climb.floorLayers(160.0, 0.0, 40.0, 0.0, ruin, flat).isEmpty())
        val sealed = { x: Double, y: Double -> if (y in 10.0..30.0 && x in 50.0..110.0) doubleArrayOf(-20.0) else DoubleArray(0) }
        assertTrue("a floor under the hill with no mouth is never reached", Climb.floorLayers(160.0, 0.0, 40.0, 0.0, sealed, hill).isEmpty())
        assertTrue(gr0 > 0)
    }

    /** a building's two storeys (Zephras' Valanaar, walkway 7704163): the ground storey is the walker's from its door, though an upper
     *  storey over it, reached by its own ramp, is higher - highest first walled a start inside the ground storey in */
    @Test fun aGroundStoreyIsWalkedFromItsDoor() {
        // the ground 0, sunk to -1.5 under the room (x 20..60); the ground storey's floor at 1.0 from its door (x 16..20) through the room;
        // the upper storey at 8 over the room, its ramp down to the ground at x 96 (its foot 1.2 yd up: higher than the door's floor)
        val ground = { gr: Int, _: Int -> val x = 32 * MapHarvest.ADT_YD - gr * Climb.STEP; if (x > 20 && x < 60) -1.5f else 0f }
        val floors = { x: Double, y: Double ->
            val out = ArrayList<Double>()
            if (y in 10.0..30.0) {
                if (x in 16.0..60.0) out.add(1.0)
                if (x in 20.0..60.0) out.add(8.0)
                if (x > 60.0 && x <= 96.0) out.add(1.2 + 6.8 * (96.0 - x) / 36.0)
            }
            out.toDoubleArray() }
        val lay = Climb.floorLayers(160.0, 0.0, 40.0, 0.0, floors, ground)
        assertEquals("the room, by its door", 1.0f, lay[Climb.vkeyAt(40.0, 20.0)]!!, 0.01f)
        assertEquals("the door", 1.0f, lay[Climb.vkeyAt(18.0, 20.0)]!!, 0.01f)
        assertEquals("the upper storey's ramp", 5.2f, lay[Climb.vkeyAt(75.0, 20.0)]!!, 0.6f)
        // and the room's step out of its door is no wall; its side, 2.5 yd over the sunk ground, still is
        val g = Climb.build(160.0, 0.0, 40.0, 0.0, ground, deck = { x, y -> lay[Climb.vkeyAt(x, y)] ?: Float.NaN })
        assertTrue("out through the door", Climb.route(g, doubleArrayOf(40.0, 20.0), doubleArrayOf(5.0, 20.0))!!.reached)
        // the room's west side (y 30): its last floor vertex's step west, off the floor 2.5 yd over the sunk ground
        val j = g.at(40.0, 20.0)[1]
        val i = (0 until g.nx).filter { lay[Climb.vkeyAt(g.wx(j), g.wy(it))] != null }.minByOrNull { g.wy(it).let { y -> -y } }!!
        assertEquals("off the room's side", Climb.WALL, g.step(i, j, -1, 0))
    }

    /** a rise of more than a step is climbed only along a floor between the two vertices (a ramp, a stair): the Southfury bridge's gate
     *  arch (its roof 5.8 yd over the deck, a pillar beside the deck with only its top read) was climbed over a lattice diagonal's 5.9 yd,
     *  and its roof, highest first, took the deck under it - the bridge's steps walled, the Barrens' bridge exit dropped (R2) */
    @Test fun aSheerSideIsNoRamp() {
        val gorge = { gr: Int, _: Int -> val x = 32 * MapHarvest.ADT_YD - gr * Climb.STEP; if (x > 40 && x < 120) -40f else 0f }
        // the deck at 0.5 (y 10..30) over the gorge; over its middle (x 70..90) a gate arch: its roof at 5.5 over the deck, its pillar
        // beside the deck (y 30..40) solid to the top - only its top (5.5) read
        val arch = { x: Double, y: Double ->
            val out = ArrayList<Double>()
            if (y in 10.0..30.0 && x in 30.0..130.0) out.add(0.5)
            if (y in 10.0..40.0 && x in 70.0..90.0) out.add(5.5)
            out.toDoubleArray() }
        val lay = Climb.floorLayers(160.0, 0.0, 50.0, 0.0, arch, gorge)
        for (x in listOf(72.0, 80.0, 88.0)) for (y in listOf(12.0, 20.0, 28.0))
            assertEquals("the deck under the arch at $x,$y", 0.5f, lay[Climb.vkeyAt(x, y)]!!, 0.01f)
        assertTrue("the pillar's top is no ground", lay[Climb.vkeyAt(80.0, 36.0)] == null)
        // a ramp of 42 degrees from the bank (its floor between every two vertices) is still walked up
        val flat = { _: Int, _: Int -> 0f }
        val ramp = { x: Double, y: Double -> if (y in 10.0..30.0 && x in 10.0..60.0) doubleArrayOf(maxOf(0.0, (x - 20.0) * 0.9)) else DoubleArray(0) }
        assertEquals("up the ramp", 27.0f, Climb.floorLayers(160.0, 0.0, 40.0, 0.0, ramp, flat)[Climb.vkeyAt(50.0, 20.0)]!!, 2f)
    }

    /** the user's "No cutting through": a straight line longer than a step never crosses a building's room ([Climb.Grid.solid]); a
     *  step into it (an inn's door) stays open */
    @Test fun noStraightLineCutsThroughABuilding() {
        val g = Climb.build(100.0, 0.0, 100.0, 0.0, { _, _ -> 0f })
        val ox = 32 * MapHarvest.ADT_YD
        g.solid = { x, y -> x in 40.0..60.0 && y in 40.0..60.0 }
        assertFalse(g.lineClear(doubleArrayOf(50.0, 10.0), doubleArrayOf(50.0, 90.0)))
        assertTrue(g.lineClear(doubleArrayOf(30.0, 10.0), doubleArrayOf(30.0, 90.0)))
        assertTrue("a step in", g.lineClear(doubleArrayOf(50.0, 36.0), doubleArrayOf(50.0, 41.0)))
        assertTrue(ox > 0)
    }

    /** the user's "Allow short steps to 48°": one lattice step under 48 degrees between gentler ones is walked (Feralas' Woodpaw Den);
     *  two in a row, or a 50 degree step, stay walls (Jasperlode) */
    @Test fun aLoneShortSteepStepIsWalked() {
        val rise46 = (Climb.STEP * Math.tan(Math.toRadians(46.0))).toFloat()
        val rise50 = (Climb.STEP * Math.tan(Math.toRadians(50.0))).toFloat()
        val gc0 = Math.floor((32 * MapHarvest.ADT_YD - 100.0) / Climb.STEP).toInt()
        fun grid(h: (Int) -> Float) = Climb.build(100.0, 0.0, 100.0, 0.0, { _, gc -> h(gc - gc0) })
        // flat, one step up at column 10 -> 11
        val one = grid { c -> if (c > 10) rise46 else 0f }
        assertEquals(Climb.STEEP, one.east(10, 5))
        // two in a row: both walls
        val two = grid { c -> if (c > 11) 2 * rise46 else if (c > 10) rise46 else 0f }
        assertEquals(Climb.WALL, two.east(10, 5)); assertEquals(Climb.WALL, two.east(11, 5))
        // 50 degrees: a wall
        assertEquals(Climb.WALL, grid { c -> if (c > 10) rise50 else 0f }.east(10, 5))
    }
}
