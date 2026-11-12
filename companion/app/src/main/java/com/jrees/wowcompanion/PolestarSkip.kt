package com.jrees.wowcompanion

/**
 * SKIP POLESTAR'S STEP, AND UNDO THE LAST SKIP (pk1; pure: no Android types). The user, 2026-11-12: "we need to make a way to skip a
 * polestar objective, idealy in the home map moduale with X or Y, maybe we also add a previous step button? idk"; the picks:
 *  - X Skip, Y Previous on the light blue Polestar row (merged with a quest, that quest's Track / Open leave X and Y);
 *  - Previous = "Undo my last skip": the step skipped last comes back as the live step (pressed again: the skip before it);
 *  - no confirm: one press skips at once;
 *  - on Home's map, in the Map room (its quest list's Polestar row) and as two slices on the Start pause wheel.
 * Map.lua (WC.pskip) does it through Polestar's own Engine.Skip / Unskip / Goto and keeps the per-character stack of skips; the app
 * taps the "pnav" row: 38 then 10 = Skip, 38 then 11 = Previous. Its step doc says whether Skip is there ([PolestarStep.skip], an
 * older add-on: never) and how many skips can be undone ([PolestarStep.undo]); nothing to undo: Previous is hidden (Home's hints, the
 * Map room's legend) or dim (the wheel) and does nothing. Its answer {"type":"pskip", op, ok, why} says why a press did nothing.
 * pk3 (the user's picks, 2026-11-12): "Work in fights too" - no combat refusal any more (Map.lua pk3 skips in a fight; the pnav row is
 * a plain pad row, its taps reach the add-on in a fight as out of it); the answer carries u, so Previous is there AT ONCE after a Skip
 * ([undoAfter]); every pnav tap is spaced across batches ([TapSpacing]) so two quick presses both land; a refused press from the pause
 * wheel says why on the wheel's own line wherever it was opened ([noteWhere]).
 */
object PolestarSkip {
    const val SAY = 38
    const val SKIP = 10
    const val PREV = 11

    /** the words (terse: the user's rule), the same on every route */
    const val SKIP_WORD = "Skip step"
    const val PREV_WORD = "Previous step"

    /** the "pnav" row's columns for one press: Skip, or Previous ([prev]) */
    fun cols(prev: Boolean): List<Int> = listOf(SAY, if (prev) PREV else SKIP)

    /** Skip can be pressed: a Polestar step is shown ([shown]: the setting and a step) and the add-on can skip it */
    fun canSkip(shown: Boolean, step: PolestarStep?): Boolean = shown && step?.skip == true

    /** Previous can be pressed: as [canSkip], and a skip is left to undo */
    fun canPrev(shown: Boolean, step: PolestarStep?): Boolean = canSkip(shown, step) && (step?.undo ?: 0) > 0

    /**
     * the light blue row's hints on Home's map, as (button, words): A Focus / Unfocus ([holds]), X Skip step while [canSkip], Y Previous
     * step while [canPrev]. The same merged with a quest or not (that quest's Track / Open of the 2026-11-11 merge left X and Y)
     */
    fun rowHints(holds: Boolean, canSkip: Boolean, canPrev: Boolean): List<Pair<String, String>> = listOfNotNull(
        "a" to (if (holds) "Unfocus" else "Focus"),
        if (canSkip) "x" to SKIP_WORD else null,
        if (canPrev) "y" to PREV_WORD else null,
    )

    const val NOTHING_LINE = "Nothing to undo"
    const val NO_STEP_LINE = "No Polestar step"
    /** pk3: Polestar's own call failed (an error of its own; never seen) */
    const val REFUSED_LINE = "Polestar refused it"

    /** the line for a refused press (the add-on's why), or null to say nothing (no why). pk3: no combat refusal any more */
    fun refusedLine(why: String?): String? = when (why) {
        null, "" -> null
        "nothing" -> NOTHING_LINE
        "no Polestar", "no guide", "no step" -> NO_STEP_LINE
        else -> REFUSED_LINE
    }

    /** pk3: a press that cannot go now ([prev] with nothing to undo, or no step to skip): its line */
    fun cantLine(prev: Boolean, canSkip: Boolean): String = if (prev && canSkip) NOTHING_LINE else NO_STEP_LINE

    /**
     * pk3 (the rough edge "Previous does nothing for ~1 s right after a Skip"): the undo count after an answer {"type":"pskip"} ([op],
     * [ok], [u] = the add-on's count after the press, null from a pk1 add-on), from [cur]: the add-on's count when it says one, else
     * an ok Skip +1 and an ok Previous -1 (never below 0); a refused press without a count leaves it
     */
    fun undoAfter(op: String, ok: Boolean, u: Int?, cur: Int): Int = when {
        u != null && u >= 0 -> u
        !ok -> cur
        op == "skip" -> cur + 1
        op == "prev" -> (cur - 1).coerceAtLeast(0)
        else -> cur
    }

    /** where a refused press's line goes: the pause wheel's own line, the Map room's, Home's map, or nowhere */
    enum class Where { WHEEL, MAP_ROOM, HOME_MAP, NONE }

    /** a press from the pause wheel within this long of its answer: the answer's line goes on the wheel's line */
    const val WHEEL_ANSWER_MS = 5_000L

    /**
     * pk3 (the rough edge "a refused Skip / Previous from the pause wheel shows no note outside Home and the Map room"): a refused
     * answer's line goes where the press came from - pressed on the wheel ([wheelAt] its time, 0 none; [now]) the wheel's own line in
     * any room; else in the Map room ([inMapRoom]) its line, else Home's map. An ok answer, or no line: nowhere
     */
    fun noteWhere(ok: Boolean, line: String?, now: Long, wheelAt: Long, inMapRoom: Boolean): Where = when {
        ok || line == null -> Where.NONE
        wheelAt > 0L && now - wheelAt in 0L..WHEEL_ANSWER_MS -> Where.WHEEL
        inMapRoom -> Where.MAP_ROOM
        else -> Where.HOME_MAP
    }

    /** the pause wheel's two slices' keys */
    const val KEY_SKIP = "ps_skip"
    const val KEY_PREV = "ps_prev"
}
