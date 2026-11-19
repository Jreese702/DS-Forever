package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.max

/**
 * i167 (the user 2026-11-19: "Instead of an item list let's show a grid with collapsible bags"): the auction house's Sell grid, laid out
 * exactly as the add-on's cursor walks it (Interact.lua AH.gridRows): per bag a header (its fold), then - unless folded - its sellable
 * items [cols] a row. Boxes are in the view's own coordinates (y from the top of the content, before the scroll). Pure (AhSellGridTest).
 */
object AhSellGrid {
    const val COLS = 4
    const val HEAD_H = 46f
    const val GAP = 6f
    const val EDGE = 8f

    /** a box in the view's content coordinates (no android.graphics here: the JVM tests run it) */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    class Cell(val cmd: String, val box: Box, val o: JSONObject, val head: Boolean)

    fun cmdOf(b: JSONObject) = "ahsitem ${b.optInt("bag")} ${b.optInt("s")} ${b.optInt("id")}"

    fun tile(cols: Int, width: Float): Float = (width - 2 * EDGE - (cols - 1) * GAP) / cols

    fun layout(bags: List<JSONObject>, groups: List<JSONObject>, cols0: Int, width: Float): List<Cell> {
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
            for ((k, b) in its.withIndex()) {
                val x = EDGE + (k % cols) * (t + GAP)
                val ty = y + (k / cols) * (t + GAP)
                out.add(Cell(cmdOf(b), Box(x, ty, x + t, ty + t), b, false))
            }
            y += ((its.size + cols - 1) / cols) * (t + GAP) + 6f
        }
        return out
    }

    fun height(cells: List<Cell>): Float = (cells.maxOfOrNull { it.box.bottom } ?: 0f) + 8f

    /** the scroll that keeps [box] in a view [viewH] tall, moved as little as possible from [scroll] (never back to the top) */
    fun follow(box: Box, scroll: Float, viewH: Float): Float = when {
        box.top - 4f < scroll -> max(0f, box.top - 4f)
        box.bottom + 4f > scroll + viewH -> box.bottom + 4f - viewH
        else -> scroll
    }
}
