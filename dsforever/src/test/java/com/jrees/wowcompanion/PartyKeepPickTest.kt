package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "KEEP MY PICK" (the user's ruling on the 1530 audit, 2026-11-25: "Anything you set on the Party mode tab stays its own, even when it
 * happens to match In combat. Settings > Home changes In combat only."): the Party mode tab's Combat bars and Cast bar, once set there,
 * are Party mode's own - never folded into "follows In combat" because they came to equal In combat's (HomeLayout.canonical, the
 * record's JSON read), so Settings > Home's In fights / Cast bar in fights rows (In combat's) never carry them along. A Party mode tab
 * never set still follows In combat (and no other tab's or row's change turns that into a pick of its own). Pure (the store on test prefs).
 */
class PartyKeepPickTest {
    @After fun down() { HomeSettings.startForTest(null) }

    private val P = HomeTab.PARTY
    private val C = HomeTab.COMBAT
    private val TOP = HomeLayout.FightBand.TOP
    private val GROW = HomeLayout.FightBand.GROW
    private val SAME = HomeLayout.FightBand.SAME
    private val CTOP = HomeLayout.FightCast.TOP
    private val CBOT = HomeLayout.FightCast.BOTTOM

    private fun fresh() {
        HomeSettings.startForTest(HomeLayoutStoreTest.Prefs())
        HomeLayoutStore.character("Korvash-Realm")
    }
    private val l get() = HomeSettings.layout

    /** THE REPORTED REPRO: Party mode's Cast bar on Top screen while In combat's is Bottom screen; Settings > Home's Cast bar in fights
     *  toggled to Top screen and back - Party mode's stays Top screen (it was folded at the first toggle and followed the second) */
    @Test fun aPartyCastBarPickOutlivesSettingsGoingThereAndBack() {
        fresh()
        HomeSettings.fightBand = TOP                                    // (the Cast bar row is a Top-screen fight's)
        assertEquals(CBOT, HomeSettings.fightCast)
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(fightCast = CTOP) } }
        assertEquals(CTOP, l.forTab(P).fightCast); assertEquals(CBOT, l.forTab(C).fightCast)
        HomeSettings.fightCast = CTOP                                   // Settings > Home: In combat's, now equal to Party mode's
        assertEquals(CTOP, l.forTab(C).fightCast)
        assertEquals("equal to In combat's, still Party mode's own", CTOP, l.partyCast)
        HomeSettings.fightCast = CBOT                                   // ... and back
        assertEquals(CBOT, l.forTab(C).fightCast)
        assertEquals("Party mode keeps its Top screen", CTOP, l.forTab(P).fightCast)
        assertTrue(HomeLayoutJson.json(l).contains("\"p.fcast\":\"TOP\""))
    }

    /** the same for the Combat bars: Party mode's Top screen while In combat's is Bottom screen (Grow); Settings' In fights to Top
     *  screen and back to Bottom screen - Party mode's stays Top screen (folded, it would have followed back to No grow, R0) */
    @Test fun aPartyCombatBarsPickOutlivesSettingsGoingThereAndBack() {
        fresh()
        assertEquals(GROW, HomeSettings.fightBand)
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(fight = TOP) } }
        assertEquals(TOP, l.forTab(P).fight)
        HomeSettings.fightBand = TOP
        assertEquals(TOP, l.partyFight)
        HomeSettings.fightBand = GROW
        assertEquals(GROW, l.forTab(C).fight)
        assertEquals("Party mode keeps its Top screen", TOP, l.forTab(P).fight)
    }

    /** a Party mode tab never set follows In combat (Settings > Home's rows, as before the tabs: R0's No grow for Bottom screen), and
     *  nothing writes a pick of its own for it: no p.fight / p.fcast key */
    @Test fun aPartyTabNeverSetStillFollowsInCombat() {
        fresh()
        HomeSettings.fightBand = TOP
        assertEquals(TOP, l.forTab(P).fight)
        HomeSettings.fightCast = CTOP; assertEquals(CTOP, l.forTab(P).fightCast)
        HomeSettings.fightCast = CBOT; assertEquals(CBOT, l.forTab(P).fightCast)
        HomeSettings.fightBand = GROW; assertEquals(SAME, l.forTab(P).fight)
        HomeSettings.fightBand = TOP; assertEquals(TOP, l.forTab(P).fight)
        assertNull(l.partyFight); assertNull(l.partyCast)
        val t = HomeLayoutJson.json(l)
        assertFalse(t, t.contains("p.fight")); assertFalse(t, t.contains("p.fcast"))
    }

    /** the other roads to a tab's own values (another row on the Party mode tab, the bar everywhere, the tab's badges, the bar's
     *  Controller room mirrors, Reset to Classic) never turn "follows In combat" into a pick, and never drop one */
    @Test fun otherChangesNeitherMakeNorDropAPick() {
        val base = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = TOP)
        for (l0 in listOf(base.onTab(P) { it.swapped() }, base.castEverywhere(HomeLayout.CastDock.ACROSS), base.withBadges(P, listOf("gold")),
                base.onTab(P) { it.copy(party = HomeLayout.PartyDock.UNDER_MAP, grow = true) })) {
            assertNull(l0.partyFight); assertNull(l0.partyCast)
            assertEquals("still follows", CBOT, l0.copy(fightCast = CBOT).forTab(P).fightCast)
            assertEquals("still follows", CTOP, l0.copy(fightCast = CTOP).forTab(P).fightCast)
        }
        val mine = base.onTab(P) { it.copy(fightCast = CTOP) }.copy(fightCast = CTOP)          // a pick, then In combat's equal to it
        for (l0 in listOf(mine.onTab(P) { it.swapped() }, mine.castEverywhere(HomeLayout.CastDock.ACROSS), mine.withBadges(P, emptyList()),
                HomeLayoutJson.parse(HomeLayoutJson.json(mine)))) {
            assertEquals("kept", CTOP, l0.partyCast)
            assertEquals("kept", CTOP, l0.copy(fightCast = CBOT).forTab(P).fightCast)
        }
        assertNull("Reset Home to Classic: Party mode follows In combat again", mine.classic().partyCast)
        // the store's lay-on of an edit (its full form): a following Party mode tab stays following, a pick is laid on / kept
        val edited = base.onTab(HomeTab.OUT) { it.swapped() }
        val laid = HomeLayoutStore.layOn(base.copy(fightCast = CTOP), base, edited)
        assertNull("a lay-on never makes a pick", laid.partyCast); assertNull(laid.partyFight)
        assertEquals(CTOP, laid.forTab(P).fightCast)
        assertEquals("an edit's pick laid on", CTOP, HomeLayoutStore.layOn(base, base, base.onTab(P) { it.copy(fightCast = CTOP) }).partyCast)
        assertEquals("a pick made meanwhile kept", CTOP, HomeLayoutStore.layOn(mine, base, edited).partyCast)
        fresh()
        HomeSettings.fightBand = TOP
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(fightCast = CTOP) } }
        HomeSettings.fightCast = CTOP
        HomeSettings.castBarPartyShow = false; HomeSettings.castBarPartyShow = true
        HomeSettings.castBarCombat = CastStripModel.Combat.STAY
        HomeSettings.fightCast = CBOT
        assertEquals("the chips' writes keep it", CTOP, l.forTab(P).fightCast)
    }

    // ---------------------------------------------------------------- RULING B: Party mode's PLACES (the user's pick 2026-11-25,
    // "Keep my pick here too (Recommended)"): anything set on the Party mode tab stays its own even when it matches Out of combat - its
    // places that come to equal Out of combat's are no longer folded into "follows Out of combat" (HomeLayout.canonical, the JSON read,
    // the store's lay-on), and Settings' Out of combat bar changes never carry a Party mode place of its own along; a Party mode tab
    // never set still follows Out of combat as before

    private val UNDER_CHAT = HomeLayout.CastDock.UNDER_CHAT
    private val UNDER_MAP = HomeLayout.CastDock.UNDER_MAP
    private val ACROSS = HomeLayout.CastDock.ACROSS

    /** THE REPRO: the Party mode tab's bar moved Under map and back Under chat (= Out of combat's); Settings > Home's bar Across (Out of
     *  combat's) - Party mode's stays Under chat (folded, it followed Out of combat Across) */
    @Test fun aPartyPlaceSetBackToOutOfCombatsStaysItsOwn() {
        fresh()
        HomeSettings.castDock = UNDER_CHAT
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(cast = UNDER_MAP) } }
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(cast = UNDER_CHAT) } }
        assertEquals(UNDER_CHAT, l.forTab(P).cast)
        assertTrue("equal to Out of combat's, still Party mode's own", l.partyTab != null)
        HomeSettings.castDock = ACROSS                                  // Settings > Home: Out of combat's bar
        assertEquals(ACROSS, l.forTab(HomeTab.OUT).cast)
        assertEquals("Party mode keeps its Under chat", UNDER_CHAT, l.forTab(P).cast)
        // the same for the other places: the party list's and the quest card's dock
        fresh()
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(party = HomeLayout.PartyDock.UNDER_MAP, card = HomeLayout.CardDock.OFF) } }
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(party = HomeLayout.PartyDock.ABOVE_CHAT, card = HomeLayout.CardDock.ON_MAP) } }
        HomeSettings.questOverMap = false                               // Settings > Home: Out of combat's card Above chat
        assertEquals(HomeLayout.CardDock.ABOVE_CHAT, l.forTab(HomeTab.OUT).card)
        assertEquals("Party mode keeps its card on the map", HomeLayout.CardDock.ON_MAP, l.forTab(P).card)
        HomeLayoutStore.update { it.copy(party = HomeLayout.PartyDock.UNDER_CHAT) }
        assertEquals(HomeLayout.PartyDock.UNDER_CHAT, l.forTab(HomeTab.OUT).party)
        assertEquals("Party mode keeps its list above the chat", HomeLayout.PartyDock.ABOVE_CHAT, l.forTab(P).party)
    }

    /** the pick survives the record's JSON (its own, written whole: no key would differ from Out of combat's), the full form and the
     *  store's lay-on; a tab never set writes no "p." place key and stays following through all three */
    @Test fun aPartyPlacePickRoundTripsAndANeverSetOneStaysFollowing() {
        val base = HomeLayout(cast = UNDER_CHAT)
        val mine = base.onTab(P) { it.copy(cast = ACROSS) }.onTab(P) { it.copy(cast = UNDER_CHAT) }
        assertEquals(HomeLayout.TabPlaces.of(base), mine.partyTab); assertTrue(mine.partyPicked)
        for (r in listOf(HomeLayoutJson.parse(HomeLayoutJson.json(mine)), HomeLayoutJson.parse(HomeLayoutJson.json(mine, full = true)))) {
            assertEquals("kept", mine, r)
            assertEquals("still its own after Out of combat's bar moves", UNDER_CHAT, r.copy(cast = ACROSS).forTab(P).cast)
        }
        val t = HomeLayoutJson.json(base)
        assertFalse("a tab never set: no place key ($t)", t.contains("\"p."))
        assertNull("the full form (every key written): still following", HomeLayoutJson.parse(HomeLayoutJson.json(base, full = true)).partyTab)
        assertFalse(HomeLayoutJson.json(base, full = true).contains("p.pick"))
        // the lay-on: an edit of another tab never makes a Party mode pick; an edit's pick is laid on; a pick made meanwhile is kept
        val edited = base.copy(rest = HomeRest.ROW)
        assertNull("a lay-on never makes a pick", HomeLayoutStore.layOn(base, base, edited).partyTab)
        assertEquals("an edit's pick laid on", mine.partyTab, HomeLayoutStore.layOn(base, base, mine).partyTab)
        assertEquals("a pick made meanwhile kept", mine.partyTab, HomeLayoutStore.layOn(mine, base, edited).partyTab)
        assertEquals("following, then Out of combat's bar Across: follows", ACROSS,
            HomeLayoutStore.layOn(base, base, edited).copy(cast = ACROSS).forTab(P).cast)
    }

    /** the roads that write every tab (the bar everywhere, Settings > Bottom screen's Tap-to-cast bar, the one-time question's Yes, the
     *  Controller room's Turn on) and the others that write the Party mode tab's places back (another tab's change, its Combat bars /
     *  Cast bar, the Controller room's chips) never turn a following tab into a pick; Settings' bar to another place never carries a
     *  Party mode bar of its own along (Off is still Off on every tab); Reset Home to Classic: following again */
    @Test fun theOtherRoadsNeitherMakeNorCarryAPartyPlace() {
        val base = HomeLayout(cast = UNDER_CHAT)
        for (l0 in listOf(base.castEverywhere(ACROSS), base.onTab(P) { it.copy(fightCast = CTOP) }, base.onTab(P) { it.copy(fight = TOP) },
                base.onTab(HomeTab.COMBAT) { it.copy(cast = ACROSS) }, base.copy(card = HomeLayout.CardDock.OFF),
                HomeLayoutJson.parse(HomeLayoutJson.json(base)))) {
            assertNull("no pick made ($l0)", l0.partyTab)
        }
        fresh()
        HomeSettings.castDock = UNDER_CHAT
        HomeSettings.castBarSide = CastStripModel.Side.BOTH             // never set: follows (the bar on every tab that shows one)
        assertEquals(ACROSS, l.forTab(P).cast); assertNull(l.partyTab)
        HomeLayoutStore.update { it.onTab(P) { p -> p.copy(cast = UNDER_MAP) } }
        HomeSettings.castBarSide = CastStripModel.Side.LEFT             // Settings > Bottom screen: the bar under the chat
        assertEquals(UNDER_CHAT, l.forTab(HomeTab.OUT).cast)
        assertEquals("Party mode's own bar stays where it was put", UNDER_MAP, l.forTab(P).cast)
        HomeSettings.castBarPartyShow = false; HomeSettings.castBarPartyShow = true
        assertEquals("the chip's Off and On: back where Out of combat has it (the chip's own rule)", UNDER_CHAT, l.forTab(P).cast)
        HomeSettings.castBarSide = CastStripModel.Side.RIGHT
        assertEquals("... and kept there by Settings' next move", UNDER_CHAT, l.forTab(P).cast)
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        assertEquals("Off: off on every tab (as before)", HomeLayout.CastDock.OFF, l.forTab(P).cast)
        val pick = base.onTab(P) { it.copy(cast = ACROSS) }.onTab(P) { it.copy(cast = UNDER_CHAT) }
        assertNull("Reset Home to Classic: Party mode follows Out of combat again", pick.classic().partyTab)
    }

    /** Edit Home: another tab's change copies the following Party mode tab's places ("Fully separate") - put back, it follows again (the
     *  copy was never set on its tab: the session's opening record again, nothing changed); a change made ON the Party mode tab and put
     *  back stays Party mode's own (ruling B) */
    @Test fun editHomesCopyFollowsBackAPartyTabSetThereStaysItsOwn() {
        val base = HomeLayout(cast = UNDER_CHAT)
        val stops = mapOf(EditStop.CHAT to HomeLayoutSolver.Box(0f, 200f, 615f, 900f), EditStop.MAP to HomeLayoutSolver.Box(625f, 200f, 1240f, 900f))
        fun HomeEditModel.k(key: String) = key(key, stops) { s -> HomeWidgetMenus.rows(s, page, HomeWidgetMenus.Values(), tab) }
        val m = HomeEditModel(base, EditStop.CHAT)
        m.k("PADBACK")                                                  // Out of combat's swap: Party mode keeps the chat on the left
        assertEquals(HomeLayout.Side.LEFT, m.draft.forTab(P).chatSide); assertTrue(m.draft.partyTab != null)
        m.k("PADBACK")                                                  // ... swapped back
        assertNull("the copy put back: following again", m.draft.partyTab)
        assertFalse("nothing changed", m.changed)
        val n = HomeEditModel(base, EditStop.CHAT, HomeTab.PARTY)
        n.k("PADBACK"); n.k("PADBACK")                                  // the Party mode tab's own swap, and back
        assertEquals("set on its tab: its own, equal to Out of combat's", HomeLayout.TabPlaces.of(base), n.draft.partyTab)
        n.tabTo(HomeTab.OUT); n.k("PADBACK"); n.k("PADBACK")
        assertEquals("kept through Out of combat's change and back", HomeLayout.TabPlaces.of(base), n.draft.partyTab)
        assertEquals("Out of combat's bar moved later: Party mode's stays", UNDER_CHAT, n.draft.copy(cast = ACROSS).forTab(P).cast)
    }

    /** Edit Home's Party mode tab: a pick of the value it shows now (what it follows) is a pick too - Party mode's own from then on */
    @Test fun aPickOfTheFollowedValueOnThePartyTabIsItsOwn() {
        val v = HomeWidgetMenus.Values(ct = 1, cc = 1)
        val m = HomeEditModel(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = TOP), EditStop.CHAT)
        m.tabTo(P)
        m.openMenu(EditStop.FIGHT_PEN)
        val rows = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.page, v, m.tab)
        val cast = rows.indexOfFirst { it.key == BottomTiles.FIGHT_CAST }
        assertTrue(cast >= 0)
        assertEquals(CBOT, m.page.fightCast)
        m.menuTouch(cast, CBOT.ordinal, rows)                           // "Bottom screen", what it follows now
        assertEquals(CBOT, m.draft.partyCast)
        assertEquals("Settings' Top screen later: Party mode's Bottom screen stays", CBOT, m.draft.copy(fightCast = CTOP).forTab(P).fightCast)
        val bars = rows.indexOfFirst { it.key == BottomTiles.COMBAT }
        m.menuTouch(bars, HomeWidgetMenus.COMBAT_BARS.indexOf(TOP), HomeWidgetMenus.rows(EditStop.FIGHT_PEN, m.page, v, m.tab))
        assertEquals(TOP, m.draft.partyFight)
        assertEquals(TOP, m.draft.copy(fight = GROW).forTab(P).fight)
        // the In combat tab's own row is In combat's alone (no Party mode pick made by it)
        val n = HomeEditModel(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT, fight = TOP), EditStop.CHAT)
        n.tabTo(C); n.openMenu(EditStop.FIGHT_PEN)
        val r2 = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, n.page, v, n.tab)
        n.menuTouch(r2.indexOfFirst { it.key == BottomTiles.FIGHT_CAST }, CBOT.ordinal, r2)
        assertNull(n.draft.partyCast)
    }
}
