package com.jrees.wowcompanion

import android.view.Display
import android.view.View
import com.jrees.wowcompanion.BottomParkLifecycleTest.LauncherHooksActivity
import com.jrees.wowcompanion.fork.DsForeverScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            "held by (${holders.size}, ${HostHolders.walked} objects walked from ${HostHolders.roots} roots):\n" + holders.joinToString("\n") { "  ${it.path}" } + "\n  live threads: $threads",
            emptyList<String>(), kept)
    }

    // Every step that touches a host runs in a function of its own and hands back WEAK references only: a host left in a local of the
    // test's own frame is a GC root the heap walk cannot see (the interpreter keeps a finished loop's locals in their slots)

    /** the host on show now: every room on it, then its references (weakly) */
    private fun shownRefs(tag: String): List<Pair<String, WeakReference<Any>>> {
        val h = host()
        everyRoom(h)
        return refsOf(h, tag)
    }

    /** the host on show (every room on it), then Android's own close of its window (a display change): the next host up */
    private fun closedByAndroid(tag: String): List<Pair<String, WeakReference<Any>>> {
        val refs = shownRefs(tag)
        val w = WeakReference(shown()!!)
        (w.get() as android.app.Dialog).cancel()
        settle(4_000) { shown() != null && shown() !== w.get() && tail(host()) != null }
        assertNotNull("the next host up", shown())
        return refs
    }

    /** the host on show (every room on it), then single screen mode's switch [on] / off (the wheel's button): the next host up */
    private fun singleSwitch(on: Boolean, tag: String): List<Pair<String, WeakReference<Any>>> {
        val refs = shownRefs(tag)
        val was = WeakReference<Any>(host())
        SingleScreen.request?.invoke(on, "the test")
        settle(4_000) { shown() != null && runCatching { host() }.getOrNull().let { it != null && it !== was.get() } }
        assertNotNull("the next host up", shown())
        return refs
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
        refs += shownRefs("host ${n + 1}")
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
        c.pause().stop().destroy()
        settle(500)
        return refs
    }
}
