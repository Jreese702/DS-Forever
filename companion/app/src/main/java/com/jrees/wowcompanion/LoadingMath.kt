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
 * The light is cheap per frame: two maps in the swirl's own square, built once from our own home_swirl picture ([Maps]): the DISTANCE
 * from the arm and WHEN the spark passes the nearest arm point (v9; v1-v8 used a blurred coverage), so lit = band(distance, age) *
 * life(age).
 *
 * v8 (the user, 2026-10-28: "the spiral is a bit too fast. It should start smaller and tighter and get bigger and wider, there should be
 * some darkness between the sides of each spiral and the glowing effect is a bit too strong ... the tail needs to fade out a touch
 * faster"; study src/trail3.py): a slower spark, the light NARROW at the inner end and WIDE at the tail, with dark left between the
 * turns, a softer glow, a short squared tail.
 *
 * v9 (the user, 2026-10-28: "the spiral needs to like slightly grow and ripple into the dark space before fading out, not stay the same
 * size"; study src/trail4.py, loading_v9_30fps.mp4): the light round each stretch of arm is a band whose half-width ([R0_IN] + tn *
 * R0_OUT) GROWS after the spark passed (to x1.9 over HOLD + FADE, quickly at first), with a soft ripple ring at its edge,
 * then fades. The maps are now the DISTANCE from the (smoothed) arm and the arm position of the nearest arm point.
 *
 * v10 (the user, 2026-10-29: "The black gaps in the spiral are way too big and bubbly ... I do think I want it to fully bleed into each
 * other"; study src/trail5.py, loading_v10_30fps.mp4): the band keeps growing by [SPREAD] until the turns MEET, with a long soft edge
 * (a smoothstep over the whole width) instead of a disc's rim, and no ripple ring; the timing map is blurred wider so the seam where
 * two turns meet is soft.
 */
object LoadingMath {
    /** the spark's speed through the inner turns: 1 / TRAVEL of the arm per second */
    const val TRAVEL = 6.2f
    /**
     * v11 (the user, 2026-10-29: "they should speed up on the last big circle of the spiral to finish the full screen painting as the
     * fade begins"): from [ACCEL_FROM] along the arm (the last full turn starts at ~0.27, measured on swirl_arclen) the spark speeds up
     * smoothly to [ACCEL] x by the tail, so the outer turn is painted while the inner light begins to fade.
     */
    const val ACCEL_FROM = 0.3f
    const val ACCEL = 2.6f
    private const val ARRIVE_N = 256
    /** when the spark reaches each 1 / ARRIVE_N of the arm (s from the pass's start) */
    private val ARRIVE = FloatArray(ARRIVE_N + 1).also { a ->
        for (i in 1..ARRIVE_N) {
            val mid = (i - 0.5f) / ARRIVE_N
            val e = ((mid - ACCEL_FROM) / (1f - ACCEL_FROM)).coerceIn(0f, 1f)
            val speed = 1f + (ACCEL - 1f) * e * e * (3f - 2f * e)
            a[i] = a[i - 1] + TRAVEL / ARRIVE_N / speed
        }
    }
    /** the spark's whole journey, inner end to tail (s) */
    val JOURNEY = ARRIVE[ARRIVE_N]

    /** when the spark passes [tn] along the arm (s from the pass's start) */
    fun arrive(tn: Float): Float {
        val f = tn.coerceIn(0f, 1f) * ARRIVE_N
        val i = min(ARRIVE_N - 1, f.toInt())
        return ARRIVE[i] + (ARRIVE[i + 1] - ARRIVE[i]) * (f - i)
    }

    /** how far along the arm the spark is [s] seconds into its journey (0..1; < 0 before, > 1 after) */
    fun along(s: Float): Float {
        if (s <= 0f) return s / TRAVEL
        if (s >= JOURNEY) return 1f + (s - JOURNEY) / TRAVEL
        var lo = 0; var hi = ARRIVE_N
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (ARRIVE[mid] <= s) lo = mid else hi = mid }
        return (lo + (s - ARRIVE[lo]) / (ARRIVE[hi] - ARRIVE[lo])) / ARRIVE_N
    }
    const val HOLD = 2.2f
    const val FADE = 3.0f
    const val TAIL = 0.55f
    /** the light comes up over [RISE] s after the spark passed (a soft leading edge, eased ^1.5) */
    const val RISE = 0.7f
    /** a new pass this often (the v8 study: 0 black frames at 6.6; v11 5.0 for its shorter journey, the same hand-over; LoadingMathTest measures it) */
    const val EVERY = 5.6f
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
    /** v10: the band's half-width round the arm (virtual px) at the inner end, and the extra at the tail (the study's 60 + 110 tn) */
    const val R0_IN = 60f
    const val R0_OUT = 110f
    /** v10: the band grows by SPREAD virtual px (quickly at first) over HOLD + FADE / 2: wide enough for the turns to meet */
    const val SPREAD = 520f
    /** v10: the timing map's blur (virtual px): a soft seam where two turns meet */
    const val NEAR_BLUR = 36f
    const val PAD = 32
    const val M = N + 2 * PAD

    class Maps(
        /** the arm's light, 0..1 (M x M) */
        val arm: FloatArray,
        /** how far along the arm, inner end 0 .. tail 1; 2 = not the arm */
        val pos: FloatArray,
        /** v9: the distance (map px) from the arm, its outline smoothed so the band's edge has no spikes */
        val dist: FloatArray,
        /** v9: the arm position (0..1) of the nearest arm point: when the spark passes near a place */
        val near: FloatArray,
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
        // v9 (study trail4.py): the distance from the arm, its outline smoothed (blurred ~10 virtual px, > 0.5: no spiky edge), and the
        // timing from the nearest REAL arm point (the nearest smoothed-outline point can be off the arm: black needles in the study)
        val kv = N / SWIRL                         // map px per virtual px
        val solid = FloatArray(M * M) { if (pos[it] < 2f && arm[it] > 0.25f) 1f else 0f }
        val smooth = blur(solid, max(0.6f, 10f * kv))
        val dist = edt(BooleanArray(M * M) { smooth[it] > 0.5f }).first
        val nearest = edt(BooleanArray(M * M) { pos[it] < 2f }).second
        val near = blur(FloatArray(M * M) { if (nearest[it] >= 0) pos[nearest[it]] else 1f }, max(0.6f, NEAR_BLUR * kv))
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
        return Maps(arm, pos, dist, near, head)
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

    /**
     * The exact euclidean distance transform of an M x M map (Felzenszwalb & Huttenlocher, separable): for each pixel its distance
     * (map px) to the nearest [feature] pixel and that pixel's index (-1 and a huge distance = no feature at all).
     */
    fun edt(feature: BooleanArray): Pair<FloatArray, IntArray> {
        val colD = FloatArray(M * M); val colI = IntArray(M * M)
        val f = FloatArray(M); val d = FloatArray(M); val a = IntArray(M)
        for (x in 0 until M) {
            for (y in 0 until M) f[y] = if (feature[y * M + x]) 0f else FAR
            dt1(f, d, a)
            for (y in 0 until M) { colD[y * M + x] = d[y]; colI[y * M + x] = if (a[y] >= 0) a[y] * M + x else -1 }
        }
        val dist = FloatArray(M * M); val idx = IntArray(M * M)
        for (y in 0 until M) {
            for (x in 0 until M) f[x] = colD[y * M + x]
            dt1(f, d, a)
            for (x in 0 until M) {
                val src = if (a[x] >= 0) colI[y * M + a[x]] else -1
                idx[y * M + x] = src
                dist[y * M + x] = if (src >= 0) kotlin.math.sqrt(d[x]) else FAR
            }
        }
        return dist to idx
    }
    private const val FAR = 1e20f

    /** the 1-D squared distance transform of [f] into [d] (lower envelope of parabolas), the winning sample's index in [arg] */
    private fun dt1(f: FloatArray, d: FloatArray, arg: IntArray) {
        val n = f.size
        val v = IntArray(n); val z = FloatArray(n + 1)
        var k = -1
        for (q in 0 until n) {
            if (f[q] >= FAR) continue
            if (k < 0) { k = 0; v[0] = q; z[0] = -Float.MAX_VALUE; z[1] = Float.MAX_VALUE; continue }
            var s: Float
            while (true) {
                val p = v[k]
                s = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * q - 2f * p)
                if (s <= z[k] && k > 0) k-- else break
            }
            if (s <= z[k]) { v[k] = q; z[k + 1] = Float.MAX_VALUE }
            else { k++; v[k] = q; z[k] = s; z[k + 1] = Float.MAX_VALUE }
        }
        if (k < 0) { for (q in 0 until n) { d[q] = FAR; arg[q] = -1 }; return }
        var j = 0
        for (q in 0 until n) {
            while (z[j + 1] < q) j++
            val p = v[j]
            d[q] = (q - p).toFloat() * (q - p) + f[p]; arg[q] = p
        }
    }

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

        fun end() = start + JOURNEY + HOLD + FADE
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

    /** the light's life at [age] s after the spark passed: rises in [RISE] (eased), holds [HOLD], dies in [FADE] */
    fun life(age: Float): Float {
        if (age < 0f) return 0f
        val up = Math.pow(min(1f, age / RISE).toDouble(), 1.5).toFloat()
        val down = (1f - (age - HOLD) / FADE).coerceIn(0f, 1f)
        return up * down
    }

    /** how far along the arm the spark is at [t] (0..1), or < 0 / > 1 outside its journey */
    fun progress(p: Pass, t: Float) = along(t - p.start)

    /** bilinear sample of an M x M map at (u, v); [outside] past the edge */
    fun sample(a: FloatArray, u: Float, v: Float, outside: Float): Float {
        if (u < 0f || v < 0f || u >= M - 1 || v >= M - 1) return outside
        val x0 = u.toInt(); val y0 = v.toInt(); val fx = u - x0; val fy = v - y0
        val i = y0 * M + x0
        return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + M] * (1 - fx) + a[i + M + 1] * fx) * fy
    }

    /** the band's widest reach (map px): no light past it */
    private val REACH = (R0_IN + R0_OUT + SPREAD) * N / SWIRL + 3f

    /**
     * How lit the virtual point (x, y) is by pass [p] at [t] (0..1). v10: a band round the arm, its half-width growing after the spark
     * passed (quickly at first) until the turns meet, a soft edge, the whole fading with [life].
     */
    fun lit(m: Maps, p: Pass, t: Float, x: Float, y: Float, tmp: FloatArray): Float {
        p.toMap(x, y, tmp)
        val d = sample(m.dist, tmp[0], tmp[1], FAR)
        if (d > REACH) return 0f
        val tn = sample(m.near, tmp[0], tmp[1], 1f)
        val a2 = t - p.start - arrive(tn)
        val w = life(a2)
        if (w <= 0f) return 0f
        return w * band(d, tn, a2)
    }

    /** v10: the band's light (0..1) at [d] map px from the arm, [tn] along it, [age] s after the spark passed */
    fun band(d: Float, tn: Float, age: Float): Float {
        val k = N / SWIRL
        val grow = (age / (HOLD + FADE * 0.5f)).coerceIn(0f, 1f)
        val r = (R0_IN + tn * R0_OUT + SPREAD * (1f - (1f - grow) * (1f - grow))) * k
        val e = (1f - d / r).coerceIn(0f, 1f)
        return Math.pow((e * e * (3f - 2f * e)).toDouble(), 0.8).toFloat()
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
        if (born > JOURNEY) return false
        val age = t - p.start - born
        if (age < 0f || age >= MOTE_LIFE) return false
        head(m, p, along(born), out)
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
