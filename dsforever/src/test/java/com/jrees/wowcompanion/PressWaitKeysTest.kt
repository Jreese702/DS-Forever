package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #521 (1.0.34 crash report): PressWait.keys() threw ConcurrentModificationException when two or more waits had expired, because
 *  isWaiting() removed each expired key from the very key set keys() was walking */
class PressWaitKeysTest {
    private var clock = 1_000L
    private fun wait() = PressWait(redraw = {}, nextFrame = {}, later = { _, _ -> }, now = { clock })

    @Test fun keysSurvivesSeveralExpiredWaits() {
        val w = wait()
        w.begin("a"); w.begin("b"); w.begin("c")
        clock += PressWait.TIMEOUT_MS + 1
        assertEquals(emptyList<String>(), w.keys())             // before the fix: ConcurrentModificationException
        assertTrue(w.accept("a"))                               // and the expired waits are gone
    }

    @Test fun keysKeepsTheLiveWaitsAndDropsTheExpiredOnes() {
        val w = wait()
        w.begin("old1"); w.begin("old2")
        clock += PressWait.TIMEOUT_MS - 100
        w.begin("fresh")
        clock += 200                                            // old1 / old2 expired, fresh still waits
        assertEquals(listOf("fresh"), w.keys())
        assertFalse(w.accept("fresh"))
        assertTrue(w.accept("old1"))
    }
}
