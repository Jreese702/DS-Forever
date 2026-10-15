package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** f-map-tracker, off-device: Settings > Map's indoor-map progress line and its deferred list */
class IndoorProgressTest {
    private fun st(phase: IndoorScheduler.Phase, ready: Int = 312, total: Int = 1460, deferred: Int = 0, note: String = "", now: String = "") =
        IndoorScheduler.Status(phase, ready, total, deferred, note, now)

    @Test fun nothingYet() {
        assertEquals("Not started: it starts after Play.", IndoorProgress.line(null, true))
        assertEquals("Off: nothing is built.", IndoorProgress.line(null, false))
    }

    @Test fun buildingNowWithDistance() =
        assertEquals("312 / 1460 ready · building one 30 yd from you · 3 deferred",
            IndoorProgress.line(st(IndoorScheduler.Phase.BAKING, deferred = 3, now = IndoorProgress.nowText(30.2)), true))

    @Test fun pausedSaysWhy() =
        assertEquals("312 / 1460 ready · paused: player moving",
            IndoorProgress.line(st(IndoorScheduler.Phase.PAUSED, note = "player moving"), true))

    @Test fun doneAndDeferredEnds() {
        assertEquals("1460 / 1460 ready · all built", IndoorProgress.line(st(IndoorScheduler.Phase.DONE, ready = 1460), true))
        assertEquals("1455 / 1460 ready · finished · 5 deferred",
            IndoorProgress.line(st(IndoorScheduler.Phase.DEFERRED, ready = 1455, deferred = 5), true))
    }

    @Test fun stoppedOffInSettings() =
        assertEquals("312 / 1460 ready · off in Settings", IndoorProgress.line(st(IndoorScheduler.Phase.STOPPED, note = "off in Settings"), false))

    @Test fun catalogueBeforeACount() =
        assertEquals("listing buildings", IndoorProgress.line(st(IndoorScheduler.Phase.CATALOGUE, ready = 0, total = 0), true))

    @Test fun nowTexts() {
        assertEquals("the one you are in", IndoorProgress.nowText(0.0))
        assertEquals("one 120 yd from you", IndoorProgress.nowText(119.6))
        assertEquals("one on another map", IndoorProgress.nowText(Double.POSITIVE_INFINITY))
        assertEquals("one building", IndoorProgress.nowText(null))
    }

    @Test fun deferredLinesAreShortAndOneLine() {
        assertEquals("r123_u4: native out of memory", IndoorProgress.deferredLine("r123_u4", "native out of memory\nat produce(…)"))
        assertEquals("r1_u1: failed", IndoorProgress.deferredLine("r1_u1", "  "))
        val long = IndoorProgress.deferredLine("k", "x".repeat(200))
        assertTrue(long.length <= "k: ".length + 80 && long.endsWith("…"))
    }
}
