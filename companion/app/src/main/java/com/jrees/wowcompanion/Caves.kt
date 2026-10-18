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
    const val WELD_YD = 0.6                // floor vertices this near are one (the groups meet at their portals, not always exactly)
    const val MOUTH_YD = 1.5               // a floor vertex this near the terrain's own height is where they meet
    const val MOUTH_GAP_YD = 25.0          // mouths farther apart than this are separate openings
    const val SIMPLIFY_YD = 3.0            // a way's points kept at least this far apart

    /** the WMO name parts that mark a cave, a tunnel, a mine, a den */
    val KINDS = listOf("cave", "tunnel", "mine", "den")

    /** one way through: world points (x, y, z) from mouth [a] to mouth [b], [yd] long along its floor */
    class Way(val pts: List<DoubleArray>, val yd: Double) {
        val a get() = pts.first()
        val b get() = pts.last()
    }
    /** a placed cave: its [mouths] (world x, y, z) and the [ways] between them */
    class Cave(val fid: Int, val uid: Int, val wx: Double, val wy: Double, val mouths: List<DoubleArray>, val ways: List<Way>)

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
    fun cave(p: WorldBuildings.Placement, groups: List<ByteArray>, ground: (Double, Double) -> Float, standable: (Double, Double) -> Boolean): Cave {
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
        for (g in groups) {
            val t = floorTris(g)
            for (q in 0 until t.size / 9) {
                val a = vid(t[q * 9], t[q * 9 + 1], t[q * 9 + 2]); val b = vid(t[q * 9 + 3], t[q * 9 + 4], t[q * 9 + 5]); val c = vid(t[q * 9 + 6], t[q * 9 + 7], t[q * 9 + 8])
                for ((u, w) in listOf(a to b, b to c, c to a)) if (u != w) { adj[u].add(w); adj[w].add(u) }
            }
        }
        val n = xs.size
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
            if (mouths.none { m -> comp[m] == comp[v] && hypot(xs[m] - xs[v], ys[m] - ys[v]) < MOUTH_GAP_YD }) mouths.add(v)
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
                    val d = dist[u] + sqrt((xs[w] - xs[u]).let { it * it } + (ys[w] - ys[u]).let { it * it } + (zs[w] - zs[u]).let { it * it })
                    if (d < dist[w]) { dist[w] = d; prev[w] = u; pq.add(doubleArrayOf(d, w.toDouble())) }
                }
            }
            for (b in later) {
                if (dist[b] == Double.MAX_VALUE) continue
                val chain = ArrayList<Int>(); var c = b; while (c != -1) { chain.add(c); c = prev[c] }
                chain.reverse()
                val pts = ArrayList<DoubleArray>()
                for ((q, v) in chain.withIndex()) {
                    val pt = doubleArrayOf(xs[v], ys[v], zs[v])
                    if (q == 0 || q == chain.size - 1 || hypot(pt[0] - pts.last()[0], pt[1] - pts.last()[1]) >= SIMPLIFY_YD) pts.add(pt)
                }
                ways.add(Way(pts, dist[b]))
            }
        }
        return Cave(p.fid, p.uid, p.ox, p.oy, mouths.map { doubleArrayOf(xs[it], ys[it], zs[it]) }, ways)
    }

    private fun tag(b: ByteArray, o: Int) = String(charArrayOf(b[o + 3].toInt().toChar(), b[o + 2].toInt().toChar(), b[o + 1].toInt().toChar(), b[o].toInt().toChar()))
}
