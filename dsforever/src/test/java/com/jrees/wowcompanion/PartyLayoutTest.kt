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
 * party_design.md C, "ADAPTIVE ROWS" and m134's "PLAYER / PET REVISION" + "SELECT TAB LOOK" (the user, 2026-11-11, after using 1428:
 * "the player is too small and the pet is too big. I think the pet should be to the right of the player"; the Select tab "attached to
 * the side of the screen"): the cells right of the Select tab, every shape x {rest, fight on, takeover}
 */
class PartyLayoutTest {
    private fun b(l: Int, t: Int, r: Int, bb: Int) = Box(l.toFloat(), t.toFloat(), r.toFloat(), bb.toFloat())

    /** every shape the module holds: no pet n 1..5, the pet n 2..6 */
    private val shapes = (1..5).map { it to false } + (2..6).map { it to true }

    @Test fun upToThreeFullRows() {
        val m = PartyLayout.module(Mode.REST, 3)
        assertEquals(listOf(b(68, 228, 603, 292), b(68, 300, 603, 364), b(68, 372, 603, 436)), m.cells)
        assertEquals(b(56, 216, 615, 448), m.well); assertEquals(456f, m.chatTop); assertEquals(listOf(0, 1, 2), m.rows)
        PartyLayout.module(Mode.REST, 1).let { assertEquals(listOf(b(68, 228, 603, 292)), it.cells); assertEquals(b(56, 216, 615, 304), it.well) }
        // no pet: you full width whatever the size (the user: "WHEN NO PET MAKE THE PLAYER FULL SIZED")
        for (n in 1..5) assertEquals(b(68, 228, 603, 292), PartyLayout.module(Mode.REST, n).cells[0])
    }

    @Test fun theBottomRowsConsolidate() {
        // n 4: e1 | e2 | (e3 e4) - halves, 8 px apart
        PartyLayout.module(Mode.REST, 4).let { m ->
            assertEquals(listOf(b(68, 228, 603, 292), b(68, 300, 603, 364), Box(68f, 372f, 331.5f, 436f), Box(339.5f, 372f, 603f, 436f)), m.cells)
            assertEquals(listOf(0, 1, 2, 2), m.rows)
            assertEquals(m.cells[2].w, m.cells[3].w, 0.01f); assertEquals(8f, m.cells[3].l - m.cells[2].r, 0.01f)
        }
        // n 5: you | (p1 p2) | (p3 p4); with the pet: (you pet) | p1 | (p2 p3) and (you pet) | (p1 p2) | (p3 p4)
        assertEquals(listOf(0, 1, 1, 2, 2), PartyLayout.module(Mode.REST, 5).rows)
        assertEquals(listOf(0, 0, 1, 2, 2), PartyLayout.module(Mode.REST, 5, pet = true).rows)
        assertEquals(listOf(0, 0, 1, 1, 2, 2), PartyLayout.module(Mode.REST, 6, pet = true).rows)
        assertEquals(listOf(0, 0, 1, 2), PartyLayout.module(Mode.REST, 4, pet = true).rows)
        for (n in 3..6) assertEquals(b(56, 216, 615, 448), PartyLayout.module(Mode.REST, n, pet = n == 6).well)
    }

    /** m134: the pet ALWAYS on your right in the first row, you the big cell (about 70 / 30) */
    @Test fun thePetSitsRightOfYouAndSmaller() {
        for ((n, pet) in shapes.filter { it.second }) {
            val m = PartyLayout.module(Mode.REST, n, pet = true)
            val you = m.cells[0]; val p = m.cells[1]
            assertEquals("n $n: one row", m.rows[0], m.rows[1]); assertEquals(0, m.rows[1])
            assertEquals(68f, you.l); assertEquals(603f, p.r); assertEquals(8f, p.l - you.r, 0.01f)
            assertEquals(PartyLayout.PET_SHARE * (535f - 8f), p.w, 0.01f)
            assertTrue("the pet clearly secondary (about a third)", p.w < you.w * 0.55f)
            assertTrue("nobody else on the first row", m.rows.count { it == 0 } == 2)
        }
        // the party's halves under you + pet stay equal
        PartyLayout.module(Mode.REST, 6, pet = true).let { m -> assertEquals(m.cells[2].w, m.cells[3].w, 0.01f) }
    }

    @Test fun rowHeightsPerMode() {
        for ((n, pet) in shapes) {
            assertTrue(PartyLayout.module(Mode.REST, n, pet).cells.all { it.h == 64f })
            assertTrue(PartyLayout.module(Mode.FIGHT_ON, n, pet).cells.all { it.h == 88f })
            val t = PartyLayout.module(Mode.FIGHT_OFF, n, pet)
            assertTrue(t.cells.all { it.h == 56f }); assertEquals(398f, t.well.t)
            assertEquals(n, t.cells.size)
            assertEquals(PartyModel.rows(n, pet).size, t.rows.distinct().size)
            assertTrue(t.cells.all { it.l >= 68f && it.r <= 603f })
        }
        PartyLayout.module(Mode.FIGHT_ON, 5).let { assertEquals(b(56, 216, 615, 520), it.well); assertEquals(528f, it.chatTop) }
    }

    /**
     * the user's decision 1 (2026-11-11): the tab STAYS, so nothing moves when Party mode turns on or off; m134 ("sit outside of the
     * player brand look more like its attached to the side of the screen"): flush on the screen's left edge, the well's full height,
     * the member band to its right
     */
    @Test fun theSelectTabStandsOnTheScreensEdge() {
        val m = PartyLayout.module(Mode.REST, 3)
        assertEquals(b(0, 216, 52, 448), m.tag)
        assertTrue(m.tag.r < m.well.l && m.cells.all { it.l > m.tag.r })
        assertTrue(PartyLayout.hitTag(m, 10f, 300f)); assertFalse(PartyLayout.hitTag(m, 300f, 300f))
        assertEquals(1, PartyLayout.hit(m, 300f, 330f)); assertEquals(-1, PartyLayout.hit(m, 30f, 330f)); assertEquals(-1, PartyLayout.hit(m, 700f, 330f))
        val tall = PartyLayout.tabParts(m.tag)
        assertEquals(26f, tall.glyphCx); assertEquals(32f, tall.glyph); assertEquals(20f, tall.arrow); assertFalse(tall.bOnArrow)
        assertTrue(tall.glyphCy < tall.arrowCy && tall.arrowCy < tall.bCy)
        // one row at rest: the well's margins make the tab 88 high, all three parts fit; the takeover's one row (80): B on the arrow
        val r1 = PartyLayout.module(Mode.REST, 1).tag
        assertFalse(PartyLayout.tabParts(r1).bOnArrow)
        val t1 = PartyLayout.module(Mode.FIGHT_OFF, 1).tag
        val s = PartyLayout.tabParts(t1)
        assertTrue("B swaps in for the arrow", s.bOnArrow); assertEquals(s.arrowCy, s.bCy); assertTrue(s.b > 0f)
        assertTrue("the glyph and the B inside the tab",
            s.glyphCy - s.glyph / 2f >= t1.t && s.bCy + s.b / 2f <= t1.b && s.glyphCy + s.glyph / 2f <= s.bCy - s.b / 2f)
        for ((n, pet) in shapes) for (mode in Mode.values()) {
            val t = PartyLayout.module(mode, n, pet).tag
            val p = PartyLayout.tabParts(t)
            assertTrue("$mode $n $pet: inside", p.glyphCy - p.glyph / 2f >= t.t && p.bCy + p.b / 2f <= t.b)
        }
        // the notch at the picked cell's row centre (a shared row's two cells: the same notch)
        assertEquals(332f, PartyLayout.notchY(m, 2)); assertEquals(404f, PartyLayout.notchY(m, 9))
        val m5 = PartyLayout.module(Mode.REST, 5)
        assertEquals(PartyLayout.notchY(m5, 2), PartyLayout.notchY(m5, 3)); assertTrue(PartyLayout.notchY(m5, 4) > PartyLayout.notchY(m5, 3))
        // the tab spans the well (three rows at most)
        assertEquals(b(0, 216, 52, 448), PartyLayout.module(Mode.REST, 6, pet = true).tag)
    }

    @Test fun plateParts() {
        val p = b(68, 228, 603, 292)
        assertEquals(b(68, 228, 74, 292), PartyLayout.stripe(p))
        assertEquals(b(82, 240, 122, 280), PartyLayout.icon(p, Mode.REST))
        assertEquals(48f, PartyLayout.icon(b(68, 228, 603, 316), Mode.FIGHT_ON).w)
        assertEquals(134f, PartyLayout.nameX(p, Mode.REST)); assertEquals(24f, PartyLayout.nameSize(Mode.REST)); assertEquals(28f, PartyLayout.nameSize(Mode.FIGHT_ON))
        // m134: no chips and no LB glyph - the name runs to the plate's end
        assertEquals(603f - 18f - 134f, PartyLayout.nameRoom(p, Mode.REST))
        // the pet's cell still leaves its name room
        val pet = PartyLayout.module(Mode.REST, 3, pet = true).cells[1]
        assertTrue(PartyLayout.nameRoom(pet, Mode.REST) > 60f)
    }

    /** the drawn cells are the NAV cells: Down goes to a cell drawn lower, Right to one drawn right in the same row */
    @Test fun navFollowsTheDrawing() {
        for ((n, pet) in shapes) {
            val m = PartyLayout.module(Mode.REST, n, pet = pet)
            for (k in 1..n) {
                PartyModel.nav(n, k, Dir.DOWN, pet)?.let { assertTrue(m.cells[it - 1].t > m.cells[k - 1].t) }
                PartyModel.nav(n, k, Dir.UP, pet)?.let { assertTrue(m.cells[it - 1].t < m.cells[k - 1].t) }
                PartyModel.nav(n, k, Dir.RIGHT, pet)?.let { assertEquals(m.rows[k - 1], m.rows[it - 1]); assertTrue(m.cells[it - 1].l > m.cells[k - 1].l) }
                PartyModel.nav(n, k, Dir.LEFT, pet)?.let { assertEquals(m.rows[k - 1], m.rows[it - 1]); assertTrue(m.cells[it - 1].l < m.cells[k - 1].l) }
            }
        }
        assertNull(PartyModel.nav(1, 1, Dir.DOWN))
    }
}
