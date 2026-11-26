package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * fix/hold-tweaks (1535; the user on test 1533, 2026-11-26: "The L and R in the bind menu is really confusing when it cycles to the tap
 * to cast bar. Instead I want select to cycle to the tap to cast and l and r to keep swapping tabs"): the Bind box's LB / RB step the
 * SITUATIONS only (Out of combat, In combat, Party mode), keeping the device the box is on (a situation without that bar: its
 * controller); Select ([CastStripModel.toggleDevice], the add-on's counted `bsel`, Book b89) moves between the situation's controller
 * and its tap-to-cast bar.
 */
class BindBoxSelectTest {
    private fun t(fight: Boolean, tap: Boolean, party: Boolean = false) = CastStripModel.BindTab(fight, tap, party)

    @Test fun lbAndRbStepTheSituationsOnlyKeepingTheDevice() {
        val out = t(false, false); val outBar = t(false, true); val inC = t(true, false); val inBar = t(true, true)
        val party = t(false, false, true); val partyBar = t(false, true, true)
        fun step(f: CastStripModel.BindTab, d: Int, fightTap: Boolean = true, p: Boolean = true, pTap: Boolean = true) =
            CastStripModel.stepSituation(f, d, tap = true, fightTap = fightTap, party = p, partyTap = pTap)
        assertEquals("RB from Out of combat's controller: In combat's controller (never the bar)", inC, step(out, 1))
        assertEquals("RB again: Party mode's controller", party, step(inC, 1))
        assertEquals("no wrap", party, step(party, 1))
        assertEquals("LB back", inC, step(party, -1)); assertEquals(out, step(inC, -1)); assertEquals(out, step(out, -1))
        assertEquals("on the bar: the next situation's bar", inBar, step(outBar, 1))
        assertEquals(partyBar, step(inBar, 1)); assertEquals(outBar, step(inBar, -1))
        assertEquals("In combat has no bar layer: its controller", inC, step(outBar, 1, fightTap = false))
        assertEquals("Party mode not allowed: In combat is the end", inC, step(inC, 1, p = false))
        assertEquals("no bar at all: the controllers", inC, CastStripModel.stepSituation(out, 1, tap = false))
    }

    @Test fun selectTogglesTheControllerAndTheBar() {
        fun tog(f: CastStripModel.BindTab, tap: Boolean = true, fightTap: Boolean = true, pTap: Boolean = true) =
            CastStripModel.toggleDevice(f, tap, fightTap, pTap)
        assertEquals(t(false, true), tog(t(false, false))); assertEquals(t(false, false), tog(t(false, true)))
        assertEquals(t(true, true), tog(t(true, false))); assertEquals(t(true, false), tog(t(true, true)))
        assertEquals(t(false, true, true), tog(t(false, false, true))); assertEquals(t(false, false, true), tog(t(false, true, true)))
        assertEquals("no bar: Select changes nothing", t(false, false), tog(t(false, false), tap = false))
        assertEquals("In combat without its layer: stays", t(true, false), tog(t(true, false), fightTap = false))
        assertEquals("Party mode without its layer: stays", t(false, false, true), tog(t(false, false, true), pTap = false))
    }

    @Test fun theAddonsSelectCountIsRead() {
        val c = SpellsModel.parseCtl(JSONObject().put("type", "spellsctl").put("ctl", JSONObject().put("on", true).put("mode", "bind").put("bsel", 3)))
        assertEquals(3, c?.bindSel)
        val n = SpellsModel.parseCtl(JSONObject().put("type", "spellsctl").put("ctl", JSONObject().put("on", true).put("mode", "bind")))
        assertEquals(null, n?.bindSel)
    }
}
