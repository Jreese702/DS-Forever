package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuestTrace t2 (the user, 2026-11-12, "Testing the Wells": "why is it showing this long narrow blue path as the quest area for the
 * current objective???"): a quest's separate traced blobs stay separate areas, and a thin strip is never drawn.
 */
class QuestTraceAreasTest {
    /** a filled square blob of side [s] yd at ([x], [y]), sampled every 4 yd */
    private fun blob(x: Double, y: Double, s: Double = 40.0): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var i = 0.0
        while (i <= s) { var j = 0.0; while (j <= s) { out.add(doubleArrayOf(x + i, y + j)); j += 4.0 }; i += 4.0 }
        return out
    }

    @Test fun twoFarBlobsAreTwoAreasNotOneStrip() {
        val a = QuestTrace.areas(blob(0.0, 0.0) + blob(500.0, 300.0))
        assertEquals(2, a.size)
        for (h in a) for (p in h) assertTrue("each hull stays in its own blob", p[0] < 60 || p[0] > 480)
    }

    @Test fun aWalkAlongARoadIsNeverDrawn() {
        val road = (0..100).map { doubleArrayOf(it * 4.0, it * 2.4) } + (0..100).map { doubleArrayOf(it * 4.0 + 3, it * 2.4) }
        assertTrue(QuestTrace.areas(road).isEmpty())
    }

    @Test fun aRealBlobIsDrawn() {
        assertEquals(1, QuestTrace.areas(blob(10.0, 10.0, 60.0)).size)
    }

    @Test fun drawableRejectsAStripKeepsASquare() {
        val strip = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(500.0, 300.0), doubleArrayOf(510.0, 285.0), doubleArrayOf(10.0, -15.0))
        assertFalse(QuestTrace.drawable(strip))
        val sq = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(50.0, 0.0), doubleArrayOf(50.0, 50.0), doubleArrayOf(0.0, 50.0))
        assertTrue(QuestTrace.drawable(sq))
    }
}
