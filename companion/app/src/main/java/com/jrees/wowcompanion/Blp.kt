package com.jrees.wowcompanion

/**
 * THE GAME'S TEXTURE FORMAT, decoded on the device (Home, 2026-10-02; the harvest: the companion's pictures are built from the user's
 * own game files). BLP2, the top mip only: palettized (alpha 0 / 1 / 4 / 8 bits), DXT1 / DXT3 / DXT5, and raw BGRA. Pure Kotlin, no
 * android.* (tools/harvest_test runs it against what our PC tools' Pillow decode produced).
 */
object Blp {
    class Image(val w: Int, val h: Int, val argb: IntArray)

    /** null when [b] is not a BLP2 file this decoder knows */
    fun decode(b: ByteArray): Image? {
        if (b.size < 148 || b[0] != 'B'.code.toByte() || b[1] != 'L'.code.toByte() || b[2] != 'P'.code.toByte() || b[3] != '2'.code.toByte())
            return null
        val enc = b[8].toInt() and 0xFF
        val alphaBits = b[9].toInt() and 0xFF
        val alphaType = b[10].toInt() and 0xFF
        val w = u32(b, 12); val h = u32(b, 16)
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
        val off = u32(b, 20); val size = u32(b, 84)
        if (off <= 0 || size <= 0 || off + size > b.size) return null
        return when (enc) {
            1 -> palette(b, w, h, off, alphaBits)
            2 -> dxt(b, w, h, off, alphaBits, alphaType)
            3 -> bgra(b, w, h, off)
            else -> null
        }
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun palette(b: ByteArray, w: Int, h: Int, off: Int, alphaBits: Int): Image? {
        val n = w * h
        if (off + n > b.size || 148 + 1024 > b.size) return null
        val out = IntArray(n)
        val a0 = off + n
        for (i in 0 until n) {
            val p = 148 + 4 * (b[off + i].toInt() and 0xFF)
            val rgb = ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)
            val a = when (alphaBits) {
                1 -> if (a0 + i / 8 < b.size && (b[a0 + i / 8].toInt() shr (i % 8)) and 1 == 1) 255 else 0
                4 -> if (a0 + i / 2 < b.size) { val v = (b[a0 + i / 2].toInt() shr ((i % 2) * 4)) and 0xF; v * 17 } else 255
                8 -> if (a0 + i < b.size) b[a0 + i].toInt() and 0xFF else 255
                else -> 255
            }
            out[i] = (a shl 24) or rgb
        }
        return Image(w, h, out)
    }

    private fun bgra(b: ByteArray, w: Int, h: Int, off: Int): Image? {
        val n = w * h
        if (off + 4 * n > b.size) return null
        val out = IntArray(n)
        for (i in 0 until n) {
            val p = off + 4 * i
            out[i] = ((b[p + 3].toInt() and 0xFF) shl 24) or ((b[p + 2].toInt() and 0xFF) shl 16) or
                ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)
        }
        return Image(w, h, out)
    }

    /** RGB565 -> 8-bit channels */
    private fun r5(c: Int) = ((c shr 11) and 31).let { (it shl 3) or (it shr 2) }
    private fun g6(c: Int) = ((c shr 5) and 63).let { (it shl 2) or (it shr 4) }
    private fun b5(c: Int) = (c and 31).let { (it shl 3) or (it shr 2) }

    private fun dxt(b: ByteArray, w: Int, h: Int, off: Int, alphaBits: Int, alphaType: Int): Image? {
        // Pillow's BLP plugin: alpha type 0 = DXT1, 1 = DXT3, 7 = DXT5
        val kind = when (alphaType) { 1 -> 3; 7 -> 5; else -> 1 }
        val blockBytes = if (kind == 1) 8 else 16
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        if (off + bw * bh * blockBytes > b.size) return null
        val out = IntArray(w * h)
        val col = IntArray(4); val alp = IntArray(8)
        var p = off
        for (by in 0 until bh) for (bx in 0 until bw) {
            var aBlock = -1
            if (kind == 3 || kind == 5) { aBlock = p; p += 8 }
            val c0 = u16(b, p); val c1 = u16(b, p + 2)
            val idx = u32(b, p + 4)
            p += 8
            val r0 = r5(c0); val g0 = g6(c0); val bl0 = b5(c0)
            val r1 = r5(c1); val g1 = g6(c1); val bl1 = b5(c1)
            col[0] = (255 shl 24) or (r0 shl 16) or (g0 shl 8) or bl0
            col[1] = (255 shl 24) or (r1 shl 16) or (g1 shl 8) or bl1
            if (kind != 1 || c0 > c1) {
                col[2] = (255 shl 24) or (((2 * r0 + r1) / 3) shl 16) or (((2 * g0 + g1) / 3) shl 8) or ((2 * bl0 + bl1) / 3)
                col[3] = (255 shl 24) or (((r0 + 2 * r1) / 3) shl 16) or (((g0 + 2 * g1) / 3) shl 8) or ((bl0 + 2 * bl1) / 3)
            } else {
                col[2] = (255 shl 24) or (((r0 + r1) / 2) shl 16) or (((g0 + g1) / 2) shl 8) or ((bl0 + bl1) / 2)
                col[3] = if (alphaBits > 0) 0 else (255 shl 24)          // DXT1's fourth colour: transparent when the BLP has alpha
            }
            var abits = 0L
            if (kind == 5) {
                val a0 = b[aBlock].toInt() and 0xFF; val a1 = b[aBlock + 1].toInt() and 0xFF
                alp[0] = a0; alp[1] = a1
                if (a0 > a1) for (k in 1..6) alp[k + 1] = ((7 - k) * a0 + k * a1) / 7
                else { for (k in 1..4) alp[k + 1] = ((5 - k) * a0 + k * a1) / 5; alp[6] = 0; alp[7] = 255 }
                for (k in 0 until 6) abits = abits or ((b[aBlock + 2 + k].toLong() and 0xFF) shl (8 * k))
            }
            for (py in 0 until 4) for (px in 0 until 4) {
                val x = bx * 4 + px; val y = by * 4 + py
                if (x >= w || y >= h) continue
                val i = py * 4 + px
                var c = col[(idx ushr (2 * i)) and 3]
                if (kind == 3) {
                    val v = (b[aBlock + i / 2].toInt() shr ((i % 2) * 4)) and 0xF
                    c = (c and 0xFFFFFF) or ((v * 17) shl 24)
                } else if (kind == 5) {
                    c = (c and 0xFFFFFF) or (alp[((abits ushr (3 * i)) and 7).toInt()] shl 24)
                }
                out[y * w + x] = c
            }
        }
        return Image(w, h, out)
    }
}
