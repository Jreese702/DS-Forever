package com.jrees.wowcompanion

/**
 * DIRECTPAD'S PURE PART (the user, 2026-10-06: "this menu and many others are just sometimes really baggy, and delayed ... We need to
 * improve this dramatically"; the latency study: a room's cursor moved ~200 ms after a press, the game -> log -> app trip). The app's
 * own controller reader (PadInput, ~20 ms) reports the same presses; these are their names, EXACTLY the capture's (Quests.lua's
 * guestpad `k`), so a room's handler cannot tell a direct press from a relayed one. Positional, as the game's PAD1..4 (Cast: PAD1 =
 * the bottom face button).
 */
object DirectPadKeys {
    /** PadInput's button slots -> the capture's key names */
    val SLOT_KEYS = mapOf(
        0 to "PADDLEFT", 1 to "PADDUP", 2 to "PADDRIGHT", 3 to "PADDDOWN",
        4 to "PAD3", 5 to "PAD4", 6 to "PAD2", 7 to "PAD1",
        8 to "PADFORWARD", 9 to "PADBACK", 10 to "PADLSTICK", 11 to "PADRSTICK",
    )
    const val LB = "PADLSHOULDER"
    const val RB = "PADRSHOULDER"
    const val LT = "PADLTRIGGER"
    const val RT = "PADRTRIGGER"

    /** what a room's guest holds by default: the D-pad and the four face buttons (Quests.lua's eight) */
    val EIGHT = setOf("PADDUP", "PADDDOWN", "PADDLEFT", "PADDRIGHT", "PAD1", "PAD2", "PAD3", "PAD4")

    /** the hold-repeat: the first repeat after [REPEAT_FIRST_MS], then every [REPEAT_MS] (Quests' proposal, one feel for every room) */
    const val REPEAT_FIRST_MS = 280L      // 350 -> 280, 90 -> 70 (the user after b73: the D-pad "okayish"; plan D)
    const val REPEAT_MS = 70L

    /** the presses (true) and releases (false) between two button masks, by key name, in slot order */
    fun edges(before: Int, now: Int): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for ((slot, k) in SLOT_KEYS) {
            val was = before and (1 shl slot) != 0
            val isNow = now and (1 shl slot) != 0
            if (was != isNow) out += k to isNow
        }
        return out
    }

    /** the same for the shoulders and the triggers (PadInput's onPad booleans) */
    fun padEdges(before: BooleanArray, lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean): List<Pair<String, Boolean>> {
        val now = booleanArrayOf(lt, rt, lb, rb)
        val names = arrayOf(LT, RT, LB, RB)
        return (0..3).filter { before[it] != now[it] }.map { names[it] to now[it] }
    }

    /** the guest "pad:<id>" the capture names, or null */
    fun guestOf(holder: String?): String? = holder?.takeIf { it.startsWith("pad:") }?.removePrefix("pad:")
}
