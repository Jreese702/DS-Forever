package com.jrees.wowcompanion

/**
 * THE QUESTS ROOM'S CONTROLLER. Pure Kotlin, so tools/quests_test drives it.
 *
 * ONE SELECTOR: the cursor IS the selection, and the details follow it. Selecting a quest does NOT focus it (the user, RC3
 * 2026-10-09: "I don't like the auto focus in the quests menu, bring back the more button and move focus and share to that
 * menu"; the 2026-10-01 rule "the quest I leave the selection on is focused" is gone). THE BUTTONS (the user, RC4: "Y more with
 * abandon inside, Track/ untrack set to A"): on the list the D-pad's up / down choose the quest; A = Track / Untrack, Y = MORE (a
 * menu: Focus / Remove Focus, Share Quest, Abandon Quest -- its confirm bar next), X = nothing, B = Home. IN THE MORE MENU up / down
 * move its highlight, A chooses the row, B closes the menu; X / Y do nothing (the user dislikes other buttons' glyphs while a
 * menu is open). On the Abandon confirm bar (UI Art's v5: Cancel with the B glyph, Abandon with the A glyph) A abandons and B
 * cancels. The cursor is shown from the moment the room holds the controller ([show]).
 */
class QuestCursor {
    /** what the room must do for a press */
    sealed class Do {
        object Nothing : Do()
        data class Select(val id: Int) : Do()
        /** Y: open the More menu */
        object More : Do()
        /** A: Track / Untrack */
        object Track : Do()
        /** the confirm bar's answers */
        object Cancel : Do()
        object ConfirmAbandon : Do()
        object Leave : Do()
        /** the More menu: its highlight moved to row [i] / A on row [i] / B closes it */
        data class MenuMove(val i: Int) : Do()
        data class MenuPick(val i: Int) : Do()
        object MenuClose : Do()
    }

    /** the cursor is drawn (the room holds the controller) */
    var shown = false
        private set

    fun show() { shown = true }

    /** the room was left, or the controller went away */
    fun reset() { shown = false }

    /** DIRECTPAD: the keys that REPEAT while held (the list's and the menu's up / down); every other key acts once per press */
    fun repeats(k: String): Boolean = k in REPEAT_KEYS

    /**
     * One press [k] (PADDUP / PADDDOWN / PADDLEFT / PADDRIGHT on the press; PAD1 = A, PAD2 = B, PAD3 = X, PAD4 = Y on the
     * release). [ids] = the list in order, [selected] = the quest the details show, [confirm] = the Abandon confirm bar is open,
     * [menu] = the More menu's highlighted row (-1: closed), [menuRows] = its number of rows.
     */
    fun key(k: String, ids: List<Int>, selected: Int, confirm: Boolean, menu: Int = -1, menuRows: Int = 0): Do {
        if (confirm) return when (k) {
            "PAD1" -> Do.ConfirmAbandon
            "PAD2" -> Do.Cancel
            else -> Do.Nothing
        }
        if (menu >= 0) return when (k) {
            "PADDUP", "PADDDOWN" -> {
                if (menuRows <= 0) return Do.Nothing
                val j = (menu + if (k == "PADDUP") -1 else 1).coerceIn(0, menuRows - 1)
                if (j != menu) Do.MenuMove(j) else Do.Nothing
            }
            "PAD1" -> if (menu < menuRows) Do.MenuPick(menu) else Do.Nothing
            "PAD2" -> Do.MenuClose
            else -> Do.Nothing
        }
        return when (k) {
            "PADDUP", "PADDDOWN" -> {
                if (ids.isEmpty()) return Do.Nothing
                val i = ids.indexOf(selected)
                val j = if (i < 0) 0 else (i + if (k == "PADDUP") -1 else 1).coerceIn(0, ids.size - 1)
                if (ids[j] != selected) Do.Select(ids[j]) else Do.Nothing
            }
            "PAD1" -> if (selected in ids) Do.Track else Do.Nothing
            "PAD4" -> if (selected in ids) Do.More else Do.Nothing          // X (PAD3): nothing since RC4
            "PAD2" -> Do.Leave
            else -> Do.Nothing
        }
    }

    /** where B leads now, for the Home button's B glyph: back while the confirm bar or the More menu is open */
    fun bLeads(confirm: Boolean, menu: Boolean = false): String = if (confirm || menu) "back" else "home"

    companion object {
        /** DirectPad's hold-repeat for this room (Home's PadHub reads it): only up / down */
        val REPEAT_KEYS = setOf("PADDUP", "PADDDOWN")
    }
}
