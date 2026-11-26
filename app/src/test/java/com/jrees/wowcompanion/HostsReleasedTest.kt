package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Display
import android.view.View
import com.jrees.wowcompanion.BottomParkLifecycleTest.LauncherHooksActivity
import com.jrees.wowcompanion.fork.DsForeverScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
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
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.time.Duration
import java.util.Calendar

/**
 * A HOST THAT WENT IS LET GO (the bottom-sync branch's first review pass, 2026-11-26: every CompanionHost made again stayed alive - its
 * rooms, its Home, its map, its decoded art - held by some 27 process-wide listener lists older than this branch: CharacterBars,
 * BrowserLogin, CastSettings, FrameGen, GameUi, Settings, Theme, ImportedAddons, ThirdPartyAddons, LayoutModel.Live, RouteWork's
 * file listeners, the sweep broadcast receiver and others, registered by the host and by its Settings, Home and controller views and
 * never taken off; on an 8 GB Thor every real rebuild and every single screen mode switch kept one more whole host). The REAL
 * DsForeverScreen / HostPresentation / TopWindow / CompanionHost, driven through the launcher MainActivity's hooks (BottomParkLifecycleTest's
 * activity): hosts made, every room shown on each, each host gone the way the device lets one go (Android's own close of the bottom
 * window, a single screen mode switch, the launcher's end) - then, after the background workers and the main looper are quiet and a
 * full GC, NO host that went, and no view it held, may still be reachable. A failure names every holder (HostHolders: the walk from
 * every static, Android's process-wide objects and the live threads).
 *
 * The leak fix's review (2026-11-26): each host gets the game's documents (a full one: the controller's spells, the minimap, the bags; the
 * quests; the chat) and draws the Settings pictures (the Settings stage - PreviewStage - with its own context, as the Bottom screen tiles,
 * the Theme maker, the controller pictures, the Message row's keyboard and the cast bar question make it), so the process-wide stage and
 * its DS toolbar hook are in the run; the hosts made again are let go at THEIR end, while the newest is up (never only at the launcher's
 * end); a parked host let go for memory is collectable while the launcher is still up; a host whose start throws part way is ended; and
 * the launcher's activity, its DS Forever and the windows go with the launcher's end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class HostsReleasedTest {
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
        stageFresh()
    }

    /** the Settings stage made by THIS test's hosts: an earlier test class may have left one (made with the application: no host in it),
     *  and its documents (the stage is drawn only once a host fed it a full document) */
    private fun stageFresh() {
        for (f in listOf("tree", "kb", "stripView", "full", "quests", "chat"))
            PreviewStage::class.java.getDeclaredField(f).apply { isAccessible = true }.set(PreviewStage, null)
        PreviewStage.roomBar = { null }; PreviewStage.roomBarOn = { false }
    }

    @After fun down() {
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
    private fun showRoom(h: CompanionHost, r: Room) =
        h.javaClass.getDeclaredMethod("show", Room::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(h, r, false, false, false)

    /** every room once on [h] (each makes its views and their registrations), then Home again */
    private fun everyRoom(h: CompanionHost) {
        for (r in Room.values()) { runCatching { showRoom(h, r) }; settle(120) }
        showRoom(h, Room.HOME); settle(200)
    }

    /** the game's documents as a host gets them on the device (CastBarAskShotsTest's sample values: a level-6 Shaman's eight spells,
     *  Durotar's minimap, the bags; a focused quest; a few chat lines) */
    private fun gameDocs() {
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val nn = JSONArray()
        for (p in 1..8) nn.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        write(JSONObject().put("type", "full")
            .put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
                .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", nn)))
                .put("hudS", JSONObject().put("c", false).put("g", 1000.0)))
            .put("map", JSONObject().put("ok", true).put("mapID", 1411).put("zone", "Durotar").put("subzone", "Razor Hill")
                .put("rect", JSONObject().put("c", 1).put("t", 1808.33).put("b", -1716.67).put("l", -1962.5).put("r", -7250.0))
                .put("x", 0.5177).put("y", 0.4279).put("wx", 300.0).put("wy", -4700.0).put("facing", 2.11))
            .put("bags", JSONObject().put("money", 1_234_567L).put("freeGeneral", 14).put("free", 14).put("total", 66)).toString())
        write(JSONObject().put("type", "quests").put("q", JSONObject().put("plv", 6).put("list", JSONArray()
            .put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup").put("lv", 6).put("watch", 1).put("focus", true).put("zone", "Durotar")
                .put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10")))))).toString())
        val lines = JSONArray()
        for ((ch, who, text) in listOf(Triple("say", "Grukk", "anyone up for Burning Blade Coven?"), Triple("guild", "Zalaya", "grats on 6!"),
                Triple("system", "", "You have learned a new spell: Earth Shock."))) lines.put(JSONObject().put("ch", ch).put("who", who).put("text", text).put("i", lines.length() + 1))
        write(JSONObject().put("type", "chat").put("chat", JSONObject().put("lines", lines).put("s", 1)).toString())
    }

    private fun offset(t: LogTail): Long = field(t, "offset") as Long

    /**
     * the game's documents read by [h], then the Settings pictures drawn with [h]'s context as its own views pass theirs: the Theme
     * maker's mini Home (the host's own hook), the Bottom screen tiles' stage (BottomPictures), the controller pictures' class wheel,
     * the Message row's keyboard and the one-time cast bar question's frames (AskFrames(this) in the host)
     */
    private fun previews(h: CompanionHost) {
        gameDocs()
        settle(3_000) { tail(h)?.let { offset(it) == log.length() } == true && PreviewStage.ready }
        assertTrue("the host read the documents and fed the Settings stage", PreviewStage.ready)
        val b = Bitmap.createBitmap(PreviewStage.W / 4, PreviewStage.H / 4, Bitmap.Config.ARGB_8888)
        ThemeMakerPage.homeRender?.invoke(Theme.cur, b)
        PreviewStage.render(h, PreviewStage.playerLook(), true, null, b)
        PreviewStage.classWheel(h, Canvas(b), b.width.toFloat(), b.height.toFloat(), true, -1)
        PreviewStage.keyboard(h, Canvas(b), b.width.toFloat(), b.height.toFloat())
        val ask = PreviewStage.AskFrames(h, CastBarAsk.pictureBar(null, listOf(403 to 136048, 8042 to 136026), { true }), 0.1f)
        repeat(3) { ask.advance() }
        ask.drop()
        settle(200)
    }

    /** the launcher's own: its activity, its DS Forever, the bottom window on show (weakly) */
    private fun launcherRefs(a: android.app.Activity): List<Pair<String, WeakReference<Any>>> = listOf(
        "the launcher's activity" to WeakReference<Any>(a), "the launcher's DS Forever" to WeakReference<Any>(screen()),
        "the last window" to WeakReference<Any>(shown()!!))

    /** the host itself and every view it keeps in a field of its own, weakly (the test must hold none of them) */
    private fun refsOf(h: CompanionHost, tag: String): List<Pair<String, WeakReference<Any>>> {
        val out = ArrayList<Pair<String, WeakReference<Any>>>()
        out += "$tag CompanionHost" to WeakReference<Any>(h)
        var c: Class<*>? = h.javaClass
        while (c != null && c != Any::class.java && !c.name.startsWith("android.")) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val v = runCatching { f.isAccessible = true; f.get(h) }.getOrNull() ?: continue
                if (v is View) out += "$tag ${f.name} (${v.javaClass.simpleName})" to WeakReference<Any>(v)
            }
            c = c.superclass
        }
        return out
    }

    /** the companion's own background workers idle and the main looper empty, a few rounds (HostHolders.quietWorkers) */
    private fun quiet() = HostHolders.quietWorkers { ShadowSystemClock.advanceBy(Duration.ofMillis(50)); ShadowLooper.idleMainLooper() }

    /** every ref that is still reachable after the workers are quiet and a full GC (several, with time for threads to end) */
    private fun stillHeld(refs: List<Pair<String, WeakReference<Any>>>): List<String> {
        // ROBOLECTRIC ONLY: its ShadowDialog keeps every Dialog ever shown (the bottom Presentation, single screen mode's dialogs) for the
        // test's life - never on a device, where a dismissed window is nobody's; its list is let go here (every window is closed by now)
        runCatching { Class.forName("org.robolectric.shadows.ShadowDialog").getMethod("reset").invoke(null) }
        for (i in 0 until 25) {
            quiet()
            System.gc(); System.runFinalization()
            if (refs.all { it.second.get() == null }) return emptyList()
            Thread.sleep(60)
        }
        return refs.filter { it.second.get() != null }.map { it.first }
    }

    private fun assertAllGone(what: String, refs: List<Pair<String, WeakReference<Any>>>) {
        val kept = stillHeld(refs)
        if (kept.isEmpty()) return
        val held = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        refs.mapNotNullTo(held) { it.second.get() }
        val holders = HostHolders.all({ o -> o in held })
        val threads = Thread.getAllStackTraces().keys.filter { it.isAlive }.map { it.name }.sorted()
        assertEquals("$what: still reachable (${kept.size}): ${kept.take(12)}${if (kept.size > 12) " ..." else ""}\n" +
            "held by (${holders.size}, ${HostHolders.walked} objects walked from ${HostHolders.roots} roots):\n" + holders.joinToString("\n") { "  ${it.path}" } +
            "\n  queued on the main looper for them: ${queuedFor(held)}\n  live threads: $threads",
            emptyList<String>(), kept)
    }

    /** (int/1534, a failure's words) every main-looper message whose callback holds one of [held] directly: the host's own field it is
     *  (a Runnable kept in a field), else its class, and how long until it runs */
    private fun queuedFor(held: Set<Any>): List<String> = runCatching {
        val q = android.os.Looper.getMainLooper().queue
        val head = android.os.MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
        val next = android.os.Message::class.java.getDeclaredField("next").apply { isAccessible = true }
        val now = SystemClock.uptimeMillis()
        val out = ArrayList<String>()
        var m = head.get(q) as android.os.Message?
        var n = 0
        while (m != null && n++ < 100_000) {
            val cb = m.callback
            if (cb != null) {
                val args = cb.javaClass.declaredFields.mapNotNull { f -> runCatching { f.isAccessible = true; f.get(cb) }.getOrNull() }
                val owner = args.firstOrNull { it in held }
                if (owner != null) {
                    var name: String? = null
                    var k: Class<*>? = owner.javaClass
                    while (name == null && k != null && k != Any::class.java) {
                        name = k.declaredFields.firstOrNull { f -> !Modifier.isStatic(f.modifiers) && runCatching { f.isAccessible = true; f.get(owner) === cb }.getOrDefault(false) }?.name
                        k = k.superclass
                    }
                    out += "${owner.javaClass.simpleName}.${name ?: cb.javaClass.name} in ${m.`when` - now} ms"
                }
            }
            m = next.get(m) as android.os.Message?
        }
        out
    }.getOrElse { listOf("(${it.message})") }

    // Every step that touches a host runs in a function of its own and hands back WEAK references only: a host left in a local of the
    // test's own frame is a GC root the heap walk cannot see (the interpreter keeps a finished loop's locals in their slots)

    /** the host on show now: every room on it, the rooms' tab rows stepped with L / R, then its references (weakly) */
    private fun shownRefs(tag: String): List<Pair<String, WeakReference<Any>>> {
        val h = host()
        everyRoom(h)
        previews(h)
        tabSteps(h)
        return refsOf(h, tag)
    }

    /**
     * THE ROOMS' TAB ROW ON TOP (test 1533; int/1534): L / R through the host's own step (the one its RoomTabsPad reader runs:
     * roomTabsShoulder) on every room with a tab row along the top, then Home again; Social's page must move (R the next tab, L back)
     */
    private fun tabSteps(h: CompanionHost) {
        val step = h.javaClass.getDeclaredMethod("roomTabsShoulder", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val pages = listOf("FRIENDS", "GUILD", "RAID")
        fun social() = field(field(h, "socialView")!!, "page").toString()
        for (r in listOf(Room.SOCIAL, Room.GROUPFINDER, Room.PROFESSIONS, Room.CHARACTER, Room.SPELLBOOK, Room.TALENTS, Room.MACROS)) {
            runCatching { showRoom(h, r) }; settle(100)
            val before = if (r == Room.SOCIAL) social() else null
            step.invoke(h, true); settle(60)
            if (before != null) assertEquals("Social: R opens the next tab", pages.getOrElse(pages.indexOf(before) + 1) { before }, social())
            val after = if (r == Room.SOCIAL) social() else null
            step.invoke(h, false); settle(60)
            if (before != null && after != before) assertEquals("Social: L the one before", before, social())
        }
        showRoom(h, Room.HOME); settle(200)
    }

    /** the host on show (every room on it), then Android's own close of its window (a display change): the next host up */
    private fun closedByAndroid(tag: String): List<Pair<String, WeakReference<Any>>> {
        val refs = shownRefs(tag)
        val w = WeakReference(shown()!!)
        (w.get() as android.app.Dialog).cancel()
        settle(4_000) { shown() != null && shown() !== w.get() && tail(host()) != null }
        assertNotNull("the next host up", shown())
        return refs + ("$tag's window" to WeakReference<Any>(w.get()))
    }

    /** the host on show (every room on it), then single screen mode's switch [on] / off (the wheel's button): the next host up */
    private fun singleSwitch(on: Boolean, tag: String): List<Pair<String, WeakReference<Any>>> {
        val refs = shownRefs(tag)
        val was = WeakReference<Any>(host())
        val win = WeakReference<Any>(shown()!!)
        SingleScreen.request?.invoke(on, "the test")
        settle(4_000) { shown() != null && runCatching { host() }.getOrNull().let { it != null && it !== was.get() } }
        assertNotNull("the next host up", shown())
        return refs + ("$tag's window" to win)
    }

    /** two screens, the game in the world, [n] times Android's own close of the bottom window (a host made again each time), then the
     *  launcher's end: none of the n + 1 hosts may stay */
    private fun hostsMadeAgainAreLetGo(n: Int) = assertAllGone("${n + 1} hosts gone", madeAgain(n))
    /** (the launcher's activity too lives only in this frame: nothing of the run is left on the test's stack for the GC check) */
    private fun madeAgain(n: Int): List<Pair<String, WeakReference<Any>>> {
        bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host()) != null }
        assertNotNull("the bottom window is up", shown())
        screen().attachGame(sink)
        val refs = ArrayList<Pair<String, WeakReference<Any>>>()
        for (i in 1..n) refs += closedByAndroid("host $i")
        // (the leak fix's review) each host is let go at ITS end, while the newest is up - never only once the launcher goes
        if (n > 0) assertAllGone("hosts 1..$n while host ${n + 1} is up", refs)
        refs += shownRefs("host ${n + 1}")
        refs += launcherRefs(c.get())
        c.pause().stop().destroy()                             // the launcher's end: the last one goes too
        settle(500)
        return refs
    }

    @Test fun oneHostThatWentIsLetGo() = hostsMadeAgainAreLetGo(0)

    @Test fun twoHostsThatWentAreLetGo() = hostsMadeAgainAreLetGo(1)

    @Test fun threeHostsThatWentAreLetGo() = hostsMadeAgainAreLetGo(2)

    /**
     * SINGLE SCREEN MODE'S SWITCH (the wheel's button): on, the bottom host goes and single screen mode's top window makes its own
     * (fork/TopWindow); off, that one goes and a bottom host is made again. Twice, then the launcher's end: none may stay
     */
    @Test fun singleScreenSwitchesLetTheReplacedHostsGo() = assertAllGone("single screen switches", singleSwitches())
    private fun singleSwitches(): List<Pair<String, WeakReference<Any>>> {
        bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host()) != null }
        screen().attachGame(sink)
        val refs = ArrayList<Pair<String, WeakReference<Any>>>()
        for (i in 1..2) {
            refs += singleSwitch(true, "two screens $i")
            refs += singleSwitch(false, "one screen $i")
        }
        refs += shownRefs("the last")
        refs += launcherRefs(c.get())
        c.pause().stop().destroy()
        settle(500)
        return refs
    }

    /**
     * THE MEMORY RELEASE WHILE AWAY (the user's pick: a parked host is kept until Android signals real memory pressure): minimized, the
     * bottom window parked, then Android's trim lets it go - that host must be collectable THEN, with the launcher still up (else the
     * release frees nothing and the return builds a second whole host beside it), and a recording that runs out while no host exists
     * still saves its report. Back: a host made again, restored; then the launcher's end - none of them may stay
     */
    @Test fun aHostLetGoForMemoryWhileAwayIsCollectedThen() = assertAllGone("the host let go for memory and the one after", releasedWhileAway())
    private fun releasedWhileAway(): List<Pair<String, WeakReference<Any>>> {
        bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host()) != null }
        screen().attachGame(sink)
        val first = ArrayList(shownRefs("the parked host"))
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
        assertTrue("parked", parkedNow())
        (field(screen(), "trims") as ComponentCallbacks2).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        settle(1_000) { shown() == null }
        assertNull("let go for memory", shown())
        assertNotNull("a recording that runs out while no host exists still saves its report", DiagLog.onEndedByItself)
        assertAllGone("the host let go for memory, the launcher still up", first)
        c.start().resume()
        settle(4_000) { shown() != null && tail(host()) != null }
        assertNotNull("made again at the return", shown())
        val refs = ArrayList(first)
        refs += shownRefs("the host made again")
        refs += launcherRefs(c.get())
        c.pause().stop().destroy()
        settle(500)
        return refs
    }
    private fun parkedNow(): Boolean = host().parked

    /**
     * A HOST WHOSE START THROWS PART WAY (the leak fix's review: a decode's OutOfMemoryError while it is made again right after a memory
     * release on an 8 GB unit - "its host could not be made"): the throw goes on to its window (openBottom's catch and the watchdog's
     * next try), and everything the host registered before it is taken off - the host itself let go, its count given back
     */
    @Test fun aHostWhoseStartThrowsIsEndedAndLetGo() {
        val before = CompanionHost.live
        assertAllGone("a host whose start threw", failedStart())
        assertEquals("no host left counted", before, CompanionHost.live)
    }
    private fun failedStart(): List<Pair<String, WeakReference<Any>>> {
        var made: WeakReference<Any>? = null
        val shell = object : HostShell {
            // late in onCreate: nearly every registration of the host and its views is made by now
            override fun setHostContent(v: View) { made = WeakReference<Any>(v.context); throw IllegalStateException(FAIL) }
            override fun hostGone() = false
            override fun recreateHost() {}
            override fun hostWindow(): android.view.Window? = null
            override fun hostIntent(): android.content.Intent? = null
            override fun startHostActivity(i: android.content.Intent) {}
        }
        val t = runCatching { CompanionHost.made(app, shell, null) }.exceptionOrNull()
        assertEquals("the throw goes on to the window", FAIL, t?.message)
        assertNotNull("the host's start got as far as its content", made)
        settle(300)
        return listOf("the host whose start threw" to made!!)
    }
    private companion object { const val FAIL = "the test: a throw part way through the host's start" }

    // ---------------------------------------------------------------- int/1534: TEST 1533'S OWN PATHS (the leak fix over the code 1533 added)

    /** hold to cast's launcher side for one test: its fire timer and its later checks on the main looper, as the launcher's own (an earlier
     *  class in the same run may have set its own: HoldCastShotsTest), and everything of it clean before and after */
    private fun <T> withHoldCast(body: () -> T): T {
        val sched0 = HoldCast.schedule; val later0 = HoldCast.later
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val fire = Runnable { HoldCast.fireNow() }
        HoldCast.schedule = { at -> h.removeCallbacks(fire); if (at != HoldRelease.NEVER) h.postAtTime(fire, at) }
        HoldCast.later = { ms, f -> h.postDelayed({ f() }, ms) }
        HoldCast.padReset(true)
        try { return body() } finally {
            HoldCast.padReset(true)
            HoldCast.onMap(JSONObject().put("type", "holdmap").put("on", false).put("why", "none"))
            HoldCast.schedule = sched0; HoldCast.later = later0
        }
    }

    /** the host's two senders to the add-on that wait out a fight on the main looper (test 1533's hold bump, PadMap m202 / Book b88; the
     *  hold time, m194 / b82): which of them is queued now */
    private fun sendersQueued(h: CompanionHost): List<String> {
        val ui = field(h, "ui") as android.os.Handler
        return listOf("holdAgain", "bumpAgain").filter { ui.hasCallbacks(field(h, it) as Runnable) }
    }

    /** a current padmap in a fight whose hold time (`hold.n`) and bump (`hold.hb`) are not the app's: both senders wait the fight out */
    private fun fightPadmap() {
        val n = if (CastSettings.holdTime.ticks == 4) 3 else 4
        val hb = if (HoldCast.release.bumpSteps == 1) 2 else 1
        write(JSONObject().put("type", "padmap").put("v", "m203").put("m", JSONObject().put("combat", true)
            .put("hold", JSONObject().put("n", n).put("hb", hb).put("lk", 5))).toString())
    }

    /** hold to cast (test 1533): the add-on's holdmap (its fire point far off: 3 s frames), X held (the launcher's write), the add-on's keep
     *  word through the host (facecast) - CONFIRMED; then Android's cancelled up (the keys moved to the other screen's window): KEPT */
    private fun keptX() {
        write(JSONObject().put("type", "holdmap").put("on", true).put("b", "bare:5").put("why", "on").put("cn", 3).put("ft", 3000).toString())
        settle(3_000) { HoldCast.mapWhy == "on" && HoldCast.release.cn == 3 }
        assertEquals("the host gave hold to cast the add-on's holdmap", 3, HoldCast.release.cn)
        HoldCast.padIn(1 shl 2, false, false, false, false, SystemClock.uptimeMillis())
        write(JSONObject().put("type", "facecast").put("pos", 5).put("hk", "keep").put("kind", "holdkeep").put("lay", "bare").put("key", "PAD3")
            .put("need", 3).toString())
        settle(3_000) { HoldCast.release.state(HoldRelease.X) == HoldRelease.CONFIRMED }
        assertEquals("the add-on kept X's press", HoldRelease.CONFIRMED, HoldCast.release.state(HoldRelease.X))
        HoldCast.canceling(true)
        try { HoldCast.padIn(0, false, false, false, false, SystemClock.uptimeMillis()) } finally { HoldCast.canceling(false) }
        assertEquals("KEPT: down for the game to its fire point", HoldRelease.KEPT, HoldCast.release.state(HoldRelease.X))
        assertEquals(HoldRelease.bitOf(HoldRelease.X), HoldCast.keptMask())
    }

    /** a press KEPT goes for the game at its fire point by the launcher's own timer, with or without a host: never a down left in the game */
    private fun keptLetGo(what: String) {
        settle(18_000) { HoldCast.keptMask() == 0 }
        assertEquals("$what: X let go for the game at its fire point", 0, HoldCast.keptMask())
        assertEquals(0, HoldCast.forcedMask())
        assertEquals(HoldRelease.IDLE, HoldCast.release.state(HoldRelease.X))
    }

    /**
     * HOLD TO CAST IN A FIGHT (test 1533): the host's padmap says the add-on's hold time and bump are not the app's, so its two senders
     * wait out the fight on the main looper (holdAgain / bumpAgain, every TopCombatBar.RESEND_MS), and X is KEPT. Android's own close of
     * the window then: the old host must go at ITS end while the next is up (never kept by its senders' reposting, never one of them
     * running for it) and the kept press goes at its fire point with no host. Then a minimize (the park) with the same on the next host:
     * nothing of the parked host runs while away, the press goes while away. Back, then the launcher's end: none may stay
     */
    @Test fun aHostEndedOrParkedInAFightWithAPressKeptIsLetGo() = withHoldCast { assertAllGone("hosts in a fight with a press kept", keptInAFight()) }
    private fun keptInAFight(): List<Pair<String, WeakReference<Any>>> {
        bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host()) != null }
        screen().attachGame(sink)
        val refs = ArrayList<Pair<String, WeakReference<Any>>>()
        refs += keptThenClosed("the host closed with X kept")
        assertAllGone("the host closed by Android in a fight with X kept, while the next is up", refs)
        keptLetGo("its host gone")
        refs += keptThenParked(c)
        refs += launcherRefs(c.get())
        c.pause().stop().destroy()
        settle(500)
        return refs
    }
    private fun fightAndKept(tag: String): List<Pair<String, WeakReference<Any>>> {
        val refs = shownRefs(tag)
        val h = WeakReference(host())
        fightPadmap()
        settle(3_000) { h.get()?.let { sendersQueued(it).size == 2 } == true }
        assertEquals("$tag: its senders wait out the fight", listOf("holdAgain", "bumpAgain"), h.get()?.let { sendersQueued(it) })
        keptX()
        return refs
    }
    private fun keptThenClosed(tag: String): List<Pair<String, WeakReference<Any>>> {
        val refs = fightAndKept(tag)
        val w = WeakReference(shown()!!)
        (w.get() as android.app.Dialog).cancel()
        settle(4_000) { shown() != null && shown() !== w.get() && tail(host()) != null }
        assertNotNull("the next host up", shown())
        assertEquals("X still KEPT for the game (its fire point not come yet)", HoldRelease.KEPT, HoldCast.release.state(HoldRelease.X))
        return refs + ("$tag's window" to WeakReference<Any>(w.get()))
    }
    private fun keptThenParked(c: org.robolectric.android.controller.ActivityController<LauncherHooksActivity>): List<Pair<String, WeakReference<Any>>> {
        val refs = fightAndKept("the host parked with X kept")
        val h = WeakReference(host())
        c.get().onUserLeaveHint(); c.pause().stop(); settle(300)
        assertTrue("parked", parkedNow())
        assertEquals("parked: neither sender runs while away", emptyList<String>(), h.get()?.let { sendersQueued(it) })
        keptLetGo("parked")
        assertTrue("still parked", parkedNow())
        assertEquals("parked: neither sender came back while away", emptyList<String>(), h.get()?.let { sendersQueued(it) })
        c.start().resume()
        settle(1_000) { !parkedNow() }
        assertTrue("back: the same host", h.get() === host() && !parkedNow())
        return refs
    }

    /**
     * SINGLE SCREEN MODE'S WAITING ROW AT THE TOP (test 1533, the user's ruling "Top of the screen"): the add-on's logout word brings the
     * waiting page, which single screen mode shows as its row alone over the game (the strip "start", as high as the row: StartView's
     * onBareNeed, asked for after its draw, posted). The row grows (the region line comes) and Android closes the top window before that
     * post runs: the dead host must never place a strip window for its dead top window (a window over the game's top nobody closes),
     * and it must go while the window made again is up; then the launcher's end - none may stay
     */
    @Test fun singleScreensWaitingRowLetsItsHostGo() = assertAllGone("single screen mode's waiting row", waitingRow())
    private fun waitingRow(): List<Pair<String, WeakReference<Any>>> {
        bottomDisplay()
        write("""{"type":"quests","q":{"n":1}}""")
        val c = Robolectric.buildActivity(LauncherHooksActivity::class.java).setup()
        settle(6_000) { shown() != null && tail(host()) != null }
        screen().attachGame(sink)
        val refs = ArrayList<Pair<String, WeakReference<Any>>>()
        refs += singleSwitch(true, "two screens")
        refs += rowThenClosed("the waiting row's host")
        val oldTop = refs.last().second
        assertEquals("a top window that went never gets a window again", 0, oldTop.get()?.let { (field(it, "subs") as Map<*, *>).size } ?: 0)
        assertAllGone("the waiting row's host and its top window, while the one made again is up", refs)
        refs += shownRefs("the top window made again")
        refs += launcherRefs(c.get())
        c.pause().stop().destroy()
        settle(500)
        return refs
    }
    private fun rowThenClosed(tag: String): List<Pair<String, WeakReference<Any>>> {
        val refs = shownRefs(tag)
        val h = WeakReference(host())
        write("""{"type":"bye"}""")
        settle(3_000) { h.get()?.let { field(it, "stripKind") == SingleNpc.START } == true }
        assertEquals("the waiting row shown", SingleNpc.START, h.get()?.let { field(it, "stripKind") })
        rowGrows(h)
        val w = WeakReference(shown()!!)
        (w.get() as android.app.Dialog).cancel()
        settle(4_000) { shown() != null && shown() !== w.get() && tail(host()) != null }
        assertNotNull("the top window made again", shown())
        return refs + ("$tag's window" to WeakReference<Any>(w.get()))
    }
    /** the row drawn, then the region line comes and it is drawn again: its new height is asked for (posted, not run yet) */
    private fun rowGrows(h: WeakReference<CompanionHost>) {
        val sv = field(h.get()!!, "startView") as StartView
        assertTrue("the row alone over the game", sv.bare)
        assertTrue("the row in its strip window", sv.isAttachedToWindow)
        val bmp = Bitmap.createBitmap(maxOf(1, sv.width), maxOf(1, sv.height), Bitmap.Config.ARGB_8888)
        sv.draw(Canvas(bmp))
        sv.region = if (sv.region == null) GameRegion.Note("EU", "US") else null
        sv.draw(Canvas(bmp))
    }
}
