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
