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
}
