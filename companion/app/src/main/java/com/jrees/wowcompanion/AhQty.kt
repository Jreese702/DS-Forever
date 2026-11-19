package com.jrees.wowcompanion

/**
 * THE AUCTION HOUSE'S QUANTITY BUTTONS (the user's ruling 2026-11-19, test 1502: "Y = minus and X = plus"; on the Thor's Nintendo
 * layout Y is the left face button, so left means minus). The Sell form's Quantity and a commodity's buy quantity draw the minus on
 * the left with Y's glyph and the plus on the right with X's; the add-on (Interact i171) steps the same way: Y (PAD4) less, X (PAD3)
 * more. Glyph names are ACTIONS ("rev_y" = the action Y), so [Glyphs.translate] draws them in the player's chosen button set. A on
 * the quantity row stays the game's Max. Pure.
 */
object AhQty {
    const val LESS_GLYPH = "rev_y"
    const val MORE_GLYPH = "rev_x"

    /** the game's key name of a press -> the step it makes on an AH quantity (-1 / +1), else 0 */
    fun step(key: String): Int = when (key) { "PAD4" -> -1; "PAD3" -> 1; else -> 0 }
}
