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
import org.junit.Assert.assertNull
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
        companion object {
            /** the display this activity is made on (null = display 0): a reopen from the bottom grid makes it on the bottom screen */
            var onDisplay: Int? = null
        }
        /** Robolectric's activity context is not a display context (ContextImpl.getDisplay throws); on a device it is display 0 here */
        @Suppress("DEPRECATION") override fun getDisplay(): Display =
            onDisplay?.let { getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(it) } ?: windowManager.defaultDisplay
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
        LauncherHooksActivity.onDisplay = null
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

    private fun bottomDisplay(): Int {
        val bottom = ShadowDisplayManager.addDisplay("w1240dp-h1080dp", Display.FLAG_PRESENTATION)
        display(bottom) { it.type = 1; it.flags = Display.FLAG_PRESENTATION }
        return bottom
    }

    private val sink = object : InputSink.Sink { override fun key(xKeycode: Int, down: Boolean) {}; override fun tap(fx: Float, fy: Float) {} }

    private fun showRoom(h: CompanionHost, r: Room) =
        h.javaClass.getDeclaredMethod("show", Room::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(h, r, false, false, false)

    /** Android closes the shown window by itself; only weak references come back (the test itself must hold neither) */
    private fun cancelShown(): Pair<java.lang.ref.WeakReference<Any>, java.lang.ref.WeakReference<CompanionHost>> {
        val w = shown()!!; val h = host()
        (w as android.app.Dialog).cancel()
        return java.lang.ref.WeakReference<Any>(w) to java.lang.ref.WeakReference(h)
    }

    /**
     * THE REVIEW (2026-11-26): a browser page whose WebView cannot be made (a provider update) after the window came up must never take
     * the window down - before, the open's catch abandoned a window that was already on screen (a dead host's views over the bottom
     * launcher) and the watchdog stacked one more every 5 s. And a host that goes (Android's own close) leaves nothing of it on the main
     * looper: its once-a-second loop was never taken off, so every rebuild kept the whole old host (its rooms, its map) alive and ticking.
     */
    @Test fun aFailedPageKeepsOneWindowAndAGoneHostStopsTicking() {
        bottomDisplay()
        val base = CompanionHost.live
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).create()
        // the sign-in page wanted before the window opens (DsForeverScreen.browserPage), its view cannot be made
        assertFalse(screen().browserPage { throw IllegalStateException("WebView provider updating") })
        c.start().resume()                                    // the resume's show() opens the bottom window
        settle(6_000) { shown() != null && tail(host()) != null }
        settle(3_000)                                         // the watchdog's 250 / 500 / 1000 / 2000 ms looks, had it been asked
        assertNotNull("the window stays up", shown())
        assertTrue("shown", (shown() as android.app.Dialog).isShowing)
        assertEquals("one host", base + 1, CompanionHost.live)
        assertFalse("no reopen loop: ${BottomSync.line()}", BottomSync.line().contains("reopen looks"))
        assertTrue(screen().browserPage(null))
        screen().attachGame(sink)

        // Android closes the window by itself: the new one comes, and NONE of the old host's repeating ticks stays on the main looper
        // (the once-a-second loop reposted itself for ever: the whole old host alive and ticking - GameUi, Home, the pet's B - beside
        // the new one). (Its views are still reachable from settings listener lists the app registers in many views and never removes:
        // older than this branch, a task of its own; this checks the looper, the reviewer's path)
        val (oldWin, oldHost) = cancelShown()
        settle(3_000) { shown() != null && shown() !== oldWin.get() && tail(host()) != null }
        assertEquals("never two hosts", base + 1, CompanionHost.live)
        settle(2_500)                                         // past the 1 s loop's next turn
        val old = oldHost.get()
        if (old != null) {
            @Suppress("UNCHECKED_CAST")
            val ticks = old.javaClass.getDeclaredMethod("repeatingTicks").apply { isAccessible = true }.invoke(old) as List<Runnable>
            val queued = queuedCallbacks()
            val left = ticks.filter { t -> queued.any { it === t } }.map { t ->
                old.javaClass.declaredFields.firstOrNull { f -> runCatching { f.isAccessible = true; f.get(old) === t }.getOrDefault(false) }?.name ?: t.javaClass.name
            }
            assertEquals("the old host's ticks left on the main looper", emptyList<String>(), left)
        }
        c.pause().stop().destroy()
        settle(300)
    }

    /** every callback queued on the main looper now (its MessageQueue's list) */
    private fun queuedCallbacks(): List<Any> {
        val q = android.os.Looper.getMainLooper().queue
        val head = android.os.MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
        val next = android.os.Message::class.java.getDeclaredField("next").apply { isAccessible = true }
        val cb = android.os.Message::class.java.getDeclaredField("callback").apply { isAccessible = true }
        val out = ArrayList<Any>()
        var m = head.get(q) as android.os.Message?
        var n = 0
        while (m != null && n++ < 200_000) { cb.get(m)?.let { out += it }; m = next.get(m) as android.os.Message? }
        return out
    }

    /**
     * THE REVIEW (2026-11-26): an activity of ours over the bottom screen (Settings > import add-ons, the DLL picker, the microphone
     * question: CompanionHost.openOnBottom hides the window until it closes). Closed while the launcher is away, its hook must never show
     * the PARKED window over the Thor's bottom launcher; still open at the return, the window stays hidden behind it.
     */
    @Test fun anActivityOverTheBottomScreenClosingWhileAwayShowsNothing() {
        bottomDisplay()
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host()) != null }
        screen().attachGame(sink)
        val win = (shown() as android.app.Dialog).window!!
        val open = host().javaClass.getDeclaredMethod("openOnBottom", Class::class.java, String::class.java, Function1::class.java)
            .apply { isAccessible = true }
        var closed: (() -> Unit)? = null
        val setClosed: ((() -> Unit)?) -> Unit = { closed = it }
        assertTrue("up on the bottom screen", open.invoke(host(), VoiceAskActivity::class.java, "Voice", setClosed) as Boolean)
        assertEquals("hidden behind it", View.GONE, win.decorView.visibility)
        // away; the activity closes meanwhile (the user cancels it on the bottom screen)
        c.get().onUserLeaveHint(); c.pause().stop()
        settle(300)
        closed!!.invoke()
        settle(300)
        assertEquals("parked: nothing shows over the bottom launcher", View.GONE, win.decorView.visibility)
        c.start().resume()
        settle(500)
        assertEquals("back: shown (the activity is gone)", View.VISIBLE, win.decorView.visibility)

        // again, but the activity still open at the return: it stays in view, the window hidden until it closes (round 2: really up -
        // alive, and its task shows on a screen: Android's own word, ActivityManager.AppTask's TaskInfo.isVisible)
        assertTrue(open.invoke(host(), VoiceAskActivity::class.java, "Voice", setClosed) as Boolean)
        val ask = Robolectric.buildActivity(VoiceAskActivity::class.java).create()
        val task = org.robolectric.shadows.ShadowAppTask.newInstance()
        val info = android.app.ActivityManager.RecentTaskInfo().apply { taskId = ask.get().taskId }
        android.app.TaskInfo::class.java.getDeclaredField("isVisible").apply { isAccessible = true }.setBoolean(info, true)
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowAppTask>(task).setTaskInfo(info)
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowActivityManager>(am).setAppTasks(listOf(task))
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
        c.start().resume(); settle(500)
        assertEquals("the activity still over it: hidden", View.GONE, win.decorView.visibility)
        closed!!.invoke(); settle(300)
        assertEquals(View.VISIBLE, win.decorView.visibility)
        runCatching { ask.destroy() }
        c.pause().stop().destroy()
        settle(300)
    }

    /**
     * THE REVIEW (2026-11-26; device check D4): a reopen from the bottom grid with the game running. Android makes the launcher's
     * activity again ON THE BOTTOM SCREEN (a configuration relaunch: no bottom window there, the game's display), its game screen
     * stamps the game's start anew (XServerScreen -> attachGame), the move to the top screen makes it again (a second relaunch), and
     * that one opens the bottom window. The snapshot the first activity kept must still be THIS game's: the new host opens restored
     * (the reader on from its place, the room by the adopt rule), not on Home with the whole-log replay.
     */
    @Test fun aReopenFromTheBottomGridOpensRestored() {
        val bottom = bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host())?.let { offset(it) == log.length() } == true }
        showRoom(host(), Room.SETTINGS)
        screen().attachGame(sink)
        LauncherHooks.gameStartSec = 1_000L                    // the game came up long ago (its stamp is not this second's)
        // the minimize; the add-on writes on while away
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
        write("""{"type":"quests","q":{"n":2}}""")
        // the reopen from the bottom grid: made again on the bottom screen (the game's display now: no bottom window), its game screen up
        LauncherHooksActivity.onDisplay = bottom
        c.recreate()
        c.start().resume(); settle(500)
        screen().attachGame(sink)
        assertNull("no bottom window while the launcher is on the bottom screen", shown())
        // the move to the top screen: made again there, and the bottom window opens
        LauncherHooksActivity.onDisplay = null
        c.recreate()
        settle(6_000) { shown() != null && tail(host()) != null }
        screen().attachGame(sink)
        assertTrue("rebuilt restored: ${BottomSync.line()}", BottomSync.line().contains("a configuration relaunch"))
        val t2 = tail(host())!!
        settle(2_000) { offset(t2) == log.length() }
        assertEquals(log.length(), offset(t2))
        write("""{"type":"interact","holder":"pad:settings"}""")
        settle(3_000) { room(host()) == Room.SETTINGS }
        assertEquals("the room adopted (the game still holds it)", Room.SETTINGS, room(host()))
        c.pause().stop().destroy()
        settle(300)
    }
}
