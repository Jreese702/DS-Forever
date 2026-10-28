package com.jrees.wowcompanion

/**
 * PARTY MODE'S HOME MODULE, EVERY RECT (PARTY_BUILD.md 4.2; SPEC_DRAFT v10 / v17 / v18). Pure (no Android types: [Box], never RectF),
 * JVM-tested (PartyLayoutTest). All px on the 1240 x 1080 bottom screen; the module is NEVER hidden (on, off, solo).
 *
 * The left column is x 8..615; cells x 20..603 (583 wide), side-by-side cells 13 apart, rows 11 apart; the well = the cells + 12 all
 * round. AT REST (out of combat, Party mode on or off) and IN A FIGHT WITH PARTY MODE ON (no takeover; every row 150 high) the module
 * stands at the left column's top; IN A FIGHT WITH PARTY MODE OFF (the takeover) at the top of the left well under it, you on top
 * and the others in ONE row (the user's "about half of the chat space").
 */
object PartyLayout {
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
        val cx: Float get() = (l + r) / 2f
        val cy: Float get() = (t + b) / 2f
        fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
        fun offset(dx: Float, dy: Float) = Box(l + dx, t + dy, r + dx, b + dy)
    }

    enum class Mode { REST, FIGHT_ON, FIGHT_OFF }

    /** [cells] in box order (index 0 = you); [tag] the (-) Party mode tag (null when Party mode is on and live) */
    data class Module(val well: Box, val cells: List<Box>, val tag: Box?, val chatTop: Float)

    const val LEFT = 8f
    const val RIGHT = 615f
    /** the well's inside margin: cells start 12 in */
    const val IN = 12f
    const val ROW_GAP = 11f
    const val COL_GAP = 13f
    /** the left column's top at rest on the device (barsBottom + MODULE_GAP) */
    const val REST_TOP = 216f
    /** the left well's top under the fight takeover */
    const val FIGHT_TOP = 398f
    /** chat starts this far under the well's foot */
    const val CHAT_GAP = 8f
    const val TAG_W = 208f
    const val TAG_H = 42f
    /** the tag's right edge, in from the well's right edge */
    const val TAG_RIGHT_IN = 15f
    /** the tag's foot, under the well's top edge */
    const val TAG_BELOW = 20f

    /** the default top for [mode] (the caller passes its own: the left column's top after the bars / the focused quest's card) */
    fun defaultTop(mode: Mode) = if (mode == Mode.FIGHT_OFF) FIGHT_TOP else REST_TOP

    /** k cells across [width] with [COL_GAP] between: each cell's width (whole px, the rest left at the right: 185 x 3 = x 20..601) */
    fun across(k: Int, width: Float): Float = if (k <= 1) width else kotlin.math.floor((width - COL_GAP * (k - 1)) / k)

    /** the module for [n] members (1..5) in [mode], its well's top at [top]; [tagShown] = Party mode off or suspended */
    fun module(mode: Mode, n: Int, top: Float = defaultTop(mode), tagShown: Boolean = true, left: Float = LEFT, right: Float = RIGHT): Module {
        val m = n.coerceIn(1, 5)
        val cl = left + IN; val cr = right - IN; val cw = cr - cl
        val youH = when (mode) {
            Mode.REST -> if (m >= 3) 128f else 102f
            Mode.FIGHT_ON -> 150f
            Mode.FIGHT_OFF -> if (m == 1) 102f else 100f
        }
        val otherH = when (mode) {
            Mode.REST -> if (m == 2) 102f else 100f
            Mode.FIGHT_ON -> 150f
            Mode.FIGHT_OFF -> 100f
        }
        // the others' rows: how many across in each
        val shape: List<Int> = when {
            m == 1 -> emptyList()
            mode == Mode.FIGHT_OFF -> listOf(m - 1)
            m == 2 -> listOf(1)
            m == 3 -> listOf(2)
            m == 4 -> listOf(3)
            else -> listOf(2, 2)
        }
        val cells = ArrayList<Box>()
        var y = top + IN
        cells.add(Box(cl, y, cr, y + youH))
        y += youH
        for (k in shape) {
            y += ROW_GAP
            val w = across(k, cw)
            for (j in 0 until k) {
                val x = cl + j * (w + COL_GAP)
                cells.add(Box(x, y, x + w, y + otherH))
            }
            y += otherH
        }
        val well = Box(left, top, right, y + IN)
        return Module(well, cells, if (tagShown) tag(top, right) else null, well.b + CHAT_GAP)
    }

    /** the (-) tag: 208 x 42 standing on the well's top edge at its right end (at rest x 392..600, y 194..236) */
    fun tag(wellTop: Float, wellRight: Float = RIGHT) =
        Box(wellRight - TAG_RIGHT_IN - TAG_W, wellTop + TAG_BELOW - TAG_H, wellRight - TAG_RIGHT_IN, wellTop + TAG_BELOW)

    /** the tag's parts: the `rev_minus` glyph's centre and size, the words' left x and baseline-centre y */
    data class TagParts(val glyphCx: Float, val glyphCy: Float, val glyph: Float, val textX: Float, val textCy: Float, val textSize: Float)
    fun tagParts(t: Box) = TagParts(t.l + 28f, t.cy, 36f, t.l + 54f, t.cy, 26f)

    // ------------------------------------------------------------------------------------------------ the cell's parts

    /** a full-width cell (a row) gets the role icon and the row's name place; a side-by-side cell the grid's */
    fun fullWidth(cell: Box, left: Float = LEFT, right: Float = RIGHT) = cell.w >= (right - left - 2 * IN) - 0.5f

    /** the health bar's ground, x 2..w-1, y 2..h-13 (cell-relative, made absolute) */
    fun hpBar(c: Box) = Box(c.l + 2f, c.t + 2f, c.r - 1f, c.b - 13f)

    /** the fill for [pct] (0..100) inside [hpBar]; the soft edge's start x (the last 40 px fade out; no fade when [full] or empty) */
    data class Fill(val box: Box?, val fadeFrom: Float?)
    const val FADE = 40f
    fun hpFill(c: Box, pct: Int, full: Boolean): Fill {
        val bar = hpBar(c)
        val p = pct.coerceIn(0, 100)
        if (p == 0) return Fill(null, null)
        val r = bar.l + bar.w * p / 100f
        val f = Box(bar.l, bar.t, r, bar.b)
        return Fill(f, if (full || p >= 100) null else maxOf(bar.l, r - FADE))
    }

    /** the power line: 6 px at y h-7..h-1, the full width (the type's colour only: no amount is ever sent) */
    fun power(c: Box) = Box(c.l + 2f, c.b - 7f, c.r - 1f, c.b - 1f)

    /** the role icon (46 px at (6, 8)) on full-width cells; the dead skull (34 px at (10, 14)) in its place */
    fun role(c: Box) = Box(c.l + 6f, c.t + 8f, c.l + 52f, c.t + 54f)
    fun skull(c: Box) = Box(c.l + 10f, c.t + 14f, c.l + 44f, c.t + 48f)

    /** the power token -> its colour (RGB): MANA blue, RAGE red, ENERGY yellow, FOCUS orange, else grey */
    fun powerRgb(pt: String): Int = when (pt) {
        "MANA" -> 0x0000FF
        "RAGE" -> 0xFF0000
        "ENERGY" -> 0xFFFF00
        "FOCUS" -> 0xFF8040
        else -> 0x808080
    }

    // ------------------------------------------------------------------------------------------------ the words

    /**
     * Where the name and the "~NN%" go, after fitting. [lines] 1 or 2 (never cut: smaller first; in a side cell, under [MIN_ONE] px it
     * takes two lines instead). Every y is a vertical CENTRE; x is the left of the name, [pctRight] the right edge of the % text.
     */
    data class Words(
        val lines: List<String>, val size: Float, val x: Float, val ys: List<Float>,
        val pct: String?, val pctSize: Float, val pctRight: Float, val pctY: Float,
        val dead: Boolean, val deadY: Float, val deadSize: Float,
    )

    const val ROW_NAME = 30f
    const val BIG_NAME = 34f
    const val SMALL_NAME = 28f
    const val ROW_PCT = 28f
    const val BIG_PCT = 32f
    const val TWO_LINE_MAX = 26f
    const val TWO_LINE_PCT = 26f
    const val MIN_ONE = 22f
    const val MIN_SIZE = 10f

    /**
     * The words of cell [c]. [big] = your cell at 128 / 150 high with 3+ members (Friz 34, % 32). [measure] = the text's width at a
     * size in the name's font (Friz). [pct] = "~72%" / "Offline" (null when dead: "Dead" is drawn instead).
     */
    fun words(c: Box, name: String, pct: String?, dead: Boolean, big: Boolean, measure: (String, Float) -> Float,
              left: Float = LEFT, right: Float = RIGHT): Words {
        val row = fullWidth(c, left, right)
        val x = c.l + if (row || dead) 50f else 12f
        val room = if (row) c.w - 140f else c.r - 12f - x - 2f
        val start = when { row && big -> BIG_NAME; row -> ROW_NAME; else -> SMALL_NAME }
        val pctSize = if (big) BIG_PCT else ROW_PCT
        val pctIn = if (row) 14f else 12f
        val pctY = if (row) c.t + 31f else c.t + 68f
        val deadY = c.t + 66f
        // one line: the largest size that fits
        var s = start
        while (s > MIN_SIZE && measure(name, s) > room) s -= 1f
        if (row || s >= MIN_ONE || !name.contains(' ')) {
            return Words(listOf(name), s, x, listOf(c.t + 31f), if (dead) null else pct, pctSize, c.r - pctIn, pctY, dead, deadY, 26f)
        }
        // two lines (a side cell): the split that keeps the longer line shortest, each at <= 26 px, the % under them
        val words = name.split(' ')
        var best: Pair<String, String>? = null
        var bestW = Float.MAX_VALUE
        for (k in 1 until words.size) {
            val a = words.subList(0, k).joinToString(" "); val b = words.subList(k, words.size).joinToString(" ")
            val w = maxOf(measure(a, TWO_LINE_MAX), measure(b, TWO_LINE_MAX))
            if (w < bestW) { bestW = w; best = a to b }
        }
        val (a, b) = best!!
        var t = TWO_LINE_MAX
        while (t > MIN_SIZE && (measure(a, t) > room || measure(b, t) > room)) t -= 1f
        return Words(listOf(a, b), t, x, listOf(c.t + 22f, c.t + 48f), if (dead) null else pct, TWO_LINE_PCT, c.r - pctIn, c.t + 74f,
            dead, deadY + 10f, 26f)
    }

    /** the health text: "~NN%" (always an estimate), "Offline" for a disconnected member */
    fun pctText(hp: Int, off: Boolean) = if (off) "Offline" else "~${hp.coerceIn(0, 100)}%"

    /** the cell index under ([x], [y]), else -1 */
    fun hit(m: Module, x: Float, y: Float): Int = m.cells.indexOfFirst { it.contains(x, y) }
    fun hitTag(m: Module, x: Float, y: Float): Boolean = m.tag?.contains(x, y) == true
}
