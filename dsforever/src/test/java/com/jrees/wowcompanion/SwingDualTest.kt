package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sw-dual (the user, 2026-11-16: "Dual weilding needs split swing timers for each weapon"): WHEN Home's bar shows two swing lines
 * (hudS.so, the off hand's swing, beside hudS.sw, the main hand's) and WHERE they sit in the bar's own box. Pure.
 */
class SwingDualTest {
    private val NaN = Double.NaN
    private val G = 0.5                                        // CastView's SWING_GRACE

    @Test fun twoLinesOnlyWithAnOffHandSwing() {
        assertFalse("no off-hand swing (one weapon): today's one line", SwingLine.dual(true, 1_000.0, NaN, 0.0, false, NaN, 0.0, G))
        assertTrue("an off-hand swing running", SwingLine.dual(true, 1_000.0, NaN, 0.0, false, 500.0, 1_800.0, G))
        assertTrue("its wait after the fill (the grace) keeps the two lines", SwingLine.dual(true, 3_000.0, NaN, 0.0, false, 500.0, 1_800.0, G))
        assertFalse("long over: one line again", SwingLine.dual(true, 3_300.0, NaN, 0.0, false, 500.0, 1_800.0, G))
        assertFalse("a zero length is no swing", SwingLine.dual(true, 1_000.0, NaN, 0.0, false, 500.0, 0.0, G))
    }

    @Test fun aCastOrTheSwingSettingWins() {
        assertFalse("a cast takes the whole box", SwingLine.dual(true, 1_000.0, 800.0, 2_000.0, false, 500.0, 1_800.0, G))
        assertTrue("... and gives it back when it ends", SwingLine.dual(true, 2_200.0, 800.0, 2_000.0, false, 1_500.0, 1_800.0, G))
        assertFalse("a cast's prediction / finish / interrupt too", SwingLine.dual(true, 1_000.0, NaN, 0.0, true, 500.0, 1_800.0, G))
        assertFalse("Settings > Home \"Swing timer\" off: no swing at all", SwingLine.dual(false, 1_000.0, NaN, 0.0, false, 500.0, 1_800.0, G))
    }

    @Test fun theRowsSplitTheBarsOwnBox() {
        for ((top, bottom, s) in listOf(Triple(2f, 30f, 1f), Triple(1022f, 1036f, 1f), Triple(3f, 45f, 1.5f))) {
            val r = SwingLine.rows(top, bottom, s)
            assertEquals("the main hand's line starts where today's bar does", top, r[0], 0.001f)
            assertEquals("the off hand's line ends where today's bar does", bottom, r[3], 0.001f)
            assertEquals("a gap between", SwingLine.DUAL_GAP * s, r[2] - r[1], 0.001f)
            assertTrue("the off hand's line is the thinner", r[3] - r[2] < r[1] - r[0])
            assertTrue("both have height", r[3] - r[2] > 0f && r[1] - r[0] > 0f)
        }
        assertTrue("dimmer, not faint", SwingLine.OFF_ALPHA in 150..230)
    }
}
