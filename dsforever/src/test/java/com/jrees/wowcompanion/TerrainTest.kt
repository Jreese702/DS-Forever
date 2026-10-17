package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** b-route-terrain: the game's heights read, a zone's slope grid, the route kept off the mountains */
class TerrainTest {
    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun chunk(tag: String, body: ByteArray) = tag.reversed().toByteArray(Charsets.ISO_8859_1) + le(body.size) + body

    @Test fun aRootAdtsHeightsAsTheGameLaysThemOut() {
        // one MCNK (ix 1, iy 2), base height 100 (position z at 0x70), MCVT rising 1 yd per outer column
        val hdr = ByteArray(128)
        le(1).copyInto(hdr, 4); le(2).copyInto(hdr, 8)
        le(java.lang.Float.floatToIntBits(100f)).copyInto(hdr, 0x70)
        val mcvt = ByteArrayOutputStream()
        for (row in 0 until 17) { val n = if (row % 2 == 0) 9 else 8; for (c in 0 until n) mcvt.write(le(java.lang.Float.floatToIntBits(if (n == 9) c.toFloat() else -1f))) }
        val adt = chunk("MVER", le(18)) + chunk("MCNK", hdr + chunk("MCVT", mcvt.toByteArray()))
        val t = Terrain.tile(adt)!!
        // outer row 3 of chunk iy 2 = global row 19; column 5 of chunk ix 1 = global column 13
        assertEquals(105f, t.h[19 * 128 + 13], 1e-4f)
        assertTrue(t.h[0].isNaN())                                     // chunks not in the file: no height
    }

    @Test fun slopesAreTheGroundsAverage() {
        val S = 100.0 / 3.0 / 8.0
        val rect = MapHarvest.Rect(0, 100.0, 0.0, 100.0, 0.0)            // 100 x 100 yd: 4 x 4 cells
        // flat on the west half, a 45 degree climb (1 yd up per yard east) on the east half
        val g = Terrain.grid(rect) { gr, gc ->
            val wy = 32.0 * MapHarvest.ADT_YD - gc * S
            if (wy > 50.0) 0f else (50.0 - wy).toFloat()
        }
        assertEquals(4, g.nx); assertEquals(4, g.ny)
        assertTrue(g.at(50.0, 90.0) < 5)                                 // west: flat
        assertTrue(g.at(50.0, 10.0) in 15..30)                           // east: the climb, averaged with its level rows (only east-west edges rise)
        assertEquals(Terrain.NO_DATA, g.at(500.0, 500.0))
        // the device file reads back
        val f = java.io.File.createTempFile("trn", ".bin"); g.write(f)
        val back = Terrain.Grid.read(f)!!; f.delete()
        assertEquals(g.at(50.0, 10.0), back.at(50.0, 10.0))
    }

    @Test fun climbsCostMoreCliffsAreWalls() {
        assertEquals(1f, Terrain.factor(10)); assertEquals(1f, Terrain.factor(Terrain.NO_DATA))
        assertEquals(1f, Terrain.factor(22))                            // Elwynn's rolling forest: level enough
        assertTrue(Terrain.factor(35) in 2f..3f)
        assertTrue(Terrain.factor(45) > 6f)
        assertEquals(60f, Terrain.factor(70))
    }

    @Test fun theRouteGoesRoundAMountainNotOverIt() {
        // a 2000 x 2000 yd map with no mobs; a ridge (a cliff) across the straight line, open at its south end
        val g = DangerGrid.build(emptyList(), 0, 10, 2000.0, 2000.0) { x, y ->
            if (x in 0.45..0.55 && y < 0.8) Terrain.factor(70) else 1f
        }
        val r = g.route(doubleArrayOf(0.2, 0.3), doubleArrayOf(0.8, 0.3), DangerGrid.strength("high"))
        assertTrue("bent: ${r.size}", r.size > 2)
        assertTrue(r.any { it[1] > 0.8 })                                  // round the ridge's open end
        for (i in 1 until r.size) assertTrue(g.segCost(r[i - 1], r[i], 0.0, r.last()) < 2.0 * 2000.0)   // never over the cliff
        // level ground: straight
        assertEquals(2, DangerGrid.build(emptyList(), 0, 10, 2000.0, 2000.0) { _, _ -> 1f }.route(doubleArrayOf(0.2, 0.3), doubleArrayOf(0.8, 0.3), 8.0).size)
    }

    @Test fun walkedGroundIsPreferred() {
        // two ways round an obstacle; the player has walked the northern one
        val g = DangerGrid.build(emptyList(), 0, 10, 2000.0, 2000.0) { x, y ->
            when {
                x in 0.45..0.55 && y in 0.25..0.75 -> 60f                     // a cliff in the middle
                y < 0.25 && x in 0.3..0.7 -> 1f * Terrain.Walked.WALKED         // the walked track north of it
                else -> 1f
            }
        }
        val r = g.route(doubleArrayOf(0.2, 0.5), doubleArrayOf(0.8, 0.5), 8.0)
        assertTrue("north: ${r.joinToString { "%.2f,%.2f".format(it[0], it[1]) }}", r.any { it[1] < 0.25 })
    }
}
