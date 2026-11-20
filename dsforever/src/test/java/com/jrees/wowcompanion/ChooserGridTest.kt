package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * p58 THE CHOOSER AS A GRID (the user, 2026-11-20: "maybe a grid similar to the AH"): the pure layout - the cells in the add-on's walk
 * order (PadTabCtl CG.COLS = 4 a row), the category list, the header line (the name, its rank words only while the rank badge shows).
 */
class ChooserGridTest {
    @Test fun fourARowInTheAddOnsOrder() {
        assertEquals(4, ChooserGrid.COLS)
        val g = ChooserGrid.grid(7, 440f)
        val t = ChooserGrid.tile(440f)
        assertTrue("big tiles", t >= 90f)
        // row r = k / 4, column k % 4: the walk's Down (+4) is the cell straight under, Right (+1) the next on the row
        for (k in 0 until 7) {
            assertEquals(g[k % 4].left, g[k].left, 0.01f)
            assertEquals(g[(k / 4) * 4].top, g[k].top, 0.01f)
        }
        assertTrue(g[1].left > g[0].right && g[4].top > g[0].bottom)
        assertTrue("all inside the width", g.all { it.right <= 440f })
        assertEquals(2, ChooserGrid.hit(g, g[2].left + 3f, g[2].top + 3f))
        assertEquals(-1, ChooserGrid.hit(g, g[0].right + ChooserGrid.GAP / 2f, g[0].top + 3f))
    }

    @Test fun categoryHeadersAndRows() {
        val b = ChooserGrid.list(listOf(true, false, false, true), 400f)
        assertEquals(ChooserGrid.HEAD_H, b[0].height, 0.01f)
        assertEquals(ChooserGrid.ROW_H, b[1].height, 0.01f)
        assertTrue(b[1].top >= b[0].bottom && b[3].top >= b[2].bottom)
    }

    @Test fun followMovesAsLittleAsItCan() {
        val box = ChooserGrid.Box(0f, 500f, 10f, 600f)
        assertEquals(306f, ChooserGrid.follow(box, 0f, 300f), 0.01f)
        assertEquals(306f, ChooserGrid.follow(box, 306f, 300f), 0.01f)
        assertEquals(494f, ChooserGrid.follow(box, 550f, 300f), 0.01f)
    }

    @Test fun theHeaderLine() {
        val a = ChooserGrid.header("Earth Shock", "Spells", "Rank 2", rankShown = true)
        assertEquals("Earth Shock", a.text); assertFalse(a.dim); assertEquals("Rank 2", a.rank)
        assertNull("the Spell rank badges setting off: no rank words", ChooserGrid.header("Earth Shock", "Spells", "Rank 2", rankShown = false).rank)
        val none = ChooserGrid.header(null, "Macros", null, rankShown = true)
        assertEquals("Macros", none.text); assertTrue("nothing highlighted: the tab's word, dim", none.dim)
    }

    @Test fun tabsAndCategories() {
        assertEquals(listOf("spells", "general", "professions", "extra", "macros"), ChooserTabs.ALL_TABS)
        assertEquals(setOf("spells", "general", "professions", "macros"), ChooserTabs.GRID_TABS)
        assertEquals(listOf("cmd", "ds", "bind", "mount", "item"), ChooserTabs.CATS)
        assertEquals("DS Forever", ChooserTabs.catWord("ds"))
    }
}
