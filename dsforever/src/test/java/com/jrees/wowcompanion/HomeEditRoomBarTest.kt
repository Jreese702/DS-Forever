package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME, PHASE 4: THE ROOM BAR IN EDIT MODE (SPEC.txt sections 4, 7, 8; final_08; D3: reorder only, every room stays on the bar):
 * the cursor reaches the bar (Down from the map), Left / Right walk its rooms; A lifts a room, Left / Right carry it (EDIT_STEP, across the
 * disc too), A drops, B puts the order back; X = its menu (XP bar Show / Hide, Order Reset); a finger taps (select, then the menu) and
 * drags (the others make way after the 80 ms hover; the release lays it); a carried room settles (is saved) only at its drop. Pure.
 */
class HomeEditRoomBarTest {
    private val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
    private fun model(saved: HomeLayout = classic, start: EditStop = EditStop.CHAT) =
        HomeEditModel(saved, start, true)

    /** the bar's slots as the Strip lays them (1240 wide): 6 left of the 128 px disc, 6 right of it */
    private val slots: List<Float> = run {
        val homeX = 620f; val l0 = 8f; val l1 = homeX - 64f - 14f; val r0 = homeX + 64f + 14f; val r1 = 1232f
        val cl = (l1 - l0) / 6f; val cr = (r1 - r0) / 6f
        (0 until 6).map { l0 + cl * it + cl / 2f } + (0 until 6).map { r0 + cr * it + cr / 2f }
    }
    private fun slotBox(i: Int) = HomeLayoutSolver.Box(slots[i] - 40f, 990f, slots[i] + 40f, 1070f)

    private fun stops(l: HomeLayout): Map<EditStop, HomeLayoutSolver.Box> {
        val b = HomeLayoutSolver.places(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f)).boxes
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        out[EditStop.BAND] = b.getValue(HomePart.BARS).copy(r = 470f)
        out[EditStop.CLOCK] = HomeLayoutSolver.Box(470f, 12f, 770f, 200f)
        out[EditStop.CHAT] = b.getValue(HomePart.CHAT)
        out[EditStop.MAP] = b.getValue(HomePart.MAP)
        b[HomePart.QUESTS]?.let { out[EditStop.QUEST] = it }
        b[HomePart.STRIP]?.let { out[EditStop.CAST] = it }
        out[EditStop.ROOMS] = HomeLayoutSolver.Box(8f, 935f + Strip.TRACKS, 1232f, 935f + Strip.BAR)
        return out
    }
    private val geo = object : HomeEditModel.Geo {
        override fun columns(l: HomeLayout) = HomeLayoutSolver.columns(l, 1240f, 8f)
        override fun valid(l: HomeLayout) = true
        override val w = 1240f
        override fun barSlots() = slots
    }
    private fun HomeEditModel.press(vararg keys: String): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, stops(draft)) { s -> HomeWidgetMenus.rows(s, draft, HomeWidgetMenus.Values(), toolbarWant) }
        return out
    }

    @Test fun theCursorReachesTheBarAndWalksItsRooms() {
        val m = model(start = EditStop.MAP)
        m.press("PADDDOWN")
        assertEquals("Down from the map: the room bar", EditStop.ROOMS, m.cursor)
        assertEquals(0, m.barSel)
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT")
        assertEquals("across the disc: Bags", "BAGS", m.draft.rooms[m.barSel])
        repeat(20) { m.press("PADDRIGHT") }
        assertEquals("the right end holds", RoomBar.DEFAULT.size - 1, m.barSel)
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
        assertEquals("BAGS", m.draft.rooms[m.barSel])
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

    @Test fun xIsTheBarsMenuXpBarAndOrderReset() {
        val m = model(start = EditStop.ROOMS)
        repeat(3) { m.press("PADDRIGHT") }
        m.press("PAD1", "PADDRIGHT", "PAD1")                                   // Talents one to the right
        assertEquals("TALENTS", m.draft.rooms[4])
        m.press("PAD3")
        assertEquals(EditStop.ROOMS, m.menu)
        val rows = HomeWidgetMenus.rows(EditStop.ROOMS, m.draft, HomeWidgetMenus.Values())
        assertEquals(listOf("XP bar", "Order"), rows.map { it.label })
        assertEquals(listOf("Show", "Hide"), rows[0].words)
        assertEquals(0, rows[0].picked)
        assertEquals(MenuRow.Kind.BUTTON, rows[1].kind)
        // no row takes a room off the bar (D3)
        assertFalse(rows.any { it.label.contains("Off", ignoreCase = true) || it.words.any { w -> w.contains("Remove") } })
        // XP bar Hide: the draft (the layout), live, nothing else
        m.press("PADDRIGHT")
        assertFalse(m.draft.xpBar)
        // Order Reset: today's order, the cursor stays on its room
        m.press("PADDDOWN", "PAD1")
        assertEquals(RoomBar.DEFAULT, m.draft.rooms)
        assertEquals("TALENTS", m.draft.rooms[m.barSel])
        // B closes the menu, the cursor stays on the bar
        m.press("PAD2")
        assertNull(m.menu)
        assertEquals(EditStop.ROOMS, m.cursor)
        assertTrue("the hidden XP track is a change of the session's", m.changed)
    }

    @Test fun aFingerTapsThenDragsARoom() {
        val m = model()
        // a tap selects the bar on that room; a second tap on it opens the menu
        assertTrue(m.barTap(9).isEmpty())
        assertEquals(EditStop.ROOMS, m.cursor)
        assertEquals(9, m.barSel)
        m.barTap(9)
        assertEquals(EditStop.ROOMS, m.menu)
        m.barTap(2)                                                            // a tap elsewhere closes it, cursor there next time
        assertNull(m.menu)
        // drag Settings (11) to the far left: lifted at once, the others make way after the 80 ms hover; the release lays it
        val x0 = slots[11]
        m.barDragStart(11, x0, 1030f, slotBox(11))
        assertEquals(EditStop.ROOMS, m.lifted)
        assertEquals("SETTINGS", m.barLifted)
        assertTrue(m.dragMove(x0 - 200f, 1030f, 1000L, geo).isEmpty())       // asked for: not laid before the hover
        assertEquals(RoomBar.DEFAULT, m.draft.rooms)
        val laid = m.dragTick(1090L)
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), laid)
        assertEquals("SETTINGS", m.draft.rooms[9])
        // past the disc to the far left: released there (no hover needed)
        m.dragMove(slots[0] + 6f, 1030f, 1100L, geo)
        val end = m.dragEnd(slots[0] + 6f, 1030f, 1110L, geo)
        assertEquals("SETTINGS", m.draft.rooms[0])
        assertEquals(0, m.barSel)
        assertNull(m.lifted); assertNull(m.barLifted)
        assertTrue(end.contains(HomeEditModel.Fx.Relay(Motion.MODULE_CLOSE)))
        assertTrue(end.contains(HomeEditModel.Fx.Lift))
        assertEquals("every room still on the bar", RoomBar.DEFAULT.toSet(), m.draft.rooms.toSet())
        // a carried room cancelled (a second finger) goes back
        m.barDragStart(0, slots[0], 1030f, slotBox(0))
        m.dragMove(slots[5], 1030f, 2000L, geo); m.dragTick(2100L)
        assertEquals("SETTINGS", m.draft.rooms[5])
        m.dragCancel()
        assertEquals("SETTINGS", m.draft.rooms[0])
    }

    @Test fun theCarryOnlyGlidesAndAFightParksIt() {
        val m = model(start = EditStop.ROOMS)
        val fx = m.press("PAD1", "PADDRIGHT", "PADDRIGHT", "PAD1", "PAD3")
        assertEquals(EditStop.ROOMS, m.menu)
        assertTrue("only draft glides and the lift: no Press, no Done", fx.all { it is HomeEditModel.Fx.Relay || it == HomeEditModel.Fx.Lift })
        // a fight mid-carry: the lift goes, the draft (and its order) waits
        m.press("PAD2")                                                        // the menu closes
        m.press("PAD1")
        m.park()
        assertNull(m.lifted); assertNull(m.barLifted)
        assertTrue(m.press("PADDLEFT").isEmpty())
        m.resume()
        assertEquals("MAP", m.draft.rooms[2])
        // Start is Done from the bar
        assertEquals(listOf(HomeEditModel.Fx.Done), m.press("PADFORWARD"))
    }
}
