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
        // of the six arrangements 1517 offered, only Fighter is in Top screen (the player's own opt-in is the only way to Top)
        for ((id, l) in HomeArrangements.on(HomeLayout()))
            assertEquals(id, id == HomeArrangements.FIGHTER, l.fight == HomeLayout.FightBand.TOP)
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

    /** 1518 (no presets): a 1517 record saved as Fighter reads as its fields - Top screen, the Split bar - and wants the top bar */
    @Test fun aFighterRecordKeepsItsTopScreenBar() {
        val l = HomeLayoutJson.parse(HomeLayoutJson.json(HomeArrangements.of(HomeArrangements.FIGHTER, HomeLayout())))
        assertEquals(HomeLayout.FightBand.TOP, l.fight)
        assertEquals(HomeArrangements.FIGHTER, l.name)
        assertTrue(TopCombatBar.wanted(l))
        assertEquals("its Split (phase 2: slots 1-4 under the left column, 5-8 under the right)", HomeLayout.CastDock.SPLIT,
            HomeLayoutRules.shown(l).cast)
    }

    // ---------------------------------------------------------------- the bottom band

    /** TopSlide (the user, 2026-11-22: "slides up to the top screen", the pick "Chat and map grow up"): in a fight every Top screen record
     *  lays the band ABOVE the page at its rest size and the columns grow up into the strip; every other box keeps its rest x range */
    @Test fun topScreenLaysEveryLayoutWithTheBandAwayInAFight() {
        var n = 0
        for (l in HomeLayoutRules.all().filter { it.fight == HomeLayout.FightBand.TOP }) {
            for (hidden in listOf(false, true)) for (card in listOf(false, true)) {
                val rest = HomeLayoutSolver.places(l, HomeLayoutSolver.State(card = card, cardH = 118f, castHidden = hidden))
                val fight = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true, card = card, cardH = 118f, castHidden = hidden))
                assertFalse(HomeLayoutSolver.big(l, HomeLayoutSolver.State(takeover = true)))
                assertTrue("$l: away", fight.away && !rest.away)
                assertEquals("$l: the band's bottom", 0f, fight.barsBottom, 0f)
                val rb = rest.boxes.getValue(HomePart.BARS); val fb = fight.boxes.getValue(HomePart.BARS)
                assertEquals("$l: the band its rest size", rb.h, fb.h, 0.001f)
                assertEquals("$l: above the page", -HomeLayoutSolver.AWAY_PAST, fb.b, 0f)
                // the columns: the same x ranges, grown up by the band's rest bottom
                for (p in listOf(HomePart.CHAT, HomePart.MAP)) {
                    val a = rest.boxes.getValue(p); val b = fight.boxes.getValue(p)
                    assertEquals("$l: $p's x", a.l, b.l, 0f); assertEquals(a.r, b.r, 0f)
                    assertTrue("$l: $p grown up", b.t <= a.t - rest.barsBottom + 0.01f)
                }
                assertTrue("$l: valid in the fight", HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), HomeLayoutSolver.places(l,
                    HomeLayoutSolver.State(takeover = true))).isEmpty())
                n++
            }
            // Grow (the same record otherwise) does grow
            val g = l.copy(fight = HomeLayout.FightBand.GROW)
            assertEquals(416f, HomeLayoutSolver.places(g, HomeLayoutSolver.State(takeover = true)).barsBottom, 0.01f)
        }
        assertEquals("3,024 Top-screen records (7 bar docks, Above chat / map too; x 4 party list docks after 1518) x 4", 3024 * 4, n)
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

    /** nbar11 / TopSlide (the user, 2026-11-22: "It should be a slick animation looking like it slides up to the top screen"): the old
     *  1.5 s "up" plate is gone - the slide is the cue */
    @Test fun theUpCueIsGoneTheSlideIsTheCue() {
        val src = java.io.File(listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion"),
            java.io.File("companion/app/src/main/java/com/jrees/wowcompanion")).first { it.isDirectory }, "TopCombatBar.kt").readText()
        assertFalse(src.contains("fun cueStarts")); assertFalse(src.contains("fun cueAlpha")); assertFalse(src.contains("CUE_MS"))
        assertTrue("the Book's codes in the note (b76: 22 off / 23 on)", src.contains("then 22 (off) / 23 (on) (Book b76"))
        assertEquals(listOf(PadMapModel.ROW_PADTAB to 39, PadMapModel.ROW_PADTAB to 38, PadMapModel.ROW_PADTAB to 37, PadMapModel.ROW_PADTAB to 23),
            TopCombatBar.cells(true))
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
        // the 1519 audit: the Fight preview (edit mode: topCheck holds the send until Done) keeps Top screen whenever the add-on has the
        // flag - `ct` 0 previews the Top look the first fight after Done lays; no flag (null) previews Grow
        assertEquals(top, TopCombatBar.previewed(top, 0)); assertEquals(top, TopCombatBar.previewed(top, 1))
        assertEquals(HomeLayout.FightBand.GROW, TopCombatBar.previewed(top, null).fight)
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)) for (ct in listOf(null, 0, 1))
            assertEquals("$f is never changed", f, TopCombatBar.previewed(HomeLayout(fight = f), ct).fight)
        // HomeView lays and shrinks by the CURRENT padmap's flag (no cue any more: the band slides)
        val home = java.io.File(listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion"),
            java.io.File("companion/app/src/main/java/com/jrees/wowcompanion")).first { it.isDirectory }, "HomeView.kt").readText()
        assertTrue(home.contains("if (look == null) l = if (editFightLook()) TopCombatBar.previewed(l, padMap?.dpad?.ct) else TopCombatBar.honoured(l, padMap?.dpad?.ct)"))
        assertFalse(home.contains("cueStarts")); assertFalse(home.contains("topCueAlpha"))
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
        val words = listOf("In fights", "Top screen", "Grow", "Same size", "Combat bar", "Needs the newer Spells add-on",
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

    /** the user's ruling C (11-22): Party mode keeps the Top screen in its FIGHT - only with the Wings live, Party mode on and the fight */
    @Test fun partyModeKeepsTheTopScreenInItsFight() {
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
        for (ct in listOf(null, 0, 1)) for (on in listOf(false, true)) for (fighting in listOf(false, true))
            for (f in HomeLayout.FightBand.values()) {
                val want = f == HomeLayout.FightBand.TOP && ct == 1 && on && fighting
                assertEquals("$f ct=$ct on=$on fighting=$fighting", want, TopCombatBar.partyTop(top.copy(fight = f), ct, on, fighting))
            }
        // the 1519 audit: Party mode's Top fight sends the band up too, so the tap-to-cast bar stays by the Top screen's rule there as
        // well (TopCombatBar.stripStays has no Party-mode exception any more); B is unchanged
        assertTrue(TopCombatBar.stripStays(top))
        assertFalse(TopCombatBar.bShrinks(top, fighting = true, ct = 1))
    }

    private fun addons(): java.io.File = listOf(java.io.File("../companion/app/src/main/assets/companion/addons"),
        java.io.File("companion/app/src/main/assets/companion/addons")).first { it.isDirectory }
}
