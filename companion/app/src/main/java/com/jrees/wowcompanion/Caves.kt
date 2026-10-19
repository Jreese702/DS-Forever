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

    /**
     * the elevators' vertical travels, from the client's TransportAnimation rows ([rows]: each its transport (the row's parent) and
     * its position): a transport whose keyframes rise [LIFT_MIN_RISE_YD] or more while drifting under [LIFT_MAX_DRIFT_YD] across
     * is an elevator, its travel the rise. Derived on the device from the player's own files - never typed from game data.
     */
    fun liftTravels(rows: List<Pair<Int, FloatArray>>): List<Double> =
        rows.groupBy({ it.first }, { it.second }).values.mapNotNull { ps ->
            val zs = ps.map { it[2].toDouble() }; val rise = zs.max() - zs.min()
            val drift = hypot(ps.maxOf { it[0] } - ps.minOf { it[0] }.toDouble(), ps.maxOf { it[1] } - ps.minOf { it[1] }.toDouble())
            if (rise >= LIFT_MIN_RISE_YD && drift < LIFT_MAX_DRIFT_YD) rise else null
        }.distinctBy { Math.round(it * 2) }
    const val LIFT_MIN_RISE_YD = 20.0
    const val LIFT_MAX_DRIFT_YD = 3.0
    const val LIFT_COL_YD = 2.0            // the shaft search's columns
    const val LIFT_UNDER_TRAVEL_YD = 3.0   // a landing's rise is an elevator's travel or up to this much less (the car rides a little past
                                           // its landings: Thunder Bluff's 59.7 / 69.7 yd rises, 61.2 / 71.2 yd travels) ...
    const val LIFT_OVER_TRAVEL_YD = 0.5    // ... and never more than this over it
    const val LIFT_MIN_COLS = 8            // a shaft is at least this many columns (a ledge over a floor by chance is fewer)

    /**
     * the lifts of placement [p] ([groups] its group files): where its own floor has a landing straight over another, nothing
     * walkable between, the rise an elevator's travel ([travels], from [liftTravels]) - a lift's car is a server object, but its two
     * landings are the building's fixed floor (Thunder Bluff's lifts: its mesas' WMOs). Each lift at its shaft's middle, its landings'
     * heights. Derived at runtime from the player's own files: no lift is typed into the app. Asked only of the buildings of a city
     * with lifts (the caller's choice: a capital's own ground) - a cave's stacked floors match an elevator's travel by chance.
     */
    fun findLifts(p: WorldBuildings.Placement, groups: List<ByteArray>, travels: List<Double>): List<Lift> {
        if (travels.isEmpty()) return emptyList()
        val cols = HashMap<Long, ArrayList<Double>>()
        fun key(i: Long, j: Long) = (i shl 32) xor (j and 0xFFFFFFFFL)
        for (g in groups) {
            val t = floorTris(g)
            for (q in 0 until t.size / 9) {
                // the face's points: its corners and its middle, each in its column
                val xs = DoubleArray(4); val ys = DoubleArray(4); val zs = DoubleArray(4)
                for (k in 0..2) { val lx = t[q * 9 + k * 3]; val ly = t[q * 9 + k * 3 + 1]; val lz = t[q * 9 + k * 3 + 2]
                    val w = p.world(lx.toDouble(), ly.toDouble(), lz.toDouble()); xs[k] = w[0]; ys[k] = w[1]; zs[k] = w[2] }
                xs[3] = (xs[0] + xs[1] + xs[2]) / 3; ys[3] = (ys[0] + ys[1] + ys[2]) / 3; zs[3] = (zs[0] + zs[1] + zs[2]) / 3
                for (k in 0..3) cols.getOrPut(key(Math.floor(xs[k] / LIFT_COL_YD).toLong(), Math.floor(ys[k] / LIFT_COL_YD).toLong())) { ArrayList() }.add(zs[k])
            }
        }
        // the shaft columns: a floor height and the next one up, nothing between, their gap an elevator's travel
        class Hit(val i: Long, val j: Long, val lo: Double, val hi: Double)
        val hits = ArrayList<Hit>()
        for ((k, zs) in cols) {
            val sorted = zs.sorted()
            for (q in 0 until sorted.size - 1) {
                val gap = sorted[q + 1] - sorted[q]
                if (travels.any { gap >= it - LIFT_UNDER_TRAVEL_YD && gap <= it + LIFT_OVER_TRAVEL_YD }) hits.add(Hit(k shr 32, (k and 0xFFFFFFFFL).toInt().toLong(), sorted[q], sorted[q + 1]))
            }
        }
        // the shafts: neighbouring hit columns of about the same landings, LIFT_MIN_COLS or more
        val left = hits.toMutableList(); val out = ArrayList<Lift>()
        while (left.isNotEmpty()) {
            val seed = left.removeAt(0); val group = arrayListOf(seed); var grew = true
            while (grew) { grew = false
                val more = left.filter { h -> group.any { g -> abs(g.i - h.i) <= 1 && abs(g.j - h.j) <= 1 && abs(g.lo - h.lo) <= 2.0 && abs(g.hi - h.hi) <= 2.0 } }
                if (more.isNotEmpty()) { group.addAll(more); left.removeAll(more.toSet()); grew = true } }
            if (group.size < LIFT_MIN_COLS) continue
            val x = group.map { (it.i + 0.5) * LIFT_COL_YD }.average(); val y = group.map { (it.j + 0.5) * LIFT_COL_YD }.average()
            out.add(Lift("lift ${p.fid}", x, y, group.map { it.lo }.average(), group.map { it.hi }.average()))
        }
        return out
    }

    /** one way through: world points (x, y, z) from mouth [a] to mouth [b], [yd] long along its floor; a point with a 4th value 1 is a
     *  lift's landing (two in a row: the ride between them) */
    class Way(val pts: List<DoubleArray>, val yd: Double) {
        val a get() = pts.first()
        val b get() = pts.last()
    }
    /** a placed cave: its [mouths] (world x, y, z) and the [ways] between them */
    class Cave(val fid: Int, val uid: Int, val wx: Double, val wy: Double, val mouths: List<DoubleArray>, val ways: List<Way>,
               val floor: List<DoubleArray> = emptyList())          // its floor's footprint: a point (x, y, z) per FLOOR_SAMPLE_YD square

    /** a group file's MOGP flags (0x8: an outdoor group - a mine's entrance, a bridge; 0x2000: a room), 0 when unreadable */
    fun groupFlags(group: ByteArray): Int {
        var o = 0
        while (o + 8 <= group.size) {
            val n = Db2.le32(group, o + 4); if (n < 0 || o + 8 + n > group.size) break
            if (tag(group, o) == "MOGP") return if (n >= 12) Db2.le32(group, o + 8 + 8) else 0
            o += 8 + n
        }
        return 0
    }

    /** a group file's walkable collision triangles, in the WMO's own frame: 9 floats each (three corners x, y, z) */
    fun floorTris(group: ByteArray): FloatArray {
        // MVER, then MOGP: its 0x44-byte header, then its own chunks
        var o = 0
        while (o + 8 <= group.size) {
            val n = Db2.le32(group, o + 4); if (n < 0 || o + 8 + n > group.size) break
            if (tag(group, o) == "MOGP") return trisIn(group, o + 8 + 0x44, o + 8 + n, Db2.le32(group, o + 8 + 0x34))
            o += 8 + n
        }
        return FloatArray(0)
    }
    /**
     * THE GROUP'S OWN LAVA (its MLIQ: Searing Gorge's Cauldron, Blackrock Mountain - lava inside a WMO the water grid never sees): its
     * tiles of a liquid no one wades (the group's liquid type [groupLiquid] in [Terrain.lethal], or a legacy tile type 2 magma / 3 slime)
     * and their surface; a floor triangle under one is no floor
     */
    private class Lava(val x0: Float, val y0: Float, val nx: Int, val ny: Int, val top: FloatArray) {
        fun covers(x: Float, y: Float, z: Float): Boolean {
            val i = Math.floor(((x - x0) / Climb.STEP).toDouble()).toInt(); val j = Math.floor(((y - y0) / Climb.STEP).toDouble()).toInt()
            if (i !in 0 until nx || j !in 0 until ny) return false
            val t = top[j * nx + i]; return !t.isNaN() && z <= t + 0.5f
        }
    }
    private fun lavaIn(b: ByteArray, at: Int, size: Int, groupLiquid: Int): Lava? {
        if (size < 30) return null
        val xv = Db2.le32(b, at); val yv = Db2.le32(b, at + 4); val xt = Db2.le32(b, at + 8); val yt = Db2.le32(b, at + 12)
        if (xv <= 0 || yv <= 0 || xt <= 0 || yt <= 0 || xv * yv * 8 + xt * yt + 30 > size) return null
        val cx = java.lang.Float.intBitsToFloat(Db2.le32(b, at + 16)); val cy = java.lang.Float.intBitsToFloat(Db2.le32(b, at + 20))
        val vAt = at + 30; val tAt = vAt + xv * yv * 8
        val groupDeadly = groupLiquid in Terrain.lethal
        val top = FloatArray(xt * yt) { Float.NaN }; var any = false
        for (j in 0 until yt) for (i in 0 until xt) {
            val f = b[tAt + j * xt + i].toInt() and 0xFF
            if (f and 0x0F == 0x0F) continue                                      // no liquid on this tile
            if (!groupDeadly && (f and 0x03) != 2 && (f and 0x03) != 3) continue   // water: the water grid's business
            fun h(ii: Int, jj: Int) = java.lang.Float.intBitsToFloat(Db2.le32(b, vAt + (jj * xv + ii) * 8 + 4))
            top[j * xt + i] = maxOf(h(i, j), h(i + 1, j), h(i, j + 1), h(i + 1, j + 1)); any = true
        }
        return if (any) Lava(cx, cy, xt, yt, top) else null
    }
    private fun trisIn(b: ByteArray, from: Int, end: Int, groupLiquid: Int = 0): FloatArray {
        var mopy = -1; var nPoly = 0; var polyStride = 2; var movi = -1; var nIdx = 0; var movt = -1; var nVert = 0
        var lava: Lava? = null
        var o = from
        while (o + 8 <= end) {
            val n = Db2.le32(b, o + 4); if (n < 0 || o + 8 + n > end) break
            when (tag(b, o)) {
                "MOPY" -> { mopy = o + 8; nPoly = n / 2; polyStride = 2 }
                "MPY2" -> { mopy = o + 8; nPoly = n / 4; polyStride = 4 }      // the newer groups: a face's flags (16 bits), its material
                "MOVI" -> { movi = o + 8; nIdx = n / 2 }
                "MOVT" -> { movt = o + 8; nVert = n / 12 }
                "MLIQ" -> lava = lavaIn(b, o + 8, n, groupLiquid)
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
            if (lava != null && lava!!.covers((v(ix[0], 0) + v(ix[1], 0) + v(ix[2], 0)) / 3, (v(ix[0], 1) + v(ix[1], 1) + v(ix[2], 1)) / 3,
                    (v(ix[0], 2) + v(ix[1], 2) + v(ix[2], 2)) / 3)) continue           // under the group's own lava
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
        // the floor's vertices, welded; its edges
        val bucket = HashMap<Long, ArrayList<Int>>(); val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        val adj = ArrayList<ArrayList<Int>>()
        fun bk(a: Long, b: Long, c: Long) = (a and 0x1FFFFFL) or ((b and 0x1FFFFFL) shl 21) or ((c and 0x1FFFFFL) shl 42)
        fun vid(lx: Float, ly: Float, lz: Float): Int {
            val w = p.world(lx.toDouble(), ly.toDouble(), lz.toDouble()); val x = w[0]; val y = w[1]; val z = w[2]
            val bx = Math.floor(x).toLong(); val by = Math.floor(y).toLong(); val bz = Math.floor(z).toLong()
            for (a in -1L..1L) for (b in -1L..1L) for (c in -1L..1L) bucket[bk(bx + a, by + b, bz + c)]?.forEach { v ->
                if ((xs[v] - x) * (xs[v] - x) + (ys[v] - y) * (ys[v] - y) + (zs[v] - z) * (zs[v] - z) <= WELD_YD * WELD_YD) return v
            }
            xs.add(x); ys.add(y); zs.add(z); adj.add(ArrayList())
            bucket.getOrPut(bk(bx, by, bz)) { ArrayList() }.add(xs.size - 1)
            return xs.size - 1
        }
        val faces = ArrayList<IntArray>()
        // the outdoor groups' vertices: a mine's entrance (a deep floor meeting the terrain's height under a hill's far side is no mouth:
        // Westfall's Jangolode, 120 yd inside)
        val outdoor = HashSet<Int>(); var anyOutdoor = false
        for (g in groups) {
            val t = floorTris(g)
            val isOut = groupFlags(g) and 0x8 != 0; if (isOut) anyOutdoor = true
            for (q in 0 until t.size / 9) {
                val a = vid(t[q * 9], t[q * 9 + 1], t[q * 9 + 2]); val b = vid(t[q * 9 + 3], t[q * 9 + 4], t[q * 9 + 5]); val c = vid(t[q * 9 + 6], t[q * 9 + 7], t[q * 9 + 8])
                if (isOut) { outdoor.add(a); outdoor.add(b); outdoor.add(c) }
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
        val meets = (0 until n).filter { v -> val h = ground(xs[v], ys[v]); !h.isNaN() && abs(h - zs[v]) <= MOUTH_YD && standable(xs[v], ys[v]) &&
            (!anyOutdoor || v in outdoor) }
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
            fun w(lx: Float, ly: Float, lz: Float) = p.world(lx.toDouble(), ly.toDouble(), lz.toDouble()).let { vid(it[0], it[1], it[2]) }
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
