package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-travel-layer: the docks, the tram, the flight masters and known flights as a scene; the harvest's names and boat-or-air */
class TravelTest {
    // made-up places (no game data here): two Alliance towns with a boat between them, a Horde tower with a zeppelin, three masters
    private val masters = listOf(
        TransportRoutes.FlightMaster(0, 100f, 100f, 1025, 1, "Harbor Town, Coastland"),
        TransportRoutes.FlightMaster(0, 2000f, 100f, 1025, 2, "Port Village, Isleland"),
        TransportRoutes.FlightMaster(0, 5000f, 5000f, 1026, 3, "Tower Hold, Hordeland"),
        TransportRoutes.FlightMaster(0, 3000f, 3000f, 1025, 4, "Hill Camp, Midland"),
        TransportRoutes.FlightMaster(0, 120f, 90f, 1024, 5, "Transport, Harbor Ships"),
        TransportRoutes.FlightMaster(0, 9000f, 9000f, 1026, 6, "zzOLDCamp, Nowhere"),
    )
    private fun node(path: Int, i: Int, x: Float, y: Float, z: Float, stop: Boolean) = TransportRoutes.Node(path, i, 0, if (stop) 2 else 0, 30, x, y, z)

    @Test fun boatOrAirFromThePathsHeightAndDocksNamedByTheirTown() {
        val nodes = listOf(node(1, 0, 110f, 110f, 0f, true), node(1, 1, 1000f, 100f, 0.5f, false), node(1, 2, 1990f, 110f, 0f, true),
            node(2, 0, 4990f, 4990f, 60f, true), node(2, 1, 4000f, 4000f, 140f, false), node(2, 2, 3010f, 2990f, 70f, true))
        val rides = TransportRoutes.withFactions(TransportRoutes.rides(nodes), masters)
        val boat = rides.first { it.path == 1 }; val zep = rides.first { it.path == 2 }
        assertFalse(boat.air); assertTrue(zep.air)
        assertEquals("Harbor Town", boat.na); assertEquals("Port Village", boat.nb)
        assertEquals("Tower Hold", zep.na)
        // read back through the device file
        val o = JSONObject(TransportRoutes.toJson(nodes, rides, masters, listOf(intArrayOf(1, 2), intArrayOf(1, 5), intArrayOf(2, 4))).toString())
        val back = TransportRoutes.fromJson(o)
        assertTrue(back.first { it.path == 2 }.air); assertEquals("Port Village", back.first { it.path == 1 }.nb)
        // only the real masters go (not the table's transport point or a retired row); routes only between them
        assertEquals(setOf(1, 2, 3, 4), TransportRoutes.mastersFromJson(o).map { it.id }.toSet())
        assertEquals(2, TransportRoutes.flightsFromJson(o).size)
        // nothing named near: no name (the app names the zone)
        assertNull(TransportRoutes.dockName(masters, 0, 7000f, 7000f))
    }

    @Test fun theSceneForAnAllianceCharacter() {
        val rides = listOf(
            TransportRoutes.Ride(1, 0, floatArrayOf(110f, 110f, 0f), 0, floatArrayOf(1990f, 110f, 0f), 2000.0, 1, 1, false, "Harbor Town", "Port Village"),
            TransportRoutes.Ride(2, 0, floatArrayOf(4990f, 4990f, 60f), 0, floatArrayOf(3010f, 2990f, 70f), 3000.0, 2, 1, true, "Tower Hold", "Hill Camp"),
            TransportRoutes.Ride(3, 0, floatArrayOf(130f, 120f, 0f), 1, floatArrayOf(10f, 10f, 0f), 4000.0, 1, 1, false, "Harbor Town", "Far Shore"),
        )
        val known = setOf(1, 2)
        val s = Travel.scene(rides, masters.filter { it.real }, listOf(intArrayOf(1, 2), intArrayOf(2, 4)), TransportRoutes.ALLIANCE,
            { it in known }, { a, b -> a in known && b in known }, doubleArrayOf(0.0, 500.0, 500.0, 900.0, 900.0)) { _, _, _ -> "Zone" }
        // Harbor Town's two rides from one pier: one dock, both destinations
        val harbor = s.points.filter { it.kind == Travel.Kind.BOAT && it.name == "Harbor Town" }
        assertEquals(1, harbor.size); assertEquals("Harbor Town: boats to Port Village, Far Shore", harbor[0].text)
        // the Horde tower's zeppelin: dimmed, said hostile; its line dim
        val tower = s.points.first { it.kind == Travel.Kind.AIR && it.name == "Tower Hold" }
        assertFalse(tower.lit); assertTrue(tower.text.endsWith("(a hostile town)"))
        assertFalse(s.lines.first { it.kind == Travel.Kind.AIR }.lit)
        // the crossing to another continent has no line here; the same-continent boat has one, once
        assertEquals(1, s.lines.count { it.kind == Travel.Kind.BOAT })
        // the masters: the Horde one left out, known lit, unknown dim; the known flight only (2 -> 4: 4 unknown)
        val fm = s.points.filter { it.kind == Travel.Kind.FLIGHT }
        assertEquals(setOf("Harbor Town", "Port Village", "Hill Camp"), fm.map { it.name }.toSet())
        assertTrue(fm.first { it.name == "Harbor Town" }.lit); assertFalse(fm.first { it.name == "Hill Camp" }.lit)
        assertEquals("Flight master, Harbor Town: flights to Port Village", fm.first { it.name == "Harbor Town" }.text)
        assertEquals(1, s.lines.count { it.kind == Travel.Kind.FLIGHT })
        // the tram: its two ends and a line
        assertEquals(2, s.points.count { it.kind == Travel.Kind.TRAM }); assertEquals(1, s.lines.count { it.kind == Travel.Kind.TRAM })
        // a tap near a point finds it
        assertNotNull(s.near(0, 115.0, 112.0, 30.0)); assertNull(s.near(0, 700.0, 700.0, 30.0))
    }
}
