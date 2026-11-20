package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
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
 * With GENERAL_SHOTS_DIR set it renders the tab (THEME_ART_ROOT = an art root laid out like the app's): no game, each band, the cursor (landing,
 * a tile, a swatch), Account + Help, Spanish, French, other themes, and with no kit.
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
    private lateinit var host: SettingsView

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
        host = v
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
        clearStage()
        BottomPictures.drop()
    }

    /** no game document (as a fresh start): PreviewStage holds none */
    private fun clearStage() {
        for (f in listOf("full", "quests", "chat")) PreviewStage::class.java.getDeclaredField(f).apply { isAccessible = true }.set(PreviewStage, null)
    }

    /** the game's documents as the stage gets them on the device (BottomTilesRoomTest's): the controller's spells, Durotar's minimap, the
     *  bags, a focused quest, a few chat lines - sample values */
    private fun feedStage() {
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val nn = org.json.JSONArray()
        for (p in 1..8) nn.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        PreviewStage.feedFull(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
                .put("pages", org.json.JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", nn)))
                .put("hudS", JSONObject().put("c", false).put("g", 1000.0)))
            .put("map", JSONObject().put("ok", true).put("mapID", 1411).put("zone", "Durotar").put("subzone", "Razor Hill")
                .put("rect", JSONObject().put("c", 1).put("t", 1808.33).put("b", -1716.67).put("l", -1962.5).put("r", -7250.0))
                .put("x", 0.5177).put("y", 0.4279).put("wx", 300.0).put("wy", -4700.0).put("facing", 2.11))
            .put("bags", JSONObject().put("money", 1_234_567L).put("freeGeneral", 14).put("free", 14).put("total", 66)))
        PreviewStage.feedQuests(JSONObject().put("plv", 6).put("list", org.json.JSONArray()
            .put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup").put("lv", 6).put("watch", 1).put("focus", true).put("zone", "Durotar")
                .put("obj", org.json.JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10"))))))
        val lines = org.json.JSONArray()
        for ((ch, who, text) in listOf(Triple("say", "Grukk", "anyone up for Burning Blade Coven?"), Triple("guild", "Zalaya", "grats on 6!"),
                Triple("party", "Mogra", "I'll tank, pull on 3"), Triple("system", "", "You have learned a new spell: Earth Shock."))) {
            lines.put(JSONObject().put("ch", ch).put("who", who).put("text", text).put("i", lines.length() + 1))
        }
        PreviewStage.feedChat(JSONObject().put("lines", lines).put("s", 1))
    }

    /** the Theme tile's well in the Look part's coordinates */
    private fun themeWell(r: SettingsRoom): RectF {
        val t = part(r, SettingsTabs.Group.LOOK).tileBox(GeneralTiles.THEME)!!
        return GameUiTiles.well(GameUiTiles.Box(t.left, t.top, t.right, t.bottom)).rect()
    }
    private fun GameUiTiles.Box.rect() = RectF(l, t, r, b)

    // ---------------------------------------------------------------- the page

    /**
     * the user's ruling, 2026-11-17: "General's Theme tile uses the real Home miniature (the same renderer as the theme maker preview and
     * Bottom tab tiles, with its reuse/free rules; the drawn mini room only as the before-first-data fallback)": no document = the drawn
     * room (no crop); with one, a crop of BottomPictures' 1x stage in the theme in use; a theme change makes a new crop; General out of view
     * frees it with the Bottom tiles' crops
     */
    @Test fun themeTileShowsTheRealHomeMiniature() {
        clearStage(); BottomPictures.drop()
        val r = room()
        val well = themeWell(r)
        val w = well.width().toInt(); val h = well.height().toInt()
        repeat(2) { lay(r) }
        assertTrue("no document: the drawn mini room", GeneralPictures.themeWaiting)
        assertFalse(BottomPictures.themeHomeReady(w, h))
        feedStage()
        part(r, SettingsTabs.Group.LOOK).invalidate(); repeat(3) { lay(r) }
        assertTrue("the real Home crop", BottomPictures.themeHomeReady(w, h))
        assertFalse(GeneralPictures.themeWaiting)
        // the window: Home's whole width, the tile's shape, inside Home
        val win = BottomPictures.themeWindow(mapOf("map" to RectF(10f, 240f, 610f, 1000f)), w / h.toFloat())!!
        assertEquals(0f, win.left, 0.01f); assertEquals(BottomPictures.HOME_W, win.right, 0.01f)
        assertEquals(w / h.toFloat(), win.width() / win.height(), 0.01f)
        assertEquals(240f + BottomPictures.THEME_HEAD, win.bottom, 0.5f)
        // another theme: a new crop (the old theme's is not shown for it)
        Theme.use("oled_bronze")
        assertFalse(BottomPictures.themeHomeReady(w, h))
        part(r, SettingsTabs.Group.LOOK).invalidate(); repeat(3) { lay(r) }
        assertTrue(BottomPictures.themeHomeReady(w, h))
        // General out of view (Android's aggregated visibility; the test room is never attached to a window, so it is called here): the
        // crops and the stage are freed, as the Bottom tiles' are
        part(r, SettingsTabs.Group.LOOK).onVisibilityAggregated(true)
        assertTrue(BottomPictures.themeHomeReady(w, h))
        part(r, SettingsTabs.Group.LOOK).onVisibilityAggregated(false)
        assertFalse(BottomPictures.themeHomeReady(w, h))
    }

    @Test fun generalIsOneTileRowThenCompactRows() {
        val r = room()
        val look = part(r, SettingsTabs.Group.LOOK)
        assertEquals(listOf(GeneralTiles.THEME, GeneralTiles.SWAP, GeneralTiles.VOICE), look.tileIds())
        assertEquals(listOf(GeneralTiles.Row.LANGUAGE), look.rowIds())
        assertEquals(476f, look.tileBox(GeneralTiles.THEME)!!.width(), 0.01f)
        assertEquals(listOf(GeneralTiles.Row.LOGIN, GeneralTiles.Row.GAME_ACCOUNT, GeneralTiles.Row.WINDOWS_ID, GeneralTiles.Row.BROWSER_LOGIN), part(r, SettingsTabs.Group.ACCOUNT).rowIds())
        assertEquals(listOf(GeneralTiles.Row.REPORT, GeneralTiles.Row.RELOAD, GeneralTiles.Row.STATUS), part(r, SettingsTabs.Group.HELP).rowIds())
        // no group folds, no heading is a stop; every part is shown - but Reset (2026-11-19: it folds on its own, shown folded)
        for (g in SettingsTabs.groups(SettingsTabs.Tab.GENERAL)) assertEquals(g.name, g != SettingsTabs.Group.RESET, g in r.open)
        // compact rows are 88 px: the Account part is its four rows (Browser login under Windows ID, 2026-11-18) and the foot
        assertEquals(4 + 4 * 88 + 16, part(r, SettingsTabs.Group.ACCOUNT).height)
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
        // the mode's three, then #477's Voice language stepper (previous / next) while voice typing is on
        assertEquals(listOf(3, 2), look.bandButtons().map { it.size })
        val line = look.bandButtons()[0]
        tap(r, look, line[1]); assertEquals(Voice.Mode.ONLINE, Settings.voiceTyping)
        tap(r, look, look.bandButtons()[0][2]); assertEquals(Voice.Mode.OFF, Settings.voiceTyping)
        assertEquals("Off: no language line (no dead stop)", listOf(3), look.bandButtons().map { it.size })
        tap(r, look, look.bandButtons()[0][0]); assertEquals(Voice.Mode.DEVICE, Settings.voiceTyping)
        // #477: Same as the app by default; next = Auto-detect, next again = English ... previous goes back; every button a stop
        Settings.voiceLanguage = VoiceLanguage.SAME
        try {
            for (b in look.bandButtons()[1]) assertTrue(look.focusTargets().any { it == b })
            tap(r, look, look.bandButtons()[1][1]); assertEquals(VoiceLanguage.AUTO, Settings.voiceLanguage)
            repeat(5) { tap(r, look, look.bandButtons()[1][1]) }; assertEquals("ru", Settings.voiceLanguage)
            tap(r, look, look.bandButtons()[1][0]); assertEquals("de", Settings.voiceLanguage)
            tap(r, look, look.bandButtons()[1][1]); assertEquals("ru", Settings.voiceLanguage)
        } finally { Settings.voiceLanguage = VoiceLanguage.SAME }
        // one band in the room: opening Swap screens' moves it
        tap(r, look, look.tileBox(GeneralTiles.SWAP)!!)
        assertEquals(GeneralTiles.SWAP, look.openTile)
    }

    @Test fun themeTileOpensTheMakerPageOrThePremades() {
        val r = room()
        val look = part(r, SettingsTabs.Group.LOOK)
        // no page wired: the premade gallery in its band (standard tiles, 5 x 2), a press applies one at once, the gallery stays
        tap(r, look, look.tileBox(GeneralTiles.THEME)!!)
        assertEquals(GeneralTiles.THEME, look.openTile)
        assertEquals(listOf(5, 5, 2), look.bandButtons().map { it.size })            // 12 premades since themes added Dark Violet and OLED Jade
        val sizes = look.bandButtons().flatten().map { Math.round(it.width() * 10) to Math.round(it.height() * 10) }.toSet()
        assertEquals(1, sizes.size)
        assertEquals(GameUiTiles.TILE_H, look.bandButtons()[0][0].height(), 0.01f)
        // every premade is a cursor stop
        for (b in look.bandButtons().flatten()) assertTrue(look.focusTargets().any { it == b })
        tap(r, look, look.bandButtons()[1][2])
        assertEquals("oled_silver", Theme.cur.id)                                   // row 2, third: Dark Violet moved the OLEDs one on
        assertEquals(GeneralTiles.THEME, look.openTile)
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

    @Test fun savedReportOpensThenDismissesOnlyThatReport() {
        val r = room()
        val help = part(r, SettingsTabs.Group.HELP)
        var opened = 0
        host.onReportOpenSaved = { opened++ }
        try {
            ReportFlow.markSaved("/x/report1.zip", 1_000L, "https://example.invalid/1")
            lay(r)
            // the saved report's note is the row's label (its time); its ONE button opens it
            assertEquals(Tr.t(ReportFlow.noteTitle(ReportFlow.pending!!)), help.reportLabel())
            assertEquals("Open issue", help.reportWord())
            val b = help.controlBoxes(GeneralTiles.Row.REPORT).single()
            tap(r, help, b)
            assertEquals(1, opened)
            // no browser opened (still pending): the second state Dismiss, for THIS report
            assertEquals("Dismiss", help.reportWord())
            // a browser opened it (the host clears it) and a NEW report saves itself: it starts at Open issue, never dismissed unseen
            ReportFlow.clearPending()
            ReportFlow.markSaved("/x/report2.zip", 2_000L, "https://example.invalid/2")
            lay(r)
            assertEquals("Open issue", help.reportWord())
            tap(r, help, b)
            assertEquals(2, opened)
            assertNotNull(ReportFlow.pending)
            tap(r, help, b)                                                          // Dismiss
            assertNull(ReportFlow.pending)
            assertEquals("Send", help.reportWord())
            assertEquals(Tr.t("Problem report"), help.reportLabel())
        } finally { ReportFlow.clearPending() }
    }

    /** the user, 2026-11-19: "just a simple button at the bottom that says 'Support the Project'": the tab's LAST stop, one button across
     *  the row; touch or A opens the Ko-fi page through the host's link opener; no browser = the button says so for a few seconds */
    @Test fun supportTheProjectIsTheLastButtonAndOpensKofi() {
        val r = room()
        val urls = ArrayList<String>()
        var browser = true
        host.onOpenUrl = { u -> urls += u; browser }
        // 2026-11-19: its own headless group, the page's last (Reset folds above it)
        val help = part(r, SettingsTabs.Group.SUPPORT)
        assertEquals(listOf(GeneralTiles.Row.SUPPORT), help.rowIds())
        assertEquals(SettingsTabs.Group.SUPPORT, SettingsTabs.groups(SettingsTabs.Tab.GENERAL).last())
        val b = help.controlBoxes(GeneralTiles.Row.SUPPORT).single()
        assertEquals("the page's last stop", b, help.focusTargets().last())
        assertTrue("one button across the row", b.width() > W * 0.8f)
        assertEquals(SupportLink.ROW_ID, help.supportWord())
        // touch
        tap(r, help, b)
        assertEquals(listOf("https://ko-fi.com/yeslander"), urls)
        assertEquals(SupportLink.ROW_ID, help.supportWord())
        // no browser: the note on the button
        browser = false
        tap(r, help, b)
        assertEquals(2, urls.size)
        assertEquals(SupportLink.NO_BROWSER, help.supportWord())
        browser = true
        // the controller: Down walks to it (the page's last stop), A opens the page
        hold(r)
        press(r, "PADDDOWN")
        val want = inPage(r, help, b)
        repeat(40) { if (r.pad.focusBox() != want) press(r, "PADDDOWN") }
        assertEquals(want, r.pad.focusBox())
        press(r, "PAD1")
        assertEquals(3, urls.size)
        assertEquals(SupportLink.URL, urls.last())
        // its words in Spanish and French (Tr, menus only)
        for ((l, w) in listOf("es" to "Apoya el proyecto", "fr" to "Soutenir le projet")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .first { it.canRead() }.let { Tr.load(l, it.readText()) }
            Tr.setChoice(app, l)
            assertEquals(w, Tr.t(SupportLink.ROW_ID))
        }
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
        // the room opens on the tab row (Settings' shared landing, as Top screen's top_1); Down = the page's first stop, the Theme tile
        shot("gen_1_no_game")
        hold(r); press(r, "PADDDOWN"); repeat(3) { lay(r) }
        shot("gen_0_cursor_on_theme_tile")
        r.pad.backToTabs()
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
        // the cursor on a premade tile: Down to the tile, A opens its band, Down into it
        look().closeSelection()
        hold(r); press(r, "PADDDOWN"); press(r, "PAD1"); repeat(3) { lay(r) }; press(r, "PADDDOWN"); repeat(3) { lay(r) }
        shot("gen_8b_cursor_on_premade")
        r.pad.backToTabs()
        look().closeSelection()
        // the cursor on the Swap screens tile, its band open
        hold(r); press(r, "PADDDOWN"); press(r, "PADDRIGHT"); press(r, "PAD1"); repeat(3) { lay(r) }
        shot("gen_9_cursor_on_open_tile")
        look().closeSelection(); r.pad.backToTabs()
        // the Theme tile and its premades in other themes (the drawn room in each theme's colours)
        for (id in listOf("oled_rose", "dark_silver", "oled_bronze")) {
            Theme.use(id); r = room()
            shot("gen_11_theme_$id")
            tap(r, look(), look().tileBox(GeneralTiles.THEME)!!)
            shot("gen_11_theme_${id}_band")
        }
        Theme.use("classic")
        // a saved report waiting (its note as the label, Open issue)
        r = room()
        ReportFlow.markSaved("/x/report.zip", 1_763_400_000_000L, "https://example.invalid/1")
        shot("gen_15_saved_report", scrollTo = 10_000)
        ReportFlow.clearPending()
        for (l in listOf("es", "fr")) {
            Tr.setChoice(app, l); r = room()
            tap(r, look(), look().tileBox(GeneralTiles.SWAP)!!)
            Settings.swapScreens = true; lay(r)
            shot("gen_12_$l"); shot("gen_12_${l}_account_help", scrollTo = 10_000)
            Settings.swapScreens = false
        }
        Tr.setChoice(app, "auto")
        // the user's ruling 2026-11-17: the Theme tile's real Home miniature (a game document fed), in four themes; the Theme maker's
        // left preview beside it uses the same renderer
        feedStage()
        for (id in listOf("classic", "dark_silver", "oled_bronze", "oled_rose")) {
            Theme.use(id); BottomPictures.drop(); r = room()
            repeat(4) { look().invalidate(); lay(r) }
            shot("gen_16_theme_tile_live_home_$id")
            // the tile alone at 2x (the Thor's own size reads as the page's 1x)
            val t = look().tileBox(GeneralTiles.THEME)!!
            // the part's top in the room (every parent's top up to the room, less the page's scroll)
            var top = 0f; var v: View = look()
            while (v !== r) { top += v.top - v.scrollY; v = v.parent as View }
            val crop = Bitmap.createBitmap(page, t.left.toInt() - 8, (t.top + top).toInt() - 8, t.width().toInt() + 16, t.height().toInt() + 16)
            File(out, "gen_16_theme_tile_live_home_${id}_2x.png").outputStream().use {
                Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Theme.use("classic"); clearStage(); BottomPictures.drop()
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
