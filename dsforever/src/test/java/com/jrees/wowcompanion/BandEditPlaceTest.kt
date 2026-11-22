package com.jrees.wowcompanion

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TOP BAR'S PIECES STAND IN THE BAND'S OWN GAPS (P1 / P3 / P4, the user's rulings 2026-11-22: nothing on the band moves for them):
 * against CastBarPlace's real slot table at 1240 wide, in the thin row, the small diamonds and the grown fight band, no pencil, Look
 * chip or fight pencil lies on a button, the clock chip or another piece, and each stays inside the band. Pure.
 */
class BandEditPlaceTest {
    private val W = 1240f

    /** the band's 8 buttons for a band [h] tall, its top at [top] (CastView.bandSlot's arithmetic, CastBarPlace's tables) */
    private fun slots(diamonds: Boolean, h: Float, top: Float, grown: Boolean): List<HomeLayoutSolver.Box> {
        val strip = if (grown) 0f else CastBarPlace.REST_STRIP
        val topPx = if (grown) CastBarPlace.TOP_STRIP else 0f
        val bandH = h - strip - topPx
        val sx = if (diamonds) bandH / CastBarPlace.TAKE_H else W / CastBarPlace.FRAME_W
        val t = if (diamonds) 1f else 0f
        val o = FloatArray(3)
        return (0 until 8).map { p ->
            CastBarPlace.slot(p, diamonds, t, sx, 0f, topPx, W, o)
            val r = o[2] / 2f
            HomeLayoutSolver.Box(o[0] - r, top + o[1] - r, o[0] + r, top + o[1] + r)
        }
    }

    private fun check(tag: String, band: HomeLayoutSolver.Box, slots: List<HomeLayoutSolver.Box>, clock: HomeLayoutSolver.Box?, diamonds: Boolean,
                      grown: Boolean, preview: Boolean) {
        val p = BandEdit.place(band, slots, clock, diamonds, grown, preview)
        val pieces = listOfNotNull("pad" to p.padPen, "face" to p.facePen, p.look?.let { "look" to it }, p.fightPen?.let { "fight" to it })
        for ((n, b) in pieces) {
            assertTrue("$tag: $n $b inside the band $band", b.l >= band.l && b.r <= band.r && b.t >= band.t - 0.5f && b.b <= band.b + 0.5f)
            assertTrue("$tag: $n $b at least 24 px", b.w >= 24f && b.h >= 24f)
            for ((i, s) in slots.withIndex()) assertTrue("$tag: $n $b on button $i $s", !b.overlaps(s))
            if (clock != null) assertTrue("$tag: $n $b on the clock chip $clock", !b.overlaps(clock))
            for ((m, o) in pieces) if (m != n) assertTrue("$tag: $n $b on $m $o", !b.overlaps(o))
        }
        if (preview) { assertNull(p.look); assertNotNull(p.fightPen) } else { assertNotNull(p.look); assertNull(p.fightPen) }
    }

    @Test fun thePiecesStandClearOfEveryButton() {
        val top = 8f
        // the thin row (112 tall)
        val row = HomeLayoutSolver.Box(0f, top, W, top + CastBarPlace.ROW_H)
        for (pv in listOf(false, true)) check("row preview=$pv", row, slots(false, CastBarPlace.ROW_H, top, false), null, false, false, pv)
        // the small diamonds at rest (200 tall), the clock chip at the centre (with the gold line: 108 tall)
        val dh = 200f
        val dia = HomeLayoutSolver.Box(0f, top, W, top + dh)
        for (gold in listOf(false, true)) {
            val ch = UiKitSizes.chip(gold)
            val clock = HomeLayoutSolver.Box(W / 2f - 78f, top + ClockBand.CHIP_TOP, W / 2f + 78f, top + ClockBand.CHIP_TOP + ch)
            for (pv in listOf(false, true)) check("diamonds gold=$gold preview=$pv", dia, slots(true, dh, top, false), clock, true, false, pv)
        }
        // the grown fight band (380 tall): the fight pencil at its top centre
        val gh = 380f
        check("grown", HomeLayoutSolver.Box(0f, top, W, top + gh), slots(true, gh, top, true), null, true, true, true)
    }

    private object UiKitSizes { fun chip(gold: Boolean) = ClockBand.chipBottom(gold) - ClockBand.CHIP_TOP }
}
