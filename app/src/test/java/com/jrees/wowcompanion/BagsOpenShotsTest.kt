package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
 * bags-open1 (wk-containers; the user, 2026-11-16: "opening containers from my bags is a little cluncky, there is not a button although
 * weirdly x does work to open them, it opens the loot menu back in the home room, it should just open the loot menu inside of the bags and
 * then keep the player inside of the bag room."): the Bags room drawn off-device on the 1240 x 1080 bottom screen from made-up documents -
 *  1. the cursor on a clam: its tooltip's row reads A Move / X Open / Y More;
 *  2. the cursor on a lockbox the game says is Locked: "Locked" faint, no glyph (never tried);
 *  3. the clam opened: Interact's own loot window (its dim, cards, A Loot / X Loot All) drawn OVER the Bags room, which waits behind it
 *     (capture v21: the interact document's under = "bags"; BagsView.lootOver - no tooltip of its own);
 *  4. the loot taken: the Bags room as it was, the cursor on the same slot (the clam used up).
 * The room's 1007 px over the bottom bar's 73 px band (drawn plain here). Runs only when BAGS_SHOTS_DIR is set; BAGS_ART_ROOT = an art
 * root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BagsOpenShotsTest {
    private val out: File? = System.getenv("BAGS_SHOTS_DIR")?.let { File(it) }
    private val w = 1240
    private val roomH = 1007
    private val h = 1080

    private fun artRoot() {
        val root = System.getenv("BAGS_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }

    private fun item(slot: Int, id: Int, name: String, icon: Int, q: Int = 1, count: Int = 1) =
        JSONObject().put("bag", 0).put("slot", slot).put("id", id).put("icon", icon).put("count", count).put("quality", q).put("name", name)
            .put("itype", "Miscellaneous").put("isub", "Junk").put("cls", 15).put("sub", 0)

    private val CLAM = 2
    private val LOCKBOX = 3

    /** the backpack: a hearthstone, a clam (opens), a Locked lockbox, cloth, potions, a stone, a lockbox already picked (opens) */
    private fun items(clamGone: Boolean): JSONArray {
        val a = JSONArray()
        a.put(item(1, 6948, "Hearthstone", 134414).put("use", true).put("cls", 0))
        if (!clamGone) a.put(item(CLAM, 5523, "Small Barnacled Clam", 134433).put("opens", true))
        a.put(item(LOCKBOX, 4632, "Ornate Bronze Lockbox", 132595).put("lockedBox", true))
        a.put(item(4, 2589, "Linen Cloth", 132889, count = 12).put("cls", 7).put("itype", "Trade Goods").put("isub", "Cloth"))
        a.put(item(5, 118, "Minor Healing Potion", 134829, count = 4).put("use", true).put("cls", 0))
        a.put(item(6, 2835, "Rough Stone", 135241, count = 7).put("cls", 7).put("itype", "Trade Goods").put("isub", "Parts"))
        a.put(item(7, 4633, "Heavy Bronze Lockbox", 132596).put("opens", true))
        if (clamGone) {
            a.put(item(8, 5498, "Small Lustrous Pearl", 134120, q = 2).put("cls", 7).put("itype", "Trade Goods"))
            a.put(item(9, 5503, "Clam Meat", 133971, count = 2).put("cls", 7).put("itype", "Trade Goods"))
        }
        return a
    }

    private val tips = mapOf(
        CLAM to (5523 to arr(l("Small Barnacled Clam"), l("<Right Click to Open>", "00ff00"), l("Sell Price: 1 Copper", "ffffff"))),
        LOCKBOX to (4632 to arr(l("Ornate Bronze Lockbox"), l("Locked", "ff2020"), l("Requires Lockpicking (25)", "ff2020"))),
    )

    private fun bagsDoc(slot: Int, clamGone: Boolean = false): JSONObject {
        val ctl = JSONObject().put("on", true).put("mode", "grid").put("seq", 1).put("sid", 1).put("open", true)
            .put("cur", JSONObject().put("bag", 0).put("slot", slot))
        tips[slot]?.takeIf { !clamGone }?.let { (id, lines) -> ctl.put("tip", JSONObject().put("key", "bag 0 $slot $id").put("lines", lines)) }
        return JSONObject().put("free", if (clamGone) 7 else 9).put("total", 16).put("money", 10234)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16).put("free", if (clamGone) 7 else 9).put("fam", 0)))
            .put("items", items(clamGone)).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30).put("bmv", 29).put("bagsort", 28)))
    }

    /** Interact's loot document for the opened clam, the Bags room suspended under it (capture v21) */
    private fun lootDoc(): JSONObject {
        val loot = JSONObject().put("free", 9).put("items", JSONArray()
            .put(JSONObject().put("i", 1).put("icon", 133784).put("name", "3 Copper").put("count", 0).put("q", 0).put("coin", true))
            .put(JSONObject().put("i", 2).put("icon", 134120).put("name", "Small Lustrous Pearl").put("count", 1).put("q", 2).put("id", 5498))
            .put(JSONObject().put("i", 3).put("icon", 133971).put("name", "Clam Meat").put("count", 2).put("q", 1).put("id", 5503)))
            .put("act", JSONObject().put("all", 9))
        return JSONObject().put("type", "interact").put("iv", "i161").put("mode", true).put("holder", "npc").put("under", "bags")
            .put("bnext", "home").put("loot", loot)
            .put("nav", JSONObject().put("on", true).put("cur", "loot 2"))
            .put("tip", JSONObject().put("key", "loot 2").put("lines", arr(l("Small Lustrous Pearl", "1eff00"), l("Trade Goods"),
                l("Sell Price: 1 Silver"))))
    }

    private fun settle(vararg views: View, frames: Int = 4, draw: (Canvas) -> Unit) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0 until frames) {
            draw(Canvas(b))
            ShadowSystemClock.advanceBy(Duration.ofMillis(400))
            ShadowLooper.idleMainLooper()
            Thread.sleep(150)                                  // icons decode off the UI thread
            ShadowLooper.idleMainLooper()
        }
        last = b
    }
    private var last: Bitmap? = null

    private fun lay(v: View, vw: Int, vh: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(vw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(vh, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, vw, vh)
    }

    private fun page(c: Canvas, bags: BagsView, over: InteractView?) {
        c.drawColor(Theme.ink)
        lay(bags, w, roomH)
        bags.draw(c)
        // the bottom bar's band (the Strip itself is the host's): plain here
        c.drawRect(0f, roomH.toFloat(), w.toFloat(), h.toFloat(), Paint().apply { color = Theme.raised })
        if (over != null) { lay(over, w, h); over.draw(c) }
    }

    private fun save(name: String) {
        val dir = out ?: return
        dir.mkdirs()
        File(dir, name).outputStream().use { last!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bagsView(d: JSONObject): BagsView {
        val v = BagsView(RuntimeEnvironment.getApplication())
        v.update(JSONObject(d.toString()).put("ctl", JSONObject().put("on", false)), true)   // the baseline (no replayed requests)
        v.update(d, true)
        return v
    }

    @Test fun shots() {
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        // 1. the clam: X Open
        val clam = bagsView(bagsDoc(CLAM))
        settle(clam) { c -> page(c, clam, null) }
        save("1_bags_open_clam.png")
        // 2. the Locked lockbox: "Locked" faint, no glyph
        val box = bagsView(bagsDoc(LOCKBOX))
        settle(box) { c -> page(c, box, null) }
        save("2_bags_locked_box.png")
        // 3. the clam opened: the loot window over the Bags room (the room behind it, no tooltip of its own)
        val room = bagsView(bagsDoc(CLAM))
        val loot = InteractView(RuntimeEnvironment.getApplication())
        loot.dryRun = true
        val ld = lootDoc()
        // a live document (the clock LogTail stamps with) and the tap pad, so the window is not "Waiting for the game"
        val gn = InteractView::class.java.getDeclaredMethod("gameNow").apply { isAccessible = true }
        ld.put("_gt", gn.invoke(loot) as Long)
            .put("pad", JSONObject().put("rows", JSONObject().put("ibuy", 15).put("iact", 14).put("ipick", 13)))
        room.lootOver = BagsLoot.over(ld)
        loot.update(ld, true)
        settle(room, loot, frames = 5) { c -> page(c, room, loot) }
        save("3_bags_loot_inside.png")
        // 4. looted: the room as it was, the cursor on the same slot (the clam used up, the pearl in a free slot)
        room.lootOver = false
        room.update(bagsDoc(CLAM, clamGone = true), true)
        settle(room) { c -> page(c, room, null) }
        save("4_bags_after_loot.png")
    }
}
