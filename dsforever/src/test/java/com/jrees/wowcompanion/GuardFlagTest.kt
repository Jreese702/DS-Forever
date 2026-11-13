package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** gp1, the guard's flag (the user, 2026-11-13: "when asking a guard for directions, it does not put the arrow and flag to what I am
 *  looking for on our map"): Map.lua's map.gpoi parsed, placed, cut and arrived at */
class GuardFlagTest {
    private fun doc(s: String) = JSONObject(s)

    @Test fun parsesTheFlagOnThePlayersMap() {
        val f = GuardFlag.parse(doc("""{"mapID":1453,"gpoi":{"m":1453,"x":0.61,"y":0.7,"n":"Stormwind Bank","id":31,"tex":5,
            "tc":[0.3125,0.375,0,0.0625]}}"""))!!
        assertEquals("Stormwind Bank", f.name); assertEquals(31, f.id); assertEquals(5, f.tex)
        assertArrayEquals(doubleArrayOf(0.61, 0.7), f.on(1453), 1e-9)
        assertNull(f.on(1429))                                                   // another map: not known there
        assertArrayEquals(floatArrayOf(0.3125f, 0.375f, 0f, 0.0625f), f.cut(), 1e-6f)   // the game's own cut
    }

    @Test fun noFlagOrABrokenOneIsNone() {
        assertNull(GuardFlag.parse(doc("""{"mapID":1453}""")))
        assertNull(GuardFlag.parse(doc("""{"mapID":1453,"gpoi":{"m":1453,"x":0.5}}""")))
        assertNull(GuardFlag.parse(doc("""{"mapID":1453,"gpoi":{"x":0.5,"y":0.5}}""")))
        assertNull(GuardFlag.parse(null))
    }

    @Test fun aParentsFlagIsCarriedOntoThePlayersMapOnly() {
        val f = GuardFlag.parse(doc("""{"mapID":1453,"gpoi":{"m":1415,"x":0.45,"y":0.75,"hx":0.5,"hy":0.52,"id":7,"tex":13}}"""))!!
        assertArrayEquals(doubleArrayOf(0.45, 0.75), f.on(1415), 1e-9)          // the Map room's continent
        assertArrayEquals(doubleArrayOf(0.5, 0.52), f.on(1453), 1e-9)           // Home's map: the carried copy
        assertNull(f.on(1429))                                                   // walked into another zone before the next snapshot
    }

    @Test fun noCutFromTheGameMeansTheGamesGrid() {
        val f = GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.1,"y":0.2,"tex":15}}"""))!!
        val w = 16f / 256f
        assertArrayEquals(floatArrayOf(w, 2 * w, w, 2 * w), f.cut(), 1e-6f)    // 15 = row 1, column 1 of 14
        assertNull(GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.1,"y":0.2}}"""))!!.cut())          // no index: no picture
        val bad = GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.1,"y":0.2,"tex":0,"tc":[0.5,0.1,0,1]}}"""))!!
        assertArrayEquals(GuardFlag.gridCut(0), bad.cut(), 1e-6f)              // a nonsense cut: the grid
    }

    @Test fun theKeyIsTheFlagNotTheSnapshot() {
        val a = GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.5,"y":0.5,"id":3}}"""))!!
        val b = GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.50001,"y":0.5,"id":3,"n":"Bank"}}"""))!!
        val c = GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.6,"y":0.5,"id":3}}"""))!!
        assertEquals(a.key, b.key)
        assertNotEquals(a.key, c.key)                                            // a new direction
    }

    @Test fun theArrowGoesOnArrivalAndComesBackForANewFlag() {
        val w = 1000.0; val h = 1000.0                                           // a 1000 yd map: 0.01 = 10 yd
        val arr = GuardFlag.Arrival()
        val bank = GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.5,"y":0.5,"id":3}}"""))!!
        assertTrue(arr.wants(bank, 0.2, 0.2, 0.5, 0.5, w, h))
        assertTrue(arr.wants(bank, 0.5, 0.52, 0.5, 0.5, w, h))                  // 20 yd: still
        assertFalse(arr.wants(bank, 0.5, 0.505, 0.5, 0.5, w, h))                // 5 yd: arrived
        assertFalse(arr.wants(bank, 0.2, 0.2, 0.5, 0.5, w, h))                  // walked away again: stays away for this flag
        val inn = GuardFlag.parse(doc("""{"mapID":1,"gpoi":{"m":1,"x":0.3,"y":0.3,"id":4}}"""))!!
        assertTrue(arr.wants(inn, 0.2, 0.2, 0.3, 0.3, w, h))                    // a new direction: the arrow again
        assertFalse(arr.wants(null, 0.2, 0.2, 0.3, 0.3, w, h))                  // the game cleared it
        assertTrue(arr.wants(bank, 0.2, 0.2, 0.5, 0.5, w, h))                   // asked again after a clear: the arrow again
        assertTrue(arr.wants(bank, Double.NaN, Double.NaN, 0.5, 0.5, w, h))     // no position: it keeps pointing
        assertTrue(arr.wants(bank, 0.5, 0.505, 0.5, 0.5, 0.0, 0.0))             // no map size: no arrival can be told
    }
}
