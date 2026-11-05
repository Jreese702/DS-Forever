package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyLayout.Box
import com.jrees.wowcompanion.PartyLayout.Mode
import com.jrees.wowcompanion.PartyModel.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** every number of PARTY_BUILD.md 4.2 for n = 1..5 x {rest, fight on, fight off}; the tag's two places; NAV = 3.4 */
class PartyLayoutTest {
    private fun b(l: Int, t: Int, r: Int, bb: Int) = Box(l.toFloat(), t.toFloat(), r.toFloat(), bb.toFloat())
    // the cells' own geometry, Party mode on (no side tab); theTag checks the tab and the shift it makes
    private fun mod(mode: Mode, n: Int) = PartyLayout.module(mode, n, tagShown = false)

    @Test fun atRest() {
        mod(Mode.REST, 1).let { assertEquals(listOf(b(20, 228, 603, 330)), it.cells); assertEquals(b(8, 216, 615, 342), it.well) }
        mod(Mode.REST, 2).let {
            assertEquals(listOf(b(20, 228, 603, 330), b(20, 341, 603, 443)), it.cells); assertEquals(b(8, 216, 615, 455), it.well)
        }
        mod(Mode.REST, 3).let {
            assertEquals(listOf(b(20, 228, 603, 356), b(20, 367, 305, 467), b(318, 367, 603, 467)), it.cells)
            assertEquals(b(8, 216, 615, 479), it.well); assertEquals(487f, it.chatTop)
        }
        mod(Mode.REST, 4).let {
            assertEquals(listOf(b(20, 228, 603, 356), b(20, 367, 205, 467), b(218, 367, 403, 467), b(416, 367, 601, 467)), it.cells)
            assertEquals(b(8, 216, 615, 479), it.well)
        }
        mod(Mode.REST, 5).let {
            assertEquals(listOf(b(20, 228, 603, 356), b(20, 367, 305, 467), b(318, 367, 603, 467), b(20, 478, 305, 578), b(318, 478, 603, 578)),
                it.cells)
            assertEquals(b(8, 216, 615, 590), it.well); assertEquals(598f, it.chatTop)
        }
    }

    @Test fun inAFightPartyModeOn() {
        val wellH = mapOf(1 to 174f, 2 to 335f, 3 to 335f, 4 to 335f, 5 to 496f)
        for (n in 1..5) {
            val m = mod(Mode.FIGHT_ON, n)
            assertEquals("n $n", wellH[n], m.well.h)
            assertTrue(m.cells.all { it.h == 150f })
            assertEquals(b(20, 228, 603, 378), m.cells[0])
            for (k in 1 until m.cells.size) assertEquals(if (k <= 2 || n < 5) 389f else 550f, m.cells[k].t)
        }
        mod(Mode.FIGHT_ON, 5).let { assertEquals(b(8, 216, 615, 712), it.well); assertEquals(720f, it.chatTop) }
        mod(Mode.FIGHT_ON, 3).let { assertEquals(listOf(285f, 285f), it.cells.drop(1).map { c -> c.w }) }
    }

    @Test fun inAFightPartyModeOff() {
        mod(Mode.FIGHT_OFF, 1).let { assertEquals(listOf(b(20, 410, 603, 512)), it.cells); assertEquals(b(8, 398, 615, 524), it.well) }
        mod(Mode.FIGHT_OFF, 2).let { assertEquals(listOf(b(20, 410, 603, 510), b(20, 521, 603, 621)), it.cells) }
        mod(Mode.FIGHT_OFF, 3).let { assertEquals(listOf(285f, 285f), it.cells.drop(1).map { c -> c.w }) }
        mod(Mode.FIGHT_OFF, 4).let { assertEquals(listOf(b(20, 521, 205, 621), b(218, 521, 403, 621), b(416, 521, 601, 621)), it.cells.drop(1)) }
        mod(Mode.FIGHT_OFF, 5).let {
            assertEquals(listOf(b(20, 410, 603, 510), b(20, 521, 156, 621), b(169, 521, 305, 621), b(318, 521, 454, 621), b(467, 521, 603, 621)),
                it.cells)
            assertEquals(b(8, 398, 615, 633), it.well); assertEquals(641f, it.chatTop)
        }
    }

    @Test fun theTag() {
        // 2026-11-04: the side tab down the well's left inside edge, the cells after it; Party mode on: no tab, the cells as before
        val withTab = PartyLayout.module(Mode.REST, 3)
        assertEquals(b(20, 228, 56, 467), withTab.tag)
        assertEquals(64f, withTab.cells[0].l); assertEquals(603f, withTab.cells[0].r)
        assertEquals(b(8, 216, 615, 479), withTab.well)
        assertNull(mod(Mode.REST, 3).tag)
        val p = PartyLayout.tagParts(withTab.tag!!)
        assertEquals(38f, p.glyphCx); assertEquals(32f, p.glyph)
        assertTrue(PartyLayout.hitTag(withTab, 30f, 300f))
        assertTrue(!PartyLayout.hitTag(withTab, 300f, 300f))
    }

    @Test fun cellParts() {
        val c = b(20, 228, 603, 330)
        assertEquals(b(22, 230, 602, 317), PartyLayout.hpBar(c))
        assertEquals(b(22, 323, 602, 329), PartyLayout.power(c))
        assertEquals(b(26, 236, 72, 282), PartyLayout.role(c))
        assertEquals(b(30, 242, 64, 276), PartyLayout.skull(c))
        val half = PartyLayout.hpFill(c, 50, full = false)
        assertEquals(312f, half.box!!.r); assertNull(half.fadeFrom)          // a hard line, never a fade
        assertNull(PartyLayout.hpFill(c, 100, full = true).fadeFrom)
        assertNull(PartyLayout.hpFill(c, 0, full = false).box)
        assertEquals(0x0000FF, PartyLayout.powerRgb("MANA")); assertEquals(0x808080, PartyLayout.powerRgb("RUNIC_POWER"))
        assertEquals("72%", PartyLayout.pctText(72, false)); assertEquals("Offline", PartyLayout.pctText(72, true))
        assertEquals(2, PartyLayout.hit(mod(Mode.REST, 3), 400f, 400f)); assertEquals(-1, PartyLayout.hit(mod(Mode.REST, 3), 700f, 400f))
    }

    /** a stand-in for the font: 0.5 px per px of size per letter */
    private val measure: (String, Float) -> Float = { s, size -> s.length * size * 0.5f }

    @Test fun wordsShrinkThenTwoLinesNeverCut() {
        val row = b(20, 228, 603, 330)
        PartyLayout.words(row, "Korvash", "~100%", dead = false, big = false, measure = measure).let {
            assertEquals(listOf("Korvash"), it.lines); assertEquals(30f, it.size); assertEquals(70f, it.x); assertEquals(589f, it.pctRight)
            assertEquals(259f, it.pctY)
        }
        PartyLayout.words(b(20, 228, 603, 356), "Korvash", "~100%", false, big = true, measure = measure).let {
            assertEquals(34f, it.size); assertEquals(32f, it.pctSize)
        }
        // a small cell (185 wide): a long name shrinks first, then takes two lines when it would go under 22 px
        val small = b(20, 367, 205, 467)
        PartyLayout.words(small, "Brannoc", "~72%", false, false, measure).let { assertEquals(1, it.lines.size); assertEquals(28f, it.size)
            assertEquals(32f, it.x); assertEquals(435f, it.pctY); assertEquals(193f, it.pctRight) }
        PartyLayout.words(small, "Seraphine Duskwhisperer", "~91%", false, false, measure).let {
            assertEquals(listOf("Seraphine", "Duskwhisperer"), it.lines); assertTrue(it.size <= 26f)
            assertTrue(measure("Duskwhisperer", it.size) <= 205f - 12f - 32f - 2f)
            assertEquals(listOf(389f, 415f), it.ys); assertEquals(441f, it.pctY); assertEquals(26f, it.pctSize)
        }
        // dead: no %, the name from x 50
        PartyLayout.words(small, "Tamsin", "~0%", dead = true, big = false, measure = measure).let { assertNull(it.pct); assertEquals(70f, it.x) }
    }

    @Test fun navIsTheTable() {
        assertEquals(2, PartyModel.nav(5, 1, Dir.DOWN)); assertEquals(4, PartyModel.nav(5, 2, Dir.DOWN)); assertEquals(3, PartyModel.nav(5, 5, Dir.UP))
        assertEquals(1, PartyModel.nav(4, 4, Dir.UP)); assertNull(PartyModel.nav(2, 1, Dir.RIGHT)); assertNull(PartyModel.nav(1, 1, Dir.DOWN))
        // the layout's rows are NAV's rows (rest + fight on): the box walks what is drawn
        for (n in 1..5) {
            val cells = mod(Mode.REST, n).cells
            for ((r, row) in PartyModel.rows(n).withIndex()) for (i in row) {
                val top = cells[i - 1].t
                assertTrue(row.all { cells[it - 1].t == top })
                if (r > 0) assertTrue(cells[PartyModel.rows(n)[r - 1][0] - 1].t < top)
            }
        }
    }
}
