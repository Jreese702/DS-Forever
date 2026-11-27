package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE LB PARTY PICTURE'S D-PAD HINTS (a player's report: the picture shows a D-pad direction by each party member, but on their
 * setup LB + D-pad Up / Down cycles through the party; only LB + A for yourself is a real button). Off-device.
 *
 * The cause: LbMap kept its made-up cross (Up party1, Right party2, Down party3, Left party4) whenever the game's own bindings
 * named nobody under LB, so the picture showed shortcuts that do not exist. Now, once the game's lbmap document has come, the
 * D-pad glyph shows only for a member the game really binds; with no party binding at all the members keep their places without a
 * direction (no glyph, no press light); you keep A (the game's own LB + A). No health or mana in the picture (m129, the user's
 * ruling 2026-11-11, and 2026-11-27 for this report).
 */
class LbPartyHintsTest {

    private fun doc(s: String) = LbMap.update(JSONObject(s))

    @Test fun noPartyBindingUnderLbShowsNoDpadShortcuts() {
        doc("""{"type":"lbmap","v":"m208","keys":{},"swap":false,"fb":"LB"}""")
        for (k in listOf("PADDUP", "PADDRIGHT", "PADDDOWN", "PADDLEFT")) {
            assertNull(k, LbMap.unitOf(k))                              // a press lights no one: no such shortcut
            assertFalse(k, LbMap.slot(k).glyph)                        // and no direction glyph by the name
        }
        for (u in listOf("party1", "party2", "party3", "party4")) assertNull(u, LbMap.keyOf(u))
        assertFalse(LbMap.partyBound)
    }

    @Test fun theMembersKeepTheirPlacesWithoutADirection() {
        doc("""{"type":"lbmap","v":"m208","keys":{},"swap":false,"fb":"LB"}""")
        assertEquals("party1", LbMap.slot("PADDUP").unit)
        assertEquals("party2", LbMap.slot("PADDRIGHT").unit)
        assertEquals("party3", LbMap.slot("PADDDOWN").unit)
        assertEquals("party4", LbMap.slot("PADDLEFT").unit)
    }

    @Test fun youKeepAEvenWithNoBindingRead() {
        doc("""{"type":"lbmap","v":"m208","keys":{},"swap":false,"fb":"LB"}""")
        assertEquals("player", LbMap.unitOf("PAD1"))
        assertEquals("PAD1", LbMap.keyOf("player"))
    }

    @Test fun aRealBindingStillShowsItsGlyph() {
        doc("""{"type":"lbmap","v":"m208","mod":"CTRL","self":"PAD1","p1":"PADDUP","p3":"PADDDOWN"}""")
        assertTrue(LbMap.partyBound)
        assertEquals(LbMap.Slot("party1", true), LbMap.slot("PADDUP"))
        assertEquals(LbMap.Slot("party3", true), LbMap.slot("PADDDOWN"))
        assertEquals("party1", LbMap.unitOf("PADDUP"))
        // a direction the game does not bind while others are: "Not bound" there, its glyph kept (as before)
        assertEquals(LbMap.Slot(null, true), LbMap.slot("PADDRIGHT"))
    }

    @Test fun thePictureDrawsNoHealth() {
        val src = java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion/LbPartyView.kt").let { if (it.exists()) it else
            java.io.File("companion/app/src/main/java/com/jrees/wowcompanion/LbPartyView.kt") }.readText()
        assertFalse(src.contains("m.hp") || src.contains("hpPct") || src.contains("health("))
    }
}
