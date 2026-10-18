package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** b-maproom-layers: one canvas a root, every finer map a layer at its place, its art fading in as it fills the view */
class WorldMapLayersTest {
    private fun map(id: Int, type: Int, parent: Int, rect: List<Double>?) = JSONObject().put("uiMapID", id).put("name", "m$id")
        .put("mapType", type).put("parent", parent).put("w", 1002).put("h", 668).apply { rect?.let { put("rect", JSONArray(it)) } }

    /** the world (947), Eastern Kingdoms (1415: 8000 yd a side) drawn in the world's right half, Elwynn, Westfall, Stormwind, a dungeon */
    private fun model() = WorldMapModel().apply {
        addIndex(map(947, 1, 0, null))
        addIndex(map(1415, 2, 947, listOf(0.0, 4000.0, -4000.0, 4000.0, -4000.0)))
        addIndex(map(1429, 3, 1415, listOf(0.0, 0.0, -1000.0, 0.0, -1000.0)))
        addIndex(map(1436, 3, 1415, listOf(0.0, 0.0, -1000.0, 1000.0, 0.0)))
        addIndex(map(1453, 3, 1415, listOf(0.0, -100.0, -300.0, -100.0, -300.0)))
        addIndex(map(9999, 4, 1415, listOf(0.0, -10.0, -20.0, -10.0, -20.0)))
        // [map, uMin, vMin, uMax, vMax, xMin, yMin, xMax, yMax]: the continent's whole region in the box u .5..1, v .25..75
        setAssign(JSONObject().put("947", JSONArray().put(JSONArray(listOf(0, 0.5, 0.25, 1.0, 0.75, -4000.0, -4000.0, 4000.0, 4000.0)))))
    }

    private fun near(e: FloatArray, a: FloatArray?) = assertArrayEquals(e, a, 1e-5f)

    @Test fun placesOnTheirCanvas() {
        val m = model()
        near(floatArrayOf(0.5f, 0.5f, 0.625f, 0.625f), m.placeOn(1429, 1415))
        // on the world through the continent's assignment
        near(floatArrayOf(0.75f, 0.5f, 0.8125f, 0.5625f), m.placeOn(1429, 947))
        near(floatArrayOf(0.5f, 0.25f, 1f, 0.75f), m.placeOn(1415, 947))
        // the world seen from the continent reaches past its frame
        near(floatArrayOf(-1f, -0.5f, 1f, 1.5f), m.placeOn(947, 1415))
        near(WorldMapModel.UNIT, m.placeOn(1415, 1415))
    }

    @Test fun eachMapsCanvas() {
        val m = model()
        assertEquals(947, m.canvasFor(947))
        assertEquals(1415, m.canvasFor(1415))
        assertEquals(1415, m.canvasFor(1429))
        assertEquals(1415, m.canvasFor(1453))         // a capital: its continent's, through its zone
    }

    @Test fun layersParentsFirstNeverADungeon() {
        val m = model()
        val zones = { _: Int -> listOf(1429, 1436, 9999) }
        val w = m.layersOn(947, zones)
        assertEquals(listOf(1415, 1429, 1436, 1453), w.map { it.id })
        assertEquals(listOf(WorldMapModel.CONTINENT, WorldMapModel.ZONE, WorldMapModel.ZONE, WorldMapModel.CITY), w.map { it.kind })
        assertEquals(listOf(null, 1415, 1415, 1429), w.map { it.parent })
        val c = m.layersOn(1415, zones)
        assertEquals(listOf(1429, 1436, 1453), c.map { it.id })
        assertNull(c[0].parent)                        // a zone sits on the canvas itself
        assertEquals(1429, c[2].parent)
    }

    @Test fun theFadeRampsOverAboutOnePointFiveOfZoom() {
        assertEquals(0f, WorldMapModel.fade(0.4f), 0f)
        assertEquals(0f, WorldMapModel.fade(0.55f), 1e-6f)
        assertEquals(0.5f, WorldMapModel.fade(0.7f), 1e-5f)
        assertEquals(1f, WorldMapModel.fade(0.85f), 1e-6f)
        assertEquals(1f, WorldMapModel.fade(3f), 0f)
        // a continent: none at the world's whole, whole where its frame (0.8 of the world) fills the view
        assertEquals(0f, WorldMapModel.fadeContinent(1f, 0.8f), 0f)
        assertEquals(1f, WorldMapModel.fadeContinent(1.25f, 0.8f), 1e-6f)
        assertEquals(0.5f, WorldMapModel.fadeContinent(1.125f, 0.8f), 1e-5f)
    }
}
