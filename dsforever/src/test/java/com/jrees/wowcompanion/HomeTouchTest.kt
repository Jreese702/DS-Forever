package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * combat-touch (the user, 2026-11-13: "When in combat sometimes I touch the map to open it and see, but it won't open and I have to
 * press B to get to it. I JUST WANT TOUCHING IT OR CHAT or any of the rooms and elements TO BEHAVE THAT SAME WAY"): the first touch
 * on a Home module counts in a fight exactly as out of one.
 */
class HomeTouchTest {
    private val t0 = 1_000_000L

    private fun fight(): Pair<HomeFlow, HomeGrow> {
        val grow = HomeGrow()
        grow.noteFight(true, current = true, now = t0)
        val flow = HomeFlow()
        flow.combat(grow.shown(t0))
        assertTrue(flow.takeover)
        return flow to grow
    }

    @Test fun outOfAFightATouchOpensTheMap() {
        val flow = HomeFlow(); val grow = HomeGrow()
        assertTrue(HomeTouch.open(flow, grow, HomeModule.MAP, t0))
        assertEquals(HomeModule.MAP, flow.full)
    }

    @Test fun inTheTakeoverTheFirstTouchOpensTheMap() {
        val (flow, grow) = fight()
        assertTrue(HomeTouch.open(flow, grow, HomeModule.MAP, t0 + 10))
        assertEquals(HomeModule.MAP, flow.full)
        assertFalse("the takeover went, as after B", flow.takeover)
        assertFalse("the big controller shrank, as after B", grow.shown(t0 + 10))
        assertTrue("the red reminder stays: still in the fight", grow.red(t0 + 10))
    }

    @Test fun noGraceForATouchRightAfterThePull() {
        // B is refused in the first 2 s after the grow (a fight button); a touch never is
        val (flow, grow) = fight()
        assertTrue(HomeTouch.open(flow, grow, HomeModule.MAP, t0))
        assertEquals(HomeModule.MAP, flow.full)
    }

    @Test fun everyModuleOpensTheSameWay() {
        for (m in HomeModule.values()) {
            val (flow, grow) = fight()
            assertTrue(HomeTouch.open(flow, grow, m, t0 + 5))
            assertEquals(m, flow.full)
            assertFalse(flow.takeover)
        }
    }

    @Test fun aTouchOnACoveredModuleShowsItAgain() {
        // a module open BEFORE the pull is covered by the takeover; the touch on the small map gives it back at once
        val grow = HomeGrow(); val flow = HomeFlow()
        assertTrue(flow.tapMap())
        grow.noteFight(true, true, t0); flow.combat(grow.shown(t0))
        assertTrue(flow.takeover)
        assertTrue(HomeTouch.open(flow, grow, HomeModule.MAP, t0 + 5))
        assertFalse(flow.takeover)
        assertEquals(HomeModule.MAP, flow.full)
    }

    @Test fun theTakeoverNeverComesBackOverAModuleOpenedInTheFight() {
        val (flow, grow) = fight()
        HomeTouch.open(flow, grow, HomeModule.MAP, t0 + 5)
        // a new auto-attack start would grow the controller again
        grow.noteAttack(true, true, t0 + 100)
        assertTrue(grow.shown(t0 + 100))
        val mark = HomeTouch.keepOpened(true, flow.full, grow.fighting(t0 + 100))
        assertTrue(mark)
        assertFalse(HomeTouch.takeover(grow.shown(t0 + 100), partyOn = false, full = flow.full, openedInFight = mark))
    }

    @Test fun theTakeoverStillCoversAModuleOpenBeforeThePull() {
        assertTrue(HomeTouch.takeover(growShown = true, partyOn = false, full = HomeModule.MAP, openedInFight = false))
    }

    @Test fun partyModeOnNeverTakesOver() {
        assertFalse(HomeTouch.takeover(growShown = true, partyOn = true, full = null, openedInFight = false))
    }

    @Test fun closingTheModuleLetsTheTakeoverBack() {
        val mark = HomeTouch.keepOpened(true, full = null, fighting = true)
        assertFalse(mark)
        assertTrue(HomeTouch.takeover(growShown = true, partyOn = false, full = null, openedInFight = mark))
    }

    @Test fun theMarkEndsWithTheFight() {
        assertFalse(HomeTouch.keepOpened(true, HomeModule.MAP, fighting = false))
    }

    @Test fun closeIsNotRefusedInAFight() {
        // arriving Home / the disc closes a module in a fight as out of one
        val grow = HomeGrow(); val flow = HomeFlow()
        flow.tapChat()
        grow.noteFight(true, true, t0); flow.combat(grow.shown(t0))
        assertTrue(flow.land())
        assertNull(flow.full)
    }

    @Test fun anOpenModuleRefusesASecondOpen() {
        val flow = HomeFlow(); val grow = HomeGrow()
        HomeTouch.open(flow, grow, HomeModule.MAP, t0)
        assertFalse(HomeTouch.open(flow, grow, HomeModule.CHAT, t0))
        assertEquals(HomeModule.MAP, flow.full)
    }
}
