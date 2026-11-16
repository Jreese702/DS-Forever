package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE START SCREEN'S MAPS LINE, DRAWN (the user's pick, 2026-11-16, #284): the top box with "Maps: build while the game is closed",
 * then "Maps: ready", under the game art, in English, Spanish and French; each fits the box's row on one line. PNGs when
 * MAPS_SHOTS_DIR is set; PARTY_ART_ROOT = an art root laid out like the app's (the game's fonts).
 *   MAPS_SHOTS_DIR=<dir> PARTY_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*MapsLineShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsLineShotsTest {
    private val out: File? = System.getenv("MAPS_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("PARTY_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun lang(l: String) {
        val app = RuntimeEnvironment.getApplication()
        if (l != "en") listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    private fun render(v: View, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1920, 1080)
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        out?.let { d -> d.mkdirs(); File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private val base = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
        art = SetupFlow.Art.READY, addonsInstalled = true, client = "1.60.1.70685 (up to date)",
        appLine = "DS Forever 1.0.33: up to date", iconsLine = "Icons: all icons ready")

    @Test fun theMapsRowInEveryLanguage() {
        artRoot()
        LoadingScene.pin(LoadingMath.EVERY + 2.0f, 11L, 1)
        val app = RuntimeEnvironment.getApplication()
        // the row's room in the box at 1080 px (LoadingTopView.drawPanel: a waiting / OK row is one line, never wrapped)
        val room = LoadingTopView.PANEL_W - (LoadingTopView.ROW_X + 64f + 60f)
        val p = UiKit.bodyPaint(32f, Theme.text)
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                for ((maps, name) in listOf(false to "M1_maps_building", true to "M2_maps_ready")) {
                    SetupBridge.publish(base.copy(maps = maps))
                    val row = SetupFlow.terse(SetupFlow.checks(base.copy(maps = maps))).first { it.label.startsWith("Maps") }
                    val w = p.measureText(Tr.t(row.label))
                    assertTrue("$l: \"${Tr.t(row.label)}\" is $w px, the row has $room", w <= room)
                    if (l != "en") assertTrue("$l: translated", Tr.t(row.label) != row.label)
                    render(LoadingTopView(app), "${name}_$l")
                }
            }
        } finally {
            SetupBridge.publish(null); lang("en")
        }
    }
}
