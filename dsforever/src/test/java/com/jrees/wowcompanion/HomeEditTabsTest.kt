package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EDIT HOME'S THREE TABS (the user, 2026-11-24: "I want to add tabs to the top of edit mode for out of combat, in combat and party mode
 * ... have custom layouts for each mode"; the picks: "Fully separate", per tab the places + the fight rows + the clock's badges, the
 * Party mode layout "Whenever Party mode is on", "8 slots, fill the width"): the record's tabs (each its own copy, a change on one never
 * moving another), their JSON (a Classic record's text unchanged), the solver over every tab x dock x swap x state, the store's one-time
 * migration of the app-wide fight settings, the facades the Controller room's chips write, the runtime tab (incl. B on Home), the edit
 * model's tab switch / LB / RB / per-tab rows / Discard, and Grow's fill numbers. Pure.
 */
class HomeEditTabsTest {
    @After fun down() { HomeSettings.startForTest(null) }

    private val S = HomeLayoutSolver
    private val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
    private val C = HomeTab.COMBAT
    private val P = HomeTab.PARTY
    private val O = HomeTab.OUT

    // ---------------------------------------------------------------- the record

    /** a record of before the tabs: every tab is its places' copy; Party mode's Combat bars follow In fights as Party mode did (R0) */
    @Test fun aRecordWithNoTabsIsTheSamePlacesOnEveryTab() {
        for (fight in HomeLayout.FightBand.values()) {
            val l = classic.copy(chatShare = HomeLayout.Share.THIRD, fight = fight, fightCast = HomeLayout.FightCast.TOP)
            for (t in HomeTab.values()) {
                val p = l.forTab(t)
                assertEquals(t.name, HomeLayout.TabPlaces.of(l), HomeLayout.TabPlaces.of(p))
                assertFalse(t.name, p.grow)
            }
            assertEquals(fight, l.forTab(C).fight)
            assertEquals("Party mode never grew (R0): Top screen stays, the rest No grow",
                if (fight == HomeLayout.FightBand.TOP) HomeLayout.FightBand.TOP else HomeLayout.FightBand.SAME, l.forTab(P).fight)
            assertEquals(HomeLayout.FightCast.TOP, l.forTab(P).fightCast)
            assertNull(l.combat); assertNull(l.partyTab); assertNull(l.partyFight); assertNull(l.partyCast)
        }
    }

    /** the user's pick "Fully separate": a change on one tab never changes another - a tab that was still a copy keeps what it had */
    @Test fun aChangeOnOneTabNeverMovesAnother() {
        val l = classic
        // Out of combat's swap: the fight tabs keep the chat on the left
        val a = l.onTab(O) { it.swapped() }
        assertEquals(HomeLayout.Side.RIGHT, a.forTab(O).chatSide)
        assertEquals(HomeLayout.Side.LEFT, a.forTab(C).chatSide); assertEquals(HomeLayout.Side.LEFT, a.forTab(P).chatSide)
        // In combat's bar Across and its Grow: Out of combat and Party mode untouched
        val b = a.onTab(C) { it.copy(cast = HomeLayout.CastDock.ACROSS, grow = true) }
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, b.forTab(O).cast); assertEquals(HomeLayout.CastDock.UNDER_CHAT, b.forTab(P).cast)
        assertTrue(b.forTab(C).grow); assertFalse("Out of combat never grows", b.forTab(O).grow); assertFalse(b.forTab(P).grow)
        // In combat's Combat bars: Party mode's (still following it) keeps what it was
        val c = b.onTab(C) { it.copy(fight = HomeLayout.FightBand.TOP) }
        assertEquals(HomeLayout.FightBand.TOP, c.forTab(C).fight)
        assertEquals(HomeLayout.FightBand.SAME, c.forTab(P).fight)
        // Party mode's own Bottom screen: In combat untouched
        val d = c.onTab(P) { it.copy(fight = HomeLayout.FightBand.GROW, party = HomeLayout.PartyDock.UNDER_MAP) }
        assertEquals(HomeLayout.FightBand.GROW, d.forTab(P).fight); assertEquals(HomeLayout.FightBand.TOP, d.forTab(C).fight)
        assertEquals(HomeLayout.PartyDock.ABOVE_CHAT, d.forTab(O).party); assertEquals(HomeLayout.PartyDock.ABOVE_CHAT, d.forTab(C).party)
        // the shared fields are every tab's (the rest look, the room bar, the extras' order, the party list's sizing)
        val e = d.onTab(C) { it.copy(rest = HomeRest.ROW, xpBar = false, partyGrow = HomeLayout.PartyGrow.ALWAYS) }
        for (t in HomeTab.values()) { assertEquals(HomeRest.ROW, e.forTab(t).rest); assertFalse(e.forTab(t).xpBar)
            assertEquals(HomeLayout.PartyGrow.ALWAYS, e.forTab(t).partyGrow) }
        // every tab's own as it was set, whatever the order of the changes
        for ((t, cast) in listOf(O to HomeLayout.CastDock.UNDER_CHAT, C to HomeLayout.CastDock.ACROSS, P to HomeLayout.CastDock.UNDER_CHAT))
            assertEquals(t.name, cast, e.forTab(t).cast)
    }

    /** one form for one meaning: a tab set back to its copy is null again (no key written), and the JSON round trip holds */
    @Test fun theTabsAreCanonicalAndRoundTrip() {
        val a = classic.onTab(C) { it.copy(cast = HomeLayout.CastDock.ACROSS) }
        assertTrue(a.combat != null)
        val back = a.onTab(C) { it.copy(cast = HomeLayout.CastDock.UNDER_CHAT) }
        assertEquals(classic, back); assertNull(back.combat)
        val recs = listOf(classic, a, classic.onTab(P) { it.copy(fight = HomeLayout.FightBand.GROW, grow = true, card = HomeLayout.CardDock.OFF) },
            classic.onTab(O) { it.swapped() }.onTab(C) { it.copy(chatShare = HomeLayout.Share.TWO_THIRDS, fightCast = HomeLayout.FightCast.TOP) }
                .withBadges(C, listOf("gold", "hearth")).withBadges(P, emptyList()))
        for (l in recs) {
            assertEquals(l, HomeLayoutJson.parse(HomeLayoutJson.json(l)))
            assertEquals("the full form too (the store's lay-on)", l, HomeLayoutJson.parse(HomeLayoutJson.json(l, full = true)))
        }
        // badges: a fight tab's own; the Out of combat tab's are Settings' (never in the record)
        val bd = recs.last()
        assertEquals(listOf("gold", "hearth"), bd.badges(C)); assertEquals(emptyList<String>(), bd.badges(P)); assertNull(bd.badges(O))
        assertEquals(bd, bd.withBadges(O, listOf("gold")))
    }

    /** a Classic record's text is what it always was (no tab key), and an older build's text reads as it always did */
    @Test fun aClassicRecordsTextIsUnchanged() {
        val text = """{"v":1,"name":"Classic","chat":"LEFT","share":"HALF","card":"ON_MAP","cast":"OFF","size":"L","rest":"DIAMONDS","fight":"GROW","xp":true}"""
        // (the same keys and values: the JVM's org.json keeps no key order, Android's keeps the written one)
        fun flat(t: String) = org.json.JSONObject(t).let { o -> o.keys().asSequence().associateWith { o.get(it).toString() } }
        assertEquals(flat(text), flat(HomeLayoutJson.json(HomeLayout())))
        assertEquals(HomeLayout(), HomeLayoutJson.parse(text))
        // an older record's "size" (S / M) reads as nothing: every bar is L now
        assertEquals(HomeLayout(cast = HomeLayout.CastDock.ACROSS), HomeLayoutJson.parse(text.replace("\"OFF\"", "\"ACROSS\"").replace("\"L\"", "\"S\"")))
        // a tab's keys are flat and only where it differs
        val t = HomeLayoutJson.json(classic.onTab(C) { it.copy(cast = HomeLayout.CastDock.OFF) })
        assertTrue(t, t.contains("\"c.cast\":\"OFF\"")); assertFalse(t, t.contains("c.chat")); assertFalse(t, t.contains("p."))
    }

    /** Reset Home to Classic: all three tabs back (each fight tab's own bar kept in its column, Off kept); All characters copies them */
    @Test fun classicPutsEveryTabBack() {
        val l = classic.onTab(O) { it.swapped().copy(chatShare = HomeLayout.Share.THIRD) }
            .onTab(C) { it.copy(cast = HomeLayout.CastDock.SPLIT, grow = true, fight = HomeLayout.FightBand.TOP, card = HomeLayout.CardDock.OFF) }
            .onTab(P) { it.copy(cast = HomeLayout.CastDock.OFF, fight = HomeLayout.FightBand.GROW, party = HomeLayout.PartyDock.UNDER_MAP) }
            .withBadges(C, listOf("hearth"))
        val c = l.classic()
        for (t in HomeTab.values()) {
            val p = c.forTab(t)
            assertEquals(t.name, HomeLayout.Side.LEFT, p.chatSide); assertEquals(HomeLayout.Share.HALF, p.chatShare)
            assertEquals(HomeLayout.CardDock.ON_MAP, p.card); assertEquals(HomeLayout.PartyDock.ABOVE_CHAT, p.party); assertFalse(p.grow)
            assertNull(c.badges(t))
        }
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, c.forTab(C).cast)
        assertEquals("Off stays Off", HomeLayout.CastDock.OFF, c.forTab(P).cast)
        assertEquals(HomeLayout.FightBand.GROW, c.forTab(C).fight); assertEquals(HomeLayout.FightBand.SAME, c.forTab(P).fight)
    }

    // ---------------------------------------------------------------- the solver over every tab

    private fun states(): List<HomeLayoutSolver.State> {
        val out = ArrayList<HomeLayoutSolver.State>()
        for (card in listOf(0f, 118.75f)) for (n in listOf(0, 1, 5)) {
            val rest = if (n == 0) null else HomeLayoutSolver.Party(PartyLayout.Mode.REST, n, false)
            out += HomeLayoutSolver.State(card = card > 0f, cardH = card, party = rest)
            out += HomeLayoutSolver.State(card = card > 0f, cardH = card, takeover = true,
                party = if (n == 0) null else HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_OFF, n, false))
            if (n > 0) {
                out += HomeLayoutSolver.State(card = card > 0f, cardH = card, party = rest, partyOn = true)
                out += HomeLayoutSolver.State(card = card > 0f, cardH = card, partyOn = true, partyFight = true,
                    party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, n, false))
                // (Party mode's own Bottom screen: its fight lays the list in the takeover's rows - the proposal's decision 4 A)
                out += HomeLayoutSolver.State(card = card > 0f, cardH = card, partyOn = true, partyFight = true,
                    party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_OFF, n, false))
            }
            out += HomeLayoutSolver.State(card = card > 0f, cardH = card, takeover = true, castHidden = true)
        }
        return out
    }

    /** every tab's page of records whose tabs differ (each tab a dock of its own, swapped or not, Grow on its fight tabs, every Combat
     *  bars) is valid in every state its tab can be laid in - Party mode's Bottom screen grows the band in its fight */
    @Test fun everyTabsPageIsValidInEveryState() {
        val docks = HomeLayout.CastDock.values()
        var n = 0
        val bad = ArrayList<String>()
        for (d0 in docks) for (d1 in docks) for (swap in listOf(false, true)) for (grow in listOf(false, true))
            for (fight in HomeLayout.FightBand.values()) for (share in HomeLayout.Share.values()) {
                val rec = HomeLayout(cast = d0, chatShare = share)
                    .onTab(C) { (if (swap) it.swapped() else it).copy(cast = d1, grow = grow, fight = fight) }
                    .onTab(P) { it.copy(cast = docks[(d1.ordinal + 2) % docks.size], grow = !grow, fight = fight,
                        party = HomeLayout.PartyDock.values()[(d0.ordinal + d1.ordinal) % 4]) }
                for (t in HomeTab.values()) {
                    val page = rec.forTab(t)
                    for (s in states()) {
                        // the tab's own situations: Out of combat at rest (or B's minimized fight), In combat's takeover, Party mode on
                        val mode = s.party?.mode
                        val fits = when (t) { HomeTab.OUT -> !s.takeover && !s.partyOn; HomeTab.COMBAT -> s.takeover && !s.partyOn
                            // Party mode's fight lays the takeover's rows exactly when its own Bottom screen grows the band (HomeView.partyModeNow)
                            HomeTab.PARTY -> s.partyOn && (!s.partyFight || (mode == PartyLayout.Mode.FIGHT_OFF) == page.partyBand) }
                        if (!fits) continue
                        n++
                        val r = S.places(page, s)
                        val p = HomeLayoutRules.problems(page, s, r)
                        if (p.isNotEmpty() && bad.size < 12) bad += "$t $page / $s: $p"
                        if (t == P && s.partyFight && fight == HomeLayout.FightBand.GROW && !S.big(page, s) && bad.size < 12)
                            bad += "Party mode's Bottom screen did not grow the band: $page / $s"
                    }
                }
            }
        assertTrue("checked $n: ${bad.joinToString("\n")}", bad.isEmpty())
        assertTrue(n > 10000)
    }

    /** R0 lifted for Party mode's own Bottom screen only: its fight grows the band, a record of before the tabs never does */
    @Test fun partyModesBottomScreenGrowsTheBandInItsFight() {
        val fightOn = HomeLayoutSolver.State(partyOn = true, partyFight = true)
        assertFalse("a record of before the tabs (Grow in fights) - Party mode never grew", S.big(classic.forTab(P), fightOn))
        assertFalse("a record's own Grow laid with Party mode on: R0 as ever", S.big(classic, fightOn))
        val grown = classic.onTab(P) { it.copy(fight = HomeLayout.FightBand.GROW) }.forTab(P)
        assertTrue(S.big(grown, fightOn))
        assertFalse("not at rest", S.big(grown, HomeLayoutSolver.State(partyOn = true)))
        assertFalse("not with a module full screen", S.big(grown, fightOn.copy(full = HomeModule.MAP)))
        assertEquals(416f, S.barsBottom(grown, fightOn), 0f)
        assertTrue("the takeover still grows In combat's", S.big(classic.forTab(C), HomeLayoutSolver.State(takeover = true)))
    }

    // ---------------------------------------------------------------- Grow's fill (the pick "8 slots, fill the width")

    @Test fun growFillsTheBarsWidth() {
        fun bar(l: HomeLayout, s: HomeLayoutSolver.State = HomeLayoutSolver.State()) = S.places(l, s).boxes.getValue(HomePart.STRIP)
        fun side(b: HomeLayoutSolver.Box) = b.h - 2 * CastStripModel.PAD
        // Across: 8 in 1224 px - 143.5 (the normal 8 px gap) against Same size's 112
        val across = HomeLayout(cast = HomeLayout.CastDock.ACROSS)
        assertEquals(112f, side(bar(across)), 0.01f)
        assertEquals((1224f - 20f - 7 * 8f) / 8f, side(bar(across.copy(grow = true))), 0.01f)
        assertEquals(163.5f, bar(across.copy(grow = true)).h, 0.01f)
        // Split: 4 in each 608 px half; a half column: the same
        val split = HomeLayout(cast = HomeLayout.CastDock.SPLIT, grow = true)
        assertEquals(141f, side(bar(split)), 0.01f)
        assertEquals(141f, side(bar(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, grow = true))), 0.01f)
        assertEquals(141f, side(bar(HomeLayout(cast = HomeLayout.CastDock.ABOVE_MAP, grow = true))), 0.01f)
        // a 2/3 column: the cap (144)
        assertEquals(CastStripModel.GROW_MAX, side(bar(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, chatShare = HomeLayout.Share.TWO_THIRDS, grow = true))), 0.01f)
        // a 1/3 column: already full (the same as Same size)
        val third = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, chatShare = HomeLayout.Share.THIRD)
        assertEquals(side(bar(third)), side(bar(third.copy(grow = true))), 0.01f)
        assertTrue(side(bar(third)) < 112f)
        // the room comes from the column(s) above the bar: the chat ~30 px shorter
        val c0 = S.places(across, HomeLayoutSolver.State()).boxes.getValue(HomePart.CHAT)
        val c1 = S.places(across.copy(grow = true), HomeLayoutSolver.State()).boxes.getValue(HomePart.CHAT)
        assertEquals(31.5f, c0.h - c1.h, 0.01f)
        // the strip view draws the slots the bar's height gives (8 always: a spell never moves)
        val bw = 1224f
        assertEquals(143.5f, CastStripModel.slotBox(CastStripModel.Side.BOTH, 7, bw, 163.5f).let { it.r - it.l }, 0.01f)
        assertEquals(112f, CastStripModel.slotBox(CastStripModel.Side.BOTH, 7, bw, 132f).let { it.r - it.l }, 0.01f)
        assertTrue(CastStripModel.slotBox(CastStripModel.Side.BOTH, 7, bw, 163.5f).r <= bw - CastStripModel.PAD + 0.01f)
    }

    /** "If the map would drop under its 240 px minimum, Grow steps back toward Same size first": never a map shorter for Grow, never a
     *  fallback Grow alone fires */
    @Test fun growStepsBackBeforeAnyFallback() {
        var n = 0
        val bad = ArrayList<String>()
        for (l in HomeLayoutRules.all()) {
            if (!l.grow || l.cast == HomeLayout.CastDock.OFF) continue
            for (s in states()) {
                val g = S.places(l, s); val same = S.places(l.copy(grow = false), s)
                n++
                val m = g.boxes.getValue(HomePart.MAP).h; val m0 = same.boxes.getValue(HomePart.MAP).h
                if (m < HomeLayoutRules.MAP_MIN && m < m0 - 0.5f && bad.size < 10) bad += "map $m under $m0: $l / $s"
                if ((g.f1 != same.f1 || g.f2 != same.f2 || g.f3 != same.f3) && bad.size < 10) bad += "a fallback for Grow: $l / $s"
                val bar = g.boxes[HomePart.STRIP]; val bar0 = same.boxes[HomePart.STRIP]
                if (bar != null && bar0 != null && bar.h + 0.01f < bar0.h && bad.size < 10) bad += "Grow smaller than Same size: $l / $s"
            }
        }
        assertTrue("checked $n: ${bad.joinToString("\n")}", bad.isEmpty())
    }

    // ---------------------------------------------------------------- the runtime tab

    /** which tab's layout Home lays: Party mode's whenever Party mode is on, In combat's on OUR takeover (B on Home drops it: Out of combat
     *  again mid-fight), else Out of combat's */
    @Test fun theTabFollowsPartyModeAndOurTakeover() {
        assertEquals(O, HomeTab.active(partyOn = false, takeover = false))
        assertEquals(C, HomeTab.active(partyOn = false, takeover = true))
        assertEquals("B on Home: the takeover given back mid-fight", O, HomeTab.active(partyOn = false, takeover = false))
        assertEquals(P, HomeTab.active(partyOn = true, takeover = false))
        assertEquals("Party mode on in a fight: Party mode's", P, HomeTab.active(partyOn = true, takeover = true))
        assertEquals(P, HomeTab.opening(true)); assertEquals(O, HomeTab.opening(false))
        assertEquals(listOf(O, C, P), HomeTab.shown(true)); assertEquals(listOf(O, C), HomeTab.shown(false))
    }

    /** a fight tab's bar Off while Out of combat has one: the Out of combat dock, slid away (cb7) - the switch glides it; else the tab's
     *  own page */
    @Test fun anOffBarOnAFightTabLiesSlidAway() {
        val l = HomeLayout(cast = HomeLayout.CastDock.UNDER_MAP, chatShare = HomeLayout.Share.THIRD)
            .onTab(C) { it.copy(cast = HomeLayout.CastDock.OFF, chatShare = HomeLayout.Share.TWO_THIRDS) }
        val (p, hidden) = HomeTab.page(l, C)
        assertTrue(hidden); assertEquals(HomeLayout.CastDock.UNDER_MAP, p.cast); assertEquals(HomeLayout.Share.TWO_THIRDS, p.chatShare)
        val r = S.places(p, HomeLayoutSolver.State(takeover = true, castHidden = hidden))
        assertTrue(r.castHidden); assertEquals(935f, r.boxes.getValue(HomePart.STRIP).t, 0.01f)
        assertEquals(l.forTab(O) to false, HomeTab.page(l, O))
        assertEquals(l.forTab(P) to false, HomeTab.page(l, P))
        // nothing to slide when Out of combat has no bar either
        assertEquals(false, HomeTab.page(HomeLayout().onTab(C) { it.copy(cast = HomeLayout.CastDock.OFF) }, C).second)
    }

    // ---------------------------------------------------------------- the store's migration and the facades

    private fun fresh(p: HomeLayoutStoreTest.Prefs = HomeLayoutStoreTest.Prefs()): HomeLayoutStoreTest.Prefs { HomeSettings.startForTest(p); return p }

    /** the app-wide "Show in combat" / "Bar in Party mode" become every record's fight tabs once (nothing changes on screen) */
    @Test fun theStoreMigratesTheFightSettingsIntoTheTabsOnce() {
        val bar = HomeLayoutJson.json(HomeLayout(cast = HomeLayout.CastDock.ACROSS))
        val top = HomeLayoutJson.json(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = HomeLayout.FightBand.TOP))
        val none = HomeLayoutJson.json(HomeLayout())
        val held = """{"Pending":{"base":$none,"edit":$bar}}"""
        val p = fresh(HomeLayoutStoreTest.Prefs(hashMapOf(HomeLayoutStore.KEY_MIGRATED to true, HomeLayoutStore.KEY_DEFAULT to bar,
            "homeLayout.Korvash-Realm" to top, "homeLayout.Brannoc-Realm" to none, HomeLayoutStore.KEY_HELD to held,
            "castBarCombat" to "HIDE", "castBarPartyShow" to false)))
        HomeLayoutStore.character("Korvash-Realm")
        val k = HomeSettings.layout
        assertEquals("Top screen kept its bar (Always stay)", HomeLayout.CastDock.UNDER_CHAT, k.forTab(C).cast)
        assertEquals(HomeLayout.CastDock.OFF, k.forTab(P).cast)
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, k.forTab(O).cast)
        val d = HomeLayoutJson.parse(p.m[HomeLayoutStore.KEY_DEFAULT] as String)
        assertEquals(HomeLayout.CastDock.OFF, d.forTab(C).cast); assertEquals(HomeLayout.CastDock.OFF, d.forTab(P).cast)
        assertEquals("a Classic record's text unchanged", none, p.m["homeLayout.Brannoc-Realm"])
        val h = org.json.JSONObject(p.m[HomeLayoutStore.KEY_HELD] as String).getJSONObject("Pending")
        assertEquals(HomeLayout.CastDock.OFF, HomeLayoutJson.parse(h.getJSONObject("edit").toString()).forTab(C).cast)
        assertEquals(true, p.m[HomeLayoutStore.KEY_TABS_MIGRATED])
        // once: a fight tab set back on afterwards stays on
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        HomeLayoutStore.character("Brannoc-Realm"); HomeLayoutStore.character("Korvash-Realm")
        assertEquals(HomeLayout.CastDock.UNDER_CHAT, HomeSettings.layout.forTab(C).cast)
        // Show in combat on, Bar in Party mode on (the defaults' Party half): nothing to map
        val q = fresh(HomeLayoutStoreTest.Prefs(hashMapOf(HomeLayoutStore.KEY_MIGRATED to true, HomeLayoutStore.KEY_DEFAULT to bar,
            "castBarCombat" to "STAY")))
        assertEquals(HomeLayout(cast = HomeLayout.CastDock.ACROSS), HomeSettings.layout)
        assertEquals(bar, q.m[HomeLayoutStore.KEY_DEFAULT])
    }

    /** the Controller room's chips are mirrors of this character's tabs (decision 5 A): Off = that tab's bar Off, On = back where the Out
     *  of combat tab has it; the one-time question's Yes and Turn on: the bar on every tab that shows one, at the same place */
    @Test fun theChipsAreTheTabsMirrors() {
        fresh()
        HomeLayoutStore.character("Korvash-Realm")
        assertEquals(CastStripModel.Combat.HIDE, HomeSettings.castBarCombat)
        CastBarAsk.answer(true)                                             // Yes: under both columns, hidden in fights (the 11-13 ruling)
        val l = HomeSettings.layout
        assertEquals(HomeLayout.CastDock.ACROSS, l.forTab(O).cast); assertEquals(HomeLayout.CastDock.OFF, l.forTab(C).cast)
        assertEquals(HomeLayout.CastDock.ACROSS, l.forTab(P).cast)
        assertEquals(CastStripModel.Combat.HIDE, HomeSettings.castBarCombat); assertTrue(HomeSettings.castBarPartyShow)
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        assertEquals(HomeLayout.CastDock.ACROSS, HomeSettings.layout.forTab(C).cast)
        HomeSettings.castBarPartyShow = false
        assertEquals(HomeLayout.CastDock.OFF, HomeSettings.layout.forTab(P).cast); assertFalse(HomeSettings.castBarPartyShow)
        assertEquals("Out of combat untouched", HomeLayout.CastDock.ACROSS, HomeSettings.layout.forTab(O).cast)
        // turned off and on again: back where the Out of combat tab has its bar
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(cast = HomeLayout.CastDock.UNDER_MAP) } }
        HomeSettings.castBarPartyShow = false; HomeSettings.castBarPartyShow = true
        assertEquals(HomeLayout.CastDock.ACROSS, HomeSettings.layout.forTab(P).cast)
        // the bar Off (an older route): off on every tab
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        for (t in HomeTab.values()) assertEquals(HomeLayout.CastDock.OFF, HomeSettings.layout.forTab(t).cast)
        // the In fights facade (an older route) is In combat's Combat bars and changes the record as it always did: Party mode's, still
        // following it, follows (Top screen in Party mode's fights too: ruling C); one of its own is kept
        HomeSettings.fightBand = HomeLayout.FightBand.TOP
        assertEquals(HomeLayout.FightBand.TOP, HomeSettings.layout.forTab(C).fight)
        assertEquals(HomeLayout.FightBand.TOP, HomeSettings.layout.forTab(P).fight)
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(fight = HomeLayout.FightBand.GROW) } }
        HomeSettings.fightBand = HomeLayout.FightBand.SAME
        assertEquals(HomeLayout.FightBand.GROW, HomeSettings.layout.forTab(P).fight)
    }

    /** the store's lay-on of an edit is the edited tab's only: an Out of combat change never lands on a fight tab kept as a copy, a
     *  change made meanwhile on another tab is kept */
    @Test fun theLayOnIsPerTab() {
        val base = classic
        val edit = base.onTab(O) { it.swapped() }
        val now = base.onTab(C) { it.copy(cast = HomeLayout.CastDock.ACROSS) }          // meanwhile: In combat's bar (the Controller room)
        val r = HomeLayoutStore.layOn(now, base, edit)
        assertEquals(HomeLayout.Side.RIGHT, r.forTab(O).chatSide)
        assertEquals(HomeLayout.Side.LEFT, r.forTab(C).chatSide); assertEquals(HomeLayout.CastDock.ACROSS, r.forTab(C).cast)
        assertEquals(HomeLayout.Side.LEFT, r.forTab(P).chatSide)
        // and the other way: a fight tab's own change over an Out of combat change made meanwhile
        val e2 = base.onTab(P) { it.copy(fight = HomeLayout.FightBand.GROW) }
        val n2 = base.onTab(O) { it.copy(card = HomeLayout.CardDock.OFF) }
        val r2 = HomeLayoutStore.layOn(n2, base, e2)
        assertEquals(HomeLayout.CardDock.OFF, r2.forTab(O).card); assertEquals(HomeLayout.FightBand.GROW, r2.forTab(P).fight)
        assertEquals(HomeLayout.CardDock.ON_MAP, r2.forTab(P).card)
    }

    // ---------------------------------------------------------------- the edit model

    private fun stops(l: HomeLayout): Map<EditStop, HomeLayoutSolver.Box> {
        val b = S.places(l, HomeLayoutSolver.State(card = true, cardH = 118f)).boxes
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        out[EditStop.CHAT] = b.getValue(HomePart.CHAT); out[EditStop.MAP] = b.getValue(HomePart.MAP)
        b[HomePart.QUESTS]?.let { out[EditStop.QUEST] = it }
        b[HomePart.STRIP]?.let { out[EditStop.CAST] = it }
        return out
    }
    private fun HomeEditModel.press(vararg keys: String, v: HomeWidgetMenus.Values = HomeWidgetMenus.Values(ct = 0, cc = 0)): List<HomeEditModel.Fx> {
        val out = ArrayList<HomeEditModel.Fx>()
        for (k in keys) out += key(k, stops(page)) { s -> HomeWidgetMenus.rows(s, page, v, tab) }
        return out
    }

    /** LB / RB walk the tabs (no wrap); Party mode's only while it is allowed; the edit opens on what Home shows */
    @Test fun lbRbWalkTheTabs() {
        val m = HomeEditModel(classic, EditStop.CHAT)
        assertEquals(O, m.tab); assertFalse(m.fightPreview)
        assertEquals(emptyList<HomeEditModel.Fx>(), m.press("PADLSHOULDER"))
        assertEquals(listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Tab(C)), m.press("PADRSHOULDER"))
        assertEquals(C, m.tab); assertTrue("In combat shows the fight look", m.fightPreview)
        m.press("PADRSHOULDER"); assertEquals(P, m.tab); assertFalse("Party mode's own look, its Fight toggle off", m.fightPreview)
        assertEquals(emptyList<HomeEditModel.Fx>(), m.press("PADRSHOULDER")); assertEquals(P, m.tab)
        m.press("PADLSHOULDER", "PADLSHOULDER"); assertEquals(O, m.tab)
        // Party mode not allowed: two tabs; opened in Party mode: Party mode's tab
        val two = HomeEditModel(classic, EditStop.CHAT, HomeTab.PARTY, partyTabShown = false)
        assertEquals(O, two.tab); two.press("PADRSHOULDER", "PADRSHOULDER"); assertEquals(C, two.tab)
        assertEquals(P, HomeEditModel(classic, EditStop.CHAT, HomeTab.PARTY).tab)
        // Party mode switched off in QOL while on its tab: Out of combat
        val q = HomeEditModel(classic, EditStop.CHAT, HomeTab.PARTY)
        q.tabsAre(false); assertEquals(O, q.tab); assertEquals(listOf(O, C), q.tabs)
        // parked (a fight): no switch
        val pk = HomeEditModel(classic, EditStop.CHAT); pk.park()
        assertTrue(pk.tabTo(C).isEmpty()); assertEquals(O, pk.tab)
    }

    /** a switch drops a lifted widget where it is and closes a bubble / tray; the cursor stays on a widget the page has */
    @Test fun aSwitchDropsTheLiftAndClosesTheBubble() {
        val m = HomeEditModel(classic, EditStop.CAST)
        m.press("PAD1"); assertEquals(EditStop.CAST, m.lifted)
        m.press("PADDDOWN")                                                 // Under chat -> Across
        val fx = m.press("PADRSHOULDER")
        assertTrue(fx.contains(HomeEditModel.Fx.Lift)); assertTrue(fx.contains(HomeEditModel.Fx.Tab(C)))
        assertNull(m.lifted); assertEquals("dropped where it was: Out of combat's", HomeLayout.CastDock.ACROSS, m.draft.forTab(O).cast)
        assertEquals("In combat kept its own", HomeLayout.CastDock.UNDER_CHAT, m.page.cast)
        m.press("PAD3"); assertEquals(EditStop.CAST, m.menu)
        m.press("PADLSHOULDER"); assertNull(m.menu); assertEquals(O, m.tab)
        // a shared piece's cursor goes to the chat on a fight tab (they sit still there)
        val s = HomeEditModel(classic, EditStop.CHAT); s.startOn(EditStop.LOOK, false)
        s.tabTo(C); assertEquals(EditStop.CHAT, s.cursor)
        assertTrue("the Look chip sits still on a fight tab", s.lookFlip().isEmpty())
    }

    /** each tab's widgets move its own page only; Discard puts every tab back */
    @Test fun eachTabEditsItsOwnPageAndDiscardPutsAllBack() {
        val m = HomeEditModel(classic, EditStop.CHAT)
        m.press("PADBACK")                                                  // Select: Out of combat's swap
        m.tabTo(C)
        assertEquals(HomeLayout.Side.LEFT, m.page.chatSide)
        m.press("PAD4")                                                     // Y on the chat: In combat's width
        assertEquals(HomeLayout.Share.TWO_THIRDS, m.page.chatShare)
        assertEquals(HomeLayout.Share.HALF, m.draft.forTab(O).chatShare); assertEquals(HomeLayout.Side.RIGHT, m.draft.forTab(O).chatSide)
        m.tabTo(P)
        m.press("PADBACK")
        assertEquals(HomeLayout.Side.RIGHT, m.page.chatSide); assertEquals(HomeLayout.Share.HALF, m.page.chatShare)
        assertTrue(m.changed)
        val fx = m.discard()
        assertTrue(fx.contains(HomeEditModel.Fx.Discard))
        assertEquals(classic, m.draft)
        for (t in HomeTab.values()) assertEquals(HomeLayout.TabPlaces.of(classic), HomeLayout.TabPlaces.of(m.draft.forTab(t)))
    }

    /** the bar's menu per tab: no sizes anywhere; a fight tab's Slots (Grow / Same size), Y flips it; Out of combat has none */
    @Test fun theBarsMenuHasSlotsOnTheFightTabsOnly() {
        val v = HomeWidgetMenus.Values(ct = 1, cc = 1)
        val out = HomeWidgetMenus.rows(EditStop.CAST, classic, v, O)
        assertEquals(listOf(HomeEditModel.K_PLACE, HomeEditModel.K_CAST_EDGE, HomeEditModel.K_SLOTS), out.map { it.key })
        for (t in listOf(C, P)) {
            val r = HomeWidgetMenus.rows(EditStop.CAST, classic, v, t)
            assertEquals(t.name, listOf(HomeEditModel.K_PLACE, HomeEditModel.K_CAST_EDGE, HomeEditModel.K_GROW, HomeEditModel.K_SLOTS), r.map { it.key })
            assertEquals(listOf("Grow", "Same size"), r[2].words); assertEquals("Slots", r[2].label); assertEquals(1, r[2].picked)
        }
        assertTrue(HomeWidgetMenus.rows(EditStop.CAST, HomeLayout(), v, C).none { it.key == HomeEditModel.K_GROW })
        val m = HomeEditModel(classic, EditStop.CAST)
        assertTrue("Out of combat: Y does nothing", m.press("PAD4").isEmpty())
        m.tabTo(C)
        m.press("PAD4"); assertTrue(m.page.grow); assertFalse(m.draft.forTab(O).grow); assertFalse(m.draft.forTab(P).grow)
        m.press("PAD3", "PADDDOWN", "PADDDOWN", "PADDRIGHT")                // the menu's Slots: Same size
        assertFalse(m.page.grow)
    }

    /** the fight pencil's bubble ("Fight"): Combat bars Top screen | Bottom screen | No grow, Cast bar with Top screen, the swing timer -
     *  each fight tab's own; Top screen dimmed without the add-on's word */
    @Test fun theCombatBarsRowsAreEachFightTabsOwn() {
        val v = HomeWidgetMenus.Values(ct = 1, cc = 1)
        val rows = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, classic.forTab(C), v, C)
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.SWING), rows.map { it.key })
        assertEquals(listOf("Top screen", "Bottom screen", "No grow"), rows[0].words); assertEquals("Combat bars", rows[0].label)
        assertEquals("Grow = Bottom screen", 1, rows[0].picked)
        assertEquals(2, HomeWidgetMenus.rows(EditStop.FIGHT_PEN, classic.forTab(P), v, P)[0].picked)
        assertEquals("Fight", HomeWidgetMenus.title(EditStop.FIGHT_PEN))
        val top = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, classic.copy(fight = HomeLayout.FightBand.TOP), v, C)
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.FIGHT_CAST, BottomTiles.SWING), top.map { it.key })
        assertEquals(setOf(0), HomeWidgetMenus.rows(EditStop.FIGHT_PEN, classic, v.copy(ct = null), C)[0].off)
        // the model: a row is the tab's own (Discard puts it back), never a press of the app-wide setter
        val m = HomeEditModel(classic, EditStop.CHAT)
        m.tabTo(P)
        m.openMenu(EditStop.FIGHT_PEN)
        val fx = m.menuTouch(0, 1, HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.page, v, m.tab))
        assertTrue(fx.none { it is HomeEditModel.Fx.Press })
        assertEquals(HomeLayout.FightBand.GROW, m.page.fight); assertEquals("In combat's untouched", HomeLayout.FightBand.GROW, m.draft.forTab(C).fight)
        assertEquals(HomeLayout.FightBand.SAME, m.saved.forTab(P).fight)
        m.menuTouch(0, 0, HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.page, v, m.tab))
        assertEquals(HomeLayout.FightBand.TOP, m.page.fight)
        m.menuTouch(1, 1, HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.page, v, m.tab))      // Cast bar: Top screen
        assertEquals(HomeLayout.FightCast.TOP, m.page.fightCast); assertEquals(HomeLayout.FightCast.BOTTOM, m.draft.forTab(C).fightCast)
        // the party list's bubble: Grows and Size in fights, no Preview row (the ribbon's Fight toggle)
        assertEquals(listOf(HomeEditModel.K_PARTY_GROW, HomeEditModel.K_PARTY_SIZE), HomeWidgetMenus.rows(EditStop.PARTY_PEN, m.page, v, P).map { it.key })
    }

    /** the ribbon's Fight toggle is the Party mode tab's alone; In combat always shows the fight (a park ends it, the resume brings it) */
    @Test fun theFightToggleIsPartyModesAlone() {
        val m = HomeEditModel(classic, EditStop.CHAT)
        assertTrue(m.toggleFight().isEmpty()); assertFalse(m.fightPreview)
        m.tabTo(C); assertTrue(m.toggleFight().isEmpty()); assertTrue(m.fightPreview)
        m.park(); assertFalse(m.fightPreview); m.resume(); assertTrue("back after the fight", m.fightPreview)
        m.tabTo(P); assertFalse(m.fightPreview)
        assertEquals(listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Preview(true)), m.toggleFight()); assertTrue(m.fightPreview)
    }

    /** the clock's badges per tab: a fight tab hides one for itself (the draft: Discard puts it back); Out of combat's is Settings' own
     *  press (app-wide, a behaviour row) - a fight tab with none of its own shows Settings' badges */
    @Test fun theBadgesArePerTab() {
        val m = HomeEditModel(classic, EditStop.CHAT)
        m.bandIs(listOf("hearth", "gold", "bags"), listOf("ammo"))
        m.tabTo(C)
        assertEquals(listOf("gold", "hearth", "bags"), m.extrasOn)
        val fx = m.hideExtra("hearth")
        assertTrue(fx.none { it is HomeEditModel.Fx.Press })
        assertEquals(listOf("gold", "bags"), m.draft.badges(C)); assertNull(m.draft.badges(P))
        assertEquals(listOf("hearth", "ammo"), m.extrasOff)
        m.unhideExtra("ammo"); assertEquals(listOf("gold", "bags", "ammo"), m.draft.badges(C))
        m.tabTo(O)
        assertEquals(listOf("gold", "hearth", "bags"), m.extrasOn)
        val o = m.hideExtra("bags")
        assertEquals(listOf<HomeEditModel.Fx>(HomeEditModel.Fx.Press("bags", 1)), o)
        // (the review: "Fully separate" - Party mode showed Settings' badges; Out of combat's change leaves it the ones it showed)
        assertEquals("Party mode keeps what it showed", listOf("gold", "hearth", "bags"), m.draft.badges(P))
        assertEquals("In combat's own untouched", listOf("gold", "bags", "ammo"), m.draft.badges(C))
        m.discard()
        assertNull(m.draft.badges(C)); assertNull(m.draft.badges(P))
    }

    /** the review: a tab's own list keeps the extras edit mode does not offer now (the pet's face while no pet is out): a badge never
     *  lost because it could not be seen when another one was changed */
    @Test fun aTabsOwnBadgesKeepTheOnesNotOfferedNow() {
        val m = HomeEditModel(classic, EditStop.CHAT)
        m.bandIs(listOf("hearth", "gold", "bags"), listOf("ammo"), hiddenOn = listOf("pet"))
        m.tabTo(C)
        assertEquals(listOf("gold", "hearth", "bags"), m.extrasOn)
        m.hideExtra("hearth")
        assertEquals(listOf("gold", "bags", "pet"), m.draft.badges(C))
        m.unhideExtra("ammo")
        assertEquals("the pet's kept again", setOf("gold", "bags", "ammo", "pet"), m.draft.badges(C)!!.toSet())
        m.tabTo(O)
        m.hideExtra("gold")
        assertEquals("Party mode's copy: what it showed, the pet's face too", listOf("gold", "hearth", "bags", "pet"), m.draft.badges(P))
    }

    /** the review: the fight shows a fight tab's OWN list exactly as Edit Home shows it - not filtered by Settings' rows (they are the
     *  Out of combat tab's): a badge put back on In combat with Settings' row Off shows in the fight; none of its own = Settings' */
    @Test fun aFightTabsOwnBadgesAreWhatItsFightShows() {
        assertTrue(HomeLayout.badgeOn("ammo", listOf("gold", "ammo"), settingsOn = false))
        assertFalse(HomeLayout.badgeOn("hearth", listOf("gold", "ammo"), settingsOn = true))
        assertTrue(HomeLayout.badgeOn("hearth", null, settingsOn = true))
        assertFalse(HomeLayout.badgeOn("hearth", null, settingsOn = false))
        assertFalse("an empty list of its own: none", HomeLayout.badgeOn("gold", emptyList(), settingsOn = true))
    }

    /** the review: a bar laid only on a fight tab (the Out of combat tab without one) is a bar that is ON - the Controller room and the
     *  Spells bind box veil it ("Turn on") only when it is Off on every tab; Edit slots opens each tab's own bar */
    @Test fun aBarOnAFightTabAloneIsOn() {
        fresh()
        HomeLayoutStore.character("Korvash-Realm")
        assertFalse(HomeSettings.castBarOnAnyTab)
        HomeLayoutStore.update { it.onTab(C) { p -> p.copy(cast = HomeLayout.CastDock.UNDER_CHAT) } }
        assertEquals(CastStripModel.Side.OFF, HomeSettings.castBarSide)
        assertTrue("In combat's own bar", HomeSettings.castBarOnAnyTab)
        HomeLayoutStore.update { it.onTab(C) { p -> p.copy(cast = HomeLayout.CastDock.OFF) }.onTab(P) { p -> p.copy(cast = HomeLayout.CastDock.ACROSS) } }
        assertTrue("Party mode's own bar", HomeSettings.castBarOnAnyTab)
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        assertFalse(HomeSettings.castBarOnAnyTab)
        assertEquals(listOf("castbar", "cbfight", "pbar"), HomeTab.values().map { it.bar })
    }
}
