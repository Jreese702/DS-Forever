package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * p58 THE CHOOSER AS A GRID, in the room (the user, 2026-11-20: "maybe a grid similar to the AH ... the 5th tab could easily be a
 * category in tab 4, we also need to add macros here as an option"): the tabs (Spells, General, Professions, Extra, Macros), the name
 * header, a finger on a grid cell / a category header / a macro / a chooser tab, each one's taps. tools/test_chooser_grid.py proves the
 * add-on half (the walk, the folds, the puts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChooserGridRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960
    private var ranksWas = false

    @Before fun up() {
        HomeSettings.init(app); ranksWas = HomeSettings.rankBadges
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() { HomeSettings.rankBadges = ranksWas }

    private class Rig(val v: ControllerView) { val cells = ArrayList<List<Pair<String, Int>>>() }

    private fun rig(): Rig {
        lateinit var r: Rig
        val v = ControllerView(app, { c -> r.cells += c; true })
        r = Rig(v)
        val book = SpellsModel.parse(JSONObject(ChooserGridShotsTest.BOOK))!!
        SpellRanks.update(book)
        v.update(book)
        v.onPadMap(PadMapModel.parse(JSONObject(ChooserGridShotsTest.MAP))!!)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return r
    }

    private fun ctl(list: String, id: String?, row: Int = 1, tabs: Boolean = false, xo: String? = null, view: String = "normal",
                    pos: Int = 4, v: String = "p58"): PadMapModel.Ctl {
        val ch = JSONObject().put("list", list).put("row", row).put("tabs", tabs)
        id?.let { ch.put("id", it) }
        val c = JSONObject("""{"on":true,"focus":"buttons","set":1,"shown":1,"pos":$pos,"page":1,"view":"$view","top":"${PadMapModel.topOf(view)}",
            "lay":"swap","tcur":1}""").put("chooser", ch)
        xo?.let { c.put("xo", it) }
        return PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", v).put("c", c))!!
    }

    private fun Rig.draw() { val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b)) }
    private fun Rig.tap(x: Float, y: Float) {
        draw()
        val t = SystemClock.uptimeMillis()
        v.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.onTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        draw()
    }
    private fun Rig.tap(r: RectF) = tap(r.centerX(), r.centerY())
    private fun tabs(page: PadTabPage) = (PadTabPage::class.java.getDeclaredField("cGaps").apply { isAccessible = true }.get(page) as UiKit.TabGaps).rects

    /** the chooser's list view and row boxes as drawn (PadTabPage.chooserLay) */
    private fun cellRect(page: PadTabPage, i: Int): RectF {
        val m = PadTabPage::class.java.declaredMethods.first { it.name == "chooserLay" }.apply { isAccessible = true }
        val map = PadTabPage::class.java.getDeclaredField("map").apply { isAccessible = true }.get(page)
        val ch = page.chooserTab()!!
        val book = SpellsModel.parse(JSONObject(ChooserGridShotsTest.BOOK))!!
        val lay = m.invoke(page, map, book, ch, w.toFloat(), h.toFloat())!!
        fun <T> f(n: String) = lay.javaClass.getDeclaredField(n).apply { isAccessible = true }.get(lay) as T
        val view: RectF = f("view"); val boxes: List<ChooserGrid.Box> = f("boxes")
        val b = boxes[i]
        return RectF(view.left + b.left, view.top + b.top, view.left + b.right, view.top + b.bottom)
    }

    @Test fun theTabsAreSpellsGeneralProfessionsExtraMacros() {
        val r = rig()
        r.v.onPadTabCtl(ctl("spells", "403")); r.draw()
        assertEquals(listOf("spells", "general", "professions", "extra", "macros"), ChooserTabs.ALL_TABS)
        assertEquals(5, tabs(r.v.page()).size)
        // an older add-on's "dstool" list is Extra now
        r.v.onPadTabCtl(ctl("dstool", "DSMAP"))
        assertEquals("extra", r.v.page().chooserTab())
    }

    @Test fun aFingerOnAChooserTabMovesTheControllersChooserAndKeepsItsCursor() {
        val r = rig()
        r.v.onPadTabCtl(ctl("spells", "8042", 2)); r.draw()
        val page = r.v.page()
        r.cells.clear()
        r.tap(tabs(page)[4])                                             // Macros
        assertEquals(listOf(ChooserTabs.touchCells("macros")), r.cells)
        assertEquals(listOf(PadMapModel.ROW_PADTAB to 39, PadMapModel.ROW_PADTAB to 38, PadMapModel.ROW_PADTAB to 10), r.cells.single())
        assertEquals("the finger's tab shows at once", "macros", page.chooserTab())
        // the add-on agrees (its cursor on the Macros tab), then Down: its first cell - the CONTROLLER's chooser all along
        r.v.onPadTabCtl(ctl("macros", "mc:Bravo", 1, tabs = true))
        r.v.onPadTabCtl(ctl("macros", "mc:Bravo", 1))
        assertEquals("macros", page.chooserTab())
        assertEquals(listOf("mc:Bravo", "mc:Totems", "mc:Heal Me", "mc:Pull"), page.chooserRowIds(null))
    }

    @Test fun aGridCellByTouchPutsThatSpell() {
        val r = rig()
        // In combat on the empty D-pad Down: a spell goes straight on (no box): the combat pick, then the spell's own Bind cell
        r.v.onPadTabCtl(ctl("spells", "403", view = "combat", pos = 4)); r.draw()
        val page = r.v.page()
        assertEquals("403", page.chooserRowIds(SpellsModel.parse(JSONObject(ChooserGridShotsTest.BOOK))!!)[0])
        r.cells.clear()
        r.tap(cellRect(page, 0))                                         // the first cell: Lightning Bolt
        assertEquals(listOf(listOf(PadMapModel.ROW_LAYOUT to PadMapModel.COL_COMBAT_LAYER, "spells" to 2)), r.cells)
    }

    @Test fun extraIsFoldingCategoriesAndAFingerFoldsThem() {
        val r = rig()
        r.v.onPadTabCtl(ctl("extra", "h:cmd")); r.draw()
        val page = r.v.page()
        assertEquals(listOf("h:cmd", "h:ds", "h:bind", "h:mount", "h:item"), page.chooserRowIds(null))
        r.v.onPadTabCtl(ctl("extra", "DSMAP", 5, xo = "ds"))
        assertEquals(listOf("h:cmd", "h:ds", "DSMAP", "DSCHAT", "DSRADIAL", "h:bind", "h:mount", "h:item"), page.chooserRowIds(null))
        r.cells.clear()
        r.tap(cellRect(page, 0))                                         // Commands' header: opens it (no game action), the add-on told
        assertEquals(listOf(PadMapModel.foldCells(1)), r.cells)
        assertEquals(listOf(PadMapModel.ROW_PADTAB to 39, PadMapModel.ROW_PADTAB to 38, PadMapModel.ROW_PADTAB to 37, PadMapModel.ROW_PADTAB to 4),
            r.cells.single())
        assertTrue("shown open at once", page.chooserRowIds(null).containsAll(listOf("TARGETNEARESTENEMY", "ASSIST", "FOLLOW")))
    }

    @Test fun aMacroByTouchIsItsPagePickAndTheExtraColumn() {
        val r = rig()
        r.v.onPadTabCtl(ctl("macros", "mc:Bravo", pos = 4)); r.draw()
        val page = r.v.page()
        r.cells.clear()
        r.tap(cellRect(page, 1))                                         // Totems on an empty button: straight on
        assertEquals(listOf(PadMapModel.macroCells(1)), r.cells)
        assertEquals(listOf(PadMapModel.ROW_PADTAB to 39, PadMapModel.ROW_PADTAB to 38, PadMapModel.ROW_PADTAB to 37, PadMapModel.ROW_PADTAB to 0,
            PadMapModel.ROW_EXTRA to 1), r.cells.single())
        assertEquals(PadMapModel.ROW_PADTAB to 2, PadMapModel.macroCells(85)[3])          // macro 86: page 2 (from 80), column 5
        assertEquals(PadMapModel.ROW_EXTRA to 5, PadMapModel.macroCells(85)[4])
    }

    @Test fun theMacrosModelAndTheOpenCategoriesParse() {
        val m = PadMapModel.parse(JSONObject(ChooserGridShotsTest.MAP))!!
        assertEquals(listOf("Bravo", "Totems", "Heal Me", "Pull"), m.macs.map { it.name })
        assertEquals(setOf("cmd", "ds"), ctl("extra", null, xo = "cmd,ds").xo)
        assertEquals("p58 without xo: all folded", emptySet<String>(), ctl("extra", null).xo)
        assertNull("an older add-on: no word", ctl("extra", null, v = "p56").xo)
    }
}
