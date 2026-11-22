package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SETTINGS > BOTTOM SCREEN AS PICTURE TILES, off-device (the beautify pass; the user, 2026-11-17: "I REALLY REALLY love what you did with
 * the top screen settings menu with the pictures and want to do something similar to the other menus"): the room built as the host
 * builds it, the Bottom screen tab's two groups of tiles (Home's 8, Map & chat's 3), no "What it does" panel, the controller's walk (A
 * opens the band and the cursor stays on the tile, Down into the choices, A presses one write, B closes the band with the cursor back on
 * the tile, then the tab row, then Home), touch the same.
 *
 * With BOTTOM_SHOTS_DIR set it also renders the tab (no game yet, the game's documents in, every band, the cursor, Spanish / French, no
 * kit) and a sheet of the 11 pictures; THEME_ART_ROOT = an art root laid out like the app's (ui/kit, ui/cast, fonts, icons.pak, seamless).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomTilesRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var n = 0
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var toolbarWant = true

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root
        for (n in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(n).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
    }

    private fun dpad() = PadMapModel.Dpad(toolbarWant, emptyMap(), emptyList(), emptyList(), toolbarWant, 1, 2, null, null)

    private fun room(kit: Boolean = true): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app); HomeSettings.init(app)
        GameUi.character = "Sample-Realm"
        if (kit) art() else art(null)
        val v = SettingsView(app) { }
        return SettingsRoom(app, v) { null }.also {
            it.homeSection.dpadState = { dpad() }
            it.homeSection.onDpad = { on -> toolbarWant = on }
            it.show(SettingsTabs.Tab.BOTTOM, force = true); lay(it)
        }
    }

    private val scratch by lazy { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(scratch))
        }
    }

    private fun press(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); lay(r) }

    private fun hold(r: SettingsRoom) {
        r.pad.enter(); r.show(SettingsTabs.Tab.BOTTOM, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); lay(r)
    }

    private fun parts(r: SettingsRoom) = r.bottomTiles

    private fun contentTop(r: SettingsRoom, v: View): Float? {
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        val content = sc.getChildAt(0)
        var y = 0f; var cur: View = v
        while (cur !== content) { if (cur.visibility != View.VISIBLE) return null; y += cur.top; cur = cur.parent as? View ?: return null }
        return y
    }

    /** every tile's box in the page's content coordinates, by id */
    private fun tileBoxes(r: SettingsRoom): Map<String, RectF> {
        val out = LinkedHashMap<String, RectF>()
        for (p in parts(r)) {
            val top = contentTop(r, p) ?: continue
            val ids = p.tileIds()
            for ((i, id) in ids.withIndex()) out[id] = RectF(p.focusTargets()[i]).apply { offset(0f, top) }
        }
        return out
    }

    private fun partOf(r: SettingsRoom, id: String) = parts(r).first { id in it.tileIds() }
    private fun openTile(r: SettingsRoom) = parts(r).mapNotNull { it.openTile }.singleOrNull()

    private fun tap(v: View, x: Float, y: Float) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle()
    }

    private fun openByTouch(r: SettingsRoom, id: String) {
        val p = partOf(r, id); val t = tileBoxes(r).getValue(id)
        tap(p, t.centerX(), t.centerY() - contentTop(r, p)!!); lay(r)
    }

    @After fun forget() {
        Theme.use("classic")
        GameUi.character = null
        Tr.setChoice(app, "auto")
        BottomPictures.drop()
        HomeSettings.castBarSide = CastStripModel.Side.OFF
    }

    // ---------------------------------------------------------------- the tab

    /** the Bottom screen tab: Home's 11 tiles (11-20: Spell rank badges; 1518: Reset Home) and Map & chat's 3, each group ONE tile part; the old Tap-to-cast and Chat groups gone; no
     *  "What it does" panel (the tiles are the pictures, as on Top screen) */
    @Test fun bottomTabIsTwoGroupsOfTiles() {
        val r = room()
        lay(r)                                                            // (pick 5: the tab does not fold, both groups open)
        assertEquals(setOf(SettingsTabs.Group.HOME, SettingsTabs.Group.MAP), r.open)
        val home = r.groupBoxes.single { it.group == SettingsTabs.Group.HOME }
        val map = r.groupBoxes.single { it.group == SettingsTabs.Group.MAP }
        assertEquals(BottomTiles.HOME_TILES, (home.parts.single() as BottomTilesView).tileIds())
        assertEquals(BottomTiles.MAP_TILES, (map.parts.single() as BottomTilesView).tileIds())
        for (g in listOf(SettingsTabs.Group.CAST_BAR, SettingsTabs.Group.CHAT))
            assertEquals("$g not shown", View.GONE, r.groupBoxes.single { it.group == g }.box.visibility)
        // five columns of Top's 232 x 184 tiles on the 1240 px page
        val b = tileBoxes(r)
        assertEquals(15, b.size)                                             // movable Home: Edit Home first; 1518: Reset Home last of Home's, the XP bar before it
        for (t in b.values) { assertEquals(GameUiTiles.TILE_W, t.width(), 0.5f); assertEquals(GameUiTiles.TILE_H, t.height(), 0.01f) }
        assertEquals(16f, b.getValue(BottomTiles.LAYOUT).left, 0.5f)
        assertEquals(W - 16f, b.getValue(BottomTiles.QUEST).right, 0.5f)
        assertEquals(b.getValue(BottomTiles.LAYOUT).left, b.getValue(BottomTiles.CLOCK).left, 0.01f)
        // the toolbar off: Hold to talk is not drawn (it cannot act), the Tap-to-cast tile takes its place
        toolbarWant = false; r.refreshGroups(); lay(r)
        assertEquals(BottomTiles.HOME_TILES - BottomTiles.TALK, (home.parts.single() as BottomTilesView).tileIds())
        toolbarWant = true
    }

    /** A opens the tile's band and the cursor stays on the tile; Down goes into its choices; A there is one write; B closes the band with
     *  the cursor back on the tile; B again: the tab row; B again: Home */
    @Test fun aOpensTheBandAndBStepsBack() {
        val r = room(); hold(r)
        var left = false
        r.pad.onLeave = { left = true }
        assertTrue(r.pad.onTabRow)
        press(r, "PADDDOWN")
        val boxes = tileBoxes(r)
        var tile = boxes.getValue(BottomTiles.LAYOUT)                   // movable Home (D4): Edit Home is the first tile
        // (pick 5: no heading stops - the first stop under the tab row is a tile)
        repeat(4) { if (r.pad.focusBox() != tile) press(r, "PADDLEFT") }
        assertEquals("the first tile: top-left", tile, r.pad.focusBox())
        press(r, "PADDRIGHT")
        assertEquals(boxes.getValue(BottomTiles.REST), r.pad.focusBox())
        val first0 = tile
        tile = boxes.getValue(BottomTiles.REST)                          // the Rest controller tile's band from here
        assertTrue(first0.left < tile.left)
        HomeSettings.rest = HomeRest.DIAMONDS
        press(r, "PAD1")
        assertEquals(BottomTiles.REST, openTile(r))
        assertEquals(tile, r.pad.focusBox())
        press(r, "PADDDOWN")
        val first = r.pad.focusBox()!!
        assertTrue("into the band", first.top > tile.bottom)
        // the band's first button is Thin row
        press(r, "PAD1")
        assertEquals(HomeRest.ROW, HomeSettings.rest)
        assertEquals(BottomTiles.REST, openTile(r))
        press(r, "PADDRIGHT"); press(r, "PAD1")
        assertEquals(HomeRest.DIAMONDS, HomeSettings.rest)
        press(r, "PAD2")
        assertNull(openTile(r))
        assertEquals(tile, r.pad.focusBox())
        press(r, "PAD2")
        assertTrue(r.pad.onTabRow)
        assertTrue(!left)
        press(r, "PAD2")
        assertTrue("B on the tab row leaves (Home)", left)
    }

    /** touch = the pad: a tap opens, a band button writes, a line that cannot act is not drawn (no dead stop), a tap on the tile closes */
    @Test fun touchAndNoDeadStops() {
        val r = room()
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        openByTouch(r, BottomTiles.CASTBAR)
        val p = partOf(r, BottomTiles.CASTBAR)
        assertEquals(BottomTiles.CASTBAR, p.openTile)
        var buttons = p.focusTargets().drop(p.tileIds().size)
        assertEquals("Off / Chat / Map / Across / Split (P2), no In combat while Off", 5, buttons.size)
        for (b in buttons) assertTrue(b.height() >= 110f)
        tap(p, buttons[2].centerX(), buttons[2].centerY()); lay(r)
        assertEquals(CastStripModel.Side.RIGHT, HomeSettings.castBarSide)
        assertEquals(HomeLayout.CastDock.UNDER_MAP, HomeSettings.layout.cast)
        buttons = p.focusTargets().drop(p.tileIds().size)
        assertEquals("Map Above / Below, Size S / M / L and In combat Hide / Stay now", 12, buttons.size)
        // the edge line: Above the map (the screen side kept), then Below again
        tap(p, buttons[5].centerX(), buttons[5].centerY()); lay(r)
        assertEquals(HomeLayout.CastDock.ABOVE_MAP, HomeSettings.layout.cast)
        assertEquals("Above map", BottomTiles.value(BottomTiles.CASTBAR, BottomTiles.Env(null, null)))
        buttons = p.focusTargets().drop(p.tileIds().size)
        tap(p, buttons[1].centerX(), buttons[1].centerY()); lay(r)
        assertEquals("Chat keeps the edge", HomeLayout.CastDock.ABOVE_CHAT, HomeSettings.layout.cast)
        buttons = p.focusTargets().drop(p.tileIds().size)
        tap(p, buttons[6].centerX(), buttons[6].centerY()); lay(r)
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, HomeSettings.layout.cast)
        HomeSettings.castBarSide = CastStripModel.Side.RIGHT; lay(r)
        buttons = p.focusTargets().drop(p.tileIds().size)
        tap(p, buttons[11].centerX(), buttons[11].centerY()); lay(r)
        assertEquals(CastStripModel.Combat.STAY, HomeSettings.castBarCombat)
        HomeSettings.castBarCombat = CastStripModel.Combat.HIDE
        // a tap on the open tile closes it; another tile's tap moves the one band
        openByTouch(r, BottomTiles.QUEST); assertEquals(BottomTiles.QUEST, openTile(r))
        openByTouch(r, BottomTiles.QUEST); assertNull(openTile(r))
        // L / R: another tab, the band closed
        hold(r); openByTouch(r, BottomTiles.SWING)
        assertEquals(BottomTiles.SWING, openTile(r))
        press(r, "PADRSHOULDER")
        assertNull(openTile(r))
        assertEquals(SettingsTabs.Tab.TOP, r.tab)
    }

    // ---------------------------------------------------------------- the renders (BOTTOM_SHOTS_DIR)

    /** the game's documents as the stage gets them on the device (CastBarAskShotsTest's): the controller's spells, Durotar's minimap,
     *  the bags, a focused quest, a few chat lines - sample values */
    private fun feedStage() {
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val nn = JSONArray()
        for (p in 1..8) nn.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        PreviewStage.feedFull(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
                .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", nn)))
                .put("hudS", JSONObject().put("c", false).put("g", 1000.0)))
            .put("map", JSONObject().put("ok", true).put("mapID", 1411).put("zone", "Durotar").put("subzone", "Razor Hill")
                .put("rect", JSONObject().put("c", 1).put("t", 1808.33).put("b", -1716.67).put("l", -1962.5).put("r", -7250.0))
                .put("x", 0.5177).put("y", 0.4279).put("wx", 300.0).put("wy", -4700.0).put("facing", 2.11))
            .put("bags", JSONObject().put("money", 1_234_567L).put("freeGeneral", 14).put("free", 14).put("total", 66)))
        PreviewStage.feedQuests(JSONObject().put("plv", 6).put("list", JSONArray()
            .put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup").put("lv", 6).put("watch", 1).put("focus", true).put("zone", "Durotar")
                .put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10"))))))
        val lines = JSONArray()
        for ((ch, who, text) in listOf(Triple("say", "Grukk", "anyone up for Burning Blade Coven?"), Triple("guild", "Zalaya", "grats on 6!"),
                Triple("party", "Mogra", "I'll tank, pull on 3"), Triple("system", "", "You have learned a new spell: Earth Shock."),
                Triple("say", "Torrik", "lfg Razor Hill quests"))) lines.put(JSONObject().put("ch", ch).put("who", who).put("text", text).put("i", lines.length() + 1))
        PreviewStage.feedChat(JSONObject().put("lines", lines).put("s", 1))
    }

    @Test fun shots() {
        val out = System.getenv("BOTTOM_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        val made = ArrayList<Pair<String, Bitmap>>()
        var r = room()
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }
        fun scroll(to: Int) { (sc.get(r) as android.widget.ScrollView).scrollTo(0, to); lay(r) }
        // the crops come one per frame: draw until none wait
        fun settle() { repeat(40) { lay(r); if (BottomPictures.pendingCount() == 0) return }; lay(r) }
        fun shot(name: String) {
            settle(); Thread.sleep(250); settle()
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            made.add(name to Bitmap.createScaledBitmap(b, W / 2, H / 2, true)); b.recycle()
        }
        fun reveal(id: String) {
            val p = partOf(r, id); val top = contentTop(r, p)!!
            val t = tileBoxes(r).getValue(id)
            val bottom = p.bandBox()?.let { it.bottom + top } ?: t.bottom
            val sv = sc.get(r) as android.widget.ScrollView
            GameUiTiles.reveal(sv.scrollY.toFloat(), sv.height.toFloat(), t.top - SettingsPad.REVEAL_TOP, bottom + SettingsPad.REVEAL_BOTTOM)
                ?.let { scroll(it.toInt().coerceAtLeast(0)) }
        }
        fun open(id: String) { r.bottomTiles.forEach { it.closeSelection() }; lay(r); openByTouch(r, id); reveal(id) }
        fun close() { r.bottomTiles.forEach { it.closeSelection() }; lay(r) }
        shot("bot_1_no_game")
        open(BottomTiles.REST); shot("bot_1b_no_game_band_rest")
        close(); scroll(0)
        feedStage()
        HomeSettings.castBarSide = CastStripModel.Side.BOTH
        r.refreshGroups(); lay(r)
        // the first crops, then the second pass once the minimap's tiles decoded (their own thread) and the stage drew again
        settle(); Thread.sleep(BottomPictures.AGAIN_MS + 300); feedStage(); org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(BottomPictures.AGAIN_MS + 300))
        shot("bot_2_game")
        // (the whole tab fits the page: no "lower" view; the bands lower down scroll, below)
        System.getenv("BOTTOM_DEBUG_DIR")?.let { d ->
            File(d).mkdirs()
            for ((name, fight) in listOf("home_stage" to false, "home_stage_fight" to true)) {
                val b = Bitmap.createBitmap(PreviewStage.W, PreviewStage.H, Bitmap.Config.ARGB_8888)
                if (PreviewStage.render(app, HomeView.Companion.Look(HomeSettings.rest, true, HomeSettings.questOverMap, fight), fight, null, b) != null)
                    File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        for ((i, id) in BottomTiles.ALL.withIndex()) {
            open(id)
            // Hold to talk's band a moment in: the hold done, the words in the box (the band opens on the hold)
            if (id == BottomTiles.TALK) org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1600))
            shot("bot_band_${i + 1}_$id")
        }
        // In fights = Top screen: the fight's frames with the "up" plate (the controls went to the game screen)
        HomeSettings.fightBand = HomeLayout.FightBand.TOP
        open(BottomTiles.COMBAT); shot("bot_band_3b_combat_top_screen")
        HomeSettings.fightBand = HomeLayout.FightBand.GROW
        // Hold to talk On (this character): the hold, the words in the box, A to send; then Send now, the line sent into the chat
        HomeSettings.setHoldTalk("Sample-Realm", true); HomeSettings.setTalkSendNow("Sample-Realm", false)
        open(BottomTiles.TALK); org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1600)); shot("bot_band_7b_talk_on")
        HomeSettings.setTalkSendNow("Sample-Realm", true)
        open(BottomTiles.TALK); org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2600)); shot("bot_band_7c_talk_send_now")
        HomeSettings.setTalkSendNow("Sample-Realm", false); HomeSettings.setHoldTalk("Sample-Realm", false)
        // the zoom band with the cursor on Indoors: the picture follows it
        close(); hold(r); lay(r)
        open(BottomTiles.ZOOM)
        val zp = partOf(r, BottomTiles.ZOOM)
        val zb = zp.focusTargets().drop(zp.tileIds().size)
        zp.focusAt(zb[2].centerX(), zb[2].centerY()); shot("bot_band_9b_zoom_indoors")
        // the cursor: on a tile, in a band, B back
        close(); scroll(0); hold(r); press(r, "PADDDOWN"); press(r, "PADDDOWN"); press(r, "PADDLEFT"); press(r, "PAD1"); reveal(BottomTiles.COMBAT); shot("bot_cursor_on_open_tile")
        press(r, "PADDDOWN"); shot("bot_cursor_in_band")
        press(r, "PAD2"); shot("bot_b_back_on_tile")
        // the themes: the default Classic Bronze, two OLED ones and a dark one (ThemePresets has no light preset), the tab and a band each
        for (th in listOf("classic", "oled_bronze", "dark_silver", "oled_frost")) {
            Theme.use(th); close(); hold(r); lay(r); scroll(0); shot("bot_theme_$th")
            open(BottomTiles.QUEST); shot("bot_theme_${th}_band_quest")
        }
        Theme.use("classic")
        // Spanish and French (with this area's new words, BOTTOM_I18N = scratchpad res/i18n/bottom.json, merged as the integrator will)
        val extra = System.getenv("BOTTOM_I18N")?.let { File(it) }?.takeIf { it.canRead() }?.let { JSONObject(it.readText()) }
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { f ->
                    val doc = JSONObject(f.readText())
                    extra?.let { x -> val plain = doc.getJSONObject("plain"); for (k in x.keys()) plain.put(k, x.getJSONObject(k).getString(l)) }
                    Tr.load(l, doc.toString())
                }
            Tr.setChoice(app, l); close(); scroll(0); shot("bot_${l}")
            open(BottomTiles.CLOCK); shot("bot_${l}_band_clock")
        }
        Tr.setChoice(app, "auto")
        // no kit on the phone yet (the stage's own pictures, made with the kit, go too: a phone without the kit never had them)
        BottomPictures.drop()
        MemWatch.room = "home"; MemWatch.trimmed(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW); MemWatch.room = "-"
        r = room(kit = false); lay(r); shot("bot_nokit")
        open(BottomTiles.CLOCK); shot("bot_nokit_band_clock")
        open(BottomTiles.CASTBAR); shot("bot_nokit_band_castbar")
        open(BottomTiles.WORLD); shot("bot_nokit_band_worldmap")
        art()
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        sheet(out, made)
    }

    private fun sheet(out: File, made: List<Pair<String, Bitmap>>) {
        val k = 0.5f
        val cw = (W * k).toInt(); val ch = (H * k).toInt()
        val cols = 3; val rows = (made.size + cols - 1) / cols
        val pad = 12; val label = 34
        val sheet = Bitmap.createBitmap(cols * (cw + pad) + pad, rows * (ch + label + pad) + pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 40))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }
        for ((i, e) in made.withIndex()) {
            val x = pad + (i % cols) * (cw + pad); val y = pad + (i / cols) * (ch + label + pad)
            c.drawBitmap(e.second, null, android.graphics.Rect(x, y, x + cw, y + ch), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawText(e.first, x.toFloat(), (y + ch + 26).toFloat(), tp)
        }
        File(out, "bot_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** review round 2 ("pictures never lie"): the Tap-to-cast tile's picture puts an Above chat / Above map bar at its column's TOP (the
     *  column starts under it), an Under one at the foot - the very edge the tile's word and Home use */
    @Test fun theCastBarPictureShowsTheBarsEdge() {
        room(kit = false)
        fun goldRows(dock: HomeLayout.CastDock, x0: Int, x1: Int): List<Int> {
            HomeLayoutStore.character(null); HomeLayoutStore.set(HomeLayout(cast = dock))
            val b = Bitmap.createBitmap(432, 216, Bitmap.Config.ARGB_8888)
            BottomPictures.draw(Canvas(b), app, BottomTiles.CASTBAR, RectF(0f, 0f, 432f, 216f), BottomPictures.Look(BottomTiles.Env(null, null)), null, 0)
            val g = Theme.gold
            fun near(c: Int) = kotlin.math.abs(Color.red(c) - Color.red(g)) < 40 && kotlin.math.abs(Color.green(c) - Color.green(g)) < 40 &&
                kotlin.math.abs(Color.blue(c) - Color.blue(g)) < 40
            return (0 until 216).filter { y -> (x0 until x1).count { x -> near(b.getPixel(x, y)) } > (x1 - x0) / 2 }
        }
        try {
            for ((dock, x0, x1) in listOf(Triple(HomeLayout.CastDock.ABOVE_CHAT, 30, 190), Triple(HomeLayout.CastDock.ABOVE_MAP, 242, 402))) {
                val top = goldRows(dock, x0, x1)
                assertTrue("$dock: the bar's edge near the picture's top: $top", top.isNotEmpty() && top.all { it < 216 / 3 })
                val under = goldRows(dock.withTop(false), x0, x1)
                assertTrue("${dock.withTop(false)}: at the foot: $under", under.isNotEmpty() && under.all { it > 216 * 2 / 3 })
            }
        } finally { HomeLayoutStore.set(HomeLayout()) }
    }
}
