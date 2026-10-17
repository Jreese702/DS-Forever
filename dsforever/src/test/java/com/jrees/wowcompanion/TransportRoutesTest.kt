package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/** b-route-transports: a transport path's stops are its docks; consecutive stops are rides; a flight (no stops) gives none */
class TransportRoutesTest {
    private fun n(path: Int, i: Int, flags: Int, x: Float, cont: Int = 0) = TransportRoutes.Node(path, i, cont, flags, if (flags and 2 != 0) 60 else 0, x, 0f, 0f)

    @Test fun stopsMakeRides() {
        val nodes = listOf(
            // a boat: stop, sail, stop, sail, stop (a loop back to the first dock)
            n(292, 0, 2, 100f), n(292, 1, 0, 200f), n(292, 2, 2, 300f), n(292, 3, 0, 200f), n(292, 4, 2, 100f),
            // a flight: no stops
            n(35, 0, 0, 1f), n(35, 1, 0, 2f), n(35, 2, 0, 3f),
            // a cross-continent boat: its second dock on continent 1
            n(241, 0, 2, 10f, cont = 0), n(241, 1, 1, 20f, cont = 0), n(241, 2, 2, 30f, cont = 1))
        val rides = TransportRoutes.rides(nodes)
        assertEquals(3, rides.size)
        assertEquals(listOf(100f, 300f), rides.filter { it.path == 292 }.map { it.a[0] })
        val cross = rides.single { it.path == 241 }
        assertEquals(0, cross.ca); assertEquals(1, cross.cb)
        assertEquals("transports: 11 path nodes, 3 paths, 2 transport paths, 3 rides (continent 0: 3)", TransportRoutes.summary(nodes, rides))
    }

    @Test fun aRideCostsItsSailAndItsWait() {
        // 100 yd, 100 yd sailed; a 60 s wait at the first dock (7 yd/s); the jump to another continent counts nothing
        val nodes = listOf(n(292, 0, 2, 100f), n(292, 1, 0, 200f), n(292, 2, 2, 300f),
            n(241, 0, 2, 10f), n(241, 1, 0, 20f), n(241, 2, 2, 5000f, cont = 1))
        val rides = TransportRoutes.rides(nodes)
        assertEquals(200.0 + 420.0, rides.single { it.path == 292 }.yd, 1e-6)
        assertEquals(10.0 + 420.0, rides.single { it.path == 241 }.yd, 1e-6)
        // the device file back
        val back = TransportRoutes.fromJson(TransportRoutes.toJson(nodes, rides))
        assertEquals(2, back.size); assertEquals(620.0, back.single { it.path == 292 }.yd, 1e-6)
        assertEquals(1, back.single { it.path == 241 }.cb)
        assertEquals(0, TransportRoutes.fromJson(null).size)
    }

    @Test fun hopsGoBothWaysAndCarryTheTailOverseas() {
        val home = TransportRoutes.Ride(1, 0, floatArrayOf(0f, 0f, 0f), 0, floatArrayOf(500f, 0f, 0f), 100.0)
        val sea = TransportRoutes.Ride(2, 1, floatArrayOf(0f, 0f, 0f), 0, floatArrayOf(-100f, 0f, 0f), 300.0)
        // on continent 0, the quest on continent 1 at (30, 40) from the far dock: home both ways (no tail), the boat back over (tail 50)
        val hops = TransportRoutes.hops(listOf(home, sea), 0, listOf(doubleArrayOf(1.0, 30.0, 40.0)))
        assertEquals(3, hops.size)
        val over = hops.single { it.ride.path == 2 }
        assertEquals(1, over.ride.cb); assertEquals(50.0, over.tailYd, 1e-6)
        // no spot over there: only the home rides
        assertEquals(2, TransportRoutes.hops(listOf(home, sea), 0, emptyList()).size)
    }
}

/** b-route-faction: a dock's town from the game's flight masters (the PC client's TaxiNodes, these exact rows) */
class RideFactionTest {
    private val masters = listOf(
        TransportRoutes.FlightMaster(0, -12414f, 146f, 1026),     // Grom'gol, Stranglethorn (Horde)
        TransportRoutes.FlightMaster(0, -3792f, -783f, 1025),     // Menethil Harbor, Wetlands (Alliance)
        TransportRoutes.FlightMaster(0, -14444f, 509f, 1026),     // Booty Bay (Horde's)
        TransportRoutes.FlightMaster(0, -14473f, 464f, 1025),     // Booty Bay (Alliance's)
        TransportRoutes.FlightMaster(1, -894f, -3773f, 1027),     // Ratchet (both)
        TransportRoutes.FlightMaster(0, -12418f, 235f, 1024))     // "Transport, Grom'gol - Orgrimmar": no faction, not counted

    @Test fun docksTakeTheirTownsFaction() {
        assertEquals(TransportRoutes.HORDE, TransportRoutes.townFaction(masters, 0, -12464f, 231f))       // Grom'gol's tower
        assertEquals(TransportRoutes.ALLIANCE, TransportRoutes.townFaction(masters, 0, -3709f, -575f))    // Menethil's dock
        assertEquals(3, TransportRoutes.townFaction(masters, 0, -14277f, 582f))                          // Booty Bay: both
        assertEquals(3, TransportRoutes.townFaction(masters, 1, -1005f, -3841f))                         // Ratchet: both
        assertEquals(0, TransportRoutes.townFaction(masters, 0, -8232f, -5800f))                         // nobody near: unknown
    }

    @Test fun aHostileTownSkipsTheRideBothWays() {
        // Grom'gol -> Undercity (both Horde): an Alliance character never; a Horde one yes
        val r = TransportRoutes.Ride(301, 0, floatArrayOf(-12406f, 211f, 0f), 0, floatArrayOf(2062f, 235f, 0f), 900.0, fa = 2, fb = 2)
        assertEquals(1, r.hostileDock(TransportRoutes.ALLIANCE)); assertEquals(1, r.back().hostileDock(TransportRoutes.ALLIANCE))
        assertEquals(0, r.hostileDock(TransportRoutes.HORDE))
        // Ratchet (both) -> Booty Bay (both): anyone
        val b = TransportRoutes.Ride(241, 1, floatArrayOf(-1005f, -3841f, 0f), 0, floatArrayOf(-14277f, 582f, 0f), 900.0, fa = 3, fb = 3)
        assertEquals(0, b.hostileDock(TransportRoutes.ALLIANCE)); assertEquals(0, b.hostileDock(TransportRoutes.HORDE))
        // Menethil (Alliance) -> an unknown dock: a Horde character no (the near dock), the way back no (the far one)
        val m = TransportRoutes.Ride(11167, 0, floatArrayOf(-3709f, -575f, 0f), 0, floatArrayOf(-1102f, -554f, 0f), 900.0, fa = 1, fb = 0)
        assertEquals(1, m.hostileDock(TransportRoutes.HORDE)); assertEquals(2, m.back().hostileDock(TransportRoutes.HORDE))
        // the faction unknown: no rule
        assertEquals(0, r.hostileDock(TransportRoutes.factionOf(null)))
        // the device file keeps the towns
        val back = TransportRoutes.fromJson(TransportRoutes.toJson(emptyList(), listOf(r)))
        assertEquals(2, back[0].fa); assertEquals(2, back[0].fb)
    }
}
