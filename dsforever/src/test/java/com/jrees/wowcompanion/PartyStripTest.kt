package com.jrees.wowcompanion

import com.jrees.wowcompanion.PartyModel.Dir
import com.jrees.wowcompanion.PartyStrip.Kind
import com.jrees.wowcompanion.PartyStrip.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PARTY_BUILD.md 4.3: lit / dim, the chord layer swap, Y's picture in / out of combat with Smart Y on / off, the dim arrows */
class PartyStripTest {
    private val cfg = PartyModelTest.cfg()
    private val party = PartyModelTest.party()               // box 2 = Brannoc, alive
    private val dead = party.copy(m = party.m.mapIndexed { i, m -> if (i == 1) m.copy(dead = true) else m })
    private val learned = cfg.copy(slots = cfg.slots.mapValues { (_, s) -> s?.copy(k = true) })

    @Test fun litAndDim() {
        // alive: heals lit, res dim; not learned dim
        assertTrue(PartyStrip.face("a", Layer.BASE, cfg, party, 2, false).lit)
        assertFalse(PartyStrip.face("x", Layer.BASE, cfg, party, 2, false).lit)                 // k = false
        assertFalse(PartyStrip.face("a", Layer.ZR, learned, party, 2, false).lit)               // res on an alive member
        // dead: only res lit
        assertTrue(PartyStrip.face("a", Layer.ZR, learned, dead, 2, false).lit)
        assertFalse(PartyStrip.face("a", Layer.BASE, learned, dead, 2, false).lit)
        assertFalse(PartyStrip.face("a", Layer.ZL, learned, dead, 2, false).lit)
        // Y and B never dim
        assertTrue(PartyStrip.face("y", Layer.BASE, cfg, dead, 2, false).lit)
        assertTrue(PartyStrip.face("b", Layer.BASE, cfg, dead, 2, true).lit)
        assertEquals(Kind.BACK, PartyStrip.face("b", Layer.ZL, cfg, dead, 2, true).kind)
    }

    @Test fun theChordLayerSwap() {
        assertEquals(331, PartyStrip.face("a", Layer.BASE, cfg, party, 1, false).spell!!.id)
        assertEquals(526, PartyStrip.face("a", Layer.ZL, cfg, party, 1, false).spell!!.id)
        assertEquals(2870, PartyStrip.face("x", Layer.ZL, cfg, party, 1, false).spell!!.id)
        assertEquals(Kind.EMPTY, PartyStrip.face("y", Layer.ZL, cfg, party, 1, false).kind)     // zly Empty: the ring alone
        assertEquals(2008, PartyStrip.face("a", Layer.ZR, cfg, party, 1, false).spell!!.id)
        assertEquals(Kind.EMPTY, PartyStrip.face("x", Layer.ZR, cfg, party, 1, false).kind)
        for (f in listOf("a", "x", "y")) assertEquals(Kind.EMPTY, PartyStrip.face(f, Layer.BOTH, cfg, party, 1, false).kind)
        assertEquals(Layer.ZL, PartyStrip.layer(true, false)); assertEquals(Layer.BOTH, PartyStrip.layer(true, true))
        assertEquals(Layer.BASE, PartyStrip.layer(false, false))
    }

    @Test fun yOutOfAndInCombat() {
        // Smart Y off: the game's Y, in and out of a fight (yc null)
        assertEquals(Kind.INSPECT, PartyStrip.face("y", Layer.BASE, cfg, party, 2, false).kind)
        assertEquals(Kind.INSPECT, PartyStrip.face("y", Layer.BASE, cfg, party, 2, true).kind)
        // Smart Y on: the pick for the boxed member (party1) out of combat; none for you -> Inspect
        val sy = cfg.copy(sy = cfg.sy.copy(on = true))
        PartyStrip.face("y", Layer.BASE, sy, party, 2, false).let { assertEquals(Kind.SMART, it.kind); assertEquals(131, it.spell!!.id) }
        assertEquals(Kind.INSPECT, PartyStrip.face("y", Layer.BASE, sy, party, 1, false).kind)
        // never Smart Y's buff in a fight: yc, or Inspect
        assertEquals(Kind.INSPECT, PartyStrip.face("y", Layer.BASE, sy, party, 2, true).kind)
        val yc = sy.copy(yc = PartyModel.Spell(331, "Healing Wave", 136052))
        PartyStrip.face("y", Layer.BASE, yc, party, 2, true).let { assertEquals(Kind.SPELL, it.kind); assertEquals(331, it.spell!!.id); assertTrue(it.lit) }
        assertEquals(Kind.SMART, PartyStrip.face("y", Layer.BASE, yc, party, 2, false).kind)     // out of combat: unchanged
        // no cfg yet: Y is the game's, the rest empty
        assertEquals(Kind.INSPECT, PartyStrip.face("y", Layer.BASE, null, party, 1, false).kind)
        assertEquals(Kind.EMPTY, PartyStrip.face("a", Layer.BASE, null, party, 1, false).kind)
    }

    @Test fun dimArrowsPerNAndBox() {
        assertEquals(mapOf(Dir.UP to false, Dir.DOWN to false, Dir.LEFT to false, Dir.RIGHT to false), PartyStrip.arrows(1, 1))
        assertEquals(mapOf(Dir.UP to false, Dir.DOWN to true, Dir.LEFT to false, Dir.RIGHT to false), PartyStrip.arrows(2, 1))
        // m131 adaptive rows: n 4 = 1 | 2 | (3 4); n 5 = 1 | (2 3) | (4 5) - Left / Right lit inside a shared row
        assertEquals(mapOf(Dir.UP to true, Dir.DOWN to false, Dir.LEFT to false, Dir.RIGHT to true), PartyStrip.arrows(4, 3))
        assertEquals(mapOf(Dir.UP to true, Dir.DOWN to false, Dir.LEFT to true, Dir.RIGHT to false), PartyStrip.arrows(5, 5))
        assertEquals(mapOf(Dir.UP to true, Dir.DOWN to true, Dir.LEFT to false, Dir.RIGHT to false), PartyStrip.arrows(3, 2))
    }

    @Test fun triggerPlates() {
        assertEquals(listOf(526, 2870), PartyStrip.plateIcons(cfg, left = true).map { it.id })
        assertEquals(listOf(2008), PartyStrip.plateIcons(cfg, left = false).map { it.id })
        assertTrue(PartyStrip.plateLit(true, lt = true, rt = false)); assertFalse(PartyStrip.plateLit(false, lt = true, rt = false))
    }
}
