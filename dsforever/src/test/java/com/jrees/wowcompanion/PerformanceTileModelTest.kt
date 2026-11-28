package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SETTINGS > PERFORMANCE AS PICTURE TILES, the pure model (the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus"; the beautify design's section 6, critic C6 /
 * C10): one headless row of exactly five columns with Frame generation wide; each band's lines as they are NOW, never a line that
 * cannot act; the tiles' words and badges.
 */
class PerformanceTileModelTest {
    private val M = PerformanceTileModel
    private fun keys(id: String, s: PerformanceTileModel.State, open: Boolean = false) = M.lines(id, s, open).map { it.key }

    @Test fun oneRowOfExactlyFiveColumns() {
        assertEquals(listOf(M.LIMIT, M.FG, M.MEM, M.INDOOR), M.SLOTS.map { it.id })
        assertEquals(listOf(M.FG), M.SLOTS.filter { it.wide }.map { it.id })
        assertEquals(5, M.span(M.SLOTS))
        assertEquals(1, M.rows(M.SLOTS, 1240f))
        val g = M.grid(M.SLOTS, 1240f, -1, 0f)
        // the Top screen's numbers: 232 x 184 tiles, 12 apart, 16 margins; the wide one two tiles and the gap between
        assertEquals(listOf(16f, 260f, 748f, 992f), g.tiles.map { it.l })
        assertEquals(GameUiTiles.TILE_W, g.tiles[0].w, 0.01f)
        assertEquals(2 * GameUiTiles.TILE_W + GameUiTiles.GAP, g.tiles[1].w, 0.01f)
        assertEquals(1240f - 16f, g.tiles[3].r, 0.01f)
        assertTrue(g.tiles.all { it.t == M.TOP && it.h == GameUiTiles.TILE_H })
        assertNull(g.band)
        assertEquals(M.TOP + GameUiTiles.TILE_H + GameUiTiles.BOTTOM, g.height, 0.01f)
    }

    /**
     * STREAM SETTINGS IS A PICTURE TILE (the user's pick, 2026-11-17: "Performance 'Stream settings' becomes a picture tile like the
     * others"; it was a compact row): the second row's first tile; its A opens its page (no band, no
     * lines), its word "Open" as Top's Edit layout
     * (since the 11-17 ruling it shows in Local too: [streamSettingsShowsInLocalDimmed])
     */
    @Test fun streamSettingsIsATile() {
        val on = M.slots(PerformanceTileModel.State(streamOn = true))
        assertEquals(listOf(M.LIMIT, M.FG, M.MEM, M.INDOOR, M.STREAM), on.map { it.id })
        val g = M.grid(on, 1240f, -1, 0f)
        assertEquals(2, M.rows(on, 1240f))
        assertEquals(16f, g.tiles[4].l, 0.01f)
        assertTrue(g.tiles[4].t > g.tiles[0].t)
        assertEquals(GameUiTiles.TILE_W, g.tiles[4].w, 0.01f)
        // set up, it opens its band (the 11-18 move to the bottom screen): no page
        assertTrue(!M.opensPage(M.STREAM, PerformanceTileModel.State(streamOn = true)))
        assertTrue(M.lines(M.STREAM, PerformanceTileModel.State(streamOn = true)).isEmpty())
        assertEquals("Open", M.value(M.STREAM, PerformanceTileModel.State(streamOn = true)))
        assertEquals("Stream settings", M.NAMES[M.STREAM])
        // the part's height is the grid's: no compact row under it any more
        assertEquals(g.height + GameUiTiles.BOTTOM, M.height(g.height), 0.01f)
    }

    /**
     * the user's ruling, 2026-11-17: "Stream settings tile: ALWAYS show it on the Performance tab; in Local mode (streaming not set up)
     * it is dimmed, and A opens its page, which shows one terse line on how to set streaming up": the same five slots with streaming
     * off; dimmed, its word "Local", still a page (no band, no lines); set up, not dimmed and "Open"
     */
    @Test fun streamSettingsShowsInLocalDimmed() {
        val local = PerformanceTileModel.State(streamOn = false)
        val set = PerformanceTileModel.State(streamOn = true)
        assertEquals(M.ALL, M.slots(local))
        assertEquals(M.slots(set), M.slots(local))
        assertTrue(M.dimmed(M.STREAM, local))
        assertTrue(!M.dimmed(M.STREAM, set))
        assertEquals("Local", M.value(M.STREAM, local))
        assertTrue(M.opensPage(M.STREAM, local))
        assertTrue(M.lines(M.STREAM, local).isEmpty())
        assertNull(M.badge(M.STREAM, local))
        // the page's one line: terse (one line, no full stop, no explanation)
        assertTrue(StreamHelpHost.LINE.length <= 64)
        assertTrue(!StreamHelpHost.LINE.endsWith("."))
        assertTrue('\n' !in StreamHelpHost.LINE)
    }

    /**
     * THE STREAM'S SETTINGS ON THE BOTTOM SCREEN (the user, 2026-11-18: "the streaming settings appear on the top screen and the
     * controller inputs get all wacky, they should probably appear on the bottom screen in the stream settings box"): the old top page's
     * five rows as band lines, each choice lit as stored, Bit rate a stepper disabled at its ends, Defaults / Test connection presses
     * (Test waits while a test runs); 120 fps only where the Thor shows it; the tile's word the size and rate; the status state only
     */
    @Test fun streamBandCarriesTheOldPagesRows() {
        val q = com.jrees.wowcompanion.stream.StreamQuality()
        val s = PerformanceTileModel.State(streamOn = true, stream = PerformanceTileModel.Stream(q))
        val lines = M.lines(M.STREAM, s)
        assertEquals(listOf(M.L_CODEC, M.L_RES, M.L_FPS, M.L_RATE, M.L_SOUND, M.L_TOOLS), lines.map { it.key })
        assertEquals(listOf("HEVC", "H.264", "AV1"), lines[0].words); assertEquals(0, lines[0].chosen)
        assertEquals(listOf("720p", "900p", "1080p"), lines[1].words); assertEquals(2, lines[1].chosen)
        assertEquals(listOf("30 fps", "60 fps"), lines[2].words); assertEquals(1, lines[2].chosen)
        assertEquals("20 Mbps", lines[3].value); assertEquals(PerformanceTileModel.Kind.STEPPER, lines[3].kind); assertTrue(lines[3].off.isEmpty())
        assertEquals(listOf("Thor", "PC"), lines[4].words); assertEquals(0, lines[4].chosen)
        assertEquals(listOf("Defaults", "Test connection"), lines[5].words); assertNull(lines[5].label)
        assertTrue(lines.drop(3).none { l -> l.kind == PerformanceTileModel.Kind.STEPPER && (0 until l.words.size).any { M.lit(l, it) } })
        assertEquals("1080p 60", M.value(M.STREAM, s))
        assertTrue(!M.dimmed(M.STREAM, s))
        assertEquals("", M.status(M.STREAM, s))
        // the bit rate's ends; 120 offered; a change waiting; a test running
        val low = s.copy(stream = PerformanceTileModel.Stream(q.copy(bitrateKbps = 5_000)))
        assertEquals(setOf(0), M.lines(M.STREAM, low)[3].off)
        // the top step is 150 Mbps now (the user's pick 2026-11-23); above 50 the Wi-Fi note is in the status line
        val mid = M.lines(M.STREAM, s.copy(stream = PerformanceTileModel.Stream(q.copy(bitrateKbps = 50_000))))
        assertTrue("50 Mbps is no end any more", mid[3].off.isEmpty())
        assertEquals(emptyList<String>(), M.statusParts(M.STREAM, s.copy(stream = PerformanceTileModel.Stream(q.copy(bitrateKbps = 50_000)))))
        assertEquals(listOf(com.jrees.wowcompanion.stream.StreamQuality.NOTE_WIFI),
            M.statusParts(M.STREAM, s.copy(stream = PerformanceTileModel.Stream(q.copy(bitrateKbps = 60_000)))))
        val high = s.copy(stream = PerformanceTileModel.Stream(q.copy(bitrateKbps = 150_000, fps = 120), listOf(30, 60, 120), restarts = true,
            test = com.jrees.wowcompanion.StreamTest(emptyList(), running = true)))
        val hl = M.lines(M.STREAM, high)
        assertEquals(setOf(1), hl[3].off)
        assertEquals(listOf("30 fps", "60 fps", "120 fps"), hl[2].words); assertEquals(2, hl[2].chosen)
        assertEquals(setOf(1), hl[5].off)
        assertEquals(listOf(com.jrees.wowcompanion.stream.StreamQuality.NOTE_120, com.jrees.wowcompanion.stream.StreamQuality.NOTE_WIFI, M.RESTARTS, M.TESTING),
            M.statusParts(M.STREAM, high))
        // the test's list: its rows, then the reason and the fix
        val t = com.jrees.wowcompanion.StreamTest(listOf(com.jrees.wowcompanion.StreamTest.Row("PC found", "", com.jrees.wowcompanion.StreamTest.Mark.PASS),
            com.jrees.wowcompanion.StreamTest.Row("Paired", "no", com.jrees.wowcompanion.StreamTest.Mark.FAIL)), "no", "Pair again")
        assertEquals(4, M.testLines(t)); assertEquals(0, M.testLines(null))
        // Local: no lines, a page
        assertTrue(M.lines(M.STREAM, PerformanceTileModel.State()).isEmpty())
    }

    @Test fun aWideSlotNeverStartsInTheLastColumn() {
        val slots = listOf(PerformanceTileModel.Slot("a"), PerformanceTileModel.Slot("b"), PerformanceTileModel.Slot("c"), PerformanceTileModel.Slot("d"), PerformanceTileModel.Slot("w", wide = true), PerformanceTileModel.Slot("e"))
        val g = M.grid(slots, 1240f, -1, 0f)
        // a..d fill columns 1-4; the wide one would be cut by the row's end: it starts the next row
        assertEquals(16f, g.tiles[4].l, 0.01f)
        assertTrue(g.tiles[4].t > g.tiles[3].t)
        assertEquals(g.tiles[4].r + GameUiTiles.GAP, g.tiles[5].l, 0.01f)
    }

    @Test fun theBandOpensUnderTheRow() {
        val g = M.grid(M.SLOTS, 1240f, 2, 300f)
        val band = g.band!!
        assertEquals(g.tiles[2].b + GameUiTiles.GAP, band.t, 0.01f)
        assertEquals(GameUiTiles.MARGIN, band.l, 0.01f)
        assertEquals(300f, band.h, 0.01f)
        assertEquals(M.TOP + GameUiTiles.TILE_H + GameUiTiles.GAP + 300f + GameUiTiles.GAP + GameUiTiles.BOTTOM,
            g.height + GameUiTiles.GAP, 0.01f)
    }

    /** C10: no dead stops - Lossless.dll alone before a copy is set; the generation lines only while it is on; Restart only while a
     *  change waits for it */
    @Test fun frameGenerationLinesAreOnlyTheOnesThatAct() {
        val none = PerformanceTileModel.State(hasDll = false)
        assertEquals(listOf(M.L_DLL), keys(M.FG, none))
        // with a copy the DLL line stays with generation Off too (the old row's: a new copy can be chosen any time)
        val off = PerformanceTileModel.State(hasDll = true, fgOn = false)
        assertEquals(listOf(M.L_FG, M.L_DLL), keys(M.FG, off))
        val on = PerformanceTileModel.State(hasDll = true, fgOn = true)
        assertEquals(listOf(M.L_FG, M.L_MULT, M.L_FLOW, M.L_PERF, M.L_DLL), keys(M.FG, on))
        assertEquals("6 lines at most", 6, keys(M.FG, on.copy(fgWaits = true)).size)
        assertEquals(M.L_RESTART, keys(M.FG, on.copy(fgWaits = true)).last())
        // the switch on with no copy is not generating: its lines do not show
        assertEquals(listOf(M.L_DLL), keys(M.FG, PerformanceTileModel.State(hasDll = false, fgOn = true)))
        // the chosen words
        val lines = M.lines(M.FG, on.copy(multiplier = 3, perfMode = false))
        assertEquals(0, lines[0].chosen)
        assertEquals(1, lines.first { it.key == M.L_MULT }.chosen)
        assertEquals(1, lines.first { it.key == M.L_PERF }.chosen)
        assertEquals("80%", lines.first { it.key == M.L_FLOW }.value)
    }

    /** Flow scale: the old row's - / + as Faster / Sharper (no reset), each off at its bound, never a dead stop */
    @Test fun flowScaleStepsAndItsBoundsAreOff() {
        val on = PerformanceTileModel.State(hasDll = true, fgOn = true)
        val flow = { f: Float -> M.lines(M.FG, on.copy(flow = f)).first { it.key == M.L_FLOW } }
        assertEquals(listOf("Faster", "Sharper"), flow(0.8f).words)
        assertEquals(PerformanceTileModel.Kind.STEPPER, flow(0.8f).kind)
        assertTrue(flow(0.8f).off.isEmpty())
        assertEquals(setOf(0), flow(FrameGen.FLOW_MIN).off)
        assertEquals(setOf(1), flow(FrameGen.FLOW_MAX).off)
    }

    @Test fun frameLimitRestartOnlyWhileAChangeWaits() {
        assertEquals(listOf(M.L_LIMIT), keys(M.LIMIT, PerformanceTileModel.State(limit = 40)))
        assertEquals(listOf(M.L_LIMIT, M.L_RESTART), keys(M.LIMIT, PerformanceTileModel.State(limit = 60, limitWaits = true)))
        // terse: the Restart line and the hourglass say a change waits, no status line repeats it
        assertEquals("", M.status(M.LIMIT, PerformanceTileModel.State(limit = 60, limitWaits = true)))
        assertEquals("", M.status(M.FG, PerformanceTileModel.State(hasDll = true, fgOn = true, fgWaits = true, lastPick = "x")))
        assertEquals(FrameGen.LIMITS.indexOf(60), M.lines(M.LIMIT, PerformanceTileModel.State(limit = 60))[0].chosen)
        assertEquals(listOf("30", "40", "60", "Off"), M.lines(M.LIMIT, PerformanceTileModel.State())[0].words)
        assertEquals(0, M.lines(M.LIMIT, PerformanceTileModel.State(limit = 30))[0].chosen)
        // a launcher-menu value: none lit, the status line says where it came from
        assertEquals(-1, M.lines(M.LIMIT, PerformanceTileModel.State(limit = 45))[0].chosen)
        assertEquals("45 fps, set in the launcher's menu", M.status(M.LIMIT, PerformanceTileModel.State(limit = 45)))
    }

    @Test fun memoryIsAPackOfThreeLines() {
        val s = PerformanceTileModel.State(lighter = true, lowGfx = false, mapFull = true)
        val lines = M.lines(M.MEM, s)
        // the design's order (section 6: "Lighter mode, Low-memory graphics, Home map: full detail"); all three stacked beside the
        // picture, so no line needs to sit next to it first
        // the 1.0.34 merge: Map style when zoomed out (Terrain / Painted, Terrain by default) the fourth line
        // #56 G1 (2026-11-19): the Video memory cap the last line, on by default
        // 2026-11-23: Compress idle memory (PageOut) after it, off unless the Thor's default or the player says so
        assertEquals(listOf(M.L_LIGHTER, M.L_LOWGFX, M.L_MAP, M.L_STYLE, M.L_VRAM, M.L_PAGEOUT), lines.map { it.key })
        assertEquals(listOf(0, 1, 0, 0, 0, 1), lines.map { it.chosen })
        assertTrue(lines.filter { it.key != M.L_STYLE }.all { it.label != null && it.words == listOf("On", "Off") })
        assertEquals(listOf("Terrain", "Painted"), lines.first { it.key == M.L_STYLE }.words)
        assertEquals(1, M.lines(M.MEM, s.copy(mapTerrain = false)).first { it.key == M.L_STYLE }.chosen)
        assertEquals(1, M.lines(M.MEM, s.copy(vramCap = false)).first { it.key == M.L_VRAM }.chosen)
        assertEquals("Video memory cap: from the next game start", M.status(M.MEM, s.copy(vramChanged = true)))
        // the 8 / 12 GB Thors: Lighter mode is the model's, its line not drawn; the status line says so
        val fixed = s.copy(lighterFixed = true)
        assertEquals(listOf(M.L_LOWGFX, M.L_MAP, M.L_STYLE, M.L_VRAM, M.L_PAGEOUT), keys(M.MEM, fixed))
        assertEquals("Lighter mode: always on for this Thor", M.status(M.MEM, fixed))
        assertEquals("Lighter", M.value(M.MEM, fixed))
        assertEquals("Full map", M.value(M.MEM, PerformanceTileModel.State(mapFull = true)))
        assertEquals("Zone map", M.value(M.MEM, PerformanceTileModel.State(mapFull = false)))
        assertNull(M.badge(M.MEM, s))
    }

    @Test fun indoorDeferredListOnlyWithDeferredBuildings() {
        assertEquals(listOf(M.L_BUILD), keys(M.INDOOR, PerformanceTileModel.State()))
        // the switch is the background build, said on its line (not the indoor maps themselves)
        assertEquals("Build in the background", M.lines(M.INDOOR, PerformanceTileModel.State())[0].label)
        val d = PerformanceTileModel.State(deferred = 3)
        assertEquals(listOf(M.L_BUILD, M.L_DEFERRED), keys(M.INDOOR, d))
        assertEquals(listOf("List"), M.lines(M.INDOOR, d)[1].words)
        assertEquals(listOf("Hide"), M.lines(M.INDOOR, d, deferredOpen = true)[1].words)
        assertEquals("12 / 40", M.value(M.INDOOR, PerformanceTileModel.State(ready = 12, total = 40)))
        assertEquals("Off", M.value(M.INDOOR, PerformanceTileModel.State(indoorOn = false)))
        // the status parts are translated one by one (a joined line never matches a template)
        assertEquals(listOf("building one 40 yd from you", "Route data 4 / 9"),
            M.statusParts(M.INDOOR, PerformanceTileModel.State(progress = "building one 40 yd from you", terrain = "Route data 4 / 9")))
    }

    /** Deferred > List (verifier 11-18: the hex ids were debug clutter): one line per reason with how many buildings it held back, in
     *  the bake's order; no building key on screen; the list's height carries every line and a "+ n" line */
    @Test fun deferredListReadsAsReasons() {
        val rows = M.deferredRows(listOf("b3f2a1c0d9e8f7a6b5c4d3e2f1a0b9c8: no floor found", "k1: timed out", "77aa1c0d: no floor found", "odd"))
        assertEquals(listOf("no floor found" to 2, "timed out" to 1, "odd" to 1), rows)
        assertTrue("no key reaches the screen", rows.none { (w, _) -> w.contains("b3f2") || w.contains("k1") || w.contains("77aa") })
        assertEquals(4, rows.sumOf { it.second })
        assertEquals(emptyList<Pair<String, Int>>(), M.deferredRows(emptyList()))
        assertEquals(0f, M.listH(0, 0), 0.01f)
        assertEquals(M.listH(3, 0) + M.LIST_LINE, M.listH(3, 2), 0.01f)
        assertEquals(M.listH(20, 0) - M.listH(1, 0), 19 * M.LIST_LINE, 0.01f)
    }

    /** verifier 11-18: only a choice's picked button is lit (Top's look for "selected"); a stepper's and an action's buttons are presses,
     *  never lit (Faster AND Sharper lit read as both picked; Choose / Restart / List as picked states) */
    @Test fun onlyAPickedChoiceIsLit() {
        val all = listOf(
            M.lines(M.FG, PerformanceTileModel.State(hasDll = true, fgOn = true, fgWaits = true)),
            M.lines(M.LIMIT, PerformanceTileModel.State(limit = 60, limitWaits = true)),
            M.lines(M.MEM, PerformanceTileModel.State(lighter = true)),
            M.lines(M.INDOOR, PerformanceTileModel.State(deferred = 2))).flatten()
        val kinds = all.map { it.kind }.toSet()
        assertEquals(setOf(PerformanceTileModel.Kind.CHOICE, PerformanceTileModel.Kind.STEPPER, PerformanceTileModel.Kind.ACTION), kinds)
        for (line in all) for (k in line.words.indices) {
            val want = line.kind == PerformanceTileModel.Kind.CHOICE && k == line.chosen
            assertEquals("${line.key} $k", want, M.lit(line, k))
        }
        assertTrue(all.filter { it.kind != PerformanceTileModel.Kind.CHOICE }.none { l -> l.words.indices.any { M.lit(l, it) } })
    }

    @Test fun badgesAreTopsSet() {
        val on = PerformanceTileModel.State(hasDll = true, fgOn = true)
        // Top's eye means "shown in the game": none of these tiles is, so no eye; Off reads from the dimmed picture
        assertNull(M.badge(M.FG, on))
        assertNull(M.badge(M.FG, on.copy(fgOn = false)))
        assertEquals("the running game waits for a restart: the hourglass", GameUiTiles.Badge.SENT, M.badge(M.FG, on.copy(fgWaits = true)))
        assertEquals(GameUiTiles.Badge.SENT, M.badge(M.LIMIT, PerformanceTileModel.State(limitWaits = true)))
        assertNull(M.badge(M.LIMIT, PerformanceTileModel.State()))
        assertNull(M.badge(M.INDOOR, PerformanceTileModel.State(indoorOn = false)))
        assertNull(M.badge(M.INDOOR, PerformanceTileModel.State(indoorOn = true)))
        assertTrue(M.dimmed(M.FG, on.copy(fgOn = false)) && !M.dimmed(M.FG, on))
        assertTrue(!M.dimmed(M.LIMIT, PerformanceTileModel.State(limit = 0)))
        assertEquals("3x", M.value(M.FG, on.copy(multiplier = 3)))
        assertEquals("Not set", M.value(M.FG, PerformanceTileModel.State()))
        assertEquals("40 fps", M.value(M.LIMIT, PerformanceTileModel.State(limit = 40)))
    }

    /** the band's boxes: every button the 110 px touch floor; an action's one button no wider than 300 px */
    @Test fun bandBoxes() {
        val lines = M.lines(M.FG, PerformanceTileModel.State(hasDll = true, fgOn = true, fgWaits = true))
        val b = M.band(16f, 0f, 1208f, lines)
        assertEquals(lines.size, b.lines.size)
        for ((i, lb) in b.lines.withIndex()) {
            assertEquals(lines[i].words.size, lb.buttons.size)
            for (x in lb.buttons) assertTrue(x.h >= 110f)
            if (lines[i].kind == PerformanceTileModel.Kind.ACTION) assertTrue(lb.buttons.single().w <= M.ACTION_W + 0.01f)
        }
        assertTrue(b.lines.first().label == null && b.lines.drop(1).all { it.label != null })
        // Memory's long labels: a wider column than Top's 26 %, the buttons still 150 px at least
        val mem = M.band(16f, 0f, 1208f, M.lines(M.MEM, PerformanceTileModel.State()))
        for (lb in mem.lines) {
            assertTrue("label ${lb.key} ${lb.label!!.w}", lb.label!!.w >= 260f)
            for (x in lb.buttons) assertTrue(x.w >= M.BTN_MIN - 0.01f)
            assertTrue(lb.buttons.first().l > lb.label!!.r)
        }
    }

    /** verifier 11-18: a three-line band (Memory's pack) stacks ALL its lines in one column beside the picture (no lone line dropped
     *  under the picture on the left), the picture centred on them; the long labels ("Home map: full detail") on one line */
    @Test fun aThreeLineBandStacksBesideThePicture() {
        val mem = M.band(16f, 0f, 1208f, M.lines(M.MEM, PerformanceTileModel.State()))
        assertEquals(6, mem.lines.size)                 // the 1.0.34 merge: + Map style when zoomed out; #56 G1: + Video memory cap; + Compress idle memory
        val x = mem.lines.map { it.label!!.l }.distinct()
        assertEquals("one column", 1, x.size)
        assertTrue("beside the picture", x.single() > mem.picture.r)
        for (i in 1 until mem.lines.size) assertTrue(mem.lines[i].label!!.t > mem.lines[i - 1].label!!.t)
        val top = mem.lines.first().label!!.t; val bot = mem.lines.last().label!!.b
        assertEquals("the picture centred on the lines", (top + bot) / 2f, (mem.picture.t + mem.picture.b) / 2f, 0.5f)
        assertTrue("one label width, one button column", mem.lines.map { it.buttons.first().l }.distinct().size == 1)
        assertTrue("room for 'Home map: full detail' on one line", mem.lines.all { it.label!!.w >= 340f })
        assertTrue(mem.status.t >= bot)
        // a two-line band and the six-line Frame generation band keep the Top screen's layout
        val fg = M.band(16f, 0f, 1208f, M.lines(M.FG, PerformanceTileModel.State(hasDll = true, fgOn = true)))
        assertTrue(fg.lines.any { it.buttons.first().l < fg.picture.r })
    }
}
