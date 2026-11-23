package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ROOM BAR IN EDIT MODE (SPEC.txt sections 4, 8; test build 1518 build 2, the user's ruling 2026-11-21: "just have the individual
 * icons wiggle like their own apps with buttons around them like apps that can move"): the bar is no widget - no menu, no tag. The cursor
 * reaches the rooms (Down from the map), Left / Right walk them; A lifts a room, Left / Right carry it (EDIT_STEP, across the disc too),
 * A drops, B puts the order back; X hides the room (Settings never: a red pulse); hidden rooms wait in a "+" spot at the bar's end whose
 * A opens a small tray (A puts one back where it was); a finger taps (the cursor only), drags (the others make way after the 80 ms
 * hover), taps a badge (hides) or "+" (the tray). Pure.
 */
class HomeEditRoomBarTest {
    private val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
    private fun model(saved: HomeLayout = classic, start: EditStop = EditStop.CHAT) =
        HomeEditModel(saved, start)

    /** the bar's slots as the Strip lays them (1240 wide) for [n] visible rooms (+ the "+" cell when [plus]): left of the 128 px disc
     *  leftCount of the cells, the rest right of it; only the rooms' centres (the "+" cell is no slot) */
    private fun slots(n: Int = 12, plus: Boolean = false): List<Float> {
        val cells = n + if (plus) 1 else 0
        val half = RoomBar.leftCount(cells)
        val homeX = 620f; val l0 = 8f; val l1 = homeX - 64f - 14f; val r0 = homeX + 64f + 14f; val r1 = 1232f
        val cl = (l1 - l0) / half; val cr = (r1 - r0) / (cells - half)
        return ((0 until half).map { l0 + cl * it + cl / 2f } + (0 until cells - half).map { r0 + cr * it + cr / 2f }).take(n)
    }
    private fun slotBox(s: List<Float>, i: Int) = HomeLayoutSolver.Box(s[i] - 40f, 990f, s[i] + 40f, 1070f)

    private fun stops(l: HomeLayout): Map<EditStop, HomeLayoutSolver.Box> {
        val b = HomeLayoutSolver.places(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f)).boxes
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        out[EditStop.CHAT] = b.getValue(HomePart.CHAT)
        out[EditStop.MAP] = b.getValue(HomePart.MAP)
        b[HomePart.QUESTS]?.let { out[EditStop.QUEST] = it }
        b[HomePart.STRIP]?.let { out[EditStop.CAST] = it }
        out[EditStop.ROOMS] = HomeLayoutSolver.Box(8f, 935f + Strip.TRACKS, 1232f, 935f + Strip.BAR)
        return out
    }
    private fun geo(m: HomeEditModel) = object : HomeEditModel.Geo {
        override fun columns(l: HomeLayout) = HomeLayoutSolver.columns(l, 1240f, 8f)
        override fun valid(l: HomeLayout) = true
        override val w = 1240f
        override fun barSlots() = slots(m.barRooms.size, m.barPlus)
    }
    private fun HomeEditModel.press(vararg keys: String): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, stops(draft)) { s -> HomeWidgetMenus.rows(s, draft, HomeWidgetMenus.Values()) }
        return out
    }

    @Test fun theCursorReachesTheBarAndWalksItsRooms() {
        val m = model(start = EditStop.MAP)
        m.press("PADDDOWN")
        assertEquals("Down from the map: the room bar", EditStop.ROOMS, m.cursor)
        assertEquals(0, m.barSel)
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT")
        assertEquals("across the disc: Bags", "BAGS", m.barRoom)
        repeat(20) { m.press("PADDRIGHT") }
        assertEquals("the right end holds (no \"+\" while nothing is hidden)", RoomBar.DEFAULT.size - 1, m.barSel)
        assertFalse(m.barPlus); assertFalse(m.barOnPlus)
        m.press("PADDUP")
        assertTrue("Up: back into Home (1518: the ribbon has no stop)", m.cursor == EditStop.CAST || m.cursor == EditStop.MAP || m.cursor == EditStop.CHAT)
        m.press("PADDDOWN")
        if (m.cursor != EditStop.ROOMS) m.press("PADDDOWN")
        assertEquals(EditStop.ROOMS, m.cursor)
        assertTrue("nothing moved", !m.changed)
    }

    @Test fun aLiftsLeftRightCarryADropsBPutsBack() {
        val m = model(start = EditStop.ROOMS)
        repeat(7) { m.press("PADDRIGHT") }                                     // Bags
        val fx = m.press("PAD1")
        assertEquals(EditStop.ROOMS, m.lifted)
        assertEquals("BAGS", m.barLifted)
        assertTrue(fx.contains(HomeEditModel.Fx.Lift))
        // Left x2: Bags over the disc into the left half; each step a 120 ms glide (EDIT_STEP)
        val step = m.press("PADDLEFT")
        assertEquals(listOf(HomeEditModel.Fx.Relay(HomeEditModel.EDIT_STEP)), step)
        m.press("PADDLEFT")
        assertEquals("BAGS", m.draft.rooms[5])
        assertEquals("the cursor rides with it", 5, m.barSel)
        assertTrue(RoomBar.position(Room.BAGS, m.draft.rooms) < RoomBar.position(Room.HOME, m.draft.rooms))
        // Y has no size here; Up / Down do nothing while carried
        assertTrue(m.press("PAD4").isEmpty())
        assertTrue(m.press("PADDUP").isEmpty())
        // B: the order back, the room still under the cursor
        val back = m.press("PAD2")
        assertNull(m.lifted)
        assertEquals(RoomBar.DEFAULT, m.draft.rooms)
        assertEquals("BAGS", m.barRoom)
        assertTrue(back.contains(HomeEditModel.Fx.Relay(Motion.MODULE_CLOSE)))
        // A ... A: dropped where it is (the draft keeps it: settled, saved now), changed; B is Done
        m.press("PAD1", "PADDLEFT")
        assertFalse("carried: not settled yet", m.settled)
        m.press("PAD1")
        assertNull(m.lifted); assertTrue(m.settled)
        assertEquals("BAGS", m.draft.rooms[6])
        assertTrue(m.changed)
        assertEquals(EditStop.ROOMS, m.cursor)
        assertEquals(listOf(HomeEditModel.Fx.Done), m.press("PAD2"))
        // the ends never wrap
        val e = model(start = EditStop.ROOMS)
        e.press("PAD1")
        assertTrue(e.press("PADDLEFT").isEmpty())
    }

    @Test fun theBarHasNoMenuAndNoTag() {
        val m = model(start = EditStop.ROOMS)
        assertFalse(EditStop.ROOMS.hasMenu)
        assertEquals("", EditStop.ROOMS.tag)
        assertTrue(HomeWidgetMenus.rows(EditStop.ROOMS, m.draft, HomeWidgetMenus.Values()).isEmpty())
        assertEquals("", HomeWidgetMenus.title(EditStop.ROOMS))
        assertTrue(m.openMenu(EditStop.ROOMS).isEmpty())
        assertNull(m.menu)
    }

    @Test fun xHidesARoomSettingsRefusesAndPlusPutsItBackWhereItWas() {
        val m = model(start = EditStop.ROOMS)
        repeat(3) { m.press("PADDRIGHT") }                                     // Talents
        assertEquals("TALENTS", m.barRoom)
        val fx = m.press("PAD3")
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), fx)
        assertEquals(listOf("TALENTS"), m.draft.hidden)
        assertEquals("the whole order keeps it", RoomBar.DEFAULT, m.draft.rooms)
        assertEquals(RoomBar.DEFAULT - "TALENTS", m.barRooms)
        assertEquals("the cursor stays at its place: the next room slid in", "PROFESSIONS", m.barRoom)
        assertTrue(m.barPlus); assertTrue(m.changed)
        assertNull("no menu, no pill", m.menu)
        // the cursor walks onto "+" at the end, and no further
        repeat(20) { m.press("PADDRIGHT") }
        assertTrue(m.barOnPlus)
        assertNull(m.barRoom)
        assertEquals(m.barRooms.size, m.barSel)
        // X on "+" does nothing; Y nothing
        assertTrue(m.press("PAD3").isEmpty()); assertTrue(m.press("PAD4").isEmpty())
        // Settings (the last room, left of "+") can never be hidden: one red pulse, nothing changed
        m.press("PADDLEFT")
        assertEquals("SETTINGS", m.barRoom)
        val no = m.press("PAD3")
        assertEquals(listOf(HomeEditModel.Fx.Refuse(EditStop.ROOMS)), no)
        assertEquals(listOf("TALENTS"), m.draft.hidden)
        // Macros (the last hideable before Settings): hidden; then A on "+" opens the tray (the whole order's order: Talents, Macros)
        m.press("PADDLEFT", "PAD3")
        assertEquals(listOf("TALENTS", "MACROS"), m.draft.hidden)
        repeat(5) { m.press("PADDRIGHT") }
        assertTrue(m.barOnPlus)
        assertTrue(m.press("PAD1").isEmpty())
        assertEquals(HomeEditModel.Sheet.ROOMS, m.sheet)
        assertEquals(listOf("TALENTS", "MACROS"), m.hiddenRooms)
        // B closes the tray, the cursor on "+"; A again, Down to Macros, A: back where it was (before Settings), the cursor on it
        m.press("PAD2")
        assertNull(m.sheet); assertTrue(m.barOnPlus)
        m.press("PAD1", "PADDDOWN")
        assertEquals(1, m.sheetSel)
        val put = m.press("PAD1")
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), put)
        assertNull(m.sheet)
        assertEquals(listOf("TALENTS"), m.draft.hidden)
        assertEquals("MACROS", m.barRoom)
        assertEquals(m.barRooms.indexOf("SETTINGS") - 1, m.barSel)
        // the last one back: "+" goes, the bar is today's again
        m.openRoomTray(); m.press("PAD1")
        assertTrue(m.draft.hidden.isEmpty())
        assertFalse(m.barPlus)
        assertEquals("TALENTS", m.barRoom)
        assertEquals(RoomBar.DEFAULT, m.barRooms)
        assertFalse("hidden and put back: nothing changed", m.changed)
        // Discard puts back the hidden rooms too (the whole record edit mode opened on)
        m.press("PAD3")
        assertTrue(m.changed)
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_CLOSE), HomeEditModel.Fx.Discard), m.discard())
        assertTrue(m.draft.hidden.isEmpty())
    }

    @Test fun theCarryWalksTheVisibleRoomsAndAHiddenRoomKeepsItsNeighbour() {
        val m = model(start = EditStop.ROOMS)
        m.press("PADDRIGHT", "PAD3")                                           // Quests hidden (it follows Map)
        assertEquals(listOf("QUESTS"), m.draft.hidden)
        assertEquals("SPELLBOOK", m.barRoom)
        // carry Spellbook to the far left (one step: over Map), then Map right of it: Quests stays after Map, its neighbour
        m.press("PAD1", "PADDLEFT", "PAD1")
        assertEquals(listOf("SPELLBOOK", "MAP"), m.barRooms.take(2))
        assertEquals(listOf("SPELLBOOK", "MAP", "QUESTS"), m.draft.rooms.take(3))
        // the carry stops at the last room: "+" is never a slot
        repeat(5) { m.press("PADDRIGHT") }
        m.press("PAD1")
        repeat(20) { m.press("PADDRIGHT") }
        assertEquals(m.barRooms.size - 1, m.barSel)
        assertEquals(m.barLifted, m.barRooms.last())
        m.press("PAD1")
        assertTrue(m.draft.rooms.toSet() == RoomBar.DEFAULT.toSet() && m.draft.rooms.size == RoomBar.DEFAULT.size)
        // Quests back from the tray: after Map again
        m.openRoomTray(); m.press("PAD1")
        assertEquals(m.barRooms.indexOf("MAP") + 1, m.barRooms.indexOf("QUESTS"))
    }

    @Test fun aFingerTapsMovesTheCursorDragsBadgesAndPlus() {
        val m = model()
        // a tap moves the cursor there; a second tap opens nothing (a room is an app: no menu)
        assertTrue(m.barTap(9).isEmpty())
        assertEquals(EditStop.ROOMS, m.cursor)
        assertEquals(9, m.barSel)
        assertTrue(m.barTap(9).isEmpty())
        assertNull(m.menu)
        // drag Settings (11) to the far left: lifted at once, the others make way after the 80 ms hover; the release lays it
        val s = slots()
        val geo = geo(m)
        val x0 = s[11]
        m.barDragStart(11, x0, 1030f, slotBox(s, 11))
        assertEquals(EditStop.ROOMS, m.lifted)
        assertEquals("SETTINGS", m.barLifted)
        assertTrue(m.dragMove(x0 - 200f, 1030f, 1000L, geo).isEmpty())       // asked for: not laid before the hover
        assertEquals(RoomBar.DEFAULT, m.draft.rooms)
        val laid = m.dragTick(1090L)
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), laid)
        assertEquals("SETTINGS", m.draft.rooms[9])
        // past the disc to the far left: released there (no hover needed)
        m.dragMove(s[0] + 6f, 1030f, 1100L, geo)
        val end = m.dragEnd(s[0] + 6f, 1030f, 1110L, geo)
        assertEquals("SETTINGS", m.draft.rooms[0])
        assertEquals(0, m.barSel)
        assertNull(m.lifted); assertNull(m.barLifted)
        assertTrue(end.contains(HomeEditModel.Fx.Relay(Motion.MODULE_CLOSE)))
        assertTrue(end.contains(HomeEditModel.Fx.Lift))
        // a carried room cancelled (a second finger) goes back
        m.barDragStart(0, s[0], 1030f, slotBox(s, 0))
        m.dragMove(s[5], 1030f, 2000L, geo); m.dragTick(2100L)
        assertEquals("SETTINGS", m.draft.rooms[5])
        m.dragCancel()
        assertEquals("SETTINGS", m.draft.rooms[0])
        // a badge's tap: Bags hidden in one tap (no "Remove?"), the cursor at its place; Settings' (it has none) refuses
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), m.hideRoom("BAGS"))
        assertEquals(listOf("BAGS"), m.draft.hidden)
        assertNull(m.removeAsk)
        assertEquals(listOf(HomeEditModel.Fx.Refuse(EditStop.ROOMS)), m.hideRoom("SETTINGS"))
        // with "+" shown the slots are the 11 rooms' (12 cells with "+": today's geometry); a drag lays over the visible rooms only
        val s2 = slots(m.barRooms.size, true)
        assertEquals(11, s2.size)
        val i = m.barRooms.indexOf("CONTROLLER")
        m.barDragStart(i, s2[i], 1030f, slotBox(s2, i))
        m.dragMove(s2[1] + 4f, 1030f, 2990L, geo(m))
        m.dragEnd(s2[1] + 4f, 1030f, 3000L, geo(m))
        assertEquals("CONTROLLER", m.barRooms[1])
        assertEquals("BAGS kept its neighbour (after Character)", m.draft.rooms.indexOf("CHARACTER") + 1, m.draft.rooms.indexOf("BAGS"))
        // "+" tapped: the tray; a tap on the bar closes it; a tray row's tap puts the room back
        m.openRoomTray()
        assertEquals(HomeEditModel.Sheet.ROOMS, m.sheet)
        assertTrue(m.barTap(3).isEmpty())
        assertNull(m.sheet)
        m.openRoomTray()
        m.unhideRoom("BAGS")
        assertTrue(m.draft.hidden.isEmpty()); assertEquals("BAGS", m.barRoom)
    }

    @Test fun theCarryOnlyGlidesAndAFightParksIt() {
        val m = model(start = EditStop.ROOMS)
        val fx = m.press("PAD1", "PADDRIGHT", "PADDRIGHT", "PAD1", "PAD3")
        assertTrue("only draft glides and the lift: no Press, no Done", fx.all { it is HomeEditModel.Fx.Relay || it == HomeEditModel.Fx.Lift })
        assertEquals("X hid the room under the cursor (Map, carried two places right)", listOf("MAP"), m.draft.hidden)
        // the tray open, then a fight: the tray closes, the lift goes, nothing answers while parked (no badge, no "+")
        m.openRoomTray()
        m.park()
        assertNull(m.sheet)
        assertNull(m.lifted); assertNull(m.barLifted)
        assertTrue(m.press("PADDLEFT").isEmpty())
        assertTrue(m.hideRoom("BAGS").isEmpty())
        assertTrue(m.openRoomTray().isEmpty())
        assertTrue(m.barTap(0).isEmpty())
        assertEquals(listOf("MAP"), m.draft.hidden)
        m.resume()
        assertEquals("MAP", m.draft.rooms[2])
        // Start is Done from the bar
        assertEquals(listOf(HomeEditModel.Fx.Done), m.press("PADFORWARD"))
    }

    /** the review: a controller on the RELAYED feed (no held B to see) reaches Discard too - Done is a stop, reached by Right from the
     *  room bar's right end (or the ribbon's "+"), never by the spatial walk; A on it is Done, X Discard; Left / Down go back */
    private fun withDone(l: HomeLayout, plus: Boolean = false): Map<EditStop, HomeLayoutSolver.Box> {
        val out = LinkedHashMap(stops(l))
        if (plus) out[EditStop.PLUS] = HomeLayoutSolver.Box(14f, 937f, 60f, 965f)
        out[EditStop.DONE] = HomeLayoutSolver.Box(1080f, 937f, 1230f, 965f)
        return out
    }
    private fun HomeEditModel.pressD(vararg keys: String, plus: Boolean = false): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, withDone(draft, plus)) { s -> HomeWidgetMenus.rows(s, draft, HomeWidgetMenus.Values()) }
        return out
    }

    @Test fun doneIsAStopRightOfTheBarsEndAndXOnItDiscards() {
        val m = model(start = EditStop.ROOMS)
        m.pressD("PADDRIGHT")
        assertEquals("one room right: still the bar", EditStop.ROOMS, m.cursor)
        m.pressD("PAD3")                                                      // a room hidden (a change Discard puts back)
        assertTrue(m.changed)
        repeat(RoomBar.DEFAULT.size + 2) { m.pressD("PADDRIGHT") }
        assertEquals("Right past the bar's end (its \"+\"): Done", EditStop.DONE, m.cursor)
        m.pressD("PADDRIGHT"); assertEquals(EditStop.DONE, m.cursor)
        m.pressD("PADDLEFT")
        assertEquals("Left: back on the bar's end", EditStop.ROOMS, m.cursor); assertTrue(m.barOnPlus)
        m.pressD("PADDRIGHT"); m.pressD("PADDDOWN")
        assertEquals("Down: the bar again", EditStop.ROOMS, m.cursor)
        m.pressD("PADDRIGHT")
        val fx = m.pressD("PAD3")
        assertTrue("X on Done: Discard", fx.contains(HomeEditModel.Fx.Discard))
        assertFalse(m.changed)
        // A on Done: Done
        val d = model(start = EditStop.ROOMS)
        repeat(RoomBar.DEFAULT.size + 1) { d.pressD("PADDRIGHT") }
        assertEquals(EditStop.DONE, d.cursor)
        assertEquals(listOf(HomeEditModel.Fx.Done), d.pressD("PAD1"))
    }

    @Test fun theSpatialWalkNeverLandsOnDone() {
        for (start in listOf(EditStop.CHAT, EditStop.MAP, EditStop.CAST, EditStop.ROOMS)) {
            val m = model(start = start)
            for (k in listOf("PADDDOWN", "PADDDOWN", "PADDUP", "PADDLEFT", "PADDRIGHT", "PADDDOWN", "PADDUP", "PADDUP")) {
                if (m.cursor == EditStop.ROOMS && k == "PADDRIGHT") continue           // the bar's own walk (its end leads to Done)
                m.pressD(k)
                assertTrue("$start then $k: never Done by the walk", m.cursor != EditStop.DONE)
            }
        }
        // the ribbon's "+": Right is Done
        val m = model(classic.copy(cast = HomeLayout.CastDock.OFF), start = EditStop.PLUS)
        m.pressD("PADDRIGHT", plus = true)
        assertEquals(EditStop.DONE, m.cursor)
    }
}
