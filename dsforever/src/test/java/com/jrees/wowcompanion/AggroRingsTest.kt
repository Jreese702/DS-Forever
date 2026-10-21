package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the bottom map's aggro rings (the user's "Both"): the shared rule and which plates get one */
class AggroRingsTest {
    private fun mob(lv: Int? = 22, r: Int? = 1, cb: Boolean? = false, dd: Boolean = false, td: Boolean? = false,
                    a: Double? = null, b: Double? = null, br: Double? = null, bs: String? = null, n: Int? = 100) =
        AggroDoc.Mob(1, n, null, lv, null, r, cb, null, a, b, null, null, dd, td, null, null, null, null, br, bs)
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
            plate(8, mob(), LivePlace.Placed(LivePlace.How.SECTOR, 300.0, 0.0, 12.0)),   // its place too vague (12 yd)
            plate(9, mob(r = null), at(350.0, 0.0)))                        // reaction unknown: counted hostile
        val look = AggroRings.look(plates, 20, Double.NaN, Double.NaN)
        assertEquals(listOf(1L, 9L), look.rings.map { it.id })
        assertEquals(listOf(9, 1, 2, 1, 3), listOf(look.plates, look.combat, look.band, look.vague, look.other))
    }

    @Test fun theRingThePlayerStandsInIsInside() {
        val plates = listOf(plate(1, mob(lv = 22), at(0.0, 0.0)), plate(2, mob(lv = 20), at(100.0, 0.0)),
            plate(3, mob(lv = null), at(200.0, 0.0, spawn = LivePlace.SpawnPt(0, 100, 200.0, 0.0, 24, 26, false, true))))
        val rs = AggroRings.of(plates, 20, 15.0, 15.0).associateBy { it.id }
        assertTrue(rs[1]!!.inside)                                          // 21.2 yd from a 22 yd ring
        assertFalse(rs[2]!!.inside)
        assertEquals(25.0, rs[3]!!.r, 1e-9)                                 // its spawn's 24..26: 25
        assertFalse(AggroRings.of(plates, 20, Double.NaN, Double.NaN).any { it.inside })
    }

    // ---- test52 ("Wrong place"): a known mob's place through the doc's bearing and bracket to the ring's centre
    private val me = AggroDoc.Me(20, 1, 0.0, 0.0, 0.0)          // at the origin, facing north (x north, y west)
    private fun ringOf(m: AggroDoc.Mob, spawns: List<LivePlace.SpawnPt> = emptyList(), moving: Boolean = false): AggroRings.Look {
        val (a, b) = LivePlace.bracket(m, null)
        val p = LivePlace.place(m, me, listOf(LivePlace.Ring(0.0, 0.0, a, b, 0L)), spawns, emptySet(), moving, null)
        return AggroRings.look(listOf(plate(1, m, p)), 20, me.x, me.y)
    }

    @Test fun aMobFifteenYardsWestOnTheCompasssBearingIsRingedWhereItStands() {
        // the mob at (0, 15): bearing 90 degrees (counter-clockwise from north: west), a 10..20 yd bracket, the camera's yaw
        val l = ringOf(mob(a = 10.0, b = 20.0, br = Math.PI / 2, bs = "c", n = null))
        assertEquals(1, l.rings.size)
        assertEquals(0.0, l.rings[0].x, 1e-6); assertEquals(15.0, l.rings[0].y, 1e-6)
        assertTrue(l.rings[0].why, l.rings[0].why.startsWith("SECTOR at 0,15"))
        // east (bearing -90 degrees): (0, -15)
        val e = ringOf(mob(a = 10.0, b = 20.0, br = -Math.PI / 2, bs = "c", n = null)).rings[0]
        assertEquals(0.0, e.x, 1e-6); assertEquals(-15.0, e.y, 1e-6)
    }

    @Test fun aVaguePlaceGetsNoRing() {
        // the facing taken as the yaw while standing (40 degrees either way): 11.6 yd of doubt - no ring
        assertEquals(1, ringOf(mob(a = 10.0, b = 20.0, br = Math.PI / 2, bs = "f", n = null)).vague)
        // a 0..41 bracket's middle on the compass's bearing: 21 yd of doubt - no ring
        assertEquals(1, ringOf(mob(a = null, b = null, br = Math.PI / 2, bs = "c", n = null)).vague)
        // no bearing at all: the band only
        assertEquals(1, ringOf(mob(a = 10.0, b = 20.0, n = null)).band)
    }

    @Test fun anUnclearSnapGetsNoRing() {
        // two spawns of its creature fit its bracket as well (no bearing on Forever: plate:GetCenter is secret) - no ring
        val l = ringOf(mob(a = 10.0, b = 20.0), listOf(LivePlace.SpawnPt(7, 100, 1.0, 14.0, 21, 21, false, true),
            LivePlace.SpawnPt(8, 100, -14.0, 2.0, 21, 21, false, true)))
        assertEquals(0, l.rings.size); assertEquals(1, l.vague)
    }

    @Test fun aPlateSnappedToItsSpawnIsRingedAtTheSpawn() {
        val sp = LivePlace.SpawnPt(7, 100, 1.0, 14.0, 21, 21, false, true)
        val l = ringOf(mob(a = 10.0, b = 20.0), listOf(sp))
        assertEquals(1, l.rings.size); assertEquals(1.0, l.rings[0].x, 1e-9); assertEquals(14.0, l.rings[0].y, 1e-9)
        assertTrue(l.rings[0].why, l.rings[0].why.startsWith("SPAWN spawn 7 sure"))
    }
}
