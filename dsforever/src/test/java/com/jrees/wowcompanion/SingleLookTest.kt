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

    /** the 1535 polish (the user on test 1533, 2026-11-26: "When looting the screen dims but thats excessive"): the loot window alone
     *  over the game leaves it undimmed (still the panel's window: its touches ours); anything else open with it dims as before */
    @Test fun lootingLeavesTheGameUndimmed() {
        val l = SingleLook.of(play.copy(npc = true, loot = true))
        assertEquals(Frame.FULL, l.frame); assertEquals(SingleLook.DIM_LOOT, l.dim); assertEquals(0f, l.dim); assertTrue(l.touchable)
        assertEquals("a vendor still dims", SingleLook.DIM_PANEL, SingleLook.of(play.copy(npc = true)).dim)
        for (i in listOf(play.copy(npc = true, loot = true, room = "BAGS"), play.copy(npc = true, loot = true, glanceHub = true),
            play.copy(npc = true, loot = true, dialog = true), play.copy(npc = true, loot = true, startPages = true),
            play.copy(npc = true, loot = true, pause = true)))
            assertEquals("$i", SingleLook.DIM_PANEL, SingleLook.of(i).dim)
        val h = listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt"),
            java.io.File("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")).first { it.canRead() }.readText()
        assertTrue("the host tells the loot window apart", h.contains("loot = ::interactView.isInitialized && interactView.visibility == View.VISIBLE && interactView.looting(),"))
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
        // (NPC's refinement: the LB picture in the strip window over the left dock, no window over the whole screen - SingleLookNpcTest)
        val lb = SingleLook.of(play.copy(lb = true))
        assertEquals(Frame.HIDDEN, lb.frame); assertEquals(0f, lb.dim); assertFalse("display only", lb.touchable); assertTrue(lb.docks)
        assertEquals("lb", lb.strip)
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

    /**
     * THE REVIEW (2026-11-25, "Single mode puts DS Forever's opaque waiting page over the game's login and character select"): with the
     * game screen up, the waiting page (a logout to character select, a disconnect to the login screen, a reload, "Loading your
     * character") is no start page of the host's: it is [In.waiting], and its Menu opened is the start page again (the Menu needs the page)
     */
    @Test fun theInGameWaitingPageIsItsRowOverTheGame() {
        val l = SingleLook.of(play.copy(waiting = true))
        assertEquals("the game's login screen / character select stay seen and touched", Frame.HIDDEN, l.frame)
        assertFalse(l.touchable); assertFalse("no docks over character select", l.docks); assertEquals(Root.GONE, l.root)
        assertEquals("its row in the strip", SingleNpc.START, l.strip); assertFalse(l.play)
        assertEquals("over a room too (it covers the rooms on two screens)", SingleNpc.START, SingleLook.of(play.copy(waiting = true, room = "BAGS")).strip)
        assertEquals("the Menu, setup, the chooser, the sign-in form: the panel", Frame.FULL, SingleLook.of(play.copy(waiting = true, startPages = true)).frame)
        assertEquals("the report over it: the panel", Frame.FULL, SingleLook.of(play.copy(waiting = true, report = true)).frame)
        val c = SingleLook.of(play.copy(waiting = true, launchCover = true))
        assertEquals("the launch swirl still covers", Frame.HIDDEN, c.frame); assertNull(c.strip)
        assertEquals("a tap beside it is nothing", SingleNpc.Dim.KEEP, SingleNpc.dimTap(play.copy(waiting = true)))
    }

    /**
     * THE USER'S RULING (2026-11-25, after build 1533) on where single mode's waiting row goes, verbatim: "Top of the screen (Recommended):
     * Keeps character select's Enter World and the bottom of the login screen free for touch." The strip is the centred box's width
     * (the 1240 panel's, x 340..1580 on the Thor) from the top edge down, only as high as what the row shows now - the line's row, the
     * sign-in row (or the add-ons' How) under it, the region line under those - so no empty strip over the game takes its touches; in
     * StartView's own px, never scaled down, never past the screen
     */
    @Test fun theWaitingRowSitsAtTheTopOfTheScreen() {
        val all = SingleNpc.startNeed(second = true, region = true)
        val r = SingleNpc.startRect(1920, 1080, all)
        assertEquals("the box's width, from the top edge", listOf(340, 0, 1580, all), r.toList())
        assertEquals("the line's row, the sign-in row, the region line: 334 px", 334, all)
        assertTrue("the whole row in the screen's top third: Enter World and the login screen's foot stay the game's", r[3] <= 1080 / 3)
        assertEquals("the line's row alone", 151, SingleNpc.startNeed(second = false, region = false))
        assertEquals("the sign-in row under it", 286, SingleNpc.startNeed(second = true, region = false))
        assertEquals("the region line under the line's row", 199, SingleNpc.startNeed(second = false, region = true))
        assertEquals("the line's row from the top edge kept free", 24, SingleNpc.START_TOP)
        val small = SingleNpc.startRect(1280, 720, all)
        assertEquals("a smaller screen: still the top edge", 0, small[1])
        assertEquals("never scaled down (StartView's own px)", all, small[3])
        assertTrue("centred (the box's own rounding: a px)", Math.abs(1280 - (small[0] + small[2])) <= 1)
        assertEquals("never past the screen", 200, SingleNpc.startRect(1920, 200, all)[3])
    }

    /** the host places the strip by what the row shows now, and again whenever that changes (a sign-in row coming, the region line) */
    @Test fun theHostFitsTheTopStripToTheRow() {
        val h = listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt"),
            java.io.File("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")).first { it.canRead() }.readText()
        val place = h.substringAfter("private fun stripPlace() {").substringBefore("\n    }")
        assertTrue("the rect fits the row", place.contains("SingleNpc.START -> SingleNpc.startRect(w, h, startView.bareNeed())"))
        assertTrue("a change of the row places it again", h.contains("startView.onBareNeed = { if (stripKind == SingleNpc.START) stripPlace() }"))
    }

    /**
     * THE int/1533 REVIEW (input focus): a field typing (Bags' search) when a pull lets go of the room (PLAY, HIDDEN) kept the invisible
     * window focusable over the whole screen - every pad press of the fight, B too, went to it, never to the game. A HIDDEN look can type
     * in nothing (its content is not drawn; the strips are their own windows), so the top window gives the keys back first; every FULL
     * look (a room, an NPC window, the report, the start pages) keeps a field typing
     */
    @Test fun aHiddenLookGivesATypingFieldsKeysBack() {
        assertFalse(SingleLook.canType(SingleLook.PLAY)); assertFalse(SingleLook.canType(SingleLook.COVERED))
        for (i in listOf(play.copy(tool = 1), play.copy(lb = true), play.copy(fightRolls = true), play.copy(waiting = true)))
            assertFalse("$i", SingleLook.canType(SingleLook.of(i)))
        for (i in listOf(play.copy(room = "BAGS"), play.copy(room = "PROFESSIONS"), play.copy(npc = true), play.copy(report = true),
            play.copy(startPages = true), play.copy(pause = true, room = "BAGS"), play.copy(glanceHub = true), play.copy(quick = true)))
            assertTrue("$i", SingleLook.canType(SingleLook.of(i)))
        val tw = listOf(java.io.File("src/main/java/com/jrees/wowcompanion/fork/TopWindow.kt"),
            java.io.File("dsforever/src/main/java/com/jrees/wowcompanion/fork/TopWindow.kt")).first { it.isFile }.readText()
        val look = tw.substringAfter("override fun look(l: SingleLook.Look) {").substringBefore("override fun sub(")
        val give = look.indexOf("if (typer.typing && !pageSlot.up && !SingleLook.canType(l)) { typer.focusable(false); return }")
        assertTrue("the keys back before the decor is laid", give in 0 until look.indexOf("showDecor(full || pageSlot.up || typer.typing)"))
        assertTrue("the typer's hook lays the look again", tw.contains("else lookNow?.let { l -> lookNow = null; look(l) } }"))
    }

    @Test fun theHostCountsTheWaitingPageApartOnceTheGameIsUp() {
        val h = listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt"),
            java.io.File("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")).first { it.canRead() }.readText()
        val w = h.substringAfter("private fun singleWaiting(): Boolean =").substringBefore("\n")
        assertTrue("the game screen up, the page shown, its Menu shut", w.contains("startView.visibility == View.VISIBLE && LauncherHooks.gameUp() && !startView.menuOpen"))
        val i = h.substringAfter("private fun singleIn(): SingleLook.In = singleNpcIn(SingleLook.In(").substringBefore("\n    ))")
        assertTrue("a start page only before the game (or its Menu)", i.contains("startPages = (startView.visibility == View.VISIBLE && !singleWaiting()) ||"))
        assertTrue(i.contains("waiting = singleWaiting(),"))
    }
}
