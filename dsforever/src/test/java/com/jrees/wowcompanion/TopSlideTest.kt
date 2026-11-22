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
}
