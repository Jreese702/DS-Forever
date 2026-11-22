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
    private fun stops(l: HomeLayout, m: HomeEditModel): Map<EditStop, HomeLayoutSolver.Box> {
        val r = HomeLayoutSolver.places(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f, takeover = m.fightPreview))
        val b = r.boxes
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        if (!m.fightPreview) {
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
        out[EditStop.FIGHT] = B(426f, 937f, 536f, 967f)                     // fix round: left of the disc (the hints keep their room)
        out[EditStop.DONE] = B(1080f, 937f, 1230f, 967f)
        out[EditStop.ROOMS] = B(8f, 969f, 1232f, 1080f)
        val diamonds = l.rest == HomeRest.DIAMONDS
        if (diamonds) {
            out[EditStop.PAD_PEN] = B(379f, 12f, 419f, 52f); out[EditStop.FACE_PEN] = B(804f, 14f, 844f, 54f)
            if (m.fightPreview) out[EditStop.FIGHT_PEN] = B(450f, 12f, 490f, 52f)
            else { out[EditStop.LOOK] = B(450f, 14f, 514f, 50f); out[EditStop.EXTRAS] = B(440f, 100f, 800f, 198f) }
        } else {
            out[EditStop.PAD_PEN] = B(582f, 24f, 618f, 60f); out[EditStop.FACE_PEN] = B(622f, 24f, 658f, 60f)
            if (m.fightPreview) out[EditStop.FIGHT_PEN] = B(588f, 66f, 652f, 102f) else out[EditStop.LOOK] = B(588f, 66f, 652f, 102f)
        }
        return out
    }
    private val values = HomeWidgetMenus.Values(toolbarOn = true, toolbarAvail = true, talk = true, ct = null)
    private fun HomeEditModel.press(vararg keys: String, v: HomeWidgetMenus.Values = values): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, stops(draft, this)) { s -> HomeWidgetMenus.rows(s, draft, v) }
        return out
    }
    private fun model(l: HomeLayout = classic, start: EditStop = EditStop.CHAT) = HomeEditModel(l, start).also { it.bandIs(HomeEditModel.EXTRA_KEYS, emptyList()) }

    @Test fun theBandsPiecesAreStopsWithFreeKeys() {
        val band = listOf(EditStop.PAD_PEN, EditStop.FACE_PEN, EditStop.LOOK, EditStop.EXTRAS, EditStop.FIGHT_PEN)
        assertEquals(band.toSet(), EditStop.values().filter { it.band }.toSet())
        assertEquals("the 1518 stops keep their keys", listOf(2, 3, 4, 5, 6, HomeEditMotion.PARTY, 7, 8, 13),
            EditStop.values().take(9).map { it.key })
        assertEquals(EditStop.values().size, EditStop.values().map { it.key }.toSet().size)
        assertTrue("every key under the rooms'", EditStop.values().all { it.key < HomeEditMotion.roomKey(0) })
        assertTrue("nothing on the band moves or is removed", band.none { it.movable || it.removable } && !EditStop.FIGHT.movable)
        assertEquals(setOf(EditStop.PAD_PEN, EditStop.FACE_PEN, EditStop.FIGHT_PEN), EditStop.values().filter { it.pencil }.toSet())
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
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT"); assertEquals(EditStop.EXTRAS, m.cursor); assertEquals("ammo", m.extra)
        m.press("PADDRIGHT"); assertEquals(EditStop.FACE_PEN, m.cursor)
        m.press("PADDRIGHT"); assertEquals("no wrap", EditStop.FACE_PEN, m.cursor)
        m.press("PADDLEFT"); assertEquals(EditStop.EXTRAS, m.cursor); assertEquals("from the right: the last", "ammo", m.extra)
        m.press("PADDUP"); assertEquals("Up on the band: nothing", EditStop.EXTRAS, m.cursor)
        m.press("PADDDOWN"); assertFalse("Down: the page", m.cursor.band)
        // Up from the divider: the piece nearest the page's centre
        val d = model(start = EditStop.DIVIDER)
        d.press("PADDUP"); assertTrue(d.cursor.band)
        // every look, every stop, every press
        for (l in listOf(classic, classic.copy(rest = HomeRest.ROW))) for (preview in listOf(false, true)) {
            val probe = model(l)
            if (preview) probe.toggleFight()
            for (s in stops(l, probe).keys) for (k in listOf("PADDLEFT", "PADDRIGHT", "PADDUP", "PADDDOWN")) {
                val t = model(l, start = s)
                if (preview) { t.toggleFight(); t.startOn(s, false) }
                if (s == EditStop.DONE || s == EditStop.ROOMS || s == EditStop.DIVIDER) continue   // their own rules: below
                t.press(k)
                assertTrue("${l.rest} preview=$preview: $s $k -> ${t.cursor} (no box)", t.cursor in stops(t.draft, t))
            }
        }
    }

    /** the ribbon's row: "+" <-> Fight <-> Done; Up from the room bar is Fight, Down from Fight the room bar, Left from Done is Fight */
    @Test fun theRibbonRowWalksPlusFightDone() {
        val m = model(classic.copy(card = HomeLayout.CardDock.OFF), start = EditStop.PLUS)
        assertTrue(m.plusShown)
        m.press("PADDRIGHT"); assertEquals(EditStop.FIGHT, m.cursor)
        m.press("PADDRIGHT"); assertEquals(EditStop.DONE, m.cursor)
        m.press("PADDLEFT"); assertEquals(EditStop.FIGHT, m.cursor)
        m.press("PADDLEFT"); assertEquals(EditStop.PLUS, m.cursor)
        m.press("PADDRIGHT", "PADDDOWN"); assertEquals(EditStop.ROOMS, m.cursor)
        m.press("PADDUP"); assertEquals(EditStop.FIGHT, m.cursor)
        m.press("PADDUP"); assertFalse("Up from Fight: the page", m.cursor == EditStop.FIGHT || m.cursor == EditStop.ROOMS)
        val d = model(start = EditStop.DONE)
        d.press("PADDDOWN"); assertEquals("Done + Down: the room bar's end", EditStop.ROOMS, d.cursor)
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
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT", "PADDRIGHT")
        assertEquals(EditStop.FACE_PEN, m.cursor)
        m.press("PAD3"); assertEquals("X opens it too", EditStop.FACE_PEN, m.menu)
        assertEquals(listOf(BottomTiles.REST, BottomTiles.RANK), HomeWidgetMenus.rows(EditStop.FACE_PEN, m.draft, values).map { it.key })
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.REST, HomeRest.ROW.ordinal)), m.press("PADDLEFT"))
        m.press("PADDDOWN")
        assertEquals("rank badges On", listOf(HomeEditModel.Fx.Press(BottomTiles.RANK, 0)), m.press("PADDLEFT"))
        m.press("PAD2"); assertNull(m.menu)
        // the fight pencil (the preview only)
        m.toggleFight(); m.startOn(EditStop.FIGHT_PEN, false)
        m.press("PAD1"); assertEquals(EditStop.FIGHT_PEN, m.menu)
        val f = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.draft, values)
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.SWING), f.map { it.key })
        assertEquals("no Wings word: Top screen dimmed", setOf(HomeLayout.FightBand.TOP.ordinal), f[0].off)
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, 1)), m.press("PADDRIGHT"))
        assertEquals("Top screen refused", listOf(HomeEditModel.Fx.Refuse(EditStop.FIGHT_PEN)),
            m.press("PADDRIGHT", v = values.copy(fightBand = HomeLayout.FightBand.SAME)))
        assertTrue("with the Wings word: pickable", HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.draft, values.copy(ct = 1))[0].off.isEmpty())
    }

    /** fix round (the review: with no Wings word A on In fights went Grow -> Same size, then stuck on the dimmed Top screen, refused every
     *  time): A's cycle steps over a dimmed word and wraps; Left / Right step over it too, and only dimmed words that way is refused */
    @Test fun aSkipsADimmedWordAndWraps() {
        val m = model(start = EditStop.FIGHT)
        m.press("PAD1"); m.startOn(EditStop.FIGHT_PEN, false); m.press("PAD1")
        assertEquals(EditStop.FIGHT_PEN, m.menu)
        val same = values.copy(fightBand = HomeLayout.FightBand.SAME)
        assertEquals("Grow -> Same size", listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, HomeLayout.FightBand.SAME.ordinal)), m.press("PAD1"))
        assertEquals("Same size -> past the dimmed Top screen -> Grow",
            listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, HomeLayout.FightBand.GROW.ordinal)), m.press("PAD1", v = same))
        assertEquals("with the Wings word Top screen is next",
            listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, HomeLayout.FightBand.TOP.ordinal)), m.press("PAD1", v = same.copy(ct = 1)))
        // a row whose middle word is dimmed: Left / Right step over it
        val mid = MenuRow(BottomTiles.COMBAT, "In fights", BottomTiles.FIGHT_WORDS, 0, off = setOf(1))
        val rows = listOf(mid)
        fun k(key: String, r: MenuRow) = m.key(key, stops(m.draft, m)) { listOf(r) }
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, 2)), k("PADDRIGHT", rows[0]))
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, 0)), k("PADDLEFT", mid.copy(picked = 2)))
        assertEquals("A from Grow: past the dimmed middle", listOf(HomeEditModel.Fx.Press(BottomTiles.COMBAT, 2)), k("PAD1", mid))
        // every other word dimmed: refused (nothing to pick)
        assertEquals(listOf(HomeEditModel.Fx.Refuse(EditStop.FIGHT_PEN)), k("PAD1", mid.copy(off = setOf(1, 2))))
        assertEquals(listOf(HomeEditModel.Fx.Refuse(EditStop.FIGHT_PEN)), k("PADDRIGHT", mid.copy(off = setOf(1, 2))))
        assertTrue("Left at the row's start: nothing", k("PADDLEFT", mid).isEmpty())
    }

    /** P2: X (or the "-") hides the extra under the cursor - Settings' row Off; the cursor stays at its place; "+" opens the tray of the
     *  hidden ones (those with no data today and the gold line too), A puts one back where it was; B closes the tray */
    @Test fun extrasHideAndTheTrayBringsThemBack() {
        val m = model(start = EditStop.EXTRAS)
        assertEquals(HomeEditModel.EXTRA_KEYS, m.extrasOn)
        assertFalse(m.extraPlus)
        m.press("PADDRIGHT", "PADDRIGHT"); assertEquals("gold", m.extra)
        assertEquals(listOf(HomeEditModel.Fx.Press("gold", 1)), m.press("PAD3"))
        assertEquals(listOf("gold"), m.extrasOff); assertEquals("bags", m.extra)
        assertEquals(listOf(HomeEditModel.Fx.Press("hearth", 1)), m.hideExtra("hearth"))
        assertEquals(listOf("hearth", "gold"), m.extrasOff)
        assertTrue(m.extraPlus)
        assertTrue("A on an extra does nothing (decision D3)", m.press("PAD1").isEmpty())
        m.press("PADDRIGHT", "PADDRIGHT", "PADDRIGHT"); assertTrue(m.extraOnPlus)
        m.press("PAD1"); assertEquals(HomeEditModel.Sheet.EXTRAS, m.sheet)
        m.press("PADDDOWN")
        assertEquals(listOf(HomeEditModel.Fx.Press("gold", 0)), m.press("PAD1"))
        assertNull(m.sheet); assertEquals("gold", m.extra); assertEquals(listOf("hearth"), m.extrasOff)
        m.openExtraTray(); m.press("PAD2"); assertNull("B closes the tray", m.sheet); assertEquals(EditStop.EXTRAS, m.cursor)
        // the host's feed wins (Settings changed it meanwhile): all on again, the tray closes with nothing to show
        m.openExtraTray(); m.bandIs(HomeEditModel.EXTRA_KEYS, emptyList()); assertNull(m.sheet); assertFalse(m.extraPlus)
        // every one hidden: only "+" is left, the tray lists all five
        val all = model(start = EditStop.EXTRAS)
        for (k in HomeEditModel.EXTRA_KEYS) all.hideExtra(k)
        assertTrue(all.extraOnPlus)
        all.press("PAD1"); assertEquals(HomeEditModel.EXTRA_KEYS, all.extrasOff); assertEquals(HomeEditModel.Sheet.EXTRAS, all.sheet)
    }

    /** P3 / P4 and Discard (1518's ruling for behaviour rows): the Look chip's A is Settings' Rest controller (a Press, never the draft),
     *  the Fight toggle a preview only (no Press, no Relay: nothing sent); Discard and Done end the preview; a park ends it too */
    @Test fun lookAndFightAreBehaviourNotTheDraft() {
        val m = model(start = EditStop.LOOK)
        assertEquals(listOf(HomeEditModel.Fx.Press(BottomTiles.REST, HomeRest.ROW.ordinal)), m.press("PAD1"))
        assertEquals("the draft is the store's: the host rebases it", classic, m.draft)
        assertFalse(m.changed)
        val f = model(start = EditStop.FIGHT)
        assertEquals(listOf(HomeEditModel.Fx.Preview(true)), f.press("PAD1"))
        assertTrue(f.fightPreview)
        assertFalse("the preview is no change to undo", f.changed)
        f.startOn(EditStop.FIGHT_PEN, false); f.park()
        assertFalse("a real fight ends the preview", f.fightPreview); assertEquals(EditStop.FIGHT, f.cursor)
        f.resume(); f.toggleFight()
        assertTrue(f.discard().contains(HomeEditModel.Fx.Discard)); assertFalse(f.fightPreview)
        val g = model(start = EditStop.FIGHT); g.press("PAD1"); g.done(); assertFalse(g.fightPreview)
    }

    /** a piece gone (the look changed, the preview off): the cursor to the Look chip, else the D-pad's pencil */
    @Test fun aGonePieceMovesTheCursor() {
        val m = model(start = EditStop.EXTRAS)
        val row = classic.copy(rest = HomeRest.ROW)
        val thin = HomeEditModel(row, EditStop.EXTRAS)
        assertTrue(thin.cursorCheck(stops(row, thin))); assertEquals(EditStop.LOOK, thin.cursor)
        m.toggleFight(); m.startOn(EditStop.EXTRAS, false)
        assertTrue(m.cursorCheck(stops(classic, m))); assertEquals("no Look chip in the preview", EditStop.PAD_PEN, m.cursor)
        assertNotNull(stops(classic, m)[EditStop.FIGHT_PEN])
    }

    /** fix round 3 (the review: the Add tray during the Fight preview put the cursor on the bar or card it brought back - no stop then,
     *  D4 - so A lifted it, Y resized it and the D-pad jumped anywhere): the cursor stays on a stop that exists ("+" while shown, else
     *  the ribbon's Fight), and a cursor left on a page widget the preview hides goes there too */
    @Test fun addingAWidgetBackInThePreviewKeepsTheCursorOnAStop() {
        for (w in listOf(EditStop.CAST, EditStop.QUEST)) {
            for (bothOff in listOf(false, true)) {
                val l = when {
                    bothOff -> classic.copy(cast = HomeLayout.CastDock.OFF, card = HomeLayout.CardDock.OFF)
                    w == EditStop.CAST -> classic.copy(cast = HomeLayout.CastDock.OFF)
                    else -> classic.copy(card = HomeLayout.CardDock.OFF)
                }
                val m = model(l, start = EditStop.FIGHT)
                m.press("PAD1")                                                 // the preview on
                assertTrue(m.fightPreview)
                m.press("PADDLEFT"); assertEquals("$w: Left from Fight to \"+\"", EditStop.PLUS, m.cursor)
                m.press("PAD1"); assertEquals(HomeEditModel.Sheet.ADD, m.sheet)
                val sel = m.removed.indexOf(w); assertTrue(sel >= 0)
                repeat(sel) { m.press("PADDDOWN") }
                m.press("PAD1")
                assertNull(m.sheet)
                assertFalse("$w back on Home", w in m.removed)
                val st = stops(m.draft, m)
                assertTrue("$w (both off $bothOff): the cursor on a stop that exists (${m.cursor})", m.cursor in st)
                assertEquals(if (m.plusShown) EditStop.PLUS else EditStop.FIGHT, m.cursor)
                assertFalse("nothing to move it to", m.cursorCheck(st))
                val size = m.draft.castSize
                m.press("PAD4"); assertEquals("$w: Y resizes nothing", size, m.draft.castSize)
                if (m.cursor == EditStop.PLUS) m.press("PAD2") else m.press("PADDRIGHT")
                assertNull("$w: nothing lifted", m.lifted)
            }
            // a cursor left on a page widget the preview hides: to "+" when shown, else to the ribbon's Fight
            for (plus in listOf(false, true)) {
                val l = if (plus) classic.copy(card = if (w == EditStop.CAST) HomeLayout.CardDock.OFF else classic.card,
                    cast = if (w == EditStop.QUEST) HomeLayout.CastDock.OFF else classic.cast) else classic
                val m = model(l, start = EditStop.FIGHT)
                m.press("PAD1"); m.startOn(w, false)
                val st = stops(m.draft, m)
                assertFalse(w in st)
                assertTrue("$w: the cursor moves", m.cursorCheck(st))
                assertEquals("$w (plus $plus)", if (plus) EditStop.PLUS else EditStop.FIGHT, m.cursor)
            }
        }
    }
}
