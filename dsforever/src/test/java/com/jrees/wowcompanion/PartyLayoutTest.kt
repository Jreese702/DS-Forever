package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyLayout.Box
import com.jrees.wowcompanion.PartyLayout.Mode
import com.jrees.wowcompanion.PartyModel.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * party_design.md C and "ADAPTIVE ROWS" (the user, 2026-11-11: "when one two or three full sized, when 4, bottom 2 consolidate, when
 * 5 the next 2 consolidate", "WHEN NO PET MAKE THE PLAYER FULL SIZED", "MAKE THE PET A BIT SMALLER"): the cells beside the Select tab,
 * n = 1..6 x pet / no pet x {rest, fight on, takeover}
 */
class PartyLayoutTest {
    private fun b(l: Int, t: Int, r: Int, bb: Int) = Box(l.toFloat(), t.toFloat(), r.toFloat(), bb.toFloat())

    @Test fun upToThreeFullRows() {
        val m = PartyLayout.module(Mode.REST, 3)
        assertEquals(listOf(b(64, 228, 603, 292), b(64, 300, 603, 364), b(64, 372, 603, 436)), m.cells)
        assertEquals(b(8, 216, 615, 448), m.well); assertEquals(456f, m.chatTop); assertEquals(listOf(0, 1, 2), m.rows)
        PartyLayout.module(Mode.REST, 1).let { assertEquals(listOf(b(64, 228, 603, 292)), it.cells); assertEquals(b(8, 216, 615, 304), it.well) }
        // no pet: you full width whatever the size (the user: "WHEN NO PET MAKE THE PLAYER FULL SIZED")
        for (n in 1..5) assertEquals(b(64, 228, 603, 292), PartyLayout.module(Mode.REST, n).cells[0])
    }

    @Test fun theBottomRowsConsolidate() {
        // n 4: e1 | e2 | (e3 e4) - halves, 8 px apart
        PartyLayout.module(Mode.REST, 4).let { m ->
            assertEquals(listOf(b(64, 228, 603, 292), b(64, 300, 603, 364), Box(64f, 372f, 329.5f, 436f), Box(337.5f, 372f, 603f, 436f)), m.cells)
            assertEquals(listOf(0, 1, 2, 2), m.rows)
            assertEquals(m.cells[2].w, m.cells[3].w, 0.01f); assertEquals(8f, m.cells[3].l - m.cells[2].r, 0.01f)
        }
        // n 5: e1 | (e2 e3) | (e4 e5); n 6: (e1 e2) | (e3 e4) | (e5 e6) - always three rows
        assertEquals(listOf(0, 1, 1, 2, 2), PartyLayout.module(Mode.REST, 5).rows)
        assertEquals(listOf(0, 0, 1, 1, 2, 2), PartyLayout.module(Mode.REST, 6, pet = true).rows)
        for (n in 3..6) assertEquals(b(8, 216, 615, 448), PartyLayout.module(Mode.REST, n, pet = n == 6).well)
    }

    @Test fun thePetIsTheNarrowerOfItsRow() {
        // n 5 with the pet: you | (pet p1) | (p2 p3) - the pet left and narrower, p1 wider
        PartyLayout.module(Mode.REST, 5, pet = true).let { m ->
            assertTrue(m.cells[1].w < m.cells[2].w); assertEquals(PartyLayout.PET_SHARE * (539f - 8f), m.cells[1].w, 0.01f)
            assertEquals(m.cells[3].w, m.cells[4].w, 0.01f)                               // two people: equal halves
            assertEquals(64f, m.cells[1].l); assertEquals(603f, m.cells[2].r)
        }
        // n 6: (you pet) - the pet right and narrower
        PartyLayout.module(Mode.REST, 6, pet = true).let { m -> assertTrue(m.cells[1].w < m.cells[0].w); assertEquals(603f, m.cells[1].r) }
        // n 4 with the pet: you | pet | (p1 p2) - the pet's row is its own, full width
        PartyLayout.module(Mode.REST, 4, pet = true).let { m -> assertEquals(539f, m.cells[1].w); assertEquals(m.cells[2].w, m.cells[3].w, 0.01f) }
    }

    @Test fun rowHeightsPerMode() {
        for (n in 1..6) for (pet in listOf(false, true)) {
            if (pet && n < 2 || !pet && n > 5) continue
            assertTrue(PartyLayout.module(Mode.REST, n, pet).cells.all { it.h == 64f })
            assertTrue(PartyLayout.module(Mode.FIGHT_ON, n, pet).cells.all { it.h == 88f })
            val t = PartyLayout.module(Mode.FIGHT_OFF, n, pet)
            assertTrue(t.cells.all { it.h == 56f }); assertEquals(398f, t.well.t)
            assertEquals(n, t.cells.size)
            assertEquals(minOf(n, 3), t.rows.distinct().size)
            assertTrue(t.cells.all { it.l >= 64f && it.r <= 603f })
        }
        PartyLayout.module(Mode.FIGHT_ON, 5).let { assertEquals(b(8, 216, 615, 520), it.well); assertEquals(528f, it.chatTop) }
    }

    /** the user's decision 1 (2026-11-11): the tab STAYS, so nothing moves when Party mode turns on or off */
    @Test fun theSelectTabAlwaysStands() {
        val m = PartyLayout.module(Mode.REST, 3)
        assertEquals(b(20, 228, 56, 436), m.tag)
        assertTrue(PartyLayout.hitTag(m, 30f, 300f)); assertFalse(PartyLayout.hitTag(m, 300f, 300f))
        assertEquals(1, PartyLayout.hit(m, 300f, 330f)); assertEquals(-1, PartyLayout.hit(m, 40f, 330f)); assertEquals(-1, PartyLayout.hit(m, 700f, 330f))
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
        // the notch at the picked cell's row centre (a shared row's two cells: the same notch)
        assertEquals(332f, PartyLayout.notchY(m, 2)); assertEquals(404f, PartyLayout.notchY(m, 9))
        val m5 = PartyLayout.module(Mode.REST, 5)
        assertEquals(PartyLayout.notchY(m5, 2), PartyLayout.notchY(m5, 3)); assertTrue(PartyLayout.notchY(m5, 4) > PartyLayout.notchY(m5, 3))
        // the tab spans the column's rows (three at most)
        assertEquals(b(20, 228, 56, 436), PartyLayout.module(Mode.REST, 6, pet = true).tag)
    }

    @Test fun plateParts() {
        val p = b(64, 228, 603, 292)
        assertEquals(b(64, 228, 70, 292), PartyLayout.stripe(p))
        assertEquals(b(78, 240, 118, 280), PartyLayout.icon(p, Mode.REST))
        assertEquals(48f, PartyLayout.icon(b(64, 228, 603, 316), Mode.FIGHT_ON).w)
        assertEquals(130f, PartyLayout.nameX(p, Mode.REST)); assertEquals(24f, PartyLayout.nameSize(Mode.REST)); assertEquals(28f, PartyLayout.nameSize(Mode.FIGHT_ON))
        assertEquals(b(559, 242, 595, 278), PartyLayout.glyph(p))
        // a full cell: the chips 40 px, 50 px apart, right-aligned before the LB glyph
        val ch = PartyLayout.chips(p, 3)
        assertEquals(listOf(447f, 497f, 547f), ch.map { it.r }); assertTrue(ch.all { it.w == 40f && it.cy == p.cy })
        assertTrue(ch.last().r < PartyLayout.glyph(p).l)
        assertTrue(PartyLayout.nameRoom(p, Mode.REST, 3, true) < PartyLayout.nameRoom(p, Mode.REST, 0, true))
        assertEquals(24f, PartyLayout.badge(ch[0]).w)
        assertEquals(3, PartyLayout.chipsFit(p, Mode.REST, 3, true))
    }

    /** m131: a shared row's narrow cell takes fewer, smaller chips; the name keeps its room */
    @Test fun narrowCellsTakeFewerSmallerChips() {
        val m = PartyLayout.module(Mode.REST, 5, pet = true)
        val half = m.cells[3]; val pet = m.cells[1]; val wide = m.cells[2]
        assertEquals(30f, PartyLayout.chips(half, 2)[0].w); assertEquals(18f, PartyLayout.badge(PartyLayout.chips(half, 1)[0]).w)
        for (c in listOf(half, pet, wide)) {
            val k = PartyLayout.chipsFit(c, Mode.REST, 3, glyph = c !== pet)
            assertTrue("chips $k", k in 0..3)
            if (k > 0) assertTrue(PartyLayout.nameRoom(c, Mode.REST, k, c !== pet) >= PartyLayout.MIN_NAME)
            if (k < 3) assertTrue(PartyLayout.nameRoom(c, Mode.REST, k + 1, c !== pet) < PartyLayout.MIN_NAME)
            assertTrue(PartyLayout.chips(c, k).all { it.l >= c.l && it.r <= c.r })
        }
        assertTrue(PartyLayout.chipsFit(half, Mode.REST, 3, true) < 3)
    }

    /** the drawn cells are the NAV cells: Down goes to a cell drawn lower, Right to one drawn right in the same row */
    @Test fun navFollowsTheDrawing() {
        for (n in 1..6) {
            val m = PartyLayout.module(Mode.REST, n, pet = n == 6)
            for (k in 1..n) {
                PartyModel.nav(n, k, Dir.DOWN)?.let { assertTrue(m.cells[it - 1].t > m.cells[k - 1].t) }
                PartyModel.nav(n, k, Dir.UP)?.let { assertTrue(m.cells[it - 1].t < m.cells[k - 1].t) }
                PartyModel.nav(n, k, Dir.RIGHT)?.let { assertEquals(m.rows[k - 1], m.rows[it - 1]); assertTrue(m.cells[it - 1].l > m.cells[k - 1].l) }
                PartyModel.nav(n, k, Dir.LEFT)?.let { assertEquals(m.rows[k - 1], m.rows[it - 1]); assertTrue(m.cells[it - 1].l < m.cells[k - 1].l) }
            }
        }
        assertNull(PartyModel.nav(1, 1, Dir.DOWN))
    }
}
