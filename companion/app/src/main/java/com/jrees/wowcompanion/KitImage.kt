package com.jrees.wowcompanion

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * THE KIT HARVEST'S PICTURE TOOLS (UI Art, 2026-10-09; the user: the APK carries no game art, the phone builds it from the user's own game
 * files). The PC tools that made the kit (tools/icons/extract_kit.py and its siblings) used Pillow; these are Pillow's own operations,
 * ported so the phone makes the same pictures: crop (outside = transparent black), Lanczos resize (Pillow's fixed-point resampler, RGBA
 * premultiplied as Pillow does), the grey conversion, alpha compositing, a mask paste. Pure Kotlin, no android.* (tools/harvest_test runs it
 * against Pillow's own results). [px] is ARGB with straight alpha, as [Blp.Image].
 */
class KitImage(val w: Int, val h: Int, val px: IntArray) {

    /** Pillow's crop: the box may reach outside; what is outside is transparent black */
    fun crop(l: Int, t: Int, r: Int, b: Int): KitImage {
        val nw = r - l; val nh = b - t
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy = y + t
            if (sy < 0 || sy >= h) continue
            for (x in 0 until nw) {
                val sx = x + l
                if (sx in 0 until w) out[y * nw + x] = px[sy * w + sx]
            }
        }
        return KitImage(nw, nh, out)
    }

    /** Pillow's convert('RGB'): the alpha dropped (every pixel opaque) */
    fun opaque(): KitImage = KitImage(w, h, IntArray(w * h) { px[it] or (0xFF shl 24) })

    /** Pillow's convert('L') (ITU-R 601-2 luma, Pillow's fixed point; the alpha is ignored) */
    fun gray(): Plane = Plane(w, h, IntArray(w * h) {
        val c = px[it]
        (((c shr 16) and 0xFF) * 19595 + ((c shr 8) and 0xFF) * 38470 + (c and 0xFF) * 7471 + 0x8000) ushr 16
    })

    /**
     * Pillow's resize(..., LANCZOS) on an RGBA image: premultiplied (RGBa), resampled, un-premultiplied. One channel at a time (a loading
     * picture is 3000 px wide); an opaque picture skips the premultiplying and its alpha (Pillow's give the same pixels for it).
     */
    fun resize(nw: Int, nh: Int): KitImage {
        if (nw == w && nh == h) return KitImage(w, h, px.copyOf())
        val opaque = px.all { (it ushr 24) == 255 }
        val out = IntArray(nw * nh)
        val chan = IntArray(w * h)
        for (sh in if (opaque) intArrayOf(16, 8, 0) else intArrayOf(24, 16, 8, 0)) {
            for (i in 0 until w * h) {
                val c = px[i]
                chan[i] = if (opaque || sh == 24) (c shr sh) and 0xFF else mul255((c shr sh) and 0xFF, c ushr 24)
            }
            val r = Lanczos.resample(chan, w, h, nw, nh)
            for (i in 0 until nw * nh) out[i] = out[i] or (r[i] shl sh)
        }
        for (i in 0 until nw * nh) {
            val c = out[i]
            if (opaque) { out[i] = c or (0xFF shl 24); continue }
            val a = c ushr 24
            if (a != 255 && a != 0) {
                out[i] = (a shl 24) or (clip8(255 * ((c shr 16) and 0xFF) / a) shl 16) or (clip8(255 * ((c shr 8) and 0xFF) / a) shl 8) or
                    clip8(255 * (c and 0xFF) / a)
            }
        }
        return KitImage(nw, nh, out)
    }

    /** Pillow's getchannel('A').point(a > [over]).getbbox(): (left, top, right, bottom) or null when nothing is that opaque */
    fun alphaBox(over: Int): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > over) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /** Pillow's Image.alpha_composite(this, top): [top] (same size) over this, in place */
    fun compositeOver(top: KitImage) {
        for (i in 0 until w * h) px[i] = over(px[i], top.px[i])
    }

    /** Pillow's paste(src, (x, y), mask) with an L mask the size of [src], on this RGBA image (every channel blended, alpha too) */
    fun paste(src: KitImage, x0: Int, y0: Int, mask: Plane) {
        for (y in 0 until src.h) {
            val dy = y + y0
            if (dy !in 0 until h) continue
            for (x in 0 until src.w) {
                val dx = x + x0
                if (dx !in 0 until w) continue
                val m = mask.v[y * src.w + x]
                if (m == 0) continue
                val s = src.px[y * src.w + x]; val d = px[dy * w + dx]
                px[dy * w + dx] = if (m == 255) s else {
                    var o = 0
                    for (sh in intArrayOf(24, 16, 8, 0)) o = o or (div255(((d shr sh) and 0xFF) * (255 - m) + ((s shr sh) and 0xFF) * m) shl sh)
                    o
                }
            }
        }
    }

    /** the alpha replaced by [a] (Pillow's putalpha) */
    fun withAlpha(a: Plane): KitImage = KitImage(w, h, IntArray(w * h) { (px[it] and 0xFFFFFF) or (a.v[it] shl 24) })
    fun alpha(): Plane = Plane(w, h, IntArray(w * h) { px[it] ushr 24 })

    companion object {
        fun of(b: Blp.Image) = KitImage(b.w, b.h, b.argb)
        fun blank(w: Int, h: Int) = KitImage(w, h, IntArray(w * h))

        fun clip8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
        /** Pillow's MULDIV255 (premultiplying) */
        fun mul255(a: Int, b: Int): Int { val t = a * b + 128; return ((t shr 8) + t) shr 8 }
        /** Pillow's DIV255 */
        fun div255(a: Int): Int { val t = a + 128; return ((t shr 8) + t) shr 8 }

        /** one pixel of Pillow's alpha_composite (AlphaComposite.c, 7 bits of precision): [src] over [dst] */
        fun over(dst: Int, src: Int): Int {
            val sa = src ushr 24
            if (sa == 0) return dst
            val da = dst ushr 24
            val blend = da * (255 - sa)
            val outa255 = sa * 255 + blend
            val coef1 = ((sa.toLong() * 255 * 255 * 128) / outa255).toInt()
            val coef2 = 255 * 128 - coef1
            fun ch(sh: Int): Int {
                val t = ((src shr sh) and 0xFF) * coef1 + ((dst shr sh) and 0xFF) * coef2 + (0x80 shl 7)
                return (((t shr 8) + t) shr 8) shr 7
            }
            val a = (outa255 + 0x80).let { ((it shr 8) + it) shr 8 }
            return (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** an 8-bit single channel (Pillow's mode L), values 0..255 */
class Plane(val w: Int, val h: Int, val v: IntArray) {
    /** Pillow's resize(..., LANCZOS) on an L image */
    fun resize(nw: Int, nh: Int): Plane = if (nw == w && nh == h) Plane(w, h, v.copyOf()) else Plane(nw, nh, Lanczos.resample(v, w, h, nw, nh))

    companion object {
        /**
         * Pillow's ImageDraw.ellipse(box, fill=...) on a w x h mask of [ground], the box given as floats (Pillow truncates them to int):
         * Draw.c's ellipseNew, its quarter-ellipse walk on the doubled grid and its horizontal spans
         */
        fun ellipse(w: Int, h: Int, x0f: Double, y0f: Double, x1f: Double, y1f: Double, fill: Int = 255, ground: Int = 0): Plane {
            val x0 = x0f.toInt(); val y0 = y0f.toInt(); val x1 = x1f.toInt(); val y1 = y1f.toInt()
            val out = IntArray(w * h) { ground }
            val a = x1 - x0; val b = y1 - y0
            if (a < 0 || b < 0) return Plane(w, h, out)
            PilEllipse(a, b, a + b).spans { sx0, sy, sx1 ->
                val y = y0 + (sy + b) / 2
                if (y in 0 until h) for (x in maxOf(0, x0 + (sx0 + a) / 2)..minOf(w - 1, x0 + (sx1 + a) / 2)) out[y * w + x] = fill
            }
            return Plane(w, h, out)
        }
    }
}

/** Pillow's ellipse walk (Draw.c: quarter_init / quarter_next, ellipse_init / ellipse_next), on the doubled integer grid */
private class PilEllipse(a: Int, b: Int, w: Int) {
    private class Quarter(a: Int, b: Int) {
        var finished = a < 0 || b < 0
        var cx = a; var cy = if (finished) 0 else b % 2
        val ex = if (finished) 0 else a % 2; val ey = b
        val a2 = a.toLong() * a; val b2 = b.toLong() * b; val a2b2 = a2 * b2

        private fun delta(x: Long, y: Long) = Math.abs(a2 * y * y + b2 * x * x - a2b2)

        /** the next point (x, y), or null when the quarter is done */
        fun next(): IntArray? {
            if (finished) return null
            val r = intArrayOf(cx, cy)
            if (cx == ex && cy == ey) finished = true
            else {
                var nx = cx; var ny = cy + 2
                var nd = delta(nx.toLong(), ny.toLong())
                if (nx > 1) {
                    var d = delta((cx - 2).toLong(), (cy + 2).toLong())
                    if (nd > d) { nx = cx - 2; ny = cy + 2; nd = d }
                    d = delta((cx - 2).toLong(), cy.toLong())
                    if (nd > d) { nx = cx - 2; ny = cy }
                }
                cx = nx; cy = ny
            }
            return r
        }
    }

    private val leftmost = a % 2
    private val o = Quarter(a, b)
    private var finished: Boolean
    private var py = 0; private var pl = 0; private var pr = 0
    private lateinit var i: Quarter

    init {
        val first = if (w < 1) null else o.next()
        if (first == null) finished = true
        else { finished = false; pr = first[0]; py = first[1]; i = Quarter(a - 2 * (w - 1), b - 2 * (w - 1)); pl = leftmost }
    }

    /** every horizontal span (x0, y, x1) on the doubled grid, in Pillow's order */
    fun spans(f: (Int, Int, Int) -> Unit) {
        val buf = ArrayList<IntArray>(4)
        while (true) {
            if (buf.isEmpty()) {
                if (finished) return
                val y = py; var l = pl; val r = pr
                var p: IntArray?
                while (true) { p = o.next(); if (p == null || p[1] > y) break }
                if (p == null) finished = true else { pr = p[0]; py = p[1] }
                var q: IntArray?
                while (true) { q = i.next(); if (q == null || q[1] > y) break; l = q[0] }
                pl = q?.get(0) ?: leftmost
                if ((l > 0 || l < r) && y > 0) buf.add(intArrayOf(if (l == 0) 2 else l, y, r))
                if (y > 0) buf.add(intArrayOf(-r, y, -l))
                if (l > 0 || l < r) buf.add(intArrayOf(if (l == 0) 2 else l, -y, r))
                buf.add(intArrayOf(-r, -y, -l))
            }
            val s = buf.removeAt(buf.size - 1)
            f(s[0], s[1], s[2])
        }
    }
}

/** Pillow's Lanczos resampler (Resample.c: precompute_coeffs, normalize_coeffs_8bpc, the horizontal pass then the vertical one) */
object Lanczos {
    private const val PRECISION_BITS = 32 - 8 - 2

    private fun sinc(x: Double): Double { if (x == 0.0) return 1.0; val p = x * Math.PI; return Math.sin(p) / p }
    private fun filter(x: Double): Double = if (-3.0 <= x && x < 3.0) sinc(x) * sinc(x / 3) else 0.0

    /** (bounds: xmin, count per output; fixed-point coefficients, ksize per output; ksize) */
    private class Coeffs(val bounds: IntArray, val k: IntArray, val ksize: Int)

    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterscale = if (scale < 1.0) 1.0 else scale
        val support = 3.0 * filterscale
        val ksize = Math.ceil(support).toInt() * 2 + 1
        val kk = DoubleArray(outSize * ksize)
        val bounds = IntArray(outSize * 2)
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var ww = 0.0
            val ss = 1.0 / filterscale
            var xmin = (center - support + 0.5).toInt(); if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt(); if (xmax > inSize) xmax = inSize
            xmax -= xmin
            for (x in 0 until xmax) { val wv = filter((x + xmin - center + 0.5) * ss); kk[xx * ksize + x] = wv; ww += wv }
            for (x in 0 until xmax) if (ww != 0.0) kk[xx * ksize + x] /= ww
            bounds[xx * 2] = xmin; bounds[xx * 2 + 1] = xmax
        }
        val one = (1 shl PRECISION_BITS).toDouble()
        val k = IntArray(kk.size) { if (kk[it] < 0) (-0.5 + kk[it] * one).toInt() else (0.5 + kk[it] * one).toInt() }
        return Coeffs(bounds, k, ksize)
    }

    private fun clip8(v: Int): Int = if (v >= (1 shl PRECISION_BITS shl 8)) 255 else if (v <= 0) 0 else v shr PRECISION_BITS

    /** one 8-bit channel, w x h -> nw x nh */
    fun resample(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        var cur = src; var cw = w
        val needH = nw != w
        val needV = nh != h
        val cv = coeffs(h, nh)
        if (needH) {
            val ch = coeffs(w, nw)
            // only the rows the vertical pass reads (Pillow's ybox_first .. ybox_last)
            val first = cv.bounds[0]
            val last = cv.bounds[nh * 2 - 2] + cv.bounds[nh * 2 - 1]
            val rows = if (needV) last - first else h
            val y0 = if (needV) first else 0
            val tmp = IntArray(nw * rows)
            for (y in 0 until rows) {
                val row = (y + y0) * w
                for (xx in 0 until nw) {
                    val xmin = ch.bounds[xx * 2]; val xmax = ch.bounds[xx * 2 + 1]
                    val ko = xx * ch.ksize
                    var ss = 1 shl (PRECISION_BITS - 1)
                    for (x in 0 until xmax) ss += cur[row + x + xmin] * ch.k[ko + x]
                    tmp[y * nw + xx] = clip8(ss)
                }
            }
            if (needV) for (i in 0 until nh) cv.bounds[i * 2] -= first
            cur = tmp; cw = nw
        }
        if (needV) {
            val out = IntArray(cw * nh)
            for (yy in 0 until nh) {
                val ymin = cv.bounds[yy * 2]; val ymax = cv.bounds[yy * 2 + 1]
                val ko = yy * cv.ksize
                for (xx in 0 until cw) {
                    var ss = 1 shl (PRECISION_BITS - 1)
                    for (y in 0 until ymax) ss += cur[(y + ymin) * cw + xx] * cv.k[ko + y]
                    out[yy * cw + xx] = clip8(ss)
                }
            }
            return out
        }
        return if (cur === src) src.copyOf() else cur
    }
}

/** a PNG writer (8-bit RGBA, or RGB for an opaque picture), so the harvest's pictures keep every pixel exactly (an Android Bitmap would
 *  premultiply them on the way) */
object KitPng {
    fun encode(img: KitImage, rgb: Boolean = false): ByteArray = encode(img.w, img.h, if (rgb) 3 else 4) { x, y ->
        img.px[y * img.w + x]
    }

    /** [pixel] gives ARGB; [bpp] 3 = RGB, 4 = RGBA */
    fun encode(w: Int, h: Int, bpp: Int, pixel: (Int, Int) -> Int): ByteArray {
        val stride = w * bpp
        val raw = ByteArray((stride + 1) * h)
        val prev = ByteArray(stride)
        val line = ByteArray(stride)
        val cand = Array(5) { ByteArray(stride) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixel(x, y); val o = x * bpp
                line[o] = (c shr 16).toByte(); line[o + 1] = (c shr 8).toByte(); line[o + 2] = c.toByte()
                if (bpp == 4) line[o + 3] = (c ushr 24).toByte()
            }
            // the filter with the smallest sum of signed bytes (libpng's heuristic)
            var best = 0; var bestSum = Long.MAX_VALUE
            for (f in 0..4) {
                val d = cand[f]
                var sum = 0L
                for (i in 0 until stride) {
                    val a = if (i >= bpp) line[i - bpp].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val v = line[i].toInt() and 0xFF
                    val p = when (f) {
                        0 -> v; 1 -> v - a; 2 -> v - b; 3 -> v - ((a + b) shr 1)
                        else -> v - paeth(a, b, c)
                    }
                    d[i] = p.toByte()
                    sum += Math.abs(d[i].toInt())
                }
                if (sum < bestSum) { bestSum = sum; best = f }
            }
            raw[y * (stride + 1)] = best.toByte()
            System.arraycopy(cand[best], 0, raw, y * (stride + 1) + 1, stride)
            System.arraycopy(line, 0, prev, 0, stride)
        }
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream(raw.size / 3 + 64)
        val buf = ByteArray(1 shl 16)
        while (!def.finished()) { val n = def.deflate(buf); z.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream(z.size() + 64)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArray(13)
        be32(ihdr, 0, w); be32(ihdr, 4, h); ihdr[8] = 8; ihdr[9] = if (bpp == 4) 6 else 2
        chunk(out, "IHDR", ihdr); chunk(out, "IDAT", z.toByteArray()); chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun be32(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4); be32(len, 0, data.size); out.write(len)
        val t = type.toByteArray(Charsets.US_ASCII); out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        val c = ByteArray(4); be32(c, 0, crc.value.toInt()); out.write(c)
    }
}
