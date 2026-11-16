package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
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
 * loot-pop (the user, 2026-11-16: "The loot pop up looks weird offset, I want it to be centered and then animate over to the side to make
 * space for the tooltip. / The loot roll pop up is WAY too intrusive during a fight / there should be a smaller version that pops up over
 * the minimap and gets bigger when transitioning out of combat. / during combat it should accept touch input and show things per
 * item/roll"): the pictures, off-device on the 1240 x 1080 bottom screen, the real Home behind -
 *  1. the loot window CENTRED (the cursor on the coins: no tooltip);           2. mid-slide (the cursor moved to an item: its tooltip comes);
 *  3. SLID ASIDE with the tooltip beside it;                                  4. the same over the Bags room (bags-open1);
 *  5. a fight: the small roll panel over the minimap, ONE roll;               6. ... THREE rolls (one more counted);
 *  7. ... a bind-on-pickup Need: the game's confirm on its row;               8. the fight's end: the panel GROWING (mid-frame);
 *  9. the full roll window (gear: Currently Equipped beside its tooltip);   10. (review) the tap-to-cast bar under the minimap in a fight:
 *     the panel fits the cut module (two rows, the rest counted), never over the bar.
 * Runs only when LOOT_SHOTS_DIR is set; LOOT_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LootPopShotsTest {
    private val out: File? = System.getenv("LOOT_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 1080
    private val homeH = 1080 - Strip.HEIGHT

    private fun artRoot() {
        val root = System.getenv("LOOT_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun lay(v: View, vw: Int, vh: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(vw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(vh, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, vw, vh)
    }

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }
    private fun later(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); ShadowLooper.idleMainLooper() }

    // ---------------------------------------------------------------- the real Home behind

    private fun home(fight: Boolean): HomeView {
        HomeSettings.init(app)
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, w, homeH)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))
            .put("hudS", JSONObject().put("c", fight))), true)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, w, homeH)
        return home
    }

    private var shot: Bitmap? = null
    /** draws [frames] passes ([step] ms apart; icons decode off the UI thread), the last one kept */
    private fun page(frames: Int = 3, step: Long = 400L, draw: (Canvas) -> Unit) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0 until frames) {
            val c = Canvas(b); c.drawColor(Theme.ink)
            draw(c)
            if (pass < frames - 1) { later(step); Thread.sleep(120); ShadowLooper.idleMainLooper() }
        }
        shot = b
    }
    private fun homePage(c: Canvas, home: HomeView, strip: Strip) {
        home.draw(c)
        lay(strip, w, Strip.HEIGHT)
        c.save(); c.translate(0f, homeH.toFloat()); strip.draw(c); c.restore()
    }
    private fun save(name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- the loot window (Interact)

    private fun lootDoc(v: InteractView, cur: String): JSONObject {
        val loot = JSONObject().put("free", 9).put("items", JSONArray()
            .put(JSONObject().put("i", 1).put("icon", 133784).put("name", "24 Copper").put("count", 0).put("q", 0).put("coin", true))
            .put(JSONObject().put("i", 2).put("icon", 135274).put("name", "Cruel Barb").put("count", 1).put("q", 3).put("id", 5191))
            .put(JSONObject().put("i", 3).put("icon", 132889).put("name", "Linen Cloth").put("count", 3).put("q", 1).put("id", 2589)))
            .put("act", JSONObject().put("all", 9))
        val gn = InteractView::class.java.getDeclaredMethod("gameNow").apply { isAccessible = true }
        val d = JSONObject().put("type", "interact").put("iv", "i162").put("mode", true).put("holder", "npc")
            .put("bnext", "home").put("loot", loot).put("nav", JSONObject().put("on", true).put("cur", cur))
            .put("_gt", gn.invoke(v) as Long).put("pad", JSONObject().put("rows", JSONObject().put("ibuy", 15).put("iact", 14).put("ipick", 13)))
        if (cur == "loot 2") d.put("tip", JSONObject().put("key", "loot 2").put("lines", arr(l("Cruel Barb", "0070dd"), l("Binds when equipped"),
            l("One-Hand", "ffffff").put("r", "Sword").put("rc", "ffffff"), l("30 - 57 Damage", "ffffff").put("r", "Speed 2.80").put("rc", "ffffff"),
            l("(15.5 damage per second)"), l("+5 Strength"), l("Durability 75 / 75"), l("Requires Level 18"), l("Sell Price: 1 Silver 87 Copper"))))
        return d
    }

    @Test fun lootWindow() {
        if (out == null) return
        artRoot(); Tr.init(app)
        val home = home(false); val strip = Strip(app) { }
        val loot = InteractView(app); loot.dryRun = true
        // 1. centred: the cursor on the coins (no tooltip)
        loot.update(lootDoc(loot, "loot 1"), true)
        lay(loot, w, h)
        page(frames = 4) { c -> homePage(c, home, strip); loot.draw(c) }
        save("1_loot_centered.png")
        // 2. the cursor moves to the sword: its tooltip comes, the window slides aside - stopped at 70 of its 180 ms (the window's own
        // animation clock, fxClock, held still for the frame)
        loot.update(lootDoc(loot, "loot 2"), true)
        val t0 = System.currentTimeMillis()
        loot.fxClock = { t0 }
        page(frames = 1) { c -> homePage(c, home, strip); loot.draw(c) }
        loot.fxClock = { t0 + 70L }
        page(frames = 1) { c -> homePage(c, home, strip); loot.draw(c) }
        save("2_loot_sliding.png")
        loot.fxClock = { System.currentTimeMillis() }
        // 3. aside, the tooltip beside it (its own wait and fade done)
        page(frames = 3) { c -> homePage(c, home, strip); loot.draw(c) }
        save("3_loot_aside_tooltip.png")
        // 4. the same window over the Bags room (bags-open1: the loot window inside Bags)
        val bags = BagsView(app)
        val bd = JSONObject().put("free", 9).put("total", 16).put("money", 10234)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16).put("free", 9).put("fam", 0)))
            .put("items", JSONArray().put(JSONObject().put("bag", 0).put("slot", 1).put("id", 6948).put("icon", 134414).put("count", 1).put("quality", 1)
                .put("name", "Hearthstone").put("use", true).put("cls", 0)))
            .put("ctl", JSONObject().put("on", true).put("mode", "grid").put("seq", 1).put("sid", 1).put("open", true)
                .put("cur", JSONObject().put("bag", 0).put("slot", 1)))
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30).put("bmv", 29).put("bagsort", 28)))
        bags.update(JSONObject(bd.toString()).put("ctl", JSONObject().put("on", false)), true)
        bags.update(bd, true)
        bags.lootOver = true
        val ov = InteractView(app); ov.dryRun = true
        ov.update(lootDoc(ov, "loot 2").put("under", "bags"), true)
        lay(ov, w, h)
        page(frames = 5) { c ->
            lay(bags, w, 1007); bags.draw(c)
            c.drawRect(0f, 1007f, w.toFloat(), h.toFloat(), Paint().apply { color = Theme.raised })
            ov.draw(c)
        }
        save("4_loot_aside_over_bags.png")
    }

    // ---------------------------------------------------------------- the rolls (Dialogs)

    private fun gameNow(v: DialogView): Long =
        DialogView::class.java.getDeclaredMethod("gameNow").apply { isAccessible = true }.invoke(v) as Long

    private fun roll(id: Int, name: String, icon: Int, q: Int, bop: Boolean = false, de: Boolean = false, left: Int = 52) =
        JSONObject().put("id", id).put("name", name).put("icon", icon).put("q", q).put("need", true).put("greed", true).put("de", de)
            .put("bop", bop).put("left", left).put("dur", 60)
            .put("cells", JSONObject().put("need", id * 10 + 1).put("greed", id * 10 + 2).put("pass", id * 10).put("de", id * 10 + 3))

    private fun barb() = roll(1, "Cruel Barb", 135274, 3).put("tip", JSONObject()
        .put("lines", arr(l("Cruel Barb", "0070dd"), l("Binds when equipped"), l("One-Hand").put("r", "Sword").put("rc", "ffffff"),
            l("30 - 57 Damage").put("r", "Speed 2.80").put("rc", "ffffff"), l("(15.5 damage per second)"), l("+5 Strength"),
            l("Durability 75 / 75"), l("Requires Level 18"), l("Sell Price: 1 Silver 87 Copper")))
        .put("cmp", arr(JSONObject().put("lines", arr(l("Worn Shortsword", "ffffff"), l("One-Hand").put("r", "Sword").put("rc", "ffffff"),
            l("1 - 3 Damage").put("r", "Speed 1.90").put("rc", "ffffff"), l("(1.1 damage per second)"), l("Durability 16 / 16"))))))

    private fun rollsDoc(v: DialogView, rolls: List<JSONObject>, more: Int = 0) = JSONObject().put("type", "dialogs").put("dv", "d34")
        .put("mode", true).put("list", JSONArray()).put("touch", true).put("rollsMore", more)
        .put("rolls", JSONArray().also { a -> rolls.forEachIndexed { i, r -> a.put(r.put("sel", i == 0)) } })
        .put("life", JSONObject().put("col", 3).put("every", 5))
        .put("pad", JSONObject().put("rows", JSONObject().put("isell", 16).put("idlg", 18).put("iact", 14)))
        .put("_gt", gameNow(v))

    private var fight = true
    private fun rollsOver(home: HomeView): DialogView {
        val v = DialogView(app)
        v.dryRun = true
        v.fightNow = { fight }
        v.mapBox = { mapRect(home) }
        return v
    }

    /** Home's minimap module as laid (its `now` box for MAP; the host gives the same through previewMapRect) */
    @Suppress("UNCHECKED_CAST")
    private fun mapRect(home: HomeView): RectF? {
        val now = HomeView::class.java.getDeclaredField("now").apply { isAccessible = true }.get(home) as Map<Any, RectF>
        return now.entries.firstOrNull { it.key.toString() == "MAP" }?.value?.let { RectF(it) }
    }

    @Test fun rolls() {
        if (out == null) return
        artRoot(); Tr.init(app)
        fight = true
        val home = home(true); val strip = Strip(app) { }
        // 5. a fight: one roll over the minimap
        val one = rollsOver(home)
        one.update(rollsDoc(one, listOf(barb())), true)
        lay(one, w, h)
        page(frames = 3) { c -> homePage(c, home, strip); one.draw(c) }
        save("5_fight_compact_1_roll.png")
        // 6. three rolls (and one more counted)
        val three = rollsOver(home)
        three.update(rollsDoc(three, listOf(barb(), roll(2, "Smelting Pants", 134588, 2, de = true, left = 47),
            roll(3, "Linen Cloth", 132889, 1, left = 40).put("count", 4)), more = 1), true)
        lay(three, w, h)
        page(frames = 3) { c -> homePage(c, home, strip); three.draw(c) }
        save("6_fight_compact_3_rolls.png")
        // 7. a bind-on-pickup Need: the game's confirm on its row (a finger on Need)
        val bop = rollsOver(home)
        bop.update(rollsDoc(bop, listOf(roll(4, "Gold-flecked Gloves", 132939, 3, bop = true), roll(2, "Smelting Pants", 134588, 2))), true)
        lay(bop, w, h)
        page(frames = 3) { c -> homePage(c, home, strip); bop.draw(c) }
        @Suppress("UNCHECKED_CAST")
        val hits = (DialogView::class.java.getDeclaredField("hits").apply { isAccessible = true }.get(bop) as ArrayList<Pair<RectF, String>>)
        hits.lastOrNull { it.second == "roll:4:need" }?.first?.let { r ->
            val t0 = android.os.SystemClock.uptimeMillis()
            bop.dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0))
            bop.dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0 + 40, android.view.MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0))
        }
        // review: then the game's own pop-up (its focus: A / B are its in a fight too) - its keys on its buttons
        bop.update(rollsDoc(bop, listOf(roll(4, "Gold-flecked Gloves", 132939, 3, bop = true).put("ask", "need").put("askI", 1),
            roll(2, "Smelting Pants", 134588, 2))).put("list", JSONArray().put(JSONObject().put("i", 1).put("which", "CONFIRM_LOOT_ROLL")
            .put("roll", 4).put("rollType", 1).put("text", "Looting |cff0070ddGold-flecked Gloves|r will bind it to you.").put("focus", true)
            .put("buttons", arr(JSONObject().put("k", 1).put("label", "Okay").put("on", true).put("key", "PAD1"),
                JSONObject().put("k", 2).put("label", "Cancel").put("on", true).put("key", "PAD2")))
            .put("cells", JSONObject().put("b1", 0).put("b2", 1)))), true)
        page(frames = 2) { c -> homePage(c, home, strip); bop.draw(c) }
        save("7_fight_compact_bop_confirm.png")
        // 8. the fight ends: the panel grows into the window (half way)
        val grow = rollsOver(home)
        grow.update(rollsDoc(grow, listOf(barb(), roll(2, "Smelting Pants", 134588, 2, de = true, left = 47))), true)
        lay(grow, w, h)
        val calm = home(false)
        page(frames = 3) { c -> homePage(c, home, strip); grow.draw(c) }
        fight = false
        // the grow's own clock held 30 % into it for the frame (the panel fading, the window growing)
        val g0 = System.currentTimeMillis()
        grow.drawClock = { g0 }
        page(frames = 1) { c -> homePage(c, calm, strip); grow.draw(c) }
        grow.drawClock = { g0 + DialogView.GROW_MS * 3 / 10 }
        page(frames = 1) { c -> homePage(c, calm, strip); grow.draw(c) }
        save("8_fight_end_grow_mid.png")
        grow.drawClock = { System.currentTimeMillis() }
        // 9. grown: the full roll window
        page(frames = 3) { c -> homePage(c, calm, strip); grow.draw(c) }
        save("9_full_roll_window.png")
        // 10. (review) the tap-to-cast bar under the minimap, kept in fights (Right, Stay): the module is cut short, the panel fits in it -
        // two rows, the rest counted - and never reaches the bar
        HomeSettings.castBarSide = CastStripModel.Side.RIGHT
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        fight = true
        val barHome = home(true)
        val four = rollsOver(barHome)
        four.update(rollsDoc(four, listOf(barb(), roll(2, "Smelting Pants", 134588, 2, de = true, left = 47),
            roll(3, "Linen Cloth", 132889, 1, left = 40).put("count", 4), roll(5, "Bone Ring", 133345, 2, left = 38))), true)
        lay(four, w, h)
        page(frames = 3) { c -> homePage(c, barHome, strip); four.draw(c) }
        save("10_fight_compact_cast_bar_right.png")
    }
}
