package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #578 ("in undercity ... took me like 20 mins to find the exit and the default quest plugin was making me run in loops"): the route
 * knew no way out of the Undercity (CityExits.TABLE had only the Stormwind tram, a way out is learned only after walking out once), so
 * it fell back to the map's edge toward the quest - under the ground, a ring with no way up. Now the measured elevators are a way out,
 * never ruled out as "back where the player came from", a capital's gate walked through teaches the way back as well, and the world
 * chain (TravelLinks / MultiHop) takes the elevators both ways.
 */
class UndercityExitsTest {
    private val uc = CityExits.TABLE.first { it.map == CityExits.UNDERCITY }
    // a synthetic Undercity frame: 600 x 600 yd, its top-left corner (world x top, y left) put so the elevators fall at (0.6, 0.15)
    private val top = 1631.0 + 0.15 * 600; private val left = 224.0 + 0.6 * 600
    private fun here(wx: Double, wy: Double) = doubleArrayOf((left - wy) / 600, (top - wx) / 600)

    @Test fun theElevatorsArePlacedOnTheMapFromTheirWorldYards() {
        val at = CityExits.tableAt(uc) { wx, wy -> here(wx, wy) }!!
        assertEquals(0.6, at[0], 1e-9); assertEquals(0.15, at[1], 1e-9)
        // a 0..1 entry keeps its own place
        val tram = CityExits.tableAt(CityExits.TABLE[0]) { _, _ -> error("not asked") }!!
        assertEquals(0.690, tram[0], 1e-9)
    }

    @Test fun underTheGroundTheElevatorsAreNeverRuledOutAsBack() {
        assertEquals(0, CityExits.backRule(1458, 1420, goalThere = false))
        assertEquals(1429, CityExits.backRule(1453, 1429, goalThere = false))     // Stormwind keeps test40's rule
        assertEquals(0, CityExits.backRule(1453, 1429, goalThere = true))
        val at = CityExits.tableAt(uc) { wx, wy -> here(wx, wy) }!!
        val ways = listOf(CityExits.Way(uc, at[0], at[1], at[0], at[1]))
        // a quest south of the player, past the map (the Undercity's ring runs on under it): the elevators (north) bring it no nearer,
        // so as a way "back" into Tirisfal they would be dropped - and the edge would run the player in loops
        val far = doubleArrayOf(0.5, 1.8)
        val dropped = CityExits.choose(0.5, 0.5, far, ways, 600.0, 600.0, cameFrom = 1420, inCity = true)
        assertEquals(null, dropped.way)
        val r = CursorRoute.plan(0.5, 0.5, 600.0, 600.0, emptyList(), far, null, null, emptyList(), ways = ways, inCity = true,
            wayName = { it.name }, cameFrom = CityExits.backRule(1458, 1420, false)) { _, _ -> false }!!
        assertEquals(CursorRoute.Kind.EXIT, r.kind); assertEquals(CityExits.UNDERCITY_LIFTS, r.label)
        assertEquals(0.6, r.x, 1e-9); assertEquals(0.15, r.y, 1e-9)
    }

    private class Mem : CityExits.Learner.Store {
        val m = HashMap<String, String>()
        override fun get(k: String) = m[k]
        override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v }
    }

    @Test fun walkingIntoACapitalTeachesTheWayBackOut() {
        val l = CityExits.Learner(Mem())
        l.onDoc(1420, 0, 1633.0, 226.0, false, 0L)                       // the Ruins of Lordaeron, at the elevators' top
        val e = l.onDoc(1458, 0, 1631.0, 224.0, false, 20_000L)          // riding down: the map turns to the Undercity
        assertNotNull(e); assertEquals(1420, e!!.map)
        val out = l.of(1458)
        assertEquals(1, out.size); assertEquals(1420, out[0].toMap); assertTrue(!out[0].transport)
        assertEquals(1631.0, out[0].wx, 1e-9)
        // a border between two zones teaches one way only (a drop down a cliff is no way back up)
        l.onDoc(1429, 0, -9500.0, -1300.0, false, 200_000L)
        l.onDoc(1433, 0, -9510.0, -1310.0, false, 201_000L)
        assertEquals(1, l.of(1429).size); assertTrue(l.of(1433).isEmpty())
    }

    @Test fun walkingOutOfTheUndercityTeachesTheWayBackIn() {
        val l = CityExits.Learner(Mem())
        l.onDoc(1458, 0, 1631.0, 224.0, false, 0L)                        // the Undercity, on the elevator riding up
        l.onDoc(1420, 0, 1633.0, 226.0, false, 15_000L)                   // the map turns to Tirisfal at the top
        assertEquals(listOf(1420), l.of(1458).map { it.toMap })
        assertEquals(listOf(1458), l.of(1420).map { it.toMap }); assertEquals(1633.0, l.of(1420)[0].wx, 1e-9)
    }

    @Test fun aDropOffThunderBluffIsNoWayUpIntoIt() {
        // review 11-22: a jump off a Thunder Bluff mesa turns the map to Mulgore a few yards (2-D) from the edge; the cliff's foot
        // is no way up. The way down is kept (it was before #578), never the reverse at the landing
        val l = CityExits.Learner(Mem())
        l.onDoc(1456, 0, -1290.0, 120.0, false, 0L)                       // Thunder Bluff, at a mesa's edge
        l.onDoc(1412, 0, -1296.0, 126.0, false, 3_000L)                   // landed in Mulgore below
        assertEquals(listOf(1412), l.of(1456).map { it.toMap })
        assertTrue(l.of(1412).isEmpty())
        // a city gate on the ground, the same: one way only, the one walked (Stormwind's gate into Elwynn)
        l.onDoc(1453, 0, -9000.0, 500.0, false, 100_000L)
        l.onDoc(1429, 0, -9010.0, 505.0, false, 101_000L)
        assertEquals(listOf(1429), l.of(1453).map { it.toMap }); assertTrue(l.of(1429).isEmpty())
        assertTrue(CityExits.backToo(1420, 1458)); assertTrue(CityExits.backToo(1458, 1420))
        assertFalse(CityExits.backToo(1456, 1412)); assertFalse(CityExits.backToo(1412, 1456)); assertFalse(CityExits.backToo(1453, 1429))
    }

    @Test fun theWorldChainTakesTheElevatorsUpAndDown() {
        val names = mapOf(1458 to "Undercity", 1420 to "Tirisfal Glades")
        val inp = TravelLinks.Inputs(mapAt = { _, _, _ -> null }, mapName = { names[it] ?: "map $it" })
        val g = TravelLinks.build(inp, TravelLinks.Character(TransportRoutes.HORDE))
        val up = g.links.first { it.key == "t:1458>1420" }
        assertEquals(TravelLinks.Kind.WALK, up.kind); assertEquals("Take the elevators up to Tirisfal Glades", up.words)
        assertTrue(g.links.any { it.key == "t:1420>1458" && it.fromMap == 1420 && it.toMap == 1458 })
        // from the Undercity's Trade Quarter to Brill: up the elevators first
        val p = TravelLinks.plan(g, 1458, 1580.0, 240.0, 2260.0, 290.0, { m, _, _ -> m == 1420 })!!
        assertEquals("t:1458>1420", p.steps.first().link.key)
        // and back down into the Undercity from Brill
        val d = TravelLinks.plan(g, 1420, 2260.0, 290.0, 1580.0, 240.0, { m, _, _ -> m == 1458 })!!
        assertEquals("t:1420>1458", d.steps.first().link.key)
    }
}
