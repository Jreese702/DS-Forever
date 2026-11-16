package com.jrees.wowcompanion

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #355 ("Automatic crash report: DS Forever was stopped by a signal."): the launcher's window closed, MainActivity ended the game and
 * then its own process (Process.killProcess, 1.5 s later); Android recorded REASON_SIGNALED, status 9, and the next start asked for a
 * crash report. Our own kill leaves a mark; a signal 9 right after it is no crash, and the game's end says DS Forever closed it.
 */
class OwnExitTest {
    private val sig = ApplicationExitInfo.REASON_SIGNALED
    private val t = 1_763_190_748_000L

    @Test fun aSignal9RightAfterOurMarkIsOurs() {
        assertTrue(LastRun.ownKill(sig, 9, exitAt = t + 1_600L, ownAt = t))           // #355: the kill 1.5 s after the window closed
        assertTrue(LastRun.ownKill(sig, 9, exitAt = t + 200L, ownAt = t))
        assertTrue(LastRun.ownKill(sig, 9, exitAt = t - 500L, ownAt = t))              // clocks a moment apart
    }

    @Test fun anythingElseIsStillAsked() {
        assertFalse(LastRun.ownKill(sig, 9, exitAt = t + 1_600L, ownAt = 0L))          // no mark: a kill from outside
        assertFalse(LastRun.ownKill(sig, 9, exitAt = t + 60_000L, ownAt = t))          // long after the mark
        assertFalse(LastRun.ownKill(sig, 9, exitAt = t - 5_000L, ownAt = t))           // before it
        assertFalse(LastRun.ownKill(sig, 11, exitAt = t + 1_600L, ownAt = t))          // a crash (SIGSEGV) is never ours
        assertFalse(LastRun.ownKill(ApplicationExitInfo.REASON_LOW_MEMORY, 9, exitAt = t + 1_600L, ownAt = t))
        assertFalse(LastRun.ownKill(ApplicationExitInfo.REASON_CRASH_NATIVE, 9, exitAt = t + 1_600L, ownAt = t))
        // and the offer itself is unchanged for a signal that is not ours
        assertTrue(LastRun.offer(sig, 400, 415L * 1024, gameRunning = false))
    }

    @Test fun theGamesEndSaysWeClosedIt() {
        assertEquals("was closed by DS Forever (the app closing, signal 9), not by Android", GameLife.describe(137, "the app closing"))
        assertFalse(GameLife.abnormal(137, "the app closing"))                         // no "The game closed" note for it
    }
}
