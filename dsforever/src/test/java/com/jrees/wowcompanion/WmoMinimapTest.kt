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
        assertEquals("""{"wmo":53,"groups":{"3":[[1,3,209958],[2,3,209957]]}}""", WmoMinimap.toJson(53, by.getValue(53)).toString())
    }
}
