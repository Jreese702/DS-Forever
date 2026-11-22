package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME, PHASE 2: EDIT MODE'S STATE MACHINE (SPEC.txt section 15 HomeEditModelTest): the B ladder (put back, close, then Done:
 * test build 1518, no prompt - every change is saved as it is made); Discard (Done's long press, a held B) back to the opening record;
 * the cursor's spatial moves over Home's real boxes; lift / drop / put back and each widget's docks; Select's swap from any stop; a
 * finger's drag with the 48 px hysteresis and the 80 ms hover; the divider's snap; Y's size steps; no layouts (the shoulders do
 * nothing); remove and "+"; a fight's park and resume; Start = Done; and the top bar stays still (1518 build 3: no band widget, no Clock
 * box; the user's ruling 2026-11-22: no D-pad icon swaps, no clock spots - the band has no stops). Pure.
 */
class HomeEditModelTest {
    private val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
    private fun model(saved: HomeLayout = classic, start: EditStop = EditStop.CHAT) = HomeEditModel(saved, start)

    /** Home's boxes for [l] (the solver's, a focused quest shown) as the edit view gives the cursor, the ribbon under the frame */
    private fun stops(l: HomeLayout, plus: Boolean = false): Map<EditStop, HomeLayoutSolver.Box> {
        val r = HomeLayoutSolver.places(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f))
        val b = r.boxes
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        out[EditStop.CHAT] = b.getValue(HomePart.CHAT)
        out[EditStop.MAP] = b.getValue(HomePart.MAP)
        b[HomePart.QUESTS]?.let { out[EditStop.QUEST] = it }
        b[HomePart.STRIP]?.let { s -> out[EditStop.CAST] = b[HomePart.CAST2]?.let { s.copy(r = it.r) } ?: s }
        val c = b.getValue(HomePart.CHAT); val m = b.getValue(HomePart.MAP)
        val x = (minOf(c.r, m.r) + maxOf(c.l, m.l)) / 2f
        val top = minOf(c.t, m.t)
        out[EditStop.DIVIDER] = HomeLayoutSolver.Box(x - 2f, top, x + 2f, top + 40f)
        if (plus) out[EditStop.PLUS] = HomeLayoutSolver.Box(14f, 937f, 60f, 967f)
        return out
    }

    private val geo = object : HomeEditModel.Geo {
        override fun columns(l: HomeLayout) = HomeLayoutSolver.columns(l, 1240f, 8f)
        override fun valid(l: HomeLayout) = HomeLayoutRules.valid(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f))
        override val w = 1240f
    }

    private fun HomeEditModel.press(vararg keys: String, l: HomeLayout = draft): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, stops(l, plusShown)) { s -> HomeWidgetMenus.rows(s, draft, HomeWidgetMenus.Values()) }
        return out
    }

    // ---------------------------------------------------------------- the B ladder

    @Test fun bIsDoneWhenNothingIsOpen() {
        val m = model()
        assertEquals(listOf(HomeEditModel.Fx.Done), m.press("PAD2"))
        assertFalse(m.changed)
    }

    @Test fun bStepsBackOneLevelThenIsDone() {
        val m = model()
        m.press("PAD1")                                         // A on Chat: lifted
        assertEquals(EditStop.CHAT, m.lifted)
        m.press("PADDRIGHT")                                    // the chat to the right column
        assertEquals(HomeLayout.Side.RIGHT, m.draft.chatSide)
        m.press("PAD2")                                         // B: put back
        assertNull(m.lifted)
        assertEquals(HomeLayout.Side.LEFT, m.draft.chatSide)
        assertFalse(m.changed)
        m.press("PAD3")                                         // X: the chat's menu
        assertEquals(EditStop.CHAT, m.menu)
        m.press("PAD2")                                         // B: the menu closes
        assertNull(m.menu)
        m.press("PADBACK")                                      // Select: the swap
        assertTrue(m.changed)
        assertEquals("B with a change: Done at once (no prompt: it is saved already)", listOf(HomeEditModel.Fx.Done), m.press("PAD2"))
        assertTrue("Done keeps the change", m.draft.mirrored)
    }

    /** 1518: Discard (Done's long press, a held B) from any level - the opening record back, nothing lifted / open, the glide back
     *  first */
    @Test fun discardPutsTheOpeningRecordBack() {
        val m = model(start = EditStop.CAST)
        m.press("PADBACK")                                       // the swap
        m.press("PAD1", "PADDDOWN", "PAD1")                      // the bar Across
        m.press("PAD1", "PADDDOWN")                              // lifted again, on to Split (still lifted)
        assertEquals(HomeLayout.CastDock.SPLIT, m.draft.cast)
        assertEquals(EditStop.CAST, m.lifted)
        assertTrue(m.changed)
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_CLOSE), HomeEditModel.Fx.Discard), m.discard())
        assertEquals(classic, m.draft)
        assertNull(m.menu); assertNull(m.lifted)
        assertFalse(m.changed)
        // lifted mid-move: the lift goes with it
        val l = model()
        l.press("PAD1", "PADDRIGHT")
        assertEquals(EditStop.CHAT, l.lifted)
        l.discard()
        assertNull(l.lifted); assertEquals(classic, l.draft)
        assertTrue(l.settled)
    }

    @Test fun startIsDoneFromEveryLevel() {
        assertEquals(listOf(HomeEditModel.Fx.Done), model().press("PADFORWARD"))
        val lifted = model(); lifted.press("PAD1", "PADDRIGHT")
        assertTrue(HomeEditModel.Fx.Done in lifted.press("PADFORWARD")); assertNull(lifted.lifted)
        assertTrue("a lift Start ends keeps its dock", lifted.draft.mirrored)
        val menu = model(); menu.press("PAD3"); assertTrue(HomeEditModel.Fx.Done in menu.press("PADFORWARD")); assertNull(menu.menu)
        val tray = model(start = EditStop.CAST); tray.remove(EditStop.CAST); tray.tap(EditStop.PLUS)
        assertEquals(HomeEditModel.Sheet.ADD, tray.sheet)
        assertEquals(listOf(HomeEditModel.Fx.Done), tray.press("PADFORWARD")); assertNull(tray.sheet)
    }

    /** 1518: the session knows what it changed (Discard has something to put back); a change undone by hand is no change */
    @Test fun theSessionKnowsWhatItChanged() {
        val m = model()
        assertFalse(m.changed)
        m.press("PADBACK"); assertTrue(m.changed)
        m.press("PADBACK"); assertFalse("swapped back: the opening record again", m.changed)
        val t = model(start = EditStop.CAST)
        t.press("PAD1", "PADDDOWN", "PAD1")
        assertTrue("a module moved is a change too", t.changed)
        t.press("PAD1", "PADDUP", "PAD1")
        assertFalse("moved back by hand: no change", t.changed)
    }

    /** 1518, the autosave's moment: the draft is settled (written now) only with nothing lifted and no finger down */
    @Test fun theDraftSettlesOnlyAtTheDrop() {
        val m = model()
        assertTrue(m.settled)
        m.press("PAD1"); assertFalse("lifted: its steps wait for the drop", m.settled)
        m.press("PADDRIGHT"); assertFalse(m.settled)
        m.press("PAD1"); assertTrue("dropped", m.settled)
        val chat = stops(classic).getValue(EditStop.CHAT)
        m.dragStart(EditStop.CHAT, chat.cx, 500f, chat); assertFalse("a finger down", m.settled)
        m.dragEnd(chat.cx, 500f, 10L, geo); assertTrue(m.settled)
    }

    /** 1518: a change made while edit mode waited (another room's Settings, the late key's real record) is the session's new base:
     *  Discard undoes only the session's own changes */
    @Test fun aRebaseKeepsDiscardToTheSessionsOwnChanges() {
        val m = model()
        m.press("PADBACK")                                       // the session's own: the swap
        val outside = m.draft.copy(castSize = HomeLayout.SlotSize.M)
        m.rebase(outside, HomeLayoutStore.layOn(m.saved, m.draft, outside))
        assertEquals(outside, m.draft)
        assertTrue(m.changed)
        m.discard()
        assertEquals("the swap undone, the outside change kept", classic.copy(castSize = HomeLayout.SlotSize.M), m.draft)
        assertFalse(m.changed)
    }

    // ---------------------------------------------------------------- the cursor

    @Test fun theCursorMovesSpatiallyBetweenTheWidgets() {
        val m = model()
        assertEquals(EditStop.CHAT, m.cursor)
        m.press("PADDRIGHT"); assertEquals("straight across to the map (the divider is no Left / Right stop)", EditStop.MAP, m.cursor)
        m.press("PADDUP"); assertEquals("the card over the map is above its centre", EditStop.QUEST, m.cursor)
        // the band has no stops (the user's ruling 2026-11-22: the top bar stays still): Up from the columns' top is the divider's
        // stop (it sits at the columns' top), never a band icon
        m.press("PADDUP"); assertEquals(EditStop.DIVIDER, m.cursor)
        m.press("PADDUP"); assertEquals("nothing above it", EditStop.DIVIDER, m.cursor)
        val c = model()
        c.press("PADDDOWN"); assertEquals("the bar under the chat", EditStop.CAST, c.cursor)
        c.press("PADDDOWN"); assertEquals("nothing under it (1518: the ribbon's Done is no stop - B and Start are Done)", EditStop.CAST, c.cursor)
        c.press("PADDUP"); assertEquals(EditStop.CHAT, c.cursor)
        c.press("PADDUP"); assertEquals("Up from the chat: the divider (no band stop above it)", EditStop.DIVIDER, c.cursor)
        // Left / Right on the divider step the width
        val d = model(saved = classic.copy(card = HomeLayout.CardDock.OFF))
        d.press("PADDUP"); assertEquals(EditStop.DIVIDER, d.cursor)
        d.press("PADDRIGHT"); assertEquals(HomeLayout.Share.TWO_THIRDS, d.draft.chatShare); assertEquals(EditStop.DIVIDER, d.cursor)
        d.press("PADDDOWN"); assertTrue("down from the divider: a column", d.cursor == EditStop.CHAT || d.cursor == EditStop.MAP)
        val col = d.cursor
        d.press(if (col == EditStop.CHAT) "PADDLEFT" else "PADDRIGHT"); assertEquals("nothing beyond the outer column", col, d.cursor)
    }

    @Test fun theDividerStepsTheWidthAndTheShouldersDoNothing() {
        val m = model(start = EditStop.DIVIDER)
        m.press("PADDLEFT")
        assertEquals("the left column (the chat) one step narrower", HomeLayout.Share.THIRD, m.draft.chatShare)
        m.press("PADDLEFT")
        assertEquals("no step past a third", HomeLayout.Share.THIRD, m.draft.chatShare)
        m.press("PADDRIGHT", "PADDRIGHT")
        assertEquals(HomeLayout.Share.TWO_THIRDS, m.draft.chatShare)
        // mirrored: the divider still widens the LEFT column (the map now)
        val r = model(saved = classic.swapped(), start = EditStop.DIVIDER)
        r.press("PADDRIGHT")
        assertEquals(HomeLayout.Share.THIRD, r.draft.chatShare)
        // 1518: no layouts - LB / RB flip nothing, from any stop
        for (s in listOf(EditStop.CHAT, EditStop.MAP, EditStop.QUEST, EditStop.CAST, EditStop.DIVIDER)) {
            val c = model(start = s)
            assertTrue(s.name, c.press("PADLSHOULDER", "PADRSHOULDER").isEmpty())
            assertEquals(classic, c.draft); assertEquals(s, c.cursor)
        }
    }

    // ---------------------------------------------------------------- lift, docks, size, swap

    /** review round 2: every step of the lifted bar's Up / Down ladder undoes the last (Up from Across goes back to the column it came
     *  down from: Under map -> Down -> Up = Under map, as 1516's cycle did) */
    @Test fun theBarsLadderUndoesEachStep() {
        for (start in listOf(HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.UNDER_MAP, HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP))
            for (side in HomeLayout.Side.values()) {
                val b = model(saved = classic.copy(cast = start, chatSide = side), start = EditStop.CAST)
                b.press("PAD1")
                assertEquals(EditStop.CAST, b.lifted)
                val way = ArrayList<HomeLayout.CastDock>()
                while (true) { way += b.draft.cast; if (b.press("PADDDOWN").isEmpty()) break }
                assertEquals("$start $side: down to Split", HomeLayout.CastDock.SPLIT, b.draft.cast)
                val back = ArrayList<HomeLayout.CastDock>()
                while (true) { back += b.draft.cast; if (b.press("PADDUP").isEmpty()) break }
                assertEquals("$start $side: Up retraces Down", way.reversed(), back.dropLast(back.size - way.size))
                b.press("PADDDOWN")
                assertEquals("$start $side: and Down again", start.withTop(false), b.draft.cast)
            }
        // a bar that never sat in a column (saved Across): Up goes under the chat
        val a = model(saved = classic.copy(cast = HomeLayout.CastDock.ACROSS), start = EditStop.CAST)
        a.press("PAD1"); a.press("PADDUP")
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, a.draft.cast)
    }

    @Test fun aLiftedWidgetTakesItsDocks() {
        val m = model(start = EditStop.QUEST)
        m.press("PAD1")
        assertEquals(EditStop.QUEST, m.lifted)
        val fx = m.press("PADDLEFT")                           // the chat's column: Above chat
        assertEquals(HomeLayout.CardDock.ABOVE_CHAT, m.draft.card)
        assertEquals("a controller step glides EDIT_STEP", listOf(HomeEditModel.Fx.Relay(HomeEditModel.EDIT_STEP)), fx)
        m.press("PADDUP")                                       // the next dock: On map again
        assertEquals(HomeLayout.CardDock.ON_MAP, m.draft.card)
        m.press("PAD1")                                         // A: dropped where it is
        assertNull(m.lifted); assertEquals(EditStop.QUEST, m.cursor)
        val b = model(start = EditStop.CAST)
        b.press("PAD1")
        // the bar's ladder, no wrap: Above x <-> Under x <-> Across <-> Split
        val seen = ArrayList<HomeLayout.CastDock>()
        repeat(3) { b.press("PADDDOWN"); seen += b.draft.cast }
        assertEquals(listOf(HomeLayout.CastDock.ACROSS, HomeLayout.CastDock.SPLIT, HomeLayout.CastDock.SPLIT), seen)
        assertEquals("the foot of the ladder: no move", emptyList<HomeEditModel.Fx>(), b.press("PADDDOWN"))
        seen.clear()
        repeat(4) { b.press("PADDUP"); seen += b.draft.cast }
        assertEquals(listOf(HomeLayout.CastDock.ACROSS, HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_CHAT), seen)
        b.press("PADDRIGHT")
        assertEquals("Right = the right column's dock (the map's), the edge kept", HomeLayout.CastDock.ABOVE_MAP, b.draft.cast)
        b.press("PADDDOWN")
        assertEquals(HomeLayout.CastDock.UNDER_MAP, b.draft.cast)
        b.press("PADDUP"); b.press("PADDLEFT")
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, b.draft.cast)
        // the arrows show only real moves (nextDock null at the ladder's ends)
        assertEquals(null, b.nextDock(EditStop.CAST, b.draft, -1, horizontal = false))
        assertEquals(null, b.nextDock(EditStop.CAST, b.draft.copy(cast = HomeLayout.CastDock.SPLIT), 1, horizontal = false))
        // across a swap the bar keeps its module and its edge (Above map stays above the map)
        val sw = b.draft.copy(cast = HomeLayout.CastDock.ABOVE_MAP).swapped()
        assertEquals(HomeLayout.CastDock.ABOVE_MAP, sw.cast)
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, b.nextDock(EditStop.CAST, sw, 1, horizontal = true)?.cast)
    }

    @Test fun theBarsEdgeRowAndPlusBringItBackOnTop() {
        val m = model(saved = classic.copy(cast = HomeLayout.CastDock.UNDER_MAP), start = EditStop.CAST)
        val v = HomeWidgetMenus.Values()
        var rows = HomeWidgetMenus.rows(EditStop.CAST, m.draft, v)
        val edge = rows.indexOfFirst { it.key == HomeEditModel.K_CAST_EDGE }
        assertTrue("a column's bar has the Above / Below row", edge >= 0)
        assertEquals("Map", rows[edge].label); assertEquals(listOf("Above", "Below"), rows[edge].words); assertEquals(1, rows[edge].picked)
        m.openMenu(EditStop.CAST)
        m.menuTouch(edge, 0, rows)
        assertEquals(HomeLayout.CastDock.ABOVE_MAP, m.draft.cast)
        rows = HomeWidgetMenus.rows(EditStop.CAST, m.draft, v)
        assertEquals("Place still says Map", HomeWidgetMenus.CAST_DOCKS.indexOf(HomeLayout.CastDock.UNDER_MAP),
            rows.first { it.key == HomeEditModel.K_PLACE }.picked)
        // Place Chat keeps the edge
        m.menuTouch(rows.indexOfFirst { it.key == HomeEditModel.K_PLACE }, HomeWidgetMenus.CAST_DOCKS.indexOf(HomeLayout.CastDock.UNDER_CHAT), rows)
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, m.draft.cast)
        rows = HomeWidgetMenus.rows(EditStop.CAST, m.draft, v)
        assertEquals("Chat", rows.first { it.key == HomeEditModel.K_CAST_EDGE }.label)
        // Across: no edge row (Across and Split stay at the foot)
        m.menuTouch(rows.indexOfFirst { it.key == HomeEditModel.K_PLACE }, HomeWidgetMenus.CAST_DOCKS.indexOf(HomeLayout.CastDock.ACROSS), rows)
        assertEquals(HomeLayout.CastDock.ACROSS, m.draft.cast)
        assertTrue(HomeWidgetMenus.rows(EditStop.CAST, m.draft, v).none { it.key == HomeEditModel.K_CAST_EDGE })
        assertTrue(HomeWidgetMenus.rows(EditStop.CAST, m.draft.copy(cast = HomeLayout.CastDock.OFF), v).none { it.key == HomeEditModel.K_CAST_EDGE })
        // removed above the chat, "+" brings it back above the chat
        val r = model(saved = classic.copy(cast = HomeLayout.CastDock.ABOVE_CHAT), start = EditStop.CAST)
        r.remove(EditStop.CAST)
        assertEquals(HomeLayout.CastDock.OFF, r.draft.cast)
        r.addBack(EditStop.CAST)
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, r.draft.cast)
    }

    @Test fun dragTheBarUpToLayItAboveItsColumn() {
        val m = model(start = EditStop.CAST)
        val bar = stops(classic).getValue(EditStop.CAST)
        m.dragStart(EditStop.CAST, bar.cx, (bar.t + bar.b) / 2f, bar)
        // straight up the chat's column into its upper half: above the chat
        m.dragMove(bar.cx, 300f, 0L, geo); m.dragTick(100L)
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, m.draft.cast)
        // along the top into the middle third: never Across (that is the foot's), the edge and the column kept
        m.dragMove(630f, 300f, 200L, geo); m.dragTick(300L)
        assertEquals(HomeLayout.CastDock.ABOVE_CHAT, m.draft.cast)
        // over the map, still high: above the map
        m.dragMove(1100f, 300f, 400L, geo); m.dragTick(500L)
        assertEquals(HomeLayout.CastDock.ABOVE_MAP, m.draft.cast)
        // down into the lower half: under the map
        m.dragMove(1100f, 860f, 600L, geo); m.dragTick(700L)
        assertEquals(HomeLayout.CastDock.UNDER_MAP, m.draft.cast)
        // the middle third of the lower half: Across
        m.dragMove(630f, 860f, 800L, geo); m.dragTick(900L)
        assertEquals(HomeLayout.CastDock.ACROSS, m.draft.cast)
        // from Across, up in the middle: stays Across (no column to take the top of)
        m.dragMove(630f, 300f, 1000L, geo); m.dragTick(1100L)
        assertEquals(HomeLayout.CastDock.ACROSS, m.draft.cast)
        m.dragEnd(630f, 300f, 1110L, geo)
        assertNull(m.lifted)
    }

    @Test fun ySizesTheSelectedWidget() {
        val m = model()
        m.press("PAD4"); assertEquals(HomeLayout.Share.TWO_THIRDS, m.draft.chatShare)
        m.press("PAD4"); assertEquals(HomeLayout.Share.THIRD, m.draft.chatShare)
        val bar = model(start = EditStop.CAST); bar.press("PAD4"); assertEquals(HomeLayout.SlotSize.S, bar.draft.castSize)
        val card = model(start = EditStop.QUEST)
        assertEquals("the card's fold is the app-wide setting (the card's own tap)", listOf(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, -1)), card.press("PAD4"))
    }

    @Test fun selectSwapsFromAnyStop() {
        for (s in listOf(EditStop.CHAT, EditStop.QUEST, EditStop.MAP, EditStop.CAST, EditStop.DIVIDER, EditStop.ROOMS)) {
            val m = model(start = s)
            m.press("PADBACK")
            assertEquals(s.name, HomeLayout.Side.RIGHT, m.draft.chatSide)
            assertTrue(m.draft.mirrored)
        }
    }

    // ---------------------------------------------------------------- a finger's drag

    @Test fun aDragSwapsOnlyPastTheHysteresisAndAfterTheHover() {
        val m = model(start = EditStop.MAP)
        val map = stops(classic).getValue(EditStop.MAP)
        val y = 600f
        m.dragStart(EditStop.MAP, map.cx, y, map)
        assertEquals(EditStop.MAP, m.lifted)
        // the map's centre 40 px past the divider (620): not enough (48)
        assertTrue(m.dragMove(620f - 40f, y, 1000L, geo).isEmpty())
        assertEquals(HomeLayout.Side.LEFT, m.draft.chatSide)
        // 60 px past it: asked for, laid only after 80 ms
        assertTrue(m.dragMove(620f - 60f, y, 1010L, geo).isEmpty())
        assertNotNull(m.drag?.cand)
        assertTrue(m.dragTick(1060L).isEmpty())
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), m.dragTick(1090L))
        assertEquals("the chat glided right", HomeLayout.Side.RIGHT, m.draft.chatSide)
        // back over the (new) divider by less than 48: it stays
        assertTrue(m.dragMove(620f + 30f, y, 1100L, geo).isEmpty())
        assertEquals(HomeLayout.Side.RIGHT, m.draft.chatSide)
        val end = m.dragEnd(620f + 30f, y, 1120L, geo)
        assertNull(m.lifted); assertEquals(EditStop.MAP, m.cursor)
        assertTrue(end.contains(HomeEditModel.Fx.Lift))
    }

    @Test fun aReleaseLaysTheDockUnderTheFingerWithoutTheHover() {
        val m = model(start = EditStop.CHAT)
        val chat = stops(classic).getValue(EditStop.CHAT)
        m.dragStart(EditStop.CHAT, chat.cx, 500f, chat)
        m.dragMove(900f, 500f, 0L, geo)
        val fx = m.dragEnd(900f, 500f, 10L, geo)
        assertEquals(HomeLayout.Side.RIGHT, m.draft.chatSide)
        assertEquals("the drop settles on MODULE_CLOSE", HomeEditModel.Fx.Relay(Motion.MODULE_CLOSE), fx.first())
    }

    @Test fun theHoldWithoutAMoveLeavesTheWidgetSelected() {
        val m = model(start = EditStop.MAP)
        val map = stops(classic).getValue(EditStop.MAP)
        m.startOn(EditStop.MAP, lift = true)
        m.dragStart(EditStop.MAP, map.cx, 600f, map)
        m.dragEnd(map.cx, 600f, 400L, geo)
        assertEquals(classic, m.draft); assertNull(m.lifted); assertEquals(EditStop.MAP, m.cursor)
    }

    @Test fun theBarAndTheCardDockByTheColumnUnderThem() {
        val m = model(start = EditStop.CAST)
        val bar = stops(classic).getValue(EditStop.CAST)
        m.dragStart(EditStop.CAST, bar.cx, bar.t + 20f, bar)
        // the bar's centre into the middle third (+ 48): across
        m.dragMove(bar.cx + 620f - bar.cx + 10f, bar.t, 0L, geo); m.dragTick(100L)
        assertEquals(HomeLayout.CastDock.ACROSS, m.draft.cast)
        m.dragMove(1100f, bar.t, 200L, geo); m.dragTick(300L)
        assertEquals(HomeLayout.CastDock.UNDER_MAP, m.draft.cast)
        m.dragEnd(1100f, bar.t, 310L, geo)
        val split = model(saved = classic.copy(cast = HomeLayout.CastDock.SPLIT), start = EditStop.CAST)
        val sb = stops(split.draft).getValue(EditStop.CAST)
        split.dragStart(EditStop.CAST, sb.cx, sb.t, sb)
        split.dragMove(200f, sb.t, 0L, geo); split.dragTick(100L)
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, split.draft.cast)
        split.dragMove(620f, sb.t, 200L, geo); split.dragTick(300L)
        assertEquals("the middle gives Split back to a bar that was split", HomeLayout.CastDock.SPLIT, split.draft.cast)
        val card = model(start = EditStop.QUEST)
        val q = stops(classic).getValue(EditStop.QUEST)
        card.dragStart(EditStop.QUEST, q.cx, q.t, q)
        card.dragMove(200f, q.t, 0L, geo); card.dragTick(100L)
        assertEquals(HomeLayout.CardDock.ABOVE_CHAT, card.draft.card)
    }

    @Test fun theDividerSnapsToTheNearestShare() {
        val m = model(start = EditStop.DIVIDER)
        val d = stops(classic).getValue(EditStop.DIVIDER)
        m.dragStart(EditStop.DIVIDER, d.cx, 560f, d)
        assertNull("the divider is not a lifted widget", m.lifted)
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), m.dragMove(430f, 560f, 0L, geo))
        assertEquals(HomeLayout.Share.THIRD, m.draft.chatShare)
        m.dragMove(820f, 560f, 10L, geo)
        assertEquals(HomeLayout.Share.TWO_THIRDS, m.draft.chatShare)
        m.dragEnd(820f, 560f, 20L, geo)
    }

    @Test fun tapsSelectThenOpenTheMenu() {
        val m = model()
        m.tap(EditStop.MAP); assertEquals(EditStop.MAP, m.cursor); assertNull(m.menu)
        m.tap(EditStop.MAP); assertEquals(EditStop.MAP, m.menu)
        m.tap(EditStop.CHAT); assertNull("a tap elsewhere closes the menu first", m.menu)
    }

    // ---------------------------------------------------------------- remove and "+"

    @Test fun theRedChipAsksOnceThenRemovesAndPlusBringsItBack() {
        val m = model(start = EditStop.CAST)
        assertTrue(m.removeChip(EditStop.CAST).isEmpty())
        assertEquals(EditStop.CAST, m.removeAsk)
        m.removeChip(EditStop.CAST)
        assertEquals(HomeLayout.CastDock.OFF, m.draft.cast)
        assertTrue(m.plusShown)
        assertEquals(listOf(EditStop.CAST), m.removed)
        m.press("PADDDOWN")
        m.tap(EditStop.PLUS)
        assertEquals(HomeEditModel.Sheet.ADD, m.sheet)
        m.press("PAD1")
        assertEquals("back in its last dock", HomeLayout.CastDock.UNDER_CHAT, m.draft.cast)
        assertFalse(m.plusShown)
        val card = model(start = EditStop.QUEST)
        card.remove(EditStop.QUEST)
        assertEquals(HomeLayout.CardDock.OFF, card.draft.card)
        card.addBack(EditStop.QUEST)
        assertEquals(HomeLayout.CardDock.ON_MAP, card.draft.card)
    }

    // ---------------------------------------------------------------- menus: layout rows edit the draft, behaviour rows press their setter

    @Test fun menusChangeTheDraftOrPressTheSameSetters() {
        val m = model(start = EditStop.CAST)
        m.press("PAD3")
        assertEquals(EditStop.CAST, m.menu)
        repeat(4) { m.press("PADDRIGHT") }                      // Place: Off | Chat | Map | Across | Split
        assertEquals(HomeLayout.CastDock.SPLIT, m.draft.cast)
        m.press("PADDDOWN"); m.press("PADDLEFT")               // Size: M
        assertEquals(HomeLayout.SlotSize.M, m.draft.castSize)
        m.press("PADDDOWN")
        assertEquals(listOf(HomeEditModel.Fx.Press(HomeEditModel.K_CAST_FIGHT, 0)), m.press("PADDLEFT"))   // In fights: Show
        m.press("PADDDOWN", "PADDDOWN")
        assertEquals(listOf(HomeEditModel.Fx.EditSlots), m.press("PAD1"))
    }

    @Test fun theMenusRowsFollowTheRulings() {
        val v = HomeWidgetMenus.Values(talk = true)
        // 1518 build 3: no Controller menu, no Clock menu (every row of theirs is a Settings > Bottom screen tile); the stops with no
        // menu have no rows
        for (s in EditStop.values().filter { !it.hasMenu }) assertTrue(s.name, HomeWidgetMenus.rows(s, classic, v).isEmpty())
        assertEquals(listOf("Off", "Chat", "Map", "Across", "Split"), HomeWidgetMenus.rows(EditStop.CAST, classic, v).first().words)
        val map = HomeWidgetMenus.rows(EditStop.MAP, classic.copy(chatShare = HomeLayout.Share.THIRD), v)
        assertEquals("the map's width is its own share", 2, map.first { it.key == HomeEditModel.K_MAP_WIDTH }.picked)
        assertEquals(1, map.first { it.key == HomeEditModel.K_MAP_SIDE }.picked)
        val chat = HomeWidgetMenus.rows(EditStop.CHAT, classic, v)
        assertTrue(chat.any { it.key == "release" })
        assertTrue(HomeWidgetMenus.rows(EditStop.CHAT, classic, v.copy(talk = null)).none { it.key == BottomTiles.TALK })
    }

    // ---------------------------------------------------------------- a fight mid-edit

    @Test fun aFightParksTheDraftAndItsEndBringsItBack() {
        val m = model()
        m.press("PADBACK", "PAD1")
        assertEquals(EditStop.CHAT, m.lifted)
        m.park()
        assertTrue(m.parked)
        assertNull("the lift goes with the chrome", m.lifted)
        assertTrue("no key acts while parked", m.press("PADBACK", "PAD2", "PADFORWARD").isEmpty())
        assertTrue(m.draft.mirrored)
        m.resume()
        assertFalse(m.parked)
        assertEquals(EditStop.CHAT, m.cursor)
        assertTrue("the draft came back as it was", m.draft.mirrored)
    }

    // ---------------------------------------------------------------- the top bar stays still (the user's ruling 2026-11-22)

    /** "What the hell is the dpad swapping buttons or clock move? ... remove that": the band has no stop (no D-pad icon, no clock, no
     *  band), and the stops keep the motion keys 1518 gave them (their wiggle phases and paces unchanged) */
    @Test fun theBandHasNoStopsAndTheKeysStay() {
        assertEquals(listOf("CHAT", "MAP", "QUEST", "DIVIDER", "CAST", "PLUS", "ROOMS", "DONE"), EditStop.values().map { it.name })
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 13), EditStop.values().map { it.key })
        assertEquals(setOf(EditStop.QUEST, EditStop.CAST), HomeEditModel.GONE_CHECKED)
        assertTrue("no key is a room's or the party's", EditStop.values().none { it.key == HomeEditMotion.PARTY || it.key >= HomeEditMotion.roomKey(0) })
    }

    // ---------------------------------------------------------------- the fix pass (review findings)

    /** 1518, Settings' "Reset Home to Classic": from every arrangement, Classic's places, the diamonds, L slots, no top-screen fight, the
     *  room bar as today; the bar's own column and Same size stay the player's (as 1517's Classic preset kept them) */
    @Test fun classicIsTheWayBackFromEveryArrangement() {
        for (b in listOf(classic, classic.copy(cast = HomeLayout.CastDock.ABOVE_MAP), classic.copy(cast = HomeLayout.CastDock.OFF),
                classic.copy(fight = HomeLayout.FightBand.SAME, xpBar = false, rooms = RoomBar.DEFAULT.reversed())))
            for ((id, l) in HomeArrangements.on(b)) {
                val c = l.classic()
                assertTrue("$id: Classic's places", c.classicPlace)
                assertEquals(id, HomeArrangements.of(HomeArrangements.CLASSIC, l).copy(xpBar = true, rooms = RoomBar.DEFAULT), c)
                assertEquals(HomeRest.DIAMONDS, c.rest); assertEquals(HomeLayout.SlotSize.L, c.castSize)
                assertTrue(c.fight != HomeLayout.FightBand.TOP); assertTrue(c.xpBar); assertEquals(RoomBar.DEFAULT, c.rooms)
            }
        val fighter = HomeArrangements.of(HomeArrangements.FIGHTER, classic).classic()
        assertEquals(HomeLayout.FightBand.GROW, fighter.fight); assertEquals(HomeLayout.CastDock.UNDER_CHAT, fighter.cast)
        assertEquals("Same size stays the player's", HomeLayout.FightBand.SAME, classic.copy(fight = HomeLayout.FightBand.SAME).classic().fight)
        assertEquals("the bar's column stays", HomeLayout.CastDock.UNDER_MAP, classic.copy(cast = HomeLayout.CastDock.ABOVE_MAP).classic().cast)
        assertEquals("a Classic record is unchanged", classic, classic.classic())
    }

    /** fix round 1: Off picked in the bar's / the card's OWN menu moves the cursor as the red X does ("+", else the chat); the map's
     *  "Quest card" row leaves it on the map; a widget left with no box (Party mode's Hide) moves it at the next check */
    @Test fun offFromItsOwnMenuMovesTheCursor() {
        val v = HomeWidgetMenus.Values()
        val m = model()
        m.openMenu(EditStop.CAST)
        val rows = HomeWidgetMenus.rows(EditStop.CAST, m.draft, v)
        m.menuTouch(rows.indexOfFirst { it.key == HomeEditModel.K_PLACE }, HomeWidgetMenus.CAST_DOCKS.indexOf(HomeLayout.CastDock.OFF), rows)
        assertEquals(HomeLayout.CastDock.OFF, m.draft.cast)
        assertNull("the menu closed", m.menu)
        assertEquals("on \"+\" (shown now)", EditStop.PLUS, m.cursor)
        assertTrue(m.plusShown)
        val q = model()
        q.openMenu(EditStop.QUEST)
        val qr = HomeWidgetMenus.rows(EditStop.QUEST, q.draft, v)
        q.menuTouch(qr.indexOfFirst { it.key == HomeEditModel.K_CARD }, HomeWidgetMenus.CARD_DOCKS.indexOf(HomeLayout.CardDock.OFF), qr)
        assertEquals(HomeLayout.CardDock.OFF, q.draft.card)
        assertNull(q.menu); assertEquals(EditStop.PLUS, q.cursor)
        // another dock from its own menu: the menu stays, the cursor too
        val d = model()
        d.openMenu(EditStop.CAST)
        val dr = HomeWidgetMenus.rows(EditStop.CAST, d.draft, v)
        d.menuTouch(dr.indexOfFirst { it.key == HomeEditModel.K_PLACE }, HomeWidgetMenus.CAST_DOCKS.indexOf(HomeLayout.CastDock.ACROSS), dr)
        assertEquals(EditStop.CAST, d.menu); assertEquals(EditStop.CAST, d.cursor)
        // the card Off from the MAP's menu: the cursor stays on the map
        val mm = model()
        mm.openMenu(EditStop.MAP)
        val mr = HomeWidgetMenus.rows(EditStop.MAP, mm.draft, v)
        mm.menuTouch(mr.indexOfFirst { it.key == HomeEditModel.K_CARD }, HomeWidgetMenus.CARD_DOCKS.indexOf(HomeLayout.CardDock.OFF), mr)
        assertEquals(EditStop.MAP, mm.cursor); assertEquals(EditStop.MAP, mm.menu)
        // the bar slid away by Party mode's Hide (a Press: the draft keeps the bar): no CAST box -> the cursor to the chat ("+" not shown)
        val p = model()
        p.openMenu(EditStop.CAST); p.closeMenu()
        assertEquals(EditStop.CAST, p.cursor)
        assertFalse("its box is there: nothing moves", p.cursorCheck(stops(p.draft)))
        val gone = stops(p.draft).filterKeys { it != EditStop.CAST }
        assertTrue(p.cursorCheck(gone))
        assertEquals(EditStop.CHAT, p.cursor)
        assertFalse(p.cursorCheck(gone))
        // the D-pad works from there as always (no jump to the first stop)
        p.press("PADDDOWN")
        assertEquals(EditStop.CAST, p.cursor)
    }

    @Test fun parkedTouchesDoNothing() {
        val m = model(saved = classic.copy(cast = HomeLayout.CastDock.OFF), start = EditStop.PLUS)
        m.park()
        assertTrue(m.tap(EditStop.PLUS).isEmpty())
        assertNull("no tray opened behind a fight", m.sheet)
        val chat = stops(m.draft).getValue(EditStop.CHAT)
        assertTrue(m.dragStart(EditStop.CHAT, chat.cx, 500f, chat).isEmpty())
        assertEquals(classic.copy(cast = HomeLayout.CastDock.OFF), m.draft)
    }

    /** 1518 (F2): every path that ends or replaces a lift carries the drop's [HomeEditModel.Fx.Lift] (the view plops the old widget:
     *  never left grown, still and raised for the session) - a tap on the map, a tap on a room, a menu, a room dragged, another widget
     *  dragged, the lifted bar removed, its box gone */
    @Test fun everyLiftEndedOrReplacedSaysSo() {
        val lift = HomeEditModel.Fx.Lift
        fun chatLifted(): HomeEditModel = model().also { it.press("PAD1"); assertEquals(EditStop.CHAT, it.lifted) }
        var m = chatLifted()
        assertEquals("a tap on the map", listOf(lift), m.tap(EditStop.MAP)); assertNull(m.lifted); assertEquals(EditStop.MAP, m.cursor)
        m = chatLifted()
        assertEquals("a tap on a room", listOf(lift), m.barTap(2)); assertNull(m.lifted); assertEquals(EditStop.ROOMS, m.cursor)
        m = chatLifted()
        assertEquals("the map's menu", listOf(lift), m.openMenu(EditStop.MAP)); assertNull(m.lifted); assertEquals(EditStop.MAP, m.menu)
        m = chatLifted()
        val tapped = m.tap(EditStop.MAP); assertTrue(lift in tapped)
        assertEquals("a second tap opens the menu (nothing lifted: no drop)", emptyList<HomeEditModel.Fx>(), m.tap(EditStop.MAP))
        m = chatLifted()
        assertEquals("a room dragged: the chat's drop, then the room's lift", listOf(lift, lift),
            m.barDragStart(1, 100f, 1000f, HomeLayoutSolver.Box(60f, 960f, 140f, 1040f)))
        assertEquals(EditStop.ROOMS, m.lifted); assertEquals(m.barRooms[1], m.barLifted)
        m.dragCancel()
        m = chatLifted()
        val map = stops(classic).getValue(EditStop.MAP)
        assertEquals("the map dragged", listOf(lift, lift), m.dragStart(EditStop.MAP, map.cx, (map.t + map.b) / 2f, map)); assertEquals(EditStop.MAP, m.lifted)
        m.dragCancel()
        m = chatLifted()
        assertEquals("the same widget dragged: no drop", listOf(lift), m.dragStart(EditStop.CHAT, 100f, 400f, stops(classic).getValue(EditStop.CHAT)))
        m.dragCancel()
        // the lifted bar removed: its drop first (then the relay of the change)
        m = model(start = EditStop.CAST); m.press("PAD1"); assertEquals(EditStop.CAST, m.lifted)
        val off = m.remove(EditStop.CAST)
        assertEquals(lift, off.first()); assertNull(m.lifted)
        assertTrue(off.any { it is HomeEditModel.Fx.Relay })
        // a room lifted by A, another room dragged: the first room's drop, then the second's lift
        m = model(start = EditStop.ROOMS); m.press("PAD1"); val first = m.barLifted
        assertEquals(listOf(lift, lift), m.barDragStart(3, 300f, 1000f, HomeLayoutSolver.Box(260f, 960f, 340f, 1040f)))
        assertTrue(first != m.barLifted)
        m.dragCancel()
        // the lifted bar's box gone (Party mode's Hide): the lift ends with it
        m = model(start = EditStop.CAST); m.press("PAD1")
        assertTrue(m.cursorCheck(stops(m.draft).filterKeys { it != EditStop.CAST }))
        assertNull(m.lifted); assertNull(m.barLifted)
    }
}
