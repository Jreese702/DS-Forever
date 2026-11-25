package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TEST BUILD 1529, "Lighter add-ons + log" (the user's pick 2026-11-24: "Slower log polling, ..."): LogTail polls every 50 ms inside the
 * launcher too (it was 20 ms there). Measured here: the polls a second and what it adds to a document's wait, for the add-on's flushes
 * while walking (every 0.2 s) and in a fight (every 0.3 s, Sink sink2), the flush landing at any point between two polls.
 */
class LogTailPollTest {
    /** (polls a second, mean ms from the flush to its read, worst ms) for polls every [pollMs] and flushes every [flushMs] */
    private fun measure(pollMs: Long, flushMs: Long): Triple<Double, Double, Long> {
        val total = 60_000L
        var lat = 0L; var worst = 0L; var n = 0
        var flush = 7L                                     // not on the poll grid
        while (flush < total) {
            val read = ((flush + pollMs - 1) / pollMs) * pollMs   // the first poll at or after the flush
            val d = read - flush
            lat += d; n++; if (d > worst) worst = d
            flush += flushMs + (flush * 7919 % 13)          // a little jitter, deterministic
        }
        return Triple(1000.0 / pollMs, lat.toDouble() / n, worst)
    }

    @Test fun fiftyMillisecondsAndWhatItCosts() {
        for ((what, flush) in listOf("walking" to 200L, "fighting" to 300L)) {
            val before = measure(20L, flush); val after = measure(50L, flush)
            println("LogTail ($what, a flush every $flush ms): before ${before.first} polls/s, +${"%.1f".format(before.second)} ms mean, " +
                "${before.third} ms worst; after ${after.first} polls/s, +${"%.1f".format(after.second)} ms mean, ${after.third} ms worst")
            assertEquals(50.0, before.first, 0.01)
            assertEquals(20.0, after.first, 0.01)
            assertTrue("the mean wait stays about half a poll (${after.second})", after.second in 15.0..35.0)
            assertTrue("never more than one poll (${after.third})", after.third <= 50L)
        }
    }

    @Test fun theTailPollsEveryFiftyMillisecondsEverywhere() {
        assertEquals(50L, LogTail.POLL_MS)
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        val t = File(src, "LogTail.kt").readText()
        assertTrue(t.contains("if (running) handler.postDelayed(this, POLL_MS)"))
        assertTrue(!t.contains("else 20L)"))
    }
}
