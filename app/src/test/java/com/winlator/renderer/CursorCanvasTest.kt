package com.winlator.renderer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Crash 11-12: every cursor must reach the native renderer at ONE fixed size (no texture recreate mid-record). */
class CursorCanvasTest {
    private fun img(w: Int, h: Int, stride: Int = w): ByteBuffer {
        val b = ByteBuffer.allocateDirect(stride * h * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until stride) b.putInt((y * stride + x) * 4, if (x < w) (0xFF000000.toInt() or (y shl 8) or x) else 0x7F7F7F7F)
        return b
    }

    private fun px(b: ByteBuffer, x: Int, y: Int) = b.order(ByteOrder.LITTLE_ENDIAN).getInt((y * CursorCanvas.SIZE + x) * 4)

    @Test fun smallCursorTopLeftRestTransparent() {
        val c = CursorCanvas()
        val out = c.fill(img(16, 16), 16, 16, 16)!!
        assertEquals(CursorCanvas.SIZE * CursorCanvas.SIZE * 4, out.capacity())
        assertEquals(0, out.position())
        assertEquals(0xFF000000.toInt() or (3 shl 8) or 5, px(out, 5, 3))
        assertEquals(0, px(out, 16, 0))
        assertEquals(0, px(out, 0, 16))
        assertEquals(0, px(out, CursorCanvas.SIZE - 1, CursorCanvas.SIZE - 1))
    }

    @Test fun sizeChangeReusesSameBufferAndClearsOldPixels() {
        val c = CursorCanvas()
        val a = c.fill(img(32, 32), 32, 32, 32)
        val b = c.fill(img(16, 16), 16, 16, 16)
        assertEquals(System.identityHashCode(a), System.identityHashCode(b))
        assertEquals(0, px(b!!, 20, 20)) // old 32x32 pixel cleared
    }

    @Test fun strideRespected() {
        val src = img(10, 4, 16)
        assertEquals(16, CursorCanvas.strideOf(src, 10, 4))
        val out = CursorCanvas().fill(src, 10, 4, 16)!!
        assertEquals(0xFF000000.toInt() or (2 shl 8) or 9, px(out, 9, 2))
        assertEquals(0, px(out, 10, 2)) // stride padding not copied
    }

    @Test fun largeCursorClipped() {
        val n = CursorCanvas.SIZE + 20
        val out = CursorCanvas().fill(img(n, n), n, n, n)!!
        val e = CursorCanvas.SIZE - 1
        assertEquals(0xFF000000.toInt() or (e shl 8) or e, px(out, e, e))
    }

    @Test fun badInputsRejected() {
        val c = CursorCanvas()
        assertNull(c.fill(null, 16, 16, 16))
        assertNull(c.fill(img(4, 4), 0, 4, 4))
        assertNull(c.fill(img(4, 4), 8, 8, 8)) // buffer too small
        assertNotNull(c.fill(img(4, 4), 4, 4, 4))
    }
}
