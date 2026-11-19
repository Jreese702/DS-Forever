package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 1503 AH fixes (Interact i171): the quantity is Y less / X more (the user's ruling), and the Sell form's cells are tapped on
 * isell, where the add-on now asks them (iact ran out: the confirm's Post and Reset drew grey).
 */
class AhTabsQtyTest {
    @Test fun yIsMinusXIsPlus() {
        assertEquals("rev_y", AhQty.LESS_GLYPH)
        assertEquals("rev_x", AhQty.MORE_GLYPH)
        assertEquals(-1, AhQty.step("PAD4"))
        assertEquals(1, AhQty.step("PAD3"))
        assertEquals(0, AhQty.step("PAD1"))
        // the player's button set draws them: Y's action in PlayStation's set is the triangle, X's the square
        assertEquals("shp_triangle", Glyphs.translate(AhQty.LESS_GLYPH, CastSettings.ButtonLayout.PLAYSTATION))
        assertEquals("ltr_x", Glyphs.translate(AhQty.MORE_GLYPH, CastSettings.ButtonLayout.XBOX))
    }

    @Test fun theSellFormIsTappedOnIsell() {
        for (c in listOf("ahsqty less", "ahsqty more", "ahsqty max", "ahsedit pg", "ahsdur 2", "ahsbo", "ahspost", "ahsitem 0 1 2589"))
            assertEquals(c, "isell", InteractRows.of(c))
        // the rest of the AH where it was
        assertEquals("iact", InteractRows.of("ahreset"))
        assertEquals("iact", InteractRows.of("ahtab sell"))
        assertEquals("iact", InteractRows.of("ahqty more"))
        assertEquals("ipick", InteractRows.of("ahcat 1"))
        assertEquals("ibuy", InteractRows.of("ahres 3"))
    }
}
