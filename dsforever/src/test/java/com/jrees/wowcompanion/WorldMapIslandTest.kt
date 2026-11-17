package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE SKYBORNE'S ISLAND ON THE WORLD MAP (the user, 2026-11-17: "The new Island for the skyborn is missing from both maps it should be in
 * like the upper center is where they put it in the actual game map"). Zephras Isle is UiMap 2521 (mapType 3, parent Azeroth 947) on its
 * own map 2991; the game's tables give it no UiMapAssignment row on Azeroth (only 69208 / 69852, on 2991 itself), so the worldmap recipe
 * carries its place on the world (947's row for map 2991, the painted islands at the top middle). These tests read the app's real recipe
 * and the client's real world rects.
 */
class WorldMapIslandTest {
    private fun recipe(): JSONObject = JSONObject(listOf(File("../companion/app/src/main/assets/harvest/worldmap.json"),
        File("companion/app/src/main/assets/harvest/worldmap.json")).first { it.canRead() }.readText())

    private fun map(id: Int, type: Int, parent: Int, rect: JSONArray?) = JSONObject().put("uiMapID", id).put("name", "m$id")
        .put("mapType", type).put("parent", parent).put("w", 1002).put("h", 668).apply { rect?.let { put("rect", it) } }

    /** the world, both continents, Elwynn Forest, Durotar and Zephras Isle with the recipe's rects and assignments (UiMap.db2's types /
     *  parents: 947 type 1; 1414 / 1415 type 2 under 947; 1429 / 1411 type 3; 2521 type 3 under 947) */
    private fun model(): WorldMapModel {
        val r = recipe()
        val rects = r.getJSONObject("rects")
        return WorldMapModel().apply {
            addIndex(map(947, 1, 0, null))
            addIndex(map(1414, 2, 947, rects.getJSONArray("1414")))
            addIndex(map(1415, 2, 947, rects.getJSONArray("1415")))
            addIndex(map(1429, 3, 1415, rects.getJSONArray("1429")))
            addIndex(map(1411, 3, 1414, rects.getJSONArray("1411")))
            addIndex(map(2521, 3, 947, rects.getJSONArray("2521")))
            addIndex(map(2665, 3, 0, rects.getJSONArray("2665")))           // the island's second uiMap: no parent (never on the world)
            setAssign(r.optJSONObject("assign"))
        }
    }

    @Test fun theRecipePlacesTheIslandUpperCentreOnTheWorld() {
        val rows = recipe().getJSONObject("assign").getJSONArray("947")
        val isle = (0 until rows.length()).map { rows.getJSONArray(it) }.firstOrNull { it.getInt(0) == 2991 }
        assertNotNull("947 has a row for Zephras Isle's map 2991", isle)
        val u = (isle!!.getDouble(1) + isle.getDouble(3)) / 2; val v = (isle.getDouble(2) + isle.getDouble(4)) / 2
        assertTrue("upper centre: u $u", u in 0.42..0.66)
        assertTrue("upper third: v $v", v < 0.3)
        // its region is the island's own (UiMapAssignment 69208: uiMap 2521 on map 2991), so its frame is its whole picture
        val rect = recipe().getJSONObject("rects").getJSONArray("2521")
        assertEquals(rect.getDouble(2), isle.getDouble(5), 0.01)      // min x = bottom
        assertEquals(rect.getDouble(4), isle.getDouble(6), 0.01)      // min y = right
        assertEquals(rect.getDouble(1), isle.getDouble(7), 0.01)      // max x = top
        assertEquals(rect.getDouble(3), isle.getDouble(8), 0.01)      // max y = left
        // the 3:2 picture keeps its shape on the 3:2 world
        val du = isle.getDouble(3) - isle.getDouble(1); val dv = isle.getDouble(4) - isle.getDouble(2)
        assertEquals(1.0, du / dv, 0.02)
    }

    @Test fun theIslandIsALandOnTheWorld() {
        val m = model()
        assertTrue(m.isLand(2521)); assertTrue(m.isLand(1415)); assertFalse(m.isLand(1429)); assertFalse(m.isLand(2665))
        assertEquals(2521, m.continentOf(2521))
        assertEquals(1415, m.continentOf(1429))                         // the old continents unchanged
        assertEquals(2521, m.canvasFor(2521))
        val ls = m.layersOn(947) { emptyList() }
        assertEquals(listOf(1415, 1414, 2521), ls.map { it.id })
        assertEquals(WorldMapModel.CONTINENT, ls.last().kind)
        val p = ls.last().place
        assertTrue("on the world's top middle: ${p.toList()}", (p[0] + p[2]) / 2 in 0.42f..0.66f && (p[1] + p[3]) / 2 < 0.3f)
        // its place on its root is the world's box, so Home's map zooms out from it to the world
        val root = m.rootPlace(2521)!!
        assertEquals(947, root.first)
        for (i in 0..3) assertEquals(p[i], root.second[i], 1e-4f)
        // and the world on the island's frame reaches far past it (the zoom-out)
        val w = m.placeOn(947, 2521)!!
        assertTrue(w[0] < -2f && w[2] > 3f)
    }

    @Test fun thePlayerAndQuestsOnTheIslandProjectOntoTheWorld() {
        val m = model()
        val ls = m.layersOn(947) { emptyList() }
        val p = ls.first { it.id == 2521 }.place
        // the island's middle, and its corners, land on the same spots of its box
        val mid = m.carryUp(2521, 0.5f, 0.5f, 947)!!
        assertEquals((p[0] + p[2]) / 2, mid[0], 1e-4f); assertEquals((p[1] + p[3]) / 2, mid[1], 1e-4f)
        val tl = m.carryUp(2521, 0f, 0f, 947)!!
        assertEquals(p[0], tl[0], 1e-4f); assertEquals(p[1], tl[1], 1e-4f)
        // a world spot on map 2991 (a quest's, [continent, x, y]) through the same row
        val r = m.world(2521)!!
        val q = m.project(947, 2991, r.wx(0.25), r.wy(0.75))!!
        assertEquals(p[0] + 0.75f * (p[2] - p[0]), q[0], 1e-4f); assertEquals(p[1] + 0.25f * (p[3] - p[1]), q[1], 1e-4f)
    }

    @Test fun theWorldCanSelectTheIslandWhenTheGameGivesNoRect() {
        val m = model()
        // the game's own answer (Map.lua mapexpl, measured: C_Map.GetMapRectOnMap(2521, 947) = 0, 0, 0, 0)
        m.onMapExpl(JSONObject().put("all", true).put("maps", JSONObject()
            .put("2521", JSONObject().put("p", 947).put("r", JSONArray(listOf(0, 0, 0, 0))))))
        val s = m.selectables(947).firstOrNull { it.first == 2521 }
        assertNotNull("the island is selectable on the world", s)
        val p = m.layersOn(947) { emptyList() }.first { it.id == 2521 }.place
        assertEquals((p[0] + p[2]) / 2, s!!.second, 1e-4f); assertEquals((p[1] + p[3]) / 2, s.third, 1e-4f)
    }
}
