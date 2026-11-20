package com.jrees.wowcompanion

/**
 * p58 THE CHOOSER AS A GRID (the user, 2026-11-20: "when assigning skills in the controller mode I dont like the list for the skills, can
 * we make it better? maybe a grid similar to the AH, I guess it depends on the tab. on the first second and third tab it should be because
 * the spell Icon matchs the spell and so we can put the name above the tabs. the third icon should probablt stay the same but the 3
 * categories should open kind of similar to the auction house after selected. the 5th tab could easily be a category in tab 4, we also
 * need to add macros here as an option."). Pure (ChooserGridTest): where every cell / row of the chooser stands, laid out EXACTLY as the
 * add-on's cursor walks it (PadTabCtl p58 CG.step: [COLS] a row, the cells in the list's order), and the header line over the tabs.
 *
 * - Spells / General / Professions / Macros: an ICON GRID of [COLS] tiles a row (the Bags grid's slot tiles; the AH Sell grid's gold
 *   box on the cursor's). The highlighted one's NAME stands in the header line above the tabs, so the grid itself is icons only.
 * - Extra: its list, under FOLDING CATEGORY headers (the AH's: a triangle, the word, the count): Commands, DS Forever (the old 5th tab),
 *   Controller, Mounts, Items ([ChooserTabs.CATS]). A or a touch on a header opens it, again folds it.
 *
 * Boxes are in the list's own coordinates (y from the top of the scrolled content).
 */
object ChooserGrid {
    /** the add-on's CG.COLS: its cursor walks these rows */
    const val COLS = 4
    const val GAP = 14f
    const val EDGE = 14f
    /** an Extra category header's height and a list row's (the old chooser rows, PADTAB_SPEC 5) */
    const val HEAD_H = 68f
    const val ROW_H = 92f

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
        val height get() = bottom - top
    }

    /** a tile's side for a list [width] wide */
    fun tile(width: Float): Float = ((width - 2 * EDGE - (COLS - 1) * GAP) / COLS).coerceAtLeast(24f)

    /** [n] tiles, [COLS] a row, left to right then down (the add-on's walk) */
    fun grid(n: Int, width: Float): List<Box> {
        val t = tile(width)
        return List(n) { k ->
            val x = EDGE + (k % COLS) * (t + GAP)
            val y = EDGE * 0.5f + (k / COLS) * (t + GAP)
            Box(x, y, x + t, y + t)
        }
    }

    /** the list's rows: [heads] true = a category header ([HEAD_H]), else an entry row ([ROW_H]) */
    fun list(heads: List<Boolean>, width: Float): List<Box> {
        var y = 4f
        return heads.map { h ->
            val b = Box(0f, y, width, y + (if (h) HEAD_H else ROW_H))
            y = b.bottom + (if (h) 4f else 0f)
            b
        }
    }

    fun height(boxes: List<Box>): Float = (boxes.maxOfOrNull { it.bottom } ?: 0f) + EDGE

    /** the scroll that keeps [box] in a view [viewH] tall, moved as little as possible from [scroll] (AhSellGrid.follow) */
    fun follow(box: Box, scroll: Float, viewH: Float): Float = when {
        box.top - 6f < scroll -> maxOf(0f, box.top - 6f)
        box.bottom + 6f > scroll + viewH -> box.bottom + 6f - viewH
        else -> scroll
    }

    /** the box under ([x], [y]) in the content's own coordinates, or -1 */
    fun hit(boxes: List<Box>, x: Float, y: Float): Int = boxes.indexOfFirst { it.contains(x, y) }

    /** the header line over the tabs: the highlighted thing's [name] (else the open tab's own word, dim), and its [rank] words ("Rank 4")
     *  while ranks are shown ([rankShown]: the "Spell rank badges" setting, HomeSettings.rankBadges, off by default - the caller passes
     *  whether the spell's #471 badge shows, SpellRanks.of). Returns (the words, dim?, the rank words or null) */
    data class Header(val text: String, val dim: Boolean, val rank: String?)
    fun header(name: String?, tabWord: String, rank: String?, rankShown: Boolean): Header =
        if (name.isNullOrEmpty()) Header(tabWord, true, null) else Header(name, false, rank?.takeIf { rankShown && it.isNotEmpty() })
}
