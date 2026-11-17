package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * bags-gear2 (the user, 2026-11-16: "The equip a one hand from a bag is really confusing because the tool tip is in front of the
 * options"; then "I think it just needs its own menu pop out for an easy clear fix"): X on gear with two places to go (a dual wielder's
 * one-hander, a ring, a trinket) opens its own small slot menu beside the piece, and the piece's tooltip stands clear of it. Checked for
 * the piece in every column of the grid, and drawn (EQUIP_SHOTS_DIR set; EQUIP_ART_ROOT = an art root laid out like the app's) with,
 * for comparison, the single-slot cases that keep today's plain tooltip (a one-hander for a class that can't dual wield, a two-hander).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EquipCardOverlapTest {
    private val out: File? = System.getenv("EQUIP_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("EQUIP_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }
    private fun stat(k: String, n: String, v: Int) = JSONObject().put("k", k).put("n", n).put("v", v)
    private val sta = "ITEM_MOD_STAMINA_SHORT"; private val str = "ITEM_MOD_STRENGTH_SHORT"; private val agi = "ITEM_MOD_AGILITY_SHORT"

    private fun item(id: Int, name: String, equip: String, q: Int, icon: Int, stats: JSONArray) =
        JSONObject().put("id", id).put("name", name).put("equip", equip).put("quality", q).put("ilvl", 18).put("icon", icon)
            .put("count", 1).put("stats", stats)

    private fun worn(slot: Int, name: String, id: Int? = null, item: String? = null, icon: Int = 0, q: Int = 1, equip: String? = null) =
        JSONObject().put("slot", slot).put("name", name).also { o ->
            if (id != null) o.put("id", id).put("icon", icon).put("quality", q).put("dur", 100).put("item", item).put("equip", equip)
        }

    private fun equipped(dw: Boolean) = JSONObject().put("dw", dw).put("rangedShown", true).put("slots", JSONArray()
        .put(worn(11, "Finger 1", 908, "Band of Thorns", 133347, 2, "INVTYPE_FINGER"))
        .put(worn(12, "Finger 2"))
        .put(worn(13, "Trinket 1")).put(worn(14, "Trinket 2"))
        .put(worn(16, "Main Hand", 911, "Worn Shortsword", 135274, 1, "INVTYPE_WEAPON"))
        .put(worn(17, "Off Hand", 912, "Battered Buckler", 134955, 1, "INVTYPE_SHIELD")))

    private val sword = item(3302, "Militia Shortsword", "INVTYPE_WEAPON", 2, 135274, arr(stat(str, "Strength", 3), stat(sta, "Stamina", 2)))
    private val maul = item(3301, "Heavy Ironwood Maul", "INVTYPE_2HWEAPON", 3, 133052, arr(stat(str, "Strength", 8), stat(sta, "Stamina", 5)))
    private val ring = item(3201, "Ring of the Copper Vein", "INVTYPE_FINGER", 2, 133344, arr(stat(sta, "Stamina", 3), stat(agi, "Agility", 2)))

    private fun tipOf(it: JSONObject, at: Int, kind: String) = JSONObject().put("key", "bag 0 $at ${it.optInt("id")}")
        .put("lines", arr(l(it.optString("name"), "1eff00"), l("Binds when equipped"), l(kind), l("+3 Strength"), l("+2 Stamina"),
            l("Durability 45 / 45"), l("Requires Level 12")))

    /** the Bags room at Thor size, [piece] in backpack slot [at]; [targets] = the worn slots of the X menu (null = the plain tooltip) */
    private fun room(piece: JSONObject, at: Int, dw: Boolean, targets: List<Int>?, sel: Int = 1): BagsView {
        val v = BagsView(RuntimeEnvironment.getApplication())
        val row = JSONObject(piece.toString()).put("bag", 0).put("slot", at)
        val items = JSONArray()
            .put(item(6948, "Hearthstone", "", 1, 134414, JSONArray()).put("bag", 0).put("slot", if (at == 1) 2 else 1).put("use", true))
            .put(row)
        val eqd = equipped(dw)
        val wornBy = (0 until eqd.getJSONArray("slots").length()).associate { i -> eqd.getJSONArray("slots").getJSONObject(i).let { it.getInt("slot") to it } }
        val ctl = JSONObject().put("on", true).put("mode", if (targets != null) "equip" else "grid").put("seq", 1).put("sid", 1)
            .put("cur", JSONObject().put("bag", 0).put("slot", at))
            .put("tip", tipOf(piece, at, if (piece === ring) "Finger" else if (piece === maul) "Two-Hand" else "One-Hand"))
        if (targets != null) {
            val t = JSONArray()
            for (inv in targets) {
                val w = wornBy[inv]
                t.put(JSONObject().put("inv", inv).put("ok", true).also { o ->
                    if (w != null && w.has("id")) o.put("id", w.getInt("id")).put("icon", w.getInt("icon")).put("quality", w.getInt("quality"))
                        .put("name", w.getString("item"))
                })
            }
            ctl.put("eq", JSONObject().put("gear", true).put("bag", 0).put("slot", at).put("id", piece.optInt("id")).put("at", sel).put("targets", t))
        }
        val doc = JSONObject().put("free", 12).put("total", 32).put("money", 102345).put("combat", false)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 32)))
            .put("items", items).put("equipped", eqd).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30)))
        v.update(JSONObject(doc.toString()).put("ctl", JSONObject().put("on", false)), true)
        v.update(doc, true)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        act.setContentView(v, android.view.ViewGroup.LayoutParams(1240, 1007))
        return v
    }

    private fun draw(v: View, name: String?) {
        val w = 1240; val h = 1007
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
            if (pass < 3) ShadowSystemClock.advanceBy(Duration.ofMillis(400L))
            ShadowLooper.idleMainLooper()
            if (name != null) Thread.sleep(120)                               // icons decode off the UI thread
            ShadowLooper.idleMainLooper()
        }
        val dir = out ?: return
        if (name == null) return
        dir.mkdirs()
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun shown(v: BagsView): Pair<RectF?, List<RectF>>? {
        val layer = BagsView::class.java.getDeclaredField("ctlLayer").also { it.isAccessible = true }.get(v)
        return layer.javaClass.getDeclaredMethod("getGearShown").invoke(layer) as Pair<RectF?, List<RectF>>?
    }

    private fun clear(what: String, v: BagsView) {
        val s = shown(v)
        assertNotNull("$what: the slot menu was drawn", s)
        val card = s!!.first
        assertNotNull("$what: the tooltip was drawn", card)
        assertTrue("$what: the options (and the menu, the tile)", s.second.size >= 2)
        for ((i, r) in s.second.withIndex())
            assertTrue("$what: option / menu / tile ${i + 1} $r is clear of the tooltip $card", !RectF.intersects(card!!, r))
        for (r in s.second + card!!) assertTrue("$what: $r inside the room", r.left >= 0f && r.right <= 1240f)
    }

    /** the piece in every grid column (two rows), each option highlighted: the tooltip never covers an option */
    @Test fun theTooltipNeverCoversTheSlotOptions() {
        Tr.init(RuntimeEnvironment.getApplication())
        for (at in 1..20) for (sel in 1..2) {
            room(sword, at, dw = true, targets = listOf(16, 17), sel = sel).also { draw(it, null); clear("one-hander in slot $at, option $sel", it) }
        }
        for (at in listOf(1, 5, 6, 10)) room(ring, at, dw = false, targets = listOf(11, 12), sel = 2).also { draw(it, null); clear("ring in slot $at", it) }
    }

    @Test fun shots() {
        if (out == null) return
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        draw(room(sword, 6, dw = true, targets = listOf(16, 17), sel = 1), "onehand_dw_mid_main.png")
        draw(room(sword, 6, dw = true, targets = listOf(16, 17), sel = 2), "onehand_dw_mid_off.png")
        draw(room(ring, 4, dw = false, targets = listOf(11, 12), sel = 2), "ring_col4_finger2.png")
        draw(room(sword, 4, dw = true, targets = listOf(16, 17), sel = 2), "onehand_dw_col4_off.png")     // before: the tooltip over Off Hand
        draw(room(sword, 1, dw = true, targets = listOf(16, 17), sel = 2), "onehand_dw_left_edge.png")
        draw(room(sword, 10, dw = true, targets = listOf(16, 17), sel = 1), "onehand_dw_right_edge.png")
        draw(room(sword, 6, dw = false, targets = null), "onehand_no_dw_plain.png")       // one place to go: today's plain tooltip
        draw(room(maul, 6, dw = true, targets = null), "twohand_plain.png")
        draw(room(ring, 6, dw = false, targets = listOf(11, 12), sel = 2), "ring_mid_finger2.png")
        draw(room(ring, 3, dw = false, targets = listOf(11, 12), sel = 1), "ring_left_finger1.png")
    }
}
