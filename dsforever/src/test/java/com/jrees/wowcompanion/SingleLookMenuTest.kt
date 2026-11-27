package com.jrees.wowcompanion

import com.jrees.wowcompanion.SingleLook.Frame
import com.jrees.wowcompanion.SingleLook.In
import com.jrees.wowcompanion.SingleLook.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DS MENU IN SINGLELOOK (the 1536 pass 1): the row alone over the dimmed game (row 5b), every room on the menu's stage (never the
 * centred panel again), Home's stage only when the row's swirl opened it, the row's drop (5c: no dim, no touch - the game's at once),
 * a window of the game over a room keeps the room on the stage, and PLAY unchanged.
 */
class SingleLookMenuTest {
    private val play = In()

    @Test fun theRowAloneIsTheMenuOverTheDimmedGame() {
        val l = SingleLook.of(play.copy(menuRow = true))
        assertEquals(Frame.FULL, l.frame); assertEquals(SingleLook.DIM_PANEL, l.dim); assertTrue(l.touchable)
        assertEquals("nothing of the rooms column: the row is the menu's", Root.GONE, l.root)
        assertTrue(l.menu); assertFalse("the docks give way", l.docks)
    }

    @Test fun everyRoomStandsOnTheStage() {
        for (r in Room.values().filter { it != Room.HOME }) {
            val l = SingleLook.of(play.copy(room = r.name))
            assertEquals(Root.ROOM, l.root); assertTrue("${r.name} on the stage", l.menu)
        }
        // a window of the game over a room (an NPC, the pause wheel, a dialog): the room stays on the stage under it
        assertTrue(SingleLook.of(play.copy(room = "BAGS", npc = true)).menu)
        assertTrue(SingleLook.of(play.copy(room = "BAGS", pause = true)).menu)
        assertTrue(SingleLook.of(play.copy(room = "BAGS", dialog = true)).menu)
        // the start pages and the report are no room's
        assertFalse(SingleLook.of(play.copy(room = "BAGS", startPages = true)).menu)
        // the layout editor over Settings keeps its own look (the NPC builder's)
        assertFalse(SingleLook.of(play.copy(room = "SETTINGS", layoutEdit = true)).menu)
    }

    @Test fun homesStageOnlyFromTheRowsSwirl() {
        val fromRow = SingleLook.of(play.copy(glanceHub = true, menuHome = true))
        assertEquals(Root.HOME, fromRow.root); assertTrue("Home's stage", fromRow.menu)
        val fromWheel = SingleLook.of(play.copy(glanceHub = true))
        assertEquals(Root.HOME, fromWheel.root); assertFalse("the pause wheel's hub: Home's glance as before", fromWheel.menu)
        // a Home sub-state over Home's stage keeps the stage
        assertTrue(SingleLook.of(play.copy(glanceHub = true, menuHome = true, homeSub = true)).menu)
    }

    @Test fun theRowDropsWithNoDimAndNoTouchThenPlay() {
        val l = SingleLook.of(play.copy(menuDrop = true))
        assertEquals(Frame.FULL, l.frame); assertEquals(0f, l.dim); assertFalse("the game's touches at once", l.touchable)
        assertTrue(l.menu); assertEquals(Root.GONE, l.root)
        assertTrue("after the drop: the game alone", SingleLook.of(play).play)
        assertFalse(SingleLook.of(play).menu)
        // the row up again during a drop: the row wins
        assertEquals(SingleLook.DIM_PANEL, SingleLook.of(play.copy(menuRow = true, menuDrop = true)).dim)
    }

    @Test fun theLaunchCoverAndTheStartPagesStillWin() {
        assertEquals(Frame.HIDDEN, SingleLook.of(play.copy(menuRow = true, launchCover = true)).frame)
        assertFalse(SingleLook.of(play.copy(menuRow = true, startPages = true)).menu)
    }
}
