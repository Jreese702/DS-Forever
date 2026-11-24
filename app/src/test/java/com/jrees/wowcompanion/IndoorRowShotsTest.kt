package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * D1, DRAWN (the user's pick, 2026-11-23: "Done when all mappable built" - "Shows 'Finished: 1,632 maps, 221 places can't be mapped',
 * the row turns green"): the start screen's top box with the indoor maps' row while they build (optional) and finished (OK, green), in
 * English, Spanish and French; the finished row fits the box's row on one line. PNGs when INDOOR_ROW_SHOTS_DIR is set; PARTY_ART_ROOT =
 * an art root laid out like the app's (the game's fonts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IndoorRowShotsTest {
    private val out: File? = System.getenv("INDOOR_ROW_SHOTS_DIR")?.let { File(it) }

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
        art = SetupFlow.Art.READY, addonsInstalled = true, client = "1.60.1.70685 (up to date)", maps = true,
        appLine = "DS Forever 1.0.40: up to date", iconsLine = "Icons: all icons ready")

    @Test fun theFinishedRowIsGreenInEveryLanguage() {
        artRoot()
        LoadingScene.pin(LoadingMath.EVERY + 2.0f, 11L, 1)
        val app = RuntimeEnvironment.getApplication()
        val room = LoadingTopView.PANEL_W - (LoadingTopView.ROW_X + 64f + 60f)
        val p = UiKit.bodyPaint(32f, Theme.text)
        val building = base.copy(indoor = "1616/1853 ready - Builds when minimized or screen off")
        val finished = base.copy(indoor = "Finished: 1,632 maps, 221 places can't be mapped", indoorDone = true)
        try {
            for (l in listOf("en", "es", "fr")) {
                lang(l)
                for ((inputs, name) in listOf(building to "I1_indoor_building", finished to "I2_indoor_finished")) {
                    SetupBridge.publish(inputs)
                    val row = SetupFlow.terse(SetupFlow.checks(inputs)).first { it.label.startsWith("Indoor") }
                    assertEquals(if (inputs.indoorDone) SetupFlow.Mark.OK else SetupFlow.Mark.OPTIONAL, row.mark)
                    val w = p.measureText(Tr.t(row.label))
                    assertTrue("$l: \"${Tr.t(row.label)}\" is $w px, the row has $room", w <= room)
                    if (l != "en" && inputs.indoorDone) assertTrue("$l: translated", Tr.t(row.label) != row.label)
                    render(LoadingTopView(app), "${name}_$l")
                }
                // the full words (Settings, the problem report) translate too
                val full = SetupFlow.checks(finished).first { it.label.startsWith("Indoor") }.label
                assertEquals("Indoor maps finished: 1,632 maps, 221 places can't be mapped", full)
                if (l != "en") assertTrue("$l: ${Tr.t(full)}", Tr.t(full) != full && Tr.t(full).contains("1,632") && Tr.t(full).contains("221"))
            }
        } finally {
            SetupBridge.publish(null); lang("en")
        }
    }
}
