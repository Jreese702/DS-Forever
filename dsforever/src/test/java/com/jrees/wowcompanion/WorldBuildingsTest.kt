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

    // ---------------------------------------------------------------- M2 bridges (b-safepath-water: Duskwood's covered bridge)

    /** a made-up chunked M2: an MD21 holding an MD20 header named [name], its collision mesh a flat deck 6 x 32 yd at height 5 (two
     *  faces) and one upright face (a wall, never floor); [deckY] the deck's half length */
    private fun m2(name: String, deckY: Float = 16f): ByteArray {
        val verts = listOf(floatArrayOf(-3f, -deckY, 5f), floatArrayOf(3f, -deckY, 5f), floatArrayOf(3f, deckY, 5f), floatArrayOf(-3f, deckY, 5f),
            floatArrayOf(-3f, -deckY, 0f), floatArrayOf(-3f, deckY, 0f), floatArrayOf(-3f, deckY, 10f))
        val idx = shortArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6)
        val nameAt = 0x100; val vAt = nameAt + 64; val iAt = vAt + verts.size * 12
        val md = ByteBuffer.allocate(iAt + idx.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        md.put("MD20".toByteArray()); md.putInt(272); md.putInt(name.length + 1); md.putInt(nameAt)
        md.position(0xD8); md.putInt(idx.size); md.putInt(iAt); md.putInt(verts.size); md.putInt(vAt)
        md.position(nameAt); md.put(name.toByteArray()); md.put(0)
        md.position(vAt); for (v in verts) v.forEach { md.putFloat(it) }
        md.position(iAt); for (i in idx) md.putShort(i)
        val body = md.array()
        val b = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("MD21".toByteArray()); b.putInt(body.size); b.put(body)
        return b.array()
    }

    /** one MDDF entry: fileDataID [fid] (flag 0x40), uid 9, at map position (x, y, z), yaw [ry], tilt [rx] degrees */
    private fun mddf(fid: Int, px: Float, py: Float, pz: Float, ry: Float, rx: Float = 0f, flags: Int = 0x40): ByteArray {
        val b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(fid); b.putInt(9); b.putFloat(px); b.putFloat(py); b.putFloat(pz); b.putFloat(rx); b.putFloat(ry); b.putFloat(0f)
        b.putShort(1024); b.putShort(flags.toShort())
        return b.array()
    }

    @Test fun doodadsInTheSameFrameAsTheBuildings() {
        val d = WorldBuildings.doodads(chunk("MVER", ByteArray(4)) + chunk("MDDF", mddf(189417, 17066.666f - 200f, 30f, 17066.666f - 100f, 90f) +
            mddf(5, 0f, 0f, 0f, 0f, rx = 30f) + mddf(6, 0f, 0f, 0f, 0f, flags = 0)))
        assertEquals(1, d.size)                              // the tilted one and the one without a fileDataID left out
        assertEquals(189417, d[0].fid); assertEquals(100.0, d[0].ox, 0.01); assertEquals(200.0, d[0].oy, 0.01); assertEquals(30.0, d[0].oz, 0.01)
        assertEquals(Math.PI / 2, d[0].yaw, 1e-6); assertEquals(1.0, d[0].scale, 1e-9)
    }

    @Test fun anM2BridgeIsPickedByItsOwnModelName() {
        assertEquals("DuskwoodCoveredBridge", WorldBuildings.m2Name(m2("DuskwoodCoveredBridge")))
        for (n in listOf("DuskwoodCoveredBridge", "SmallDock", "RedRidgeDocksBoardwalk01", "ThousandNeedlesBridge", "ElvenWalkway")) assertTrue(n, WorldBuildings.isM2Bridge(n))
        for (n in listOf("ElwynnTree01", "Barrel", "")) assertFalse(n, WorldBuildings.isM2Bridge(n))
        assertTrue(WorldBuildings.m2BridgeFloor(m2("DuskwoodCoveredBridge")) != null)
        assertTrue(WorldBuildings.m2BridgeFloor(m2("ElwynnTree01")) == null)
        // a brick named for its bridge (RedRidgeBridgeBrick01, 3 yd) is no deck
        assertTrue(WorldBuildings.m2BridgeFloor(m2("RedRidgeBridgeBrick01", deckY = 1f)) == null)
        // a model with no name (WoW Forever's new piers) whose floor is one flat board 32 yd long is a deck
        assertTrue(WorldBuildings.m2BridgeFloor(m2("")) != null)
    }

    @Test fun anM2BridgesDeckIsItsCollisionFloor() {
        val t = WorldBuildings.m2FloorTris(m2("DuskwoodCoveredBridge"))
        assertEquals(2 * 9, t.size)                          // the two flat faces; the upright one is a wall
        val p = WorldBuildings.Placement(1, 1, 100.0, 200.0, 30.0, 0.0, 1.0)
        val d = WorldBuildings.m2Deck(p, m2("DuskwoodCoveredBridge"))!!
        // yaw 0: world x = ox - lx, y = oy - ly (as a WMO's)
        assertTrue(d.holds(100.0, 200.0)); assertTrue(d.holds(102.0, 185.0)); assertTrue(d.holds(98.0, 215.0))
        assertFalse(d.holds(110.0, 200.0)); assertFalse(d.holds(100.0, 230.0))
        // its floor's height: the placement's own plus the model's (5), the lowest at or over the water's surface asked for
        assertEquals(35.0, d.heightAt(100.0, 200.0, 20.0), 1e-6)
        assertTrue(d.heightAt(100.0, 200.0, 36.0).isNaN()); assertTrue(d.heightAt(110.0, 200.0).isNaN())
        // turned a quarter: the deck runs along world x
        val q = WorldBuildings.m2Deck(WorldBuildings.Placement(1, 1, 100.0, 200.0, 30.0, Math.PI / 2, 1.0), m2("DuskwoodCoveredBridge"))!!
        assertTrue(q.holds(115.0, 200.0)); assertFalse(q.holds(100.0, 215.0))
        // the doodads by the water only, their models' own names deciding
        val obj = chunk("MDDF", mddf(1, 17066.666f - 200f, 30f, 17066.666f - 100f, 0f) + mddf(2, 17066.666f - 900f, 30f, 17066.666f - 900f, 0f))
        val read = HashMap<Int, Int>()
        val decks = WorldBuildings.m2Decks(obj, { x, _ -> x < 500 }) { fid -> read[fid] = (read[fid] ?: 0) + 1; WorldBuildings.m2BridgeFloor(m2("DuskwoodCoveredBridge")) }
        assertEquals(1, decks.size); assertEquals(setOf(1), read.keys)       // the one far from the water never read
        assertTrue(decks[0].holds(100.0, 200.0))
    }

    /** a tilted placement (MODF rotation x / z) turns the model about its own axes; level ones keep the yaw-only formula */
    @Test fun tiltedPlacementsTurnTheirFloors() {
        val yaw = Math.toRadians(158.5)
        val level = WorldBuildings.Placement(1, 1, 1921.0, -729.0, 110.0, yaw, 1.0)
        val w = level.world(-400.0, 10.0, 2.0)
        assertEquals(1921.0 + 400.0 * Math.cos(yaw) + 10.0 * Math.sin(yaw), w[0], 1e-9)
        assertEquals(-729.0 + 400.0 * Math.sin(yaw) - 10.0 * Math.cos(yaw), w[1], 1e-9)
        assertEquals(112.0, w[2], 1e-9)
        // pitched 3 degrees about the model's y axis (MODF rotation x = -3): the tunnel's far end 400 yd out sinks 20.9 yd
        val tunnel = WorldBuildings.Placement(1, 1, 1921.0, -729.0, 110.0, yaw, 1.0, rx = Math.toRadians(-3.0))
        val far = tunnel.world(-400.0, 0.0, 0.0)
        assertEquals(110.0 - 400.0 * Math.sin(Math.toRadians(3.0)), far[2], 1e-6)
        assertEquals(1921.0 + 400.0 * Math.cos(Math.toRadians(3.0)) * Math.cos(yaw), far[0], 1e-6)
        // rolled about the model's x axis (rotation z): a point off to the side rises or sinks, one on the axis stays
        val rolled = WorldBuildings.Placement(1, 1, 0.0, 0.0, 0.0, 0.0, 1.0, rz = Math.toRadians(10.0))
        assertEquals(0.0, rolled.world(5.0, 0.0, 0.0)[2], 1e-9)
        assertEquals(10.0 * Math.sin(Math.toRadians(10.0)), Math.abs(rolled.world(0.0, 10.0, 0.0)[2]), 1e-9)
        // the distances are kept (a rotation)
        val a = tunnel.world(3.0, -4.0, 12.0); val o = tunnel.world(0.0, 0.0, 0.0)
        assertEquals(13.0, Math.sqrt((a[0] - o[0]) * (a[0] - o[0]) + (a[1] - o[1]) * (a[1] - o[1]) + (a[2] - o[2]) * (a[2] - o[2])), 1e-9)
    }
}
