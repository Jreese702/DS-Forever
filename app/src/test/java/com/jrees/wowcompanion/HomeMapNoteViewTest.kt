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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Issues #316 #317 #284 (1.0.32: "the minimap on bottom screen does not display correctly. its black with blue zones", "game art shows
 * its finished but map is still empty"): the real MapView, as Home's small map, over a made-up art folder. With no minimap tile and no
 * minimap index it says the Map room's note where the terrain would be; once the minimap step's index is written (and its refresh
 * runs) the note goes; a tile where the player is draws, never the note. Review: the step part-way (this continent's folder, no tile
 * here) said nothing over a black field; it says the note now, once the tiles here are looked for, and keeps it through the step's
 * refreshes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeMapNoteViewTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val rootField = ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }
    private var was: Any? = null
    private lateinit var root: File

    @Before fun art() {
        was = rootField.get(null)
        root = java.nio.file.Files.createTempDirectory("art").toFile()
        rootField.set(null, root)
        forgetTiles()
    }

    /** MinimapTiles is one object for the whole run: its loads in flight land first, then no tile another test decoded is kept */
    private fun forgetTiles() {
        landLoads()
        for (f in listOf("cache", "small")) (field(f) as MutableMap<*, *>).clear()
        MinimapTiles.refresh()
    }
    private fun field(f: String): Any? = MinimapTiles::class.java.getDeclaredField(f).apply { isAccessible = true }.get(null)

    /** every tile load in flight lands (as the step's own writes would come after them) */
    private fun landLoads() {
        val pending = field("pending") as MutableCollection<*>
        val until = System.currentTimeMillis() + 5_000L
        while (pending.isNotEmpty() && System.currentTimeMillis() < until) { Thread.sleep(5); ShadowLooper.idleMainLooper() }
        assertTrue("the loads landed", pending.isEmpty())
    }

    /** the tile loads land (MinimapTiles' own thread, then the UI thread) until [done] */
    private fun settle(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000L
        while (!done() && System.currentTimeMillis() < until) { Thread.sleep(5); ShadowLooper.idleMainLooper() }
        assertTrue("settled", done())
    }

    /** a 512 px minimap tile on the device, as the step writes it */
    private fun tile(cont: Int, col: Int, row: Int) {
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(60, 110, 50)) }
        File(root, "minimap/$cont/${col}_$row.webp").apply { parentFile?.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it) }
    }

    @After fun back() {
        rootField.set(null, was)
        forgetTiles()
        root.deleteRecursively()
    }

    /** every drawText the map makes */
    private class Rec(b: Bitmap) : Canvas(b) {
        val texts = ArrayList<String>()
        var tiles = 0                                   // minimap tiles drawn (their small copies: 128 or 64 px)
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { texts += text; super.drawText(text, x, y, paint) }
        override fun drawBitmap(bitmap: Bitmap, src: android.graphics.Rect?, dst: android.graphics.RectF, paint: Paint?) {
            if (bitmap.width == bitmap.height && (bitmap.width == 128 || bitmap.width == 64)) tiles++
            super.drawBitmap(bitmap, src, dst, paint)
        }
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

    private fun draw(v: MapView): List<String> = drawRec(v).texts
    private fun drawRec(v: MapView): Rec = Rec(Bitmap.createBitmap(520, 420, Bitmap.Config.ARGB_8888)).also { v.draw(it) }

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

    @Test fun aTileHereDrawsItNotTheNote() {
        // the step part-way (no index yet), the tile under the player (Dun Morogh at wx -5500, wy 0: column 32, row 42) made
        tile(0, 32, 42)
        MinimapTiles.refresh()
        assertTrue(MinimapTiles.has(0))
        assertTrue(MinimapTiles.building())
        val v = smallMap()
        draw(v)
        assertNull("its tiles still loading: no note yet", v.artNote)
        settle { drawRec(v).tiles > 0 }
        val rec = drawRec(v)
        assertTrue("the tile drawn", rec.tiles > 0)
        assertNull(v.artNote)
        assertTrue(!rec.texts.contains(Tr.t(HomeMapNote.TEXT)))
    }

    @Test fun thisContinentsFolderWithNoTileHereSaysTheNote() {
        // review of #316 #317: the step part-way made this continent's folder (a tile far off), none where the player is: the field
        // was black (no grid, no note); now the note, once the tiles here are looked for
        tile(0, 10, 10)
        MinimapTiles.refresh()
        assertTrue(MinimapTiles.has(0))
        val v = smallMap()
        draw(v)
        assertNull("the tiles here still being looked for: no note in the first frame", v.artNote)
        settle { draw(v); v.artNote != null }
        val rec = drawRec(v)
        assertEquals(HomeMapNote.TEXT, v.artNote)
        assertEquals(0, rec.tiles)
        assertTrue(rec.texts.contains(Tr.t(HomeMapNote.TEXT)))
        // the step's refresh (every 20 tiles) looks for them again: the note stays while they load (no blink)
        MinimapTiles.refresh()
        draw(v)
        assertEquals(HomeMapNote.TEXT, v.artNote)
        landLoads()
        draw(v)
        assertEquals(HomeMapNote.TEXT, v.artNote)
        // the tile here made: it draws and the note goes
        tile(0, 32, 42)
        MinimapTiles.refresh()
        settle { drawRec(v).tiles > 0 }
        draw(v)
        assertNull(v.artNote)
    }

    @Test fun anotherContinentsTilesStillLeaveTheNoteHere() {
        File(root, "minimap/1").mkdirs()                  // Kalimdor's first tiles; the player in Dun Morogh (continent 0)
        MinimapTiles.refresh()
        val v = smallMap()
        draw(v)
        assertEquals(HomeMapNote.TEXT, v.artNote)
    }
}
