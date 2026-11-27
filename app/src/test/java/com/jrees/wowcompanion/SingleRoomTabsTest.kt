package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * THE 1533 MERGE, THE ROOMS' TABS ON TOP x SINGLE SCREEN MODE (the user, 2026-11-25: "On all of the rooms that have the tabs on the
 * right side we need to move the test to the top and make them so that L and R shift the tabs left and right"; single screen mode's pick
 * "Centred panel": every room the bottom screen's 1240 x 1080 page 1:1 in the middle of the 1920 x 1080 top screen). Each tabbed room -
 * Character, Group Finder, Professions, Social, the Spellbook, Talents, the Macros grid - laid in the box the single-mode host lays
 * (SingleScreen.boxMargins on the rooms column, its room bar under it) inside a 1920 x 1080 window:
 *  - the top tab row fits the panel: a finger walking the band under the header at (340 + x, y) meets every tab, left to right, and the
 *    dim's side strip beside the panel opens none;
 *  - L / R step the tabs and stop at both ends (no wrap), as on two screens;
 *  - the Spellbook's and Talents' rows keep the L / R glyphs' room at their ends inside the panel (drawn there with an art root);
 *  - the Bank (an NPC window, not a tabbed room) is left as it is: no tab row of the rooms' kind.
 * Pictures over the game's frame when SINGLE_TABS_SHOTS_DIR is set (SINGLE_TOP_BG = the blurred game frame, else a plain dark one;
 * SINGLE_ART_ROOT / HOME_LAYOUT_ART = an art root laid out like the app's: ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleRoomTabsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("SINGLE_TABS_SHOTS_DIR")?.let { File(it) }
    private val art: String? = System.getenv("SINGLE_ART_ROOT") ?: System.getenv("HOME_LAYOUT_ART")
    private val TW = 1920
    private val TH = 1080
    private val BW = 1240
    private val BX = 340
    private var n = 100

    @Before fun up() {
        art?.let { root -> ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        Tr.init(app); Settings.init(app); SettingsTabs.init(app); GameUi.init(app); HomeSettings.init(app); CastSettings.init(app)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }

    // ---------------------------------------------------------------- the panel

    private class Panel(val content: FrameLayout, val column: LinearLayout, val room: View)

    private fun lay(v: View, l: Int, t: Int, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(l, t, l + w, t + h)
    }

    /** the host's content as single screen mode lays it: the rooms column (the room over its bar) in the centred box */
    private fun panel(t: Tabbed): Panel = panel(t.v, t.r).also { p -> t.prep(); frame(p) }

    private fun panel(room: View, r: Room): Panel {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val content = FrameLayout(act)
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; ThemeLive.bg(this, Tok.INK) }
        (room.parent as? ViewGroup)?.removeView(room)
        column.addView(room, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val strip = Strip(act) { }
        strip.active = r
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Strip.HEIGHT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val m = SingleScreen.boxMargins(intArrayOf(0, 0, 0, 0), SingleScreen.panelBox(TW, TH), TW, TH)
        lp.setMargins(m[0], m[1], m[2], m[3])
        content.addView(column, lp)
        act.setContentView(content)
        val p = Panel(content, column, room)
        frame(p)
        return p
    }

    private val scratch: Bitmap by lazy { Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888) }

    /** laid and drawn (a room's touch is judged against its last frame), its easing let finish */
    private fun frame(p: Panel) {
        for (pass in 0..1) {
            lay(p.content, 0, 0, TW, TH)
            p.content.draw(Canvas(scratch))
            ShadowSystemClock.advanceBy(Duration.ofMillis(300L))
            ShadowLooper.idleMainLooper()
        }
    }

    /** a finger on the TOP screen at ([x], [y]) */
    private fun tap(p: Panel, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        p.content.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        p.content.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        frame(p)
    }

    private fun field(o: Any, name: String): Any? {
        var k: Class<*>? = o.javaClass
        while (k != null) {
            runCatching { return k!!.getDeclaredField(name).also { it.isAccessible = true }.get(o) }
            k = k.superclass
        }
        throw AssertionError("no field $name on ${o.javaClass.simpleName}")
    }

    private fun backdrop(): Bitmap {
        val b = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(46, 52, 58))
        System.getenv("SINGLE_TOP_BG")?.let { BitmapFactory.decodeFile(it) }?.let { g ->
            c.drawBitmap(g, null, RectF(0f, 0f, TW.toFloat(), TH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return b
    }

    /** the panel over the dimmed game (SingleLook's room look: FULL, DIM_PANEL) */
    private fun picture(p: Panel): Bitmap {
        for (i in 0 until 3) { frame(p); Thread.sleep(120); ShadowLooper.idleMainLooper() }
        val pic = backdrop()
        val c = Canvas(pic)
        c.drawColor(Color.argb((255 * SingleLook.DIM_PANEL).toInt(), 0, 0, 0))
        lay(p.content, 0, 0, TW, TH)
        p.content.draw(c)
        return pic
    }

    private fun shot(p: Panel, name: String) {
        val d = out ?: return
        d.mkdirs()
        val b = picture(p)
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- the rooms with sample documents (RoomTabsTopTest's)

    private inner class Tabbed(val name: String, val r: Room, val v: View, val tabs: Int, val tab: () -> Int, val step: (Boolean) -> Unit,
                               val prep: () -> Unit = { })

    private fun relayed(type: String, k: String) = JSONObject().put("type", type).put("k", k).put("n", ++n)

    private fun socialDoc(k: String, s: JSONObject) = JSONObject().put("type", "social").put("k", k).put("s", s)
    private fun friend(nm: String, lvl: Int, cls: String, area: String, slot: Int, on: Boolean = true) =
        JSONObject().put("n", nm).put("on", on).put("lvl", lvl).put("cls", cls).put("area", area).put("slot", slot)

    private fun social(): Tabbed {
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
        val pages = listOf("FRIENDS", "GUILD", "RAID")
        return Tabbed("social", Room.SOCIAL, v, 3, { pages.indexOf(field(v, "page").toString()) }, { v.shoulder(it) })
    }

    private fun groupFinder(): Tabbed {
        val v = GroupFinderView(app)
        val dungeons = JSONArray()
        for ((i, nm) in listOf("The Deadmines", "Wailing Caverns", "Shadowfang Keep", "The Stockade").withIndex())
            dungeons.put(JSONObject().put("id", 10 + i).put("n", nm).put("min", 15 + 3 * i).put("max", 5))
        val cats = JSONArray().put(JSONObject().put("id", 2).put("n", "Dungeons").put("slot", 1).put("acts", dungeons))
            .put(JSONObject().put("id", 6).put("n", "Custom").put("slot", 2).put("acts", JSONArray()))
        v.onDoc(JSONObject().put("type", "gfcat").put("c", JSONObject().put("v", "gf1").put("lvl", 24).put("cats", cats)))
        v.onDoc(JSONObject().put("type", "gf").put("g", JSONObject().put("v", "gf1").put("on", true).put("can", true)
            .put("ctl", JSONObject().put("held", true).put("cap", true).put("lr", true))
            .put("search", JSONObject().put("st", "done").put("cat", 2).put("total", 2).put("res", JSONArray()
                .put(JSONObject().put("id", 1).put("leader", "Brannock").put("n", 3).put("acts", JSONArray().put(10)).put("lvl", 24)
                    .put("cls", "WARRIOR").put("cn", "Warrior").put("age", 300))
                .put(JSONObject().put("id", 2).put("leader", "Mirelle").put("n", 1).put("acts", JSONArray().put(11)).put("lvl", 22)
                    .put("cls", "PRIEST").put("cn", "Priest").put("age", 60))))))
        v.onShown()
        return Tabbed("groupfinder", Room.GROUPFINDER, v, 3, { field(v, "tab") as Int }, { v.shoulder(it) })
    }

    private val profIds = listOf(0, 186, 185)

    private fun professions(): Tabbed {
        val herb = JSONObject().put("pos", 1).put("id", 182).put("name", "Herbalism").put("icon", 136246).put("rank", 112).put("max", 150)
            .put("main", true).put("btn", JSONArray().put(JSONObject().put("n", "Find Herbs").put("icon", 133939)))
        val mining = JSONObject().put("pos", 2).put("id", 186).put("name", "Mining").put("icon", 136248).put("rank", 98).put("max", 150)
            .put("main", true).put("craft", true).put("oslot", 4)
            .put("btn", JSONArray().put(JSONObject().put("n", "Smelting").put("icon", 135811).put("rec", true).put("o", true)))
        val cooking = JSONObject().put("pos", 5).put("id", 185).put("name", "Cooking").put("icon", 133971).put("rank", 40).put("max", 75)
            .put("craft", true)
        ProfessionsStore.onProf(JSONObject().put("type", "prof").put("v", "p28").put("held", true).put("mode", "book")
            .put("list", JSONArray().put(herb).put(mining).put(cooking))
            .put("pad", JSONObject().put("rows", JSONObject().put("prof", 1).put("profsel", 2).put("profopen", 3))), System.currentTimeMillis())
        val v = ProfessionsView(app)
        return Tabbed("professions", Room.PROFESSIONS, v, 3, { profIds.indexOf(field(v, "viewed") as Int) }, { v.shoulder(it) },
            prep = { v.refresh() })
    }

    private fun character(): Tabbed {
        val v = CharacterView(app)
        v.update(JSONObject().put("type", "character").put("combat", false).put("c", JSONObject()
            .put("who", JSONObject().put("name", "Tharn").put("lvl", 24).put("line", "Level 24 Dwarf Hunter").put("cls", "Hunter")
                .put("clsFile", "HUNTER").put("race", "Dwarf").put("faction", "Alliance"))
            .put("st", JSONArray().put(JSONObject().put("h", "Attributes").put("r", JSONArray()
                .put(JSONObject().put("k", "str").put("l", "Strength").put("v", "41"))
                .put(JSONObject().put("k", "agi").put("l", "Agility").put("v", "77"))
                .put(JSONObject().put("k", "sta").put("l", "Stamina").put("v", "58")))))
            .put("win", JSONObject().put("open", false).put("ctl", true))))
        return Tabbed("character", Room.CHARACTER, v, CharTab.values().size, { v.model.tab.ordinal }, { v.shoulder(it) })
    }

    private fun book(): SpellbookView {
        val v = SpellbookView(app, onBind = { })
        v.update(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!)
        return v
    }

    private val talentTaps = ArrayList<Pair<String, Int>>()

    private fun talents(): TalentsView {
        val v = TalentsView(app) { row, col -> talentTaps += row to col }
        val groups = JSONArray()
        for ((i, s) in listOf(Triple("Beast Mastery", 461112, 5), Triple("Marksmanship", 236179, 0), Triple("Survival", 461113, 0)).withIndex())
            groups.put(JSONObject().put("id", 361 + i).put("n", s.first).put("i", s.second).put("spent", s.third))
        v.update(TalentsModel.parse(JSONObject().put("type", "talents").put("v", "t9").put("t", JSONObject().put("lvl", 24).put("use", true)
            .put("g", 1).put("ng", 1).put("pool", JSONObject().put("have", 10).put("max", 15).put("spent", 5)).put("groups", groups)
            .put("nodes", JSONArray())))!!)
        return v
    }

    private val macroKeys = """{"a": 3, "b": 5, "f": 1, "fb": 2, "h": 0, "kx": 10, "n": 24, "pn": 9, "pp": 8, "s0": 12, "tc": 7, "tg": 6, "wx": 11, "x": 4}"""
    private val macroGrid = """{"m": {"cells": [{"i": 134400, "n": "Bandage"}, {"i": 134400, "n": "Bolt"}, {"i": 134400, "n": "Focus Heal"},
        {"plus": true}], "cnt": {"c": {"max": 18, "n": 1}, "g": {"max": 120, "n": 3}}, "ctl": {"cap": true, "held": true, "want": true},
        "cur": 2, "keys": $macroKeys, "mac": {"i": 134400, "len": 56, "lines": ["#showtooltip", "/cast [@focus,exists,nodead][] Healing Wave"],
        "n": "Focus Heal"}, "me": "Korvash", "mode": "grid", "n": 7, "page": 1, "pages": 1, "ready": true, "tab": "g", "v": "s10",
        "view": "grid"}, "type": "macros", "v": "s10"}"""

    private fun macros(tab: String = "g"): MacrosView {
        val v = MacrosView(app).apply { dryRun = true }
        v.onDoc(JSONObject(macroGrid.replace("\"tab\": \"g\"", "\"tab\": \"$tab\"")), live = false)
        return v
    }

    private fun tabRect(v: View, i: Int): RectF =
        v.javaClass.getDeclaredMethod("tabRect", Int::class.javaPrimitiveType).also { it.isAccessible = true }.invoke(v, i) as RectF

    // ---------------------------------------------------------------- the four rooms whose tabs moved on top

    private fun moved(): List<() -> Tabbed> = listOf({ character() }, { groupFinder() }, { professions() }, { social() })

    @Test fun theTopRowFitsThePanelAndAFingerMeetsEveryTab() {
        for (make in moved()) {
            val t = make()
            val p = panel(t)
            assertEquals("${t.name}: the box's left", BX, p.column.left)
            assertEquals("${t.name}: the room is the bottom screen's width", BW, t.v.width)
            assertEquals("${t.name}: the room's page height", TH - Strip.HEIGHT, t.v.height)
            // the dim's side strip beside the panel opens no tab
            tap(p, BX - 40f, 112f)
            assertEquals("${t.name}: a touch beside the panel opens nothing", 0, t.tab())
            val seen = ArrayList<Int>()
            var x = 8f
            while (x < BW) {
                tap(p, BX + x, 112f)
                val now = t.tab()
                if (seen.lastOrNull() != now) seen += now
                x += 20f
            }
            assertEquals("${t.name}: the tabs along the panel's top, left to right", (0 until t.tabs).toList(), seen.distinct())
            assertEquals("${t.name}: met in order", seen.distinct(), seen)
            // past the panel's right edge: the dim again, the last tab stays
            tap(p, BX + BW + 40f, 112f)
            assertEquals("${t.name}: a touch right of the panel changes nothing", t.tabs - 1, t.tab())
        }
    }

    @Test fun lAndRStepTheTabsAndStopAtTheEndsInThePanel() {
        for (make in moved()) {
            val t = make()
            val p = panel(t)
            t.step(false); frame(p)
            assertEquals("${t.name}: L on the first tab: nothing (no wrap)", 0, t.tab())
            for (i in 1 until t.tabs) {
                t.step(true); frame(p)
                assertEquals("${t.name}: R opens the next tab", i, t.tab())
            }
            t.step(true); frame(p)
            assertEquals("${t.name}: R on the last tab: nothing (no wrap)", t.tabs - 1, t.tab())
            t.step(false); frame(p)
            assertEquals("${t.name}: L opens the tab before", t.tabs - 2, t.tab())
        }
    }

    // ---------------------------------------------------------------- the Spellbook, Talents and the Macros grid

    @Test fun theSpellbookAndTalentsRowsKeepTheirGlyphsInThePanel() {
        val b = book()
        val pb = panel(b, Room.SPELLBOOK)
        val bookTabs = SpellsModel.tabs(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!).take(8)
        assertTrue("the sample book has tabs", bookTabs.size >= 2)
        val first = tabRect(b, 0); val last = tabRect(b, bookTabs.size - 1)
        assertTrue("the book: L's room left of its first tab, inside the panel", first.left - UiKit.TAB_END_W >= 16f - 0.5f)
        assertTrue("the book: R's room right of its last tab, inside the panel", tabRect(b, 7).right + UiKit.TAB_END_W <= BW - 16f + 0.5f)
        assertTrue(last.right <= BW)
        val t = talents()
        panel(t, Room.TALENTS)
        assertTrue("talents: L's room", tabRect(t, 0).left - UiKit.TAB_END_W >= 12f - 0.5f)
        assertTrue("talents: R's room", tabRect(t, 2).right + UiKit.TAB_END_W <= BW - 12f + 0.5f)
        // with an art root the glyphs are drawn there: the panel's pixels at the L glyph's place differ from the room's ground
        if (art != null && File(art, "ui/kit").isDirectory) {
            val pic = picture(pb)
            val cx = BX + (first.left - UiKit.TAB_END_W / 2f).toInt(); val cy = (first.centerY() + 4f).toInt()
            val ground = pic.getPixel(BX + 4, cy)
            var lit = 0
            for (y in cy - 16..cy + 16 step 2) for (x in cx - 16..cx + 16 step 2) if (pic.getPixel(x, y) != ground) lit++
            assertTrue("the book's L glyph drawn left of its first tab in the panel ($lit)", lit > 20)
        }
    }

    @Test fun theSpellbookTalentsAndMacrosStepAndStopInThePanel() {
        val b = book()
        val p = panel(b, Room.SPELLBOOK)
        val keys = SpellsModel.tabs(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!).take(8).map { it.key }
        fun key() = field(b, "tabKey") as String
        assertEquals("the book opens on its first tab", keys[0], key())
        assertTrue("the book's L / R are free (no Controller page, no hold)", b.shouldersFree())
        b.shoulder(false); frame(p)
        assertEquals("the book: L on the first tab: nothing", keys[0], key())
        for (i in 1 until keys.size) { b.shoulder(true); frame(p); assertEquals("the book: R opens tab ${i + 1}", keys[i], key()) }
        b.shoulder(true); frame(p)
        assertEquals("the book: R on the last tab: nothing", keys.last(), key())
        val t = talents()
        val pt = panel(t, Room.TALENTS)
        fun school() = TalentsView::class.java.getDeclaredField("school").also { it.isAccessible = true }.getInt(t)
        t.shoulder(false); frame(pt)
        assertEquals("talents: L on the first school: nothing", 1, school()); assertTrue(talentTaps.isEmpty())
        t.shoulder(true); t.shoulder(true); frame(pt)
        assertEquals(3, school())
        t.shoulder(true); frame(pt)
        assertEquals("talents: R on the last school: nothing", 3, school()); assertEquals(2, talentTaps.size)
        // the Macros grid: L on General and R on the character's tab step nowhere (RoomTabRow's ends); its L / R free at the grid
        val m = macros("g")
        panel(m, Room.MACROS)
        assertTrue("the grid's L / R free", m.shouldersFree())
        assertEquals(null, RoomTabRow.step(0, 2, -1)); assertEquals(1, RoomTabRow.step(0, 2, 1))
        assertEquals(null, RoomTabRow.step(1, 2, 1)); assertEquals(0, RoomTabRow.step(1, 2, -1))
        m.shoulder(false); m.shoulder(true)                         // dry run: nothing sent, nothing thrown
    }

    // ---------------------------------------------------------------- the Bank

    @Test fun theBankIsLeftAsItIs() {
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        val iv = File(src, "InteractView.kt").readText()
        assertFalse("the NPC windows (the Bank among them) have no tab row of the rooms' kind", iv.contains("RoomTabRow"))
        assertFalse("nor the rooms' L / R", iv.contains("fun shoulder("))
    }

    // ---------------------------------------------------------------- the pictures

    @Test fun shots() {
        if (out == null) return
        character().let { t -> val p = panel(t); shot(p, "rooms_single_01_character")
            t.step(true); frame(p); shot(p, "rooms_single_02_character_R_reputation") }
        groupFinder().let { t -> val p = panel(t); shot(p, "rooms_single_03_groupfinder")
            t.step(true); frame(p); shot(p, "rooms_single_04_groupfinder_R_browse") }
        professions().let { t -> val p = panel(t); shot(p, "rooms_single_05_professions")
            t.step(true); frame(p); shot(p, "rooms_single_06_professions_R_mining") }
        social().let { t -> val p = panel(t); shot(p, "rooms_single_07_social")
            t.step(true); frame(p); shot(p, "rooms_single_08_social_R_guild") }
        book().let { b -> val p = panel(b, Room.SPELLBOOK); shot(p, "rooms_single_09_spellbook_glyphs")
            b.shoulder(true); frame(p); shot(p, "rooms_single_10_spellbook_R") }
        talents().let { t -> val p = panel(t, Room.TALENTS); shot(p, "rooms_single_11_talents_glyphs")
            t.shoulder(true); frame(p); shot(p, "rooms_single_12_talents_R_marksmanship") }
        macros("g").let { m -> shot(panel(m, Room.MACROS), "rooms_single_13_macros_general") }
        macros("c").let { m -> shot(panel(m, Room.MACROS), "rooms_single_14_macros_character") }
    }
}
