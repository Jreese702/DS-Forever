package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * fix/stream-row-center (the user, 2026-11-18, test 1495 streamed: "I'm trying to sync my polestar up to where I'm actually at and its
 * saying polestar did not answer"): test 1495's own numbers. The strip: aimed 16.5 / 119.5, seen 16.0 / 118.0 (fit y x0.9903 -0.34).
 * The misses: pnav (rows 14-16) aimed 15.5 seen 16.0 pressed chq (16-18); iact (52-54) aimed 53.5 seen 52.0 pressed ipick (50-52);
 * mapw (94-96) aimed 96.5 seen 96.0 pressed mapw. The game sees whole pixels: a 2-px row's only own pixel is top + 1.
 */
class TapRowCenterTest {
    @Before fun clean() = TapCal.reset()
    @After fun after() = TapCal.reset()

    private val fit = TapCal.fitOf(listOf(16.5, 119.5), listOf(16.0, 118.0), "k")!!

    /** test 1495's rows: name -> top (game px), 2 px each */
    private val rows1495 = linkedMapOf("pnav" to 14.0, "chq" to 16.0, "ipick" to 50.0, "iact" to 52.0, "mapw" to 94.0)

    /**
     * test 1495's PC, as its landings allow: the whole pixel the game sees = floor(aim + e), e per row (the stream's staircase: no one
     * line fits 15.5 -> 16, 53.5 -> 52 and 96.5 -> 96 at once). Each e is inside the window its row's landing leaves.
     */
    private val e1495 = mapOf("pnav" to 0.6, "iact" to -1.4, "mapw" to -0.4)
    private fun px1495(op: String, aim: Double) = floor(aim + e1495.getValue(op))
    /** the button a press seen at whole pixel [y] goes to: its own row's centre pixel, or on an edge the game's pick ([edgeTo]) */
    private fun inRow(top: Double, y: Double) = y > top && y < top + 2

    @Test fun theMeasuredFitIsTest1495s() {
        assertEquals("y x0.9903 -0.34", fit.word())
    }

    @Test fun the1495MissesAreReproducedByTheOldAims() {
        for ((op, e) in e1495) assertTrue(abs(e) < 2)
        assertEquals(16.0, px1495("pnav", 15.5), 0.0)       // pnav -> chq's edge
        assertEquals(52.0, px1495("iact", 53.5), 0.0)       // iact -> ipick's edge
        assertEquals(96.0, px1495("mapw", 96.5), 0.0)       // mapw on its lower edge
    }

    @Test fun everyRowsCorrectedAimIsTheExactInverseAtItsCentre() {
        // through the fit, each row's aim is seen exactly at its centre pixel - inside its own band, never on an edge
        for ((op, top) in rows1495) {
            val aim = TapCal.cellY(top, 2.0, fit)
            assertEquals(op, top + 1.0, fit.seen(aim), 1e-9)
            assertTrue(op, fit.seen(aim) > top && fit.seen(aim) < top + 2)
        }
        for (r in 0 until 56) {
            val top = 12.0 + 2 * r
            val s = fit.seen(TapCal.cellY(top, 2.0, fit))
            assertTrue("row $r", s > top + 0.5 && s < top + 1.5)
        }
        // unsnapped: pnav's aim is 15.49 (the old re-snap made it 15.5), mapw's 96.27 (was 96.5)
        assertEquals(15.490, TapCal.cellY(14.0, 2.0, fit), 0.001)
        assertEquals(53.862, TapCal.cellY(52.0, 2.0, fit), 0.001)
        assertEquals(96.274, TapCal.cellY(94.0, 2.0, fit), 0.001)
        // an exact PC (seen = floor(aim): fit x1 -0.5) keeps today's point, the cell's lower pixel's centre
        val exact = TapCal.fitOf(listOf(16.5, 119.5), listOf(16.0, 119.0), "k")!!
        assertEquals(TapCal.cellY(14.0, 2.0, null), TapCal.cellY(14.0, 2.0, exact), 1e-9)
    }

    @Test fun theCentreAimAloneFixesMapwOnThe1495Pc() {
        assertTrue(inRow(94.0, px1495("mapw", TapCal.cellY(94.0, 2.0, fit))))
    }

    /** one stream tap of [op] on the 1495 PC, aimed as TapBridge aims it (fit + the row's nudge); the pixel the game saw */
    private fun streamTap(op: String, col: Int, batch: Long, at: Long, retry: Boolean = false): Double {
        val top = rows1495.getValue(op)
        val nudge = TapCal.nudge(op, top)
        val aim = TapCal.cellY(top, 2.0, fit) + nudge
        TapCal.aimed(listOf(TapCal.Aim(op, col, top, 2.0, aim, at, batch, stream = true, retry = retry, nudge = nudge)))
        return px1495(op, aim)
    }

    @Test fun pnavMissesOnceIsNudgedAndSentAgainAndThenLands() {
        // the first tap still meets chq's edge (pnav's e is past the fit's reach)
        val y1 = streamTap("pnav", 38, batch = 7, at = 1_000)
        assertEquals(16.0, y1, 0.0)
        val note = TapCal.landed("chq", 38, y1, 1_500)!!
        assertTrue(note, note.startsWith("tap landed off: pnav:38 (rows y 14.0-16.0, aimed 15.5) pressed chq:38 seen at y 16.0"))
        assertTrue(note, note.endsWith("; pnav aim nudged -1.0, sent again"))
        assertEquals(-1.0, TapCal.nudge("pnav", 14.0), 0.0)
        assertEquals(listOf(7L), TapCal.takeRetries())
        assertEquals("taken once", emptyList<Long>(), TapCal.takeRetries())
        // the retry (TapBridge.retryMissed sends batch 7 again, retry = true) lands on pnav's own pixel
        val y2 = streamTap("pnav", 38, batch = 8, at = 2_000, retry = true)
        assertEquals(15.0, y2, 0.0)
        assertTrue(inRow(14.0, y2))
        assertNull("a centre press says nothing", TapCal.landed("pnav", 38, y2, 2_300))
        // and every later pnav tap of the session lands too
        assertTrue(inRow(14.0, streamTap("pnav", 8, batch = 9, at = 3_000)))
    }

    @Test fun iactIsNudgedDownAndMapwUpOnItsEdge() {
        val yi = streamTap("iact", 0, batch = 3, at = 1_000)
        assertEquals(52.0, yi, 0.0)
        assertTrue(TapCal.landed("ipick", 0, yi, 1_200)!!.endsWith("; iact aim nudged +1.0, sent again"))
        assertTrue(inRow(52.0, streamTap("iact", 0, batch = 4, at = 2_000, retry = true)))
        // mapw at its old aim: the right button, but on the shared lower edge - nudged up, NOT sent again (it was pressed)
        TapCal.aimed(listOf(TapCal.Aim("mapw", 9, 94.0, 2.0, 96.5, 3_000, batch = 5, stream = true)))
        val n = TapCal.landed("mapw", 9, 96.0, 3_200)!!
        assertEquals("tap landed off: mapw:9 (rows y 94.0-96.0, aimed 96.5) pressed mapw:9 seen at y 96.0; mapw aim nudged -1.0", n)
        assertEquals(listOf(3L), TapCal.takeRetries())
        assertEquals(-1.0, TapCal.nudge("mapw", 94.0), 0.0)     // the next mapw tap goes one px up: 95.5 -> pixel 95, its centre
    }

    @Test fun oneLessonPerNudgeAndARetryIsNeverRetried() {
        // a 10-cell pnav batch: every press meets chq; only the first teaches (the rest carried the old nudge)
        for (c in 0 until 10) TapCal.aimed(listOf(TapCal.Aim("pnav", c, 14.0, 2.0, 15.49, 1_000, batch = 11, stream = true)))
        for (c in 0 until 10) TapCal.landed("chq", c, 16.0, 1_400)
        assertEquals(-1.0, TapCal.nudge("pnav", 14.0), 0.0)
        assertEquals(listOf(11L), TapCal.takeRetries())
        // the retry misses too (the other edge): nudged back, never queued again
        TapCal.aimed(listOf(TapCal.Aim("pnav", 38, 14.0, 2.0, 14.49, 2_000, batch = 12, stream = true, retry = true, nudge = -1.0)))
        assertNotNull(TapCal.landed("blank", 38, 14.0, 2_200))
        assertEquals(-0.0, TapCal.nudge("pnav", 14.0), 0.0)
        assertEquals(emptyList<Long>(), TapCal.takeRetries())
        // the nudge never passes one row height
        repeat(5) { i ->
            val cur = TapCal.nudge("pnav", 14.0)
            TapCal.aimed(listOf(TapCal.Aim("pnav", 1, 14.0, 2.0, 15.49 + cur, 3_000L + i, batch = 20L + i, stream = true, nudge = cur)))
            TapCal.landed("chq", 1, 16.0, 3_100L + i)
        }
        assertEquals(-2.0, TapCal.nudge("pnav", 14.0), 0.0)
    }

    @Test fun localTapsAreNeverNudgedNorSentAgain() {
        TapCal.aimed(listOf(TapCal.Aim("pnav", 38, 14.0, 2.0, 15.5, 1_000, batch = 1, stream = false)))
        assertNotNull("still noted", TapCal.landed("chq", 38, 16.0, 1_200))
        assertEquals(0.0, TapCal.nudge("pnav", 14.0), 0.0)
        assertEquals(emptyList<Long>(), TapCal.takeRetries())
        // a Local press on its own row's edge says nothing, as before
        TapCal.aimed(listOf(TapCal.Aim("mapw", 9, 94.0, 2.0, 95.5, 2_000, batch = 2, stream = false)))
        assertNull(TapCal.landed("mapw", 9, 95.0, 2_100))
        assertFalse(TapCal.nudge("mapw", 94.0) != 0.0)
    }
}
