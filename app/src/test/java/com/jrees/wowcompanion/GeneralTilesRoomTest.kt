package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * SETTINGS > GENERAL AS PICTURE TILES, off-device (the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen settings
 * menu with the pictures and want to do something similar to the other menus ... an ultra code 'beautification' task"; the beautify
 * design, section 2): the room built as the host builds it; Look = ONE tile row (Theme wide, Swap screens, Voice typing) and Language as a
 * compact row; Account and Help compact rows. A tile opens Top screen's band (Swap screens' On / Off and, while a restart can apply it,
 * Restart DS Forever; Voice typing's three); the Theme tile opens the theme maker's page (its premades in the band while there is none);
 * the controller's walk (A opens, B closes the band with the cursor back on the tile, then the tab row); touch the same.
 *
 * With GENERAL_SHOTS_DIR set it renders the tab (THEME_ART_ROOT = an art root laid out like the app's): no game, the game's documents
 * (the Theme tile's live Home), each band, the cursor, Account + Help, Spanish, French, other themes, and with no kit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeneralTilesRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var n = 0
    private var game = false
    private var restarts = 0
    private var swapRestarts = 0
    private var reloads = 0
    private var reports = 0

    private fun art(root: String? = System.getenv("THEME_ART_ROOT")) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root
        for (nm in listOf("castCache", "castMissing")) when (val o = UiKit::class.java.getDeclaredField(nm).apply { isAccessible = true }.get(UiKit)) {
            is MutableMap<*, *> -> o.clear(); is MutableSet<*> -> o.clear()
        }
        GeneralPictures.letGo()
    }

    private fun room(kit: Boolean = true, swap: Boolean = true): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); Theme.init(app)
        if (kit) art() else art(null)
        ScreenSwap.offered = swap
        ScreenSwap.take(false)
        ScreenSwap.restart = { swapRestarts++ }
        val v = SettingsView(app) { reloads++ }
        v.loginRow = true
        v.gameRunning = { game }
        v.onRestart = { restarts++ }
        v.onReport = { reports++ }
        val r = SettingsRoom(app, v) { null }
        r.show(SettingsTabs.Tab.GENERAL, force = true)
        lay(r)
        return r
    }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(scratch))
        }
    }
    /** one page-sized bitmap for every layout pass (a new one per pass ran the native heap out in the renders) */
    private val scratch: Bitmap by lazy { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }

    private fun part(r: SettingsRoom, g: SettingsTabs.Group) = r.groupBoxes.first { it.group == g }.parts.single() as GeneralTilesView

    private fun tap(r: SettingsRoom, v: View, b: RectF) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY(), 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, b.centerX(), b.centerY(), 0); v.dispatchTouchEvent(u); u.recycle()
        lay(r)
    }

    private fun press(r: SettingsRoom, k: String) { r.pad.onPad(JSONObject().put("k", k).put("n", ++n)); lay(r) }

    private fun hold(r: SettingsRoom) {
        r.pad.enter(); r.show(SettingsTabs.Tab.GENERAL, force = true)
        r.pad.onGuestAck(JSONObject().put("op", "hold").put("ok", true)); lay(r)
    }

    private fun contentTop(r: SettingsRoom, v: View): Float {
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        val content = sc.getChildAt(0)
        var y = 0f; var cur: View = v
        while (cur !== content) { y += cur.top; cur = cur.parent as View }
        return y
    }

    private fun inPage(r: SettingsRoom, v: View, b: RectF) = RectF(b).apply { offset(0f, contentTop(r, v)) }

    @After fun forget() {
        Theme.use("classic")
        Settings.swapScreens = false
        Settings.voiceTyping = Voice.Mode.DEVICE
        runCatching { Settings.windowsId = WindowsId.PER_INSTALL; Settings.windowsIdRan = null }
        WindowsId.forgetLaunchForTest()
        ScreenSwap.offered = false; ScreenSwap.restart = null; ScreenSwap.take(false)
        GeneralTiles.onOpenTheme = null
        Tr.setChoice(app, "auto")
        GeneralPictures.letGo()
    }

    // ---------------------------------------------------------------- the page

    @Test fun generalIsOneTileRowThenCompactRows() {
        val r = room()
        val look = part(r, SettingsTabs.Group.LOOK)
        assertEquals(listOf(GeneralTiles.THEME, GeneralTiles.SWAP, GeneralTiles.VOICE), look.tileIds())
        assertEquals(listOf(GeneralTiles.Row.LANGUAGE), look.rowIds())
        assertEquals(476f, look.tileBox(GeneralTiles.THEME)!!.width(), 0.01f)
        assertEquals(listOf(GeneralTiles.Row.LOGIN, GeneralTiles.Row.GAME_ACCOUNT, GeneralTiles.Row.WINDOWS_ID), part(r, SettingsTabs.Group.ACCOUNT).rowIds())
        assertEquals(listOf(GeneralTiles.Row.REPORT, GeneralTiles.Row.RELOAD, GeneralTiles.Row.STATUS), part(r, SettingsTabs.Group.HELP).rowIds())
        // no group folds, no heading is a stop; every part is shown
        for (g in SettingsTabs.groups(SettingsTabs.Tab.GENERAL)) assertTrue(g in r.open)
        // compact rows are 88 px: the Account part is its three rows and the foot
        assertEquals(4 + 3 * 88 + 16, part(r, SettingsTabs.Group.ACCOUNT).height)
        // a single-screen device: no Swap screens tile, the row closes up
        val single = part(room(swap = false), SettingsTabs.Group.LOOK)
        assertEquals(listOf(GeneralTiles.THEME, GeneralTiles.VOICE), single.tileIds())
    }

    @Test fun swapScreensBandSavesAndRestartsOnlyWhenItCan() {
        val r = room()
        val look = part(r, SettingsTabs.Group.LOOK)
        tap(r, look, look.tileBox(GeneralTiles.SWAP)!!)
        assertEquals(GeneralTiles.SWAP, look.openTile)
        assertEquals(listOf(2), look.bandButtons().map { it.size })                  // On / Off; nothing to restart yet
        tap(r, look, look.bandButtons()[0][0])                                       // On
        assertTrue(Settings.swapScreens)
        assertEquals("the Restart line comes (no game: it can restart now)", listOf(2, 1), look.bandButtons().map { it.size })
        tap(r, look, look.bandButtons()[1][0])
        assertEquals(1, swapRestarts)
        // Off again: the choice in use, the line goes
        tap(r, look, look.bandButtons()[0][1])
        assertFalse(Settings.swapScreens)
        assertEquals(listOf(2), look.bandButtons().map { it.size })
        // the game up: no Restart line (a restart would end it), the status says when it applies
        game = true
        tap(r, look, look.bandButtons()[0][0])
        assertEquals(listOf(2), look.bandButtons().map { it.size })
        assertEquals("After the game closes", look.bandStatus())
        // a second tap on the tile closes its band
        tap(r, look, look.tileBox(GeneralTiles.SWAP)!!)
        assertNull(look.openTile)
    }

    @Test fun voiceTypingBandSaves() {
        val r = room()
        val look = part(r, SettingsTabs.Group.LOOK)
        tap(r, look, look.tileBox(GeneralTiles.VOICE)!!)
        val line = look.bandButtons().single()
        assertEquals(3, line.size)
        tap(r, look, line[1]); assertEquals(Voice.Mode.ONLINE, Settings.voiceTyping)
        tap(r, look, look.bandButtons()[0][2]); assertEquals(Voice.Mode.OFF, Settings.voiceTyping)
        tap(r, look, look.bandButtons()[0][0]); assertEquals(Voice.Mode.DEVICE, Settings.voiceTyping)
        // one band in the room: opening Swap screens' moves it
        tap(r, look, look.tileBox(GeneralTiles.SWAP)!!)
        assertEquals(GeneralTiles.SWAP, look.openTile)
    }

    @Test fun themeTileOpensTheMakerPageOrThePremades() {
        val r = room()
        val look = part(r, SettingsTabs.Group.LOOK)
        // no page wired: the premades in its band, a press applies one at once
        tap(r, look, look.tileBox(GeneralTiles.THEME)!!)
        assertEquals(GeneralTiles.THEME, look.openTile)
        assertEquals(listOf(4, 3, 3), look.bandButtons().map { it.size })
        tap(r, look, look.bandButtons()[2][0])
        assertEquals("oled_rose", Theme.cur.id)
        // the maker's page wired (themes' ThemeMakerPage): A opens it, no band
        var opened = 0
        GeneralTiles.onOpenTheme = { opened++; true }
        tap(r, look, look.tileBox(GeneralTiles.THEME)!!)
        assertEquals(1, opened)
        assertNull(look.openTile)
    }

    @Test fun controllerWalkOpensAndBClosesTheBand() {
        val r = room()
        hold(r)
        assertTrue(r.pad.onTabRow)
        press(r, "PADDDOWN")
        val look = part(r, SettingsTabs.Group.LOOK)
        assertEquals("the page's first stop is the Theme tile", inPage(r, look, look.tileBox(GeneralTiles.THEME)!!), r.pad.focusBox())
        press(r, "PADDRIGHT")
        val swapBox = inPage(r, look, look.tileBox(GeneralTiles.SWAP)!!)
        assertEquals(swapBox, r.pad.focusBox())
        press(r, "PAD1")                                                          // A: its band opens, the cursor stays on the tile
        repeat(3) { lay(r) }
        assertEquals(GeneralTiles.SWAP, look.openTile)
        assertEquals(swapBox, r.pad.focusBox())
        press(r, "PADDDOWN")                                                      // into its choices
        val f = r.pad.focusBox()!!
        assertTrue(look.bandButtons().flatten().any { inPage(r, look, it) == f })
        press(r, "PAD2")                                                          // B: the band closes, the cursor back on the tile
        repeat(3) { lay(r) }
        assertNull(look.openTile)
        assertEquals(swapBox, r.pad.focusBox())
        press(r, "PAD2")                                                          // B again: the tab row
        assertTrue(r.pad.onTabRow)
    }

    @Test fun accountAndHelpRows() {
        val r = room()
        val acct = part(r, SettingsTabs.Group.ACCOUNT)
        val win = acct.controlBoxes(GeneralTiles.Row.WINDOWS_ID)
        assertEquals(2, win.size)
        for (b in win) assertTrue(acct.focusTargets().any { it == b })
        // Original while the game runs Per install: the Restart the game row comes, its ONE button the launcher's restart
        game = true
        WindowsId.markLaunched(WindowsId.PER_INSTALL, WindowsId.result(0))
        tap(r, acct, win[1])
        assertEquals(WindowsId.ORIGINAL, Settings.windowsId)
        assertEquals(GeneralTiles.Row.WINDOWS_RESTART, acct.rowIds().last())
        tap(r, acct, acct.controlBoxes(GeneralTiles.Row.WINDOWS_RESTART).single())
        assertEquals(1, restarts)
        tap(r, acct, acct.controlBoxes(GeneralTiles.Row.GAME_ACCOUNT)[0])
        assertTrue(Settings.gameAccountAsk)
        tap(r, acct, acct.controlBoxes(GeneralTiles.Row.GAME_ACCOUNT)[1])
        assertFalse(Settings.gameAccountAsk)
        // Help: Send opens the report; Reload is two taps; the cursor never starts on Reload
        val help = part(r, SettingsTabs.Group.HELP)
        tap(r, help, help.controlBoxes(GeneralTiles.Row.REPORT).single())
        assertEquals(1, reports)
        val rl = help.controlBoxes(GeneralTiles.Row.RELOAD).single()
        assertTrue(help.isReload(rl))
        tap(r, help, rl); assertEquals(0, reloads)
        tap(r, help, rl); assertEquals(1, reloads)
        // Language: the four, a tap saves
        val look = part(r, SettingsTabs.Group.LOOK)
        val lang = look.controlBoxes(GeneralTiles.Row.LANGUAGE)
        assertEquals(Tr.LANGS.size, lang.size)
        tap(r, look, lang[2])
        assertEquals("es", Tr.choice())
    }

    // ---------------------------------------------------------------- the renders (GENERAL_SHOTS_DIR)

    /** the game's documents as the stage gets them on the device (the Theme tile's live Home) */
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
        PreviewStage.feedQuests(JSONObject().put("quests", JSONArray()
            .put(JSONObject().put("id", 1).put("title", "Sample Quest").put("focus", true)
                .put("obj", JSONArray().put(JSONObject().put("text", "Sample slain: 4/10"))))))
    }

    @Test fun shots() {
        val out = System.getenv("GENERAL_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        }
        val page = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        var r = room()
        val sc = { SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView }
        fun shot(name: String, scrollTo: Int = 0) {
            lay(r); Thread.sleep(300); lay(r)
            sc().scrollTo(0, scrollTo); lay(r)
            val c = Canvas(page); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val look = { part(r, SettingsTabs.Group.LOOK) }
        shot("gen_1_no_game")
        shot("gen_2_account_help", scrollTo = 10_000)
        // the Swap screens band, On with Restart waiting
        Settings.swapScreens = true
        tap(r, look(), look().tileBox(GeneralTiles.SWAP)!!)
        shot("gen_3_band_swap_on_restart")
        game = true; lay(r)
        shot("gen_4_band_swap_on_game_running")
        game = false
        Settings.swapScreens = false
        tap(r, look(), look().tileBox(GeneralTiles.VOICE)!!)
        shot("gen_5_band_voice")
        Settings.voiceTyping = Voice.Mode.OFF; lay(r)
        shot("gen_6_band_voice_off")
        Settings.voiceTyping = Voice.Mode.ONLINE; lay(r)
        shot("gen_7_band_voice_online")
        Settings.voiceTyping = Voice.Mode.DEVICE
        tap(r, look(), look().tileBox(GeneralTiles.THEME)!!)
        shot("gen_8_band_theme_premades")
        look().closeSelection()
        // the cursor on the Swap screens tile, its band open
        hold(r); press(r, "PADDDOWN"); press(r, "PADDRIGHT"); press(r, "PAD1"); repeat(3) { lay(r) }
        shot("gen_9_cursor_on_open_tile")
        look().closeSelection(); r.pad.backToTabs()
        // the game's documents: the Theme tile shows our live Home
        feedStage()
        shot("gen_10_with_game")
        PreviewStage.home(app, HomeView.Companion.Look(HomeSettings.rest, HomeSettings.combatGrow, HomeSettings.questOverMap, HomeSettings.swingTimer), false)
            ?.let { h -> File(out, "gen_10b_stage_home.png").outputStream().use { h.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        for (id in listOf("oled_rose", "dark_silver", "oled_bronze")) {
            Theme.use(id); r = room()
            // the stage's picture is still the last theme's until it renders again: the drawn mini room meanwhile
            shot("gen_11_theme_${id}_mini_room")
            feedStage(); org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4))
            shot("gen_11_theme_$id")
        }
        Theme.use("classic")
        for (l in listOf("es", "fr")) {
            Tr.setChoice(app, l); r = room()
            tap(r, look(), look().tileBox(GeneralTiles.SWAP)!!)
            Settings.swapScreens = true; lay(r)
            shot("gen_12_$l"); shot("gen_12_${l}_account_help", scrollTo = 10_000)
            Settings.swapScreens = false
        }
        Tr.setChoice(app, "auto")
        // no kit on the device yet: every picture's own drawing
        r = room(kit = false)
        shot("gen_13_nokit")
        tap(r, look(), look().tileBox(GeneralTiles.SWAP)!!)
        shot("gen_14_nokit_band")
        art()
        // the pictures alone at the band's size, kit and no kit (a sheet)
        val sheet = Bitmap.createBitmap(3 * 470 + 20, 4 * 250 + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(android.graphics.Color.rgb(40, 40, 40))
        val cells = listOf(
            Triple(GeneralTiles.THEME, GeneralPictures.Look(), true), Triple(GeneralTiles.SWAP, GeneralPictures.Look(swap = false), true),
            Triple(GeneralTiles.SWAP, GeneralPictures.Look(swap = true), true), Triple(GeneralTiles.VOICE, GeneralPictures.Look(voice = Voice.Mode.DEVICE), true),
            Triple(GeneralTiles.VOICE, GeneralPictures.Look(voice = Voice.Mode.ONLINE), true), Triple(GeneralTiles.VOICE, GeneralPictures.Look(voice = Voice.Mode.OFF), true),
            Triple(GeneralTiles.THEME, GeneralPictures.Look(), false), Triple(GeneralTiles.SWAP, GeneralPictures.Look(swap = false), false),
            Triple(GeneralTiles.SWAP, GeneralPictures.Look(swap = true), false))
        for ((i, cell) in cells.withIndex()) {
            if (!cell.third) art(null)
            val x = 10f + (i % 3) * 470f; val y = 10f + (i / 3) * 250f
            val box = if (cell.first == GeneralTiles.THEME) RectF(x, y + 60f, x + 460f, y + 168f) else RectF(x, y, x + 438f, y + 219f)
            GeneralPictures.draw(c, app, cell.first, box, cell.second)
        }
        art()
        File(out, "gen_pictures_sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(sheet)
    }
}
