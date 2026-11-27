package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE'S ONE TYPE SCALE AND SPACING GRID (the tidy pass; the user on the 1537 build, 2026-11-26: "In general the single
 * screen just looks like a mess like things don't fit right with like different font sizes and shit"). Every piece of single mode over
 * the game - the docks (chat, party list, quest card, map), the waiting row, the hint plate, the corner buttons, the room row and the DS
 * menu's stage - takes its text sizes, corner radius and edges from here, so text of one role is one size and the docks share their edges.
 *
 * DERIVED FROM THE BOTTOM SCREEN'S KIT (UiKit), scaled ONCE for the top screen: the kit's panel title (UiKit.titlePaint 30), its body
 * text (the list rows and menus at 26; Home's chat pane 27 rounds to it), its small print (the notes, 22), its module corner (MODULE_R
 * 12) and padding (MODULE_PAD 14). The bottom panel is 1080 lines tall as the top screen is, so the one scale is [k] = the top screen's
 * height / 1080 (1 on the Thor) - never a second scale per piece.
 *
 * THE GRID (1080-line px): every piece [EDGE] from the screen's edges, [GAP] between pieces, the bottom row's foot on one line
 * ([FOOT]: the room row and both corner buttons end there).
 *
 * Pure (no android.*): SingleTypeTest.
 */
object SingleType {
    /** a panel's / a card's title: UiKit.titlePaint's 30 */
    const val TITLE = 30f
    /** body text (chat lines, party names, quest objectives, menu rows, the hint plate): the kit's 26 */
    const val BODY = 26f
    /** small print (levels, tags, a second line): the kit's notes' 22 */
    const val SMALL = 22f
    /** the hint plate's floor when its words are long (two lines) */
    const val FLOOR = 18f

    /** every plate's corner (UiKit.MODULE_R) and inner padding (UiKit.MODULE_PAD) */
    const val R = 12f
    const val PAD = 14f

    /** the grid: from the screen's edges, between pieces, the bottom row's foot over the bottom edge (4: the row's top stays GAP under the Wings at rest) */
    const val EDGE = 16
    const val GAP = 8
    const val FOOT = 4

    /** Home's quest card's own sizes at scale 1 (UiKit.questCard: the title 27, the objectives 23) */
    const val CARD_TITLE_1 = 27f
    const val CARD_OBJ_1 = 23f
    /** the card's scale in the quest dock: its objectives at [BODY] (the title then at 30.5, [TITLE] within a pixel) */
    const val CARD_SCALE = BODY / CARD_OBJ_1

    /** the one scale for a top screen [h] lines tall */
    fun k(h: Int): Float = if (h > 0) h / 1080f else 1f

    /** a role's size on a top screen [h] lines tall */
    fun px(size: Float, h: Int): Float = size * k(h)
}
