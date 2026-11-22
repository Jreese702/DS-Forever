package com.jrees.wowcompanion

/**
 * MOVABLE HOME, THE DS TOOLBAR FOLLOWS THE LAYOUT (SPEC.txt section 6, "SYNC"; 1518 build 3, the user's ruling 2026-11-21: the top bar's
 * D-pad icons are apps - dragging one onto another D-pad spot swaps what those two directions open). The add-on owns the binding (PadMap:
 * the router's D-pad buttons' "wc-roombar" attribute, re-bound OUT OF COMBAT only, its `d` saying what the keys do); the app's layout is
 * the source of truth ([HomeLayoutSolver.dpadDirs]) and tells it, never in a fight, re-sent until the padmap's `d` says it - DpadChoice's
 * pend / resend pattern:
 *  - today's two tables (Left = chat or Left = map, Up / Down as always) go as page-3 codes 20 / 21 (padtab 39, 38, 37 then 20 / 21, Book
 *    b76+: every add-on since the mirror takes them);
 *  - any other order as page-3 code 24 then the order's rank 0-23 ([rank]: padtab 39, 38, 37, 24, N; Book b77, PadMap m184's rbp) - only
 *    when the padmap says `pp` (an older add-on cannot bind it: edit mode offers it only the Left / Right swap, [Act.Old] here).
 * Until `d` says it every picture is drawn from `d` (P5: the pictures never lie). Pure: CompanionHost keeps the clock and does the taps.
 */
object ToolbarMirror {
    /** the gap between two sends of the same wish (the add-on answers a tap with a padmap within a second) */
    const val RESEND_MS = 5_000L
    /** the D-pad toast's hold on Home (SPEC section 10: 120 in, 1.6 s, 200 out) */
    const val TOAST_IN_MS = 120L
    const val TOAST_HOLD_MS = 1_600L
    const val TOAST_OUT_MS = 200L
    const val TOAST_MS = TOAST_IN_MS + TOAST_HOLD_MS + TOAST_OUT_MS

    /** PadMap's words in the order [rank] counts them (ROOMBAR's positions 1-4: its identity is rank 0) */
    val WORDS = listOf("chat", "radial", "map", "home")
    /** today's two tables: the mirror bit off (ROOMBAR) and on (R.RB_M) */
    val NORMAL: Map<Int, String> = mapOf(1 to "chat", 2 to "radial", 3 to "map", 4 to "home")
    val MIRROR: Map<Int, String> = mapOf(1 to "map", 2 to "radial", 3 to "chat", 4 to "home")

    sealed class Act {
        /** `d` already says the layout's order */
        object Matched : Act()
        /** wait (no padmap yet, a fight, or a send just went) */
        object Wait : Act()
        /** the order needs the newer Spells add-on (no `pp`): nothing can be sent for it */
        object Old : Act()
        /** tap these cells */
        data class Send(val cells: List<Pair<String, Int>>) : Act()
    }

    private fun same(a: Map<Int, String>, b: Map<Int, String>) = (1..4).all { a[it] == b[it] }

    /** the order's lexicographic rank 0-23 over [WORDS] (positions 1-4); null = not the four words once each */
    fun rank(dirs: Map<Int, String>): Int? {
        val idx = (1..4).map { WORDS.indexOf(dirs[it]) }
        if (idx.any { it < 0 } || idx.toSet().size != 4) return null
        val left = WORDS.indices.toMutableList()
        var r = 0
        for ((i, v) in idx.withIndex()) {
            val at = left.indexOf(v)
            r += at * FACT[3 - i]
            left.removeAt(at)
        }
        return r
    }
    /** the order of rank [r] (0-23), positions 1-4 */
    fun ofRank(r: Int): Map<Int, String>? {
        if (r !in 0 until 24) return null
        val left = WORDS.toMutableList()
        var k = r
        val out = HashMap<Int, String>()
        for (i in 0 until 4) {
            val f = FACT[3 - i]
            out[i + 1] = left.removeAt(k / f)
            k %= f
        }
        return out
    }
    private val FACT = intArrayOf(1, 1, 2, 6)

    /**
     * [want] the layout's order ([HomeLayoutSolver.dpadDirs]); [dirs] the CURRENT padmap's `d` (null = none yet this run); [pp] the add-on
     * binds any order (PadMap m184); [inFight] Home shows a fight (queued: sent after it); [sinceSendMs] since the last send of this wish
     */
    fun decide(want: Map<Int, String>, dirs: Map<Int, String>?, pp: Boolean, inFight: Boolean, sinceSendMs: Long?): Act {
        if (dirs.isNullOrEmpty()) return Act.Wait
        if (same(dirs, want)) return Act.Matched
        val cells = cells(want, pp) ?: return Act.Old
        if (inFight || (sinceSendMs != null && sinceSendMs < RESEND_MS)) return Act.Wait
        return Act.Send(cells)
    }

    /** the taps for [want]: today's tables by the mirror bit's codes (any add-on), another order by its rank (only with [pp]); null = none */
    fun cells(want: Map<Int, String>, pp: Boolean): List<Pair<String, Int>>? = when {
        same(want, NORMAL) -> PadMapModel.mirrorCells(false)
        same(want, MIRROR) -> PadMapModel.mirrorCells(true)
        pp -> rank(want)?.let { PadMapModel.permCells(it) }
        else -> null
    }

    /** the toast: the order [now] differs from the last confirmed [before] (null = the run's first word: no toast) */
    fun toastFor(before: Map<Int, String>?, now: Map<Int, String>?): Boolean =
        before != null && now != null && before.isNotEmpty() && now.isNotEmpty() && !same(before, now)

    /** the toast's alpha at [ms] since it started (0 after [TOAST_MS]) */
    fun toastAlpha(ms: Long): Float = when {
        ms < 0 || ms >= TOAST_MS -> 0f
        ms < TOAST_IN_MS -> ms / TOAST_IN_MS.toFloat()
        ms < TOAST_IN_MS + TOAST_HOLD_MS -> 1f
        else -> 1f - (ms - TOAST_IN_MS - TOAST_HOLD_MS) / TOAST_OUT_MS.toFloat()
    }

    /**
     * the toast's pieces in order: (null, "D-pad"), then each direction that changed from [before] (1-4, the kit's gold arrow that way
     * before its word) and its word now; a swap of Left and Right alone (and the run's first word, [before] null) = Left's, then Right's
     */
    fun toastItems(before: Map<Int, String>?, now: Map<Int, String>): List<Pair<Int?, String>> {
        val changed = if (before == null) listOf(1, 3) else (1..4).filter { before[it] != now[it] }.ifEmpty { listOf(1, 3) }
        return listOf<Pair<Int?, String>>(null to "D-pad") + changed.map { it to word(now[it]) }
    }
    private fun word(w: String?): String = when (w) { "map" -> "Map"; "chat" -> "Chat"; "radial" -> "Radial"; "home" -> "Home"; else -> w ?: "" }

    /** the first-run prompt's lines from what the keys do (CompanionHost's DS WoW toolbar question): Left / Right, then Up / Down */
    fun promptLine(dirs: Map<Int, String>?): String {
        val d = dirs?.takeIf { rank(it) != null } ?: NORMAL
        return "Left: ${word(d[1])}     Right: ${word(d[3])}"
    }
    fun promptLine2(dirs: Map<Int, String>?): String {
        val d = dirs?.takeIf { rank(it) != null } ?: NORMAL
        fun w(p: Int) = when (d[p]) { "radial" -> "the radial"; "home" -> "the Home bar"; else -> word(d[p]) }
        return "Up: ${w(2)}     Down: ${w(4)}"
    }
}
