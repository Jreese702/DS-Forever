package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** b-route-buildings (test54: "Sometimes checkpoints are going through buildings"): footprints as walls on the route's grid */
class BuildingsTest {
    // a 1000 x 1000 yd map: world x north from 0 (top) to -1000, world y west from 0 (left) to -1000
    private val r = PoiStore.Rect(0, 0.0, -1000.0, 0.0, -1000.0, true)

    @Test fun aFootprintPlacedAndItsCover() {
        // a 60 x 40 yd building, unrotated (cs 1, sn 0), its origin at world (-500, -500)
        val f = Buildings.Foot(-500.0, -500.0, 1.0, 0.0, -30.0, 30.0, -20.0, 20.0)
        assertTrue(f.holds(-500.0, -500.0)); assertTrue(f.holds(-525.0, -515.0)); assertFalse(f.holds(-540.0, -500.0))
        // the cell on its middle is covered whole; one 100 yd off not at all
        val c = DangerGrid.CELL_YD / 1000.0
        assertEquals(1f, Buildings.cover(listOf(f), 0.5, 0.5, c, c, r), 1e-6f)
        assertEquals(0f, Buildings.cover(listOf(f), 0.6, 0.5, c, c, r), 1e-6f)
        assertEquals(1, Buildings.onMap(listOf(f), r).size)
    }

    @Test fun theRouteGoesRoundABlockOfBuildingsNotThrough() {
        // a row of building cells (walls) across the middle, 200 yd wide, between the player and the goal 300 yd south
        val ax = 1000.0; val ay = 1000.0
        val wall = { x: Double, y: Double -> if (y in 0.47..0.53 && x in 0.4..0.6) Terrain.WALL else 1f }
        // a spawn far away so the grid is not empty of danger (the route only bends when something costs)
        val g = DangerGrid.build(listOf(QuestieNpcs.Spawn(1, 0.95f, 0.05f, 10, 10, false, 3, "x")), 0, 10, ax, ay, wall)
        val pts = g.route(doubleArrayOf(0.5, 0.35), doubleArrayOf(0.5, 0.65), 8.0)
        assertTrue("bent round", pts.size > 2)
        // no leg crosses the wall row between x 0.42 and 0.58
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if ((a[1] - 0.5) * (b[1] - 0.5) < 0) {
                val t = (0.5 - a[1]) / (b[1] - a[1]); val x = a[0] + (b[0] - a[0]) * t
                assertFalse("crosses the buildings at x $x", x in 0.42..0.58)
            }
        }
        // a goal inside the row (a quest giver indoors) is still reached: the walls do not hold near it
        val inside = g.route(doubleArrayOf(0.5, 0.35), doubleArrayOf(0.5, 0.5), 8.0)
        assertTrue(hypot(inside.last()[0] - 0.5, inside.last()[1] - 0.5) < 1e-9)
    }
}
