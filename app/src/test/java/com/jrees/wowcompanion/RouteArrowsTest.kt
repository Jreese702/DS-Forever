package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * feat/route-arrows (the user, 2026-11-17: "on the mini map every 5 dots I want a blue arrow so that its consistent"): [RouteArrows]'
 * dash and arrow places, pure. Home's map draws the dots with [RouteArrows.DASH] at [RouteArrows.phase] and the arrows at
 * [RouteArrows.arrowDistances]; then "I want it to be 1 arrow every 5 dots": five dots, then an arrow in a 6th slot of its own (in
 * addition to the five, not in the 5th's place), the 16 px rhythm unbroken, 96 px a cycle, all moving 24 px a second.
 */
class RouteArrowsTest {
    @Test fun theDashIsFiveDotsThenTheArrowsSlot() {
        val d = RouteArrows.DASH
        assertEquals("5 dots, 5 gaps", 10, d.size)
        assertEquals("one cycle = 6 slots of 16 px", 96f, d.sum(), 1e-4f)
        assertEquals(96f, RouteArrows.PERIOD, 0f)
        for (i in 0 until 5) assertEquals("a dot", RouteArrows.DOT, d[2 * i], 1e-6f)
        for (i in 0 until 4) assertEquals("16 px to the next dot", 16f, d[2 * i] + d[2 * i + 1], 1e-4f)
        assertEquals("the last gap spans the arrow's slot", 32f, d[8] + d[9], 1e-4f)
    }

    @Test fun anArrowAfterEveryFiveDotsTheRhythmUnbroken() {
        for (t in longArrayOf(0L, 417L, 1000L, 3333L, 86_400_000L * 30 + 123L)) {
            val len = 700f
            val dots = RouteArrows.dotDistances(len, t)
            val arrows = RouteArrows.arrowDistances(len, t)
            assertTrue("arrows along the path ($t)", arrows.size >= 7)
            for (i in 1 until arrows.size) assertEquals("arrows 96 px apart", 96f, arrows[i] - arrows[i - 1], 1e-3f)
            for (a in arrows) assertTrue("no dot under an arrow ($t, $a)", dots.none { abs(it - a) < 1f })
            val all = (dots.toList() + arrows.toList()).sorted()
            for (i in 1 until all.size) assertEquals("16 px between every piece ($t)", 16f, all[i] - all[i - 1], 1e-3f)
            // every 6th piece an arrow: five dots between two arrows
            for (i in 1 until arrows.size) assertEquals(5, dots.count { it > arrows[i - 1] && it < arrows[i] })
            assertTrue("all on the path", all.all { it in 0f..len })
        }
    }

    @Test fun theDrawsInPlaceWalkMatchesTheArrowPlaces() {
        // MapView walks firstArrow + k * PERIOD with no per-frame array: the same places as arrowDistances
        for (t in longArrayOf(0L, 417L, 3333L, 86_400_000L * 30 + 123L)) for (len in floatArrayOf(5f, 80f, 81f, 700f)) {
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
        assertEquals("12 px in half a second (24 px/s)", (a0[0] + 12f) % 96f, a1[0], 1e-3f)
        assertEquals("the dash phase runs against the path", -RouteArrows.travel(500L), RouteArrows.phase(500L), 0f)
        assertEquals("a loop: the same places one cycle (96 px / 24 px/s = 4 s) later", a0[0], RouteArrows.arrowDistances(500f, 4_000L)[0], 1e-3f)
        // a long-running clock (a month of uptime) still exact: worked in double
        val big = 86_400_000L * 30
        assertEquals(((big / 1000.0 * 24.0) % 96.0).toFloat(), RouteArrows.travel(big), 1e-3f)
        assertEquals("no arrow on a path shorter than its first slot", 0, RouteArrows.arrowDistances(10f, 0L).size)
    }
}
