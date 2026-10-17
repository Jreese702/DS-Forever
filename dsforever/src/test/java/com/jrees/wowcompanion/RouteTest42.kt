package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * test42 on the Thor, the WCMAP lines verbatim (the user: "It's saying to fly to hillsbrad but I'm standing outside stormwind"):
 *   route: quest 98021 on map 1429 (came from 1453): chose Ride to Hillsbrad Foothills (18757 yd) over Ride to Wetlands (18780 yd);
 *          straight 1561 yd
 *   learned a way out of map 1415 (Eastern Kingdoms) to 1453 (Stormwind City) on foot
 *   route: quest 98021 on map 1453 (came from 1415): chose To Elwynn Forest (1801 yd) over To Eastern Kingdoms (1997 yd); straight 1689
 *   route: quest 93963 on map 1453 (came from 1415): chose Deeprun Tram (1015 yd) over To Eastern Kingdoms (4318 yd); straight 4304
 * A map of 1000 x 1000 yd, the player at its middle, the quest [straight] yd east; a way's yards set by its ride.
 */
class RouteTest42 {
    private fun far(straight: Double) = doubleArrayOf(0.5 + straight / 1000.0, 0.5)
    /** a way at the player's feet whose ride makes its whole cost [cost] (its far end at the quest) */
    private fun ride(name: String, cost: Double, f: DoubleArray) =
        CityExits.Way(CityExits.Exit(1429, name, toMap = 1, rideYd = cost), 0.5, 0.5, f[0], f[1])
    @Test fun inElwynnARideNeverBeatsTheQuestStraightAhead() {
        val f = far(1561.0)
        val ch = CityExits.choose(0.5, 0.5, f, listOf(ride("Ride to Hillsbrad Foothills", 18757.0, f), ride("Ride to Wetlands", 18780.0, f)),
            1000.0, 1000.0, cameFrom = 1453, inCity = false)
        assertNull(ch.way)
        assertEquals(1561.0, ch.direct, 0.5)
        // ... so the route heads for the quest (the edge toward it here; the game's waypoint when it gives one)
        val r = CursorRoute.plan(0.5, 0.5, 1000.0, 1000.0, emptyList(), f, null, null, emptyList(),
            ways = listOf(ride("Ride to Hillsbrad Foothills", 18757.0, f)), inCity = false, wayName = { it.name }, cameFrom = 1453) { _, _ -> false }!!
        assertEquals("edge", r.key)
    }

    @Test fun inStormwindTheTramStillWins() {
        val f = far(4304.0)
        val tram = CityExits.Way(CityExits.Exit(1453, "Deeprun Tram", toMap = 1455, rideYd = 1015.0), 0.5, 0.5, f[0], f[1])
        assertEquals("Deeprun Tram", CityExits.choose(0.5, 0.5, f, listOf(tram), 1000.0, 1000.0, inCity = true).way!!.exit.name)
    }

    @Test fun noWaysLearnedFromOrIntoAContinent() {
        val kept = HashMap<String, String>()
        val l = CityExits.Learner(object : CityExits.Learner.Store {
            override fun get(k: String) = kept[k]
            override fun put(k: String, v: String?) { if (v == null) kept.remove(k) else kept[k] = v }
        })
        // what test42 learned: 1415 (the continent) -> Stormwind, and Stormwind -> Elwynn; plus an old Stormwind -> 1415 way
        kept["x:1415"] = "1453,-8885.0,576.0,,"
        kept["x:1453"] = "1429,-9076.0,422.0,,;1415,-8900.0,500.0,,"
        val notZone = { id: Int -> id in setOf(946, 947, 1414, 1415) }
        assertEquals(2, l.purge(listOf(1415, 1453), notZone))
        assertTrue(l.of(1415).isEmpty())
        assertEquals(listOf(1429), l.of(1453).map { it.toMap })             // "To Eastern Kingdoms" is gone, "To Elwynn Forest" stays
        assertEquals(0, l.purge(listOf(1415, 1453), notZone))               // once is enough
    }
}

/** test42 bug 2: a dock's zone is the one it lies deepest inside, not the smallest box holding it */
class DockZoneTest {
    @Test fun aCoastDockIsNamedByTheZoneItIsIn() {
        val m = WorldMapModel()
        // Auberdine's dock (6406, 823) on Kalimdor: inside Darkshore's box near its middle, and at the corner of Felwood's smaller box
        fun idx(id: Int, name: String, t: Double, b: Double, l: Double, r: Double) = m.addIndex(org.json.JSONObject()
            .put("uiMapID", id).put("name", name).put("mapType", 3).put("rect", org.json.JSONArray().put(1).put(t).put(b).put(l).put(r)))
        idx(1439, "Darkshore", 7500.0, 5000.0, 2000.0, -500.0)
        idx(1448, "Felwood", 6500.0, 4800.0, 900.0, -900.0)
        assertEquals("Darkshore", m.zoneAt(1, 6406.0, 823.0)?.name)
    }
}
