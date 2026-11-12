package com.jrees.wowcompanion

import com.jrees.wowcompanion.SelectTab.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** party_design.md E and the user's decision 2026-11-11 ("the Select tab STAYS and lights up gold while party mode is on") */
class SelectTabTest {
    private val party = PartyModelTest.party()                  // on, live, sel bound, grouped

    @Test fun looksFollowTheDocumentOnly() {
        assertEquals(Look.ON, SelectTab.lookOf(party))
        assertEquals(Look.OFF, SelectTab.lookOf(party.copy(on = false, box = null)))
        assertEquals(Look.OFF, SelectTab.lookOf(null))
        for (sel in listOf("off", "dialog", "aside")) assertEquals(sel, Look.UNBOUND, SelectTab.lookOf(party.copy(on = false, sel = sel)))
        // suspended by a room (aside): not lit (the add-on routes nothing)
        assertEquals(Look.UNBOUND, SelectTab.lookOf(party.copy(aside = "room", sel = "aside")))
    }

    @Test fun theWipeAndTheArrowTurnOver180ms() {
        // the first look is shown as it is (Home opening with Party mode already on: lit at once, no wipe)
        val first = SelectTab()
        first.setLook(Look.ON, 50L)
        assertEquals(1f, first.wipe(50L)); assertFalse(first.moving(50L))
        val t = SelectTab()
        t.setLook(Look.OFF, 0L)
        assertEquals(0f, t.wipe(0L)); assertEquals(0f, t.arrowDeg(0L))
        t.setLook(Look.ON, 1000L)
        assertEquals(0.5f, t.wipe(1090L), 1e-4f); assertEquals(90f, t.arrowDeg(1090L), 1e-3f)
        assertEquals(1f, t.wipe(1180L)); assertEquals(180f, t.arrowDeg(2000L))
        assertTrue(t.moving(1100L)); assertFalse(t.moving(1300L))
        // off half way: it turns back from where it stood
        t.setLook(Look.OFF, 3000L)
        t.setLook(Look.ON, 3090L)
        assertEquals(0.5f, t.wipe(3090L), 1e-4f)
        assertEquals(1f, t.wipe(3270L))
        t.setLook(Look.UNBOUND, 4000L)
        assertEquals(0f, t.wipe(4180L))
    }

    @Test fun aSelectPressFlashes120ms() {
        val t = SelectTab()
        assertFalse(t.pressed(0L)); assertEquals(1f, t.glyphScale(0L))
        t.press(500L)
        assertTrue(t.pressed(560L)); assertEquals(0.92f, t.glyphScale(560L)); assertFalse(t.pressed(620L))
    }

    @Test fun theNotchSlidesToThePick() {
        val t = SelectTab()
        assertNull(t.notchY(0L))
        t.setNotch(260f, 0L)
        assertEquals(260f, t.notchY(0L))                               // the first one: no slide
        t.setNotch(332f, 1000L)
        assertEquals(296f, t.notchY(1060L)!!, 1e-3f); assertEquals(332f, t.notchY(1120L))
        t.setNotch(null, 2000L)
        assertNull(t.notchY(2000L))                                    // Party mode off: no notch
    }

    @Test fun theEdgePulsesTwiceWhenAGroupFormsNeverInAFight() {
        val t = SelectTab()
        t.groupSeen(false, false, 0L)
        assertEquals(1f, t.edgeAlpha(10L))
        t.groupSeen(true, false, 1000L)                                // a group formed
        assertEquals(0.5f, t.edgeAlpha(1000L), 1e-4f); assertEquals(1f, t.edgeAlpha(1150L), 1e-4f)
        assertEquals(0.5f, t.edgeAlpha(1300L), 1e-4f); assertEquals(1f, t.edgeAlpha(1450L), 1e-4f)
        assertEquals(1f, t.edgeAlpha(1600L))                           // two pulses, then steady
        t.groupSeen(true, false, 2000L)
        assertEquals(1f, t.edgeAlpha(2150L))                           // still grouped: no new pulse
        val f = SelectTab()
        f.groupSeen(false, false, 0L); f.groupSeen(true, true, 100L)   // formed in a fight: none
        assertEquals(1f, f.edgeAlpha(250L))
        f.homeOpened(true, true, 300L); assertEquals(1f, f.edgeAlpha(450L))
        f.homeOpened(true, false, 500L); assertEquals(1f, f.edgeAlpha(650L), 1e-4f); assertEquals(0.5f, f.edgeAlpha(500L), 1e-4f)
        f.homeOpened(false, false, 2000L); assertEquals(1f, f.edgeAlpha(2000L))
    }
}
