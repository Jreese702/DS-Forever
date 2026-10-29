package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The loading reveal's math on the JVM (the user's rulings, 2026-10-28): the spark follows the arm, one pass lights nearly all of both
 * screens ("travel the full black space"), and no screen is black longer than ~50 ms ("at no point do I want more than like .050 MS
 * with a black screen"), measured at 30 fps over a long run with the drawing's own math.
 */
class LoadingMathTest {
    companion object {
        lateinit var maps: LoadingMath.Maps

        @BeforeClass @JvmStatic fun build() {
            // our own swirl (assets/harvest/orig, never game art); the test runs from the module folder or the root
            val f = listOf("../companion/app/src/main/assets/harvest/orig/home_swirl.png", "companion/app/src/main/assets/harvest/orig/home_swirl.png")
                .map(::File).first { it.isFile }
            val (n, rgb) = png(f.readBytes())
            val lum = FloatArray(n * n) { maxOf(rgb[it * 3], rgb[it * 3 + 1], rgb[it * 3 + 2]) / 255f }
            maps = LoadingMath.maps(lum, n)
        }

        /** an 8-bit RGB, non-interlaced, square PNG (home_swirl's own format): its size and its pixels 0..255 */
        fun png(b: ByteArray): Pair<Int, IntArray> {
            fun u32(i: Int) = ((b[i].toInt() and 255) shl 24) or ((b[i + 1].toInt() and 255) shl 16) or ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)
            val w = u32(16); val h = u32(20)
            check(b[24].toInt() == 8 && b[25].toInt() == 2 && b[28].toInt() == 0) { "not 8-bit RGB" }
            val z = java.io.ByteArrayOutputStream()
            var i = 8
            while (i < b.size) {
                val len = u32(i); val type = String(b, i + 4, 4, Charsets.US_ASCII)
                if (type == "IDAT") z.write(b, i + 8, len)
                i += 12 + len
            }
            val raw = java.util.zip.InflaterInputStream(z.toByteArray().inputStream()).readBytes()
            val stride = w * 3
            val out = IntArray(w * h * 3); val prev = IntArray(stride); val cur = IntArray(stride)
            var o = 0
            for (y in 0 until h) {
                val f = raw[y * (stride + 1)].toInt()
                for (x in 0 until stride) {
                    val v = raw[y * (stride + 1) + 1 + x].toInt() and 255
                    val a = if (x >= 3) cur[x - 3] else 0; val up = prev[x]; val c = if (x >= 3) prev[x - 3] else 0
                    cur[x] = (v + when (f) {
                        1 -> a; 2 -> up; 3 -> (a + up) / 2
                        4 -> { val p = a + up - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - up); val pc = kotlin.math.abs(p - c)
                            if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c }
                        else -> 0
                    }) and 255
                }
                for (x in 0 until stride) { out[o++] = cur[x]; prev[x] = cur[x] }
            }
            return w to out
        }

        val screens = listOf(LoadingMath.TOP, LoadingMath.BOTTOM)

        /** a coarse grid of a screen's points (virtual) */
        fun grid(r: LoadingMath.Region, step: Float = 40f): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>()
            var y = r.top + step / 2
            while (y < r.bottom) { var x = r.left + step / 2; while (x < r.right) { out += x to y; x += step }; y += step }
            return out
        }
    }

    @Test fun theWalkFollowsTheArm() {
        val m = maps
        val onArm = m.pos.count { it < 2f }
        assertTrue("the arm is found ($onArm px)", onArm > 3000)
        // the spark's path moves a little each step (never jumps across the swirl): a walk along the arm, not a radius
        var worst = 0f
        for (b in 0 until LoadingMath.HEADS) {
            val dx = m.head[(b + 1) * 2] - m.head[b * 2]; val dy = m.head[(b + 1) * 2 + 1] - m.head[b * 2 + 1]
            worst = maxOf(worst, kotlin.math.sqrt(dx * dx + dy * dy))
        }
        assertTrue("largest step of the spark $worst map px", worst < LoadingMath.N * 0.06f)
        // it starts near the middle and ends far out (inner end -> tail)
        val c = LoadingMath.M / 2f
        fun r(b: Int) = kotlin.math.hypot(m.head[b * 2] - c, m.head[b * 2 + 1] - c)
        assertTrue("starts inside (${r(0)}) ends outside (${r(LoadingMath.HEADS)})", r(0) < r(LoadingMath.HEADS))
        // the time map grows along the arm
        assertTrue(m.time.all { it in 0f..1f })
    }

    @Test fun onePassLightsNearlyBothScreens() {
        val tmp = FloatArray(3)
        val shares = ArrayList<Float>()
        for (k in 0 until 12) {
            val p = LoadingMath.pass(7L, k, 8, 0)
            var lit = 0; var all = 0
            for (s in screens) for ((x, y) in grid(s)) {
                p.toMap(x, y, tmp)
                if (LoadingMath.sample(maps.cover, tmp[0], tmp[1], 0f) > 0.35f) lit++
                all++
            }
            shares += lit.toFloat() / all
        }
        val mean = shares.average()
        assertTrue("mean share $mean (each: $shares)", mean >= 0.9)
        assertTrue("worst share ${shares.min()}", shares.min() >= 0.8f)
    }

    /** a screen shows something: a lit place of the picture, or the spark / its tail / a mote on it */
    private fun showing(r: LoadingMath.Region, t: Float, seed: Long): Boolean {
        val tmp = FloatArray(3)
        val ps = LoadingMath.passes(seed, t, 8, 0)
        for (p in ps) {
            val q = LoadingMath.progress(p, t)
            if (q in 0f..1f) {
                LoadingMath.head(maps, p, q, tmp)
                if (tmp[0] in r.left..r.right && tmp[1] in r.top..r.bottom) return true
            }
            for ((x, y) in grid(r, 60f)) if (LoadingMath.lit(maps, p, t, x, y, tmp) > 0.1f) return true
        }
        return false
    }

    @Test fun neverBlackLongerThan50ms() {
        for (seed in listOf(1L, 2L, 3L)) {
            for (s in screens) {
                var run = 0; var worst = 0; var at = 0f
                var f = 0
                while (f < 30 * 40) {                 // 40 s at 30 fps
                    val t = f / 30f
                    if (showing(s, t, seed)) run = 0 else { run++; if (run > worst) { worst = run; at = t } }
                    f++
                }
                assertTrue("seed $seed ${if (s == LoadingMath.TOP) "top" else "bottom"}: $worst black frames in a row (at $at s)", worst <= 1)
            }
        }
    }

    @Test fun passesChangePlaceAndPictureAndTheNewerIsLast() {
        val a = LoadingMath.pass(5L, 0, 8, 3); val b = LoadingMath.pass(5L, 1, 8, 3)
        assertFalse(a.cx == b.cx && a.cy == b.cy && a.angle == b.angle)
        assertEquals(3, a.picture); assertEquals(4, b.picture)
        // both alive during the hand-over: oldest first, so the newer is drawn over it
        val t = b.start + 0.5f
        val ps = LoadingMath.passes(5L, t, 8, 3)
        assertTrue(ps.size >= 1)
        assertEquals(ps.sortedBy { it.start }, ps)
        assertTrue(LoadingMath.pass(5L, 0, 8, 3).start < 0f)   // the lead: the first spark already runs at 0
    }

    @Test fun lifeRisesHoldsAndDies() {
        assertEquals(0f, LoadingMath.life(-0.1f))
        assertEquals(1f, LoadingMath.life(1.0f))
        assertEquals(0f, LoadingMath.life(LoadingMath.HOLD + LoadingMath.FADE + 0.01f))
        assertTrue(LoadingMath.life(LoadingMath.HOLD + LoadingMath.FADE / 2) in 0.4f..0.6f)
    }

    @Test fun aRedPictureMakesARedSpark() {
        val red = LoadingMath.tint(149 / 255f, 103 / 255f, 89 / 255f)
        assertTrue(red[0] == 1f && red[2] < 0.5f)
        val blue = LoadingMath.tint(87 / 255f, 115 / 255f, 160 / 255f)
        assertTrue(blue[2] == 1f && blue[0] < 0.5f)
    }
}
