package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix/menus-fit (the user on 1516: "The LB menu doesn't fit right with the map swapped and bigger and probably a lot of other menus"):
 * every Home overlay that parts the columns, over ALL 1,620 records the editor can make x rest / a fight (grown or not, the bar hidden
 * or staying) / Party mode. Pure: the solver's boxes, [HomeSlide]'s halves, [LbFit] and [ClassSlot] as HomeView uses them.
 *  - the LB picture: under the band as laid, inside the frame, never covered by a part once the sides moved (each part by its column);
 *  - the middle wheel: inside the frame, under the band, never covered by a parted column;
 *  - the class wheels: inside the frame (a hidden Split half below the page is no column's), under the band;
 *  - no column is "across" the page (only a full-screen module keeps its name's side in the slides).
 * Classic's own look stays 1516's (the picture's top over a grown band is held for the user's yes): [classicIs1516].
 */
class HomeMenusFitTest {
    private val S = HomeLayoutSolver
    private val W = 1240f
    private val G = 8f
    private val CARD_H = 118.75f

    private fun states(): List<Pair<String, HomeLayoutSolver.State>> = listOf(
        "rest" to HomeLayoutSolver.State(),
        "rest card" to HomeLayoutSolver.State(card = true, cardH = CARD_H),
        "fight" to HomeLayoutSolver.State(takeover = true, card = true, cardH = CARD_H),
        "fight hidden" to HomeLayoutSolver.State(takeover = true, card = true, cardH = CARD_H, castHidden = true),
        "party" to HomeLayoutSolver.State(party = HomeLayoutSolver.Party(PartyLayout.Mode.REST, 3, false), partyOn = true),
        "fight party" to HomeLayoutSolver.State(takeover = true, party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_OFF, 5, true)),
    )

    private class Page(val l: HomeLayout, val s: HomeLayoutSolver.State, val r: HomeLayoutSolver.Result) {
        val split get() = l.cast == HomeLayout.CastDock.SPLIT
        val bb get() = r.boxes.getValue(HomePart.BARS).b
        /** the parts on the page (the buff list lies inside the map; a bar hidden for a fight lies below the page) */
        val shown: Map<HomePart, HomeLayoutSolver.Box> get() = r.boxes.filter { (p, b) -> p != HomePart.BARS && p != HomePart.AURAS && b.t < s.h }
        fun half(p: HomePart, b: HomeLayoutSolver.Box): HomeSlide.Half =
            HomeSlide.half(p == HomePart.STRIP || p == HomePart.CAST2, r.castSide, r.castHidden && r.castSide != CastStripModel.Side.OFF,
                b.cx, 1240f, split = split)
        fun line(): Pair<Float, Float> = HomeLayoutSolver.columns(l, 1240f, 8f).let { it.first.second to it.second.first }
    }

    private fun pages(): List<Page> {
        val out = ArrayList<Page>()
        for (l in HomeLayoutRules.all()) for ((_, s) in states()) out += Page(l, s, S.places(l, s))
        return out
    }

    private fun box(b: LbFit.Box) = HomeLayoutSolver.Box(b.l, b.t, b.r, b.b)
    private fun moved(b: HomeLayoutSolver.Box, dx: Float) = b.copy(l = b.l + dx, r = b.r + dx)
    private fun overlap(a: HomeLayoutSolver.Box, b: HomeLayoutSolver.Box) =
        a.l < b.r - 0.5f && b.l < a.r - 0.5f && a.t < b.b - 0.5f && b.t < a.b - 0.5f

    /** the LB picture's bottom limit HomeView.lbPlace uses: the top of a bar that stays (no column's), else the frame's foot */
    private fun lbBottom(p: Page): Float {
        var bottom = p.s.h - G
        for ((part, b) in p.shown) if ((part == HomePart.STRIP || part == HomePart.CAST2) && p.half(part, b) == HomeSlide.Half.NONE)
            bottom = minOf(bottom, b.t - G)
        return bottom
    }

    @Test fun noColumnIsAcrossThePage() {
        for (side in HomeLayout.Side.values()) for (share in HomeLayout.Share.values()) {
            val b = S.places(HomeLayout(chatSide = side, chatShare = share), HomeLayoutSolver.State()).boxes
            for (p in listOf(HomePart.CHAT, HomePart.MAP)) {
                val x = b.getValue(p)
                assertTrue("$side $share $p is a column, not across", !HomeSlide.fullWidth(x.l, x.r, W, G))
                // its centre is on its own side of the line between the columns
                val line = HomeLayoutSolver.columns(HomeLayout(chatSide = side, chatShare = share), W, G)
                assertEquals("$side $share $p's centre is on its own side", x.cx < W / 2f, x.r <= line.first.second + 0.5f)
            }
        }
    }

    @Test fun theLbPictureFitsEveryLayout() {
        val bad = ArrayList<String>()
        var n = 0
        for (p in pages()) {
            val line = p.line()
            val pl = LbFit.place(W, p.bb, line.first, line.second, lbBottom(p), classic = false, staleBars = 0f)
            val bars = LbFit.bars(pl).values.map { box(it) }
            n++
            fun no(m: String) { if (bad.size < 12) bad += "${p.l} / ${p.s}: $m" }
            if (pl.k < LbFit.K_MIN - 1e-3f) no("scaled to ${pl.k}")
            for (b in bars) {
                if (b.l < G - 0.5f || b.r > W - G + 0.5f || b.b > p.s.h - G + 0.5f) no("bar $b off the frame")
                if (b.t < p.bb) no("bar $b under the band (bottom ${p.bb})")
            }
            for ((part, b) in p.shown) {
                val h = if (HomeSlide.fullWidth(b.l, b.r, W, G)) null else p.half(part, b)
                val dx = HomeSlide.dx(h ?: continue, pl.pushLeft, pl.pushRight)
                val m = moved(b, dx)
                for (x in bars) if (overlap(m, x)) no("$part ${m} covers the picture's bar $x (push ${pl.pushLeft} / ${pl.pushRight})")
            }
        }
        assertTrue("checked $n pages:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    @Test fun theMiddleWheelFitsEveryLayout() {
        val bad = ArrayList<String>()
        for (p in pages()) {
            val line = p.line()
            val m = ClassSlot.middle(W, p.s.h, p.bb, G, W * 0.6f,
                bottom = HomeSlide.middleBottom(p.r.castSide, p.r.castHidden && p.r.castSide != CastStripModel.Side.OFF,
                    p.r.boxes[HomePart.STRIP]?.t, p.s.h, G), mid = (line.first + line.second) / 2f)
            val sq = HomeLayoutSolver.Box(m.left, m.top, m.left + m.side, m.top + m.side)
            fun no(x: String) { if (bad.size < 12) bad += "${p.l} / ${p.s}: $x" }
            if (sq.l < G - 0.5f || sq.r > W - G + 0.5f || sq.b > p.s.h - G + 0.5f || sq.t < p.bb) no("square $sq off the frame / under the band")
            for ((part, b) in p.shown) {
                if (HomeSlide.fullWidth(b.l, b.r, W, G)) continue
                val mv = moved(b, HomeSlide.dx(p.half(part, b), m.push, m.pushRight))
                if (overlap(mv, sq)) no("$part $mv covers the wheel $sq")
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theClassWheelsStayInsideTheFrame() {
        val bad = ArrayList<String>()
        for (p in pages()) for (left in listOf(true, false)) {
            val want = if (left) HomeSlide.Half.LEFT else HomeSlide.Half.RIGHT
            val col = p.r.boxes.filter { (part, b) -> part != HomePart.BARS && part != HomePart.AURAS && p.half(part, b) == want }
            val a = ClassSlot.column(col.values.map { ClassSlot.Area(it.l, it.t, it.r, it.b) }, p.bb, p.s.h, G, left, W)
            val sl = ClassSlot.inColumn(a)
            if (sl.left < 0f || sl.left + sl.side > W || sl.top < p.bb || sl.top + sl.side > p.s.h - G + 0.5f)
                if (bad.size < 12) bad += "${p.l} / ${p.s} ${if (left) "LB+LT" else "RB+RT"}: ${sl}"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** Fighter's Split bar hidden for a fight is no column's (it lies below the page); shown, each half is its column's */
    @Test fun aHiddenSplitHalfIsNoColumns() {
        val B = CastStripModel.Side.BOTH
        assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, B, true, 300f, W, split = true))
        assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, B, true, 930f, W, split = true))
        assertEquals(HomeSlide.Half.LEFT, HomeSlide.half(true, B, false, 300f, W, split = true))
        assertEquals(HomeSlide.Half.RIGHT, HomeSlide.half(true, B, false, 930f, W, split = true))
        assertEquals("Both's bar stays", HomeSlide.Half.NONE, HomeSlide.half(true, B, false, 620f, W))
    }

    /** Classic (chat left, half): the picture and the wheels stand where 1516 put them, both sides 340 */
    @Test fun classicIs1516() {
        val c = HomeLayoutSolver.columns(HomeLayout(), W, G)
        val pl = LbFit.place(W, 226f, c.first.second, c.second.first, 927f, classic = true, staleBars = 0f)
        assertEquals(620f, pl.cx, 0f); assertEquals(236f, pl.top, 0f); assertEquals(1f, pl.k, 0f)
        assertEquals(LbPartyView.SIDE_PX, pl.pushLeft, 0f); assertEquals(LbPartyView.SIDE_PX, pl.pushRight, 0f)
        // the new rule gives Classic's rest look too (the band 226: top 236, the page's middle, 340 each side)
        val nw = LbFit.place(W, 226f, c.first.second, c.second.first, 927f, classic = false, staleBars = 0f)
        assertEquals(pl, nw.copy(classic = true))
        val bars = LbFit.bars(pl)
        assertEquals(312f, bars.getValue("PADDLEFT").l, 0f); assertEquals(928f, bars.getValue("PADDRIGHT").r, 0f)
        assertEquals(236f + 462f, bars.getValue("PADDDOWN").b, 0f)
        val m = ClassSlot.middle(W, 935f, 226f, G, W * 0.6f, mid = (c.first.second + c.second.first) / 2f)
        val old = ClassSlot.middle(W, 935f, 226f, G, W * 0.6f)
        assertEquals(old, m)
        assertEquals(m.push, m.pushRight, 0f)
    }

    /** the picture under a grown band scales to fit above a bar that stays (Compact's Both bar in a fight), never under the band */
    @Test fun theLbPictureScalesAboveABothBar() {
        val l = HomePresets.byId(HomePresets.COMPACT)!!.apply(HomeLayout())
        val r = S.places(l, HomeLayoutSolver.State(takeover = true))
        val strip = r.boxes.getValue(HomePart.STRIP)
        val bb = r.boxes.getValue(HomePart.BARS).b
        val c = HomeLayoutSolver.columns(l, W, G)
        val pl = LbFit.place(W, bb, c.first.second, c.second.first, strip.t - G, classic = false, staleBars = 0f)
        assertEquals(bb + 10f, pl.top, 0f)
        val bars = LbFit.bars(pl).values
        assertTrue("above the bar: ${bars.maxOf { it.b }} vs ${strip.t}", bars.maxOf { it.b } <= strip.t - G + 0.5f || pl.k <= LbFit.K_MIN)
    }
}
