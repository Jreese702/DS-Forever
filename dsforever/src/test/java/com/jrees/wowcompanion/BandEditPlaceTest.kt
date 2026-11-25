package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
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
            // (test 1530, "way to small": PEN_FIGHT square now, PEN before)
            assertTrue("diamonds=$diamonds: at the page's top centre ($f)", kotlin.math.abs(f.cx - W / 2f) < 0.5f && f.t == BandEdit.AWAY_TOP &&
                kotlin.math.abs(f.w - BandEdit.PEN_FIGHT) < 0.5f && kotlin.math.abs(f.h - BandEdit.PEN_FIGHT) < 0.5f)
            assertNull(p.look)
            assertTrue("the band's pencils above the page with it", p.padPen.b <= 0f && p.facePen.b <= 0f)
            // the band on the page: away changes nothing; out of the preview: no fight pencil
            val on = HomeLayoutSolver.Box(0f, 8f, W, rb)
            val a = BandEdit.place(on, slots(diamonds, h, 8f, false), null, diamonds, false, true)
            assertTrue(a.fightPen!!.t >= on.t)
            assertNull(BandEdit.place(band, sl, null, diamonds, false, false, away = true).fightPen)
        }
    }

    /**
     * THE FIGHT PENCIL AT THE FIGHT STRIP'S LEFT END (the user's pick 2026-11-23, "Left end of the strip": at the page's top centre the
     * pencil covered the middle of the cast bar strip a Top-screen fight lays across the page's top, HomeLayoutSolver.hasFightStrip):
     * with the strip ([BandEdit.place]'s strip) the pencil stands on the strip's left end - its centre on the strip's height and in the
     * strip's first [END] px, the strip's middle clear of it, the controller's cursor round it ([BandEdit.RING] out) on the page. Without
     * the strip (Cast bar in fights = Top screen) the page's top centre as before. Both looks
     */
    @Test fun withTheFightStripTheFightPencilStandsOnItsLeftEnd() {
        val strip = HomeLayoutSolver.Box(8f, 8f, W - 8f, 8f + HomeLayoutSolver.FIGHT_STRIP_H)
        for (diamonds in listOf(false, true)) {
            val h = if (diamonds) 200f else CastBarPlace.ROW_H
            val band = HomeLayoutSolver.Box(0f, 8f - (8f + h + HomeLayoutSolver.AWAY_PAST), W, -HomeLayoutSolver.AWAY_PAST)
            val sl = slots(diamonds, h, band.t, false)
            val f = BandEdit.place(band, sl, null, diamonds, false, true, away = true, strip = strip).fightPen!!
            val n = "diamonds=$diamonds: $f on the strip $strip"
            assertTrue("$n: at its left end", f.cx >= strip.l && f.cx <= strip.l + END && f.r <= strip.l + END + f.w / 2f)
            // (test 1530: the bigger pencil is taller than the strip - it hangs from the strip's left end, over its whole height)
            assertTrue("$n: over the strip's height", f.t < strip.b && f.b > strip.b && f.t <= strip.t + BandEdit.RING)
            assertTrue("$n: the strip's middle clear (${f.r} vs ${strip.cx})", f.r < strip.cx - strip.w / 4f)
            assertTrue("$n: the cursor round it on the page", f.l - BandEdit.RING >= -0.01f && f.t - BandEdit.RING >= -0.01f)
            assertTrue("$n: the strip pencil's size", kotlin.math.abs(f.w - BandEdit.PEN_ON_STRIP) < 0.5f && kotlin.math.abs(f.h - BandEdit.PEN_ON_STRIP) < 0.5f)
            // no strip: the page's top centre, PEN square, AWAY_TOP down (today's)
            val c = BandEdit.place(band, sl, null, diamonds, false, true, away = true, strip = null).fightPen!!
            assertTrue("diamonds=$diamonds: no strip - the page's top centre ($c)", kotlin.math.abs(c.cx - W / 2f) < 0.5f && c.t == BandEdit.AWAY_TOP &&
                kotlin.math.abs(c.w - BandEdit.PEN_FIGHT) < 0.5f)
            // the band on the page (out of Top screen's preview): a strip changes nothing
            val on = HomeLayoutSolver.Box(0f, 8f, W, 8f + h)
            val os = slots(diamonds, h, 8f, false)
            assertEquals(BandEdit.place(on, os, null, diamonds, false, true).fightPen!!.toString(),
                BandEdit.place(on, os, null, diamonds, false, true, strip = strip).fightPen!!.toString())
        }
    }
    /**
     * THE PENCILS ARE BIG ENOUGH TO TAP (the user on test 1530, 2026-11-25: "The in fight pencil is way to small in edit mode to reasonably
     * expect someone to try tapping it"): the fight pencil DRAWN at least 48 px (twice 1530's 24 px floor; 56 on the strip, 64 where the
     * band went) wherever it stands - the thin row's gap, the diamonds' notch, the grown band's top, the page's top centre, the strip's left
     * end - and EVERY pencil's TOUCH box (the D-pad's, the ABXY's, the fight one, the party list's) at least 48 dp on the screen: 110.7 px on
     * the Thor's 369 dpi bottom panel, through edit mode's page zoom (HomeView.pageMatrix: (frame - TAB_ROW) / frame). 1530 drew the fight
     * pencil 28-40 px and touched it in a 48 px box (HomeEditView.TOUCH_MIN): 19 dp
     */
    @Test fun thePencilsAreBigEnoughToTap() {
        val frame = 1080f - Strip.HEIGHT
        val zoom = (frame - HomeEditView.TAB_ROW) / frame
        val floor = BandEdit.TOUCH_DP * BandEdit.PANEL_DPI / 160f
        assertTrue("the page zoom ${zoom} within the touch floor's reach", zoom >= BandEdit.TOUCH_ZOOM_MIN)
        assertTrue("PEN_TOUCH ${BandEdit.PEN_TOUCH} Home px = ${BandEdit.PEN_TOUCH * zoom} screen px >= 48 dp ($floor)",
            BandEdit.PEN_TOUCH * BandEdit.TOUCH_ZOOM_MIN >= floor)
        val top = 8f
        val cases = ArrayList<Pair<String, BandEdit.Pieces>>()
        cases += "row" to BandEdit.place(HomeLayoutSolver.Box(0f, top, W, top + CastBarPlace.ROW_H), slots(false, CastBarPlace.ROW_H, top, false), null, false, false, true)
        val ch = UiKitSizes.chip(true)
        val clock = HomeLayoutSolver.Box(W / 2f - 78f, top + ClockBand.CHIP_TOP, W / 2f + 78f, top + ClockBand.CHIP_TOP + ch)
        cases += "diamonds" to BandEdit.place(HomeLayoutSolver.Box(0f, top, W, top + 200f), slots(true, 200f, top, false), clock, true, false, true)
        cases += "grown" to BandEdit.place(HomeLayoutSolver.Box(0f, top, W, top + 380f), slots(true, 380f, top, true), null, true, true, true)
        val away = HomeLayoutSolver.Box(0f, 8f - (8f + 200f + HomeLayoutSolver.AWAY_PAST), W, -HomeLayoutSolver.AWAY_PAST)
        val asl = slots(true, 200f, away.t, false)
        cases += "away" to BandEdit.place(away, asl, null, true, false, true, away = true)
        cases += "strip" to BandEdit.place(away, asl, null, true, false, true, away = true,
            strip = HomeLayoutSolver.Box(8f, 8f, W - 8f, 8f + HomeLayoutSolver.FIGHT_STRIP_H))
        for ((n, p) in cases) {
            val f = p.fightPen!!
            assertTrue("$n: the fight pencil drawn ${f.w} x ${f.h} >= 48", f.w >= 48f && f.h >= 48f)
            for ((pn, b) in listOf("fight" to f, "pad" to p.padPen, "face" to p.facePen)) {
                val t = BandEdit.touchBox(b)
                assertTrue("$n: the $pn pencil's touch box ${t.w} x ${t.h} (screen ${t.w * zoom}) >= 48 dp", t.w * zoom >= floor && t.h * zoom >= floor)
                assertTrue("$n: the $pn pencil's touch box holds the pencil", t.l <= b.l && t.r >= b.r && t.t <= b.t && t.b >= b.b)
            }
        }
        assertTrue("the strip's pencil", cases.first { it.first == "strip" }.second.fightPen!!.w >= BandEdit.PEN_ON_STRIP - 0.5f)
        assertTrue("where the band went", cases.first { it.first == "away" }.second.fightPen!!.w >= BandEdit.PEN_FIGHT - 0.5f)
        // the party list's pencil (HomeEditView.partyPenBox: PEN_PARTY with the room, PEN on a small list): its touch box too
        for (side in listOf(BandEdit.PEN_PARTY, BandEdit.PEN)) {
            val t = BandEdit.touchBox(HomeLayoutSolver.Box(100f, 100f, 100f + side, 100f + side))
            assertTrue("the party list's pencil ($side): touch ${t.w * zoom} >= 48 dp", t.w * zoom >= floor && t.h * zoom >= floor)
        }
        assertTrue("the party list's pencil drawn bigger than 1530's", BandEdit.PEN_PARTY > BandEdit.PEN)
    }

    /** the strip's "left end": its first 64 px (5 % of the 1224 px strip) */
    private val END = 64f

    /** the empty space between two boxes (0 = touching or overlapping) */
    private fun gap(a: HomeLayoutSolver.Box, b: HomeLayoutSolver.Box): Float {
        val dx = maxOf(0f, maxOf(a.l - b.r, b.l - a.r)); val dy = maxOf(0f, maxOf(a.t - b.b, b.t - a.b))
        return kotlin.math.hypot(dx, dy)
    }

    private object UiKitSizes { fun chip(gold: Boolean) = ClockBand.chipBottom(gold) - ClockBand.CHIP_TOP }
}
