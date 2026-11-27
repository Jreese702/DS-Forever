package com.jrees.wowcompanion

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.view.Display
import com.jrees.wowcompanion.BottomParkLifecycleTest.LauncherHooksActivity
import com.jrees.wowcompanion.fork.DsForeverScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.Calendar

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK, THE INT/1534 REVIEW (2026-11-26), on the REAL DsForeverScreen / HostPresentation / TopWindow /
 * CompanionHost driven through the launcher MainActivity's own hooks (BottomParkLifecycleTest's activity):
 * - single screen mode: "Tap the game for the controller" pinned while the keys stayed on the other screen goes at a minimize or a sleep
 *   (the park keeps the same top window now, its plate with it) and does not come back at the return once the game has the keys; keys
 *   the other screen kept over the park (no new answer from Android) are taken back after the return by the normal rules;
 * - memory pressure Android reported while the window was in use (its trim then let nothing go: not parked) lets the parked host go at
 *   the park itself - Android calls onTrimMemory again only when the level rises; no pressure, a moderate one, the UI_HIDDEN trim and
 *   RUNNING_LOW off Light 8 keep it (the user's pick: kept "until Android asks").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BottomRound4LifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private lateinit var log: File
    private var seq = 0

    @Before fun up() {
        if (System.getenv("BOTTOM_PARK_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        BottomSync.clear(); DocMemory.shared.clear()
        root = File(app.cacheDir, "wf-${System.nanoTime()}").apply { mkdirs() }
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED)
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(File(root, "sdcard").path)
        app.getSharedPreferences(GameFiles.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(GameFiles.KEY_GAME_PATH, root.path).commit()
        log = File(GamePaths.log(root.path)).apply { parentFile?.mkdirs(); writeText("") }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }

    @After fun down() {
        trimLevel(0)
        light8(null)
        LauncherHooksActivity.onDisplay = null
        runCatching { DsForeverScreen.closeAll() }
        runCatching { SingleScreen::class.java.getDeclaredMethod("reset").apply { isAccessible = true }.invoke(SingleScreen) }
        GameInput.away = false
        root.deleteRecursively()
    }

    private fun write(json: String) {
        val c = Calendar.getInstance()
        val stamp = "%d/%d %02d:%02d:%02d.%03d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND))
        log.appendText("$stamp  [N][Lua] WCB1|${++seq}|1/1|$json\r\n")
    }

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            c = c.superclass
        }
        error("no field $name on ${o.javaClass}")
    }

    private fun settle(ms: Long = 3_000, done: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(50)); ShadowLooper.idleMainLooper()
            if (done()) return
            Thread.sleep(20)
        }
    }

    private fun display(id: Int, f: (org.robolectric.android.internal.DisplayConfig) -> Unit) {
        ShadowDisplayManager::class.java.getDeclaredMethod("changeDisplay", Int::class.javaPrimitiveType, org.robolectric.util.Consumer::class.java)
            .apply { isAccessible = true }
            .invoke(null, id, object : org.robolectric.util.Consumer<org.robolectric.android.internal.DisplayConfig> {
                override fun accept(t: org.robolectric.android.internal.DisplayConfig) = f(t)
            })
    }
    private fun bottomDisplay(): Int {
        val bottom = ShadowDisplayManager.addDisplay("w1240dp-h1080dp", Display.FLAG_PRESENTATION)
        display(bottom) { it.type = 1; it.flags = Display.FLAG_PRESENTATION }
        return bottom
    }

    private val sink = object : InputSink.Sink { override fun key(xKeycode: Int, down: Boolean) {}; override fun tap(fx: Float, fy: Float) {} }
    private fun screen() = DsForeverScreen.current!!
    private fun shown(): Any? = field(screen(), "shown")
    private fun host(): CompanionHost = field(shown()!!, "host") as CompanionHost
    private fun tail(h: CompanionHost): LogTail? = field(h, "tail") as? LogTail
    /** the window on show is parked (TopWindow's / HostPresentation's own flag) */
    private fun parked(): Boolean = shown()?.let { field(it, "parkedNow") as Boolean } == true

    private fun inTheWorld(): org.robolectric.android.controller.ActivityController<LauncherHooksActivity> {
        bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host()) != null }
        assertNotNull("the bottom window is up", shown())
        screen().attachGame(sink)
        LauncherHooks.gameStartSec = 1_000L
        return c
    }

    private fun minimize(c: org.robolectric.android.controller.ActivityController<LauncherHooksActivity>) {
        c.get().onUserLeaveHint(); c.pause().stop()
    }

    /** the trim level Android holds for this process now (ActivityManager.getMyMemoryState's lastTrimLevel: Robolectric's process list) */
    private fun trimLevel(level: Int) {
        val am = app.getSystemService(ActivityManager::class.java)
        Shadows.shadowOf(am).setProcesses(listOf(ActivityManager.RunningAppProcessInfo(app.packageName, android.os.Process.myPid(),
            arrayOf(app.packageName)).apply { lastTrimLevel = level }))
    }
    /** Light 8 (the 8 GB Thors) on / off for the test, null = the device's own RAM decides */
    private fun light8(on: Boolean?) {
        DeviceTier::class.java.getDeclaredField("light8Override").apply { isAccessible = true }.set(null, on)
    }

    // ---------------------------------------------------------------- single screen mode: the stuck keys' plate across a park

    private fun plate(): SingleScreen.Plate = field(shown()!!, "plate") as SingleScreen.Plate
    /** the plate's small window says it is shown (its `on`; GONE while parked whatever it says) */
    private fun plateOn(): Boolean = (field(shown()!!, "subs") as Map<*, *>)["hint"]?.let { field(it, "on") as Boolean } ?: false

    @Test fun theStuckKeysPlateGoesAtTheParkAndStaysGoneAfterTheReturn() {
        val c = inTheWorld()
        SingleScreen.request?.invoke(true, "the test")
        settle(4_000) { SingleScreen.inUse && shown()?.javaClass?.simpleName == "TopWindow" && tail(host()) != null }
        assertEquals("one screen: the top window up", "TopWindow", shown()?.javaClass?.simpleName)
        val top = WeakReference(shown()!!)
        // Cocoon's grid on the bottom screen took the keys (Android's top-resumed answer: no more ours), and they stayed there
        DsForeverScreen.topResumedFor(c.get(), false)
        settle(SingleScreen.KEYS_BACK_MS + SingleScreen.KEYS_STUCK_MS + 1_000) { plate().pinned == SingleScreen.TAP_FOR_KEYS }
        assertEquals("the keys stuck: the pinned words", SingleScreen.TAP_FOR_KEYS, plate().pinned)
        assertTrue(plateOn())
        // the lid closed (or Home pressed): the same top window parked
        minimize(c); settle(300)
        assertTrue("parked", parked())
        assertSame("the same top window kept", top.get(), shown())
        assertNull("the pinned words went with the stuck state", plate().pinned)
        // back: the same window shown again; Android's answer says the launcher is the top resumed one (the game has the keys)
        c.start().resume(); settle(1_000) { !parked() }
        assertFalse("back", parked())
        assertSame("the same top window shown again", top.get(), shown())
        DsForeverScreen.topResumedFor(c.get(), true)
        settle(SingleScreen.HINT_MS + 500)
        assertNull("no 'Tap the game for the controller' over the game after the return", plate().pinned)
        assertNull("nothing on the plate", plate().now())
        assertFalse("the plate's window not shown", plateOn())
        assertFalse("the keys are the game's: nothing taken back", field(screen(), "keysAway") as Boolean)
        c.pause().stop().destroy()
    }

    /**
     * the same, but Android sends no new answer at the return (the other screen kept the keys over the park: no change, no call): the
     * last answer is acted on as a switch does - the keys taken back by the normal rules, the plate again only if they stay away
     */
    @Test fun keysTheOtherScreenKeptOverTheParkAreTakenBackAfterTheReturn() {
        val c = inTheWorld()
        SingleScreen.request?.invoke(true, "the test")
        settle(4_000) { SingleScreen.inUse && shown()?.javaClass?.simpleName == "TopWindow" && tail(host()) != null }
        assertEquals("one screen: the top window up", "TopWindow", shown()?.javaClass?.simpleName)
        DsForeverScreen.topResumedFor(c.get(), false)
        settle(SingleScreen.KEYS_BACK_MS + SingleScreen.KEYS_STUCK_MS + 1_000) { plate().pinned == SingleScreen.TAP_FOR_KEYS }
        assertEquals(SingleScreen.TAP_FOR_KEYS, plate().pinned)
        minimize(c); settle(300)
        assertTrue("parked", parked())
        assertFalse("the stuck state dropped at the park", field(screen(), "keysAway") as Boolean)
        c.start().resume(); settle(1_000) { !parked() }
        assertFalse("back", parked())
        settle(DsForeverScreen.KEYS_RETURN_MS + 500) { field(screen(), "keysAway") as Boolean }
        assertTrue("no new answer: the other screen still has the keys - taken back", field(screen(), "keysAway") as Boolean)
        settle(SingleScreen.KEYS_BACK_MS + SingleScreen.KEYS_STUCK_MS + 1_000) { plate().pinned == SingleScreen.TAP_FOR_KEYS }
        assertEquals("still away: the plate by the normal rules", SingleScreen.TAP_FOR_KEYS, plate().pinned)
        // Android's answer comes (a tap on the game): the plate goes
        DsForeverScreen.topResumedFor(c.get(), true)
        settle(300)
        assertNull(plate().pinned)
        c.pause().stop().destroy()
    }

    // ---------------------------------------------------------------- Android's memory pressure reported before the park

    /** [level] said by Android while the window is in use (its trim: nothing let go: not parked), then a minimize */
    private fun pressureThenPark(c: org.robolectric.android.controller.ActivityController<LauncherHooksActivity>, level: Int) {
        trimLevel(level)
        if (level > 0) (field(screen(), "trims") as ComponentCallbacks2).onTrimMemory(level)
        settle(300)
        assertNotNull("in use: nothing let go at Android's trim $level", shown())
        assertFalse(parked())
        minimize(c); settle(600) { shown() == null }
    }

    @Test fun pressureReportedBeforeTheParkLetsTheParkedHostGoThen() {
        light8(false)
        val c = inTheWorld()
        pressureThenPark(c, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertNull("RUNNING_CRITICAL still held at the park: the parked host let go", shown())
        assertNotNull("its snapshot kept for the return", BottomSync.last)
        c.start().resume()
        settle(4_000) { shown() != null && tail(host()) != null }
        assertNotNull("made again at the return", shown())
        c.pause().stop().destroy()
    }

    @Test fun runningLowBeforeTheParkLetsItGoOnLight8Only() {
        light8(true)
        val c = inTheWorld()
        pressureThenPark(c, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertNull("Light 8: RUNNING_LOW held at the park lets the parked host go", shown())
        c.start().resume()
        settle(4_000) { shown() != null && tail(host()) != null }
        assertNotNull("made again at the return", shown())
        c.pause().stop().destroy()
    }

    @Test fun noPressureTheMinimizeTrimOrRunningLowOffLight8KeepTheParkedHost() {
        light8(false)
        val c = inTheWorld()
        val h = WeakReference(host())
        for (level in listOf(0, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)) {
            pressureThenPark(c, level)
            assertNotNull("level $level: the parked host kept", shown())
            assertTrue("level $level: parked", parked())
            assertSame("level $level: the same host", h.get(), host())
            trimLevel(0)
            c.start().resume(); settle(1_000) { !parked() }
            assertFalse("level $level: back", parked())
            assertSame("level $level: the same host back", h.get(), host())
        }
        c.pause().stop().destroy()
    }
}
