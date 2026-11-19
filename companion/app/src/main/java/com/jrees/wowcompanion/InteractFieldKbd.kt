package com.jrees.wowcompanion

/**
 * InteractView's field keyboard (Send Mail's boxes, the auction house's search and price boxes) and its window, decided here. Pure
 * (InteractFieldKbdTest).
 *
 * #536 (1.0.34: "AH room keeps reopening with a price text field focused and the keyboard open"): X or the Home disc hid the window
 * while a field was being typed; the add-on kept the field and the keyboard stayed up (Mail and the auction house keep it on every
 * refresh). When the game kept its own window open, ours came back DISMISS_BACK_MS later with the field focused and the keyboard
 * open, every time. Now the field closes with the window, a hidden window never opens the keyboard, and the keyboard never stays up
 * over a window that is not shown.
 */
object InteractFieldKbd {
    /** the window closes (X, the Home disc): the field open on it [open] goes with it (the add-on's "kbd off"; the value stays) */
    fun closeWithWindow(open: String?): Boolean = open != null

    /** a document's kbd naming field [name] opens the keyboard: current, a new field, and never while the window is dismissed */
    fun mayOpen(current: Boolean, name: String, open: String?, dismissed: Boolean): Boolean =
        current && name.isNotEmpty() && name != open && !dismissed

    /** an open keyboard stays at a refresh: on Mail / the auction house only, and only while the window shows */
    fun stays(mailOrAuction: Boolean, shown: Boolean): Boolean = mailOrAuction && shown

    /**
     * i167 (the user 2026-11-19: "if I press b to go back without entering a gold amount it breaks everything"): B closes the keyboard and
     * taps the add-on's "kbd off"; a document the add-on wrote BEFORE that tap still names the field, and it opened the keyboard again
     * (a second B was then needed, and the B's release reached the window's own Back). A field [closed] [ageMs] ago is not reopened
     * by a document naming it for [REOPEN_GUARD_MS]; a document without the field ends the guard (the caller clears [closed]).
     */
    fun justClosed(name: String, closed: String?, ageMs: Long): Boolean =
        closed != null && name == closed && ageMs in 0 until REOPEN_GUARD_MS

    /** i167: the auction house's red Search while one of its own boxes is typed: the typed text is handed over first (not dropped) */
    fun searchCommits(open: String?): Boolean = open == "ahtext" || open == "ahmin" || open == "ahmax"

    const val REOPEN_GUARD_MS = 3_000L
}
