package com.jrees.wowcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PET AUTOCAST ON THE FACE BUTTONS (the user, 2026-11-16: "I wonder if we should just use the face buttons without having to select the
 * attack to toggle the attack on and off?"; "Class pet wheel only"; "B still makes a lot of sense to use here"), BUTTON BY WHEEL SPOT
 * (the user, 2026-11-16: "the glyph locations on this bar should match where they actually are on the controller"; "mainly whatever
 * controller setting the user is currently using. where I am using nintento what you described wouldnt be right"): [ClassPetKeys] -
 * which spot each ability takes (cardinals, diagonals, the tie-break, a 5th), the button physically there under the Xbox and the
 * Nintendo ABXY location, its glyph, the back and pick buttons' jobs, the D-pad nothing, and a wheel with no pet ability as before.
 */
class ClassPetKeysTest {
    // PadInput's bits: the ACTIONS (the game's Xbox slots), wherever the location puts them
    private val Y = 1 shl PadInput.SLOT_FACE_TOP
    private val X = 1 shl PadInput.SLOT_FACE_LEFT
    private val A = 1 shl PadInput.SLOT_FACE_BOTTOM
    private val B = 1 shl PadInput.SLOT_FACE_RIGHT
    private val UP = 1 shl PadInput.SLOT_DPAD_UP
    private val DOWN = 1 shl PadInput.SLOT_DPAD_DOWN
    private val T = ClassPetKeys.TOP; private val R = ClassPetKeys.RIGHT; private val BO = ClassPetKeys.BOTTOM; private val L = ClassPetKeys.LEFT

    /** a wheel of auto.size places; place i may cast itself when [auto] says so */
    private fun spots(vararg auto: Boolean): IntArray {
        val has = BooleanArray(8); val a = BooleanArray(8)
        for (i in auto.indices) { has[i] = true; a[i] = auto[i] }
        return ClassPetKeys.spots(has, a)
    }
    private fun all(n: Int) = spots(*BooleanArray(n) { true })
    /** the spots as (top, right, bottom, left) */
    private fun ia(t: Int, r: Int, b: Int, l: Int) = intArrayOf(t, r, b, l)

    @Test fun theButtonPhysicallyOnEachSpotFollowsTheAbxyLocation() {
        // Xbox: Y top, B right, A bottom, X left
        assertEquals(listOf("y", "b", "a", "x"), (0 until 4).map { ClassPetKeys.action(it, false) })
        assertEquals(listOf(PadInput.SLOT_FACE_TOP, PadInput.SLOT_FACE_RIGHT, PadInput.SLOT_FACE_BOTTOM, PadInput.SLOT_FACE_LEFT),
            (0 until 4).map { ClassPetKeys.bit(it, false) })
        // Nintendo (the Thor's print): X top, A right, B bottom, Y left - the bar's own face swap (CastView srcOf)
        assertEquals(listOf("x", "a", "b", "y"), (0 until 4).map { ClassPetKeys.action(it, true) })
        assertEquals(listOf(PadInput.SLOT_FACE_LEFT, PadInput.SLOT_FACE_BOTTOM, PadInput.SLOT_FACE_RIGHT, PadInput.SLOT_FACE_TOP),
            (0 until 4).map { ClassPetKeys.bit(it, true) })
        // the glyphs name the action; UiKit.pad turns them into the BUTTON GLYPHS set's picture
        assertEquals(listOf("rev_y", "rev_b", "rev_a", "rev_x"), (0 until 4).map { ClassPetKeys.glyph(it, false) })
        assertEquals(listOf("rev_x", "rev_a", "rev_b", "rev_y"), (0 until 4).map { ClassPetKeys.glyph(it, true) })
        assertEquals("ltr_b", Glyphs.translate(ClassPetKeys.glyph(R, false), CastSettings.ButtonLayout.XBOX))
        assertEquals("shp_cross", Glyphs.translate(ClassPetKeys.glyph(R, true), CastSettings.ButtonLayout.PLAYSTATION))
        // back / pick: B and A wherever they sit
        assertEquals(R, ClassPetKeys.spotOf(ClassPetKeys.BACK_BIT, false)); assertEquals(BO, ClassPetKeys.spotOf(ClassPetKeys.PICK_BIT, false))
        assertEquals(BO, ClassPetKeys.spotOf(ClassPetKeys.BACK_BIT, true)); assertEquals(R, ClassPetKeys.spotOf(ClassPetKeys.PICK_BIT, true))
    }

    @Test fun eachAbilityTakesItsWheelSpot() {
        // a hunter's wolf on 4 places: Growl top, Bite right, Cower bottom, Dash left
        assertArrayEquals(ia(0, 1, 2, 3), all(4))
        // 1: the top
        assertArrayEquals(ia(0, -1, -1, -1), all(1))
        // 2 (0 and 180 degrees): top, bottom
        assertArrayEquals(ia(0, -1, 1, -1), all(2))
        // 3 (0, 120, 240): top; 120 is nearest the right, 240 nearest the left
        assertArrayEquals(ia(0, 1, -1, 2), all(3))
        // only the ones that may cast themselves: the imp's Phase Shift (left) has no spot, the left stays empty
        assertArrayEquals(ia(0, 1, 2, -1), spots(true, true, true, false))
        // Growl on 3 places with only the 2nd (120) and 3rd (240) casting themselves: right and left, the top empty
        assertArrayEquals(ia(-1, 1, -1, 2), spots(false, true, true))
        // 5 places (0, 72, 144, 216, 288): top, right (72), left (288), bottom (144 and 216 both 36 from it: the earlier slice
        // wins); 216 finds every spot taken: a 5th gets no button
        assertArrayEquals(ia(0, 1, 2, 4), all(5))
        // nothing may cast itself: no pet wheel
        assertArrayEquals(ia(-1, -1, -1, -1), spots(false, false, false))
        assertFalse(ClassPetKeys.isPet(spots(false, false)))
    }

    @Test fun aDiagonalSliceTakesTheNearestFreeSpotClockwiseOnATie() {
        // 8 places at 45 degree steps: the cardinals first (0 top, 2 right, 4 bottom, 6 left), the diagonals get nothing (all taken)
        assertArrayEquals(ia(0, 2, 4, 6), all(8))
        // 135 (place 3) alone: right and bottom equally near - clockwise: bottom
        assertArrayEquals(ia(-1, -1, 3, -1), spots(false, false, false, true, false, false, false, false))
        // 45 -> right, 225 -> left, 315 -> top (each clockwise)
        assertArrayEquals(ia(-1, 1, -1, -1), spots(false, true, false, false, false, false, false, false))
        assertArrayEquals(ia(-1, -1, -1, 5), spots(false, false, false, false, false, true, false, false))
        assertArrayEquals(ia(7, -1, -1, -1), spots(false, false, false, false, false, false, false, true))
        // top (0), 135, left (270): 135 goes clockwise to the bottom
        assertArrayEquals(ia(0, -1, 3, 6), spots(true, false, false, true, false, false, true, false))
        // top (0) and 45: 45's clockwise spot (right) is free
        assertArrayEquals(ia(0, 1, -1, -1), spots(true, true, false, false, false, false, false, false))
        // right (90) and 45: 45's nearer spots are top (free) and right (taken by the exact one): the top
        assertArrayEquals(ia(1, 2, -1, -1), spots(false, true, true, false, false, false, false, false))
        // 0, 45, 90, 135 all: 0 top, 90 right (exact first), then 135 bottom (clockwise), then 45: top / right taken, bottom taken,
        // the left the last free
        assertArrayEquals(ia(0, 2, 3, 1), spots(true, true, true, true, false, false, false, false))
    }

    @Test fun eachButtonTogglesTheAbilityOnItsSpotXbox() {
        val o = all(4)                                           // Growl 0 top, Bite 1 right, Cower 2 bottom, Dash 3 left
        assertEquals(listOf(0), ClassPetKeys.press(o, Y, false).toggles)
        assertEquals(listOf(1), ClassPetKeys.press(o, B, false).toggles)
        assertEquals(listOf(2), ClassPetKeys.press(o, A, false).toggles)
        assertEquals(listOf(3), ClassPetKeys.press(o, X, false).toggles)
        assertEquals(listOf(0, 3), ClassPetKeys.press(o, Y or X, false).toggles)
        assertTrue("the D-pad toggles nothing", ClassPetKeys.press(o, UP or DOWN, false).toggles.isEmpty())
        assertTrue(ClassPetKeys.backToggles(o, false)); assertTrue(ClassPetKeys.pickToggles(o, false))
        assertFalse("A is a toggle: never the pick", ClassPetKeys.press(o, A, false).aPick)
    }

    @Test fun eachButtonTogglesTheAbilityOnItsSpotNintendo() {
        val o = all(4)
        // Nintendo: the top button is X, the right A, the bottom B, the left Y
        assertEquals(listOf(0), ClassPetKeys.press(o, X, true).toggles)
        assertEquals(listOf(1), ClassPetKeys.press(o, A, true).toggles)
        assertEquals(listOf(2), ClassPetKeys.press(o, B, true).toggles)
        assertEquals(listOf(3), ClassPetKeys.press(o, Y, true).toggles)
        assertTrue(ClassPetKeys.backToggles(o, true)); assertTrue(ClassPetKeys.pickToggles(o, true))
        // 3 (top, right, left): Nintendo's B (bottom) is free - it goes back; A (right) is Bite's toggle
        val three = all(3)
        assertFalse(ClassPetKeys.backToggles(three, true)); assertTrue(ClassPetKeys.pickToggles(three, true))
        assertEquals(listOf(1), ClassPetKeys.press(three, A, true).toggles)
        assertTrue(ClassPetKeys.press(three, B, true).toggles.isEmpty())
        // the same wheel on Xbox: B (right) is Bite's, A (bottom) free - the pick
        assertTrue(ClassPetKeys.backToggles(three, false)); assertFalse(ClassPetKeys.pickToggles(three, false))
        assertEquals(listOf(1), ClassPetKeys.press(three, B, false).toggles)
        assertTrue(ClassPetKeys.press(three, A, false).aPick)
    }

    @Test fun oneAndTwoAbilities() {
        val one = all(1)                                         // the top
        for (sw in listOf(false, true)) {
            assertFalse(ClassPetKeys.backToggles(one, sw)); assertFalse(ClassPetKeys.pickToggles(one, sw))
            assertTrue("A keeps its pick", ClassPetKeys.press(one, A, sw).aPick)
            assertFalse("never the pointed one on a pet wheel", ClassPetKeys.press(one, X or Y, sw).xPointed)
        }
        assertEquals(listOf(0), ClassPetKeys.press(one, Y, false).toggles)
        assertTrue(ClassPetKeys.press(one, X, false).toggles.isEmpty())
        assertEquals(listOf(0), ClassPetKeys.press(one, X, true).toggles)
        assertTrue(ClassPetKeys.press(one, Y, true).toggles.isEmpty())
        val two = all(2)                                         // top, bottom
        assertTrue("Xbox: A (bottom) is the 2nd's", ClassPetKeys.pickToggles(two, false)); assertFalse(ClassPetKeys.backToggles(two, false))
        assertEquals(listOf(1), ClassPetKeys.press(two, A, false).toggles)
        assertTrue("Nintendo: B (bottom) is the 2nd's", ClassPetKeys.backToggles(two, true)); assertFalse(ClassPetKeys.pickToggles(two, true))
        assertEquals(listOf(1), ClassPetKeys.press(two, B, true).toggles)
        assertTrue(ClassPetKeys.press(two, A, true).aPick)
    }

    @Test fun aClassWheelWithNoPetAbilityIsAsBefore() {
        val o = spots(false, false, false)
        for (sw in listOf(false, true)) {
            assertTrue(ClassPetKeys.press(o, Y or UP, sw).toggles.isEmpty())
            assertTrue("A: the pick, as before", ClassPetKeys.press(o, A, sw).aPick)
            assertTrue("X: the pointed slice's autocast, as before", ClassPetKeys.press(o, X, sw).xPointed)
            assertFalse(ClassPetKeys.pickToggles(o, sw))
            assertFalse("B goes back, as before", ClassPetKeys.backToggles(o, sw))
            assertTrue(ClassPetKeys.press(o, B, sw).toggles.isEmpty())
        }
    }
}
