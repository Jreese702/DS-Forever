package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE USER, 2026-11-24: "The indoor maps just never builds. I want it to show the bar and build on the first screen if people wait there
 * and I also want a button in game to force building in game" (the picks: Settings > Performance, "A 'Build now' button on the Indoor
 * maps row next to its progress bar"; "Only while I'm idle"). Drawn and pressed: the start screen's indoor maps row with its bar while it
 * builds (and a moving bar while it lists), the Settings > Performance > Indoor maps band with Build now while the game runs, Stop while
 * it is armed and every status it shows, and the finished build clearing the button. PNGs when INDOOR_BUILD_SHOTS_DIR is set
 * (THEME_ART_ROOT / PARTY_ART_ROOT: an art root laid out like the app's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IndoorBuildNowShotsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("INDOOR_BUILD_SHOTS_DIR")?.let { File(it).apply { mkdirs() } }
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private var running = true

    private fun art() {
        val root = System.getenv("THEME_ART_ROOT") ?: System.getenv("PARTY_ART_ROOT")
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root?.let { File(it) })
        UiKit.root = UiKit.root
    }

    private fun lang(l: String) {
        if (l != "en") listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    private fun room(): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); FrameGen.init(app)
        art()
        val v = SettingsView(app) { }
        v.gameRunning = { running }
        return SettingsRoom(app, v) { null }.also { it.show(SettingsTabs.Tab.PERFORMANCE); lay(it) }
    }

    private val scratch by lazy { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }

    private fun lay(r: View) {
        for (k in 0..2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, W, H)
            r.draw(Canvas(scratch))
        }
    }

    private fun shot(r: View, name: String) {
        lay(r)
        val o = out ?: return
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); r.draw(c)
        File(o, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0); v.dispatchTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0); v.dispatchTouchEvent(u); u.recycle()
    }

    private fun open(r: SettingsRoom, id: String) {
        val p = r.perf
        if (p.openTile == id) return
        val t = p.focusTargets()[p.tileIds().indexOf(id)]
        tap(p, t.centerX(), t.centerY()); lay(r)
    }

    private fun bandButtons(r: SettingsRoom): List<RectF> {
        val p = r.perf
        val band = p.bandBox() ?: return emptyList()
        return p.focusTargets().drop(p.tileIds().size).filter { band.contains(it.centerX(), it.centerY()) }
    }

    /** the bake's status from its own JSON (IndoorScheduler is internal to the companion's module: by reflection) */
    private fun status(o: JSONObject): Any? {
        val cls = Class.forName("com.jrees.wowcompanion.IndoorScheduler\$Status")
        val comp = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        return comp.javaClass.getMethod("of", JSONObject::class.java).apply { isAccessible = true }.invoke(comp, o)
    }

    // (PlayGate is internal to the companion's module: its words as they are; IndoorBuildAnywhereTest pins them to its constants)
    private fun st(phase: String, note: String = "", ready: Int = 1616, total: Int = 1853) =
        status(JSONObject().put("phase", phase).put("ready", ready).put("total", total).put("note", note))

    private val armedField = IndoorSchedulerStep::class.java.getDeclaredField("armed").apply { isAccessible = true }
    private val threadField = IndoorSchedulerStep::class.java.getDeclaredField("thread").apply { isAccessible = true }
    private fun armed(v: Boolean) = armedField.setBoolean(null, v)

    @After fun forget() {
        armed(false); threadField.set(null, null)
        for (f in listOf("status")) IndoorSchedulerStep::class.java.getDeclaredField(f).apply { isAccessible = true }.set(null, null)
        IndoorSchedulerStep::class.java.getDeclaredField("again").apply { isAccessible = true }.setBoolean(null, false)
        Tr.setChoice(app, "auto"); Theme.use("classic"); SetupBridge.publish(null)
    }

    private fun nowLine(r: SettingsRoom) = PerformanceTileModel.lines(PerformanceTileModel.INDOOR, r.perf.state()).firstOrNull { it.key == PerformanceTileModel.L_NOW }

    @Test fun settingsHasBuildNowWhileTheGameRunsAndStopWhileArmed() {
        Settings.init(app); Settings.indoorBuild = true
        val r = room()
        val prog = PerformanceTiles::class.java.getDeclaredField("progress").apply { isAccessible = true }
        fun progress(s: Any?) { prog.set(r.perf, s); lay(r) }
        try {
            // the game running, building by itself never (it waits for the game): Build now beside the ring
            progress(st("PAUSED", "waiting: game running"))
            open(r, PerformanceTileModel.INDOOR)
            assertEquals(listOf("Build now"), nowLine(r)!!.words)
            shot(r, "S1_settings_build_now")
            // pressed (the builder's thread held so no real build starts here): armed, the line says Stop
            threadField.set(null, Thread {})
            val buttons = bandButtons(r)
            assertEquals("On, Off, Build now", 3, buttons.size)
            tap(r.perf, buttons[2].centerX(), buttons[2].centerY()); lay(r)
            assertTrue("Build now armed it", IndoorSchedulerStep.armed)
            assertEquals(listOf("Stop"), nowLine(r)!!.words)
            // each status the line under it shows while armed
            for ((name, s) in listOf(
                    "S2_building_while_idle" to st("BAKING", "building while you're idle"),
                    "S3_paused_moving" to st("PAUSED", "paused: moving"),
                    "S4_paused_fight" to st("PAUSED", "paused: in a fight"),
                    "S5_paused_loading" to st("PAUSED", "paused: loading"),
                    "S6_paused_flight" to st("PAUSED", "paused: on a flight"),
                    "S7_paused_low_memory" to st("PAUSED", "paused: low memory"))) {
                progress(s)
                val note = JSONObject(s!!.javaClass.getMethod("toJson").invoke(s).toString()).getString("note")
                assertTrue("$name: ${PerformanceTileModel.statusParts(PerformanceTileModel.INDOOR, r.perf.state())}",
                    PerformanceTileModel.statusParts(PerformanceTileModel.INDOOR, r.perf.state()).contains(note))
                assertEquals(listOf("Stop"), nowLine(r)!!.words)
                shot(r, name)
            }
            for (l in listOf("es", "fr")) {
                lang(l)
                progress(st("PAUSED", "paused: moving")); shot(r, "S8_${l}_stop_paused_moving")
                progress(st("BAKING", "building while you're idle")); shot(r, "S8_${l}_stop_building_while_idle")
                armed(false); lay(r); progress(st("PAUSED", "waiting: game running")); shot(r, "S8_${l}_build_now"); armed(true); lay(r)
            }
            lang("en")
            // Stop: disarmed, Build now again
            progress(st("PAUSED", "paused: moving"))
            val stop = bandButtons(r)[2]
            tap(r.perf, stop.centerX(), stop.centerY()); lay(r)
            assertFalse("Stop disarmed it", IndoorSchedulerStep.armed)
            assertEquals(listOf("Build now"), nowLine(r)!!.words)
            // finished: no button at all
            armed(true)
            progress(status(JSONObject().put("phase", "DONE").put("ready", 1632).put("total", 1853).put("rejected", 221).put("deferred", 221)))
            assertNull("finished clears the button", nowLine(r))
            shot(r, "S9_finished_no_button")
            // the game not running and not armed: nothing to press (it builds by itself)
            armed(false); running = false
            progress(st("BAKING"))
            assertNull(nowLine(r))
            shot(r, "S10_not_in_game_no_button")
        } finally { running = true }
    }

    /** the builder's own publication of a finished status ends Build now (the dungeons go on after the game, as before) */
    @Test fun finishedDisarmsBuildNow() {
        Settings.init(app)
        armed(true)
        val cls = Class.forName("com.jrees.wowcompanion.IndoorScheduler\$Status")
        val m = IndoorSchedulerStep::class.java.getDeclaredMethod("publishStatus", android.content.Context::class.java, cls).apply { isAccessible = true }
        m.invoke(IndoorSchedulerStep, app, st("BAKING", "building while you're idle"))
        assertTrue("still building: armed", IndoorSchedulerStep.armed)
        m.invoke(IndoorSchedulerStep, app, status(JSONObject().put("phase", "DONE").put("ready", 1632).put("total", 1853)))
        assertFalse("finished: disarmed", IndoorSchedulerStep.armed)
    }

    @Test fun theStartScreenRowShowsItsBar() {
        art()
        LoadingScene.pin(LoadingMath.EVERY + 2.0f, 11L, 1)
        val base = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true,
            art = SetupFlow.Art.READY, addonsInstalled = true, client = "1.60.1.71037 (up to date)", maps = true,
            appLine = "DS Forever 1.0.41: up to date", iconsLine = "Icons: all icons ready")
        val room = LoadingTopView.PANEL_W - (LoadingTopView.ROW_X + 64f + 60f)
        val p = UiKit.bodyPaint(32f, Theme.text)
        val cases = listOf("Building 1616/1853" to "T1_start_row_building", "Listing buildings" to "T2_start_row_listing",
            "1616/1853 ready - Waiting: game art" to "T3_start_row_waiting_art")
        for (l in listOf("en", "es", "fr")) {
            lang(l)
            for ((indoor, name) in cases) {
                val inputs = base.copy(indoor = indoor)
                SetupBridge.publish(inputs)
                val full = SetupFlow.checks(inputs).first { it.label.startsWith("Indoor") }
                assertTrue("$name: a bar", SetupFlow.bar(full, inputs) != null)
                val row = SetupFlow.terse(listOf(full)).single()
                assertEquals(SetupFlow.INDOOR_ROW, row.label)
                val w = p.measureText(Tr.t(row.label))
                assertTrue("$l: \"${Tr.t(row.label)}\" is $w px, the row has $room", w <= room)
                if (l != "en") assertTrue("$l translated", Tr.t(row.label) != row.label)
                val v = LoadingTopView(app)
                v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, 1920, 1080)
                val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(b))
                out?.let { d -> File(d, "${name}_$l.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                b.recycle()
            }
        }
    }
}
