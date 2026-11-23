package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import com.jrees.wowcompanion.IndoorKeepAlive.Machine
import com.jrees.wowcompanion.IndoorKeepAlive.Op
import com.jrees.wowcompanion.IndoorKeepAlive.State
import com.jrees.wowcompanion.IndoorSchedulerStep.Action
import com.jrees.wowcompanion.IndoorSchedulerStep.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue 568 (the user's ruling 2026-11-23): 1.0.38's builder may run after a game while DS Forever is minimized or the screen is off,
 * but then DS Forever is a CACHED app (importance 400) and Android's background CPU check kills it within ~5 minutes for the builder's
 * CPU use (the saved reports: "EXCESSIVE_RESOURCE_USAGE (importance 400) ... excessive cpu X during ~300000 dur=D limit=L"). A quiet
 * foreground service now runs exactly while the builder builds. These tests replay #573's evening with Android's two rules modelled:
 * a foreground service starts only in front (or in the few seconds after), and the CPU check looks only at cached processes.
 */
class IndoorKeepAliveTest {

    // ------------------------------------------------------------------ Android's rules, as the reports show them

    /** AOSP checkExcessivePowerUsage's limit by time in the background (POWER_CHECK_INTERVAL 5 min; MAX_CPU 25 / 25 / 10 / 2 %) */
    private fun limitFor(durMs: Long): Int = when {
        durMs <= 600_000L -> 25
        durMs <= 900_000L -> 10
        else -> 2
    }

    private val exitLine = Regex("""EXCESSIVE_RESOURCE_USAGE \(status \d+, importance (\d+), process main, pss \d+ MB\): (?:Caused by child process: )?excessive cpu (\d+) during (\d+) dur=(\d+) limit=(\d+)""")

    /** DS Forever's own exits from saved reports (numbers only), one per bucket and the 1.0.38-era ones the issue cites */
    private val savedExits = listOf(
        "EXCESSIVE_RESOURCE_USAGE (status 0, importance 400, process main, pss 754 MB): excessive cpu 413620 during 300054 dur=315060 limit=25",
        "EXCESSIVE_RESOURCE_USAGE (status 0, importance 400, process main, pss 566 MB): excessive cpu 371870 during 300041 dur=424549 limit=25",
        "EXCESSIVE_RESOURCE_USAGE (status 0, importance 400, process main, pss 285 MB): excessive cpu 191570 during 300031 dur=252657 limit=25",
        "EXCESSIVE_RESOURCE_USAGE (status 0, importance 400, process main, pss 341 MB): excessive cpu 37990 during 300133 dur=748064 limit=10",
        "EXCESSIVE_RESOURCE_USAGE (status 0, importance 400, process main, pss 276 MB): excessive cpu 19960 during 300107 dur=1028216 limit=2",
        "EXCESSIVE_RESOURCE_USAGE (status 0, importance 400, process main, pss 555 MB): excessive cpu 300480 during 300037 dur=477586 limit=25",
    )

    @Test fun theReportsKillsAreTheCachedAppCpuCheck() {
        for (line in savedExits) {
            val m = exitLine.find(line) ?: error("unparsed: $line")
            val (imp, cpu, during, dur, limit) = m.destructured
            assertEquals("cached when killed: $line", 400, imp.toInt())
            assertEquals("the limit is the one for its time in the background: $line", limitFor(dur.toLong()), limit.toInt())
            assertTrue("over the limit: $line", cpu.toLong() * 100 > during.toLong() * limit.toLong())
            assertTrue("one check interval: $line", during.toLong() in 299_000L..301_000L)
        }
        // the builder alone (74-94 % of a core, the 2026-11-07 measurement) is over every limit, the first one too
        assertTrue(74 > limitFor(0L))
    }

    // ------------------------------------------------------------------ #573's evening

    /**
     * [hooks]: what MainActivity / the game screen tell the keep-alive at that moment, in order ("resume", "leave" = leave hint +
     * pause + stop, "closed" = the game's close, "start" = Play / launch / game screen); [front]: DS Forever is in front right then
     * (or a few seconds after: a start is allowed); afterwards [shown] / [gameActive] / [played] hold for the moment.
     */
    private data class Moment(val what: String, val event: Event?, val hooks: List<String>, val front: Boolean,
                              val shown: Boolean, val gameActive: Boolean, val played: Boolean)

    private val evening = listOf(
        Moment("DS Forever opens, the launcher in front", Event.COMPANION_UP, listOf("resume"), true, shown = true, gameActive = false, played = false),
        Moment("Play: the game runs", null, listOf("start"), true, shown = true, gameActive = true, played = true),
        Moment("minimized during the game", Event.COMPANION_GONE, listOf("leave"), true, shown = false, gameActive = true, played = true),
        Moment("back in the game", Event.COMPANION_UP, listOf("resume"), true, shown = true, gameActive = true, played = true),
        Moment("quit to the Ready screen", Event.GAME_CLOSED, listOf("closed"), true, shown = true, gameActive = false, played = true),
        Moment("minimized", Event.COMPANION_GONE, listOf("leave"), true, shown = false, gameActive = false, played = true),
        Moment("the screen times out", null, emptyList(), false, shown = false, gameActive = false, played = true),
        Moment("back to DS Forever", Event.COMPANION_UP, listOf("resume"), true, shown = true, gameActive = false, played = true),
        Moment("the screen turns off on the Ready screen", Event.COMPANION_GONE, listOf("leave"), true, shown = false, gameActive = false, played = true),
        Moment("Play again", Event.COMPANION_UP, listOf("resume", "start"), true, shown = true, gameActive = true, played = true),
    )

    private data class Night(val building: List<String>, val service: List<String>, val cachedWhileBuilding: List<String>, val starts: Int)

    /**
     * Replays the evening. [withService] false = 1.0.38 (no service); true = the keep-alive's decisions through [Machine], each start
     * answered by Android's rule (allowed only when [Moment.front]). After each moment's hooks: is the builder building (alive and
     * [PlayGate.indoorWait] null, quiet = not shown), is the service on, and is the process cached while it builds (killable).
     */
    private fun replay(withService: Boolean, setting: Boolean = true): Night {
        var alive = false
        val m = Machine()
        var starts = 0
        val building = ArrayList<String>(); val service = ArrayList<String>(); val cached = ArrayList<String>()
        var t = 0L
        for (x in evening) {
            when (x.event?.let(IndoorSchedulerStep::on)) { Action.START -> alive = true; Action.STOP -> alive = false; else -> {} }
            val builds = setting && alive && PlayGate.indoorWait(false, false, x.gameActive, x.played, quiet = !x.shown) == null
            if (withService) {
                // every hook asks (front), then the builder's own status change right after (not front)
                for (front in x.hooks.map { true } + false) {
                    val want = IndoorKeepAlive.wanted(State(setting, hasWork = alive, gameActive = x.gameActive, streaming = false,
                        playedThisRun = x.played, shown = x.shown))
                    t += 1_000L
                    if (m.next(want, front, 0, t) == Op.START) { starts++; m.started(x.front, 0, t) }
                }
            }
            if (builds) building += x.what
            if (m.running) service += x.what
            if (builds && !x.shown && !m.running) cached += x.what
        }
        return Night(building, service, cached, starts)
    }

    @Test fun before_theBuilderBuiltOnlyAsACachedAppAndWasKilledForCpu() {
        val n = replay(withService = false)
        assertEquals(listOf("minimized", "the screen times out", "the screen turns off on the Ready screen"), n.building)
        assertEquals("1.0.38: every building moment cached = the CPU kill", n.building, n.cachedWhileBuilding)
    }

    @Test fun now_theServiceCoversEveryBuildingMoment() {
        val n = replay(withService = true)
        assertEquals(emptyList<String>(), n.cachedWhileBuilding)
        assertEquals("only while the builder builds", n.building, n.service)
        assertEquals("one start per leave after a game", 2, n.starts)
    }

    @Test fun neverWithTheGameOrBeforeTheFirstGameOrWithTheSettingOff() {
        val n = replay(withService = true)
        for (x in evening.filter { it.gameActive || !it.played }) assertFalse(x.what, x.what in n.service)
        val off = replay(withService = true, setting = false)
        assertEquals(emptyList<String>(), off.service)
        assertEquals(0, off.starts)
    }

    // ------------------------------------------------------------------ the decision

    private val base = State(setting = true, hasWork = true, gameActive = false, streaming = false, playedThisRun = true, shown = false)

    @Test fun wantedOnlyAfterAGameWhileBuildingUnseen() {
        assertTrue(IndoorKeepAlive.wanted(base))
        assertFalse("Off in Settings", IndoorKeepAlive.wanted(base.copy(setting = false)))
        assertFalse("done, failed, stopped or nothing to do", IndoorKeepAlive.wanted(base.copy(hasWork = false)))
        assertFalse("a game is up or launching", IndoorKeepAlive.wanted(base.copy(gameActive = true)))
        assertFalse("streaming", IndoorKeepAlive.wanted(base.copy(streaming = true)))
        assertFalse("before the first game", IndoorKeepAlive.wanted(base.copy(playedThisRun = false)))
        assertFalse("DS Forever's screen shown: the builder waits", IndoorKeepAlive.wanted(base.copy(shown = true)))
        assertFalse("lighter mode stood down", IndoorKeepAlive.wanted(base.copy(stoodDown = true)))
    }

    @Test fun lighterModeStandsDownOnlyOnAndroidsCriticalWarnings() {
        assertTrue(IndoorKeepAlive.standsDown(true, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(IndoorKeepAlive.standsDown(true, MemWatch.LOW_MEMORY))
        assertFalse(IndoorKeepAlive.standsDown(true, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertFalse("a background level says where the screen is", IndoorKeepAlive.standsDown(true, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertFalse("the 16 GB Thor Max, lighter mode off", IndoorKeepAlive.standsDown(false, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    }

    @Test fun aRefusalIsNotAskedAgainUntilDsForeverIsInFront() {
        val m = Machine()
        assertEquals(Op.START, m.next(want = true, front = false, ready = 0, nowMs = 0))
        assertTrue("the first refusal is logged", m.started(false, 0, 0))
        assertEquals("the builder's next status asks nothing", Op.NONE, m.next(true, false, 1, 1_000))
        assertEquals(Op.NONE, m.next(true, false, 2, 2_000))
        assertEquals("in front: asked again", Op.START, m.next(true, true, 3, 3_000))
        assertFalse("ok", m.started(true, 3, 3_000))
        assertTrue(m.running)
    }

    @Test fun progressIsRefreshedSparinglyAndItStopsOnce() {
        val m = Machine()
        assertEquals(Op.START, m.next(true, true, 10, 0)); m.started(true, 10, 0)
        assertEquals("same count", Op.NONE, m.next(true, false, 10, 10_000))
        assertEquals("too soon", Op.NONE, m.next(true, false, 11, 1_000))
        assertEquals(Op.PROGRESS, m.next(true, false, 11, IndoorKeepAlive.PROGRESS_EVERY_MS))
        assertEquals(Op.STOP, m.next(false, false, 11, 20_000))
        assertEquals("stopped once", Op.NONE, m.next(false, true, 11, 21_000))
        assertFalse("nothing to lose", m.lost())
    }

    @Test fun theServiceEndingByItselfIsAskedForAgainInFront() {
        val m = Machine()
        m.next(true, true, 0, 0); m.started(true, 0, 0)
        assertTrue(m.lost())
        assertEquals(Op.START, m.next(true, true, 0, 1_000))
    }

    // ------------------------------------------------------------------ the wiring (source pins)

    private fun src(rel: String): String = listOf(File("../$rel"), File(rel)).first { it.isFile }.readText()

    @Test fun theBuilderAndTheGameDriveIt() {
        val step = src("companion/app/src/main/java/com/jrees/wowcompanion/IndoorSchedulerStep.kt")
        assertTrue("every status change", step.substringAfter("private fun publishStatus(").substringBefore("// ---").contains("IndoorKeepAlive.update("))
        assertTrue("the thread's end", step.substringAfter("} finally {").substringBefore("\"interiors-bake\"").contains("IndoorKeepAlive.update("))
        // review: a new thread starts with no work counted (an old thread that ended normally left the flag set)
        assertTrue("a new thread", step.substringAfter("fun start(context: Context)").substringBefore("thread = Thread(").contains("working = false"))
        assertTrue("the game's close", step.contains("Event.GAME_CLOSED -> runCatching { IndoorKeepAlive.gameClosed(context) }"))
        val hooks = src("companion/app/src/main/java/com/jrees/wowcompanion/LauncherHooks.kt")
        assertTrue("Play", hooks.substringAfter("fun holdForPlay()").substringBefore("\n").contains("IndoorKeepAlive.gameStarting()"))
        assertTrue("a launch step", hooks.substringAfter("fun launchEvent(").substringBefore("\n    }").contains("IndoorKeepAlive.update("))
        val screen = src("dsforever/src/main/java/com/jrees/wowcompanion/fork/DsForeverScreen.kt")
        assertTrue("the game screen", screen.substringAfter("fun attachGame(").substringBefore("fun attachStream(").contains("IndoorKeepAlive.gameStarting()"))
        assertTrue("streaming", screen.substringAfter("fun attachStream(").substringBefore("fun signInBoard(").contains("IndoorKeepAlive.gameStarting()"))
        val tiles = src("companion/app/src/main/java/com/jrees/wowcompanion/PerformanceTiles.kt")
        assertTrue("Settings' Off", tiles.substringAfter("M.L_BUILD ->").substringBefore("M.L_").contains("IndoorKeepAlive.update(context"))
    }

    @Test fun theLauncherSaysWhenItsScreenIsShown() {
        val a = src("app/src/main/java/app/gamenative/MainActivity.kt")
        fun body(name: String) = a.substringAfter("override fun $name(").substringBefore("\n    }")
        assertTrue(body("onResume").contains("IndoorKeepAlive.shown(applicationContext, true"))
        for (leave in listOf("onUserLeaveHint", "onPause", "onStop")) {
            val b = body(leave)
            assertTrue(leave, b.contains("IndoorKeepAlive.shown(applicationContext, false"))
            assertTrue("$leave: before the bottom screen goes", b.indexOf("IndoorKeepAlive.shown(") < b.indexOf("DsForeverScreen.hideFor("))
        }
        assertTrue(src("app/src/main/java/app/gamenative/PluviaApp.kt").contains("IndoorKeepAlive.service = IndoorBuildService.Control"))
    }
}
