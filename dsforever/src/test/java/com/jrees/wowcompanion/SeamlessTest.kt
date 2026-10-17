package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-seamless-map: zones placed on their continent's frame by the world rects, which are stitched, and the feathered edge */
class SeamlessTest {
    // Eastern Kingdoms and Elwynn as the worldmap recipe has them
    private val ek = MapHarvest.Rect(0, 7466.6001, -16000.0, 16000.0, -19199.9004)
    private val elwynn = MapHarvest.Rect(0, -7939.583, -10254.166, 1535.4166, -1935.4166)

    @Test fun aZoneLiesWhereItsWorldRectSays() {
        val p = Seamless.place(ek, elwynn)
        assertEquals((16000.0 - 1535.4166) / 35199.9004, p[0].toDouble(), 1e-5)
        assertEquals((7466.6001 + 7939.583) / 23466.6001, p[1].toDouble(), 1e-5)
        assertEquals(3470.833 / 35199.9004, (p[2] - p[0]).toDouble(), 1e-4)
    }

    @Test fun theZonesOfTheContinentLargestFirstNoCapital() {
        val m = WorldMapModel()
        fun add(id: Int, type: Int) = m.addIndex(JSONObject().put("uiMapID", id).put("name", "m$id").put("mapType", type))
        add(1415, 2); add(1429, 3); add(1436, 3); add(1453, 3); add(1414, 2); add(1411, 3)
        val rects = mapOf(1415 to ek, 1429 to elwynn,
            1436 to MapHarvest.Rect(0, -9400.0, -11900.0, 2600.0, -1000.0),         // larger than Elwynn
            1453 to MapHarvest.Rect(0, -8200.0, -9200.0, 1300.0, 200.0),           // Stormwind: a capital, in its zone's art
            1414 to MapHarvest.Rect(1, 10000.0, -10000.0, 10000.0, -10000.0),
            1411 to MapHarvest.Rect(1, 1000.0, -1000.0, -4000.0, -6000.0))       // Kalimdor's: not this continent's
        assertEquals(listOf(1436, 1429), Seamless.zonesOf(1415, m, rects))
    }

    @Test fun theEdgeIsSoftAndTheMiddleSolid() {
        val w = 40; val h = 40
        val a = IntArray(w * h) { k -> val x = k % w; val y = k / w; if (x in 10 until 30 && y in 10 until 30) 255 else 0 }
        val f = Seamless.feather(a, w, h, 3)
        assertEquals(255, f[20 * w + 20])                                    // the middle stays solid
        assertTrue(f[20 * w + 10] in 60..200)                                // the edge in between
        assertEquals(0, f[2 * w + 2])                                        // far outside: nothing
    }
}
