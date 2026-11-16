package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issues #316 #317 #284 (1.0.32: "the minimap on bottom screen does not display correctly. its black with blue zones", "game art shows
 * its finished but map is still empty"): Home's small map says the Map room's note while its pictures are still to be made. The pure
 * rule and the wiring (the real MapView's draw: app HomeMapNoteViewTest).
 */
class HomeMapNoteTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @Test fun theNoteIsTheMapRoomsOwnWords() {
        assertEquals("Map pictures build while the game is closed", HomeMapNote.TEXT)
        val v = File(src, "WorldMapRoomView.kt").readText()
        assertTrue("the same words as the Map room's note", v.contains("const val NO_ART_NOTE = \"${HomeMapNote.TEXT}\""))
        // and translated: the note's es / fr lines are the Map room's (ArtMapsFirstTest checks them and their CRLF)
        val i18n = File(src, "../../../../assets/companion/i18n")
        for (lang in listOf("es", "fr")) {
            val plain = org.json.JSONObject(File(i18n, "$lang.json").readText()).getJSONObject("plain")
            assertTrue("$lang has it", plain.optString(HomeMapNote.TEXT).isNotBlank())
        }
    }

    @Test fun itShowsOnlyWithNoTerrainWhileThePicturesAreStillToBeMade() {
        assertTrue(HomeMapNote.shows(morphing = false, terrain = false, building = true))
        assertFalse("the tiles drawn", HomeMapNote.shows(morphing = false, terrain = true, building = true))
        assertFalse("the step done: a map with no tiles at all (an instance) says nothing", HomeMapNote.shows(morphing = false, terrain = false, building = false))
        assertFalse("never in Home's open / close glide", HomeMapNote.shows(morphing = true, terrain = false, building = true))
        assertFalse(HomeMapNote.shows(morphing = false, terrain = true, building = false))
    }

    @Test fun theTerrainCountsOnlyWhenATileDrawsOrIsStillLoading() {
        // review of #316 #317: the step part-way makes this continent's folder before the tiles where the player is
        assertFalse("no folder for this continent", HomeMapNote.terrainShown(tiled = false, drew = false, allAbsent = true, showing = false))
        assertTrue("a tile drawn", HomeMapNote.terrainShown(tiled = true, drew = true, allAbsent = false, showing = true))
        assertTrue("the tiles here still loading, no note up: not yet", HomeMapNote.terrainShown(tiled = true, drew = false, allAbsent = false, showing = false))
        assertFalse("every tile here looked for and not made: the note", HomeMapNote.terrainShown(tiled = true, drew = false, allAbsent = true, showing = false))
        assertFalse("the note up stays while the step's refresh loads them again", HomeMapNote.terrainShown(tiled = true, drew = false, allAbsent = false, showing = true))
    }

    @Test fun aNarrowMapShrinksTheLine() {
        assertEquals(20f, HomeMapNote.fit(300f, 20f, 488f), 0f)                // fits: its own size
        assertEquals(20f, HomeMapNote.fit(488f, 20f, 488f), 0f)
        assertEquals(16f, HomeMapNote.fit(500f, 20f, 400f), 0.001f)            // 400 / 500 of 20
        assertEquals("never under the smallest", HomeMapNote.MIN, HomeMapNote.fit(2000f, 20f, 300f), 0f)
        assertEquals("nothing measured: its own size", 20f, HomeMapNote.fit(0f, 20f, 300f), 0f)
        assertEquals("no field yet: its own size", 20f, HomeMapNote.fit(300f, 20f, 0f), 0f)
    }

    @Test fun theLineSitsUnderThePlayer() {
        assertEquals(255f, HomeMapNote.baseline(200f, 300f), 0.001f)
        assertTrue(HomeMapNote.baseline(210f, 420f) in 210f..420f)
    }

    @Test fun buildingReadsTheMinimapStepsIndexAndRefreshLooksAgain() {
        val t = File(src, "MinimapTiles.kt").readText()
        assertTrue(t.contains("const val INDEX = \"minimap/index.json\""))
        val building = t.substringAfter("fun building(): Boolean {").substringBefore("\n    }")
        assertTrue(building.contains("!(indexed ?: File(root, INDEX).isFile.also { indexed = it })"))
        // review: the draw reads the app's own folder only, never the /sdcard copy (ArtHarvest.file falls back to it)
        assertTrue(building.contains("val root = ArtHarvest.rootDir ?: return false"))
        assertTrue(!building.contains("ArtHarvest.file("))
        val refresh = t.substringAfter("fun refresh() {").substringBefore("}")
        assertTrue("refresh forgets the index's answer", refresh.contains("indexed = null"))
        // the step writes that index after its last tile, then refreshes (the note goes as the step ends)
        val m = File(src, "MapHarvestSteps.kt").readText()
        val step = m.substringAfter("override val id = \"minimap\"").substringBefore("override val id = \"transports\"")
        val write = step.indexOf("job.writeBytes(\"minimap/index.json\"")
        val loopEnd = step.indexOf("val conts = JSONObject()")
        assertTrue("the index after the tiles", loopEnd in 1 until write)
        assertTrue("then the refresh", step.indexOf("main.post { MinimapTiles.refresh() }", write) > write)
    }

    @Test fun mapViewDrawsItInPlaceOfTheTerrain() {
        val v = File(src, "MapView.kt").readText()
        val grid = v.indexOf("// no tiles for this map (yet): keep the old grid so the field is not a blank slab")
        val shown = v.indexOf("val tilesShown = HomeMapNote.terrainShown(terrain, drew, allAbsent, artNote != null)")
        val note = v.indexOf("artNote = if (HomeMapNote.shows(morphOn, tilesShown, MinimapTiles.building())) HomeMapNote.TEXT else null")
        assertTrue("what the terrain drew decides it", shown in 1 until note)
        // a tile drawn, or one not looked for yet, keeps the field "drawn"
        assertTrue(v.contains("?.let { c.drawBitmap(it, null, tileDst, pArt); drew = true }"))
        assertTrue(v.contains("?: run { if (!MinimapTiles.absent(rT.continent, col, row, sample)) allAbsent = false }"))
        val floor = v.indexOf("// the floorplan sits ON the terrain, under every marker")
        assertTrue("after the terrain, before the floorplan and every marker", grid in 1 until note && note < floor)
        assertTrue("through Tr", v.contains("val t = Tr.t(note)"))
    }
}
