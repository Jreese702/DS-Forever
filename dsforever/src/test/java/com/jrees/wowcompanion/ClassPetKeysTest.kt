package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PET AUTOCAST ON THE FACE BUTTONS (the user, 2026-11-16: "I wonder if we should just use the face buttons without having to select the
 * attack to toggle the attack on and off? ... using Y/X/A as the toggles for them?"; picks "Y / X / A, pet bar order", the 4th "D-pad Up",
 * "Class pet wheel only"): [ClassPetKeys] - which place each button toggles, A's job with 1 / 2 / 3 / 4 abilities, X's, and a class wheel
 * with no pet ability exactly as before.
 */
class ClassPetKeysTest {
    private val Y = 1 shl PadInput.SLOT_FACE_TOP
    private val X = 1 shl PadInput.SLOT_FACE_LEFT
    private val A = 1 shl PadInput.SLOT_FACE_BOTTOM
    private val B = 1 shl PadInput.SLOT_FACE_RIGHT
    private val UP = 1 shl PadInput.SLOT_DPAD_UP
    private val DOWN = 1 shl PadInput.SLOT_DPAD_DOWN

    /** a wheel of [n] places: place i may cast itself when [auto] says so, at pet bar slot [slots] (0 = none sent) */
    private fun order(auto: List<Boolean>, slots: List<Int>): List<Int> {
        val has = BooleanArray(8); val a = BooleanArray(8); val s = IntArray(8)
        for (i in auto.indices) { has[i] = true; a[i] = auto[i]; s[i] = slots[i] }
        return ClassPetKeys.order(has, a, s)
    }
    /** [n] abilities that may cast themselves, at pet bar slots 4.. in wheel order (the add-on lays them out in pet bar order) */
    private fun pets(n: Int) = order(List(n) { true }, List(n) { 4 + it })

    @Test fun theButtonsGoYXAThenDpadUpInPetBarOrder() {
        assertEquals("the face buttons Y, X, A, then D-pad Up", listOf(PadInput.SLOT_FACE_TOP, PadInput.SLOT_FACE_LEFT,
            PadInput.SLOT_FACE_BOTTOM, PadInput.SLOT_DPAD_UP), ClassPetKeys.BUTTONS.toList())
        assertEquals(listOf("rev_y", "rev_x", "rev_a", "gen_up"), ClassPetKeys.GLYPHS.toList())
        // a hunter's wolf: Growl (slot 4), Bite (5), Cower (6), Dash (7) at places 0..3
        assertEquals(listOf(0, 1, 2, 3), pets(4))
        // the wheel's places in another order than the pet bar's: the PET BAR decides (Bite slot 5 at place 0, Growl slot 4 at place 2)
        assertEquals(listOf(2, 0, 1), order(listOf(true, true, true), listOf(5, 7, 4)))
        // a warlock's imp: Firebolt, Blood Pact, Fire Shield may, Phase Shift (place 3) may not: no button
        assertEquals(listOf(0, 1, 2), order(listOf(true, true, true, false), listOf(4, 5, 6, 7)))
        // a place with no slot (an older add-on) after the ones that have one, in wheel order
        assertEquals(listOf(1, 0, 2), order(listOf(true, true, true), listOf(0, 4, 0)))
        // a 5th ability has no button (the long press still toggles it)
        assertEquals(listOf(0, 1, 2, 3), order(List(5) { true }, List(5) { 4 + it }))
    }

    @Test fun eachButtonTogglesItsOwnAbilityWithNoAiming() {
        val o = pets(4)
        assertEquals(listOf(0), ClassPetKeys.press(o, Y).toggles)
        assertEquals(listOf(1), ClassPetKeys.press(o, X).toggles)
        assertEquals(listOf(2), ClassPetKeys.press(o, A).toggles)
        assertEquals(listOf(3), ClassPetKeys.press(o, UP).toggles)
        // two pressed at once: both, each once
        assertEquals(listOf(0, 1), ClassPetKeys.press(o, Y or X).toggles)
        // B and the other D-pad arrows toggle nothing (B is the wheel's back, before this)
        assertTrue(ClassPetKeys.press(o, B or DOWN).toggles.isEmpty())
    }

    @Test fun aWithOneAbility() {
        val o = pets(1)
        assertEquals(listOf(0), ClassPetKeys.press(o, Y).toggles)
        assertFalse(ClassPetKeys.aToggles(o))
        val a = ClassPetKeys.press(o, A)
        assertTrue("A keeps its pick", a.aPick); assertTrue(a.toggles.isEmpty())
        val x = ClassPetKeys.press(o, X)
        assertTrue("X owns nothing here: nothing", x.toggles.isEmpty()); assertFalse("never the pointed one on a pet wheel", x.xPointed)
        assertTrue("D-pad Up owns nothing", ClassPetKeys.press(o, UP).toggles.isEmpty())
    }

    @Test fun aWithTwoAbilities() {
        val o = pets(2)
        assertEquals(listOf(1), ClassPetKeys.press(o, X).toggles)
        assertFalse(ClassPetKeys.press(o, X).xPointed)
        assertFalse(ClassPetKeys.aToggles(o))
        assertTrue("A keeps its pick", ClassPetKeys.press(o, A).aPick)
        assertTrue(ClassPetKeys.press(o, A).toggles.isEmpty())
    }

    @Test fun aWithThreeAbilitiesIsATogglerAndNeverCasts() {
        val o = pets(3)
        assertTrue(ClassPetKeys.aToggles(o))
        val a = ClassPetKeys.press(o, A)
        assertEquals(listOf(2), a.toggles)
        assertFalse("A never casts once it is a toggle", a.aPick)
        assertTrue("D-pad Up owns nothing", ClassPetKeys.press(o, UP).toggles.isEmpty())
    }

    @Test fun aWithFourAbilities() {
        val o = pets(4)
        assertTrue(ClassPetKeys.aToggles(o))
        assertFalse(ClassPetKeys.press(o, A).aPick)
        assertEquals(listOf(2), ClassPetKeys.press(o, A).toggles)
        assertEquals(listOf(3), ClassPetKeys.press(o, UP).toggles)
    }

    @Test fun aClassWheelWithNoPetAbilityIsAsBefore() {
        // a warrior's stances / a druid's forms / the pet's commands: nothing may cast itself
        val o = order(listOf(false, false, false), listOf(1, 2, 3))
        assertTrue(o.isEmpty())
        assertTrue(ClassPetKeys.press(o, Y or UP).toggles.isEmpty())
        assertTrue("A: the pick, as before", ClassPetKeys.press(o, A).aPick)
        assertTrue("X: the pointed slice's autocast, as before", ClassPetKeys.press(o, X).xPointed)
        assertFalse(ClassPetKeys.aToggles(o))
        assertTrue(ClassPetKeys.press(emptyList(), A).aPick)
    }
}
