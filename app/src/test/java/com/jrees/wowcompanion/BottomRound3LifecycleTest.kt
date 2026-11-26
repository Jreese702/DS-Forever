package com.jrees.wowcompanion

import android.view.Display
import com.jrees.wowcompanion.BottomParkLifecycleTest.LauncherHooksActivity
import com.jrees.wowcompanion.fork.DsForeverScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
 * THE BOTTOM SCREEN COMES STRAIGHT BACK, ROUND 3 OF THE REVIEW (2026-11-26), on the REAL DsForeverScreen / HostPresentation /
 * CompanionHost driven through the launcher MainActivity's own hooks (BottomParkLifecycleTest's activity):
 * - a reopen from Cocoon's bottom grid (two configuration relaunches: a rebuild) brings back Chat's draft and its keyboard, Bags' clean
 *   view and the Spellbook's tab and page - not only the room - when the game still holds that room's window;
 * - the old Home lets go of an Edit Home session it handed to the new one;
 * - single screen mode ending with the game (the host's own start over) is no rebuild in the report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BottomRound3LifecycleTest {
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
        runCatching { SingleScreen::class.java.getDeclaredMethod("reset").apply { isAccessible = true }.invoke(SingleScreen) }
        GameInput.away = false
        root.deleteRecursively()
    }

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

    /** BottomRound2LifecycleTest's reopen from Cocoon's bottom grid: the minimize, two configuration relaunches, the restored host */
    private fun reopenFromTheBottomGrid(c: org.robolectric.android.controller.ActivityController<LauncherHooksActivity>, bottom: Int) {
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
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

    /** the first current interact document says the game still holds [holder]'s window: the room adopted (no tap) */
    private fun adopted(holder: String, r: Room) {
        write("""{"type":"interact","holder":"$holder"}""")
        settle(3_000) { room(host()) == r }
        assertEquals(r, room(host()))
        settle(400)
    }

    @Test fun theBottomGridReturnKeepsChatsDraftAndKeyboard() {
        val c = inTheWorld()
        val bottom = bottomId()
        showRoom(host(), Room.CHAT)
        val chat = field(host(), "chatView") as ChatView
        setField(chat, "typed", "half a mess")
        chat.javaClass.getDeclaredMethod("setBoard", ChatPad.Board::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(chat, ChatPad.Board.KEYS, false)
        reopenFromTheBottomGrid(c, bottom)
        adopted("pad:chat", Room.CHAT)
        val chat2 = field(host(), "chatView") as ChatView
        assertNotSame("a rebuild: a new Chat", chat, chat2)
        assertEquals("what was written", "half a mess", field(chat2, "typed"))
        assertEquals("still typing", ChatPad.Board.KEYS, field(chat2, "board"))
        c.pause().stop().destroy(); settle(300)
    }

    @Test fun theBottomGridReturnKeepsBagsCleanView() {
        val c = inTheWorld()
        val bottom = bottomId()
        showRoom(host(), Room.BAGS)
        val bags = field(host(), "bagsView") as BagsView
        val st = field(bags, "state")!!
        st.javaClass.getDeclaredMethod("toggleClean").invoke(st)
        assertEquals(true, field(st, "clean"))
        reopenFromTheBottomGrid(c, bottom)
        adopted("bags", Room.BAGS)
        assertEquals("the clean view laid back", true, field(field(field(host(), "bagsView")!!, "state")!!, "clean"))
        c.pause().stop().destroy(); settle(300)
    }

    @Test fun theBottomGridReturnKeepsTheSpellbooksTabAndPage() {
        val c = inTheWorld()
        val bottom = bottomId()
        showRoom(host(), Room.SPELLBOOK)
        val book = field(host(), "spellbookView") as SpellbookView
        setField(book, "tabKey", "tab-2"); setField(book, "page", 3)
        reopenFromTheBottomGrid(c, bottom)
        adopted("spells", Room.SPELLBOOK)
        val book2 = field(host(), "spellbookView")!!
        assertEquals("the game's window is still on its tab", "tab-2", field(book2, "tabKey"))
        assertEquals(3, field(book2, "page"))
        c.pause().stop().destroy(); settle(300)
    }

    @Test fun anEditSessionHandedOverIsLetGoByTheOldHome() {
        val c = inTheWorld()
        val bottom = bottomId()
        val old = home(host())
        assertTrue("Edit Home opens", old.editOpen())
        reopenFromTheBottomGrid(c, bottom)
        settle(1_000)
        assertTrue("laid back on the new Home", home(host()).editPresent)
        assertNotSame(old, home(host()))
        assertNull("the old Home holds no session any more", field(old, "edit"))
        assertNull(field(old, "editOwner"))
        c.pause().stop().destroy(); settle(300)
    }

    /** single screen mode on (the wheel's button), then the game ends: the mode goes with it (the host's own start over) - no drop */
    @Test fun singleScreenEndingWithTheGameIsNoRebuild() {
        val c = inTheWorld()
        SingleScreen.request?.invoke(true, "the test")
        settle(4_000) { SingleScreen.inUse && shown() != null }
        assertTrue(SingleScreen.inUse)
        screen().detachGame()
        settle(4_000) { !SingleScreen.inUse && shown() is android.app.Presentation }
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 0"))
        c.pause().stop().destroy(); settle(300)
    }
}
