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

    /** b-qtrail-golden: QuestTrail's minimap drawing (pixels, x east, y north) put back on the map */
    @Test fun theGoldenPathFromItsMinimapPixels() {
        // a 140 px minimap 200 yd across (0.7 px a yard); the player at world (-100, -100) on a map whose top-left is world (0, 0),
        // 1000 x 1000 yd: the player at 0.1, 0.1
        val gp = JSONObject("""{"w":140,"yd":200,"rot":0,"s":[[0,0,0,35,0],[0,35,35,35,2]]}""")
        val p = QuestTrailBridge.trail(gp, -100.0, -100.0, 0.0, 0.0, 1000.0, 1000.0)
        assertEquals(2, p.size)
        // 35 px north = 50 yd north: world x -50 = map y 0.05; then 50 yd east = world y -150 = map x 0.15
        assertEquals(0.1, p[0].x0, 1e-9); assertEquals(0.1, p[0].y0, 1e-9); assertEquals(0.05, p[0].y1, 1e-9)
        assertEquals(0.15, p[1].x1, 1e-9); assertEquals(0.05, p[1].y1, 1e-9); assertEquals(2, p[1].kind)
        // a rotating minimap, the player facing west (pi/2): "up" on it is west, so 35 px up = 50 yd west = map x 0.05
        val r = QuestTrailBridge.trail(JSONObject("""{"w":140,"yd":200,"rot":1.5707963267948966,"s":[[0,0,0,35,0]]}"""),
            -100.0, -100.0, 0.0, 0.0, 1000.0, 1000.0)
        assertEquals(0.05, r[0].x1, 1e-9); assertEquals(0.1, r[0].y1, 1e-9)
        assertTrue(QuestTrailBridge.trail(null, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0).isEmpty())
    }

    /** the user on test61: "the QuestTrail objective needs to trump the focus objective" - a step for no quest of the log keeps its own words */
    @Test fun aStepNotInTheLogIsItsOwnCard() {
        val titles = mapOf(7 to "Kobold Candles")
        assertNull(QuestTrailBridge.step("Take\nReport to Gryan Stoutmantle", titles))
        val t = QuestTrailBridge.loose("Take\nReport to Gryan Stoutmantle")!!
        assertEquals(0, t.questId); assertTrue(t.loose)
        assertEquals("Report to Gryan Stoutmantle", t.title)
        assertEquals("Take: Report to Gryan Stoutmantle", t.words)
        val g = QuestTrailBridge.loose("Take\nThe Fargodeep Mine (estimate) – Marshal McBride")!!
        assertEquals("The Fargodeep Mine", g.title)
        assertEquals("Take: The Fargodeep Mine from Marshal McBride", g.words)
        assertNull(QuestTrailBridge.loose("  "))
        // a quest of the log still matches it, with its objective's words
        val m = QuestTrailBridge.step("Objective\nKobold Candles – Large Candle: 3/8", titles)!!
        assertEquals(7, m.questId); assertEquals("Large Candle: 3/8", m.words)
    }
}
