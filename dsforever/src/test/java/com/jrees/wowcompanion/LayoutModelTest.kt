package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE TOP-SCREEN LAYOUT EDITOR, STAGE 1 (the user, 2026-11-16: "we need to make a way to basically edit the UI locations on the top
 * screen from our UI menu"), off-device: the "lay" documents the add-on's Lua REALLY sends (Hub hub55, dumped by tools/test_layout.py
 * into src/test/resources/layout-fixtures; the harness fails when they go stale) read by [LayoutModel], and the editor's state: ONE tap
 * at a time (the selection first, the reach switch, then only the LATEST spot, at most every SPOT_MS; a lost tap again after
 * RETRY_MS), snapping, keeping on the screen, the add-on's adjusted answer adopted, the size stepped to the choice, B's steps, Save
 * before the close, the HUD's tap loop dropping height / scale once the layout owns them.
 */
class LayoutModelTest {
    private fun fx(name: String): JSONObject {
        val s = javaClass.classLoader!!.getResourceAsStream("layout-fixtures/$name.json")
        assertNotNull("fixture $name (run tools/test_layout.py with PARTY_FIXTURES_WRITE=1)", s)
        return JSONObject(s!!.bufferedReader().use { it.readText() })
    }
    private fun doc(name: String) = LayoutModel.parse(fx(name))!!

    /** [d] with one piece's anchor moved to ([ax], [ay]) and the add-on's counters advanced (its answer) */
    private fun answered(d: LayoutModel.Doc, id: String, ax: Double? = null, ay: Double? = null, s: Int? = null, sel: String? = d.sel,
                         taps: Int = d.taps + 1, cells: Int = d.cells, reach: Boolean = d.reach): LayoutModel.Doc =
        d.copy(n = d.n + 1, taps = taps, cells = cells, sel = sel, reach = reach, pieces = d.pieces.map { p ->
            if (p.id != id) p else {
                val nx = ax ?: p.ax; val ny = ay ?: p.ay
                p.copy(ax = nx, ay = ny, rect = p.rect.moved(nx - p.ax, ny - p.ay), s = s ?: p.s, atDefault = false)
            }
        })

    /** the editor on the page, the add-on's editor open (with the grid, as lay_moved was opened) and the HUD selected there */
    private fun open(): LayoutModel.Editor = LayoutModel.Editor().apply { wantOpen = true; onDoc(doc("lay_moved")); gridOn = true }

    // ---------------------------------------------------------------- the document
    @Test fun theDocumentAtLogin() {
        val d = doc("lay_closed")
        assertFalse(d.open); assertTrue(d.held); assertTrue("the row on the pad", d.ready)
        assertEquals(listOf("hud", "compass", "target", "party", "nbar", "chat"), d.pieces.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), d.pieces.map { it.col })
        val hud = d.piece("hud")!!
        assertEquals(960.0, hud.ax, 0.01); assertEquals(890.0, hud.ay, 0.01); assertTrue(hud.atDefault)
        assertEquals(70, hud.smin); assertEquals(160, hud.smax); assertEquals(10, hud.sstep); assertTrue(hud.sizable)
        assertEquals("compass", d.piece("target")!!.link)
        assertFalse("the chat strip only moves", d.piece("chat")!!.sizable)
        assertTrue(d.pieces.none { it.game })                          // stage 2's hook: none today
        assertEquals(listOf("pad", "sink"), d.zones.map { it.why })
        assertEquals(160.0, d.reachPx, 0.0)
        assertEquals(1920.0, d.w, 0.0); assertEquals(1080.0, d.h, 0.0)
        assertTrue(LayoutModel.Live.run { clearForTest(); note(fx("lay_closed")); available() })
        LayoutModel.Live.clearForTest()
    }

    @Test fun aPartDocumentMergesItsPiece() {
        val full = doc("lay_closed")
        val part = LayoutModel.parse(fx("lay_part"), full)!!
        assertEquals(full.pieces.size, part.pieces.size)
        assertEquals(800.0, part.piece("hud")!!.ay, 0.01)
        assertEquals(full.piece("compass"), part.piece("compass"))
        assertEquals(full.zones, part.zones)
        assertTrue(part.dirty)
    }

    @Test fun theTilesAreGrouped() {
        val t = LayoutModel.tiles(doc("lay_closed"))
        assertEquals(listOf("hud", "target", "party", "compass", "nbar", "chat"), t.map { it.id })
        assertEquals(listOf("Your HUD", "Target", "Party", "Navigation", "Bars", "Bars"), t.map { LayoutModel.groupOf(it).label })
    }

    // ---------------------------------------------------------------- the taps
    @Test fun openThenTheSelectionThenTheSpot() {
        val e = LayoutModel.Editor().apply { wantOpen = true; onDoc(doc("lay_closed")) }
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_OPEN), e.next(0))         // the game's grid is off: Open without it
        assertNull("an open asked again only after OPEN_RETRY_MS", e.next(100))
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_OPEN), e.next(LayoutModel.OPEN_RETRY_MS + 1))
        val d = doc("lay_moved")
        e.onDoc(d); e.gridOn = true
        e.pick("compass")
        assertEquals("the add-on's selection first", LayoutModel.Send.Cell(1), e.next(2000))
        e.onDoc(d.copy(sel = "compass", cells = d.cells + 1, n = d.n + 1))
        e.nudge(1, 0)
        assertEquals(LayoutModel.Send.Spot(961.0, 4.0), e.next(2100))
    }

    @Test fun onlyTheLatestSpotGoes() {
        val e = open()
        e.pick("hud")                                                   // the add-on has the HUD selected in this document
        e.nudge(1, 0)
        assertEquals(LayoutModel.Send.Spot(1001.0, 800.0), e.next(1000))
        for (i in 0 until 5) e.nudge(1, 0)
        assertNull("at most one spot every SPOT_MS", e.next(1000 + LayoutModel.SPOT_MS - 1))
        assertEquals("the latest wins, the ones between never go", LayoutModel.Send.Spot(1006.0, 800.0), e.next(1000 + LayoutModel.SPOT_MS))
        // answered there: nothing more
        e.onDoc(answered(e.doc!!, "hud", 1006.0, 800.0))
        assertNull(e.next(5000))
    }

    @Test fun aDropRightAfterAMoveKeepsTheMove() {
        val e = open()
        e.pick("hud"); e.nudge(1, 0)
        e.drop()                                                        // A, before the spot has gone
        assertNull(e.picked)
        assertEquals("the dropped piece's wish still goes", LayoutModel.Send.Spot(1001.0, 800.0), e.next(0))
        e.onDoc(answered(e.doc!!, "hud", 1001.0, 800.0))
        assertNull("there: the wish is done", e.wantId)
        assertNull(e.next(5000))
        // Revert / Reset / Copy forget an older wish (it would undo them)
        e.pick("hud"); e.nudge(0, 1); e.forget(); e.command(LayoutModel.C_REVERT)
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_REVERT), e.next(6000))
        e.onDoc(e.doc!!.copy(cells = e.doc!!.cells + 1, n = e.doc!!.n + 1))  // its answer
        assertNull(e.next(7000))
    }

    @Test fun aLostSpotGoesAgainAfterRetry() {
        val e = open()
        e.pick("hud"); e.nudge(0, 1)
        assertEquals(LayoutModel.Send.Spot(1000.0, 801.0), e.next(0))
        assertNull(e.next(LayoutModel.SPOT_MS))                          // the same spot, no answer yet: wait
        assertEquals(LayoutModel.Send.Spot(1000.0, 801.0), e.next(LayoutModel.RETRY_MS))
    }

    @Test fun theStepIsOneOrTen() {
        val e = open()
        e.pick("hud"); e.step = 10; e.nudge(-1, 0)
        assertEquals(990.0, e.want!!.first, 0.0)
    }

    @Test fun aSpotInThePadBandGoesByTheReachSwitch() {
        val d = doc("lay_moved")
        val band = d.zones.first { it.why == "pad" }
        // the compass's anchor 10 px down: inside the band, so the tap goes REACH lower, with the switch on first
        val t = LayoutModel.tapFor(960.0, 20.0, d)!!
        assertTrue(t.reach); assertEquals(180.0, t.y, 0.0)
        assertTrue(band.contains(960.0, 20.0)); assertFalse(band.contains(t.x, t.y))
        val e = LayoutModel.Editor().apply { wantOpen = true; onDoc(d.copy(sel = "compass")); gridOn = true }
        e.pick("compass")
        e.dragTo(960.0, 20.0)
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_REACH_ON), e.next(0))
        e.onDoc(e.doc!!.copy(reach = true, cells = e.doc!!.cells + 1, n = e.doc!!.n + 1))
        assertEquals(LayoutModel.Send.Spot(960.0, 180.0), e.next(10))
        // a spot out of the band again: the switch off first
        e.onDoc(answered(e.doc!!, "compass", 960.0, 20.0))
        e.dragTo(400.0, 600.0)
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_REACH_OFF), e.next(2000))
        // a spot whose reach point is off the screen and itself in a zone cannot be tapped: nothing goes
        assertNull(LayoutModel.tapFor(960.0, 20.0, d.copy(reachPx = 5000.0)))
    }

    @Test fun theAddOnsAnswerIsThePlace() {
        val e = open()
        e.pick("hud")
        e.dragTo(5.0, 1075.0)
        val s = e.next(0) as LayoutModel.Send.Spot
        // the add-on kept the HUD on the screen elsewhere: its answer (the spot counter past the send) is adopted, nothing more goes
        e.onDoc(answered(e.doc!!, "hud", 140.0, 1048.0))
        assertEquals(140.0 to 1048.0, e.want)
        assertNull(e.next(5000))
        assertTrue(s.y <= 1080.0)
        // a document from BEFORE the tap (the old counter) never undoes the move
        val e2 = open(); e2.snapOn = false; e2.pick("hud"); e2.dragTo(1200.0, 500.0); e2.next(0)
        e2.onDoc(e2.doc!!.copy(n = e2.doc!!.n + 1))
        assertEquals(1200.0 to 500.0, e2.want)
    }

    @Test fun snappingAndKeepingOnTheScreen() {
        val e = open()
        e.pick("hud")
        // the HUD (280 wide, its anchor the top-centre) dragged 5 px off the screen's centre line: it snaps onto it
        e.dragTo(965.0, 600.0)
        assertEquals(960.0, e.want!!.first, 0.0)
        e.snapOn = false
        e.dragTo(965.0, 600.0)
        assertEquals(965.0, e.want!!.first, 0.0)
        // far off the screen: kept on it (within the app's slack; the add-on keeps the exact rule)
        e.dragTo(-900.0, 5000.0)
        val r = e.previewRect(e.pickedPiece()!!)
        assertTrue(r.x >= -LayoutModel.EDGE_SLACK - 0.01 && r.bottom <= 1080 + LayoutModel.EDGE_SLACK + 0.01)
        // the snap lines: the screen's edges / centre, the grid from the centre, the other pieces' edges
        val d = e.doc!!
        val (xs, ys) = LayoutModel.snapLines(d, listOf(LayoutModel.Rect(100.0, 200.0, 50.0, 40.0)), 100.0)
        assertTrue(0.0 in xs && 960.0 in xs && 1920.0 in xs && 1060.0 in xs && 860.0 in xs && 100.0 in xs && 150.0 in xs)
        assertTrue(540.0 in ys && 640.0 in ys && 200.0 in ys && 240.0 in ys)
        assertEquals(0.0 to 0.0, LayoutModel.snap(LayoutModel.Rect(300.0, 300.0, 10.0, 10.0), listOf(0.0) to listOf(0.0)))
        // the D-pad's 1 px steps never snap (the fine control)
        e.snapOn = true
        e.dragTo(950.0, 600.0); e.nudge(1, 0)
        assertEquals(e.want!!.first, (e.want!!.first).toInt().toDouble(), 0.0)
    }

    @Test fun theSizeStepsToTheChoice() {
        val e = open()
        e.pick("hud")
        e.size(1); e.size(1); e.size(1)
        assertEquals(130, e.wantScale)
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_BIGGER), e.next(0))
        assertNull("its answer first", e.next(100))
        e.onDoc(answered(e.doc!!, "hud", s = 110, taps = e.doc!!.taps, cells = e.doc!!.cells + 1))
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_BIGGER), e.next(200))
        for (i in 0 until 20) e.size(1)
        assertEquals("never past its largest", 160, e.wantScale)
        for (i in 0 until 30) e.size(-1)
        assertEquals("never under its smallest", 70, e.wantScale)
        val c = open(); c.pick("chat"); c.size(1)
        assertNull("the chat strip has no size", c.wantScale)
        assertEquals(LayoutModel.C_SMALLER, LayoutModel.sizeCell(120, 100)); assertNull(LayoutModel.sizeCell(100, 100))
    }

    @Test fun bOneStepBackThenOut() {
        val e = open()
        e.pick("hud")
        assertEquals(LayoutModel.Back.DESELECT, e.back())
        e.drop()
        assertEquals("unsaved changes: ask", LayoutModel.Back.ASK, e.back())
        e.prompt = true
        assertEquals(LayoutModel.Back.CLOSE_PROMPT, e.back())
        e.prompt = false; e.list = true
        assertEquals(LayoutModel.Back.CLOSE_LIST, e.back())
        e.list = false
        e.onDoc(e.doc!!.copy(dirty = false))
        assertEquals("nothing unsaved: out to Settings", LayoutModel.Back.LEAVE, e.back())
    }

    @Test fun saveGoesBeforeTheClose() {
        val e = open()
        e.command(LayoutModel.C_SAVE)
        e.wantOpen = false
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_SAVE), e.next(0))
        assertNull("the close waits for Save's answer", e.next(10))
        e.onDoc(e.doc!!.copy(cells = e.doc!!.cells + 1, dirty = false, saves = e.doc!!.saves + 1, n = e.doc!!.n + 1))
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_CLOSE), e.next(20))
    }

    @Test fun thePromptsSaveSurvivesThePageClosing() {
        val e = open()
        e.pick("hud"); e.nudge(1, 0)
        e.prompt = true
        e.command(LayoutModel.C_SAVE)
        e.leave()                                                       // the page closes right behind the prompt's Save
        assertNull(e.picked); assertFalse(e.prompt); assertNull("no wish after the close", e.want)
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_SAVE), e.next(0))
        // the review: the Save's tap LOST (no answer): it goes again after RETRY_MS, and the close never goes before its answer
        assertNull(e.next(LayoutModel.RETRY_MS - 1))
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_SAVE), e.next(LayoutModel.RETRY_MS))
        assertNull(e.next(LayoutModel.RETRY_MS + 10))
        e.onDoc(e.doc!!.copy(cells = e.doc!!.cells + 1, dirty = false, n = e.doc!!.n + 1))
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_CLOSE), e.next(LayoutModel.RETRY_MS + 20))
    }

    @Test fun aCommandWaitsForTheCellBeforeIt() {
        // a selection still on its way when Save is pressed: Save goes once that is answered, so the counter moving past is Save's own
        val e = open()
        e.pick("compass")
        assertEquals(LayoutModel.Send.Cell(1), e.next(0))
        e.command(LayoutModel.C_SAVE)
        assertNull("the selection's answer first", e.next(10))
        e.onDoc(e.doc!!.copy(sel = "compass", cells = e.doc!!.cells + 1, n = e.doc!!.n + 1))
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_SAVE), e.next(20))
        e.onDoc(e.doc!!.copy(cells = e.doc!!.cells + 1, n = e.doc!!.n + 1))
        assertNull("answered: nothing more", e.next(2000))
        // a selection never answered holds a command back only RETRY_MS
        val f = open()
        f.pick("compass"); f.next(0); f.command(LayoutModel.C_REVERT)
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_REVERT), f.next(LayoutModel.RETRY_MS))
    }

    @Test fun anAnchorOffTheScreenIsTappedOnIt() {
        // the app's slack lets the party block's top-left stand a little left of the screen: the tap goes at the edge (never no tap at
        // all, a wish left waiting for ever), the add-on keeps it on the screen and its answer is the place
        val d = doc("lay_moved")
        assertEquals(LayoutModel.Tap(0.0, 500.0, false), LayoutModel.tapFor(-12.0, 500.0, d))
        assertEquals(LayoutModel.Tap(1920.0, 1080.0, false), LayoutModel.tapFor(1940.0, 1100.0, d))
        val e = LayoutModel.Editor().apply { wantOpen = true; onDoc(d.copy(sel = "party")); gridOn = true; snapOn = false }
        e.pick("party")
        e.dragTo(-15.0, 300.0)
        assertEquals(-15.0, e.want!!.first, 0.0)
        assertEquals(LayoutModel.Send.Spot(0.0, 300.0), e.next(0))
        e.onDoc(answered(e.doc!!, "party", 0.0, 300.0))
        assertEquals("the add-on's answer is the place", 0.0 to 300.0, e.want)
        assertNull(e.next(5000))
    }

    @Test fun aPieceCommandWaitsForItsSelection() {
        val e = open()
        e.pick("target")
        e.command(LayoutModel.C_RESET)
        assertEquals("the target bar selected first", LayoutModel.Send.Cell(2), e.next(0))
        e.onDoc(e.doc!!.copy(sel = "target", cells = e.doc!!.cells + 1, n = e.doc!!.n + 1))
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_RESET), e.next(10))
    }

    @Test fun nothingInAFightOrWithoutTheRow() {
        val e = LayoutModel.Editor().apply { wantOpen = true; onDoc(doc("lay_combat")) }
        e.pick("compass"); e.nudge(1, 0)                               // (the fight's fixture: the compass selected, in the fight)
        assertNull("a fight: no spot", e.next(0))
        val d = doc("lay_moved")
        val gone = d.copy(pad = JSONObject(d.pad.toString()).apply { optJSONObject("rows")?.remove("lay") })
        assertFalse(gone.ready)
        val e2 = LayoutModel.Editor().apply { wantOpen = true; onDoc(gone) }
        e2.pick("hud"); e2.nudge(1, 0)
        assertNull("no row on the pad (the Settings room not holding): nothing", e2.next(0))
    }

    @Test fun theGridFollowsTheButton() {
        val e = open()                                                  // lay_moved: opened with the grid
        e.gridOn = false
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_OPEN), e.next(0))
        val c = LayoutModel.Editor().apply { onDoc(doc("lay_copy")) }
        assertEquals(listOf("Other-Realm"), c.doc!!.chars)
    }

    // ---------------------------------------------------------------- the rest of the app
    @Test fun theHudLoopLeavesHeightAndScaleToTheLayout() {
        assertEquals(listOf("w"), GameUi.hudWants(true))
        assertEquals(listOf("y", "w", "s"), GameUi.hudWants(false))
        assertTrue("the row's taps are spaced (a size step right behind its answer)", LayoutModel.ROW in TapSpacing.ROWS)
    }

    @Test fun everyNewWordIsTranslated() {
        val words = listOf("Layout", "Edit layout", "Move and size the top screen's pieces.", "Bars", "HUD", "Compass", "Nintendo bar",
            "Top chat", "Unsaved", "Size", "Snap", "Grid", "Revert", "Copy", "Drop", "Step", "Game's Edit Mode is open", "Unsaved changes",
            "Discard", "Copy from", "Open", "Your HUD", "Target", "Party", "Navigation", "Target bar", "Party frames", "Waiting for the game",
            "Reset", "Save", "Pick", "Back", "No pad row", "In combat", "Cancel")
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val raw = File(dir, "$l.json").readBytes()
            val text = raw.toString(Charsets.UTF_8)
            val plain = JSONObject(text).getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w))
            assertTrue("$l: CRLF lines", text.contains("\"Edit layout\": ") && text.split("\n").dropLast(1).all { it.endsWith("\r") })
        }
        for (w in LayoutModel.Btn.values().map { it.word } + LayoutModel.Group.values().map { it.label })
            assertTrue(w, w in words || w == "Game")
    }
}
