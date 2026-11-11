package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** ps2 (the user, 2026-11-11: the Polestar path "looks like I have to back track"): the walk being walked starts at the player */
class PolestarTrimTest {
    // an L-shaped walk: (0,0) -> (100,0) -> (100,100)
    private val pts = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 100.0)

    @Test fun thePathStartsAtTheNearestPointAheadOfThePlayer() {
        val (seg, out) = PolestarNav.trimAt(pts, 1, 40.0, 5.0, 60.0)!!
        assertEquals(1, seg)
        assertEquals(listOf(40.0, 0.0, 100.0, 0.0, 100.0, 100.0), out.toList())
    }

    @Test fun progressNeverRunsBack() {
        // past the corner, on the second segment: searching from segment 2 never goes back to the first
        val (seg, out) = PolestarNav.trimAt(pts, 2, 95.0, 30.0, 60.0)!!
        assertEquals(2, seg)
        assertEquals(listOf(100.0, 30.0, 100.0, 100.0), out.toList())
    }

    @Test fun tooFarFromThePathIsNoHit() {
        assertNull(PolestarNav.trimAt(pts, 1, 40.0, 500.0, 60.0))
        assertNotNull(PolestarNav.trimAt(pts, 1, 40.0, 500.0, Double.MAX_VALUE))
    }

    @Test fun liveRouteFromKeepsProgressUntilANewPlan() {
        val nav = PolestarNav()
        nav.tick(doubleArrayOf(1429.0, 0.5, 0.5), 0L)              // GO: active
        val doc = JSONObject().put("legs", JSONArray().put(JSONObject().put("k", "walk").put("p", JSONArray(pts.toList()))))
        nav.onRoute(doc, 1L)
        assertEquals(100.0, nav.liveRouteFrom(2L, 98.0, 50.0, null)!![0].pts[0], 1e-9)      // on the second segment
        // walking back near the start does not pull the path back to its first segment
        assertEquals(100.0, nav.liveRouteFrom(3L, 5.0, 1.0, null)!![0].pts[0], 1e-9)
        // the same plan again (the 10 s resend) keeps it; a new plan starts over
        nav.onRoute(doc, 4L)
        assertEquals(100.0, nav.liveRouteFrom(5L, 5.0, 1.0, null)!![0].pts[0], 1e-9)
        val other = JSONObject().put("legs", JSONArray().put(JSONObject().put("k", "walk").put("p", JSONArray(listOf(0.0, 0.0, 100.0, 0.0)))))
        nav.onRoute(other, 6L)
        assertEquals(5.0, nav.liveRouteFrom(7L, 5.0, 1.0, null)!![0].pts[0], 1e-9)
    }
}
