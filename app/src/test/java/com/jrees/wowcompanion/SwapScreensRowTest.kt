package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * GitHub #232 SWAP SCREENS' SETTINGS ROW, off-device (the user, 2026-11-14: build it, untested): General > Look > Swap screens, On / Off,
 * only with a second screen; a change brings Restart DS Forever under it (its button with no game, "After the game closes" with the
 * game up), and the button is the launcher's restart (ScreenSwap.restart).
 *
 * With SWAP_SHOTS_DIR set it renders the General tab in each state (THEME_ART_ROOT = an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwapScreensRowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var game = false
    private var restarts = 0

    @After fun clean() {
        Settings.swapScreens = false
        ScreenSwap.offered = false; ScreenSwap.restart = null; ScreenSwap.take(false)
        Tr.setChoice(app, "auto")
    }

    private fun room(offered: Boolean, saved: Boolean, inUse: Boolean): Pair<SettingsRoom, SettingsView> {
        Settings.init(app); SettingsTabs.init(app)
        System.getenv("THEME_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        ScreenSwap.offered = offered
        ScreenSwap.take(inUse)
        ScreenSwap.restart = { restarts++ }
        Settings.swapScreens = saved
        val v = SettingsView(app) { }
        v.loginRow = true
        v.gameRunning = { game }
        // in a window, as on the device (a tap fires only on a shown view)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val r = SettingsRoom(act, v) { null }
        act.setContentView(r)
        r.show(SettingsTabs.Tab.GENERAL)
        lay(r)
        return r to look(r)
    }

    private fun look(r: SettingsRoom) = r.groupBoxes.first { it.group == SettingsTabs.Group.LOOK }.parts.single() as SettingsView

    private fun lay(r: View, w: Int = 1240, h: Int = 1080 - Strip.HEIGHT) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, w, h)
            r.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun segs(s: SettingsView) = SettingsView::class.java.getDeclaredField("segSwap").apply { isAccessible = true }.get(s) as Array<RectF>
    private fun restartBtn(s: SettingsView) = SettingsView::class.java.getDeclaredField("btnSwapRestart").apply { isAccessible = true }.get(s) as RectF

    private fun tap(s: SettingsView, r: RectF) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0)
        val up = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0)
        s.dispatchTouchEvent(down); s.dispatchTouchEvent(up)
    }

    /** a single-screen device: no row (the Look group keeps Theme and Language) */
    @Test fun hiddenWithoutASecondScreen() {
        val (r, single) = room(offered = false, saved = false, inUse = false)
        val h1 = single.measuredHeight
        assertTrue(segs(single).all { it.isEmpty })
        assertEquals(View.VISIBLE, r.groupBoxes.first { it.group == SettingsTabs.Group.LOOK }.box.visibility)
        val (_, two) = room(offered = true, saved = false, inUse = false)
        assertEquals("one row more with a second screen", 96 + 10, two.measuredHeight - h1)
        assertTrue(segs(two).none { it.isEmpty })
        // the cursor stops on both segments
        val targets = two.focusTargets()
        for (sg in segs(two)) assertTrue(targets.any { it == sg })
    }

    /** the Thor: Off, no Restart; On saves, Restart comes; its button is the launcher's restart; Off again: Restart goes */
    @Test fun onSavesAndAsksForARestart() {
        val (r, s) = room(offered = true, saved = false, inUse = false)
        val h0 = s.measuredHeight
        assertTrue(restartBtn(s).isEmpty)
        tap(s, segs(s)[0])                                                    // On
        assertTrue(Settings.swapScreens)
        lay(r)
        assertEquals("Restart under the row", 96 + 10, s.measuredHeight - h0)
        val b = RectF(restartBtn(s))
        assertFalse(b.isEmpty)
        assertTrue(s.focusTargets().any { it == b })
        tap(s, b)
        assertEquals(1, restarts)
        // Off again (the one in use): nothing waits, the row alone
        lay(r)
        tap(s, segs(s)[1])
        assertFalse(Settings.swapScreens)
        lay(r)
        assertEquals(h0, s.measuredHeight)
        assertTrue(restartBtn(s).isEmpty)
    }

    /** the game up: "After the game closes", no button (a restart would end the game) */
    @Test fun noRestartWhileTheGameRuns() {
        val h0 = room(offered = true, saved = false, inUse = false).second.measuredHeight
        game = true
        val (_, s) = room(offered = true, saved = true, inUse = false)
        assertEquals("the row says so", 96 + 10, s.measuredHeight - h0)
        assertTrue(restartBtn(s).isEmpty)
        assertEquals(0, restarts)
    }

    @Test fun shots() {
        val out = System.getenv("SWAP_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        fun shot(name: String, r: SettingsRoom) {
            lay(r); Thread.sleep(250); lay(r)                               // icons decode off the UI thread
            val b = Bitmap.createBitmap(1240, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("swap_1_single_screen_hidden", room(offered = false, saved = false, inUse = false).first)
        shot("swap_2_thor_off", room(offered = true, saved = false, inUse = false).first)
        shot("swap_3_on_restart", room(offered = true, saved = true, inUse = false).first)
        shot("swap_4_on_in_use", room(offered = true, saved = true, inUse = true).first)
        game = true
        shot("swap_5_on_game_running", room(offered = true, saved = true, inUse = false).first)
        game = false
        for (l in listOf("es", "fr")) {
            listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
            Tr.setChoice(app, l)
            shot("swap_6_on_restart_$l", room(offered = true, saved = true, inUse = false).first)
        }
        Tr.setChoice(app, "auto")
    }
}
