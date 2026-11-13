package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyLayout.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** THE HOLD FILL (PartyLayout.holdFill): a held member plate fills over exactly the long press's time, drains when let go early */
class PartyHoldFillTest {
    private val e = 1e-4f

    @Test fun fillRunsOverExactlyTheHoldThreshold() {
        assertEquals(PartyLayout.HOLD_MS, PartyModuleView.LONG_MS)             // the fill and the radial's open share one constant
        val d = 1_000L
        assertEquals(0f, PartyLayout.holdFill(d, d, null), e)
        assertEquals(0.5f, PartyLayout.holdFill(d + PartyLayout.HOLD_MS / 2, d, null), e)
        assertTrue(PartyLayout.holdFill(d + PartyLayout.HOLD_MS - 1, d, null) < 1f)
        assertEquals(1f, PartyLayout.holdFill(d + PartyLayout.HOLD_MS, d, null), e)
        assertEquals(1f, PartyLayout.holdFill(d + PartyLayout.HOLD_MS * 3, d, null), e)  // held past it: stays full
        // rises monotonically
        var last = -1f
        for (t in 0..PartyLayout.HOLD_MS step 15) { val f = PartyLayout.holdFill(d + t, d, null); assertTrue(f >= last); last = f }
    }

    @Test fun earlyReleaseDrainsFromWhereItStood() {
        val d = 0L
        val up = PartyLayout.HOLD_MS / 2                                       // let go at half
        assertEquals(0.5f, PartyLayout.holdFill(up, d, up), e)
        assertEquals(0.25f, PartyLayout.holdFill(up + PartyLayout.FILL_DRAIN_MS / 2, d, up), e)
        assertEquals(0f, PartyLayout.holdFill(up + PartyLayout.FILL_DRAIN_MS, d, up), e)
        assertEquals(0f, PartyLayout.holdFill(up + 10_000, d, up), e)
        // a quick tap (40 ms) never gets near full and is gone after the drain
        assertTrue(PartyLayout.holdFill(40, 0, 40) < 0.1f)
        assertEquals(0f, PartyLayout.holdFill(40 + PartyLayout.FILL_DRAIN_MS, 0, 40), e)
    }

    @Test fun fillBoxGrowsLeftToRight() {
        val b = Box(10f, 20f, 210f, 84f)
        assertEquals(Box(10f, 20f, 10f, 84f), PartyLayout.fillBox(b, 0f))
        assertEquals(Box(10f, 20f, 110f, 84f), PartyLayout.fillBox(b, 0.5f))
        assertEquals(b, PartyLayout.fillBox(b, 1f))
        assertEquals(b, PartyLayout.fillBox(b, 2f))
    }

    @Test fun onlyUnitsWhoseHoldOpensTheRadialFill() {
        for (i in 0..4) assertTrue(PartyLayout.holdOpens(i))
        // m157 (the user on 1462: "holding on the pet bar does not open the pet inspect"): the live pet's hold opens its wheel too
        assertTrue(PartyLayout.holdOpens(5))
        assertFalse(PartyLayout.holdOpens(5, dead = true))                     // a dead pet's slot: its Smart Res tap alone
        assertFalse(PartyLayout.holdOpens(5, petMenu = false))                 // an older add-on: no pet menu cell, no fill
        assertTrue(PartyLayout.holdOpens(2, dead = true))                      // a dead member keeps its wheel (as before)
        assertFalse(PartyLayout.holdOpens(6))
        assertFalse(PartyLayout.holdOpens(null))
    }
}
