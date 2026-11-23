package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * ISSUE 590 OFF-DEVICE: the auction house's Sell grid drawn (Robolectric's native graphics, 1240 x 1080) from a made-up Sell document
 * (Interact i173): the items the game refuses are drawn greyed with a touch that says why, never picked; a grid taller than its panel
 * draws a scroll bar. Saves PNGs only when AH_SHOTS_DIR is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AhSellRefusedShotsTest {
    private val out: File? = System.getenv("AH_SHOTS_DIR")?.let { File(it) }

    private fun gameNow(): Long {
        val c = java.util.Calendar.getInstance()
        val before = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val doy = before[c.get(java.util.Calendar.MONTH)] + c.get(java.util.Calendar.DAY_OF_MONTH)
        return ((doy * 24L + c.get(java.util.Calendar.HOUR_OF_DAY)) * 60L + c.get(java.util.Calendar.MINUTE)) * 60_000L +
            c.get(java.util.Calendar.SECOND) * 1000L + c.get(java.util.Calendar.MILLISECOND)
    }

    private fun doc(sellable: Int, refused: List<String>): JSONObject {
        val bags = JSONArray(); val no = JSONArray()
        for (k in 1..sellable) bags.put(JSONObject().put("bag", 0).put("s", k).put("id", 2000 + k).put("n", k).put("q", 1).put("icon", 132889)
            .put("name", "Cloth $k").put("slot", k))
        for ((k, why) in refused.withIndex()) no.put(JSONObject().put("bag", 0).put("s", sellable + k + 1).put("id", 3000 + k).put("n", 1)
            .put("q", 1).put("icon", 134414).put("no", why))
        val groups = JSONArray().put(JSONObject().put("bag", 0).put("name", "Backpack").put("n", sellable).put("slot", 40).apply {
            if (refused.isNotEmpty()) put("x", refused.size) })
        val sell = JSONObject().put("bags", bags).put("groups", groups).put("cols", 4).put("dur", 2)
        if (refused.isNotEmpty()) sell.put("no", no)
        val au = JSONObject().put("tab", "sell").put("sell", sell).put("money", 123456L).put("act", JSONObject())
        val nav = JSONObject().put("on", true).put("n", 1).put("cur", "ahsfold 0")
        return JSONObject().put("type", "interact").put("iv", "i173").put("auction", au).put("mode", true).put("_gt", gameNow()).put("nav", nav)
    }

    private fun drawn(d: JSONObject, name: String): InteractView {
        val v = InteractView(RuntimeEnvironment.getApplication())
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

    private fun float(v: InteractView, f: String): Float = InteractView::class.java.getDeclaredField(f).apply { isAccessible = true }.getFloat(v)

    @Test fun refusedItemsAreGreyedWithTheGamesReason() {
        val v = drawn(doc(3, listOf("bound", "quest", "damaged", "no")), "ah_sell_refused.png")
        val h = hits(v).map { it.second }
        for (code in listOf("bound", "quest", "damaged", "no")) assertTrue(code, "why:${AhSellGrid.noWhy(code)}" in h)
        assertEquals("the sellable ones keep their taps", 3, h.count { it.startsWith("act:ahsitem ") })
        assertTrue("no refused item can be picked", h.none { it.startsWith("act:ahsno") })
    }

    @Test fun aLongGridHasAScrollBarItsTilesClearOf() {
        val v = drawn(doc(40, listOf("bound")), "ah_sell_scroll.png")
        val content = float(v, "contentB")
        val view = InteractView::class.java.getDeclaredField("viewB").apply { isAccessible = true }.get(v) as RectF
        assertTrue("taller than the panel: $content > ${view.height()}", content > view.height())
        assertTrue(AhSellGrid.bar(0f, content, view.height()) != null)
        // the item tiles stop short of the bar's strip at the panel's right
        val tiles = hits(v).filter { it.second.startsWith("act:ahsitem ") || it.second.startsWith("why:") && it.first.width() < 120f }
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.first.right <= view.right - 14f + 0.5f })
    }
}
