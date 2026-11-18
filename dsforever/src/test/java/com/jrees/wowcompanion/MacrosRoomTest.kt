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
        // Spells s14 MR.editRows: the name, the icon, the lines, then (s15, the look pass "Full match") + Line and More
        assertEquals(listOf("name", "icon", "l1", "l2", "add", "more"), MacrosModel.editRows(mac))
        assertEquals(0, MacrosModel.editCell("name")); assertEquals(1, MacrosModel.editCell("icon")); assertEquals(3, MacrosModel.editCell("l2"))
        assertEquals(listOf(21, 22), MacrosModel.BUTTONS.map { MacrosModel.editCell(it) })
        val many = MacrosModel.Mac("x", 0, List(25) { "/say $it" }, 200, emptySet(), more = true)
        assertEquals(2 + MacrosModel.LINE_ROWS + 2, MacrosModel.editRows(many).size)                // at most 19 line rows (s2..s20)
        assertEquals(20, MacrosModel.editCell("l19"))
        // an empty macro: its preset tiles where the lines go, + Line and More (More holds Delete only then)
        val empty = MacrosModel.Mac("x", 0, emptyList(), 0, emptySet(), more = false)
        assertEquals(listOf("name", "icon", "t1", "t2", "t3", "add", "more"), MacrosModel.editRows(empty, tiles = 3))
        assertEquals(2, MacrosModel.editCell("t1"))
    }

    /** Spells s15 (the look pass, the user's "Full match"): the editor's Y opens More (ym), More's rows, the Delete ask */
    @Test fun theMoreMenuAndTheAsk() {
        val e = st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit","ym":true,"del":true,"pop":{"k":"more","cur":"r2","cols":1,
            "base":0,"rows":[{"o":"pre"},{"o":"del","off":true}]}}}""")
        assertTrue(e.ym); assertTrue(e.del)
        val p = e.pop!!
        assertEquals("more", p.kind); assertEquals(1, p.cols); assertEquals(0, p.cellOf("r1")); assertEquals(1, p.cellOf("r2"))
        assertEquals(listOf("pre", "del"), p.rows.map { it.optString("o") }); assertTrue(p.rows[1].optBoolean("off"))
        assertFalse(st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit"}}""").ym)
    }

    /** Spells s16 X = REMOVE (the user, 2026-11-18: "in the macro menu I need a button to delete a line, maybe just on the X glyph"): the
     *  focused line's hint (xr), the line asking (rem), More's Insert below and the line it goes under */
    @Test fun xRemoveAndInsertBelow() {
        val h = st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit","ef":"l2","xr":true}}""")
        assertTrue(h.xRemove); assertNull(h.remLine)
        val a = st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit","ef":"l2","rem":2}}""")
        assertEquals(2, a.remLine); assertFalse(a.xRemove)
        val m = st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit","pop":{"k":"more","cur":"r1","cols":1,"base":0,
            "rows":[{"o":"ins","l":3},{"o":"pre"},{"o":"del"}]}}}""")
        assertEquals("Insert below", MacrosModel.moreWord("ins"))
        assertEquals(3, MacrosModel.insertUnder(m.pop))
        assertNull(MacrosModel.insertUnder(m.pop!!.copy(cur = "r2")))
        assertNull(MacrosModel.insertUnder(null))
    }

    /** (s13, an older add-on's + Spell/Item list) its rows' lines, kept for a document from before s14 */
    @Test fun theOlderPickListStillReads() {
        val p = st("""{"type": "macros", "m": {"ready": true, "mode": "pick", "view": "pick", "pick": {"rows": [{"t": "s", "n": "Lightning Bolt",
            "i": 1}, {"t": "u", "n": "Minor Healing Potion", "i": 2}, {"t": "e", "n": "Worn Shortsword", "i": 3}], "page": 1, "pages": 1,
            "cur": 1}}}""")
        assertEquals(listOf("/cast Lightning Bolt", "/use Minor Healing Potion", "/equip Worn Shortsword"), p.pick!!.rows.map { MacrosModel.lineOf(it) })
    }

    // ---------------------------------------------------------------- s14 THE MACRO BUILDER v2 (the add-on's documents, MACRO_DUMP, trimmed)
    private val build = """{"type":"macros","m":{"ready":true,"mode":"build","view":"build","b":{"a":{"i":136052,"n":"Healing Wave"},"c":"cast",
        "ch":{"add":16,"arg":1,"c1f":5,"c1u":4,"c2f":8,"c2u":7,"c3f":11,"c3u":10,"cmd":0,"done":19,"keep":3,"more":17},"ym":true,
        "cs":[{"f":["help","!dead"],"u":"focus","x":1},{"f":["help","!dead"],"u":"mouseover","x":2},{"u":"player","x":3}],"cur":"cmd","len":241,
        "ln":2,"rows":[["cmd","arg","keep"],["c1u","c1f"],["c2u","c2f"],["c3u","c3f"],["add"],["done","more"]],
        "t":"/cast [@focus,help,nodead][@mouseover,help,nodead][@player] Healing Wave"},"keys":$keys}}"""

    @Test fun parsesTheBuilder() {
        val s = st(build)
        val b = s.builder!!
        assertEquals("/cast [@focus,help,nodead][@mouseover,help,nodead][@player] Healing Wave", b.text)
        assertEquals(2, b.line); assertNull(b.at); assertEquals(241, b.len); assertEquals("cast", b.cmd)
        assertEquals(MacrosModel.Look(136052, "Healing Wave"), b.look)
        assertEquals(listOf("focus", "mouseover", "player"), b.cases.map { it.unit })
        assertEquals(listOf("help", "!dead"), b.cases[0].conds)
        assertTrue(b.cases[2].conds.isEmpty())
        assertEquals(listOf("cmd", "arg", "keep"), b.rows.first()); assertEquals(listOf("done", "more"), b.rows.last())
        assertEquals(19, MacrosModel.chipCell(b, "done")); assertEquals(7, MacrosModel.chipCell(b, "c2u")); assertNull(MacrosModel.chipCell(b, "rank"))
        // s15: More (s17) in the band beside Done; on the action chip Y opens it (ym); Aa Text / Remove are More's rows, no chips
        assertEquals(17, MacrosModel.chipCell(b, "more")); assertNull(MacrosModel.chipCell(b, "text")); assertTrue(b.ym)
        assertEquals("Revert", MacrosModel.moreWord("rem", revert = true)); assertEquals("Remove", MacrosModel.moreWord("rem"))
        assertEquals("Presets", MacrosModel.moreWord("pre")); assertEquals("Delete", MacrosModel.moreWord("del")); assertEquals("Aa Text", MacrosModel.moreWord("text"))
        assertFalse(b.draft); assertNull(b.hard); assertTrue(b.warns.isEmpty()); assertNull(b.pop)
        assertEquals("Remove", MacrosModel.removeWord(b))
        assertEquals("Revert", MacrosModel.removeWord(b.copy(draft = true)))                            // a draft of a stored line
        assertEquals("Remove", MacrosModel.removeWord(b.copy(line = null, draft = true)))              // a new line's draft goes
        assertEquals("Remove?", MacrosModel.removeWord(b.copy(remArmed = true)))
        // a finger: the chip's s-cell then A; Done is the `ok` key (the Select button's own work)
        assertEquals(listOf("mac" to 12 + 7, "mac" to 3), MacrosModel.pickCells(s, 7, act = true))
        assertEquals(listOf("mac" to 13), MacrosModel.doneCells(s.copy(keys = MacrosModel.Keys(s.keys!!.col + ("ok" to 13), 14, 24))))
    }

    @Test fun parsesTheStripsAndTheDraftMark() {
        val s = st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit","ls":[{"c":"tip","cs":[{"x":1}],"k":"tip"},{"c":"cast",
            "cs":[{"f":["help","!dead"],"u":"focus","x":1},{"f":["help","!dead"],"u":"mouseover","x":2},{"u":"player","x":3}],"i":136052,"k":"cmd",
            "n":"Healing Wave"},{"c":"seq","cs":[{"x":1}],"i":136048,"k":"cmd","n":"Lightning Bolt","rs":"target","sq":[{"i":136048,
            "n":"Lightning Bolt"},{"i":136026,"n":"Earth Shock"}]},{"k":"text"},{"k":"lock"},{"c":"petattack","cn":"Attack","i":132152,"k":"cmd",
            "cs":[{"x":1}]},{"c":"cast","cs":[{"x":1}],"k":"cmd","n":"Frostbolt","w":"book"}],"ef":"l2","dr":{"n":2}}}""")
        assertEquals(listOf("tip", "cmd", "cmd", "text", "lock", "cmd", "cmd"), s.strips.map { it.kind })
        assertEquals(3, s.strips[1].cases.size)
        assertEquals(listOf("Lightning Bolt", "Earth Shock"), s.strips[2].seq.map { it.name }); assertEquals("target", s.strips[2].reset)
        assertEquals("Attack", s.strips[5].cmdName)                                                     // the game's own word
        assertEquals("book", s.strips[6].warn); assertEquals("Not in book", MacrosModel.warnWord(s.strips[6].warn))
        assertEquals(2, s.draftLine); assertNull(s.draftAt)
    }

    @Test fun parsesPopOutsAndPresets() {
        val s = st("""{"type":"macros","m":{"ready":true,"mode":"build","view":"build","b":{"c":"cast","t":"/cast","len":5,"rows":[["cmd","arg"]],
            "ch":{"cmd":0,"arg":1},"h":"pick","hc":"arg","pop":{"base":6,"case":1,"cols":6,"cur":"r1","forc":"cast","k":"arg","page":1,"pages":2,
            "q":"bolt","rows":[{"i":136048,"id":403,"n":"Lightning Bolt","t":"s"}],"tab":"sp","tabs":["sp","gen","it","sl"]}}}}""")
        val b = s.builder!!
        assertEquals("pick", b.hard); assertEquals("arg", b.hardChip); assertEquals("Pick", MacrosModel.hardWord(b.hard))
        val p = b.pop!!
        assertEquals("arg", p.kind); assertEquals(6, p.base); assertEquals(6, p.cols); assertEquals(2, p.pages); assertEquals("bolt", p.query)
        assertEquals(listOf("sp", "gen", "it", "sl"), p.tabs); assertEquals("sp", p.tab); assertEquals("cast", p.forCmd)
        assertEquals(6, p.cellOf("r1")); assertEquals(5, p.cellOf("q")); assertEquals(2, p.cellOf("t3"))
        assertEquals(403, p.rows[0].optInt("id"))
        val ps = st("""{"type":"macros","m":{"ready":true,"mode":"ps","view":"ps","ps":{"back":"edit","cur":"p1","k":"heal","len":54,"ls":[{"c":"tip",
            "cs":[{"x":1}],"k":"tip"},{"c":"cast","cs":[{"f":["help","!dead"],"x":1},{"u":"player","x":2}],"i":136052,"k":"cmd","n":"Healing Wave"}],
            "picks":[{"i":136052,"kind":"help","n":"Healing Wave"}],"t":"#showtooltip\n/cast [help,nodead][@player] Healing Wave","arm":true},
            "tiles":[{"ic":[136052],"k":"heal","t":"#showtooltip"},{"ic":[136052,136048],"k":"fof","t":"/cast"}]}}""")
        val pr = ps.preset!!
        assertEquals("heal", pr.key); assertEquals(2, pr.strips.size); assertTrue(pr.armed); assertEquals("help", pr.picks[0].second)
        assertEquals(listOf("heal", "fof"), ps.tiles.map { it.key }); assertEquals(listOf(136052, 136048), ps.tiles[1].icons)
        assertEquals("Heal, else me", MacrosModel.presetWord("heal")); assertEquals("Friend or foe", MacrosModel.presetWord("fof"))
    }

    /** Spells s17 BLANK (the user, 2026-11-18: "I need a blank template option on macros to just start a fresh new blank template"): the
     *  first tile is Blank (the default icon, no lines); after it an empty macro's editor carries no tiles: name, icon, + Line, More */
    @Test fun blankIsTheFirstTileAndLeavesABlankEditor() {
        val e = st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit","ef":"t1","mac":{"n":"New Macro","i":134400,"lines":[]},
            "tiles":[{"ic":[134400],"k":"blank","t":""},{"ic":[136052],"k":"heal","t":"#showtooltip"}]}}""")
        assertEquals(listOf("blank", "heal"), e.tiles.map { it.key }); assertEquals(listOf(134400), e.tiles[0].icons); assertEquals("", e.tiles[0].text)
        assertEquals("Blank", MacrosModel.presetWord("blank"))
        val empty = MacrosModel.Mac("New Macro", 134400, emptyList(), 0, emptySet(), more = false)
        assertEquals(listOf("name", "icon", "t1", "t2", "add", "more"), MacrosModel.editRows(empty, tiles = e.tiles.size))
        val blank = st("""{"type":"macros","m":{"ready":true,"mode":"edit","view":"edit","ef":"add","mac":{"n":"New Macro","i":134400,"lines":[]},"ls":[]}}""")
        assertTrue(blank.tiles.isEmpty()); assertEquals("add", blank.ef)
        assertEquals(listOf("name", "icon", "add", "more"), MacrosModel.editRows(empty, tiles = blank.tiles.size))
    }

    @Test fun theChipsWords() {
        // D5's units: the controller's targets; party1..4 = "Party" + its number; a typed name is the player's text (no word)
        assertEquals("Target", MacrosModel.unitWord(null)); assertEquals("Soft enemy", MacrosModel.unitWord("softenemy"))
        assertEquals("Me", MacrosModel.unitWord("player")); assertEquals("Party", MacrosModel.unitWord("party3"))
        assertEquals(3, MacrosModel.partyNo("party3")); assertNull(MacrosModel.unitWord("Bob"))
        // the conditions: "!dead" = Alive, the other "no" forms struck through; a raw one is the game's own text
        assertEquals("Alive", MacrosModel.condWord("!dead")); assertFalse(MacrosModel.condStruck("!dead"))
        assertEquals("Enemy", MacrosModel.condWord("!harm")); assertTrue(MacrosModel.condStruck("!harm"))
        assertNull(MacrosModel.condWord("~spec:2"))
        // the modifiers: the player's own glyph set (the game's Shift = ZL / LT / L2, its Ctrl = LB / L / L1)
        assertEquals("ltrigger", MacrosModel.condGlyph("mod:shift")); assertEquals("lshoulder", MacrosModel.condGlyph("!mod:ctrl"))
        assertEquals("Cast", MacrosModel.cmdWord("cast")); assertEquals("Start attack", MacrosModel.cmdWord("startattack"))
        assertEquals("Else on me", MacrosModel.caseWord("me")); assertEquals("Soft friend first", MacrosModel.caseWord("sf1"))
        assertNull(MacrosModel.resetWord("12")); assertEquals("Combat", MacrosModel.resetWord("combat"))
        assertTrue(MacrosModel.tooLong(256)); assertFalse(MacrosModel.tooLong(255))
        // terse: every chip word fits a chip
        for (w in listOf("Soft friend first", "Else another spell", "Not in bags or worn", "Pet attack + spell", "Leave form + spell"))
            assertTrue(w, w.length <= 20)
    }

    @Test fun theLockedMacro() {
        val s = st("""{"type":"macros","m":{"ready":true,"mode":"grid","view":"grid","cells":[{"n":"DS Attack 12","i":134400,"lk":true},
            {"n":"Mine","i":1}],"cur":0,"mac":{"n":"DS Attack 12","i":134400,"lines":["#showtooltip"],"len":12,"lk":true}}}""")
        assertTrue(s.cells[0].locked); assertFalse(s.cells[1].locked); assertTrue(s.mac!!.locked)
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
