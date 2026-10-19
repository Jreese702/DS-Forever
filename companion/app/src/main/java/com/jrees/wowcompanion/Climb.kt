package com.jrees.wowcompanion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * THE GROUND AT ITS OWN GRAIN (b-route-climb; the user on Durotar's test routes: "Some of the paths are still going through a wall (for
 * example b6 to b7)"; Durotar's hills are clusters of steep cones, and the 25 yd slope grid's MEAN slope blurred a cone's wall into a
 * climb). The terrain's own vertex lattice (4.2 yd: an ADT's outer heights): between each vertex and its east and south neighbours, the
 * step's class - level (under 20 degrees), a climb (to 35), steep (to 45), a WALL (45 or steeper, or nothing known: the user, "I cant
 * walk up walls no matter how much you seem to think I can" - Classic stops a climb near 50 and slides a player earlier, so 45 with
 * the margin; a wall is never crossed, whatever it would save, and the gentler classes cost only a little more). A route's leg is planned
 * on it (A* by the lattice, its bends straightened only along a line of sight that never crosses a wall), and a goal on ground no
 * player can reach (a cone's top) is walked to the nearest spot one can stand on. Built by the terrain step from the heights it reads;
 * kept on the device only (terrain/<map>.clb). Pure but for the file.
 */
object Climb {
    const val STEP = 100.0 / 3.0 / 8.0          // the lattice: an ADT chunk's 8 cells
    private const val ORG = 32.0 * MapHarvest.ADT_YD
    const val LEVEL = 0; const val CLIMB = 1; const val STEEP = 2; const val WALL = 3
    const val CLIMB_DEG = 20.0; const val STEEP_DEG = 35.0; const val WALL_DEG = 45.0
    /** a step's cost per yard by its class (a wall: none) */
    val COST = doubleArrayOf(1.0, 1.3, 2.0, Double.NaN)

    fun classOf(dh: Float, run: Double = STEP): Int {
        if (dh.isNaN()) return WALL
        val deg = Math.toDegrees(atan(abs(dh) / run))
        return when { deg < CLIMB_DEG -> LEVEL; deg < STEEP_DEG -> CLIMB; deg < WALL_DEG -> STEEP; else -> WALL }
    }

    /**
     * one zone's lattice: its first vertex at global row [gr0] / column [gc0] (rows south from the map's top, columns east), [nx] x [ny]
     * vertices; [cls] each vertex's east step (bits 0-1) and south step (bits 2-3)
     */
    class Grid(val gr0: Int, val gc0: Int, val nx: Int, val ny: Int, val cls: ByteArray, val faces: ByteArray? = null) {
        /** the class of the terrain triangle under world ([wx], [wy]) ([faces]: each cell's north, east, south, west triangles, 2 bits
         *  each); LEVEL when the faces are not known, a WALL off the grid */
        fun faceAt(wx: Double, wy: Double): Int {
            val f = faces ?: return LEVEL
            val gx = (ORG - wy) / STEP - gc0; val gy = (ORG - wx) / STEP - gr0
            val i = floor(gx).toInt(); val j = floor(gy).toInt()
            if (i < 0 || j < 0 || i >= nx || j >= ny) return WALL
            val fx = gx - i; val fy = gy - j
            val t = when { fy <= fx && fy <= 1 - fx -> 0; fx >= fy && fx >= 1 - fy -> 1; fy >= fx && fy >= 1 - fx -> 2; else -> 3 }
            return (f[j * nx + i].toInt() shr (2 * t)) and 3
        }
        /** the straight line from world [a] to [b] crosses no wall face (sampled each half yard; at each, the gentlest face within a
         *  quarter yard - a line along an edge is walked on its gentler side) */
        fun lineClear(a: DoubleArray, b: DoubleArray): Boolean {
            if (faces == null) return true
            val n = max(1, (hypot(b[0] - a[0], b[1] - a[1]) / 0.5).toInt())
            for (q in 0..n) {
                val x = a[0] + (b[0] - a[0]) * q / n; val y = a[1] + (b[1] - a[1]) * q / n
                if (minOf(faceAt(x, y), faceAt(x + 0.25, y), faceAt(x - 0.25, y), faceAt(x, y + 0.25), faceAt(x, y - 0.25)) == WALL) return false
            }
            return true
        }
        fun wx(j: Int) = ORG - (gr0 + j) * STEP
        fun wy(i: Int) = ORG - (gc0 + i) * STEP
        /** the vertex nearest world ([wx], [wy]) (column, row), clamped onto the grid */
        fun at(wx: Double, wy: Double): IntArray = intArrayOf((((ORG - wy) / STEP).let { Math.round(it).toInt() } - gc0).coerceIn(0, nx - 1),
            (((ORG - wx) / STEP).let { Math.round(it).toInt() } - gr0).coerceIn(0, ny - 1))
        fun east(i: Int, j: Int): Int = if (i < 0 || j < 0 || i >= nx - 1 || j >= ny) WALL else cls[j * nx + i].toInt() and 3
        fun south(i: Int, j: Int): Int = if (i < 0 || j < 0 || i >= nx || j >= ny - 1) WALL else (cls[j * nx + i].toInt() shr 2) and 3
        /** the class of the step from (i, j) by (di, dj), one of the eight: a diagonal by the easier of its two corners' pairs */
        fun step(i: Int, j: Int, di: Int, dj: Int): Int {
            fun h(a: Int, b: Int, d: Int) = if (d > 0) east(a, b) else east(a - 1, b)       // the east-west step from (a, b) by d
            fun v(a: Int, b: Int, d: Int) = if (d > 0) south(a, b) else south(a, b - 1)
            return when {
                dj == 0 -> h(i, j, di)
                di == 0 -> v(i, j, dj)
                else -> {
                    val corners = min(max(h(i, j, di), v(i + di, j, dj)), max(v(i, j, dj), h(i, j + dj, di)))
                    // straight across the cell, through its middle vertex: each half of the diagonal walked on the gentler face beside it
                    val f = faces ?: return corners
                    val ci = min(i, i + di); val cj = min(j, j + dj)
                    if (ci < 0 || cj < 0 || ci >= nx || cj >= ny) return WALL
                    val c = f[cj * nx + ci].toInt(); val nN = c and 3; val nE = (c shr 2) and 3; val nS = (c shr 4) and 3; val nW = (c shr 6) and 3
                    val across = if (di == dj) max(min(nN, nW), min(nE, nS)) else max(min(nN, nE), min(nS, nW))
                    max(corners, across)
                }
            }
        }
        fun write(f: File) {
            f.parentFile?.mkdirs()
            DataOutputStream(GZIPOutputStream(f.outputStream().buffered())).use { o ->
                o.writeInt(MAGIC); o.writeInt(gr0); o.writeInt(gc0); o.writeInt(nx); o.writeInt(ny); o.write(cls)
                o.write(faces ?: ByteArray(nx * ny))
            }
        }
        companion object {
            const val MAGIC = 0x434C4232                       // "CLB2": the steps, then the cells' faces
            fun read(f: File): Grid? = runCatching {
                DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { i ->
                    if (i.readInt() != MAGIC) return null
                    val gr0 = i.readInt(); val gc0 = i.readInt(); val nx = i.readInt(); val ny = i.readInt()
                    Grid(gr0, gc0, nx, ny, ByteArray(nx * ny).also { i.readFully(it) }, ByteArray(nx * ny).also { i.readFully(it) })
                }
            }.getOrNull()
        }
    }

    /** the lattice over world rect [t] / [b] / [l] / [r] from the vertex heights ([height]: global row, column -> yards; NaN none);
     *  [level] a spot a building's walkway makes level (a city's street, a bridge: its steps level, whatever the terrain under it) */
    fun build(t: Double, b: Double, l: Double, r: Double, height: (Int, Int) -> Float, level: ((Double, Double) -> Boolean)? = null,
              hole: ((Int, Int) -> Boolean)? = null, centre: ((Int, Int) -> Float)? = null): Grid {
        val gr0 = floor((ORG - t) / STEP).toInt(); val gr1 = ceil((ORG - b) / STEP).toInt()
        val gc0 = floor((ORG - l) / STEP).toInt(); val gc1 = ceil((ORG - r) / STEP).toInt()
        val nx = gc1 - gc0 + 1; val ny = gr1 - gr0 + 1
        val cls = ByteArray(nx * ny)
        val faces = if (centre != null) ByteArray(nx * ny) else null
        for (j in 0 until ny) for (i in 0 until nx) {
            val h = height(gr0 + j, gc0 + i)
            var e = classOf(height(gr0 + j, gc0 + i + 1) - h); var s = classOf(height(gr0 + j + 1, gc0 + i) - h)
            // the game's own surface (the user on Elwynn: "Jasperlode mine path literally walks up and down a mountain thay cant be
            // climbed"): each cell four triangles through its middle vertex ([centre]: global row, column of a cell), a ridge between
            // two outer vertices standing on the middle ones. A step along an edge is walked on the gentler of the two faces beside it.
            if (centre != null) {
                val gr = gr0 + j; val gc = gc0 + i
                val hE = height(gr, gc + 1); val hS = height(gr + 1, gc)
                e = maxOf(e, minOf(faceClass(h, hE, centre(gr - 1, gc)), faceClass(h, hE, centre(gr, gc))))
                s = maxOf(s, minOf(faceClass(h, hS, centre(gr, gc - 1)), faceClass(h, hS, centre(gr, gc))))
            }
            // a step between two holed cells (the terrain opened into a cave: [hole] global row, column of a cell) has no ground
            if (hole != null) {
                if (hole(gr0 + j - 1, gc0 + i) && hole(gr0 + j, gc0 + i)) e = WALL
                if (hole(gr0 + j, gc0 + i - 1) && hole(gr0 + j, gc0 + i)) s = WALL
            }
            if (level != null && (e != LEVEL || s != LEVEL) && level(ORG - (gr0 + j) * STEP, ORG - (gc0 + i) * STEP)) { e = LEVEL; s = LEVEL }
            cls[j * nx + i] = (e or (s shl 2)).toByte()
            // the cell's own four triangles (north, east, south, west of its middle vertex): a straight line across it meets them
            if (faces != null) {
                val gr = gr0 + j; val gc = gc0 + i
                val ha = h; val hb = height(gr, gc + 1); val hc = height(gr + 1, gc); val hd = height(gr + 1, gc + 1); val m = centre!!(gr, gc)
                val f = when {
                    hole?.invoke(gr, gc) == true -> WALL or (WALL shl 2) or (WALL shl 4) or (WALL shl 6)
                    level != null && level(ORG - (gr + 0.5) * STEP, ORG - (gc + 0.5) * STEP) -> 0
                    else -> faceClass(ha, hb, m) or (faceClass(hb, hd, m) shl 2) or (faceClass(hc, hd, m) shl 4) or (faceClass(ha, hc, m) shl 6)
                }
                faces[j * nx + i] = f.toByte()
            }
        }
        return Grid(gr0, gc0, nx, ny, cls, faces)
    }

    /** the class of the triangle on an edge between two outer vertices ([a], [b]: STEP apart) and a cell's middle vertex [m] (half a
     *  STEP off the edge, at its middle): by its face's own slope, its normal's angle from the vertical; NaN (no middle) the edge's own */
    fun faceClass(a: Float, b: Float, m: Float): Int {
        if (a.isNaN() || b.isNaN()) return WALL
        if (m.isNaN()) return classOf(b - a)
        // the edge along x (0..STEP, heights a, b), the middle at (STEP / 2, STEP / 2, m): the normal of (STEP, 0, b - a) x (STEP / 2, STEP / 2, m - a)
        val ux = STEP; val uz = (b - a).toDouble(); val vx = STEP / 2; val vy = STEP / 2; val vz = (m - a).toDouble()
        val nx = -uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy
        val deg = Math.toDegrees(Math.acos(abs(nz) / Math.sqrt(nx * nx + ny * ny + nz * nz)))
        return when { deg < CLIMB_DEG -> LEVEL; deg < STEEP_DEG -> CLIMB; deg < WALL_DEG -> STEEP; else -> WALL }
    }

    /** a planned leg: world points (x, y) from the start; [reached] false when the goal stands on ground no one can walk to (the leg
     *  ends at the nearest spot that can) */
    class Leg(val pts: List<DoubleArray>, val reached: Boolean)

    /** a way the lattice does not see: a cave's or a tunnel's floor between two mouths ([Caves.Way]: world x, y, z points; [yd] along) */
    class Link(val pts: List<DoubleArray>, val yd: Double, val oneWay: Boolean = false)    // oneWay: a portal, an updraft - from its first point only

    /** a point of a leg inside a cave: world x, y and this mark (its ground is the cave's floor, not the terrain over it) */
    const val IN_CAVE = 1.0
    /** a lift's landing on a link's way (a point marked so: "take the lift" there; the ride to the next such point) - inside, as a cave's */
    const val LIFT = 2.0
    fun inCave(p: DoubleArray) = p.size > 2 && (p[2] == IN_CAVE || p[2] == LIFT)
    fun atLift(p: DoubleArray) = p.size > 2 && p[2] == LIFT

    /**
     * the leg from world [from] to [to] on [g]: A* over the lattice within [MARGIN_YD] of the two, its cost each step's length by its
     * class (a wall never), plus [k] x [danger] there (the danger grid's, 0..4) and [water] a wall (deep water; null = none known);
     * then its bends straightened along lines of sight that cross no wall and cost no more. Null when the start itself is off the grid.
     */
    fun route(g: Grid, from: DoubleArray, to: DoubleArray, danger: (Double, Double) -> Float = { _, _ -> 0f }, k: Double = 0.0,
              water: ((Double, Double) -> Boolean)? = null, links: List<Link> = emptyList()): Leg? {
        val s = g.at(from[0], from[1]); val e = g.at(to[0], to[1])
        val m = (MARGIN_YD / STEP).toInt()
        val i0 = max(0, min(s[0], e[0]) - m); val i1 = min(g.nx - 1, max(s[0], e[0]) + m)
        val j0 = max(0, min(s[1], e[1]) - m); val j1 = min(g.ny - 1, max(s[1], e[1]) + m)
        val w = i1 - i0 + 1; val hgt = j1 - j0 + 1
        fun id(i: Int, j: Int) = (j - j0) * w + (i - i0)
        val dist = DoubleArray(w * hgt) { Double.MAX_VALUE }; val prev = IntArray(w * hgt) { -1 }
        val stepCost = HashMap<Long, Double>()
        // the mobs a cost, never a wall (the user: "some quests do expect the player to have to kill through mobs to get to the
        // destination"): the danger fades toward the goal, to none at it - over [GOAL_FADE_YD], twice that when the goal's own spot is
        // dangerous (a quest's mobs in a ravine, a cave full of them) - so the way avoids them on the way and goes in straight at the end
        val fade = if (danger(to[0], to[1]) >= GOAL_DANGEROUS) GOAL_FADE_YD * 2 else GOAL_FADE_YD
        fun cellCost(i: Int, j: Int): Double {
            val x = g.wx(j); val y = g.wy(i)
            if (water?.invoke(x, y) == true) return Double.NaN
            return k * danger(x, y) * (hypot(x - to[0], y - to[1]) / fade).coerceAtMost(1.0)
        }
        val extra = DoubleArray(w * hgt) { Double.NaN }
        fun extraOf(i: Int, j: Int): Double { val q = id(i, j); if (extra[q].isNaN()) extra[q] = cellCost(i, j).let { if (it.isNaN()) -1.0 else it }; return extra[q] }
        val start = id(s[0], s[1]); val goal = id(e[0], e[1])
        // the caves' links with both mouths in the window: from a mouth's vertex to the other's, at their floor's length (and danger)
        val jumps = HashMap<Int, ArrayList<IntArray>>()
        val linkCost = DoubleArray(links.size)
        for ((li, lk) in links.withIndex()) {
            val p = g.at(lk.pts.first()[0], lk.pts.first()[1]); val q = g.at(lk.pts.last()[0], lk.pts.last()[1])
            if (p[0] !in i0..i1 || p[1] !in j0..j1 || q[0] !in i0..i1 || q[1] !in j0..j1) continue
            linkCost[li] = lk.yd + k * lk.pts.zipWithNext { a, b -> val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
                hypot(b[0] - a[0], b[1] - a[1]) * danger(mx, my) * (hypot(mx - to[0], my - to[1]) / fade).coerceAtMost(1.0) }.sum()
            jumps.getOrPut(id(p[0], p[1])) { ArrayList() }.add(intArrayOf(id(q[0], q[1]), li, 0))
            if (!lk.oneWay) jumps.getOrPut(id(q[0], q[1])) { ArrayList() }.add(intArrayOf(id(p[0], p[1]), li, 1))
        }
        val via = IntArray(w * hgt) { -1 }                     // how a vertex was reached: a link (its index x 2 + its way back) or a step
        fun hEst(i: Int, j: Int) = hypot((i - e[0]).toDouble(), (j - e[1]).toDouble()) * STEP
        val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
        dist[start] = 0.0; open.add(doubleArrayOf(hEst(s[0], s[1]), start.toDouble()))
        var best = start; var bestD = hEst(s[0], s[1])
        while (open.isNotEmpty()) {
            val top = open.poll(); val u = top[1].toInt()
            val ui = u % w + i0; val uj = u / w + j0
            if (top[0] - hEst(ui, uj) > dist[u] + 1e-6) continue
            val hd = hEst(ui, uj); if (hd < bestD) { bestD = hd; best = u }
            if (u == goal) break
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val vi = ui + di; val vj = uj + dj
                if (vi !in i0..i1 || vj !in j0..j1) continue
                val c = g.step(ui, uj, di, dj); if (c == WALL) continue
                // the step's own straight line, as the final check walks it (walkable / segcheck): a turn at a vertex every face within
                // a quarter yard of which is a wall passes the steps beside it but not the line (Tirisfal, Brill -> Agamand Mills)
                if (g.faces != null && !g.lineClear(doubleArrayOf(g.wx(uj), g.wy(ui)), doubleArrayOf(g.wx(vj), g.wy(vi)))) continue
                // and off deep water along it (a diagonal between two shallow vertices clips a deep cell's corner: Mulgore's route D)
                if (water != null && (1..3).any { q -> water(g.wx(uj) + (g.wx(vj) - g.wx(uj)) * q / 4, g.wy(ui) + (g.wy(vi) - g.wy(ui)) * q / 4) }) continue
                val x = extraOf(vi, vj); if (x < 0) continue
                val len = if (di != 0 && dj != 0) STEP * 1.41421356 else STEP
                val v = id(vi, vj)
                val nd = dist[u] + len * (COST[c] + x)
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; via[v] = -1; open.add(doubleArrayOf(nd + hEst(vi, vj), v.toDouble())) }
            }
            jumps[u]?.forEach { jp ->
                val v = jp[0]; val nd = dist[u] + linkCost[jp[1]]
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; via[v] = jp[1] * 2 + jp[2]; open.add(doubleArrayOf(nd + hEst(v % w + i0, v / w + j0), v.toDouble())) }
            }
        }
        val end = if (dist[goal] < Double.MAX_VALUE) goal else best
        val path = ArrayList<IntArray>()
        var c = end
        while (c != -1) { path.add(intArrayOf(c % w + i0, c / w + j0, via[c])); c = prev[c] }
        path.reverse()
        if (path.isEmpty()) return null
        // the bends: from each kept vertex, the farthest later one a straight line reaches crossing no wall and costing no more
        fun lineCost(a: IntArray, b: IntArray): Double {
            val n = max(abs(b[0] - a[0]), abs(b[1] - a[1]))
            if (n == 0) return 0.0
            var ci = a[0]; var cj = a[1]; var cost = 0.0
            for (q in 1..n) {
                val ni = a[0] + Math.round((b[0] - a[0]).toDouble() * q / n).toInt(); val nj = a[1] + Math.round((b[1] - a[1]).toDouble() * q / n).toInt()
                val di = sign((ni - ci).toDouble()).toInt(); val dj = sign((nj - cj).toDouble()).toInt()
                if (di == 0 && dj == 0) continue
                val cl = g.step(ci, cj, di, dj); if (cl == WALL) return Double.MAX_VALUE
                if (ni !in i0..i1 || nj !in j0..j1) return Double.MAX_VALUE
                if (q == n && n > 1 && !g.lineClear(doubleArrayOf(g.wx(a[1]), g.wy(a[0])), doubleArrayOf(g.wx(b[1]), g.wy(b[0])))) return Double.MAX_VALUE
                if (q == n && n > 1 && water != null) {                 // the straight line off deep water all along, not only at its vertices
                    val ax = g.wx(a[1]); val ay = g.wy(a[0]); val bx = g.wx(b[1]); val by = g.wy(b[0])
                    val m = max(1, (hypot(bx - ax, by - ay) / 2.0).toInt())
                    for (t in 1 until m) if (water(ax + (bx - ax) * t / m, ay + (by - ay) * t / m)) return Double.MAX_VALUE
                }
                val x = extraOf(ni, nj); if (x < 0) return Double.MAX_VALUE
                cost += (if (di != 0 && dj != 0) STEP * 1.41421356 else STEP) * (COST[cl] + x)
                ci = ni; cj = nj
            }
            return cost
        }
        val along = DoubleArray(path.size)
        for (q in 1 until path.size) along[q] = along[q - 1] + lineCost(path[q - 1], path[q]).let { if (it == Double.MAX_VALUE) 0.0 else it }
        // straightened a stretch of terrain at a time: a line never jumps a cave (each link's two mouths kept, its floor between)
        val pts = ArrayList<DoubleArray>()
        var a = 0
        fun vert(q: Int) = doubleArrayOf(g.wx(path[q][1]), g.wy(path[q][0]))
        pts.add(vert(0))
        while (a < path.size - 1) {
            if (path[a + 1][2] >= 0) {
                val lk = links[path[a + 1][2] / 2]
                val way = if (path[a + 1][2] % 2 == 0) lk.pts else lk.pts.reversed()
                for (p in way) pts.add(doubleArrayOf(p[0], p[1], if (p.size > 3 && p[3] == 1.0) LIFT else IN_CAVE))
                pts.add(vert(a + 1)); a++; continue
            }
            var stop = a + 1
            while (stop < path.size - 1 && path[stop + 1][2] < 0) stop++
            var b = stop
            while (b > a + 1 && lineCost(path[a], path[b]) > (along[b] - along[a]) * 1.03 + 1.0) b--
            pts.add(vert(b)); a = b
        }
        // the leg's own ends: the start's and the goal's vertices kept (the lines were checked from them), the exact points a short hop
        // beyond (within their cell)
        if (hypot(pts[0][0] - from[0], pts[0][1] - from[1]) > 0.5) pts.add(0, from) else pts[0] = from
        val reached = end == goal
        // the goal's exact point only where the hop to it crosses no wall face (a network point 2 yd up a cliff, an unreachable goal):
        // else the leg ends at its last vertex, short of it
        var arrived = reached
        // a goal reached by a link (a portal's, an updraft's, a floor's in the air): the leg ends on the link's own last point
        if (reached && pts.size >= 2 && inCave(pts[pts.size - 2]) && !inCave(pts.last()) && hypot(pts[pts.size - 2][0] - to[0], pts[pts.size - 2][1] - to[1]) <= 3.0)
            pts.removeAt(pts.size - 1)
        if (reached && !inCave(pts[pts.size - 1])) {
            if (hypot(pts.last()[0] - to[0], pts.last()[1] - to[1]) <= 0.5) {
                // even the last half yard re-checked: the point before must reach the goal itself (Darkshore's route J)
                if (pts.size < 2 || g.lineClear(pts[pts.size - 2], to)) pts[pts.size - 1] = to else arrived = false
            }
            else if (g.lineClear(pts.last(), to)) pts.add(to) else arrived = false
        }
        return Leg(pts, arrived)
    }
    const val MARGIN_YD = 800.0
    const val GOAL_FADE_YD = 100.0
    const val GOAL_DANGEROUS = 1f              // a goal's spot this dangerous (the grid's 0..4) fades its danger over twice as far

    /** whether the straight line from world [a] to [b] on [g] crosses no wall: the lattice's own steps along it (a way the network
     *  drew from the 25 yd cells can still climb a cliff those cells averaged away) */
    fun walkable(g: Grid, a: DoubleArray, b: DoubleArray, water: ((Double, Double) -> Boolean)? = null): Boolean {
        if (inCave(a) || inCave(b)) return true                 // a cave's floor: walkable by its own triangles, not the hill over it
        // deep water (the user: "Pathing shouldn't be in water"): a straight piece across it is no walk either (Mulgore's Stonebull Lake:
        // a network way's hooked end cut straight across it)
        if (water != null) {
            val n = max(1, (hypot(b[0] - a[0], b[1] - a[1]) / 2.0).toInt())
            for (q in 0..n) if (water(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n)) return false
        }
        val s = g.at(a[0], a[1]); val e = g.at(b[0], b[1])
        val n = max(abs(e[0] - s[0]), abs(e[1] - s[1]))
        var ci = s[0]; var cj = s[1]
        for (q in 1..n) {
            val ni = s[0] + Math.round((e[0] - s[0]).toDouble() * q / n).toInt(); val nj = s[1] + Math.round((e[1] - s[1]).toDouble() * q / n).toInt()
            val di = sign((ni - ci).toDouble()).toInt(); val dj = sign((nj - cj).toDouble()).toInt()
            if (di == 0 && dj == 0) continue
            if (g.step(ci, cj, di, dj) == WALL) return false
            ci = ni; cj = nj
        }
        return g.lineClear(a, b)
    }

    /** the zones' lattices (the terrain step's), kept in memory */
    val files = RouteWork.Files { map -> Grid.read(ArtHarvest.file("terrain/$map.clb")) }
}
