package com.jrees.wowcompanion

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** THE PET'S RENAME (Quests q69; the user on test 1502: "renaming my pet is still grayed out"): the game's own row, live, ap "rename" */
class PetRenameTest {
    @Test fun theRowIsLiveAndOpensTheKeyboard() {
        val e = PlayerMenu.parse(JSONArray("""[{"t":"Rename Pet","k":"PetRename","i":4,"ap":"rename","en":1}]"""))[0]
        assertTrue(e.enabled)
        assertEquals("rename", e.app)
        assertEquals(4, e.id)
        assertEquals(null, e.confirm)
        assertEquals(12, TargetRadial.RENAME_MAX)
        // B: the field closes by a fixed run id (two base-8 digits reach 63; the menu's entries stop at 61, 63 is Abandon Pet's)
        assertEquals(62, TargetRadial.RENAME_OFF_ID)
        assertEquals(listOf(26, 27), PlayerMenu.digitCols(62))
    }

    @Test fun aGreyedRowSaysWhy() {
        val e = PlayerMenu.parse(JSONArray("""[{"t":"Rename Pet","k":"PetRename","i":4,"ap":"rename","en":0,"wy":"Can't rename this pet"}]"""))[0]
        assertFalse(e.enabled)
        assertEquals("Can't rename this pet", TargetRadial.greyWhy(e))
        val plain = PlayerMenu.parse(JSONArray("""[{"t":"Duel","k":"Duel","i":2,"en":0}]"""))[0]
        assertEquals("Not available here", TargetRadial.greyWhy(plain))
    }

    @Test fun theRenameHoldsEveryPadButton() {
        for (k in listOf(android.view.KeyEvent.KEYCODE_BUTTON_X, android.view.KeyEvent.KEYCODE_BUTTON_Y, android.view.KeyEvent.KEYCODE_BUTTON_START,
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT, android.view.KeyEvent.KEYCODE_BUTTON_L1, android.view.KeyEvent.KEYCODE_BUTTON_R1))
            assertTrue(k in PopupHold.WHEEL_KEYS)
        assertTrue(PopupHold.WHEEL_KEYS.containsAll(PopupHold.HELD_KEYS))
    }
}
