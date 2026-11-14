package com.jrees.wowcompanion

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #233 ("I cannot ... see its sell value"): the game's sell price line comes from the add-on (Interact i160) as
 * {"t": "Sell Price:", "m": copper}; the shared tooltip (TipStack) hands the copper to the kit's line, which draws the coins.
 */
class TipSellPriceTest {
    @Test fun theSellPriceLineCarriesItsCopper() {
        val lines = TipStack.lines(JSONArray("""[{"t":"Linen Cloth"},{"t":"Sell Price:","m":12345},{"t":"Binds when picked up"}]"""), true)
        assertEquals(2, lines.size)
        assertEquals("Sell Price:", lines[0].t)
        assertEquals(12345L, lines[0].money)
        assertEquals(-1L, lines[1].money)                 // every other line: no coins
    }

    @Test fun anOlderAddOnsLinesAreUnchanged() {
        val lines = TipStack.lines(JSONArray("""[{"t":"Item"},{"t":"Durability 20 / 20","r":"Plate"}]"""), false)
        assertEquals(listOf(-1L, -1L), lines.map { it.money })
        assertEquals("Plate", lines[1].r)
    }
}
