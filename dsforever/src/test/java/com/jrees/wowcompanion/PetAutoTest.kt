package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PET'S AUTOCAST (Quests q62; the user, 2026-11-16: "hunters need to be able to visually see when their pets abilities are active
 * like growl, and a way to turn it off from those radials"): the live state (PetAuto), the menu rows (PlayerMenu `au` / `as` / `ac`), the
 * wheel's slices (TargetRadial.Slice.autoSlot, TargetRadial.kitSlice). Documents shaped as tools/test_pet_autocast.py's.
 */
class PetAutoTest {
    @After fun down() = PetAuto.reset()

    private fun doc(s: String) = JSONObject(s)
    private val growlOn = doc("""{"type":"petauto","v":"q62","pet":1,"s":[{"s":4,"n":"Growl","on":1,"sc":13,"ac":23},{"s":5,"n":"Bite","on":0,"sc":14,"ac":24}]}""")
    private val growlOff = doc("""{"type":"petauto","v":"q62","pet":1,"s":[{"s":4,"n":"Growl","on":0,"sc":13,"ac":23},{"s":5,"n":"Bite","on":0,"sc":14,"ac":24}]}""")

    @Test fun theDocumentSaysEachAbilityByItsSlot() {
        PetAuto.onDoc(growlOn, 1_000L)
        assertEquals(true, PetAuto.on(4, 1_000L))
        assertEquals(false, PetAuto.on(5, 1_000L))
        assertNull("not one of the pet's", PetAuto.on(1, 1_000L))
        assertEquals(23, PetAuto.cell(4))
        assertTrue(PetAuto.has(5)); assertFalse(PetAuto.has(6))
        assertTrue(PetAuto.pet); assertFalse(PetAuto.dead); assertFalse(PetAuto.fight)
    }

    @Test fun aPressShowsTheFlipAtOnceUntilTheGameSaysIt() {
        PetAuto.onDoc(growlOn, 1_000L)
        PetAuto.pressed(4, wasOn = true, now = 1_100L)
        assertEquals("the flip shows at once", false, PetAuto.on(4, 1_150L))
        assertTrue(PetAuto.waiting(1_150L))
        // a document sent for another reason, still the old state: the flip stands
        PetAuto.onDoc(growlOn, 1_200L)
        assertEquals(false, PetAuto.on(4, 1_250L))
        // the game's answer: settled
        PetAuto.onDoc(growlOff, 1_400L)
        assertEquals(false, PetAuto.on(4, 1_450L))
        assertFalse(PetAuto.waiting(1_450L))
    }

    @Test fun aTapTheGameNeverSawFallsBackToWhatItLastSaid() {
        PetAuto.onDoc(growlOn, 1_000L)
        PetAuto.pressed(4, wasOn = true, now = 1_000L)
        assertEquals(false, PetAuto.on(4, 1_000L + PetAuto.PENDING_MS))
        assertEquals("expired: the game's last word", true, PetAuto.on(4, 1_001L + PetAuto.PENDING_MS))
    }

    @Test fun aFightThatChangedThePetHasNoToggleCell() {
        PetAuto.onDoc(doc("""{"type":"petauto","pet":1,"fight":1,"s":[{"s":4,"n":"Firebolt","on":1}]}"""), 1_000L)
        assertTrue(PetAuto.fight)
        assertTrue(PetAuto.has(4))
        assertNull(PetAuto.cell(4))
    }

    @Test fun aPetGoneOrDeadAfterTheMenuIsGone() {
        PetAuto.seed(4, "Growl", true, 23, 1_000L)
        assertFalse("the menu's own rows: the pet alive", PetAuto.gone(1_000L))
        PetAuto.onDoc(doc("""{"type":"petauto","pet":1,"dead":1,"s":[{"s":4,"n":"Growl","on":1,"ac":23}]}"""), 1_500L)
        assertTrue(PetAuto.gone(1_000L))
        PetAuto.onDoc(doc("""{"type":"petauto","pet":0,"s":[]}"""), 1_600L)
        assertTrue(PetAuto.gone(1_000L))
        assertNull(PetAuto.on(4, 1_600L))
    }

    @Test fun theMenuRowsCarryTheAbilityAndTheWheelShowsItLive() {
        val items = PlayerMenu.parse(org.json.JSONArray("""[
            {"t":"Feed Pet","k":"PetFeed","sc":5,"en":1,"ic":132165},
            {"t":"Growl","k":"PetAuto","sc":13,"ac":23,"au":1,"as":4,"en":1,"ic":132270},
            {"t":"Bite","k":"PetAuto","sc":14,"au":0,"as":5,"en":1,"ic":132278}]"""))
        val growl = items[1]
        assertEquals(1, growl.auto); assertEquals(4, growl.autoSlot); assertEquals(23, growl.autoCell); assertEquals(13, growl.secure)
        assertNull("no toggle cell built", items[2].autoCell)
        assertNull("not an ability", items[0].auto)
        val slices = TargetRadial.slices(items)!!
        assertEquals(listOf("Feed Pet", "Growl", "Bite"), slices.filterNotNull().map { it.label })
        assertEquals(listOf(null, 4, 5), slices.filterNotNull().map { it.autoSlot })
        // the kit's slice: the menu's word until PetAuto knows better; a press flips it at once
        val g = slices.first { it?.label == "Growl" }!!
        assertEquals(1, TargetRadial.kitSlice(g, 1_000L).auto)
        assertEquals(-1, TargetRadial.kitSlice(slices[0]!!, 1_000L).auto)
        PetAuto.seed(4, "Growl", true, 23, 1_000L)
        PetAuto.pressed(4, wasOn = true, now = 1_100L)
        assertEquals(0, TargetRadial.kitSlice(g, 1_150L).auto)
        // the pet died meanwhile: the slice greyed
        assertFalse(TargetRadial.kitSlice(g, 1_150L, gone = true).enabled)
    }
}
