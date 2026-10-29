package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE LOADING REVEAL'S OFF-DEVICE RENDER CHECK (2026-10-28; no emulator, no device): the top screen (LoadingTopView, the checks
 * centred) and the bottom pages (SetupPanel during the checks, ChooserPanel once they pass) drawn by Robolectric at the same moments of
 * the shared scene, each pair saved as one picture of the device (top over bottom) to compare with the design
 * (wow-companion-design/loading/loading_final_30fps.mp4).
 *
 * Runs only when LOADING_SHOTS_DIR is set; PARTY_ART_ROOT = an art root laid out like the app's (ui/kit with the scene pictures and
 * home_swirl, fonts), e.g. E:/wow-build/runtime-art/r1/files.
 *   LOADING_SHOTS_DIR=E:/wow-build/loading-app/shots PARTY_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*LoadingShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingShotsTest {
    private val out: File? = System.getenv("LOADING_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("PARTY_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun route(r: StartMode.Route?) {
        ModeBridge::class.java.getDeclaredField("route").apply { isAccessible = true }.set(ModeBridge, r)
    }

    private fun render(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    /** top over bottom, as the device holds them (the bottom centred under the top, the hinge between) */
    private fun device(top: Bitmap, bottom: Bitmap, name: String) {
        val d = out ?: return
        val b = Bitmap.createBitmap(1920, 1080 + 140 + 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(40, 40, 44))
        c.drawBitmap(top, 0f, 0f, null)
        c.drawBitmap(bottom, (1920 - 1240) / 2f, 1080f + 140f, null)
        val s = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        File(d, "$name.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val checking = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
        art = SetupFlow.Art.RUNNING, artPercent = 42, addonsInstalled = true, client = "1.60.1.70634 (up to date)",
        appLine = "DS Forever 1.0.13-party2: up to date")
    private val passed = checking.copy(art = SetupFlow.Art.READY)
    private val broken = checking.copy(folderFound = false, art = SetupFlow.Art.IDLE)

    private fun pair(name: String, t: Float, inputs: SetupFlow.Inputs?, r: StartMode.Route) {
        LoadingScene.pin(t, 11L, 1)
        route(r)
        SetupBridge.publish(inputs)
        val ctx = RuntimeEnvironment.getApplication()
        val top = render(LoadingTopView(ctx), 1920, 1080)
        val chooser = ChooserPanel(ctx).also { it.update() }
        val setup = SetupPanel(ctx).also { it.update() }
        // the bottom as the device stacks it: the chooser over the setup over the start screen (before anything is published)
        val bottom = when {
            chooser.visibility == View.VISIBLE -> render(chooser, 1240, 1080)
            setup.visibility == View.VISIBLE -> render(setup, 1240, 1080)
            else -> render(StartView(ctx).also { it.line = "Waiting for the game." }, 1240, 1080)
        }
        device(top, bottom, name)
    }

    @Test fun shots() {
        out?.mkdirs() ?: return
        artRoot()
        try {
            pair("L0_nothing_published_loading", 0.6f, null, StartMode.Route.Chooser)
            pair("L1_start_checking", 0.4f, checking, StartMode.Route.Chooser)
            pair("L2_spark_travels", 2.2f, checking, StartMode.Route.Chooser)
            pair("L3_checks_passed_chooser", 4.0f, passed, StartMode.Route.Chooser)
            pair("L4_handover", LoadingMath.EVERY + 0.6f, passed, StartMode.Route.Chooser)
            pair("L5_next_picture", LoadingMath.EVERY + 3.0f, passed, StartMode.Route.Chooser)
            pair("L6_folder_missing", 3.0f, broken, StartMode.Route.Chooser)
        } finally {
            SetupBridge.publish(null); ModeBridge.reset()
        }
    }
}
