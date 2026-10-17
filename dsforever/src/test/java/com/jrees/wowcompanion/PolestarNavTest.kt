package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** b-route-polestar: the taps of Map.lua's row "pnav" and the route read back */
class PolestarNavTest {
    @Test fun goKeepCancel() {
        val n = PolestarNav()
        // map 1429 = 1, 3, 25; x 0.5 = 648 = 18, 0; y 0.25 = 324 = 9, 0
        assertEquals(listOf(36, 1, 3, 25, 18, 0, 9, 0), n.tick(doubleArrayOf(1429.0, 0.5, 0.25), 0))
        assertEquals(emptyList<Int>(), n.tick(doubleArrayOf(1429.0, 0.501, 0.25), 1_000))
        assertEquals(listOf(37), n.tick(doubleArrayOf(1429.0, 0.5, 0.25), 20_000))
        // another map: a new GO
        assertEquals(36, n.tick(doubleArrayOf(1453.0, 0.5, 0.25), 25_000).first())
        assertEquals(listOf(39), n.tick(null, 26_000))
        assertEquals(emptyList<Int>(), n.tick(null, 27_000))
    }

    @Test fun aRefusalWaitsAndSaysWhy() {
        val n = PolestarNav()
        n.tick(doubleArrayOf(12.0, 0.5, 0.5), 0)
        n.onAnswer("go", false, "no Polestar", false, 0)
        assertEquals("Polestar is not running", n.why)
        assertEquals(emptyList<Int>(), n.tick(doubleArrayOf(12.0, 0.5, 0.5), 30_000))
        assertEquals(36, n.tick(doubleArrayOf(12.0, 0.5, 0.5), 61_000).first())
        n.onAnswer("go", true, null, true, 61_000)
        assertNull(n.why)
    }

    @Test fun theRouteReadsBackWhileGuiding() {
        val n = PolestarNav()
        val doc = JSONObject("""{"type":"pnavroute","legs":[{"k":"walk","c":0,"p":[-9000,400,-9010,410]},{"k":"walk","p":[1,2]}]}""")
        n.onRoute(doc, 0)
        assertNull(n.liveRoute(0))                        // not guiding: not ours
        n.tick(doubleArrayOf(12.0, 0.5, 0.5), 0)
        n.onRoute(doc, 1_000)
        val r = n.liveRoute(2_000)!!
        assertEquals(1, r.size); assertEquals(0, r[0].cont); assertEquals(-9010.0, r[0].pts[2], 1e-9)
        assertNull(n.liveRoute(1_000 + PolestarNav.STALE_MS + 1))
    }
}
