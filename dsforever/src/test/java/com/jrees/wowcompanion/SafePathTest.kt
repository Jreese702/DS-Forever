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

    /** b-safepath-route: on the road rather than across, never on a removed way, out by the network's exit */
    @Test fun theRouteKeepsToTheNetwork() {
        val net = SafePath.draft(input(road = eastRoad))
        // 40 yd off the road at one end, the goal 40 yd off it near the other: along the road
        val w = SafePath.route(net, 460.0, 850.0, 460.0, 60.0)!!
        assertEquals(460.0, w.first()[0], 1e-6); assertEquals(460.0, w.last()[0], 1e-6)
        assertTrue(w.drop(1).dropLast(1).all { abs(it[0] - 500) < 40 })
        // a goal beside the player: the straight walk, no network
        assertTrue(SafePath.route(net, 460.0, 850.0, 440.0, 800.0) == null)
        // far from every way: no help
        assertTrue(SafePath.route(net, 100.0, 850.0, 100.0, 60.0) == null)
        // every way removed: none
        val e = SafePath.Edit(net); for (k in e.edges.indices) e.setState(k, SafePath.REMOVED)
        assertTrue(SafePath.route(e.net(), 460.0, 850.0, 460.0, 60.0) == null)
        // a crossing out near the network's exit (at the border, y 0): the way ends at that exit
        val x = net.nodes.first { it.id == net.exits.single().node }
        val out = SafePath.route(net, 460.0, 850.0, 400.0, -150.0, toExit = true)!!
        assertEquals(x.wx, out.last()[0], 1e-6); assertEquals(x.wy, out.last()[1], 1e-6)
    }

    @Test fun theCapitalsDistrictsNameTheirTowns() {
        assertEquals("Valley of Wisdom", SafePath.district(1454, 0.40, 0.40))
        assertEquals("The Drag", SafePath.district(1454, 0.60, 0.45))
        assertTrue(SafePath.district(1411, 0.5, 0.5) == null)
        val town = List(6) { SafePath.Npc(100.0 + it * 10, 200.0, if (it == 0) "Banker" else "Innkeeper") }
        assertEquals("Valley of Strength · Bank, Inn", SafePath.hubs(town, { _, _ -> "Orgrimmar" }, "Orgrimmar") { _, _ -> "Valley of Strength" }[0].name)
    }

    /** test9's route (the user: "why is it looping down and around an up onto the mountains?"): road pieces apart by a texture's gap
     *  are welded into one; the painted road fills a gap; a way's ends never hook back first */
    @Test fun roadPiecesAreOneWayAndTheEndsGoAhead() {
        // the road along x 500 with a 40 yd gap in it (y 400..360): one piece after the weld, the route straight along it
        val gapped = { wx: Double, wy: Double -> abs(wx - 500) < 38 && wy < 900 && wy > 0 && (wy > 400 || wy < 360) }
        val net = SafePath.draft(input(road = gapped))
        val w = SafePath.route(net, 470.0, 850.0, 470.0, 60.0)!!
        assertTrue("along the road, no detour: ${w.size} points", w.zipWithNext { a, b -> hypot(a[0] - b[0], a[1] - b[1]) }.sum() < 900.0)
        // a wider gap the paint closes: the painted cells there make it one road
        val wide = { wx: Double, wy: Double -> abs(wx - 500) < 38 && wy < 900 && wy > 0 && (wy > 500 || wy < 300) }
        val painted = SafePath.Input(1, 0, 1000.0, 0.0, 1000.0, -1000.0, wide, { _, _ -> 1f }, { _, wy -> if (wy > 0) 1 else 2 },
            painted = { wx, wy -> abs(wx - 500) < 20 && wy in 290.0..510.0 })
        val pn = SafePath.draft(painted)
        assertEquals(1, pn.edges.filter { it.kind == SafePath.ROAD }.let { es ->
            val par = HashMap<Int, Int>(); fun f(x: Int): Int { var a = x; while ((par[a] ?: a) != a) a = par[a]!!; return a }
            for (e in es) par[f(e.a)] = f(e.b); es.map { f(it.a) }.toSet().size })
        // a there-and-back is cut; a hooked start walked straight over level ground
        val loop = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 100.0), doubleArrayOf(60.0, 100.0), doubleArrayOf(10.0, 110.0), doubleArrayOf(0.0, 400.0))
        assertTrue(SafePath.smooth(loop).none { it[0] == 60.0 })                  // the spike out to x 60 is gone
        val hook = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(-120.0, 0.0), doubleArrayOf(-120.0, 200.0), doubleArrayOf(0.0, 200.0), doubleArrayOf(0.0, 900.0))
        assertEquals(listOf(0.0, 200.0), SafePath.smooth(hook, SafePath.clearOf { _, _ -> 1f })[1].toList())
        assertEquals(5, SafePath.smooth(hook, SafePath.clearOf { _, _ -> Terrain.WALL }).size)       // over a cliff: kept
    }

    @Test fun townsSharingANameAreToldApart() {
        // three towns on one subzone (Tirisfal's Deathknell): two with an inn's keeper, one with trainers - told apart by what they are
        fun town(x: Double, sub: String) = (0 until 6).map { SafePath.Npc(x + it, 0.0, sub) }
        val hubs = SafePath.hubs(town(0.0, "Innkeeper") + town(500.0, "Warrior Trainer") + town(1000.0, "Innkeeper"), { _, _ -> "Deathknell" })
        assertEquals(3, hubs.size)
        assertEquals(3, hubs.map { it.name }.toSet().size)
        assertTrue(hubs.all { it.name.startsWith("Deathknell") })
    }

    @Test fun theWalkedWayHasNoThereAndBack() {
        // Teldrassil's Fel Rock: the network ran on 58 yd past the turn, and the ground's leg walked back the same road
        val way = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(100.0, 0.0), doubleArrayOf(100.0, 58.0), doubleArrayOf(100.0, 0.0), doubleArrayOf(150.0, -30.0))
        assertTrue(SafePath.unloop(way, null).none { it[1] == 58.0 })
        assertEquals(3, SafePath.unloop(way, null).size)              // the turn's point, back on the road, cut straight to the next too
        // a short hook (Desolace's Ghost Walker Post: 25 yd out to a junction and back) is cut too
        val hook = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(50.0, 0.0), doubleArrayOf(50.0, 25.0), doubleArrayOf(52.0, 0.0), doubleArrayOf(120.0, 0.0))
        assertTrue(SafePath.unloop(hook, null).none { it[1] == 25.0 })
        // a shortcut over a wall is never taken: the loop stays
        val round = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 100.0), doubleArrayOf(60.0, 100.0), doubleArrayOf(60.0, 0.0), doubleArrayOf(60.0, -50.0))
        assertEquals(5, SafePath.unloop(round) { _, _ -> false }.size)
        // nor cut out of a cave: its points are its own floor
        val cave = listOf(doubleArrayOf(0.0, 0.0, Climb.IN_CAVE), doubleArrayOf(0.0, 100.0, Climb.IN_CAVE), doubleArrayOf(10.0, 0.0, Climb.IN_CAVE), doubleArrayOf(50.0, 0.0))
        assertEquals(4, SafePath.unloop(cave, null).size)
    }

    /** the user on the border picture: "I don't mind them, as long as there is a low danger path back to another path" */
    @Test fun aCrossingStaysWhereBothSidesHaveALowDangerWayBack() {
        fun n(id: Int, x: Double, y: Double, kind: String = SafePath.POINT) = SafePath.Node(id, x, y, kind)
        // zone 1 (y > 0): a town at y 600, a road to the border at y 0 (exit 2); a second exit 300 yd north with only a dangerous way
        fun west(danger: Float) = SafePath.Net(1, 0, "", listOf(n(0, 500.0, 600.0, SafePath.HUB), n(1, 500.0, 200.0), n(2, 500.0, 0.0, SafePath.EXIT),
            n(3, 800.0, 200.0), n(4, 800.0, 0.0, SafePath.EXIT)),
            listOf(SafePath.Edge(0, 1, SafePath.ROAD), SafePath.Edge(1, 2, SafePath.ROAD), SafePath.Edge(1, 3, SafePath.TRAIL, danger = danger),
                SafePath.Edge(3, 4, SafePath.TRAIL, danger = danger)),
            listOf(SafePath.Exit(2, 2, "walk", "East"), SafePath.Exit(4, 2, "walk", "East")))
        // zone 2 (y < 0): its exits across from both, each with a road back to its town
        val east = SafePath.Net(2, 0, "", listOf(n(10, 500.0, -600.0, SafePath.HUB), n(11, 500.0, -20.0, SafePath.EXIT), n(12, 800.0, -20.0, SafePath.EXIT),
            n(13, 650.0, -300.0)),
            listOf(SafePath.Edge(10, 13, SafePath.ROAD), SafePath.Edge(13, 11, SafePath.ROAD), SafePath.Edge(13, 12, SafePath.ROAD)),
            listOf(SafePath.Exit(11, 1, "walk", "West"), SafePath.Exit(12, 1, "walk", "West")))
        assertEquals(SafePath.Link.LOW, SafePath.link(west(0f), 2))
        assertEquals(SafePath.Link.DANGER, SafePath.link(west(1f), 4))
        // safe both ways: both kept
        assertEquals(2, SafePath.checkCrossings(west(0f), listOf(east)).exits.size)
        // the north one's way back runs through mobs: kept, its ways avoided
        val c = SafePath.checkCrossings(west(1f), listOf(east))
        assertEquals(2, c.exits.size)
        assertTrue(c.edges.single { it.b == 4 }.state == SafePath.AVOID)
        // the far side has no exit across from it: dropped, with the dead-end way to it
        val lone = SafePath.Net(2, 0, "", east.nodes, east.edges, east.exits.filter { it.node == 11 })
        val d = SafePath.checkCrossings(west(0f), listOf(lone))
        assertEquals(listOf(2), d.exits.map { it.node })
        assertTrue(d.edges.none { it.b == 4 || it.a == 4 })
        // a neighbour not drafted yet: as it was
        assertEquals(2, SafePath.checkCrossings(west(1f), emptyList()).exits.size)
    }

    /** the user on test11: "Pathing shouldn't be in water" - a river is a wall but for a bridge (or a shallow ford); the exit on its bank */
    @Test fun noWayOrNodeInWater() {
        // a road from the west to a 60 yd deep river at y -20..40 (the border at y 0), its road going on beyond on the other side
        val road = { wx: Double, wy: Double -> abs(wx - 500) < 38 && (wy > 60 || wy < -60) && wy < 900 }
        val river = { _: Double, wy: Double -> if (wy in -40.0..40.0) 3f else Float.NaN }
        fun inp(water: (Double, Double) -> Float) = SafePath.Input(1, 0, 1000.0, 0.0, 1000.0, -1000.0, road, { _, _ -> 1f },
            { _, wy -> if (wy > 0) 1 else 2 }, water = water)
        val swim = SafePath.draft(inp(river))
        assertTrue("no exit over deep water", swim.exits.isEmpty())
        assertTrue("no node in the water", swim.nodes.none { abs(it.wy) < 40 })
        // a bridge over it at x 480..520: one exit, a bridge, on the west bank (dry, this zone's)
        val bridged = SafePath.draft(inp { wx, wy -> if (wy in -40.0..40.0) (if (abs(wx - 500) < 12) SafePath.Water.BRIDGE else 3f) else Float.NaN })
        val x = bridged.exits.single()
        assertEquals("bridge", x.how)
        val n = bridged.nodes.first { it.id == x.node }
        assertTrue("on the bank: y ${n.wy}", n.wy > 25)
    }

    /** the water per vertex: the depth at a vertex is the mean of the wet liquid cells meeting there, straight between vertices (a
     *  coarse cell read only one side of its border, and the lattice's steps run on those borders: Darkshore's route B up the
     *  Cliffspring River swam 5.8 yd water reading the 1.0 yd cell beside the line); dry beyond the water's last vertex */
    @Test fun theWaterIsReadPerVertex() {
        val s = Climb.STEP
        // west of y 50 (a lattice line) 4.9 yd deep, east of it 1.0
        val w = SafePath.Water.build(100.0, 0.0, 100.0, 0.0) { _, y -> if (y > 50.0) 4.9f else 1.0f }
        assertEquals("on the border, both sides' mean", 2.95f, w.at(30.0, 50.0), 0.06f)
        assertEquals("a vertex west of it", 4.9f, w.at(30.0, 50.0 + s), 0.05f)
        assertEquals("a vertex east of it", 1.0f, w.at(30.0, 50.0 - s), 0.05f)
        assertEquals("halfway west", 3.925f, w.at(30.0, 50.0 + s / 2), 0.06f)
        // dry beyond the water's edge
        val shore = SafePath.Water.build(100.0, 0.0, 100.0, 0.0) { _, y -> if (y > 50.0) 2f else Float.NaN }
        assertTrue(shore.at(30.0, 50.0 - 1.5 * s).isNaN())
        assertEquals("the shore's vertex has its wet cells' depth", 2f, shore.at(30.0, 50.0), 0.05f)
        // the file keeps it
        val f = java.io.File.createTempFile("water", ".bin"); w.write(f)
        val back = SafePath.Water.read(f)!!; f.delete()
        assertEquals(2.95f, back.at(30.0, 50.0), 0.06f)
    }

    /** the user's narrow-stream ruling (2026-10-18, Silverpine's stream by the Sepulcher: "Yes, you wade it"): water up to 2.2 yd is
     *  crossed where it is no wider than 6 yd, a ford or the bank on both sides; a wide river of the same depth, or a narrow one deeper,
     *  is a swim */
    @Test fun aNarrowStreamIsWaded() {
        // a 2 yd deep stream 5 yd wide (y 48..53), a 2 yd deep river 30 yd wide (y 10..40), dry between
        val w = SafePath.Water.build(100.0, 0.0, 100.0, 0.0) { _, y -> if (y in 48.0..53.0 || y in 10.0..40.0) 2f else Float.NaN }
        assertTrue("the stream", w.wadeable(30.0, 50.5)); assertTrue(w.wading(30.0, 50.5))
        assertTrue("the river", !w.wadeable(30.0, 25.0))
        val deep = SafePath.Water.build(100.0, 0.0, 100.0, 0.0) { _, y -> if (y in 48.0..53.0) 3f else Float.NaN }
        assertTrue("a narrow stream 3 yd deep", !deep.wadeable(30.0, 50.5))
        assertTrue("a ford", SafePath.Water.build(100.0, 0.0, 100.0, 0.0) { _, _ -> 1.2f }.wadeable(30.0, 30.0))
    }

    /** the depth at a spot is the liquid's surface over the ground at the spot itself (straight between the terrain's vertices), not
     *  over its cell's highest corner: a stream's mouth 3.6 yd deep in Silverpine read shallow over the bank's corner */
    @Test fun depthIsOverTheGroundAtTheSpot() {
        // one tile: the surface 10 yd everywhere; the ground falling 1 yd a vertex eastward from 10
        val tc = 32; val tr = 32
        val surf = FloatArray(128 * 128) { 10f }
        val ground = FloatArray(128 * 128) { k -> 10f - (k % 128) }
        val ox = 32 * MapHarvest.ADT_YD - tr * MapHarvest.ADT_YD; val oy = 32 * MapHarvest.ADT_YD - tc * MapHarvest.ADT_YD
        fun at(col: Double) = SafePath.Water.depthOf(ox - 0.5 * Climb.STEP, oy - col * Climb.STEP, { _, _ -> surf }, { _, _ -> ground })
        assertEquals(0f, at(0.01), 0.05f)
        assertEquals(2.5f, at(2.5), 0.05f)
        assertEquals(4.0f, at(4.0), 0.05f)
    }

    /** a hooked end is never cut straight onto a cave's or a lift's floor point (Thunder Bluff: the foot of the mesa cut straight onto a
     *  ramp's floor 100 yd up, the walk's own check passing it as a cave's) */
    @Test fun smoothNeverCutsOntoACavesFloor() {
        val land = doubleArrayOf(0.0, 0.0)
        val way = listOf(land, doubleArrayOf(40.0, 0.0), doubleArrayOf(40.0, 40.0, Climb.IN_CAVE), doubleArrayOf(20.0, 40.0, Climb.LIFT),
            doubleArrayOf(20.0, 41.0, Climb.LIFT), doubleArrayOf(0.0, 30.0, Climb.IN_CAVE), doubleArrayOf(0.0, 25.0))
        val out = SafePath.smooth(way) { _, _ -> true }
        for ((a, b) in out.zipWithNext()) {
            val ia = way.indexOfFirst { it === a }; val ib = way.indexOfFirst { it === b }
            assertTrue("a cut from ${a.toList()} to ${b.toList()} skips over a floor point", ib == ia + 1 || (!Climb.inCave(a) && !Climb.inCave(b)))
        }
    }

    /** the ends' shortcut never lands on a cave's point either (the review's H2: `cut && (A) || (B)` skipped the guard on B) */
    @Test fun theEndsShortcutNeverCutsOntoACavesFloor() {
        val way = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 50.0), doubleArrayOf(10.0, 50.0), doubleArrayOf(10.0, 0.0, Climb.IN_CAVE),
            doubleArrayOf(20.0, 0.0, Climb.IN_CAVE), doubleArrayOf(200.0, 0.0))
        val out = SafePath.smooth(way) { _, _ -> true }
        assertTrue("the start cut straight onto the cave's point", out.size < 2 || !(Climb.inCave(out[1]) && way.indexOfFirst { it === out[1] } > 1))
    }

    /** a lift's ride is a [SafePath.LIFT] edge whose cost is its wait (zero yards long, it still costs Caves.LIFT_WAIT_YD) */
    @Test fun aLiftsRideCostsItsWait() {
        val w = Caves.Way(listOf(doubleArrayOf(0.0, 0.0, 60.0), doubleArrayOf(0.0, 10.0, 69.0), doubleArrayOf(0.0, 12.0, 69.0, 1.0),
            doubleArrayOf(0.0, 12.0, 129.0, 1.0), doubleArrayOf(0.0, 20.0, 129.0), doubleArrayOf(0.0, 40.0, 130.0)), 40.0 + Caves.LIFT_WAIT_YD)
        val net = SafePath.Net(1, 0, "", listOf(SafePath.Node(0, -100.0, 0.0, SafePath.JUNCTION)), emptyList(), emptyList())
        val withLift = SafePath.withCaves(net, listOf(Caves.Cave(1, 1, 0.0, 0.0, listOf(w.a, w.b), listOf(w))))
        assertEquals(1, withLift.edges.count { it.kind == SafePath.LIFT })
        assertEquals(2, withLift.nodes.count { it.kind == SafePath.LIFT })
    }

    /** b-safepath-water: a deck narrower than the water grid's cell (an M2 footbridge, a pier's edge) marks it a bridge when it holds one
     *  of the cell's four liquid points, not only its middle; open water beside it stays water */
    @Test fun aNarrowDeckStillMarksItsWaterCell() {
        val yc = 100.0 - SafePath.WATER_YD / 2                       // the first column's middle
        val strip: (Double, Double) -> Boolean = { _, y -> abs(y - (yc + SafePath.WATER_YD / 4)) < 0.5 }
        val w = SafePath.Water.build(100.0, 0.0, 100.0, 0.0, strip) { _, _ -> 5f }
        assertEquals(SafePath.Water.BRIDGE, w.at(50.0, yc), 0f)
        assertEquals(5f, w.at(50.0, yc - SafePath.WATER_YD), 0.05f)
        assertTrue(!strip(50.0, yc))                                  // its middle is off the deck
    }

    /** the walk on and off the network never cuts into a cave's way: a point there would stand, unmarked, on the terrain over the floor
     *  and the leg on from it start inside the hill (gate run 5: Loch Modan B off Stonewrought Dam's floor) */
    @Test fun theWalkOnAndOffNeverCutsIntoACave() {
        fun n(id: Int, x: Double, y: Double, kind: String = SafePath.POINT) = SafePath.Node(id, x, y, kind)
        val net = SafePath.Net(1, 0, "", listOf(n(0, 0.0, 0.0), n(1, 0.0, 400.0), n(2, 0.0, 420.0, SafePath.MOUTH),
            n(3, 0.0, 500.0, SafePath.CAVE), n(4, 0.0, 600.0, SafePath.CAVE), n(5, 0.0, 700.0, SafePath.MOUTH)),
            listOf(SafePath.Edge(0, 1, SafePath.ROAD), SafePath.Edge(1, 2, SafePath.SPUR), SafePath.Edge(2, 3, SafePath.CAVE),
                SafePath.Edge(3, 4, SafePath.CAVE), SafePath.Edge(4, 5, SafePath.CAVE)), emptyList())
        val way = SafePath.route(net, 0.0, -100.0, 10.0, 560.0)!!
        for (p in way) assertTrue("${p.toList()}: on a cave's way, unmarked", Climb.inCave(p) || !(abs(p[0]) < 1e-6 && p[1] > 420.5 && p[1] < 699.5))
    }
}
