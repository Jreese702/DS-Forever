package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE USER, 2026-11-24: "The indoor maps just never builds. I want it to show the bar and build on the first screen if people wait there
 * and I also want a button in game to force building in game"; the picks: the button in "Settings > Performance (Recommended)" ("A 'Build
 * now' button on the Indoor maps row next to its progress bar") and "Only while I'm idle" ("Builds only when you stand still out of
 * combat (in town, AFK), pauses the moment you move").
 *
 * The real scheduler and store over made-up generators: the first screen builds and Play makes it let go within one step (a building
 * under way is given up, never counted as a failed run, the wake lock released); in game, Build now builds only while the player is idle
 * and cancels the building under way the moment they move or a fight starts (not counted); loads and low memory let the game files go at
 * once; a failure beside the game is not counted; finished clears the button.
 */
class IndoorBuildAnywhereTest {
    @After fun reset() {
        LauncherHooks.gameUp = { false }; LauncherHooks.launchState = ""; LauncherHooks.launchAtMs = 0L; LauncherHooks.gameStartSec = 0L
        LauncherHooks.playPressAtMs = 0L; LauncherHooks.filesBusy = { false }
    }

    private val build = "0123456789abcdef0123456789abcdef"

    // ------------------------------------------------------------------ the first screen

    @Test fun theFirstScreenBuildsBeforeAnyGameAndAfterOne() {
        // 1.0.25 - 1.0.40: "builds after you play", then "builds when minimized or screen off" on this very screen
        assertNull("before the first Play, on the start screen", PlayGate.indoorWait(streaming = false, artRunning = false, gameActive = false))
        assertEquals(PlayGate.GAME_RUNNING, PlayGate.indoorWait(false, artRunning = false, gameActive = true))
        assertEquals("an art run goes first", PlayGate.ART_FIRST, PlayGate.indoorWait(false, artRunning = true, gameActive = false))
        // the game's own files being written (its update, an install, Repair): never read beside that, and let go at once
        assertEquals(PlayGate.FILES_BUSY, PlayGate.indoorWait(false, artRunning = false, gameActive = false, filesBusy = true))
        assertEquals(PlayGate.FILES_BUSY, PlayGate.indoorWait(true, artRunning = false, gameActive = false, filesBusy = true))
        assertTrue(IndoorScheduler.letGoAtOnce(PlayGate.FILES_BUSY))
        assertFalse("a move pauses in place (the long-pause release as before)", IndoorScheduler.letGoAtOnce(PlayGate.PLAYER_MOVING))
    }

    @Test fun playHoldsItFromThePressUntilTheGameScreen() {
        val press = 1_000_000_000_000L
        assertFalse("no press", PlayGate.pressHeld(0L, 0L, press))
        assertTrue("the press itself", PlayGate.pressHeld(press, 0L, press))
        assertTrue("Play waits for the art: still held", PlayGate.pressHeld(press, 0L, press + 90_000L))
        assertTrue("an older game's start does not end it", PlayGate.pressHeld(press, press / 1000L - 600L, press + 5_000L))
        assertFalse("the game screen came up: the game itself holds it from there", PlayGate.pressHeld(press, press / 1000L + 3L, press + 5_000L))
        assertFalse("never past the launch window", PlayGate.pressHeld(press, 0L, press + PlayGate.LAUNCH_WINDOW_MS))
        // the hooks: the start screen back (endLaunchWindow) or a stopped launch clears it
        LauncherHooks.playPressed(); assertTrue(LauncherHooks.playPressAtMs > 0L)
        LauncherHooks.endLaunchWindow(); assertEquals(0L, LauncherHooks.playPressAtMs)
        LauncherHooks.playPressed(); LauncherHooks.playStopped("test"); assertEquals(0L, LauncherHooks.playPressAtMs)
    }

    /** a made-up generator: [steps] yield points per building, [stepMs] of real time between them (a long native building) */
    private class SlowGen(val keys: List<String>, val steps: Int, val stepMs: Long, val onStart: (String, Int) -> Unit = { _, _ -> }) : IndoorGenerator {
        val made = ArrayList<String>()
        @Volatile var started = 0L
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            yield.point()
            return keys.mapIndexed { i, k ->
                IndoorBakeStore.Work(k, "%064x".format(i + 1), 1, listOf(IndoorBakeStore.Location(0, i * 50.0, i * 50.0 + 10.0, 0.0, 10.0)))
            }
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            made.add(work.key)
            started = System.currentTimeMillis()
            onStart(work.key, made.count { it == work.key })
            repeat(steps) { if (stepMs > 0) Thread.sleep(stepMs); yield.point() }
            IndoorFinishTest.building(out, work.key)
        }
        override fun close() {}
    }

    private class Lock : IndoorScheduler.WakeLock {
        @Volatile var held = false
        var acquires = 0; var releases = 0
        override fun acquire(timeoutMs: Long) { held = true; acquires++ }
        override fun release() { held = false; releases++ }
    }

    /** the launcher's state in real time: Play flips [game] from another thread */
    private class RealSig(val lock: Lock) : IndoorScheduler.Signals {
        @Volatile var game = false
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = false
        override fun gameRunning() = game
        override fun hot() = false
        override fun stop() = false
        override fun now() = System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(minOf(ms, 20L))
        override fun wakeLock() = lock
    }

    /**
     * PLAY PRESSED MID-BUILDING on the start screen: the run lets the game files go at the building's next step (a native building
     * yields every Recast tile, group, image), does not wait for a publication or the storage walk, counts nothing, leaves no kill
     * marker and lets the wake lock go; the next run builds that building again. The measured time is printed (the report's bound).
     */
    @Test fun playMidBuildingLetsGoWithinOneStepAndCountsNothing() {
        val root = Files.createTempDirectory("anywhere-play").toFile()
        val data = File(root, "interiors_sched")
        val lock = Lock()
        val sig = RealSig(lock)
        // a long building (300 steps of 20 ms = 6 s); Play comes 200 ms into it
        val gen = SlowGen(listOf("wmo_a"), steps = 300, stepMs = 20L)
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val s = IndoorScheduler(IndoorBakeStore(root), root, build, gen, sig, data, log = { logs += it })
        var outcome: IndoorScheduler.Outcome? = null
        val t = Thread { outcome = s.run() }.apply { start() }
        while (gen.started == 0L) Thread.sleep(5)
        Thread.sleep(200)
        val pressed = System.currentTimeMillis()
        sig.game = true
        t.join(5_000L)
        val took = System.currentTimeMillis() - pressed
        println("IndoorBuildAnywhereTest: Play mid-building -> run() returned (files would close) $took ms after the press")
        assertFalse("ended", t.isAlive)
        assertEquals(IndoorScheduler.Outcome.RELEASED, outcome)
        assertTrue("within one step of 20 ms plus the run's own wind-down: $took ms", took < 250L)
        assertEquals("the long building was given up, not finished", listOf("wmo_a"), gen.made)
        val deferred = File(data, "deferred.json")
        assertFalse("not counted: ${if (deferred.isFile) deferred.readText() else ""}", deferred.isFile && deferred.readText().contains("wmo_a"))
        assertFalse("no kill marker left", File(data, IndoorDeferrals.ATTEMPT_FILE).isFile)
        assertFalse("the wake lock let go", lock.held)
        assertTrue(lock.acquires > 0 && lock.releases > 0)
        assertFalse("no storage walk while letting go", logs.any { it.startsWith("stats:") })
        // the game closed: the next run builds it again (it was not skipped)
        sig.game = false
        val again = SlowGen(listOf("wmo_a"), steps = 1, stepMs = 0L)
        assertEquals(IndoorScheduler.Outcome.COMPLETE, IndoorScheduler(IndoorBakeStore(root), root, build, again, sig, data).run())
        assertEquals(listOf("wmo_a"), again.made)
    }

    /** fake time beside the game: [reason] is what IndoorIdle.Beside would say now; [beside] = Build now with the game up */
    private class BesideSig(val lock: Lock? = null) : IndoorScheduler.Signals {
        var t = 0L
        var beside = true
        var reason: String? = null
        var until = Long.MAX_VALUE                     // the reason ends at this time (the player stood still again)
        var stopNow = false
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = false
        override fun gameRunning() = !beside
        override fun beside() = beside
        override fun besideReason(): String? { if (t >= until) { reason = null; until = Long.MAX_VALUE }; return reason }
        override fun hot() = false
        override fun stop() = stopNow
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
        override fun wakeLock() = lock
    }

    /** a made-up generator in fake time: each step 500 ms */
    private class StepGen(val keys: List<String>, val sig: BesideSig, val steps: Int = 10, val transient: Set<String> = emptySet(),
                          val onStep: (String, Int, Int) -> Unit = { _, _, _ -> }) : IndoorGenerator {
        val made = ArrayList<String>()
        var stepsRun = 0
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            yield.point()
            return keys.mapIndexed { i, k ->
                IndoorBakeStore.Work(k, "%064x".format(i + 1), 1, listOf(IndoorBakeStore.Location(0, i * 50.0, i * 50.0 + 10.0, 0.0, 10.0)))
            }
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            made.add(work.key)
            val n = made.count { it == work.key }
            for (i in 0 until steps) { onStep(work.key, n, i); sig.t += 500L; stepsRun++; yield.point() }
            if (work.key in transient) throw IllegalStateException("interiors generator ran out of memory")
            IndoorFinishTest.building(out, work.key)
        }
        override fun close() {}
    }

    private fun scheduler(root: File, gen: IndoorGenerator, sig: IndoorScheduler.Signals, st: MutableList<IndoorScheduler.Status>? = null,
                          logs: MutableList<String>? = null) =
        IndoorScheduler(IndoorBakeStore(root), root, build, gen, sig, File(root, "interiors_sched"),
            onStatus = { st?.add(it) }, log = { logs?.add(it) }, app = "gen|a1|lib:aa")

    @Test fun inGameItBuildsWhileIdleAndSaysSo() {
        val root = Files.createTempDirectory("anywhere-idle").toFile()
        val st = ArrayList<IndoorScheduler.Status>()
        val lock = Lock()
        val sig = BesideSig(lock)
        val gen = StepGen(listOf("wmo_a", "wmo_b"), sig, steps = 2)
        assertEquals(IndoorScheduler.Outcome.COMPLETE, scheduler(root, gen, sig, st).run())
        assertEquals(listOf("wmo_a", "wmo_b"), gen.made)
        assertTrue("the row: building while you're idle",
            st.any { it.phase == IndoorScheduler.Phase.BAKING && it.note == PlayGate.IDLE_BUILDING })
        assertTrue("the CPU held awake while it built beside the game", lock.acquires > 0)
        assertFalse(lock.held)
    }

    /**
     * "Pauses the moment you move": the building under way gets [IndoorIdle.Beside.CANCEL_GRACE_MS], then it is cancelled - not deferred,
     * not counted - the row says "paused: moving", and once the player is idle again the same building is made again
     */
    @Test fun aMoveCancelsTheBuildingUnderWayAndItIsBuiltAgainWhenIdle() {
        val root = Files.createTempDirectory("anywhere-move").toFile()
        val st = ArrayList<IndoorScheduler.Status>()
        val logs = ArrayList<String>()
        val lock = Lock()
        val sig = BesideSig(lock)
        var movedAt = -1L
        val gen = StepGen(listOf("wmo_a", "wmo_b"), sig) { key, n, i ->
            // the player walks off 1 s into the first try of wmo_a, and stands still again 20 s later
            if (key == "wmo_a" && n == 1 && i == 2) { movedAt = sig.t; sig.reason = PlayGate.PAUSED_MOVING; sig.until = sig.t + 20_000L }
        }
        val outcome = scheduler(root, gen, sig, st, logs).run()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, outcome)
        assertEquals("wmo_a cancelled, then made again, then wmo_b", listOf("wmo_a", "wmo_a", "wmo_b"), gen.made)
        val cancel = logs.first { it.startsWith("cancelled wmo_a") }
        assertTrue(cancel, cancel.contains("not counted"))
        // the grace: ~1.5 s of the building's own steps after the move, then given up (it never held on for the 20 s walk)
        assertEquals("the first try: 2 steps before the move, the move's own step and 3 more (1.5 s of grace), then given up; then two whole tries",
            2 + 4 + 10 + 10, gen.stepsRun)
        assertTrue("paused: moving on the row", st.any { it.phase == IndoorScheduler.Phase.PAUSED && it.note == PlayGate.PAUSED_MOVING })
        val deferred = File(root, "interiors_sched/deferred.json")
        assertFalse("not deferred, not counted", deferred.isFile && deferred.readText().contains("wmo_a"))
        assertFalse("no kill marker", File(root, "interiors_sched/" + IndoorDeferrals.ATTEMPT_FILE).isFile)
        assertTrue("the wake lock went with the cancel and the end: ${lock.releases}", lock.releases >= 2)
        assertFalse(lock.held)
        assertTrue(movedAt >= 0)
    }

    @Test fun aFightPausesItTheSameWay() {
        val root = Files.createTempDirectory("anywhere-fight").toFile()
        val st = ArrayList<IndoorScheduler.Status>()
        val sig = BesideSig()
        val gen = StepGen(listOf("wmo_a"), sig) { _, n, i -> if (n == 1 && i == 1) { sig.reason = PlayGate.PAUSED_FIGHT; sig.until = sig.t + 30_000L } }
        assertEquals(IndoorScheduler.Outcome.COMPLETE, scheduler(root, gen, sig, st).run())
        assertEquals(listOf("wmo_a", "wmo_a"), gen.made)
        assertTrue(st.any { it.phase == IndoorScheduler.Phase.PAUSED && it.note == PlayGate.PAUSED_FIGHT })
    }

    @Test fun aLongWalkLetsTheGameFilesGoAndALoadOrLowMemoryAtOnce() {
        // a walk longer than a minute: the files go after the long pause, as on the start screen
        run {
            val root = Files.createTempDirectory("anywhere-walk").toFile()
            val sig = BesideSig()
            val gen = StepGen(listOf("wmo_a"), sig) { _, n, i -> if (n == 1 && i == 1) sig.reason = PlayGate.PAUSED_MOVING }
            assertEquals(IndoorScheduler.Outcome.RELEASED, scheduler(root, gen, sig).run())
        }
        for (why in listOf(PlayGate.PAUSED_LOADING, PlayGate.PAUSED_MEMORY)) {
            val root = Files.createTempDirectory("anywhere-load").toFile()
            val st = ArrayList<IndoorScheduler.Status>()
            val lock = Lock()
            val sig = BesideSig(lock)
            var at = 0L
            val gen = StepGen(listOf("wmo_a"), sig) { _, n, i -> if (n == 1 && i == 1) { sig.reason = why; at = sig.t } }
            assertEquals(why, IndoorScheduler.Outcome.RELEASED, scheduler(root, gen, sig, st).run())
            assertTrue("$why: at the very next step, no grace, no minute: ${sig.t - at} ms", sig.t - at <= 500L)
            assertEquals(why, st.last { it.phase == IndoorScheduler.Phase.PAUSED }.note)
            assertFalse(lock.held)
            val deferred = File(root, "interiors_sched/deferred.json")
            assertFalse("$why: not counted", deferred.isFile && deferred.readText().contains("wmo_a"))
        }
    }

    @Test fun stopOrTheGameWithoutBuildNowLetsGoAtOnce() {
        val root = Files.createTempDirectory("anywhere-stop").toFile()
        val lock = Lock()
        val sig = BesideSig(lock)
        // Stop pressed while it builds beside the game: the game is running and nothing asks for the build any more
        val gen = StepGen(listOf("wmo_a"), sig) { _, _, i -> if (i == 3) sig.beside = false }
        val logs = ArrayList<String>()
        assertEquals(IndoorScheduler.Outcome.RELEASED, scheduler(root, gen, sig, logs = logs).run())
        assertEquals("let go at the next step", 4, gen.stepsRun)
        assertFalse(lock.held)
        assertFalse("no last publication while letting go", logs.any { it.startsWith("publish failed") })
    }

    /** a failure that may pass, beside the game (its memory, its files being written): tried again after the game, not counted */
    @Test fun aFailureBesideTheGameIsNotCountedButOneAfterItIs() {
        val root = Files.createTempDirectory("anywhere-fail").toFile()
        val logs = ArrayList<String>()
        val sig = BesideSig()
        val gen = StepGen(listOf("wmo_a", "wmo_b"), sig, steps = 1, transient = setOf("wmo_a"))
        assertEquals(IndoorScheduler.Outcome.DEFERRED, scheduler(root, gen, sig, logs = logs).run())
        assertTrue(logs.any { it == "deferral wmo_a: failed beside the game: tried again after it (not counted)" })
        val deferred = File(root, "interiors_sched/deferred.json")
        assertFalse("no try counted", deferred.isFile && deferred.readText().contains("wmo_a"))
        // after the game (not beside): the same failure is counted as its first failed run
        sig.beside = false
        val after = object : IndoorScheduler.Signals by sig { override fun gameRunning() = false; override fun beside() = false }
        val gen2 = StepGen(listOf("wmo_a", "wmo_b"), sig, steps = 1, transient = setOf("wmo_a"))
        val logs2 = ArrayList<String>()
        assertEquals(IndoorScheduler.Outcome.DEFERRED, scheduler(root, gen2, after, logs = logs2).run())
        assertTrue(logs2.toString(), logs2.any { it == "deferral wmo_a: " + IndoorScheduler.triesText(1) })
    }

    @Test fun theStartScreenStillPausesInPlace() {
        // not beside the game (the start screen, minimized): a warm device pauses the building where it is, as before - no cancel
        val root = Files.createTempDirectory("anywhere-warm").toFile()
        val sig = BesideSig()
        var hot = false
        val start = object : IndoorScheduler.Signals by sig {
            override fun gameRunning() = false
            override fun beside() = false
            override fun hot() = hot
            override fun sleep(ms: Long) { sig.sleep(ms); if (sig.t > 10_000L) hot = false }
        }
        val gen = StepGen(listOf("wmo_a"), sig) { _, _, i -> if (i == 2) hot = true }
        assertEquals(IndoorScheduler.Outcome.COMPLETE, scheduler(root, gen, start).run())
        assertEquals("one try, paused in place", listOf("wmo_a"), gen.made)
    }

    // ------------------------------------------------------------------ the idle rule

    @Test fun theIdleRule() {
        val B = IndoorIdle.Beside
        val idle = IndoorIdle.Beside.Now(inWorld = true, calmMs = 120_000L, aliveAgoMs = 2_000L, sinceFightMs = 60_000L, sinceMoveMs = 30_000L,
            memAvailable = 6_000L shl 20)
        assertNull("in town, AFK", B.reason(idle))
        assertEquals("the character list or not in the world", PlayGate.PAUSED_LOADING, B.reason(idle.copy(inWorld = false)))
        assertEquals("a loading screen's first minute", PlayGate.PAUSED_LOADING, B.reason(idle.copy(calmMs = 30_000L)))
        assertEquals("the add-on's word on a loading screen", PlayGate.PAUSED_LOADING, B.reason(idle.copy(loading = true)))
        assertEquals("the add-on silent past its heartbeat", PlayGate.PAUSED_LOADING, B.reason(idle.copy(aliveAgoMs = 40_000L)))
        assertEquals(PlayGate.PAUSED_FIGHT, B.reason(idle.copy(fight = true, sinceFightMs = 0L)))
        assertEquals("a fight just ended: settle first", PlayGate.PAUSED_FIGHT, B.reason(idle.copy(sinceFightMs = 3_000L)))
        assertEquals(PlayGate.PAUSED_MOVING, B.reason(idle.copy(sinceMoveMs = 0L)))
        assertEquals("standing still 5 s: not settled yet", PlayGate.PAUSED_MOVING, B.reason(idle.copy(sinceMoveMs = 5_000L)))
        assertNull("standing still past the settle", B.reason(idle.copy(sinceMoveMs = B.SETTLE_MS)))
        assertNull("never moved (logged in and stood)", B.reason(idle.copy(sinceMoveMs = Long.MAX_VALUE)))
        assertEquals(PlayGate.PAUSED_FLIGHT, B.reason(idle.copy(sinceMoveMs = 100L, taxi = true)))
        assertEquals("an art step beside the game goes first", PlayGate.ART_FIRST, B.reason(idle.copy(artRunning = true)))
        assertNull("memory not readable: not tested (the native guard still is)", B.reason(idle.copy(memAvailable = null)))
        // the order: a fight over a move, a load over everything
        assertEquals(PlayGate.PAUSED_FIGHT, B.reason(idle.copy(fight = true, sinceFightMs = 0L, sinceMoveMs = 0L)))
        assertEquals(PlayGate.PAUSED_LOADING, B.reason(idle.copy(inWorld = false, fight = true)))
        assertTrue(B.letGoNow(PlayGate.PAUSED_LOADING)); assertTrue(B.letGoNow(PlayGate.PAUSED_MEMORY))
        assertFalse(B.letGoNow(PlayGate.PAUSED_MOVING)); assertFalse(B.letGoNow(PlayGate.PAUSED_FIGHT)); assertFalse(B.letGoNow(PlayGate.PAUSED_FLIGHT))
    }

    /** each open beside the game costs ~5 s of a core and +500-630 MB: once let go in a game, the next open waits for a longer idle */
    @Test fun aSecondOpenInTheSameGameWaitsLonger() {
        val B = IndoorIdle.Beside
        val idle = IndoorIdle.Beside.Now(inWorld = true, calmMs = 120_000L, aliveAgoMs = 2_000L, sinceMoveMs = 10_000L)
        assertNull("the first open: 8 s still is enough", B.reason(idle))
        assertEquals("let go once already: 10 s is not", PlayGate.PAUSED_MOVING, B.reason(idle.copy(reopen = true)))
        assertEquals(PlayGate.PAUSED_FIGHT, B.reason(idle.copy(reopen = true, sinceMoveMs = Long.MAX_VALUE, sinceFightMs = 20_000L)))
        assertNull(B.reason(idle.copy(reopen = true, sinceMoveMs = B.REOPEN_SETTLE_MS)))
        assertNull("files still open: the short settle", B.reason(idle.copy(reopen = true, filesOpen = true)))
    }

    @Test fun theMemoryRuleIsStricterOnTheSmallThors() {
        val B = IndoorIdle.Beside
        val idle = IndoorIdle.Beside.Now(inWorld = true, calmMs = 120_000L, aliveAgoMs = 2_000L)
        val gb = 1L shl 30
        // the 16 GB Thor: 3 GB free opens the game files, 2 GB keeps building
        assertNull(B.reason(idle.copy(memAvailable = 3 * gb)))
        assertEquals("too little to open them", PlayGate.PAUSED_MEMORY, B.reason(idle.copy(memAvailable = 2 * gb)))
        assertNull("enough to go on once open", B.reason(idle.copy(memAvailable = 2 * gb, filesOpen = true)))
        assertEquals(PlayGate.PAUSED_MEMORY, B.reason(idle.copy(memAvailable = 1 * gb, filesOpen = true)))
        // the 8 / 12 GB Thors (317 MB free at worst while playing on an 8 GB one): 3 GB is not enough to open them there
        assertEquals(PlayGate.PAUSED_MEMORY, B.reason(idle.copy(memAvailable = 3 * gb, small = true)))
        assertEquals(PlayGate.PAUSED_MEMORY, B.reason(idle.copy(memAvailable = 2 * gb, small = true, filesOpen = true)))
        assertNull(B.reason(idle.copy(memAvailable = 4 * gb, small = true)))
        assertTrue(B.minFree(small = true, filesOpen = false) > B.minFree(small = false, filesOpen = false))
        assertTrue(B.minFree(small = true, filesOpen = true) > B.minFree(small = false, filesOpen = true))
        // the native generator's own guard stays below these (a building starts with 1 GB free, stops under 384 MB)
        assertTrue(B.RUN_MIN > NativeIndoorGenerator.START_MIN_AVAILABLE)
        // Android's running-low warning: the rest of that game on a small Thor, five minutes on another
        val start = 1_000_000L
        assertTrue(B.pressure(small = true, pressureAt = start + 10, gameStartedAt = start, now = start + 3_600_000L))
        assertFalse("a warning from an earlier game", B.pressure(small = true, pressureAt = start - 10, gameStartedAt = start, now = start + 10))
        assertTrue(B.pressure(small = false, pressureAt = start, gameStartedAt = start, now = start + 60_000L))
        assertFalse(B.pressure(small = false, pressureAt = start, gameStartedAt = start, now = start + B.PRESSURE_MS))
        assertFalse("no warning", B.pressure(small = true, pressureAt = 0L, gameStartedAt = start, now = start))
        assertEquals(PlayGate.PAUSED_MEMORY, B.reason(idle.copy(memAvailable = 8 * gb, pressure = true)))
    }

    // ------------------------------------------------------------------ the scheduler's pause reason beside the game

    @Test fun pauseReasonBesideTheGame() {
        val sig = BesideSig()
        assertNull(IndoorScheduler.pauseReason(sig))
        sig.reason = PlayGate.PAUSED_FIGHT
        assertEquals(PlayGate.PAUSED_FIGHT, IndoorScheduler.pauseReason(sig))
        sig.beside = false
        assertEquals("no Build now: the game holds it", PlayGate.GAME_RUNNING, IndoorScheduler.pauseReason(sig))
    }

    // ------------------------------------------------------------------ the Settings line, the start row

    @Test fun theBuildNowLineShowsWhileTheGameRunsAndGoesWhenFinished() {
        val M = PerformanceTileModel
        assertNull("the game not running: it builds by itself", M.buildNowLine(indoorOn = true, finished = false, gameUp = false, armed = false))
        assertEquals(false, M.buildNowLine(indoorOn = true, finished = false, gameUp = true, armed = false))
        assertEquals("armed: Stop", true, M.buildNowLine(indoorOn = true, finished = false, gameUp = true, armed = true))
        assertEquals("Stop stays reachable while armed", true, M.buildNowLine(indoorOn = true, finished = false, gameUp = false, armed = true))
        assertNull("finished clears the button", M.buildNowLine(indoorOn = true, finished = true, gameUp = true, armed = true))
        assertNull("Off", M.buildNowLine(indoorOn = false, finished = false, gameUp = true, armed = false))
        fun line(armed: Boolean?) = M.lines(M.INDOOR, PerformanceTileModel.State(buildNow = armed)).firstOrNull { it.key == M.L_NOW }
        assertNull(line(null))
        assertEquals(listOf("Build now"), line(false)!!.words)
        assertEquals(listOf("Stop"), line(true)!!.words)
        assertEquals(PerformanceTileModel.Kind.ACTION, line(true)!!.kind)
        assertEquals("beside its progress (the band's picture) and the background switch",
            listOf(M.L_BUILD, M.L_NOW, M.L_DEFERRED), M.lines(M.INDOOR, PerformanceTileModel.State(buildNow = false, deferred = 3)).map { it.key })
    }

    @Test fun theStartRowHasItsBar() {
        val base = SetupFlow.Inputs(path = "/x", folderFound = true, loginSaved = true, art = SetupFlow.Art.READY, addonsInstalled = true)
        fun row(indoor: String) = SetupFlow.checks(base.copy(indoor = indoor)).first { it.label.startsWith("Indoor") }
        val building = row("Building 120/1853")
        assertEquals("Indoor maps (optional): Building 120/1853", building.label)
        assertEquals(120f / 1853f, SetupFlow.bar(building, base)!!, 1e-4f)
        assertEquals("listing (no count yet): a moving bar", SetupFlow.BAR_MOVING, SetupFlow.bar(row("Listing buildings"), base))
        assertEquals(1616f / 1853f, SetupFlow.bar(row("1616/1853 ready - Waiting: game art"), base)!!, 1e-4f)
        assertEquals("Indoor maps (optional)", SetupFlow.terse(listOf(building)).single().label)
        assertNull("finished: no bar", SetupFlow.bar(SetupFlow.checks(base.copy(indoor = "Finished: 3 maps", indoorDone = true))
            .first { it.label.startsWith("Indoor") }, base))
    }

    @Test fun theWordsAreTranslated() {
        for (lang in listOf("es", "fr")) {
            val f = listOf(File("../companion/app/src/main/assets/companion/i18n/$lang.json"), File("companion/app/src/main/assets/companion/i18n/$lang.json"))
                .first { it.isFile }
            val raw = f.readText()
            Tr.load(lang, raw)
            for (en in listOf(PlayGate.IDLE_BUILDING, PlayGate.PAUSED_MOVING, PlayGate.PAUSED_FIGHT, PlayGate.PAUSED_LOADING,
                    PlayGate.PAUSED_FLIGHT, PlayGate.PAUSED_MEMORY, PlayGate.FILES_BUSY, "Paused: moving", "Paused: in a fight",
                    "Paused: loading", "Paused: on a flight", "Paused: low memory", "Waiting: game update", "Build now", "Stop",
                    PerformanceTileModel.NOW_LABEL, SetupFlow.INDOOR_ROW, IndoorSchedulerStep.STARTING, "Indoor maps (optional): Building 120/1853")) {
                val t = Tr.t(en, lang)
                assertTrue("$lang: $en -> $t", t != en)
            }
            assertTrue("$lang keeps CRLF", raw.contains("\r\n") && raw.replace("\r\n", "").none { it == '\n' })
        }
    }

    // ------------------------------------------------------------------ Play's bounded wait

    @Test fun playWaitsAtMostASecondForTheGameFiles() {
        // nothing open: no wait at all
        assertTrue(IndoorSchedulerStep.letGoForPlay() < 100L)
        // a builder that does not let go (a read stuck on a download): the launch goes on after the bound
        val f = IndoorSchedulerStep::class.java.getDeclaredField("filesOpen").apply { isAccessible = true }
        f.setBoolean(IndoorSchedulerStep, true)
        try {
            val waited = IndoorSchedulerStep.letGoForPlay(maxMs = 300L)
            assertTrue("bounded: $waited ms", waited in 300L..600L)
            // the builder lets go 100 ms into the wait: Play goes on then
            Thread { Thread.sleep(100); f.setBoolean(IndoorSchedulerStep, false) }.start()
            val short = IndoorSchedulerStep.letGoForPlay()
            assertTrue("as soon as the files closed: $short ms", short in 80L..400L)
        } finally { f.setBoolean(IndoorSchedulerStep, false) }
        assertEquals(1_000L, IndoorSchedulerStep.PLAY_WAIT_MS)
    }

    /**
     * the 1528 audit: Play pressed while the builder OPENS the game files (CascLib's ~5 s, +500 MB; on the start screen that is right
     * after the app opens) - Play's wait saw nothing open ("were not open") and the builder went on into its first building beside the
     * booting game. Now the open counts for Play's bounded wait, and once it returns a Play (or a stop) since lets the files go at once
     */
    @Test fun playDuringTheOpenWaitsForItAndTheFilesGoRightAfter() {
        val f = IndoorSchedulerStep::class.java.getDeclaredField("filesOpening").apply { isAccessible = true }
        f.setBoolean(IndoorSchedulerStep, true)
        try {
            val waited = IndoorSchedulerStep.letGoForPlay(maxMs = 300L)
            assertTrue("an open under way: Play waits (bounded): $waited ms", waited in 300L..600L)
        } finally { f.setBoolean(IndoorSchedulerStep, false) }
        assertTrue("Play since the open: let go", IndoorSchedulerStep.letGoAfterOpen(PlayGate.GAME_RUNNING, stopping = false))
        assertTrue("a stop since the open: let go", IndoorSchedulerStep.letGoAfterOpen(null, stopping = true))
        assertFalse("nothing since: it builds", IndoorSchedulerStep.letGoAfterOpen(null, stopping = false))
        assertFalse("a pause as ever (the scheduler's own gate)", IndoorSchedulerStep.letGoAfterOpen(PlayGate.PLAYER_MOVING, stopping = false))
        val run = src("companion/app/src/main/java/com/jrees/wowcompanion/IndoorSchedulerStep.kt")
            .substringAfter("private fun runOnce(").substringBefore("factory.open(g)")
        assertTrue("the open is marked before it starts", run.indexOf("filesOpening = true") in 0 until run.indexOf("GameFiles.open(ctx)"))
        assertTrue("and checked right after it", run.indexOf("letGoAfterOpen(") > run.indexOf("GameFiles.open(ctx)"))
    }

    // ------------------------------------------------------------------ the wiring (source pins)

    private fun src(rel: String): String = listOf(File("../$rel"), File(rel)).first { it.isFile }.readText()

    @Test fun playStopsItAtThePressAndWaitsAtMostASecond() {
        val screen = src("app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
        val launch = screen.substringAfter("fun launchGame(by: String = \"play\")").substringBefore("LifecycleResumeEffect(gamePath)")
        val press = launch.indexOf("LauncherHooks.playPressed()")
        assertTrue("Play presses at once", press > 0)
        assertTrue("before Play's wait for the art", press < launch.indexOf("awaitArtHarvest("))
        assertTrue("after the old window ends (it clears the press)", launch.indexOf("LauncherHooks.endLaunchWindow()") < press)
        val wait = launch.indexOf("IndoorSchedulerStep.letGoForPlay()")
        assertTrue("the bounded wait before the container", wait > 0 && wait < launch.indexOf("launchEvent(\"preparing the container"))
        assertEquals("every stopped launch clears the press", 3, Regex("LauncherHooks\\.playStopped\\(").findAll(launch).count())
        assertTrue("the game's own files written: it waits", screen.contains("LauncherHooks.filesBusy = { isUpdating || isRepairing || installStartedAt != 0L }"))
        val host = src("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")
        assertTrue("the fight is its own word", host.contains("IndoorSchedulerStep.noteAlive(fight || setupLoading || startView.visibility == View.VISIBLE, fight = fight)"))
        val files = src("companion/app/src/main/java/com/jrees/wowcompanion/GameFiles.kt")
        assertTrue("a damaged read beside the game is not marked", files.substringAfter("if (CascHeal.damaged(error)) {").substringBefore("lastReadWhy =")
            .let { it.contains("besideGame()") && it.indexOf("besideGame()") < it.indexOf("readSawDamage = true") })
        val step = src("companion/app/src/main/java/com/jrees/wowcompanion/IndoorSchedulerStep.kt")
        assertTrue("the builder's handle says when it reads beside the game", step.contains("game.besideGame = { LauncherHooks.gameUp() && !PlayGate.streamingNow() }"))
        assertTrue("the thread's end clears Build now", step.substringAfter("} finally {").substringBefore("\"interiors-bake\"").contains("disarm("))
        assertTrue("finished clears Build now", step.substringAfter("private fun publishStatus(").substringBefore("synchronized(statusLock)")
            .contains("Phase.DONE && armed) disarm(\"finished\")"))
        assertTrue("the input downloads stop starting on Play", src("companion/app/src/main/java/com/jrees/wowcompanion/NativeIndoorGenerator.kt")
            .contains("game.prefetch(unit.inputs.toList(), letGo = { runCatching { letGoNow() }.getOrDefault(false) })"))
    }
}
