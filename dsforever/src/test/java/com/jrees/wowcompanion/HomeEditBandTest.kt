package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TOP BAR'S PIECES IN EDIT MODE (the user's rulings 2026-11-22, the picker's ALL FOUR - nothing on the band moves or swaps, they only
 * change what the top bar shows and how it looks): P1 the D-pad's and the ABXY's pencils and their speech bubbles, P2 the clock's extras
 * as apps (a "-" hides, "+" opens the tray of the hidden ones), P3 the Look chip, P4 the ribbon's Fight preview and its pencil. The
 * controller's walk over them (Left / Right along the band, never trapped; the ribbon's "+" <-> Fight <-> Done), B closing a bubble or the
 * tray, and every one of them a BEHAVIOUR row ([HomeEditModel.Fx.Press]: Settings' own setters - they autosave and Discard leaves them,
 * as 1518 rules for behaviour rows). Pure.
 */
class HomeEditBandTest {
    private val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
    private fun B(l: Float, t: Float, r: Float, b: Float) = HomeLayoutSolver.Box(l, t, r, b)

    /** Home's stops as the edit view gives them: the page's (the solver's boxes), the ribbon's ("+", Fight, Done), the room bar, and the
     *  band's pieces for the look (Diamonds at rest: the extras' row; the thin row: none; the preview: the fight pencil, no page) */
    private fun stops(l0: HomeLayout, m: HomeEditModel): Map<EditStop, HomeLayoutSolver.Box> {
        // (edit tabs: the tab's page; its widgets are stops in the fight look too - D4 went with the tabs)
        val l = if (l0 === m.draft) m.page else l0
        val r = HomeLayoutSolver.places(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f, takeover = m.fightPreview))
        val b = r.boxes
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        run {
            out[EditStop.CHAT] = b.getValue(HomePart.CHAT)
            out[EditStop.MAP] = b.getValue(HomePart.MAP)
            b[HomePart.QUESTS]?.let { out[EditStop.QUEST] = it }
            b[HomePart.STRIP]?.let { s -> out[EditStop.CAST] = b[HomePart.CAST2]?.let { s.copy(r = it.r) } ?: s }
            val c = b.getValue(HomePart.CHAT); val mp = b.getValue(HomePart.MAP)
            val x = (minOf(c.r, mp.r) + maxOf(c.l, mp.l)) / 2f
            val top = minOf(c.t, mp.t)
            out[EditStop.DIVIDER] = B(x - 2f, top, x + 2f, top + 40f)
        }
        if (m.plusShown) out[EditStop.PLUS] = B(14f, 937f, 60f, 967f)
        if (m.tab == HomeTab.PARTY) out[EditStop.FIGHT] = B(426f, 937f, 536f, 967f)   // the Party mode tab's alone (edit tabs)
        out[EditStop.DONE] = B(1080f, 937f, 1230f, 967f)
        val shared = !m.tab.fight                                            // (edit tabs: the shared pieces sit still on the fight tabs)
        if (shared) out[EditStop.ROOMS] = B(8f, 969f, 1232f, 1080f)
        val diamonds = l.rest == HomeRest.DIAMONDS
        if (diamonds) {
            if (shared) { out[EditStop.PAD_PEN] = B(379f, 12f, 419f, 52f); out[EditStop.FACE_PEN] = B(804f, 14f, 844f, 54f) }
            if (m.fightPreview) out[EditStop.FIGHT_PEN] = B(450f, 12f, 490f, 52f)
            else { if (shared) out[EditStop.LOOK] = B(450f, 14f, 514f, 50f); out[EditStop.EXTRAS] = B(440f, 100f, 800f, 198f) }
        } else {
            if (shared) { out[EditStop.PAD_PEN] = B(582f, 24f, 618f, 60f); out[EditStop.FACE_PEN] = B(622f, 24f, 658f, 60f) }
            if (m.fightPreview) out[EditStop.FIGHT_PEN] = B(588f, 66f, 652f, 102f) else if (shared) out[EditStop.LOOK] = B(588f, 66f, 652f, 102f)
        }
        return out
    }
    private val values = HomeWidgetMenus.Values(toolbarOn = true, toolbarAvail = true, talk = true, ct = null)
    private fun HomeEditModel.press(vararg keys: String, v: HomeWidgetMenus.Values = values): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, stops(draft, this)) { s -> HomeWidgetMenus.rows(s, page, v, tab) }
        return out
    }
    private fun model(l: HomeLayout = classic, start: EditStop = EditStop.CHAT, tab: HomeTab = HomeTab.OUT) =
        HomeEditModel(l, start, tab).also { it.bandIs(HomeEditModel.EXTRA_KEYS, emptyList()) }
    /** the ribbon's Fight toggle and its preview: the Party mode tab's (edit tabs) */
    private fun party(l: HomeLayout = classic, start: EditStop = EditStop.CHAT) = model(l, start, HomeTab.PARTY)

    @Test fun theBandsPiecesAreStopsWithFreeKeys() {
        val band = listOf(EditStop.PAD_PEN, EditStop.FACE_PEN, EditStop.LOOK, EditStop.EXTRAS, EditStop.FIGHT_PEN)
        assertEquals(band.toSet(), EditStop.values().filter { it.band }.toSet())
        assertEquals("the 1518 stops keep their keys", listOf(2, 3, 4, 5, 6, HomeEditMotion.PARTY, 7, 8, 13),
            EditStop.values().take(9).map { it.key })
        assertEquals(EditStop.values().size, EditStop.values().map { it.key }.toSet().size)
        assertTrue("every key under the rooms'", EditStop.values().all { it.key < HomeEditMotion.roomKey(0) })
        assertTrue("nothing on the band moves or is removed", band.none { it.movable || it.removable } && !EditStop.FIGHT.movable)
        // (party sizing, 2026-11-23: the party list's own pencil too - no band piece)
        assertEquals(setOf(EditStop.PAD_PEN, EditStop.FACE_PEN, EditStop.FIGHT_PEN, EditStop.PARTY_PEN), EditStop.values().filter { it.pencil }.toSet())
        assertFalse(EditStop.PARTY_PEN.band || EditStop.PARTY_PEN.movable || EditStop.PARTY_PEN.removable)
        assertTrue(HomeEditModel.GONE_CHECKED.containsAll(band))
        // the extras' wiggle keys: after the room bar's "+", each its own, all small pictures' pace
        val keys = HomeEditModel.EXTRA_KEYS.indices.map { HomeEditMotion.extraKey(it) } + HomeEditMotion.PLUS_EXTRA
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it > HomeEditMotion.PLUS_ROOM && it < HomeEditMotion.KEYS })
        for (k in keys) assertEquals(HomeEditMotion.hz(k), HomeEditMotion.hzFor(k, 24f), 0f)
    }

    /** Left / Right walk the band's pieces in order (the extras' own cursor between), no wrap; Up does nothing; Down goes to the page;
     *  from every stop in every look every press lands on a stop that has a box (the cursor is never trapped nor lost) */
    @Test fun theBandWalksLeftToRightAndNeverTraps() {
        val m = model(start = EditStop.PAD_PEN)
        m.press("PADDRIGHT"); assertEquals(EditStop.LOOK, m.cursor)
        m.press("PADDRIGHT"); assertEquals(EditStop.EXTRAS, m.cursor); assertEquals(0, m.extraSel)
        assertEquals("feat/extras-polish: the gold line first (the chip's, over the row)", "gold", m.extra)
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT"); assertEquals(EditStop.EXTRAS, m.cursor); assertEquals("pet", m.extra)
        m.press("PADDRIGHT"); assertEquals(EditStop.FACE_PEN, m.cursor)
        m.press("PADDRIGHT"); assertEquals("no wrap", EditStop.FACE_PEN, m.cursor)
        m.press("PADDLEFT"); assertEquals(EditStop.EXTRAS, m.cursor); assertEquals("from the right: the last", "pet", m.extra)
        m.press("PADDUP"); assertEquals("Up on the band: nothing", EditStop.EXTRAS, m.cursor)
        m.press("PADDDOWN"); assertFalse("Down: the page", m.cursor.band)
        // Up from the divider: the piece nearest the page's centre
        val d = model(start = EditStop.DIVIDER)
        d.press("PADDUP"); assertTrue(d.cursor.band)
        // every look, every stop, every press
        // (edit tabs: every tab - In combat always in the fight look, Party mode with its Fight toggle off and on)
        for (l in listOf(classic, classic.copy(rest = HomeRest.ROW))) for (tab in HomeTab.values()) for (preview in listOf(false, true)) {
            if (preview && tab != HomeTab.PARTY) continue
            val probe = model(l, tab = tab)
            if (preview) probe.toggleFight()
            for (s in stops(l, probe).keys) for (k in listOf("PADDLEFT", "PADDRIGHT", "PADDUP", "PADDDOWN")) {
                val t = model(l, start = s, tab = tab)
                if (preview) { t.toggleFight(); t.startOn(s, false) }
                if (s == EditStop.DONE || s == EditStop.ROOMS || s == EditStop.DIVIDER) continue   // their own rules: below
                t.press(k)
                assertTrue("${l.rest} $tab preview=$preview: $s $k -> ${t.cursor} (no box)", t.cursor in stops(t.draft, t))
            }
        }
    }

    /** the ribbon's row: "+" <-> Fight <-> Done; Up from the room bar is Fight, Down from Fight the room bar, Left from Done is Fight */
    @Test fun theRibbonRowWalksPlusFightDone() {
        val m = party(classic.copy(card = HomeLayout.CardDock.OFF), start = EditStop.PLUS)
        assertTrue(m.plusShown)
        m.press("PADDRIGHT"); assertEquals(EditStop.FIGHT, m.cursor)
        m.press("PADDRIGHT"); assertEquals(EditStop.DONE, m.cursor)
        m.press("PADDLEFT"); assertEquals(EditStop.FIGHT, m.cursor)
        m.press("PADDLEFT"); assertEquals(EditStop.PLUS, m.cursor)
        // (edit tabs: the room bar sits still on the Party mode tab - Up from Fight goes to the page)
        m.press("PADDRIGHT", "PADDUP"); assertFalse("Up from Fight: the page", m.cursor == EditStop.FIGHT || m.cursor == EditStop.ROOMS)
        val d = model(start = EditStop.DONE)
        d.press("PADDDOWN"); assertEquals("Done + Down: the room bar's end", EditStop.ROOMS, d.cursor)
        // (edit tabs: no Fight toggle on Out of combat - "+" goes straight to Done along the ribbon)
        val o = model(classic.copy(card = HomeLayout.CardDock.OFF), start = EditStop.PLUS)
        o.press("PADDRIGHT"); assertEquals(EditStop.DONE, o.cursor)
    }

    /** A on a pencil (or X) opens its bubble with Settings' rows; the toolbar's talk rows only while it is on; Top screen cannot be picked
     *  without the add-on's Wings word; B closes the bubble with the cursor still on its pencil */
    @Test fun pencilsOpenBubblesWithTheirRows() {
        val m = model(start = EditStop.PAD_PEN)
        m.press("PAD1"); assertEquals(EditStop.PAD_PEN, m.menu)
        val pad = HomeWidgetMenus.rows(EditStop.PAD_PEN, m.draft, values)
        assertEquals(listOf(BottomTiles.TOOLBAR, BottomTiles.TALK, "release"), pad.map { it.key })
        assertEquals(listOf(BottomTiles.TOOLBAR), HomeWidgetMenus.rows(EditStop.PAD_PEN, m.draft, values.copy(talk = null)).map { it.key })
        assertFalse("no Spells add-on to switch it: dimmed",
            HomeWidgetMenus.rows(EditStop.PAD_PEN, m.draft, values.copy(toolbarAvail = false)).first().enabled)
        // a press: the toolbar Off - Settings' own key
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.TOOLBAR, 1)), m.press("PADDRIGHT"))
        m.press("PAD2"); assertNull("B closes the bubble", m.menu); assertEquals(EditStop.PAD_PEN, m.cursor)
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT")
        assertEquals(EditStop.FACE_PEN, m.cursor)
        m.press("PAD3"); assertEquals("X opens it too", EditStop.FACE_PEN, m.menu)
        assertEquals(listOf(BottomTiles.REST, BottomTiles.RANK), HomeWidgetMenus.rows(EditStop.FACE_PEN, m.draft, values).map { it.key })
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.REST, HomeRest.ROW.ordinal)), m.press("PADDLEFT"))
        m.press("PADDDOWN")
        assertEquals("rank badges On", listOf(HomeEditModel.Fx.Press(BottomTiles.RANK, 0)), m.press("PADDLEFT"))
        m.press("PAD2"); assertNull(m.menu)
        // the fight pencil (edit tabs: the In combat tab's - its Combat bars a layout row of the tab, never a press)
        m.tabTo(HomeTab.COMBAT); m.startOn(EditStop.FIGHT_PEN, false)
        m.press("PAD1"); assertEquals(EditStop.FIGHT_PEN, m.menu)
        val f = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.page, values, m.tab)
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.SWING), f.map { it.key })
        assertEquals("no Wings word: Top screen dimmed", setOf(0), f[0].off)
        assertEquals(listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Relay(Motion.MODULE_OPEN)), m.press("PADDRIGHT"))       // No grow
        assertEquals(HomeLayout.FightBand.SAME, m.page.fight)
        m.press("PADDLEFT"); assertEquals(HomeLayout.FightBand.GROW, m.page.fight)
        assertEquals("Top screen refused", listOf(HomeEditModel.Fx.Refuse(EditStop.FIGHT_PEN)), m.press("PADDLEFT"))
        assertTrue("with the Wings word: pickable", HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.page, values.copy(ct = 1), m.tab)[0].off.isEmpty())
    }

    /** fix round (the review: with no Wings word A on In fights went Grow -> Same size, then stuck on the dimmed Top screen, refused every
     *  time): A's cycle steps over a dimmed word and wraps; Left / Right step over it too, and only dimmed words that way is refused */
    @Test fun aSkipsADimmedWordAndWraps() {
        // (edit tabs: the Party mode tab's Fight toggle, its Combat bars Top screen | Bottom screen | No grow - the tab's own)
        val m = party(start = EditStop.FIGHT)
        m.press("PAD1"); m.startOn(EditStop.FIGHT_PEN, false); m.press("PAD1")
        assertEquals(EditStop.FIGHT_PEN, m.menu)
        assertEquals(HomeLayout.FightBand.SAME, m.page.fight)                // No grow (R0's, a record of before the tabs)
        m.press("PAD1"); assertEquals("No grow -> past the dimmed Top screen -> Bottom screen", HomeLayout.FightBand.GROW, m.page.fight)
        m.press("PAD1"); assertEquals("Bottom screen -> No grow", HomeLayout.FightBand.SAME, m.page.fight)
        m.press("PAD1", v = values.copy(ct = 1)); assertEquals("with the Wings word Top screen is next", HomeLayout.FightBand.TOP, m.page.fight)
        // a row whose middle word is dimmed: Left / Right step over it (Top screen -> No grow)
        val mid = MenuRow(BottomTiles.COMBAT, "Combat bars", HomeWidgetMenus.COMBAT_BARS_WORDS, 0, off = setOf(1))
        fun k(key: String, r: MenuRow) = m.key(key, stops(m.draft, m)) { listOf(r) }
        k("PADDRIGHT", mid); assertEquals(HomeLayout.FightBand.SAME, m.page.fight)
        k("PADDLEFT", mid.copy(picked = 2)); assertEquals(HomeLayout.FightBand.TOP, m.page.fight)
        k("PAD1", mid); assertEquals("A from Top screen: past the dimmed middle", HomeLayout.FightBand.SAME, m.page.fight)
        // every other word dimmed: refused (nothing to pick)
        assertEquals(listOf(HomeEditModel.Fx.Refuse(EditStop.FIGHT_PEN)), k("PAD1", mid.copy(off = setOf(1, 2))))
        assertEquals(listOf(HomeEditModel.Fx.Refuse(EditStop.FIGHT_PEN)), k("PADDRIGHT", mid.copy(off = setOf(1, 2))))
        assertTrue("Left at the row's start: nothing", k("PADDLEFT", mid).isEmpty())
    }

    /** P2: X (or the "-") hides the extra under the cursor - Settings' row Off; the cursor stays at its place; "+" opens the tray of the
     *  hidden ones (those with no data today and the gold line too), A puts one back where it was; B closes the tray */
    @Test fun extrasHideAndTheTrayBringsThemBack() {
        val m = model(start = EditStop.EXTRAS)
        // feat/extras-polish: the walk is the gold line, then the row in the draft's order
        assertEquals(WALK, m.extrasOn)
        assertFalse(m.extraPlus)
        assertEquals("gold", m.extra)
        assertEquals(listOf(HomeEditModel.Fx.Press("gold", 1)), m.press("PAD3"))
        assertNull("(edit tabs: Settings' own row - the fight tabs with none of their own show Settings' badges)", m.draft.badges(HomeTab.COMBAT))
        assertEquals(listOf("gold"), m.extrasOff); assertEquals("hearth", m.extra)
        m.press("PADDRIGHT", "PADDRIGHT"); assertEquals("bags", m.extra)
        assertEquals(listOf(HomeEditModel.Fx.Press("hearth", 1)), m.hideExtra("hearth"))
        assertEquals(listOf("gold", "hearth"), m.extrasOff)
        assertTrue(m.extraPlus)
        // feat/extras-polish (the user, 2026-11-23: "I also want to be able to change the order on these"): A LIFTS the extra, as A lifts
        // a room on the room bar (decision D3's "A does nothing" is the reorder's now); B puts it back where it was
        assertEquals("the cursor kept the hidden one's place", "teleport", m.extra)
        assertEquals(listOf(HomeEditModel.Fx.Lift), m.press("PAD1")); assertEquals(EditStop.EXTRAS, m.lifted); assertEquals("teleport", m.extraLifted)
        m.press("PAD2"); assertNull(m.lifted); assertNull(m.extraLifted)
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT"); assertTrue(m.extraOnPlus)
        m.press("PAD1"); assertEquals(HomeEditModel.Sheet.EXTRAS, m.sheet)
        assertEquals(listOf(HomeEditModel.Fx.Press("gold", 0)), m.press("PAD1"))
        assertNull(m.sheet); assertEquals("gold", m.extra); assertEquals(listOf("hearth"), m.extrasOff)
        m.openExtraTray(); m.press("PAD2"); assertNull("B closes the tray", m.sheet); assertEquals(EditStop.EXTRAS, m.cursor)
        // the host's feed wins (Settings changed it meanwhile): all on again, the tray closes with nothing to show
        m.openExtraTray(); m.bandIs(HomeEditModel.EXTRA_KEYS, emptyList()); assertNull(m.sheet); assertFalse(m.extraPlus)
        // every one hidden: only "+" is left, the tray lists all six (the walk's order)
        val all = model(start = EditStop.EXTRAS)
        for (k in HomeEditModel.EXTRA_KEYS) all.hideExtra(k)
        assertTrue(all.extraOnPlus)
        all.press("PAD1"); assertEquals(WALK, all.extrasOff); assertEquals(HomeEditModel.Sheet.EXTRAS, all.sheet)
    }
    /** the extras' walk with today's order: the gold line, then the row */
    private val WALK = listOf("gold", "hearth", "teleport", "bags", "ammo", "pet")

    /**
     * feat/extras-polish (the user, 2026-11-23: "I also want to be able to change the order on these"): the extras are reordered as the
     * room bar's icons are - A lifts the extra under the cursor, Left / Right carry it along the ROW (never onto the gold line, never past
     * "+"), A drops; B puts the order back; Start drops and is Done. The order is the DRAFT's (HomeLayout.extras: per character, saved as it
     * goes, Discard puts it back); a hidden extra keeps its place in the whole order; the walk rides the order.
     */
    @Test fun extrasReorderWithTheController() {
        val m = model(start = EditStop.EXTRAS)
        m.press("PADDRIGHT")                                                     // the Hearthstone (the gold line is first)
        assertEquals("hearth", m.extra)
        assertEquals(listOf(HomeEditModel.Fx.Lift), m.press("PAD1"))
        assertEquals("hearth", m.extraLifted)
        assertTrue("Left at the row's start: nothing (never onto the gold line)", m.press("PADDLEFT").isEmpty())
        assertEquals(listOf(HomeEditModel.Fx.Relay(HomeEditModel.EDIT_STEP)), m.press("PADDRIGHT"))
        assertEquals(listOf("teleport", "hearth", "bags", "ammo", "pet"), m.draft.extras)
        assertEquals("the cursor rides with it", "hearth", m.extra)
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT")
        assertEquals(listOf("teleport", "bags", "ammo", "pet", "hearth"), m.draft.extras)
        assertTrue("Right at the row's end: nothing (never onto \"+\")", m.press("PADDRIGHT").isEmpty())
        assertEquals(listOf("gold", "teleport", "bags", "ammo", "pet", "hearth"), m.extrasOn)
        assertEquals(listOf(HomeEditModel.Fx.Lift), m.press("PAD1"))             // A drops it there
        assertNull(m.lifted); assertNull(m.extraLifted)
        assertTrue("the draft changed (saved as it goes; Discard can put it back)", m.changed)
        // B puts a carried extra's order back
        m.press("PAD1", "PADDLEFT", "PADDLEFT")
        assertEquals(listOf("teleport", "bags", "hearth", "ammo", "pet"), m.draft.extras)
        m.press("PAD2")
        assertEquals(listOf("teleport", "bags", "ammo", "pet", "hearth"), m.draft.extras)
        assertEquals("hearth", m.extra)
        // a hidden extra keeps its place in the whole order: the ammo off, the Hearthstone carried one left lands before the pet
        m.hideExtra("ammo")
        m.press("PADDRIGHT")                                                     // after the hide the cursor slid onto the pet
        while (m.extra != "hearth") m.press("PADDRIGHT")
        m.press("PAD1", "PADDLEFT", "PAD1")
        assertEquals(listOf("teleport", "bags", "ammo", "hearth", "pet"), m.draft.extras)
        // the gold line never lifts, "+" opens its tray
        m.press("PADDLEFT", "PADDLEFT", "PADDLEFT")
        assertEquals("gold", m.extra)
        assertTrue("A on the gold line: nothing (it is the chip's)", m.press("PAD1").isEmpty())
        // Discard: the order edit mode opened on
        assertTrue(m.discard().contains(HomeEditModel.Fx.Discard))
        assertEquals(ClockBand.DEFAULT, m.draft.extras)
    }

    /** feat/extras-polish: a finger carries an extra along the row - the slot under its centre after the hover, the drop lays it */
    @Test fun extrasReorderWithAFinger() {
        val m = model(start = EditStop.EXTRAS)
        val xs = listOf(500f, 560f, 620f, 680f, 740f)                            // the row's cells' centres (hearth .. pet)
        val geo = object : HomeEditModel.Geo {
            override fun columns(l: HomeLayout) = (0f to 600f) to (640f to 1240f)
            override fun valid(l: HomeLayout) = true
            override val w = 1240f
            override fun extraSlots(): List<Float> = xs
        }
        val box = B(486f, 130f, 514f, 178f)                                      // the Hearthstone's cell
        assertEquals(listOf(HomeEditModel.Fx.Lift), m.extraDragStart(0, 500f, 150f, box))
        assertEquals("hearth", m.extraLifted)
        m.dragMove(560f, 150f, 1000L, geo)
        assertEquals("not before the hover", ClockBand.DEFAULT, m.draft.extras)
        m.dragMove(676f, 152f, 1100L, geo)
        val fx = m.dragTick(1100L + HomeEditModel.HOVER_MS)
        assertTrue(fx.isNotEmpty())
        assertEquals(listOf("teleport", "bags", "ammo", "hearth", "pet"), m.draft.extras)
        val end = m.dragEnd(740f, 150f, 1300L, geo)
        assertTrue(end.contains(HomeEditModel.Fx.Lift))
        assertEquals("the release over the last slot lays it there", listOf("teleport", "bags", "ammo", "pet", "hearth"), m.draft.extras)
        assertNull(m.extraLifted); assertNull(m.drag)
        // the gold line and "+" are no slots: a drag of the gold line starts nothing
        assertTrue(m.extraDragStart(-1, 0f, 0f, box).isEmpty())
    }

    /** P3 / P4 and Discard (1518's ruling for behaviour rows): the Look chip's A is Settings' Rest controller (a Press, never the draft),
     *  the Fight toggle a preview only (no Press, no Relay: nothing sent); Discard and Done end the preview; a park ends it too */
    @Test fun lookAndFightAreBehaviourNotTheDraft() {
        val m = model(start = EditStop.LOOK)
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.REST, HomeRest.ROW.ordinal)), m.press("PAD1"))
        assertEquals("the draft is the store's: the host rebases it", classic, m.draft)
        assertFalse(m.changed)
        val f = party(start = EditStop.FIGHT)
        assertEquals(listOf(HomeEditModel.Fx.Preview(true)), f.press("PAD1"))
        assertTrue(f.fightPreview)
        assertFalse("the preview is no change to undo", f.changed)
        f.startOn(EditStop.FIGHT_PEN, false); f.park()
        assertFalse("a real fight ends the preview", f.fightPreview); assertEquals(EditStop.FIGHT, f.cursor)
        f.resume(); f.toggleFight()
        assertTrue(f.discard().contains(HomeEditModel.Fx.Discard)); assertFalse(f.fightPreview)
        val g = party(start = EditStop.FIGHT); g.press("PAD1"); g.done(); assertFalse(g.fightPreview)
    }

    /** a piece gone (the look changed, the preview off): the cursor to the Look chip, else the D-pad's pencil */
    @Test fun aGonePieceMovesTheCursor() {
        val m = model(start = EditStop.EXTRAS)
        val row = classic.copy(rest = HomeRest.ROW)
        val thin = HomeEditModel(row, EditStop.EXTRAS)
        assertTrue(thin.cursorCheck(stops(row, thin))); assertEquals(EditStop.LOOK, thin.cursor)
        // (edit tabs: the preview is Party mode's - no shared pieces there: the ribbon's Fight; In combat's: the chat)
        val p = party(start = EditStop.EXTRAS)
        p.toggleFight(); p.startOn(EditStop.EXTRAS, false)
        assertTrue(p.cursorCheck(stops(classic, p))); assertEquals("no extras in the preview", EditStop.FIGHT, p.cursor)
        assertNotNull(stops(classic, p)[EditStop.FIGHT_PEN])
        m.tabTo(HomeTab.COMBAT); m.startOn(EditStop.EXTRAS, false)
        assertTrue(m.cursorCheck(stops(classic, m))); assertEquals(EditStop.CHAT, m.cursor)
    }

    /** EDIT TABS (D4 went: each fight tab's widgets are moved in its fight look): the Add tray in the Fight preview brings the bar or the
     *  card back ON the tab and the cursor onto it - a stop that exists - and Y on the bar flips the tab's Slots only */
    @Test fun addingAWidgetBackInThePreviewPutsTheCursorOnIt() {
        for (w in listOf(EditStop.CAST, EditStop.QUEST)) for (bothOff in listOf(false, true)) {
            val l = when {
                bothOff -> classic.copy(cast = HomeLayout.CastDock.OFF, card = HomeLayout.CardDock.OFF)
                w == EditStop.CAST -> classic.copy(cast = HomeLayout.CastDock.OFF)
                else -> classic.copy(card = HomeLayout.CardDock.OFF)
            }
            val m = party(l, start = EditStop.FIGHT)
            m.press("PAD1")                                                     // the preview on
            assertTrue(m.fightPreview)
            m.press("PADDLEFT"); assertEquals("$w: Left from Fight to the plus", EditStop.PLUS, m.cursor)
            m.press("PAD1"); assertEquals(HomeEditModel.Sheet.ADD, m.sheet)
            val sel = m.removed.indexOf(w); assertTrue(sel >= 0)
            repeat(sel) { m.press("PADDDOWN") }
            m.press("PAD1")
            assertNull(m.sheet)
            assertFalse("$w back on Home", w in m.removed)
            assertEquals(w, m.cursor); assertTrue(m.cursor in stops(m.draft, m))
            assertTrue("$w: only the Party mode tab's", w in HomeEditModel(m.draft, EditStop.CHAT).removed)
            val g = m.page.grow
            m.press("PAD4")
            assertEquals("$w: Y flips the bar's Slots only", if (w == EditStop.CAST) !g else g, m.page.grow)
        }
    }

}
