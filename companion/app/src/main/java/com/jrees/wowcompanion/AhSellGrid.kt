package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.max

/**
 * i167 (the user 2026-11-19: "Instead of an item list let's show a grid with collapsible bags"): the auction house's Sell grid, laid out
 * exactly as the add-on's cursor walks it (Interact.lua AH.gridRows): per bag a header (its fold), then - unless folded - its sellable
 * items [cols] a row. Boxes are in the view's own coordinates (y from the top of the content, before the scroll). Pure (AhSellGridTest).
 *
 * ISSUE 590 (Interact i173): the bag items the game refuses at the auction house (its own C_AuctionHouse.IsSellItemValid) no longer
 * vanish: the add-on lists them in `no`, each with the game's reason for the slot ("bound" / "quest" / "damaged" / "no"); they are drawn
 * greyed after the bag's sellable items with a short word, a touch says why. They are never a cursor stop (the add-on's rows skip them).
 * A list taller than its panel shows a scroll bar ([bar]).
 */
object AhSellGrid {
    const val COLS = 4
    const val HEAD_H = 46f
    const val GAP = 6f
    const val EDGE = 8f

    /** a box in the view's content coordinates (no android.graphics here: the JVM tests run it) */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** [no] issue 590: the game's reason the item cannot be auctioned (a greyed tile, no cursor stop); null = a sellable item / a header */
    class Cell(val cmd: String, val box: Box, val o: JSONObject, val head: Boolean, val no: String? = null)

    /** the room a scroll bar takes at the panel's right edge, when the grid is taller than its view */
    const val BAR_W = 16f

    /** issue 590: the greyed tile's word (terse) for the game's reason; null = none drawn */
    fun noWord(code: String): String = when (code) {
        "bound" -> "Bound"; "quest" -> "Quest"; "damaged" -> "Damaged"; else -> "Can't sell"
    }

    /** issue 590: what a touch on a greyed tile says */
    fun noWhy(code: String): String = when (code) {
        "bound" -> "Soulbound: the auction house won't take it"
        "quest" -> "A quest item: the auction house won't take it"
        "damaged" -> "Damaged: repair it to sell it"
        else -> "The auction house won't take this"
    }

    /** a bag header's count: its sellable items, and how many the game refuses */
    fun headCount(g: JSONObject): String = g.optInt("n", 0).let { n -> g.optInt("x", 0).let { x -> if (x > 0) "$n · $x can't sell" else "$n" } }

    /** the scroll bar's shown part (0..1, from .. to) for a grid [content] tall in a view [viewH] tall at [scroll]; null = it all fits */
    fun bar(scroll: Float, content: Float, viewH: Float): Pair<Float, Float>? =
        if (content <= viewH + 2f || content <= 0f) null else (scroll / content).coerceIn(0f, 1f) to ((scroll + viewH) / content).coerceIn(0f, 1f)

    fun cmdOf(b: JSONObject) = "ahsitem ${b.optInt("bag")} ${b.optInt("s")} ${b.optInt("id")}"

    fun tile(cols: Int, width: Float): Float = (width - 2 * EDGE - (cols - 1) * GAP) / cols

    fun layout(bags: List<JSONObject>, groups: List<JSONObject>, cols0: Int, width: Float, no: List<JSONObject> = emptyList()): List<Cell> {
        val cols = cols0.coerceIn(1, 8)
        val t = tile(cols, width)
        val out = ArrayList<Cell>()
        var y = 4f
        for (g in groups) {
            val bag = g.optInt("bag")
            out.add(Cell("ahsfold $bag", Box(EDGE, y, width - EDGE, y + HEAD_H), g, true))
            y += HEAD_H + 4f
            if (g.optBoolean("shut")) continue
            val its = bags.filter { it.optInt("bag") == bag }
            val refused = no.filter { it.optInt("bag") == bag }      // issue 590: greyed after the sellable ones, in the same grid
            for ((k, b) in (its + refused).withIndex()) {
                val x = EDGE + (k % cols) * (t + GAP)
                val ty = y + (k / cols) * (t + GAP)
                val why = if (k >= its.size) b.optString("no", "no").ifEmpty { "no" } else null
                out.add(Cell(if (why != null) "ahsno ${cmdOf(b).removePrefix("ahsitem ")}" else cmdOf(b), Box(x, ty, x + t, ty + t), b, false, why))
            }
            y += ((its.size + refused.size + cols - 1) / cols) * (t + GAP) + 6f
        }
        return out
    }

    /** i168: Start is the Sell form's Post (never the pause wheel) while the add-on holds the pad, the Sell tab is open and an item is in it */
    fun ownsStart(padHeld: Boolean, tab: String, hasItem: Boolean): Boolean = padHeld && tab == "sell" && hasItem

    fun height(cells: List<Cell>): Float = (cells.maxOfOrNull { it.box.bottom } ?: 0f) + 8f

    /** the scroll that keeps [box] in a view [viewH] tall, moved as little as possible from [scroll] (never back to the top) */
    fun follow(box: Box, scroll: Float, viewH: Float): Float = when {
        box.top - 4f < scroll -> max(0f, box.top - 4f)
        box.bottom + 4f > scroll + viewH -> box.bottom + 4f - viewH
        else -> scroll
    }
}
