package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Interact i174 OFF-DEVICE (the user, 2026-11-26: "When buying stuff on the AH for some reason the buttons are just staying gray", then
 * "I was in dual screen, its just how its been working. It still works but visually its gray"): an item opened at the auction house,
 * drawn (Robolectric's native graphics, 1240 x 1080) from the document the add-on sends. The listings no longer carry cells of their own
 * (there were none left after a long result list: the grey Buy Now); the AH's one listing cell `act.list` presses any of them, the row's
 * number by the count cell first. Saves PNGs only when AH_SHOTS_DIR is set ("before" = the listings with no cell and no listing cell,
 * what an i173 add-on sent after a long list; "after" = an i174 document).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AhBuyGreyShotsTest {
    private val out: File? = System.getenv("AH_SHOTS_DIR")?.let { File(it) }

    private fun gameNow(): Long {
        val c = java.util.Calendar.getInstance()
        val before = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val doy = before[c.get(java.util.Calendar.MONTH)] + c.get(java.util.Calendar.DAY_OF_MONTH)
        return ((doy * 24L + c.get(java.util.Calendar.HOUR_OF_DAY)) * 60L + c.get(java.util.Calendar.MINUTE)) * 60_000L +
            c.get(java.util.Calendar.SECOND) * 1000L + c.get(java.util.Calendar.MILLISECOND)
    }

    /** an item opened with [n] listings (row 2 yours); [listCell] = the i174 listing cell; [armed] = the row armed (the confirm up) */
    private fun doc(n: Int, listCell: Boolean, cur: String?, armed: Int = 0): JSONObject {
        val rows = JSONArray()
        for (i in 1..n) rows.put(JSONObject().put("i", i).put("auction", 9000 + i).put("price", 1000L * i).put("count", 1).put("left", 2)
            .apply { if (i == 2) put("mine", true) })
        val item = JSONObject().put("id", 505).put("name", "Sword 505").put("icon", 1).put("q", 2).put("rows", rows)
        val act = JSONObject().put("browse", 3).put("owned", 4).put("back", 5).put("buy", 6).put("less", 7).put("more", 8).put("sell", 9)
        if (listCell) act.put("list", 10)
        val results = JSONArray()
        for (i in 1..60) results.put(JSONObject().put("i", i).put("id", 500 + i).put("name", "Sword ${500 + i}").put("price", 100L * i).put("count", 1)
            .apply { if (i <= 40) put("slot", i - 1) })
        val au = JSONObject().put("tab", "browse").put("money", 5_000_000L).put("act", act).put("item", item).put("results", results)
            .put("cats", JSONArray()).put("qty", 1)
        if (armed > 0) au.put("buy", JSONObject().put("kind", "item").put("row", armed).put("total", 1000L * armed).put("qty", 1).put("armed", true))
        val nav = JSONObject().put("on", cur != null).put("n", 1).apply { if (cur != null) put("cur", cur) }
        return JSONObject().put("type", "interact").put("iv", if (listCell) "i174" else "i173").put("auction", au).put("mode", true)
            .put("_gt", gameNow()).put("nav", nav).put("pad", JSONObject())
    }

    private fun drawn(d: JSONObject, name: String, v: InteractView = InteractView(RuntimeEnvironment.getApplication())): InteractView {
        v.dryRun = true
        v.update(d, true)
        v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1240, 1080)
        val bm = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawColor(Theme.ink)
        v.draw(c)
        out?.let { dir -> dir.mkdirs(); File(dir, name).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    private fun hits(v: InteractView): List<Pair<RectF, String>> =
        InteractView::class.java.getDeclaredField("hits").apply { isAccessible = true }.get(v) as List<Pair<RectF, String>>
    private fun field(v: InteractView, f: String): Any? = InteractView::class.java.getDeclaredField(f).apply { isAccessible = true }.get(v)
    private fun goCmd(v: InteractView): String? = field(v, "goCmd")?.let { e -> e.javaClass.getDeclaredField("cmd").apply { isAccessible = true }.get(e) as String }
    private fun tapOn(v: InteractView, hit: String) {
        val r = hits(v).last { it.second == hit }.first
        InteractView::class.java.getDeclaredMethod("tap", Float::class.java, Float::class.java).apply { isAccessible = true }.invoke(v, r.centerX(), r.centerY())
    }

    @Test fun theControllersListingLightsBuyNow() {
        drawn(doc(25, listCell = false, cur = "ahrow 4"), "ah_buy_before.png")
        val v = drawn(doc(25, listCell = true, cur = "ahrow 4"), "ah_buy_after.png")
        assertEquals("Buy Now is coloured and presses listing 4", "ahrow 4", goCmd(v))
    }

    @Test fun aFingersListingLightsBuyNowAndPressesTheListingCell() {
        val v = InteractView(RuntimeEnvironment.getApplication())
        drawn(doc(25, listCell = true, cur = null), "ah_buy_finger_none.png", v)
        assertNull("nothing chosen yet: grey, says why", goCmd(v))
        tapOn(v, "sel:ahrow 7")
        drawn(doc(25, listCell = true, cur = null), "ah_buy_finger.png", v)
        assertEquals("ahrow 7", goCmd(v))
        tapOn(v, "go")
        assertEquals("the press goes on the listing cell, row 7 first", "Preview: ahlist", field(v, "noteText"))
    }

    @Test fun yourOwnListingStaysGrey() {
        val v = drawn(doc(25, listCell = true, cur = "ahrow 2"), "ah_buy_yours.png")
        assertNull("your own listing: grey", goCmd(v))
    }

    @Test fun theConfirmsBuyNowIsColoured() {
        drawn(doc(25, listCell = false, cur = "ahrow 4", armed = 4), "ah_confirm_before.png")
        val v = drawn(doc(25, listCell = true, cur = "ahrow 4", armed = 4), "ah_confirm_after.png")
        val h = hits(v).map { it.second }
        assertTrue("the confirm's Buy Now presses listing 4: $h", "do:ahrow 4" in h)
        assertTrue("never the no-cell refusal", h.none { it == "why:The game gave this no tap cell" })
    }

    @Test fun anOlderAddOnsOwnCellsStillWork() {
        val d = doc(25, listCell = false, cur = "ahrow 4")
        val rows = d.getJSONObject("auction").getJSONObject("item").getJSONArray("rows")
        for (k in 0 until rows.length()) rows.getJSONObject(k).put("slot", 20 + k)
        val v = drawn(d, "ah_buy_oldcells.png")
        assertNotNull(goCmd(v))
        tapOn(v, "go")
        assertEquals("its own cell, as before", "Preview: ahrow 4", field(v, "noteText"))
    }
}
