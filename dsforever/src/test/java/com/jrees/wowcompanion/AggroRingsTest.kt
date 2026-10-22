package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the bottom map's aggro rings (the user's "Both"): the shared rule and which plates get one */
class AggroRingsTest {
    @org.junit.Before fun fresh() { AggroFit.reset() }
    @org.junit.After fun done() { AggroFit.reset() }
    private fun mob(lv: Int? = 22, r: Int? = 1, cb: Boolean? = false, dd: Boolean = false, td: Boolean? = false,
                    a: Double? = null, b: Double? = null, br: Double? = null, bs: String? = null, n: Int? = 100) =
        AggroDoc.Mob(1, n, null, lv, null, r, cb, null, a, b, null, null, dd, td, null, null, null, null, br, bs)
    private fun at(x: Double, y: Double, how: LivePlace.How = LivePlace.How.SPAWN, spawn: LivePlace.SpawnPt? = null) =
        LivePlace.Placed(how, x, y, 0.0, spawn = spawn, sure = true)
    private fun plate(id: Long, m: AggroDoc.Mob, p: LivePlace.Placed?, last: Long = 1_000L) = AggroRings.Plate(id, m, p, last)

    @Test fun theRadiusIsTwentyYardsAYardALevelFiveToFortyFive() {
        assertEquals(20.0, AggroRings.radius(20, 20), 1e-9)
        assertEquals(22.0, AggroRings.radius(22, 20), 1e-9)
        assertEquals(20.0 - 3 * AggroFit.K_BELOW_PRIOR, AggroRings.radius(17, 20), 1e-9)      // under the level: the fitted slope
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

    // ---- "Fit it" (2026-10-22): the shared rule, fitted to test58's pulls and learning from the device's own
    private var t = 0.0
    private fun pull(lv: Int, plv: Int, lo: Double, hi: Double, n: Int? = 500) = AggroDoc.Pull(1, n, null, lv, plv, lo, hi, t++)

    @Test fun theFittedRuleFallsFasterUnderThePlayersLevel() {
        assertEquals(2.2, AggroFit.kBelow(), 1e-9)                       // the prior, test58's vote
        assertEquals(20.0, AggroFit.radius(14, 14), 1e-9)                // equal level: the base
        assertEquals(23.0, AggroFit.radius(17, 14), 1e-9)                // above: classic's yard a level
        assertEquals(13.4, AggroFit.radius(11, 14), 1e-9)                // 3 under: 20 - 3 x 2.2
        assertEquals(9.0, AggroFit.radius(9, 14), 1e-9)                  // 5 under (test58's mobs 8-10 against 13-14: measured ~5..10)
        assertEquals(5.0, AggroFit.radius(4, 14), 1e-9)                  // the floor
        assertEquals(30.0, AggroFit.radius(-1, 20), 1e-9)                // a skull: the player's level + 10
        assertEquals(45.0, AggroFit.radius(80, 20), 1e-9)                // the cap
    }

    @Test fun thePullsUnderThePlayersLevelTeachTheSlopeOutliersOutvotedWithinBounds() {
        // test58's ten pulls as measured (mobs 9 against 14: 5 under): 10..10 x2, 5..8, 8..10 x3, 10..15, 15..20, 0..5, 30..35
        for ((lo, hi) in listOf(10.0 to 10.0, 10.0 to 10.0, 5.0 to 8.0, 8.0 to 10.0, 8.0 to 10.0, 10.0 to 15.0, 15.0 to 20.0, 8.0 to 10.0, 0.0 to 5.0, 30.0 to 35.0))
            AggroFit.add(pull(9, 14, lo, hi))
        // the vote: k in [(20 - hi) / 5, (20 - lo) / 5] - the most at k = 2.0 (10..10 x2, 8..10 x3, 10..15); 30..35 (a link) outvoted
        assertEquals(2.0, AggroFit.kBelow(), 1e-9)
        assertEquals(10.0, AggroFit.radius(9, 14), 1e-9)
        assertEquals(20.0, AggroFit.radius(14, 14), 1e-9)               // the base untouched: no pull near the level
        assertTrue(AggroFit.words(), AggroFit.words().contains("2.00 yd a level under (10 pulls"))
        // pulls that would say k = 6 (radius 0 at 5 under... wider than any bound): held at K_MAX
        AggroFit.reset()
        repeat(AggroFit.MIN_PULLS) { AggroFit.add(pull(16, 20, 0.0, 1.0)) }   // 4 under, r 0..1: k 4.75..5
        assertEquals(AggroFit.K_MAX, AggroFit.kBelow(), 1e-9)
    }

    @Test fun pullsNearTheLevelTeachTheBaseNotTheSlope() {
        // test70: a few pulls teach nothing (4 had moved the base to 11 yd)
        repeat(4) { AggroFit.add(pull(14, 14, 10.0, 11.0)) }
        assertEquals(20.0, AggroFit.base(500, null), 1e-9)
        AggroFit.reset()
        repeat(AggroFit.MIN_PULLS) { AggroFit.add(pull(14, 14, 22.0, 24.0)) }
        assertEquals(22.0, AggroFit.base(500, null), 1e-9)
        assertEquals(AggroFit.K_BELOW_PRIOR, AggroFit.kBelow(), 1e-9)
        assertEquals(22.0 - 2 * AggroFit.K_BELOW_PRIOR, AggroFit.radius(12, 14, 500), 1e-9)
    }

    // ---- "Spawn circles" (2026-10-22)
    private fun sp(key: Int, x: Double, y: Double, npc: Int = 100, lv: Int = 14, hostile: Boolean = true) =
        LivePlace.SpawnPt(key, npc, x, y, lv, lv, false, hostile)

    @Test fun theKnownHostileSpawnsNearGetFaintCirclesTheNearestFirstCapped() {
        val spawns = listOf(sp(1, 30.0, 0.0), sp(2, 0.0, 200.0), sp(3, 10.0, 0.0, hostile = false), sp(4, 0.0, 15.0, lv = 9)) +
            (10 until 60).map { sp(it, 50.0 + it, 0.0) }
        val rs = AggroRings.spawnRings(spawns, emptyList(), 14, 0.0, 0.0)
        assertEquals(AggroRings.SPAWN_CAP, rs.size)
        assertEquals(listOf(4, 1), rs.take(2).map { it.key })            // nearest first; the friendly one and the far one out
        assertTrue(rs.none { it.solid })
        val r4 = rs.first { it.key == 4 }
        assertEquals(9.0, r4.r, 1e-9); assertFalse(r4.inside)            // 5 under: 9 yd; the player 15 yd off
        assertTrue(AggroRings.spawnRings(listOf(sp(1, 5.0, 0.0)), emptyList(), 14, 0.0, 0.0)[0].inside)
    }

    @Test fun aLivePlateOfItsNpcConfirmsASpawnAndASnappedOneIsLeftToItsRing() {
        val spawns = listOf(sp(1, 30.0, 0.0), sp(2, 0.0, 80.0))
        // a plate of npc 100 whose band (25..35 yd) holds spawn 1 (30 yd) and not spawn 2 (80 yd)
        val plate = plate(9, mob(lv = 14, a = 25.0, b = 35.0), null)
        val rs = AggroRings.spawnRings(spawns, listOf(plate), 14, 0.0, 0.0).associateBy { it.key }
        assertTrue(rs[1]!!.solid); assertFalse(rs[2]!!.solid)
        // a plate in combat confirms nothing
        assertFalse(AggroRings.spawnRings(spawns, listOf(plate(9, mob(lv = 14, a = 25.0, b = 35.0, cb = true), null)), 14, 0.0, 0.0)[0].solid)
        // a spawn a live ring stands on (a sure snap) is the ring's
        assertEquals(listOf(2), AggroRings.spawnRings(spawns, emptyList(), 14, 0.0, 0.0, liveKeys = setOf(1)).map { it.key })
    }
}
