package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** the cursor line's route (the agreed design, 2026-10-16): nearest still-needed goal, else the next step of the way */
class CursorRouteTest {
    private val ax = 1000.0; private val ay = 1000.0

    @Test fun theNearestUnfinishedObjectiveIsTheGoal() {
        val e = JSONObject("""{"id":5,"obj":[{"f":true},{"f":false}],
            "qdb":{"obj":[{"m":12,"x":0.51,"y":0.5,"i":1},{"m":12,"x":0.7,"y":0.5,"i":2},{"m":99,"x":0.5,"y":0.5,"i":2}]}}""")
        val g = CursorRoute.goals(null, e, 12)
        assertEquals(1, g.size)                                   // objective 1 is done; the other map's spot is not this map's
        val r = CursorRoute.plan(0.5, 0.5, ax, ay, g, null, null, null, emptyList()) { _, _ -> false }!!
        assertEquals(CursorRoute.Kind.GOAL, r.kind); assertEquals(0.7, r.x, 1e-9)
    }

    @Test fun whenAllAreDoneTheTurnIn() {
        val mq = JSONObject("""{"questID":5,"done":true,"x":0.2,"y":0.2,"tx":0.9,"ty":0.9}""")
        val g = CursorRoute.goals(mq, JSONObject("""{"id":5,"ready":true}"""), 12)
        assertEquals(listOf(0.9, 0.9), g.single().toList())
    }

    @Test fun anotherZoneFliesFromAConnectedMaster() {
        val nodes = listOf(CursorRoute.Node(0.52, 0.5, 1, "Goldshire"), CursorRoute.Node(3.0, 0.5, 2, "Sentinel Hill"))
        val r = CursorRoute.plan(0.5, 0.5, ax, ay, emptyList(), doubleArrayOf(3.05, 0.5), null, null, nodes) { a, b -> a + b == 3 }!!
        assertEquals(CursorRoute.Kind.FLIGHT, r.kind)
        assertEquals(0.52, r.x, 1e-9)
    }

    @Test fun noFlightLeavesByTheEdgeOrTheGamesWaypoint() {
        val far = doubleArrayOf(2.0, 0.5)
        val exit = CursorRoute.plan(0.5, 0.5, ax, ay, emptyList(), far, null, null, emptyList()) { _, _ -> false }!!
        assertEquals(CursorRoute.Kind.EXIT, exit.kind); assertEquals(1.0, exit.x, 1e-9); assertEquals("Exit east", exit.label)
        val way = CursorRoute.plan(0.5, 0.5, ax, ay, emptyList(), far, doubleArrayOf(0.9, 0.1), "Go to Westfall", emptyList()) { _, _ -> false }!!
        assertEquals(CursorRoute.Kind.WAYPOINT, way.kind); assertEquals("Go to Westfall", way.label)
        assertNull(CursorRoute.plan(0.5, 0.5, ax, ay, emptyList(), null, null, null, emptyList()) { _, _ -> false })
    }

    @Test fun compassAndYards() {
        assertEquals("north", CursorRoute.compass(0.5, 0.5, 0.5, 0.1, ax, ay))
        assertEquals("south-west", CursorRoute.compass(0.5, 0.5, 0.2, 0.8, ax, ay))
        assertEquals(300, CursorRoute.yards(0.5, 0.5, 0.8, 0.5, ax, ay))
    }
}
