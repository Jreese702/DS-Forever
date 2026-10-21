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
 * THE CHOICE ([snap]; W's v2 parity, NET2_PARITY.md: 22 crossings on the snap legs, then 1; round 2: 4, then 2): the targets are
 * the graph's nodes AND each way's nearest point (a cut into a roadmap edge or a road: the router walks the way's own points from it);
 * the NEAREST straight clear line (and a tie within [SPREAD_YD]) leads; a ground end whose lead is within [ON_PIECE_YD] stands on that
 * piece and gets no fallback off it; an end ON A FLOOR (a city's street, a walkway, the start pocket over a cave) snaps to its floor's nodes
 * first; every other snap is only a FALLBACK, [FAR_COST] x its rank dearer (the router takes one only when the nearer ones' piece of
 * the graph joins nothing on), the level lines before those over a climb, none over [FAR_STEEP]. No node within [SNAP_YD], or none any
 * clear walk of [WALK_YD] reaches: [Result.snaps] empty and [Result.why] says why - the caller then takes the old long attach (at most
 * ShippedRoute.ATTACH_MAX_YD) and logs it.
 * Pure: no Android, no files.
 */
object ShippedSnap {
    const val NEAR_YD = 40.0          // a roadmap node is this near on any walkable ground (its ~40 yd coverage)...
    const val SNAP_YD = 60.0          // ... the snap looks this far (the ruling: 40-60 yd)
    const val WALK_YD = 90.0          // a snap that bends: walked in no more than this
    const val CANDIDATES = 8          // the nearest walled-off nodes a walk round is tried to
    const val LINE_STEP_YD = 1.0      // a straight snap read this often (the water's 4.2 yd vertices, a wall's few yards)
    const val CELL_YD = 2.5           // a bent snap's walk cells (each step's middle read too: 1.25 yd)
    const val ON_PIECE_YD = 5.0       // a ground end its lead snap is this near stands on that piece: no fallback off it
    const val LEVEL = 1.05f           // the near-goal walk's ground: no dearer a yard than this (level; a road's, a walked cell's less)
    const val FAR_STEEP = 5f          // a fallback over terrain this dear a yard (a climb of ~37 degrees, a rock field): not offered
    const val FAR_COST = 1.0e6        // a snap past the spread: this much dearer (the fallback when the nearer ones lead nowhere)
    const val SPREAD_YD = 0.5         // the snaps kept: the nearest clear one's and those no more than this longer
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
    class Ground(val terrain: (Double, Double) -> Float, val water: SafePath.Water? = null, val room: ((Double, Double) -> Boolean)? = null,
                 /** a ghost's ground (CorpseRoute; the user: "Walk on water"): water of any depth is walked on its surface (no wall, no
                  *  ford's cost, the bed's slope under it not read); lava (WTR2's [DEEP_LAVA] and deeper) stays a wall */
                 val allowWater: Boolean = false) {
        /** deep water or lava at world ([x], [y]) a walker may not cross (the lattice walk's water) */
        fun deep(x: Double, y: Double): Boolean = wall(x, y, false)?.let { it != "a building" } == true
        /** the same ground for a ghost ([allowWater]) */
        fun ghost() = Ground(terrain, water, room, allowWater = true)
        /** water a ghost walks on at world ([x], [y]): [allowWater], wet, no bridge, not lava */
        internal fun walkedWater(x: Double, y: Double): Boolean {
            if (!allowWater) return false
            val d = water?.at(x, y) ?: return false
            return !d.isNaN() && d != SafePath.Water.BRIDGE && d < DEEP_LAVA
        }
        /** why a walker may not stand at world ([x], [y]): null = it may; [terrain] false: the water and the rooms only (the shipped
         *  lattice's classes judge the ground: [ShippedNet.Walk]) */
        fun wall(x: Double, y: Double, terrain: Boolean = true): String? {
            water?.let { w ->
                val d = w.at(x, y)
                if (allowWater) {
                    if (!d.isNaN() && d != SafePath.Water.BRIDGE && d >= DEEP_LAVA) return "deep water or lava"
                    if (walkedWater(x, y)) return if (room?.invoke(x, y) == true) "a building" else null
                } else if (!d.isNaN() && d != SafePath.Water.BRIDGE && d > SafePath.FORD_DEPTH && !w.wadeable(x, y))
                    return if (d >= DEEP_LAVA) "deep water or lava" else "deep water (${"%.1f".format(d)} yd)"
            }
            if (room?.invoke(x, y) == true) return "a building"
            if (!terrain) return null
            val t = terrain(x, y)
            if (!t.isNaN() && t >= Terrain.WALL) return "a cliff or a building"
            if (!t.isNaN() && t >= WALL) return "a steep bank"
            return null
        }
        /** the cost of a yard at world ([x], [y]) where a walker may stand: the terrain's, a ford's or a narrow stream's [SafePath.FORD_COST] */
        fun yard(x: Double, y: Double): Double {
            if (walkedWater(x, y)) return 1.0                      // a ghost on the water's surface: level going
            val t = terrain(x, y).let { if (it.isNaN()) 1f else it }.coerceAtMost(WALL).toDouble()
            val d = water?.at(x, y) ?: Float.NaN
            return if (!d.isNaN() && d != SafePath.Water.BRIDGE) max(t, 1.0) * SafePath.FORD_COST else t
        }
        /** the same ground as ONE cost a yard, for the router's other walks (ShippedRoute's ground): [Terrain.WALL] where [wall] says
         *  no, else [yard] */
        val asCost: (Double, Double) -> Float = { x, y -> if (wall(x, y) != null) Terrain.WALL else yard(x, y).toFloat() }
    }

    /** a snap to graph node [node]: [pts] from the end to the node (the end first), [yd] walked, [cost] its cost (the ground x the
     *  danger, x [BENT] when it bends; a fallback's [FAR_COST] x its rank more), [straight] the straight line, clear, [lead] a leading
     *  snap (not a fallback) */
    class Snap(val node: Int, val pts: List<DoubleArray>, val yd: Double, val cost: Double, val straight: Boolean, val lead: Boolean = false,
               val steep: Float = 1f, val way: Int = -1, val t: Double = 0.0) {
        fun ranked(rank: Int) = Snap(node, pts, yd, cost + FAR_COST * rank, straight, rank == 0, steep, way, t)
        /** a cut into way [way] ([ShippedRoute.WayView.index]) at [t], not a node */
        val cut get() = way >= 0
    }
    /** the way cuts' keys in [Snap.node]: this and down (graph node ids are never negative, the router's virtual ones -1 .. -10 000 000) */
    const val CUT_KEY = -100_000_000
    /** [snaps] cheapest first (empty: no snap, the long attach's turn); [why] the log's words */
    class Result(val snaps: List<Snap>, val why: String) {
        val ok get() = snaps.isNotEmpty()
        val best get() = snaps.firstOrNull()
    }

    /**
     * the snaps from [p] (world x, y) onto [nodes] (graph node id, world x, y) within [reach] over [ground], each yard's cost the
     * ground's plus [k] x [danger] there.
     * - AN END ON A FLOOR (the nearest of [nodes] and [floorNodes] is one of [floor]: a walkway's, a building's deck, a city's street,
     *   the start pocket over a cave): its snaps onto that floor's nodes lead, their straight lines read for the water only (the
     *   terrain and the rooms under a floor are not what it walks on); the ground's after them, as the fallback.
     * - THE NEAREST STRAIGHT CLEAR LINE and those no more than [SPREAD_YD] longer lead (W's v2 parity, 244 ends of five zones: the
     *   nearest straight line clear of the fine ground's walls never crossed a wall segcheck sees; a farther one did 1 in 20 - a sub-25
     *   yd cliff the slope grid cannot show lies more often across a longer line, and preferring a line kept off steep cells made it
     *   worse); every node within [reach] read straight.
     * - no straight line clear: the walk round ([Walk], [walkYd] at most) to the [CANDIDATES] nearest, x [BENT] a yard.
     * - THE FALLBACK: every other snap, each [FAR_COST] x its rank dearer (nearest first): the router takes one only when the nearer
     *   ones' piece of the graph joins nothing on to the other end.
     */
    fun snap(p: DoubleArray, nodes: List<Pair<Int, DoubleArray>>, ground: Ground, danger: ((Double, Double) -> Float)? = null,
             k: Double = 0.0, reach: Double = SNAP_YD, walkYd: Double = WALK_YD, floor: Set<Int> = emptySet(),
             floorNodes: List<Pair<Int, DoubleArray>> = emptyList(), ways: List<ShippedRoute.WayView> = emptyList(),
             lattice: Climb.Grid? = null): Result {
        fun within(l: List<Pair<Int, DoubleArray>>) = l.map { (id, q) -> Triple(id, q, hypot(q[0] - p[0], q[1] - p[1])) }.filter { it.third <= reach }.sortedBy { it.third }
        // THE WAY CUTS: each way's nearest point within reach (a roadmap edge's walked points, a road's bends), a target as a node is
        // (keyed [CUT_KEY] - its index here; [Snap.way] / [Snap.t] say where)
        val cutAt = HashMap<Int, Pair<Int, Double>>()
        val cutPts = ArrayList<Pair<Int, DoubleArray>>()
        for (w in ways) {
            if (w.line.size < 2) continue
            if (w.line.none { hypot(it[0] - p[0], it[1] - p[1]) <= reach + w.len }) continue
            var best: DoubleArray? = null; var bd = Double.MAX_VALUE; var at = 0.0; var bestAt = 0.0
            for ((a, b) in w.line.zipWithNext()) {
                val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
                val t = if (l2 <= 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
                val q = doubleArrayOf(a[0] + t * dx, a[1] + t * dy); val d = hypot(q[0] - p[0], q[1] - p[1])
                if (d < bd) { bd = d; best = q; bestAt = at + t * Math.sqrt(l2) }
                at += Math.sqrt(l2)
            }
            if (best == null || bd > reach || w.len <= 0.0) continue
            val key = CUT_KEY - cutAt.size
            cutAt[key] = w.index to (bestAt / w.len).coerceIn(0.0, 1.0); cutPts.add(key to best)
        }
        val near = within(nodes + cutPts)
        val onFloor = floor.isNotEmpty() && (nodes + floorNodes).minByOrNull { hypot(it.second[0] - p[0], it.second[1] - p[1]) }?.first in floor
        val floorNear = if (onFloor) within(nodes.filter { it.first in floor } + floorNodes) else emptyList()
        if (near.isEmpty() && floorNear.isEmpty()) {
            val n = nodes.minOfOrNull { (_, q) -> hypot(q[0] - p[0], q[1] - p[1]) }
            return Result(emptyList(), if (n == null) "no node to snap to" else "no node within ${reach.toInt()} yd (the nearest ${n.toInt()} yd)")
        }
        val ground0 = if (near.isEmpty()) null else pick(p, near, ground, danger, k, reach, walkYd, lattice)
        val fl = if (floorNear.isEmpty()) null else pick(p, floorNear, Ground({ _, _ -> 1f }, ground.water, allowWater = ground.allowWater), danger, k, reach, walkYd)
        // the tiers, each later one FAR_COST x its rank dearer: an end on a floor - the floor's lead, the floor's other nodes nearest
        // first (a city's streets, a pocket's deck: often in pieces the graph joins only farther on), then the ground's; the ground's
        // lead, then its FALLBACKS one a rank, the level lines first, nearest first, then those over a climb or a cliff's band; none
        // over [FAR_STEEP] (W's parity: a farther line over a slope cell of 25 degrees or more crossed a wall segcheck sees 78% of the
        // time, a level one 9%)
        val tiers = ArrayList<List<Snap>>()
        val taken = HashSet<Int>()
        fun lead(r: Result?) = r?.snaps?.filter { it.lead && taken.add(it.node) }?.takeIf { it.isNotEmpty() }?.let { tiers.add(it) }
        fun rest(r: Result?) = r?.snaps?.filter { !it.lead && it.steep < FAR_STEEP && it.node !in taken }
            ?.sortedWith(compareBy({ it.steep > 1f }, { it.yd }))?.forEach { if (taken.add(it.node)) tiers.add(listOf(it)) }
        lead(fl); rest(fl); lead(ground0)
        // an end ON the ground's nearest piece (its lead within [ON_PIECE_YD]: a dock's deck, a ledge the graph keeps apart): no
        // fallback onto another piece - W's round 2, Mulgore L: the goal on the airship dock, its own piece joined to nothing, the
        // fallback's 8 yd line off the dock's edge a 90 degree face; the harness does not reach it either
        val onPiece = fl == null && ground0?.snaps?.firstOrNull { it.lead }?.let { it.yd <= ON_PIECE_YD } == true
        if (!onPiece) rest(ground0)
        val out = tiers.flatMapIndexed { rank, t -> t.map { it.ranked(rank) } }
            .map { sn -> cutAt[sn.node]?.let { (w, t) -> Snap(sn.node, sn.pts, sn.yd, sn.cost, sn.straight, sn.lead, sn.steep, w, t) } ?: sn }
        if (out.isEmpty()) return Result(emptyList(), (if (fl != null) "on a floor: ${fl.why}; " else "") + (ground0?.why ?: "no ground node within ${reach.toInt()} yd"))
        return Result(out, (if (fl != null && fl.ok) "on a floor: ${fl.why}" else ground0!!.why) + if (out.size > out.count { it.lead }) "; ${out.size - out.count { it.lead }} fallbacks" else "")
    }

    /** [near]'s snaps over [ground]: the lead ones ([Snap.lead]: the nearest clear line and those [SPREAD_YD] longer, else the bent
     *  walks' shortest and those [SPREAD_YD] longer), then the rest nearest first */
    private fun pick(p: DoubleArray, near: List<Triple<Int, DoubleArray, Double>>, ground: Ground, danger: ((Double, Double) -> Float)?,
                     k: Double, reach: Double, walkYd: Double, lattice: Climb.Grid? = null): Result {
        val view = View(p, ground, danger, k)
        if (lattice != null) return pickOnLattice(p, near, ground, view, lattice, reach, walkYd)
        val straight = ArrayList<Snap>()
        val blocked = ArrayList<String>()
        val bent = ArrayList<Triple<Int, DoubleArray, Double>>()
        for (c in near) {
            val line = view.line(p, c.second)
            if (line.why == null) straight.add(Snap(c.first, listOf(p, c.second), c.third, line.cost, true, steep = line.steep))
            else { if (bent.size < CANDIDATES && c.first > CUT_KEY) bent.add(c); blocked.add(line.why) }
        }
        val out = ArrayList<Snap>(straight)
        if (bent.isNotEmpty()) {
            val walk = Walk(view, walkYd)
            for ((id, q, _) in bent) walk.to(q)?.let { w ->
                out.add(Snap(id, w.pts, w.yd, w.cost * BENT, false, steep = w.pts.zipWithNext { a, b -> view.line(a, b).steep }.maxOrNull() ?: 1f)) }
        }
        if (out.isEmpty()) {
            val first = blocked.groupingBy { it.substringBefore(" at ") }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} x${it.value}" }
            return Result(emptyList(), "the ${near.size} nodes within ${reach.toInt()} yd (nearest ${near.first().third.toInt()} yd): " +
                "no clear walk of ${walkYd.toInt()} yd ($first)")
        }
        val pool = if (straight.isNotEmpty()) straight else out
        val shortest = pool.minOf { it.yd }
        val lead = pool.filter { it.yd <= shortest + SPREAD_YD }.sortedBy { it.cost }.map { Snap(it.node, it.pts, it.yd, it.cost, it.straight, true, it.steep) }
        val rest = out.filter { s -> lead.none { it.node == s.node } }.sortedBy { it.yd }
        return Result(lead + rest, "snapped ${lead.first().yd.toInt()} yd ${if (lead.first().straight) "straight" else "round a wall or water"} " +
            "(${lead.size} leading of ${near.size} nodes within ${reach.toInt()} yd${if (blocked.isNotEmpty()) "; ${blocked.size} lines walled" else ""})")
    }

    /**
     * THE SNAP ON THE SHIPPED LATTICE ([ShippedNet.Walk]; the user's "Yes, try it"): the ground judged by the gate's own rule instead of
     * the 25 yd slope grid - a straight line to a node only when [Climb.walkable] passes it on the lattice (its steps and its faces, as
     * py/segcheck.py reads them) and the device's own water and rooms let it; else the lattice's own leg round ([Climb.route], deep water
     * a wall), every piece of it held to the same. Leading and fallbacks as [pick]'s: the nearest clear straight line (and those
     * [SPREAD_YD] longer), else the shortest walk; the rest nearest first.
     */
    private fun pickOnLattice(p: DoubleArray, near: List<Triple<Int, DoubleArray, Double>>, ground: Ground, view: View, g: Climb.Grid,
                              reach: Double, walkYd: Double): Result {
        val deep: (Double, Double) -> Boolean = { x, y -> ground.deep(x, y) }
        fun clear(a: DoubleArray, b: DoubleArray): View.Line? {
            val soft = view.line(a, b, terrain = false)
            return if (soft.why == null && Climb.walkable(g, a, b)) soft else null
        }
        val straight = ArrayList<Snap>(); val bent = ArrayList<Triple<Int, DoubleArray, Double>>(); var walled = 0
        for (c in near) {
            val l = clear(p, c.second)
            if (l != null) straight.add(Snap(c.first, listOf(p, c.second), c.third, l.cost, true, steep = l.steep))
            else { walled++; if (bent.size < CANDIDATES && c.first > CUT_KEY) bent.add(c) }
        }
        val out = ArrayList<Snap>(straight)
        for ((id, q, _) in bent) {
            val leg = Climb.route(g, p, q, water = deep, edges = false, limits = Climb.Limits(LATTICE_WINDOW)) ?: continue
            if (!leg.reached || leg.pts.size < 2) continue
            val pts = ShippedNet.anchored(leg.pts.map { doubleArrayOf(it[0], it[1]) }, p, q).first
            val yd = ShippedRoute.length(pts); if (yd > walkYd) continue
            val ls = pts.zipWithNext { a, b -> clear(a, b) }
            if (ls.any { it == null }) continue
            out.add(Snap(id, pts, yd, ls.sumOf { it!!.cost } * BENT, false, steep = ls.maxOf { it!!.steep }))
        }
        if (out.isEmpty()) return Result(emptyList(), "the ${near.size} nodes within ${reach.toInt()} yd (nearest ${near.first().third.toInt()} yd): " +
            "no walk of ${walkYd.toInt()} yd on the shipped lattice")
        val pool = if (straight.isNotEmpty()) straight else out
        val shortest = pool.minOf { it.yd }
        val lead = pool.filter { it.yd <= shortest + SPREAD_YD }.sortedBy { it.cost }.map { Snap(it.node, it.pts, it.yd, it.cost, it.straight, true, it.steep) }
        val rest = out.filter { s -> lead.none { it.node == s.node } }.sortedBy { it.yd }
        return Result(lead + rest, "snapped ${lead.first().yd.toInt()} yd ${if (lead.first().straight) "straight" else "round"} on the shipped lattice " +
            "(${lead.size} leading of ${near.size} nodes within ${reach.toInt()} yd; $walled lines walled)")
    }
    const val LATTICE_WINDOW = 120_000      // the lattice leg's scratch at most (vertices): ~ 340 x 340 steps

    /**
     * THE SNAP AS ShippedRoute's ATTACH ([ShippedRoute.Attacher], the seam): each end's [snap]s onto the graph's nodes over [ground]
     * (null: the router's own ground, [ShippedRoute.AttachView.ground], read as the terrain); with none, the old long attach
     * ([ShippedRoute.DefaultAttach]: a walk onto the nearest ways, [ShippedRoute.AttachView.lim] at most) over the same ground with
     * the water and the rooms folded in as walls ([Ground.asCost]), its why the snap's own - logged by the caller.
     */
    fun attacher(ground: Ground? = null, log: ((String) -> Unit)? = null) = object : ShippedRoute.Attacher {
        override fun attach(v: ShippedRoute.AttachView, p: DoubleArray): ShippedRoute.Attach {
            val g = ground ?: Ground(v.ground)
            val s = snap(p, v.nodes, g, v.danger, v.k, floor = v.floor, floorNodes = v.floorNodes, ways = v.ways, lattice = v.lattice)
            if (s.ok) return ShippedRoute.Attach(s.snaps.map {
                if (it.cut) ShippedRoute.Join(null, it.way, it.t, it.pts.last(), it.pts, it.cost, it.yd)
                else ShippedRoute.Join(it.node, -1, 0.0, it.pts.last(), it.pts, it.cost, it.yd) }, true, s.why)
            log?.invoke("snap: none at ${p[0].toInt()},${p[1].toInt()} (${s.why}) - the long attach")
            val long = ShippedRoute.DefaultAttach.attach(ShippedRoute.AttachView(emptyList(), v.ways, g.asCost, v.danger, v.k, v.lim), p)
            return ShippedRoute.Attach(long.joins, false, "${long.why ?: "on ${long.joins.size} ways by the long attach"} (no snap: ${s.why})")
        }
        override fun direct(v: ShippedRoute.AttachView, from: DoubleArray, to: DoubleArray): ShippedRoute.Join? =
            directWalk(from, to, ground ?: Ground(v.ground), v.danger, v.k, lattice = v.lattice)?.let { ShippedRoute.Join(null, -1, 0.0, to, it.pts, it.cost, it.yd) }
    }

    /**
     * THE NEAR-GOAL WALK with no net (the graph's rival for a goal within ShippedRoute.DIRECT_MAX_YD): over the fine ground with the
     * snap's refusals AND only on LEVEL ground ([LEVEL]: no slope cell of 25 degrees or more, no cliff's band - W's Kalimdor parity,
     * Felwood J and Moonglade C: the old walk crossed a 70 degree face over 24 yd the 25 yd slope grid read as a climb): the straight
     * line when it is clear, else the walk round ([Walk], [lim] at most). Null: none walks clean - the net's way only.
     */
    fun directWalk(from: DoubleArray, to: DoubleArray, ground: Ground, danger: ((Double, Double) -> Float)? = null, k: Double = 0.0,
                   lim: Double = ShippedRoute.DIRECT_WALK_YD, lattice: Climb.Grid? = null): Snap? {
        if (lattice != null) {
            // the shipped lattice: its own rule, straight when it passes, else its leg (deep water a wall)
            val view = View(from, ground, danger, k)
            fun clear(a: DoubleArray, b: DoubleArray) = view.line(a, b, terrain = false).takeIf { it.why == null && Climb.walkable(lattice, a, b) }
            clear(from, to)?.let { return Snap(-1, listOf(from, to), hypot(to[0] - from[0], to[1] - from[1]), it.cost, true) }
            val leg = Climb.route(lattice, from, to, water = { x, y -> ground.deep(x, y) }, edges = false, limits = Climb.Limits(LATTICE_WINDOW))
                ?.takeIf { it.reached && it.pts.size >= 2 } ?: return null
            val pts = ShippedNet.anchored(leg.pts.map { doubleArrayOf(it[0], it[1]) }, from, to).first
            val ls = pts.zipWithNext { a, b -> clear(a, b) }; if (ls.any { it == null }) return null
            val yd = ShippedRoute.length(pts); if (yd > lim) return null
            return Snap(-1, pts, yd, ls.sumOf { it!!.cost }, false)
        }
        val view = View(from, ground, danger, k, level = true)
        val line = view.line(from, to)
        if (line.why == null) return Snap(-1, listOf(from, to), hypot(to[0] - from[0], to[1] - from[1]), line.cost, true)
        val w = Walk(view, lim).to(to) ?: return null
        return Snap(-1, w.pts, w.yd, w.cost, false)
    }

    /** the straight line from [a] to [b] read every [LINE_STEP_YD] over [ground] (an end in a wall passes those within [DOOR_YD] of
     *  it): null = clear, else the first wall's words and where */
    fun lineWall(a: DoubleArray, b: DoubleArray, ground: Ground): String? = View(a, ground, null, 0.0).line(a, b).why

    // ---------------------------------------------------------------- the fine reads

    /** one end's view of the ground: [c] the end (its door rule), the cost a yard with the danger */
    private class View(val c: DoubleArray, val g: Ground, val danger: ((Double, Double) -> Float)?, val k: Double, val level: Boolean = false) {
        val inWall = g.wall(c[0], c[1]) != null || (level && g.terrain(c[0], c[1]).let { !it.isNaN() && it > LEVEL })
        /** [steep] the line's dearest terrain a yard (1 level; the slope grid's climb, a cliff's band) */
        class Line(val cost: Double, val why: String?, val steep: Float = 1f)
        /** the wall at ([x], [y]) for this end's walk (null: none; the door's within [DOOR_YD] of an end standing in one) */
        fun wall(x: Double, y: Double, terrain: Boolean = true): String? {
            if (!terrain) return g.wall(x, y, false)?.let { w -> if (inWall && hypot(x - c[0], y - c[1]) <= DOOR_YD) null else w }
            val w = g.wall(x, y) ?: (if (level && !g.walkedWater(x, y)) g.terrain(x, y).takeIf { !it.isNaN() && it > LEVEL }?.let { "a climb or a cliff's band" } else null) ?: return null
            return if (inWall && hypot(x - c[0], y - c[1]) <= DOOR_YD) null else w
        }
        fun yard(x: Double, y: Double): Double = g.yard(x, y) + k * (danger?.invoke(x, y)?.takeIf { !it.isNaN() } ?: 0f)
        /** the straight line [a] -> [b], every [LINE_STEP_YD]; its last [END_YD] (the node's own spot) never a wall */
        fun line(a: DoubleArray, b: DoubleArray, terrain: Boolean = true): Line {
            val len = hypot(b[0] - a[0], b[1] - a[1]); val m = max(1, ceil(len / LINE_STEP_YD).toInt()); var s = 0.0
            for (q in 0..m) {
                val t = q.toDouble() / m; val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
                if (len * (1 - t) > END_YD) wall(x, y, terrain)?.let { return Line(Double.NaN, "$it at ${x.toInt()},${y.toInt()}") }
                if (q < m) s += yard(x + (b[0] - a[0]) / (2 * m), y + (b[1] - a[1]) / (2 * m)) * len / m
            }
            var st = 1f
            for (q in 0..m) { val t = q.toDouble() / m; g.terrain(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t).takeIf { !it.isNaN() }?.let { if (it > st) st = it } }
            return Line(s, null, st)
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
