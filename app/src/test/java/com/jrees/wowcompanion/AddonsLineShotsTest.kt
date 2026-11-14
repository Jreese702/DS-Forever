package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
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
 * THE START SCREEN'S ADD-ONS WORD (the add-ons-silent triage, 2026-11-14): the start screen still up after the sign-in says "Waiting for
 * the world."; once the game's memory says the world with no word from the add-ons, the line's panel says what Home's "add-ons aren't
 * running" card would (its text, a cause when one is known) with How (touch only) in the row's place; How shows the steps. Drawn on the
 * 1240 x 1080 bottom screen. PNGs when ADDONS_SHOTS_DIR is set; PARTY_ART_ROOT = an art root laid out like the app's
 * (E:/wow-build/runtime-art/r1/files).
 *   ADDONS_SHOTS_DIR=<dir> PARTY_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*AddonsLineShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddonsLineShotsTest {
    private val out: File? = System.getenv("ADDONS_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("PARTY_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun render(v: View, name: String): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1240, 1080)
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        out?.let { d -> d.mkdirs(); File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return b
    }

    private fun tap(v: View, x: Float, y: Float) {
        v.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0))
        v.onTouchEvent(MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun lang(l: String) {
        val app = RuntimeEnvironment.getApplication()
        if (l != "en") listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    @Test fun shots() {
        artRoot()
        LoadingScene.pin(4.0f, 11L, 1)
        val gameUp = LauncherHooks.gameUp
        LauncherHooks.gameUp = { true }
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                val v = StartView(RuntimeEnvironment.getApplication())
                v.line = AddonSilence.WORLD_LINE
                render(v, "A1_waiting_for_the_world_$l")
                var how = 0
                v.onHow = { how++ }
                v.addons = ReportNotice.card(ReportNotice.State.AddonsOff(null))
                assertTrue(v.addonsShown)
                render(v, "A2_addons_not_running_$l")
                // How, touch only, where the sign-in row would be: centred over the line's panel
                val y = 1080f - StartView.ROW_DOWN - StartView.BUTTON_H - StartView.GAP - StartView.BUTTON_H / 2f
                tap(v, 620f, y)
                assertEquals(1, how)
                v.addons = ReportNotice.card(ReportNotice.State.AddonsOff(null, how = true))
                render(v, "A3_addons_how_$l")
                tap(v, 620f, y)
                assertEquals("no How once the steps show", 1, how)
                v.addons = ReportNotice.card(ReportNotice.State.AddonsOff(AddonSilence.offCause(listOf("WowCompanionSpells:DISABLED"))))
                render(v, "A4_addons_cause_spells_off_$l")
                v.addons = ReportNotice.card(ReportNotice.State.AddonsOff("The game loads an old private copy of the add-ons. Restart the game from DS Forever."))
                render(v, "A5_addons_cause_private_copy_$l")
                // another line (a reload's) always shows: the word only over a plain waiting line
                v.line = "Reloading the game's interface"
                assertFalse(v.addonsShown)
                render(v, "A6_reload_line_wins_$l")
            }
        } finally {
            LauncherHooks.gameUp = gameUp
            Tr.setChoice(RuntimeEnvironment.getApplication(), "auto")
        }
    }
}
