package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-route-roads: the ground textures read, the dominant one per point, the roads learned from the walking */
class RoadsTest {
    @Test fun alphaMapsDecode() {
        // RLE: fill 0x80|3 of 200, then copy 2 bytes (10, 20)
        val rle = byteArrayOf(0x83.toByte(), 200.toByte(), 0x02, 10, 20)
        val a = Roads.decodeAlpha(rle, 0, rle.size, true)
        assertEquals(listOf(200, 200, 200, 10, 20, 0), (0..5).map { a[it].toInt() and 0xFF })
        // 4 bits: the low nibble first, scaled to 0..255
        val four = ByteArray(2048); four[0] = 0xF1.toByte()
        val b = Roads.decodeAlpha(four, 0, 2048, false)
        assertEquals(17, b[0].toInt() and 0xFF); assertEquals(255, b[1].toInt() and 0xFF)
    }

    @Test fun theTopLayerShowsWhereItsAlphaIsHigh() {
        val road = ByteArray(4096); road[0] = 255.toByte(); road[1] = 100.toByte()
        val c = Roads.Chunk(intArrayOf(111, 222), arrayOf(null, road))
        assertEquals(222, Roads.showing(c, 0, 0))               // fully the road
        assertEquals(111, Roads.showing(c, 1, 0))               // 100 / 255 over grass: grass shows most
        assertEquals(111, Roads.showing(c, 5, 5))               // no road there
    }

    @Test fun roadsAreTheTexturesTheWalkingFindsOverRepresented() {
        // a 40 x 40 zone: grass (1) everywhere, a road (2) along row 20, rock (3) in a corner
        val tex = IntArray(1600) { k -> val i = k % 40; val j = k / 40; when { j == 20 -> 2; i < 8 && j < 8 -> 3; else -> 1 } }
        val g = Roads.Grid(0, 1000.0, 0.0, 1000.0, 0.0, 40, 40, tex)
        fun cell(wx: Double, wy: Double) = ((1000.0 - wx) / 25).toInt() * 40 + ((1000.0 - wy) / 25).toInt()
        // the player walked 30 road cells and 20 grass cells
        val walked = (0 until 30).map { 20 * 40 + it }.toSet() + (0 until 20).map { 5 * 40 + 10 + it }.toSet()
        val (roads, n) = Roads.learn(listOf(g to 0)) { _, wx, wy -> cell(wx, wy) in walked }
        assertEquals(setOf(2), roads); assertEquals(50, n)
        // too little walking: nothing learned
        assertTrue(Roads.learn(listOf(g to 0)) { _, wx, wy -> cell(wx, wy) in (0 until 5).map { 20 * 40 + it } }.first.isEmpty())
        // the grid file reads back
        val f = java.io.File.createTempFile("gtx", ".bin"); g.write(f)
        val back = Roads.Grid.read(f)!!; f.delete()
        assertEquals(2, back.at(1000.0 - 20.5 * 25, 1000.0 - 3.5 * 25)); assertEquals(0, back.c)
    }
}
