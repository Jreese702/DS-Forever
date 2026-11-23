package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARTY SIZING (the user, 2026-11-23: "We should add some dynamic sizing options to the party mode windows, so if it goes while in combat
 * like the combat bar or only in party mode and then if the controlls are on the top or bottom screens"; the picks: "Player picks" for
 * all three, "Settings + Edit Home"): the three per-character picks on the layout record (HomeLayout.partyGrow / partyTop / partyBottom),
 * their rows' heights ([PartyLayout.sizedH]: the defaults today's exactly), the record's text (a Classic record's unchanged), Settings >
 * Bottom screen > Party's tile (BottomTiles.PARTY: this character's record) and the party list's pencil bubble in Edit Home
 * (EditStop.PARTY_PEN: the same three rows, the preview's Rest / Fight; Discard puts the picks back).
 */
class PartySizingTest {
    private val S = HomeLayoutSolver
    private val G = HomeLayout.PartyGrow.values()
    private val T = HomeLayout.PartyTopFight.values()
    private val B = HomeLayout.PartyBottomFight.values()
    private val M = PartyLayout.Mode.values()

    @After fun down() { HomeSettings.startForTest(null); RealmFix.forget() }

    /** the defaults are today's rows: rest 64, a Party fight 88, a Party-mode Top fight the fill (null), the takeover 56 */
    @Test fun theDefaultsAreToday() {
        val d = HomeLayout()
        assertEquals(HomeLayout.PartyGrow.FIGHTS, d.partyGrow)
        assertEquals(HomeLayout.PartyTopFight.FILL, d.partyTop)
        assertEquals(HomeLayout.PartyBottomFight.GROW, d.partyBottom)
        for (on in listOf(false, true)) {
            assertEquals(PartyLayout.REST_H, PartyLayout.sizedH(PartyLayout.Mode.REST, on, d))
            assertEquals(PartyLayout.FIGHT_H, PartyLayout.sizedH(PartyLayout.Mode.FIGHT_ON, on, d))
            assertNull(PartyLayout.sizedH(PartyLayout.Mode.FIGHT_TOP, on, d))
            assertEquals(PartyLayout.TAKEOVER_H, PartyLayout.sizedH(PartyLayout.Mode.FIGHT_OFF, on, d))
        }
        // the defaults' heights are each mode's own (the view's plateH: nothing changes until a player picks)
        for (m in M) PartyLayout.sizedH(m, true, d)?.let { assertEquals(m.name, PartyLayout.plateH(m), it) }
    }

    /** the whole matrix: 3 x 3 x 3 picks x 4 modes x Party mode on / off */
    @Test fun everyPickGivesItsRows() {
        val rest = PartyLayout.REST_H; val fight = PartyLayout.FIGHT_H; val shrink = PartyLayout.TAKEOVER_H
        var n = 0
        for (g in G) for (t in T) for (b in B) for (m in M) for (on in listOf(false, true)) {
            val l = HomeLayout(partyGrow = g, partyTop = t, partyBottom = b)
            val base = if (on && g == HomeLayout.PartyGrow.ALWAYS) fight else rest
            val grows = g != HomeLayout.PartyGrow.OFF
            val want: Float? = when (m) {
                PartyLayout.Mode.REST -> base
                PartyLayout.Mode.FIGHT_OFF -> shrink
                PartyLayout.Mode.FIGHT_ON -> when (b) {
                    HomeLayout.PartyBottomFight.GROW -> if (grows) fight else base
                    HomeLayout.PartyBottomFight.SAME -> base
                    HomeLayout.PartyBottomFight.SHRINK -> shrink
                }
                PartyLayout.Mode.FIGHT_TOP -> when (t) {
                    HomeLayout.PartyTopFight.FILL -> if (grows) null else base
                    HomeLayout.PartyTopFight.GROW -> if (grows) fight else base
                    HomeLayout.PartyTopFight.SAME -> base
                }
            }
            assertEquals("$g $t $b $m on=$on", want, PartyLayout.sizedH(m, on, l))
            n++
        }
        assertEquals(27 * 4 * 2, n)
        // Party mode off at rest: 64 whatever is picked; the takeover (Party mode off in a fight) always 56
        for (g in G) assertEquals(rest, PartyLayout.sizedH(PartyLayout.Mode.REST, false, HomeLayout(partyGrow = g)))
    }

    /** the record's text: a Classic record's unchanged; each pick written only off its default, read back; junk falls back */
    @Test fun theRecordCarriesThePicks() {
        val classic = HomeLayoutJson.json(HomeLayout())
        for (k in listOf("pgrow", "ptop", "pbot")) assertFalse(classic.contains(k))
        for (g in G) for (t in T) for (b in B) {
            val l = HomeLayout(partyGrow = g, partyTop = t, partyBottom = b)
            val s = HomeLayoutJson.json(l)
            assertEquals(g != HomeLayout.PartyGrow.FIGHTS, s.contains("\"pgrow\":\"${g.name}\""))
            assertEquals(t != HomeLayout.PartyTopFight.FILL, s.contains("\"ptop\":\"${t.name}\""))
            assertEquals(b != HomeLayout.PartyBottomFight.GROW, s.contains("\"pbot\":\"${b.name}\""))
            assertEquals(l, HomeLayoutJson.parse(s))
            // Reset Home to Classic: today's again; a swap keeps them
            val c = l.classic()
            assertEquals(HomeLayout.PartyGrow.FIGHTS, c.partyGrow); assertEquals(HomeLayout.PartyTopFight.FILL, c.partyTop)
            assertEquals(HomeLayout.PartyBottomFight.GROW, c.partyBottom)
            assertEquals(l.partyGrow, l.swapped().partyGrow); assertEquals(l.partyBottom, l.swapped().partyBottom)
            // a pick is a change edit mode's Discard puts back
            assertEquals(l == HomeLayout(), l.samePlace(HomeLayout()))
        }
        val plain = """{"v":1,"name":"Classic","chat":"RIGHT"}"""
        for (junk in listOf(""","pgrow":"BIG"}""", ""","ptop":7}""", ""","pbot":null}""", ""","pgrow":["OFF"]}"""))
            assertEquals(HomeLayoutJson.parse(plain), HomeLayoutJson.parse(plain.dropLast(1) + junk))
    }

    /** the solver lays the picks' rows: every dock x side x the bottom controller's fight / the rest, Party mode on (full width) */
    @Test fun theSolverLaysThePicksRows() {
        for (dock in HomeLayout.PartyDock.values()) for (side in HomeLayout.Side.values()) for (g in G) for (b in B) {
            val l = HomeLayout(party = dock, chatSide = side, partyGrow = g, partyBottom = b, cast = HomeLayout.CastDock.UNDER_CHAT)
            for (fight in listOf(false, true)) for (cnt in listOf(1, 4, 6)) {
                val mode = if (fight) PartyLayout.Mode.FIGHT_ON else PartyLayout.Mode.REST
                val s = HomeLayoutSolver.State(party = HomeLayoutSolver.Party(mode, cnt, cnt == 6), partyOn = true, partyFight = fight)
                val r = S.places(l, s)
                val tag = "$dock $side $g $b fight=$fight n=$cnt"
                assertTrue("$tag: ${HomeLayoutRules.problems(l, s, r)}", HomeLayoutRules.problems(l, s, r).isEmpty())
                val want = PartyLayout.sizedH(mode, true, l)!!
                assertEquals(tag, want, r.partyRowH, 0.01f)
                val p = r.boxes.getValue(HomePart.PARTY)
                assertEquals(tag, PartyLayout.module(mode, cnt, pet = cnt == 6, top = 0f, rowH = want).well.b, p.h, 0.01f)
            }
        }
        // Shrink makes room: the chat and the map taller than with Grow
        val s = HomeLayoutSolver.State(party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, 5, false), partyOn = true, partyFight = true)
        val grow = S.places(HomeLayout(), s).boxes; val shrink = S.places(HomeLayout(partyBottom = HomeLayout.PartyBottomFight.SHRINK), s).boxes
        for (p in listOf(HomePart.CHAT, HomePart.MAP)) assertEquals(p.name, grow.getValue(p).h + 3 * (PartyLayout.FIGHT_H - PartyLayout.TAKEOVER_H),
            shrink.getValue(p).h, 0.01f)
    }

    /** Settings > Bottom screen > Party: one tile, its band's three lines the bubble's rows, each press this character's record only */
    @Test fun theSettingsTileWritesThisCharactersRecord() {
        assertEquals(listOf(BottomTiles.PARTY), BottomTiles.tiles(SettingsTabs.Group.PARTY_LIST))
        assertEquals(SettingsTabs.Tab.BOTTOM, SettingsTabs.Group.PARTY_LIST.tab)
        assertEquals("Party", SettingsTabs.Group.PARTY_LIST.heading)
        assertEquals("Party list", BottomTiles.NAMES[BottomTiles.PARTY])
        assertTrue(BottomTiles.PARTY in BottomTiles.ALL)
        val p = HomeLayoutStoreTest.Prefs()
        HomeSettings.startForTest(p)
        HomeLayoutStore.character("Korvash-Realm")
        val env = BottomTiles.Env(null, "Korvash-Realm")
        val lines = BottomTiles.lines(BottomTiles.PARTY, env)
        assertEquals(listOf(HomeEditModel.K_PARTY_GROW, HomeEditModel.K_PARTY_TOP, HomeEditModel.K_PARTY_BOTTOM), lines.map { it.key })
        assertEquals(listOf("Grows", "Controller on top (fights)", "Controller on bottom (fights)"), lines.map { it.label })
        // (the band's game buttons: the short words; the bubble keeps the long ones)
        assertEquals(listOf(listOf("Off", "In fights", "Party mode on"), listOf("Fill", "Grow a bit", "Same size"),
            listOf("Grow", "Same size", "Shrink")), lines.map { it.words })
        assertEquals(listOf(1, 0, 0), lines.map { it.picked })
        assertEquals("In fights", BottomTiles.value(BottomTiles.PARTY, env))
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_GROW, 2, env) { }
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_TOP, 1, env) { }
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_BOTTOM, 2, env) { }
        val l = HomeSettings.layout
        assertEquals(HomeLayout.PartyGrow.ALWAYS, l.partyGrow); assertEquals(HomeLayout.PartyTopFight.GROW, l.partyTop)
        assertEquals(HomeLayout.PartyBottomFight.SHRINK, l.partyBottom)
        assertEquals(listOf(2, 1, 2), BottomTiles.lines(BottomTiles.PARTY, env).map { it.picked })
        assertEquals("Party mode on", BottomTiles.value(BottomTiles.PARTY, env))
        // another character keeps today's
        HomeLayoutStore.character("Thrandil-Realm")
        assertEquals(HomeLayout().partyGrow, HomeSettings.layout.partyGrow)
        assertEquals(listOf(1, 0, 0), BottomTiles.lines(BottomTiles.PARTY, BottomTiles.Env(null, "Thrandil-Realm")).map { it.picked })
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(HomeLayout.PartyBottomFight.SHRINK, HomeSettings.layout.partyBottom)
        // an out-of-range press writes nothing
        BottomTiles.press(BottomTiles.PARTY, HomeEditModel.K_PARTY_GROW, 9, env) { }
        assertEquals(HomeLayout.PartyGrow.ALWAYS, HomeSettings.layout.partyGrow)
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
        val rows = { HomeWidgetMenus.rows(EditStop.PARTY_PEN, m.draft, HomeWidgetMenus.Values(fightPreview = m.fightPreview)) }
        assertEquals(listOf(HomeEditModel.K_PARTY_GROW, HomeEditModel.K_PARTY_TOP, HomeEditModel.K_PARTY_BOTTOM, HomeEditModel.K_PARTY_PREVIEW),
            rows().map { it.key })
        assertEquals(listOf("Grows", "Controller on top (fights)", "Controller on bottom (fights)", "Preview"), rows().map { it.label })
        assertEquals(listOf(1, 0, 0, 0), rows().map { it.picked })
        assertEquals("Party list", HomeWidgetMenus.title(EditStop.PARTY_PEN))
        // a pick: the draft, a Relay (the glide)
        val fx = m.menuTouch(0, 2, rows())
        assertTrue(fx.any { it is HomeEditModel.Fx.Relay })
        assertEquals(HomeLayout.PartyGrow.ALWAYS, m.draft.partyGrow)
        assertTrue(m.changed)
        m.menuTouch(2, 1, rows())
        assertEquals(HomeLayout.PartyBottomFight.SAME, m.draft.partyBottom)
        assertEquals(listOf(2, 0, 1, 0), rows().map { it.picked })
        // the preview: on with the bubble open, off again
        assertEquals(listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Preview(true)), m.menuTouch(3, 1, rows()))
        assertTrue(m.fightPreview); assertEquals(EditStop.PARTY_PEN, m.menu)
        assertEquals(1, rows()[3].picked)
        assertTrue("the same pick again: nothing", m.menuTouch(3, 1, rows()).isEmpty())
        assertEquals(listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Preview(false)), m.menuTouch(3, 0, rows()))
        assertFalse(m.fightPreview)
        // the controller: Down / A walk and cycle the rows, B closes the bubble
        m.menuRow = 0
        m.key("PADDDOWN", emptyMap()) { rows() }
        assertEquals(1, m.menuRow)
        m.key("PAD1", emptyMap()) { rows() }
        assertEquals(HomeLayout.PartyTopFight.GROW, m.draft.partyTop)
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
        val words = listOf("Party list", "Party", "Grows", "Off", "In fights", "Whenever Party mode is on", "Controller on top (fights)",
            "Controller on bottom (fights)", "Fill the freed space", "Grow a bit", "Same size", "Grow", "Shrink to make room", "Preview",
            "Rest", "Fight", "Party mode on", "Fill", "Shrink")
        for (l in listOf("es", "fr")) {
            val text = java.io.File(dir, "$l.json").readBytes().toString(Charsets.UTF_8)
            val plain = org.json.JSONObject(text).getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w) && plain.getString(w).isNotBlank())
            assertTrue("$l: CRLF lines", text.split("\n").dropLast(1).all { it.endsWith("\r") })
        }
    }
}
