package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Issues #316 #317 #284 (1.0.32: "the minimap on bottom screen does not display correctly. its black with blue zones", "game art shows
 * its finished but map is still empty"): the real MapView, as Home's small map, over a made-up art folder. With no minimap tile and no
 * minimap index it says the Map room's note where the terrain would be; once the minimap step's index is written (and its refresh
 * runs) the note goes; a continent with tiles draws them, never the note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeMapNoteViewTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val rootField = ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }
    private var was: Any? = null
    private lateinit var root: File

    @Before fun art() {
        was = rootField.get(null)
        root = java.nio.file.Files.createTempDirectory("art").toFile()
        rootField.set(null, root)
        MinimapTiles.refresh()
    }

    @After fun back() {
        rootField.set(null, was)
        MinimapTiles.refresh()
        root.deleteRecursively()
    }

    /** every drawText the map makes */
    private class Rec(b: Bitmap) : Canvas(b) {
        val texts = ArrayList<String>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { texts += text; super.drawText(text, x, y, paint) }
    }

    /** Dun Morogh's player at its middle, the client's rect for it (continent 0) */
    private fun doc() = JSONObject("""{"type":"map","ok":true,"mapID":1426,"zone":"Dun Morogh","subzone":"","x":0.5,"y":0.5,"cont":0,
        "wx":-5500.0,"wy":0.0,"facing":0.1,"rect":{"c":0,"t":-3500.0,"b":-7500.0,"l":2000.0,"r":-2000.0}}""")

    private fun smallMap(): MapView = MapView(app).apply {
        compact = true
        update(doc(), 0)
        measure(View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY))
        layout(0, 0, 520, 420)
    }

    private fun draw(v: MapView): List<String> {
        val rec = Rec(Bitmap.createBitmap(520, 420, Bitmap.Config.ARGB_8888))
        v.draw(rec)
        return rec.texts
    }

    @Test fun noTilesYetSaysThePicturesBuildWhileTheGameIsClosed() {
        assertTrue("no index in a fresh art folder", MinimapTiles.building())
        val v = smallMap()
        val texts = draw(v)
        assertEquals(HomeMapNote.TEXT, v.artNote)
        assertEquals("Map pictures build while the game is closed", v.artNote)
        assertTrue("drawn: $texts", texts.contains(Tr.t(HomeMapNote.TEXT)))
    }

    @Test fun theStepsIndexTakesTheNoteAway() {
        val v = smallMap()
        draw(v)
        assertEquals(HomeMapNote.TEXT, v.artNote)
        // the minimap step's last write, then its refresh (posted to the UI thread)
        File(root, MinimapTiles.INDEX).apply { parentFile?.mkdirs() }.writeText("""{"continents":{}}""")
        assertTrue("cached until the refresh", MinimapTiles.building())
        MinimapTiles.refresh()
        assertTrue(!MinimapTiles.building())
        val texts = draw(v)
        assertNull(v.artNote)
        assertTrue("not drawn: $texts", !texts.contains(Tr.t(HomeMapNote.TEXT)))
    }

    @Test fun aContinentWithTilesDrawsThemNotTheNote() {
        // the step part-way (no index yet), this continent's first tiles made: the terrain, no note
        File(root, "minimap/0").mkdirs()
        MinimapTiles.refresh()
        assertTrue(MinimapTiles.has(0))
        assertTrue(MinimapTiles.building())
        val v = smallMap()
        val texts = draw(v)
        assertNull(v.artNote)
        assertTrue(!texts.contains(Tr.t(HomeMapNote.TEXT)))
    }

    @Test fun anotherContinentsTilesStillLeaveTheNoteHere() {
        File(root, "minimap/1").mkdirs()                  // Kalimdor's first tiles; the player in Dun Morogh (continent 0)
        MinimapTiles.refresh()
        val v = smallMap()
        draw(v)
        assertEquals(HomeMapNote.TEXT, v.artNote)
    }
}
