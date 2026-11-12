package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unread login.txt's cap (issue #132, the user's pick 2026-11-12 "Wait for login screen"): while the game downloads before its login
 * screen the 5-minute cap waits, a 30-minute hard cap from the write always deletes, and the start screen says "Game downloading".
 * Timings only: no file and no credential is ever touched here.
 */
class LoginCapTest {
    private val cap = 300_000L
    private val minute = 60_000L
    private val mb = 1L shl 20

    /**
     * NativeLogin's timer, off the device: the first check at [cap] after the write (t = 0), then whenever [PreLoginTraffic.capWait]
     * says; [downloadingAt] is the game's state at each moment (holdFrom follows it once a second, as onTraffic does). Returns when the
     * file goes, or -1 if it is still there at [until].
     */
    private fun deletedAt(downloadingAt: (Long) -> Boolean, until: Long = 3_600_000L): Long {
        var holdFrom = 0L
        var next = cap
        var t = 0L
        while (t <= until) {
            if (downloadingAt(t)) holdFrom = t
            if (t == next) {
                val w = PreLoginTraffic.capWait(t, 0L, holdFrom, cap, downloadingAt(t))
                if (w == 0L) return t
                next = t + w
            }
            t += 1000L
        }
        return -1L
    }

    @Test fun noDownloadKeepsTheFiveMinuteCap() {
        assertEquals(cap, deletedAt({ false }))
        assertEquals(0L, PreLoginTraffic.capWait(cap, 0L, 0L, cap, downloading = false))
        // a handler a moment early is on time
        assertEquals(0L, PreLoginTraffic.capWait(cap - 5L, 0L, 0L, cap, downloading = false))
    }

    @Test fun aDownloadPastFiveMinutesKeepsTheFile() {
        // #132: 20 minutes of download from the start, then the login screen
        val end = 20 * minute
        assertEquals(minute, PreLoginTraffic.capWait(cap, 0L, cap, cap, downloading = true))
        val at = deletedAt({ it < end })
        assertTrue("still there at the download's end", at > end)
        // the login screen gets the full 5 minutes after the download (it reads the file well before: then 20 s after the read)
        assertTrue("$at", at in end..end + cap)
    }

    @Test fun theHardCapAlwaysDeletes() {
        assertEquals(30 * minute, PreLoginTraffic.HARD_CAP_MS)
        // downloading for ever: gone at 30 minutes, not a check later
        assertEquals(PreLoginTraffic.HARD_CAP_MS, deletedAt({ true }))
        assertEquals(0L, PreLoginTraffic.capWait(PreLoginTraffic.HARD_CAP_MS, 0L, PreLoginTraffic.HARD_CAP_MS, cap, downloading = true))
        // a download that ends at 28 minutes: its 5 minutes after would pass 30, the hard cap wins
        assertEquals(PreLoginTraffic.HARD_CAP_MS, deletedAt({ it < 28 * minute }))
        // the wait never runs past the hard cap
        assertEquals(30_000L, PreLoginTraffic.capWait(29 * minute + 30_000L, 0L, 29 * minute, cap, downloading = true))
    }

    @Test fun aReadFileStillGoesTwentySecondsAfterTheFirstRead() {
        // the first read stops the counting: no longer "before the login screen", so no longer downloading
        assertFalse(PreLoginTraffic.counts(read = true, pastLogin = false, reachedWorld = false))
        assertFalse(PreLoginTraffic.downloading(counts = false, busy = true))
        // and the cap is unchanged for it: the 20 s after the first read comes first (NativeLogin.AFTER_READ_MS)
        assertTrue(PreLoginTraffic.capWait(cap, 0L, 0L, cap, downloading = false) == 0L)
    }

    @Test fun aReLoginFileKeepsItsCap() {
        // a re-login file is written after the world: the counter never counts there, so the 5 minutes hold
        assertFalse(PreLoginTraffic.downloading(PreLoginTraffic.counts(read = false, pastLogin = false, reachedWorld = true), busy = true))
        assertEquals(cap, deletedAt({ false }))
    }

    @Test fun busyFollowsTheMinutes() {
        val t = PreLoginTraffic()
        var now = 0L
        assertFalse(t.busy)
        repeat(10) { t.add(2 * mb, now); now += 1000L }        // 20 MB so far this minute
        assertTrue(t.busy)
        repeat(60) { t.add(0L, now); now += 1000L }            // the busy minute ends, then a quiet one
        repeat(60) { t.add(0L, now); now += 1000L }
        assertFalse(t.busy)
        // #57: a login screen's small bursts never read as downloading
        val idle = PreLoginTraffic()
        repeat(240) { i -> idle.add(if (i % 30 == 0) 11_872L else 0L, i * 1000L) }
        assertFalse(idle.busy)
    }

    @Test fun theWaitingLineSaysGameDownloadingAndBack() {
        val w = PreLoginTraffic.WAITING_LINE
        assertEquals("Waiting for the game.", w)
        assertEquals("Game downloading", PreLoginTraffic.waitingLine(w, downloading = true))
        assertEquals(w, PreLoginTraffic.waitingLine("Game downloading", downloading = false))
        assertEquals(w, PreLoginTraffic.waitingLine(w, downloading = false))
        // any other line stays (a reload, a sign-in row, a failure)
        assertEquals("Reloading the game's interface", PreLoginTraffic.waitingLine("Reloading the game's interface", downloading = true))
        assertEquals("Signing in…", PreLoginTraffic.waitingLine("Signing in…", downloading = false))
    }
}
