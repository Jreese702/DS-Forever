package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** i167: the auction house's Sell grid (AhSellGrid) - the add-on's rows (Interact.lua AH.gridRows), folds, a scroll that never jumps */
class AhSellGridTest {
    private fun item(bag: Int, s: Int, id: Int) = JSONObject().put("bag", bag).put("s", s).put("id", id)
    private fun group(bag: Int, n: Int, shut: Boolean = false) = JSONObject().put("bag", bag).put("n", n).put("shut", shut)
    private val bags = listOf(item(0, 1, 10), item(0, 2, 11), item(0, 3, 12), item(0, 4, 13), item(0, 5, 14), item(1, 1, 20))

    @Test fun headersThenItemsFourARow() {
        val cells = AhSellGrid.layout(bags, listOf(group(0, 5), group(1, 1)), 4, 330f)
        assertEquals(listOf("ahsfold 0", "ahsitem 0 1 10", "ahsitem 0 2 11", "ahsitem 0 3 12", "ahsitem 0 4 13", "ahsitem 0 5 14",
            "ahsfold 1", "ahsitem 1 1 20"), cells.map { it.cmd })
        assertTrue(cells[0].head && !cells[1].head)
        assertEquals("the fifth item starts the second row", cells[1].box.left, cells[5].box.left, 0.01f)
        assertTrue(cells[5].box.top > cells[1].box.top)
        assertEquals("the four in a row fit the panel", 330f - AhSellGrid.EDGE, cells[4].box.right, 0.5f)
        assertTrue("the next bag's header below the last row", cells[6].box.top > cells[5].box.bottom)
    }

    @Test fun aFoldedBagShowsItsHeaderOnly() {
        val cells = AhSellGrid.layout(bags, listOf(group(0, 5, shut = true), group(1, 1)), 4, 330f)
        assertEquals(listOf("ahsfold 0", "ahsfold 1", "ahsitem 1 1 20"), cells.map { it.cmd })
        assertTrue(AhSellGrid.height(cells) < AhSellGrid.height(AhSellGrid.layout(bags, listOf(group(0, 5), group(1, 1)), 4, 330f)))
    }

    @Test fun theScrollFollowsTheCursorAndOtherwiseStays() {
        val b = AhSellGrid.Box(8f, 500f, 80f, 572f)
        assertEquals("in view: unchanged (a post never sends it to the top)", 300f, AhSellGrid.follow(b, 300f, 400f), 0.01f)
        assertEquals("below: just enough", 576f - 200f, AhSellGrid.follow(b, 0f, 200f), 0.01f)
        assertEquals("above: just enough", 496f, AhSellGrid.follow(b, 700f, 200f), 0.01f)
    }

    @Test fun startIsPostOnlyWithAnItemInTheSellForm() {
        assertTrue(AhSellGrid.ownsStart(padHeld = true, tab = "sell", hasItem = true))
        assertFalse("no item: Start is the pause wheel as everywhere", AhSellGrid.ownsStart(true, "sell", false))
        assertFalse("the Buy tab", AhSellGrid.ownsStart(true, "browse", true))
        assertFalse("the pad not held (the game's)", AhSellGrid.ownsStart(false, "sell", true))
    }

    @Test fun theKeyboardsBDoesNotReopenItsField() {
        assertTrue("a document still naming the field B closed a moment ago", InteractFieldKbd.justClosed("ahspg", "ahspg", 200L))
        assertFalse("another field opens", InteractFieldKbd.justClosed("ahsps", "ahspg", 200L))
        assertFalse("after the guard", InteractFieldKbd.justClosed("ahspg", "ahspg", InteractFieldKbd.REOPEN_GUARD_MS))
        assertFalse("nothing closed", InteractFieldKbd.justClosed("ahspg", null, 0L))
    }

    @Test fun theRedSearchCommitsTheAuctionHousesBoxes() {
        assertTrue(InteractFieldKbd.searchCommits("ahtext"))
        assertTrue(InteractFieldKbd.searchCommits("ahmin"))
        assertFalse("a price box is not the search's", InteractFieldKbd.searchCommits("ahspg"))
        assertFalse(InteractFieldKbd.searchCommits(null))
    }
}
