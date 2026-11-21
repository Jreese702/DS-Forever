package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME, PHASE 2: EDIT MODE'S STATE MACHINE (SPEC.txt section 15 HomeEditModelTest): the B ladder (put back, close, then Save /
 * Discard / Keep editing); the cursor's spatial moves over Home's real boxes; lift / drop / put back and each widget's docks; Select's swap
 * from any stop; a finger's drag with the 48 px hysteresis and the 80 ms hover; the divider's snap; Y's size steps; the chip's flips
 * (Fighter skipped until phase 3); remove and "+"; a fight's park and resume; Start = Save; and nothing that reaches the add-on or a
 * setting before Save (the toolbar's switch and All characters wait in the draft). Pure.
 */
class HomeEditModelTest {
    private val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
    private fun flips(): List<HomeEditModel.Flip> =
        HomePresets.ALL.map { p -> HomeEditModel.Flip(p.id, p.available) { l -> p.apply(l) } }
    private fun model(saved: HomeLayout = classic, start: EditStop = EditStop.CHAT, toolbar: Boolean? = true) =
        HomeEditModel(saved, start, flips(), HomePresets.nameOf(saved), toolbar)

    /** Home's boxes for [l] (the solver's, a focused quest shown) as the edit view gives the cursor, the ribbon under the frame */
    private fun stops(l: HomeLayout, plus: Boolean = false): Map<EditStop, HomeLayoutSolver.Box> {
        val r = HomeLayoutSolver.places(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f))
        val b = r.boxes
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        val bars = b.getValue(HomePart.BARS)
        out[EditStop.BAND] = bars.copy(r = 470f)
        out[EditStop.CLOCK] = HomeLayoutSolver.Box(470f, 12f, 770f, 200f)
        out[EditStop.CHAT] = b.getValue(HomePart.CHAT)
        out[EditStop.MAP] = b.getValue(HomePart.MAP)
        b[HomePart.QUESTS]?.let { out[EditStop.QUEST] = it }
        b[HomePart.STRIP]?.let { s -> out[EditStop.CAST] = b[HomePart.CAST2]?.let { s.copy(r = it.r) } ?: s }
        val c = b.getValue(HomePart.CHAT); val m = b.getValue(HomePart.MAP)
        val x = (minOf(c.r, m.r) + maxOf(c.l, m.l)) / 2f
        val top = minOf(c.t, m.t)
        out[EditStop.DIVIDER] = HomeLayoutSolver.Box(x - 2f, top, x + 2f, top + 40f)
        out[EditStop.CHIP] = HomeLayoutSolver.Box(160f, 937f, 380f, 967f)
        if (plus) out[EditStop.PLUS] = HomeLayoutSolver.Box(394f, 937f, 440f, 967f)
        return out
    }

    private val geo = object : HomeEditModel.Geo {
        override fun columns(l: HomeLayout) = HomeLayoutSolver.columns(l, 1240f, 8f)
        override fun valid(l: HomeLayout) = HomeLayoutRules.valid(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f))
        override val w = 1240f
    }

    private fun HomeEditModel.press(vararg keys: String, l: HomeLayout = draft): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, stops(l, plusShown)) { s -> HomeWidgetMenus.rows(s, draft, HomeWidgetMenus.Values(toolbar = true, toolbarCan = true), toolbarWant) }
        return out
    }

    // ---------------------------------------------------------------- the B ladder

    @Test fun bLeavesAtOnceWhenNothingChanged() {
        val m = model()
        assertEquals(listOf(HomeEditModel.Fx.Leave), m.press("PAD2"))
        assertFalse(m.dirty)
    }

    @Test fun bStepsBackOneLevelThenAsksWithSaveHighlighted() {
        val m = model()
        m.press("PAD1")                                         // A on Chat: lifted
        assertEquals(EditStop.CHAT, m.lifted)
        m.press("PADDRIGHT")                                    // the chat to the right column
        assertEquals(HomeLayout.Side.RIGHT, m.draft.chatSide)
        m.press("PAD2")                                         // B: put back
        assertNull(m.lifted)
        assertEquals(HomeLayout.Side.LEFT, m.draft.chatSide)
        assertFalse(m.dirty)
        m.press("PAD3")                                         // X: the chat's menu
        assertEquals(EditStop.CHAT, m.menu)
        m.press("PAD2")                                         // B: the menu closes
        assertNull(m.menu)
        m.press("PADBACK")                                      // Select: the swap
        assertTrue(m.dirty)
        val fx = m.press("PAD2")                                // B with a change: the prompt, Save highlighted, nothing written
        assertTrue(m.prompt); assertEquals(0, m.promptSel); assertTrue(fx.isEmpty())
        m.press("PAD2")                                         // B on the prompt: keep editing
        assertFalse(m.prompt); assertTrue(m.dirty)
    }

    @Test fun thePromptSavesDiscardsOrKeepsEditing() {
        val m = model(); m.press("PADBACK", "PAD2")
        assertEquals(listOf(HomeEditModel.Fx.Save), m.press("PAD1"))
        val d = model(); d.press("PADBACK", "PAD2", "PADDRIGHT")
        assertEquals(1, d.promptSel)
        assertEquals(listOf(HomeEditModel.Fx.Leave), d.press("PAD1"))
        assertEquals("Discard puts the saved layout back", classic, d.draft)
        val k = model(); k.press("PADBACK", "PAD2", "PADDLEFT")
        assertEquals(2, k.promptSel)
        assertTrue(k.press("PAD1").isEmpty()); assertFalse(k.prompt); assertTrue(k.dirty)
    }

    @Test fun startSavesFromEveryLevel() {
        assertEquals(listOf(HomeEditModel.Fx.Save), model().press("PADFORWARD"))
        val lifted = model(); lifted.press("PAD1"); assertTrue(HomeEditModel.Fx.Save in lifted.press("PADFORWARD")); assertNull(lifted.lifted)
        val menu = model(); menu.press("PAD3"); assertTrue(HomeEditModel.Fx.Save in menu.press("PADFORWARD"))
    }

    // ---------------------------------------------------------------- the cursor

    @Test fun theCursorMovesSpatiallyBetweenTheWidgets() {
        val m = model()
        assertEquals(EditStop.CHAT, m.cursor)
        m.press("PADDRIGHT"); assertEquals("straight across to the map (the divider is no Left / Right stop)", EditStop.MAP, m.cursor)
        m.press("PADDUP"); assertEquals("the card over the map is above its centre", EditStop.QUEST, m.cursor)
        m.press("PADDUP"); assertEquals(EditStop.CLOCK, m.cursor)
        m.press("PADDLEFT"); assertEquals(EditStop.BAND, m.cursor)
        m.press("PADDDOWN"); assertEquals(EditStop.CHAT, m.cursor)
        m.press("PADDDOWN"); assertEquals("the bar under the chat", EditStop.CAST, m.cursor)
        m.press("PADDDOWN"); assertEquals("the ribbon's layout chip", EditStop.CHIP, m.cursor)
        m.press("PADDUP"); assertEquals(EditStop.CAST, m.cursor)
        m.press("PADDUP"); assertEquals(EditStop.CHAT, m.cursor)
        m.press("PADDUP"); assertEquals("Up from the chat: the band, never the divider", EditStop.BAND, m.cursor)
        // the divider is reached from above (the clock over the columns' gap), Left / Right there step the width
        val d = model(start = EditStop.CLOCK, saved = classic.copy(card = HomeLayout.CardDock.OFF))
        d.press("PADDDOWN"); assertEquals(EditStop.DIVIDER, d.cursor)
        d.press("PADDRIGHT"); assertEquals(HomeLayout.Share.TWO_THIRDS, d.draft.chatShare); assertEquals(EditStop.DIVIDER, d.cursor)
        d.press("PADDDOWN"); assertTrue("down from the divider: a column", d.cursor == EditStop.CHAT || d.cursor == EditStop.MAP)
        val col = d.cursor
        d.press(if (col == EditStop.CHAT) "PADDLEFT" else "PADDRIGHT"); assertEquals("nothing beyond the outer column", col, d.cursor)
    }

    @Test fun theDividerStepsTheWidthAndTheChipFlips() {
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
        val c = model(start = EditStop.CHIP)
        c.press("PADDRIGHT")
        assertEquals(HomePresets.MIRRORED, c.chipName)
        assertEquals(HomeLayout.Side.RIGHT, c.draft.chatSide)
    }

    // ---------------------------------------------------------------- lift, docks, size, swap

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
        val seen = ArrayList<HomeLayout.CastDock>()
        repeat(4) { b.press("PADDDOWN"); seen += b.draft.cast }
        assertEquals(listOf(HomeLayout.CastDock.UNDER_MAP, HomeLayout.CastDock.ACROSS, HomeLayout.CastDock.SPLIT, HomeLayout.CastDock.UNDER_CHAT), seen)
        b.press("PADDRIGHT")
        assertEquals("Right = the right column's dock (the map's)", HomeLayout.CastDock.UNDER_MAP, b.draft.cast)
    }

    @Test fun ySizesTheSelectedWidget() {
        val m = model()
        m.press("PAD4"); assertEquals(HomeLayout.Share.TWO_THIRDS, m.draft.chatShare)
        m.press("PAD4"); assertEquals(HomeLayout.Share.THIRD, m.draft.chatShare)
        val band = model(start = EditStop.BAND); band.press("PAD4"); assertEquals(HomeRest.ROW, band.draft.rest)
        val bar = model(start = EditStop.CAST); bar.press("PAD4"); assertEquals(HomeLayout.SlotSize.S, bar.draft.castSize)
        val card = model(start = EditStop.QUEST)
        assertEquals("the card's fold is the app-wide setting (the card's own tap)", listOf(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, -1)), card.press("PAD4"))
    }

    @Test fun selectSwapsFromAnyStop() {
        for (s in listOf(EditStop.BAND, EditStop.CLOCK, EditStop.MAP, EditStop.CAST, EditStop.CHIP)) {
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
        m.tap(EditStop.CHIP); assertEquals(HomeEditModel.Sheet.LAYOUTS, m.sheet)
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
        val band = model(start = EditStop.BAND)
        band.press("PAD1")                                      // A on the band: its menu
        assertEquals(EditStop.BAND, band.menu)
        band.press("PADDDOWN", "PADDDOWN")                      // Swing timer
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.SWING, 1)), band.press("PADDRIGHT"))
        band.press("PADDDOWN", "PADDDOWN")                      // DS WoW toolbar: kept for Save, nothing sent
        assertTrue(band.press("PADDRIGHT").isEmpty())
        assertEquals(false, band.toolbarWant)
        assertTrue(band.dirty)
    }

    @Test fun theMenusRowsFollowTheRulings() {
        val v = HomeWidgetMenus.Values(toolbar = true, toolbarCan = true, talk = true)
        val band = HomeWidgetMenus.rows(EditStop.BAND, classic, v)
        assertEquals("D1: Grow is the default; Top screen (phase 3) is offered, opt-in", listOf("Grow", "Same size", "Top screen"),
            band.first { it.key == HomeEditModel.K_FIGHT }.words)
        assertEquals(0, band.first { it.key == HomeEditModel.K_FIGHT }.picked)
        assertEquals("a Top screen record shows its pick", 2, HomeWidgetMenus.rows(EditStop.BAND, classic.copy(fight = HomeLayout.FightBand.TOP), v)
            .first { it.key == HomeEditModel.K_FIGHT }.picked)
        assertEquals(listOf("Off", "Chat", "Map", "Across", "Split"), HomeWidgetMenus.rows(EditStop.CAST, classic, v).first().words)
        val map = HomeWidgetMenus.rows(EditStop.MAP, classic.copy(chatShare = HomeLayout.Share.THIRD), v)
        assertEquals("the map's width is its own share", 2, map.first { it.key == HomeEditModel.K_MAP_WIDTH }.picked)
        assertEquals(1, map.first { it.key == HomeEditModel.K_MAP_SIDE }.picked)
        val chat = HomeWidgetMenus.rows(EditStop.CHAT, classic, v)
        assertTrue(chat.any { it.key == "release" })
        assertTrue(HomeWidgetMenus.rows(EditStop.CHAT, classic, v.copy(talk = null)).none { it.key == BottomTiles.TALK })
        assertEquals(5, HomeWidgetMenus.rows(EditStop.CLOCK, classic, v).size)
    }

    // ---------------------------------------------------------------- flips, the Layouts sheet

    @Test fun flipsIncludeFighterSincePhaseThree() {
        val m = model(start = EditStop.CHIP)
        val seen = ArrayList<String>()
        repeat(HomePresets.ALL.size + 1) { m.press("PADRSHOULDER"); seen += m.chipName }
        assertEquals(listOf(HomePresets.MIRRORED, HomePresets.BIG_MAP, HomePresets.BIG_CHAT, HomePresets.COMPACT, HomePresets.FIGHTER,
            HomePresets.CLASSIC, HomePresets.MIRRORED), seen)
        m.press("PADLSHOULDER")
        assertEquals(HomePresets.CLASSIC, m.chipName)
        m.press("PADLSHOULDER")
        assertEquals("Fighter: In fights = Top screen in the draft (D1: the player's own opt-in)", HomeLayout.FightBand.TOP, m.draft.fight)
        // every flip is made from the layout edit mode opened with (Compact's thin row does not stay on Classic)
        val c = model(start = EditStop.CHIP)
        repeat(4) { c.press("PADDRIGHT") }
        assertEquals(HomeRest.ROW, c.draft.rest)
        c.press("PADDRIGHT")
        assertEquals(HomeRest.DIAMONDS, c.draft.rest)
    }

    @Test fun theSheetUsesSavesHereAndCopiesToAllCharacters() {
        val m = model(start = EditStop.CHIP)
        m.press("PAD1")
        assertEquals(HomeEditModel.Sheet.LAYOUTS, m.sheet)
        assertEquals("the sheet opens on the layout in use", 0, m.sheetSel)
        m.press("PADDRIGHT", "PADDRIGHT")                       // Big map
        m.press("PAD1")
        assertNull(m.sheet)
        assertEquals(HomePresets.BIG_MAP, m.chipName)
        m.press("PAD1")
        m.sheetSel = 5
        assertEquals(listOf(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), m.press("PAD1"))   // Fighter: offered since phase 3
        assertNull(m.sheet)
        assertEquals(HomePresets.FIGHTER, m.chipName)
        assertEquals(HomeLayout.FightBand.TOP, m.draft.fight)
        m.press("PAD1")                                          // the sheet again
        m.sheetSel = 6
        assertEquals(listOf(HomeEditModel.Fx.SaveSlot(0)), m.press("PAD3"))         // X on slot 1: Save here
        m.sheetSel = m.sheetCount() - 1
        m.press("PAD1")
        assertTrue(m.allChars)
        assertTrue(m.dirty)
        m.press("PAD2")
        assertNull(m.sheet)
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

    @Test fun nothingReachesTheAddOnBeforeSave() {
        val m = model(start = EditStop.BAND)
        val fx = ArrayList<HomeEditModel.Fx>()
        fx += m.press("PADBACK", "PAD4", "PAD1", "PADDDOWN", "PADDDOWN", "PADDDOWN", "PADDDOWN", "PADDRIGHT", "PAD2")
        fx += m.press("PADDDOWN", "PADDDOWN", "PAD1", "PADDDOWN", "PAD2", "PADRSHOULDER")
        assertTrue(fx.all { it is HomeEditModel.Fx.Relay || it is HomeEditModel.Fx.Lift || it is HomeEditModel.Fx.Press })
        assertTrue("only app-wide behaviour rows press setters", fx.filterIsInstance<HomeEditModel.Fx.Press>().none { it.key == BottomTiles.TOOLBAR })
        assertEquals(false, m.toolbarWant)
        assertEquals(listOf(HomeEditModel.Fx.Save), m.press("PADFORWARD"))
    }

    // ---------------------------------------------------------------- the fix pass (review findings)

    @Test fun startOnThePromptSaves() {
        val m = model()
        m.press("PADBACK", "PAD2")
        assertTrue(m.prompt)
        assertEquals("Start = Save in every edit context (section 12), the prompt too", listOf(HomeEditModel.Fx.Save), m.press("PADFORWARD"))
        assertFalse(m.prompt)
    }

    @Test fun classicIsTheWayBackFromEveryPreset() {
        val cl = HomePresets.byId(HomePresets.CLASSIC)!!
        val fighter = HomePresets.byId(HomePresets.FIGHTER)!!.apply(classic)
        val a = cl.apply(fighter)
        assertEquals(HomeLayout.FightBand.GROW, a.fight)
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, a.cast)
        assertEquals(HomePresets.CLASSIC, HomePresets.nameOf(a))
        val b = cl.apply(HomePresets.byId(HomePresets.COMPACT)!!.apply(classic))
        assertEquals(HomeRest.DIAMONDS, b.rest)
        assertEquals(HomeLayout.SlotSize.L, b.castSize)
        assertEquals(HomePresets.CLASSIC, HomePresets.nameOf(b))
        assertEquals("Same size stays the player's", HomeLayout.FightBand.SAME, cl.apply(classic.copy(fight = HomeLayout.FightBand.SAME)).fight)
        // saved as Fighter: Classic from the sheet, and the record Save would write is named Classic, the top-screen bar off
        val m = model(saved = fighter, start = EditStop.CHIP)
        m.press("PAD1"); m.sheetUse(0)
        assertEquals(HomePresets.CLASSIC, m.chipName)
        assertEquals(HomePresets.CLASSIC, HomePresets.nameOf(m.draft))
        assertTrue(m.draft.fight != HomeLayout.FightBand.TOP)
    }

    @Test fun theSheetsMineRowIsARowOfItsOwn() {
        val m = model(start = EditStop.CHIP)
        m.press("PAD1")
        val p = HomePresets.ALL.size
        m.sheetSel = p
        m.press("PADDDOWN"); assertEquals("Down on Mine stays (never sideways to All characters)", p, m.sheetSel)
        m.sheetSel = p + HomePresets.SLOTS
        m.press("PADDUP"); assertEquals("Up from All characters: the preset above it", p - 1, m.sheetSel)
        m.sheetSel = p
        m.press("PADDUP"); assertEquals("Up from slot 1: the last presets' row, its first", ((p - 1) / HomeEditModel.SHEET_COLS) * HomeEditModel.SHEET_COLS, m.sheetSel)
        m.sheetSel = 1
        m.press("PADDDOWN"); assertEquals(1 + HomeEditModel.SHEET_COLS, m.sheetSel)
        m.press("PADDDOWN"); assertEquals("the last presets' row: down to Mine, same column", p + 1, m.sheetSel)
    }

    @Test fun aSlotSavedThisSessionIsUsedOnEveryRoute() {
        val slots = arrayOfNulls<HomeLayout>(HomePresets.SLOTS)
        val fl = flips() + (0 until HomePresets.SLOTS).map { i ->
            HomeEditModel.Flip("${HomeEditModel.SLOT_MARK}${i + 1}", { slots[i] != null }) { l -> slots[i]?.copy(name = l.name) ?: l } }
        val m = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true, slotNow = { slots[it] })
        m.press("PAD1")
        val p = HomePresets.ALL.size
        m.sheetSel = p
        assertEquals("an empty slot: refused", listOf(HomeEditModel.Fx.Refuse(EditStop.CHIP)), m.press("PAD1"))
        assertEquals(listOf(HomeEditModel.Fx.SaveSlot(0)), m.press("PAD3"))
        slots[0] = classic.swapped()                              // what Save here kept
        m.press("PAD1")
        assertTrue("A uses the slot as it is NOW", m.draft.mirrored)
        assertEquals("the chip says 1 (as the Settings tile), never #1", "1", m.chipName)
        m.press("PADLSHOULDER")
        m.press("PADRSHOULDER")
        assertEquals("the chip's flips reach it too", "1", m.chipName)
    }

    /** fix round 1: Off picked in the bar's / the card's OWN menu moves the cursor as the red X does ("+", else the chip); the map's
     *  "Quest card" row leaves it on the map; a widget left with no box (Party mode's Hide) moves it at the next check */
    @Test fun offFromItsOwnMenuMovesTheCursor() {
        val v = HomeWidgetMenus.Values(toolbar = true, toolbarCan = true)
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
        // the bar slid away by Party mode's Hide (a Press: the draft keeps the bar): no CAST box -> the cursor to the chip ("+" not shown)
        val p = model()
        p.openMenu(EditStop.CAST); p.closeMenu()
        assertEquals(EditStop.CAST, p.cursor)
        assertFalse("its box is there: nothing moves", p.cursorCheck(stops(p.draft)))
        val gone = stops(p.draft).filterKeys { it != EditStop.CAST }
        assertTrue(p.cursorCheck(gone))
        assertEquals(EditStop.CHIP, p.cursor)
        assertFalse(p.cursorCheck(gone))
        // the chip's D-pad works from there as always (no jump to the first stop)
        p.press("PADDUP")
        assertTrue(p.cursor != EditStop.CHIP)
    }

    /** fix round 1: a preset's fold (Compact, Fighter: folded) is pressed LIVE when it is picked - never at Save because the saved record
     *  still holds the preset (the player's own unfold kept) */
    @Test fun aPresetsFoldOnlyWhenPicked() {
        val fl = HomePresets.ALL.map { p -> HomeEditModel.Flip(p.id, p.available, p.folded) { l -> p.apply(l) } }
        val compact = HomePresets.byId(HomePresets.COMPACT)!!.apply(classic)
        val m = HomeEditModel(compact, EditStop.QUEST, fl, HomePresets.nameOf(compact), true)
        assertEquals(HomePresets.COMPACT, m.chipName)
        val y = m.sizeStep(EditStop.QUEST)
        assertEquals("Y on the card: the fold toggled (app-wide), the draft untouched", listOf(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, -1)), y)
        assertEquals(listOf(HomeEditModel.Fx.Save), m.saveLeave())
        assertFalse("nothing picked: Save leaves the fold alone", m.foldPicked)
        // picking Compact from Classic: the fold pressed with the flip (the preview shows it), Mirrored presses none
        val n = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true)
        var fx: List<HomeEditModel.Fx> = emptyList()
        repeat(fl.size) { if (n.chipName != HomePresets.COMPACT) fx = n.flip(1) }
        assertEquals(HomePresets.COMPACT, n.chipName)
        assertTrue(fx.contains(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 0)))
        assertTrue(n.foldPicked)
        val r = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true)
        val mir = r.flip(1)
        assertEquals(HomePresets.MIRRORED, r.chipName)
        assertTrue(mir.none { it is HomeEditModel.Fx.Press })
        assertFalse(r.foldPicked)
        // the Layouts sheet too
        val s = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true)
        val fi = fl.filter { !it.name.startsWith(HomeEditModel.SLOT_MARK) }.indexOfFirst { it.name == HomePresets.FIGHTER }
        assertTrue(s.sheetUse(fi).contains(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 0)))
    }

    @Test fun parkedTouchesDoNothing() {
        val m = model(start = EditStop.CHIP)
        m.park()
        assertTrue(m.flip(1).isEmpty())
        assertTrue(m.tap(EditStop.CHIP).isEmpty())
        assertNull("no sheet opened behind a fight", m.sheet)
        assertTrue(m.sheetUse(0).isEmpty())
        assertEquals(classic, m.draft)
    }

    /** fix round 2: browsing past Compact / Fighter never leaves the card folded - a landing with no fold of its own (a preset whose
     *  folded is null, a slot) puts back the fold edit mode opened with; the preset that IS landed on keeps its own */
    @Test fun aPassedOverPresetsFoldIsPutBack() {
        val fl = HomePresets.ALL.map { p -> HomeEditModel.Flip(p.id, p.available, p.folded) { l -> p.apply(l) } }
        val m = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true, foldOpen = false)
        val seen = ArrayList<HomeEditModel.Fx>()
        repeat(fl.size) { if (m.chipName != HomePresets.COMPACT) seen += m.flip(1) }
        assertEquals(HomePresets.COMPACT, m.chipName)
        assertTrue("Compact's fold pressed live", seen.contains(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 0)))
        val back = m.flip(-1)
        assertEquals(HomePresets.BIG_CHAT, m.chipName)
        assertEquals("back to Big chat (no fold): the card open again, as edit mode opened",
            listOf(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 1)), back.filterIsInstance<HomeEditModel.Fx.Press>())
        assertTrue("a second landing without a fold presses nothing more", m.flip(-1).none { it is HomeEditModel.Fx.Press })
        // Compact -> Fighter (both fold): Fighter's own fold, never the open one in between
        val f = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true, foldOpen = false)
        repeat(fl.size) { if (f.chipName != HomePresets.COMPACT) f.flip(1) }
        val toFighter = f.flip(1)
        assertEquals(HomePresets.FIGHTER, f.chipName)
        assertEquals(listOf(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 0)), toFighter.filterIsInstance<HomeEditModel.Fx.Press>())
        // the card was folded when edit mode opened: the put-back folds it (it never unfolds a card the player had folded)
        val g = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true, foldOpen = true)
        repeat(fl.size) { if (g.chipName != HomePresets.COMPACT) g.flip(1) }
        assertEquals(listOf(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 0)), g.flip(-1).filterIsInstance<HomeEditModel.Fx.Press>())
        // the Layouts sheet: Compact, then a slot -> the open fold again
        val slot = HomePresets.byId(HomePresets.MIRRORED)!!.apply(classic)
        val s = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true, slotNow = { if (it == 0) slot else null }, foldOpen = false)
        val ci = fl.filter { !it.name.startsWith(HomeEditModel.SLOT_MARK) }.indexOfFirst { it.name == HomePresets.COMPACT }
        assertTrue(s.sheetUse(ci).contains(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 0)))
        assertTrue(s.sheetUse(s.sheetCount() - HomePresets.SLOTS - 1).contains(HomeEditModel.Fx.Press(HomeEditModel.K_FOLD, 1)))
        // the fold not known (a caller that gives none): no put-back, as before
        val n = HomeEditModel(classic, EditStop.CHIP, fl, HomePresets.nameOf(classic), true)
        repeat(fl.size) { if (n.chipName != HomePresets.COMPACT) n.flip(1) }
        assertTrue(n.flip(-1).none { it is HomeEditModel.Fx.Press })
    }

    /** fix round 2: the cursor on the Clock when LB / RB lands on a thin-row layout (no clock): it falls back to the band (its menu
     *  closed), never left on a widget with no box (no gold box drawn, A opening a menu for a clock not shown) */
    @Test fun theClockCursorFallsBackToTheBand() {
        val m = model()
        m.openMenu(EditStop.CLOCK)
        assertEquals(EditStop.CLOCK, m.cursor)
        assertFalse("its box is there: nothing moves", m.cursorCheck(stops(m.draft)))
        val gone = stops(m.draft).filterKeys { it != EditStop.CLOCK }
        assertTrue(m.cursorCheck(gone))
        assertEquals(EditStop.BAND, m.cursor)
        assertNull("the clock's menu closed", m.menu)
        assertFalse(m.cursorCheck(gone))
        assertTrue(EditStop.CLOCK in HomeEditModel.GONE_CHECKED)
    }
}
