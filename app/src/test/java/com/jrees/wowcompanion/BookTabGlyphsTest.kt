package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * THE SPELLBOOK'S AND TALENTS' TAB ROWS SHOW L / R (the user's pick, 2026-11-25: "Add them (Recommended)" - narrow the tabs a little so
 * the L glyph sits far left and R far right on both rows, like the other rooms): each row keeps UiKit.TAB_END_W free left of its first
 * tab and right of its last, where UiKit.tabEnds draws the glyphs, and the tabs still stand on the screen; Talents' schools step with
 * L / R and stop at the ends. Base-compatible (the rows' boxes by reflection), so the BEFORE shows the pins failing. Pictures (the same
 * states before and after) when ROOMTABS_SHOTS_DIR is set; ROOMTABS_ART_ROOT = an art root laid out like the app's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookTabGlyphsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("ROOMTABS_SHOTS_DIR")?.let { File(it) }
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    @Before fun art() {
        System.getenv("ROOMTABS_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
        }
        Tr.init(app); Settings.init(app)
    }

    private fun frame(v: View): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, W, H)
            val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
            ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
        }
        return b
    }

    private fun save(name: String, b: Bitmap) {
        val dir = out ?: return
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tabRect(v: View, i: Int): RectF =
        v.javaClass.getDeclaredMethod("tabRect", Int::class.javaPrimitiveType).also { it.isAccessible = true }.invoke(v, i) as RectF

    private fun book(): SpellbookView {
        val v = SpellbookView(app, onBind = { })
        v.update(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!)
        frame(v)
        return v
    }

    private val taps = ArrayList<Pair<String, Int>>()

    private fun talents(): TalentsView {
        val v = TalentsView(app) { row, col -> taps += row to col }
        val groups = JSONArray()
        for ((i, s) in listOf(Triple("Beast Mastery", 461112, 5), Triple("Marksmanship", 236179, 0), Triple("Survival", 461113, 0)).withIndex())
            groups.put(JSONObject().put("id", 361 + i).put("n", s.first).put("i", s.second).put("spent", s.third))
        v.update(TalentsModel.parse(JSONObject().put("type", "talents").put("v", "t9").put("t", JSONObject().put("lvl", 24).put("use", true)
            .put("g", 1).put("ng", 1).put("pool", JSONObject().put("have", 10).put("max", 15).put("spent", 5)).put("groups", groups)
            .put("nodes", JSONArray())))!!)
        frame(v)
        return v
    }

    /** the L glyph's room left of the first tab, the R glyph's right of the last, the row inside the screen */
    private fun assertGlyphRoom(name: String, first: RectF, last: RectF, edge: Float) {
        assertTrue("$name: room for L left of the first tab (${first.left})", first.left - UiKit.TAB_END_W >= edge - 0.5f)
        assertTrue("$name: room for R right of the last tab (${last.right})", last.right + UiKit.TAB_END_W <= W - edge + 0.5f)
    }

    @Test fun theSpellbooksRowKeepsRoomForLAndR() {
        val v = book()
        save("spellbook_1_book_tab_row", frame(v))                  // the picture first: the BEFORE has one too
        val n = minOf(8, SpellsModel.tabs(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!).size)
        assertTrue("the sample book has tabs", n >= 2)
        assertGlyphRoom("spellbook (the book's tabs)", tabRect(v, 0), tabRect(v, n - 1), 16f)
        assertGlyphRoom("spellbook (eight tabs, the most it shows)", tabRect(v, 0), tabRect(v, 7), 16f)
        // the tabs never touch each other and their feet stay under the page (158)
        assertTrue(tabRect(v, 1).left > tabRect(v, 0).right)
        assertTrue(tabRect(v, 0).bottom >= 158f + 8f)
    }

    @Test fun talentsRowKeepsRoomForLAndR() {
        val v = talents()
        save("talents_1_school_tab_row", frame(v))                  // the picture first: the BEFORE has one too
        assertGlyphRoom("talents (three schools)", tabRect(v, 0), tabRect(v, 2), 12f)
    }

    /** Talents' L / R: the school before / after, no wrap, the same cell as a finger on that school (TalentsModel.PAD_SCHOOL_1 + i) */
    @Test fun talentsShouldersStepTheSchoolsAndStop() {
        val v = talents()
        val step = TalentsView::class.java.methods.first { it.name == "shoulder" && it.parameterCount == 1 }
        fun school() = TalentsView::class.java.getDeclaredField("school").also { it.isAccessible = true }.getInt(v)
        step.invoke(v, false)
        assertEquals("L on the first school: nothing", 1, school()); assertTrue(taps.isEmpty())
        step.invoke(v, true); assertEquals(2, school()); assertEquals(TalentsModel.ROW_ACTIONS to TalentsModel.PAD_SCHOOL_1 + 1, taps.last())
        step.invoke(v, true); assertEquals(3, school())
        step.invoke(v, true); assertEquals("R on the last school: nothing", 3, school()); assertEquals(2, taps.size)
        step.invoke(v, false); assertEquals(2, school()); assertEquals(TalentsModel.ROW_ACTIONS to TalentsModel.PAD_SCHOOL_1 + 1, taps.last())
        frame(v)
        save("talents_2_second_school_by_L", frame(v))
    }
}
