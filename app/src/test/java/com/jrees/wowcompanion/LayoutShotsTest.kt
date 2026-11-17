package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * THE TOP-SCREEN LAYOUT EDITOR'S PAGE AND ITS SETTINGS ROW, the off-device render (the user, 2026-11-16: "we need to make a way to
 * basically edit the UI locations on the top screen from our UI menu"). The page is fed the documents the add-on's Lua REALLY sends
 * (tools/test_layout.py's fixtures, dsforever/src/test/resources/layout-fixtures) and driven as the controller and a finger drive it:
 * A picks a tile, the D-pad moves the piece, B drops it and then asks Save / Discard / Cancel with unsaved changes, a finger picks a
 * tile and drags a piece on the miniature, Copy opens the other characters. The Settings room shows "Layout" first on Top screen once
 * the add-on set has the editor. The checks always run; the PNGs only when LAYOUT_SHOTS_DIR is set (LAYOUT_ART_ROOT = an art root
 * laid out like the app's: ui/kit, fonts, icons.pak).
 *   LAYOUT_SHOTS_DIR=<dir> LAYOUT_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*LayoutShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LayoutShotsTest {
    private val out: File? = System.getenv("LAYOUT_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    @After fun clear() { LayoutModel.Live.clearForTest() }

    private fun art() {
        System.getenv("LAYOUT_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
        }
        Settings.init(app); SettingsTabs.init(app); Tr.init(app)
    }

    private fun fx(name: String): JSONObject {
        val dirs = listOf(File("../dsforever/src/test/resources/layout-fixtures"), File("dsforever/src/test/resources/layout-fixtures"))
        val f = dirs.map { File(it, "$name.json") }.firstOrNull { it.isFile } ?: error("fixture $name (run tools/test_layout.py)")
        return JSONObject(f.readText())
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        for (k in 0..1) {
            ShadowLooper.idleMainLooper()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            v.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        }
    }

    private fun page(doc: String): LayoutView {
        art()
        LayoutModel.Live.clearForTest()
        LayoutModel.Live.note(fx(doc))
        val v = LayoutView(app).apply { dryRun = true }
        v.open()
        lay(v)
        return v
    }

    private fun feed(v: LayoutView, doc: String) { LayoutModel.Live.note(fx(doc)); ShadowLooper.idleMainLooper(); lay(v) }

    /** the page over the room, the bar under it (Settings lit) */
    private fun shot(v: View, name: String) {
        val d = out ?: return
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink)
        lay(v)
        v.draw(c)
        val strip = Strip(app) { }.apply { active = Room.SETTINGS }
        strip.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        strip.layout(0, 0, W, Strip.HEIGHT)
        c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touch(v: View, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0, 0, action, x, y, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    private fun tileBox(v: LayoutView, id: String): android.graphics.RectF {
        @Suppress("UNCHECKED_CAST")
        val boxes = LayoutView::class.java.getDeclaredField("tileBoxes").apply { isAccessible = true }.get(v) as ArrayList<Pair<android.graphics.RectF, String>>
        return boxes.first { it.second == id }.first
    }

    // ---------------------------------------------------------------- the controller
    @Test fun theControllerPicksMovesAndSteps() {
        val v = page("lay_moved")
        val e = v.editor
        assertTrue(v.isOpen)
        assertEquals("the tiles, grouped", listOf("hud", "target", "party", "compass", "nbar", "chat"), e.tiles().map { it.id })
        assertEquals(LayoutModel.Focus.TILES, e.focus)
        shot(v, "layout_browse")
        v.padKey("PAD1")                                                // A picks the HUD (the first tile)
        assertEquals("hud", e.picked)
        v.padKey("PADDRIGHT"); v.padKey("PADDRIGHT")
        assertEquals("the D-pad moves it 1 px a press", 1002.0, e.want!!.first, 0.0)
        v.padKey("PAD3")                                                // X: 10 px
        v.padKey("PADDDOWN")
        assertEquals(810.0, e.want!!.second, 0.0)
        shot(v, "layout_moving")
        v.padKey("PAD4")                                                // Y: Size mode
        assertTrue(e.sizeMode)
        v.padKey("PADDUP"); v.padKey("PADDUP")
        assertEquals("Size mode: up is bigger", 120, e.wantScale)
        shot(v, "layout_size")
        v.padKey("PAD1")                                                // A drops it
        assertNull(e.picked)
        assertEquals(LayoutModel.Focus.TILES, e.focus)
        // B with unsaved changes asks (the document says dirty): Save / Discard / Cancel, B cancels
        v.padKey("PAD2")
        assertTrue(e.prompt)
        shot(v, "layout_unsaved")
        v.padKey("PAD2")
        assertFalse(e.prompt)
        assertTrue(v.isOpen)
        // Discard closes the page (the add-on's close drops the draft)
        var closedToTabs: Boolean? = null
        v.onClosed = { closedToTabs = it }
        v.padKey("PAD2"); v.padKey("PADDRIGHT"); v.padKey("PAD1")
        assertFalse(v.isOpen)
        assertEquals("B's route: Settings' cursor to its tab row (the next B goes Home)", true, closedToTabs)
    }

    @Test fun theFooterAndTheListByTheController() {
        val v = page("lay_copy")
        val e = v.editor
        // down past the last tile: the footer; right to Copy; A opens the other characters
        repeat(6) { v.padKey("PADDDOWN") }
        assertEquals(LayoutModel.Focus.FOOTER, e.focus)
        repeat(LayoutModel.Btn.COPY.ordinal) { v.padKey("PADDRIGHT") }
        v.padKey("PAD1")
        assertTrue(e.list)
        lay(v)
        shot(v, "layout_copy")
        v.padKey("PAD2")
        assertFalse("B closes the list first", e.list)
        assertTrue(v.isOpen)
        v.padKey("PAD2")
        assertEquals("B in the footer: back to the tiles", LayoutModel.Focus.TILES, e.focus)
        v.padKey("PAD2")
        assertFalse("nothing unsaved: B leaves", v.isOpen)
    }

    // the review: the stick browses as the D-pad does while no piece is picked (the design: "D-pad or stick browses the tiles"); the B
    // disc on the bar is the page's B (rooms the same on every route), never a straight Home that drops the draft unasked
    @Test fun theStickBrowsesAndTheDiscIsB() {
        val v = page("lay_moved")
        val e = v.editor
        assertEquals(0, e.tile)
        v.stickVector(0f, 1f)                                           // pushed down: one tile a push
        assertEquals(1, e.tile)
        v.stickVector(0.1f, 0.9f)                                       // still pushed: no new step (the hold-repeat steps it later)
        assertEquals(1, e.tile)
        v.stickVector(0f, 0f); v.stickVector(0f, 1f)
        assertEquals(2, e.tile)
        v.stickVector(0f, 0f)
        v.padKey("PAD1")                                                // A picks the third tile (Party frames)
        assertEquals("party", e.picked)
        v.stickVector(0f, 1f)                                           // a piece picked: the stick glides it, it never browses
        assertEquals(2, e.tile)
        v.stickVector(0f, 0f)
        lay(v)
        v.backByTouch()                                                 // the disc: drop the piece
        assertNull(e.picked)
        assertTrue(v.isOpen)
        v.backByTouch()                                                 // unsaved changes (the document says dirty): ask
        assertTrue(e.prompt)
        v.backByTouch()                                                 // the disc again: the prompt goes, the page stays
        assertFalse(e.prompt)
        assertTrue(v.isOpen)
    }

    // ---------------------------------------------------------------- a finger
    @Test fun aFingerPicksAndDrags() {
        val v = page("lay_moved")
        val e = v.editor
        val t = tileBox(v, "target")
        touch(v, MotionEvent.ACTION_DOWN, t.centerX(), t.centerY()); touch(v, MotionEvent.ACTION_UP, t.centerX(), t.centerY())
        assertEquals("a tile picks", "target", e.picked)
        lay(v)
        shot(v, "layout_target")
        // a drag on the miniature carries the piece (the miniature is 840 / 1920 of the screen)
        val p = e.doc!!.piece("hud")!!
        val k = LayoutView.MINI_W / 1920f
        val mini = LayoutView::class.java.getDeclaredField("mini").apply { isAccessible = true }.get(v) as android.graphics.RectF
        val x0 = mini.left + (p.rect.cx * k).toFloat(); val y0 = mini.top + (p.rect.cy * k).toFloat()
        touch(v, MotionEvent.ACTION_DOWN, x0, y0)
        assertEquals("a finger on a piece picks it", "hud", e.picked)
        touch(v, MotionEvent.ACTION_MOVE, x0 + 192f * k, y0 - 96f * k)
        touch(v, MotionEvent.ACTION_UP, x0 + 192f * k, y0 - 96f * k)
        assertEquals("dragged 192 screen px' worth on the miniature (then snapped, 8 px at most)", p.ax + 192.0, e.want!!.first, 9.0)
        assertTrue(e.want!!.second < p.ay)
    }

    // ---------------------------------------------------------------- the states
    @Test fun theFightAndTheLinkedPiece() {
        val v = page("lay_combat")
        assertTrue(v.editor.doc!!.combat)
        shot(v, "layout_combat")
        val l = page("lay_linked")
        assertEquals("compass", l.editor.doc!!.piece("target")!!.link)
        shot(l, "layout_linked")
        val c = page("lay_closed")
        assertFalse(c.editor.doc!!.open)
        shot(c, "layout_opening")
    }

    // ---------------------------------------------------------------- hub58: only what is on; Polestar in two
    // (the user, 2026-11-17: "the edit mode should include any UI element that is currently turned on, including the wow UI and
    // polestar. the polestar ui should be broken into 2, one for the arrow and one for the actual guide.")
    @Test fun polestarsTwoPiecesAndOnlyWhatIsOn() {
        val v = page("lay_polestar")
        val e = v.editor
        assertEquals(listOf("hud", "target", "party", "compass", "psarrow", "psguide", "nbar", "chat"), e.tiles().map { it.id })
        val mini = LayoutView::class.java.getDeclaredField("mini").apply { isAccessible = true }.get(v) as android.graphics.RectF
        for (id in e.tiles().map { it.id }) assertTrue("$id's tile inside the column (the rows fit)", tileBox(v, id).bottom <= mini.bottom + 1f)
        shot(v, "layout_polestar")
        e.tile = e.tiles().indexOfFirst { it.id == "psguide" }
        v.padKey("PAD1")
        assertEquals("A picks Polestar's guide", "psguide", e.picked)
        v.padKey("PADDLEFT"); v.padKey("PADDLEFT")
        assertEquals(e.doc!!.piece("psguide")!!.ax - 2.0, e.want!!.first, 0.0)
        lay(v)
        shot(v, "layout_polestar_guide")
        // the guide window hidden, our target bar turned off: both leave the page at the next document, the guide let go
        feed(v, "lay_off"); v.onDoc(); lay(v)                          // (a detached view's post never runs: its handler by hand)
        assertEquals(listOf("hud", "party", "compass", "psarrow", "nbar", "chat"), e.tiles().map { it.id })
        assertNull(e.picked)
        shot(v, "layout_off")
    }

    // ---------------------------------------------------------------- hub59: the game's own UI as pieces
    @Test fun theGamesFramesInTheList() {
        val v = page("lay_game")
        val e = v.editor
        assertEquals(11, e.tiles().size)
        shot(v, "layout_game")
        e.tile = e.tiles().indexOfFirst { it.id == "g.DamageMeter" }
        lay(v)
        assertNotNull("the last game tile scrolled into the column", tileBox(v, "g.DamageMeter"))
        v.padKey("PAD1")
        assertEquals("g.DamageMeter", e.picked)
        v.padKey("PADDDOWN"); v.padKey("PADDDOWN")
        assertEquals(Math.round(e.doc!!.piece("g.DamageMeter")!!.ay + 2.0).toDouble(), e.want!!.second, 0.0)
        lay(v)
        shot(v, "layout_game_meter")
        val q = page("lay_queued")
        assertEquals("Moves after the fight", LayoutModel.statusOf(q.editor.doc))
        shot(q, "layout_game_queued")
    }

    // ---------------------------------------------------------------- the Settings row
    @Test fun settingsShowsLayoutFirstOnTopScreen() {
        art()
        LayoutModel.Live.clearForTest()
        val v = SettingsView(app) { }
        var opened = 0
        v.onEditLayout = { opened++ }
        val r = SettingsRoom(app, v) { null }
        lay(r)
        r.show(SettingsTabs.Tab.TOP); lay(r)
        val layoutBox = r.groupBoxes.first { it.group == SettingsTabs.Group.LAYOUT }
        assertEquals("no editor in the add-on set: no Layout group", View.GONE, layoutBox.box.visibility)
        LayoutModel.Live.note(fx("lay_closed"))
        ShadowLooper.idleMainLooper(); lay(r)
        r.show(SettingsTabs.Tab.GENERAL); r.show(SettingsTabs.Tab.TOP); lay(r)
        assertEquals(View.VISIBLE, layoutBox.box.visibility)
        // (11-16 merge with the tiles: Top screen does not fold, so every group opens; Layout is the first)
        assertTrue("Layout opens", SettingsTabs.Group.LAYOUT in r.open)
        assertEquals("Layout is the first group", SettingsTabs.Group.LAYOUT, SettingsTabs.groups(SettingsTabs.Tab.TOP).first())
        val part = layoutBox.parts.single() as SettingsView
        assertEquals(listOf("Edit layout"), SettingsTabs.Block.LAYOUT.rows)
        // the row's Open, pressed by a finger (the cursor's A presses it the same way)
        val b = part.focusTargets().single()
        // (a detached view is never isShown(): the tap itself, as the touch's lift calls it)
        SettingsView::class.java.getDeclaredMethod("tap", java.lang.Float.TYPE, java.lang.Float.TYPE).apply { isAccessible = true }
            .invoke(part, b.centerX(), b.centerY())
        assertEquals(1, opened)
        val d = out ?: return
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink)
        lay(r); r.draw(c)
        val strip = Strip(app) { }.apply { active = Room.SETTINGS }
        strip.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        strip.layout(0, 0, W, Strip.HEIGHT)
        c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
        File(d, "settings_top_layout.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(d)
    }
}
