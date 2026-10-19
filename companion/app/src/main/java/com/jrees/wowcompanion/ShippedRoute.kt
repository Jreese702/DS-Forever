package com.jrees.wowcompanion

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * THE ROUTE ON THE SHIPPED NETWORK (the user, 2026-10-19: "Ship our path network"; E:/safepath-work/docs/DEVICE_INTEGRATION_PLAN.md,
 * SHIPPED NETWORK step 3). Pure: no Android, no files - the parity test (helper W) and MapView call the same [plan].
 *
 * 1. THE ATTACH LEGS: the player, and the goal, are joined to the nearest ways of the network by a short walk over the device's own
 *    ground ([ground]: the terrain grid's leg cost with its walls, buildings, deep water and fords, as MapView's danger grid has it;
 *    [Terrain.WALL] or more is a wall) and the character's danger ([danger] x [k]) - [ATTACH_MAX_YD] walked at most. A goal or a player
 *    farther from every way than that: no route ([Result.reached] false, [Result.why] says so); the caller keeps its old line.
 * 2. THE NETWORK: Dijkstra over the shipped ways - roads first (a spur or a cave x1.3, a trail x2.2 and its climb), an AVOID way x6,
 *    a wading way [WADE_EXTRA_YD] dearer, a lift its wait - with the danger along each way: the character's own ([danger], sampled
 *    every [SAMPLE_YD]) or, with none, the network's per-faction tier ([SafePath.Edge.danger]). The section's links (portals,
 *    updrafts, stairs) are ways too; a SWIM link only when the goal is on its island.
 * 3. THE DETOUR CAP ([SafePath.capDetour]): the way round the danger against the same search with no danger counted - longer than
 *    1.75x + 500 yd, the no-danger way wins.
 */
object ShippedRoute {
    const val ATTACH_MAX_YD = 200.0        // the walk onto / off the network at most this long (the ruling: 150-200 yd)
    const val ATTACH_STEP_YD = 5.0         // the attach leg's cells
    const val ATTACH_CANDIDATES = 6        // the nearest ways tried at each end
    const val DOOR_YD = 15.0               // walls this near the player or the goal are passed (a door: in a building, an inn's giver)
    const val SAMPLE_YD = 10.0             // the character's danger read along a way this often
    const val AVOID_FACTOR = 6.0           // an AVOID way's yard
    const val WADE_EXTRA_YD = 60.0         // a way that wades: this much dearer (a drier way preferred)
    const val LINK_SNAP_YD = 40.0          // a link's end joins the network's node this near
    const val PULL_SAMPLE_YD = 2.5         // a pulled attach line read this often

    /** what [plan] found: [pts] world (x, y[, mark]) from the player to the goal (two points: the straight line, not reached); [why]
     *  the log's words; [attachFromYd] / [attachToYd] the attach legs' walked yards; [capped] the no-danger way taken by the cap */
    class Result(val pts: List<DoubleArray>, val reached: Boolean, val why: String, val yd: Double = length(pts),
                 val attachFromYd: Double = 0.0, val attachToYd: Double = 0.0, val capped: Boolean = false, val swims: Int = 0) {
        val bends get() = max(0, pts.size - 2)
    }

    fun length(w: List<DoubleArray>) = w.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }.sum()

    /**
     * the way from [from] to [to] (world x, y) on [sec]'s network. [ground] the ground's cost per yard at a world point (NaN = unknown,
     * 1; [Terrain.WALL] or more = a wall), [danger] the character's danger there (null: the net's own tiers), [k] its weight
     * ([DangerGrid.strength]; 0 = none). [toExit]: the goal is a crossing out, snapped to the net's exit within [SafePath.EXIT_SNAP_YD].
     */
    fun plan(sec: ShippedNet.Section, ground: (Double, Double) -> Float, danger: ((Double, Double) -> Float)?, from: DoubleArray, to: DoubleArray,
             k: Double = DangerGrid.strength("high"), toExit: Boolean = false, attachMaxYd: Double = ATTACH_MAX_YD): Result {
        val g = Graph(sec, danger)
        var goal = to
        if (toExit) g.exitNear(to)?.let { goal = it }
        val round = g.search(ground, danger, from, goal, k, attachMaxYd)
        if (round.pts.size < 2 || !round.reached) return round
        if (k <= 0.0) return round
        val calm = g.search(ground, danger, from, goal, 0.0, attachMaxYd).takeIf { it.reached }
        val pick = SafePath.capDetour(round.pts, calm?.pts)
        if (pick === round.pts || calm == null) return round
        return Result(calm.pts, true, calm.why + "; capped: round the danger ${round.yd.toInt()} yd, the way ignoring it ${calm.yd.toInt()} yd",
            attachFromYd = calm.attachFromYd, attachToYd = calm.attachToYd, capped = true, swims = calm.swims)
    }

    /** the network as a graph: its ways' ends and lengths, their base costs and danger along them */
    private class Graph(val sec: ShippedNet.Section, danger: ((Double, Double) -> Float)?) {
        val net = sec.net
        val byId = net.nodes.associateBy { it.id }
        class W(val a: Int, val b: Int, val len: Double, val base: Double, val dangerYd: Double, val tier: Double, val onLand: Boolean)
        val ways: List<W>
        val pos = HashMap<Int, DoubleArray>()
        init {
            for (n in net.nodes) pos[n.id] = when (n.kind) {
                SafePath.CAVE -> doubleArrayOf(n.wx, n.wy, Climb.IN_CAVE); SafePath.LIFT -> doubleArrayOf(n.wx, n.wy, Climb.LIFT)
                else -> doubleArrayOf(n.wx, n.wy) }
            ways = net.edges.mapIndexedNotNull { i, e ->
                if (e.state == SafePath.REMOVED) return@mapIndexedNotNull null
                val a = byId[e.a] ?: return@mapIndexedNotNull null; val b = byId[e.b] ?: return@mapIndexedNotNull null
                val flags = sec.ways.getOrNull(i)?.flags ?: ShippedNet.flagsOf(e)
                val len = hypot(a.wx - b.wx, a.wy - b.wy)
                val kindF = when { flags and ShippedNet.F_ROAD != 0 || e.kind == SafePath.ROAD -> 1.0
                    flags and (ShippedNet.F_SPUR or ShippedNet.F_CAVE) != 0 || e.kind == SafePath.SPUR || e.kind == SafePath.CAVE -> 1.3
                    flags and ShippedNet.F_LIFT != 0 || e.kind == SafePath.LIFT -> 1.0
                    else -> 2.2 }
                val climb = if (kindF == 1.0) 1.0 else e.climb.toDouble().coerceAtLeast(1.0)
                val avoid = if (flags and ShippedNet.F_AVOID != 0 || e.state == SafePath.AVOID) AVOID_FACTOR else 1.0
                val extra = (if (flags and (ShippedNet.F_WADE or ShippedNet.F_SWIM) != 0) WADE_EXTRA_YD else 0.0) +
                    (if (flags and ShippedNet.F_LIFT != 0 || e.kind == SafePath.LIFT) Caves.LIFT_WAIT_YD else 0.0)
                // the character's danger along the way (none inside a cave or on a lift: their floor is not the ground the mobs stand on)
                val inside = e.kind == SafePath.CAVE || e.kind == SafePath.LIFT || flags and (ShippedNet.F_CAVE or ShippedNet.F_LIFT) != 0
                val dyd = if (danger == null || inside) 0.0 else {
                    val n = max(1, ceil(len / SAMPLE_YD).toInt()); var s = 0.0
                    for (q in 0 until n) { val t = (q + 0.5) / n; val d = danger(a.wx + (b.wx - a.wx) * t, a.wy + (b.wy - a.wy) * t); if (!d.isNaN()) s += d * len / n }
                    s }
                W(e.a, e.b, len, len * kindF * climb * avoid + extra, dyd, e.danger.toDouble(), !inside)
            }
        }
        fun cost(w: W, k: Double, danger: Boolean): Double = if (danger) w.base + k * w.dangerYd else w.base * (if (k > 0.0) 1.0 + w.tier else 1.0)

        fun exitNear(p: DoubleArray): DoubleArray? = net.exits.mapNotNull { byId[it.node] }.minByOrNull { hypot(it.wx - p[0], it.wy - p[1]) }
            ?.takeIf { hypot(it.wx - p[0], it.wy - p[1]) <= SafePath.EXIT_SNAP_YD }?.let { doubleArrayOf(it.wx, it.wy) }

        fun nearestNode(p: DoubleArray, lim: Double): Int? = net.nodes.minByOrNull { hypot(it.wx - p[0], it.wy - p[1]) }
            ?.takeIf { hypot(it.wx - p[0], it.wy - p[1]) <= lim }?.id

        class Arc(val to: Int, val c: Double, val via: List<DoubleArray>)        // [via]: the points walked after the arc's start, its end included

        fun search(ground: (Double, Double) -> Float, danger: ((Double, Double) -> Float)?, from: DoubleArray, goal: DoubleArray, k: Double,
                   lim: Double): Result {
            val straight = listOf(from, goal)
            if (ways.isEmpty()) return Result(straight, false, "the shipped network has no ways")
            val withDanger = danger != null
            val adj = HashMap<Int, ArrayList<Arc>>()
            fun arc(a: Int, b: Int, c: Double, via: List<DoubleArray>) { adj.getOrPut(a) { ArrayList() }.add(Arc(b, c, via)) }
            for (w in ways) { val c = cost(w, k, withDanger); arc(w.a, w.b, c, listOf(pos[w.b]!!)); arc(w.b, w.a, c, listOf(pos[w.a]!!)) }
            // the links: a portal / an updraft from its first point only; a swim only for a goal on its island
            var swimOffered = 0
            for (l in sec.links) {
                if (l.pts.size < 2) continue
                val swim = l.kind == "swim" || l.pts.any { Climb.atSwim(it) }
                if (swim) { val isl = l.island ?: continue; if (hypot(goal[0] - isl[0], goal[1] - isl[1]) > isl[2]) continue; swimOffered++ }
                val a = nearestNode(l.pts.first(), LINK_SNAP_YD) ?: continue; val b = nearestNode(l.pts.last(), LINK_SNAP_YD) ?: continue
                if (a == b) continue
                val c = if (l.yd > 0.0) l.yd else length(l.pts)
                val pts = l.pts.map { p -> if (swim) doubleArrayOf(p[0], p[1], Climb.SWIM) else p }
                arc(a, b, c, pts.drop(1) + listOf(pos[b]!!))
                if (!l.oneWay) arc(b, a, c, pts.reversed().drop(1) + listOf(pos[a]!!))
            }
            // the attach legs: a walk over the ground onto each of the nearest ways (a virtual node cut into it)
            val start = -1; val end = -2
            var nextVirtual = -10
            val sg = Leg(ground, danger, k, from, lim); val gg = Leg(ground, danger, k, goal, lim)
            val attachYd = HashMap<Int, Double>()
            fun attach(leg: Leg, p: DoubleArray, virt: Int, outward: Boolean): String? {
                val cands = ways.filter { it.onLand }.map { w ->
                    val a = pos[w.a]!!; val b = pos[w.b]!!
                    val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
                    val t = if (l2 <= 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
                    Triple(w, t, doubleArrayOf(a[0] + t * dx, a[1] + t * dy))
                }.sortedBy { hypot(it.third[0] - p[0], it.third[1] - p[1]) }
                val nearest = cands.firstOrNull()?.let { hypot(it.third[0] - p[0], it.third[1] - p[1]) } ?: Double.MAX_VALUE
                if (nearest > lim) return "${nearest.toInt()} yd from the nearest way (attach at most ${lim.toInt()} yd)"
                var any = false
                for ((w, t, q) in cands.filter { hypot(it.third[0] - p[0], it.third[1] - p[1]) <= lim }.take(ATTACH_CANDIDATES)) {
                    val walk = leg.to(q) ?: continue
                    any = true
                    val id = nextVirtual--
                    pos[id] = q
                    val c = cost(w, k, withDanger)
                    arc(id, w.a, t * c, listOf(pos[w.a]!!)); arc(w.a, id, t * c, listOf(q))
                    arc(id, w.b, (1 - t) * c, listOf(pos[w.b]!!)); arc(w.b, id, (1 - t) * c, listOf(q))
                    val wc = walk.cost * SafePath.OFF_NET
                    attachYd[id] = walk.yd
                    if (outward) arc(virt, id, wc, walk.pts.drop(1)) else arc(id, virt, wc, walk.pts.reversed().drop(1))
                }
                return if (any) null else "no walk of ${lim.toInt()} yd or less reaches a way (walls, deep water)"
            }
            attach(sg, from, start, true)?.let { return Result(straight, false, "the player is $it") }
            attach(gg, goal, end, false)?.let { return Result(straight, false, "the goal is $it") }
            // the ground's own walk straight there, the network's rival for a goal this near
            if (hypot(goal[0] - from[0], goal[1] - from[1]) <= lim) sg.to(goal)?.let { arc(start, end, it.cost * SafePath.DIRECT, it.pts.drop(1)) }
            // Dijkstra
            val dist = HashMap<Int, Double>(); val prev = HashMap<Int, Pair<Int, Arc>>()
            val q = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
            dist[start] = 0.0; q.add(0.0 to start)
            while (q.isNotEmpty()) {
                val (d, u) = q.poll()
                if (u == end) break
                if (d > (dist[u] ?: Double.MAX_VALUE)) continue
                for (a in adj[u] ?: continue) {
                    val nd = d + a.c
                    if (nd < (dist[a.to] ?: Double.MAX_VALUE)) { dist[a.to] = nd; prev[a.to] = u to a; q.add(nd to a.to) }
                }
            }
            if (end !in dist) return Result(straight, false, "the network does not join the player's ways to the goal's")
            val arcs = ArrayList<Pair<Int, Arc>>(); var c = end
            while (c != start) { val p = prev[c]!!; arcs.add(p); c = p.first }
            arcs.reverse()
            val pts = arrayListOf(from)
            for ((_, a) in arcs) for (p in a.via) if (hypot(pts.last()[0] - p[0], pts.last()[1] - p[1]) > 0.5 || p.size != pts.last().size) pts.add(p)
            if (hypot(pts.last()[0] - goal[0], pts.last()[1] - goal[1]) > 0.5) pts.add(goal)
            val out = tidy(pts)
            val onYd = arcs.firstOrNull()?.second?.to?.let { attachYd[it] } ?: 0.0
            val offYd = arcs.lastOrNull()?.first?.let { attachYd[it] } ?: 0.0
            val direct = arcs.size == 1
            val swims = out.count { Climb.atSwim(it) }
            return Result(out, true, (if (direct) "the ground's own walk (near)" else "on the shipped network") +
                ": ${length(out).toInt()} yd, ${max(0, out.size - 2)} bends, attach ${onYd.toInt()} / ${offYd.toInt()} yd" +
                (if (swimOffered > 0) ", swim offered ($swims swum points)" else ""), attachFromYd = onYd, attachToYd = offYd, swims = swims)
        }
    }

    /** a way's points with no straight runs' middles (within half a yard of the line), the marks kept */
    private fun tidy(pts: List<DoubleArray>): List<DoubleArray> {
        if (pts.size <= 2) return pts
        val out = arrayListOf(pts[0])
        for (i in 1 until pts.size - 1) {
            val a = out.last(); val b = pts[i]; val c = pts[i + 1]
            if (b.size > 2 || a.size > 2 || c.size > 2) { out.add(b); continue }
            val len = hypot(c[0] - a[0], c[1] - a[1])
            val off = if (len < 1e-6) hypot(b[0] - a[0], b[1] - a[1]) else abs((c[0] - a[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (c[1] - a[1])) / len
            val along = (b[0] - a[0]) * (c[0] - a[0]) + (b[1] - a[1]) * (c[1] - a[1])
            if (off > 0.5 || along < 0) out.add(b)
        }
        out.add(pts.last())
        return out
    }

    /**
     * THE ATTACH LEG's GROUND round [c] ([lim] yd each way, [ATTACH_STEP_YD] cells): Dijkstra from [c] over the ground's cost and the
     * danger, walls passed only within [DOOR_YD] of [c]; [to] the walk to a point (null: none within [lim] walked yards), pulled straight
     * where the straight line crosses no wall and costs no more
     */
    class Leg(private val ground: (Double, Double) -> Float, private val danger: ((Double, Double) -> Float)?, private val k: Double,
              private val c: DoubleArray, private val lim: Double) {
        class Walk(val pts: List<DoubleArray>, val cost: Double, val yd: Double)
        private val r = ceil(lim / ATTACH_STEP_YD).toInt()
        private val n = 2 * r + 1
        private val dist = DoubleArray(n * n) { Double.MAX_VALUE }
        private val walked = DoubleArray(n * n) { Double.MAX_VALUE }
        private val prev = IntArray(n * n) { -1 }
        private fun wx(i: Int) = c[0] + (i - r) * ATTACH_STEP_YD
        private fun wy(j: Int) = c[1] + (j - r) * ATTACH_STEP_YD
        private fun cellCost(x: Double, y: Double, nearC: Boolean): Double {
            val gr = ground(x, y).let { if (it.isNaN()) 1f else it }
            if (gr >= Terrain.WALL && !nearC) return Double.NaN
            val d = danger?.invoke(x, y)?.takeIf { !it.isNaN() } ?: 0f
            return minOf(gr, Terrain.STEEP * 4).toDouble() + k * d
        }
        init {
            val s = r * n + r
            dist[s] = 0.0; walked[s] = 0.0
            val q = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
            q.add(0.0 to s)
            val memo = DoubleArray(n * n) { -1.0 }
            while (q.isNotEmpty()) {
                val (d, u) = q.poll()
                if (d > dist[u]) continue
                val ui = u % n; val uj = u / n
                for (dj in -1..1) for (di in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val vi = ui + di; val vj = uj + dj
                    if (vi !in 0 until n || vj !in 0 until n) continue
                    val v = vj * n + vi
                    val step = if (di != 0 && dj != 0) ATTACH_STEP_YD * 1.4142 else ATTACH_STEP_YD
                    val w = walked[u] + step
                    if (w > lim) continue
                    if (memo[v] < 0.0) memo[v] = cellCost(wx(vi), wy(vj), hypot((vi - r) * ATTACH_STEP_YD, (vj - r) * ATTACH_STEP_YD) <= DOOR_YD).let { if (it.isNaN()) Double.MAX_VALUE else it }
                    if (memo[v] == Double.MAX_VALUE) continue
                    val nd = d + step * memo[v]
                    if (nd < dist[v]) { dist[v] = nd; walked[v] = w; prev[v] = u; q.add(nd to v) }
                }
            }
        }
        private fun cellOf(p: DoubleArray): Int {
            val i = Math.round((p[0] - c[0]) / ATTACH_STEP_YD).toInt() + r; val j = Math.round((p[1] - c[1]) / ATTACH_STEP_YD).toInt() + r
            return if (i in 0 until n && j in 0 until n) j * n + i else -1
        }
        /** the walk from [c] to [p], [c] first and [p] last; null = none within [lim] (or [p] beyond it) */
        fun to(p: DoubleArray): Walk? {
            if (hypot(p[0] - c[0], p[1] - c[1]) > lim) return null
            var t = cellOf(p); if (t < 0) return null
            // the point's own cell a wall (a way's edge on a bridge, a wall's foot): its reached neighbour
            if (dist[t] == Double.MAX_VALUE) {
                val ti = t % n; val tj = t / n
                t = (-1..1).flatMap { dj -> (-1..1).map { di -> (tj + dj) to (ti + di) } }.filter { (j, i) -> i in 0 until n && j in 0 until n }
                    .map { (j, i) -> j * n + i }.filter { dist[it] < Double.MAX_VALUE }.minByOrNull { dist[it] } ?: return null
            }
            val cells = ArrayList<DoubleArray>(); var u = t
            while (u != -1) { cells.add(doubleArrayOf(wx(u % n), wy(u / n))); u = prev[u] }
            cells.reverse()
            val raw = listOf(c) + cells.drop(1).dropLast(1) + listOf(p)
            val tail = hypot(p[0] - wx(t % n), p[1] - wy(t / n))
            return Walk(pull(raw), dist[t] + tail, walked[t] + tail)
        }
        /** a straight line from [a] to [b] crossing no wall (outside the door's yards), and its cost */
        private fun lineCost(a: DoubleArray, b: DoubleArray): Double {
            val len = hypot(b[0] - a[0], b[1] - a[1]); val m = max(1, ceil(len / PULL_SAMPLE_YD).toInt()); var s = 0.0
            for (q in 0 until m) {
                val t = (q + 0.5) / m; val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
                val cc = cellCost(x, y, hypot(x - c[0], y - c[1]) <= DOOR_YD)
                if (cc.isNaN()) return Double.NaN
                s += cc * len / m
            }
            return s
        }
        private fun pull(pts: List<DoubleArray>): List<DoubleArray> {
            if (pts.size <= 2) return pts
            val along = DoubleArray(pts.size)
            for (i in 1 until pts.size) along[i] = along[i - 1] + lineCost(pts[i - 1], pts[i]).let { if (it.isNaN()) 1e9 else it }
            val out = arrayListOf(pts[0]); var i = 0
            while (i < pts.size - 1) {
                var j = pts.size - 1
                while (j > i + 1) { val lc = lineCost(pts[i], pts[j]); if (!lc.isNaN() && lc <= (along[j] - along[i]) * 1.02 + 1e-6) break; j-- }
                out.add(pts[j]); i = j
            }
            return out
        }
    }

    // the floor: a point index past floor() of a negative stays in the grid
    @Suppress("unused") private fun fl(v: Double) = floor(v).toInt()
}
