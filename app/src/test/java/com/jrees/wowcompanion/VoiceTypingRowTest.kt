package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.junit.After
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
 * VOICE TYPING'S SETTINGS ROW, off-device (the user's pick 2026-11-16: "On the Thor only by default. Online is used only after you pick it
 * once"): General > Look > Voice typing, This device / Online / Off under Language, app-wide; the cursor stops on its three segments and a
 * tap saves the choice. With VOICE_SHOTS_DIR set it renders the General tab (VOICE_ART_ROOT = an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceTypingRowTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun clean() { Settings.voiceTyping = Voice.Mode.DEVICE }

    private fun room(): Pair<SettingsRoom, SettingsView> {
        Settings.init(app); SettingsTabs.init(app)
        System.getenv("VOICE_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        val v = SettingsView(app) { }
        v.loginRow = true
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val r = SettingsRoom(act, v) { null }
        act.setContentView(r)
        r.show(SettingsTabs.Tab.GENERAL)
        lay(r)
        return r to r.groupBoxes.first { it.group == SettingsTabs.Group.LOOK }.parts.single() as SettingsView
    }

    private fun lay(r: View, w: Int = 1240, h: Int = 1080 - Strip.HEIGHT) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, w, h)
            r.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun segs(s: SettingsView) = SettingsView::class.java.getDeclaredField("segVoice").apply { isAccessible = true }.get(s) as Array<RectF>

    private fun tap(s: SettingsView, r: RectF) {
        s.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0))
        s.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0))
    }

    @Test fun threeSegmentsTheCursorStopsOnAndATapSaves() {
        Settings.init(app)
        Settings.voiceTyping = Voice.Mode.DEVICE
        val (r, s) = room()
        assertTrue(segs(s).none { it.isEmpty })
        val targets = s.focusTargets()
        for (sg in segs(s)) assertTrue(targets.any { it == sg })
        tap(s, segs(s)[1])
        assertEquals(Voice.Mode.ONLINE, Settings.voiceTyping)
        lay(r)
        tap(s, segs(s)[2])
        assertEquals(Voice.Mode.OFF, Settings.voiceTyping)
        lay(r)
        tap(s, segs(s)[0])
        assertEquals(Voice.Mode.DEVICE, Settings.voiceTyping)
    }

    @Test fun shot() {
        val out = System.getenv("VOICE_SHOTS_DIR")?.let { File(it) } ?: return
        out.mkdirs()
        val (r, _) = room()
        lay(r); Thread.sleep(250); lay(r)
        val b = Bitmap.createBitmap(1240, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
        File(out, "settings_voice_typing.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
