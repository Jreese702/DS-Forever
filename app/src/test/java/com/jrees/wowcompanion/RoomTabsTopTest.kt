package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * THE ROOMS' TABS ON TOP (the user, 2026-11-25, on 1.0.41: "On all of the rooms that have the tabs on the right side we need to move the
 * test to the top and make them so that L and R shift the tabs left and right"): Character, Group Finder, Professions and Social, each
 * drawn off-device at 1240 x (1080 - the bar) with sample documents and driven by its own entry points (the relayed press, a finger, the
 * app's L / R):
 *  - the tabs stand in ONE ROW ALONG THE TOP: a finger walking the band under the header meets every tab, left to right; the old right
 *    column opens none;
 *  - the D-pad walks that row: Right / Left from a tab land on its neighbour and open it (no A, the 2026-10-07 rule), Up on the row does
 *    nothing, Down leaves it for the page;
 *  - L / R ([shoulder]) step the tabs, no wrap at either end (the Controller room's LB / RB, PadTabCtl p9 / p13), from the row the cursor
 *    moves with them.
 * Uses only what the base already had (reflection for the rooms' private state; [shoulder] by name), so the same file shows the BEFORE
 * failing. Pictures (before / after, the same states) only when ROOMTABS_SHOTS_DIR is set; ROOMTABS_ART_ROOT = an art root laid out
 * like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RoomTabsTopTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("ROOMTABS_SHOTS_DIR")?.let { File(it) }
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var n = 100

    @Before fun art() {
        System.getenv("ROOMTABS_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
        }
        Tr.init(app); Settings.init(app)
    }

    // ---------------------------------------------------------------- drawing, touching, reading

    private fun frame(v: View) {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, W, H)
            v.draw(Canvas(b))
            ShadowSystemClock.advanceBy(Duration.ofMillis(300L))
            ShadowLooper.idleMainLooper()
        }
    }

    private fun shot(name: String, v: View) {
        val dir = out ?: return
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..2) {
            v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, W, H)
            val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
            ShadowSystemClock.advanceBy(Duration.ofMillis(400L))
            ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
        }
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        quick(v)
    }

    /** one draw (the next touch is judged against it), the gaps' easing let finish */
    private fun quick(v: View) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(300L))
        ShadowLooper.idleMainLooper()
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        v.draw(Canvas(scratch))
    }
    private val scratch: Bitmap by lazy { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }

    private fun field(o: Any, name: String): Any? {
        var k: Class<*>? = o.javaClass
        while (k != null) {
            runCatching { return k!!.getDeclaredField(name).also { it.isAccessible = true }.get(o) }
            k = k.superclass
        }
        throw AssertionError("no field $name on ${o.javaClass.simpleName}")
    }

    /** the room's own L / R entry (the app's reader); none = the BEFORE */
    private fun shoulder(v: View, right: Boolean) {
        val m = v.javaClass.methods.firstOrNull { it.name == "shoulder" && it.parameterCount == 1 }
            ?: throw AssertionError("${v.javaClass.simpleName} has no L / R entry (shoulder)")
        m.invoke(v, right)
        frame(v)
    }

    /** one room under test: how to press its D-pad, which tab is open, where its cursor is */
    private inner class Room(val name: String, val v: View, val tabs: Int, val press: (String) -> Unit, val tab: () -> Int,
                             val cursorTab: () -> Int?)

    private fun relayed(type: String, k: String) = JSONObject().put("type", type).put("k", k).put("n", ++n)

    /** Fk.Tab(i=2) / Tab(id=186) -> its index among [ids] */
    private fun tabOf(f: Any?, ids: List<Int> = emptyList()): Int? {
        val s = f?.toString() ?: return null
        if (!s.startsWith("Tab(")) return null
        val v = s.substringAfter("=").substringBefore(")").toInt()
        return if (ids.isEmpty()) v else ids.indexOf(v).takeIf { it >= 0 }
    }

    // ---------------------------------------------------------------- the four rooms with sample documents

    private fun socialDoc(k: String, s: JSONObject) = JSONObject().put("type", "social").put("k", k).put("s", s)
    private fun friend(nm: String, lvl: Int, cls: String, area: String, slot: Int, on: Boolean = true) =
        JSONObject().put("n", nm).put("on", on).put("lvl", lvl).put("cls", cls).put("area", area).put("slot", slot)

    private fun social(): Room {
        val v = SocialView(app)
        v.onDoc(socialDoc("me", JSONObject().put("view", "friends").put("held", true).put("rows", true).put("name", "Tharn")))
        v.onDoc(socialDoc("friends", JSONObject().put("list", JSONArray()
            .put(friend("Brannock", 24, "WARRIOR", "Westfall", 1)).put(friend("Mirelle", 22, "PRIEST", "Redridge Mountains", 2))
            .put(friend("Korvash", 19, "SHAMAN", "The Barrens", 3)).put(friend("Sennah", 31, "MAGE", "", 4, on = false)))
            .put("bn", JSONArray())))
        v.onDoc(socialDoc("guild", JSONObject().put("list", JSONArray()
            .put(JSONObject().put("n", "Tharn").put("on", true).put("lvl", 24).put("cls", "HUNTER").put("zone", "Westfall").put("rank", 2))
            .put(JSONObject().put("n", "Oswin").put("on", true).put("lvl", 40).put("cls", "PALADIN").put("zone", "Stormwind City").put("rank", 0)))))
        v.onShown()
        frame(v)
        val pages = listOf("FRIENDS", "GUILD", "RAID")
        return Room("social", v, 3, { k -> v.onPad(relayed("socialpad", k)); frame(v) },
            { pages.indexOf(field(v, "page").toString()) }, { tabOf(field(v, "focus")) })
    }

    /** [cats5] = five listing categories (each with an activity); [draft] = Create Listing on Dungeons' activities (eight of them) */
    private fun groupFinder(cats5: Boolean = false, draft: Boolean = false): Room {
        val v = GroupFinderView(app)
        val dungeons = JSONArray()
        val names = listOf("The Deadmines", "Wailing Caverns", "Shadowfang Keep", "The Stockade", "Blackfathom Deeps", "Gnomeregan",
            "Razorfen Kraul", "Scarlet Monastery")
        for ((i, n) in names.withIndex()) dungeons.put(JSONObject().put("id", 10 + i).put("n", n).put("min", 15 + 3 * i).put("max", 5))
        val cats = JSONArray().put(JSONObject().put("id", 2).put("n", "Dungeons").put("slot", 1).put("acts", dungeons))
        if (cats5) for ((k, id) in listOf(114, 116, 118, 6).withIndex())
            cats.put(JSONObject().put("id", id).put("n", listOf("Raids", "Questing", "PvP", "Custom")[k]).put("slot", 2 + k)
                .put("acts", JSONArray().put(JSONObject().put("id", 40 + k).put("n", "Activity $k"))))
        else cats.put(JSONObject().put("id", 6).put("n", "Custom").put("slot", 2).put("acts", JSONArray()))
        v.onDoc(JSONObject().put("type", "gfcat").put("c", JSONObject().put("v", "gf1").put("lvl", 24).put("cats", cats)))
        val keys = JSONObject().put("c2", 1).put("c114", 2).put("c116", 3).put("c118", 4).put("c120", 5).put("rt", 6).put("rh", 7).put("rd", 8)
            .put("ln", 9).put("lp", 10)
        v.onDoc(JSONObject().put("type", "gf").put("g", JSONObject().put("v", "gf1").put("on", true).put("can", true)
            .put("ctl", JSONObject().put("held", true).put("cap", true).put("lr", true))
            .also { g -> if (draft) g.put("draft", JSONObject().put("cat", 2).put("acts", JSONArray().put(10)).put("keys", keys)
                .put("ticks", JSONArray().apply { for (i in names.indices) put(JSONObject().put("id", 10 + i).put("slot", 11 + i)) })) }
            .also { g -> if (cats5) g.put("draft", JSONObject().put("keys", keys)) }
            .put("search", JSONObject().put("st", "done").put("cat", 2).put("total", 2).put("res", JSONArray()
                .put(JSONObject().put("id", 1).put("leader", "Brannock").put("n", 3).put("acts", JSONArray().put(10)).put("lvl", 24)
                    .put("cls", "WARRIOR").put("cn", "Warrior").put("age", 300))
                .put(JSONObject().put("id", 2).put("leader", "Mirelle").put("n", 1).put("acts", JSONArray().put(11)).put("lvl", 22)
                    .put("cls", "PRIEST").put("cn", "Priest").put("age", 60))))))
        v.onShown()
        frame(v)
        return Room("groupfinder", v, 3, { k -> v.onPad(relayed("gfpad", k)); frame(v) },
            { field(v, "tab") as Int }, { tabOf(field(v, "focus")) })
    }

    private val profIds = listOf(0, 186, 185)

    /** [cookingTwo] = Cooking's small card with its two buttons (Cooking, Basic Campfire) */
    private fun professions(cookingTwo: Boolean = false): Room {
        val herb = JSONObject().put("pos", 1).put("id", 182).put("name", "Herbalism").put("icon", 136246).put("rank", 112).put("max", 150)
            .put("main", true).put("btn", JSONArray().put(JSONObject().put("n", "Find Herbs").put("icon", 133939)))
        val mining = JSONObject().put("pos", 2).put("id", 186).put("name", "Mining").put("icon", 136248).put("rank", 98).put("max", 150)
            .put("main", true).put("craft", true).put("oslot", 4)
            .put("btn", JSONArray().put(JSONObject().put("n", "Smelting").put("icon", 135811).put("rec", true).put("o", true)))
        val cooking = JSONObject().put("pos", 5).put("id", 185).put("name", "Cooking").put("icon", 133971).put("rank", 40).put("max", 75)
            .put("craft", true)
        if (cookingTwo) cooking.put("btn", JSONArray().put(JSONObject().put("n", "Cooking").put("icon", 133971).put("rec", true).put("o", true))
            .put(JSONObject().put("n", "Basic Campfire").put("icon", 135805)))
        val d = JSONObject().put("type", "prof").put("v", "p28").put("held", true).put("mode", "book")
            .put("list", JSONArray().put(herb).put(mining).put(cooking))
            .put("pad", JSONObject().put("rows", JSONObject().put("prof", 1).put("profsel", 2).put("profopen", 3)))
        ProfessionsStore.onProf(d, System.currentTimeMillis())
        val v = ProfessionsView(app)
        frame(v)
        v.refresh()
        frame(v)
        return Room("professions", v, 3, { k -> v.onPad(relayed("profpad", k)); frame(v) },
            { profIds.indexOf(field(v, "viewed") as Int) }, { tabOf(field(v, "focus"), profIds) })
    }

    private fun character(): Room {
        val v = CharacterView(app)
        v.update(JSONObject().put("type", "character").put("combat", false).put("c", JSONObject()
            .put("who", JSONObject().put("name", "Tharn").put("lvl", 24).put("line", "Level 24 Dwarf Hunter").put("cls", "Hunter")
                .put("clsFile", "HUNTER").put("race", "Dwarf").put("faction", "Alliance"))
            .put("st", JSONArray().put(JSONObject().put("h", "Attributes").put("r", JSONArray()
                .put(JSONObject().put("k", "str").put("l", "Strength").put("v", "41"))
                .put(JSONObject().put("k", "agi").put("l", "Agility").put("v", "77"))
                .put(JSONObject().put("k", "sta").put("l", "Stamina").put("v", "58")))))
            .put("win", JSONObject().put("open", false).put("ctl", true))))
        frame(v)
        val press = CharacterView::class.java.getDeclaredMethod("press", String::class.java).also { it.isAccessible = true }
        return Room("character", v, CharTab.values().size, { k -> press.invoke(v, k); frame(v) },
            { v.model.tab.ordinal }, {
                // the cursor: its key, or where it came to rest on the last frame (its start place while the key is unset)
                val k = (field(v, "focusKey") as String?) ?: runCatching { field(v, "restKey") as String? }.getOrNull()
                k?.takeIf { it.startsWith("tab:") }?.removePrefix("tab:")?.toInt()
            })
    }

    private fun rooms(): List<() -> Room> = listOf({ social() }, { groupFinder() }, { professions() }, { character() })

    // ---------------------------------------------------------------- the tabs stand on top

    /** a finger walking the band under the header (y 66 .. 160) meets every tab, left to right */
    @Test fun tabsStandInOneRowAlongTheTop() {
        for (make in rooms()) {
            val r = make()
            val seen = ArrayList<Int>()
            var x = 8f
            while (x < W) {
                tap(r.v, x, 112f)
                val t = r.tab()
                if (seen.lastOrNull() != t) seen += t
                x += 20f
            }
            // the walk starts on the open tab 0 (a touch on a gap or the row's ends opens nothing)
            assertEquals("${r.name}: the tabs along the top, left to right", (0 until r.tabs).toList(), seen.distinct())
            assertEquals("${r.name}: met in order", seen.distinct(), seen)
        }
    }

    /** the old side column (x 1122 .. 1240 under the header) opens no tab any more */
    @Test fun theRightColumnHoldsNoTabs() {
        for (make in rooms()) {
            val r = make()
            for (y in listOf(140f, 270f, 400f, 530f, 660f, 790f)) {
                tap(r.v, W - 58f, y)
                assertEquals("${r.name}: a touch at the old side tabs (y $y) opens nothing", 0, r.tab())
            }
        }
    }

    // ---------------------------------------------------------------- the D-pad on the row

    @Test fun dpadWalksTheRowAndLandingOpens() {
        for (make in rooms()) {
            val r = make()
            assertEquals("${r.name}: the room opens with the cursor on its open tab", 0, r.cursorTab())
            r.press("PADDRIGHT")
            assertEquals("${r.name}: Right from tab 1 lands on tab 2 and opens it", 1, r.tab())
            assertEquals("${r.name}: the cursor stays on the tab it landed on", 1, r.cursorTab())
            r.press("PADDUP")
            assertEquals("${r.name}: Up on the row changes no tab", 1, r.tab())
            assertEquals("${r.name}: Up on the row keeps the cursor there", 1, r.cursorTab())
            r.press("PADDLEFT")
            assertEquals("${r.name}: Left lands back on tab 1 and opens it", 0, r.tab())
            r.press("PADDLEFT")
            assertEquals("${r.name}: no wrap past the first tab", 0, r.tab())
            r.press("PADDDOWN")
            assertEquals("${r.name}: Down leaves the row for the page, the tab stays", 0, r.tab())
            assertEquals("${r.name}: the cursor is off the row", null, r.cursorTab())
        }
    }

    // ---------------------------------------------------------------- L / R

    @Test fun shouldersStepTheTabsWithoutWrapping() {
        for (make in rooms()) {
            val r = make()
            shoulder(r.v, false)
            assertEquals("${r.name}: L on the first tab: nothing (no wrap)", 0, r.tab())
            for (i in 1 until r.tabs) {
                shoulder(r.v, true)
                assertEquals("${r.name}: R opens the next tab", i, r.tab())
                assertEquals("${r.name}: from the row the cursor moves with it", i, r.cursorTab())
            }
            shoulder(r.v, true)
            assertEquals("${r.name}: R on the last tab: nothing (no wrap)", r.tabs - 1, r.tab())
            shoulder(r.v, false)
            assertEquals("${r.name}: L opens the tab before", r.tabs - 2, r.tab())
        }
    }

    /** from the page (the cursor off the row) L / R open the tab and the cursor goes to that page's own start, never left behind */
    @Test fun shouldersFromThePageKeepTheCursorOnScreen() {
        for (make in rooms()) {
            val r = make()
            r.press("PADDDOWN")
            assertEquals("${r.name}: the cursor is in the page", null, r.cursorTab())
            shoulder(r.v, true)
            assertEquals("${r.name}: R from the page opens tab 2", 1, r.tab())
            // into the new page (down, and right into its right-hand pane where there is one), then Up all the way: the row is reached
            // on the OPEN tab only (the centred row's nearest tab would open another page by landing)
            r.press("PADDDOWN")
            if (r.cursorTab() == null) { r.press("PADDRIGHT"); r.press("PADDRIGHT") }
            repeat(8) { r.press("PADDUP") }
            assertEquals("${r.name}: Up from the page never opens another tab", 1, r.tab())
            assertEquals("${r.name}: Up from the page reaches the row on the open tab", 1, r.cursorTab())
        }
    }

    /** the second tab open, the cursor in its page (the Group Browser's Category / Filter, Social's Guild list, Character's
     *  Reputation pane), Up never lands on another tab */
    @Test fun upFromTheSecondPageLandsOnItsOwnTab() {
        for (make in rooms()) {
            val r = make()
            r.press("PADDRIGHT")
            assertEquals("${r.name}: tab 2 open", 1, r.tab())
            r.press("PADDDOWN")
            for (k in listOf("PADDRIGHT", "PADDUP", "PADDUP", "PADDLEFT", "PADDUP", "PADDUP", "PADDUP"))
                if (r.cursorTab() == null) r.press(k)
            assertEquals("${r.name}: still tab 2", 1, r.tab())
            assertEquals("${r.name}: the cursor on the row is on tab 2", 1, r.cursorTab())
        }
    }

    // ---------------------------------------------------------------- the pictures (the same states before and after)

    @Test fun shots() {
        if (out == null) return
        social().let { r -> shot("social_1_friends_cursor_on_tab", r.v)
            (r.v as SocialView).openPage(SocialModel.Tab.GUILD); frame(r.v); shot("social_2_guild", r.v) }
        groupFinder().let { r -> shot("groupfinder_1_listing_cursor_on_tab", r.v)
            GroupFinderView::class.java.getDeclaredMethod("showTab", Int::class.javaPrimitiveType).also { it.isAccessible = true }.invoke(r.v, 1)
            frame(r.v); shot("groupfinder_2_browse", r.v)
            GroupFinderView::class.java.getDeclaredMethod("showTab", Int::class.javaPrimitiveType).also { it.isAccessible = true }.invoke(r.v, 2)
            frame(r.v); shot("groupfinder_3_who", r.v) }
        groupFinder(cats5 = true).let { r -> shot("groupfinder_4_listing_five_categories", r.v) }
        groupFinder(draft = true).let { r -> shot("groupfinder_5_listing_activities", r.v) }
        professions().let { r -> shot("professions_1_book_cursor_on_tab", r.v)
            (r.v as ProfessionsView).showProfession(185); frame(r.v); shot("professions_2_cooking", r.v) }
        professions(cookingTwo = true).let { r -> shot("professions_3_book_cooking_two_buttons", r.v) }
        character().let { r -> shot("character_1_character_cursor_on_tab", r.v)
            (r.v as CharacterView).model.tab = CharTab.REPUTATION; r.v.invalidate(); frame(r.v); shot("character_2_reputation", r.v)
            r.v.model.tab = CharTab.STATISTICS; r.v.invalidate(); frame(r.v); shot("character_3_statistics", r.v) }
    }
}
