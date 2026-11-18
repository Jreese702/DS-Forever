package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #536 (1.0.34: "AH room keeps reopening with a price text field focused and the keyboard open"): X or the Home disc hid the auction
 * window while its price box was being typed. The field stayed open in the add-on and the keyboard stayed up (Mail / the auction house
 * keep it on every refresh), so when the game kept its window open and ours came back (DISMISS_BACK_MS) it came back with the field
 * focused and the keyboard open, every time.
 */
class InteractFieldKbdTest {
    @Test fun closingTheWindowClosesItsField() {
        assertTrue("an open field goes with the window", InteractFieldKbd.closeWithWindow("ahspg"))
        assertFalse("no field: nothing to close", InteractFieldKbd.closeWithWindow(null))
    }

    @Test fun aHiddenWindowNeverOpensTheKeyboard() {
        assertFalse("dismissed: a document still naming the field opens nothing",
            InteractFieldKbd.mayOpen(current = true, name = "ahspg", open = null, dismissed = true))
        assertTrue("shown: the field the add-on opened opens the keyboard",
            InteractFieldKbd.mayOpen(current = true, name = "ahspg", open = null, dismissed = false))
        assertFalse("the same field: already open", InteractFieldKbd.mayOpen(current = true, name = "ahspg", open = "ahspg", dismissed = false))
        assertFalse("a replayed document", InteractFieldKbd.mayOpen(current = false, name = "ahspg", open = null, dismissed = false))
    }

    @Test fun theKeyboardStaysOnlyWhileMailOrTheAuctionHouseShows() {
        assertTrue(InteractFieldKbd.stays(mailOrAuction = true, shown = true))
        assertFalse("the window hidden: the keyboard goes too", InteractFieldKbd.stays(mailOrAuction = true, shown = false))
        assertFalse("another window: as before", InteractFieldKbd.stays(mailOrAuction = false, shown = true))
    }
}
