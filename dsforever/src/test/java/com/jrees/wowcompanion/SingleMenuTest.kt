package com.jrees.wowcompanion

import com.jrees.wowcompanion.SingleMenu.Back
import com.jrees.wowcompanion.SingleMenu.Move
import com.jrees.wowcompanion.SingleMenu.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DS MENU MADE FOR THE TOP SCREEN (SingleMenu; the 1536 pass 1, the user's picks on the design study: direction C "the row rises",
 * the cursor on the swirl, "Ship in passes", fights as on two screens with the red swirl, B and the red edges). The places (the stage,
 * its body, a room's place wide or at the fallback, the row's fan round the swirl), the state machine and its motions (each between two
 * real places, 180-250 ms), and the controls' rules: B folds out of a fight and goes to the game in one, ZL / ZR step rooms but never in
 * the Controller room, A on the swirl is Home's stage.
 */
class SingleMenuTest {
    private val bar = RoomBar.rooms(RoomBar.DEFAULT)
    private val order = SingleMenu.order(bar)
    private val screen = SingleMenu.Box(0f, 0f, 1920f, 1080f)

    // ---------------------------------------------------------------- the places

    @Test fun theRowIsTheBarsOrderWithTheSwirlInTheMiddle() {
        assertEquals(13, order.size)
        assertEquals(Room.HOME, order[6])
        assertEquals(listOf(Room.MAP, Room.QUESTS, Room.SPELLBOOK, Room.TALENTS, Room.PROFESSIONS, Room.GROUPFINDER), order.take(6))
        assertEquals(listOf(Room.CHARACTER, Room.BAGS, Room.CONTROLLER, Room.SOCIAL, Room.MACROS, Room.SETTINGS), order.drop(7))
        assertFalse("Chat and the Calendar are not on the bar", Room.CHAT in order || Room.CALENDAR in order)
        // a bar with rooms hidden: the swirl still in the middle (RoomBar.leftCount)
        val short = SingleMenu.order(listOf(Room.MAP, Room.QUESTS, Room.BAGS))
        assertEquals(listOf(Room.MAP, Room.QUESTS, Room.HOME, Room.BAGS), short)
    }

    @Test fun theFanRoundTheSwirlFitsTheScreenUnderTheStage() {
        val stage = SingleMenu.stage()
        assertTrue("the stage on the screen", stage.inside(screen))
        assertEquals("the study's 1848 x 772 stage", 1848f, stage.w, 0.01f); assertEquals(772f, stage.h, 0.01f)
        val ds = SingleMenu.discs(order, Room.BAGS)
        assertEquals(order.size, ds.size)
        val hub = ds.first { it.room == Room.HOME }
        assertEquals(960f, hub.cx, 0.01f); assertEquals(SingleMenu.HUB_D, hub.d, 0.01f)
        for (d in ds) {
            assertTrue("${d.room} on the screen", d.box.inside(screen))
            assertFalse("${d.room} under the stage, never on it", d.box.hits(stage))
        }
        // left to right in the row's order, none overlapping, the arc bending the far ones up
        for (i in 1 until ds.size) assertTrue("${ds[i].room} right of ${ds[i - 1].room}", ds[i].box.l >= ds[i - 1].box.r - 0.5f)
        assertTrue("the arc: the ends higher than the rooms beside the swirl", ds.first().cy < ds[5].cy && ds.last().cy < ds[7].cy)
        assertEquals("the lit room larger", SingleMenu.DISC_ON, ds.first { it.room == Room.BAGS }.d, 0.01f)
        assertEquals(SingleMenu.DISC_D, ds.first { it.room == Room.CHARACTER }.d, 0.01f)
        // the lit name under its disc stays on the screen; the hint line over the row is clear of the stage
        assertTrue(ds.maxOf { it.cy } + SingleMenu.NAME_DY < 1080f)
        assertTrue(SingleMenu.HINT_Y - 30f > stage.b)
    }

    @Test fun aWideRoomIsLaidAcrossTheStageAndTheFallbackIsItsPageCentredAtTheSameSize() {
        val body = SingleMenu.body()
        val wide = SingleMenu.place(Room.BAGS)
        val fall = SingleMenu.place(Room.CHARACTER)
        assertTrue("Bags has its 16:9 layout (pass 1)", wide.wide)
        assertFalse("Character waits for its pass: the fallback", fall.wide)
        assertEquals("one size for every room's words", wide.k, fall.k, 0.0001f)
        assertEquals("the page's height is the body's", body.h, SingleMenu.ROOM_H * wide.k, 0.5f)
        assertTrue("k between the study's 0.72 and 0.8 (${wide.k})", wide.k in 0.72f..0.8001f)
        assertEquals("the wide page fills the body", body.w, wide.drawn.w, 1.5f)
        assertTrue(wide.drawn.inside(body)); assertTrue(fall.drawn.inside(body))
        assertEquals("the fallback is the bottom screen's page", SingleMenu.PANEL_W, fall.colW)
        assertEquals("centred", body.cx, fall.drawn.cx, 0.5f)
        assertTrue("the wide room: the bottom screen's page and a column of 900 or more beside it", wide.colW - SingleMenu.PANEL_W >= 900)
        // the page's height is the bottom screen's room (the panel less the bar, the track it overlaps, the rooms' bottom margin)
        assertEquals(SingleMenu.COLUMN_H - Strip.HEIGHT + Strip.TRACKS - Strip.TRACKS, SingleMenu.ROOM_H)
        // a smaller screen: everything scaled about the centre, still inside
        val small = SingleMenu.place(Room.BAGS, 1280, 720)
        assertTrue(small.drawn.inside(SingleMenu.Box(0f, 0f, 1280f, 720f)))
    }

    // ---------------------------------------------------------------- the machine and its motions

    @Test fun downRaisesTheRowARoomUnfoldsBFoldsBAgainDrops() {
        val m = SingleMenu.Machine()
        assertEquals(Phase.CLOSED, m.phase)
        assertEquals("Down: the row rises", Move.RISE, m.sync(rowUp = true, shown = null))
        assertEquals(Phase.ROW, m.phase)
        assertEquals("nothing new: nothing moves", Move.NONE, m.sync(true, null))
        assertEquals("A on Bags: it unfolds from its disc", Move.UNFOLD, m.sync(true, Room.BAGS))
        assertEquals(Phase.ROOM, m.phase); assertEquals(Room.BAGS, m.room)
        assertEquals("ZL: the room before it slides in", Move.SLIDE, m.sync(true, Room.CHARACTER))
        assertEquals("B: it folds into its disc", Move.FOLD, m.sync(true, null))
        assertEquals(Phase.ROW, m.phase)
        assertEquals("B again: the row drops", Move.DROP, m.sync(false, null))
        assertEquals(Phase.CLOSED, m.phase)
        // straight into a room (a game window, the toolbar's Chat) and straight out (a fight's B, a tap on the dim)
        assertEquals(Move.RISE_UNFOLD, m.sync(true, Room.CHAT))
        assertEquals(Move.FOLD_DROP, m.sync(false, null))
        assertEquals(Move.FOLD_DROP, m.last)
    }

    @Test fun everyMotionIsShortAndBetweenTwoRealPlaces() {
        for (mv in Move.values()) {
            if (mv == Move.NONE) { assertEquals(0L, SingleMenu.ms(mv)); continue }
            assertTrue("$mv ${SingleMenu.ms(mv)} ms", SingleMenu.ms(mv) in 180L..250L)
        }
        // the fan: the swirl first, the far ends last, every disc landed by the rise's end
        val hub = order.indexOf(Room.HOME)
        assertEquals(1f, SingleMenu.fanK(hub, hub, SingleMenu.RISE_MS, SingleMenu.RISE_MS), 0f)
        assertEquals(1f, SingleMenu.fanK(0, hub, SingleMenu.RISE_MS, SingleMenu.RISE_MS), 0f)
        assertTrue(SingleMenu.fanK(0, hub, 80L, SingleMenu.RISE_MS) < SingleMenu.fanK(hub, hub, 80L, SingleMenu.RISE_MS))
        // a fold lands IN its disc: the page's shape, centred on it, the disc's size
        val d = SingleMenu.discs(order, Room.BAGS).first { it.room == Room.BAGS }
        val page = SingleMenu.place(Room.BAGS).drawn
        val f = SingleMenu.folded(d, page)
        assertEquals(d.cx, f.cx, 0.5f); assertEquals(d.cy, f.cy, 0.5f)
        assertEquals(page.w / page.h, f.w / f.h, 0.01f)
        assertTrue(f.w < 3 * d.d)
        // the room change's side follows the row
        assertEquals(1, SingleMenu.slideDir(order, Room.CHARACTER, Room.BAGS))
        assertEquals(-1, SingleMenu.slideDir(order, Room.BAGS, Room.CHARACTER))
    }

    // ---------------------------------------------------------------- the controls

    @Test fun bFoldsIntoTheRowOutOfAFightAndGoesStraightToTheGameInOne() {
        assertEquals(Back.ROW, SingleMenu.back(fight = false, r = Room.BAGS, order = order))
        assertEquals("the user's pick 3: B returns to the game in a fight", Back.GAME, SingleMenu.back(true, Room.BAGS, order))
        assertEquals("a room not on the row (Chat) has no disc to fold into", Back.GAME, SingleMenu.back(false, Room.CHAT, order))
        assertEquals(SingleMenu.Go.HOME_STAGE, SingleMenu.a(Room.HOME))
        assertEquals(SingleMenu.Go.ROOM, SingleMenu.a(Room.SOCIAL))
        assertTrue(SingleMenu.tabsHint(Room.CHARACTER)); assertFalse("Bags has no tabs", SingleMenu.tabsHint(Room.BAGS))
    }

    @Test fun zlZrStepRoomsButNeverWhereAPageReadsThemHeld() {
        assertEquals(Room.CHARACTER, SingleMenu.step(order, Room.BAGS, -1))
        assertEquals(Room.CONTROLLER, SingleMenu.step(order, Room.BAGS, 1))
        assertEquals("the swirl is jumped over", Room.CHARACTER, SingleMenu.step(order, Room.GROUPFINDER, 1))
        assertEquals(Room.GROUPFINDER, SingleMenu.step(order, Room.CHARACTER, -1))
        assertNull("no wrap at the ends", SingleMenu.step(order, Room.MAP, -1))
        assertNull(SingleMenu.step(order, Room.SETTINGS, 1))
        assertNull("a room off the row", SingleMenu.step(order, Room.CHAT, 1))
        assertTrue(SingleMenu.triggersStep(Room.BAGS, held = true, bookHoldsTriggers = false))
        assertFalse("the Controller room's pages read ZL / ZR held", SingleMenu.triggersStep(Room.CONTROLLER, true, false))
        assertFalse("a Spellbook page holding them", SingleMenu.triggersStep(Room.SPELLBOOK, true, true))
        assertTrue(SingleMenu.triggersStep(Room.SPELLBOOK, true, false))
        assertFalse("never while the controller is the game's", SingleMenu.triggersStep(Room.BAGS, false, false))
        assertFalse(SingleMenu.triggersStep(Room.HOME, true, false))
    }

    @Test fun inAFightTheSwirlIsRedWithBAndTheEdgesFlash() {
        assertEquals(true, SingleMenu.swirlRed(fight = true, phase = Phase.ROOM))
        assertEquals(true, SingleMenu.swirlRed(true, Phase.ROW))
        assertEquals("out of a fight a room's swirl rests blue (B folds)", false, SingleMenu.swirlRed(false, Phase.ROOM))
        assertNull("the row out of a fight: the bar cursor's own rule", SingleMenu.swirlRed(false, Phase.ROW))
        assertNull("closed: the bar's own", SingleMenu.swirlRed(true, Phase.CLOSED))
        assertTrue(SingleMenu.edgeFlash(true, Phase.ROOM)); assertTrue(SingleMenu.edgeFlash(true, Phase.ROW))
        assertFalse(SingleMenu.edgeFlash(false, Phase.ROOM)); assertFalse(SingleMenu.edgeFlash(true, Phase.CLOSED))
        var lo = 1f; var hi = 0f
        for (t in 0L until SingleMenu.PULSE_MS step 10L) { val a = SingleMenu.edgeAlpha(t); lo = minOf(lo, a); hi = maxOf(hi, a) }
        assertEquals(SingleMenu.EDGE_MIN, lo, 0.01f); assertEquals(SingleMenu.EDGE_MAX, hi, 0.01f)
        assertEquals("a pulse a beat", SingleMenu.edgeAlpha(100L), SingleMenu.edgeAlpha(100L + SingleMenu.PULSE_MS), 0.0001f)
    }
}
