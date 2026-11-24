package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CAST BAR IN A TOP-SCREEN FIGHT (the user's ruling 2026-11-23: "When the controller moves to the top, the cast bar should still show
 * across the top of the bottom screen. We can add a setting for if the combat bars move to the top screen to have the cast bar be on the
 * top or the bottom. If its on the top we should really just turn back on the cast bar for the top screen dynamically instead of trying to
 * remake ours and position it just above the health hud. Same thing with the swing timer"), the app's half. Pure.
 *  - the setting: per character on the record ("fcast", Bottom screen the default, a Classic record's text unchanged), its facade, the
 *    Settings line and Edit Home's fight bubble row offered with Top screen only, Reset Home to Classic back to Bottom screen;
 *  - THE SOLVER'S FIGHT STRIP over every record x the Top fight's states (the takeover, Party mode's fight with every party dock and size,
 *    a card, the buff list, the bar hidden, chat / map swapped, every share and bar dock): the strip across the very top (8, 8 .. w - 8,
 *    36), every other part a gap under it, no overlap (HomeLayoutRules.problems), the columns tiling; out of the fight it lies hidden over
 *    the band's own cast strip and nothing else moves; Top screen (the game's bar) and Grow / Same size have no strip and lay exactly as
 *    before;
 *  - the channel: page-3 codes 24 / 25 (Book b77), out of combat, re-sent until the padmap's `cc` says it; the bottom screen keeps the strip
 *    until the add-on says Top screen (TopCombatBar.honoured: never a cast bar on neither screen).
 */
class FightCastBarTest {
    private val S = HomeLayoutSolver
    @After fun down() { HomeSettings.startForTest(null) }

    private fun fresh(p: HomeLayoutStoreTest.Prefs = HomeLayoutStoreTest.Prefs()): HomeLayoutStoreTest.Prefs { HomeSettings.startForTest(p); return p }
    private val TOP = HomeLayout(fight = HomeLayout.FightBand.TOP)

    // ---------------------------------------------------------------- the setting

    @Test fun bottomScreenIsTheDefaultAndTheRecordKeepsIt() {
        fresh()
        assertEquals(HomeLayout.FightCast.BOTTOM, HomeLayout().fightCast)
        assertEquals(HomeLayout.FightCast.BOTTOM, HomeSettings.fightCast)
        // a Classic record's text is unchanged (no "fcast" unless Top screen)
        assertFalse(HomeLayoutJson.json(HomeLayout()).contains("fcast"))
        assertFalse(HomeLayoutJson.json(TOP).contains("fcast"))
        val t = TOP.copy(fightCast = HomeLayout.FightCast.TOP)
        assertTrue(HomeLayoutJson.json(t).contains("\"fcast\":\"TOP\""))
        assertEquals(t, HomeLayoutJson.parse(HomeLayoutJson.json(t)))
        // an older record (no fcast): Bottom screen; an unknown word: Bottom screen
        assertEquals(HomeLayout.FightCast.BOTTOM, HomeLayoutJson.parse(HomeLayoutJson.json(TOP)).fightCast)
        assertEquals(HomeLayout.FightCast.BOTTOM, HomeLayoutJson.parse(HomeLayoutJson.json(TOP).replace("}", ",\"fcast\":\"SIDE\"}")).fightCast)
        // the facade writes the per-character record; Reset Home to Classic puts Bottom screen back
        HomeSettings.fightBand = HomeLayout.FightBand.TOP
        HomeSettings.fightCast = HomeLayout.FightCast.TOP
        assertEquals(HomeLayout.FightCast.TOP, HomeSettings.layout.fightCast)
        assertEquals(HomeLayout.FightCast.BOTTOM, HomeSettings.layout.classic().fightCast)
        HomeLayoutStore.character("Alt-Realm")
        HomeLayoutStore.set(HomeSettings.layout.copy(fightCast = HomeLayout.FightCast.BOTTOM))
        assertEquals("another character: its own", HomeLayout.FightCast.BOTTOM, HomeSettings.fightCast)
    }

    @Test fun theSettingsLineAndTheFightBubbleOfferItWithTopScreenOnly() {
        fresh()
        val env = BottomTiles.Env(null, null)
        fun keys() = BottomTiles.lines(BottomTiles.COMBAT, env).map { it.key }
        assertEquals("Grow: In fights alone", listOf(BottomTiles.COMBAT), keys())
        HomeSettings.fightBand = HomeLayout.FightBand.TOP
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.FIGHT_CAST), keys())
        val line = BottomTiles.lines(BottomTiles.COMBAT, env)[1]
        assertEquals("Cast bar", line.label); assertEquals(listOf("Bottom screen", "Top screen"), line.words); assertEquals(0, line.picked)
        BottomTiles.press(BottomTiles.COMBAT, BottomTiles.FIGHT_CAST, 1, env) { }
        assertEquals(HomeLayout.FightCast.TOP, HomeSettings.fightCast)
        assertEquals(1, BottomTiles.lines(BottomTiles.COMBAT, env)[1].picked)
        BottomTiles.press(BottomTiles.COMBAT, BottomTiles.FIGHT_CAST, 0, env) { }
        assertEquals(HomeLayout.FightCast.BOTTOM, HomeSettings.fightCast)
        // Edit Home's fight pencil: In fights, then Cast bar (Top screen only), then the swing timer
        fun rows(v: HomeWidgetMenus.Values) = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, HomeLayout(), v).map { it.key }
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.SWING), rows(HomeWidgetMenus.Values(ct = 1)))
        val top = HomeWidgetMenus.Values(fightBand = HomeLayout.FightBand.TOP, ct = 1, cc = 0)
        assertEquals(listOf(BottomTiles.COMBAT, BottomTiles.FIGHT_CAST, BottomTiles.SWING), rows(top))
        val row = HomeWidgetMenus.rows(EditStop.FIGHT_PEN, HomeLayout(), top)[1]
        assertEquals(emptySet<Int>(), row.off)
        // an add-on without the flag (cc null): Top screen cannot be picked (the bar stays on the bottom screen)
        assertEquals(setOf(1), HomeWidgetMenus.rows(EditStop.FIGHT_PEN, HomeLayout(), top.copy(cc = null))[1].off)
    }

    // ---------------------------------------------------------------- the solver's fight strip

    private fun partyStates(): List<HomeLayoutSolver.State> {
        val out = ArrayList<HomeLayoutSolver.State>()
        val fight = HomeLayoutSolver.State(takeover = true)
        for (card in listOf(false, true)) for (aura in listOf(false, true)) for (hidden in listOf(false, true))
            out += fight.copy(card = card, cardH = if (card) 118.75f else 0f, auraOpen = aura, castHidden = hidden)
        // the takeover's party rows (Party mode off) and Party mode's own fight (ruling C: the band away, the rows fill the strip)
        for (n in listOf(1, 3, 5, 6)) for (pet in listOf(false, true)) {
            out += fight.copy(party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_OFF, n, pet))
            out += HomeLayoutSolver.State(party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, n, pet), partyOn = true, partyFight = true)
            out += HomeLayoutSolver.State(party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, n, pet), partyOn = true, partyFight = true,
                card = true, cardH = 118.75f)
        }
        return out
    }

    @Test fun theStripRunsAcrossTheTopAndEveryPartStaysUnderItInEveryTopFight() {
        val strip = HomeLayoutSolver.Box(8f, 8f, 1240f - 8f, 8f + HomeLayoutSolver.FIGHT_STRIP_H)
        assertEquals(28f, HomeLayoutSolver.FIGHT_STRIP_H, 0f)
        var n = 0
        val bad = ArrayList<String>()
        // every record with Top screen (swap x share x card x bar dock x size x rest x party dock) x the fight's states
        for (l in HomeLayoutRules.all().filter { it.fight == HomeLayout.FightBand.TOP }) for (s in partyStates()) {
            if (!S.away(l, s)) continue
            val r = S.places(l, s); n++
            val p = HomeLayoutRules.problems(l, s, r)
            if (p.isNotEmpty() && bad.size < 6) bad += "$l / $s: $p"
            if (!r.fightStrip && bad.size < 6) bad += "$l / $s: no strip"
            if (r.boxes[HomePart.FIGHT_CAST] != strip && bad.size < 6) bad += "$l / $s: strip ${r.boxes[HomePart.FIGHT_CAST]}"
            for ((part, b) in r.boxes) {
                if (part == HomePart.FIGHT_CAST || part == HomePart.BARS || part == HomePart.AURAS) continue
                if (r.castHidden && (part == HomePart.STRIP || part == HomePart.CAST2)) continue
                // (the party list's module box rises PartyModuleView.RISE over its well: its plates start at the column's top)
                val t = if (part == HomePart.PARTY) b.t + PartyModuleView.RISE else b.t
                if (t < strip.b + 8f - 0.01f && bad.size < 6) bad += "$l / $s: $part at $t, over the strip's foot ${strip.b} + 8"
            }
            // the same record with Top screen for the bar: today's page exactly, no strip
            val rt = S.places(l.copy(fightCast = HomeLayout.FightCast.TOP), s)
            if ((rt.fightStrip || HomePart.FIGHT_CAST in rt.boxes) && bad.size < 6) bad += "$l / $s: a strip with the game's bar"
        }
        assertTrue("checked $n: ${bad.joinToString("\n")}", bad.isEmpty())
        assertTrue("every Top record x the fights (${HomeLayoutRules.all().count { it.fight == HomeLayout.FightBand.TOP }} records)", n > 3024 * 20)
    }

    @Test fun theColumnsGiveTheStripItsRoomAndKeepTheirFeet() {
        val fight = HomeLayoutSolver.State(takeover = true)
        for (swap in listOf(false, true)) for (cast in HomeLayout.CastDock.values()) {
            val l = TOP.copy(chatSide = if (swap) HomeLayout.Side.RIGHT else HomeLayout.Side.LEFT, cast = cast)
            val a = S.places(l, fight).boxes
            val b = S.places(l.copy(fightCast = HomeLayout.FightCast.TOP), fight).boxes
            val dy = 8f + HomeLayoutSolver.FIGHT_STRIP_H + 8f - 8f
            for (p in listOf(HomePart.CHAT, HomePart.MAP)) {
                assertEquals("$l $p: the top moves down by the strip", b.getValue(p).t + dy, a.getValue(p).t, 0.01f)
                assertEquals("$l $p: the foot stays", b.getValue(p).b, a.getValue(p).b, 0.01f)
            }
            // a foot bar keeps its place; a bar above its column moves down with the column's top
            a[HomePart.STRIP]?.let { st -> val o = b.getValue(HomePart.STRIP)
                assertEquals("$l: the bar", if (cast.top) o.t + dy else o.t, st.t, 0.01f) }
        }
    }

    @Test fun outOfTheFightTheStripHidesOverTheBandsOwnLineAndNothingMoves() {
        val rest = HomeLayoutSolver.State()
        var n = 0
        for (l in HomeLayoutRules.all().filter { it.fight == HomeLayout.FightBand.TOP }) for (s in listOf(rest, rest.copy(card = true, cardH = 118.75f),
                rest.copy(party = HomeLayoutSolver.Party(PartyLayout.Mode.REST, 3, false), partyOn = true))) {
            val r = S.places(l, s); n++
            assertFalse(r.fightStrip)
            val st = r.boxes.getValue(HomePart.FIGHT_CAST)
            val bars = r.boxes.getValue(HomePart.BARS)
            assertEquals("$l: at the band's foot", bars.b, st.b, 0.01f)
            assertEquals("$l: the band's cast strip's height", CastBarPlace.REST_STRIP, st.h, 0.01f)
            assertTrue("$l / $s: valid", HomeLayoutRules.problems(l, s, r).isEmpty())
            // every other part exactly as with the game's bar (out of fights nothing changes)
            val o = S.places(l.copy(fightCast = HomeLayout.FightCast.TOP), s).boxes
            assertEquals("$l / $s", o, r.boxes - HomePart.FIGHT_CAST)
        }
        assertTrue(n > 1000)
    }

    @Test fun growAndSameSizeHaveNoStrip() {
        for (l in HomeLayoutRules.all().filter { it.fight != HomeLayout.FightBand.TOP }) for (fc in HomeLayout.FightCast.values())
            for (s in listOf(HomeLayoutSolver.State(), HomeLayoutSolver.State(takeover = true))) {
                val r = S.places(l.copy(fightCast = fc), s)
                assertFalse(S.hasFightStrip(l.copy(fightCast = fc)))
                assertNull("$l $fc $s", r.boxes[HomePart.FIGHT_CAST])
                assertEquals("$l $fc $s: as before", S.places(l, s).boxes, r.boxes)
            }
    }

    // ---------------------------------------------------------------- the channel

    @Test fun theChannelSendsCodes24And25UntilCcSaysIt() {
        assertEquals(listOf(PadMapModel.ROW_PADTAB to 39, PadMapModel.ROW_PADTAB to 38, PadMapModel.ROW_PADTAB to 37, PadMapModel.ROW_PADTAB to 25),
            TopCombatBar.castCells(true))
        assertEquals(PadMapModel.ROW_PADTAB to 24, TopCombatBar.castCells(false).last())
        assertEquals(24..25, PadMapModel.PAGE3_CODES["fight cast bar"])
        // apart from every other page-3 code
        val ranges = PadMapModel.PAGE3_CODES.values.toList()
        for (i in ranges.indices) for (j in i + 1 until ranges.size) assertTrue(ranges[i].intersect(ranges[j]).isEmpty())
        // wanted only with Top screen
        assertFalse(TopCombatBar.castWanted(HomeLayout(fightCast = HomeLayout.FightCast.TOP)))
        assertFalse(TopCombatBar.castWanted(TOP))
        assertTrue(TopCombatBar.castWanted(TOP.copy(fightCast = HomeLayout.FightCast.TOP)))
        // the same decision as the top flag's: no cc (an older add-on): wait; matched; a fight waits; re-sent after RESEND_MS
        assertEquals(TopCombatBar.Act.Wait, TopCombatBar.decide(true, null, false, null))
        assertEquals(TopCombatBar.Act.Matched, TopCombatBar.decide(true, 1, false, null))
        assertEquals(TopCombatBar.Act.Wait, TopCombatBar.decide(true, 0, true, null))
        assertEquals(TopCombatBar.Act.Send(true), TopCombatBar.decide(true, 0, false, null))
        assertEquals(TopCombatBar.Act.Wait, TopCombatBar.decide(true, 0, false, 100L))
        assertEquals(TopCombatBar.Act.Send(false), TopCombatBar.decide(false, 1, false, TopCombatBar.RESEND_MS))
        // the padmap's cc
        val d = PadMapModel.parse(org.json.JSONObject("""{"type":"padmap","m":{"dpad":{"on":true,"ct":1,"cc":1}}}"""))?.dpad
        assertEquals(1, d!!.cc); assertEquals(1, d.ct)
    }

    @Test fun theBottomScreenKeepsTheStripUntilTheAddOnSaysTopScreen() {
        val want = TOP.copy(fightCast = HomeLayout.FightCast.TOP)
        assertEquals("cc 1: the game's bar", HomeLayout.FightCast.TOP, TopCombatBar.honoured(want, 1, 1).fightCast)
        for (cc in listOf(null, 0)) assertEquals("cc $cc: the strip", HomeLayout.FightCast.BOTTOM, TopCombatBar.honoured(want, 1, cc).fightCast)
        assertTrue(S.hasFightStrip(TopCombatBar.honoured(want, 1, 0)))
        // the Wings not on (ct not 1): Grow, no strip either way
        assertEquals(HomeLayout.FightBand.GROW, TopCombatBar.honoured(want, 0, 1).fight)
        assertFalse(S.hasFightStrip(TopCombatBar.honoured(want, 0, 1)))
        // the Fight preview: Top screen whenever the add-on HAS the flag (cc not null; the first fight after Done)
        assertEquals(HomeLayout.FightCast.TOP, TopCombatBar.previewed(want, 0, 0).fightCast)
        assertEquals(HomeLayout.FightCast.BOTTOM, TopCombatBar.previewed(want, 0, null).fightCast)
        // the old two-argument calls lay Bottom screen as before (the default record)
        assertEquals(TOP, TopCombatBar.honoured(TOP, 1))
    }

    /**
     * feat/top-castbar (the user, 2026-11-24: "I only want to see it on the top screen when everything else is also on the top screen
     * during or engaging in combat. Not with stuff like dienchanting or opening shit, that can stay on the bottom screen"; then "I kind of
     * want the option for the cast bar and swing timer to appear on the bottom screen if everything else is on top"): which casts go where
     * - TopCombatBar.lineAt over the record (as honoured) x the takeover (away) x a slide. Both directions pinned: Bottom screen = the
     * strip carries the cast AND the swing in the takeover; Top screen = nothing of the line on the bottom screen in the takeover (the
     * add-on's own bar above the HUD has both); out of the takeover the band's own line whatever the choice; never both screens.
     */
    @Test fun whichCastsGoWhere() {
        val bottom = TopCombatBar.honoured(TOP, 1, 0)
        val top = TopCombatBar.honoured(TOP.copy(fightCast = HomeLayout.FightCast.TOP), 1, 1)
        assertEquals(HomeLayout.FightCast.TOP, top.fightCast)
        val L = TopCombatBar.Line.BAND; val S_ = TopCombatBar.Line.STRIP; val T = TopCombatBar.Line.TOP
        // out of the takeover (disenchanting, opening, the Hearthstone): the band's line on the bottom screen, either choice
        for (r in listOf(bottom, top)) assertEquals("${r.fightCast} out of the takeover: the band", L, TopCombatBar.lineAt(r, away = false, sliding = false))
        // the takeover: Bottom screen = the strip (cast + swing), Top screen = the top screen's bar (nothing here)
        assertEquals(S_, TopCombatBar.lineAt(bottom, away = true, sliding = false))
        assertEquals(T, TopCombatBar.lineAt(top, away = true, sliding = false))
        // the way up (away from the slide's first frame): Top screen leaves this screen at once - a cast that starts the takeover never
        // shows down here after it; the strip rides both slides
        assertEquals(T, TopCombatBar.lineAt(top, away = true, sliding = true))
        assertEquals(S_, TopCombatBar.lineAt(bottom, away = true, sliding = true))
        // the way down (B mid-cast, the fight's end mid-channel): the band brings the line back with it (Top) / the strip until it lands
        assertEquals(L, TopCombatBar.lineAt(top, away = false, sliding = true))
        assertEquals(S_, TopCombatBar.lineAt(bottom, away = false, sliding = true))
        // Top screen not confirmed by the add-on (cc not 1): the strip - the cast never left on neither screen
        for (cc in listOf(null, 0)) assertEquals(S_, TopCombatBar.lineAt(TopCombatBar.honoured(TOP.copy(fightCast = HomeLayout.FightCast.TOP), 1, cc),
            away = true, sliding = false))
        // Grow / Same size: never away - the band, whatever the cast bar choice says
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)) for (fc in HomeLayout.FightCast.values())
            assertEquals(L, TopCombatBar.lineAt(HomeLayout(fight = f, fightCast = fc), away = false, sliding = false))
        // the 1523 rule on the GAME's own swing timer is gone: Top screen confirmed stays Top screen (no swing-alone strip)
        assertTrue(!S.hasFightStrip(top))
    }

    /** the swing goes where the cast bar goes, by the app's own Swing timer: Top screen sends it to the add-on's top bar (PadMap m190
     *  `cs`, Book b78 codes 26 / 27); Bottom screen paints it here by the same setting (nothing to send) */
    @Test fun theSwingFollowsTheCastBar() {
        val top = TOP.copy(fightCast = HomeLayout.FightCast.TOP)
        assertEquals(true, TopCombatBar.swingWanted(top, true))
        assertEquals(false, TopCombatBar.swingWanted(top, false))
        assertNull("Bottom screen: nothing to send", TopCombatBar.swingWanted(TOP, false))
        assertNull("Grow: nothing to send", TopCombatBar.swingWanted(HomeLayout(fightCast = HomeLayout.FightCast.TOP), false))
        assertEquals(PadMapModel.ROW_PADTAB to 26, TopCombatBar.swingCells(false).last())
        assertEquals(PadMapModel.ROW_PADTAB to 27, TopCombatBar.swingCells(true).last())
        assertEquals(listOf(39, 38, 37), TopCombatBar.swingCells(true).dropLast(1).map { it.second })
        // the padmap's cs (none = an older add-on)
        val d = PadMapModel.parse(org.json.JSONObject("""{"type":"padmap","m":{"dpad":{"on":true,"ct":1,"cc":1,"cs":0}}}"""))?.dpad
        assertEquals(0, d!!.cs)
        assertNull(PadMapModel.parse(org.json.JSONObject("""{"type":"padmap","m":{"dpad":{"on":true,"ct":1}}}"""))?.dpad?.cs)
        // decided as `cc` is: matched, a fight waits, re-sent after RESEND_MS
        assertEquals(TopCombatBar.Act.Matched, TopCombatBar.decide(false, 0, inFight = false, sinceSendMs = null))
        assertEquals(TopCombatBar.Act.Send(false), TopCombatBar.decide(false, 1, inFight = false, sinceSendMs = null))
        assertEquals(TopCombatBar.Act.Wait, TopCombatBar.decide(false, 1, inFight = true, sinceSendMs = null))
    }
}
