package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/castbar-slides: the half a part belongs to for Home's side slides (the cast bar by its side, Both = neither), and the middle wheel's foot */
class HomeSlideTest {
    private val w = 1240f
    private val L = CastStripModel.Side.LEFT
    private val R = CastStripModel.Side.RIGHT
    private val B = CastStripModel.Side.BOTH

    @Test fun theBarsHalfIsItsSide() {
        // Both's bar spans the page: its centre is the middle, which used to count as the RIGHT column
        assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, B, false, w / 2f, w))
        assertEquals(HomeSlide.Half.LEFT, HomeSlide.half(true, L, false, 300f, w))
        assertEquals(HomeSlide.Half.RIGHT, HomeSlide.half(true, R, false, 930f, w))
        // slid away for a fight (below the page): no column's
        assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, L, true, 300f, w))
        assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, R, true, 930f, w))
    }

    @Test fun aTopBarGoesWithItsColumn() {
        // Above chat / Above map: the bar's half is its column's, either side, every share (laid by the solver)
        for (side in HomeLayout.Side.values()) for (share in HomeLayout.Share.values())
            for (d in listOf(HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP)) {
                val l = HomeLayout(chatSide = side, chatShare = share, cast = d)
                val r = HomeLayoutSolver.places(l, HomeLayoutSolver.State())
                val strip = r.boxes.getValue(HomePart.STRIP)
                val col = r.boxes.getValue(if (d == HomeLayout.CastDock.ABOVE_CHAT) HomePart.CHAT else HomePart.MAP)
                val want = if (col.cx < (r.boxes.getValue(HomePart.CHAT).cx + r.boxes.getValue(HomePart.MAP).cx) / 2f) HomeSlide.Half.LEFT else HomeSlide.Half.RIGHT
                assertEquals("$l", want, HomeSlide.half(true, r.castSide, false, strip.cx, w))
                // hidden (up behind the band): no column's
                assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, r.castSide, true, strip.cx, w))
            }
    }

    @Test fun otherPartsByTheirCentre() {
        assertEquals(HomeSlide.Half.LEFT, HomeSlide.half(false, B, false, 300f, w))
        assertEquals(HomeSlide.Half.RIGHT, HomeSlide.half(false, B, false, 930f, w))
        assertEquals(HomeSlide.Half.RIGHT, HomeSlide.half(false, L, false, w / 2f, w))
    }

    @Test fun offsets() {
        assertEquals(-340f, HomeSlide.dx(HomeSlide.Half.LEFT, 340f), 0f)
        assertEquals(340f, HomeSlide.dx(HomeSlide.Half.RIGHT, 340f), 0f)
        assertEquals(0f, HomeSlide.dx(HomeSlide.Half.NONE, 340f), 0f)
    }

    @Test fun middleWheelSitsAboveBothsBar() {
        val h = 940f; val g = 10f
        assertEquals(780f, HomeSlide.middleBottom(B, false, 790f, h, g), 0f)
        assertEquals(h - g, HomeSlide.middleBottom(B, true, 790f, h, g), 0f)      // hidden for a fight: the page's foot
        assertEquals(h - g, HomeSlide.middleBottom(L, false, 790f, h, g), 0f)
        assertEquals(h - g, HomeSlide.middleBottom(B, false, null, h, g), 0f)
        val m = ClassSlot.middle(w, h, 220f, g, w * 0.6f, bottom = 780f)
        assertEquals(780f, m.top + m.side, 0.01f)
    }
}
