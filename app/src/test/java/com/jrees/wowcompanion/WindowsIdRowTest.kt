package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
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
 * WINDOWS ID, THE ROW (the user, 2026-11-14: "build it"; the BLZ51914400 wave), off-device: Settings > General > Account > Windows ID
 * as the room draws it - Per install lit by default, a touch on Original saves it, and while the running game started with the other
 * identity a "Restart the game" row comes under it (the launcher's restart, one press), gone again when the choice matches.
 *
 * With WINID_SHOTS_DIR set it renders General with the row (English, Spanish, French) and with the restart row; THEME_ART_ROOT = an
 * art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WindowsIdRowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var gameUp = false
    private var restarts = 0

    @Before fun up() { WindowsId.forgetLaunchForTest(); gameUp = false; restarts = 0 }
    @After fun down() {
        WindowsId.forgetLaunchForTest()
        runCatching { Settings.windowsId = WindowsId.PER_INSTALL; Settings.windowsIdRan = null }
        Tr.setChoice(app, "auto")
    }

    private fun room(): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app)
        System.getenv("THEME_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        val v = SettingsView(app) { }
        v.loginRow = true
        v.gameRunning = { gameUp }
        v.onRestart = { restarts++ }
        val r = SettingsRoom(app, v) { null }
        // attached to a window as in the app (a touch fires only on a shown view)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val host = android.widget.FrameLayout(act)
        host.addView(r, android.widget.FrameLayout.LayoutParams(W, H))
        act.setContentView(host)
        r.show(SettingsTabs.Tab.GENERAL)
        lay(r)
        return r
    }

    private fun lay(r: View) {
        for (k in 0..2) {
            ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
        }
    }

    private fun account(r: SettingsRoom): SettingsView =
        r.groupBoxes.first { it.group == SettingsTabs.Group.ACCOUNT }.parts.single() as SettingsView

    @Suppress("UNCHECKED_CAST")
    private fun segs(v: SettingsView): Array<RectF> =
        SettingsView::class.java.getDeclaredField("segWinId").apply { isAccessible = true }.get(v) as Array<RectF>

    private fun restartBox(v: SettingsView): RectF =
        SettingsView::class.java.getDeclaredField("btnWinIdRestart").apply { isAccessible = true }.get(v) as RectF

    /** a finger's tap at [b]'s centre, in the slice's own coordinates */
    private fun touch(r: SettingsRoom, v: SettingsView, b: RectF) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY(), 0)
        val upE = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, b.centerX(), b.centerY(), 0)
        v.dispatchTouchEvent(down); v.dispatchTouchEvent(upE)
        down.recycle(); upE.recycle()
        lay(r)
    }

    @Test fun perInstallByDefaultOriginalOnATouch() {
        val r = room()
        val v = account(r)
        val s = segs(v)
        assertTrue("both segments drawn", s.none { it.isEmpty })
        assertEquals(WindowsId.PER_INSTALL, Settings.windowsId)
        // the cursor stops on both segments (the pad presses with a touch at a stop's centre: the same route)
        val stops = v.focusTargets()
        assertTrue(stops.any { it == s[0] } && stops.any { it == s[1] })
        touch(r, v, s[1])
        assertEquals(WindowsId.ORIGINAL, Settings.windowsId)
        // no game: no restart row, the next start uses it
        assertTrue(restartBox(v).isEmpty)
        touch(r, v, segs(v)[0])
        assertEquals(WindowsId.PER_INSTALL, Settings.windowsId)
    }

    @Test fun theRestartRowComesWhileTheGameRunsTheOther() {
        val r = room()
        val v = account(r)
        val h0 = v.height
        gameUp = true
        WindowsId.markLaunched(WindowsId.PER_INSTALL, WindowsId.result(0))
        lay(r)
        assertTrue("same as the running game: no restart row", restartBox(v).isEmpty)
        assertEquals(h0, v.height)
        touch(r, v, segs(v)[1])                                       // Original while the game runs Per install
        assertEquals(WindowsId.ORIGINAL, Settings.windowsId)
        val rb = restartBox(v)
        assertTrue("the restart row", !rb.isEmpty)
        assertTrue("the slice grew by the row", v.height > h0)
        assertTrue("the cursor stops on Restart", v.focusTargets().any { it == rb })
        touch(r, v, rb)
        assertEquals("one restart", 1, restarts)
        // back to Per install: the row goes, nothing to restart for
        touch(r, v, segs(v)[0])
        assertTrue(restartBox(v).isEmpty)
        assertEquals(h0, v.height)
        // the game restarted with Original (the launcher says so): the row matches nothing either way until the choice differs
        Settings.windowsId = WindowsId.ORIGINAL
        WindowsId.markLaunched(WindowsId.ORIGINAL, WindowsId.result(10))
        lay(r)
        assertTrue(restartBox(v).isEmpty)
        // a restart under way: no row
        Settings.windowsId = WindowsId.PER_INSTALL
        SetupBridge.restarting = true
        try { lay(r); assertTrue(restartBox(v).isEmpty) } finally { SetupBridge.restarting = false }
        lay(r)
        assertTrue(!restartBox(v).isEmpty)
    }

    // ---------------------------------------------------------------- the renders (WINID_SHOTS_DIR)

    @Test fun shots() {
        val out = System.getenv("WINID_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        }
        val r = room()
        val sc = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as android.widget.ScrollView
        fun shot(name: String) {
            lay(r); Thread.sleep(250); lay(r)                                // icons decode off the UI thread
            // the page scrolled to General > Account (the row is under the fold of a 1080 px screen)
            sc.scrollTo(0, r.groupBoxes.first { it.group == SettingsTabs.Group.ACCOUNT }.box.top - 12); lay(r)
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("winid_1_general_per_install")
        Settings.windowsId = WindowsId.ORIGINAL
        shot("winid_2_general_original")
        gameUp = true
        WindowsId.markLaunched(WindowsId.PER_INSTALL, WindowsId.result(0))
        shot("winid_3_general_original_restart")
        for (l in listOf("es", "fr")) { Tr.setChoice(app, l); r.show(SettingsTabs.Tab.GENERAL, force = true); shot("winid_4_general_restart_$l") }
        Tr.setChoice(app, "auto")
    }
}
