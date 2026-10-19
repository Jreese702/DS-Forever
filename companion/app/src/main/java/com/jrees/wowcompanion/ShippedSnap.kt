package com.jrees.wowcompanion

import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * THE ATTACH SNAP onto the shipped network (the user, 2026-10-19: "Ship our path network"; DEVICE_INTEGRATION_PLAN.md SHIPPED NETWORK
 * step 3). The parity test (helper W's shipped.txt) found the attach legs over the device's 25 yd ground grid crossing walls (10) and deep
 * water (2): a grid cell is read at its middle, so a building's wall, a cliff's face or a stream between two middles was never seen.
 *
 * The snap: from the player, and from the goal, to the NEAREST NODE of the shipped graph (the net's ways, its joins, the ROADMAP's ~40 yd
 * coverage nodes) within [NEAR_YD]..[SNAP_YD], over the device's ground READ FINE ([Ground]: every [LINE_STEP_YD] along a line, a
 * [CELL_YD] cell's walk):
 * - NEVER through a building's room ([Ground.room]: the placed buildings' own outlines, and the terrain step's cover grid at
 *   [Buildings.WALL_COVER]), a cliff ([Terrain.WALL]) or a steep bank ([WALL]: Terrain.legFactor at 45 degrees);
 * - NEVER through deep water or lava ([Ground.water]: the WTR2 depths per 4.2 yd vertex; lava and slime are stored deeper than any
 *   wade): only dry ground, a bridge's deck, a ford ([SafePath.FORD_DEPTH]) or a NARROW STREAM ([SafePath.NARROW_DEPTH] deep at most,
 *   [SafePath.NARROW_YD] across at most: [SafePath.Water.wadeable]);
 * - an end standing in a wall (the player inside an inn, the goal in a hut) passes the walls within [DOOR_YD] of itself (its door).
 * PREFERRED: the node whose snap is the STRAIGHT line and clear (no Climb, no lattice: the line read every [LINE_STEP_YD] against the
 * walls and the water); a node reached only by bending round a wall or a pond costs [BENT] more a yard, so it wins only when it is
 * really the better way on. No node within [SNAP_YD], or none any clear walk of [WALK_YD] reaches: [Result.snaps] empty and
 * [Result.why] says why - the caller then takes the old long attach (at most ShippedRoute.ATTACH_MAX_YD) and logs it.
 * Pure: no Android, no files.
 */
object ShippedSnap {
    const val NEAR_YD = 40.0          // a roadmap node is this near on any walkable ground (its ~40 yd coverage)...
    const val SNAP_YD = 60.0          // ... the snap looks this far (the ruling: 40-60 yd)
    const val WALK_YD = 90.0          // a snap that bends: walked in no more than this
    const val CANDIDATES = 8          // the nearest nodes tried
    const val LINE_STEP_YD = 1.0      // a straight snap read this often (the water's 4.2 yd vertices, a wall's few yards)
    const val CELL_YD = 2.5           // a bent snap's walk cells (each step's middle read too: 1.25 yd)
    const val SPREAD_YD = 25.0        // the snaps kept: those walking no more than this past the shortest one
    const val BENT = 1.15             // a bent snap's yard against a straight one's
    const val WALL = 12f              // a steep bank (Terrain.legFactor at 45 degrees) or worse: a wall to the snap
    const val DOOR_YD = 15.0          // an end standing in a wall passes the walls this near it (its door)
    const val END_YD = 2.5            // the node's own spot: a node on a wall's foot, a bridge's end, is taken there
    const val DEEP_LAVA = 25f         // WTR2's "deeper than 25 yd" (99): a lake's middle, or lava / slime (Terrain.water's lethal)

    /**
     * THE DEVICE'S GROUND, read at a spot. [terrain] the leg's cost a yard WITHOUT the water ([Terrain.legCost] on the slope grid, the
     * border band, the building cover grid's part cost; [Terrain.WALL] = a cliff, or the cover grid's room: NaN = unknown, 1);
     * [water] the zone's WTR2 depths (null: none read); [room] whether a placed building's room holds the spot (its own outline, finer
     * than the 25 yd cover grid; null: none).
     */
    class Ground(val terrain: (Double, Double) -> Float, val water: SafePath.Water? = null, val room: ((Double, Double) -> Boolean)? = null) {
        /** why a walker may not stand at world ([x], [y]): null = it may */
        fun wall(x: Double, y: Double): String? {
            water?.let { w ->
                val d = w.at(x, y)
                if (!d.isNaN() && d != SafePath.Water.BRIDGE && d > SafePath.FORD_DEPTH && !w.wadeable(x, y))
                    return if (d >= DEEP_LAVA) "deep water or lava" else "deep water (${"%.1f".format(d)} yd)"
            }
            if (room?.invoke(x, y) == true) return "a building"
            val t = terrain(x, y)
            if (!t.isNaN() && t >= Terrain.WALL) return "a cliff or a building"
            if (!t.isNaN() && t >= WALL) return "a steep bank"
            return null
        }
        /** the cost of a yard at world ([x], [y]) where a walker may stand: the terrain's, a ford's or a narrow stream's [SafePath.FORD_COST] */
        fun yard(x: Double, y: Double): Double {
            val t = terrain(x, y).let { if (it.isNaN()) 1f else it }.coerceAtMost(WALL).toDouble()
            val d = water?.at(x, y) ?: Float.NaN
            return if (!d.isNaN() && d != SafePath.Water.BRIDGE) max(t, 1.0) * SafePath.FORD_COST else t
        }
        /** the same ground as ONE cost a yard, for the router's other walks (ShippedRoute's ground): [Terrain.WALL] where [wall] says
         *  no, else [yard] */
        val asCost: (Double, Double) -> Float = { x, y -> if (wall(x, y) != null) Terrain.WALL else yard(x, y).toFloat() }
    }

    /** a snap to graph node [node]: [pts] from the end to the node (the end first), [yd] walked, [cost] its cost (the ground x the
     *  danger, x [BENT] when it bends), [straight] the straight line, clear */
    class Snap(val node: Int, val pts: List<DoubleArray>, val yd: Double, val cost: Double, val straight: Boolean)
    /** [snaps] cheapest first (empty: no snap, the long attach's turn); [why] the log's words */
    class Result(val snaps: List<Snap>, val why: String) {
        val ok get() = snaps.isNotEmpty()
        val best get() = snaps.firstOrNull()
    }

    /**
     * the snaps from [p] (world x, y) onto [nodes] (graph node id, world x, y) within [reach] over [ground], each yard's cost the
     * ground's plus [k] x [danger] there. Straight-and-clear snaps first tried; the rest by a walk of [walkYd] at most.
     */
    fun snap(p: DoubleArray, nodes: List<Pair<Int, DoubleArray>>, ground: Ground, danger: ((Double, Double) -> Float)? = null,
             k: Double = 0.0, reach: Double = SNAP_YD, walkYd: Double = WALK_YD): Result {
        val near = nodes.map { (id, q) -> Triple(id, q, hypot(q[0] - p[0], q[1] - p[1])) }.filter { it.third <= reach }.sortedBy { it.third }
        if (near.isEmpty()) {
            val n = nodes.minOfOrNull { (_, q) -> hypot(q[0] - p[0], q[1] - p[1]) }
            return Result(emptyList(), if (n == null) "no node to snap to" else "no node within ${reach.toInt()} yd (the nearest ${n.toInt()} yd)")
        }
        val view = View(p, ground, danger, k)
        val out = ArrayList<Snap>()
        val blocked = ArrayList<String>()
        val tried = near.take(CANDIDATES)
        val bent = ArrayList<Triple<Int, DoubleArray, Double>>()
        for (c in tried) {
            val line = view.line(p, c.second)
            if (line.why == null) out.add(Snap(c.first, listOf(p, c.second), c.third, line.cost, true))
            else { bent.add(c); blocked.add(line.why) }
        }
        if (bent.isNotEmpty()) {
            val walk = Walk(view, walkYd)
            for ((id, q, _) in bent) walk.to(q)?.let { w -> out.add(Snap(id, w.pts, w.yd, w.cost * BENT, false)) }
        }
        // the NEAREST node's: a snap walking farther than the shortest by more than [SPREAD_YD] is dropped (the network, not the open
        // ground, carries the way on: a far snap across the grass would skip its nodes at the off-net walk's price)
        val shortest = out.minOfOrNull { it.yd } ?: 0.0
        out.removeAll { it.yd > shortest + SPREAD_YD }
        out.sortBy { it.cost }
        if (out.isEmpty()) {
            val first = blocked.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} x${it.value}" }
            return Result(emptyList(), "the ${tried.size} nodes within ${reach.toInt()} yd (nearest ${tried.first().third.toInt()} yd): " +
                "no clear walk of ${walkYd.toInt()} yd ($first)")
        }
        val s = out.count { it.straight }
        return Result(out, "snapped ${out.first().yd.toInt()} yd ${if (out.first().straight) "straight" else "round a wall or water"} " +
            "($s straight, ${out.size - s} bent of ${tried.size} nodes within ${reach.toInt()} yd)")
    }

    /**
     * THE SNAP AS ShippedRoute's ATTACH ([ShippedRoute.Attacher], the seam): each end's [snap]s onto the graph's nodes over [ground]
     * (null: the router's own ground, [ShippedRoute.AttachView.ground], read as the terrain); with none, the old long attach
     * ([ShippedRoute.DefaultAttach]: a walk onto the nearest ways, [ShippedRoute.AttachView.lim] at most) over the same ground with
     * the water and the rooms folded in as walls ([Ground.asCost]), its why the snap's own - logged by the caller.
     */
    fun attacher(ground: Ground? = null, log: ((String) -> Unit)? = null) = ShippedRoute.Attacher { v, p ->
        val g = ground ?: Ground(v.ground)
        val s = snap(p, v.nodes, g, v.danger, v.k)
        if (s.ok) return@Attacher ShippedRoute.Attach(s.snaps.map { ShippedRoute.Join(it.node, -1, 0.0, it.pts.last(), it.pts, it.cost, it.yd) }, true, s.why)
        log?.invoke("snap: none at ${p[0].toInt()},${p[1].toInt()} (${s.why}) - the long attach")
        val long = ShippedRoute.DefaultAttach.attach(ShippedRoute.AttachView(emptyList(), v.ways, g.asCost, v.danger, v.k, v.lim), p)
        ShippedRoute.Attach(long.joins, false, "${long.why ?: "on ${long.joins.size} ways by the long attach"} (no snap: ${s.why})")
    }

    /** the straight line from [a] to [b] read every [LINE_STEP_YD] over [ground] (an end in a wall passes those within [DOOR_YD] of
     *  it): null = clear, else the first wall's words and where */
    fun lineWall(a: DoubleArray, b: DoubleArray, ground: Ground): String? = View(a, ground, null, 0.0).line(a, b).why

    // ---------------------------------------------------------------- the fine reads

    /** one end's view of the ground: [c] the end (its door rule), the cost a yard with the danger */
    private class View(val c: DoubleArray, val g: Ground, val danger: ((Double, Double) -> Float)?, val k: Double) {
        val inWall = g.wall(c[0], c[1]) != null
        class Line(val cost: Double, val why: String?)
        /** the wall at ([x], [y]) for this end's walk (null: none; the door's within [DOOR_YD] of an end standing in one) */
        fun wall(x: Double, y: Double): String? {
            val w = g.wall(x, y) ?: return null
            return if (inWall && hypot(x - c[0], y - c[1]) <= DOOR_YD) null else w
        }
        fun yard(x: Double, y: Double): Double = g.yard(x, y) + k * (danger?.invoke(x, y)?.takeIf { !it.isNaN() } ?: 0f)
        /** the straight line [a] -> [b], every [LINE_STEP_YD]; its last [END_YD] (the node's own spot) never a wall */
        fun line(a: DoubleArray, b: DoubleArray): Line {
            val len = hypot(b[0] - a[0], b[1] - a[1]); val m = max(1, ceil(len / LINE_STEP_YD).toInt()); var s = 0.0
            for (q in 0..m) {
                val t = q.toDouble() / m; val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
                if (len * (1 - t) > END_YD) wall(x, y)?.let { return Line(Double.NaN, "$it at ${x.toInt()},${y.toInt()}") }
                if (q < m) s += yard(x + (b[0] - a[0]) / (2 * m), y + (b[1] - a[1]) / (2 * m)) * len / m
            }
            return Line(s, null)
        }
    }

    /** the walk from the view's end over [CELL_YD] cells, [lim] walked yards at most: Dijkstra, every step's middle read, no corner cut */
    private class Walk(val v: View, val lim: Double) {
        class W(val pts: List<DoubleArray>, val cost: Double, val yd: Double)
        val c = v.c
        val r = ceil(lim / CELL_YD).toInt(); val n = 2 * r + 1
        val dist = DoubleArray(n * n) { Double.MAX_VALUE }; val walked = DoubleArray(n * n) { Double.MAX_VALUE }; val prev = IntArray(n * n) { -1 }
        val open = ByteArray(n * n)        // 0 unread, 1 open, 2 a wall
        fun wx(i: Int) = c[0] + (i - r) * CELL_YD
        fun wy(j: Int) = c[1] + (j - r) * CELL_YD
        fun free(u: Int): Boolean {
            if (open[u].toInt() == 0) open[u] = if (v.wall(wx(u % n), wy(u / n)) == null) 1 else 2
            return open[u].toInt() == 1
        }
        init {
            val s = r * n + r
            dist[s] = 0.0; walked[s] = 0.0; open[s] = 1
            val q = PriorityQueue<Pair<Double, Int>>(compareBy { it.first }); q.add(0.0 to s)
            while (q.isNotEmpty()) {
                val (d, u) = q.poll()
                if (d > dist[u]) continue
                val ui = u % n; val uj = u / n
                for (dj in -1..1) for (di in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val vi = ui + di; val vj = uj + dj
                    if (vi !in 0 until n || vj !in 0 until n) continue
                    val step = if (di != 0 && dj != 0) CELL_YD * 1.4142 else CELL_YD
                    val w = walked[u] + step
                    if (w > lim) continue
                    val t = vj * n + vi
                    if (!free(t)) continue
                    if (di != 0 && dj != 0 && (!free(uj * n + vi) || !free(vj * n + ui))) continue
                    // the step's middle: a wall or a stream thinner than a cell
                    val mx = (wx(ui) + wx(vi)) / 2; val my = (wy(uj) + wy(vj)) / 2
                    if (v.wall(mx, my) != null) continue
                    val nd = d + step * v.yard(mx, my)
                    if (nd < dist[t]) { dist[t] = nd; walked[t] = w; prev[t] = u; q.add(nd to t) }
                }
            }
        }
        /** the walk to [p] (the end first, [p] last), pulled straight where a line is clear and no dearer; null = none */
        fun to(p: DoubleArray): W? {
            if (hypot(p[0] - c[0], p[1] - c[1]) > lim) return null
            val pi = Math.round((p[0] - c[0]) / CELL_YD).toInt() + r; val pj = Math.round((p[1] - c[1]) / CELL_YD).toInt() + r
            // the node's cell, else its nearest reached neighbour from which the node's own spot is a clear line
            val cand = (-1..1).flatMap { dj -> (-1..1).map { di -> (pj + dj) to (pi + di) } }
                .filter { (j, i) -> i in 0 until n && j in 0 until n }.map { (j, i) -> j * n + i }.filter { dist[it] < Double.MAX_VALUE }
                .mapNotNull { t -> val l = v.line(doubleArrayOf(wx(t % n), wy(t / n)), p); if (l.why == null) Triple(t, dist[t] + l.cost, l) else null }
                .minByOrNull { it.second } ?: return null
            val t = cand.first
            val cells = ArrayList<DoubleArray>(); var u = t
            while (u != -1) { cells.add(doubleArrayOf(wx(u % n), wy(u / n))); u = prev[u] }
            cells.reverse()
            val raw = listOf(c) + cells.drop(1) + listOf(p)
            val pts = pull(raw)
            return W(pts, cand.second, ShippedRoute.length(pts))
        }
        private fun pull(pts: List<DoubleArray>): List<DoubleArray> {
            if (pts.size <= 2) return pts
            val along = DoubleArray(pts.size)
            for (i in 1 until pts.size) along[i] = along[i - 1] + v.line(pts[i - 1], pts[i]).cost.let { if (it.isNaN()) 1e9 else it }
            val out = arrayListOf(pts[0]); var i = 0
            while (i < pts.size - 1) {
                var j = pts.size - 1
                while (j > i + 1) { val l = v.line(pts[i], pts[j]); if (l.why == null && l.cost <= (along[j] - along[i]) * 1.02 + 1e-6) break; j-- }
                out.add(pts[j]); i = j
            }
            return out
        }
    }
}
