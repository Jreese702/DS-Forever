package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-zone routing over the SHIPPED NETWORK (ShippedCross): the nine checks of the harness's cross/plans.json (PlanCross; already
 * TravelLinksTest's over hand-made drafts) again, now with every walk read from NET1 files - each zone's crossings put in one
 * SafePath.Net, made a NET1 section a faction (ShippedNet.section), written to the file's bytes and read back (ShippedNet.read) - and
 * the zones' link starts. The world is TravelLinksTest's SMALL SYNTHETIC one: 1000 yd boxes at made-up places, the uiMap ids kept only so
 * the cases read like plans.json's. None of these positions are the game's. Then: the link starts (an air place's "the way up is
 * learned the first time you take it", a start that is QuestieDB's portal left to it), the coverage rule (a chain through a zone with no
 * shipped net is not taken), the legs on the network and the steps' words.
 */
class ShippedCrossTest {
    // ---- the synthetic world (TravelLinksTest's): map -> (continent, its box's corner); each box 1000 x 1000 yd
    private val boxes = LinkedHashMap<Int, DoubleArray>()
    private fun box(map: Int, cont: Int) { val i = boxes.size; boxes[map] = doubleArrayOf(cont.toDouble(), (i % 8) * 2000.0, (i / 8) * 2000.0) }
    init {
        for (m in listOf(1416, 1424, 1453, 1429, 1455, 1426, 1432, 1427, 1437, 1420, 1458, 1434)) box(m, 0)
        for (m in listOf(1411, 1454, 1413, 1438, 1457, -702, 1439, 1440, 1445)) box(m, 1)
        box(-900, 1)                                                     // an air place of its own (a deck only an updraft reaches)
    }
    private fun mapAt(c: Int, x: Double, y: Double): Int? = boxes.entries.firstOrNull { (_, b) ->
        b[0].toInt() == c && x >= b[1] && x <= b[1] + 1000 && y >= b[2] && y <= b[2] + 1000 }?.key
    private fun at(map: Int, dx: Double = 500.0, dy: Double = 500.0) = doubleArrayOf(boxes[map]!![1] + dx, boxes[map]!![2] + dy)
    private fun cont(map: Int) = boxes[map]!![0].toInt()
    private fun name(m: Int) = "map $m"
    private val A = TransportRoutes.ALLIANCE; private val H = TransportRoutes.HORDE

    // ---- the crossings on foot (TravelLinksTest's), each on the side of the map whose zone net holds it
    private class X(val from: Int, val to: Int, val p: DoubleArray, val name: String, val avoid: Boolean = false, val how: String = "gate")
    private val crossings = listOf(
        X(1453, 1429, at(1453, 990.0, 500.0), "the Stormwind gate"),
        X(1457, 1438, at(1457, 10.0, 500.0), "the Darnassus gate"),
        X(1411, 1413, at(1411, 990.0, 500.0), "the Southfury bridge", how = "bridge"),
        X(1420, 1458, at(1420, 500.0, 990.0), "the Undercity entrance"),
        X(1426, 1455, at(1426, 500.0, 10.0), "the Gates of Ironforge"),
        X(1416, 1424, at(1416, 900.0, 900.0), "Alterac's road to Hillsbrad", how = "walk"),
        X(1411, 1454, at(1411, 10.0, 500.0), "the Orgrimmar gate"),
        X(1413, 1440, at(1413, 500.0, 990.0), "the Barrens into Ashenvale", how = "walk"),
        X(1440, 1439, at(1440, 10.0, 500.0), "Ashenvale into Darkshore", how = "walk"),
    )
    /** a link start in a zone: node at [p] of [kind] into [to] */
    private class S(val map: Int, val p: DoubleArray, val kind: String, val to: Int, val name: String)

    /** one map's NET1 file, built and read back: its crossings' exit nodes on a hub, its starts' nodes, a section a faction */
    private fun pack(map: Int, xs: List<X>, ss: List<S>): ShippedNet.Pack {
        val hub = at(map)
        val nodes = arrayListOf(SafePath.Node(1, hub[0], hub[1], SafePath.HUB, name = "the town"))
        val edges = ArrayList<SafePath.Edge>(); val exits = ArrayList<SafePath.Exit>()
        var id = 2
        for (x in xs) {
            nodes.add(SafePath.Node(id, x.p[0], x.p[1], SafePath.EXIT))
            edges.add(SafePath.Edge(1, id, SafePath.ROAD, if (x.avoid) SafePath.AVOID else SafePath.OK))
            exits.add(SafePath.Exit(id, x.to, x.how, x.name)); id++
        }
        for (s in ss) {
            nodes.add(SafePath.Node(id, s.p[0], s.p[1], SafePath.POINT, name = s.name))
            edges.add(SafePath.Edge(1, id, SafePath.TRAIL, SafePath.OK))
            exits.add(SafePath.Exit(id, s.to, s.kind, s.name)); id++
        }
        val net = SafePath.Net(map, cont(map), "off-device", nodes, edges, exits)
        val p = ShippedNet.Pack(ShippedNet.Header(map, cont(map), Climb.RULES_VERSION, "test", "build-test", "2026-10-19T00:00:00Z"),
            listOf(H, A).map { ShippedNet.section(net, it, 10, "t$map") })
        return ShippedNet.read(ShippedNet.bytes(p).inputStream())!!
    }
    /** the shipped zones for [faction]: every map with a crossing or a start, or the ones [only] names */
    private fun zones(faction: Int, starts: List<S> = emptyList(), only: Set<Int>? = null, plus: Set<Int> = emptySet()): List<ShippedCross.Zone> {
        val maps = (crossings.map { it.from } + starts.map { it.map } + plus).toSortedSet()
        return maps.filter { only == null || it in only }.map { m ->
            ShippedCross.Zone(m, pack(m, crossings.filter { it.from == m }, starts.filter { it.map == m }).section(faction)!!) }
    }

    // ---- the rides, flights, tram, portals and the gate's requirement link (TravelLinksTest's)
    private fun ride(path: Int, a: Int, pa: DoubleArray, b: Int, pb: DoubleArray, fa: Int, fb: Int, air: Boolean = false) =
        TransportRoutes.Ride(path, cont(a), floatArrayOf(pa[0].toFloat(), pa[1].toFloat(), 0f), cont(b), floatArrayOf(pb[0].toFloat(), pb[1].toFloat(), 0f),
            1200.0, fa, fb, air)
    private val dockDurotar = at(1411, 600.0, 600.0); private val dockTirisfal = at(1420, 400.0, 400.0)
    private val dockRut = at(-702, 100.0, 100.0); private val dockAuberdine = at(1439, 500.0, 100.0)
    private val dockMenethil = at(1437, 100.0, 100.0); private val dockSouthshore = at(1424, 100.0, 100.0)
    private val dockTheramore = at(1445, 100.0, 100.0); private val dockBooty = at(1434, 100.0, 100.0); private val dockRatchet = at(1413, 100.0, 100.0)
    private val rides = listOf(
        ride(302, 1411, dockDurotar, 1420, dockTirisfal, H, H, air = true),
        ride(293, -702, dockRut, 1439, dockAuberdine, A, A),
        ride(295, 1439, dockAuberdine, 1437, dockMenethil, A, A),
        ride(11616, 1445, dockTheramore, 1437, dockMenethil, A, A),
        ride(11167, 1437, dockMenethil, 1424, dockSouthshore, A, A),
        ride(11167, 1424, dockSouthshore, 1439, dockAuberdine, A, A),
        ride(2, 1434, dockBooty, 1413, dockRatchet, 3, 3),
    )
    private val fmStormwind = at(1453, 500.0, 900.0); private val fmSouthshore = at(1424, 500.0, 500.0); private val fmAlterac = at(1416, 100.0, 100.0)
    private val masters = listOf(
        TransportRoutes.FlightMaster(0, fmStormwind[0].toFloat(), fmStormwind[1].toFloat(), A, 2, "Stormwind, Elwynn"),
        TransportRoutes.FlightMaster(0, fmSouthshore[0].toFloat(), fmSouthshore[1].toFloat(), A, 14, "Southshore, Hillsbrad"),
        TransportRoutes.FlightMaster(0, fmAlterac[0].toFloat(), fmAlterac[1].toFloat(), A, 99, "Nowhere, Alterac"),
    )
    private val taxi = setOf(2, 14)
    private fun tableAt(m: Int, x: Double, y: Double): DoubleArray? = boxes[m]?.let { doubleArrayOf(it[1] + y * 1000, it[2] + x * 1000) }
    private val tramFar = at(1455, 300.0, 300.0)
    private fun tramNear() = tableAt(1453, CityExits.TABLE[0].x, CityExits.TABLE[0].y)!!
    private val learnedTram = mapOf(1453 to listOf(CityExits.Exit(1453, "", wx = tramNear()[0], wy = tramNear()[1], toMap = 1455,
        toWx = tramFar[0], toWy = tramFar[1], rideYd = CityExits.TRAM_YD, learned = true)))
    private val dalaran = at(1416, 200.0, 200.0)
    private val portals = listOf(
        TravelLinks.Portal(631299, "Skyborne Portal to Stormwind", 1416, dalaran[0], dalaran[1], "Stormwind", 1453),
        TravelLinks.Portal(910, "Portal to Rut'theran Village", 1457, at(1457, 500.0, 500.0)[0], at(1457, 500.0, 500.0)[1], "Rut'theran Village", -702),
        TravelLinks.Portal(909, "Portal to Darnassus", -702, at(-702, 800.0, 800.0)[0], at(-702, 800.0, 800.0)[1], "Darnassus", 1457),
    )
    private val sgGate = TravelLinks.Link(TravelLinks.Kind.LINK, 1432, at(1432, 900.0, 900.0)[0], at(1432, 900.0, 900.0)[1], 1427,
        at(1427, 100.0, 100.0)[0], at(1427, 100.0, 100.0)[1], 520.0, "Through the Searing Gorge gate", "SG-GATE", requires = "key-searing-gorge")

    /** the device's inputs with NO drafted nets: every walk must come from the shipped files */
    private fun base(learned: Map<Int, List<CityExits.Exit>> = learnedTram) = TravelLinks.Inputs(
        mapAt = ::mapAt, mapName = ::name, nets = emptyList(), learnedMaps = learned.keys, learned = { learned[it].orEmpty() },
        rides = rides, masters = masters, taxiKnown = { it in taxi }, taxiConnected = { a, b -> a != b && a in taxi && b in taxi },
        tableAt = ::tableAt, portals = portals, manual = listOf(sgGate, sgGate.back("Through the Searing Gorge gate back", "SG-GATE")))

    private val everyMap get() = boxes.keys.toSet()

    /** a plans.json case over the shipped inputs: [from] -> [to], the goal = the map [to] lies on; every map shipped unless [shipped] */
    private fun plan(faction: Int, from: DoubleArray, fromCont: Int, to: DoubleArray, toCont: Int, flights: Boolean = true,
                     has: Set<String> = emptySet(), starts: List<S> = emptyList(), learned: Map<Int, List<CityExits.Exit>> = learnedTram,
                     shipped: Set<Int> = everyMap, legs: ShippedCross.LegYards? = null): ShippedCross.Chain? {
        val zs = zones(faction, starts)
        val inp = ShippedCross.inputs(base(learned), zs)
        val sm = mapAt(fromCont, from[0], from[1])!!; val gm = mapAt(toCont, to[0], to[1])!!
        return ShippedCross.plan(inp, TravelLinks.Character(faction, has, flights), sm, from[0], from[1], gm, to[0], to[1], shipped, legs = legs)
    }
    private val ShippedCross.Chain.keys get() = route.keys

    // ================================================================ the nine plans.json cases, over NET1

    @Test fun dalaranToNorthshireTakesTheOneWayPortalAndSaysItsLandingIsNotKnown() {
        val c = plan(A, dalaran, 0, at(1429, 300.0, 300.0), 0)!!
        assertTrue(c.keys.toString(), "o631299" in c.keys)
        assertTrue(c.route.gaps.any { it.key == "o631299" })                                     // expect: gap
        assertTrue(c.route.describe().contains("Take the portal to Stormwind (one way); landing not yet known"))
    }

    @Test fun stormwindToDalaranIsFlownAndWalkedNeverBackThroughThePortal() {
        val c = plan(A, at(1453, 400.0, 400.0), 0, dalaran, 0)!!
        assertTrue(c.keys.toString(), "w:1424>1416" in c.keys)
        assertFalse("o631299" in c.keys)
        assertTrue(c.route.gaps.isEmpty())
        assertEquals(TravelLinks.Kind.FLIGHT, c.first!!.kind)
    }

    @Test fun orgrimmarGateToTheUndercityByZeppelin() {
        val c = plan(H, at(1411, 15.0, 500.0), 1, at(1458, 500.0, 500.0), 0, flights = false)!!
        assertTrue(c.keys.toString(), "r302" in c.keys)
        assertTrue(c.route.gaps.isEmpty())
    }

    @Test fun shadowglenToAuberdineDownTheTreeByItsPortalThenTheBoat() {
        val c = plan(A, at(1438, 500.0, 500.0), 1, at(1439, 600.0, 600.0), 1, flights = false)!!
        assertEquals(listOf("w:1438>1457", "o910", "r293"), c.keys)
        assertTrue(c.route.gaps.any { it.key == "o910" })
    }

    @Test fun ratchetToAuberdineForTheHordeNeverTakesAnAllianceBoat() {
        val c = plan(H, at(1413, 150.0, 150.0), 1, at(1439, 600.0, 600.0), 1, flights = false)!!
        for (k in listOf("r293", "r295", "r11616", "r11167")) assertFalse(k, k in c.keys)
        assertFalse(c.graph.links.any { it.key in setOf("r293", "r295", "r11616", "r11167") })
        assertTrue(c.graph.left.any { it.startsWith("r293") && it.contains("hostile") })
    }

    @Test fun auberdineToSouthshoreGoesByMenethilBecauseTheRoundBoatRunsOneWay() {
        val c = plan(A, at(1439, 600.0, 600.0), 1, at(1424, 600.0, 600.0), 0, flights = false)!!
        assertEquals(listOf("r295", "r11167"), c.keys)
        assertFalse(c.graph.links.any { it.key == "r11167" && it.fromMap == 1439 })
    }

    @Test fun thelsamarToSearingGorgeWithoutTheKeyFindsTheGateShut() {
        assertNull(plan(A, at(1432, 500.0, 500.0), 0, at(1427, 500.0, 500.0), 0, flights = false))
    }

    @Test fun thelsamarToSearingGorgeWithTheKeyGoesThroughTheGate() {
        val c = plan(A, at(1432, 500.0, 500.0), 0, at(1427, 500.0, 500.0), 0, flights = false, has = setOf("key-searing-gorge"))!!
        assertEquals(listOf("SG-GATE"), c.keys)
    }

    @Test fun northshireToTheIronforgeGateByTheTram() {
        val c = plan(A, at(1429, 300.0, 300.0), 0, at(1426, 500.0, 500.0), 0, flights = false)!!
        assertEquals(listOf("w:1429>1453", "tram:1453>1455", "w:1455>1426"), c.keys)
        assertTrue(c.route.gaps.isEmpty())
    }

    // ================================================================ the walks are the shipped files'

    @Test fun withNoShippedNetThereIsNoWalk() {
        val inp = ShippedCross.inputs(base(), emptyList())
        val g = TravelLinks.build(inp, TravelLinks.Character(A))
        assertFalse(g.links.any { it.kind == TravelLinks.Kind.WALK && it.key.startsWith("w:") })
        assertNull(ShippedCross.plan(inp, TravelLinks.Character(A, flights = false), 1429, at(1429)[0], at(1429)[1], 1426, at(1426)[0], at(1426)[1], everyMap))
    }

    @Test fun aShippedNetReplacesTheDeviceDraftOfItsMap() {
        // the device's draft of 1453 has a crossing into 1424 nobody shipped; the shipped net of 1453 wins
        val draft = SafePath.Net(1453, 0, "device", listOf(SafePath.Node(1, at(1453)[0], at(1453)[1], SafePath.EXIT)), emptyList(),
            listOf(SafePath.Exit(1, 1424, "walk", "")))
        val b = base(); val inp = ShippedCross.inputs(TravelLinks.Inputs(b.mapAt, b.mapName, listOf(draft)), zones(A))
        assertFalse(TravelLinks.build(inp, TravelLinks.Character(A)).links.any { it.key == "w:1453>1424" })
        // a map with no shipped net keeps its draft
        val draft2 = SafePath.Net(1445, 1, "device", listOf(SafePath.Node(1, at(1445)[0], at(1445)[1], SafePath.EXIT)), emptyList(),
            listOf(SafePath.Exit(1, 1413, "walk", "")))
        val inp2 = ShippedCross.inputs(TravelLinks.Inputs(b.mapAt, b.mapName, listOf(draft2)), zones(A))
        assertTrue(TravelLinks.build(inp2, TravelLinks.Character(A)).links.any { it.key == "w:1445>1413" })
    }

    @Test fun anAvoidedShippedCrossingCostsItsWayThrough() {
        val zs = listOf(ShippedCross.Zone(1429, pack(1429, listOf(X(1429, 1453, at(1429, 500.0, 990.0), "the gate", avoid = true)), emptyList()).section(A)!!))
        val g = TravelLinks.build(ShippedCross.inputs(TravelLinks.Inputs(::mapAt, ::name), zs), TravelLinks.Character(A))
        val l = g.links.first { it.fromMap == 1429 }
        assertTrue(l.avoid); assertEquals((TravelLinks.AVOID_K - 1) * TravelLinks.CROSS_YD, l.yd, 1e-9)
    }

    @Test fun theStepsNameTheShippedExit() {
        val zs = zones(H)
        val l = TravelLinks.build(ShippedCross.inputs(base(), zs), TravelLinks.Character(H)).links.first { it.key == "w:1411>1454" }
        assertEquals("the Orgrimmar gate", ShippedCross.exitName(zs, l))
        val back = TravelLinks.build(ShippedCross.inputs(base(), zs), TravelLinks.Character(H)).links.first { it.key == "w:1454>1411" }
        assertEquals("the Orgrimmar gate", ShippedCross.exitName(zs, back))
    }

    // ================================================================ the link starts

    private val updraft = S(1440, at(1440, 700.0, 700.0), "updraft", -900, "Rohashi's updraft")

    @Test fun anAirPlaceIsRoutedToItsLinkStartAndTheWayUpIsLearned() {
        val goal = at(-900, 500.0, 500.0)
        val c = plan(A, at(1439, 600.0, 600.0), 1, goal, 1, flights = false, starts = listOf(updraft))!!
        assertEquals(listOf("w:1439>1440", "s:1440:${startNode(1440)}"), c.keys)
        val up = c.route.steps.last()
        assertTrue(up.words, up.words.endsWith("; ${ShippedCross.LEARNED_UP}"))
        assertTrue(up.link.oneWay)
        assertTrue(c.route.gaps.isNotEmpty())
        // the pin of that hop: the start's own node
        assertEquals(updraft.p[0], up.link.ax, 0.11); assertEquals(updraft.p[1], up.link.ay, 0.11)
        // once taken (the map changed at the start and the player stood on the deck), the far end is known and the words say no more
        val landing = at(-900, 100.0, 100.0)
        val l = mapOf(1440 to listOf(CityExits.Exit(1440, "", wx = updraft.p[0] + 5, wy = updraft.p[1], toMap = -900, toWx = landing[0], toWy = landing[1],
            rideYd = CityExits.TRAM_YD, learned = true)))
        val c2 = plan(A, at(1439, 600.0, 600.0), 1, goal, 1, flights = false, starts = listOf(updraft), learned = l)!!
        val up2 = c2.route.steps.last { it.link.key.startsWith("s:") }
        assertFalse(up2.words.contains(ShippedCross.LEARNED_UP))
        assertEquals(landing[0], up2.link.bx, 1e-9)
    }
    private fun startNode(map: Int) = pack(map, crossings.filter { it.from == map }, listOf(updraft)).section(A)!!.starts.first { it.kind == "updraft" }.node

    @Test fun anAirPlaceStartIsNeverWalkedBackDown() {
        val c = plan(A, at(-900, 500.0, 500.0), 1, at(1439, 600.0, 600.0), 1, flights = false, starts = listOf(updraft))
        assertNull(c)
    }

    @Test fun aStartThatIsQuestieDbsPortalIsLeftToIt() {
        // the shipped start of Darnassus' portal (beside QuestieDB's object) adds no link of its own
        val s = S(1457, at(1457, 520.0, 500.0), "portal", -702, "Portal to Rut'theran Village")
        val inp = ShippedCross.inputs(base(), zones(A, listOf(s)))
        assertTrue(ShippedCross.startLinks(zones(A, listOf(s)), base()).isEmpty())
        assertEquals(1, TravelLinks.build(inp, TravelLinks.Character(A)).links.count { it.fromMap == 1457 && it.toMap == -702 })
        // one QuestieDB does not list: a one-way portal by the user's link-kind words
        val far = S(1445, at(1445, 900.0, 900.0), "portal", 1454, "Portal to Orgrimmar")
        val ls = ShippedCross.startLinks(zones(A, listOf(far)), base())
        assertEquals(1, ls.size)
        assertTrue(ls[0].words, ls[0].words.startsWith("Take the portal to Orgrimmar (one way)"))
        assertTrue(ls[0].oneWay)
        // and a Wind Bridge
        val wb = S(1445, at(1445, 900.0, 100.0), "wind bridge", 1413, "Wind Bridge")
        assertTrue(ShippedCross.startLinks(zones(A, listOf(wb)), base())[0].words.startsWith("Take the Wind Bridge to map 1413"))
    }

    @Test fun ridesStartsAreTheGamesOwnNotLinksOfTheirOwn() {
        val dock = S(1411, dockDurotar, "zeppelin", 1420, "the zeppelin tower")
        val fm = S(1411, at(1411, 300.0, 300.0), SafePath.FLIGHT, 0, "the flight master")
        assertTrue(ShippedCross.startLinks(zones(H, listOf(dock, fm)), base()).isEmpty())
    }

    // ================================================================ the coverage rule and the legs

    @Test fun aChainThroughAZoneWithNoShippedNetIsNotCovered() {
        val all = plan(A, at(1429, 300.0, 300.0), 0, at(1426, 500.0, 500.0), 0, flights = false)!!
        assertTrue(all.covered)
        assertEquals(listOf(1429, 1453, 1455, 1426), all.maps)
        // Stormwind has no spec (its walls are its WMOs): the chain is not the shipped one's to take
        val c = plan(A, at(1429, 300.0, 300.0), 0, at(1426, 500.0, 500.0), 0, flights = false, shipped = everyMap - 1453)!!
        assertFalse(c.covered)
        assertEquals(listOf(1453), c.missing)
    }

    @Test fun theLegsAreOnTheNetworkWhereItHasOne() {
        val asked = ArrayList<Int>()
        val legs = ShippedCross.LegYards { map, a, b, _ -> asked.add(map); if (map == 1453) null else Math.hypot(b[0] - a[0], b[1] - a[1]) * 1.2 }
        val from = at(1429, 300.0, 300.0); val goal = at(1426, 500.0, 500.0)
        val c = plan(A, from, 0, goal, 0, flights = false, legs = legs)!!
        assertEquals(4, c.legs.size)                                   // Elwynn to its gate, Stormwind to the tram, Ironforge to its gate, Dun Morogh to the goal
        assertEquals(listOf(1429, 1453, 1455, 1426), c.legs.map { it.map })
        assertTrue(c.legs[0].onNet); assertFalse(c.legs[1].onNet)
        val toGate = crossings.first { it.from == 1453 && it.to == 1429 }.p
        assertEquals(Math.hypot(toGate[0] - from[0], toGate[1] - from[1]) * 1.2, c.legs[0].yd, 1e-6)
        val steps = c.steps(::name)
        assertEquals("In map 1429: walk ${c.legs[0].yd.toInt()} yd on the network", steps[0])
        assertEquals("To map 1453", steps[1])
        assertTrue(steps.toString(), steps.any { it.startsWith("Take the Deeprun Tram to map 1455") })
        assertTrue(steps.last(), steps.last().endsWith("to the goal"))
        assertTrue(c.describe(::name).startsWith("1. In map 1429"))
    }

    @Test fun aLegFromAnUnknownLandingIsSaidSo() {
        val c = plan(A, dalaran, 0, at(1429, 300.0, 300.0), 0)!!
        val after = c.legs[c.route.steps.indexOfFirst { it.link.key == "o631299" } + 1]
        assertTrue(after.a[0].isNaN()); assertEquals(MultiHop.UNKNOWN_YD, after.yd, 1e-9)
        assertTrue(c.steps(::name).any { it.contains("walk on from where it lands") })
    }

    @Test fun theStartsMapAloneIsNoChain() {
        assertNull(plan(A, at(1429, 300.0, 300.0), 0, at(1429, 700.0, 700.0), 0))
        assertNotNull(plan(A, at(1429, 300.0, 300.0), 0, at(1453, 700.0, 700.0), 0))
    }
}
