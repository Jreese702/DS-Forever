package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * SafePath device step 11: TravelLinks + the world MultiHop. The nine checks of the harness's cross/plans.json (PlanCross), each over a
 * SMALL SYNTHETIC WORLD built here: the maps are 1000 yd boxes at made-up places (the uiMap ids are kept only so the cases read like
 * plans.json's), the docks, gates, masters and portals are put in them by hand. None of these positions are the game's. Then the rules:
 * a hostile dock refused, a one-way portal never reversed, the unknown landing (and learning it), the hop cap, a round boat one way,
 * an avoided crossing's cost, flights known AND connected only, requirements, QuestieDB's portal objects.
 */
class TravelLinksTest {
    // ---- the synthetic world: map -> (continent, its box's corner); each box 1000 x 1000 yd
    private val boxes = LinkedHashMap<Int, DoubleArray>()
    private fun box(map: Int, cont: Int) { val i = boxes.size; boxes[map] = doubleArrayOf(cont.toDouble(), (i % 8) * 2000.0, (i / 8) * 2000.0) }
    init {
        // continent 0
        for (m in listOf(1416, 1424, 1453, 1429, 1455, 1426, 1432, 1427, 1437, 1420, 1458, 1434)) box(m, 0)
        // continent 1 (-702: Rut'theran, a place of its own)
        for (m in listOf(1411, 1454, 1413, 1438, 1457, -702, 1439, 1440, 1445)) box(m, 1)
    }
    private fun mapAt(c: Int, x: Double, y: Double): Int? = boxes.entries.firstOrNull { (_, b) ->
        b[0].toInt() == c && x >= b[1] && x <= b[1] + 1000 && y >= b[2] && y <= b[2] + 1000 }?.key
    /** a spot in [map]'s box, [dx] / [dy] yards from its corner */
    private fun at(map: Int, dx: Double = 500.0, dy: Double = 500.0) = doubleArrayOf(boxes[map]!![1] + dx, boxes[map]!![2] + dy)
    private fun cont(map: Int) = boxes[map]!![0].toInt()
    private fun name(m: Int) = "map $m"

    private val A = TransportRoutes.ALLIANCE; private val H = TransportRoutes.HORDE

    // ---- the walks: one zone net per crossing (an exit node on [a]'s side into [b]); [avoid]: its way marked avoid
    private fun crossing(a: Int, b: Int, p: DoubleArray, avoid: Boolean = false, how: String = "gate") = SafePath.Net(a, cont(a), "t",
        listOf(SafePath.Node(1, p[0], p[1], SafePath.EXIT), SafePath.Node(2, p[0] + 50, p[1], SafePath.HUB)),
        listOf(SafePath.Edge(1, 2, SafePath.ROAD, if (avoid) SafePath.AVOID else SafePath.DRAFT)),
        listOf(SafePath.Exit(1, b, how, "to $b")))
    private val walks = listOf(
        crossing(1453, 1429, at(1453, 990.0, 500.0)),       // the Stormwind gate
        crossing(1457, 1438, at(1457, 10.0, 500.0)),        // the Darnassus gate
        crossing(1411, 1413, at(1411, 990.0, 500.0)),       // the Southfury bridge
        crossing(1420, 1458, at(1420, 500.0, 990.0)),       // the Undercity entrance
        crossing(1426, 1455, at(1426, 500.0, 10.0)),        // the Gates of Ironforge
        crossing(1416, 1424, at(1416, 900.0, 900.0)),       // Alterac's road to Hillsbrad
        crossing(1411, 1454, at(1411, 10.0, 500.0)),        // the Orgrimmar gate
        crossing(1413, 1440, at(1413, 500.0, 990.0)),       // the Barrens into Ashenvale
        crossing(1440, 1439, at(1440, 10.0, 500.0)),        // Ashenvale into Darkshore
    )

    // ---- the rides (fa / fb: the docks' towns); 11167 a round of three stops (Menethil -> Southshore -> Auberdine)
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

    // ---- flights (the Alliance's): Stormwind and Southshore, shown connected; an unknown one in Alterac
    private val fmStormwind = at(1453, 500.0, 900.0); private val fmSouthshore = at(1424, 500.0, 500.0); private val fmAlterac = at(1416, 100.0, 100.0)
    private val masters = listOf(
        TransportRoutes.FlightMaster(0, fmStormwind[0].toFloat(), fmStormwind[1].toFloat(), A, 2, "Stormwind, Elwynn"),
        TransportRoutes.FlightMaster(0, fmSouthshore[0].toFloat(), fmSouthshore[1].toFloat(), A, 14, "Southshore, Hillsbrad"),
        TransportRoutes.FlightMaster(0, fmAlterac[0].toFloat(), fmAlterac[1].toFloat(), A, 99, "Nowhere, Alterac"),
    )
    private val net = setOf(2, 14)

    // ---- the tram (CityExits.TABLE's own entry, put on the synthetic Stormwind box) and the portals
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

    private fun inputs(learned: Map<Int, List<CityExits.Exit>> = learnedTram) = TravelLinks.Inputs(
        mapAt = ::mapAt, mapName = ::name, nets = walks, learnedMaps = learned.keys, learned = { learned[it].orEmpty() },
        rides = rides, masters = masters, taxiKnown = { it in net }, taxiConnected = { a, b -> a != b && a in net && b in net },
        tableAt = ::tableAt, portals = portals, manual = listOf(sgGate, sgGate.back("Through the Searing Gorge gate back", "SG-GATE")))

    /** a plans.json case: [from] -> [to] (world spots), its goal = the map [to] lies on */
    private fun plan(faction: Int, from: DoubleArray, fromCont: Int, to: DoubleArray, toCont: Int, flights: Boolean = true,
                     has: Set<String> = emptySet(), inp: TravelLinks.Inputs = inputs()): Pair<TravelLinks.Route?, TravelLinks.Graph> {
        val g = TravelLinks.build(inp, TravelLinks.Character(faction, has, flights))
        val sm = mapAt(fromCont, from[0], from[1])!!; val gm = mapAt(toCont, to[0], to[1])!!
        return TravelLinks.plan(g, sm, from[0], from[1], to[0], to[1], { m, _, _ -> m == gm }) to g
    }

    // ================================================================ the nine plans.json cases

    @Test fun dalaranToNorthshireTakesTheOneWayPortalAndSaysItsLandingIsNotKnown() {
        val (p, _) = plan(A, dalaran, 0, at(1429, 300.0, 300.0), 0)
        assertNotNull(p); p!!
        assertTrue(p.keys.toString(), "o631299" in p.keys)
        assertTrue("gap", p.gaps.any { it.key == "o631299" })
        assertTrue(p.describe(), p.describe().contains("Take the portal to Stormwind (one way); landing not yet known"))
    }

    @Test fun stormwindToDalaranIsFlownAndWalkedNeverBackThroughThePortal() {
        val (p, _) = plan(A, at(1453, 400.0, 400.0), 0, dalaran, 0)
        assertNotNull(p); p!!
        assertTrue(p.keys.toString(), "w:1424>1416" in p.keys)
        assertFalse("o631299" in p.keys)
        assertTrue(p.gaps.isEmpty())
        assertEquals(TravelLinks.Kind.FLIGHT, p.steps[0].link.kind)
    }

    @Test fun orgrimmarGateToTheUndercityByZeppelin() {
        val (p, _) = plan(H, at(1411, 15.0, 500.0), 1, at(1458, 500.0, 500.0), 0, flights = false)
        assertNotNull(p); p!!
        assertTrue(p.keys.toString(), "r302" in p.keys)
        assertTrue(p.gaps.isEmpty())
        assertTrue(p.describe(), p.describe().contains("Board the zeppelin to map 1420"))
    }

    @Test fun shadowglenToAuberdineDownTheTreeByItsPortalThenTheBoat() {
        val (p, _) = plan(A, at(1438, 500.0, 500.0), 1, at(1439, 600.0, 600.0), 1, flights = false)
        assertNotNull(p); p!!
        assertEquals(listOf("w:1438>1457", "o910", "r293"), p.keys)
        assertTrue(p.gaps.any { it.key == "o910" })
    }

    @Test fun ratchetToAuberdineForTheHordeNeverTakesAnAllianceBoat() {
        val (p, g) = plan(H, at(1413, 150.0, 150.0), 1, at(1439, 600.0, 600.0), 1, flights = false)
        assertNotNull(p); p!!
        for (k in listOf("r293", "r295", "r11616", "r11167")) assertFalse(k, k in p.keys)
        assertFalse(g.links.any { it.key in setOf("r293", "r295", "r11616", "r11167") })
        assertTrue(g.left.toString(), g.left.any { it.startsWith("r293") && it.contains("hostile") })
    }

    @Test fun auberdineToSouthshoreGoesByMenethilBecauseTheRoundBoatRunsOneWay() {
        val (p, g) = plan(A, at(1439, 600.0, 600.0), 1, at(1424, 600.0, 600.0), 0, flights = false)
        assertNotNull(p); p!!
        assertEquals(listOf("r295", "r11167"), p.keys)
        // no leg of the round from Auberdine to Southshore, nor Southshore back to Menethil
        assertFalse(g.links.any { it.key == "r11167" && it.fromMap == 1439 })
        assertFalse(g.links.any { it.key == "r11167" && it.fromMap == 1424 && it.toMap == 1437 })
    }

    @Test fun thelsamarToSearingGorgeWithoutTheKeyFindsTheGateShut() {
        val (p, g) = plan(A, at(1432, 500.0, 500.0), 0, at(1427, 500.0, 500.0), 0, flights = false)
        assertNull(p)
        assertFalse(g.links.any { it.key == "SG-GATE" })
        assertTrue(g.left.toString(), g.left.contains("SG-GATE (needs key-searing-gorge)"))
    }

    @Test fun thelsamarToSearingGorgeWithTheKeyGoesThroughTheGate() {
        val (p, _) = plan(A, at(1432, 500.0, 500.0), 0, at(1427, 500.0, 500.0), 0, flights = false, has = setOf("key-searing-gorge"))
        assertNotNull(p); p!!
        assertEquals(listOf("SG-GATE"), p.keys)
    }

    @Test fun northshireToTheIronforgeGateByTheTram() {
        val (p, _) = plan(A, at(1429, 300.0, 300.0), 0, at(1426, 500.0, 500.0), 0, flights = false)
        assertNotNull(p); p!!
        assertEquals(listOf("w:1429>1453", "tram:1453>1455", "w:1455>1426"), p.keys)
        assertTrue(p.gaps.isEmpty())
        assertTrue(p.describe(), p.describe().contains("Take the Deeprun Tram to map 1455"))
    }

    // ================================================================ the rules

    @Test fun aHostileDockIsNeverBoarded() {
        val only = listOf(ride(7, 1411, dockDurotar, 1420, dockTirisfal, A, 0))        // its near dock an Alliance town
        val inp = TravelLinks.Inputs(mapAt = ::mapAt, rides = only)
        val goal = at(1420, 500.0, 500.0)
        assertNull(plan(H, at(1411, 500.0, 500.0), 1, goal, 0, inp = inp).first)
        val (p, _) = plan(A, at(1411, 500.0, 500.0), 1, goal, 0, inp = inp)
        assertEquals(listOf("r7"), p!!.keys)
        // the far dock hostile: the ride back is refused too
        val g = TravelLinks.build(inp, TravelLinks.Character(H))
        assertTrue(g.links.isEmpty())
        assertEquals(2, g.left.size)
    }

    @Test fun aHostileRideLearnedIsStillNotTaken() {
        val only = listOf(ride(7, 1411, dockDurotar, 1420, dockTirisfal, A, A))
        val l = mapOf(1411 to listOf(CityExits.Exit(1411, "", wx = dockDurotar[0], wy = dockDurotar[1], toMap = 1420,
            toWx = dockTirisfal[0], toWy = dockTirisfal[1], rideYd = CityExits.TRAM_YD, learned = true)))
        val inp = TravelLinks.Inputs(mapAt = ::mapAt, rides = only, learnedMaps = l.keys, learned = { l[it].orEmpty() })
        assertTrue(TravelLinks.build(inp, TravelLinks.Character(H)).links.isEmpty())
    }

    @Test fun aOneWayPortalIsNeverReversed() {
        val inp = TravelLinks.Inputs(mapAt = ::mapAt, portals = portals.take(1))
        val g = TravelLinks.build(inp, TravelLinks.Character(A))
        assertEquals(1, g.links.size)
        assertTrue(g.links[0].oneWay)
        assertFalse(g.links.any { it.fromMap == 1453 })
        assertNull(plan(A, at(1453, 500.0, 500.0), 0, dalaran, 0, inp = inp).first)
        assertNotNull(plan(A, dalaran, 0, at(1453, 500.0, 500.0), 0, inp = inp).first)
    }

    @Test fun theUnknownLandingCostsUnknownYdThenIsLearned() {
        val inp = TravelLinks.Inputs(mapAt = ::mapAt, mapName = ::name, portals = portals.take(1))
        val goal = at(1453, 600.0, 600.0)
        val (p, _) = plan(A, dalaran, 0, goal, 0, inp = inp)
        p!!
        assertEquals(MultiHop.UNKNOWN_YD, p.cost, 1e-6)                  // standing at the portal: only the unknown landing's walk
        assertTrue(p.steps[0].words.endsWith("; ${TravelLinks.NOT_KNOWN}"))
        assertTrue(p.gaps.isNotEmpty())
        // the first trip: the map changes at the portal and the player stands in Stormwind (CityExits.Learner)
        val store = HashMap<String, String>()
        val learner = CityExits.Learner(object : CityExits.Learner.Store {
            override fun get(k: String) = store[k]
            override fun put(k: String, v: String?) { if (v == null) store.remove(k) else store[k] = v }
        })
        val landing = at(1453, 100.0, 900.0)
        learner.onDoc(1416, 0, dalaran[0] + 3, dalaran[1], false, 0L)
        assertNotNull(learner.onDoc(1453, 0, landing[0], landing[1], false, 4_000L))
        val inp2 = TravelLinks.Inputs(mapAt = ::mapAt, mapName = ::name, portals = portals.take(1), learnedMaps = listOf(1416), learned = learner::of)
        val (q, g2) = plan(A, dalaran, 0, goal, 0, inp = inp2)
        q!!
        assertTrue(q.gaps.isEmpty())
        assertEquals("Take the portal to Stormwind (one way)", q.steps[0].words)
        assertEquals(landing[0], q.steps[0].link.bx, 0.01)
        assertEquals(1, g2.links.size)                                  // the learned ride is the portal's, not a link of its own
        assertEquals(Math.hypot(goal[0] - landing[0], goal[1] - landing[1]), q.cost, 0.01)          // from the portal itself (the learned ride only gave the landing)
    }

    @Test fun theTramsFarEndWhileNotLearnedIsAGap() {
        val (p, _) = plan(A, at(1429, 300.0, 300.0), 0, at(1426, 500.0, 500.0), 0, flights = false, inp = inputs(learned = emptyMap()))
        p!!
        assertTrue(p.keys.toString(), "tram:1453>1455" in p.keys)
        assertTrue(p.gaps.any { it.key == "tram:1453>1455" })
        assertTrue(p.describe().contains("its far end not yet known"))
    }

    @Test fun theHopCapIsEight() {
        // ten maps in a row, each crossing into the next
        val chain = (1..10).map { 5000 + it }
        val local = LinkedHashMap<Int, DoubleArray>()
        chain.forEachIndexed { i, m -> local[m] = doubleArrayOf(i * 1000.0, 0.0) }
        val nets = chain.zipWithNext().map { (a, b) -> SafePath.Net(a, 0, "t", listOf(SafePath.Node(1, local[b]!![0], 0.0, SafePath.EXIT)), emptyList(),
            listOf(SafePath.Exit(1, b, "walk", ""))) }
        val g = TravelLinks.build(TravelLinks.Inputs(mapAt = { _, _, _ -> null }, nets = nets), TravelLinks.Character(A))
        fun to(goal: Int) = TravelLinks.plan(g, chain[0], 10.0, 0.0, local[goal]!![0] + 10, 0.0, { m, _, _ -> m == goal })
        assertEquals(8, to(chain[8])!!.steps.size)                      // eight hops: found
        assertNull(to(chain[9]))                                       // nine: past the cap
        assertEquals(9, TravelLinks.plan(g, chain[0], 10.0, 0.0, 0.0, 0.0, { m, _, _ -> m == chain[9] }, maxHops = 9)!!.steps.size)
        assertEquals(8, TravelLinks.MAX_HOPS)
    }

    @Test fun anAvoidedCrossingCostsSixTimesItsStretch() {
        val a = 1429; val b = 1453
        val nets = listOf(crossing(a, b, at(a, 500.0, 500.0), avoid = true), crossing(a, b, at(a, 500.0, 900.0)))
        val g = TravelLinks.build(TravelLinks.Inputs(mapAt = ::mapAt, mapName = ::name, nets = nets), TravelLinks.Character(A))
        val av = g.links.first { it.avoid && it.fromMap == a }
        assertEquals((TravelLinks.AVOID_K - 1) * TravelLinks.CROSS_YD, av.yd, 1e-9)
        assertTrue(av.words.contains("dangerous"))
        // from beside the avoided one, the kept one 400 yd off still wins (the avoided way through costs 750)
        val s = at(a, 500.0, 480.0)
        val p = TravelLinks.plan(g, a, s[0], s[1], s[0], s[1] + 400, { m, _, _ -> m == b })!!
        assertFalse(p.steps[0].link.avoid)
        // both ways
        assertTrue(g.links.any { it.fromMap == b && it.toMap == a && it.avoid })
    }

    @Test fun flightsOnlyBetweenNodesKnownAndConnected() {
        val inp = TravelLinks.Inputs(mapAt = ::mapAt, masters = masters, taxiKnown = { it == 2 || it == 14 || it == 99 },
            taxiConnected = { x, y -> x != y && x in net && y in net })
        val g = TravelLinks.build(inp, TravelLinks.Character(A))
        assertEquals(setOf("f2>14", "f14>2"), g.links.map { it.key }.toSet())
        // the Horde knows none of the Alliance's masters
        assertTrue(TravelLinks.build(inp, TravelLinks.Character(H)).links.isEmpty())
        // known but never shown connected: no flight
        val g2 = TravelLinks.build(TravelLinks.Inputs(mapAt = ::mapAt, masters = masters, taxiKnown = { true }), TravelLinks.Character(A))
        assertTrue(g2.links.isEmpty())
        // without flights
        assertTrue(TravelLinks.build(inp, TravelLinks.Character(A, flights = false)).links.isEmpty())
    }

    @Test fun aSingleCrossingRideRunsBothWays() {
        val g = TravelLinks.build(TravelLinks.Inputs(mapAt = ::mapAt, rides = rides), TravelLinks.Character(A))
        val r295 = g.links.filter { it.key == "r295" }
        assertEquals(setOf(1439 to 1437, 1437 to 1439), r295.map { it.fromMap to it.toMap }.toSet())
        assertTrue(r295.none { it.oneWay })
        assertTrue(g.links.filter { it.key == "r11167" }.all { it.oneWay })
    }

    @Test fun aFactionLinkIsOnlyItsSides() {
        val l = TravelLinks.Link(TravelLinks.Kind.LINK, 1429, 0.0, 0.0, 1453, 0.0, 0.0, 10.0, "x", "X", faction = A)
        assertEquals(1, TravelLinks.build(TravelLinks.Inputs(mapAt = ::mapAt, manual = listOf(l)), TravelLinks.Character(A)).links.size)
        assertTrue(TravelLinks.build(TravelLinks.Inputs(mapAt = ::mapAt, manual = listOf(l)), TravelLinks.Character(H)).links.isEmpty())
    }

    @Test fun aPortalWhoseDestinationIsNotKnownIsLeftOutUntilLearned() {
        val p = TravelLinks.Portal(5, "Portal to Somewhere", 1416, dalaran[0], dalaran[1], "Somewhere", null)
        val g = TravelLinks.build(TravelLinks.Inputs(mapAt = ::mapAt, portals = listOf(p)), TravelLinks.Character(A))
        assertTrue(g.links.isEmpty()); assertTrue(g.left[0].startsWith("o5"))
        val l = mapOf(1416 to listOf(CityExits.Exit(1416, "", wx = dalaran[0], wy = dalaran[1], toMap = 1453, toWx = 1.0, toWy = 2.0,
            rideYd = CityExits.TRAM_YD, learned = true)))
        val g2 = TravelLinks.build(TravelLinks.Inputs(mapAt = ::mapAt, portals = listOf(p), learnedMaps = l.keys, learned = { l[it].orEmpty() }),
            TravelLinks.Character(A))
        assertEquals(1453, g2.links.single().toMap)
    }

    @Test fun placeWordsFindTheirMap() {
        val names = mapOf(1453 to "Stormwind City", 1457 to "Darnassus", 1416 to "Alterac Mountains", 9 to "The Violet Citadel")
        assertEquals(1453, TravelLinks.placeMap("Stormwind", names))
        assertEquals(1457, TravelLinks.placeMap("darnassus", names))
        assertEquals(9, TravelLinks.placeMap("Violet Citadel", names))
        assertNull(TravelLinks.placeMap("Valanaar", names))
    }

    // ================================================================ QuestieDB's portal objects

    @Test fun portalKindsByName() {
        assertEquals("Stormwind", QuestieObjects.portalPlace("Skyborne Portal to Stormwind"))
        assertEquals("Rohashi Spires", QuestieObjects.portalPlace("Portal To Rohashi Spires"))
        assertEquals("The Violet Citadel", QuestieObjects.portalPlace("Portal to The Violet Citadel"))
        assertNull(QuestieObjects.portalPlace("Demon Portal"))
        assertNull(QuestieObjects.portalPlace("Naxxramas Portal"))
        assertNull(QuestieObjects.portalPlace("World Tree Portal"))
    }

    @Test fun portalsReadFromATocLikeQuestieDbs() {
        // a toc in QuestieDB's own form (made-up ids, area and spots): a portal with a spawn, one without, and a chest
        fun line(id: Int, f: String, v: Any) = "## X-Object-$id-$f: " + java.util.Base64.getEncoder().encodeToString(cbor(v))
        val toc = listOf(
            "## Interface: 11507",
            line(1001, "S", mapOf(1 to "Skyborne Portal to Stormwind", 5 to 77)),
            line(1001, "4", mapOf(77 to listOf(listOf(25.5, 40.0)))),
            line(1002, "S", mapOf(1 to "Skyborne Portal to Dalaran", 5 to 88)),
            line(1003, "S", mapOf(1 to "Locked Chest", 5 to 77)),
            line(1003, "4", mapOf(77 to listOf(listOf(1.0, 1.0)))),
            "## X-Npc-5-S: AAAA",
        ).joinToString("\n")
        val objs = QuestieObjects.read(toc.byteInputStream()) { QuestieObjects.portalPlace(it) != null }
        assertEquals(listOf(1001, 1002), objs.map { it.id })
        assertEquals(77, objs[0].zone)
        assertEquals(0.255, objs[0].spawns[77]!![0][0], 1e-9)
        val ps = QuestieObjects.portals(objs, { a -> if (a == 77) 1416 else null }, { m, x, y -> boxes[m]?.let { doubleArrayOf(it[1] + y * 1000, it[2] + x * 1000) } },
            { TravelLinks.placeMap(it, mapOf(1453 to "Stormwind City")) })
        assertEquals(1, ps.size)                                         // the one with no spawn gives no portal
        assertEquals(1416, ps[0].fromMap); assertEquals(1453, ps[0].toMap); assertEquals("Stormwind", ps[0].toPlace)
        assertEquals(boxes[1416]!![1] + 400.0, ps[0].x, 1e-6)
    }

    /** a small CBOR writer (what QuestieDB writes: maps, arrays, ints, text, doubles) */
    private fun cbor(v: Any): ByteArray {
        val o = ByteArrayOutputStream()
        fun head(major: Int, n: Long) {
            when {
                n < 24 -> o.write((major shl 5) or n.toInt())
                n < 256 -> { o.write((major shl 5) or 24); o.write(n.toInt()) }
                else -> { o.write((major shl 5) or 25); o.write((n shr 8).toInt() and 0xFF); o.write(n.toInt() and 0xFF) }
            }
        }
        fun w(x: Any?) {
            when (x) {
                is Int -> head(0, x.toLong())
                is String -> { val b = x.toByteArray(); head(3, b.size.toLong()); o.write(b) }
                is Double -> { o.write(0xFB); val l = java.lang.Double.doubleToLongBits(x); for (i in 7 downTo 0) o.write((l shr (8 * i)).toInt() and 0xFF) }
                is List<*> -> { head(4, x.size.toLong()); x.forEach { w(it) } }
                is Map<*, *> -> { head(5, x.size.toLong()); for ((k, vv) in x) { w(k); w(vv) } }
                else -> error("cbor: $x")
            }
        }
        w(v)
        return o.toByteArray()
    }
}
