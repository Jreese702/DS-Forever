package com.jrees.wowcompanion

import android.app.ApplicationExitInfo
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue triage 2026-11-11 ("not a bug" cluster, #111 / #106): no crash report for Android's background clean-up; who closed the game */
class CrashReportFilterTest {
    private val mb = 1024L                                              // ApplicationExitInfo.pss is in KB

    @Test fun aCachedLowMemoryKillWithNoGameIsNoReport() {
        // #111: LOW_MEMORY, importance 400, pss 0 / 11 MB, no game running
        assertFalse(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 400, 0, gameRunning = false))
        assertFalse(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 400, 11 * mb, gameRunning = false))
        // cached but big, still no game: Android tidying the background all the same
        assertFalse(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 400, 600 * mb, gameRunning = false))
        // a tiny process with no game, whatever its importance
        assertFalse(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 100, 20 * mb, gameRunning = false))
        // #106: cached and tiny under a stale "running" mark (2638 min)
        assertFalse(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 400, 13 * mb, gameRunning = true))
    }

    @Test fun aForegroundKillIsStillReported() {
        // #100 / #52: importance 100, pss 1213 / 1266 MB, the game in the world
        assertTrue(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 100, 1213 * mb, gameRunning = true))
        assertTrue(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 125, 400 * mb, gameRunning = false))   // in front, no game
        // #104: importance 400 but holding 398 MB while the game loaded
        assertTrue(LastRun.offer(ApplicationExitInfo.REASON_LOW_MEMORY, 400, 398 * mb, gameRunning = true))
    }

    @Test fun otherReasonsAreUntouched() {
        assertTrue(LastRun.offer(ApplicationExitInfo.REASON_CRASH, 400, 0, gameRunning = false))
        assertTrue(LastRun.offer(ApplicationExitInfo.REASON_CRASH_NATIVE, 100, 1103 * mb, gameRunning = true))
        assertTrue(LastRun.offer(ApplicationExitInfo.REASON_ANR, 400, 5 * mb, gameRunning = false))
        assertFalse(LastRun.offer(ApplicationExitInfo.REASON_USER_REQUESTED, 100, 900 * mb, gameRunning = true))
        assertFalse(LastRun.offer(ApplicationExitInfo.REASON_EXIT_SELF, 100, 900 * mb, gameRunning = true))
    }

    @Test fun ourOwnCloseIsSaidAsOurs() {
        assertEquals("was closed by DS Forever (the restart from Settings, signal 9), not by Android",
            GameLife.describe(137, "the restart from Settings"))
        assertEquals("was killed (signal 9: Android may have needed the memory)", GameLife.describe(137))
        assertEquals("closed normally", GameLife.describe(0, "the restart from Settings"))
        assertFalse(GameLife.abnormal(137, "Exit game from the menu"))
        assertTrue(GameLife.abnormal(137))
    }

    @Test fun theRestartsEndLeavesNoNoteAndSaysWhoInTheLog() {
        DiagLog.useDir(Files.createTempDirectory("diag").toFile())
        val t0 = System.currentTimeMillis()                              // DiagLog stamps its lines with the real clock
        GameLife.started(t0)
        GameLife.closer = "the restart from Settings"
        GameLife.ended(137, t0 + 5 * 60_000L)
        assertNull(GameLife.note)
        val end = DiagLog.lines(60 * 60_000L).single { it.contains("game: ended:") }
        assertTrue(end, end.contains("closed by DS Forever (the restart from Settings, signal 9), not by Android"))
        GameLife.started(t0 + 6 * 60_000L)                              // the new run: the closer goes
        assertNull(GameLife.closer)
        GameLife.ended(137, t0 + 9 * 60_000L)
        assertTrue(GameLife.note!!.contains("Android may have needed the memory"))
    }
}
