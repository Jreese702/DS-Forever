package com.jrees.wowcompanion

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * THE LOADING REVEAL'S MATH (the user, 2026-10-28; design: wow-companion-design/loading, src/trail2.py, loading_final_30fps.mp4). Pure:
 * no Android, so the JVM tests measure exactly what the screens draw ([LoadingScene] draws it).
 *
 * One virtual canvas behind BOTH screens ([TOP] above, a hinge, [BOTTOM] centred under it). A spark travels ALONG THE SWIRL'S ARM from
 * its inner end to the tail ([TRAVEL] s): the user, "a magical traveling effect that leaves the background lit behind it" - never the
 * whole swirl lit at once ("like a beacon ... this is not right"). The picture behind the passed arm stays lit [HOLD] s, then dies
 * [FADE] s IN THE ORDER IT WAS LIT ("don't show the entire spiral to fade out at once"); the arm shows only as a short comet tail
 * ([TAIL] s). The swirl is huge ([SWIRL] virtual px, "travel the full black space": one pass lights ~96 % of both screens), each pass in
 * its own place / turn / mirror ("not always in the same spot"), its own picture, the spark in that picture's colour (a red picture = a
 * red spark). A new pass every [EVERY] s; the newer covers the older (the user: not "overlapping each other so heavily") and the screens
 * are never black longer than ~50 ms ("at no point do I want more than like .050 MS with a black screen").
 *
 * The light is cheap per frame: two maps in the swirl's own square, built once from our own home_swirl picture ([Maps]): COVERAGE
 * (the passed arm, blurred: how lit a place gets) and TIME (when the spark passes near it), so lit = coverage * w(now - time).
 */
object LoadingMath {
    const val TRAVEL = 4.6f
    const val HOLD = 2.2f
    const val FADE = 2.6f
    const val TAIL = 1.0f
    const val RISE = 0.3f
    /** a new pass this often: the black-frame test (LoadingMathTest) passes at 5.9; 6.3 left 267 ms of black on the bottom screen */
    const val EVERY = 5.9f
    /** the first spark has already started when the app shows (no black bottom screen at launch) */
    const val LEAD = 1.5f
    const val SWIRL = 4000f
    const val PLACE = 0.08f

    data class Region(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right get() = left + width
        val bottom get() = top + height
    }
    const val HINGE = 140f
    val TOP = Region(0f, 0f, 1920f, 1080f)
    val BOTTOM = Region((1920f - 1240f) / 2f, 1080f + HINGE, 1240f, 1080f)
    const val VW = 1920f
    const val VH = 1080f + HINGE + 1080f

    // ------------------------------------------------------------------ the swirl's maps

    /** the maps' square: the swirl at [N] px plus [PAD] px round it (the light reaches past the arm) */
    const val N = 280
    /** the light's spread round the arm, as in the study (a blur of 220 / 4000 of the swirl) */
    const val SIGMA = N * 0.055f
    const val PAD = 48
    const val M = N + 2 * PAD
    const val GAIN = 3.6f

    class Maps(
        /** the arm's light, 0..1 (M x M) */
        val arm: FloatArray,
        /** how far along the arm, inner end 0 .. tail 1; 2 = not the arm */
        val pos: FloatArray,
        /** how lit a place gets when the spark has passed (the arm, blurred, x GAIN, clipped) */
        val cover: FloatArray,
        /** when (0..1 of the journey) the spark passes near a place */
        val time: FloatArray,
        /** the spark's place on the arm for p = i / HEADS (map px), x then y */
        val head: FloatArray,
    )
    const val HEADS = 200

    /**
     * The maps from the swirl's light ([lum], [size] x [size], 0..1; our own home_swirl, 560 px). The position along the arm is a walk
     * through the arm's own pixels from its inner end (the kit's home_swirl_field is closer to distance-from-the-middle: a spark on it
     * grew as a disc).
     */
    fun maps(lum: FloatArray, size: Int): Maps {
        val k = size.toFloat() / N
        val arm = FloatArray(M * M)
        for (y in 0 until N) for (x in 0 until N) {
            // the mean of the source pixels under this one
            var s = 0f; var n = 0
            val x0 = (x * k).toInt(); val x1 = max(x0 + 1, ((x + 1) * k).toInt()); val y0 = (y * k).toInt(); val y1 = max(y0 + 1, ((y + 1) * k).toInt())
            for (yy in y0 until min(size, y1)) for (xx in x0 until min(size, x1)) { s += lum[yy * size + xx]; n++ }
            arm[(y + PAD) * M + x + PAD] = if (n > 0) s / n else 0f
        }
        val on = BooleanArray(M * M) { arm[it] > 40f / 255f }
        // the biggest piece: from the brightest pixel; its two ends by a double sweep; the walk starts at the end nearer the middle
        var best = 0
        for (i in arm.indices) if (arm[i] > arm[best]) best = i
        var d = walk(on, best)
        val e1 = farthest(d)
        d = walk(on, e1)
        val e2 = farthest(d)
        val c = M / 2f
        fun r2(i: Int) = (i % M - c).let { it * it } + (i / M - c).let { it * it }
        val inner = if (r2(e1) < r2(e2)) e1 else e2
        d = walk(on, inner)
        var dmax = 0f
        for (v in d) if (v > dmax) dmax = v
        val pos = FloatArray(M * M) { if (d[it] >= 0f && dmax > 0f) d[it] / dmax else 2f }
        val lit = FloatArray(M * M) { if (pos[it] < 2f) min(1f, arm[it] * 1.4f) else 0f }
        val litPos = FloatArray(M * M) { lit[it] * (if (pos[it] < 2f) pos[it] else 0f) }
        val bl = blur(lit, SIGMA)
        val bp = blur(litPos, SIGMA)
        val cover = FloatArray(M * M) { min(1f, bl[it] * GAIN) }
        val time = FloatArray(M * M) { if (bl[it] > 1e-4f) (bp[it] / bl[it]).coerceIn(0f, 1f) else 1f }
        // the spark's path: the mean place of the arm pixels at each step along it
        val sx = FloatArray(HEADS + 1); val sy = FloatArray(HEADS + 1); val sn = IntArray(HEADS + 1)
        for (i in pos.indices) if (pos[i] < 2f) {
            val b = (pos[i] * HEADS).toInt().coerceIn(0, HEADS)
            sx[b] += (i % M).toFloat(); sy[b] += (i / M).toFloat(); sn[b]++
        }
        val head = FloatArray((HEADS + 1) * 2)
        var lx = M / 2f; var ly = M / 2f
        for (b in 0..HEADS) {
            if (sn[b] > 0) { lx = sx[b] / sn[b]; ly = sy[b] / sn[b] }
            head[b * 2] = lx; head[b * 2 + 1] = ly
        }
        return Maps(arm, pos, cover, time, head)
    }

    /** the walk through the arm's pixels (8 neighbours, a diagonal 1.414): its length from [start]; -1 = not reached */
    private fun walk(on: BooleanArray, start: Int): FloatArray {
        val d = FloatArray(on.size) { -1f }
        d[start] = 0f
        val q = ArrayDeque<Int>(); q.add(start)
        while (q.isNotEmpty()) {
            val i = q.removeFirst(); val x = i % M; val y = i / M
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= M || ny >= M) continue
                val j = ny * M + nx
                if (!on[j] || d[j] >= 0f) continue
                d[j] = d[i] + if (dx != 0 && dy != 0) 1.414f else 1f
                q.add(j)
            }
        }
        return d
    }

    private fun farthest(d: FloatArray): Int { var b = 0; for (i in d.indices) if (d[i] > d[b]) b = i; return b }

    /** a gaussian blur of an M x M map (separable) */
    fun blur(a: FloatArray, sigma: Float): FloatArray {
        val r = (sigma * 3).toInt()
        val kern = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val ks = kern.sum(); for (i in kern.indices) kern[i] /= ks
        val tmp = FloatArray(M * M); val out = FloatArray(M * M)
        for (y in 0 until M) for (x in 0 until M) {
            var s = 0f
            for (t in -r..r) { val xx = x + t; if (xx in 0 until M) s += a[y * M + xx] * kern[t + r] }
            tmp[y * M + x] = s
        }
        for (y in 0 until M) for (x in 0 until M) {
            var s = 0f
            for (t in -r..r) { val yy = y + t; if (yy in 0 until M) s += tmp[yy * M + x] * kern[t + r] }
            out[y * M + x] = s
        }
        return out
    }

    // ------------------------------------------------------------------ the passes

    /** one pass: when it starts (s, on the scene's clock), where the swirl sits, its turn and mirror, its picture's index */
    data class Pass(val k: Int, val start: Float, val cx: Float, val cy: Float, val angle: Float, val mirror: Boolean, val picture: Int) {
        private val ca = cos(angle); private val sa = sin(angle)
        /** map px per virtual px */
        private val k1 = N / SWIRL

        /** a virtual point -> the maps' square (px, may be outside) */
        fun toMap(x: Float, y: Float, out: FloatArray) {
            val dx = x - cx; val dy = y - cy
            var px = dx * ca + dy * sa
            val py = -dx * sa + dy * ca
            if (mirror) px = -px
            out[0] = px * k1 + M / 2f; out[1] = py * k1 + M / 2f
        }

        /** a point of the maps' square -> virtual */
        fun toVirtual(u: Float, v: Float, out: FloatArray) {
            var px = (u - M / 2f) / k1; val py = (v - M / 2f) / k1
            if (mirror) px = -px
            out[0] = cx + px * ca - py * sa; out[1] = cy + px * sa + py * ca
        }

        fun end() = start + TRAVEL + HOLD + FADE
        fun alive(t: Float) = t >= start && t < end()
    }

    /** pass [k] of a run with [seed]: its place ±[PLACE] round the middle, any turn, a mirror half the time; pictures in turn from [first] */
    fun pass(seed: Long, k: Int, pictures: Int, first: Int): Pass {
        val r = java.util.Random(seed * 1_000_003L + k * 7919L)
        val cx = VW / 2f + (r.nextFloat() * 2 - 1) * PLACE * VW
        val cy = VH / 2f + (r.nextFloat() * 2 - 1) * PLACE * VH
        val angle = r.nextFloat() * 2f * PI.toFloat()
        val mirror = r.nextBoolean()
        return Pass(k, k * EVERY - LEAD, cx, cy, angle, mirror, if (pictures > 0) (first + k) % pictures else 0)
    }

    /** the passes alive at [t] (oldest first: the newer is drawn over it) */
    fun passes(seed: Long, t: Float, pictures: Int, first: Int): List<Pass> {
        val kMax = ((t + LEAD) / EVERY).toInt()
        val out = ArrayList<Pass>(3)
        for (k in max(0, kMax - 3)..kMax) { val p = pass(seed, k, pictures, first); if (p.alive(t)) out += p }
        return out
    }

    /** the light's life at [age] s after the spark passed: rises in [RISE], holds [HOLD], dies in [FADE] */
    fun life(age: Float): Float {
        if (age < 0f) return 0f
        val up = min(1f, age / RISE)
        val down = (1f - (age - HOLD) / FADE).coerceIn(0f, 1f)
        return up * down
    }

    /** how far along the arm the spark is at [t] (0..1), or < 0 / > 1 outside its journey */
    fun progress(p: Pass, t: Float) = (t - p.start) / TRAVEL

    /** bilinear sample of an M x M map at (u, v); [outside] past the edge */
    fun sample(a: FloatArray, u: Float, v: Float, outside: Float): Float {
        if (u < 0f || v < 0f || u >= M - 1 || v >= M - 1) return outside
        val x0 = u.toInt(); val y0 = v.toInt(); val fx = u - x0; val fy = v - y0
        val i = y0 * M + x0
        return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + M] * (1 - fx) + a[i + M + 1] * fx) * fy
    }

    /** how lit the virtual point (x, y) is by pass [p] at [t] (0..1) */
    fun lit(m: Maps, p: Pass, t: Float, x: Float, y: Float, tmp: FloatArray): Float {
        p.toMap(x, y, tmp)
        val c = sample(m.cover, tmp[0], tmp[1], 0f)
        if (c <= 0.002f) return 0f
        val when_ = sample(m.time, tmp[0], tmp[1], 1f)
        return c * life(t - p.start - when_ * TRAVEL)
    }

    /** the spark's place (virtual) at progress [q] (0..1) */
    fun head(m: Maps, p: Pass, q: Float, out: FloatArray) {
        val f = q.coerceIn(0f, 1f) * HEADS
        val b = f.toInt().coerceIn(0, HEADS - 1); val w = f - b
        val u = m.head[b * 2] * (1 - w) + m.head[(b + 1) * 2] * w
        val v = m.head[b * 2 + 1] * (1 - w) + m.head[(b + 1) * 2 + 1] * w
        p.toVirtual(u, v, out)
    }

    // ------------------------------------------------------------------ the spark's motes

    const val MOTES_PER_S = 36f
    const val MOTE_LIFE = 0.9f

    /**
     * Mote [i] of pass [p]: thrown off the spark at i / [MOTES_PER_S] s into the journey, a fixed direction and speed from the pass and
     * i (so both screens see the same motes). Out: x, y (virtual), its strength 0..1 (0 = gone). Returns false when it isn't alive.
     */
    fun mote(m: Maps, p: Pass, i: Int, t: Float, out: FloatArray): Boolean {
        val born = i / MOTES_PER_S
        if (born > TRAVEL) return false
        val age = t - p.start - born
        if (age < 0f || age >= MOTE_LIFE) return false
        head(m, p, born / TRAVEL, out)
        val r = java.util.Random(p.k * 92821L + i * 31L + 17L)
        val a = r.nextFloat() * 2f * PI.toFloat(); val v = 100f + r.nextFloat() * 225f
        out[0] += cos(a) * v * age; out[1] += sin(a) * v * age
        out[2] = 1f - age / MOTE_LIFE
        return true
    }

    // ------------------------------------------------------------------ the picture's colour

    /** the spark's colour from a picture's mean colour (r, g, b 0..1): its hue, saturated, the brightest channel 1 */
    fun tint(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b)).coerceAtLeast(1e-3f)
        val h = floatArrayOf(r / mx, g / mx, b / mx)
        val lo = min(h[0], min(h[1], h[2]))
        val c = FloatArray(3) { 0.25f + 0.75f * ((h[it] - lo) / max(1e-3f, 1f - lo)) }
        val cm = max(c[0], max(c[1], c[2]))
        return FloatArray(3) { c[it] / cm }
    }

    /** the scene's region of a screen: [TOP] or [BOTTOM] */
    fun region(top: Boolean) = if (top) TOP else BOTTOM
}
