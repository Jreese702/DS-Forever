package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issues #138 / #112 (2026-11-11): ArtHarvest$PausedForGame thrown by Job.progress inside GameFiles.prefetch's onProgress ran on an
 * art-fetch pool thread and killed the app. GameFiles.inPool carries a pool thread's throw back to the calling thread.
 */
class ArtPoolTest {
    @Test fun aPauseOnAPoolThreadReachesTheCaller() {
        val caller = Thread.currentThread()
        val ran = AtomicInteger()
        val pause = ArtHarvest.PausedForGame()
        val uncaught = AtomicInteger()
        val was = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught.incrementAndGet() }
        try {
            GameFiles.inPool((1..200).toList(), 4, "art-test", 1) { i ->
                check(Thread.currentThread() !== caller)
                ran.incrementAndGet()
                if (i == 3) throw pause
                Thread.sleep(2)
            }
            fail("the pause must reach the calling thread")
        } catch (e: ArtHarvest.PausedForGame) {
            assertSame(pause, e)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(was)
        }
        assertEquals("no pool thread died uncaught", 0, uncaught.get())
        assertTrue("the items not started yet are skipped after the pause (${ran.get()} ran)", ran.get() < 200)
    }

    @Test fun everyItemRunsWhenNothingThrows() {
        val sum = AtomicInteger()
        GameFiles.inPool((1..100).toList(), 6, "art-test", 1) { sum.addAndGet(it) }
        assertEquals(5050, sum.get())
        GameFiles.inPool(emptyList<Int>(), 6, "art-test", 1) { fail("nothing to run") }
    }

    @Test fun laterThrowsRideOnTheFirst() {
        try {
            GameFiles.inPool((1..6).toList(), 6, "art-test", 1) { i -> Thread.sleep(20); throw IllegalStateException("x$i") }
            fail("must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("x"))
        }
    }
}
