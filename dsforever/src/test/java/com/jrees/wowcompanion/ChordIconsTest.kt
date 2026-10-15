package com.jrees.wowcompanion

import com.jrees.wowcompanion.ClassChord.ICON_L
import com.jrees.wowcompanion.ClassChord.ICON_R
import com.jrees.wowcompanion.ClassChord.ICON_ZL
import com.jrees.wowcompanion.ClassChord.ICON_ZR
import org.junit.Assert.assertEquals
import org.junit.Test

/** f-chord-icons (the user 15 Oct): a held trigger shows the class radial's icon on its bumper, a held bumper on its trigger */
class ChordIconsTest {
    private fun on(lt: Boolean = false, rt: Boolean = false, lb: Boolean = false, rb: Boolean = false, hasL: Boolean, hasR: Boolean) =
        ClassChord.iconsOn(lt, rt, lb, rb, hasL, hasR)

    @Test fun druidZlHeldShowsOnL() = assertEquals(ICON_L, on(lt = true, hasL = true, hasR = false))
    @Test fun druidLHeldShowsOnZl() = assertEquals(ICON_ZL, on(lb = true, hasL = true, hasR = false))
    @Test fun druidRightSideStaysAsItIs() = assertEquals(0, on(rt = true, rb = true, hasL = true, hasR = false))
    @Test fun warriorZrHeldShowsOnR() = assertEquals(ICON_R, on(rt = true, hasL = false, hasR = true))
    @Test fun warriorRHeldShowsOnZr() = assertEquals(ICON_ZR, on(rb = true, hasL = false, hasR = true))
    @Test fun hunterBothSides() = assertEquals(ICON_L or ICON_ZR, on(lt = true, rb = true, hasL = true, hasR = true))
    @Test fun aShamanSeesNothingNew() = assertEquals(0, on(lt = true, rt = true, lb = true, rb = true, hasL = false, hasR = false))
    @Test fun nothingHeldNothingChanges() = assertEquals(0, on(hasL = true, hasR = true))

    @Test fun theFormOnNowIsTheIcon() {
        val has = booleanArrayOf(true, true, true, false, false, false, false, false)
        val icons = intArrayOf(11, 22, 33, 0, 0, 0, 0, 0)
        assertEquals(22, ClassChord.iconOf(has, icons, booleanArrayOf(false, true, false, false, false, false, false, false)))
    }
    @Test fun noFormOnIsTheFirstPlace() {
        val has = booleanArrayOf(false, true, true, false, false, false, false, false)
        assertEquals(22, ClassChord.iconOf(has, intArrayOf(0, 22, 33, 0, 0, 0, 0, 0), BooleanArray(8)))
    }
    @Test fun anEmptyRadialHasNoIcon() = assertEquals(0, ClassChord.iconOf(BooleanArray(8), IntArray(8), BooleanArray(8)))
}
