package com.jrees.wowcompanion

/**
 * MOVABLE HOME, THE DS TOOLBAR FOLLOWS THE SWAP (SPEC.txt section 6, "SYNC"): with the chat on the right, D-pad Left opens the map and Right
 * the chat. The add-on owns the binding (PadMap m178: ROOMBAR from the per-character mirror bit WowCompanionSpellsDB.rbm, re-bound OUT
 * OF COMBAT only, its `d` saying what the keys do); the app's layout is the source of truth and tells it with padtab 39, 38, 37 then 20 /
 * 21 (Book b76; b74's 9 / 10 are the sticky realm question's since test build 1515), never in a fight, re-sent until the padmap's `d` says it - DpadChoice's pend / resend pattern. Until then every picture
 * is drawn from `d` (P5: the pictures never lie). Pure: CompanionHost keeps the clock and does the taps.
 */
object ToolbarMirror {
    /** the gap between two sends of the same wish (the add-on answers a tap with a padmap within a second) */
    const val RESEND_MS = 5_000L
    /** the D-pad toast's hold on Home (SPEC section 10: 120 in, 1.6 s, 200 out) */
    const val TOAST_IN_MS = 120L
    const val TOAST_HOLD_MS = 1_600L
    const val TOAST_OUT_MS = 200L
    const val TOAST_MS = TOAST_IN_MS + TOAST_HOLD_MS + TOAST_OUT_MS

    sealed class Act {
        /** `d` already says the layout's sides */
        object Matched : Act()
        /** wait (no padmap yet, a fight, or a send just went) */
        object Wait : Act()
        /** tap the mirror bit [on] */
        data class Send(val on: Boolean) : Act()
    }

    /** the mirror bit `dirs` (PadMap's `d`) says: 1 = Left is the map, 0 = Left is the chat, null = neither (an older add-on: no `d`) */
    fun bitOf(dirs: Map<Int, String>?): Int? = when {
        dirs == null -> null
        dirs[1] == "map" && dirs[3] == "chat" -> 1
        dirs[1] == "chat" && dirs[3] == "map" -> 0
        else -> null
    }

    /**
     * [want] the layout's bit ([HomeLayoutSolver.mirrorBit]); [dirs] the CURRENT padmap's `d` (null = none yet this run); [inFight] Home
     * shows a fight (queued: sent after it); [sinceSendMs] since the last send of this wish (null = none)
     */
    fun decide(want: Int, dirs: Map<Int, String>?, inFight: Boolean, sinceSendMs: Long?): Act {
        if (dirs.isNullOrEmpty()) return Act.Wait
        val have = bitOf(dirs)
        if (have == want) return Act.Matched
        // an add-on without the mirror (no b74) says Left = chat for ever: nothing to send for "off"; "on" is sent (it is ignored there)
        if (inFight || (sinceSendMs != null && sinceSendMs < RESEND_MS)) return Act.Wait
        return Act.Send(want == 1)
    }

    /** the taps for [on] */
    fun cells(on: Boolean): List<Pair<String, Int>> = PadMapModel.mirrorCells(on)

    /** the toast: the bit [now] differs from the last confirmed [before] (null = the run's first word: no toast) */
    fun toastFor(before: Int?, now: Int?): Boolean = before != null && now != null && before != now

    /** the toast's alpha at [ms] since it started (0 after [TOAST_MS]) */
    fun toastAlpha(ms: Long): Float = when {
        ms < 0 || ms >= TOAST_MS -> 0f
        ms < TOAST_IN_MS -> ms / TOAST_IN_MS.toFloat()
        ms < TOAST_IN_MS + TOAST_HOLD_MS -> 1f
        else -> 1f - (ms - TOAST_IN_MS - TOAST_HOLD_MS) / TOAST_OUT_MS.toFloat()
    }

    /** the toast's words in order: "D-pad", then Left's word and Right's word (the kit's gold arrows go before each) */
    fun toastWords(dirs: Map<Int, String>): List<String> = listOf("D-pad", word(dirs[1]), word(dirs[3]))
    private fun word(w: String?): String = when (w) { "map" -> "Map"; "chat" -> "Chat"; "radial" -> "Radial"; "home" -> "Home"; else -> w ?: "" }

    /** the first-run prompt's first line, from what the keys do (CompanionHost's DS WoW toolbar question) */
    fun promptLine(dirs: Map<Int, String>?): String =
        if (bitOf(dirs) == 1) "Left: Map     Right: Chat" else "Left: Chat     Right: Map"
}
