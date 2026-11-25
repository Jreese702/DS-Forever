package com.jrees.wowcompanion

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.After
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
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * BATTLE.NET SIGN-IN ON THE START SCREEN'S MENU (the user, 2026-11-24: "Maybe we add a battlenet sign in setting to the menu and we can
 * advise people with other login errors to try it?"), as drawn and pressed on the 1240 x 1080 bottom screen: the button beside Region on
 * both menus (the waiting screen's in thirds with Exit game), a press switches Settings' own value (the Settings row shows it, and the
 * other way round), the pad reaches it on the chooser, a touch presses it on the waiting screen, and while the running game signed in
 * the other way the Restart game line comes - nothing restarts by itself.
 *
 * PNGs when SIGNIN_SHOTS_DIR is set (SIGNIN_ART_ROOT = an art root laid out like the app's): both menus with each value, in English,
 * Spanish and French; the advice line under an error; the Restart game line; the Settings row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInMenuRoomTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("SIGNIN_SHOTS_DIR")?.let { File(it) }
    private var gameUp = false
    private var restarts = 0
    private val rootField = ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }
    private var rootBefore: Any? = null
    /** the windows' roots this test made: emptied after it, so no Settings room stays a viewer of the Theme tile's pictures
     *  (BottomPictures.shown) in the next class's tests */
    private val roots = ArrayList<FrameLayout>()

    @Before fun up() {
        // a fresh app per test: Settings' prefs are this test's (the launcher's own file, read below)
        Settings::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(Settings, null)
        Settings.init(app)
        StartMenu.canExit = { true }
        StartMenu.canRestart = { gameUp }
        Settings.browserLogin = false
        BrowserLogin.forgetLaunchForTest()
        gameUp = false; restarts = 0
        rootBefore = rootField.get(null)
        System.getenv("SIGNIN_ART_ROOT")?.let { root -> rootField.set(null, File(root)) }
        Tr.init(app)
        listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }
            .firstOrNull { it.isDirectory }?.let { dir -> for (l in listOf("es", "fr")) Tr.load(l, File(dir, "$l.json").readText()) }
    }

    @After fun down() {
        StartMenu.open = false; StartMenu.onSignIn = null; StartMenu.onRestart = null
        StartMenu.canExit = { false }; StartMenu.canRestart = { false }
        runCatching { Settings.browserLogin = false }
        BrowserLogin.forgetLaunchForTest()
        SetupBridge.restartGame = null
        SetupBridge.publish(null)
        Tr.setChoice(app, "auto")
        ModeBridge.reset()
        rootField.set(null, rootBefore)                                      // no art root leaks into the next class's tests
        for (h in roots) h.removeAllViews()
        roots.clear()
    }

    /** the prefs the launcher reads at each game start (BattleNetBrowserLogin): the one saved value */
    private fun saved(): Boolean = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean(BrowserLogin.KEY, false)

    // ---------------------------------------------------------------- the Menu drawn in the hosts' areas

    private fun area(game: Boolean): Pair<RectF, RectF> = if (game) {
        val top = 1080f - StartView.ROW_DOWN - StartView.BUTTON_H
        val left = (1240f - StartMenu.WIDTH) / 2f
        RectF(left, StartView.GAP, left + StartMenu.WIDTH, top - StartView.GAP) to
            RectF(StartView.SIDE_M, top, StartView.SIDE_M + StartView.SIDE_W, top + StartView.BUTTON_H)
    } else {
        val footY = 1080f - ChooserPanel.MARGIN - ChooserPanel.BUTTON_H
        RectF(ChooserPanel.MARGIN, ChooserPanel.MARGIN + 110f, 1240f - ChooserPanel.MARGIN, footY - ChooserPanel.GAP * 2) to
            RectF(ChooserPanel.MARGIN, footY, ChooserPanel.MARGIN + 220f, footY + ChooserPanel.BUTTON_H)
    }

    private fun menu(name: String?, game: Boolean, pressed: Int = -1, focus: Int? = null): List<Pair<RectF, Int>> {
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 24, 32))
        val hits = ArrayList<Pair<RectF, Int>>()
        val (a, x) = area(game)
        StartMenu.draw(c, a, pressed, hits, x, game)
        focus?.let { f -> hits.firstOrNull { it.second == f } }?.let { (r, _) -> UiKit.focus(c, RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f)) }
        if (name != null) out?.let { it.mkdirs(); File(it, "$name.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        for ((r, id) in hits) if (id != 12) assertTrue("$name: button $id at $r leaves $a", r.top >= a.top - 0.5f && r.bottom <= a.bottom + 0.5f)
        return hits
    }

    @Test fun theButtonSitsBesideRegionOnBothMenus() {
        for (game in listOf(true, false)) {
            val hits = menu(null, game)
            val sign = hits.single { it.second == StartMenu.SIGNIN }.first
            val region = hits.single { it.second == StartMenu.REGION }.first
            assertEquals("game=$game: one row with Region", region.top, sign.top, 0.5f)
            assertTrue("game=$game: right of Region", sign.left > region.right)
            assertTrue("game=$game: touch size", sign.height() >= StartView.BUTTON_H - 0.5f && sign.width() >= 280f)
            // nothing overlaps (the X is the Menu button's own place)
            val rects = hits.filter { it.second != 12 }.map { it.first }
            for (i in rects.indices) for (j in i + 1 until rects.size) assertFalse("game=$game: ${rects[i]} / ${rects[j]}", RectF.intersects(rects[i], rects[j]))
            if (game) {
                val exit = hits.single { it.second == 7 }.first
                assertEquals("Exit game stays on the row's right", sign.top, exit.top, 0.5f)
                assertTrue(exit.left > sign.right)
            }
        }
    }

    // ---------------------------------------------------------------- one setting

    @Test fun theMenuAndTheSettingsRowAreOneSetting() {
        var told: Boolean? = null
        StartMenu.onSignIn = { told = it }
        StartMenu.open = true
        StartMenu.press(StartMenu.SIGNIN)
        assertTrue("Blizzard's page saved", Settings.browserLogin && saved())
        assertEquals(true, told)
        assertTrue("the Menu stays (as ABXY and Glyphs)", StartMenu.open)
        assertEquals("Blizzard's page", BrowserLogin.words(Settings.browserLogin))
        // Settings' row: the Saved login segment switches the same value back
        val r = room()
        val v = account(r)
        val segs = v.controlBoxes(GeneralTiles.Row.BROWSER_LOGIN)
        assertEquals(2, segs.size)
        touch(r, v, segs[0])
        assertFalse(Settings.browserLogin || saved())
        assertEquals("Saved login", BrowserLogin.words(Settings.browserLogin))
        touch(r, v, v.controlBoxes(GeneralTiles.Row.BROWSER_LOGIN)[1])
        assertTrue(Settings.browserLogin)
        StartMenu.press(StartMenu.SIGNIN)
        assertFalse("and the Menu again", Settings.browserLogin || saved())
        assertEquals(false, told)
    }

    @Test fun aChangeWhileTheGameRunsAsksForARestartAndNeverRestarts() {
        StartMenu.onRestart = { restarts++ }
        SetupBridge.restartGame = { restarts++ }
        val r = room()
        val v = account(r)
        gameUp = true
        BrowserLogin.markLaunched(false)                                    // this run signed in with the saved login
        lay(r)
        assertTrue("no change: no restart row", v.controlBoxes(GeneralTiles.Row.WINDOWS_RESTART).isEmpty())
        StartMenu.open = true
        StartMenu.press(StartMenu.SIGNIN)                                   // Blizzard's page from the Menu
        lay(r)
        assertEquals("nothing restarts by itself", 0, restarts)
        assertTrue(BrowserLogin.showRestart(gameUp, false, BrowserLogin.launched, Settings.browserLogin))
        assertEquals(BrowserLogin.RESTART_PAGE, hint())
        assertTrue("Settings' Restart the game row too", v.controlBoxes(GeneralTiles.Row.WINDOWS_RESTART).isNotEmpty())
        // the Menu's own Restart game is the way (the same restart as Settings')
        val hits = menu(null, true)
        assertTrue(hits.any { it.second == 10 })
        StartMenu.press(StartMenu.SIGNIN)                                   // back to the saved login: nothing to restart for
        lay(r)
        assertEquals(null, hint())
        assertTrue(v.controlBoxes(GeneralTiles.Row.WINDOWS_RESTART).isEmpty())
        assertEquals(0, restarts)
    }

    private fun hint() = BrowserLogin.startHint(false, null, false, Settings.browserLogin, false, true, gameUp, BrowserLogin.launched, Settings.browserLogin)

    // ---------------------------------------------------------------- the pad (chooser) and a touch (waiting screen)

    @Test fun thePadReachesItOnTheChooser() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root); roots += root
        ModeBridge::class.java.getDeclaredField("route").apply { isAccessible = true }.set(ModeBridge, StartMode.Route.Chooser)
        SetupBridge.publish(SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
            art = SetupFlow.Art.READY, addonsInstalled = true, client = "1.60.1.70685 (up to date)"))
        val p = ChooserPanel(act)
        root.addView(p, FrameLayout.LayoutParams(1240, 1080))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, p.visibility)
        StartMenu.open = true
        fun draw() {
            p.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            p.layout(0, 0, 1240, 1080)
            p.draw(Canvas(Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)))
        }
        val cursor = ChooserPanel::class.java.getDeclaredField("menuCursor").apply { isAccessible = true }
        draw()
        assertEquals("the cursor starts on Auto login", 4, cursor.getInt(p))
        // down to the bottom row (Region), then right onto the sign-in
        for (k in 0 until 6) { if (cursor.getInt(p) == StartMenu.REGION) break; p.key(KeyEvent.KEYCODE_DPAD_DOWN, true); draw() }
        assertEquals(StartMenu.REGION, cursor.getInt(p))
        p.key(KeyEvent.KEYCODE_DPAD_RIGHT, true); draw()
        assertEquals(StartMenu.SIGNIN, cursor.getInt(p))
        p.key(KeyEvent.KEYCODE_BUTTON_A, true); draw()
        assertTrue("A switched it", Settings.browserLogin)
        assertEquals("the cursor stays on it", StartMenu.SIGNIN, cursor.getInt(p))
        p.key(KeyEvent.KEYCODE_BUTTON_A, true); draw()
        assertFalse(Settings.browserLogin)
        p.key(KeyEvent.KEYCODE_DPAD_LEFT, true); draw()
        assertEquals(StartMenu.REGION, cursor.getInt(p))
    }

    @Test fun aTouchPressesItOnTheWaitingScreen() {
        val v = startView()
        v.menuOpen = true
        lay(v)
        val (a, x) = area(true)
        val hits = ArrayList<Pair<RectF, Int>>()
        StartMenu.draw(Canvas(Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)), a, -1, hits, x, true)
        val b = hits.single { it.second == StartMenu.SIGNIN }.first
        tap(v, b.centerX(), b.centerY())
        assertTrue(Settings.browserLogin)
        assertTrue("the Menu stays", v.menuOpen)
        tap(v, b.centerX(), b.centerY())
        assertFalse(Settings.browserLogin)
    }

    // ---------------------------------------------------------------- the renders (SIGNIN_SHOTS_DIR)

    @Test fun shots() {
        val d = out ?: return
        d.mkdirs()
        StartMenu.region = { "us" }
        // the kit's pieces decode on first use: one warm-up draw first, so every shot has the real art
        menu(null, true); menu(null, false); Thread.sleep(400); ShadowLooper.idleMainLooper(); menu(null, true)
        for (lang in listOf("en", "es", "fr")) {
            Tr.setChoice(app, if (lang == "en") "auto" else lang)
            Settings.browserLogin = false
            menu("menu_game_saved_$lang", true)
            menu("menu_chooser_saved_$lang", false, focus = StartMenu.SIGNIN)
            Settings.browserLogin = true
            menu("menu_game_page_$lang", true)
            menu("menu_chooser_page_$lang", false, focus = StartMenu.SIGNIN)
        }
        Tr.setChoice(app, "auto")
        Settings.browserLogin = false
        menu("menu_game_saved_pressed_en", true, pressed = StartMenu.SIGNIN)
        StartMenu.region = { "tw" }
        menu("menu_game_saved_taiwan_en", true)
        StartMenu.region = { "us" }

        // the start screen: the advice under an error (the saved login, a refusal it could fix)
        fun screen(name: String, line: String, hint: String?, login: StartView.Login = StartView.Login.NONE, menuOpen: Boolean = false) {
            LoadingScene.pin(4.0f, 11L, 1)
            val v = startView()
            v.line = line; v.hint = hint; v.login = login
            v.menuOpen = menuOpen
            lay(v)
            val bm = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bm))
            File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            v.menuOpen = false
        }
        val refused = HomeLogin.reason("Unable to connect. (BLZ51901016)")
        assertTrue(BrowserLogin.advise(true, refused, false, false))
        for (lang in listOf("en", "es", "fr")) {
            Tr.setChoice(app, if (lang == "en") "auto" else lang)
            screen("start_advice_$lang", BrowserLogin.failedLine(refused), BrowserLogin.ADVICE_LINE)
            // after the switch the error's row goes (the advice is for the saved login only) and the line before it comes back
            screen("start_restart_page_$lang", "Waiting for the game.", BrowserLogin.RESTART_PAGE)
        }
        Tr.setChoice(app, "auto")
        screen("start_advice_menu_open_en", BrowserLogin.failedLine(refused), BrowserLogin.ADVICE_LINE, menuOpen = true)
        screen("start_restart_saved_en", "Waiting for the game.", BrowserLogin.RESTART_SAVED)
        // the Menu right after Blizzard's page was picked while the game runs: its value, the Restart game line under the error
        Settings.browserLogin = true; gameUp = true
        screen("start_page_menu_open_restart_en", "Waiting for the game.", BrowserLogin.RESTART_PAGE, menuOpen = true)
        Settings.browserLogin = false; gameUp = false
        // BLZ51914400 keeps its button and its line, no advice under it (BrowserLogin.offer)
        screen("start_blz14400_button_unchanged_en", BrowserLogin.OFFER_LINE, null, StartView.Login.BROWSER)

        // Settings > General > Account: the row's new words, each value
        val r = room()
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        fun settings(name: String) {
            lay(r); Thread.sleep(250); lay(r)
            sc.scrollTo(0, r.groupBoxes.first { it.group == SettingsTabs.Group.ACCOUNT }.box.top - 12); lay(r)
            val bm = Bitmap.createBitmap(1240, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm); c.drawColor(Theme.ink); r.draw(c)
            File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        settings("settings_saved_en")
        Settings.browserLogin = true
        settings("settings_page_en")
        gameUp = true
        BrowserLogin.markLaunched(false)
        settings("settings_page_restart_en")
        for (l in listOf("es", "fr")) { Tr.setChoice(app, l); r.show(SettingsTabs.Tab.GENERAL, force = true); settings("settings_page_restart_$l") }
        Tr.setChoice(app, "auto")
    }

    // ---------------------------------------------------------------- helpers

    private fun startView(): StartView {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(act); roots += host
        val v = StartView(act)
        host.addView(v, FrameLayout.LayoutParams(1240, 1080))
        act.setContentView(host)
        return v
    }

    private fun tap(v: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val upE = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0)
        v.dispatchTouchEvent(down); v.dispatchTouchEvent(upE)
        down.recycle(); upE.recycle()
        lay(v)
    }

    private fun room(): SettingsRoom {
        SettingsTabs.init(app)
        val v = SettingsView(app) { }
        v.loginRow = true
        v.gameRunning = { gameUp }
        v.onRestart = { restarts++ }
        val r = SettingsRoom(app, v) { null }
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(act); roots += host
        host.addView(r, FrameLayout.LayoutParams(1240, 1080 - Strip.HEIGHT))
        act.setContentView(host)
        r.show(SettingsTabs.Tab.GENERAL)
        lay(r)
        return r
    }

    private fun account(r: SettingsRoom): GeneralTilesView =
        r.groupBoxes.first { it.group == SettingsTabs.Group.ACCOUNT }.parts.single() as GeneralTilesView

    private fun touch(r: SettingsRoom, v: View, b: RectF) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY(), 0)
        val upE = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, b.centerX(), b.centerY(), 0)
        v.dispatchTouchEvent(down); v.dispatchTouchEvent(upE)
        down.recycle(); upE.recycle()
        lay(r)
    }

    private fun lay(v: View) {
        val w = 1240; val h = if (v is SettingsRoom) 1080 - Strip.HEIGHT else 1080
        for (k in 0..2) {
            ShadowLooper.idleMainLooper()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            v.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        }
    }
}
