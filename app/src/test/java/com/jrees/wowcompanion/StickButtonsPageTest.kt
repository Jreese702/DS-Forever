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
 * p49 reads 9 / 10); p52 / m157: Change opens the eight's chooser (all five tabs); a stick never Moves; In combat has no sticks; a taken stick draws (the add-on's st = "taken"). tools/test_stick_binds.py proves the add-on side.
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
        val (lx, ly) = PadDiamonds.centre(PadMapModel.POS_L3, true)          // m155: the pair below the diamonds
        r.tap(lx, ly)
        assertEquals(listOf(PadMapModel.stickCells(PadMapModel.POS_L3)), r.cells)
        assertEquals(listOf("talentn" to 38, "talents" to 15), PadMapModel.stickCells(PadMapModel.POS_L3))
        r.cells.clear()
        val (rx, ry) = PadDiamonds.centre(PadMapModel.POS_R3, true)
        r.tap(rx, ry)
        assertEquals(listOf(listOf("talentn" to 38, "talents" to 16)), r.cells)
    }

    /** p52 / m157 (the user, 2026-11-13: "looking good, except people also want to be able to bind any of their spells and stuff to
     *  these, so any of those 5 controller tabs should be able to bind here"): Change on a stick opens the eight's chooser (Spells
     *  first, all five tabs); a spell row is that spell's ONE Bind tap, an Extra row the "extra" row's put, as on any button */
    @Test fun changeOnAStickOpensAllFiveTabs() {
        val r = Rig(map(empty), ctl(9))
        r.tap(change.first, change.second)
        assertTrue("Choose on an empty L3 opens the chooser", r.page.chooserOpen())
        assertEquals("the chooser opens on Spells", "spells", r.page.chooserTab())
        assertEquals(ChooserTabs.ALL_TABS, ChooserTabs.keys(false, stick = true))
    }

    @Test fun aStickHoldingASpellReadsAsOne() {
        val spell = """{"pos":10,"set":2,"kind":"spell","id":403,"n":"Lightning Bolt","i":136048,"stick":"R3"}"""
        val cmd = """{"pos":9,"set":1,"kind":"command","key":"DSMAP","n":"Open Map","i":134269,"stick":"L3","ov":true}"""
        val m = map("$cmd,$spell")
        val zr3 = PadMapModel.slot(m, 2, PadMapModel.POS_R3)!!
        assertEquals(PadMapModel.Kind.SPELL, zr3.kind)
        assertEquals("the stick's spell id is read (m157)", 403, zr3.id)
        assertEquals("Lightning Bolt", zr3.name)
        val l3 = PadMapModel.slot(m, 1, PadMapModel.POS_L3)!!
        assertEquals(PadMapModel.Kind.COMMAND, l3.kind)
        assertEquals("DSMAP", l3.key)
        assertTrue(l3.ov)
        assertTrue("a stick's spell is something a put replaces (its Replace box)", PadMapModel.replaces(m, 2, PadMapModel.POS_R3) != null)
        val r = Rig(m, ctl(9))
        r.draw()
    }

    @Test fun moveIsNeverOnAStick() {
        val spell = """{"pos":9,"set":1,"kind":"spell","id":403,"n":"Lightning Bolt","i":136048,"stick":"L3"}"""
        val r = Rig(map(spell), ctl(9))
        r.tap(990f, h - 206f)                                // the Move button (h-252..h-160)
        assertTrue("no Move tap goes out from a stick: ${r.cells}", r.cells.none { it == listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_CARRY) })
    }

    /** Book b57: the Spellbook's Bind box answers a stick press with p = 9 / 10 and no bar slot; its combo is the stick's glyph */
    @Test fun theBindBoxAnswerNamesAStick() {
        val r = SpellsModel.parseBind(JSONObject("""{"type":"spellsack","op":"bind","v":"b57","id":403,"ok":true,"pad":{"pg":1,"b":2,"p":10}}"""))!!
        assertTrue("a stick answer with no bar slot is a bind that took", r.ok)
        assertEquals(10, r.pad!!.pos)
        assertEquals(2, r.pad!!.bar)
        assertEquals("gen_rstickin", SpellsModel.combo(r.pad!!, SpellsModel.PadStyle.REV).button)
        val face = SpellsModel.parseBind(JSONObject("""{"type":"spellsack","op":"bind","v":"b57","id":403,"ok":true,"pad":{"pg":1,"b":1,"p":5}}"""))!!
        assertFalse("a bar button with no slot is still no answer", face.ok)
    }

    /** Book b57: the Bind box's L3 / R3 tiles stand beside the trigger chips (ZL w/2-106..-22, ZR w/2+22..+106), inside the box */
    @Test fun theBindBoxSticksSitBesideTheChips() {
        val o = SpellsOverlay(RuntimeEnvironment.getApplication())
        val w = 1240f; val cy = 934f - 92f
        val l3 = o.stickRect(PadMapModel.POS_L3, w, cy)
        val r3 = o.stickRect(PadMapModel.POS_R3, w, cy)
        assertTrue("L3 left of ZL's chip: $l3", l3.right <= w / 2f - 106f - 8f)
        assertTrue("R3 right of ZR's chip: $r3", r3.left >= w / 2f + 106f + 8f)
        assertTrue("inside the box's foot (934)", l3.bottom <= 934f - 16f && r3.bottom <= 934f - 16f)
        assertEquals(l3.width(), r3.width(), 0.01f)
    }

    @Test fun inCombatHasNoSticks() {
        val r = Rig(map(empty), ctl(4, view = "combat"))
        PadDiamonds.centre(PadMapModel.POS_L3, true).let { (x, y) -> r.tap(x, y) }
        assertTrue("no stick pick on the In combat tab: ${r.cells}", r.cells.none { it == PadMapModel.stickCells(PadMapModel.POS_L3) })
    }

    @Test fun aTakenStickDraws() {
        val taken = autoRun.replace("\"stick\":\"L3\"", "\"stick\":\"L3\",\"st\":\"taken\"")
        val r = Rig(map(taken), ctl(9))
        r.draw()
        assertTrue(PadMapModel.slot(map(taken), 1, 9)!!.taken)
    }
}
