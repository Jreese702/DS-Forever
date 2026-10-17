package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-route-questtrail: QuestTrail's arrow (or its API, should it come) as a point on this map */
class QuestTrailBridgeTest {
    private val ax = 1000.0; private val ay = 500.0

    @Test fun theBearingAndDistanceMakeAPoint() {
        // bearing pi/2 = west (counter-clockwise from north), 100 yd: x down by 100 / 1000
        val t = QuestTrailBridge.target(JSONObject("""{"src":"arrow","b":1.5708,"yd":100,"t":"Objective: Kill Kobolds","d":"91 m"}"""),
            12, 0.5, 0.5, ax, ay)!!
        assertEquals(0.4, t.x, 1e-4); assertEquals(0.5, t.y, 1e-4)
        assertEquals("Objective: Kill Kobolds", t.label); assertTrue(!t.directionOnly)
        // north 50 yd: y up by 50 / 500
        val n = QuestTrailBridge.target(JSONObject("""{"b":0,"yd":50}"""), 12, 0.5, 0.5, ax, ay)!!
        assertEquals(0.5, n.x, 1e-9); assertEquals(0.4, n.y, 1e-9)
    }

    @Test fun noDistanceIsADirectionWithItsWords() {
        val t = QuestTrailBridge.target(JSONObject("""{"b":3.14159265,"t":"Objective","d":"~4 kills left"}"""), 12, 0.5, 0.5, ax, ay)!!
        assertTrue(t.directionOnly)
        assertEquals(0.5 + QuestTrailBridge.FAR_YD / ay, t.y, 1e-3)            // south
        assertEquals("Objective · ~4 kills left", t.label)
        assertNull(QuestTrailBridge.target(JSONObject("""{"t":"computing…"}"""), 12, 0.5, 0.5, ax, ay))
        assertNull(QuestTrailBridge.target(null, 12, 0.5, 0.5, ax, ay))
    }

    @Test fun theApiComesFirstOnItsMap() {
        val o = JSONObject("""{"src":"api","b":0,"yd":50,"t":"arrow words","api":{"m":12,"x":0.7,"y":0.2,"q":33,"t":"Kill Kobolds"}}""")
        val t = QuestTrailBridge.target(o, 12, 0.5, 0.5, ax, ay)!!
        assertTrue(t.exact); assertEquals(0.7, t.x, 1e-9); assertEquals("Kill Kobolds", t.label)
        // another map's API point: the arrow
        assertEquals(0.4, QuestTrailBridge.target(o, 99, 0.5, 0.5, ax, ay)!!.y, 1e-9)
        val m = JSONObject("""{"qtrailPath":[[12,0.5,0.5],[99,0.1,0.1],[12,0.6,0.55]]}""")
        assertEquals(listOf(0.5, 0.5, 0.6, 0.55), QuestTrailBridge.path(m, 12)!!.toList())
        assertNull(QuestTrailBridge.path(m, 99))
    }
}
