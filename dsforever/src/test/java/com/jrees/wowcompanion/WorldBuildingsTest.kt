package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** b-route-data-all: every placed building from the game's own files (made-up bytes here, no game data) */
class WorldBuildingsTest {
    private fun chunk(tag: String, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(tag.reversed().toByteArray()); b.putInt(body.size); b.put(body)
        return b.array()
    }

    /** one MODF entry: fileDataID 77, uid 5, at map position (x, y, z), yaw [ry] degrees */
    private fun modf(px: Float, py: Float, pz: Float, ry: Float, flags: Int = 8): ByteArray {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(77); b.putInt(5); b.putFloat(px); b.putFloat(py); b.putFloat(pz); b.putFloat(0f); b.putFloat(ry); b.putFloat(0f)
        repeat(6) { b.putFloat(0f) }
        b.putShort(flags.toShort()); b.putShort(0); b.putShort(0); b.putShort(1024)
        return b.array()
    }

    private fun mogi(vararg groups: Pair<Int, FloatArray>): ByteArray {
        val b = ByteBuffer.allocate(32 * groups.size).order(ByteOrder.LITTLE_ENDIAN)
        for ((fl, box) in groups) { b.putInt(fl); box.forEach { b.putFloat(it) }; b.putInt(0) }
        return b.array()
    }

    @Test fun placementsInTheInteriorsConvention() {
        val p = WorldBuildings.placements(chunk("MVER", ByteArray(4)) + chunk("MODF", modf(17066.666f - 200f, 30f, 17066.666f - 100f, 90f)))
        assertEquals(1, p.size)
        assertEquals(100.0, p[0].ox, 0.01); assertEquals(200.0, p[0].oy, 0.01); assertEquals(30.0, p[0].oz, 0.01)
        assertEquals(Math.PI / 2, p[0].yaw, 1e-6)
        // no fileDataID flag: left out
        assertTrue(WorldBuildings.placements(chunk("MODF", modf(0f, 0f, 0f, 0f, flags = 0))).isEmpty())
    }

    @Test fun roomsOnTheGroundAreWallsMinesAndStreetsAreNot() {
        val house = floatArrayOf(-10f, -6f, 0f, 10f, 6f, 8f)           // a room 20 x 12 yd, floor at the origin
        val mine = floatArrayOf(-30f, -30f, -40f, 30f, 30f, -10f)       // deep under the hill
        val street = floatArrayOf(-50f, -5f, 0f, 50f, 5f, 2f)           // outdoor
        val groups = WorldBuildings.groups(chunk("MOHD", ByteArray(8)) + chunk("MOGI", mogi(0x2000 to house, 0x2000 to mine, 0x8 to street)))
        assertEquals(3, groups.size); assertTrue(groups[0].interior); assertTrue(groups[2].exterior)
        val p = WorldBuildings.Placement(77, 5, 100.0, 200.0, 30.0, 0.0, 1.0)
        val feet = WorldBuildings.feet(p, groups) { _, _ -> 30.0 }      // the ground at the origin's height
        assertEquals(1, feet.size)                                        // the house's room only
        assertTrue(feet[0].holds(100.0, 200.0)); assertFalse(feet[0].holds(100.0, 230.0))
        assertEquals(1, WorldBuildings.walkways(p, groups).size)          // the street: level ground
        // a city (wider than CITY_YD): no rooms as walls, all of it level
        val city = groups + WorldBuildings.Group(true, floatArrayOf(-400f, -400f, 0f, 400f, 400f, 30f))
        assertTrue(WorldBuildings.feet(p, city) { _, _ -> 30.0 }.isEmpty())
        assertEquals(4, WorldBuildings.walkways(p, city).size)
    }
}
