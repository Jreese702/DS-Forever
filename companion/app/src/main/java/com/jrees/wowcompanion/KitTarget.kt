package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * "CHOOSE AN ITEM" (Bags.lua KIT: armor kits, stones, enchants, Feed Pet, Disenchant): which secure tap-pad cell answers a row of the
 * pick's list, or the add-on's `use` request. Pure, so the route is tested off-device.
 *
 * (issues #126 / #39, the player 2026-11-11: "disenchantment of items not possible. Game tries to equip instead"; 2026-11-11) a BAG GEAR
 * row must never go to the bag's own cell: that cell is the game's secure "item" action, which EQUIPS an equippable item that is not
 * worn instead of using it, so the pick's target was equipped. Bags.lua gives each bag gear row its own cell, binv 20..39 (`cell`, a
 * "/use <bag> <slot>": the item-target click). Worn gear stays binv slot - 1; a bag row without a cell (a consumable for Feed Pet) the
 * bag's own cell, which for a consumable is its right-click.
 */
object KitTarget {
    const val ROW = "binv"
    const val STOP_COL = 19
    const val CELL0 = 20

    /** a row of the pick's list -> (pad row, column); null = nothing to tap */
    fun ofRow(e: JSONObject): Pair<String, Int>? {
        if (e.optString("src") == "inv") return e.optInt("slot", 0).takeIf { it >= 1 }?.let { ROW to it - 1 }
        val cell = e.optInt("cell", -1)
        if (cell >= CELL0) return ROW to cell
        val bag = e.optInt("bag", -1); val s = e.optInt("s", 0)
        return if (bag >= 0 && s >= 1) "bag$bag" to s - 1 else null
    }

    /** the add-on's `use` request (bags.ctl.use) -> (pad row, column); null = not a pick's (the plain bag use is the caller's) */
    fun ofUse(u: JSONObject): Pair<String, Int>? {
        val inv = u.optInt("inv", 0)
        if (inv >= 1) return ROW to inv - 1
        if (u.optBoolean("stop")) return ROW to STOP_COL
        val cell = u.optInt("cell", -1)
        if (cell >= CELL0) return ROW to cell
        return null
    }

    /**
     * bags-kit3 (the user's pick, 2026-11-13: "the Character room taps a slot only if it is a matching row"): may the worn [slot] take
     * the pick the add-on's `itarget` document [doc] says started? Its `wornOk` lists the worn slots of the Bags room's target list
     * (",5,10,"); a document without it (an older add-on) leaves the game to decide, as before.
     */
    fun slotFits(doc: JSONObject?, slot: Int): Boolean {
        if (doc == null || !doc.has("wornOk")) return true
        return doc.optString("wornOk").contains(",$slot,")
    }
}
