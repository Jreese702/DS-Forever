package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * HOME'S QUEST CARD, OFF THE DEVICE (the user, 2026-11-17: "I didnt want the rewards on the quests in the home page I think that adds way
 * too much clutter, just in the actual full quest views. one thing that does make sense though on the home screen quests is showing the
 * Icon for if there is a usable quest Item that needs to be used for the quest"). The real HomeView and MapView, fed a quests document:
 *  - a quest with rewards draws the very card a quest without them draws (no reward line on Home);
 *  - a quest with a usable item (Quests.lua q64 `si`) draws its icon at the card's right edge, with its cooldown sweep;
 *  - the Quests room's detail shows the item beside the title.
 * The checks run always; the pictures only when HQC_SHOTS_DIR is set (HQC_ART_ROOT = an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeQuestCardsShotsTest {
    private val out: File? = System.getenv("HQC_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("HQC_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }
    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(n: Int = 20) { for (i in 0 until n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun item(n: String, ic: Int, q: Int, id: Int) = JSONObject().put("n", n).put("ic", ic).put("c", 1).put("q", q).put("id", id)
    private val RW = JSONObject().put("m", 75).put("xp", 450)
        .put("ch", JSONArray().put(item("Fur-lined Boots", 132539, 2, 2032)).put(item("Hunting Gloves", 132952, 2, 2033)))

    /** the focused quest (Lost Necklace-like: one short objective), with or without its rewards and its item */
    private fun questsDoc(rewards: Boolean, si: JSONObject?): JSONObject {
        val focused = JSONObject().put("id", 33).put("title", "Wolves Across the Border").put("zone", "Elwynn Forest").put("lv", 6).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Den burned: 0/1").put("have", 0).put("need", 1)))
            .put("desc", "Those wolves are a menace. Burn their dens with the torch I gave you.")
        if (rewards) focused.put("rw", RW)
        if (si != null) focused.put("si", si)
        val other = JSONObject().put("id", 46).put("title", "Bounty on Murlocs").put("zone", "Elwynn Forest").put("lv", 8).put("slot", 2).put("watch", 1)
            .put("obj", JSONArray().put(JSONObject().put("t", "Torn Murloc Fin: 3/8").put("have", 3).put("need", 8)))
        if (rewards) other.put("rw", JSONObject().put("m", 600).put("xp", 750))
        return JSONObject().put("plv", 7).put("focus", 33).put("rwMode", "id").put("list", JSONArray().put(focused).put(other))
    }
    private val SI = JSONObject().put("id", 23688).put("ic", 135432).put("c", 3)

    private class Page(val home: HomeView, val map: MapView)

    private fun page(doc: JSONObject): Page {
        art()
        HomeSettings.init(app)
        Settings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("map", JSONObject().put("zone", "Elwynn Forest").put("pvp", "contested"))
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))), true)
        QuestItem.Cd.onQuests(doc, true)
        map.onQuests(doc); home.onQuests(doc, true)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home)
        HomeView::class.java.getDeclaredMethod("changed").apply { isAccessible = true }.invoke(home)
        settle()
        lay(home)
        return Page(home, map)
    }

    private fun draw(p: Page): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            lay(p.home)
            val c = Canvas(b); c.drawColor(Theme.ink); p.home.draw(c)
            if (pass == 0) settle()
        }
        return b
    }

    /** the quest pane's box on the page (HomeView's own child, its offsets summed up to the page) */
    private fun cardBox(p: Page): Rect {
        val f = HomeView::class.java.getDeclaredField("questPane"); f.isAccessible = true
        val v = f.get(p.home) as View
        var x = v.left; var y = v.top
        var par = v.parent
        while (par is View && par !== p.home) { x += par.left; y += par.top; par = par.parent }
        assertTrue("the quest pane is laid out (${v.width} x ${v.height})", v.width > 100 && v.height > 40)
        return Rect(x, y, x + v.width, y + v.height)
    }
    private fun crop(b: Bitmap, r: Rect): Bitmap = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
    private fun same(a: Bitmap, b: Bitmap): Boolean = a.width == b.width && a.height == b.height && a.sameAs(b)

    @Test fun homesCardDrawsNoRewards() {
        val withRw = page(questsDoc(rewards = true, si = null))
        val a = draw(withRw); val box = cardBox(withRw)
        val plain = page(questsDoc(rewards = false, si = null))
        val b = draw(plain)
        save(a, "1_home_rewards_in_doc")
        save(crop(a, box), "1_home_card_rewards_in_doc")
        assertTrue("Home's card with rewards in the document is the card without them (no reward line)", same(crop(a, box), crop(b, cardBox(plain))))
    }

    /** how many pixels differ between [a] and [b] inside [r] */
    private fun diff(a: Bitmap, b: Bitmap, r: Rect): Int {
        var n = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    @Test fun homesCardShowsTheQuestItem() {
        QuestItem.Cd.reset()
        val withItem = page(questsDoc(rewards = true, si = SI))
        val card = withItem.map.focusedCard()!!
        assertEquals("the card carries the quest's item", 23688, card.item?.id)
        assertEquals(3, card.item?.count)
        val a = draw(withItem); val box = cardBox(withItem)
        val plain = page(questsDoc(rewards = false, si = null))
        val b = draw(plain)
        save(a, "2_home_quest_item")
        save(crop(a, box), "2_home_card_quest_item")
        // the icon at the card's right edge, on the title row; the left half (disc, title start, objective) as without it
        val right = Rect(box.right - box.height().coerceAtMost(90), box.top, box.right, box.top + box.height() / 2 + 10)
        val left = Rect(box.left, box.top, box.left + box.width() / 2, box.bottom)
        assertTrue("the item's icon at the card's right edge", diff(a, b, right) > 400)
        assertEquals("the card's left half untouched", 0, diff(a, b, left))

        // its cooldown: the game's dark sweep over it
        QuestItem.Cd.reset()
        val cdDoc = questsDoc(rewards = false, si = JSONObject(SI.toString()).put("cd", JSONObject().put("d", 30).put("l", 12)))
        val cooling = page(cdDoc)
        assertTrue("the cooldown is counted", QuestItem.Cd.left(23688) in 11.0..12.0)
        val c = draw(cooling)
        save(c, "3_home_quest_item_cooldown")
        save(crop(c, cardBox(cooling)), "3_home_card_quest_item_cooldown")
        assertTrue("the sweep darkens the icon", diff(a, c, right) > 100)
        // folded (the title row only): the item stays on the title row
        QuestItem.Cd.reset()
    }

    @Test fun theQuestsDetailShowsTheItem() {
        art()
        Settings.init(app)
        fun room(si: JSONObject?): QuestsView {
            val v = QuestsView(app, { null }, { })
            v.update(questsDoc(rewards = true, si = si))
            lay(v)
            return v
        }
        fun frame(v: View): Bitmap {
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            for (i in 0 until 3) { settle(4); val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c) }
            return b
        }
        val with = frame(room(SI))
        val without = frame(room(null))
        save(with, "4_quests_detail_item")
        // the detail pane is the room's right part; the item beside the title, at its top right
        val topRight = Rect(W - 160, 0, W, 200)
        assertTrue("the item's icon at the detail's top right", diff(with, without, topRight) > 400)
        val list = Rect(0, 0, W / 3, H)
        assertEquals("the list untouched", 0, diff(with, without, list))
    }
}
