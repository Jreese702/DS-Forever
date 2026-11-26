package com.jrees.wowcompanion

import android.app.Activity
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.jrees.wowcompanion.fork.DsForeverScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.Calendar

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK, ON A REAL LIFECYCLE (the user, 2026-11-25: "Coming back after a drop ... DS Forever should come
 * straight back on it, in the same room, already caught up with the game, with no app restart"; "most of the time the drops happen from
 * minimizing the game or closing my thor to sleep"). The REAL DsForeverScreen, HostPresentation and CompanionHost on a second
 * presentation display, driven by an activity that calls exactly the launcher's MainActivity hooks (begin / show / hideFor / end;
 * the real app.gamenative.MainActivity is a Hilt + Compose + game-container activity Robolectric cannot host here) through pause, stop,
 * start and resume: the SAME host instance, the same room, the same reader resumed from its byte place (never from 0), the window parked
 * meanwhile (no surface, no touch, no focus, no keep-screen-on, taps held). Then Android dismissing the window by itself: noticed, a new
 * window and host at once (never two alive), the new reader going on from the old place, the room adopted at the first current interact
 * document that says the game still holds it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BottomParkLifecycleTest {
    /** the launcher's MainActivity's DS Forever hooks, in its order (MainActivity.onCreate / onResume / onUserLeaveHint / onPause / onStop / onDestroy) */
    class LauncherHooksActivity : Activity() {
        /** Robolectric's activity context is not a display context (ContextImpl.getDisplay throws); on a device it is display 0 here */
        @Suppress("DEPRECATION") override fun getDisplay(): Display = windowManager.defaultDisplay
        override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); DsForeverScreen.begin(this) }
        override fun onResume() {
            super.onResume()
            if (DsForeverScreen.moveToTop(this, true)) return
            DsForeverScreen.current?.show()
        }
        public override fun onUserLeaveHint() { DsForeverScreen.hideFor(this); super.onUserLeaveHint() }
        override fun onPause() { DsForeverScreen.hideFor(this); super.onPause() }
        override fun onStop() { DsForeverScreen.hideFor(this); super.onStop() }
        override fun onDestroy() { DsForeverScreen.end(this); super.onDestroy() }
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private lateinit var log: File
    private var seq = 0

    @Before fun up() {
        if (System.getenv("BOTTOM_PARK_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        BottomSync.clear(); DocMemory.shared.clear()
        root = File(app.cacheDir, "wf-${System.nanoTime()}").apply { mkdirs() }
        // the host's all-files check reads the external storage (Robolectric has none by default)
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED)
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(File(root, "sdcard").path)
        app.getSharedPreferences(GameFiles.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(GameFiles.KEY_GAME_PATH, root.path).commit()
        log = File(GamePaths.log(root.path)).apply { parentFile?.mkdirs(); writeText("") }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }

    @After fun down() {
        runCatching { DsForeverScreen.closeAll() }
        GameInput.away = false
        root.deleteRecursively()
    }

    /** one add-on document as the game writes it (the log's own time prefix: the input gate reads it as current) */
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

    /** the main looper and the real reader / locator threads, for up to [ms] of real time, until [done] */
    private fun settle(ms: Long = 3_000, done: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(50)); ShadowLooper.idleMainLooper()
            if (done()) return
            Thread.sleep(20)
        }
    }

    /** one display's state / type as the device would change it (Robolectric's own changeDisplay; onDisplayChanged follows) */
    private fun display(id: Int, f: (org.robolectric.android.internal.DisplayConfig) -> Unit) {
        ShadowDisplayManager::class.java.getDeclaredMethod("changeDisplay", Int::class.javaPrimitiveType, org.robolectric.util.Consumer::class.java)
            .apply { isAccessible = true }
            .invoke(null, id, object : org.robolectric.util.Consumer<org.robolectric.android.internal.DisplayConfig> {
                override fun accept(t: org.robolectric.android.internal.DisplayConfig) = f(t)
            })
    }

    private fun screen() = DsForeverScreen.current!!
    private fun shown(): Any? = field(screen(), "shown")
    private fun host(): CompanionHost = field(shown()!!, "host") as CompanionHost
    private fun tail(h: CompanionHost): LogTail? = field(h, "tail") as? LogTail
    private fun offset(t: LogTail): Long = field(t, "offset") as Long
    private fun room(h: CompanionHost): Room = field(h, "room") as Room

    @Test fun minimizeAndSleepKeepTheSameHostRoomAndReader() {
        // the Thor's bottom panel: a built-in display (TYPE_INTERNAL, hidden constant 1) with the presentation flag
        val bottom = ShadowDisplayManager.addDisplay("w1240dp-h1080dp", Display.FLAG_PRESENTATION)
        display(bottom) { it.type = 1; it.flags = Display.FLAG_PRESENTATION }
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        // the bottom window comes SHOW_AFTER_MS after the start; its host's reader starts once the game is located (a thread)
        settle(6_000) { shown() != null && tail(host())?.let { offset(it) == log.length() } == true }
        val w = shown(); assertNotNull("the bottom window is up", w)
        val h = host()
        assertEquals(bottom, (w as android.app.Presentation).display.displayId)
        val t = tail(h); assertNotNull("the reader runs", t)
        assertEquals("the log read", log.length(), offset(t!!))
        // a room on show (Settings: no game window behind it)
        h.javaClass.getDeclaredMethod("show", Room::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(h, Room.SETTINGS, false, false, false)
        assertEquals(Room.SETTINGS, room(h))
        val hostsBefore = CompanionHost.live
        // the game screen up (XServerScreen's attachGame): the launcher's DS Forever has its game (a snapshot is kept only with one)
        screen().attachGame(object : InputSink.Sink { override fun key(xKeycode: Int, down: Boolean) {}; override fun tap(fx: Float, fy: Float) {} })

        for (way in listOf("minimize", "sleep")) {
            // away: Home / Recents (onUserLeaveHint, onPause, onStop) or the screen off (onPause, onStop)
            if (way == "minimize") c.get().onUserLeaveHint()
            c.pause().stop()
            // the sleep: both panels OFF, then ON before the resume (onDisplayChanged while away: nothing)
            if (way == "sleep") { display(bottom) { it.state = Display.STATE_OFF }; settle(300); display(bottom) { it.state = Display.STATE_ON } }
            settle(300)
            assertSame("$way: the same window kept", w, shown())
            assertTrue("$way: parked", h.parked)
            val win = (w as android.app.Dialog).window!!
            assertEquals("$way: no surface", View.GONE, win.decorView.visibility)
            val f = win.attributes.flags
            assertTrue("$way: never touchable", f and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue("$way: never focusable", f and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue("$way: nothing kept awake", f and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
            assertTrue("$way: no taps while away", GameInput.away)
            // the add-on writes on while away: nothing is read
            val at = offset(t)
            write("""{"type":"quests","q":{"n":2}}""")
            settle(400)
            assertEquals("$way: the reader paused at its place", at, offset(t))

            // back
            c.start().resume()
            // a late STATE_ON after the resume (the sleep): nothing more
            if (way == "sleep") { display(bottom) { it.state = Display.STATE_DOZE }; settle(200); display(bottom) { it.state = Display.STATE_ON } }
            settle(3_000) { offset(t) == log.length() }
            assertSame("$way: the SAME host", h, host())
            assertSame("$way: the same window", w, shown())
            assertFalse(h.parked)
            assertEquals("$way: the same room", Room.SETTINGS, room(h))
            assertSame("$way: the same reader", t, tail(h))
            assertEquals("$way: read on from its place (only what was written while away), never from 0", log.length(), offset(t))
            assertEquals(View.VISIBLE, win.decorView.visibility)
            assertTrue(win.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
            assertTrue(win.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertFalse(GameInput.away)
            assertEquals("$way: no host made", hostsBefore, CompanionHost.live)
        }
        assertTrue(BottomSync.line(), BottomSync.line().startsWith("Bottom screen: kept 2, shown again 2"))

        // ANDROID DISMISSES THE WINDOW BY ITSELF (Presentation's own cancel: a display change): noticed, a new one at once, restored
        val placeAt = offset(t)
        w.cancel()
        settle(3_000) { shown() != null && shown() !== w && tail(host()) != null }
        val w2 = shown(); assertNotNull("reopened by the watchdog", w2)
        assertNotSame(w, w2)
        val h2 = host()
        assertNotSame(h, h2)
        assertEquals("never two hosts", hostsBefore, CompanionHost.live)
        val t2 = tail(h2)!!
        settle(1_000) { offset(t2) >= placeAt }
        assertEquals("the new reader goes on from the old one's place (no whole-log replay)", placeAt, offset(t2))
        assertEquals("Home until the game says its window is still open", Room.HOME, room(h2))
        // the first current interact document: the game still holds the controller for Settings - adopted, no entry tap
        write("""{"type":"interact","holder":"pad:settings"}""")
        settle(3_000) { room(h2) == Room.SETTINGS }
        assertEquals(Room.SETTINGS, room(h2))
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (closed by Android 1)"))
        c.pause().stop().destroy()
        settle(300)
        assertEquals(hostsBefore - 1, CompanionHost.live)
    }
}
