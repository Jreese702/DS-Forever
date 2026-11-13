package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #230 (1.0.28, "DS Forever stopped responding", Waited 5000ms for MotionEvent; the main thread RUNNABLE in the touchpad's pointer
 * move and no MemWatch stall line): the diagnostics that say whether the top screen's touches reached the main thread late, were slow
 * there, or the main thread missed its beat for under 2.5 s.
 */
class TouchLagTest {
    private val lines = ArrayList<String>()

    @Before fun setUp() { TouchLag.reset(); TouchLag.sink = { lines.add(it) }; TouchLag.beatAge = { 400L } }
    @After fun tearDown() { TouchLag.reset() }

    @Test fun aLateTouchIsLoggedAtOnceWithTheBeat() {
        TouchLag.note(2, eventTime = 10_000, start = 13_200, end = 13_212)
        assertEquals(listOf("top screen touch (move) reached the main thread 3.2 s late, handled in 12 ms; main beat 0.4 s old"), lines)
    }

    @Test fun aSlowTouchIsLoggedAtOnce() {
        TouchLag.note(0, eventTime = 10_000, start = 10_002, end = 10_402)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("(down)") && lines[0].contains("handled in 400 ms"))
    }

    @Test fun theAtOnceLineIsRateLimited() {
        repeat(50) { i -> TouchLag.note(2, 10_000L + i * 10, 12_000L + i * 10, 12_001L + i * 10) }
        assertEquals(1, lines.size)
        TouchLag.note(2, 20_000, 22_000, 22_001)                      // past LOG_EVERY_MS
        assertEquals(2, lines.size)
    }

    @Test fun normalTouchesSayNothing() {
        repeat(200) { i -> TouchLag.note(2, 1_000L + i * 8, 1_004L + i * 8, 1_006L + i * 8) }
        assertTrue(lines.isEmpty())
        assertEquals("", TouchLag.take())
    }

    @Test fun theLineTakesTheWorstSinceTheLastAndStartsAgain() {
        TouchLag.note(2, 1_000, 1_150, 1_160)
        TouchLag.note(2, 2_000, 2_010, 2_050)
        assertEquals("touch late max 0.1 s, slow max 40 ms (n 2)", TouchLag.take())
        assertEquals("", TouchLag.take())
    }

    @Test fun summaryAndBeatNoteThresholds() {
        assertEquals("", TouchLag.summary(0, 9_000, 9_000))
        assertEquals("", TouchLag.summary(5, 99, 99))
        assertEquals("touch late max 0.0 s, slow max 100 ms (n 5)", TouchLag.summary(5, 0, 100))
        assertEquals("", MemWatch.beatNote(999))
        assertEquals("main beat max 1.4 s", MemWatch.beatNote(1_400))
    }

    @Test fun aClockGoingBackCountsAsZero() {
        TouchLag.note(1, eventTime = 5_000, start = 4_000, end = 3_000)
        assertTrue(lines.isEmpty())
        assertEquals("", TouchLag.take())
    }
}
