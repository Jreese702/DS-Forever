package com.jrees.wowcompanion

/**
 * THE GAME'S OWN WHEEL SHUT ONCE MORE (GitHub #306, 1.0.32: the pet wheels' stick "dont respond ... or require bashing B"; the user's pick
 * 2026-11-16: "if the game's own pet/spell flyout ... is still shown ~0.5 s after our class wheel lets go and no room holds the pad, make
 * it visible (alpha 1) and close it once more the safe way"). The same chord that opens our class wheel opens the game's (a toggle whose
 * binding group keeps the right stick); the app taps the add-on's secure shut cell (classr 24, b-game-wheel-shut) at our wheel's open and
 * close. When the game's wheel is still up 0.5 s after the let-go with no room holding, the add-on (ActionBar gws2) draws it again and
 * sends {"type":"gamewheel"}; the host taps that cell once more. The route stays the ruled one (our secure snippet's Hide by a tap): no
 * Blizzard open / close call, no pad press faked. Pure: the host keeps the clock.
 */
object GameWheelAgain {
    /** at most one shut tap of this kind a second (one document per opening; a second wheel in the same moment shares it) */
    const val GAP_MS = 1_000L

    /** the game's wheels by the document's `w` (ActionBar's GF_WHEELS order) */
    val WHEELS = listOf("GamepadPetActionFlyout", "GamepadPetSpellFlyout", "GamepadSpellFlyout")

    /** the wheel's name for the log */
    fun name(w: Int): String = WHEELS.getOrNull(w - 1) ?: "wheel $w"

    /**
     * The shut cell to tap for one CURRENT "gamewheel" document, or null with the reason: [docCol] the add-on's `col` (only while its cell
     * is built as the shut; absent = not built, a fight's change waiting), [classHolding] our class wheel holds the controller again (its
     * own open / close taps shut the game's), [now] / [lastAt] the host's clock and its last such tap.
     */
    fun decide(docCol: Int?, classHolding: Boolean, now: Long, lastAt: Long?): Pair<Int?, String> = when {
        classHolding -> null to "our class wheel holds: its own taps shut it"
        docCol == null || docCol < 0 -> null to "no shut cell built"
        lastAt != null && now - lastAt < GAP_MS -> null to "one already sent this second"
        else -> docCol to "sent"
    }
}
