package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

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
        assertEquals(listOf("hud", "compass", "target", "party", "nbar", "chat", "psarrow", "psguide"), d.pieces.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), d.pieces.map { it.col })
        // hub58: our six listed (turned on); Polestar's two not (not loaded); the party block solo and the chat strip listed, not shown
        assertEquals(listOf("hud", "compass", "target", "party", "nbar", "chat"), LayoutModel.listed(d).map { it.id })
        assertFalse(d.piece("party")!!.vis); assertFalse(d.piece("chat")!!.vis); assertTrue(d.piece("compass")!!.vis)
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

    // ---------------------------------------------------------------- hub58: only the pieces that are on; Polestar in two
    // (the user, 2026-11-17: "the edit mode should include any UI element that is currently turned on, including the wow UI and
    // polestar. the polestar ui should be broken into 2, one for the arrow and one for the actual guide.")
    @Test fun polestarIsTwoPieces() {
        val d = doc("lay_polestar")
        val t = LayoutModel.tiles(d)
        assertEquals(listOf("hud", "target", "party", "compass", "psarrow", "psguide", "nbar", "chat"), t.map { it.id })
        assertEquals(listOf("Polestar arrow", "Polestar guide"), t.filter { LayoutModel.groupOf(it) == LayoutModel.Group.POLESTAR }.map { LayoutModel.labelOf(it.id) })
        val a = d.piece("psarrow")!!; val g = d.piece("psguide")!!
        assertEquals(6, a.col); assertEquals(7, g.col)
        assertFalse("moved only: Polestar's own scale sizes them", a.sizable || g.sizable)
        assertTrue(a.movable && g.movable && a.atDefault && g.atDefault)
    }

    @Test fun onlyTheListedPiecesAreTiles() {
        val d = doc("lay_off")                                         // our target bar turned off, Polestar's guide hidden
        assertEquals(listOf("hud", "party", "compass", "psarrow", "nbar", "chat"), LayoutModel.tiles(d).map { it.id })
        assertEquals(LayoutModel.tiles(d).map { it.id }.toSet(), LayoutModel.listed(d).map { it.id }.toSet())
        assertTrue("a piece not shown this moment is still listed (dashed)", LayoutModel.tiles(d).any { it.id == "chat" && !it.vis })
        // an older add-on with no `vis`: shown = listed
        val old = LayoutModel.parsePiece(org.json.JSONObject("""{"id":"hud","on":true}"""))!!
        assertTrue(old.vis)
    }

    @Test fun aPickedPieceTurnedOffIsLetGo() {
        val e = LayoutModel.Editor().apply { wantOpen = true }
        val on = doc("lay_polestar").copy(open = true)
        e.onDoc(on)
        e.pick("psguide")
        assertEquals("psguide", e.picked)
        e.nudge(5, 0)
        assertNotNull(e.want)
        e.onDoc(doc("lay_off"))                                        // the guide window hidden
        assertNull("let go", e.picked)
        assertNull("its wish gone: no tap goes for a piece that is not there", e.want)
        assertEquals(LayoutModel.Focus.TILES, e.focus)
        e.pick("target")                                               // turned off: never picked
        assertNull(e.picked)
        val sends = (0..10).mapNotNull { e.next(it * 1000L) }
        assertTrue("no select cell for an unlisted piece", sends.none { it == LayoutModel.Send.Cell(7) || it == LayoutModel.Send.Cell(2) })
    }

    @Test fun theHighlightStaysOnItsPieceAsTheListChanges() {
        val e = LayoutModel.Editor().apply { wantOpen = true }
        e.onDoc(doc("lay_polestar").copy(open = true))
        e.tile = e.tiles().indexOfFirst { it.id == "nbar" }
        e.onDoc(doc("lay_off").copy(open = true))                      // two tiles above it gone
        assertEquals("nbar", e.tilePiece()!!.id)
    }

    @Test fun theSnapLinesAreTheListedPiecesOnly() {
        val d = doc("lay_off")
        val hidden = d.piece("psguide")!!.rect
        val e = LayoutModel.Editor().apply { wantOpen = true; onDoc(d.copy(open = true)); snapOn = true; gridOn = false }
        e.pick("psarrow")
        val a = d.piece("psarrow")!!
        // carry the arrow so its left edge sits 3 px off the hidden guide's left edge: no snap onto a piece that is not there
        val ax = hidden.x + 3 - a.offX
        e.dragTo(ax, a.ay)
        assertEquals(ax.roundToInt().toDouble(), e.want!!.first, 0.0)
        // the control: with the guide listed the same drag snaps onto its edge
        val on = doc("lay_polestar")
        val e2 = LayoutModel.Editor().apply { wantOpen = true; onDoc(on.copy(open = true)); snapOn = true; gridOn = false }
        e2.pick("psarrow")
        val a2 = on.piece("psarrow")!!; val g2 = on.piece("psguide")!!.rect
        e2.dragTo(g2.x + 3 - a2.offX, a2.ay)
        assertEquals((g2.x - a2.offX).roundToInt().toDouble(), e2.want!!.first, 0.0)
    }

    // ---------------------------------------------------------------- hub59: the game's own UI (the user's stage-2 picks, 2026-11-17)
    @Test fun theGamesFramesComeAfterOursUnitFramesAndBarsFirst() {
        val d = doc("lay_game")
        val t = LayoutModel.tiles(d)
        assertEquals(listOf("hud", "target", "party", "compass", "nbar", "chat", "g.PlayerFrame", "g.GamepadMainActionBarFrame",
            "g.MinimapCluster", "g.BuffFrame", "g.DamageMeter"), t.map { it.id })
        assertEquals(listOf("Unit frames", "Action bars", "Game UI", "Game UI", "Game UI"),
            t.filter { it.game }.map { LayoutModel.groupOf(it).label })
        assertEquals(listOf("Player", "Controller bar", "Minimap", "Buffs", "Damage meter"), t.filter { it.game }.map { LayoutModel.labelOf(it.id) })
        assertEquals(listOf(8, 9, 10, 11, 12), t.filter { it.game }.map { it.col })
        assertTrue("the player frame is protected", d.piece("g.PlayerFrame")!!.prot)
        assertFalse(d.piece("g.MinimapCluster")!!.prot)
        assertTrue("moved and resized like ours", d.piece("g.PlayerFrame")!!.sizable)
        assertFalse("a hidden game frame is no tile", t.any { it.id == "g.TargetFrame" || it.id == "g.EssentialCooldownViewer" })
        assertTrue(d.gset)
    }

    /** split-hud (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar" -> "Both"): the add-on's real
     *  documents (tools/test_layout.py p.) - the controller bar, the buffs and the XP bar three pieces, each listed while it is shown,
     *  each its own column; the buffs hidden by their tile leave the controller bar and the XP bar listed */
    @Test fun theBuffsTheControllerBarAndTheXpBarAreThreePieces() {
        val t = LayoutModel.tiles(doc("lay_split")).filter { it.game }
        val three = listOf("g.GamepadMainActionBarFrame", "g.BuffFrame", "g.MainStatusTrackingBarContainer")
        for (id in three) assertTrue(id, t.any { it.id == id })
        assertEquals("each its own select column", 3, t.filter { it.id in three }.map { it.col }.toSet().size)
        assertEquals(listOf("Controller bar", "Buffs", "XP bar"), three.map { LayoutModel.labelOf(it) })
        assertEquals(listOf("Action bars", "Game UI", "Game UI"), three.map { id -> LayoutModel.groupOf(t.first { it.id == id }).label })
        val nb = LayoutModel.tiles(doc("lay_split_nobuffs")).map { it.id }
        assertTrue("the controller bar stays listed", "g.GamepadMainActionBarFrame" in nb)
        assertTrue("the XP bar stays listed", "g.MainStatusTrackingBarContainer" in nb)
        assertFalse("the hidden buffs are no tile", "g.BuffFrame" in nb || "g.DebuffFrame" in nb)
    }

    /** split-hud (the review): a part's word on "lay" (columns 24-32) must never go out once the editor wants to be open - there those
     *  columns are Copy from / Reset game / Reach / Smaller. GameUi.layOpen is Live.editorBusy: busy from the moment the page wants the
     *  editor open (before any "open" document), while it is open, and EDITOR_HOLD_MS after the last pump that wanted it */
    @Test fun thePartsWordsWaitWhileTheEditorOpens() {
        LayoutModel.Live.clearForTest()
        var t = 1_000_000L
        LayoutModel.Live.clock = { t }
        try {
            LayoutModel.Live.note(fx("lay_closed"))
            assertFalse("closed, no editor wanted: the words may go", LayoutModel.Live.editorBusy(t))
            assertFalse("GameUi's gate is the same", GameUi.layOpen())
            val e = LayoutModel.Editor()
            e.wantOpen = true                                     // the page opens: its open cell is not even sent yet
            assertTrue("the page wants the editor: held at once", LayoutModel.Live.editorBusy(t))
            assertTrue("... GameUi holds its words", GameUi.layOpen())
            e.onDoc(doc("lay_closed"))
            assertNotNull("the open cell goes", e.next(t))
            t += LayoutModel.EDITOR_HOLD_MS - 1
            assertTrue("the document still says closed (the open on its way): still held", LayoutModel.Live.editorBusy(t))
            e.next(t)                                             // the pump keeps marking while the page wants the editor
            t += LayoutModel.EDITOR_HOLD_MS - 1
            assertTrue(LayoutModel.Live.editorBusy(t))
            e.leave()
            t += LayoutModel.EDITOR_HOLD_MS
            assertFalse("the page gone and the hold over: the words go again", LayoutModel.Live.editorBusy(t))
        } finally { LayoutModel.Live.clearForTest() }
    }

    @Test fun aChangeInAFightSaysItWaits() {
        val q = doc("lay_queued")
        assertEquals(1, q.queued)
        assertTrue(q.piece("g.PlayerFrame")!!.queued)
        assertEquals("Moves after the fight", LayoutModel.statusOf(q))
        assertEquals(null, LayoutModel.statusOf(doc("lay_game")))
    }

    @Test fun resetGameUiIsOneCell() {
        assertEquals(29, LayoutModel.C_RESET_GAME)
        assertEquals("the characters end before it", LayoutModel.C_RESET_GAME, LayoutModel.C_CHAR + LayoutModel.CHARS)
        val e = LayoutModel.Editor().apply { wantOpen = true; onDoc(doc("lay_game")) }
        e.forget(); e.command(LayoutModel.C_RESET_GAME)
        assertEquals(LayoutModel.Send.Cell(LayoutModel.C_RESET_GAME), e.next(10_000))
    }

    @Test fun aGameFramePicksAndMovesAsOurs() {
        val d = doc("lay_game")
        val e = LayoutModel.Editor().apply { wantOpen = true; onDoc(d); gridOn = d.grid }
        e.pick("g.PlayerFrame")
        assertEquals("g.PlayerFrame", e.picked)
        e.nudge(3, 0)
        val p = d.piece("g.PlayerFrame")!!
        assertEquals((p.ax + 3).roundToInt().toDouble(), e.want!!.first, 0.0)
        assertEquals("its select column first", LayoutModel.Send.Cell(8), e.next(20_000))
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
        val words = listOf("Layout", "Edit layout", "Move and size the top screen's pieces.", "Bars", "HUD", "Compass", "Combat bar",
            "Top chat", "Unsaved", "Size", "Snap", "Grid", "Revert", "Copy", "Drop", "Step", "Game's Edit Mode is open", "Unsaved changes",
            "Discard", "Copy from", "Open", "Your HUD", "Target", "Party", "Navigation", "Target bar", "Party frames", "Waiting for the game",
            "Reset", "Save", "Pick", "Back", "No pad row", "In combat", "Cancel", "Polestar", "Polestar arrow", "Polestar guide",
            "Unit frames", "Action bars", "Game UI", "Moves after the fight") + LayoutModel.GAME_LABELS.values
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
