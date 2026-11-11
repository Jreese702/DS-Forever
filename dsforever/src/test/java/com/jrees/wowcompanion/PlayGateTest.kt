package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Issues #108 #109 #100 #92 #128 #98 #85 #104 #86 #94 #80 #82 (2026-11-11): no heavy companion work (the game files' open, the indoor
 * bake, the background art) while the game is up or launching, on ANY Thor; it goes on once the game has closed.
 */
class PlayGateTest {
    // ------------------------------------------------------------------ the game up / launching

    @Test fun theGameUpIsActive() {
        assertTrue(PlayGate.gameActive(gameUp = true, launchState = "", launchAtMs = 0, gameStartSec = 0, nowMs = 1_000))
        assertFalse(PlayGate.gameActive(gameUp = false, launchState = "", launchAtMs = 0, gameStartSec = 0, nowMs = 1_000))
    }

    @Test fun aLaunchUnderWayIsActiveBeforeTheGameScreenComesUp() {
        val at = 100_000_000L
        assertTrue(PlayGate.gameActive(false, "preparing the container (Turnip, by play)", at, gameStartSec = 0, nowMs = at + 20_000))
        assertTrue(PlayGate.gameActive(false, "booting the game (container 3)", at, gameStartSec = 0, nowMs = at + 1_000))
        // a game from an earlier launch does not end this one
        assertTrue(PlayGate.gameActive(false, "booting the game (container 3)", at, gameStartSec = at / 1000 - 600, nowMs = at + 1_000))
    }

    @Test fun aLaunchIsOverOnceTheGameCameUpEvenInTheSameSecond() {
        val at = 100_000_500L
        // the game screen came up within the same second as "booting" (whole seconds), then the game closed
        assertFalse(PlayGate.gameActive(false, "booting the game (container 3)", at, gameStartSec = at / 1000, nowMs = at + 3_600_000))
        assertFalse(PlayGate.launching("booting the game (container 3)", at, gameStartSec = at / 1000 + 2, nowMs = at + 10_000))
    }

    @Test fun aFailedOrStaleLaunchIsNotActive() {
        val at = 100_000_000L
        assertFalse(PlayGate.launching("launch failed: IOException: x", at, 0, at + 1_000))
        assertFalse(PlayGate.launching("launch stopped: storage access denied", at, 0, at + 1_000))
        assertFalse(PlayGate.launching("booting the game (container 3)", at, 0, at + PlayGate.LAUNCH_WINDOW_MS))
        assertFalse(PlayGate.launching("", 0, 0, 1_000))
    }

    // ------------------------------------------------------------------ the art harvest

    @Test fun backgroundArtPausesWhileTheGameRunsOnEveryTier() {
        assertTrue(PlayGate.artMustPause(backgroundPhase = true, gameActive = true))
        assertFalse(PlayGate.artMustPause(backgroundPhase = true, gameActive = false))
        // the art before Play (Play waits for it) is never paused by this
        assertFalse(PlayGate.artMustPause(backgroundPhase = false, gameActive = true))
        assertFalse(PlayGate.artMayOpen(gameActive = true))
        assertTrue(PlayGate.artMayOpen(gameActive = false))
    }

    // ------------------------------------------------------------------ the indoor maps

    @Test fun indoorWaitsWhileTheGameRunsAndBuildsAfterItClosed() {
        assertEquals(PlayGate.GAME_RUNNING, PlayGate.indoorWait(streaming = false, artRunning = false, gameActive = true, playedThisRun = true))
        assertNull(PlayGate.indoorWait(streaming = false, artRunning = false, gameActive = false, playedThisRun = true))
        // before the first game of this run: DS Forever's own screens (the user 2026-11-07, "sluggish before I press update")
        assertEquals(PlayGate.GAME_BUSY, PlayGate.indoorWait(false, false, gameActive = false, playedThisRun = false))
        // an art run goes first
        assertEquals(PlayGate.GAME_BUSY, PlayGate.indoorWait(false, artRunning = true, gameActive = false, playedThisRun = true))
    }

    @Test fun indoorBuildsWhileStreaming() {
        assertNull(PlayGate.indoorWait(streaming = true, artRunning = false, gameActive = false, playedThisRun = false))
        assertEquals(PlayGate.GAME_BUSY, PlayGate.indoorWait(streaming = true, artRunning = true, gameActive = false, playedThisRun = false))
    }

    // ------------------------------------------------------------------ the scheduler lets the game files go at once

    private class Gen(val onProduce: () -> Unit = {}) : IndoorGenerator {
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            yield.point()
            return listOf(IndoorBakeStore.Work("wmo_1", "a".repeat(64), 1, listOf(IndoorBakeStore.Location(0, 0.0, 10.0, 0.0, 10.0))))
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            onProduce()
            yield.point()
            File(out, "x.bin").writeBytes(ByteArray(4))
        }
        override fun close() {}
    }

    private class Sig(var game: Boolean) : IndoorScheduler.Signals {
        var t = 0L
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = false
        override fun gameRunning() = game
        override fun hot() = false
        override fun stop() = false
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    @Test fun pauseReasonNamesTheGameFirst() {
        assertEquals(PlayGate.GAME_RUNNING, IndoorScheduler.pauseReason(Sig(game = true)))
        assertNull(IndoorScheduler.pauseReason(Sig(game = false)))
    }

    @Test fun theGameUpReleasesTheRunAtOnceNotAfterALongPause() {
        val root = Files.createTempDirectory("playgate").toFile()
        val sig = Sig(game = true)
        val s = IndoorScheduler(IndoorBakeStore(root), root, "build", Gen(), sig, File(root, "data"))
        assertEquals(IndoorScheduler.Outcome.RELEASED, s.run())
        assertTrue("released without waiting", sig.t < IndoorScheduler.PAUSE_POLL_MS)
    }

    @Test fun aReleaseMidBuildingDoesNotDeferThatBuilding() {
        val root = Files.createTempDirectory("playgate2").toFile()
        val sig = Sig(game = false)
        val data = File(root, "data")
        // the game comes up while the building is being made
        val first = IndoorScheduler(IndoorBakeStore(root), root, "build", Gen { sig.game = true }, sig, data)
        assertEquals(IndoorScheduler.Outcome.RELEASED, first.run())
        val deferred = File(data, "deferred.json")
        assertFalse("no deferral for a release: ${if (deferred.isFile) deferred.readText() else ""}",
            deferred.isFile && deferred.readText().contains("wmo_1"))
        // the game closed: the next run makes it again (it was not skipped as deferred)
        sig.game = false
        var made = 0
        val second = IndoorScheduler(IndoorBakeStore(root), root, "build", Gen { made++ }, sig, data)
        assertTrue(second.run() != IndoorScheduler.Outcome.RELEASED)
        assertEquals(1, made)
    }
}
