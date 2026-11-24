package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1526 follow-up (the user: "Will it show black when I zoom out or the mountain?"): zoomed out past IndoorCityArt.TILES_MAX tiles the open
 * map drew none of the city's art. IndoorCityArt.Mosaic lays the floor's tiles into one picture at the view's level of detail, within a
 * memory bound fit for an 8 GB Thor. Pure: the drawing side is HomeIndoorMapsTest (app).
 */
class IndoorCityMosaicTest {
    /** a floor of [n] x [m] groups of [yd] yards, one tile each of [px] pixels (a tile covers px / 2 yards) */
    private fun floor(n: Int, m: Int, yd: Double, px: Int, x0: Double = 0.0, y0: Double = 0.0): List<WmoMinimap.Group> =
        (0 until n * m).map { k ->
            val i = k % n; val j = k / n
            val gx = (x0 + i * yd).toFloat(); val gy = (y0 + j * yd).toFloat()
            WmoMinimap.Group(k, floatArrayOf(gx, gy, -5f, gx + yd.toFloat(), gy + yd.toFloat(), 80f), listOf(intArrayOf(0, 0, 5000 + k, px, px)))
        }

    @Test fun theLevelFollowsTheView() {
        assertEquals("a close view: a half-size tile's own, never more", 1.0, IndoorCityArt.Mosaic.lod(5.0), 0.0)
        assertEquals(1.0, IndoorCityArt.Mosaic.lod(1.5), 0.0)
        assertEquals(1.0, IndoorCityArt.Mosaic.lod(1.0), 0.0)
        assertEquals("rounded up to a power of two", 0.5, IndoorCityArt.Mosaic.lod(0.4), 0.0)
        assertEquals(0.25, IndoorCityArt.Mosaic.lod(0.2), 0.0)
        assertEquals("a continent in view", IndoorCityArt.Mosaic.MIN_PX_PER_YD, IndoorCityArt.Mosaic.lod(0.001), 0.0)
    }

    /** a whole capital's floor (Ironforge's groups span about 635 x 645 yd, the Undercity's 636 x 745: the PC's art set) is small */
    @Test fun aWholeCitysFloorIsWithinItsMemoryBound() {
        for ((name, dx, dy) in listOf(Triple("Ironforge", 640.0, 640.0), Triple("Undercity", 640.0, 768.0))) {
            val g = floor((dx / 64).toInt(), (dy / 64).toInt(), 64.0, 128)
            val l = IndoorCityArt.Mosaic.layout(g, g.indices.toList(), 1.0)!!
            assertTrue("$name: ${l.bytes} bytes", l.bytes <= 2L * 1024 * 1024)
            assertEquals("$name: a pixel a yard", 1.0, l.pxPerYd, 0.0)
            assertEquals("$name: its tiles decoded at half size", 2, l.sample)
            assertEquals(Math.ceil(dx).toInt(), l.w); assertEquals(Math.ceil(dy).toInt(), l.h)
            val q = IndoorCityArt.Mosaic.layout(g, g.indices.toList(), 0.25)!!
            assertEquals("$name zoomed further out: an eighth", 8, q.sample)
            assertTrue(q.bytes <= l.bytes / 16 + 4096)
        }
    }

    /** however wide the floor, never more than MAX_SIDE a side nor MAX_BYTES in all */
    @Test fun aHugeFloorIsBounded() {
        val g = floor(40, 40, 128.0, 256)                                     // 5120 yd square
        val l = IndoorCityArt.Mosaic.layout(g, g.indices.toList(), 1.0)!!
        assertTrue("${l.w} x ${l.h}", l.w <= IndoorCityArt.Mosaic.MAX_SIDE && l.h <= IndoorCityArt.Mosaic.MAX_SIDE)
        assertTrue("${l.bytes}", l.bytes <= IndoorCityArt.Mosaic.MAX_BYTES)
        assertTrue(l.pxPerYd < 1.0)
        assertTrue("decoded no bigger than needed (${l.sample})", WmoMinimap.PX_PER_YD / l.sample >= l.pxPerYd &&
            WmoMinimap.PX_PER_YD / (l.sample * 2) < l.pxPerYd)
        // a long thin one: the side bound
        val thin = floor(60, 1, 128.0, 256)
        val t = IndoorCityArt.Mosaic.layout(thin, thin.indices.toList(), 1.0)!!
        assertTrue("${t.w}", t.w <= IndoorCityArt.Mosaic.MAX_SIDE)
        assertTrue(t.bytes <= IndoorCityArt.Mosaic.MAX_BYTES)
    }

    /** each tile lands where it lies: the top row is the high-y side (as a tile's), x to the right; the rect is exactly the pixels' */
    @Test fun theTilesLandInPlace() {
        val g = floor(2, 2, 64.0, 128, x0 = -100.0, y0 = 50.0)
        val l = IndoorCityArt.Mosaic.layout(g, listOf(3, 0, 1, 2), 1.0)!!
        assertEquals(-100.0, l.x0, 1e-9); assertEquals(178.0, l.y1, 1e-9)
        assertEquals(128, l.w); assertEquals(128, l.h)
        assertEquals(l.x0 + l.w / l.pxPerYd, l.x1, 1e-9); assertEquals(l.y1 - l.h / l.pxPerYd, l.y0, 1e-9)
        val c0 = l.cells.first { it.fid == 5000 }                                // low x, low y: bottom-left
        assertEquals(0f, c0.l, 1e-4f); assertEquals(64f, c0.t, 1e-4f); assertEquals(64f, c0.r, 1e-4f); assertEquals(128f, c0.b, 1e-4f)
        val c3 = l.cells.first { it.fid == 5003 }                                // high x, high y: top-right
        assertEquals(64f, c3.l, 1e-4f); assertEquals(0f, c3.t, 1e-4f)
        assertEquals("the groups in index order (their draw order), whatever the pick's", listOf(5000, 5001, 5002, 5003), l.cells.map { it.fid })
        // a tile's slack past its group's box (a texture is the next power of two) is in the picture too
        val slack = listOf(WmoMinimap.Group(1, floatArrayOf(0f, 0f, 0f, 40f, 40f, 9f), listOf(intArrayOf(0, 0, 7, 128, 128))))
        assertEquals(64, IndoorCityArt.Mosaic.layout(slack, listOf(0), 1.0)!!.w)
    }

    @Test fun theNameFollowsTheFloorNotThePlayersGroup() {
        val g = floor(3, 3, 64.0, 128)
        val a = IndoorCityArt.Mosaic.key(7801267, g, listOf(4, 0, 1, 2, 3, 5, 6, 7, 8), 1.0)
        val b = IndoorCityArt.Mosaic.key(7801267, g, listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), 1.0)
        assertEquals("the player's own group first or not: the same picture (no rebuild while walking)", a, b)
        assertTrue("another floor", a != IndoorCityArt.Mosaic.key(7801267, g, listOf(0, 1, 2), 1.0))
        assertTrue("another level", a != IndoorCityArt.Mosaic.key(7801267, g, g.indices.toList(), 0.5))
        assertTrue("another city", a != IndoorCityArt.Mosaic.key(7675285, g, g.indices.toList(), 1.0))
        val other = g.map { WmoMinimap.Group(it.index, it.bb, it.tiles.map { t -> t.copyOf().also { c -> c[2] += 1 } }) }
        assertTrue("new art (the game updated): a new picture", a != IndoorCityArt.Mosaic.key(7801267, other, g.indices.toList(), 1.0))
        assertEquals("the level's part, for another level of the same floor", a.substringBefore('@'),
            IndoorCityArt.Mosaic.key(7801267, g, g.indices.toList(), 0.25).substringBefore('@'))
    }

    @Test fun noTilesNoPicture() {
        assertNull(IndoorCityArt.Mosaic.layout(emptyList(), emptyList(), 1.0))
        val bare = listOf(WmoMinimap.Group(0, floatArrayOf(0f, 0f, 0f, 10f, 10f, 5f), emptyList()))
        assertNull(IndoorCityArt.Mosaic.layout(bare, listOf(0), 1.0))
        assertNull("a pick out of range", IndoorCityArt.Mosaic.layout(bare, listOf(7), 1.0))
        assertNotNull(IndoorCityArt.Mosaic.layout(floor(1, 1, 64.0, 128), listOf(0), 1.0))
        assertTrue(IndoorCityArt.TILES_MAX == 64)
    }
}
