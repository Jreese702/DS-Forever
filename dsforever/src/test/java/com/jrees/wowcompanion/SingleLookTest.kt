package com.jrees.wowcompanion

import com.jrees.wowcompanion.SingleLook.Frame
import com.jrees.wowcompanion.SingleLook.In
import com.jrees.wowcompanion.SingleLook.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SINGLE SCREEN MODE: WHAT THE TOP SCREEN SHOWS (SingleLook; the user's picks 2026-11-25 "Centred panel", "Straight to the game", "Ours
 * over the game"): the table row by row, first match wins; the PLAY state (nothing of ours, every touch the game's) and its way to a
 * panel and back; Home's glance (sticky, the wheel's hub) against Home's sub-states (derived: they end with what opened them).
 */
class SingleLookTest {
    private val play = In()

    @Test fun playIsTheGameAlone() {
        val l = SingleLook.of(play)
        assertEquals(Frame.HIDDEN, l.frame); assertEquals(0f, l.dim); assertFalse(l.touchable); assertEquals(Root.GONE, l.root)
        assertTrue("the docks (PLAY) show while playing", l.docks); assertNull(l.strip); assertTrue(l.play)
    }

    @Test fun row1TheLaunchSwirlCoversEverything() {
        val l = SingleLook.of(play.copy(launchCover = true, room = "BAGS", pause = true, npc = true, startPages = true))
        assertEquals(Frame.HIDDEN, l.frame); assertFalse(l.docks); assertEquals(Root.GONE, l.root); assertFalse(l.play)
    }

    @Test fun row2StartPagesReportNpcDialogSpellsAreAPanel() {
        for (i in listOf(play.copy(startPages = true), play.copy(report = true), play.copy(npc = true), play.copy(dialog = true),
            play.copy(spells = true))) {
            val l = SingleLook.of(i)
            assertEquals("$i", Frame.FULL, l.frame); assertEquals(SingleLook.DIM_PANEL, l.dim); assertTrue(l.touchable); assertFalse(l.docks)
            assertEquals("over Home's rest: nothing under it", Root.GONE, l.root)
        }
        // over a room: the room stays under it; over Home's glance: Home
        assertEquals(Root.ROOM, SingleLook.of(play.copy(npc = true, room = "BAGS")).root)
        assertEquals(Root.HOME, SingleLook.of(play.copy(npc = true, glanceHub = true)).root)
        // the fight's rolls are no panel (row 9)
        assertEquals(Frame.HIDDEN, SingleLook.of(play.copy(dialog = true, fightRolls = true)).frame)
    }

    @Test fun row3ThePauseWheel() {
        val l = SingleLook.of(play.copy(pause = true))
        assertEquals(Frame.FULL, l.frame); assertEquals(SingleLook.DIM_PANEL, l.dim); assertTrue(l.touchable); assertEquals(Root.GONE, l.root)
        assertEquals(Root.ROOM, SingleLook.of(play.copy(pause = true, room = "MAP")).root)          // Start in a room: the room under it
        assertEquals(Root.HOME, SingleLook.of(play.copy(pause = true, glanceHub = true)).root)
    }

    @Test fun row4EveryRoomIsTheCentredPanel() {
        for (r in listOf("MAP", "QUESTS", "SPELLBOOK", "TALENTS", "PROFESSIONS", "GROUPFINDER", "CHARACTER", "BAGS", "CHAT", "CONTROLLER",
            "SOCIAL", "CALENDAR", "MACROS", "SETTINGS")) {
            val l = SingleLook.of(play.copy(room = r))
            assertEquals(r, Frame.FULL, l.frame); assertEquals(SingleLook.DIM_PANEL, l.dim); assertTrue(l.touchable)
            assertEquals(Root.ROOM, l.root); assertFalse(l.docks)
        }
        // a room wins over a quick wheel left from Home and over the LB picture
        assertEquals(Root.ROOM, SingleLook.of(play.copy(room = "BAGS", quick = true, lb = true)).root)
    }

    @Test fun row5HomesGlanceAndItsSubStates() {
        val g = SingleLook.of(play.copy(glanceHub = true))
        assertEquals(Frame.FULL, g.frame); assertEquals(Root.HOME, g.root); assertTrue(g.touchable)
        val s = SingleLook.of(play.copy(homeSub = true))
        assertEquals(g, s)
    }

    @Test fun rows6To10TheNpcDefaults() {
        val q = SingleLook.of(play.copy(quick = true))
        assertEquals(Frame.FULL, q.frame); assertEquals(SingleLook.DIM_QUICK, q.dim); assertEquals(Root.GONE, q.root); assertTrue(q.touchable)
        val c = SingleLook.of(play.copy(classWheel = true))
        assertEquals(Root.HOME_LAYERS, c.root); assertEquals(SingleLook.DIM_QUICK, c.dim)
        for (t in listOf(0, 1)) {
            val l = SingleLook.of(play.copy(tool = t))
            assertEquals(Frame.HIDDEN, l.frame); assertEquals("tool", l.strip); assertFalse(l.docks)
        }
        val r = SingleLook.of(play.copy(fightRolls = true))
        assertEquals(Frame.HIDDEN, r.frame); assertEquals("rolls", r.strip); assertTrue(r.docks); assertFalse(r.play)
        val lb = SingleLook.of(play.copy(lb = true))
        assertEquals(Frame.FULL, lb.frame); assertEquals(0f, lb.dim); assertFalse("display only", lb.touchable); assertTrue(lb.docks)
    }

    @Test fun firstMatchWins() {
        // the pause wheel over a quick wheel's leftovers, an NPC window over the pause wheel
        assertEquals(SingleLook.DIM_PANEL, SingleLook.of(play.copy(pause = true, quick = true)).dim)
        assertTrue(SingleLook.of(play.copy(npc = true, pause = true, tool = 1)).touchable)
        assertNull(SingleLook.of(play.copy(npc = true, tool = 1)).strip)
        // the glance over a tool: the glance
        assertEquals(Frame.FULL, SingleLook.of(play.copy(glanceHub = true, tool = 0)).frame)
    }

    @Test fun playToPanelAndBackIsTheSameLook() {
        // B in a room = straight to the game: the room's look, then Home with nothing open = PLAY again (no step through Home's page)
        val inRoom = SingleLook.of(play.copy(room = "BAGS"))
        val back = SingleLook.of(play.copy(room = "HOME"))
        assertEquals(Frame.FULL, inRoom.frame)
        assertEquals(SingleLook.PLAY, back)
        // Home's glance stays while it is up (sticky), a sub-state ends with what opened it (derived): both back to PLAY when cleared
        assertEquals(SingleLook.PLAY, SingleLook.of(play.copy(glanceHub = false, homeSub = false)))
    }
}
