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
        assertEquals("Abandon Rex? Permanent. A yes, B no", TargetRadial.askLine(ab.confirm!!))
    }

    @Test fun aGreyedRowInAFight() {
        val fight = JSONArray("""[{"t":"Abandon Pet","k":"PetAbandon","i":63,"cf":"Abandon Rex? Permanent.","en":0}]""")
        assertFalse(PlayerMenu.parse(fight)[0].enabled)
    }

    @Test fun aSecondPickOfTheSameSliceInTheWindowDoesIt() {
        val ask = TargetRadial.Ask(4_000L)
        assertFalse(ask.pick("Abandon Pet", 1_000L))          // first A: it asks
        assertTrue(ask.armed(2_000L))
        assertTrue(ask.pick("Abandon Pet", 2_000L))           // A again: yes
        assertFalse(ask.armed(2_100L))
    }

    @Test fun bCancelsTheAsk() {
        val ask = TargetRadial.Ask(4_000L)
        assertFalse(ask.pick("Abandon Pet", 1_000L))
        assertTrue(ask.cancel(1_500L))                         // B: no (the wheel stays: back() returns before its own B)
        assertFalse(ask.pick("Abandon Pet", 1_600L))          // the next A asks again, never does it
        assertFalse(ask.cancel(9_000L))                        // an ask past its window: B is the wheel's own B again
    }

    @Test fun anotherSliceOrALatePickAsksAgain() {
        val ask = TargetRadial.Ask(4_000L)
        assertFalse(ask.pick("Abandon Pet", 1_000L))
        assertFalse(ask.pick("Uninvite", 1_500L))             // another slice: it asks for that one
        assertFalse(ask.pick("Abandon Pet", 2_000L))          // back to Abandon: asks again
        assertFalse(ask.pick("Abandon Pet", 7_000L))          // past the window: asks again
        assertTrue(ask.pick("Abandon Pet", 8_000L))
        ask.clear()
        assertFalse(ask.armed(8_100L))
    }
}
