package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE VENDOR'S FREE BAG SLOTS, OFF-DEVICE (the user, 2026-11-16: "when talking to a vendor in the buy screen I should see my bag slots
 * available as well as ammo slots available (if applicable)"): the InteractView's merchant window drawn by Robolectric's native graphics
 * on a 1240 x 1080 page from made-up merchant + bags documents, saved as PNGs. Runs only when VENDOR_SHOTS_DIR is set;
 * VENDOR_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VendorSlotsShotsTest {
    private val out: File? = System.getenv("VENDOR_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("VENDOR_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun merchant(tab: String, cur: String?, money: Long = 123456L, junk: Boolean = true, repair: Boolean = true): JSONObject {
        val items = JSONArray()
        val goods = listOf(Triple("Refreshing Spring Water", 132794, 25), Triple("Ice Cold Milk", 132815, 62), Triple("Rough Arrow", 132382, 10),
            Triple("Light Shot", 132384, 10), Triple("Small Quiver", 134407, 400), Triple("Light Quiver", 134408, 2000),
            Triple("Tough Jerky", 133972, 25), Triple("Freshly Baked Bread", 133964, 25), Triple("Coarse Thread", 132891, 10),
            Triple("Heavy Shot", 132384, 50))
        for ((k, g) in goods.withIndex()) items.put(JSONObject().put("i", k + 1).put("id", 159 + k).put("name", g.first).put("icon", g.second)
            .put("price", g.third.toLong()).put("stack", if (g.first.contains("Arrow") || g.first.contains("Shot")) 200 else 5).put("avail", -1)
            .put("buyable", true).put("usable", true).put("max", 20).put("q", 1).put("slot", k))
        val sell = JSONArray()
        val bagItems = listOf("Linen Cloth" to 132889, "Stringy Wolf Meat" to 133970, "Broken Fang" to 133725, "Chipped Claw" to 134297)
        for ((k, s) in bagItems.withIndex()) sell.put(JSONObject().put("bag", 0).put("s", k + 1).put("id", 2000 + k).put("n", k + 1)
            .put("q", if (k >= 2) 0 else 1).put("price", 15L + k * 10).put("name", s.first).put("icon", s.second).put("slot", k))
        val back = JSONArray().put(JSONObject().put("i", 1).put("id", 3000).put("name", "Worn Shortsword").put("icon", 135274)
            .put("price", 7L).put("n", 1).put("q", 1).put("slot", 0))
        val m = JSONObject().put("npc", JSONObject().put("name", "Innkeeper Farley")).put("items", items).put("sell", sell).put("buyback", back)
            .put("money", money).put("qtyMax", 20)
        if (repair) m.put("canRepair", true).put("needsRepair", true).put("repairCost", 845L)
        if (junk) m.put("junk", 2).put("junkValue", 70L)
        m.put("act", JSONObject().put("repair", 1).put("junk", 2).put("back", 3))
        // a live document (InteractView's own game clock), so the strip shows Repair / Junk instead of "Waiting for the game"
        val d = JSONObject().put("type", "interact").put("iv", "i160").put("merchant", m).put("mode", true).put("_gt", gameNow())
        val nav = JSONObject().put("on", true).put("n", 1).put("tab", tab)
        if (cur != null) nav.put("cur", cur)
        d.put("nav", nav)
        return d
    }

    private fun gameNow(): Long {
        val c = java.util.Calendar.getInstance()
        val before = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val doy = before[c.get(java.util.Calendar.MONTH)] + c.get(java.util.Calendar.DAY_OF_MONTH)
        return ((doy * 24L + c.get(java.util.Calendar.HOUR_OF_DAY)) * 60L + c.get(java.util.Calendar.MINUTE)) * 60_000L +
            c.get(java.util.Calendar.SECOND) * 1000L + c.get(java.util.Calendar.MILLISECOND)
    }

    /** a bags document: [plain] = (slots, free) per plain bag, [ammo] = (slots, free, family) for a quiver / ammo pouch */
    private fun bags(plain: List<Pair<Int, Int>>, ammo: Triple<Int, Int, Int>? = null, ammoIcon: Int = 134407): JSONObject {
        val arr = JSONArray()
        var free = 0; var general = 0; var total = 0
        for ((k, p) in plain.withIndex()) {
            val e = JSONObject().put("bag", k).put("slots", p.first).put("free", p.second).put("fam", 0)
            if (k > 0) e.put("id", 4496 + k).put("icon", 133633).put("name", "Small Brown Pouch")
            arr.put(e); free += p.second; general += p.second; total += p.first
        }
        if (ammo != null) {
            arr.put(JSONObject().put("bag", plain.size).put("slots", ammo.first).put("free", ammo.second).put("fam", ammo.third)
                .put("id", 2101).put("icon", ammoIcon).put("name", if (ammo.third == 2) "Small Ammo Pouch" else "Light Quiver"))
            free += ammo.second; total += ammo.first
        }
        return JSONObject().put("bags", arr).put("free", free).put("total", total).put("freeGeneral", general).put("money", 123456L)
            .put("items", JSONArray())
    }

    private fun view(d: JSONObject, b: JSONObject?, ack: JSONObject? = null): InteractView {
        val v = InteractView(RuntimeEnvironment.getApplication())
        v.dryRun = true
        if (b != null) v.updateBags(b)
        v.update(d, true)
        if (ack != null) v.ack(ack)
        v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1240, 1080)
        return v
    }

    private fun save(name: String, v: InteractView) {
        val bm = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawColor(Theme.ink)
        v.draw(c)
        val dir = out ?: return
        dir.mkdirs()
        File(dir, name).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String, d: JSONObject, b: JSONObject?, ack: JSONObject? = null) = save(name, view(d, b, ack))

    private fun slotsOf(v: InteractView): VendorSlots.Slots? {
        val f = InteractView::class.java.getDeclaredField("vendorSlots")
        f.isAccessible = true
        return f.get(v) as VendorSlots.Slots?
    }

    /**
     * LIVE AS YOU BUY: the same open vendor, no new vendor document - only the next Bags document (Bags.lua's bags-only "full" at the
     * purchase's BAG_UPDATE_DELAYED) - and the strip's counts follow; a bags section without the counts keeps them
     */
    @Test fun followsTheBagsDocument() {
        artRoot()
        val v = view(merchant("buy", "buy 1 159"), bags(listOf(16 to 9, 6 to 3), Triple(14, 9, 1)))
        assertEquals(VendorSlots.Slots(VendorSlots.Count(12, VendorSlots.BAG_ICON), VendorSlots.Count(9, 134407)), slotsOf(v))
        save("vendor_11a_live_before_buy.png", v)
        // bought a stack of water (one more slot used) and a stack of arrows (into the quiver)
        v.updateBags(bags(listOf(16 to 8, 6 to 3), Triple(14, 8, 1)))
        assertEquals(VendorSlots.Slots(VendorSlots.Count(11, VendorSlots.BAG_ICON), VendorSlots.Count(8, 134407)), slotsOf(v))
        save("vendor_11b_live_after_buy.png", v)
        v.updateBags(JSONObject().put("ctl", JSONObject()))
        assertEquals(11, slotsOf(v)!!.bags!!.free)
        // the quiver taken off the bag bar: the ammo cell goes
        v.updateBags(bags(listOf(16 to 8, 6 to 3)))
        assertNull(slotsOf(v)!!.ammo)
    }

    @Test fun shots() {
        artRoot()
        val roomy = listOf(16 to 9, 6 to 3)
        shot("vendor_1_buy_no_ammo_bag.png", merchant("buy", "buy 1 159"), bags(roomy))
        shot("vendor_2_buy_quiver.png", merchant("buy", "buy 3 161"), bags(roomy, Triple(14, 9, 1)))
        shot("vendor_3_buy_nearly_full.png", merchant("buy", "buy 1 159", money = 4_025_311L), bags(listOf(16 to 1, 6 to 0), Triple(14, 0, 1)))
        shot("vendor_4_buy_ammo_pouch_full_bags.png", merchant("buy", "buy 4 162"), bags(listOf(16 to 0, 6 to 0), Triple(8, 3, 2), 133581))
        shot("vendor_5_sell_quiver.png", merchant("sell", "sell 0 1 2000"), bags(roomy, Triple(14, 9, 1)))
        shot("vendor_6_buyback_quiver.png", merchant("back", "back 1 3000"), bags(roomy, Triple(14, 9, 1)))
        shot("vendor_7_no_bags_doc.png", merchant("buy", "buy 1 159"), null)
        shot("vendor_8_small_purse_no_strip_extras.png", merchant("buy", "buy 1 159", money = 87L, junk = false, repair = false), bags(roomy, Triple(14, 9, 1)))
        // the bags full: the game refused the purchase (a controller Buy: its answer is the strip's line), the counts stay beside the button
        shot("vendor_10_full_refused.png", merchant("buy", "buy 1 159"), bags(listOf(16 to 0, 6 to 0), Triple(14, 2, 1)),
            JSONObject().put("type", "interactack").put("cmd", "buy 1 159").put("ok", false).put("why", "inventory is full.").put("_gt", gameNow()))
        Tr.load("es", File("../companion/app/src/main/assets/companion/i18n/es.json").readText())
        val c0 = Tr::class.java.getDeclaredField("choice"); c0.isAccessible = true; c0.set(Tr, "es")
        shot("vendor_9_spanish_quiver.png", merchant("buy", "buy 3 161"), bags(roomy, Triple(14, 9, 1)))
        c0.set(Tr, "auto")
    }
}
