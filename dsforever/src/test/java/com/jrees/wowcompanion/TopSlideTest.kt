package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN FIGHTS = TOP SCREEN, THE SLIDE ([TopSlide]; the user, 2026-11-22: "the bottom [band] does not transition out during combat like it
 * should so it shows in both places. It should be a slick animation looking like it slides up to the top screen"): the curves (up the
 * ease-in Motion.curveOut, down the ease-out Motion.curveEnter, one curve for every part so the columns ride the band's foot), the
 * durations, monotonic motion, a reversal at the midpoint continuing from where the parts are, and the solver's away box (the band above
 * the page, the columns grown up).
 */
class TopSlideTest {
    @Test fun theCurves() {
        assertTrue("up: an ease-in (behind the line at the middle)", TopSlide.partK(0.5f, up = true) < 0.5f)
        assertTrue("down: an ease-out (ahead of the line at the middle)", TopSlide.partK(0.5f, up = false) > 0.5f)
        assertTrue(TopSlide.UP.contentEquals(floatArrayOf(0.4f, 0f, 1f, 1f)))                 // Motion.curveOut
        assertTrue(TopSlide.DOWN.contentEquals(floatArrayOf(0f, 0f, 0.2f, 1f)))               // Motion.curveEnter, the add-on's NB.easeOut
        for (up in listOf(true, false)) { assertEquals(0f, TopSlide.partK(0f, up), 0f); assertEquals(1f, TopSlide.partK(1f, up), 0f) }
        // a known point of Motion.curve (0.4, 0, 0.2, 1): x = 0.5 -> y ~ 0.7756 (the Lua NB.ease's value, test_combat_bar_spot.py)
        assertEquals(0.7756f, TopSlide.cubic(0.5f, floatArrayOf(0.4f, 0f, 0.2f, 1f)), 0.001f)
    }

    @Test fun theDurations() {
        assertEquals(240L, Motion.TOP_SLIDE_UP)
        assertEquals(240L, Motion.TOP_SLIDE_DOWN)
        assertEquals(Motion.TOP_SLIDE_UP, TopSlide.durationMs(away = true))
        assertEquals(Motion.TOP_SLIDE_DOWN, TopSlide.durationMs(away = false))
        assertTrue(TopSlide.isSlide(false, true)); assertTrue(TopSlide.isSlide(true, false))
        assertFalse(TopSlide.isSlide(false, false)); assertFalse(TopSlide.isSlide(true, true))
    }

    @Test fun everyPartMovesOneWay() {
        for (up in listOf(true, false)) {
            var last = -1f
            for (i in 0..60) {
                val k = TopSlide.partK(i / 60f, up)
                assertTrue("monotonic ($up) at $i", k >= last - 1e-6f)
                last = k
            }
        }
    }

    private fun box(r: HomeLayoutSolver.Result, p: HomePart) = r.boxes.getValue(p).let { floatArrayOf(it.l, it.t, it.r, it.b) }

    @Test fun theColumnsRideTheBandsFootEveryFrame() {
        for (rest in HomeRest.values()) for (cast in HomeLayout.CastDock.values()) for (card in listOf(false, true)) {
            val l = HomeLayout(rest = rest, fight = HomeLayout.FightBand.TOP, cast = cast)
            val calm = HomeLayoutSolver.places(l, HomeLayoutSolver.State(card = card, cardH = 118f))
            val fight = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true, card = card, cardH = 118f))
            for (up in listOf(true, false)) for (i in 0..15) {
                val f = i / 15f
                val a = if (up) calm else fight; val b = if (up) fight else calm
                val band = TopSlide.frame(box(a, HomePart.BARS), box(b, HomePart.BARS), f, up)
                for (p in listOf(HomePart.CHAT, HomePart.MAP, HomePart.QUESTS)) {
                    if (p !in a.boxes || p !in b.boxes) continue
                    val c = TopSlide.frame(box(a, p), box(b, p), f, up)
                    assertTrue("$l $p up=$up f=$f: under the band's foot (${c[1]} vs ${band[3]})", c[1] >= band[3] + 8f - 0.01f)
                }
            }
        }
    }

    @Test fun theSolverLaysTheBandAwayAndTheColumnsGrowUp() {
        for (rest in HomeRest.values()) {
            val l = HomeLayout(rest = rest, fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.UNDER_CHAT)
            val calm = HomeLayoutSolver.places(l, HomeLayoutSolver.State())
            val fight = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true))
            val a = calm.boxes.getValue(HomePart.BARS); val b = fight.boxes.getValue(HomePart.BARS)
            assertFalse(calm.away); assertTrue("$rest: away in the fight", fight.away)
            assertEquals("$rest: the band's bottom 16 over the frame's top", -16f, b.b, 0f)
            assertEquals("$rest: the same size (no re-measure)", a.h, b.h, 0.001f); assertEquals(a.w, b.w, 0f)
            assertEquals("$rest: the chat's column from the top gap", 8f, fight.boxes.getValue(HomePart.CHAT).t, 0f)
            assertEquals("$rest: the map's", 8f, fight.boxes.getValue(HomePart.MAP).t, 0f)
            assertEquals(0f, fight.barsBottom, 0f)
            assertTrue(HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), fight).isEmpty())
        }
        // Party mode: no takeover in the solver's sense (R0) - the band stays; Grow / Same size unchanged
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
        assertFalse(HomeLayoutSolver.places(top, HomeLayoutSolver.State(takeover = true, partyOn = true)).away)
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME))
            assertFalse(HomeLayoutSolver.places(HomeLayout(fight = f), HomeLayoutSolver.State(takeover = true)).away)
    }

    /** a reversal at the midpoint: the new motion starts from the parts' boxes NOW (HomeView.apply: from = now), so nothing jumps */
    @Test fun aReversalAtTheMidpointIsContinuous() {
        val l = HomeLayout(fight = HomeLayout.FightBand.TOP)
        val rest = HomeLayoutSolver.places(l, HomeLayoutSolver.State())
        val away = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true))
        for (p in listOf(HomePart.BARS, HomePart.CHAT, HomePart.MAP)) {
            val mid = TopSlide.frame(box(rest, p), box(away, p), 0.5f, up = true)
            val back0 = TopSlide.frame(mid, box(rest, p), 0f, up = false)
            val back1 = TopSlide.frame(mid, box(rest, p), 1f / 15f, up = false)
            for (i in 0..3) assertEquals("$p: no jump at the reversal", mid[i], back0[i], 0f)
            assertTrue("$p: heading back", kotlin.math.abs(back1[1] - box(rest, p)[1]) <= kotlin.math.abs(mid[1] - box(rest, p)[1]) + 1e-4f)
        }
    }

    /** the 11-22 review: a re-lay inside the slide goes ON with it - the time left, the same path - and only then (not a new slide, not a
     *  module, not the other way, not after it landed) */
    @Test fun aReLayMidSlideGoesOnWithIt() {
        assertEquals(-1f, TopSlide.resumeAt(slide = true, wasAway = false, isAway = true, moduleInvolved = false, cutAt = 0.3f, cutUp = true), 0f)
        assertEquals(-1f, TopSlide.resumeAt(false, true, true, moduleInvolved = true, cutAt = 0.3f, cutUp = true), 0f)
        assertEquals(-1f, TopSlide.resumeAt(false, true, true, false, cutAt = -1f, cutUp = true), 0f)
        assertEquals("the cut one went the other way", -1f, TopSlide.resumeAt(false, true, true, false, cutAt = 0.3f, cutUp = false), 0f)
        assertEquals("landed", -1f, TopSlide.resumeAt(false, true, true, false, cutAt = 1f, cutUp = true), 0f)
        assertEquals("cut in its first frame: a whole slide still", 0f, TopSlide.resumeAt(false, true, true, false, cutAt = 0f, cutUp = true), 0f)
        assertEquals(0.4f, TopSlide.resumeAt(false, true, true, false, cutAt = 0.4f, cutUp = true), 0f)
        assertEquals(0.25f, TopSlide.resumeAt(false, false, false, false, cutAt = 0.25f, cutUp = false), 0f)
        // the time left
        assertEquals(Motion.TOP_SLIDE_UP, TopSlide.restMs(true, 0f))
        assertEquals(Math.round(Motion.TOP_SLIDE_UP * 0.6), TopSlide.restMs(true, 0.4f))
        assertEquals(1L, TopSlide.restMs(false, 1f))
        // the same path: a part whose box did not change, re-started from where it stood at the cut, follows the uncut slide exactly
        for (up in listOf(true, false)) for (cut in listOf(0f, 0.2f, 0.5f, 0.8f)) {
            val from = floatArrayOf(0f, 200f, 1240f, 416f); val to = floatArrayOf(0f, -232f, 1240f, -16f)
            val at = TopSlide.frame(from, to, cut, up)
            for (j in 0..20) {
                val u = j / 20f
                val q = TopSlide.resumeK(u, cut, up)
                val whole = TopSlide.frame(from, to, TopSlide.overall(u, cut), up)
                for (i in 0..3) assertEquals("up=$up cut=$cut u=$u", whole[i], at[i] + (to[i] - at[i]) * q, 0.05f)
            }
            assertEquals(0f, TopSlide.resumeK(0f, cut, up), 1e-5f)
            assertEquals(1f, TopSlide.resumeK(1f, cut, up), 1e-5f)
        }
    }

    /**
     * THE WAY DOWN WAITS FOR THE WINGS (the 11-22 check sheet: at the fight's end + 48 ms the band was already coming down on its
     * fast-start curve while the Wings were still leaving on their slow-start one - both showed). The hold covers the Wings mostly off
     * whatever the channel's latency (the app always hears the end after the Wings start), and nothing waits on the way up or on a
     * reversal.
     */
    @Test fun theWayDownWaitsForTheWings() {
        assertEquals(200L, TopSlide.WINGS_OUT_MS)                                  // ActionBar NB.TOP.OUT_S
        assertTrue(TopSlide.WINGS_OUT.contentEquals(floatArrayOf(0.4f, 0f, 1f, 1f))) // NB.easeIn
        assertEquals(0f, TopSlide.wingsOff(0L), 0f); assertEquals(1f, TopSlide.wingsOff(TopSlide.WINGS_OUT_MS), 0f)
        assertTrue("the Wings mostly off at the hold's own part", TopSlide.wingsOff(TopSlide.WINGS_MOSTLY_OFF_MS) >= TopSlide.WINGS_MOSTLY_OFF)
        assertTrue("(the first such ms)", TopSlide.wingsOff(TopSlide.WINGS_MOSTLY_OFF_MS - 1) < TopSlide.WINGS_MOSTLY_OFF)
        assertEquals(TopSlide.WINGS_MOSTLY_OFF_MS + TopSlide.LATENCY_ALLOWANCE_MS, TopSlide.DOWN_HOLD_MS)
        assertTrue("about 0.2 s (${TopSlide.DOWN_HOLD_MS})", TopSlide.DOWN_HOLD_MS in 150L..260L)
        // the fight's end on the add-on's clock (the Wings start down at 0); the app hears it [lat] ms later and holds: the band's first
        // frame on the page (its bottom past the top edge: any progress of the way down) never comes before the Wings are mostly off.
        // (The old motion had no hold: the band was on the page from the app's first frame, the Wings barely moved: both showed.)
        for (lat in listOf(0L, 16L, 40L, 90L)) for (t in 0L..600L) {
            val slideT = t - lat - TopSlide.DOWN_HOLD_MS                              // the band's own slide time
            if (slideT <= 0L) continue
            val k = TopSlide.partK(slideT / Motion.TOP_SLIDE_DOWN.toFloat(), up = false)
            if (k <= 0f) continue
            assertTrue("lat=$lat t=$t: the band in (k=$k) only once the Wings are mostly off (${TopSlide.wingsOff(minOf(t, 200L))})",
                TopSlide.wingsOff(minOf(t, TopSlide.WINGS_OUT_MS)) >= TopSlide.WINGS_MOSTLY_OFF)
        }
        // who holds
        assertEquals("a fresh way down from the band landed away", TopSlide.DOWN_HOLD_MS,
            TopSlide.holdMs(slide = true, isAway = false, bandOff = true, cutUp = false, cutHold = 0L, resumeAt = -1f))
        assertEquals("a reversal of an up-slide of no known age turns back at once", 0L,
            TopSlide.holdMs(slide = true, isAway = false, bandOff = false, cutUp = true, cutHold = 0L, resumeAt = -1f))
        assertEquals("a reversal: an up-slide cut on its way waits as long as the Wings need", TopSlide.reversalHoldMs(230L),
            TopSlide.holdMs(slide = true, isAway = false, bandOff = false, cutUp = true, cutHold = 0L, resumeAt = -1f, upMs = 230L))
        assertEquals("a down-slide reversed (a glide's cut, not the way up): none", 0L,
            TopSlide.holdMs(slide = true, isAway = false, bandOff = false, cutUp = false, cutHold = 0L, resumeAt = -1f, upMs = 230L))
        assertEquals("the way up never holds", 0L, TopSlide.holdMs(true, isAway = true, bandOff = false, cutUp = false, cutHold = 0L, resumeAt = -1f))
        assertEquals("a fight back inside the hold: up at once (nothing moved)", 0L,
            TopSlide.holdMs(true, isAway = true, bandOff = true, cutUp = false, cutHold = 120L, resumeAt = -1f))
        assertEquals("a re-lay inside the hold waits what was left", 120L,
            TopSlide.holdMs(false, isAway = false, bandOff = true, cutUp = false, cutHold = 120L, resumeAt = 0f))
        assertEquals("a re-lay past the hold: none", 0L, TopSlide.holdMs(false, false, bandOff = false, cutUp = false, cutHold = 0L, resumeAt = 0.3f))
        assertEquals("a re-lay of an up-slide: none", 0L, TopSlide.holdMs(false, true, bandOff = false, cutUp = true, cutHold = 50L, resumeAt = 0.3f))
        assertEquals("not a slide's continuation: none", 0L, TopSlide.holdMs(false, false, bandOff = true, cutUp = false, cutHold = 50L, resumeAt = -1f))
        assertTrue(TopSlide.bandOff(-16f)); assertTrue(TopSlide.bandOff(-15.6f)); assertFalse(TopSlide.bandOff(-10f)); assertFalse(TopSlide.bandOff(null))
        // the slide's own time after the hold
        assertEquals(0f, TopSlide.afterHold(100f, 199L, 240L), 0f)
        assertEquals(0f, TopSlide.afterHold(199f, 199L, 240L), 0f)
        assertEquals(0.5f, TopSlide.afterHold(319f, 199L, 240L), 1e-5f)
        assertEquals(1f, TopSlide.afterHold(500f, 199L, 240L), 0f)
        assertEquals(0.5f, TopSlide.afterHold(120f, 0L, 240L), 1e-5f)
    }

    /**
     * A REVERSAL WAITS AS LONG AS THE WINGS NEED (the 11-22 review: the fight's end 200-230 ms into the up-slide - the band ~93 % off the
     * page, the Wings ~93 % in - turned the band back down at once; at +48 ms it was half back while the Wings still showed 85 %). The
     * Wings' way up is the add-on's (HOLD_IN 60 ms, IN_S 0.26 s on NB.easeOut); reversed, they leave from where they are on the way
     * down's curve. The band holds where it stands until they are mostly off, whatever the channel's delay on the fight's end.
     */
    @Test fun aLateReversalWaitsForTheWings() {
        assertEquals(60L, TopSlide.WINGS_HOLD_IN_MS); assertEquals(260L, TopSlide.WINGS_IN_MS)       // ActionBar NB.TOP.HOLD_IN / IN_S
        assertTrue(TopSlide.WINGS_IN.contentEquals(floatArrayOf(0f, 0f, 0.2f, 1f)))                  // NB.easeOut
        assertEquals(0f, TopSlide.wingsIn(60L), 0f); assertEquals(1f, TopSlide.wingsIn(320L), 0f)
        assertEquals("the review's figure: 93 % in at 230 ms", 0.93f, TopSlide.wingsIn(230L), 0.01f)
        assertEquals(0L, TopSlide.wingsOutAt(1f)); assertEquals(TopSlide.WINGS_OUT_MS, TopSlide.wingsOutAt(0f))
        // the hold: none before the Wings move or for no age; the whole one when they were all in; never more; never a step
        assertEquals(0L, TopSlide.reversalHoldMs(-1L))
        assertEquals("the Wings not started: at once", 0L, TopSlide.reversalHoldMs(50L))
        assertEquals("all in: the whole hold", TopSlide.DOWN_HOLD_MS, TopSlide.reversalHoldMs(400L))
        var was = 0L
        for (up in 0L..400L) {
            val h = TopSlide.reversalHoldMs(up)
            assertTrue("up=$up: never less than an earlier cut's ($h < $was)", h >= was)
            assertTrue("up=$up: no step ($was -> $h)", h - was <= 8L)
            assertTrue(h in 0L..TopSlide.DOWN_HOLD_MS)
            was = h
        }
        assertTrue("late (230 ms): most of the hold (${TopSlide.reversalHoldMs(230L)})", TopSlide.reversalHoldMs(230L) >= 140L)
        // the property: cut at [up] on the add-on's clock (the Wings reverse at 0 from wingsIn(up)); the app hears it [lat] ms later,
        // holds, then slides: the band's first move onto the page never comes before the Wings are mostly off
        for (up in 0L..239L step 3) for (lat in listOf(0L, 16L, 40L, 90L)) {
            val e0 = TopSlide.wingsOutAt(TopSlide.wingsIn(up))
            val hold = TopSlide.reversalHoldMs(up)
            for (t in 0L..600L) {
                val slideT = t - lat - hold
                if (slideT <= 0L) continue
                val shown = 1f - TopSlide.wingsOff(minOf(e0 + t, TopSlide.WINGS_OUT_MS))
                assertTrue("up=$up lat=$lat t=$t: the band moved while the Wings showed $shown", shown <= 1f - TopSlide.WINGS_MOSTLY_OFF + 0.005f)
            }
        }
    }

    /**
     * THE TAP-TO-CAST BAR STAYS IN A TOP-SCREEN FIGHT (the 11-22 shots: Show in combat Off, the default, slid it away under the room
     * bar while the band left too - the bottom screen had no fight control at all): Top screen with Party mode off keeps it (STAY),
     * Grow / Same size and Party mode keep the player's setting. The solver then lays it on the page in the fight, in its dock.
     */
    @Test fun theTapToCastBarStaysInATopFight() {
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.UNDER_CHAT)
        assertTrue(TopCombatBar.stripStays(top, partyOn = false))
        assertFalse("Party mode: no Top-screen takeover, the setting rules", TopCombatBar.stripStays(top, partyOn = true))
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)) assertFalse(TopCombatBar.stripStays(top.copy(fight = f), false))
        assertEquals(CastStripModel.Combat.STAY, TopCombatBar.stripCombat(top, false, CastStripModel.Combat.HIDE))
        assertEquals(CastStripModel.Combat.HIDE, TopCombatBar.stripCombat(top, true, CastStripModel.Combat.HIDE))
        assertEquals(CastStripModel.Combat.HIDE, TopCombatBar.stripCombat(top.copy(fight = HomeLayout.FightBand.GROW), false, CastStripModel.Combat.HIDE))
        assertEquals(CastStripModel.Combat.STAY, TopCombatBar.stripCombat(top.copy(fight = HomeLayout.FightBand.GROW), false, CastStripModel.Combat.STAY))
        // the fight's page with the bar kept (castHidden false): every dock on the page, under its column(s), nothing below the frame
        val st = HomeLayoutSolver.State()
        for (cast in HomeLayout.CastDock.values()) {
            if (cast == HomeLayout.CastDock.OFF) continue
            val l = top.copy(cast = cast)
            val hidden = CastStripModel.hiddenIn(TopCombatBar.stripCombat(l, false, CastStripModel.Combat.HIDE), fighting = true)
            assertFalse("$cast: not hidden in the Top fight", hidden)
            val r = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true, castHidden = hidden))
            assertTrue(r.away); assertFalse(r.castHidden)
            val b = r.boxes.getValue(HomePart.STRIP)
            assertTrue("$cast: on the page (${b.t}..${b.b} of ${st.h})", b.t >= 0f && b.b <= st.h + 0.01f)
            assertTrue("$cast: ${HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), r)}",
                HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), r).isEmpty())
            if (cast.top) assertEquals("$cast: at the top of its grown column (the top gap)", 8f, b.t, 0.01f)
        }
    }
}
