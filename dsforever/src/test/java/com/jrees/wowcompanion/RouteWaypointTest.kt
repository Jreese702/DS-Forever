package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-route-waypoint: the game's pin on the route's next way, moved, kept alive and cleared */
class RouteWaypointTest {
    @Test fun setKeepMoveClear() {
        val w = RouteWaypoint()
        val tram = doubleArrayOf(0.690, 0.310)
        assertEquals(listOf(36, 24, 30, 11, 5), w.tick(tram, 0L))                // 894 = 24*36+30, 401 = 11*36+5
        assertTrue(w.tick(tram, 1_000L).isEmpty())                               // unchanged: nothing
        assertTrue(w.tick(doubleArrayOf(0.70, 0.31), 1_500L).isEmpty())          // moved, but within the 2 s pace
        assertEquals(36, w.tick(doubleArrayOf(0.70, 0.31), 2_500L).first())      // moved: a new pin
        assertEquals(listOf(37), w.tick(doubleArrayOf(0.70, 0.31), 22_500L))      // unchanged for 20 s: the keep-alive
        assertEquals(listOf(39), w.tick(null, 23_000L))                           // direct / unfocused / arrived: cleared
        assertTrue(w.tick(null, 24_000L).isEmpty())
    }

    @Test fun aRefusedPinWaitsAMinute() {
        val w = RouteWaypoint()
        w.tick(doubleArrayOf(0.5, 0.5), 0L)
        w.onAnswer("set", false, 100L)                                            // the player's own pin
        assertTrue(w.tick(doubleArrayOf(0.5, 0.5), 30_000L).isEmpty())
        assertEquals(36, w.tick(doubleArrayOf(0.5, 0.5), 61_000L).first())
    }

    @Test fun digitsRoundTrip() {
        for (v in listOf(0.0, 0.31, 0.69, 1.0)) {
            val d = RouteWaypoint.digits(v)
            assertEquals(v, (d[0] * 36 + d[1]) / 1295.0, 0.001)
        }
    }
}
