package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyLayout.Box
import com.jrees.wowcompanion.PartyLayout.Mode
import com.jrees.wowcompanion.PartyModel.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** party_design.md C (the user's decisions 2026-11-11): ONE COLUMN of plates beside the Select tab, n = 1..5 x {rest, fight on, takeover} */
class PartyLayoutTest {
    private fun b(l: Int, t: Int, r: Int, bb: Int) = Box(l.toFloat(), t.toFloat(), r.toFloat(), bb.toFloat())

    @Test fun oneColumnAtRest() {
        val m = PartyLayout.module(Mode.REST, 5)
        assertEquals(listOf(b(64, 228, 603, 292), b(64, 300, 603, 364), b(64, 372, 603, 436), b(64, 444, 603, 508), b(64, 516, 603, 580)), m.cells)
        assertEquals(b(8, 216, 615, 592), m.well); assertEquals(600f, m.chatTop)
        PartyLayout.module(Mode.REST, 1).let { assertEquals(listOf(b(64, 228, 603, 292)), it.cells); assertEquals(b(8, 216, 615, 304), it.well) }
    }

    @Test fun plateHeightsPerMode() {
        for (n in 1..5) {
            assertTrue(PartyLayout.module(Mode.REST, n).cells.all { it.h == 64f })
            assertTrue(PartyLayout.module(Mode.FIGHT_ON, n).cells.all { it.h == 88f })
            val t = PartyLayout.module(Mode.FIGHT_OFF, n)
            assertTrue(t.cells.all { it.h == 56f }); assertEquals(398f, t.well.t)
            // one member per row, 8 px apart, all the same x
            assertEquals(n, t.cells.size)
            for (k in 1 until n) assertEquals(8f, t.cells[k].t - t.cells[k - 1].b)
            assertTrue(t.cells.all { it.l == 64f && it.r == 603f })
        }
        PartyLayout.module(Mode.FIGHT_ON, 5).let { assertEquals(b(8, 216, 615, 712), it.well); assertEquals(720f, it.chatTop) }
    }

    /** the user's decision 1 (2026-11-11): the tab STAYS, so nothing moves when Party mode turns on or off */
    @Test fun theSelectTabAlwaysStands() {
        val m = PartyLayout.module(Mode.REST, 3)
        assertEquals(b(20, 228, 56, 436), m.tag)
        assertTrue(PartyLayout.hitTag(m, 30f, 300f)); assertFalse(PartyLayout.hitTag(m, 300f, 300f))
        assertEquals(1, PartyLayout.hit(m, 300f, 330f)); assertEquals(-1, PartyLayout.hit(m, 40f, 330f)); assertEquals(-1, PartyLayout.hit(m, 700f, 330f))
        // the tab's parts: a tall tab carries the glyph, the arrow and the B; the user's decision 1 (2026-11-11, "B shown"): a one-plate
        // tab (64 at rest, 56 in the takeover) never drops the B - it takes the arrow's place in the On look; one fight plate (88) holds all three
        val tall = PartyLayout.tabParts(m.tag)
        assertEquals(38f, tall.glyphCx); assertEquals(32f, tall.glyph); assertEquals(20f, tall.arrow); assertFalse(tall.bOnArrow)
        assertTrue(tall.glyphCy < tall.arrowCy && tall.arrowCy < tall.bCy)
        for (mode in listOf(Mode.REST, Mode.FIGHT_OFF)) {
            val t1 = PartyLayout.module(mode, 1).tag
            val s = PartyLayout.tabParts(t1)
            assertTrue("$mode: B swaps in for the arrow", s.bOnArrow); assertEquals(s.arrowCy, s.bCy); assertTrue(s.b > 0f)
            assertTrue("$mode: the glyph and the B inside the tab",
                s.glyphCy - s.glyph / 2f >= t1.t && s.bCy + s.b / 2f <= t1.b && s.glyphCy + s.glyph / 2f <= s.bCy - s.b / 2f)
        }
        val f1 = PartyLayout.module(Mode.FIGHT_ON, 1).tag
        val f = PartyLayout.tabParts(f1)
        assertFalse(f.bOnArrow); assertTrue(f.glyphCy - f.glyph / 2f >= f1.t && f.bCy + f.b / 2f <= f1.b && f.arrowCy + f.arrow / 2f <= f.bCy - f.b / 2f)
        // the notch at the picked plate's centre
        assertEquals(332f, PartyLayout.notchY(m, 2)); assertEquals(404f, PartyLayout.notchY(m, 9))
    }

    @Test fun plateParts() {
        val p = b(64, 228, 603, 292)
        assertEquals(b(64, 228, 70, 292), PartyLayout.stripe(p))
        assertEquals(b(78, 240, 118, 280), PartyLayout.icon(p, Mode.REST))
        assertEquals(48f, PartyLayout.icon(b(64, 228, 603, 316), Mode.FIGHT_ON).w)
        assertEquals(130f, PartyLayout.nameX(p, Mode.REST)); assertEquals(24f, PartyLayout.nameSize(Mode.REST)); assertEquals(28f, PartyLayout.nameSize(Mode.FIGHT_ON))
        assertEquals(b(559, 242, 595, 278), PartyLayout.glyph(p))
        // the chips: 40 px, 50 px apart, right-aligned before the LB glyph
        val ch = PartyLayout.chips(p, 3)
        assertEquals(listOf(447f, 497f, 547f), ch.map { it.r }); assertTrue(ch.all { it.w == 40f && it.cy == p.cy })
        assertTrue(ch.last().r < PartyLayout.glyph(p).l)
        assertTrue(PartyLayout.nameRoom(p, Mode.REST, 3, true) < PartyLayout.nameRoom(p, Mode.REST, 0, true))
        assertEquals(24f, PartyLayout.badge(ch[0]).w)
    }

    @Test fun navWalksTheColumn() {
        assertEquals(2, PartyModel.nav(5, 1, Dir.DOWN)); assertEquals(3, PartyModel.nav(5, 2, Dir.DOWN)); assertEquals(4, PartyModel.nav(5, 5, Dir.UP))
        assertNull(PartyModel.nav(2, 1, Dir.RIGHT)); assertNull(PartyModel.nav(1, 1, Dir.DOWN)); assertNull(PartyModel.nav(3, 1, Dir.UP))
        // the drawn rows are the NAV rows: each plate its own row, Down = the next one under it
        for (n in 1..5) {
            val cells = PartyLayout.module(Mode.REST, n).cells
            assertEquals(PartyModel.rows(n), (1..n).map { listOf(it) })
            for (k in 1 until n) assertEquals(k + 1, PartyModel.nav(n, k, Dir.DOWN)); for (k in 1 until n) assertTrue(cells[k].t > cells[k - 1].t)
        }
    }
}
