package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-route-waypoint: the game's pin on the route's next way, moved, kept alive and cleared */
class RouteWaypointTest {
    @Test fun setKeepMoveClear() {
        val w = RouteWaypoint()
        val tram = doubleArrayOf(0.690, 0.310)
        assertEquals(listOf(36, 24, 30, 11, 5, 35, 35), w.tick(tram, 0L))      // 894 = 24*36+30, 401 = 11*36+5; z unknown
        assertTrue(w.tick(tram, 1_000L).isEmpty())                               // unchanged: nothing
        assertTrue(w.tick(doubleArrayOf(0.70, 0.31), 1_500L).isEmpty())          // moved, but within the 5 s pace (test48: it churned)
        assertEquals(36, w.tick(doubleArrayOf(0.70, 0.31), 5_500L).first())      // moved: a new pin
        assertEquals(listOf(37), w.tick(doubleArrayOf(0.70, 0.31), 25_500L))      // unchanged for 20 s: the keep-alive
        assertTrue(w.tick(null, 26_000L).isEmpty())                               // none for a moment: kept (test48)
        assertTrue(w.tick(doubleArrayOf(0.70, 0.31), 28_000L).isEmpty())         // back: nothing to tap
        assertTrue(w.tick(null, 29_000L).isEmpty())
        assertEquals(listOf(39), w.tick(null, 37_500L))                           // none for 8 s: cleared
        assertTrue(w.tick(null, 38_000L).isEmpty())
    }

    @Test fun aRefusedPinWaitsAMinute() {
        val w = RouteWaypoint()
        w.tick(doubleArrayOf(0.5, 0.5), 0L)
        w.onAnswer("set", false, 100L)                                            // the player's own pin
        assertTrue(w.tick(doubleArrayOf(0.5, 0.5), 30_000L).isEmpty())
        assertEquals(36, w.tick(doubleArrayOf(0.5, 0.5), 61_000L).first())
    }

    @Test fun aFurtherFlagsHeightIsTwoDigitsAgainstThePlayers() {
        // Interact's parse: dz = (n - 648) x 0.25, 1295 unknown
        assertEquals(listOf(18, 0), RouteWaypoint.zDigits(0.0))                       // 648 = level with the player
        assertEquals(listOf(19, 4), RouteWaypoint.zDigits(10.0))                     // 688
        assertEquals(listOf(17, 28), RouteWaypoint.zDigits(-2.0))                    // 640
        assertEquals(listOf(35, 34), RouteWaypoint.zDigits(500.0))                   // clamped to 1294
        assertEquals(listOf(0, 0), RouteWaypoint.zDigits(-500.0))
        assertEquals(listOf(35, 35), RouteWaypoint.zDigits(Double.NaN))              // unknown
        val w = RouteWaypoint()
        val cols = w.tick(doubleArrayOf(0.5, 0.5, -2.0), 0L, listOf(doubleArrayOf(0.6, 0.6, 10.0)))
        assertEquals("the SET carries the pin's own height (cp20)", listOf(36, 18, 0, 18, 0, 17, 28), cols.take(7))
        assertEquals(listOf(38, 21, 21, 21, 21, 19, 4), cols.drop(7))
    }

    @Test fun furtherCheckpointsRideTheSetAndFollowANewLeg() {
        val w = RouteWaypoint()
        val pin = doubleArrayOf(0.690, 0.310)
        val a = doubleArrayOf(900 / 1295.0, 432 / 1295.0); val b = doubleArrayOf(936 / 1295.0, 468 / 1295.0); val c = doubleArrayOf(0.8, 0.4)
        // cp19: the SET, then each further flag as 38 + 4 digits (only the first two)
        // (no height given: z 1295 = unknown, 35 35)
        assertEquals(listOf(36, 24, 30, 11, 5, 35, 35, 38, 25, 0, 12, 0, 35, 35, 38, 26, 0, 13, 0, 35, 35), w.tick(pin, 0L, listOf(a, b, c)))
        assertTrue(w.tick(pin, 1_000L, listOf(a, b, c)).isEmpty())                // unchanged: nothing
        assertTrue(w.tick(pin, 2_000L, listOf(b)).isEmpty())                     // the leg changed, within the 5 s pace
        assertEquals(listOf(38, 38, 38, 26, 0, 13, 0, 35, 35), w.tick(pin, 5_000L, listOf(b)))   // dropped and sent again
        assertEquals(listOf(38, 38), w.tick(pin, 10_000L, emptyList()))           // none after the pin now
        assertTrue(w.tick(pin, 16_000L, emptyList()).isEmpty())
        assertEquals(listOf(37), w.tick(pin, 30_000L, emptyList()))               // the keep-alive as ever
    }

    @Test fun routingWithNoPinKeepsTheAddonsWordAlive() {
        val w = RouteWaypoint()
        // ag5: a direct route (no pin): the KEEP at once, then every 20 s; the route gone: one CLEAR
        assertEquals(listOf(37), w.tick(null, 0L, routing = true))
        assertTrue(w.tick(null, 10_000L, routing = true).isEmpty())
        assertEquals(listOf(37), w.tick(null, 20_000L, routing = true))
        assertEquals(listOf(39), w.tick(null, 21_000L, routing = false))
        assertTrue(w.tick(null, 50_000L, routing = false).isEmpty())
        // a pin, then the route turns direct: after the grace the pin's CLEAR and the KEEP right behind it
        assertEquals(36, w.tick(doubleArrayOf(0.5, 0.5), 60_000L, routing = true).first())
        assertTrue(w.tick(null, 61_000L, routing = true).isEmpty())
        assertEquals(listOf(39, 37), w.tick(null, 69_500L, routing = true))
        assertTrue(w.tick(null, 70_000L, routing = true).isEmpty())
    }

    @Test fun digitsRoundTrip() {
        for (v in listOf(0.0, 0.31, 0.69, 1.0)) {
            val d = RouteWaypoint.digits(v)
            assertEquals(v, (d[0] * 36 + d[1]) / 1295.0, 0.001)
        }
    }
}
