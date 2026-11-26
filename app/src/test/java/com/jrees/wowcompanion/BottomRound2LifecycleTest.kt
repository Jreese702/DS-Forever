package com.jrees.wowcompanion

import android.view.Display
import android.view.View
import com.jrees.wowcompanion.BottomParkLifecycleTest.LauncherHooksActivity
import com.jrees.wowcompanion.fork.DsForeverScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * THE BOTTOM SCREEN COMES STRAIGHT BACK, ROUND 2 OF THE REVIEW (2026-11-26), on the REAL DsForeverScreen / HostPresentation /
 * CompanionHost driven through the launcher MainActivity's own hooks (BottomParkLifecycleTest's activity):
 * - a reopen from Cocoon's bottom grid (two configuration relaunches) brings back Home's open layer, Edit Home's session and the shown
 *   room's own page - not only the room - and the restored host counts the relaunch's game as up (no waiting screen);
 * - an activity of ours that was over the bottom screen but is no longer up at the return never keeps the window hidden;
 * - a park and a return keep a room's sub-state (Chat's keyboard): a park is no room exit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BottomRound2LifecycleTest {
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
        LauncherHooksActivity.onDisplay = null
        runCatching { DsForeverScreen.closeAll() }
        GameInput.away = false
        root.deleteRecursively()
    }

    /** [agoMs]: written that long ago by the game's clock (older than the input gate's 20 s = never current) */
    private fun write(json: String, agoMs: Long = 0L) {
        val c = Calendar.getInstance().apply { timeInMillis -= agoMs }
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
    private fun setField(o: Any, name: String, v: Any?) {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { c.getDeclaredField(name).apply { isAccessible = true }.set(o, v); return }
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
    private fun offset(t: LogTail): Long = field(t, "offset") as Long
    private fun room(h: CompanionHost): Room = field(h, "room") as Room
    private fun home(h: CompanionHost): HomeView = field(h, "homeView") as HomeView
    private fun showRoom(h: CompanionHost, r: Room) =
        h.javaClass.getDeclaredMethod("show", Room::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(h, r, false, false, false)

    /** two screens, the game in the world (a current document read), the launcher's game screen up */
    private fun inTheWorld(): org.robolectric.android.controller.ActivityController<LauncherHooksActivity> {
        bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host())?.let { offset(it) == log.length() } == true }
        assertNotNull("the bottom window is up", shown())
        screen().attachGame(sink)
        LauncherHooks.gameStartSec = 1_000L
        return c
    }

    /**
     * the reopen from Cocoon's bottom grid with the game running (device check D4): the minimize, Android making the launcher's
     * activity again ON THE BOTTOM SCREEN (a configuration relaunch), its game screen up there, the move to the top screen making it again
     * (a second relaunch); the restored host is up before that last activity's game screen (XServerScreen -> attachGame)
     */
    private fun reopenFromTheBottomGrid(c: org.robolectric.android.controller.ActivityController<LauncherHooksActivity>, bottom: Int) {
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
        // what the add-on wrote while away, a minute ago: replayed state, never current (the player stands still: no current one yet)
        write("""{"type":"quests","q":{"n":2}}""", agoMs = 60_000L)
        LauncherHooksActivity.onDisplay = bottom
        c.recreate()
        c.start().resume(); settle(500)
        screen().attachGame(sink)
        LauncherHooksActivity.onDisplay = null
        c.recreate()
        settle(6_000) { shown() != null && tail(host()) != null }
    }

    private fun bottomId(): Int = (shown() as android.app.Presentation).display.displayId

    @Test fun theBottomGridReturnKeepsHomesLayer() {
        val c = inTheWorld()
        val bottom = bottomId()
        assertTrue("Home's map module opens", home(host()).openMapModule())
        assertEquals(HomeModule.MAP, home(host()).flow.full)
        reopenFromTheBottomGrid(c, bottom)
        settle(1_000)
        assertEquals("Home's open layer laid back", HomeModule.MAP, home(host()).flow.full)
        c.pause().stop().destroy(); settle(300)
    }

    /** the restored host is made before the last activity's game screen is up: the same game runs on - no waiting screen */
    @Test fun theBottomGridReturnShowsNoWaitingScreen() {
        val c = inTheWorld()
        reopenFromTheBottomGrid(c, bottomId())
        assertEquals("no waiting screen", View.GONE, (field(host(), "startView") as View).visibility)
        c.pause().stop().destroy(); settle(300)
    }

    @Test fun theBottomGridReturnKeepsEditHome() {
        val c = inTheWorld()
        val bottom = bottomId()
        assertTrue("Edit Home opens", home(host()).editOpen())
        assertTrue(home(host()).editPresent)
        reopenFromTheBottomGrid(c, bottom)
        settle(1_000)
        assertTrue("Edit Home's session laid back", home(host()).editPresent)
        c.pause().stop().destroy(); settle(300)
    }

    @Test fun theBottomGridReturnKeepsTheRoomsPage() {
        val c = inTheWorld()
        val bottom = bottomId()
        showRoom(host(), Room.SOCIAL)
        val social = field(host(), "socialView") as SocialView
        social.openPage(SocialModel.Tab.GUILD)                                  // the game's own window on its Guild tab
        assertEquals("GUILD", (field(social, "page") as Enum<*>).name)
        reopenFromTheBottomGrid(c, bottom)
        // the first current interact document: the game still holds the controller for Social (its window open) - adopted, no tap
        write("""{"type":"interact","holder":"social"}""")
        settle(3_000) { room(host()) == Room.SOCIAL }
        assertEquals(Room.SOCIAL, room(host()))
        assertEquals("the room's own page laid back", "GUILD", (field(field(host(), "socialView")!!, "page") as Enum<*>).name)
        c.pause().stop().destroy(); settle(300)
    }

    /**
     * THE REVIEW ROUND 2: Settings > import add-ons (or the DLL picker, the microphone question) up on the bottom screen, then the
     * Thor's Home; the activity is only stopped (excluded from Recents: the user cannot go back to it). At the return it is no longer up
     * on the bottom screen: the window shows (it stayed hidden for good before)
     */
    @Test fun anActivityNoLongerUpAtTheReturnNeverKeepsTheWindowHidden() {
        val c = inTheWorld()
        val win = (shown() as android.app.Dialog).window!!
        val open = host().javaClass.getDeclaredMethod("openOnBottom", Class::class.java, String::class.java, Function1::class.java)
            .apply { isAccessible = true }
        val setClosed: ((() -> Unit)?) -> Unit = { }
        assertTrue(open.invoke(host(), VoiceAskActivity::class.java, "Voice", setClosed) as Boolean)
        assertEquals(View.GONE, win.decorView.visibility)
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
        c.start().resume(); settle(500)
        assertEquals("the activity is not up any more (no task of it shows): the window is back", View.VISIBLE, win.decorView.visibility)
        c.pause().stop().destroy(); settle(300)
    }

    /** THE REVIEW ROUND 2: typing in the Chat room (DS Forever's keyboard up), then a minimize and the return - still typing */
    @Test fun aParkKeepsChatsKeyboard() {
        val c = inTheWorld()
        showRoom(host(), Room.CHAT)
        val chat = field(host(), "chatView") as ChatView
        chat.javaClass.getDeclaredMethod("setBoard", ChatPad.Board::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(chat, ChatPad.Board.KEYS, false)
        assertEquals(ChatPad.Board.KEYS, field(chat, "board"))
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
        assertEquals("parked: the board as it was", ChatPad.Board.KEYS, field(chat, "board"))
        c.start().resume(); settle(500)
        assertEquals("back: still typing", ChatPad.Board.KEYS, field(chat, "board"))
        c.pause().stop().destroy(); settle(300)
    }
}
