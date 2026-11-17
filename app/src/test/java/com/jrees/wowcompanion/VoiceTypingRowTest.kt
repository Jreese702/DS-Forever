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

    private fun room(): Pair<SettingsRoom, GeneralTilesView> {
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
        return r to r.groupBoxes.first { it.group == SettingsTabs.Group.LOOK }.parts.single() as GeneralTilesView
    }

    private fun lay(r: View, w: Int = 1240, h: Int = 1080 - Strip.HEIGHT) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, w, h)
            r.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        }
    }

    /** 2026-11-17 (the beautify pass): Voice typing is a tile; its three choices are its band's */
    private fun segs(r: SettingsRoom, s: GeneralTilesView): List<RectF> {
        if (s.openTile != GeneralTiles.VOICE) { tap(s, s.tileBox(GeneralTiles.VOICE)!!); lay(r) }
        return s.bandButtons()[0]
    }

    private fun tap(s: View, r: RectF) {
        s.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0))
        s.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0))
    }

    @Test fun threeSegmentsTheCursorStopsOnAndATapSaves() {
        Settings.init(app)
        Settings.voiceTyping = Voice.Mode.DEVICE
        val (r, s) = room()
        assertTrue(s.focusTargets().any { it == s.tileBox(GeneralTiles.VOICE) })
        val sg = segs(r, s)
        assertEquals(3, sg.size)
        val targets = s.focusTargets()
        for (b in sg) assertTrue(targets.any { it == b })
        tap(s, sg[1])
        assertEquals(Voice.Mode.ONLINE, Settings.voiceTyping)
        lay(r)
        tap(s, segs(r, s)[2])
        assertEquals(Voice.Mode.OFF, Settings.voiceTyping)
        lay(r)
        tap(s, segs(r, s)[0])
        assertEquals(Voice.Mode.DEVICE, Settings.voiceTyping)
    }

    /**
     * Add-ons > Voice (the user, 2026-11-16: "we could make the per languege downloads part of the addons folder"): two rows in the add-on
     * rows' look, each a stop for the cursor; Download only on the press (a host that refuses = its one line), Ready = Delete, twice.
     */
    @Test fun voiceModelsRowsOnAddOns() {
        val was = VoiceModels.offered
        val dir = java.nio.file.Files.createTempDirectory("voice-rows").toFile()
        try {
            VoiceModels.offered = { true }
            VoiceModels.start(dir, VoiceModels.Net { _, _ -> throw java.io.IOException("HTTP 503") }) {}
            Settings.init(app); SettingsTabs.init(app)
            val v = SettingsView(app) { }
            val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val r = SettingsRoom(act, v) { null }
            act.setContentView(r)
            r.show(SettingsTabs.Tab.ADDONS)
            assertTrue(SettingsTabs.Group.VOICE in SettingsTabs.groups(SettingsTabs.Tab.ADDONS))
            if (SettingsTabs.Group.VOICE !in r.open) r.toggle(SettingsTabs.Group.VOICE)   // open (the first folding group opens with the tab since the cards, 2026-11-17)
            lay(r)
            val s = r.groupBoxes.first { it.group == SettingsTabs.Group.VOICE }.parts.single() as SettingsView
            @Suppress("UNCHECKED_CAST")
            val btns = SettingsView::class.java.getDeclaredField("voiceModelBtn").apply { isAccessible = true }.get(s) as Map<VoiceModels.Model, RectF>
            assertEquals(VoiceModels.Model.values().toSet(), btns.keys)
            val targets = s.focusTargets()
            for (b in btns.values) { assertTrue(!b.isEmpty); assertTrue(targets.any { it == b }) }
            assertEquals(VoiceModels.Model.values().map { it.label }.toSet(), btns.values.map { b -> s.rowAt(b.centerX(), b.centerY()) }.toSet())
            for ((m, b) in btns) assertEquals(m.label, s.rowAt(b.centerX(), b.centerY()))
            assertEquals(4, btns.size)
            // Download: on the worker; this host refuses, so the row says so in one line
            tap(s, btns.getValue(VoiceModels.Model.EN))
            val until = System.currentTimeMillis() + 10_000
            while (VoiceModels.state(VoiceModels.Model.EN) !is VoiceModels.State.Failed && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals("Download failed: HTTP 503", VoiceModels.line(VoiceModels.Model.EN, VoiceModels.state(VoiceModels.Model.EN)))
            // a ready file: Delete, twice
            val f = VoiceModels.fileOf(VoiceModels.Model.MULTI)
            assertEquals(VoiceModels.State.None, VoiceModels.state(VoiceModels.Model.MULTI))
            java.io.RandomAccessFile(f, "rw").use { it.setLength(VoiceModels.Model.MULTI.bytes) }
            File(dir, f.name + ".ok").writeText("${f.length()}:${f.lastModified()}")
            assertTrue(VoiceModels.ready(VoiceModels.Model.MULTI))
            lay(r)
            tap(s, btns.getValue(VoiceModels.Model.MULTI))
            assertTrue(f.exists())                                  // armed: "Tap again"
            lay(r)
            tap(s, btns.getValue(VoiceModels.Model.MULTI))
            assertTrue(!f.exists())
        } finally {
            VoiceModels.offered = was
            dir.deleteRecursively()
        }
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
