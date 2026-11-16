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
import kotlin.math.min

/**
 * THE VENDOR'S BAG AND AMMO FIGURES, OFF-DEVICE (the user, 2026-11-16: "when talking to a vendor in the buy screen I should see my bag
 * slots available as well as ammo slots available (if applicable)", then "It should show like bag slots out of how many available and for
 * ammo it should show ammo/total available space. The numbers on their own don't mean anything"): the InteractView's merchant window
 * drawn by Robolectric's native graphics on a 1240 x 1080 page from made-up merchant + bags documents, saved as PNGs. Saves only when
 * VENDOR_SHOTS_DIR is set (VENDOR_SHOTS_PREFIX before each name); VENDOR_ART_ROOT = an art root laid out like the app's (ui/kit, fonts,
 * icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VendorSlotsShotsTest {
    private val out: File? = System.getenv("VENDOR_SHOTS_DIR")?.let { File(it) }
    private val prefix = System.getenv("VENDOR_SHOTS_PREFIX") ?: ""

    private fun artRoot() {
        val root = System.getenv("VENDOR_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun merchant(tab: String, cur: String?, money: Long = 123456L, junk: Boolean = true, repair: Boolean = true, junkValue: Long = 70L): JSONObject {
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
        if (junk) m.put("junk", 2).put("junkValue", junkValue)
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

    /**
     * a bags document as Bags.lua sends it: [plain] = (slots, free) per plain bag (its used slots hold a stack each), [ammo] = (slots,
     * ammo carried, family) for a quiver / ammo pouch, its ammo in stacks of [stack]; the worn ammo says its [stack] (bags-vslots1)
     */
    private fun bags(plain: List<Pair<Int, Int>>, ammo: Triple<Int, Int, Int>? = null, ammoIcon: Int = 134407, stack: Int = 200): JSONObject {
        val arr = JSONArray(); val items = JSONArray()
        var free = 0; var general = 0; var total = 0
        for ((k, p) in plain.withIndex()) {
            val e = JSONObject().put("bag", k).put("slots", p.first).put("free", p.second).put("fam", 0)
            if (k > 0) e.put("id", 4496 + k).put("icon", 133633).put("name", "Small Brown Pouch")
            arr.put(e); free += p.second; general += p.second; total += p.first
            for (s in 1..(p.first - p.second)) items.put(JSONObject().put("bag", k).put("slot", s).put("id", 2589).put("count", 1))
        }
        val b = JSONObject()
        if (ammo != null) {
            val bag = plain.size
            var left = ammo.second; var s = 0
            while (left > 0) {
                val n = min(stack, left); s++
                items.put(JSONObject().put("bag", bag).put("slot", s).put("id", 2512).put("count", n)); left -= n
            }
            arr.put(JSONObject().put("bag", bag).put("slots", ammo.first).put("free", ammo.first - s).put("fam", ammo.third)
                .put("id", 2101).put("icon", ammoIcon).put("name", if (ammo.third == 2) "Small Ammo Pouch" else "Light Quiver"))
            free += ammo.first - s; total += ammo.first
            b.put("equipped", JSONObject().put("ammo", JSONObject().put("id", 2512).put("icon", 132382).put("count", ammo.second)
                .put("stack", stack)))
        }
        return b.put("bags", arr).put("free", free).put("total", total).put("freeGeneral", general).put("money", 123456L).put("items", items)
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
        File(dir, prefix + name).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String, d: JSONObject, b: JSONObject?, ack: JSONObject? = null) = save(name, view(d, b, ack))

    private fun slotsOf(v: InteractView): VendorSlots.Slots? {
        val f = InteractView::class.java.getDeclaredField("vendorSlots")
        f.isAccessible = true
        return f.get(v) as VendorSlots.Slots?
    }

    /**
     * LIVE AS YOU BUY: the same open vendor, no new vendor document - only the next Bags document (Bags.lua's bags-only "full" at the
     * purchase's BAG_UPDATE_DELAYED) - and the strip's figures follow; a bags section without the counts keeps them
     */
    @Test fun followsTheBagsDocument() {
        artRoot()
        val v = view(merchant("buy", "buy 1 159"), bags(listOf(16 to 9, 6 to 3), Triple(14, 1000, 1)))
        assertEquals(VendorSlots.Slots(VendorSlots.Count(10, 22, VendorSlots.BAG_ICON, VendorSlots.Tone.PLAIN),
            VendorSlots.Count(1000, 2800, 134407, VendorSlots.Tone.PLAIN)), slotsOf(v))
        save("vendor_11a_live_before_buy.png", v)
        // bought a stack of water (one more slot used) and a stack of arrows (into the quiver)
        v.updateBags(bags(listOf(16 to 8, 6 to 3), Triple(14, 1200, 1)))
        assertEquals(VendorSlots.Slots(VendorSlots.Count(11, 22, VendorSlots.BAG_ICON, VendorSlots.Tone.PLAIN),
            VendorSlots.Count(1200, 2800, 134407, VendorSlots.Tone.PLAIN)), slotsOf(v))
        save("vendor_11b_live_after_buy.png", v)
        v.updateBags(JSONObject().put("ctl", JSONObject()))
        assertEquals(11, slotsOf(v)!!.bags!!.have)
        // the quiver taken off the bag bar: the ammo cell goes
        v.updateBags(bags(listOf(16 to 8, 6 to 3)))
        assertNull(slotsOf(v)!!.ammo)
    }

    @Test fun shots() {
        artRoot()
        val roomy = listOf(16 to 9, 6 to 3)
        shot("vendor_1_buy_no_ammo_bag.png", merchant("buy", "buy 1 159"), bags(roomy))
        shot("vendor_2_buy_quiver.png", merchant("buy", "buy 3 161"), bags(roomy, Triple(14, 1850, 1)))
        // the bags one slot short of full, the quiver full (the goal: plain, not red)
        shot("vendor_3_buy_nearly_full.png", merchant("buy", "buy 1 159", money = 4_025_311L), bags(listOf(16 to 1, 6 to 0), Triple(14, 2800, 1)))
        // the bags full (red), an ammo pouch under a tenth (amber)
        shot("vendor_4_buy_ammo_pouch_full_bags.png", merchant("buy", "buy 4 162"), bags(listOf(16 to 0, 6 to 0), Triple(8, 120, 2), 133581))
        shot("vendor_5_sell_quiver.png", merchant("sell", "sell 0 1 2000"), bags(roomy, Triple(14, 1850, 1)))
        shot("vendor_6_buyback_quiver.png", merchant("back", "back 1 3000"), bags(roomy, Triple(14, 1850, 1)))
        shot("vendor_7_no_bags_doc.png", merchant("buy", "buy 1 159"), null)
        shot("vendor_8_small_purse_no_strip_extras.png", merchant("buy", "buy 1 159", money = 87L, junk = false, repair = false), bags(roomy, Triple(14, 1850, 1)))
        // the bags full: the game refused the purchase (a controller Buy: its answer is the strip's line), the figures stay beside the
        // button; the quiver empty (red)
        shot("vendor_10_full_refused.png", merchant("buy", "buy 1 159"), bags(listOf(16 to 0, 6 to 0), Triple(14, 0, 1)),
            JSONObject().put("type", "interactack").put("cmd", "buy 1 159").put("ok", false).put("why", "inventory is full.").put("_gt", gameNow()))
        // a client whose arrows stack to 1000: an 18-slot quiver = 18000, written in thousands
        shot("vendor_13_thousand_stacks.png", merchant("buy", "buy 3 161"), bags(roomy, Triple(18, 12_340, 1), stack = 1000))
        // no repair, no junk: the bags alone (a non-hunter) at full size
        shot("vendor_14_bags_only_no_extras.png", merchant("buy", "buy 1 159", junk = false, repair = false), bags(listOf(16 to 2, 14 to 0, 14 to 5)))
        Tr.load("es", File("../companion/app/src/main/assets/companion/i18n/es.json").readText())
        Tr.load("fr", File("../companion/app/src/main/assets/companion/i18n/fr.json").readText())
        val c0 = Tr::class.java.getDeclaredField("choice"); c0.isAccessible = true
        val was = c0.get(Tr)
        try {
            c0.set(Tr, "es")
            shot("vendor_9_spanish_quiver.png", merchant("buy", "buy 3 161"), bags(roomy, Triple(14, 1850, 1)))
            // the strip's tightest case - French's longest Repair / Junk words, the widest figures (a full bag bar of 18-slot bags, an
            // 18-slot quiver nearly full): Junk keeps its place, its words stop short of the figures
            c0.set(Tr, "fr")
            shot("vendor_12_french_widest_counts.png", merchant("sell", "sell 0 1 2000"), bags(WIDEST, Triple(18, 3550, 1)))
        } finally { c0.set(Tr, was) }
    }

    /**
     * the widest figures ("70 / 70" over "3550 / 3600") with a repair due and a gold-sized junk value leave Repair and the FULL Junk box
     * in the strip (the stacked figures take less room than the old two cells side by side): Junk is never pushed out, and the figures'
     * block stays inside the strip, clear of the action button
     */
    @Test fun widestCountsKeepJunk() {
        artRoot()
        val v = view(merchant("buy", "buy 1 159", junkValue = 12_345L), bags(WIDEST, Triple(18, 3550, 1)))
        save("vendor_12b_widest_counts_english.png", v)
        val f = InteractView::class.java.getDeclaredField("hits"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val hits = f.get(v) as List<Pair<android.graphics.RectF, String>>
        val junk = hits.firstOrNull { it.second.startsWith("act:junk") }?.first
        val go = hits.firstOrNull { it.second == "go" }?.first
        org.junit.Assert.assertNotNull("Sell junk drawn beside the widest figures; hits ${hits.map { it.second to it.first }}", junk)
        org.junit.Assert.assertNotNull(go)
        org.junit.Assert.assertTrue("Junk ends left of the action button", junk!!.right < go!!.left)
        org.junit.Assert.assertEquals("the full 270 Junk box", 270f, junk.width(), 0.5f)
        assertEquals(VendorSlots.Count(70, 70, VendorSlots.BAG_ICON, VendorSlots.Tone.OUT), slotsOf(v)!!.bags)
        assertEquals("3550 / 3600", VendorSlots.text(slotsOf(v)!!.ammo!!))
    }

    private companion object {
        /** a full bag bar of the biggest Classic bags: the backpack and three 18s, every slot used */
        val WIDEST = listOf(16 to 0, 18 to 0, 18 to 0, 18 to 0)
    }
}
