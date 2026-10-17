package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b-route-cross-zone (test58: "it still has it sending me up a mountain"): a plan over the stitched zones crosses a border where both
 * sides are walkable (the gap in the ridge, the bridge), never over the mountain straight toward the quest
 */
class CrossZoneTest {
    // two zones side by side, east (world y -1000..0) and west (world y 0..1000), world x -1000..0; a ridge along the border (y -50..50)
    // with one gap at x -800..-700 (the bridge, far from the straight line)
    private fun slope(cliff: Boolean) = { wx: Double, wy: Double ->
        if (wy in -60.0..60.0 && !(wx in -800.0..-700.0)) 60 else 10
    }
    private fun grid(t: Double, b: Double, l: Double, r: Double): Terrain.Grid {
        val nx = ((l - r) / 25).toInt(); val ny = ((t - b) / 25).toInt()
        val s = ByteArray(nx * ny)
        val f = slope(true)
        for (j in 0 until ny) for (i in 0 until nx) s[j * nx + i] = f(t - (j + 0.5) * 25, l - (i + 0.5) * 25).toByte()
        return Terrain.Grid(t, b, l, r, nx, ny, s)
    }
    // (the zones' grids reach past the plan's margin, as a continent's zones do: no way round the ridge's end)
    private val east = CrossZone.Zone(1, grid(1500.0, -2500.0, 0.0, -2000.0), null)
    private val west = CrossZone.Zone(2, grid(1500.0, -2500.0, 2000.0, 0.0), null)

    @Test fun throughTheGapNotOverTheRidge() {
        // from the east side at x -200 to the west side at x -200: straight across is the ridge; the way is the gap at x -750
        val p = CrossZone.plan(-200.0, -400.0, -200.0, 400.0, { wx, wy -> CrossZone.cost(listOf(east, west), wx, wy) }, emptyList(), 0.0)
        assertNotNull(p)
        val ex = CrossZone.exit(p!!, 1500.0, -2500.0, 0.0, -2000.0)!!                     // where it leaves the east zone's box
        assertTrue("crossed at x ${ex.second[0]}", ex.second[0] in -825.0..-675.0)
        // every point of the plan off the ridge, but in the gap
        for (q in p.pts) assertTrue(q[1] !in -40.0..40.0 || q[0] in -825.0..-675.0)
    }

    @Test fun aLearnedRoadIsPreferred() {
        val road = CrossZone.Zone(1, east.slope, null) { wx, _ -> wx in -350.0..-300.0 }
        val p = CrossZone.plan(-200.0, -900.0, -200.0, -100.0, { wx, wy -> CrossZone.cost(listOf(road), wx, wy) }, emptyList(), 0.0)!!
        // the plan walks down to the road (x -325) for most of the way rather than straight along x -200
        assertTrue(p.pts.count { it[0] in -375.0..-275.0 } > p.pts.size / 2)
    }

    @Test fun noWayIsNoPlan() {
        val sealed = CrossZone.Zone(1, Terrain.Grid(2000.0, -3000.0, 2000.0, -2000.0, 160, 200, ByteArray(32000) { i -> if ((i % 160) in 78..81) 60 else 10 }), null)
        assertNull(CrossZone.plan(-500.0, -500.0, -500.0, 500.0, { wx, wy -> CrossZone.cost(listOf(sealed), wx, wy) }, emptyList(), 0.0))
        assertEquals(CrossZone.UNKNOWN, CrossZone.cost(emptyList(), 0.0, 0.0))
    }
}
