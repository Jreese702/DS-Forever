package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * v8's pictures (2026-10-28, after the Thor test): the top box with its bars, the "Game update needed" pop-up after A on Update (pushed
 * in, then waiting), the chooser with the selection wearing A (Local boxed, Streaming boxed), the v8 reveal.
 *   LOADING_SHOTS_DIR=E:/wow-build/loading-app/shots-v8 PARTY_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*LoadingV8ShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingV8ShotsTest {
    private val out: File? = System.getenv("LOADING_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("PARTY_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
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

    private fun pad(code: Int) {
        val t = SystemClock.uptimeMillis()
        StartKeys.route(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, 1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        StartKeys.route(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, 0, 1, 0, 0, InputDevice.SOURCE_GAMEPAD))
    }

    private val base = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
        art = SetupFlow.Art.READY, addonsInstalled = true, client = "1.60.1.70685 (up to date)",
        appLine = "DS Forever 1.0.13-party5: up to date")

    @Test fun shots() {
        out?.mkdirs() ?: return
        artRoot()
        val ctx = RuntimeEnvironment.getApplication()
        try {
            route(StartMode.Route.Chooser)
            // 1. the bars: the art running, the background icons, the game's update with a counted step
            LoadingScene.pin(2.0f, 11L, 1)
            val bars = base.copy(art = SetupFlow.Art.RUNNING, artPercent = 42, iconsLine = "Icons: 250 / 1000 (background)")
            SetupBridge.publish(bars)
            device(render(LoadingTopView(ctx), 1920, 1080), render(SetupPanel(ctx).also { it.update() }, 1240, 1080), "V1_bars_art_icons")
            val updating = base.copy(client = "1.60.1.70658 (1.60.1.70685 needed)", gameOutdated = "1.60.1.70685", updating = true,
                busy = "Downloading data.012 (3/10)...")
            SetupBridge.publish(updating)
            device(render(LoadingTopView(ctx), 1920, 1080), render(SetupPanel(ctx).also { it.update() }, 1240, 1080), "V2_bar_game_update")
            // 2. "Game update needed": A on Update (the device's route), pushed in, then waiting while it is queued
            val outdated = base.copy(client = "1.60.1.70658 (1.60.1.70685 needed)", gameOutdated = "1.60.1.70685",
                iconsLine = "Icons: 610 / 1000 (background)")
            SetupBridge.publish(outdated)
            val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val root = android.widget.FrameLayout(act); act.setContentView(root)
            val setup = SetupPanel(act); root.addView(setup); ShadowLooper.idleMainLooper()
            render(setup, 1240, 1080)
            SetupBridge.launcherActions = { a ->
                // the launcher queues it behind the background art step and says so (WoWForeverScreen.performUpdate)
                if (a == SetupFlow.Action.UPDATE_GAME) SetupBridge.publish(outdated.copy(busy = SetupFlow.updateGateLine(SetupFlow.UpdateGate.STOP_ART, 61)))
            }
            pad(KeyEvent.KEYCODE_BUTTON_A)
            device(render(LoadingTopView(ctx), 1920, 1080), render(setup, 1240, 1080), "V3_update_pressed_down")
            ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            device(render(LoadingTopView(ctx), 1920, 1080), render(setup, 1240, 1080), "V4_update_waiting_queued")
            SetupBridge.launcherActions = null
            root.removeAllViews()
            // 3. the chooser: the selection wears A, Local boxed then Streaming boxed
            LoadingScene.pin(LoadingMath.EVERY + 2.0f, 11L, 1)
            SetupBridge.publish(base)
            val chooser = ChooserPanel(act); root.addView(chooser); ShadowLooper.idleMainLooper()
            device(render(LoadingTopView(ctx), 1920, 1080), render(chooser, 1240, 1080), "V5_chooser_local_boxed")
            pad(KeyEvent.KEYCODE_DPAD_RIGHT)
            device(render(LoadingTopView(ctx), 1920, 1080), render(chooser, 1240, 1080), "V6_chooser_streaming_boxed")
            // 4. the reveal through a pass (v8: narrow at the middle, wide at the tail, dark between the turns; v9 since party5: the band
            // grows and ripples into the dark after the spark passed)
            SetupBridge.publish(bars)
            for ((i, t) in listOf(1.0f, 3.0f, 5.0f, 7.5f, 9.0f).withIndex()) {
                LoadingScene.pin(t, 11L, 1)
                device(render(LoadingTopView(ctx), 1920, 1080), render(SetupPanel(ctx).also { it.update() }, 1240, 1080), "V7_reveal_${i}_${t}s")
            }
        } finally {
            SetupBridge.publish(null); SetupBridge.launcherActions = null; ModeBridge.reset()
        }
    }
}
