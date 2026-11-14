package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue triage 2026-11-14, the 1.0.29 - 1.0.31 low-memory cluster (#236 - #276; the user, 2026-11-13: "yes on the triage things"):
 * MemWatch's relief before the game's own big allocations, and what the next reports say about the moment (off-device).
 */
class MemReliefTest {

    @Test fun theKernelsPressureInOneShortNote() {
        val psi = "some avg10=2.10 avg60=0.85 avg300=0.20 total=123456\nfull avg10=0.40 avg60=0.10 avg300=0.02 total=23456\n"
        assertEquals("psi some 2.10 full 0.40", MemWatch.psiNote(psi))
        assertEquals("psi some 0.00", MemWatch.psiNote("some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n"))   // an older kernel: no full
        assertEquals("", MemWatch.psiNote(""))
        assertEquals("", MemWatch.psiNote("MemTotal: 11536228 kB\n"))                                       // not that file
    }

    @Test fun thePressureFileIsReadUntilItFailsOnce() {
        val was = MemWatch.psiRead
        try {
            MemWatch.psiDead = false
            var reads = 0
            MemWatch.psiRead = { reads++; "some avg10=12.50 avg60=3.00 avg300=1.00 total=9\nfull avg10=4.25 avg60=1.00 avg300=0.50 total=3\n" }
            assertEquals("psi some 12.50 full 4.25", MemWatch.psiNow())
            assertEquals("psi some 12.50 full 4.25", MemWatch.psiNow())
            assertEquals(2, reads)
            MemWatch.psiRead = { reads++; null }                          // an enforcing device refuses it
            assertEquals("", MemWatch.psiNow())
            assertEquals("", MemWatch.psiNow())
            assertEquals(3, reads)                                        // never read again this process
        } finally { MemWatch.psiRead = was; MemWatch.psiDead = false }
    }

    @Test fun aReliefOrTrimLineSaysWhatWentTheCollectionAndThePressure() {
        assertEquals("relief (world): let go worldroom 43 MB, scenes 41 MB; java 140 -> 31 MB; psi some 2.10 full 0.40",
            MemWatch.trimLine("relief (world)", listOf("worldroom" to (43L shl 20), "preview" to 0L, "scenes" to (41L shl 20)),
                "java 140 -> 31 MB", "psi some 2.10 full 0.40"))
        assertEquals("trim from Android: running-low: let go preview failed",
            MemWatch.trimLine("trim from Android: running-low", listOf("preview" to -1L, "scenes" to 0L), "", ""))
        assertEquals("relief (game start)", MemWatch.trimLine("relief (game start)", listOf("worldroom" to 0L), "", ""))
    }

    @Test fun oneCollectionPerTenSecondsAndOnlyAtRunningLowOrWorse() {
        assertTrue(MemWatch.gcDue(10_000L, Long.MIN_VALUE / 2))                      // never yet
        assertFalse(MemWatch.gcDue(19_999L, 10_000L))
        assertTrue(MemWatch.gcDue(20_000L, 10_000L))
        assertFalse(MemWatch.collects(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(MemWatch.collects(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(MemWatch.collects(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(MemWatch.collects(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(MemWatch.collects(MemWatch.LOW_MEMORY))
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, MemWatch.RELIEF_LEVEL)   // the relief lets go what a running-low trim does
    }

    @Test fun aReliefWithoutTheWatchIsNothing() {
        var got = -1
        MemWatch.onTrim("relief-test") { level -> got = level; 0L }
        MemWatch.relieve("game start")                                    // the watch never started here: no trim, no collection, no throw
        assertEquals(-1, got)
        MemWatch.onTrim("relief-test") { 0L }
    }
}
