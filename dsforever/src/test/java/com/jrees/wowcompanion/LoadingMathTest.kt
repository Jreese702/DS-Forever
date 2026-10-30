package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The loading reveal's math on the JVM (the user's rulings, 2026-10-28): the spark follows the arm, one pass reaches most of both
 * screens with dark left between the turns and its light widening along the arm (v8), and no screen is black longer than ~50 ms ("at no point do I want more than like .050 MS
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
        // the nearest-arm-point timing is a position along the arm; the distance is 0 on the (smoothed) arm
        assertTrue(m.near.all { it in 0f..1f })
        assertTrue(m.pos.indices.count { m.pos[it] < 2f && m.arm[it] > 0.5f && m.dist[it] <= 1.5f } > 1000)
    }

    /** the share of both screens one pass [p] lights (> 0.35) at [t] */
    private fun share(p: LoadingMath.Pass, t: Float): Float {
        val tmp = FloatArray(3)
        var lit = 0; var all = 0
        for (s in screens) for ((x, y) in grid(s)) { if (LoadingMath.lit(maps, p, t, x, y, tmp) > 0.35f) lit++; all++ }
        return lit.toFloat() / all
    }

    /** the most of both screens a pass ever lights (sampled through its life) */
    private fun peak(p: LoadingMath.Pass): Float {
        var best = 0f
        var t = p.start
        while (t < p.end()) { best = maxOf(best, share(p, t)); t += 0.5f }
        return best
    }

    /** v8/v9 ("some darkness between the sides of each spiral"): a pass lights much of both screens but leaves dark between the turns */
    @Test fun onePassLeavesDarkBetweenTheTurns() {
        val shares = (0 until 8).map { peak(LoadingMath.pass(7L, it, 8, 0)) }
        val mean = shares.average()
        println("v10 peak lit share per pass: mean $mean, each $shares")
        assertTrue("mean share $mean: most of the screens are reached", mean >= 0.3)
        // v10 ("fully bleed into each other"): the turns meet, so most of both screens is lit at a pass's peak
        assertTrue("mean share $mean: the turns meet", mean >= 0.7)
    }

    /** v8 ("start smaller and tighter and get bigger and wider"): the band round the arm is wider near the tail than near the middle */
    @Test fun theLightWidensAlongTheArm() {
        val k = LoadingMath.N / LoadingMath.SWIRL
        // the band's half-width = where band() falls under 0.35, at the same age
        fun width(tn: Float): Float {
            var d = 0f
            while (LoadingMath.band(d, tn, 1f) > 0.35f) d += 0.05f
            return d / k
        }
        val inner = width(0.1f); val outer = width(0.9f)
        println("v9 band half-width at age 1 s: inner $inner, outer $outer virtual px")
        assertTrue("inner $inner < outer $outer", inner < outer)
    }

    /** v9 ("slightly grow ... into the dark space before fading out") + v10 ("fully bleed into each other"): the band grows with age until
     *  the turns meet, its edge soft (no ripple ring: "bubbly"), and the whole fades */
    @Test fun theLightGrowsUntilTheTurnsMeetThenFades() {
        val k = LoadingMath.N / LoadingMath.SWIRL
        fun width(age: Float): Float { var d = 0f; while (LoadingMath.band(d, 0.5f, age) > 0.35f) d += 0.02f; return d / k }
        val w0 = width(0.2f); val w1 = width(2f); val w2 = width(LoadingMath.HOLD + LoadingMath.FADE)
        println("v10 band half-width by age: 0.2 s $w0, 2 s $w1, end $w2 virtual px")
        assertTrue("grows: $w0 < $w1 <= $w2", w0 < w1 && w1 <= w2)
        assertTrue("grows wide enough for the turns to meet ($w2)", w2 > 300f)
        // a soft edge: the light falls off steadily outwards, never rising again (no ring)
        var last = 2f; var d = 0f
        while (d < 60f) { val v = LoadingMath.band(d, 0.5f, 0.3f); assertTrue("no ring at $d", v <= last + 1e-4f); last = v; d += 0.25f }
        assertEquals(0f, LoadingMath.life(LoadingMath.HOLD + LoadingMath.FADE + 0.01f), 0f)
        val p = LoadingMath.pass(7L, 2, 8, 0)
        // v11: the painting grows through the journey and is at its widest as the spark reaches the tail (the inner light then fades)
        val half = share(p, p.start + LoadingMath.JOURNEY * 0.5f)
        val atEnd = share(p, p.start + LoadingMath.JOURNEY)
        println("v11 lit share: half way $half, spark at the tail $atEnd")
        assertTrue("the light keeps spreading while the spark runs ($half -> $atEnd)", atEnd > half)
    }

    /** the per-frame cost: both screens' light grids (8 px cells, as LoadingScene) for every live pass, 30 frames in a hand-over */
    @Test fun aFrameStaysCheap() {
        val tmp = FloatArray(3)
        fun frame(t: Float): Int {
            var n = 0
            for (p in LoadingMath.passes(3L, t, 8, 0)) for (r in screens) {
                var y = r.top + 4f
                while (y < r.bottom) { var x = r.left + 4f; while (x < r.right) { if (LoadingMath.lit(maps, p, t, x, y, tmp) > 0f) n++; x += 8f }; y += 8f }
            }
            return n
        }
        val t0 = LoadingMath.EVERY * 2 - 0.5f
        repeat(30) { frame(t0 + it / 30f) }                          // warm up
        val start = System.nanoTime()
        repeat(60) { frame(t0 + it / 30f) }
        val ms = (System.nanoTime() - start) / 1e6 / 60
        println("v9 light grids per frame (both screens, all passes): %.2f ms on this PC".format(ms))
        assertTrue("a frame's light costs $ms ms on the PC (the phone is ~3-5x slower; 33 ms per frame)", ms < 6.0)
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
        assertTrue("an eased rise", LoadingMath.life(LoadingMath.RISE / 2) < 0.5f)
        assertEquals(0f, LoadingMath.life(LoadingMath.HOLD + LoadingMath.FADE + 0.01f))
        assertTrue(LoadingMath.life(LoadingMath.HOLD + LoadingMath.FADE / 2) in 0.4f..0.6f)
    }

    @Test fun aRedPictureMakesARedSpark() {
        val red = LoadingMath.tint(149 / 255f, 103 / 255f, 89 / 255f)
        assertTrue(red[0] == 1f && red[2] < 0.5f)
        val blue = LoadingMath.tint(87 / 255f, 115 / 255f, 160 / 255f)
        assertTrue(blue[2] == 1f && blue[0] < 0.5f)
    }

    /** v11 ("speed up on the last big circle ... to finish the full screen painting as the fade begins") */
    @Test fun theSparkSpeedsUpOnTheLastTurn() {
        val inner = LoadingMath.arrive(0.2f) - LoadingMath.arrive(0.1f)
        val outer = LoadingMath.arrive(1f) - LoadingMath.arrive(0.9f)
        println("v11 a tenth of the arm: inner ${inner}s, outer ${outer}s; journey ${LoadingMath.JOURNEY}s")
        assertTrue("faster at the tail ($inner vs $outer)", outer * 2f < inner)
        assertEquals(0.1f * LoadingMath.TRAVEL, LoadingMath.arrive(0.1f), 1e-3f)
        // the tail is reached about when the first light starts to fade (HOLD after the inner end)
        assertTrue("journey ${LoadingMath.JOURNEY}", LoadingMath.JOURNEY > LoadingMath.HOLD && LoadingMath.JOURNEY < LoadingMath.HOLD + LoadingMath.FADE)
        for (q in listOf(0f, 0.25f, 0.5f, 0.8f, 1f)) assertEquals(q, LoadingMath.along(LoadingMath.arrive(q)), 2e-3f)
    }
}
