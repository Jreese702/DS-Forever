package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * m155 / p51 (the user, 2026-11-13: "L3 and R3 should show the wow default options on them by default, I also want those to be slightly
 * smaller and moved away from the other two cross's to make it clear what they are."): L3 / R3 are a smaller pair of their own, apart
 * from both diamonds and from everything else on the Controller tab's left page (the Thor's bottom screen, 1240 x 960), and an empty
 * stick shows the game's own binding (the doc's gd / gn / gi) until one of ours is put. tools/test_stick_binds.py 9 proves the add-on side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickTilesV2Test {
    private fun box(p: Int, swap: Boolean) = PadDiamonds.square(p, swap)
    private fun overlap(a: PartyLayout.Box, b: PartyLayout.Box) = a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b
    private fun gap(a: PartyLayout.Box, b: PartyLayout.Box): Float {
        val dx = maxOf(0f, maxOf(a.l - b.r, b.l - a.r)); val dy = maxOf(0f, maxOf(a.t - b.b, b.t - a.b))
        return maxOf(dx, dy)
    }

    @Test fun theSticksAreSmallerThanTheCrossTiles() {
        for (swap in listOf(false, true)) {
            for (p in PadMapModel.STICKS) {
                val b = box(p, swap)
                assertTrue("stick $p smaller than a button (80)", b.r - b.l < 80f && b.b - b.t < 80f)
                assertTrue("... but not tiny (a finger / the glyph)", b.r - b.l >= 60f)
            }
            for (p in 1..8) box(p, swap).let { assertEquals(80f, it.r - it.l, 0.01f) }
        }
    }

    @Test fun theSticksSitApartFromBothCrossesAndEverythingElse() {
        for (swap in listOf(false, true)) {
            val l3 = box(PadMapModel.POS_L3, swap); val r3 = box(PadMapModel.POS_R3, swap)
            assertTrue("L3 left of R3, a gap between", l3.r < r3.l)
            for (p in 1..8) {
                val b = box(p, swap)
                for (st in listOf(l3, r3)) {
                    assertTrue("no overlap with button $p", !overlap(st, b))
                    assertTrue("clearly apart from button $p (>= 24 px): ${gap(st, b)}", gap(st, b) >= 24f)
                }
            }
            // below both crosses, centred between them
            val crossBottom = (1..8).maxOf { box(it, swap).b }
            assertTrue("the pair below the crosses' bottom row", l3.t > crossBottom && r3.t > crossBottom)
            val mid = (l3.l + r3.r) / 2f
            assertEquals("centred between the two diamonds (190 / 556)", (190f + 556f) / 2f, mid, 1f)
            // the rest of the left page: the set row, the form / Touch row, the page arrows (bookPaging at h - 70, 72 px arrows at +-150),
            // the form note's line, the page's edge (SPLIT 740) and the screen's (960)
            val others = (0..2).map { PadDiamonds.setRect(it) } + (0..5).map { PadDiamonds.formRect(it) } + (0..1).map { PadDiamonds.touchRect(it) } +
                listOf(PartyLayout.Box(330f - 150f - 36f, 890f - 36f, 330f - 150f + 36f, 890f + 36f),
                       PartyLayout.Box(330f + 150f - 36f, 890f - 36f, 330f + 150f + 36f, 890f + 36f),
                       PartyLayout.Box(330f - 110f, 890f - 16f, 330f + 110f, 890f + 16f),                  // "Page 1 / 3"
                       PartyLayout.Box(0f, PadTabPage.FORM_NOTE_Y - 20f, 740f, PadTabPage.FORM_NOTE_Y + 6f))  // the form note
            for (o in others) for (st in listOf(l3, r3)) assertTrue("no overlap with $o", !overlap(st, o))
            for (st in listOf(l3, r3)) assertTrue("on the left page", st.l > 0f && st.r < 740f && st.b < 960f)
        }
    }

    private fun map(sticks: String) = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m155","m":{"page":1,"pages":3,
        "sets":[{"set":1,"slots":[{"pos":4,"s":4,"kind":"none"}]}],
        "binds":[{"key":"JUMP","n":"Jump","i":135992},{"key":"INTERACTTARGET","n":"Interact With Target","i":132938},
                 {"key":"TOGGLEAUTORUN","n":"Auto Run","i":132307},{"key":"TOGGLERUN","n":"Walk / Run","i":132539}],
        "sticks":[$sticks]}}"""))!!

    private fun page(m: PadMapModel.Map, pos: Int, view: String = "normal"): PadTabPage {
        val host = View(RuntimeEnvironment.getApplication())
        val p = PadTabPage(host, { }, {}, { false })
        p.onMap(m)
        p.onCtl(PadMapModel.parseCtl(JSONObject("""{"type":"padtabctl","v":"p51","c":{"focus":"buttons","set":1,"shown":1,"pos":$pos,
            "page":1,"view":"$view","lay":"swap"}}"""))!!)
        p.draw(Canvas(Bitmap.createBitmap(1240, 960, Bitmap.Config.ARGB_8888)), 1240f, 960f, null)
        return p
    }

    private val game = """{"pos":9,"set":1,"kind":"none","stick":"L3","gd":"TOGGLERUN","gn":"Walk / Run","gi":132539},""" +
        """{"pos":10,"set":1,"kind":"none","stick":"R3","gd":"SPELL Frost Nova","gn":"Frost Nova","gi":0}"""

    @Test fun anEmptyStickShowsTheGamesOwn() {
        val m = map(game)
        val l3 = PadMapModel.slot(m, 1, PadMapModel.POS_L3)!!
        assertEquals(PadMapModel.Kind.NONE, l3.kind)
        assertEquals(PadMapModel.Bind("TOGGLERUN", "Walk / Run", 132539), l3.game)
        assertEquals("Walk / Run", page(m, PadMapModel.POS_L3).shownName())
        assertEquals("no icon of ours: the name still", "Frost Nova", page(m, PadMapModel.POS_R3).shownName())
        assertNull("ZL + L3 with nothing of the game's: no game binding", PadMapModel.slot(m, 2, PadMapModel.POS_L3)!!.game)
        assertEquals("an empty stick with nothing there reads Empty", "Empty", page(map(""), PadMapModel.POS_L3).shownName())
        // the chooser still opens on Extra's four (the game's own is no put of ours: no Clear)
        assertTrue(ChooserTabs.keys(false, stick = true) == listOf("extra"))
    }

    @Test fun oneOfOursWins() {
        // a stick with our binding carries no gd (the add-on leaves it out); a stray gd beside a bind is ignored too
        val m = map("""{"pos":9,"set":1,"kind":"bind","key":"TOGGLEAUTORUN","n":"Auto Run","i":132307,"stick":"L3","gd":"TOGGLERUN","gn":"Walk / Run"}""")
        assertNull(PadMapModel.slot(m, 1, PadMapModel.POS_L3)!!.game)
        assertEquals("Auto Run", page(m, PadMapModel.POS_L3).shownName())
    }

    @Test fun theEightNeverShowAGameBinding() {
        val m = map(game)
        assertEquals("Empty", page(m, 4).shownName())
    }
}
