package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** b-interiors-game-art: the table's rows by building, a root's WMO id from its MOHD header */
class WmoMinimapTest {
    private fun le(vararg v: Int) = ByteArray(v.size * 4) { i -> (v[i / 4] ushr (8 * (i % 4))).toByte() }
    private fun chunk(magic: String, body: ByteArray) = magic.toByteArray(Charsets.ISO_8859_1) + le(body.size) + body

    @Test fun theHeaderGivesTheId() {
        // MVER, then MOHD: textures, groups 281, portals, lights, doodad names, doodad defs, doodad sets, ambient, the WMO id 61, ...
        val root = chunk("REVM", le(17)) + chunk("DHOM", le(10, 281, 3, 4, 5, 6, 7, 0, 61, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(61, WmoMinimap.wmoId(root)); assertEquals(281, WmoMinimap.groupCount(root))
        assertNull(WmoMinimap.wmoId(chunk("REVM", le(17))))
        assertNull(WmoMinimap.wmoId(ByteArray(3)))
    }

    @Test fun rowsByBuildingWithTheirBlocks() {
        val rows = listOf(Db2.Row(1, arrayOf(0, 0, 256, 209953), 52), Db2.Row(2, arrayOf(3, 2, 259, 209957), 53),
            Db2.Row(3, arrayOf(3, 1, 259, 209958), 53), Db2.Row(4, arrayOf(1, 0, 256, 5), null))
        val by = WmoMinimap.byWmo(rows)
        assertEquals(setOf(52, 53), by.keys)
        val t = by.getValue(53)[0]
        assertEquals(3, t.group); assertEquals(2, t.bx); assertEquals(3, t.by)
        assertEquals("""{"wmo":53,"groups":{"3":{"t":[[1,3,209958,0,0],[2,3,209957,0,0]]}}}""", WmoMinimap.toJson(53, by.getValue(53)).toString())
    }

    @Test fun aTileLiesFromTheGroupsCornerAtTwoPixelsAYard() {
        val bb = floatArrayOf(-10f, 20f, 0f, 175.6f, 204.7f, 12f)
        // Ironforge's group 65 as measured: 2 x 2 blocks of 256; block (1, 0) starts 128 yd on in x
        assertEquals(listOf(118.0, 20.0, 246.0, 148.0), WmoMinimap.tileRect(bb, 1, 0, 256, 256).toList())
        assertEquals(listOf(-10.0, 148.0, 6.0, 180.0), WmoMinimap.tileRect(bb, 0, 1, 32, 64).toList())
    }

    @Test fun groupBoxesFromMogi() {
        fun f(v: Float) = java.lang.Float.floatToIntBits(v)
        val mogi = le(8, f(1f), f(2f), f(3f), f(4f), f(5f), f(6f), -1) + le(0, f(-1f), f(-2f), f(-3f), f(7f), f(8f), f(9f), 0)
        val root = chunk("REVM", le(17)) + chunk("IGOM", mogi)
        val b = WmoMinimap.groupBoxes(root)
        assertEquals(2, b.size); assertEquals(listOf(-1f, -2f, -3f, 7f, 8f, 9f), b[1].toList())
    }

    @Test fun groupsGoToTheStoreyTheirFloorIsOn() {
        val bands = listOf(floatArrayOf(-0.2f, 4f), floatArrayOf(4f, 9f))
        assertEquals(listOf(0, 1, 0, 1), WmoMinimap.assign(floatArrayOf(0.1f, 5f, -3f, 30f), bands).toList())
        assertEquals(listOf(-1), WmoMinimap.assign(floatArrayOf(1f), emptyList()).toList())
    }

    @Test fun theDeviceFileReadsBack() {
        val tiles = listOf(WmoMinimap.Tile(2, 0, 0, 11), WmoMinimap.Tile(2, 1, 0, 12), WmoMinimap.Tile(5, 0, 0, 13))
        val boxes = List(6) { floatArrayOf(0f, 0f, it.toFloat(), 10f, 10f, it + 3f) }
        // texture 13 unreadable: its tile (and so group 5) is left out
        val o = WmoMinimap.toJson(9, tiles, boxes) { if (it == 13) null else intArrayOf(256, 128) }
        val g = WmoMinimap.groupsOf(o)
        assertEquals(1, g.size); assertEquals(2, g[0].index); assertEquals(2f, g[0].bb[2])
        assertEquals(listOf(1, 0, 12, 256, 128), g[0].tiles[1].toList())
    }
}
