package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME PHASE 3, THE COMBAT BAR ON THE TOP SCREEN, the app's half (SPEC.txt sections 10-12 and 15; the user's rulings 2026-11-20:
 * D1 "In fights: Top screen" is OPT-IN, Grow stays the default, Same size stays): the setting (default, facade, migration, presets), the
 * bottom band's behaviour (Top screen lays every layout in a fight exactly as at rest, no grow, no B shrink in the fight, the 1.5 s "up"
 * cue), and the channel to the add-on (page-3 codes 22 / 23, Book b76, out of combat, re-sent until the padmap's `ct` says it; an older add-on is
 * never tapped). Pure.
 */
class TopCombatBarTest {
    @After fun down() { HomeSettings.startForTest(null) }

    private fun fresh(p: HomeLayoutStoreTest.Prefs = HomeLayoutStoreTest.Prefs()): HomeLayoutStoreTest.Prefs { HomeSettings.startForTest(p); return p }

    // ---------------------------------------------------------------- the setting

    @Test fun growStaysTheDefaultAndTopIsOptIn() {
        fresh()
        assertEquals("D1: Grow by default", HomeLayout.FightBand.GROW, HomeSettings.fightBand)
        assertTrue(HomeSettings.combatGrow)
        assertFalse(TopCombatBar.wanted(HomeSettings.layout))
        // the migration never picks Top screen: today's true / false = Grow / Same size
        for (grow in listOf(true, false)) {
            val l = HomeLayoutMigration.fromToday(HomeRest.DIAMONDS, grow, true, CastStripModel.Side.OFF)
            assertEquals(if (grow) HomeLayout.FightBand.GROW else HomeLayout.FightBand.SAME, l.fight)
        }
        // a store migrated from an older build's Same size: Same size, not Top
        fresh(HomeLayoutStoreTest.Prefs(hashMapOf("combatGrow" to false)))
        assertEquals(HomeLayout.FightBand.SAME, HomeSettings.fightBand)
        // every preset but Fighter keeps the player's fight mode (the player's own opt-in is the only way to Top)
        for (p in HomePresets.OFFERED) {
            val l = p.apply(HomeLayout())
            assertEquals(p.id, p.id == HomePresets.FIGHTER, l.fight == HomeLayout.FightBand.TOP)
        }
    }

    @Test fun theSettingIsAFacadeOnTheLayout() {
        val p = fresh()
        var heard = 0
        HomeSettings.onChange { heard++ }
        HomeSettings.fightBand = HomeLayout.FightBand.TOP
        assertEquals(HomeLayout.FightBand.TOP, HomeSettings.layout.fight)
        assertTrue(TopCombatBar.wanted(HomeSettings.layout))
        assertFalse("Top is not Grow", HomeSettings.combatGrow)
        assertEquals("the old key: an older build reads Same size", false, p.m["combatGrow"])
        assertTrue(heard >= 1)
        // the record keeps it per character (D5) and in JSON
        assertEquals(HomeSettings.layout, HomeLayoutJson.parse(HomeLayoutJson.json(HomeSettings.layout)))
        HomeLayoutStore.character("Alt-Realm")
        HomeLayoutStore.set(HomeSettings.layout.copy(fight = HomeLayout.FightBand.GROW))
        assertEquals(HomeLayout.FightBand.GROW, HomeSettings.fightBand)
        // the old Grow / Same size setter still works, and Same -> Top -> Grow round trips
        HomeSettings.combatGrow = false
        assertEquals(HomeLayout.FightBand.SAME, HomeSettings.fightBand)
        HomeSettings.fightBand = HomeLayout.FightBand.TOP
        HomeSettings.combatGrow = true
        assertEquals(HomeLayout.FightBand.GROW, HomeSettings.fightBand)
        assertEquals(true, p.m["combatGrow"])
    }

    @Test fun theFighterPresetIsOfferedAndNamesItself() {
        val f = HomePresets.byId(HomePresets.FIGHTER)!!
        assertTrue(f.available)
        assertTrue(f in HomePresets.OFFERED)
        val l = f.apply(HomeLayout())
        assertEquals(HomeLayout.FightBand.TOP, l.fight)
        assertEquals(HomePresets.FIGHTER, HomePresets.nameOf(l))
        assertEquals("its Split (phase 2: slots 1-4 under the left column, 5-8 under the right)", HomeLayout.CastDock.SPLIT,
            HomeLayoutRules.shown(l).cast)
    }

    // ---------------------------------------------------------------- the bottom band

    @Test fun topScreenLaysEveryLayoutAtItsRestBoxesInAFight() {
        var n = 0
        for (l in HomeLayoutRules.all().filter { it.fight == HomeLayout.FightBand.TOP }) {
            for (hidden in listOf(false, true)) for (card in listOf(false, true)) {
                val rest = HomeLayoutSolver.places(l, HomeLayoutSolver.State(card = card, cardH = 118f, castHidden = hidden))
                val fight = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true, card = card, cardH = 118f, castHidden = hidden))
                assertFalse(HomeLayoutSolver.big(l, HomeLayoutSolver.State(takeover = true)))
                assertEquals("$l: the band's bottom", rest.barsBottom, fight.barsBottom, 0f)
                assertEquals("$l: every box as at rest", rest.boxes, fight.boxes)
                assertTrue("$l: valid in the fight", HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), HomeLayoutSolver.places(l,
                    HomeLayoutSolver.State(takeover = true))).isEmpty())
                n++
            }
            // Grow (the same record otherwise) does grow: Top really is the rest's place
            val g = l.copy(fight = HomeLayout.FightBand.GROW)
            assertEquals(416f, HomeLayoutSolver.places(g, HomeLayoutSolver.State(takeover = true)).barsBottom, 0.01f)
        }
        assertEquals(540 * 4, n)
    }

    @Test fun bNeverShrinksTopScreenInTheFight() {
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
        assertFalse("in the fight: nothing grew, no shrink, no red reminder", TopCombatBar.bShrinks(top, fighting = true, ct = 1))
        assertTrue("an attack armed out of a fight: today's B (the faces given back)", TopCombatBar.bShrinks(top, fighting = false, ct = 1))
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)) for (ct in listOf(null, 0, 1)) {
            assertTrue(TopCombatBar.bShrinks(HomeLayout(fight = f), true, ct)); assertTrue(TopCombatBar.bShrinks(HomeLayout(fight = f), false, ct))
        }
        // the 1515 merge's review: no Wings on the top screen (no key, Later, an older add-on, not confirmed): the band grew, B shrinks it
        for (ct in listOf(null, 0)) assertTrue("ct=$ct", TopCombatBar.bShrinks(top, fighting = true, ct = ct))
    }

    @Test fun theUpCueAtTheFightsStart() {
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
        assertTrue(TopCombatBar.cueStarts(false, true, top, 1))
        assertFalse("not again while the fight lasts", TopCombatBar.cueStarts(true, true, top, 1))
        assertFalse("not at its end", TopCombatBar.cueStarts(true, false, top, 1))
        assertFalse("Grow: no cue (the band grows)", TopCombatBar.cueStarts(false, true, HomeLayout(), 1))
        assertFalse(TopCombatBar.cueStarts(false, true, HomeLayout(fight = HomeLayout.FightBand.SAME), 1))
        // the 1515 merge's review: the Wings not on (no key while asking / after Later: no ct; an older add-on; ct 0): no "up" cue
        assertFalse("no key", TopCombatBar.cueStarts(false, true, top, null))
        assertFalse("not confirmed", TopCombatBar.cueStarts(false, true, top, 0))
        // 1.5 s: 120 in, held, 200 out
        assertEquals(1_500L, TopCombatBar.CUE_MS)
        assertEquals(0f, TopCombatBar.cueAlpha(0), 0f)
        assertEquals(0.5f, TopCombatBar.cueAlpha(60), 0.001f)
        assertEquals(1f, TopCombatBar.cueAlpha(120), 0f)
        assertEquals(1f, TopCombatBar.cueAlpha(1_299), 0f)
        assertEquals(0.5f, TopCombatBar.cueAlpha(1_400), 0.001f)
        assertEquals(0f, TopCombatBar.cueAlpha(1_500), 0f)
        assertEquals(0f, TopCombatBar.cueAlpha(-5), 0f)
    }

    /** the 1515 merge's review (P5, "the pictures never lie"): Top screen is honoured only while the add-on's Wings are on (`ct` 1). With
     *  no key (the question asks, or "Later" kept the login keyless: who has no r, the padmap no ct) the band grows as Grow does */
    @Test fun topScreenWithoutTheWingsGrowsTheBand() {
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
        val fight = HomeLayoutSolver.State(takeover = true)
        for (ct in listOf(null, 0)) {
            val l = TopCombatBar.honoured(top, ct)
            assertEquals("ct=$ct: laid as Grow", HomeLayout.FightBand.GROW, l.fight)
            assertTrue("ct=$ct: the band grows", HomeLayoutSolver.big(l, fight))
            assertEquals(416f, HomeLayoutSolver.places(l, fight).barsBottom, 0.01f)
            assertFalse(TopCombatBar.live(top, ct))
        }
        assertEquals("the Wings on: Top screen kept", top, TopCombatBar.honoured(top, 1))
        assertFalse(HomeLayoutSolver.big(TopCombatBar.honoured(top, 1), fight))
        assertTrue(TopCombatBar.live(top, 1))
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)) for (ct in listOf(null, 0, 1))
            assertEquals("$f is never changed", f, TopCombatBar.honoured(HomeLayout(fight = f), ct).fight)
        // a keyless padmap (the add-on asking: no r, no ct) as the host reads it
        val asking = PadMapModel.parse(JSONObject("""{"type":"padmap","v":"m183","m":{"dpad":{"on":true,"want":true,"who":{"n":"Korvash","rs":"ask",
            "pk":[{"r":"Realm 7","n":4},{"r":"Realm 8","n":5}]}}}}"""))!!.dpad!!
        assertNull(asking.realm); assertNull(asking.ct)
        assertEquals(HomeLayout.FightBand.GROW, TopCombatBar.honoured(top, asking.ct).fight)
        // HomeView lays, cues and shrinks by the CURRENT padmap's flag
        val home = java.io.File(listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion"),
            java.io.File("companion/app/src/main/java/com/jrees/wowcompanion")).first { it.isDirectory }, "HomeView.kt").readText()
        assertTrue(home.contains("if (look == null) l = TopCombatBar.honoured(l, padMap?.dpad?.ct)"))
        assertTrue(home.contains("TopCombatBar.cueStarts(cueWas, on, HomeSettings.layout, padMap?.dpad?.ct)"))
        assertTrue(home.contains("TopCombatBar.bShrinks(HomeSettings.layout, grow.fighting(now), padMap?.dpad?.ct)"))
    }

    // ---------------------------------------------------------------- the channel

    @Test fun theFlagIsSentOutOfCombatUntilTheAddonSaysIt() {
        assertEquals(TopCombatBar.Act.Matched, TopCombatBar.decide(false, 0, false, null))
        assertEquals(TopCombatBar.Act.Matched, TopCombatBar.decide(true, 1, false, null))
        assertEquals(TopCombatBar.Act.Send(true), TopCombatBar.decide(true, 0, false, null))
        assertEquals(TopCombatBar.Act.Send(false), TopCombatBar.decide(false, 1, false, null))
        assertEquals("never in a fight (queued: the add-on switches by itself)", TopCombatBar.Act.Wait, TopCombatBar.decide(true, 0, true, null))
        assertEquals("just sent", TopCombatBar.Act.Wait, TopCombatBar.decide(true, 0, false, 1_000L))
        assertEquals("sent again", TopCombatBar.Act.Send(true), TopCombatBar.decide(true, 0, false, TopCombatBar.RESEND_MS))
        assertEquals("an older add-on (no ct): never tapped", TopCombatBar.Act.Wait, TopCombatBar.decide(true, null, false, null))
        // padtab 39, 38, 37 then 22 / 23 (Book b76)
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 23), TopCombatBar.cells(true))
        assertEquals(listOf("padtab" to 39, "padtab" to 38, "padtab" to 37, "padtab" to 22), TopCombatBar.cells(false))
        assertEquals(PadMapModel.P3_TOP_OFF..PadMapModel.P3_TOP_ON, PadMapModel.PAGE3_CODES["top combat bar"])
        val used = HashMap<Int, String>()
        for ((what, r) in PadMapModel.PAGE3_CODES) for (c in r) assertNull("code $c is both", used.put(c, what))
        // Book b76 handles exactly 22 / 23 (the add-on's own table)
        val book = java.io.File(addons(), "WowCompanionSpells/Book.lua").readText()
        assertTrue(book.contains("for k = 0, 1 do P3[22 + k] = function()") && book.contains("pm.combatTop, k == 1"))
    }

    @Test fun thePadmapCarriesTheFlag() {
        fun doc(dpad: String) = JSONObject("""{"type":"padmap","v":"m179","m":{"dpad":$dpad}}""")
        val on = PadMapModel.parse(doc("""{"on":true,"want":true,"d":{"1":"chat","2":"radial","3":"map","4":"home"},"m":0,"ct":1}"""))!!.dpad!!
        assertEquals(1, on.ct)
        assertEquals(0, PadMapModel.parse(doc("""{"on":true,"ct":0}"""))!!.dpad!!.ct)
        assertNull("an older add-on", PadMapModel.parse(doc("""{"on":true}"""))!!.dpad!!.ct)
        // the Settings band says so while Top is chosen and the add-on cannot do it
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
        assertEquals("Needs the newer Spells add-on", TopCombatBar.status(top, null, true))
        assertNull(TopCombatBar.status(top, 1, true)); assertNull(TopCombatBar.status(top, 0, true))
        assertNull("no padmap yet: nothing said", TopCombatBar.status(top, null, false))
        assertNull(TopCombatBar.status(HomeLayout(), null, true))
    }

    @Test fun theResetPlanHoldsTheFlag() {
        val k = ResetPlan.SV_KEYS.first { it.top == "ctop" }
        assertEquals("WowCompanionSpellsDB", k.variable); assertEquals(ResetPlan.Kind.LAYOUTS, k.kind); assertTrue(k.perChar)
        assertNull("a reset = off (no entry): the band grows", k.default)
        // the add-on's own Layouts reset clears it too (PadMap m179)
        val pm = java.io.File(addons(), "WowCompanionSpells/PadMap.lua").readText()
        assertTrue(pm.contains("layouts = { \"dpad\", \"rbm\", \"ctop\" }"))
    }

    @Test fun theWordsAreTranslated() {
        val words = listOf("In fights", "Top screen", "Grow", "Same size", "Combat bar", "Fighter", "Needs the newer Spells add-on",
            "In a fight the controller grows, keeps its size, or moves to the top screen.")
        val dir = listOf(java.io.File("../companion/app/src/main/assets/companion/i18n"), java.io.File("companion/app/src/main/assets/companion/i18n"))
            .first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val text = java.io.File(dir, "$l.json").readBytes().toString(Charsets.UTF_8)
            val plain = JSONObject(text).getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w))
            assertTrue("$l: CRLF lines", text.split("\n").dropLast(1).all { it.endsWith("\r") })
        }
    }

    private fun addons(): java.io.File = listOf(java.io.File("../companion/app/src/main/assets/companion/addons"),
        java.io.File("companion/app/src/main/assets/companion/addons")).first { it.isDirectory }
}
