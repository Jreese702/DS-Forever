package com.jrees.wowcompanion

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * CAVES AND TUNNELS AS WAYS (b-route-climb; the user on Drygulch Ravine: "there's like caves connecting those walkable spaces. Caves
 * like this need to be logically detected and factored"). A cave is a WMO placed in the hills (MODF); its walkable floor is its
 * groups' own collision triangles (MOPY, or the newer groups' MPY2: a collision face, or a rendered one that is not detail - the server extractors' rule) no
 * steeper than [FLOOR_DEG]. Where that floor meets the terrain at the terrain's own height on ground one can stand on, the cave has a
 * MOUTH; a floor that reaches two or more mouths is a tunnel joining pockets the cliffs wall apart, and the floor's shortest way
 * between each two of them a [Way] the routes may take (Climb's links). The ADT's holes (MCNK) are where the terrain opens into a cave:
 * their ground is not there to walk ([Terrain.holes]). Pure: bytes in, ways out.
 */
object Caves {
    const val FLOOR_DEG = 45.0             // the hard rule's limit, as on the terrain
    const val WELD_YD = 0.8                // floor vertices this near are one (the groups meet at their portals, not always exactly; Ban'ethil
                                           // Barrow Den's stair risers join at 0.8)
    const val MOUTH_YD = 1.5               // a floor vertex this near the terrain's own height is where they meet
    const val MOUTH_GAP_YD = 25.0          // mouths farther apart than this are separate openings
    const val MOUTH_GAP_Z = 10.0           // ... or this far apart in height (a lift's foot and its top: one over the other, never one opening)
    const val SIMPLIFY_YD = 3.0            // a way's points kept at least this far apart
    const val FLOOR_SAMPLE_YD = 4          // the floor's footprint kept a point a square this size
    const val STEP_JOIN_YD = 1.0           // a stair's treads (their risers no floor): vertices this near across (TB's stairs: 1.0) ...
    const val STEP_UP_YD = 1.5             // ... and no more than this apart in height are one step

    /** the floor's steps joined: each two vertices within [STEP_JOIN_YD] across and [STEP_UP_YD] up (a stair's treads, a ledge's lip) */
    private fun joinSteps(xs: List<Double>, ys: List<Double>, zs: List<Double>, link: (Int, Int) -> Unit) {
        val grid = HashMap<Long, ArrayList<Int>>()
        fun key(i: Long, j: Long) = (i shl 32) xor (j and 0xFFFFFFFFL)
        for (v in xs.indices) grid.getOrPut(key(Math.floor(xs[v]).toLong(), Math.floor(ys[v]).toLong())) { ArrayList() }.add(v)
        for (v in xs.indices) {
            val bx = Math.floor(xs[v]).toLong(); val by = Math.floor(ys[v]).toLong()
            for (i in -1L..1L) for (j in -1L..1L) grid[key(bx + i, by + j)]?.forEach { w ->
                if (w > v && hypot(xs[w] - xs[v], ys[w] - ys[v]) <= STEP_JOIN_YD && abs(zs[w] - zs[v]) <= STEP_UP_YD) link(v, w)
            }
        }
    }

    /** the WMO name parts that mark a cave, a tunnel, a mine, a den, a pass (Coldridge Valley's tunnel is md_anvilmarpass/anvilmarpass.wmo),
     *  a crypt or a barrow (Tirisfal's md_cryptonerm and md_crypt, Teldrassil's Ban'ethil barrow den), a hollow, a grave */
    val KINDS = listOf("cave", "tunnel", "mine", "den", "pass", "crypt", "barrow", "hollow", "grave")

    /**
     * A LIFT (the user: "what about quests going up to thunder bluff?"): its car is a server object, in none of the client's files,
     * but its two landings are the WMO's own fixed floor, one straight above the other with nothing walkable between (the shaft).
     * World [x], [y] of the shaft and its landings' floor heights [zBottom] / [zTop], measured from the WMO's floor (the harness's
     * py/liftfind.py) and checked against the car's travel in TransportAnimation. Never estimated: a lift no floor shows needs a
     * manual link. Riding it costs [LIFT_WAIT_YD].
     */
    class Lift(val name: String, val x: Double, val y: Double, val zBottom: Double, val zTop: Double)
    /** a lift's wait and ride (about 30 s, at a run's 7 yd a second) as yards of walking */
    const val LIFT_WAIT_YD = 210.0
    /** a lift's landing is the floor vertex this near its shaft (x, y: a landing is a few big triangles, their corners well off it) and this near its height */
    const val LIFT_SNAP_YD = 12.0
    const val LIFT_SNAP_Z = 2.5

    /** one way through: world points (x, y, z) from mouth [a] to mouth [b], [yd] long along its floor; a point with a 4th value 1 is a
     *  lift's landing (two in a row: the ride between them) */
    class Way(val pts: List<DoubleArray>, val yd: Double) {
        val a get() = pts.first()
        val b get() = pts.last()
    }
    /** a placed cave: its [mouths] (world x, y, z) and the [ways] between them */
    class Cave(val fid: Int, val uid: Int, val wx: Double, val wy: Double, val mouths: List<DoubleArray>, val ways: List<Way>,
               val floor: List<DoubleArray> = emptyList())          // its floor's footprint: a point (x, y, z) per FLOOR_SAMPLE_YD square

    /** a group file's walkable collision triangles, in the WMO's own frame: 9 floats each (three corners x, y, z) */
    fun floorTris(group: ByteArray): FloatArray {
        // MVER, then MOGP: its 0x44-byte header, then its own chunks
        var o = 0
        while (o + 8 <= group.size) {
            val n = Db2.le32(group, o + 4); if (n < 0 || o + 8 + n > group.size) break
            if (tag(group, o) == "MOGP") return trisIn(group, o + 8 + 0x44, o + 8 + n)
            o += 8 + n
        }
        return FloatArray(0)
    }
    private fun trisIn(b: ByteArray, from: Int, end: Int): FloatArray {
        var mopy = -1; var nPoly = 0; var polyStride = 2; var movi = -1; var nIdx = 0; var movt = -1; var nVert = 0
        var o = from
        while (o + 8 <= end) {
            val n = Db2.le32(b, o + 4); if (n < 0 || o + 8 + n > end) break
            when (tag(b, o)) {
                "MOPY" -> { mopy = o + 8; nPoly = n / 2; polyStride = 2 }
                "MPY2" -> { mopy = o + 8; nPoly = n / 4; polyStride = 4 }      // the newer groups: a face's flags (16 bits), its material
                "MOVI" -> { movi = o + 8; nIdx = n / 2 }
                "MOVT" -> { movt = o + 8; nVert = n / 12 }
            }
            o += 8 + n
        }
        if (mopy < 0 || movi < 0 || movt < 0) return FloatArray(0)
        val minUp = cos(Math.toRadians(FLOOR_DEG))
        val out = ArrayList<Float>()
        fun v(i: Int, c: Int) = java.lang.Float.intBitsToFloat(Db2.le32(b, movt + i * 12 + c * 4))
        for (t in 0 until minOf(nPoly, nIdx / 3)) {
            val fl = b[mopy + t * polyStride].toInt() and 0xFF
            val render = fl and 0x20 != 0 && fl and 0x04 == 0
            if (fl and 0x08 == 0 && !render) continue
            val ix = IntArray(3) { (b[movi + (t * 3 + it) * 2].toInt() and 0xFF) or ((b[movi + (t * 3 + it) * 2 + 1].toInt() and 0xFF) shl 8) }
            if (ix.any { it >= nVert }) continue
            val ax = v(ix[1], 0) - v(ix[0], 0); val ay = v(ix[1], 1) - v(ix[0], 1); val az = v(ix[1], 2) - v(ix[0], 2)
            val bx = v(ix[2], 0) - v(ix[0], 0); val by = v(ix[2], 1) - v(ix[0], 1); val bz = v(ix[2], 2) - v(ix[0], 2)
            val nx = ay * bz - az * by; val ny = az * bx - ax * bz; val nz = ax * by - ay * bx
            val len = sqrt((nx * nx + ny * ny + nz * nz).toDouble()); if (len < 1e-9) continue
            if (abs(nz) / len < minUp) continue                 // a wall or a ceiling's slope: no floor (either winding)
            for (k in ix) for (c in 0..2) out.add(v(k, c))
        }
        return out.toFloatArray()
    }

    /**
     * placement [p]'s cave from its [groups]' files: the floor in world yards (the placements' own frame, as [Buildings.Foot]),
     * its mouths where the floor meets [ground] (the terrain's height at world x, y; NaN = none) on spots [standable], and the ways.
     */
    fun cave(p: WorldBuildings.Placement, groups: List<ByteArray>, ground: (Double, Double) -> Float, standable: (Double, Double) -> Boolean,
             lifts: List<Lift> = emptyList()): Cave {
        val cs = cos(p.yaw); val sn = Math.sin(p.yaw)
        // the floor's vertices, welded; its edges
        val bucket = HashMap<Long, ArrayList<Int>>(); val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        val adj = ArrayList<ArrayList<Int>>()
        fun bk(a: Long, b: Long, c: Long) = (a and 0x1FFFFFL) or ((b and 0x1FFFFFL) shl 21) or ((c and 0x1FFFFFL) shl 42)
        fun vid(lx: Float, ly: Float, lz: Float): Int {
            val x = p.ox - lx * p.scale * cs + ly * p.scale * sn; val y = p.oy - lx * p.scale * sn - ly * p.scale * cs; val z = p.oz + lz * p.scale
            val bx = Math.floor(x).toLong(); val by = Math.floor(y).toLong(); val bz = Math.floor(z).toLong()
            for (a in -1L..1L) for (b in -1L..1L) for (c in -1L..1L) bucket[bk(bx + a, by + b, bz + c)]?.forEach { v ->
                if ((xs[v] - x) * (xs[v] - x) + (ys[v] - y) * (ys[v] - y) + (zs[v] - z) * (zs[v] - z) <= WELD_YD * WELD_YD) return v
            }
            xs.add(x); ys.add(y); zs.add(z); adj.add(ArrayList())
            bucket.getOrPut(bk(bx, by, bz)) { ArrayList() }.add(xs.size - 1)
            return xs.size - 1
        }
        val faces = ArrayList<IntArray>()
        for (g in groups) {
            val t = floorTris(g)
            for (q in 0 until t.size / 9) {
                val a = vid(t[q * 9], t[q * 9 + 1], t[q * 9 + 2]); val b = vid(t[q * 9 + 3], t[q * 9 + 4], t[q * 9 + 5]); val c = vid(t[q * 9 + 6], t[q * 9 + 7], t[q * 9 + 8])
                for ((u, w) in listOf(a to b, b to c, c to a)) if (u != w) { adj[u].add(w); adj[w].add(u) }
                if (lifts.isNotEmpty()) faces.add(intArrayOf(a, b, c))
            }
        }
        joinSteps(xs, ys, zs) { u, w -> adj[u].add(w); adj[w].add(u) }
        val n = xs.size
        // the lifts whose two landings are this floor's: their bottom and top vertices joined (the ride, [LIFT_WAIT_YD])
        val rides = HashSet<Long>()
        // a landing: the floor face straight over / under the shaft at that height (its corner nearest the shaft); else the nearest
        // floor vertex at that height within [LIFT_SNAP_YD]
        fun inside(f: IntArray, x: Double, y: Double): Boolean {
            fun side(i: Int, j: Int) = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i])
            val d0 = side(f[0], f[1]); val d1 = side(f[1], f[2]); val d2 = side(f[2], f[0])
            return !((d0 < 0 || d1 < 0 || d2 < 0) && (d0 > 0 || d1 > 0 || d2 > 0))
        }
        fun landing(l: Lift, z: Double): Int {
            faces.filter { f -> abs((zs[f[0]] + zs[f[1]] + zs[f[2]]) / 3 - z) <= LIFT_SNAP_Z && inside(f, l.x, l.y) }
                .minByOrNull { f -> abs((zs[f[0]] + zs[f[1]] + zs[f[2]]) / 3 - z) }
                ?.let { f -> return f.minByOrNull { v -> hypot(xs[v] - l.x, ys[v] - l.y) }!! }
            return (0 until n).filter { v -> hypot(xs[v] - l.x, ys[v] - l.y) <= LIFT_SNAP_YD && abs(zs[v] - z) <= LIFT_SNAP_Z }
                .minByOrNull { v -> hypot(xs[v] - l.x, ys[v] - l.y) + abs(zs[v] - z) } ?: -1
        }
        for (l in lifts) {
            val lo = landing(l, l.zBottom); val hi = landing(l, l.zTop)
            if (lo < 0 || hi < 0 || lo == hi) continue
            adj[lo].add(hi); adj[hi].add(lo); rides.add(lo.toLong() shl 32 or hi.toLong()); rides.add(hi.toLong() shl 32 or lo.toLong())
        }
        fun ride(u: Int, w: Int) = (u.toLong() shl 32 or w.toLong()) in rides
        // its pieces (a cave's floor can be several: a ledge, a pit)
        val comp = IntArray(n) { -1 }; var nc = 0
        for (s in 0 until n) if (comp[s] < 0) {
            val st = ArrayDeque<Int>(); st.add(s); comp[s] = nc
            while (st.isNotEmpty()) { val u = st.removeLast(); for (w in adj[u]) if (comp[w] < 0) { comp[w] = nc; st.add(w) } }
            nc++
        }
        // the mouths: floor at the terrain's own height, on ground one can stand on; grouped per opening, each its best-met vertex
        val meets = (0 until n).filter { v -> val h = ground(xs[v], ys[v]); !h.isNaN() && abs(h - zs[v]) <= MOUTH_YD && standable(xs[v], ys[v]) }
        val mouths = ArrayList<Int>()
        for (v in meets.sortedBy { abs(ground(xs[it], ys[it]) - zs[it]) }) {
            if (mouths.none { m -> comp[m] == comp[v] && hypot(xs[m] - xs[v], ys[m] - ys[v]) < MOUTH_GAP_YD && abs(zs[m] - zs[v]) < MOUTH_GAP_Z }) mouths.add(v)
        }
        // the ways: each two mouths on one piece, by the floor's shortest way (3D lengths)
        val ways = ArrayList<Way>()
        for ((ai, a) in mouths.withIndex()) {
            val later = mouths.drop(ai + 1).filter { comp[it] == comp[a] }; if (later.isEmpty()) continue
            val dist = DoubleArray(n) { Double.MAX_VALUE }; val prev = IntArray(n) { -1 }
            val pq = PriorityQueue<DoubleArray>(compareBy { it[0] }); dist[a] = 0.0; pq.add(doubleArrayOf(0.0, a.toDouble()))
            while (pq.isNotEmpty()) {
                val top = pq.poll(); val u = top[1].toInt(); if (top[0] > dist[u]) continue
                for (w in adj[u]) {
                    val d = dist[u] + if (ride(u, w)) LIFT_WAIT_YD else
                        sqrt((xs[w] - xs[u]).let { it * it } + (ys[w] - ys[u]).let { it * it } + (zs[w] - zs[u]).let { it * it })
                    if (d < dist[w]) { dist[w] = d; prev[w] = u; pq.add(doubleArrayOf(d, w.toDouble())) }
                }
            }
            for (b in later) {
                if (dist[b] == Double.MAX_VALUE) continue
                val chain = ArrayList<Int>(); var c = b; while (c != -1) { chain.add(c); c = prev[c] }
                chain.reverse()
                val pts = ArrayList<DoubleArray>()
                for ((q, v) in chain.withIndex()) {
                    // a lift's two landings always kept, marked (the route's "take the lift" flag)
                    val atLift = (q > 0 && ride(chain[q - 1], v)) || (q < chain.size - 1 && ride(v, chain[q + 1]))
                    val pt = if (atLift) doubleArrayOf(xs[v], ys[v], zs[v], 1.0) else doubleArrayOf(xs[v], ys[v], zs[v])
                    if (q == 0 || q == chain.size - 1 || atLift || hypot(pt[0] - pts.last()[0], pt[1] - pts.last()[1]) >= SIMPLIFY_YD) pts.add(pt)
                }
                ways.add(Way(pts, dist[b]))
            }
        }
        val floor = (0 until n).groupBy { Math.floorDiv(Math.round(xs[it]), FLOOR_SAMPLE_YD.toLong()) * 100000 + Math.floorDiv(Math.round(ys[it]), FLOOR_SAMPLE_YD.toLong()) }
            .values.map { vs -> vs.minByOrNull { zs[it] }!!.let { doubleArrayOf(xs[it], ys[it], zs[it]) } }
        return Cave(p.fid, p.uid, p.ox, p.oy, mouths.map { doubleArrayOf(xs[it], ys[it], zs[it]) }, ways, floor)
    }

    /**
     * one walkable floor across several placed WMOs ([parts]: each placement with its groups' files), welded where they meet: a place
     * built in the air (Zephras' Rohashi Spires: a sanctuary, its stairs and perimeter paths, the confrontation platform) is walked on
     * these floors alone. Points world (x, y, z).
     */
    class Floor(val xs: DoubleArray, val ys: DoubleArray, val zs: DoubleArray, val adj: Array<IntArray>) {
        val size get() = xs.size
        /** the floor's pieces: each vertex's piece number */
        val piece: IntArray by lazy {
            val c = IntArray(size) { -1 }; var nc = 0
            for (s in 0 until size) if (c[s] < 0) {
                val st = ArrayDeque<Int>(); st.add(s); c[s] = nc
                while (st.isNotEmpty()) { val u = st.removeLast(); for (w in adj[u]) if (c[w] < 0) { c[w] = nc; st.add(w) } }
                nc++
            }
            c
        }
        /** the vertex nearest world ([x], [y]) on the floor (within piece [inPiece] when given): the highest of those within 2 yd of it
         *  ([high]), else the lowest - a tower's floors stand over each other */
        fun near(x: Double, y: Double, high: Boolean = true, inPiece: Int = -1): Int {
            var best = -1; var bd = Double.MAX_VALUE
            for (v in 0 until size) { if (inPiece >= 0 && piece[v] != inPiece) continue
                val d = hypot(xs[v] - x, ys[v] - y); if (d < bd) { bd = d; best = v } }
            if (best < 0) return -1
            return (0 until size).filter { (inPiece < 0 || piece[it] == inPiece) && hypot(xs[it] - x, ys[it] - y) <= bd + 2.0 }
                .let { c -> if (high) c.maxByOrNull { zs[it] } else c.minByOrNull { zs[it] } } ?: best
        }
        /** the floor's shortest way from vertex [a] to [b] (3D lengths), its points kept SIMPLIFY_YD apart; null when they don't join */
        fun path(a: Int, b: Int): List<DoubleArray>? {
            if (a < 0 || b < 0 || piece[a] != piece[b]) return null
            val dist = DoubleArray(size) { Double.MAX_VALUE }; val prev = IntArray(size) { -1 }
            val pq = PriorityQueue<DoubleArray>(compareBy { it[0] }); dist[a] = 0.0; pq.add(doubleArrayOf(0.0, a.toDouble()))
            while (pq.isNotEmpty()) {
                val top = pq.poll(); val u = top[1].toInt(); if (top[0] > dist[u]) continue; if (u == b) break
                for (w in adj[u]) {
                    val d = dist[u] + sqrt((xs[w] - xs[u]).let { it * it } + (ys[w] - ys[u]).let { it * it } + (zs[w] - zs[u]).let { it * it })
                    if (d < dist[w]) { dist[w] = d; prev[w] = u; pq.add(doubleArrayOf(d, w.toDouble())) }
                }
            }
            if (dist[b] == Double.MAX_VALUE) return null
            val chain = ArrayList<Int>(); var c = b; while (c != -1) { chain.add(c); c = prev[c] }
            chain.reverse()
            val pts = ArrayList<DoubleArray>()
            for ((q, v) in chain.withIndex()) {
                val pt = doubleArrayOf(xs[v], ys[v], zs[v])
                if (q == 0 || q == chain.size - 1 || hypot(pt[0] - pts.last()[0], pt[1] - pts.last()[1]) >= SIMPLIFY_YD || abs(pt[2] - pts.last()[2]) >= SIMPLIFY_YD) pts.add(pt)
            }
            return pts
        }
        fun length(pts: List<DoubleArray>) = pts.zipWithNext { p, q -> sqrt((q[0] - p[0]).let { it * it } + (q[1] - p[1]).let { it * it } + (q[2] - p[2]).let { it * it }) }.sum()
    }

    /** the walkable floor of [parts] (placements with their groups' files) as one [Floor] */
    fun floor(parts: List<Pair<WorldBuildings.Placement, List<ByteArray>>>): Floor {
        val bucket = HashMap<Long, ArrayList<Int>>(); val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        val adj = ArrayList<HashSet<Int>>()
        fun bk(a: Long, b: Long, c: Long) = (a and 0x1FFFFFL) or ((b and 0x1FFFFFL) shl 21) or ((c and 0x1FFFFFL) shl 42)
        fun vid(x: Double, y: Double, z: Double): Int {
            val bx = Math.floor(x).toLong(); val by = Math.floor(y).toLong(); val bz = Math.floor(z).toLong()
            for (i in -1L..1L) for (j in -1L..1L) for (k in -1L..1L) bucket[bk(bx + i, by + j, bz + k)]?.forEach { v ->
                if ((xs[v] - x) * (xs[v] - x) + (ys[v] - y) * (ys[v] - y) + (zs[v] - z) * (zs[v] - z) <= WELD_YD * WELD_YD) return v
            }
            xs.add(x); ys.add(y); zs.add(z); adj.add(HashSet())
            bucket.getOrPut(bk(bx, by, bz)) { ArrayList() }.add(xs.size - 1)
            return xs.size - 1
        }
        for ((p, groups) in parts) {
            val cs = cos(p.yaw); val sn = Math.sin(p.yaw)
            fun w(lx: Float, ly: Float, lz: Float) = vid(p.ox - lx * p.scale * cs + ly * p.scale * sn, p.oy - lx * p.scale * sn - ly * p.scale * cs, p.oz + lz * p.scale)
            for (g in groups) {
                val t = floorTris(g)
                for (q in 0 until t.size / 9) {
                    val a = w(t[q * 9], t[q * 9 + 1], t[q * 9 + 2]); val b = w(t[q * 9 + 3], t[q * 9 + 4], t[q * 9 + 5]); val c = w(t[q * 9 + 6], t[q * 9 + 7], t[q * 9 + 8])
                    for ((u, v) in listOf(a to b, b to c, c to a)) if (u != v) { adj[u].add(v); adj[v].add(u) }
                }
            }
        }
        joinSteps(xs, ys, zs) { u, w -> adj[u].add(w); adj[w].add(u) }
        return Floor(xs.toDoubleArray(), ys.toDoubleArray(), zs.toDoubleArray(), Array(adj.size) { adj[it].toIntArray() })
    }

    private fun tag(b: ByteArray, o: Int) = String(charArrayOf(b[o + 3].toInt().toChar(), b[o + 2].toInt().toChar(), b[o + 1].toInt().toChar(), b[o].toInt().toChar()))
}
