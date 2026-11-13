package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * bags-onerow1 (the user, 2026-11-13: "Bags share one row"): the pad cells of ONE tap on a bag slot's drag cell. The add-on (Bags.lua)
 * has ONE shared plain drag row [ROW] (column = slot - 1) for every bag; the bag it means is NAMED by the tap just before it, on Bags'
 * own row [NAME_ROW] column [BASE] + bag (that row's column 0 stays the game's sort). A name is used by one drag tap only, and a drag
 * tap with no fresh name is refused by the add-on, so the two always go together, in ONE batch (all or nothing).
 * An older add-on (one row "bmv<bag>" per bag, a one-cell bagsort) is still reached the old way. Pure: no Android, no taps.
 */
object BagsMoveRoute {
    const val ROW = "bmv"
    const val NAME_ROW = "bagsort"
    const val BASE = 10
    /** ms between the taps of a batch that may press the SAME cell twice (a drag within one bag names it twice): TapPad's REPEAT is 0.25 s.
     *  bags-onerow2 (review): 200, not 150. The same cell comes two taps apart (2 x (GAP_MS + the launcher's 20 ms tap rest) = 440 ms), and
     *  TapPad stamps a press when a frame handles it, so a slow frame (TapSpacing.FRAME_MS, 150 ms in a fight) eats into that: 150 left
     *  340 - 150 = 190 ms < 250 and the second name was dropped; 200 leaves 290 ms. Across batches TapSpacing spaces these rows too. */
    const val GAP_MS = 200L

    /**
     * The cells that press bag [bag] slot [slot] (1-based) on [pad] (the add-on's pad document: rows + sizes), or null when the pad
     * cannot reach it (then nothing is tapped). Never a guess: the shared route only when its row and the naming column are published.
     */
    fun cells(pad: JSONObject?, bag: Int, slot: Int): List<Pair<String, Int>>? {
        val rows = pad?.optJSONObject("rows") ?: return null
        if (bag < 0 || slot < 1 || slot > 40) return null
        val col = BASE + bag
        val nameSize = pad.optJSONObject("sizes")?.optInt(NAME_ROW, 0) ?: 0
        if (rows.has(ROW) && rows.has(NAME_ROW) && col < nameSize) return listOf(NAME_ROW to col, ROW to slot - 1)
        if (rows.has("$ROW$bag")) return listOf("$ROW$bag" to slot - 1)       // an older add-on: one row per bag
        return null
    }

    /** whether bag [bag]'s slots can be pressed on [pad] at all */
    fun reaches(pad: JSONObject?, bag: Int): Boolean = cells(pad, bag, 1) != null

    /** a drag: the pick (from) then the drop (to), one batch; null = nothing to tap */
    fun drag(pad: JSONObject?, fromBag: Int, fromSlot: Int, toBag: Int, toSlot: Int): List<Pair<String, Int>>? {
        val a = cells(pad, fromBag, fromSlot) ?: return null
        val b = cells(pad, toBag, toSlot) ?: return null
        return a + b
    }
}
