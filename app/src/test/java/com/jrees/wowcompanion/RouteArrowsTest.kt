package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * feat/route-arrows (the user, 2026-11-17: "on the mini map every 5 dots I want a blue arrow so that its consistent"): [RouteArrows]'
 * dash and arrow places, pure. Home's map draws the dots with [RouteArrows.DASH] at [RouteArrows.phase] and the arrows at
 * [RouteArrows.arrowDistances]: one arrow in place of every 5th dot, the 16 px rhythm unbroken, all moving 24 px a second.
 */
class RouteArrowsTest {
    @Test fun theDashIsFourDotsThenTheArrowsSlot() {
        val d = RouteArrows.DASH
        assertEquals("4 dots, 4 gaps", 8, d.size)
        assertEquals("one cycle = 5 slots of 16 px", 80f, d.sum(), 1e-4f)
        for (i in 0 until 4) assertEquals("a dot", RouteArrows.DOT, d[2 * i], 1e-6f)
        for (i in 0 until 3) assertEquals("16 px to the next dot", 16f, d[2 * i] + d[2 * i + 1], 1e-4f)
        assertEquals("the last gap spans the arrow's slot", 32f, d[6] + d[7], 1e-4f)
    }

    @Test fun anArrowEveryFifthDotTheRhythmUnbroken() {
        for (t in longArrayOf(0L, 417L, 1000L, 3333L, 86_400_000L * 30 + 123L)) {
            val len = 700f
            val dots = RouteArrows.dotDistances(len, t)
            val arrows = RouteArrows.arrowDistances(len, t)
            assertTrue("arrows along the path ($t)", arrows.size >= 8)
            for (i in 1 until arrows.size) assertEquals("arrows 80 px apart", 80f, arrows[i] - arrows[i - 1], 1e-3f)
            for (a in arrows) assertTrue("no dot under an arrow ($t, $a)", dots.none { abs(it - a) < 1f })
            val all = (dots.toList() + arrows.toList()).sorted()
            for (i in 1 until all.size) assertEquals("16 px between every piece ($t)", 16f, all[i] - all[i - 1], 1e-3f)
            // every 5th piece an arrow: four dots between two arrows
            for (i in 1 until arrows.size) assertEquals(4, dots.count { it > arrows[i - 1] && it < arrows[i] })
            assertTrue("all on the path", all.all { it in 0f..len })
        }
    }

    @Test fun theDrawsInPlaceWalkMatchesTheArrowPlaces() {
        // MapView walks firstArrow + k * PERIOD with no per-frame array: the same places as arrowDistances
        for (t in longArrayOf(0L, 417L, 3333L, 86_400_000L * 30 + 123L)) for (len in floatArrayOf(5f, 64f, 65f, 700f)) {
            val walk = ArrayList<Float>()
            var s = RouteArrows.firstArrow(t)
            while (s <= len) { walk.add(s); s += RouteArrows.PERIOD }
            val want = RouteArrows.arrowDistances(len, t)
            assertEquals("count ($t, $len)", want.size, walk.size)
            for (i in want.indices) assertEquals(want[i], walk[i], 1e-3f)
        }
    }

    @Test fun theArrowsMoveWithTheDotsTowardTheGoal() {
        val a0 = RouteArrows.arrowDistances(500f, 0L)
        val a1 = RouteArrows.arrowDistances(500f, 500L)
        assertEquals("12 px in half a second (24 px/s)", (a0[0] + 12f) % 80f, a1[0], 1e-3f)
        assertEquals("the dash phase runs against the path", -RouteArrows.travel(500L), RouteArrows.phase(500L), 0f)
        assertEquals("a loop: the same places one cycle (80 px / 24 px/s) later", a0[0], RouteArrows.arrowDistances(500f, 10_000L)[0], 1e-3f)
        // a long-running clock (a month of uptime) still exact: worked in double
        val big = 86_400_000L * 30
        assertEquals(((big / 1000.0 * 24.0) % 80.0).toFloat(), RouteArrows.travel(big), 1e-3f)
        assertEquals("no arrow on a path shorter than its first slot", 0, RouteArrows.arrowDistances(10f, 0L).size)
    }
}
