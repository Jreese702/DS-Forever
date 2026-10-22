package com.jrees.wowcompanion

/**
 * NEVER A ROOM THE PLAYER CANNOT LEAVE (the user: "when I open the map module while in combat, I cannot exit it; the controls do not
 * let me do anything in that menu"). Home's full map takes the controller through the generic guest "home" (Quests' guest hold), and in
 * a fight the game REFUSES that hold (combat lockdown): the module stayed full screen with no key of the controller reaching it.
 * The guard: when the hold is refused, or no answer came within [ANSWER_MS], Home reads the controller ITSELF (PadInput: read only,
 * the game still gets every press) and B - the game's own back button, harmless to the game - closes the module; once the fight is
 * over the hold is asked for again (every [RETRY_MS]) and, when it takes, the fallback ends. Pure.
 */
object MapExitGuard {
    const val ANSWER_MS = 2_000L
    const val RETRY_MS = 3_000L

    /** the fallback reader should run: the module is open and the controller is not held (refused, or no answer in time) */
    fun fallback(wanted: Boolean, held: Boolean, refused: Boolean, sinceAskMs: Long): Boolean =
        wanted && !held && (refused || sinceAskMs >= ANSWER_MS)

    /** ask for the hold again: still without it, the fight over, the last ask long enough ago */
    fun retry(wanted: Boolean, held: Boolean, inFight: Boolean, sinceAskMs: Long): Boolean =
        wanted && !held && !inFight && sinceAskMs >= RETRY_MS

    /** a press of B in the read buttons: held now and not before (one close per press) */
    fun bPressed(before: Int, now: Int): Boolean =
        PadInput.isHeld(now, PadInput.SLOT_FACE_RIGHT) && !PadInput.isHeld(before, PadInput.SLOT_FACE_RIGHT)
}
