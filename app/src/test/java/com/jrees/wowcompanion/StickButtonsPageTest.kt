package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * m150 / p49 STICK BUTTONS (GitHub #78, the 1.0.18 player report: "No way to remap auto run to L3 on contrller"): the Controller tab's
 * WoW Defaults draws L3 / R3 low between the diamonds; a finger's pick is the one-shot talentn 38 pick + button code 1 / 2 (PadTabCtl
 * p49 reads 9 / 10); Change opens the chooser on Extra with the four game bindings alone, a row is the "extra" row's ONE put; In combat
 * has no sticks; a taken stick draws (the add-on's st = "taken"). tools/test_stick_binds.py proves the add-on side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickButtonsPageTest {
    private val w = 1240f
    private val h = 960f

    private fun map(sticks: String) = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m150","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":4,"s":4,"kind":"none"}]}],
        "binds":[{"key":"JUMP","n":"Jump","i":135992},{"key":"INTERACTTARGET","n":"Interact With Target","i":132938},
                 {"key":"TOGGLEAUTORUN","n":"Auto Run","i":132307},{"key":"TOGGLERUN","n":"Walk / Run","i":132539}],
        "sticks":[$sticks]}}"""))!!

    private fun ctl(pos: Int, view: String = "normal") = PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p49","c":{"focus":"buttons",
        "set":1,"shown":1,"pos":$pos,"page":1,"view":"$view","lay":"swap"}}"""))!!

    private class Rig(m: PadMapModel.Map, c: PadMapModel.Ctl) {
        val cells = ArrayList<List<Pair<String, Int>>>()
        val host = View(RuntimeEnvironment.getApplication())
        val page = PadTabPage(host, { cells += it }, {}, { false })
        init { page.onMap(m); page.onCtl(c); draw() }
        fun draw() {
            val b = Bitmap.createBitmap(1240, 960, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(b), 1240f, 960f, null)
        }
        fun tap(x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            page.onTouch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0), 1240f, 960f, null)
            page.onTouch(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0), 1240f, 960f, null)
            draw()
        }
    }

    private val empty = """{"pos":9,"set":1,"kind":"none","stick":"L3"},{"pos":10,"set":1,"kind":"none","stick":"R3"}"""
    private val autoRun = """{"pos":9,"set":1,"kind":"bind","key":"TOGGLEAUTORUN","n":"Auto Run","i":132307,"stick":"L3"}"""
    private val change = 990f to h - 85f                    // the right page's Choose / Change (h-141..h-30)

    @Test fun theModelReadsTheSticks() {
        val m = map("$autoRun,{\"pos\":10,\"set\":2,\"kind\":\"bind\",\"key\":\"TOGGLERUN\",\"n\":\"Walk / Run\",\"stick\":\"R3\",\"st\":\"taken\"}")
        val l3 = PadMapModel.slot(m, 1, PadMapModel.POS_L3)!!
        assertEquals(PadMapModel.Kind.BIND, l3.kind)
        assertEquals("TOGGLEAUTORUN", l3.key)
        assertEquals("L3", l3.stick)
        assertFalse(l3.taken)
        val zr3 = PadMapModel.slot(m, 2, PadMapModel.POS_R3)!!
        assertTrue("ZL + R3 taken back by the game", zr3.taken)
        assertEquals("an empty stick reads as an empty place", PadMapModel.Kind.NONE, PadMapModel.slot(m, 2, PadMapModel.POS_L3)!!.kind)
        assertEquals("the eight buttons' sets carry no stick", listOf(4), m.sets.flatMap { it.slots }.map { it.pos })
        assertEquals("a controller cursor on R3", 10, ctl(10).pos)
    }

    @Test fun aFingerOnL3PicksItByTheOneShotPick() {
        val r = Rig(map(empty), ctl(4))
        r.tap(290f, 690f)
        assertEquals(listOf(PadMapModel.stickCells(PadMapModel.POS_L3)), r.cells)
        assertEquals(listOf("talentn" to 38, "talents" to 15), PadMapModel.stickCells(PadMapModel.POS_L3))
        r.cells.clear()
        r.tap(456f, 690f)
        assertEquals(listOf(listOf("talentn" to 38, "talents" to 16)), r.cells)
    }

    @Test fun changeOnAStickOpensTheBindingsAndARowIsTheExtraPut() {
        val r = Rig(map(empty), ctl(9))
        r.tap(change.first, change.second)
        assertTrue("Choose on an empty L3 opens the chooser", r.page.chooserOpen())
        // the rows: Jump, Interact, Auto Run, Walk / Run (no headers, no spells): Auto Run is the 3rd (list top 296, 92 a row)
        r.tap(990f, 296f + 2 * 92f + 46f)
        assertEquals(listOf(PadMapModel.extraCells(2)), r.cells)
        assertFalse(r.page.chooserOpen())
    }

    @Test fun inCombatHasNoSticks() {
        val r = Rig(map(empty), ctl(4, view = "combat"))
        r.tap(290f, 690f)
        assertTrue("no stick pick on the In combat tab: ${r.cells}", r.cells.none { it == PadMapModel.stickCells(PadMapModel.POS_L3) })
    }

    @Test fun aTakenStickDraws() {
        val taken = autoRun.replace("\"stick\":\"L3\"", "\"stick\":\"L3\",\"st\":\"taken\"")
        val r = Rig(map(taken), ctl(9))
        r.draw()
        assertTrue(PadMapModel.slot(map(taken), 1, 9)!!.taken)
    }
}
