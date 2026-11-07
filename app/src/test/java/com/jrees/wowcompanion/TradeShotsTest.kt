package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE TRADE WINDOW'S OFF-DEVICE RENDER (Interact i137; the user, 2026-11-07: "We really need to make a good trade window"): the
 * InteractView drawn by Robolectric's native graphics on a 1240 x 1080 page from a made-up trade document, saved as PNGs.
 * Runs only when TRADE_SHOTS_DIR is set; TRADE_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TradeShotsTest {
    private val out: File? = System.getenv("TRADE_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("TRADE_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun item(i: Int, name: String?, icon: Int = 0, count: Int = 0, q: Int = 1, slot: Int = 10 + i) =
        JSONObject().put("i", i).put("slot", slot).also { o -> if (name != null) o.put("name", name).put("icon", icon).put("count", count).put("q", q) }

    private fun doc(cur: String?, acc: Boolean, tacc: Boolean, edit: JSONObject? = null): JSONObject {
        val mine = JSONArray().put(item(1, "Linen Cloth", 132889, 20)).put(item(2, "Stringy Wolf Meat", 133970, 5))
            .put(item(3, null)).put(item(4, null)).put(item(5, null)).put(item(6, null)).put(item(7, null))
        val theirs = JSONArray().put(item(1, "Bronze Bar", 133217, 4, 1, 40)).put(item(2, "Heavy Leather Ball", 132485, 1, 2, 41))
            .put(item(3, null, slot = -1)).put(item(4, null, slot = -1)).put(item(5, null, slot = -1)).put(item(6, null, slot = -1))
            .put(item(7, null, slot = -1))
        val bags = JSONArray()
        val names = listOf("Malachite" to 134085, "Light Leather" to 134252, "Minor Healing Potion" to 134830, "Hearthstone" to 134414,
            "Rough Stone" to 135232, "Copper Ore" to 134566, "Silver Ore" to 134579, "Tigerseye" to 134120, "Small Egg" to 132832,
            "Ice Cold Milk" to 132815, "Coarse Thread" to 132891, "Fine Thread" to 132912)
        for ((k, nm) in names.withIndex()) bags.put(JSONObject().put("bag", 0).put("s", k + 1).put("id", 1000 + k).put("icon", nm.second)
            .put("n", if (k % 3 == 0) 5 else 1).put("q", 1).put("name", nm.first).put("slot", 60 + k))
        val act = JSONObject().put("edit", 1).put("set", 2).put("off", 3)
        var c = 4
        for (cn in listOf("g", "s", "c")) for (st in listOf("+", "-", "++", "--")) act.put(cn + st, c++)
        val td = JSONObject().put("who", "Brannoc").put("lvl", 14).put("cls", "WARRIOR").put("slots", 7).put("cols", 5)
            .put("mine", mine).put("theirs", theirs).put("money", 12550).put("tmoney", 2500).put("purse", 102345).put("bags", bags)
            .put("act", act).put("cells", JSONObject().put("accept", 31).put("cancel", 32))
        if (acc) td.put("acc", true)
        if (tacc) td.put("tacc", true)
        if (edit != null) td.put("edit", edit)
        val d = JSONObject().put("type", "interact").put("iv", "i137").put("trade", td).put("mode", true)
        if (cur != null) d.put("nav", JSONObject().put("on", true).put("cur", cur))
        return d
    }

    private fun shot(name: String, d: JSONObject) {
        val v = InteractView(RuntimeEnvironment.getApplication())
        v.dryRun = true
        v.update(d, true)
        v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1240, 1080)
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Theme.ink)
        v.draw(c)
        val dir = out ?: return
        dir.mkdirs()
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        artRoot()
        shot("trade_1_open.png", doc("tslot p 3", acc = false, tacc = false))
        shot("trade_2_they_accepted.png", doc("tslot t 1", acc = false, tacc = true))
        shot("trade_3_bags_cursor.png", doc("tradein 0 2 1001", acc = false, tacc = false))
        shot("trade_4_money_stepper.png", doc("tmoney", acc = false, tacc = false, edit = JSONObject().put("g", 1).put("s", 25).put("c", 50).put("sel", 2)))
    }
}
