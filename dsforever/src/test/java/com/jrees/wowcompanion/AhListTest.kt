package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interact i174: an AH listing is pressed on the one listing cell ("ahlist", iact) with its row number first; an older add-on's own cell wins */
class AhListTest {
    @Test fun theListingCell() {
        assertEquals(10, AhList.cell(-1, 10, 7))
        assertEquals(22, AhList.cell(22, 10, 7))
        assertEquals(-1, AhList.cell(-1, -1, 7))
        assertEquals("past the count row's 40: none", -1, AhList.cell(-1, 10, 41))
    }

    @Test fun aPress() {
        assertEquals(AhList.Press("ahlist", 10, 7), AhList.press("ahrow 7", -1, 10))
        assertEquals(AhList.Press("ahrow 7", 22, 0), AhList.press("ahrow 7", 22, 10))
        assertNull(AhList.press("ahrow 7", -1, -1))
        assertNull(AhList.press("ahbuy", -1, 10))
        assertNull(AhList.press("ahrow x", -1, 10))
        assertEquals("iact", InteractRows.of(AhList.CMD))
    }

    @Test fun waitingOnAPress() {
        assertTrue(AhList.waiting("ahlist", "ahrow 3"))
        assertTrue(AhList.waiting("ahrow 3", "ahrow 3"))
        assertFalse(AhList.waiting("ahlist", "ahbuy"))
        assertFalse(AhList.waiting(null, "ahrow 3"))
    }
}
