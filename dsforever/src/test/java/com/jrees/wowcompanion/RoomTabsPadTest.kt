package com.jrees.wowcompanion

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ROOMS' TABS ON TOP, the pure rules (the user, 2026-11-25: "On all of the rooms that have the tabs on the right side we need to move
 * the test to the top and make them so that L and R shift the tabs left and right"): L / R never reach the game while a tabbed room owns
 * them (PopupHold's room hold, presses only), they step the row with no wrap (RoomTabRow.step), the D-pad's moves between the row and the
 * page (RoomTabRow.mayLand), and when a room owns them (RoomTabsPad).
 */
class RoomTabsPadTest {

    @Test fun aRoomHoldsBothBumpersFromTheGamePressesOnly() {
        for (k in listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)) {
            assertTrue("$k press held from the game", PopupHold.holdsKey(holding = false, keyCode = k, down = true, room = true))
            assertFalse("$k release always passes", PopupHold.holdsKey(holding = false, keyCode = k, down = false, room = true))
            assertFalse("$k with no tabbed room: the game's", PopupHold.holdsKey(holding = false, keyCode = k, down = true, room = false))
        }
        // nothing else of the pad is the room hold's: the room's own capture already has the D-pad and the face buttons
        for (k in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL))
            assertFalse("$k is not a room's", PopupHold.holdsKey(holding = false, keyCode = k, down = true, room = true))
        // the pop-ups' own holds are unchanged by it
        assertTrue(PopupHold.holdsKey(holding = true, keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true, room = false))
        assertTrue(PopupHold.holdsKey(holding = false, keyCode = KeyEvent.KEYCODE_BUTTON_R1, down = true, wheel = true, room = false))
    }

    @Test fun shouldersStepWithoutWrapping() {
        assertNull("L on the first tab: nothing", RoomTabRow.step(0, 3, -1))
        assertEquals(1, RoomTabRow.step(0, 3, 1))
        assertEquals(2, RoomTabRow.step(1, 3, 1))
        assertNull("R on the last tab: nothing", RoomTabRow.step(2, 3, 1))
        assertEquals(1, RoomTabRow.step(2, 3, -1))
        assertNull("one tab: nowhere to go", RoomTabRow.step(0, 1, 1))
        assertNull("no tabs", RoomTabRow.step(0, 0, 1))
    }

    @Test fun theDpadBetweenTheRowAndThePage() {
        // on the row: Left / Right along it only, Up nothing, Down leaves it for the page
        assertTrue(RoomTabRow.mayLand(fromTab = true, toTab = true, dx = 1, dy = 0))
        assertTrue(RoomTabRow.mayLand(fromTab = true, toTab = true, dx = -1, dy = 0))
        assertFalse("Right on the row never drops into the page", RoomTabRow.mayLand(fromTab = true, toTab = false, dx = 1, dy = 0))
        assertFalse("Up on the row: nothing", RoomTabRow.mayLand(fromTab = true, toTab = false, dx = 0, dy = -1))
        assertFalse("Up on the row: nothing", RoomTabRow.mayLand(fromTab = true, toTab = true, dx = 0, dy = -1))
        assertTrue("Down leaves the row", RoomTabRow.mayLand(fromTab = true, toTab = false, dx = 0, dy = 1))
        assertFalse("Down never lands on another tab", RoomTabRow.mayLand(fromTab = true, toTab = true, dx = 0, dy = 1))
        // from the page: only Up reaches the row, and only on the open tab (the centred row's nearest tab would open another page)
        assertTrue(RoomTabRow.mayLand(fromTab = false, toTab = true, dx = 0, dy = -1, toOpen = true))
        assertFalse("Up never lands on a tab that is not open", RoomTabRow.mayLand(fromTab = false, toTab = true, dx = 0, dy = -1, toOpen = false))
        assertFalse(RoomTabRow.mayLand(fromTab = false, toTab = true, dx = 1, dy = 0))
        assertFalse(RoomTabRow.mayLand(fromTab = false, toTab = true, dx = -1, dy = 0))
        assertFalse(RoomTabRow.mayLand(fromTab = false, toTab = true, dx = 0, dy = 1))
        for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
            assertTrue("the page's own moves are untouched", RoomTabRow.mayLand(fromTab = false, toTab = false, dx = dx, dy = dy))
    }

    /** one decision per press: the launcher's key path holds it for the room (the reader acts) or gives it to the game (the relayed copy
     *  acts); with no key path the reader decides by the room's ownership, and then drops the relayed copy */
    @Test fun eachBumperPressIsDecidedOnce() {
        RoomTabsPad.reset()
        assertEquals(DirectPadKeys.LB, RoomTabsPad.keyName(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(DirectPadKeys.RB, RoomTabsPad.keyName(KeyEvent.KEYCODE_BUTTON_R1))
        assertNull(RoomTabsPad.keyName(KeyEvent.KEYCODE_BUTTON_A))
        val rb = DirectPadKeys.RB
        // held for the room: the reader acts even if the ownership flipped under it; the decision is used once
        RoomTabsPad.decide(rb, room = true, now = 1_000L)
        assertTrue("a held press waits for its delivery", RoomTabsPad.heldWaiting(1_100L))
        assertTrue(RoomTabsPad.take(rb, live = false, now = 1_020L))
        assertFalse(RoomTabsPad.heldWaiting(1_100L))
        assertFalse("no relay to drop: the game never had it", RoomTabsPad.relayDropped(rb, 1_200L))
        // the game's: the reader does nothing even if the room owns L / R by then; its relayed copy acts
        RoomTabsPad.decide(rb, room = false, now = 2_000L)
        assertFalse(RoomTabsPad.take(rb, live = true, now = 2_020L))
        assertFalse(RoomTabsPad.relayDropped(rb, 2_200L))
        // no key path: the reader's own look decides; the relayed copy of that press is dropped (it would act twice)
        assertTrue(RoomTabsPad.take(rb, live = true, now = 3_000L))
        assertTrue(RoomTabsPad.relayDropped(rb, 3_200L))
        assertFalse("a later relay is another press", RoomTabsPad.relayDropped(rb, 3_000L + RoomTabsPad.RELAY_MS + 1))
        // a stale decision (its delivery lost) never applies to a later press
        RoomTabsPad.decide(rb, room = true, now = 4_000L)
        assertFalse(RoomTabsPad.heldWaiting(4_000L + RoomTabsPad.DECIDED_MS + 1))
        assertFalse(RoomTabsPad.take(rb, live = false, now = 4_000L + RoomTabsPad.DECIDED_MS + 1))
        RoomTabsPad.reset()
    }

    @Test fun whereLAndRLeaveTheCursor() {
        assertEquals("from the row: on the new tab", 2, RoomTabRow.lands(onRow = true, to = 2))
        assertNull("from the page: the new page's own start", RoomTabRow.lands(onRow = false, to = 2))
    }

    @Test fun theFourTabbedRoomsOwnLAndRWhileTheyHoldThePad() {
        assertEquals("character", RoomTabsPad.idOf("CHARACTER"))
        assertEquals("groupfinder", RoomTabsPad.idOf("GROUPFINDER"))
        assertEquals("professions", RoomTabsPad.idOf("PROFESSIONS"))
        assertEquals("social", RoomTabsPad.idOf("SOCIAL"))
        // the rooms whose tabs were on top already with no L / R: the same now (the Spells guest's book and schools, the Macros grid)
        assertEquals("spells", RoomTabsPad.idOf("SPELLBOOK"))
        assertEquals("spells", RoomTabsPad.idOf("TALENTS"))
        assertEquals("macros", RoomTabsPad.idOf("MACROS"))
        // rooms whose L / R already do something else keep them (Map's zoom, Quests' / Settings' / Chat's own, the Controller room's
        // PadTabCtl), and the rooms with no tabs
        for (r in listOf("HOME", "MAP", "QUESTS", "CONTROLLER", "BAGS", "CHAT", "CALENDAR", "SETTINGS"))
            assertNull(r, RoomTabsPad.idOf(r))
        assertFalse("the room is not where its tabs show (the Bind box, a builder, the Apply box)",
            RoomTabsPad.live(shown = true, holder = true, roomMode = false, covered = false, free = false))
        assertTrue(RoomTabsPad.live(shown = true, holder = true, roomMode = false, covered = false))
        assertTrue("a fight's room mode: the router hands us every press", RoomTabsPad.live(shown = true, holder = false, roomMode = true, covered = false))
        assertFalse("still waiting for the hold: the game would act on the bumper itself",
            RoomTabsPad.live(shown = true, holder = false, roomMode = false, covered = false))
        assertFalse("an NPC window / a wheel over the room reads the pad itself", RoomTabsPad.live(shown = true, holder = true, roomMode = false, covered = true))
        assertFalse(RoomTabsPad.live(shown = false, holder = true, roomMode = true, covered = false))
    }

    /** int/1533: hold to cast's fire at its threshold is off while a tabbed room owns the pad (HoldCast.gates via SetupBridge.roomHoldsPad) */
    @Test fun holdToCastsFireIsOffWhileATabbedRoomOwnsThePad() {
        for (r in listOf("CHARACTER", "GROUPFINDER", "PROFESSIONS", "SOCIAL")) {
            assertTrue("$r shows, the capture not named yet: off", RoomTabsPad.blocksHoldFire(r, holder = false, roomMode = false))
            assertTrue("$r holding: off", RoomTabsPad.blocksHoldFire(r, holder = true, roomMode = false))
            assertTrue("$r in a fight's room mode: off", RoomTabsPad.blocksHoldFire(r, holder = false, roomMode = true))
        }
        for (r in listOf("SPELLBOOK", "TALENTS", "MACROS")) {
            assertTrue("$r holding: off", RoomTabsPad.blocksHoldFire(r, holder = true, roomMode = false))
            assertTrue("$r in a fight's room mode: off", RoomTabsPad.blocksHoldFire(r, holder = false, roomMode = true))
            assertFalse("$r not holding: the gate stays as it was", RoomTabsPad.blocksHoldFire(r, holder = false, roomMode = false))
        }
        // Home and the rooms that are not the rooms' tabs' keep the gates they had
        for (r in listOf("HOME", "MAP", "QUESTS", "CONTROLLER", "BAGS", "CHAT", "CALENDAR", "SETTINGS"))
            assertFalse(r, RoomTabsPad.blocksHoldFire(r, holder = true, roomMode = true))
        // the gate is wired: HoldCast.gates asks SetupBridge.roomHoldsPad, the host answers with blocksHoldFire (weakly)
        val dir = listOf(java.io.File("../companion"), java.io.File("companion")).first { it.isDirectory }
        val src = { n: String -> java.io.File(dir, "app/src/main/java/com/jrees/wowcompanion/$n").readText() }
        assertTrue(src("HoldCast.kt").substringAfter("var gates: () -> Boolean = {").substringBefore("\n    }").contains("SetupBridge.roomHoldsPad()"))
        val host = src("CompanionHost.kt")
        assertTrue(host.contains("SetupBridge.roomHoldsPad = { me.get()?.let"))
        assertTrue(host.substringAfter("private fun roomHoldsPad(): Boolean {").substringBefore("\n    }").contains("RoomTabsPad.blocksHoldFire("))
    }
}
