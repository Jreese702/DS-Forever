package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE START SCREEN'S REGION LINE (the user's pick 2026-11-13, "Show region when it differs"; #248) drawn on the 1240 x 1080 bottom
 * screen: waiting, signing in, with the first-time sign-in row, in Spanish and French, and without a line. PNGs only when
 * REGION_SHOTS_DIR is set; REGION_ART_ROOT = an art root laid out like the app's (e.g. E:/wow-build/runtime-art/r1/files).
 *   REGION_SHOTS_DIR=<dir> REGION_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*RegionLineShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegionLineShotsTest {
    private val out: File? = System.getenv("REGION_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("REGION_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun shot(name: String, line: String, region: GameRegion.Note?, login: StartView.Login = StartView.Login.NONE) {
        val d = out ?: return
        LoadingScene.pin(4.0f, 11L, 1)
        val v = StartView(RuntimeEnvironment.getApplication()).also {
            it.line = line; it.region = region; it.login = login; it.firstLabel = "Save your Battle.net login"
        }
        v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1240, 1080)
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        out?.mkdirs() ?: return
        artRoot()
        val ctx = RuntimeEnvironment.getApplication()
        Tr.init(ctx)
        // the words as the app ships them (in case the test's assets lack them)
        listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }
            .firstOrNull { it.isDirectory }?.let { dir -> for (l in listOf("es", "fr")) Tr.load(l, File(dir, "$l.json").readText()) }
        SetupBridge.openPcKeyboard = {}                    // the local game's waiting screen: Menu | line | Keyboard
        try {
            val eu = GameRegion.note("eu", "us")
            shot("R1_waiting_eu_on_us", "Waiting for the game.", eu)
            shot("R2_signing_in_eu_on_us", "Signing in…", eu)
            shot("R3_first_row_kr_on_us", "Save it once and the game signs in by itself.", GameRegion.note("kr", "us"), StartView.Login.FIRST)
            shot("R4_same_region_no_line", "Waiting for the game.", GameRegion.note("us", "us"))
            Tr.setChoice(ctx, "es")
            shot("R5_es_kr_on_us", "Waiting for the game.", GameRegion.note("kr", "us"))
            Tr.setChoice(ctx, "fr")
            shot("R6_fr_tw_on_eu", "Waiting for the game.", GameRegion.note("tw", "eu"))
        } finally {
            Tr.setChoice(ctx, "auto"); SetupBridge.openPcKeyboard = null
        }
    }
}
