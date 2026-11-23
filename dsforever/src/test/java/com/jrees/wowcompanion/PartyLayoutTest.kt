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

    /** the user's ruling C (11-22): a Party-mode Top fight's rows fill the freed strip - 88..192 px, the module laid with them */
    @Test fun theFilledRows() {
        // the Diamonds: a Party fight's well 234..538 (3 rows) / ..442 (2) / ..346 (1); the band away: the top gap at 8
        assertEquals((530f - 24f - 16f) / 3f, PartyLayout.fillH(3, 8f, 538f), 0.001f)
        assertEquals(PartyLayout.FILL_MAX_H, PartyLayout.fillH(2, 8f, 442f), 0f)
        assertEquals(PartyLayout.FILL_MAX_H, PartyLayout.fillH(1, 8f, 346f), 0f)
        assertEquals("never under a Party fight's rows", PartyLayout.FIGHT_H, PartyLayout.fillH(3, 300f, 400f), 0f)
        assertEquals(192f, PartyLayout.FILL_MAX_H, 0f)
        // rowsFit gives each mode's own rows back exactly at its own well
        for ((n, pet) in shapes) for (mode in listOf(Mode.REST, Mode.FIGHT_ON, Mode.FIGHT_OFF)) {
            val m = PartyLayout.module(mode, n, pet)
            val rows = PartyModel.rows(n, pet).size
            assertEquals("$mode $n $pet", PartyLayout.plateH(mode), PartyLayout.rowsFit(rows, m.well.h), 0.001f)
        }
        // the module with the filled rows: cells that tall, the well to the foot, the chat under it
        val h = PartyLayout.fillH(3, 8f, 538f)
        val m = PartyLayout.module(Mode.FIGHT_TOP, 3, top = 8f, rowH = h)
        assertTrue(m.cells.all { kotlin.math.abs(it.h - h) < 0.001f })
        assertEquals(538f, m.well.b, 0.01f); assertEquals(546f, m.chatTop, 0.01f)
        assertEquals("the tab takes its tall layout", false, PartyLayout.tabParts(m.tag).bOnArrow)
        // a FIGHT_TOP module without rows given: a Party fight's
        assertEquals(PartyLayout.FIGHT_H, PartyLayout.plateH(Mode.FIGHT_TOP), 0f)
        // the plate's parts grow with the row: the icon half the plate (48..72), the name 32 from 120 px
        val tall = b(68, 20, 603, 183)
        assertEquals(72f, PartyLayout.icon(tall, Mode.FIGHT_TOP).w, 0.01f)
        assertEquals(48f, PartyLayout.icon(b(68, 20, 603, 108), Mode.FIGHT_TOP).w, 0.01f)
        assertEquals(60f, PartyLayout.icon(b(68, 20, 603, 140), Mode.FIGHT_TOP).w, 0.01f)
        assertEquals(32f, PartyLayout.nameSize(Mode.FIGHT_TOP, 163f), 0f)
        assertEquals(28f, PartyLayout.nameSize(Mode.FIGHT_TOP, 88f), 0f)
        assertEquals("the 11-22 review: no step at 120 px - 28 at 88 up to 32 at 120", 29.5f, PartyLayout.nameSize(Mode.FIGHT_TOP, 100f), 0.001f)
        assertEquals(32f, PartyLayout.nameSize(Mode.FIGHT_TOP, 120f), 0f)
        assertEquals("the other modes unchanged", 28f, PartyLayout.nameSize(Mode.FIGHT_ON, 163f), 0f)
        assertEquals(24f, PartyLayout.nameSize(Mode.REST, 163f), 0f)
        assertEquals(48f, PartyLayout.icon(tall, Mode.FIGHT_ON).w, 0f)
    }

    /**
     * the 11-22 review (rulings C / D, "no jump at either end"): while Home's Top-screen slide moves the module ([PartyModuleView.stretch])
     * the plates' icon and name follow the ROW's height on one continuous rule - at the fight's end the mode is the rest's at once while
     * the rows are still full height, and on the way up FIGHT_TOP starts from the rest's 64 px rows. Every frame's step is small, and
     * each end is what the settled mode draws
     */
    @Test fun aSlidesRowsGrowAndShrinkTheirPartsWithNoStep() {
        // which plates size from their row: a Top fight's filled rows always; a slide's (REST or FIGHT_TOP); never the other fights'
        assertTrue(PartyLayout.filledParts(Mode.FIGHT_TOP, false))
        assertTrue(PartyLayout.filledParts(Mode.REST, true)); assertTrue(PartyLayout.filledParts(Mode.FIGHT_TOP, true))
        assertFalse(PartyLayout.filledParts(Mode.REST, false))
        assertFalse(PartyLayout.filledParts(Mode.FIGHT_ON, true)); assertFalse(PartyLayout.filledParts(Mode.FIGHT_OFF, true))
        fun plate(h: Float) = Box(68f, 20f, 603f, 20f + h)
        for (mode in listOf(Mode.REST, Mode.FIGHT_TOP)) {
            // the way down / up: rows from 192 to the rest's 64 in 1 px frames (a 240 ms slide moves a row far more than 1 px a frame;
            // the bound is per px of height)
            var h = PartyLayout.FILL_MAX_H
            var lastI = PartyLayout.icon(plate(h), mode, filled = true).w
            var lastN = PartyLayout.nameSize(mode, h, filled = true)
            while (h > PartyLayout.REST_H) {
                h -= 1f
                val i = PartyLayout.icon(plate(h), mode, filled = true).w
                val n = PartyLayout.nameSize(mode, h, filled = true)
                assertTrue("$mode icon at $h: $lastI -> $i", i <= lastI + 1e-4f && lastI - i <= 0.51f)
                assertTrue("$mode name at $h: $lastN -> $n", n <= lastN + 1e-4f && lastN - n <= 0.21f)
                lastI = i; lastN = n
            }
        }
        // the ends: the rest's 64 px row = the rest's own 40 / 24; a Party fight's 88 = 48 / 28; a 163 px filled row = 72 / 32
        assertEquals(PartyLayout.icon(plate(64f), Mode.REST).w, PartyLayout.icon(plate(64f), Mode.REST, filled = true).w, 0f)
        assertEquals(PartyLayout.nameSize(Mode.REST), PartyLayout.nameSize(Mode.REST, 64f, filled = true), 0f)
        assertEquals(48f, PartyLayout.icon(plate(88f), Mode.REST, filled = true).w, 0.001f)
        assertEquals(28f, PartyLayout.nameSize(Mode.REST, 88f, filled = true), 0.001f)
        assertEquals(72f, PartyLayout.icon(plate(163f), Mode.REST, filled = true).w, 0.001f)
        assertEquals(32f, PartyLayout.nameSize(Mode.REST, 163f, filled = true), 0f)
        // the fight's end's first frame: REST at once, the rows still 163 px - the same parts as the Top fight drew the frame before
        assertEquals(PartyLayout.icon(plate(163f), Mode.FIGHT_TOP).w, PartyLayout.icon(plate(163f), Mode.REST, filled = true).w, 0f)
        assertEquals(PartyLayout.nameSize(Mode.FIGHT_TOP, 163f), PartyLayout.nameSize(Mode.REST, 163f, filled = true), 0f)
        assertEquals(PartyLayout.nameX(plate(163f), Mode.FIGHT_TOP), PartyLayout.nameX(plate(163f), Mode.REST, filled = true), 0f)
        // the way up's first frame: FIGHT_TOP at once, the rows still the rest's 64 px - the rest's parts
        assertEquals(40f, PartyLayout.icon(plate(64f), Mode.FIGHT_TOP, filled = true).w, 0f)
        assertEquals(24f, PartyLayout.nameSize(Mode.FIGHT_TOP, 64f, filled = true), 0f)
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
