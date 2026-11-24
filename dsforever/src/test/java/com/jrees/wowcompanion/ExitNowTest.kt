package com.jrees.wowcompanion

import android.app.ApplicationExitInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * EXIT GAME CLOSES DS FOREVER AT ONCE (feat/exit-now; the user, 2026-11-24: "I want our built in close game button to completly close
 * the app immidiatly"), off-device: the order and bounds of [ExitNow.Sequence] with every step faked, the next start's reading of such
 * an exit (no crash question, no "the game closed" note), the indoor maps' stand-down while DS Forever goes, the pending writes
 * flushed, and the routes in the sources (the waiting screen's Menu, the launcher's steps, Streaming's quick end).
 */
class ExitNowTest {
    @Before fun fresh() = ExitNow.reset()
    @After fun after() = ExitNow.reset()

    private fun read(vararg paths: String): String = File(paths.flatMap { listOf("../$it", it) }.first { File(it).isFile }).readText()
    private val host by lazy { read("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt") }
    private val main by lazy { read("app/src/main/java/app/gamenative/MainActivity.kt") }
    private val exitHost by lazy { read("app/src/main/java/app/gamenative/ExitNowHost.kt") }
    private val streamTop by lazy { read("dsforever/src/main/java/com/jrees/wowcompanion/stream/StreamTop.kt") }

    // ---------------------------------------------------------------- the sequence

    /** a fake clock: every step and sleep moves it; [uiAfter] = the activities are gone this long after finishUi */
    private class Rig(var streaming: Boolean = false, val uiAfter: Long = 120L, val fail: String? = null, val stepMs: Long = 10L) {
        var now = 0L
        val did = ArrayList<String>()
        val bounds = HashMap<String, Long>()
        var finishedAt = -1L
        val log = ArrayList<String>()
        private fun at(name: String) { if (name == fail) throw IllegalStateException("boom"); did += name; now += stepMs }
        val steps = ExitNow.Steps(
            watchdog = { ms -> bounds["watchdog"] = ms; at("watchdog") },
            mark = { at("mark") },
            flush = { ms -> bounds["flush"] = ms; at("flush") },
            streaming = { streaming },
            endStream = { ms -> bounds["stream"] = ms; at("stream") },
            killGame = { ms -> bounds["game"] = ms; at("game") },
            stopServices = { at("services") },
            finishUi = { at("ui"); finishedAt = now },
            uiGone = { finishedAt >= 0 && now - finishedAt >= uiAfter },
            endProcess = { at("end") },
            log = { log += it },
            clock = { now },
            sleep = { ms -> now += ms },
        )
    }

    @Test fun localTheOrderNoCleanExitAndTheProcessEndsLast() {
        val r = Rig()
        ExitNow.Sequence(r.steps).run()
        assertEquals(listOf("watchdog", "mark", "flush", "game", "services", "ui", "end"), r.did)
        assertTrue("the closer is recorded before the game is killed", r.did.indexOf("mark") < r.did.indexOf("game"))
        assertTrue("the writes flushed before anything is killed", r.did.indexOf("flush") < r.did.indexOf("game"))
        assertTrue("every activity finished before the process ends", r.did.indexOf("ui") < r.did.indexOf("end"))
        assertFalse("no stream: nothing ended", r.did.contains("stream"))
        assertTrue(ExitNow.underWay)
    }

    @Test fun streamingTheStreamEndsBeforeTheGameIsKilled() {
        val r = Rig(streaming = true)
        ExitNow.Sequence(r.steps).run()
        assertEquals(listOf("watchdog", "mark", "flush", "stream", "game", "services", "ui", "end"), r.did)
    }

    @Test fun theBoundsKeepItUnderTwoSeconds() {
        assertEquals("the pending writes: ~300 ms", 300L, ExitNow.FLUSH_MS)
        assertTrue("the stream's end: a short bound", ExitNow.STREAM_MS <= 500L)
        assertTrue("the hard stop under 2 s", ExitNow.HARD_MS < 2_000L)
        val worst = ExitNow.FLUSH_MS + ExitNow.STREAM_MS + ExitNow.KILL_MS + ExitNow.MAIN_MS + ExitNow.MAIN_MS + ExitNow.UI_MS + ExitNow.SETTLE_MS
        assertTrue("every bound together ($worst ms) under 2 s", worst < 2_000L)
        val r = Rig(streaming = true)
        ExitNow.Sequence(r.steps).run()
        assertEquals(ExitNow.HARD_MS, r.bounds["watchdog"])
        assertEquals(ExitNow.FLUSH_MS, r.bounds["flush"])
        assertEquals(ExitNow.STREAM_MS, r.bounds["stream"])
        assertEquals(ExitNow.KILL_MS, r.bounds["game"])
    }

    @Test fun activitiesThatNeverGoDoNotHoldTheEnd() {
        val r = Rig(uiAfter = Long.MAX_VALUE)
        ExitNow.Sequence(r.steps).run()
        assertEquals("end", r.did.last())
        assertTrue("waited at most UI_MS after the finish", r.now - r.finishedAt <= ExitNow.UI_MS + ExitNow.SETTLE_MS + 2 * r.stepMs + ExitNow.POLL_MS)
    }

    @Test fun theUiGoneThenAShortSettleForItsStopsWrites() {
        val r = Rig(uiAfter = 100L)
        ExitNow.Sequence(r.steps).run()
        val endAt = r.now - r.stepMs
        assertTrue("not before the activities went", endAt - r.finishedAt >= 100L)
        assertTrue("and not much after", endAt - r.finishedAt <= 100L + ExitNow.POLL_MS + ExitNow.SETTLE_MS)
    }

    @Test fun aFailingStepNeverStopsTheRest() {
        for (bad in listOf("mark", "flush", "stream", "game", "services", "ui")) {
            ExitNow.reset()
            val r = Rig(streaming = true, fail = bad)
            ExitNow.Sequence(r.steps).run()
            assertEquals("$bad failed: the process still ends", "end", r.did.last())
            assertTrue(r.log.any { it.contains("$bad failed") })
        }
    }

    @Test fun aSecondPressDoesNothing() {
        val a = Rig(); val b = Rig()
        ExitNow.Sequence(a.steps).run()
        ExitNow.Sequence(b.steps).run()
        assertEquals("the second press: nothing", emptyList<String>(), b.did)
        assertTrue(b.log.any { it.contains("already under way") })
    }

    // ---------------------------------------------------------------- the next start

    @Test fun theNextStartReadsItAsOurOwnExit() {
        // Android's record of Process.killProcess on ourselves: signal 9, a moment after the own-exit mark
        assertTrue(LastRun.ownKill(ApplicationExitInfo.REASON_SIGNALED, 9, exitAt = 10_000L + ExitNow.HARD_MS, ownAt = 10_000L))
        // the game's end as DS Forever's own, never "the game closed" (no note, no report offered)
        assertFalse(GameLife.abnormal(137, ExitNow.CLOSER))
        assertTrue(GameLife.describe(137, ExitNow.CLOSER).startsWith("was closed by DS Forever (Exit game from the menu"))
        assertEquals("Exit game from the menu", ExitNow.CLOSER)
        // the "running" mark alone (the game was up) asks nothing
        assertEquals(null, LastRun.words(null, wasRunning = true, inWorld = true, ranMs = 60_000L))
    }

    /** the user's pick (2026-11-24): the pause wheel's power button runs the same immediate exit; its own words in the game's end */
    @Test fun thePowerButtonsCloserIsItsOwn() {
        assertEquals(ExitNow.CLOSER, ExitNow.closer(ExitNow.MENU))
        assertEquals("Close DS Forever from the pause menu", ExitNow.closer(ExitNow.POWER))
        assertFalse(GameLife.abnormal(137, ExitNow.closer(ExitNow.POWER)))
        assertTrue(GameLife.describe(137, ExitNow.closer(ExitNow.POWER)).startsWith("was closed by DS Forever (Close DS Forever from the pause menu"))
    }

    @Test fun theIndoorMapsStandDownWhileDsForeverGoes() {
        assertEquals(IndoorSchedulerStep.Action.START, IndoorSchedulerStep.on(IndoorSchedulerStep.Event.GAME_CLOSED, exiting = false))
        assertEquals("the game's close during the exit starts nothing", IndoorSchedulerStep.Action.KEEP,
            IndoorSchedulerStep.on(IndoorSchedulerStep.Event.GAME_CLOSED, exiting = true))
        assertEquals(IndoorSchedulerStep.Action.KEEP, IndoorSchedulerStep.on(IndoorSchedulerStep.Event.COMPANION_UP, exiting = true))
        assertEquals(IndoorSchedulerStep.Action.STOP, IndoorSchedulerStep.on(IndoorSchedulerStep.Event.APP_EXIT, exiting = true))
        val s = IndoorKeepAlive.State(setting = true, hasWork = true, gameActive = false, streaming = false, shown = false)
        assertTrue(IndoorKeepAlive.wanted(s))
        assertFalse("no foreground service asked for as DS Forever goes", IndoorKeepAlive.wanted(s.copy(exiting = true)))
    }

    @Test fun everyQueuedLogLineIsWrittenByTheFlush() {
        val dir = createTempDir("exitnow")
        try {
            val a = BgAppend({ File(dir, "a.log") }, flushMs = 60_000L)
            val b = BgAppend({ File(dir, "b.log") }, flushMs = 60_000L)
            a.add("one"); b.add("two")
            assertTrue("flushed within its bound", BgAppend.flushAll(300L))
            assertEquals("one\n", File(dir, "a.log").readText())
            assertEquals("two\n", File(dir, "b.log").readText())
        } finally { dir.deleteRecursively() }
    }

    // ---------------------------------------------------------------- the routes in the sources

    @Test fun theMenusExitGameIsTheImmediateExit() {
        val onExit = host.substringAfter("StartMenu.onExit = {").substringBefore("\n")
        assertTrue(onExit, onExit.trim().startsWith("LauncherHooks.exitNow?.invoke(ExitNow.MENU)"))
        assertFalse("no single confirm added (the Menu never asked)", read("companion/app/src/main/java/com/jrees/wowcompanion/StartMenu.kt")
            .contains("Exit game?"))
    }

    @Test fun theLauncherWiresTheImmediateExit() {
        assertTrue(main.contains("com.jrees.wowcompanion.LauncherHooks.exitNow = { why -> ExitNowHost.start(applicationContext, why) }"))
        assertFalse("never the game's own Exit Game first", exitHost.contains("cleanExit"))
        val mark = exitHost.substringAfter("mark = {").substringBefore("},\n")
        assertTrue("the closer, by who asked", mark.contains("GameLife.closer = ExitNow.closer(why)"))
        assertTrue(exitHost.substringAfter("fun start(").substringBefore("Thread(").contains("GameLife.closer = ExitNow.closer(why)"))
        assertTrue("the own-exit mark (commit)", mark.contains("LastRun.ownExit(app)"))
        assertTrue("the indoor maps' kill marker goes", mark.contains("IndoorSchedulerStep.appExit()"))
        assertTrue("a written login.txt goes", mark.contains("NativeLogin.clear(app)"))
        assertTrue("Swap screens' start count untouched by it", mark.contains("ScreenSwap.ownExit(app)"))
        val kill = exitHost.substringAfter("killGame = {").substringBefore("},\n")
        assertTrue("every Wine process killed", kill.contains("ProcessHelper.listRunningWineProcesses()") && kill.contains("ProcessHelper.killProcess("))
        assertTrue("then the environment's own teardown", kill.contains("AndroidEvent.ForceCloseApp") && kill.contains("PluviaApp.shutdownEnvironment()"))
        val svc = exitHost.substringAfter("stopServices = {").substringBefore("},\n")
        assertTrue(svc.contains("GameSessionService.stop(app)"))
        assertTrue(svc.contains("IndoorBuildService.Control.stop(app)"))
        assertTrue(svc.contains("cancelAll()"))
        val ui = exitHost.substringAfter("finishUi = {").substringBefore("},\n")
        assertTrue("both screens' DS Forever", ui.contains("DsForeverScreen.closeAll()"))
        assertTrue("every task, out of recents", ui.contains("finishAndRemoveTask()"))
        val end = exitHost.substringAfter("private fun endProcess(").substringBefore("\n    }")
        assertTrue("the mark again, then the kill", end.indexOf("LastRun.ownExit(app)") in 0 until end.indexOf("killProcess"))
        val flush = exitHost.substringAfter("private fun flush(").substringBefore("\n    }")
        assertTrue("the logs", flush.contains("BgAppend.flushAll("))
        assertTrue("every SharedPreferences file's pending apply()", flush.contains("shared_prefs") && flush.contains(".edit().commit()"))
    }

    @Test fun streamingEndsWithoutWaitingForDesktop() {
        val q = streamTop.substringAfter("SetupBridge.quitStream = {").substringBefore("\n        }")
        assertTrue("Desktop told to close WoW and put the PC back", q.contains("endSession(false)"))
        assertTrue("a bounded send", q.contains(".join(boundMs)"))
        assertTrue("the stream and the bridge stopped", q.contains("stopStream(") && q.contains("bridge?.stop()"))
        assertFalse("no wait for Desktop's word", q.contains("SessionEnd("))
        assertTrue("cleared with the stream", streamTop.substringAfter("private fun teardown()").substringBefore("\n    }").contains("SetupBridge.quitStream = null"))
    }
}
