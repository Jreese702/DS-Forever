package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** cp27 (the user on test65: one checkpoint look everywhere): the add-on's own pictures read for the bottom map, and the spec's rules */
class MarkerArtTest {
    private fun tga(w: Int, h: Int, topDown: Boolean, px: (Int, Int) -> IntArray): ByteArray {
        val out = ByteArray(18 + w * h * 4)
        out[2] = 2; out[12] = w.toByte(); out[14] = h.toByte(); out[16] = 32; out[17] = (8 or if (topDown) 0x20 else 0).toByte()
        var o = 18
        for (row in 0 until h) { val y = if (topDown) row else h - 1 - row
            for (x in 0 until w) { val (b, g, r, a) = px(x, y).toList(); out[o] = b.toByte(); out[o + 1] = g.toByte(); out[o + 2] = r.toByte(); out[o + 3] = a.toByte(); o += 4 } }
        return out
    }

    @Test fun aBottomUpTgaIsReadTopRowFirst() {
        // 2 x 2: the top-left red, the bottom-right blue half-clear
        val b = tga(2, 2, topDown = false) { x, y -> when { x == 0 && y == 0 -> intArrayOf(0, 0, 255, 255); x == 1 && y == 1 -> intArrayOf(255, 0, 0, 128); else -> intArrayOf(0, 0, 0, 0) } }
        val (w, h, px) = MarkerArt.decode(b)!!
        assertEquals(2, w); assertEquals(2, h)
        assertEquals(0xFFFF0000.toInt(), px[0])
        assertEquals(0x800000FF.toInt(), px[3])
        // the same picture stored top-down reads the same
        assertEquals(px.toList(), MarkerArt.decode(tga(2, 2, topDown = true) { x, y -> when { x == 0 && y == 0 -> intArrayOf(0, 0, 255, 255); x == 1 && y == 1 -> intArrayOf(255, 0, 0, 128); else -> intArrayOf(0, 0, 0, 0) } })!!.third.toList())
        assertNull(MarkerArt.decode(ByteArray(10)))
    }

    @Test fun theShippedPinAndDiscRead() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "companion/app/src/main/assets").isDirectory } ?: return
        for ((path, size) in listOf(MarkerArt.PIN to 128, MarkerArt.DISC to 64, MarkerArt.DIAMOND to 128)) {
            val d = MarkerArt.decode(File(root, "companion/app/src/main/assets/$path").readBytes())
            assertNotNull(path, d); assertEquals(size, d!!.first); assertEquals(size, d.second)
        }
    }

    @Test fun theNearerOneFadesFrom25YardsToNothingAtTheTenYardPass() {
        assertEquals(1f, MarkerArt.nearAlpha(80.0), 0f)
        assertEquals(1f, MarkerArt.nearAlpha(25.0), 0f)                // test68: full until 25 yd
        assertEquals(0.5f, MarkerArt.nearAlpha(17.5), 1e-6f)
        assertEquals(0f, MarkerArt.nearAlpha(DangerGrid.PASS_YD), 0f)  // test70: gone at 10 yd, exactly when passed
        assertEquals(0f, MarkerArt.nearAlpha(0.0), 0f)
        assertEquals(0.8f, MarkerArt.SECOND_SIZE, 0f); assertEquals(0.75f, MarkerArt.SECOND_ALPHA, 0f)
    }
}
