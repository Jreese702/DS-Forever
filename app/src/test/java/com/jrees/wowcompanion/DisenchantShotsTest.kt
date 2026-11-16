package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
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
 * bags-de1 THE DISENCHANT GRID, OFF-DEVICE (the user's list 2026-11-16: "A custom grid of bag items that can be disenchanted. Hover shows
 * the full tooltip, as in Bags. It stays open after each one; B closes it. The disenchant cast bar is visible."; their picks the same day,
 * every RECOMMENDED option of design_disenchant.md). The real [BagsView] drawn from made-up bags documents in the add-on's shape (Bags.lua
 * DE.doc): the grid with the cursor's tooltip and its green arrows, a disenchant casting (Home's own cast bar painted into the foot by a
 * real [CastView], the tile's sweep), the blue ask, the strip of what Auto Loot gave, a fight (dimmed), nothing to disenchant, and the
 * Bags room's top strip with the Disenchant icon under the controller's cursor. The painter's own checks run always; the pictures only
 * when DE_SHOTS_DIR is set (DE_ART_ROOT = an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DisenchantShotsTest {
    private val out: File? = System.getenv("DE_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1007
    private val G = 5000.0

    private fun art() {
        System.getenv("DE_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
    }
    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }

    // the bags: 14 pieces of green-and-better gear among the rest (bag 0: 16 slots, bag 1: 10)
    private data class It(val bag: Int, val slot: Int, val id: Int, val icon: Int, val q: Int, val name: String, val equip: String)
    private val gear = listOf(
        It(0, 2, 5001, 135274, 2, "Brutish Sword", "INVTYPE_WEAPON"), It(0, 3, 5002, 132723, 3, "Glimmering Mail Breastplate", "INVTYPE_CHEST"),
        It(0, 5, 5003, 132539, 2, "Ridge Cleaver", "INVTYPE_2HWEAPON"), It(0, 6, 5004, 133345, 4, "Band of the Unicorn", "INVTYPE_FINGER"),
        It(0, 7, 5005, 132938, 2, "Rawhide Gloves", "INVTYPE_HAND"), It(0, 9, 5006, 132512, 2, "Bandit Boots", "INVTYPE_FEET"),
        It(0, 10, 5007, 133071, 2, "Durable Hat", "INVTYPE_HEAD"), It(0, 11, 5008, 132492, 2, "Bright Belt", "INVTYPE_WAIST"),
        It(0, 13, 5009, 133122, 2, "Forest Leather Bracers", "INVTYPE_WRIST"), It(0, 14, 5010, 135145, 3, "Gnarled Staff", "INVTYPE_2HWEAPON"),
        It(0, 16, 5011, 134955, 2, "Fortified Shield", "INVTYPE_SHIELD"), It(1, 1, 5012, 133768, 2, "Scouting Cloak", "INVTYPE_CLOAK"),
        It(1, 3, 5013, 135612, 2, "Thick Bark Bow", "INVTYPE_RANGED"), It(1, 4, 5014, 132629, 2, "Ancestral Leggings", "INVTYPE_LEGS"))

    private fun bagRow(g: It) = JSONObject().put("bag", g.bag).put("slot", g.slot).put("id", g.id).put("icon", g.icon).put("count", 1)
        .put("quality", g.q).put("name", g.name).put("equip", g.equip).put("itype", if (g.equip.contains("WEAPON") || g.equip == "INVTYPE_RANGED") "Weapon" else "Armor")

    private fun tipOf(g: It, gains: Boolean) = JSONObject().put("key", "bag ${g.bag} ${g.slot} ${g.id}")
        .put("lines", arr(l(g.name, when (g.q) { 3 -> "0070dd"; 4 -> "a335ee"; else -> "1eff00" }), l("Binds when equipped"), l("One-Hand"), l("Sword", "ffffff"),
            l("9 - 17 Damage"), l("Speed 2.20"), l("(5.9 damage per second)"), l("+3 Strength"), l("+2 Stamina"), l("Durability 55 / 55"),
            l("Requires Level 18")).also { a -> a.put(JSONObject().put("t", "Sell Price:").put("m", 1234)) })
        .put("cmp", JSONArray().put(JSONObject().put("slot", 16).put("dn", 3).put("nw", true).put("lines", arr(l(" "),
            l("If you replace this item, the following stat changes will occur:", "ffd100"),
            l("+1.6 Damage Per Second", "00ff00"), l("+3 Strength", "00ff00"),
            if (gains) l("+2 Stamina", "00ff00") else l("-1 Stamina", "ff2020")))))

    /** the add-on's documents: the bags (items + layout) and ctl in mode "bulk" with [bulk] (Bags.lua DE.doc's shape) */
    private fun doc(sel: Int, bulk: JSONObject, tip: Boolean = true): JSONObject {
        val items = JSONArray()
            .put(JSONObject().put("bag", 0).put("slot", 1).put("id", 6948).put("icon", 134414).put("count", 1).put("quality", 1).put("name", "Hearthstone"))
            .put(JSONObject().put("bag", 0).put("slot", 4).put("id", 117).put("icon", 133972).put("count", 12).put("quality", 1).put("name", "Tough Jerky"))
            .put(JSONObject().put("bag", 0).put("slot", 8).put("id", 5003).put("icon", 132492).put("count", 1).put("quality", 1).put("name", "Worn Belt").put("equip", "INVTYPE_WAIST"))
        gear.forEach { items.put(bagRow(it)) }
        val list = JSONArray()
        gear.forEachIndexed { i, g ->
            val e = JSONObject().put("b", g.bag).put("s", g.slot).put("id", g.id).put("q", g.q)
            if (i == 0 || i == 3 || i == 6 || i == 11) e.put("up", true)
            if (i == 12) e.put("lk", true)        // (review: a coloured icon in the 2nd row, clear of the tooltips: its grey shows)
            list.put(e)
        }
        bulk.put("list", list).put("sel", sel).put("n", "Disenchant").put("i", 132853).put("ct", 3000).put("back", "grid")
        val cur = gear[sel - 1]
        val ctl = JSONObject().put("on", true).put("mode", "bulk").put("seq", 7).put("sid", 1)
            .put("cur", JSONObject().put("bag", cur.bag).put("slot", cur.slot)).put("bulk", bulk)
        if (tip) ctl.put("tip", tipOf(cur, true))
        return JSONObject().put("free", 6).put("total", 26).put("money", 482310)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16)).put(JSONObject().put("bag", 1).put("slots", 10).put("icon", 133628)))
            .put("items", items).put("ctl", ctl)
            .put("de", JSONObject().put("n", "Disenchant").put("i", 132853).put("ct", 3000).put("col", 9))
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30).put("bagsort", 6).put("bmv", 55)))
    }

    private fun castView(cs: JSONObject?): CastView {
        val cast = CastView(app) { }
        val holder = FrameLayout(app); holder.addView(cast)
        lay(cast, W, 400)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        val s = JSONObject().put("g", G)
        if (cs != null) s.put("cs", cs)
        cast.update(JSONObject().put("hudS", s))
        ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper()
        return cast
    }

    private fun shot(name: String, d: JSONObject, cast: CastView? = null, frames: Int = 4) {
        val v = BagsView(app)
        if (cast != null) v.castBar = { c, r, now -> cast.drawCastInto(c, r, now) }
        v.update(JSONObject(d.toString()).put("ctl", JSONObject().put("on", false)), true)      // the baseline (no replayed requests)
        v.update(d, true)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0 until frames) {
            lay(v, W, H)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            v.draw(c)
            ShadowSystemClock.advanceBy(Duration.ofMillis(if (cast != null) 40 else 400))
            ShadowLooper.idleMainLooper()
            Thread.sleep(150)
            ShadowLooper.idleMainLooper()
        }
        val dir = out ?: return
        dir.mkdirs()
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- the painter (always)

    @Test fun thePainterDrawsHomesCastIntoAnotherBox() {
        art()
        val idle = castView(null)
        val bm = Bitmap.createBitmap(600, 40, Bitmap.Config.ARGB_8888)
        val now = SystemClock.elapsedRealtime()
        assertEquals("nothing runs: nothing drawn, no redraw asked", Long.MAX_VALUE, idle.drawCastInto(Canvas(bm), RectF(0f, 5f, 600f, 35f), now))
        assertFalse(idle.castShowing(now))
        assertTrue("a blank box stays blank", (0 until 600 step 7).all { x -> bm.getPixel(x, 20) == 0 })
        val cast = castView(JSONObject().put("k", "c").put("s", G - 1.0).put("e", G + 2.0).put("nm", "Disenchant"))
        val now2 = SystemClock.elapsedRealtime()
        assertTrue(cast.castShowing(now2))
        val c2 = Canvas(bm)
        assertEquals("a cast runs: every frame", 0L, cast.drawCastInto(c2, RectF(0f, 5f, 600f, 35f), now2))
        assertTrue("the bar is painted inside the box", (0 until 600 step 7).any { x -> Color.alpha(bm.getPixel(x, 20)) > 0 })
        assertTrue("nothing above the box", (0 until 600 step 7).all { x -> bm.getPixel(x, 1) == 0 })
        assertEquals("an empty box: nothing", Long.MAX_VALUE, cast.drawCastInto(c2, RectF(0f, 0f, 0f, 0f), now2))
        // the press prediction: a cast time from another room's press starts the pending bar at once (and only without a cast)
        assertFalse("a cast already runs: no prediction", cast.predictFrom(3000, now2))
        assertTrue("idle: predicted", idle.predictFrom(3000, now))
        assertTrue(idle.castShowing(now))
        assertFalse("an instant spell never predicts", castView(null).predictFrom(0, now))
    }

    // ---------------------------------------------------------------- the pictures

    @Test fun shots() {
        if (out == null) return
        art()
        Tr.init(app)
        // the grid at rest, the cursor on a sword whose changes are all gains (its green arrow; its tooltip beside it)
        shot("bulk_cursor.png", doc(1, JSONObject()))
        // the cursor further in (the tooltip goes left of a right-hand tile), a locked item greyed in the list
        shot("bulk_cursor_right.png", doc(10, JSONObject()))
        // a disenchant casting: Home's own bar in the foot (a real CastView fed hudS.cs), the tile's dark sweep, B = Stop
        val cv = castView(JSONObject().put("k", "c").put("s", G - 1.2).put("e", G + 1.8).put("nm", "Disenchant"))
        shot("bulk_casting.png", doc(1, JSONObject().put("busy", true).put("stop", true).put("cast", JSONObject().put("b", 0).put("s", 2).put("id", 5001))), cv, 3)
        // the blue ask: "Disenchant?" A Yes / B No
        shot("bulk_ask.png", doc(2, JSONObject().put("ask", JSONObject().put("b", 0).put("s", 3).put("id", 5002).put("q", 3))))
        // Auto Loot: the strip of what came, at the top right
        shot("bulk_got.png", doc(3, JSONObject().put("got", JSONObject().put("n", 1).put("auto", true).put("items", JSONArray()
            .put(JSONObject().put("i", 132871).put("c", 3).put("q", 1)).put(JSONObject().put("i", 132874).put("c", 1).put("q", 2))))), frames = 2)
        // in a fight: the tiles dimmed, A greyed
        shot("bulk_fight.png", doc(4, JSONObject().put("fight", true)))
        // nothing to disenchant
        val empty = doc(1, JSONObject())
        empty.getJSONObject("ctl").getJSONObject("bulk").put("list", JSONArray()).put("sel", 0)
        empty.getJSONObject("ctl").remove("tip")
        shot("bulk_empty.png", empty)
        // the Bags room's top strip: the Disenchant icon after Clean Up (and the view toggle); then the controller's cursor on it (A Open;
        // off-device the strip's cursor box lands one bar too high - the harness's getLocationOnScreen, Clean Up's the same)
        val strip = doc(1, JSONObject())
        strip.getJSONObject("ctl").put("mode", "grid").remove("bulk")
        strip.getJSONObject("ctl").remove("tip")
        strip.getJSONObject("ctl").put("cur", JSONObject().put("bag", 0).put("slot", 1))
        shot("strip_icon.png", strip)
        strip.getJSONObject("ctl").put("bar", JSONObject().put("k", "de"))
        shot("strip_icon_cursor.png", strip)
    }
}
