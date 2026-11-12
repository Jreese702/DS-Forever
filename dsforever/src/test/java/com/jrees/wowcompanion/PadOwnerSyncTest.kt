package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** combat_nav_design Step 3 (the user, 2026-11-11: "If I am in combat and press B I should be able to fully navigate everything like
 *  normal"): the app keeps the pad where the bottom screen wants it in a fight, following only Interact's fightMode */
class PadOwnerSyncTest {
    private fun doc(mode: String?, enterCol: Int = 25, leaveCol: Int? = 24, gm: Boolean = false, holder: String? = null) = JSONObject().apply {
        if (mode != null) put("fightMode", mode)
        put("fightEnter", JSONObject().put("row", "idlg").put("col", enterCol))
        if (leaveCol != null) put("fightLeave", JSONObject().put("row", "idlg").put("col", leaveCol))
        if (gm) put("gmLock", true)
        if (holder != null) put("holder", holder)
    }

    @Test fun theStateTable() {
        val s = PadOwnerSync()
        s.note(doc("game"))
        assertEquals(PadOwnerSync.Step(PadOwnerSync.Act.ENTER, "idlg" to 25), s.step(wantsPad = true, nowMs = 0))
        s.note(doc("room"))
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 100).act)                 // a room shown, the mode room: in step
        assertEquals(PadOwnerSync.Step(PadOwnerSync.Act.LEAVE, "idlg" to 24), s.step(false, 200))   // Home: LEAVE
        s.note(doc("game"))
        assertEquals(PadOwnerSync.Act.NONE, s.step(false, 300).act)                // Home, the mode game: in step
    }

    @Test fun outOfAFightAndUnderTheGameMenuItDoesNothing() {
        val s = PadOwnerSync()
        s.note(doc(null))
        assertNull(s.mode)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 0).act)
        s.note(doc("game", gm = true))
        assertTrue(s.gmLock)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 0).act)                   // the user's decision 1: the game menu has the pad
    }

    @Test fun threeTriesThenStuckOnce() {
        val s = PadOwnerSync()
        s.note(doc("game"))
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 0).act)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 100).act)                 // the tap's document is still on its way
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 450).act)
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 900).act)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 1000).act)
        assertEquals(PadOwnerSync.Act.STUCK, s.step(true, 1350).act)               // the red cue and a log line, once
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 5000).act)
        // a change of want starts afresh
        assertEquals(PadOwnerSync.Act.NONE, s.step(false, 5100).act)
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 5200).act)
    }

    @Test fun aReplayedDocumentSaysNothingAboutNow() {
        val s = PadOwnerSync()
        s.note(doc("game"))
        s.note(doc("room"), current = false)                                        // a stale (replayed) document
        assertEquals("game", s.mode)
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 0).act)
    }

    @Test fun theCellMovesWithTheDocument() {
        val s = PadOwnerSync()
        s.note(doc("game", enterCol = 25))
        assertEquals("idlg" to 25, s.step(true, 0).cell)
        s.note(doc("game", enterCol = 31))
        assertEquals("idlg" to 31, s.step(true, 500).cell)
        // no leave cell published: nothing to tap (never a guess)
        val t = PadOwnerSync()
        t.note(doc("room", leaveCol = null))
        assertEquals(PadOwnerSync.Act.NONE, t.step(false, 0).act)
    }

    @Test fun theDirectPadGate() {
        assertTrue(PadOwnerSync.directLive(shown = true, holderMatch = true, roomMode = false))
        assertTrue(PadOwnerSync.directLive(shown = true, holderMatch = false, roomMode = true))   // the swap / the "app" holder
        assertFalse(PadOwnerSync.directLive(shown = true, holderMatch = false, roomMode = false))
        assertFalse(PadOwnerSync.directLive(shown = false, holderMatch = true, roomMode = true))
    }

    @Test fun noDoublePressOnLoot() {
        assertTrue(PadOwnerSync.appReadsLoot(fightNav = true, fightMode = "game"))
        assertFalse(PadOwnerSync.appReadsLoot(fightNav = true, fightMode = "room"))          // the add-on's N.move has it
        assertFalse(PadOwnerSync.appReadsLoot(fightNav = false, fightMode = "game"))
    }
}
