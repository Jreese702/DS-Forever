package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/castbar-slides: the half a part belongs to for Home's side slides (the cast bar by its side, Both = neither); feat/radial-castbar: Both's drop */
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

    /**
     * feat/radial-castbar (the user, 2026-11-23: "When using radials and the tap to cast bar is set to full size, it should still move out
     * of the way to make space for the full sized radials"): Across's bar (no column's) drops below the page for a radial, to the box Hide in
     * combat lays it in; a column's bar (it goes with its column), a hidden one and every other part do not drop
     */
    @Test fun acrossDropsBelowThePage() {
        val h = 940f
        assertEquals(h - 790f, HomeSlide.barDrop(true, HomeSlide.Half.NONE, 790f, h), 0f)
        assertEquals(0f, HomeSlide.barDrop(true, HomeSlide.Half.LEFT, 790f, h), 0f)
        assertEquals(0f, HomeSlide.barDrop(true, HomeSlide.Half.RIGHT, 790f, h), 0f)
        assertEquals("hidden for a fight: already below", 0f, HomeSlide.barDrop(true, HomeSlide.Half.NONE, h, h), 0f)
        assertEquals("not a bar", 0f, HomeSlide.barDrop(false, HomeSlide.Half.NONE, 790f, h), 0f)
        // every dock the solver lays: only Across's bar drops (Split's halves go with their columns), and it lands where Hide lays it
        for (d in HomeLayout.CastDock.values()) for (side in HomeLayout.Side.values()) {
            val l = HomeLayout(chatSide = side, cast = d)
            val r = HomeLayoutSolver.places(l, HomeLayoutSolver.State())
            val hid = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true, castHidden = true))
            for (p in listOf(HomePart.STRIP, HomePart.CAST2)) {
                val b = r.boxes[p] ?: continue
                val half = HomeSlide.half(true, r.castSide, false, b.cx, w, split = d == HomeLayout.CastDock.SPLIT)
                val ph = HomeLayoutSolver.State().h
                val drop = HomeSlide.barDrop(true, half, b.t, ph)
                if (d == HomeLayout.CastDock.ACROSS) {
                    assertEquals("$l", ph - b.t, drop, 0f)
                    assertEquals("$l: Hide's box", hid.boxes.getValue(p).t, b.t + drop, 0.5f)
                } else assertEquals("$l $p", 0f, drop, 0f)
            }
        }
    }

    /** Across's bar makes room only when the radial needs it: smaller with the bar there, or reaching over it; never with no such bar */
    @Test fun theBarMakesRoomOnlyWhenNeeded() {
        val h = 940f; val g = 8f
        assertEquals(782f, HomeSlide.stayFoot(790f, h, g), 0f)
        assertEquals(h - g, HomeSlide.stayFoot(null, h, g), 0f)
        assertEquals(true, HomeSlide.makesRoom(790f, 553f, 693f, 782f, g))           // the middle wheel shrunk above it
        assertEquals(false, HomeSlide.makesRoom(790f, 462f, 462f, 698f, g))          // Classic's LB picture fits above it
        assertEquals(true, HomeSlide.makesRoom(790f, 462f, 462f, 800f, g))           // as large, but it would lie over the bar
        assertEquals(false, HomeSlide.makesRoom(null, 553f, 693f, 782f, g))           // no Across bar
    }

    /** the middle wheel down to the page's foot (it sat above Both's bar before the bar made room) */
    @Test fun middleWheelReachesThePagesFoot() {
        val h = 940f; val g = 10f
        val m = ClassSlot.middle(w, h, 220f, g, w * 0.6f)
        assertEquals(h - g, m.top + m.side, 0.01f)
    }

    /** a class wheel's column reaches down to a dropped bar's foot (its parts end over the bar): the column's square as with no bar */
    @Test fun classColumnReachesADroppedBarsFoot() {
        val chat = ClassSlot.Area(8f, 230f, 616f, 780f)
        val a = ClassSlot.column(listOf(chat), 220f, 940f, 8f, left = true, w = w, reach = 932f)
        assertEquals(932f, a.bottom, 0f)
        assertEquals(ClassSlot.column(listOf(chat.copy(bottom = 932f)), 220f, 940f, 8f, left = true, w = w), a)
        assertEquals("never below the frame", 932f, ClassSlot.column(listOf(chat), 220f, 940f, 8f, true, w, reach = 2000f).bottom, 0f)
        assertEquals("none: as before", 780f, ClassSlot.column(listOf(chat), 220f, 940f, 8f, true, w).bottom, 0f)
    }
}
