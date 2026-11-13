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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * bags-nocmp1 (the user, 2026-11-13: "the bag items are still showing currently equipped, I dont want to see that, but I do want to see
 * the stat changes at the bottom of that weapon if I equip it"): the Bags room's HOVER card for gear, drawn off-device on a 1240 x 1007
 * room from a made-up bags document - a chest against a worn chest, two rings (one worn, one empty), a two-hander against a sword and a
 * shield - in both of Interact's forms (i158: the worn piece's tooltip in the compare; i159: nw, only the changes), and the Y menu's
 * Compare sheet for the same items (unchanged: the worn pieces beside the bag item).
 * Runs only when BAGS_SHOTS_DIR is set; BAGS_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BagsHoverShotsTest {
    private val out: File? = System.getenv("BAGS_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("BAGS_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }
    private fun stat(k: String, n: String, v: Int) = JSONObject().put("k", k).put("n", n).put("v", v)
    private val HEAD = "If you replace this item, the following stat changes will occur:"

    private fun wornTip(name: String, q: String, vararg rest: String) = arr(l(name, q), *rest.map { l(it) }.toTypedArray())
    private val wornChest = wornTip("Tattered Cloth Vest", "ffffff", "Soulbound", "Chest", "12 Armor", "+1 Stamina", "Durability 30 / 35")
    private val wornRing = wornTip("Band of Thorns", "1eff00", "Soulbound", "Finger", "+2 Stamina", "+1 Spirit")
    private val wornSword = wornTip("Worn Shortsword", "ffffff", "Soulbound", "Main Hand", "1 - 4 Damage", "Speed 1.90", "(1.3 damage per second)")
    private val wornShield = wornTip("Battered Buckler", "ffffff", "Soulbound", "Off Hand", "Shield", "57 Armor")

    /** one gear item in bag 0 slot [slot] with its own tooltip; [cmp] in Interact's i158 form (worn lines) or i159's (nw) */
    private fun item(slot: Int, id: Int, name: String, q: Int, equip: String, stats: JSONArray) =
        JSONObject().put("bag", 0).put("slot", slot).put("id", id).put("icon", 132000 + slot).put("count", 1).put("quality", q)
            .put("name", name).put("equip", equip).put("stats", stats)

    private fun cmpEntry(slot: Int, worn: JSONArray?, vararg changes: JSONObject): JSONObject {
        val lines = JSONArray()
        if (worn != null) for (i in 0 until worn.length()) lines.put(worn.get(i))
        lines.put(l(" ")).put(l(HEAD, "ffd100"))
        changes.forEach { lines.put(it) }
        return JSONObject().put("slot", slot).put("dn", changes.size).put("lines", lines).also { if (worn == null) it.put("nw", true) }
    }

    private fun doc(slot: Int, nw: Boolean): JSONObject {
        val items = JSONArray()
            .put(item(1, 3001, "Hearthstone", 1, "", JSONArray()))
            .put(item(5, 2005, "Stonemason Chestguard", 2, "INVTYPE_CHEST", arr(stat("RESISTANCE0_NAME", "Armor", 58), stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 4), stat("ITEM_MOD_STRENGTH_SHORT", "Strength", 3))))
            .put(item(6, 2006, "Ring of the Copper Vein", 2, "INVTYPE_FINGER", arr(stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 3), stat("ITEM_MOD_AGILITY_SHORT", "Agility", 2))))
            .put(item(7, 2007, "Heavy Ironwood Maul", 3, "INVTYPE_2HWEAPON", arr(stat("ITEM_MOD_STRENGTH_SHORT", "Strength", 8), stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 5))))
        val tips = mapOf(
            5 to (arr(l("Stonemason Chestguard", "1eff00"), l("Binds when equipped"), l("Chest"), l("Mail", "ffffff"), l("58 Armor"),
                l("+3 Strength"), l("+4 Stamina"), l("Durability 75 / 75"), l("Requires Level 14")) to JSONArray().put(
                cmpEntry(5, if (nw) null else wornChest, l("+46 Armor", "00ff00"), l("+3 Strength", "00ff00"), l("+3 Stamina", "00ff00")))),
            6 to (arr(l("Ring of the Copper Vein", "1eff00"), l("Binds when equipped"), l("Finger"), l("+2 Agility"), l("+3 Stamina"),
                l("Requires Level 12")) to JSONArray().put(
                cmpEntry(11, if (nw) null else wornRing, l("+2 Agility", "00ff00"), l("+1 Stamina", "00ff00"), l("-1 Spirit", "ff2020")))),
            7 to (arr(l("Heavy Ironwood Maul", "0070dd"), l("Binds when equipped"), l("Two-Hand"), l("Mace", "ffffff"), l("14 - 22 Damage"),
                l("Speed 3.40"), l("(5.3 damage per second)"), l("+8 Strength"), l("+5 Stamina"), l("Durability 85 / 85"), l("Requires Level 15"))
                to JSONArray().put(cmpEntry(16, if (nw) null else wornSword, l("+4.0 Damage Per Second", "00ff00"), l("+8 Strength", "00ff00"),
                    l("+5 Stamina", "00ff00"), l("-57 Armor", "ff2020")).put("with", 17))
                    .put(JSONObject().put("slot", 17).put("pair", 16).put("lines", if (nw) JSONArray() else wornShield).also { if (nw) it.put("nw", true) })))
        val (lines, cmp) = tips.getValue(slot)
        val tip = JSONObject().put("key", "bag 0 $slot ${items.getJSONObject(listOf(1, 5, 6, 7).indexOf(slot)).getInt("id")}").put("lines", lines).put("cmp", cmp)
        val ctl = JSONObject().put("on", true).put("mode", "grid").put("seq", 1).put("sid", 1)
            .put("cur", JSONObject().put("bag", 0).put("slot", slot)).put("tip", tip)
        fun w(s: Int, n: String, id: Int?, item: String?, tip: JSONArray?, st: JSONArray?) = JSONObject().put("slot", s).put("name", n)
            .also { o -> if (id != null) o.put("id", id); if (item != null) o.put("item", item); if (tip != null) o.put("tip", tip); if (st != null) o.put("stats", st) }
        val equipped = JSONObject().put("slots", JSONArray()
            .put(w(5, "Chest", 900, "Tattered Cloth Vest", wornChest, arr(stat("RESISTANCE0_NAME", "Armor", 12), stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 1))))
            .put(w(11, "Finger 1", 901, "Band of Thorns", wornRing, arr(stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 2), stat("ITEM_MOD_SPIRIT_SHORT", "Spirit", 1))))
            .put(w(12, "Finger 2", null, null, null, null))
            .put(w(16, "Main Hand", 902, "Worn Shortsword", wornSword, JSONArray()))
            .put(w(17, "Off Hand", 903, "Battered Buckler", wornShield, arr(stat("RESISTANCE0_NAME", "Armor", 57)))))
        return JSONObject().put("free", 12).put("total", 16).put("money", 102345)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16)))
            .put("items", items).put("equipped", equipped).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30)))
    }

    private fun shot(name: String, d: JSONObject) {
        val app = RuntimeEnvironment.getApplication()
        val v = BagsView(app)
        v.update(JSONObject(d.toString()).put("ctl", JSONObject().put("on", false)), true)   // the baseline (no replayed requests)
        v.update(d, true)
        val w = 1240; val h = 1007
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            v.draw(c)
            ShadowSystemClock.advanceBy(Duration.ofMillis(400))              // past the first tooltip's quarter second and its fade
            ShadowLooper.idleMainLooper()
            Thread.sleep(150)                                                 // icons decode off the UI thread
            ShadowLooper.idleMainLooper()
        }
        val dir = out ?: return
        dir.mkdirs()
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        for (nw in listOf(false, true)) {
            val tag = if (nw) "i159" else "i158"
            shot("hover_chest_$tag.png", doc(5, nw))
            shot("hover_rings_$tag.png", doc(6, nw))
            shot("hover_2h_$tag.png", doc(7, nw))
            // the Y menu's Compare sheet stays as it was: the worn pieces' text (i159: from the equipped rows) and their changes
            shot("compare_2h_$tag.png", doc(7, nw).also { d -> d.getJSONObject("ctl").put("mode", "compare") })
            shot("compare_rings_$tag.png", doc(6, nw).also { d -> d.getJSONObject("ctl").put("mode", "compare") })
        }
    }
}
