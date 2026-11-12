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

    /** the line for a refused press (the add-on's why), or null to say nothing */
    fun refusedLine(why: String?): String? = when (why) {
        "combat" -> "After combat"
        "nothing" -> "Nothing to undo"
        "no Polestar", "no guide", "no step" -> "No Polestar step"
        else -> null
    }

    /** the pause wheel's two slices' keys */
    const val KEY_SKIP = "ps_skip"
    const val KEY_PREV = "ps_prev"
}
