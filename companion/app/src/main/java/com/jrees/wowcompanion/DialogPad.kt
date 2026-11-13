package com.jrees.wowcompanion

/**
 * POP-UPS AND LOOT ROLLS WITH THE PAD IN A FIGHT (combat_nav; the user's decision 1, 2026-11-11: "those scenarios should be built on the
 * bottom screen"). Every key is carried at the pull now (PadMap m134), so the game's own A / B on a pop-up or its roll frame never sees
 * the press while the app has the pad (PadOwnerSync's room mode). DialogView answers the mirror itself, one tap on the button's own cell:
 *  - a pop-up: A = its first button (never a delayed one, never the QUIT dialog's "Exit now": the user's standing ruling), B = its second
 *    (Dialogs d16's A / B rule for a pop-up the game did not focus);
 *  - a loot roll: A Need, X Greed (or Transmogrification when Greed is not allowed), B Pass (the game's own roll frame keys), the D-pad's
 *    Left / Right between the rolls. Pure (DialogPadTest).
 */
object DialogPad {
    class B(val k: Int, val on: Boolean, val cell: Int)

    /** the button number [key] answers on a dialog, or null */
    fun dialogButton(key: String, buttons: List<B>, delayed: Boolean, quit: Boolean): Int? {
        val k = when (key) { "PAD1" -> 1; "PAD2" -> 2; else -> return null }
        if (k == 1 && (delayed || quit)) return null
        val b = buttons.firstOrNull { it.k == k } ?: return null
        return if (b.on && b.cell >= 0) k else null
    }

    /**
     * camp-exit (build 1462, the user: "when exiting the game and the timer pops up it doesnt have the 'exit now' option that it normally
     * should"): the QUIT countdown's "Exit Now" (its button 1, the game's ForceQuit) is the player's alone. [finger] = a finger on its
     * drawn button (Dialogs d32 mirrors it with its cell, as the game shows it): yes. Any press the APP makes by itself (the controller's
     * fallback tap, a pad answer in a fight, the safe close): never - the user's standing ruling "never press Exit Now" for the player.
     */
    fun mayPress(which: String?, k: Int, finger: Boolean): Boolean = finger || !(which == "QUIT" && k == 1)

    /** the roll kind [key] answers on a roll that allows [need] / [greed] / [transmog], or null */
    fun rollKind(key: String, need: Boolean, greed: Boolean, transmog: Boolean): String? = when (key) {
        "PAD1" -> if (need) "need" else null
        "PAD3" -> if (greed) "greed" else if (transmog) "transmog" else null
        "PAD2" -> "pass"
        else -> null
    }

    /** Left / Right: the roll id to show next among [ids] from [cur] (no wrap), or null when [key] is no move */
    fun rollMove(key: String, ids: List<Int>, cur: Int): Int? {
        val d = when (key) { "PADDLEFT" -> -1; "PADDRIGHT" -> 1; else -> return null }
        if (ids.isEmpty()) return null
        val i = ids.indexOf(cur).coerceAtLeast(0)
        return ids[(i + d).coerceIn(0, ids.size - 1)]
    }
}
