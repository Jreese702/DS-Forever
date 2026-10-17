package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** b-dock-names: the ground's chunk areas named by AreaTable (made-up ids and names, no game data) */
class PlaceNamesTest {
    /** a root ADT of just MCNK headers: each chunk (ix, iy) with [area] */
    private fun adt(area: (Int, Int) -> Int): ByteArray {
        val b = ByteBuffer.allocate(256 * (8 + 128)).order(ByteOrder.LITTLE_ENDIAN)
        for (iy in 0 until 16) for (ix in 0 until 16) {
            b.put("KNCM".toByteArray()); b.putInt(128)
            val d = b.position()
            b.putInt(d, 0); b.putInt(d + 4, ix); b.putInt(d + 8, iy); b.putInt(d + 0x34, area(ix, iy))
            b.position(d + 128)
        }
        return b.array()
    }
    private val areas = mapOf(10 to PlaceNames.Area("Coastland", 0), 20 to PlaceNames.Area("Old Harbor", 10), 30 to PlaceNames.Area("Gull Bay", 10))

    @Test fun chunksAndTheirAreas() {
        val a = PlaceNames.chunkAreas(adt { ix, iy -> if (ix == 3 && iy == 5) 20 else 10 })
        assertEquals(256, a.size); assertEquals(20, a[PlaceNames.chunk(3, 5)]); assertEquals(10, a[PlaceNames.chunk(0, 0)])
    }

    @Test fun theSubzoneHereElseTheNearestElseTheZone() {
        // a 64 x 64 grid of chunks around the spot: the spot's own chunk is open ground (the zone), a subzone 3 chunks east
        val c = PlaceNames.CHUNK_YD
        fun at(sub: Map<Pair<Int, Int>, Int>): (Double, Double) -> Int? = { wx, wy ->
            val dx = Math.round(-wy / c).toInt(); val dy = Math.round(-wx / c).toInt()     // east = -wy, south = -wx
            sub[dx to dy] ?: 10
        }
        assertEquals("Old Harbor", PlaceNames.nameAt(0.0, 0.0, areas, at(mapOf((0 to 0) to 20))))
        assertEquals("Gull Bay", PlaceNames.nameAt(0.0, 0.0, areas, at(mapOf((3 to 0) to 30, (6 to 0) to 20))))
        assertEquals("Coastland", PlaceNames.nameAt(0.0, 0.0, areas, at(mapOf((PlaceNames.NEAR_CHUNKS + 2 to 0) to 20))))
        assertNull(PlaceNames.nameAt(0.0, 0.0, areas) { _, _ -> null })
    }

    @Test fun aSpotsTileAndChunk() {
        // the world's middle is tile (32, 32), chunk (0, 0); a chunk east and south moves the chunk
        val m = PlaceNames.locate(-1.0, -1.0)
        assertEquals(32, m[0]); assertEquals(32, m[1]); assertEquals(PlaceNames.chunk(0, 0), m[2])
        val n = PlaceNames.locate(-PlaceNames.CHUNK_YD * 2.5, -PlaceNames.CHUNK_YD * 1.5)
        assertEquals(PlaceNames.chunk(1, 2), n[2])
    }

    @Test fun aTownsMasterFirstThenThePlace() {
        val masters = listOf(TransportRoutes.FlightMaster(0, 100f, 100f, 1, 1, "Harbor Town, Coastland"))
        assertEquals("Harbor Town", TransportRoutes.dockName(masters, 0, 120f, 90f) { _, _, _ -> "Gull Bay" })
        assertEquals("Gull Bay", TransportRoutes.dockName(masters, 0, 3000f, 3000f) { _, _, _ -> "Gull Bay" })
    }
}
