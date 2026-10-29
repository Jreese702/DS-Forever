package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.jrees.wowcompanion.stream.PcWow
import com.jrees.wowcompanion.stream.StreamCurtain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * STREAMING'S CURTAIN, OFF-DEVICE (2026-10-28): the top screen while the PC starts WoW (the loading reveal and its box, StreamCurtain's
 * rows and line), and the one lifted state (Battle.net's sign-in: the stream with its gold line; the stream is a flat stand-in here).
 *   LOADING_SHOTS_DIR=E:/wow-build/loading-app/shots-stream PARTY_ART_ROOT=<root> gradlew :app:testModernDebugUnitTest --tests "*StreamCurtainShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamCurtainShotsTest {
    private val out: File? = System.getenv("LOADING_SHOTS_DIR")?.let { File(it) }

    private fun shot(name: String, t: Float, connected: Boolean, pc: PcWow?) {
        val d = out ?: return
        LoadingScene.pin(t, 11L, 1)
        val shows = StreamCurtain.decide(connected, true, pc)
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        when (shows) {
            is StreamCurtain.Shows.Curtain -> {
                val v = LoadingTopView(RuntimeEnvironment.getApplication()).apply { curtain = { shows.rows to shows.line } }
                v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, 1920, 1080)
                v.draw(c)
            }
            else -> {
                // the stream (a stand-in for the PC's picture), and Battle.net's sign-in line when it is lifted for that
                c.drawColor(Color.rgb(28, 40, 60))
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 210, 225); textSize = 40f; textAlign = Paint.Align.CENTER }
                c.drawText("(the PC's picture)", 960f, 560f, p)
                if (shows is StreamCurtain.Shows.SignIn) {
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 14, 11, 8) }
                    c.drawRoundRect(660f, 30f, 1260f, 110f, 18f, 18f, bg)
                    c.drawText(shows.line, 960f, 84f, UiKit.bodyPaint(34f, Theme.gold).apply { textAlign = Paint.Align.CENTER })
                }
            }
        }
        val s = Bitmap.createScaledBitmap(b, 960, 540, true)
        File(d, "$name.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        val d = out ?: return
        d.mkdirs()
        System.getenv("PARTY_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
        }
        shot("S0_connecting", 0.5f, false, null)
        shot("S1_battlenet_starting", 1.5f, true, PcWow(state = "stopped", screen = "none", bnet = "not_running"))
        shot("S2_battlenet_signin_lifted", 2.0f, true, PcWow(state = "stopped", screen = "none", bnet = "signin_needed"))
        shot("S3_wow_starting", 2.6f, true, PcWow(state = "starting", screen = "none", bnet = "signed_in"))
        shot("S4_signing_in", 3.4f, true, PcWow(state = "running", screen = "signing_in", focused = true, bnet = "signed_in"))
        shot("S5_login_error", 4.0f, true, PcWow(state = "running", screen = "login", focused = true, bnet = "signed_in", loginError = "308"))
        shot("S6_needs_you_lifted", 4.4f, true, PcWow(state = "running", screen = "login", focused = true, bnet = "signed_in", needsYou = true))
        shot("S7_signed_in_stream", 5.0f, true, PcWow(state = "running", screen = "charselect", focused = true, bnet = "signed_in"))
    }
}
