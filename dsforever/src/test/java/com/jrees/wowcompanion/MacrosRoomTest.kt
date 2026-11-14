package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MACROS ROOM AND THE CALENDAR BUTTON, the app's pure half (the user, 2026-11-13: "Lets scrap the smart ranged attack and start
 * building a macro room. The calender can be moved up to a button on the right side of the map name make it small. Use the room slot for
 * macros."). The add-on's half is tools/test_macro_room.py, whose documents these are (MACRO_DUMP, trimmed).
 */
class MacrosRoomTest {
    private val keys = """{"a": 3, "b": 5, "f": 1, "fb": 2, "h": 0, "kx": 10, "n": 24, "pn": 9, "pp": 8, "s0": 12, "tc": 7, "tg": 6, "wx": 11, "x": 4}"""
    private val grid = """{"m": {"cells": [{"i": 134400, "n": "Bandage"}, {"i": 134400, "n": "Bolt"}, {"i": 134400, "n": "Focus Heal"},
        {"plus": true}], "cnt": {"c": {"max": 18, "n": 1}, "g": {"max": 120, "n": 3}}, "ctl": {"cap": true, "held": true, "want": true},
        "cur": 2, "keys": $keys, "mac": {"i": 134400, "len": 56, "lines": ["#showtooltip", "/cast [@focus,exists,nodead][] Healing Wave"],
        "n": "Focus Heal"}, "me": "Korvash", "mode": "grid", "n": 7, "page": 1, "pages": 1, "ready": true, "tab": "g", "v": "s10",
        "view": "grid"}, "type": "macros", "v": "s10"}"""

    private fun st(json: String) = MacrosModel.parse(JSONObject(json))!!

    // ---------------------------------------------------------------- the document
    @Test fun parsesTheGrid() {
        val s = st(grid)
        assertTrue(s.ready); assertFalse(s.off); assertEquals("g", s.tab)
        assertEquals(MacrosModel.Count(3, 120), s.general); assertEquals(MacrosModel.Count(1, 18), s.char)
        assertEquals(listOf("Bandage", "Bolt", "Focus Heal", ""), s.cells.map { it.name })
        assertTrue(s.cells[3].plus)
        assertEquals(2, s.cur)
        assertEquals("Focus Heal", s.mac?.name)
        assertEquals(listOf("#showtooltip", "/cast [@focus,exists,nodead][] Healing Wave"), s.mac?.lines)
        assertEquals("56/255", MacrosModel.counter(s.mac))
        assertTrue(s.held && s.want)
        assertEquals("Korvash", s.me)
    }

    @Test fun theTapsUseTheRowsOwnColumns() {
        val s = st(grid)
        assertEquals(listOf("mac" to 0), MacrosModel.cell(s, "h"))
        assertEquals(listOf("mac" to 11), MacrosModel.cell(s, "wx"))
        assertEquals(listOf("mac" to 14), MacrosModel.pickCells(s, 2, act = false))                 // s2: a select only
        assertEquals(listOf("mac" to 15, "mac" to 3), MacrosModel.pickCells(s, 3, act = true))     // "+": select it, then A
        assertNull(MacrosModel.pickCells(s, 24, act = true))                                         // past the 24 on show
    }

    @Test fun noRowYetNoTapsButTheRowAsk() {
        val s = st("""{"type": "macros", "m": {"ready": true, "mode": "grid", "tab": "g", "ctl": {"want": false}}}""")
        assertNull(s.keys)
        assertNull(MacrosModel.cell(s, "h"))
        assertNull(MacrosModel.ctlFix(s, shown = true))                                             // nothing to tap until the row exists
        assertEquals(listOf("padtab" to 39, "padtab" to 34), MacrosModel.rowCells())               // Book b63's padtab pick
        assertTrue(st("""{"type": "macros", "m": {"norow": true}}""").noRow)
    }

    @Test fun theHoldFollowsTheScreen() {
        val notWanted = st(grid.replace("\"want\": true", "\"want\": false").replace("\"held\": true", "\"held\": false"))
        assertEquals("h", MacrosModel.ctlFix(notWanted, shown = true))
        assertEquals("f", MacrosModel.ctlFix(st(grid), shown = false))
        assertNull(MacrosModel.ctlFix(st(grid), shown = true))
    }

    @Test fun bLeadsHomeOnlyAtTheGrid() {
        assertTrue(MacrosModel.bLeadsHome(st(grid)))
        assertFalse(MacrosModel.bLeadsHome(st(grid.replace("\"mode\": \"grid\"", "\"mode\": \"edit\""))))
        assertFalse(MacrosModel.bLeadsHome(st(grid.replace("\"mode\": \"grid\"", "\"mode\": \"bind\""))))
        val kbd = st(grid.replace("\"mode\": \"grid\"", "\"mode\": \"kbd\", \"kbd\": {\"name\": \"macname\", \"text\": \"Bolt\", \"max\": 16}"))
        assertFalse(MacrosModel.bLeadsHome(kbd))
        assertEquals(MacrosModel.Kbd("macname", "Bolt", 16, null), kbd.kbd)
        assertEquals("Name", MacrosModel.kbdWord(kbd.kbd))
    }

    @Test fun theEditorsRowsMatchTheAddOns() {
        val mac = st(grid).mac
        assertEquals(listOf("name", "l1", "l2", "add", "pick", "rec", "del"), MacrosModel.editRows(mac))
        assertEquals(0, MacrosModel.editCell("name")); assertEquals(2, MacrosModel.editCell("l2"))
        assertEquals(listOf(20, 21, 22, 23), MacrosModel.BUTTONS.map { MacrosModel.editCell(it) })
        val many = MacrosModel.Mac("x", 0, List(25) { "/say $it" }, 200, emptySet(), more = true)
        assertEquals(MacrosModel.LINE_ROWS + 1 + 4, MacrosModel.editRows(many).size)            // at most 19 line rows (s1..s19)
    }

    @Test fun picksAndRecipes() {
        val p = st("""{"type": "macros", "m": {"ready": true, "mode": "pick", "view": "pick", "pick": {"rows": [{"t": "s", "n": "Lightning Bolt",
            "i": 1}, {"t": "u", "n": "Minor Healing Potion", "i": 2}, {"t": "e", "n": "Worn Shortsword", "i": 3}], "page": 1, "pages": 1,
            "cur": 1, "rec": "sh", "step": 2, "of": 2}}}""")
        val pick = p.pick!!
        assertEquals(listOf("/cast Lightning Bolt", "/use Minor Healing Potion", "/equip Worn Shortsword"), pick.rows.map { MacrosModel.lineOf(it) })
        assertEquals("sh", pick.rec); assertEquals(2, pick.step); assertEquals(2, pick.of)
        assertEquals(listOf("mo", "fo", "me", "sh", "at", "tr"), MacrosModel.RECIPES)
        assertTrue(MacrosModel.RECIPES.all { MacrosModel.RECIPE_WORDS[it] != null && MacrosModel.RECIPE_LINES[it] != null })
    }

    @Test fun refusalWordsAreTerse() {
        for (w in listOf("combat", "window", "loading", "off", "taken", "long", "empty", "locked", "missing", "anything")) {
            val word = MacrosModel.noteWord(w)!!
            assertTrue(word, word.length <= 18)
        }
        assertNull(MacrosModel.noteWord(null))
    }

    @Test fun theLockAndTheWindow() {
        val off = st("""{"type": "macros", "m": {"ready": true, "off": true, "win": true, "cb": true}}""")
        assertTrue(off.off && off.win && off.combat)
    }

    /** D2 (Spells s10 review): the game's window is closed by the `wx` tap once per opening, and only once that cell IS its close button */
    @Test fun theWindowsCloseTapWaitsForItsButton() {
        val open = st(grid.replace("\"ready\": true", "\"ready\": true, \"win\": true"))
        assertFalse(open.wxArmed)
        assertFalse(MacrosModel.closeWindowNow(open, sentThisOpening = false))                     // not armed yet: no tap (it would click nothing)
        val armed = st(grid.replace("\"ready\": true", "\"ready\": true, \"win\": true, \"wxa\": true"))
        assertTrue(armed.wxArmed)
        assertTrue(MacrosModel.closeWindowNow(armed, sentThisOpening = false))
        assertFalse(MacrosModel.closeWindowNow(armed, sentThisOpening = true))                      // ONCE per opening
        assertFalse(MacrosModel.closeWindowNow(st(grid.replace("\"ready\": true", "\"ready\": true, \"wxa\": true")), false))   // no window
        val notHeld = st(grid.replace("\"held\": true", "\"held\": false").replace("\"ready\": true", "\"ready\": true, \"win\": true, \"wxa\": true"))
        assertFalse(MacrosModel.closeWindowNow(notHeld, false))                                     // the room does not hold yet
        val noRow = st(grid.replace("\"ready\": true", "\"ready\": true, \"win\": true, \"wxa\": true, \"norow\": true"))
        assertFalse(MacrosModel.closeWindowNow(noRow, false))                                       // the row not placed: nothing to tap
    }

    // ---------------------------------------------------------------- the bar, the wheel, the routes
    @Test fun macrosHasTheCalendarsSlotOnTheBar() {
        val bar = Room.values().filter { it != Room.HOME && it.onBar }
        assertFalse(Room.CALENDAR.onBar)
        assertEquals(listOf(Room.MAP, Room.QUESTS, Room.SPELLBOOK, Room.TALENTS, Room.PROFESSIONS, Room.GROUPFINDER, Room.CHARACTER,
            Room.BAGS, Room.CONTROLLER, Room.SOCIAL, Room.MACROS, Room.SETTINGS), bar)
        assertEquals(bar.indexOf(Room.SETTINGS) - 1, bar.indexOf(Room.MACROS))                     // the last room before Settings
    }

    @Test fun thePauseWheelsSliceIsMacros() {
        val page3 = HomePauseModel.PAGES[2]
        assertTrue(page3.any { it?.room == "MACROS" && it.key == "macros" })
        assertTrue(HomePauseModel.PAGES.flatten().none { it?.room == "CALENDAR" })
        assertEquals("MACROS", HomePauseModel.MACROS.room)
        assertEquals(HomePauseModel.Kind.ROOM, HomePauseModel.MACROS.kind)
        // in the Calendar's own place: the slice the game counts second on its page 3 (NE)
        assertEquals(HomePauseModel.MACROS, page3[HomePauseModel.fromGame(1)])
    }

    @Test fun theGuestGoesHomeByItsOwnB() {
        assertEquals(setOf("MACROS"), HomeBack.roomsOf("macros"))
        assertEquals(setOf("macros"), HomeBack.idsOf("MACROS"))
        assertEquals(setOf("calendar"), HomeBack.idsOf("CALENDAR"))
        // in a fight the room's top-level B asks Home (Interact i151's fightHome, who = "macros"): Home goes, as for every room
        assertTrue(PadOwnerSync.fightHomeGoes("macros", "MACROS"))
        assertFalse(PadOwnerSync.fightHomeGoes("macros", "CALENDAR"))
        assertFalse(PadOwnerSync.fightHomeGoes("macros", "HOME"))
    }

    @Test fun theBindBoxIsHostedByTheRoom() {
        val c = SpellsModel.parseCtl(JSONObject("""{"ctl": {"bind": 2, "bitem": {"i": 134400, "id": 2, "mac": true, "n": "Bolt"},
            "bk": "select", "idx": 1, "mode": "bind", "note": "bind-wait", "on": true, "page": 1, "pages": 1, "pg": "book", "via": "macros"},
            "type": "spellsctl", "v": "b63"}"""))!!
        assertTrue(c.hosted)
        assertTrue(c.bindItem!!.mac)
        assertEquals("Bolt", c.bindItem!!.name)
        assertEquals(2, c.binding)
        val bags = SpellsModel.parseCtl(JSONObject("""{"type": "spellsctl", "ctl": {"on": true, "via": "bags", "bitem": {"id": 6948, "n": "Hearthstone"}}}"""))!!
        assertTrue(bags.hosted); assertFalse(bags.bindItem!!.mac)
    }

    // ---------------------------------------------------------------- the Calendar button (HomeCalendarButton)
    @Test fun theButtonSitsAtTheBandsRightEnd() {
        // the map module at rest: 608 px wide (screen x 624..1232), its band 8..72 down
        assertArrayEquals(floatArrayOf(536f, 8f, 600f, 72f), HomeCalendarButton.box(608f, 0f), 0.01f)
        assertEquals(530f, HomeCalendarButton.bandRight(608f), 0.01f)                              // the band ends 6 px before it
        // on screen (the map's left at 624, its top at 234 at rest): (1160, 242) - (1224, 306)
        val b = HomeCalendarButton.box(624f + 608f, 234f)
        assertArrayEquals(floatArrayOf(1160f, 242f, 1224f, 306f), b, 0.01f)
        assertEquals(HomeCalendarButton.SIZE, b[2] - b[0], 0.01f); assertEquals(HomeCalendarButton.SIZE, b[3] - b[1], 0.01f)
    }

    @Test fun theFingersBoxIsTheMapsCornerAboveTheBuffs() {
        assertArrayEquals(floatArrayOf(488f, 0f, 608f, 78f), HomeCalendarButton.hit(608f), 0.01f)
        assertTrue(HomeCalendarButton.inHit(570f, 40f, 608f))
        assertTrue(HomeCalendarButton.inHit(495f, 75f, 608f))
        assertFalse(HomeCalendarButton.inHit(470f, 40f, 608f))                                      // the zone name: the map's touch
        assertFalse(HomeCalendarButton.inHit(570f, 82f, 608f))                                      // the buff row (BTN_TOP 80): the auras'
        // the button lies inside its finger's box
        val b = HomeCalendarButton.box(608f, 0f)
        assertTrue(HomeCalendarButton.inHit(b[0], b[1], 608f) && HomeCalendarButton.inHit(b[2], b[3], 608f))
    }

    @Test fun longZoneNamesKeepMostOfTheBand() {
        // the band loses the button's width and the gap: 70 of its 592 px
        val full = 608f - 8f - 8f
        val now = HomeCalendarButton.bandRight(608f) - 8f
        assertEquals(70f, full - now, 0.01f)
        assertTrue(now / full > 0.85f)
    }

    @Test fun theOpenMapsStopIsTheTopRightCorner() {
        val b = HomeCalendarButton.fullBox(1240f, 150f, 16f)
        assertArrayEquals(floatArrayOf(1026f, 16f, 1090f, 80f), b, 0.01f)
        assertEquals(HomeCalendarButton.SIZE + HomeCalendarButton.GAP, HomeCalendarButton.FULL_SHIFT, 0.01f)
        assertTrue(HomeCalendarButton.inBox(1030f, 20f, b))
        assertFalse(HomeCalendarButton.inBox(1000f, 20f, b))
        assertTrue(HomeCalendarButton.inBox(1000f, 20f, b, pad = 30f))
    }

    @Test fun theDocumentsOwnDefaults() {
        val s = st("""{"type": "macros", "m": {}}""")
        assertFalse(s.ready); assertEquals("grid", s.mode); assertEquals("grid", s.view); assertNotNull(s)
        assertNull(MacrosModel.parse(JSONObject("""{"type": "calendar"}""")))
        assertFalse(s.shared)
    }

    // ---------------------------------------------------------------- THE SHARED ROW (TapPad pad21, Spells s12, Book b65)
    @Test fun theRoomsRowIsTheCalendarsGuest() {
        // the document says the row is shared (`sh`): the app asks for it on every showing, before its hold
        val shared = st("""{"type": "macros", "v": "s12", "m": {"ready": true, "sh": true, "keys": {"h": 0, "f": 1, "s0": 12, "n": 24}}}""")
        assertTrue(shared.shared); assertNotNull(shared.keys)
        // the Calendar has the row back: no keys, not norow - the app asks again (padtab 39 then 34), it never says "No pad row"
        val away = st("""{"type": "macros", "v": "s12", "m": {"ready": true, "sh": true}}""")
        assertNull(away.keys); assertFalse(away.noRow)
        assertNull(MacrosModel.cell(away, "h"))
        assertEquals(listOf("padtab" to 39, "padtab" to 34), MacrosModel.rowCells())
        // no Calendar row (its add-on off): a row of its own, as before
        assertFalse(st("""{"type": "macros", "m": {"ready": true, "keys": {"h": 0, "s0": 12, "n": 24}}}""").shared)
    }

    @Test fun theCalendarAsksItsRowBack() {
        assertEquals(listOf("padtab" to 39, "padtab" to 35), CalendarModel.rowBackCells())
        assertEquals("cal", CalendarModel.PAD_ROW)
        // the Calendar's own code 35 never collides with the Macros room's 34
        assertTrue(CalendarModel.ROW_BACK != MacrosModel.ROW_CODE && CalendarModel.PICK == MacrosModel.PICK)
    }

    @Test fun theCalendarAsksItsSharedRowOnEveryShowing() {
        // the cross-branch review: while the Macros room's row is the Calendar's guest, the Calendar asks its row back on EVERY showing,
        // the hold right behind the ask (one tap queue) - a document a moment old that still lists the row never sends the hold alone
        assertEquals(CalendarModel.Showing.ASK_HOLD, CalendarModel.showing(padRow = true, shared = true))
        assertEquals(CalendarModel.Showing.ASK_HOLD, CalendarModel.showing(padRow = null, shared = true))
        // the row gone: the ask alone, the hold on the document that lists the row again (as before)
        assertEquals(CalendarModel.Showing.ASK, CalendarModel.showing(padRow = false, shared = true))
        assertEquals(CalendarModel.Showing.ASK, CalendarModel.showing(padRow = false, shared = false))
        // no Macros guest (the room never opened, or its own row): the hold alone, exactly as before the shared row
        assertEquals(CalendarModel.Showing.HOLD, CalendarModel.showing(padRow = true, shared = false))
        assertEquals(CalendarModel.Showing.HOLD, CalendarModel.showing(padRow = null, shared = false))
    }
}
