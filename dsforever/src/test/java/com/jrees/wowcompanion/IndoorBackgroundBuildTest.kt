package com.jrees.wowcompanion

import com.jrees.wowcompanion.IndoorSchedulerStep.Action
import com.jrees.wowcompanion.IndoorSchedulerStep.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #568 / #573 (2026-11-20): the indoor maps never built. After a game they may build only while nobody looks at DS Forever (the screen
 * off, or the launcher not in front), but exactly then the bottom screen closed and its CompanionHost.onDestroy stopped the step; it
 * started again only with the bottom screen, i.e. with the launcher in front, where it waits. These tests replay #573's evening
 * (play, quit to the Ready screen, minimize, let the screen time out, come back) against the step's start / stop table, then run the
 * real scheduler through the same moments.
 */
class IndoorBackgroundBuildTest {

    // ------------------------------------------------------------------ the start / stop table over #573's evening

    /** one moment of the evening: the lifecycle event that came with it, and what PlayGate sees then */
    private data class Moment(val what: String, val event: Event?, val gameActive: Boolean, val played: Boolean, val quiet: Boolean)

    private val evening = listOf(
        Moment("DS Forever opens, the launcher in front", Event.COMPANION_UP, gameActive = false, played = false, quiet = false),
        Moment("Play: the game runs", null, gameActive = true, played = true, quiet = false),
        Moment("minimized during the game (the game paused, still up)", Event.COMPANION_GONE, gameActive = true, played = true, quiet = true),
        Moment("back in the game", Event.COMPANION_UP, gameActive = true, played = true, quiet = false),
        Moment("quit to the Ready screen", Event.GAME_CLOSED, gameActive = false, played = true, quiet = false),
        Moment("minimized", Event.COMPANION_GONE, gameActive = false, played = true, quiet = true),
        Moment("the screen times out", null, gameActive = false, played = true, quiet = true),
        Moment("back to DS Forever", Event.COMPANION_UP, gameActive = false, played = true, quiet = false),
        Moment("the screen turns off on the Ready screen", Event.COMPANION_GONE, gameActive = false, played = true, quiet = true),
        Moment("Play again", Event.COMPANION_UP, gameActive = true, played = true, quiet = false),
    )

    /** the moments in which the step is alive AND allowed to build, under the start / stop table [table] */
    private fun buildingMoments(table: (Event) -> Action): List<String> {
        var alive = false
        val out = ArrayList<String>()
        for (m in evening) {
            when (m.event?.let(table)) {
                Action.START -> alive = true
                Action.STOP -> alive = false
                Action.KEEP, null -> {}
            }
            val wait = PlayGate.indoorWait(streaming = false, artRunning = false, gameActive = m.gameActive, playedThisRun = m.played, quiet = m.quiet)
            if (alive && wait == null) out.add(m.what)
        }
        return out
    }

    /** 1.0.25 - 1.0.37: CompanionHost.onDestroy stopped the step, the game's detach did nothing */
    private val before: (Event) -> Action = {
        when (it) {
            Event.COMPANION_UP -> Action.START
            Event.COMPANION_GONE -> Action.STOP
            Event.GAME_CLOSED -> Action.KEEP
            Event.APP_EXIT -> Action.STOP
        }
    }

    @Test fun beforeTheFixTheStepAndItsBuildWindowNeverOverlapped() {
        assertEquals("the #573 reproduction: never alive while it may build", emptyList<String>(), buildingMoments(before))
    }

    @Test fun nowItBuildsWhileMinimizedOrWithTheScreenOff() {
        assertEquals(listOf("minimized", "the screen times out", "the screen turns off on the Ready screen"),
            buildingMoments(IndoorSchedulerStep::on))
    }

    @Test fun neverWhileTheGameRunsEvenMinimized() {
        val building = buildingMoments(IndoorSchedulerStep::on).toSet()
        for (m in evening.filter { it.gameActive }) assertFalse(m.what, m.what in building)
    }

    @Test fun onlyTheAppsExitStopsIt() {
        assertEquals(Action.KEEP, IndoorSchedulerStep.on(Event.COMPANION_GONE))
        assertEquals(Action.START, IndoorSchedulerStep.on(Event.COMPANION_UP))
        assertEquals(Action.START, IndoorSchedulerStep.on(Event.GAME_CLOSED))
        assertEquals(Action.STOP, IndoorSchedulerStep.on(Event.APP_EXIT))
    }

    // ------------------------------------------------------------------ the real scheduler through the same moments

    private class Gen(val onProduce: (Int) -> Unit) : IndoorGenerator {
        var made = 0
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            yield.point()
            return listOf("a", "b", "c").mapIndexed { i, d ->
                IndoorBakeStore.Work("wmo_$i", d.repeat(64), 1, listOf(IndoorBakeStore.Location(0, i * 20.0, i * 20.0 + 10.0, 0.0, 10.0)))
            }
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            made++
            onProduce(made)
            yield.point()
            File(out, "x.bin").writeBytes(ByteArray(4))
        }
        override fun close() {}
    }

    /** the step's AndroidSignals in miniature: busyReason is PlayGate.indoorWait over the launcher's state, as on the device */
    private class Launcher : IndoorScheduler.Signals {
        var t = 0L
        var gameUp = false
        var played = true                 // a game ran and was quit to the Ready screen
        var quiet = false                 // the Ready screen in front
        var minimizeAt = Long.MAX_VALUE
        var stops = 0
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = busyReason() != null
        override fun busyReason(): String? = PlayGate.indoorWait(false, artRunning = false, gameActive = gameUp, playedThisRun = played, quiet = quiet)
        override fun gameRunning() = gameUp
        override fun hot() = false
        override fun stop(): Boolean { stops++; return false }      // the bottom screen going away is no stop any more
        override fun now() = t
        override fun sleep(ms: Long) { t += ms; if (t >= minimizeAt) quiet = true }
    }

    @Test fun afterTheGameItWaitsOnTheReadyScreenBuildsOnceMinimizedAndLetsGoAtPlay() {
        val root = Files.createTempDirectory("indoor568").toFile()
        val sig = Launcher().apply { minimizeAt = 10_000L }
        // Play pressed while the second building is being made
        val gen = Gen { n -> if (n == 2) sig.gameUp = true }
        val notes = ArrayList<String>()
        val logs = ArrayList<String>()
        val s = IndoorScheduler(IndoorBakeStore(root), root, "build", gen, sig, File(root, "data"),
            onStatus = { if (it.phase == IndoorScheduler.Phase.PAUSED) notes.add(it.note) }, log = { logs += it })
        assertEquals(IndoorScheduler.Outcome.RELEASED, s.run())
        assertEquals("the Ready screen's wait says why, not \"game busy\"", PlayGate.IN_BACKGROUND, notes.first())
        assertTrue("it waited until the minimize: ${sig.t} ms", sig.t >= 10_000L)
        assertEquals("the first made, the second let go at Play, the third never started", 2, gen.made)
        assertEquals("nothing stopped it on the way", 0, logs.count { it == "stopped" })
        assertFalse("a release is no deferral", logs.any { it.startsWith("deferred wmo_1") })
    }

    @Test fun aNewWaitReasonShowsAtOnce() {
        val root = Files.createTempDirectory("indoor568b").toFile()
        // before the first game of the run, then a game played and quit, still in front: the row follows
        val sig = Launcher().apply { played = false }
        val notes = ArrayList<String>()
        val gen = Gen {}
        val s = IndoorScheduler(IndoorBakeStore(root), root, "build", gen, object : IndoorScheduler.Signals by sig {
            override fun sleep(ms: Long) {
                sig.sleep(ms)
                if (sig.t >= 4_000L) sig.played = true
                if (sig.t >= 8_000L) sig.quiet = true
            }
        }, File(root, "data"), onStatus = { if (it.phase == IndoorScheduler.Phase.PAUSED) notes.add(it.note) })
        // (the fake generator's output is not a sealed building: the store defers each; what counts here is that all three ran)
        val outcome = s.run()
        assertTrue("$outcome", outcome == IndoorScheduler.Outcome.COMPLETE || outcome == IndoorScheduler.Outcome.DEFERRED)
        assertEquals(listOf(PlayGate.AFTER_PLAY, PlayGate.IN_BACKGROUND), notes)
        assertEquals(3, gen.made)
    }

    // ------------------------------------------------------------------ the row's text

    private fun st(phase: IndoorScheduler.Phase, note: String = "", ready: Int = 120, total: Int = 1853) =
        IndoorScheduler.Status(phase, ready, total, note = note)

    @Test fun theRowSaysWhatHappens() {
        val head = "Indoor Maps (Optional): "
        assertEquals(head + "Builds after you play", IndoorSchedulerStep.readiness(null).label)
        assertEquals(head + "Building 120/1853", IndoorSchedulerStep.readiness(st(IndoorScheduler.Phase.BAKING)).label)
        assertEquals(head + "120/1853 ready - Waiting: game running",
            IndoorSchedulerStep.readiness(st(IndoorScheduler.Phase.PAUSED, PlayGate.GAME_RUNNING)).label)
        assertEquals(head + "Builds when minimized or screen off",
            IndoorSchedulerStep.readiness(st(IndoorScheduler.Phase.PAUSED, PlayGate.IN_BACKGROUND, 0, 0)).label)
        assertEquals(head + "1853/1853 ready", IndoorSchedulerStep.readiness(st(IndoorScheduler.Phase.DONE, ready = 1853)).label)
        assertTrue(IndoorSchedulerStep.readiness(st(IndoorScheduler.Phase.DONE, ready = 1853)).done)
    }

    @Test fun noWaitIsCalledGameBusyAnyMore() {
        for (streaming in listOf(false, true)) for (art in listOf(false, true)) for (game in listOf(false, true))
            for (played in listOf(false, true)) for (quiet in listOf(false, true)) {
                val why = PlayGate.indoorWait(streaming, art, game, played, quiet)
                assertTrue("$streaming $art $game $played $quiet -> $why", why != PlayGate.GAME_BUSY && why?.contains("busy") != true)
            }
    }
}
