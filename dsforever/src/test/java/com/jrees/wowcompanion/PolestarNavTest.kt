package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- pn4 (the user, 2026-11-11: "Okay now I actually do have a polestar objective that is not in my bag and not telling me where to
    // go", then: Polestar's own arrow hidden, DS Forever's compass leads)

    @Test fun theHeldRowIsSaidAfterTheGoNeverInsideIt() {
        val n = PolestarNav()
        // the device's GO: map 2521 = 1, 34, 1; x 0.5398 -> 699 = 19, 15; y 0.3892 -> 504 = 14, 0; then 38, 8 (the row holds)
        assertEquals(listOf(36, 1, 34, 1, 19, 15, 14, 0, 38, 8), n.tick(doubleArrayOf(2521.0, 0.5398, 0.3892), 0, ps = true))
        assertTrue(n.psOn)
        assertEquals(emptyList<Int>(), n.tick(doubleArrayOf(2521.0, 0.5398, 0.3892), 1_000, ps = true))
        // re-said with the KEEP, well inside Map.lua's 60 s
        assertEquals(listOf(37, 38, 8), n.tick(doubleArrayOf(2521.0, 0.5398, 0.3892), 20_000, ps = true))
        // released (the row unpicked, another quest focused): a GO for it, then 38, 9 once
        assertEquals(listOf(36, 1, 34, 1, 0, 0, 0, 0, 38, 9), n.tick(doubleArrayOf(2521.0, 0.0, 0.0), 21_000, ps = false))
        assertEquals(emptyList<Int>(), n.tick(doubleArrayOf(2521.0, 0.0, 0.0), 22_000, ps = false))
    }

    @Test fun neverSaidWhenPolestarIsNotTheTracker() {
        val n = PolestarNav()
        assertEquals(emptyList<Int>(), n.tick(null, 0, ps = false))
        assertEquals(emptyList<Int>(), n.tick(null, 60_000, ps = false))
    }

    @Test fun aHeldStepWithNoSpotStillSaysTheRowHolds() {
        val n = PolestarNav()
        // a step with no waypoint: no GO, but the add-on hears the row holds (its compass then follows Polestar when a spot comes)
        assertEquals(listOf(38, 8), n.tick(null, 0, ps = true))
        assertEquals(emptyList<Int>(), n.tick(null, 1_000, ps = true))
    }

    @Test fun aRefusedGoStillSaysTheRowHolds() {
        val n = PolestarNav()
        n.tick(doubleArrayOf(12.0, 0.5, 0.5), 0, ps = true)
        n.onAnswer("go", false, "refused", true, 0)
        assertEquals(listOf(38, 8), n.tick(doubleArrayOf(12.0, 0.5, 0.5), 20_000, ps = true))   // (no GO while refused)
    }

    @Test fun theHeldRowsRouteIsKeptWhenPolestarGuidesItsStepItself() {
        // Polestar dropped our GO at a step change (its Nav.lua stepMoved) and guides its own step; the add-on (pn4) still reads the
        // route while the row holds - the app keeps it even with no GO of its own in force (a no-quest accept step: Home's small map)
        val n = PolestarNav()
        val doc = JSONObject("""{"type":"pnavroute","legs":[{"k":"walk","c":0,"p":[-9000,400,-9010,410]}]}""")
        n.tick(null, 0, ps = true)
        n.onRoute(doc, 1_000)
        assertNotNull(n.liveRoute(2_000))
        assertNotNull(n.liveRouteFrom(2_000, -9000.0, 400.0, 0))
        // released: gone
        n.tick(null, 3_000, ps = false)
        assertNull(n.liveRoute(3_000))
        n.onRoute(doc, 4_000)
        assertNull(n.liveRoute(4_000))
    }

    @Test fun theGoalMarkForANoQuestStep() {
        val accept = PolestarStep("The Strange Hermit", "Accept The Strange Hermit", "accept", 0, 2521, 0.5398, 0.3892)
        // held, its spot on the map shown, no quest focused through it: marked
        assertArrayEquals(doubleArrayOf(0.5398, 0.3892), PolestarNav.goalMark(true, accept, 2521, 0)!!, 1e-9)
        // its quest not in the log (q set, focusThrough 0): still marked
        val notInLog = PolestarStep("The Strange Hermit", "Accept", "accept", 94000, 2521, 0.5, 0.5)
        assertNotNull(PolestarNav.goalMark(true, notInLog, 2521, 0))
        // not held (the row unpicked: the focused quest as before), another map, a quest focused through it, no spot: none
        assertNull(PolestarNav.goalMark(false, accept, 2521, 0))
        assertNull(PolestarNav.goalMark(true, accept, 1429, 0))
        assertNull(PolestarNav.goalMark(true, accept, 2521, 77))
        assertNull(PolestarNav.goalMark(true, PolestarStep("Talk", "Talk to Kandris", "talk", 77, 0, Double.NaN, Double.NaN), 2521, 0))
        assertNull(PolestarNav.goalMark(true, null, 2521, 0))
    }

    /** ps4 (the review of pn4; the user, 2026-11-11: "a polestar objective that is not in my bag and not telling me where to go"): a held
     *  step on ANOTHER map of the shown map's continent is marked through its world spot and the shown map's world rect (as the route's
     *  legs are); another continent, no rect, or an older add-on with no world spot: none */
    @Test fun theGoalMarkForAStepOnAnotherMap() {
        val r = PoiStore.Rect(0, 1000.0, 0.0, 500.0, -500.0, fromClient = true)
        val there = PolestarStep("Go to Elwynn", "Go to Goldshire", "goto", 0, 1429, 0.42, 0.65, wx = 750.0, wy = 0.0, cont = 0)
        assertArrayEquals(doubleArrayOf(0.5, 0.25), PolestarNav.goalMark(true, there, 1436, 0, r)!!, 1e-9)
        // off the shown map's field (north of it): still given, the small map pins it at the rim on the way there
        val north = PolestarStep("Go", "Go", "goto", 0, 1429, 0.4, 0.4, wx = 1500.0, wy = 250.0, cont = 0)
        assertArrayEquals(doubleArrayOf(0.25, -0.5), PolestarNav.goalMark(true, north, 1436, 0, r)!!, 1e-9)
        assertNull("another continent", PolestarNav.goalMark(true,
            PolestarStep("Go", "Go", "goto", 0, 1429, 0.4, 0.4, wx = 750.0, wy = 0.0, cont = 1), 1436, 0, r))
        assertNull("no rect", PolestarNav.goalMark(true, there, 1436, 0, null))
        assertNull("an older add-on: no world spot", PolestarNav.goalMark(true,
            PolestarStep("Go", "Go", "goto", 0, 1429, 0.4, 0.4), 1436, 0, r))
        assertNull("not held", PolestarNav.goalMark(false, there, 1436, 0, r))
        assertNull("a quest focused through it", PolestarNav.goalMark(true, there, 1436, 77, r))
        // the same map still uses the step's own map place, not the world spot
        assertArrayEquals(doubleArrayOf(0.42, 0.65), PolestarNav.goalMark(true, there, 1429, 0, r)!!, 1e-9)
    }
}
