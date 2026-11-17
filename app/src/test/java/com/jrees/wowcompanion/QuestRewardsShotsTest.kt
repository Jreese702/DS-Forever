package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Quests q63 OFF THE DEVICE (the user, 2026-11-17: "The quests don't show rewards at all anywhere"): the Quests room's detail pane
 * with a quest's rewards (two choose-one items, a fixed stack, a spell, XP and money), driven by the controller as DirectPad delivers
 * it (Right onto the rewards, Down, Left / B back) and by a finger (a reward's tooltip), and Home's quest card with its compact reward
 * line. The checks run always; the pictures only when QRW_SHOTS_DIR is set (QRW_ART_ROOT = an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class QuestRewardsShotsTest {
    private val out: File? = System.getenv("QRW_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun art() {
        System.getenv("QRW_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }

    private fun item(n: String, ic: Int, q: Int, id: Int, c: Int = 1, usable: Boolean = true) =
        JSONObject().put("n", n).put("ic", ic).put("c", c).put("q", q).put("id", id).also { if (!usable) it.put("u", false) }

    private fun questsDoc(): JSONObject {
        val a = JSONArray()
        val wolves = JSONObject().put("id", 33).put("title", "Wolves Across the Border").put("zone", "Elwynn Forest").put("lv", 6).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Tough Wolf Meat: 8/8").put("have", 8).put("need", 8).put("f", true)))
            .put("ready", true)
            .put("desc", "Those wolves are a menace. Bring me eight pieces of Tough Wolf Meat and I'll see that you're well paid.")
            .put("rw", JSONObject().put("m", 2575).put("xp", 450)
                .put("ch", JSONArray().put(item("Fur-lined Boots", 132539, 2, 2032)).put(item("Hunting Gloves", 132952, 2, 2033, usable = false)))
                .put("it", JSONArray().put(item("Tough Jerky", 133970, 1, 117, c = 5)))
                .put("sp", JSONArray().put(JSONObject().put("n", "Flametongue Weapon").put("ic", 135814).put("id", 8024))))
        a.put(wolves)
        a.put(JSONObject().put("id", 46).put("title", "Bounty on Murlocs").put("zone", "Elwynn Forest").put("lv", 8).put("slot", 2).put("watch", 1)
            .put("obj", JSONArray().put(JSONObject().put("t", "Torn Murloc Fin: 3/8").put("have", 3).put("need", 8)))
            .put("rw", JSONObject().put("m", 600).put("xp", 750)))
        a.put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup").put("zone", "Northshire").put("lv", 3).put("slot", 3)
            .put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10").put("have", 4).put("need", 10))))
        return JSONObject().put("list", a).put("plv", 7).put("maxQ", 25).put("rwMode", "id")
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun frames(v: View, n: Int = 4): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (i in 0 until n) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(200))                  // past the tooltip's quarter second and its fade
            ShadowLooper.idleMainLooper()
            val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        }
        return b
    }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun <T> field(v: QuestsView, name: String): T {
        val f = QuestsView::class.java.getDeclaredField(name); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return f.get(v) as T
    }

    private fun room(): QuestsView {
        art()
        Settings.init(app)
        val v = QuestsView(app, { null }, { })
        v.update(questsDoc())
        v.onGuestAck(JSONObject().put("op", "hold").put("ok", true))         // the room holds the controller: the cursor shows
        lay(v)
        frames(v)
        return v
    }

    @Test fun theDetailShowsTheRewardsAndTheCursorReadsThem() {
        QuestRewardTips.clear()
        QuestRewardTips.onDoc(JSONObject().put("type", "qrtip").put("id", 2033).put("tip", JSONObject().put("key", "link item:2033")
            .put("lines", JSONArray().put(JSONObject().put("t", "Hunting Gloves").put("c", "1eff00")).put(JSONObject().put("t", "Binds when equipped"))
                .put(JSONObject().put("t", "Hands").put("r", "Mail").put("c", "ffffff").put("rc", "ff2020"))
                .put(JSONObject().put("t", "64 Armor")).put(JSONObject().put("t", "+2 Agility")).put(JSONObject().put("t", "+1 Stamina"))
                .put(JSONObject().put("t", "Requires Level 6")))))
        val v = room()
        save(frames(v), "1_detail_rewards")
        assertEquals(-1, field<Int>(v, "rewardAt"))
        val hits: ArrayList<Pair<RectF, Int>> = field(v, "rewardHits")
        assertTrue("the reward rows are on screen", hits.size in 2..4 && hits[0].second == 0)
        // the user's pick (2026-11-17, "Above the description"): the rewards right under the objectives, not after the story
        assertTrue("the first reward row near the top: ${hits[0].first}", hits[0].first.top < 500f)

        v.onDirect("PADDRIGHT"); frames(v, 1)
        assertEquals("Right: onto the first reward", 0, field<Int>(v, "rewardAt"))
        v.onDirect("PADDDOWN")
        assertEquals(1, field<Int>(v, "rewardAt"))
        save(frames(v), "2_focused_reward_tooltip")
        v.onDirect("PADDDOWN"); v.onDirect("PADDDOWN")
        assertEquals("the spell", 3, field<Int>(v, "rewardAt"))
        save(frames(v), "3_focused_spell")
        v.onDirect("PADDLEFT")
        assertEquals("Left: back to the list", -1, field<Int>(v, "rewardAt"))
        v.onDirect("PADDDOWN")
        assertEquals("the list moves again", 46, field<Int>(v, "selectedId"))
        v.onDirect("PADDRIGHT")
        assertEquals("Right: onto Bounty on Murlocs' rewards - none to stand on (money and XP only)", -1, field<Int>(v, "rewardAt"))
        save(frames(v), "4_money_xp_only")
        v.onDirect("PADDUP"); frames(v, 1)
        v.onDirect("PADDRIGHT"); v.onDirect("PAD2")
        assertEquals("B: back to the list", -1, field<Int>(v, "rewardAt"))

        // a finger: a reward's row shows its tooltip, the same row again hides it
        frames(v, 1)
        val row = field<ArrayList<Pair<RectF, Int>>>(v, "rewardHits").first { it.second == 0 }.first
        fun tap(x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            v.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        }
        tap(row.centerX(), row.centerY())
        assertEquals(0, field<Int>(v, "rewardTouch"))
        save(frames(v), "5_touch_tooltip")
        tap(row.centerX(), row.centerY())
        assertEquals(-1, field<Int>(v, "rewardTouch"))
    }

    @Test fun homesCardCarriesTheRewardLine() {
        art()
        val doc = questsDoc()
        val list = doc.getJSONArray("list")
        fun card(e: JSONObject, lines: List<Pair<String, Boolean>>, turnIn: Boolean) =
            UiKit.QuestCard(e.optString("title"), e.optInt("lv"), Theme.questGreen, lines, turnIn = turnIn, number = 1, focused = true,
                rewards = QuestRewards.parse(e)?.strip())
        val cards = listOf(
            card(list.getJSONObject(0), listOf("Tough Wolf Meat: 8/8" to true), true),
            card(list.getJSONObject(1), listOf("Torn Murloc Fin: 3/8" to false), false),
            card(list.getJSONObject(0), listOf("Collect the Tough Wolf Meat from the wolves of the eastern hills: 3/8" to false), false))
        assertTrue("a strip for the wolves", cards[0].rewards != null && cards[0].rewards!!.icons.size == 3)
        val w = 600f
        val b = Bitmap.createBitmap(W, 700, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink)
        var y = 20f
        for ((i, q) in cards.withIndex()) {
            val h = UiKit.questCardHeight(q, UiKit.CARD_HOME_SCALE)
            UiKit.questCard(c, RectF(20f, y, 20f + w, y + h), q, scale = UiKit.CARD_HOME_SCALE, rewardLine = true)
            // the same card as the Map room's list draws it (no line there)
            UiKit.questCard(c, RectF(640f, y, 640f + 400f, y + UiKit.questCardHeight(q)), q)
            y += h + 24f
            if (i == 0) assertTrue(UiKit.rewardStripWidth(q.rewards!!, UiKit.CARD_HOME_SCALE) < w / 2f)
        }
        save(b, "6_home_cards")
    }
}
