package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the bottom map's aggro rings (the user's "Both"): the shared rule and which plates get one */
class AggroRingsTest {
    private fun mob(lv: Int? = 22, r: Int? = 1, cb: Boolean? = false, dd: Boolean = false, td: Boolean? = false) =
        AggroDoc.Mob(1, 100, null, lv, null, r, cb, null, null, null, null, null, dd, td, null, null, null, null)
    private fun at(x: Double, y: Double, how: LivePlace.How = LivePlace.How.SPAWN, spawn: LivePlace.SpawnPt? = null) =
        LivePlace.Placed(how, x, y, 0.0, spawn = spawn, sure = true)
    private fun plate(id: Long, m: AggroDoc.Mob, p: LivePlace.Placed?, last: Long = 1_000L) = AggroRings.Plate(id, m, p, last)

    @Test fun theRadiusIsTwentyYardsAYardALevelFiveToFortyFive() {
        assertEquals(20.0, AggroRings.radius(20, 20), 1e-9)
        assertEquals(22.0, AggroRings.radius(22, 20), 1e-9)
        assertEquals(17.0, AggroRings.radius(17, 20), 1e-9)
        assertEquals(5.0, AggroRings.radius(1, 40), 1e-9)                   // never under 5
        assertEquals(45.0, AggroRings.radius(70, 20), 1e-9)                 // never over 45
        assertEquals(30.0, AggroRings.radius(-1, 20), 1e-9)                 // a skull: the player's level + 10
        assertEquals(21.0, AggroRings.radius(null, 20, 19..23), 1e-9)       // unknown: the bracket's midpoint
        assertEquals(20.0, AggroRings.radius(null, 20), 1e-9)               // nothing known: equal level
    }

    @Test fun onlyAnUnpulledHostilePlacedAtAPointGetsARing() {
        val now = 2_000L
        val plates = listOf(
            plate(1, mob(), at(0.0, 0.0)),                                  // yes
            plate(2, mob(cb = true), at(50.0, 0.0)),                        // in combat: none
            plate(3, mob(dd = true), at(100.0, 0.0)),                       // dead
            plate(4, mob(td = true), at(150.0, 0.0)),                       // tapped by another
            plate(5, mob(r = 4), at(200.0, 0.0)),                           // neutral
            plate(6, mob(), at(250.0, 0.0, LivePlace.How.RING)),            // only a bracket's band: no centre
            plate(7, mob(), null),                                          // not placed
            plate(8, mob(), at(300.0, 0.0), last = now - AggroRings.FRESH_MS - 1),   // not seen lately
            plate(9, mob(r = null), at(350.0, 0.0)))                        // reaction unknown: counted hostile
        assertEquals(listOf(1L, 9L), AggroRings.of(plates, 20, Double.NaN, Double.NaN, now).map { it.id })
    }

    @Test fun theRingThePlayerStandsInIsInside() {
        val plates = listOf(plate(1, mob(lv = 22), at(0.0, 0.0)), plate(2, mob(lv = 20), at(100.0, 0.0)),
            plate(3, mob(lv = null), at(200.0, 0.0, spawn = LivePlace.SpawnPt(0, 100, 200.0, 0.0, 24, 26, false, true))))
        val rs = AggroRings.of(plates, 20, 15.0, 15.0, 1_000L).associateBy { it.id }
        assertTrue(rs[1]!!.inside)                                          // 21.2 yd from a 22 yd ring
        assertFalse(rs[2]!!.inside)
        assertEquals(25.0, rs[3]!!.r, 1e-9)                                 // its spawn's 24..26: 25
        assertFalse(AggroRings.of(plates, 20, Double.NaN, Double.NaN, 1_000L).any { it.inside })
    }
}
