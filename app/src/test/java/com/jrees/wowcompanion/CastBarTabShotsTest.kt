package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * p53 THE CONTROLLER ROOM'S CAST BAR TAB (the user, 2026-11-13: "in the controller menu we need a Tab for the cast bar if its enabled,
 * where people can assign anything from the 5 tabs"; PadTabCtl p53, ActionBar cb9): the tab shows only while Settings' bar is not Off
 * (and the add-on's bar takes every kind); its 8 slots as one row; a finger on a slot is button code k; Change opens the eight's
 * chooser (all five tabs, no game binding); Move is the eight's padtab 3; the try prompt's Yes ("if they press yes on the prompt to
 * try the tap to cast bar, we should open them to the spell bind room that you are making so they can add spells to it") opens the
 * room on that tab, slot 1. With CASTBAR_TAB_SHOTS_DIR set the room is drawn as ctltab_<name>.png (1240 x 960); CLOCK_ART_ROOT = an
 * art root laid out like the app's. tools/test_castbar_tab.py proves the add-on side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastBarTabShotsTest {
    private val out: File? = System.getenv("CASTBAR_TAB_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private var sideWas = CastStripModel.Side.OFF
    @Before fun up() { artRoot(); HomeSettings.init(app); sideWas = HomeSettings.castBarSide; HomeSettings.castBarSide = CastStripModel.Side.BOTH }
    @After fun down() { HomeSettings.castBarSide = sideWas }

    /** a level-6 Shaman's bar with every kind: two spells, a command, a DS command, a consumable stack, the Hearthstone, a mount */
    private val barDoc = JSONObject("""{"type":"castbar","v":"cb9","n":8,"any":1,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":331,"i":136052,"c":1},
        {"s":3,"k":"command","key":"TARGETNEARESTENEMY","n":"Target Nearest Enemy","i":132212,"c":2},
        {"s":4,"k":"command","key":"DSMAP","n":"Open Map","i":134269,"c":3},
        {"s":5,"k":"item","id":118,"n":"Minor Healing Potion","i":134829,"ct":7,"cons":1,"c":4},
        {"s":6,"k":"item","id":6948,"n":"Hearthstone","i":134414,"ct":1,"c":5},
        {"s":7,"k":"mount","id":6,"n":"Brown Horse","i":132261,"sid":458,"c":6}]}""")

    private fun map() = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m159","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":4,"s":4,"kind":"none"}]}],
        "commands":[{"key":"TARGETNEARESTENEMY","n":"Target Nearest Enemy","i":132212,"made":true,"b":"/targetenemy"},
                {"key":"DSMAP","n":"Open Map","i":134269,"made":true,"b":"/click WowCompanionPadMap"}],
        "binds":[{"key":"JUMP","n":"Jump","i":135992},{"key":"TOGGLEAUTORUN","n":"Auto Run","i":132307}],
        "mounts":[{"id":6,"n":"Brown Horse","i":132261}],
        "items":[{"id":118,"n":"Minor Healing Potion","i":134829},{"id":6948,"n":"Hearthstone","i":134414}]}}"""))!!

    private fun book() = SpellsModel.parse(JSONObject("""{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General"},{"k":2,"n":"Elemental"}],
        "items":[{"n":"Lightning Bolt","t":1,"id":403,"a":403,"sl":1,"ln":2,"i":136048},{"n":"Healing Wave","t":1,"id":331,"a":331,"sl":2,"ln":2,"i":136052},
                 {"n":"Earth Shock","t":1,"id":8042,"a":8042,"sl":3,"ln":2,"i":136026}],
        "win":{"croom":5,"open":0,"close":1}}}"""))!!

    private fun ctl(pos: Int, extra: String = "", view: String = "castbar", focus: String = "buttons", tcur: Int = 6) =
        PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p53","c":{"on":true,
        "focus":"$focus","set":1,"shown":1,"pos":$pos,"page":1,"view":"$view","lay":"swap","tcur":$tcur$extra}}"""))!!

    private class Rig(val v: ControllerView) { val cells = ArrayList<List<Pair<String, Int>>>() }

    private fun rig(bar: JSONObject? = barDoc): Rig {
        bar?.let { CastStripModel.Live.set(CastStripModel.parse(it)!!) }
        lateinit var r: Rig
        val v = ControllerView(app, { c -> r.cells += c; true })
        r = Rig(v)
        v.update(book())
        v.onPadMap(map())
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return r
    }

    private fun Rig.draw(name: String? = null) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        if (name != null && out != null) File(out, "ctltab_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun Rig.tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        draw()
    }

    /** the left page's slot tile centres (PadTabPage's CB_* : 8 x 72 px, 12 apart, centred on the left page, top 360) */
    private fun tile(p: Int): Pair<Float, Float> {
        val x0 = (8f + 740f) / 2f - (8 * 72f + 7 * 12f) / 2f
        return x0 + (p - 1) * 84f + 36f to 396f
    }
    private val change = 990f to h - 85f

    @Test fun theTabShowsOnlyWhileTheBarIsOn() {
        val r = rig()
        assertTrue(r.v.tabViews().contains("castbar"))
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        assertFalse("Off: no Cast bar tab", r.v.tabViews().contains("castbar"))
        HomeSettings.castBarSide = CastStripModel.Side.LEFT
        assertTrue(r.v.tabViews().contains("castbar"))
        CastStripModel.Live.set(CastStripModel.parse(JSONObject("""{"type":"castbar","v":"cb6","n":8,"e":[]}"""))!!)
        assertFalse("an add-on bar of spells only (cb6): no tab", r.v.tabViews().contains("castbar"))
    }

    @Test fun theRoomOpenTellsTheAddOnTheSetting() {
        val r = rig()
        assertTrue(r.v.enterRoom())
        val first = r.cells.first { it.contains("spells" to 5) }
        assertEquals("the setting before the open, then the croom tap", listOf("padtab" to 39, "padtab" to 30, "spells" to 5), first)
    }

    @Test fun aFingerOnASlotIsItsButtonCode() {
        val r = rig()
        r.v.onPadTabCtl(ctl(1))
        r.draw()
        for (p in listOf(3, 8)) {
            r.cells.clear()
            val (x, y) = tile(p)
            r.tap(x, y)
            assertEquals("slot $p", listOf(listOf(PadMapModel.ROW to PadMapModel.colPos(p))), r.cells)
        }
    }

    @Test fun changeOpensTheFiveTabsWithoutBindings() {
        val r = rig()
        r.v.onPadTabCtl(ctl(8))
        r.draw()
        r.tap(change.first, change.second)
        assertTrue("Choose on an empty slot opens the chooser", r.v.page().chooserOpen())
        assertEquals("spells", r.v.page().chooserTab())
        assertEquals(ChooserTabs.ALL_TABS, ChooserTabs.keys(false))
        val r2 = rig()                                                  // the controller's chooser on Extra
        r2.v.onPadTabCtl(ctl(8, ""","chooser":{"list":"extra","row":1,"id":"TARGETNEARESTENEMY"}"""))
        val ids = r2.v.page().chooserRowIds(book())
        assertTrue("Extra lists the commands, mounts, items: $ids", ids.containsAll(listOf("TARGETNEARESTENEMY", "m:6", "i:118")))
        assertFalse("no game binding on the cast bar: $ids", ids.any { it == "JUMP" || it == "TOGGLEAUTORUN" || it == "h:Controller" })
    }

    @Test fun moveIsTheEightsPickUp() {
        val r = rig()
        r.v.onPadTabCtl(ctl(3))
        r.draw()
        r.cells.clear()
        r.tap(990f, h - 206f)                                            // Move (h-252..h-160)
        assertEquals(listOf(listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_CARRY)), r.cells)
    }

    /** the try prompt's Yes: the next room open lands on the Cast bar tab, slot 1 (once), by the room's own open */
    @Test fun yesOnTheTryPromptOpensTheCastBarTab() {
        CastBarAsk.answer(true)
        assertEquals(CastStripModel.Side.BOTH, HomeSettings.castBarSide)
        assertTrue("Home free: open now", CastBarAsk.openTabNow(true, onHome = true, fight = false, capFree = true, busy = false))
        assertFalse("a fight: later", CastBarAsk.openTabNow(true, onHome = true, fight = true, capFree = true, busy = false))
        assertFalse("a room holds the pad: later", CastBarAsk.openTabNow(true, onHome = true, fight = false, capFree = false, busy = false))
        assertFalse("done once", CastBarAsk.openTabNow(false, onHome = true, fight = false, capFree = true, busy = false))
        val r = rig(JSONObject("""{"type":"castbar","v":"cb9","n":8,"any":1,"e":[]}"""))
        r.v.openOnCastBar()
        assertTrue(r.v.enterRoom())
        val open = r.cells.first { it.contains("spells" to 5) }
        assertEquals("the setting, the room's own open, then the Cast bar tab and slot 1",
            listOf("padtab" to 39, "padtab" to 30, "spells" to 5, "padtab" to 39, "padtab" to 29, PadMapModel.ROW to PadMapModel.colPos(1)), open)
        assertEquals("castbar", r.v.page().currentView())
        assertFalse("once", r.v.castBarOwed)
        r.cells.clear()
        r.v.enterRoom()
        assertFalse("the next open is the usual one", r.cells.first().contains("padtab" to 29))
    }

    @Test fun shots() {
        if (out == null) return
        val r = rig()
        r.v.onPadTabCtl(ctl(3)); r.draw("slots")
        r.v.onPadTabCtl(ctl(5)); r.draw("item")
        r.v.onPadTabCtl(ctl(8)); r.draw("empty")
        r.v.onPadTabCtl(ctl(1, focus = "tabs")); r.draw("tabs")
        r.v.onPadTabCtl(ctl(8, ""","chooser":{"list":"spells","row":1,"id":"403"}""")); r.draw("chooser_spells")
        r.v.onPadTabCtl(ctl(8, ""","chooser":{"list":"extra","row":2,"id":"TARGETNEARESTENEMY"}""")); r.draw("chooser_extra")
        r.v.onPadTabCtl(ctl(8, ""","chooser":{"list":"dstool","row":1,"id":"DSMAP"}""")); r.draw("chooser_ds")
        r.v.onPadTabCtl(ctl(6, ""","carry":{"set":1,"pos":3,"page":1}""")); r.draw("move")
        r.v.onPadTabCtl(ctl(1, ""","box":{"kind":"replace","thing":"spell","what":"331"}""")); r.draw("replace")
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        r.v.onPadTabCtl(ctl(1, view = "normal", focus = "tabs", tcur = 1)); r.draw("off_tabs")
    }
}
