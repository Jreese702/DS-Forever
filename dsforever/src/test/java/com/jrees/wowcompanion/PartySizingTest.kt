package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARTY SIZING (the user, 2026-11-23: "We should add some dynamic sizing options to the party mode windows, so if it goes while in combat
 * like the combat bar or only in party mode and then if the controlls are on the top or bottom screens"; the picks: "Player picks",
 * "Settings + Edit Home"; then on test 1523: "I dont understand why there is controller on top vs controller on bottom settings, just
 * consolidate that into one setting" - the pick "Grow / Same size"): the two per-character picks on the layout record
 * (HomeLayout.partyGrow / partySize), their rows' heights ([PartyLayout.sizedH]: the defaults today's exactly), the record's text (a
 * Classic record's unchanged; 1523's two keys read once), Settings > Bottom screen > Party's tile (BottomTiles.PARTY: this character's
 * record) and the party list's pencil bubble in Edit Home (EditStop.PARTY_PEN: the same two rows, the preview's Rest / Fight; Discard
 * puts the picks back).
 */
class PartySizingTest {
    private val S = HomeLayoutSolver
    private val G = HomeLayout.PartyGrow.values()
    private val Z = HomeLayout.PartySize.values()
    private val M = PartyLayout.Mode.values()
    private val GROW = HomeLayout.PartySize.GROW
    private val SAME = HomeLayout.PartySize.SAME

    @After fun down() { HomeSettings.startForTest(null); RealmFix.forget() }

    /** the defaults are today's rows: rest 64, a Party fight 88, a Party-mode Top fight the fill (null), the takeover 56 */
    @Test fun theDefaultsAreToday() {
        val d = HomeLayout()
        assertEquals(HomeLayout.PartyGrow.FIGHTS, d.partyGrow)
        assertEquals(GROW, d.partySize)
        assertEquals("exactly two choices", listOf("GROW", "SAME"), Z.map { it.name })
        for (on in listOf(false, true)) {
            assertEquals(PartyLayout.REST_H, PartyLayout.sizedH(PartyLayout.Mode.REST, on, d))
            assertEquals(PartyLayout.FIGHT_H, PartyLayout.sizedH(PartyLayout.Mode.FIGHT_ON, on, d))
            assertNull(PartyLayout.sizedH(PartyLayout.Mode.FIGHT_TOP, on, d))
            assertEquals(PartyLayout.TAKEOVER_H, PartyLayout.sizedH(PartyLayout.Mode.FIGHT_OFF, on, d))
        }
        // the defaults' heights are each mode's own (the view's plateH: nothing changes until a player picks)
        for (m in M) PartyLayout.sizedH(m, true, d)?.let { assertEquals(m.name, PartyLayout.plateH(m), it) }
    }

    /** the whole matrix: 3 x 2 picks x 4 modes x Party mode on / off. Grow = 1523's defaults on both screens (the top screen's fill, the
     *  bottom screen's 88); Same size = the rest's rows in every fight, either screen */
    @Test fun everyPickGivesItsRows() {
        val rest = PartyLayout.REST_H; val fight = PartyLayout.FIGHT_H; val shrink = PartyLayout.TAKEOVER_H
        var n = 0
        for (g in G) for (z in Z) for (m in M) for (on in listOf(false, true)) {
            val l = HomeLayout(partyGrow = g, partySize = z)
            val base = if (on && g == HomeLayout.PartyGrow.ALWAYS) fight else rest
            val grows = g != HomeLayout.PartyGrow.OFF && z == GROW
            val want: Float? = when (m) {
                PartyLayout.Mode.REST -> base
                PartyLayout.Mode.FIGHT_OFF -> shrink
                PartyLayout.Mode.FIGHT_ON -> if (grows) fight else base
                PartyLayout.Mode.FIGHT_TOP -> if (grows) null else base
            }
            assertEquals("$g $z $m on=$on", want, PartyLayout.sizedH(m, on, l))
            // Same size: a fight's rows are the rest's, on both screens
            if (z == SAME && m != PartyLayout.Mode.FIGHT_OFF) assertEquals("$g $m on=$on", PartyLayout.sizedH(PartyLayout.Mode.REST, on, l),
                PartyLayout.sizedH(m, on, l))
            n++
        }
        assertEquals(6 * 4 * 2, n)
        // Party mode off at rest: 64 whatever is picked; the takeover (Party mode off in a fight) always 56
        for (g in G) for (z in Z) assertEquals(rest, PartyLayout.sizedH(PartyLayout.Mode.REST, false, HomeLayout(partyGrow = g, partySize = z)))
    }

    /** the record's text: a Classic record's unchanged; the pick written only off its default, read back; junk falls back */
    @Test fun theRecordCarriesThePicks() {
        val classic = HomeLayoutJson.json(HomeLayout())
        for (k in listOf("pgrow", "psize", "ptop", "pbot")) assertFalse(classic.contains(k))
        for (g in G) for (z in Z) {
            val l = HomeLayout(partyGrow = g, partySize = z)
            val s = HomeLayoutJson.json(l)
            assertEquals(g != HomeLayout.PartyGrow.FIGHTS, s.contains("\"pgrow\":\"${g.name}\""))
            assertEquals(z != GROW, s.contains("\"psize\":\"${z.name}\""))
            assertFalse("1523's keys never written", s.contains("ptop") || s.contains("pbot"))
            assertEquals(l, HomeLayoutJson.parse(s))
            // Reset Home to Classic: today's again (Grow); a swap keeps them
            val c = l.classic()
            assertEquals(HomeLayout.PartyGrow.FIGHTS, c.partyGrow); assertEquals(GROW, c.partySize)
            assertEquals(l.partyGrow, l.swapped().partyGrow); assertEquals(l.partySize, l.swapped().partySize)
            // a pick is a change edit mode's Discard puts back
            assertEquals(l == HomeLayout(), l.samePlace(HomeLayout()))
        }
        val plain = """{"v":1,"name":"Classic","chat":"RIGHT"}"""
        for (junk in listOf(""","pgrow":"BIG"}""", ""","psize":7}""", ""","psize":null}""", ""","pgrow":["OFF"]}""", ""","psize":"SHRINK"}"""))
            assertEquals(junk, HomeLayoutJson.parse(plain), HomeLayoutJson.parse(plain.dropLast(1) + junk))
    }

    /**
     * 1523's TWO PICKS, READ ONCE (the task's rule): any old choice of Same size or Shrink to make room in either "Controller on top
     * (fights)" ("ptop") or "Controller on bottom (fights)" ("pbot") -> Same size, anything else -> Grow; the record's own "psize" wins
     * over them, and a save writes only "psize" (the old keys gone, so they are never read again)
     */
    @Test fun the1523PicksAreMappedOnce() {
        val tops = listOf(null, "FILL", "GROW", "SAME")
        val bottoms = listOf(null, "GROW", "SAME", "SHRINK")
        var n = 0
        for (t in tops) for (b in bottoms) {
            val o = org.json.JSONObject("""{"v":1,"name":"Classic","chat":"RIGHT","pgrow":"ALWAYS"}""")
            t?.let { o.put("ptop", it) }; b?.let { o.put("pbot", it) }
            val want = if (t == "SAME" || b == "SAME" || b == "SHRINK") SAME else GROW
            val l = HomeLayoutJson.parse(o.toString())
            val tag = "ptop=$t pbot=$b"
            assertEquals(tag, want, l.partySize)
            assertEquals(tag, HomeLayout.PartyGrow.ALWAYS, l.partyGrow)
            assertEquals(tag, HomeLayout.Side.RIGHT, l.chatSide)
            // saved: the new key only; read back the same
            val saved = HomeLayoutJson.json(l)
            assertFalse(tag, saved.contains("ptop") || saved.contains("pbot"))
            assertEquals(tag, l, HomeLayoutJson.parse(saved))
            // the record's own pick wins over the old keys (they are ignored once it has one)
            for (z in Z) assertEquals(tag, z, HomeLayoutJson.parse(org.json.JSONObject(o.toString()).put("psize", z.name).toString()).partySize)
            n++
        }
        assertEquals(16, n)
        // edit mode's lay-on (only the fields changed) over a 1523 record: the old keys never come back, the mapped pick kept
        val old = HomeLayoutJson.parse("""{"v":1,"name":"Classic","ptop":"SAME"}""")
        assertEquals(SAME, old.partySize)
        val laid = HomeLayoutStore.layOn(old, HomeLayout(), HomeLayout(chatSide = HomeLayout.Side.RIGHT))
        assertEquals(SAME, laid.partySize); assertEquals(HomeLayout.Side.RIGHT, laid.chatSide)
        // a character's 1523 record in the store: read mapped, saved without the old keys
        val p = HomeLayoutStoreTest.Prefs()
        p.edit().putString(HomeLayoutStore.KEY_PREFIX + "Korvash-Realm", """{"v":1,"name":"Classic","pbot":"SHRINK"}""")
            .putBoolean(HomeLayoutStore.KEY_MIGRATED, true).commit()
        HomeSettings.startForTest(p)
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(SAME, HomeSettings.layout.partySize)
        HomeLayoutStore.update { it.copy(partyGrow = HomeLayout.PartyGrow.ALWAYS) }
        val raw = p.getString(HomeLayoutStore.KEY_PREFIX + "Korvash-Realm", null)!!
        assertFalse(raw, raw.contains("pbot") || raw.contains("ptop"))
        assertTrue(raw, raw.contains("\"psize\":\"SAME\""))
        // Grow picked: no key at all, still Grow when read again (the old Shrink never comes back)
        HomeLayoutStore.update { it.copy(partySize = GROW) }
        val raw2 = p.getString(HomeLayoutStore.KEY_PREFIX + "Korvash-Realm", null)!!
        assertFalse(raw2, raw2.contains("psize") || raw2.contains("pbot"))
        assertEquals(GROW, HomeLayoutJson.parse(raw2).partySize)
    }

    /** the solver lays the picks' rows: every dock x side x the bottom controller's fight / the rest, Party mode on (full width) */
    @Test fun theSolverLaysThePicksRows() {
        for (dock in HomeLayout.PartyDock.values()) for (side in HomeLayout.Side.values()) for (g in G) for (z in Z) {
            val l = HomeLayout(party = dock, chatSide = side, partyGrow = g, partySize = z, cast = HomeLayout.CastDock.UNDER_CHAT)
            for (fight in listOf(false, true)) for (cnt in listOf(1, 4, 6)) {
                val mode = if (fight) PartyLayout.Mode.FIGHT_ON else PartyLayout.Mode.REST
                val s = HomeLayoutSolver.State(party = HomeLayoutSolver.Party(mode, cnt, cnt == 6), partyOn = true, partyFight = fight)
                val r = S.places(l, s)
                val tag = "$dock $side $g $z fight=$fight n=$cnt"
                assertTrue("$tag: ${HomeLayoutRules.problems(l, s, r)}", HomeLayoutRules.problems(l, s, r).isEmpty())
                val want = PartyLayout.sizedH(mode, true, l)!!
                assertEquals(tag, want, r.partyRowH, 0.01f)
                val p = r.boxes.getValue(HomePart.PARTY)
                assertEquals(tag, PartyLayout.module(mode, cnt, pet = cnt == 6, top = 0f, rowH = want).well.b, p.h, 0.01f)
            }
        }
        // Same size gives the chat and the map the room Grow takes: taller by the rows' growth (3 rows of a party of 5)
        val s = HomeLayoutSolver.State(party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, 5, false), partyOn = true, partyFight = true)
        val grow = S.places(HomeLayout(), s).boxes; val same = S.places(HomeLayout(partySize = SAME), s).boxes
        for (p in listOf(HomePart.CHAT, HomePart.MAP)) assertEquals(p.name, grow.getValue(p).h + 3 * (PartyLayout.FIGHT_H - PartyLayout.REST_H),
            same.getValue(p).h, 0.01f)
    }

    /** Settings > Bottom screen > Party: one tile, its band's two lines the bubble's rows, each press this character's record only */
    @Test fun theSettingsTileWritesThisCharactersRecord() {
        // (edit tabs, the proposal's decision 5 A: the tile left Settings - Edit Home's Party mode tab is its one place; its lines and
        // press stay the bubble's words)
        assertEquals(emptyList<String>(), BottomTiles.tiles(SettingsTabs.Group.PARTY_LIST))
        assertTrue(BottomTiles.PARTY in BottomTiles.TAB_TILES)
        assertEquals(SettingsTabs.Tab.BOTTOM, SettingsTabs.Group.PARTY_LIST.tab)
        // (headed "Party list": Top screen has its own "Party" group, the game's party frames)
        assertEquals("Party list", SettingsTabs.Group.PARTY_LIST.heading)
        assertEquals("Party", SettingsTabs.Group.PARTY.heading)
        assertEquals("Party list", SettingsTabs.Block.PARTY_LIST.heading)
        assertEquals("Party list", BottomTiles.NAMES[BottomTiles.PARTY])
        assertFalse(BottomTiles.PARTY in BottomTiles.ALL)
        val p = HomeLayoutStoreTest.Prefs()
        HomeSettings.startForTest(p)
        HomeLayoutStore.character("Korvash-Realm")
        val env = BottomTiles.Env(null, "Korvash-Realm")
        val lines = BottomTiles.lines(BottomTiles.PARTY, env)
        assertEquals(listOf(HomeEditModel.K_PARTY_GROW, HomeEditModel.K_PARTY_SIZE), lines.map { it.key })
        assertEquals(listOf("Grows", "Size in fights"), lines.map { it.label })
        // (the band's game buttons: the short words; the bubble keeps the long ones)
        assertEquals(listOf(listOf("Off", "In fights", "Party mode on"), listOf("Grow", "Same size")), lines.map { it.words })
        assertEquals(listOf(1, 0), lines.map { it.picked })
        assertEquals("In fights", BottomTiles.value(BottomTiles.PARTY, env))
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_GROW, 2, env) { }
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_SIZE, 1, env) { }
        val l = HomeSettings.layout
        assertEquals(HomeLayout.PartyGrow.ALWAYS, l.partyGrow); assertEquals(SAME, l.partySize)
        assertEquals(listOf(2, 1), BottomTiles.lines(BottomTiles.PARTY, env).map { it.picked })
        assertEquals("Party mode on", BottomTiles.value(BottomTiles.PARTY, env))
        // another character keeps today's
        HomeLayoutStore.character("Thrandil-Realm")
        assertEquals(HomeLayout().partyGrow, HomeSettings.layout.partyGrow)
        assertEquals(listOf(1, 0), BottomTiles.lines(BottomTiles.PARTY, BottomTiles.Env(null, "Thrandil-Realm")).map { it.picked })
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(SAME, HomeSettings.layout.partySize)
        // an out-of-range press writes nothing
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_GROW, 9, env) { }
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_SIZE, 2, env) { }
        assertEquals(HomeLayout.PartyGrow.ALWAYS, HomeSettings.layout.partyGrow); assertEquals(SAME, HomeSettings.layout.partySize)
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_SIZE, 0, env) { }
        assertEquals(GROW, HomeSettings.layout.partySize)
    }

    /** Edit Home: the party list's pencil - a pencil (its bubble), never a band piece, never moved; its bubble's rows; a pick changes the
     *  draft (a layout row: the glide, saved as it goes, Discard puts it back); Preview: the fight preview with the bubble kept open */
    @Test fun thePartyPencilsBubble() {
        val saved = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val m = HomeEditModel(saved, EditStop.CHAT)
        assertTrue(EditStop.PARTY_PEN.pencil); assertTrue(EditStop.PARTY_PEN.hasMenu); assertFalse(EditStop.PARTY_PEN.band)
        assertTrue(EditStop.PARTY_PEN in HomeEditModel.GONE_CHECKED)
        assertTrue(m.pencilTap(EditStop.PARTY_PEN).isEmpty())
        assertEquals(EditStop.PARTY_PEN, m.menu); assertEquals(EditStop.PARTY_PEN, m.cursor)
        // (edit tabs: no Preview row - the Party mode tab's ribbon Fight toggle shows the fight)
        val rows = { HomeWidgetMenus.rows(EditStop.PARTY_PEN, m.draft, HomeWidgetMenus.Values(fightPreview = m.fightPreview)) }
        assertEquals(listOf(HomeEditModel.K_PARTY_GROW, HomeEditModel.K_PARTY_SIZE), rows().map { it.key })
        assertEquals(listOf("Grows", "Size in fights"), rows().map { it.label })
        assertEquals(listOf(listOf("Off", "In fights", "Whenever Party mode is on"), listOf("Grow", "Same size")), rows().map { it.words })
        assertEquals(listOf(1, 0), rows().map { it.picked })
        assertEquals("Party list", HomeWidgetMenus.title(EditStop.PARTY_PEN))
        // a pick: the draft, a Relay (the glide)
        val fx = m.menuTouch(0, 2, rows())
        assertTrue(fx.any { it is HomeEditModel.Fx.Relay })
        assertEquals(HomeLayout.PartyGrow.ALWAYS, m.draft.partyGrow)
        assertTrue(m.changed)
        assertTrue(m.menuTouch(1, 1, rows()).any { it is HomeEditModel.Fx.Relay })
        assertEquals(SAME, m.draft.partySize)
        assertEquals(listOf(2, 1), rows().map { it.picked })
        assertTrue("the same pick again: nothing", m.menuTouch(1, 1, rows()).isEmpty())
        // the controller: Down / A walk and cycle the rows (two stops: Grows, Size in fights), B closes the bubble
        m.menuRow = 0
        m.key("PADDDOWN", emptyMap()) { rows() }
        assertEquals(1, m.menuRow)
        m.key("PAD1", emptyMap()) { rows() }
        assertEquals("A cycles Same size -> Grow", GROW, m.draft.partySize)
        m.key("PADDRIGHT", emptyMap()) { rows() }
        assertEquals(SAME, m.draft.partySize)
        m.key("PADDDOWN", emptyMap()) { rows() }
        assertEquals("Size in fights is the last", 1, m.menuRow)
        m.key("PADDUP", emptyMap()) { rows() }
        assertEquals(0, m.menuRow)
        m.key("PAD2", emptyMap()) { rows() }
        assertNull(m.menu)
        assertEquals(EditStop.PARTY_PEN, m.cursor)
        // the same pencil tapped twice: open, closed
        m.pencilTap(EditStop.PARTY_PEN); assertEquals(EditStop.PARTY_PEN, m.menu)
        m.pencilTap(EditStop.PARTY_PEN); assertNull(m.menu)
        // A on the pencil opens the bubble; X too
        m.key("PAD1", mapOf(EditStop.PARTY_PEN to HomeLayoutSolver.Box(1180f, 240f, 1220f, 280f))) { rows() }
        assertEquals(EditStop.PARTY_PEN, m.menu)
        m.key("PAD2", emptyMap()) { rows() }
        m.key("PAD3", mapOf(EditStop.PARTY_PEN to HomeLayoutSolver.Box(1180f, 240f, 1220f, 280f))) { rows() }
        assertEquals(EditStop.PARTY_PEN, m.menu)
        // Discard: the picks back
        assertTrue(m.discard().contains(HomeEditModel.Fx.Discard))
        assertEquals(saved, m.draft)
    }

    /**
     * GROWS = OFF GREYS WHAT DOES NOTHING (the user's pick 2026-11-23, "Grey them out": only the choices that do something stay active):
     * Grow keeps the size then, as Same size - dimmed in the bubble and on the Settings tile, a press refused (nothing written), the
     * cursor stepping over it; the row lights Same size (what the list does) while the record keeps its pick; Grows back on: both words,
     * the pick lit again
     */
    @Test fun growsOffGreysTheChoicesThatDoNothing() {
        // every Grows x pick: the dimmed words are exactly those whose rows equal Same size's in every fight mode (and are not Same size)
        fun asSame(l: HomeLayout, z: HomeLayout.PartySize) = listOf(PartyLayout.Mode.FIGHT_ON, PartyLayout.Mode.FIGHT_TOP).all { m ->
            listOf(false, true).all { on -> PartyLayout.sizedH(m, on, l.copy(partySize = z)) == PartyLayout.sizedH(m, on, l.copy(partySize = SAME)) } }
        for (g in G) for (z in Z) {
            val l = HomeLayout(partyGrow = g, partySize = z)
            val rows = HomeWidgetMenus.partySizingRows(l)
            val tag = "$g $z"
            assertEquals(tag, 2, rows.size)
            assertEquals(tag, Z.filter { it != SAME && asSame(l, it) }.map { it.ordinal }.toSet(), rows[1].off)
            assertTrue(tag, rows[0].off.isEmpty())
            assertEquals(tag, g == HomeLayout.PartyGrow.OFF, rows[1].off == setOf(GROW.ordinal))
            // the lit word: the pick, or Same size while the pick is dimmed
            assertEquals(tag, if (z.ordinal in rows[1].off) SAME.ordinal else z.ordinal, rows[1].picked)
            assertFalse(tag, rows[1].picked in rows[1].off)
        }
        // the bubble: the dimmed word refused, A / Left / Right refused too (Same size is the only word left)
        val m = HomeEditModel(HomeLayout(partyGrow = HomeLayout.PartyGrow.OFF), EditStop.CHAT)
        m.pencilTap(EditStop.PARTY_PEN)
        val rows = { HomeWidgetMenus.rows(EditStop.PARTY_PEN, m.draft, HomeWidgetMenus.Values(fightPreview = m.fightPreview)) }
        val refused = listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Refuse(EditStop.PARTY_PEN))
        assertEquals(listOf(0, 1), rows().map { it.picked })
        assertEquals(refused, m.menuTouch(1, 0, rows()))
        assertEquals("the record keeps its pick", GROW, m.draft.partySize)
        m.menuRow = 1
        assertEquals("only Same size - A refused", refused, m.key("PAD1", emptyMap()) { rows() })
        assertEquals(refused, m.key("PADDLEFT", emptyMap()) { rows() })
        assertTrue(m.key("PADDRIGHT", emptyMap()) { rows() }.none { it is HomeEditModel.Fx.Relay })
        assertEquals(GROW, m.draft.partySize)
        // (edit tabs: no Preview row under it - the row is the bubble's last)
        m.key("PADDDOWN", emptyMap()) { rows() }
        assertEquals(1, m.menuRow)
        // Grows back on: both words, the record's pick lit (Grow kept)
        m.menuRow = 0
        m.key("PADDRIGHT", emptyMap()) { rows() }
        assertEquals(HomeLayout.PartyGrow.FIGHTS, m.draft.partyGrow)
        assertTrue(rows()[1].off.isEmpty())
        assertEquals(listOf(1, 0), rows().map { it.picked })
        // Settings' tile: the same dimmed word, a press on it writes nothing; Same size writes
        HomeSettings.startForTest(HomeLayoutStoreTest.Prefs())
        HomeLayoutStore.character("Korvash-Realm")
        val env = BottomTiles.Env(null, "Korvash-Realm")
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_GROW, 0, env) { }
        val lines = BottomTiles.lines(BottomTiles.PARTY, env)
        assertEquals(listOf(emptySet(), setOf(0)), lines.map { it.off })
        assertEquals(listOf(0, 1), lines.map { it.picked })
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_SIZE, 1, env) { }
        assertEquals(SAME, HomeSettings.layout.partySize)
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_SIZE, 0, env) { }
        assertEquals("dimmed: nothing written", SAME, HomeSettings.layout.partySize)
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_GROW, 1, env) { }
        assertTrue(BottomTiles.lines(BottomTiles.PARTY, env).all { it.off.isEmpty() })
        assertEquals(listOf(1, 1), BottomTiles.lines(BottomTiles.PARTY, env).map { it.picked })
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_SIZE, 0, env) { }
        assertEquals(GROW, HomeSettings.layout.partySize)
    }

    /**
     * EDIT TABS (2026-11-24): the party bubble's Preview row went - the Party mode tab's ribbon Fight toggle shows the fight; the bubble
     * opening or closing never changes the preview (the 11-23 "Off on close" had nothing to end any more)
     */
    @Test fun theBubbleLeavesTheFightToggleAlone() {
        val m = HomeEditModel(HomeLayout(), EditStop.CHAT, HomeTab.PARTY)
        val rows = { HomeWidgetMenus.rows(EditStop.PARTY_PEN, m.page, HomeWidgetMenus.Values(), m.tab) }
        assertTrue(rows().none { it.key == HomeEditModel.K_PARTY_PREVIEW })
        assertEquals(listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Preview(true)), m.toggleFight())
        m.pencilTap(EditStop.PARTY_PEN); assertEquals(EditStop.PARTY_PEN, m.menu)
        m.key("PAD2", emptyMap()) { rows() }
        assertNull(m.menu); assertTrue("the ribbon's preview stays", m.fightPreview)
        m.pencilTap(EditStop.PARTY_PEN); m.closeMenu(); assertTrue(m.fightPreview)
        m.toggleFight(); m.pencilTap(EditStop.PARTY_PEN); m.closeMenu(); assertFalse(m.fightPreview)
    }

    /**
     * STAY IN ITS COLUMN (the user's pick 2026-11-23): a party list at the FOOT keeps its column whenever the record's tap-to-cast bar
     * lies under one column (Under chat / Under map) - no gap under a list across on the bar-free side - and the sizing picks still lay
     * its rows; across with no bar, Across, Split, or a bar above a column; a top dock always across
     */
    @Test fun aFootListOverAOneColumnBarStaysInItsColumn() {
        val p5 = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, 5, false)
        val s = HomeLayoutSolver.State(party = p5, partyOn = true, partyFight = true)
        for (dock in HomeLayout.PartyDock.values()) for (cast in HomeLayout.CastDock.values()) for (side in HomeLayout.Side.values()) {
            val l = HomeLayout(party = dock, cast = cast, chatSide = side, partySize = HomeLayout.PartySize.SAME)
            val r = S.places(l, s)
            val tag = "$dock $cast $side"
            assertTrue("$tag: ${HomeLayoutRules.problems(l, s, r)}", HomeLayoutRules.problems(l, s, r).isEmpty())
            val oneCol = cast == HomeLayout.CastDock.UNDER_CHAT || cast == HomeLayout.CastDock.UNDER_MAP
            assertEquals(tag, oneCol, S.oneColumnFootBar(l))
            assertEquals(tag, dock.top || !oneCol, r.partyWide)
            assertEquals(tag, dock.top || !oneCol, S.wideParty(l, s))
            assertEquals(tag, PartyLayout.REST_H, r.partyRowH, 0.01f)
            if (r.partyWide) continue
            // in its column: no gap under it (its foot the column's foot), the column's part a gap over it
            val party = r.boxes.getValue(HomePart.PARTY)
            val col = r.boxes.getValue(if (dock.column == HomeModule.CHAT) HomePart.CHAT else HomePart.MAP)
            assertEquals(tag, party.t - PartyLayout.CHAT_GAP, col.b, 0.01f)
            val strip = r.boxes.getValue(HomePart.STRIP)
            val under = strip.l <= col.cx && col.cx <= strip.r
            assertEquals(tag, if (under) strip.t - 8f else 927f, party.b, 0.01f)
            assertTrue(tag, party.w < 700f)
        }
        // Party mode off: never across, any record
        for (dock in HomeLayout.PartyDock.values()) assertFalse(S.wideParty(HomeLayout(party = dock), s.copy(partyOn = false)))
    }

    /** the cursor on the pencil when the party list goes (QOL > Party mode off): to the chat, its bubble closed */
    @Test fun thePencilGoneMovesTheCursor() {
        val m = HomeEditModel(HomeLayout(), EditStop.CHAT)
        m.pencilTap(EditStop.PARTY_PEN)
        val stops = mapOf(EditStop.CHAT to HomeLayoutSolver.Box(8f, 234f, 616f, 927f), EditStop.MAP to HomeLayoutSolver.Box(624f, 234f, 1232f, 927f))
        assertTrue(m.cursorCheck(stops))
        assertEquals(EditStop.CHAT, m.cursor); assertNull(m.menu)
    }

    /** es / fr: every new word, CRLF lines kept */
    @Test fun theWordsAreTranslated() {
        val dir = sequenceOf("companion/app/src/main/assets/companion/i18n", "../companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        val words = listOf("Party list", "Party", "Grows", "Off", "In fights", "Whenever Party mode is on", "Size in fights", "Same size",
            "Grow", "Preview", "Rest", "Fight", "Party mode on")
        // (test 1523's two rows' words, gone with them)
        val gone = listOf("Controller on top (fights)", "Controller on bottom (fights)", "Fill the freed space", "Grow a bit",
            "Shrink to make room", "Fill", "Shrink")
        for (l in listOf("es", "fr")) {
            val text = java.io.File(dir, "$l.json").readBytes().toString(Charsets.UTF_8)
            val plain = org.json.JSONObject(text).getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w) && plain.getString(w).isNotBlank())
            for (w in gone) assertFalse("$l: $w gone", plain.has(w))
            // the label never reads as Grows' own "In fights"
            assertTrue(l, plain.getString("Size in fights") != plain.getString("In fights"))
            assertTrue("$l: CRLF lines", text.split("\n").dropLast(1).all { it.endsWith("\r") })
        }
    }
}
