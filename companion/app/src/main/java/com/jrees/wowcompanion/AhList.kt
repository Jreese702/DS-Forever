package com.jrees.wowcompanion

/**
 * AN ITEM'S LISTINGS AT THE AUCTION HOUSE, PRESSED BY A FINGER (Interact i174; the user, 2026-11-26: "When buying stuff on the AH for some
 * reason the buttons are just staying gray", then "I was in dual screen, its just how its been working. It still works but visually its
 * gray"). The listings had their own ibuy cells beside the browse results' (up to 60 on a 40-column row whose freed columns rest 30 s),
 * so an item opened from a long list had none: Buy Now and the confirm's Buy Now drew grey while the controller's A (the add-on's own
 * press) bought. Now the add-on asks ONE iact cell for every listing, `act.list` ("ahlist"), and a press names its row through the
 * iqty count cell just before (column k-1 = row k, as the favourite star's). A listing that still carries its own cell (an older
 * add-on) is pressed on it, as before. Pure.
 */
object AhList {
    const val CMD = "ahlist"
    /** the iqty row's counts (Interact QTY_MAX): the rows a number can name */
    const val MOST = 40

    /** the cell a listing is pressed through: its own, else the one listing cell (row 1..[MOST]); -1 = none */
    fun cell(rowSlot: Int, listCol: Int, row: Int): Int = when {
        rowSlot >= 0 -> rowSlot
        listCol >= 0 && row in 1..MOST -> listCol
        else -> -1
    }

    /** one press: the command the add-on runs, its cell, and the row number sent first (0 = none) */
    data class Press(val cmd: String, val slot: Int, val pick: Int)

    /** a press on listing [cmd] ("ahrow <i>"): on its own cell [rowSlot], else on [listCol] with its row number; null = no cell */
    fun press(cmd: String, rowSlot: Int, listCol: Int): Press? {
        if (!cmd.startsWith("ahrow ")) return null
        if (rowSlot >= 0) return Press(cmd, rowSlot, 0)
        val i = cmd.substringAfter(' ').trim().toIntOrNull() ?: return null
        if (listCol < 0 || i !in 1..MOST) return null
        return Press(CMD, listCol, i)
    }

    /** whether the command waiting for the game's answer is a press on listing [cmd] */
    fun waiting(pending: String?, cmd: String?): Boolean =
        pending != null && cmd != null && (pending == cmd || (pending == CMD && cmd.startsWith("ahrow ")))
}
