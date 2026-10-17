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
}
