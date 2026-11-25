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
