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
            // readability pass: in the thin row a pencil sits ON its own button's corner (its own group only); nothing else on a button
            val own = when { diamonds -> emptyList(); n == "pad" -> listOf(2); n == "face" -> listOf(4); else -> emptyList() }
            for ((i, s) in slots.withIndex()) if (i !in own) assertTrue("$tag: $n $b on button $i $s", !b.overlaps(s))
            for (i in own) assertTrue("$tag: $n $b on its button's corner ${slots[i]}", b.cx >= slots[i].l && b.cx <= slots[i].r + b.w * 0.5f &&
                (b.t + b.b) / 2f >= (slots[i].t + slots[i].b) / 2f && b.b <= slots[i].b + 0.5f)
            if (clock != null) assertTrue("$tag: $n $b on the clock chip $clock", !b.overlaps(clock))
            for ((m, o) in pieces) if (m != n) assertTrue("$tag: $n $b on $m $o", !b.overlaps(o))
        }
        // each pencil at its OWN part's corner (the readability pass: the shots read the two as one pair): its centre within its half
        // and a little of its group's nearest button, and the two pencils well apart (the band's ends, or the thin row's two sides)
        for ((n, b, g) in listOf(Triple("pad", p.padPen, 0..3), Triple("face", p.facePen, 4..7))) {
            val d = g.minOf { gap(b, slots[it]) }
            assertTrue("$tag: $n $b at its part ($d px off)", d <= BandEdit.PAD + 1f)
            val other = if (n == "pad") 4..7 else 0..3
            assertTrue("$tag: $n $b nearer its own part than the other", d < other.minOf { gap(b, slots[it]) })
        }
        val apart = maxOf(p.facePen.l - p.padPen.r, p.padPen.l - p.facePen.r)
        assertTrue("$tag: the two pencils apart ($apart px)", apart >= if (diamonds) 400f else 60f)
        // the D-pad's pencil is not beside the Look chip (the shots: they read as one piece)
        p.look?.let { l -> assertTrue("$tag: the Look chip ${l} clear of the D-pad's pencil ${p.padPen}", gap(l, p.padPen) >= if (diamonds) 24f else 12f) }
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

    /** the 1519 integration: Top screen's preview lays the band above the page (HomeLayoutSolver.away) - the fight pencil stands at the
     *  PAGE's top centre, PEN square, AWAY_TOP down; the band's other pieces stay with the band (above the page); out of the preview, or
     *  with the band on the page, [BandEdit.place]'s away changes nothing */
    @Test fun topScreensPreviewPutsTheFightPencilWhereTheBandWent() {
        for (diamonds in listOf(false, true)) {
            val h = if (diamonds) 200f else CastBarPlace.ROW_H
            val rb = 8f + h
            val band = HomeLayoutSolver.Box(0f, 8f - (rb + HomeLayoutSolver.AWAY_PAST), W, -HomeLayoutSolver.AWAY_PAST)
            val sl = slots(diamonds, h, band.t, false)
            val p = BandEdit.place(band, sl, null, diamonds, false, true, away = true)
            val f = p.fightPen!!
            assertTrue("diamonds=$diamonds: at the page's top centre ($f)", kotlin.math.abs(f.cx - W / 2f) < 0.5f && f.t == BandEdit.AWAY_TOP &&
                kotlin.math.abs(f.w - BandEdit.PEN) < 0.5f && kotlin.math.abs(f.h - BandEdit.PEN) < 0.5f)
            assertNull(p.look)
            assertTrue("the band's pencils above the page with it", p.padPen.b <= 0f && p.facePen.b <= 0f)
            // the band on the page: away changes nothing; out of the preview: no fight pencil
            val on = HomeLayoutSolver.Box(0f, 8f, W, rb)
            val a = BandEdit.place(on, slots(diamonds, h, 8f, false), null, diamonds, false, true)
            assertTrue(a.fightPen!!.t >= on.t)
            assertNull(BandEdit.place(band, sl, null, diamonds, false, false, away = true).fightPen)
        }
    }

    /** the empty space between two boxes (0 = touching or overlapping) */
    private fun gap(a: HomeLayoutSolver.Box, b: HomeLayoutSolver.Box): Float {
        val dx = maxOf(0f, maxOf(a.l - b.r, b.l - a.r)); val dy = maxOf(0f, maxOf(a.t - b.b, b.t - a.b))
        return kotlin.math.hypot(dx, dy)
    }

    private object UiKitSizes { fun chip(gold: Boolean) = ClockBand.chipBottom(gold) - ClockBand.CHIP_TOP }
}
