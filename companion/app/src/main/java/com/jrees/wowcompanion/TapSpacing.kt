package com.jrees.wowcompanion

/**
 * SPACING ONE PAD ROW'S TAPS ACROSS BATCHES (pk3; pure: no Android types). The rough edge (the user's Skip / Previous follow-ups,
 * 2026-11-12): "Two quick presses can lose the second". TapPad's REPEAT guard (0.25 s) treats a second press of the SAME plain cell
 * within 0.25 s as the tap's own second press and drops it. One batch spaces its own taps ([TapBridge.tapsSpaced]), but two batches
 * back to back did not: a Skip is 38 then 10 (120 ms apart, then the batch's short rest), so a second Skip's 38 came ~200 ms after
 * the first's and was dropped, and its 10 then found no 38 in front of it. Map.lua's "pnav" row is the one whose words are two taps
 * (38 then a word; PolestarNav's 38 then 8 / 9 every 20 s too), so its cells are spaced: no cell of a [ROWS] row is tapped twice
 * within [MIN_MS], counted across batches. The later batch waits first ([lead]); nothing is dropped or reordered, other rows never wait.
 *
 * MEASURED, NOT GUESSED: [lead] is asked on the shell's own thread right as the batch starts (RootShell.runBuilt), and [ran] is told
 * when it has finished, so the times are the shell's own. A tap's time is put no earlier than it can have been: the batch's end less
 * the least the script can still have taken after it (its later gaps and [TAIL_MIN_MS], the launcher's shortest tail rest; every tap's
 * own rest only makes it earlier still). So the real spacing is never less than [MIN_MS].
 */
object TapSpacing {
    /** TapPad's REPEAT (0.25 s) and a FRAME'S margin (pk3 review fix): TapPad stamps a press when the game's frame handles it, so
     *  one press handled a slow frame late (a fight at 7-10 fps on the Thor) shrinks the gap the game sees by up to a frame; 260 ms
     *  left 10 ms of that. 400 ms keeps the seen gap >= 0.25 s for frames up to ~150 ms (with the [ran] estimate's own ~20 ms) */
    const val MIN_MS = 400L
    /** the slowest frame [MIN_MS] is sized for (ms) */
    const val FRAME_MS = 150L
    /** the rows spaced (by name): Map.lua's "pnav" (PolestarNav.ROW); bags-onerow2: Bags' name row "bagsort" and shared drag row
     *  "bmv" (BagsMoveRoute: two quick taps on slots of ONE bag name it twice ~one batch apart; a dropped second name voids that tap) */
    val ROWS: Set<String> = setOf(PolestarNav.ROW, BagsMoveRoute.NAME_ROW, BagsMoveRoute.ROW)
    /** the least rest a batch takes after its last tap (InputSink's TAIL_REST_MS 30; a shell's "sleep 0.08" is longer) */
    const val TAIL_MIN_MS = 30L

    /** when each spaced cell was last tapped (ms, the shell thread's clock), at the latest */
    class Clock { val lastAt = HashMap<Pair<String, Int>, Long>() }

    /**
     * A batch starting at [now]: [cells] in order (null = taps at game points, never spaced), [gapMs] between its taps. The wait (ms)
     * before its first tap so that no cell of a [ROWS] row comes within [MIN_MS] of its last tap; 0 = at once.
     */
    fun lead(clock: Clock, now: Long, cells: List<Pair<String, Int>>?, gapMs: Long): Long {
        var lead = 0L
        cells?.forEachIndexed { i, cell ->
            if (cell.first !in ROWS) return@forEachIndexed
            val last = clock.lastAt[cell] ?: return@forEachIndexed
            val need = last + MIN_MS - (now + i * gapMs)
            if (need > lead) lead = need
        }
        return lead.coerceAtMost(MIN_MS)
    }

    /** the batch of [cells] ([gapMs] apart) finished at [end]: each spaced cell's tap, at the latest it can have been */
    fun ran(clock: Clock, end: Long, cells: List<Pair<String, Int>>?, gapMs: Long) {
        if (cells == null) return
        val n = cells.size
        cells.forEachIndexed { i, cell -> if (cell.first in ROWS) clock.lastAt[cell] = end - ((n - 1 - i) * gapMs + TAIL_MIN_MS) }
        if (clock.lastAt.size > 64) clock.lastAt.entries.removeAll { it.value + MIN_MS < end }     // a long session: the map stays small
    }
}
