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

    /** the review of 2026-11-11: the game menu open at the pull, then closed in the fight - no lock any more, but the fight stays the
     *  game's (gmFight): no ENTER for a room shown, until the fight ends */
    @Test fun aFightThatBeganInTheGameMenuStaysTheGames() {
        val s = PadOwnerSync()
        s.note(doc("game").put("gmFight", true))
        assertFalse(s.gmLock)
        assertTrue(s.gmFight)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 0).act)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 1000).act)
        s.note(doc("game"))                                                          // the next fight: the reconciler again
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 2000).act)
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

    // combat_nav Step 4 (the user, 2026-11-11: "If I am in combat and press B I should be able to fully navigate everything like normal"):
    // B reaches the room's own chain; at its top level the room goes Home and the reconciler LEAVEs, below it nothing is tapped
    @Test fun theBChainInAFight() {
        val s = PadOwnerSync()
        s.note(doc("room", holder = "map"))
        assertEquals(PadOwnerSync.Act.NONE, s.step(wantsPad = true, nowMs = 0).act)      // B one step back: the room still shows
        assertEquals(PadOwnerSync.Act.LEAVE, s.step(wantsPad = false, nowMs = 100).act)  // B at the top level: Home -> LEAVE
    }

    @Test fun bAtAHeldWindowsTopLevelAsksForOneLeave() {
        val s = PadOwnerSync()
        s.note(doc("room", holder = "npc"))
        assertNull(s.backAsked())
        s.note(doc("room", holder = "npc").put("fightBack", 1))
        assertEquals("idlg" to 24, s.backAsked())
        assertNull(s.backAsked())                                                        // once per step
        s.note(doc("game").put("fightBack", 1).put("npcLeft", true))
        assertNull(s.backAsked())
        assertTrue(s.npcLeft)
        s.note(doc(null))                                                                // the fight ends: the count starts again
        s.note(doc("room", holder = "npc").put("fightBack", 1))
        assertEquals("idlg" to 24, s.backAsked())
    }

    // ---------------------------------------------------------------- B -> HOME FROM EVERY ROOM IN A FIGHT (the user, 2026-11-12: "ALL ROOMS
    // SHOULD ALWAYS GO HOME WITH B EVEN IN COMBAT"; 1429: B in the Bags room did nothing after the pull). Interact i151's doc.fightHome

    /** every room on the bottom screen and the capture id that holds the pad for it (HomeBack.idsOf, the inverse of roomsOf) */
    private val rooms = Room.values().filter { it != Room.HOME }

    @Test fun everyRoomHasACaptureIdTheAskCanName() {
        for (r in rooms) assertTrue("${r.name} has a capture id", HomeBack.idsOf(r.name).isNotEmpty())
    }

    /** the MATRIX: every room x {opened out of combat, then the pull} x {opened in a fight}: sub-screen B steps (no ask), the top level's
     *  B asks Home, Home taps the LEAVE once, the mode goes "game" and the next press on Home needs nothing of ours */
    @Test fun theMatrixEveryRoomBothWaysIn() {
        for (r in rooms) for (who in HomeBack.idsOf(r.name)) for (inFight in listOf(false, true)) {
            val tag = "${r.name}/$who/${if (inFight) "opened in a fight" else "opened before the pull"}"
            val s = PadOwnerSync()
            if (inFight) {
                s.note(doc("game"))                                                       // the room shown in the fight: ENTER
                assertEquals(tag, PadOwnerSync.Act.ENTER, s.step(true, 0).act)
            }
            s.note(doc("room", holder = who))                                            // held (the pull kept it, or the ENTER landed)
            assertEquals(tag, PadOwnerSync.Act.NONE, s.step(true, 1000).act)
            assertNull(tag, s.homeAsked())
            // B inside a sub-screen: the room's own code steps back (no ask): the room still shows and keeps the pad
            s.note(doc("room", holder = who))
            assertNull(tag, s.homeAsked())
            assertEquals(tag, PadOwnerSync.Act.NONE, s.step(true, 1100).act)
            // B at the top level: the ask
            s.note(doc("room", holder = who).put("fightHome", JSONObject().put("n", 1).put("who", who)))
            val asked = s.homeAsked()
            assertEquals(tag, who, asked)
            assertTrue(tag, PadOwnerSync.fightHomeGoes(asked!!, r.name))
            assertNull("$tag: once per ask", s.homeAsked())
            // Home: nothing wants the pad -> ONE LEAVE, then the game's
            assertEquals(tag, PadOwnerSync.Step(PadOwnerSync.Act.LEAVE, "idlg" to 24), s.step(false, 1200))
            assertEquals(tag, PadOwnerSync.Act.NONE, s.step(false, 1300).act)
            s.note(doc("game").put("fightHome", JSONObject().put("n", 1).put("who", who)))
            assertNull(tag, s.homeAsked())
            assertEquals("$tag: the next press on Home is the game's", PadOwnerSync.Act.NONE, s.step(false, 2000).act)
            assertFalse(tag, PadOwnerSync.fightHomeGoes(who, "HOME"))
        }
    }

    @Test fun aLateAskOfARoomLeftBehindNeverPullsTheNewRoomHome() {
        assertFalse(PadOwnerSync.fightHomeGoes("bags", "CHARACTER"))
        assertFalse(PadOwnerSync.fightHomeGoes("pad:social", "BAGS"))
        assertFalse(PadOwnerSync.fightHomeGoes("pad:radial", "BAGS"))                   // a wheel closes itself
        assertFalse(PadOwnerSync.fightHomeGoes("pad:bar", "BAGS"))
        assertFalse(PadOwnerSync.fightHomeGoes("app", "BAGS"))
        assertTrue(PadOwnerSync.fightHomeGoes("spells", "TALENTS"))
        assertTrue(PadOwnerSync.fightHomeGoes("spells", "CONTROLLER"))
    }

    @Test fun theAskOutOfAFightOrReplayedIsNothingAndTheCountStartsAgain() {
        val s = PadOwnerSync()
        s.note(doc(null).put("fightHome", JSONObject().put("n", 1).put("who", "bags")))
        assertNull(s.homeAsked())                                                        // out of a fight: the capture's own release
        s.note(doc("room", holder = "bags").put("fightHome", JSONObject().put("n", 1).put("who", "bags")), current = false)
        assertNull(s.homeAsked())                                                        // a replayed document says nothing about now
        s.note(doc("room", holder = "bags").put("fightHome", JSONObject().put("n", 1).put("who", "bags")))
        assertEquals("bags", s.homeAsked())
        s.note(doc(null))                                                                // the fight ends
        s.note(doc("room", holder = "bags").put("fightHome", JSONObject().put("n", 1).put("who", "bags")))
        assertEquals("bags", s.homeAsked())                                              // the next fight's first ask
    }

    /** Interact i152 (router-heal; the user, 2026-11-12: "I died and then my jump input just stopped working"): out of a fight the router
     *  in room mode with nobody holding (doc.routerMode "room" + doc.routerLeave) gets its LEAVE, TRIES taps RETRY_MS apart, then STUCK once */
    @Test fun outOfAFightRoomModeTapsLeaveOnce() {
        val s = PadOwnerSync()
        val stuck = JSONObject().put("routerMode", "room").put("routerLeave", JSONObject().put("row", "idlg").put("col", 24))
        s.note(stuck)
        assertNull(s.mode)
        assertTrue(s.outRoom)
        assertEquals(PadOwnerSync.Step(PadOwnerSync.Act.LEAVE, "idlg" to 24), s.step(wantsPad = false, nowMs = 0))
        s.note(JSONObject(stuck.toString()))                                          // the next document, still stuck
        assertEquals(PadOwnerSync.Act.NONE, s.step(false, 100).act)                 // the tap's document is still on its way
        assertEquals(PadOwnerSync.Act.LEAVE, s.step(true, 450).act)                 // whatever the bottom screen wants
        assertEquals(PadOwnerSync.Act.LEAVE, s.step(false, 900).act)
        assertEquals(PadOwnerSync.Act.STUCK, s.step(false, 1350).act)               // once
        assertEquals(PadOwnerSync.Act.NONE, s.step(false, 5000).act)
        s.note(JSONObject())                                                         // healed: routerMode nil
        assertFalse(s.outRoom)
        assertEquals(PadOwnerSync.Act.NONE, s.step(false, 6000).act)
        s.note(JSONObject(stuck.toString()))                                         // stuck again later: afresh
        assertEquals(PadOwnerSync.Act.LEAVE, s.step(false, 7000).act)
        // room mode without a leave cell (the pull's pre-lockdown window, or a room holds): nothing
        s.note(JSONObject().put("routerMode", "room"))
        assertFalse(s.outRoom)
        assertEquals(PadOwnerSync.Act.NONE, s.step(false, 8000).act)
        // in a fight the routerMode word is not this one's (fightMode decides)
        s.note(doc("room").put("routerMode", "room").put("routerLeave", JSONObject().put("row", "idlg").put("col", 24)))
        assertFalse(s.outRoom)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 9000).act)
    }

    /** i152: the DEATH pop-up alone (DialogView's only want) of a dead player never asks for an ENTER; anything else still does */
    @Test fun noEnterFromADeadDocument() {
        val s = PadOwnerSync()
        s.note(doc("game").put("dead", true))
        assertTrue(s.dead)
        assertEquals(PadOwnerSync.Act.NONE, s.step(wantsPad = true, nowMs = 0, deathOnly = true).act)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 1000, deathOnly = true).act)
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 2000, deathOnly = false).act)    // a room shown as well: the pad is wanted
    }

    /** review: the real death order - the last document is the in-combat, alive one (no dead field): still no ENTER */
    @Test fun noEnterForTheDeathPopUpFromAStaleAliveDocument() {
        val s = PadOwnerSync()
        s.note(doc("game"))
        assertFalse(s.dead)
        assertEquals(PadOwnerSync.Act.NONE, s.step(wantsPad = true, nowMs = 0, deathOnly = true).act)
        assertEquals(PadOwnerSync.Act.NONE, s.step(true, 1000, deathOnly = true).act)
        assertEquals(PadOwnerSync.Act.ENTER, s.step(true, 2000, deathOnly = false).act)    // something else wants it: ENTER as before
    }

    /** review: room mode already on (a stray ENTER's "app" holder) with only the DEATH pop-up wanting the pad is kept, never a LEAVE */
    @Test fun theDeathPopUpAloneNeverTurnsRoomModeIntoALeave() {
        val s = PadOwnerSync()
        s.note(doc("room", holder = "app").put("dead", true))
        for (t in 0L..2000L step 500L) assertEquals(PadOwnerSync.Act.NONE, s.step(true, t, deathOnly = true).act)
        assertEquals(PadOwnerSync.Act.LEAVE, s.step(false, 3000).act)     // nothing wants it any more: LEAVE as before
    }
}
