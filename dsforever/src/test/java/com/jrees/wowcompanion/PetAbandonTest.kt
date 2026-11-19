package com.jrees.wowcompanion

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ABANDON PET ON THE PET'S WHEEL (Quests q68; the user, 2026-11-19: "I dont have a way to permanently abandon a pet"): the row comes
 * after Dismiss Pet, asks first ("Abandon Rex? Permanent." then A yes, B no), and runs by the add-on's fixed id 63.
 */
class PetAbandonTest {
    private val doc = JSONArray(
        """[{"t":"Feed Pet","k":"PetFeed","sc":5,"en":1,"ic":132165},{"t":"Dismiss Pet","k":"PetDismiss","sc":6,"en":1,"ic":136095},""" +
            """{"t":"Abandon Pet","k":"PetAbandon","i":63,"cf":"Abandon Rex? Permanent.","en":1,"ic":132164},""" +
            """{"t":"Mend Pet","k":"PetMend","sc":8,"en":1,"ic":132179}]""")

    @Test fun theRowSitsAfterDismissAndAsksFirst() {
        val items = PlayerMenu.parse(doc)
        assertEquals(listOf("Feed Pet", "Dismiss Pet", "Abandon Pet", "Mend Pet"), items.map { it.text })
        val ab = items[2]
        assertEquals(63, ab.id)
        assertEquals("Abandon Rex? Permanent.", ab.confirm)
        assertEquals(null, ab.secure)
        // the wheel lays it next to Dismiss Pet (the pet rows fill the places in the menu's order)
        val root = TargetRadial.slices(items)!!
        assertEquals(listOf("Feed Pet", "Dismiss Pet", "Abandon Pet", "Mend Pet"), root.filterNotNull().map { it.label })
        // the run's two digit taps: id 63 = cols 26, 26 (Quests 33 - digit)
        assertEquals(listOf(26, 26), PlayerMenu.digitCols(63))
        // 1503: the ask is a real Yes / No pop-up: the question, then what follows it
        assertEquals("Abandon Rex?" to "Permanent.", TargetRadial.confirmLines(ab.confirm!!))
    }

    @Test fun aGreyedRowInAFight() {
        val fight = JSONArray("""[{"t":"Abandon Pet","k":"PetAbandon","i":63,"cf":"Abandon Rex? Permanent.","en":0}]""")
        assertFalse(PlayerMenu.parse(fight)[0].enabled)
    }

    @Test fun thePopUpsAnswersAreAAndBOnly() {
        // the user on test 1502: "pressing a jumps. this needs to have a real yes no buttons pop up and a should not jump on that prompt"
        assertEquals(TargetRadial.Answer.YES, TargetRadial.answer("PAD1"))
        assertEquals(TargetRadial.Answer.NO, TargetRadial.answer("PAD2"))
        for (k in listOf("PAD3", "PAD4", "PADDUP", "PADDLEFT", "PADFORWARD")) assertEquals(k, TargetRadial.Answer.NONE, TargetRadial.answer(k))
        // every cf entry of the wheel asks the same way (Uninvite, Promote: one line)
        assertEquals("Uninvite?" to null, TargetRadial.confirmLines("Uninvite?"))
        assertEquals("Promote to Party Leader?" to null, TargetRadial.confirmLines("Promote to Party Leader?"))
    }

    @Test fun whileTheAskIsUpThePadIsHeldFromTheGame() {
        val w = PopupHold.Wheel()
        assertFalse(w.holds(1_000L))
        w.up()
        assertTrue(w.holds(1_000L))
        for (k in listOf(android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_BUTTON_X, android.view.KeyEvent.KEYCODE_BUTTON_Y, android.view.KeyEvent.KEYCODE_BUTTON_START)) {
            assertTrue(PopupHold.holdsKey(holding = false, keyCode = k, down = true, wheel = w.holds(1_500L)))   // no A jump, no B action
            assertFalse(PopupHold.holdsKey(holding = false, keyCode = k, down = false, wheel = w.holds(1_500L)))  // a release passes
        }
        // THE HOLD RACE: the A that answered is read off getevent and closes the pop-up before Android's dispatch asks: still held
        w.down(2_000L)
        assertTrue(PopupHold.holdsKey(holding = false, keyCode = android.view.KeyEvent.KEYCODE_BUTTON_A, down = true, wheel = w.holds(2_000L + PopupHold.GRACE_MS - 1)))
        assertFalse(w.holds(2_000L + PopupHold.GRACE_MS))
        assertFalse(PopupHold.holdsKey(holding = false, keyCode = android.view.KeyEvent.KEYCODE_BUTTON_A, down = true, wheel = w.holds(9_000L)))
    }

    /**
     * fix/abandon-runs (the user on test 1505: "Nothing after Yes"; taps.log party:35, then qact:26 qact:26 and no answer): id 63's two
     * digits are ONE cell twice, and TapPad's 0.25 s REPEAT guard dropped the second. The qact row is spaced now: the second 26 waits
     * TapSpacing.MIN_MS behind the first, in the batch and across batches; a pair of different digits never waits.
     */
    @Test fun theRunsRepeatedDigitIsSpaced() {
        assertTrue(GuestPad.ROW in TapSpacing.ROWS)
        val pad = org.json.JSONObject("""{"rows":{"qact":{}}}""")
        val cells = GuestPad.playerMenuRun(pad, 63)!!
        assertEquals(listOf("qact" to 26, "qact" to 26), cells)
        val at = TapSpacing.offsets(cells, 0L)
        assertTrue("the second 26 waits for the guard (${at[1]} ms)", at[1] - at[0] >= TapSpacing.MIN_MS)
        assertTrue(TapSpacing.MIN_MS > 250L)
        // every same-digit id (0, 9, ..., 63: Uninvite / Promote / Rename at those places too) is spaced; others go at once
        for (id in 0..63) {
            val c = GuestPad.playerMenuRun(pad, id)!!
            val o = TapSpacing.offsets(c, 0L)
            if (c[0] == c[1]) assertTrue("id $id", o[1] - o[0] >= TapSpacing.MIN_MS) else assertEquals("id $id", 0L, o[1] - o[0])
        }
        // a second run right behind the first (another batch): its first 26 waits too, never dropped
        val clock = TapSpacing.Clock()
        TapSpacing.ran(clock, 10_000L, cells, 0L)
        assertTrue(TapSpacing.lead(clock, 10_050L, cells, 0L) > 0L)
    }

    /** fix/abandon-runs: a refused run is said, naming the entry while the run is fresh; never silent */
    @Test fun aRefusalIsShownWithItsReason() {
        assertEquals("Release: In combat", PlayerMenu.refusalLine("Release", 1_000L, 1_500L, "In combat"))
        assertEquals("Release: Another pet", PlayerMenu.refusalLine("Release", 1_000L, 1_000L + PlayerMenu.RUN_NAME_MS, "Another pet"))
        assertEquals("Menu gone", PlayerMenu.refusalLine("Release", 1_000L, 1_001L + PlayerMenu.RUN_NAME_MS, "Menu gone"))
        assertEquals("Tap lost", PlayerMenu.refusalLine(null, 0L, 10L, "Tap lost"))
        assertEquals("Release: The game did not do it", PlayerMenu.refusalLine("Release", 1_000L, 1_100L, ""))
        assertEquals("Cannot abandon", PlayerMenu.refusalLine("  ", 1_000L, 1_100L, " Cannot abandon "))
    }
}
