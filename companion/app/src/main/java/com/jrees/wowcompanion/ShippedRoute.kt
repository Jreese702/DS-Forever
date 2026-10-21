package com.jrees.wowcompanion

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * THE ROUTE ON THE SHIPPED NETWORK (the user, 2026-10-19: "Ship our path network"; E:/safepath-work/docs/DEVICE_INTEGRATION_PLAN.md,
 * SHIPPED NETWORK step 3). Pure: no Android, no files - the parity test (helper W) and MapView call the same [plan].
 *
 * ONE GRAPH: the shipped ways (roads, trails, spurs, caves, lifts, the joins between the net's pieces and to the cave mouths), each
 * along its OWN shipped points (a rewalk way never drawn straight: its walked polyline, else the ground's own walk between its ends),
 * the [Roadmap] (NET1 v2's coverage layer: ~40 yd nodes over all walkable ground, its walked edges with their cost tiers and danger)
 * and the section's links (portals, updrafts, stairs; a SWIM only for a goal on its island).
 *
 * 1. THE ATTACH LEGS: the player, and the goal, snap to the nearest graph nodes within [SNAP_YD] by a short walk over the device's own
 *    ground ([ground]: the terrain grid's leg cost; [Terrain.WALL] or more - a cliff, a building, deep water - and a steep bank of
 *    [ATTACH_WALL] or more are walls to it). Only with no node that near: the old walk onto the nearest ways, [ATTACH_MAX_YD] walked
 *    at most, its why said. Farther: no route ([Result.reached] false); the caller keeps its old line.
 * 2. THE COSTS: roads first - a road's yard 1, a spur, a join or a cave x1.3, a trail x2.2 and its climb, a roadmap edge [NET_FAVOUR] x
 *    its tier; an AVOID way x6, a wading way [WADE_EXTRA_YD] dearer, a lift its wait; and the danger along each: the character's own
 *    ([danger] x [k], sampled every [SAMPLE_YD]) or, with none, the network's per-faction share.
 * 3. THE DETOUR CAP ([SafePath.capDetour]): the way round the danger against the same search with no danger counted - longer than
 *    1.75x + 500 yd, the no-danger way wins.
 */
object ShippedRoute {
    const val SNAP_YD = 60.0               // an end snaps to a graph node this near (straight) ...
    const val SNAP_WALK_YD = 90.0          // ... walked in no more than this
    const val SNAP_CANDIDATES = 8          // the nearest nodes tried at each end
    const val ATTACH_MAX_YD = 200.0        // with no node near: the walk onto / off the ways at most this long (the ruling: 150-200 yd)
    const val ATTACH_STEP_YD = 5.0         // the attach leg's cells
    const val ATTACH_CANDIDATES = 6        // the nearest ways tried at each end (the long attach)
    const val ATTACH_WALL = 12f            // the attach leg's wall: a steep bank (Terrain.legFactor at 45 degrees) or worse
    const val DOOR_YD = 15.0               // an end standing in a wall (in a building): walls this near it are passed (its door)
    // the ground's own walk straight to a goal this near, the graph's rival. All-zones parity A (2026-10-21): the walk's three
    // crossings (Stormwind B, D: a 90 degree curb; Dun Morogh I: 62 degrees over 8.5 yd) were 73-115 yd walks over faces the 25 yd
    // slope grid reads level; at 60 yd every one takes the net, and no zone of six lost a safe route
    const val DIRECT_MAX_YD = 60.0
    const val GHOST_DIRECT_MAX_YD = 120.0  // a ghost's (CorpseRoute: no danger, walks on water, drops down): the old reach
    const val DIRECT_WALK_YD = 180.0       // the near-goal walk's own yards at most (round a wall)
    const val NET_FAVOUR = 1.3             // a roadmap edge's yard against a road's (the harness's NET_FAVOUR)
    const val SAMPLE_YD = 10.0             // the character's danger read along a way this often
    const val AVOID_FACTOR = 6.0           // an AVOID way's yard
    const val WADE_EXTRA_YD = 60.0         // a way that wades: this much dearer (a drier way preferred)
    const val LINK_SNAP_YD = 40.0          // a link's end joins the graph's node this near
    const val LINK_SAME_YD = 2.0           // two links' ends this near are one place (a chain: a portal's landing, the stairs' foot)
    const val LINK_BASE = 900_000          // the links' end nodes' ids (under the roadmap's RM_BASE)
    const val STITCH_YD = 35.0             // an OLD file's run-time stitch: a net node to its nearest roadmap nodes this near ...
    const val STITCH_N = 2                 // ... this many, each where the straight line crosses no wall ([ATTACH_WALL])
    const val PULL_SAMPLE_YD = 1.0         // a pulled attach line read this often
    const val REWALK_TRIES = 4             // a rewalk way no ground walk joins is dropped and the graph searched again, this often

    /** what [plan] found: [pts] world (x, y[, mark]) from the player to the goal (two points: the straight line, not reached); [why]
     *  the log's words; [attachFromYd] / [attachToYd] the attach legs' walked yards; [capped] the no-danger way taken by the cap;
     *  [snapped] both ends snapped to a node (no long attach) */
    class Result(val pts: List<DoubleArray>, val reached: Boolean, val why: String, val yd: Double = length(pts),
                 val attachFromYd: Double = 0.0, val attachToYd: Double = 0.0, val capped: Boolean = false, val swims: Int = 0,
                 val snapped: Boolean = false, val roadmapYd: Double = 0.0, val danger: Double = 0.0, val avoided: Double = 0.0,
                 /** LIVE AGGRO: the live part of [danger] (k x the plates' discs along the way) */
                 val live: Double = 0.0) {
        val bends get() = max(0, pts.size - 2)
    }

    /**
     * THE ROADMAP (NET1 v2's coverage layer), as the router takes it: [nodes] id -> world (x, y) - ids of their own (never the net's;
     * [RM_BASE] up from NET1's), each net node joined to its nearest within [STITCH_YD]; [edges] walked ([Edge.pts]: its points
     * between its ends, or none = straight), [Edge.tier] its cost a yard (1 level; the climb's, the edge clearance's, a building's or
     * a wade's dearer), [Edge.danger] this faction's danger along it in the danger grid's units (null: none shipped)
     */
    class Roadmap(val nodes: Map<Int, DoubleArray>, val edges: List<Edge>,
                  /** the nodes on a floor layer (ShippedNet.R_FLOOR: a walkway's, a building's deck): an end on one snaps to its floor first */
                  val floor: Set<Int> = emptySet()) {
        class Edge(val a: Int, val b: Int, val tier: Double = 1.0, val danger: Double? = null, val pts: List<DoubleArray> = emptyList(),
                   /** it crosses a ford ([tier] holds its [RM_WADE_COST]; a ghost's way does not pay it) */
                   val wade: Boolean = false)
    }
    const val RM_BASE = 1_000_000

    fun length(w: List<DoubleArray>) = w.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }.sum()

    /** a section alone carries no roadmap (NET1 v2 keeps it on the pack: [roadmapOf] (pack's roadmap, section)) */
    fun roadmapOf(@Suppress("UNUSED_PARAMETER") sec: ShippedNet.Section): Roadmap? = null
    private val rmCache = java.util.WeakHashMap<ShippedNet.Section, Pair<ShippedNet.Roadmap, Roadmap>>()
    /**
     * the pack's [ShippedNet.Roadmap] (NET1 v2) as the router takes it, with [sec]'s faction danger (Section.roadmapDangerOf; none
     * shipped: null, the character's own only): node n -> [RM_BASE] + n; an edge's cost a yard Climb.COST of its climb tier + its edge
     * tier x ShippedNet.EDGE_TIER_COST, a building's edge +[RM_BUILDING_COST], a wade's +[RM_WADE_COST]. Kept per section (weakly).
     */
    fun roadmapOf(rm: ShippedNet.Roadmap?, sec: ShippedNet.Section): Roadmap? {
        rm ?: return null
        synchronized(rmCache) { rmCache[sec]?.let { (r, out) -> if (r === rm) return out } }
        val nodes = HashMap<Int, DoubleArray>(rm.nodes * 2)
        for (n in 0 until rm.nodes) nodes[RM_BASE + n] = doubleArrayOf(rm.x(n), rm.y(n))
        val edges = (0 until rm.edges).map { e ->
            val f = rm.edgeFlags[e]
            val tier = Climb.COST[ShippedNet.climbTier(f).coerceIn(0, 2)] + ShippedNet.edgeTier(f) * ShippedNet.EDGE_TIER_COST +
                (if (f and ShippedNet.RE_BUILDING != 0) RM_BUILDING_COST else 0.0) + (if (f and ShippedNet.RE_WADE != 0) RM_WADE_COST else 0.0)
            val pts = if (rm.edgePts[e] == null) emptyList() else rm.points(e).let { it.subList(1, it.size - 1) }
            Roadmap.Edge(RM_BASE + rm.edgeA[e], RM_BASE + rm.edgeB[e], tier, sec.roadmapDanger?.let { sec.roadmapDangerOf(e).toDouble() }, pts,
                f and ShippedNet.RE_WADE != 0)
        }
        val floor = (0 until rm.nodes).filter { rm.nodeFlags[it] and ShippedNet.R_FLOOR != 0 }.map { RM_BASE + it }.toHashSet()
        return Roadmap(nodes, edges, floor).also { synchronized(rmCache) { rmCache[sec] = rm to it } }
    }
    const val RM_BUILDING_COST = 0.5       // a roadmap edge within 2 yd of a building's rooms: this much dearer a yard
    const val RM_WADE_COST = SafePath.FORD_COST - 1.0   // ... one that crosses a ford

    /** way [i]'s walked points between its ends (NET1 v2's [ShippedNet.Way.pts], its ends dropped), null = straight; a floor's street
     *  (ShippedNet.F_FLOOR) marked in-floor (Climb.IN_CAVE) */
    fun wayPts(sec: ShippedNet.Section, i: Int): List<DoubleArray>? {
        val w = sec.ways.getOrNull(i) ?: return null
        val p = w.pts?.takeIf { it.size > 2 } ?: return null
        val floor = w.flags and ShippedNet.F_FLOOR != 0
        return p.subList(1, p.size - 1).map { if (floor) doubleArrayOf(it[0], it[1], Climb.IN_CAVE) else doubleArrayOf(it[0], it[1]) }
    }

    /**
     * the way from [from] to [to] (world x, y) on [sec]'s graph (and [roadmap]). [ground] the ground's cost per yard at a world point
     * (NaN = unknown, 1; [Terrain.WALL] or more = a wall), [danger] the character's danger there (null: the net's own shares), [k] its
     * weight ([DangerGrid.strength]; 0 = none). [toExit]: the goal is a crossing out, snapped to the net's exit within
     * [SafePath.EXIT_SNAP_YD]. The last [goalFreeYd] to the goal are never danger. [links]: which of the section's links may be taken
     * (all by default; a ghost's corpse run: only the walked ones, [CorpseRoute.walkable] - no portal, no updraft, no ride).
     */
    fun plan(sec: ShippedNet.Section, ground: (Double, Double) -> Float, danger: ((Double, Double) -> Float)?, from: DoubleArray, to: DoubleArray,
             k: Double = DangerGrid.strength("high"), toExit: Boolean = false, attachMaxYd: Double = ATTACH_MAX_YD,
             goalFreeYd: Double = DangerGrid.GOAL_FREE_YD, roadmap: Roadmap? = roadmapOf(sec), attacher: Attacher = ShippedSnap.attacher(),
             stitchGround: ShippedSnap.Ground? = null, live: LiveDanger.Snapshot? = null,
             links: (ShippedNet.Link) -> Boolean = { true },
             /** a ghost's corpse run (CorpseRoute): no AVOID way's x6, no wade's surcharge, a swim whatever the goal */
             ghost: Boolean = false): Result {
        // LIVE AGGRO (LiveDanger): the plates' discs over the static danger, the spawns they show absent or hold taken off - a cost
        // like the static danger (k x it a yard), never a wall; only over a character danger (none: the net's own shares, as before)
        @Suppress("NAME_SHADOWING") val danger = LiveDanger.combine(danger, live)
        val liveOn = live != null && !live.empty && danger != null
        var goal = to
        if (toExit) sec.net.exits.mapNotNull { x -> sec.net.nodes.firstOrNull { it.id == x.node } }.minByOrNull { hypot(it.wx - to[0], it.wy - to[1]) }
            ?.takeIf { hypot(it.wx - to[0], it.wy - to[1]) <= SafePath.EXIT_SNAP_YD }?.let { goal = doubleArrayOf(it.wx, it.wy) }
        // the last yards to the goal are never danger (a kill objective, a giver among mobs: the route must reach them; DangerGrid's rule)
        val gx = goal[0]; val gy = goal[1]
        val dz = danger?.let { d -> { x: Double, y: Double -> if (hypot(x - gx, y - gy) <= goalFreeYd) 0f else d(x, y) } }
        val g = Graph(sec, roadmap, dz, ground, attacher, stitchGround, links, ghost)
        val round = g.search(ground, dz, from, goal, k, attachMaxYd)
        if (round.pts.size < 2 || !round.reached) return round
        // THE DANGER TERM (the user's test20: "pathing me into hostile mobs"): the route's k x danger a yard ([dangerOf], the goal's free
        // yards none), and what the way round avoided against the way ignoring the danger - logged on the route's line
        // the live part along the way, said as "live D (n mobs)" (the goal's free yards none)
        val lz = if (liveOn) { x: Double, y: Double -> if (hypot(x - gx, y - gy) <= goalFreeYd) 0f else live!!.at(x, y) } else null
        fun with(r: Result, d: Double, avoided: Double, extra: String = ""): Result {
            val lv = if (lz == null) 0.0 else k * dangerOf(r.pts, lz)
            return Result(r.pts, true, r.why + extra + "; danger ${d.toInt()} (avoided ${avoided.toInt()})" + (if (live != null) "; " + live.words(lv) else ""),
                attachFromYd = r.attachFromYd, attachToYd = r.attachToYd, capped = extra.isNotEmpty(), swims = r.swims, snapped = r.snapped,
                roadmapYd = r.roadmapYd, danger = d, avoided = avoided, live = lv)
        }
        if (k <= 0.0 || dz == null) return with(round, 0.0, 0.0)
        val dRound = k * dangerOf(round.pts, dz)
        val calm = g.search(ground, dz, from, goal, 0.0, attachMaxYd).takeIf { it.reached }
        val dCalm = calm?.let { k * dangerOf(it.pts, dz) } ?: dRound
        val pick = SafePath.capDetour(round.pts, calm?.pts)
        if (pick === round.pts || calm == null) return with(round, dRound, dCalm - dRound)
        return with(calm, dCalm, 0.0, "; capped: round the danger ${round.yd.toInt()} yd, the way ignoring it ${calm.yd.toInt()} yd")
    }

    /** a way's danger: [danger] a yard along its points (read every [SAMPLE_YD] / 4), none inside a cave or on a floor (marked points) */
    fun dangerOf(pts: List<DoubleArray>, danger: (Double, Double) -> Float): Double {
        var s = 0.0
        for ((a, b) in pts.zipWithNext()) {
            if (Climb.inCave(a) && Climb.inCave(b)) continue
            val len = hypot(b[0] - a[0], b[1] - a[1]); val n = max(1, ceil(len / (SAMPLE_YD / 4)).toInt())
            for (i in 0 until n) { val t = (i + 0.5) / n; val d = danger(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t); if (!d.isNaN()) s += d * len / n }
        }
        return s
    }

    /** the one graph: the net's ways along their points, the roadmap's edges, their base costs and the danger along them */
    private class Graph(val sec: ShippedNet.Section, val roadmap: Roadmap?, danger: ((Double, Double) -> Float)?,
                        @Suppress("UNUSED_PARAMETER") ground: (Double, Double) -> Float, val attacher: Attacher, stitchGround: ShippedSnap.Ground?,
                        val linkOk: (ShippedNet.Link) -> Boolean = { true }, val ghost: Boolean = false) {
        /** the fine ground (plan's stitchGround: ShippedSnap's), for the checks of what the device itself draws onto a shipped line */
        val fineGround = stitchGround
        val net = sec.net
        /** a way: [via] its points after [a] up to [b] (both directions walk it), [rewalk] a rewalk way with no shipped points */
        /** [share]: the net's danger share (the cost x 1 + it with no character danger); [fixedYd]: the roadmap's shipped danger x yards
         *  (added x k with no character danger) */
        class W(val a: Int, val b: Int, val via: List<DoubleArray>, val len: Double, val base: Double, val dangerYd: Double, val share: Double,
                val onLand: Boolean, val roadmap: Boolean, val rewalk: Boolean, val fixedYd: Double? = null)
        val ways = ArrayList<W>()
        /** the stitches: [shipped] join ends on roadmap nodes, [stitched] made at run time (an old file), [refused] by the fine ground */
        var shipped = 0; var stitched = 0; var refused = 0
        val pos = HashMap<Int, DoubleArray>()
        /** the nodes an end may snap to (no cave's or lift's inside point: its floor is not the terrain the walk is on) */
        val snapIds = ArrayList<Int>()
        init {
            for (n in net.nodes) {
                pos[n.id] = when (n.kind) {
                    SafePath.CAVE -> doubleArrayOf(n.wx, n.wy, Climb.IN_CAVE); SafePath.LIFT -> doubleArrayOf(n.wx, n.wy, Climb.LIFT)
                    else -> doubleArrayOf(n.wx, n.wy) }
                if (n.kind != SafePath.CAVE && n.kind != SafePath.LIFT) snapIds.add(n.id)
            }
            fun dangerAlong(pts: List<DoubleArray>): Double {
                if (danger == null) return 0.0
                var s = 0.0
                for ((p, q) in pts.zipWithNext()) {
                    val len = hypot(q[0] - p[0], q[1] - p[1]); val n = max(1, ceil(len / SAMPLE_YD).toInt())
                    for (i in 0 until n) { val t = (i + 0.5) / n; val d = danger(p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t); if (!d.isNaN()) s += d * len / n }
                }
                return s
            }
            net.edges.forEachIndexed { i, e ->
                if (e.state == SafePath.REMOVED) return@forEachIndexed
                val a = pos[e.a] ?: return@forEachIndexed; val b = pos[e.b] ?: return@forEachIndexed
                val flags = sec.ways.getOrNull(i)?.flags ?: ShippedNet.flagsOf(e)
                val mid = wayPts(sec, i) ?: emptyList()
                val line = listOf(a) + mid + listOf(b)
                val len = length(line)
                val kindF = when {
                    flags and ShippedNet.F_ROAD != 0 || e.kind == SafePath.ROAD -> 1.0
                    flags and ShippedNet.F_LIFT != 0 || e.kind == SafePath.LIFT -> 1.0
                    flags and (ShippedNet.F_SPUR or ShippedNet.F_CAVE or ShippedNet.F_JOIN) != 0 || e.kind == SafePath.SPUR || e.kind == SafePath.CAVE ||
                        e.kind == JOIN || e.src == JOIN -> 1.3
                    else -> 2.2 }
                val climb = if (kindF == 1.0) 1.0 else e.climb.toDouble().coerceAtLeast(1.0)
                val avoid = if (!ghost && (flags and ShippedNet.F_AVOID != 0 || e.state == SafePath.AVOID)) AVOID_FACTOR else 1.0
                val lift = flags and ShippedNet.F_LIFT != 0 || e.kind == SafePath.LIFT
                val extra = (if (!ghost && flags and (ShippedNet.F_WADE or ShippedNet.F_SWIM) != 0) WADE_EXTRA_YD else 0.0) + (if (lift) Caves.LIFT_WAIT_YD else 0.0)
                // the character's danger along the way (none inside a cave or on a lift: their floor is not the ground the mobs stand on)
                val inside = e.kind == SafePath.CAVE || lift || flags and (ShippedNet.F_CAVE or ShippedNet.F_FLOOR) != 0
                val rewalk = mid.isEmpty() && (flags and ShippedNet.F_REWALK != 0 || e.state == SafePath.REWALK) && !inside
                // A CAVE'S OR A FLOOR'S WAY carries the in-floor mark on its own stretch (W, round 2: Alterac's edge 822, mouth to mouth,
                // 73 yd under the hill - unmarked, segcheck read the terrain over the cave): its inner points marked, and its ends'
                // marked copies where an end is a mouth (on the terrain), so every segment of the way is a marked one
                val via = if (!inside || lift) mid + listOf(b) else {
                    fun mk(q: DoubleArray) = if (Climb.inCave(q)) q else doubleArrayOf(q[0], q[1], Climb.IN_CAVE)
                    listOf(mk(a)).filter { !Climb.inCave(a) } + mid.map { mk(it) } + listOf(mk(b)).filter { !Climb.inCave(b) } + listOf(b)
                }
                ways.add(W(e.a, e.b, via, len, len * kindF * climb * avoid + extra, if (inside) 0.0 else dangerAlong(line),
                    e.danger.toDouble(), !inside, false, rewalk))
            }
            if (roadmap != null) {
                // a roadmap node on a floor layer (R_FLOOR: a city's street, a deck) carries the floor mark, and so does an edge's walk
                // between two of them (Dalaran: its points read as the floor's, not the ground's under it - W's round 2c)
                for ((id, p) in roadmap.nodes) if (pos.putIfAbsent(id, if (id in roadmap.floor) doubleArrayOf(p[0], p[1], Climb.IN_CAVE)
                        else doubleArrayOf(p[0], p[1])) == null) snapIds.add(id)
                for (e in roadmap.edges) {
                    val a = pos[e.a] ?: continue; val b = pos[e.b] ?: continue
                    val inner = if (Climb.inCave(a) && Climb.inCave(b)) e.pts.map { doubleArrayOf(it[0], it[1], Climb.IN_CAVE) } else e.pts
                    val line = listOf(a) + inner + listOf(b); val len = length(line)
                    val tier = if (ghost && e.wade) e.tier - RM_WADE_COST else e.tier        // a ghost walks the ford's water
                    ways.add(W(e.a, e.b, inner + listOf(b), len, len * NET_FAVOUR * tier.coerceAtLeast(1.0), dangerAlong(line), 0.0, true, true, false,
                        e.danger?.let { it * len }))
                }
                // THE STITCHES (net node <-> roadmap node). SHIPPED (NET1 v2 Section.stitches, T1: lattice-walked, segcheck- and
                // wetcheck-checked at export): taken along their points, and nothing stitched at run time. An OLD file without them:
                // a guarded run-time stitch, only over a straight step the FINE ground clears ([stitchGround], ShippedSnap.lineWall), else none
                val cell = 40.0
                fun key(x: Double, y: Double) = (Math.floor(x / cell).toLong() shl 32) xor (Math.floor(y / cell).toLong() and 0xffffffffL)
                val buckets = HashMap<Long, ArrayList<Int>>()
                for (id in roadmap.nodes.keys) { val p = pos[id]!!; buckets.getOrPut(key(p[0], p[1])) { ArrayList() }.add(id) }
                fun near(p: DoubleArray): List<Int> { val out = ArrayList<Int>()
                    for (dx in -1..1) for (dy in -1..1) buckets[key(p[0] + dx * cell, p[1] + dy * cell)]?.let { out.addAll(it) }
                    return out.sortedBy { hypot(pos[it]!![0] - p[0], pos[it]!![1] - p[1]) } }
                val onLandNodes = net.nodes.filter { it.kind != SafePath.CAVE && it.kind != SafePath.LIFT }
                for (st in sec.stitches) {
                    val a = pos[st.node] ?: continue; val b = pos[RM_BASE + st.rm] ?: continue
                    val inner = if (st.pts.size > 2) st.pts.subList(1, st.pts.size - 1).map { doubleArrayOf(it[0], it[1]) } else emptyList()
                    val line = listOf(a) + inner + listOf(b); val len = length(line)
                    val wade = if (!ghost && st.flags and (ShippedNet.F_WADE or ShippedNet.F_SWIM) != 0) WADE_EXTRA_YD else 0.0
                    ways.add(W(st.node, RM_BASE + st.rm, inner + listOf(b), len, len * NET_FAVOUR + wade, dangerAlong(line), 0.0, true, true, false, 0.0))
                    shipped++
                }
                if (sec.stitches.isEmpty() && stitchGround != null) for (n in onLandNodes) {
                    val p = pos[n.id]!!
                    var made = 0
                    for (id in near(p)) {
                        if (made >= STITCH_N) break
                        val q = pos[id]!!; val d = hypot(q[0] - p[0], q[1] - p[1])
                        if (d > STITCH_YD) break
                        if (ShippedSnap.lineWall(p, q, stitchGround) != null) { refused++; continue }
                        ways.add(W(n.id, id, listOf(q), d, d * NET_FAVOUR, dangerAlong(listOf(p, q)), 0.0, true, true, false, 0.0)); made++; stitched++
                    }
                }
            }
        }
        val stitchWords get() = when {
            shipped > 0 -> "stitches shipped ($shipped)"
            stitched > 0 || refused > 0 -> "stitches at run time ($stitched; $refused refused by the fine ground)"
            else -> "no stitches"
        }
        /**
         * A CUT INTO WAY [w] (W's Dustwallow N30-N31: a cut mid-segment, its piece of the shipped segment failing segcheck's lattice
         * step though the whole segment passes): at ([t], [q]) only when the fine ground ([fineGround], ShippedSnap.lineWall) clears
         * both pieces of the segment the cut splits; else at that segment's nearer vertex whose own walk there ([walk]'s last step,
         * re-aimed from [from]) the fine ground clears - (t, point, moved), null when neither. With no fine ground (a caller without
         * ShippedSnap's ground: tests) the cut stays where the attacher put it.
         */
        fun cutAt(w: W, t: Double, q: DoubleArray, from: DoubleArray): Triple<Double, DoubleArray, Boolean>? {
            val line = listOf(pos[w.a]!!) + w.via
            val at = t * w.len
            var run = 0.0; var i = 1
            while (i < line.size - 1 && run + hypot(line[i][0] - line[i - 1][0], line[i][1] - line[i - 1][1]) < at) { run += hypot(line[i][0] - line[i - 1][0], line[i][1] - line[i - 1][1]); i++ }
            val va = line[i - 1]; val vb = line[i]
            val runB = run + hypot(vb[0] - va[0], vb[1] - va[1])
            val g = fineGround
            fun clear(a: DoubleArray, b: DoubleArray) = g != null && ShippedSnap.lineWall(a, b, g) == null
            if (hypot(q[0] - va[0], q[1] - va[1]) < 0.25) return Triple(run / w.len.coerceAtLeast(1e-9), va, true)
            if (hypot(q[0] - vb[0], q[1] - vb[1]) < 0.25) return Triple(runB / w.len.coerceAtLeast(1e-9), vb, true)
            if (g == null || (clear(q, va) && clear(q, vb))) return Triple(t, q, false)
            // the walk's own step at the cut re-aimed at a vertex ([from]: the walk's point beside the cut)
            for ((v, r) in listOf(va to run, vb to runB).sortedBy { hypot(it.first[0] - q[0], it.first[1] - q[1]) }) {
                if (clear(from, v)) return Triple((r / w.len.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0), v, true)
            }
            return null
        }
        fun cost(w: W, k: Double, danger: Boolean): Double = when {
            danger -> w.base + k * w.dangerYd
            w.fixedYd != null -> w.base + k * w.fixedYd
            else -> w.base * (if (k > 0.0) 1.0 + w.share else 1.0) }

        fun nearestNode(p: DoubleArray, lim: Double): Int? = pos.entries.filter { (it.key >= 0 && it.key !in LINK_BASE until RM_BASE) || it.key in snapIds }
            .minByOrNull { hypot(it.value[0] - p[0], it.value[1] - p[1]) }?.takeIf { hypot(it.value[0] - p[0], it.value[1] - p[1]) <= lim }?.key

        /** an arc of the search: to [to] at cost [c], walking [via] (its end included); [way] the way it runs on (null: a link, a walk) */
        class Arc(val to: Int, val c: Double, val via: List<DoubleArray>, val way: W? = null)

        fun search(ground: (Double, Double) -> Float, danger: ((Double, Double) -> Float)?, from: DoubleArray, goal: DoubleArray, k: Double,
                   lim: Double): Result {
            val straight = listOf(from, goal)
            if (ways.isEmpty()) return Result(straight, false, "the shipped network has no ways")
            val banned = HashSet<W>()
            var lastWhy = ""
            repeat(REWALK_TRIES) {
                val r = searchOnce(ground, danger, from, goal, k, lim, banned)
                if (r.second == null) return r.first
                // a rewalk way the ground's walk does not join: dropped, searched again
                banned.add(r.second!!); lastWhy = r.first.why
            }
            return Result(straight, false, "rewalk ways no ground walk joins ($lastWhy)")
        }

        /** the search; the result, or (a failed result, the rewalk way to drop) */
        private fun searchOnce(ground: (Double, Double) -> Float, danger: ((Double, Double) -> Float)?, from: DoubleArray, goal: DoubleArray,
                               k: Double, lim: Double, banned: Set<W>): Pair<Result, W?> {
            val straight = listOf(from, goal)
            val withDanger = danger != null
            val adj = HashMap<Int, ArrayList<Arc>>()
            fun arc(a: Int, b: Int, c: Double, via: List<DoubleArray>, w: W? = null) { adj.getOrPut(a) { ArrayList() }.add(Arc(b, c, via, w)) }
            for (w in ways) {
                if (w in banned) continue
                val c = cost(w, k, withDanger)
                arc(w.a, w.b, c, w.via, w)
                arc(w.b, w.a, c, w.via.dropLast(1).reversed() + listOf(pos[w.a]!!), w)
            }
            // the links: a portal / an updraft from its first point only; a swim only for a goal on its island
            var swimOffered = 0
            // each link's ends are nodes of their own (one where two links' ends meet: a chain - Zephras' Rohashi Spires: portal,
            // stairs, deck, updraft), each joined to the nearest graph node within LINK_SNAP_YD by a short step when there is one
            val linkEnds = ArrayList<Pair<Int, DoubleArray>>()
            fun linkEnd(p: DoubleArray): Int {
                linkEnds.firstOrNull { hypot(it.second[0] - p[0], it.second[1] - p[1]) <= LINK_SAME_YD }?.let { return it.first }
                val id = LINK_BASE + linkEnds.size
                pos[id] = p; linkEnds.add(id to p)
                nearestNode(p, LINK_SNAP_YD)?.let { n ->
                    val q = pos[n]!!; val d = hypot(q[0] - p[0], q[1] - p[1])
                    arc(n, id, d, listOf(p)); arc(id, n, d, listOf(q)) }
                return id
            }
            for (l in sec.links) {
                if (l.pts.size < 2 || !linkOk(l)) continue
                val swim = l.kind == "swim" || l.pts.any { Climb.atSwim(it) }
                if (swim && !ghost) { val isl = l.island ?: continue; if (hypot(goal[0] - isl[0], goal[1] - isl[1]) > isl[2]) continue }
                if (swim) swimOffered++                            // a ghost: whatever the goal (it walks on the water)
                val pts = l.pts.map { p -> if (swim) doubleArrayOf(p[0], p[1], Climb.SWIM) else p }
                val a = linkEnd(pts.first()); val b = linkEnd(pts.last())
                if (a == b) continue
                val c = if (l.yd > 0.0) l.yd else length(l.pts)
                arc(a, b, c, pts.drop(1))
                if (!l.oneWay) arc(b, a, c, pts.reversed().drop(1))
            }
            val start = -1; val end = -2
            var nextVirtual = -10
            val attachYd = HashMap<Int, Double>()
            var snaps = 0
            val floorEnd = HashSet<Int>()                  // (node + OUT / IN): an end's snap onto a floor's node
            // THE ATTACH (the seam: [attacher]): each end's joins - onto graph nodes, or cut into ways - added as arcs
            val cuts = ArrayList<Triple<Int, W, Double>>()
            // the nodes an end may snap to: only those some way or link leaves (an isolated node leads nowhere); a cave's or a
            // city floor's inside nodes apart (U2's snap: only for an end standing on that floor)
            val linked = HashSet<Int>().also { s -> for (w in ways) if (w !in banned) { s.add(w.a); s.add(w.b) }; for (a in adj.keys) s.add(a) }
            val caveIds = net.nodes.filter { it.kind == SafePath.CAVE && it.id in linked }.map { it.id }
            // the links' ends: on the ground an end may snap to them; on a floor (a deck, the stairs' top) only from that floor
            val groundEnds = linkEnds.filter { !Climb.inCave(it.second) }; val floorEnds = linkEnds.filter { Climb.inCave(it.second) }
            val view = AttachView(snapIds.filter { it in linked }.map { it to pos[it]!! } + groundEnds.map { it.first to doubleArrayOf(it.second[0], it.second[1]) },
                ways.withIndex().filter { (_, w) -> w.onLand && w !in banned && !w.rewalk }.map { (i, w) -> WayView(i, listOf(pos[w.a]!!) + w.via, w.len) },
                ground, danger, k, lim, (roadmap?.floor ?: emptySet()) + caveIds + floorEnds.map { it.first },
                (caveIds + floorEnds.map { it.first }).map { it to doubleArrayOf(pos[it]!![0], pos[it]!![1]) })
            fun join(p: DoubleArray, virt: Int, outward: Boolean, who: String): Result? {
                val at = attacher.attach(view, p)
                if (at.joins.isEmpty()) return Result(straight, false, "$who is ${at.why ?: "joined to nothing"}")
                if (at.snapped) snaps++
                for (j in at.joins) {
                    val wc = j.cost * SafePath.OFF_NET
                    // an end snapped onto a floor's node (a city's street, a deck) stands on that floor: its walk there marked too
                    val onFloor = j.node != null && pos[j.node]?.let { Climb.inCave(it) && !Climb.atSwim(it) } == true
                    val jp = if (onFloor) j.pts.map { doubleArrayOf(it[0], it[1], Climb.IN_CAVE) } else j.pts
                    if (onFloor) floorEnd.add(j.node!! + (if (outward) OUT else IN))
                    val walk = if (outward) jp.drop(1) else jp.reversed().drop(1)
                    var walkPts = walk
                    val id = j.node ?: run {
                        val w = ways[j.way]
                        // the walk's point next to the cut, on the end's side (the end itself when the walk is one step)
                        val beside = if (outward) walk.getOrNull(walk.size - 2) ?: p else walk.firstOrNull() ?: p
                        val (t, q, moved) = cutAt(w, j.t, j.at, beside) ?: return@run null
                        if (moved && outward) walkPts = walk.dropLast(1) + listOf(q)          // (walked in: the arc starts at the cut itself)
                        val id = nextVirtual--
                        pos[id] = q
                        val c = cost(w, k, withDanger)
                        // the cut walks the way's own points to either end (a walked roadmap edge, a way's bends: never straight across)
                        val (before, after) = splitAt(listOf(pos[w.a]!!) + w.via, t * w.len)
                        arc(id, w.a, t * c, before.reversed()); arc(w.a, id, t * c, before.drop(1) + listOf(q))
                        arc(id, w.b, (1 - t) * c, after.ifEmpty { listOf(pos[w.b]!!) }); arc(w.b, id, (1 - t) * c, after.reversed().drop(1) + listOf(q))
                        for ((oid, ow, ot) in cuts) if (ow === w) { val oq = pos[oid]!!; arc(id, oid, abs(t - ot) * c, listOf(oq)); arc(oid, id, abs(t - ot) * c, listOf(q)) }
                        cuts.add(Triple(id, w, t))
                        id
                    } ?: continue
                    if (outward) { arc(virt, id, wc, walkPts); attachYd[id + OUT] = j.yd } else { arc(id, virt, wc, walkPts); attachYd[id + IN] = j.yd }
                }
                return null
            }
            join(from, start, true, "the player")?.let { return it to null }
            join(goal, end, false, "the goal")?.let { return it to null }
            // the ground's own walk straight there, the graph's rival for a goal this near
            if (hypot(goal[0] - from[0], goal[1] - from[1]) <= if (ghost) GHOST_DIRECT_MAX_YD else DIRECT_MAX_YD)
                attacher.direct(view, from, goal)?.let { arc(start, end, it.cost * SafePath.DIRECT, it.pts.drop(1)) }
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
            if (end !in dist) return Result(straight, false, "the graph does not join the player's ways to the goal's") to null
            val arcs = ArrayList<Pair<Int, Arc>>(); var c = end
            while (c != start) { val p = prev[c]!!; arcs.add(p); c = p.first }
            arcs.reverse()
            val pts = arrayListOf(from)
            var rmYd = 0.0
            fun add(p: DoubleArray) { if (hypot(pts.last()[0] - p[0], pts.last()[1] - p[1]) >= SAME_PT_YD || p.size != pts.last().size) pts.add(p) }
            for ((u, a) in arcs) {
                val w = a.way
                if (w != null && w.roadmap) rmYd += length(listOf(pts.last()) + a.via)
                if (w != null && w.rewalk && a.via.size == 1) {
                    // a rewalk way never straight: the ground's own walk between its ends (none: the way dropped, searched again)
                    val s = pos[u]!!; val e = a.via.last()
                    val walk = Leg(ground, danger, k, s, w.len * 1.6 + 20.0).to(e)
                        ?: return Result(straight, false, "a rewalk way (${s[0].toInt()},${s[1].toInt()}) no ground walk joins") to w
                    for (p in walk.pts.drop(1)) add(p)
                } else for (p in a.via) add(p)
            }
            val first = arcs.first(); val last = arcs.last()
            // the ends themselves on the floor their snap reached (the harness's own ends on a floor are marked)
            if (first.second.to + OUT in floorEnd) pts[0] = doubleArrayOf(from[0], from[1], Climb.IN_CAVE)
            val goalFloor = last.first + IN in floorEnd
            if (hypot(pts.last()[0] - goal[0], pts.last()[1] - goal[1]) > 0.5 || (goalFloor && !Climb.inCave(pts.last()))) {
                if (hypot(pts.last()[0] - goal[0], pts.last()[1] - goal[1]) <= 0.5) pts.removeAt(pts.size - 1)
                pts.add(if (goalFloor) doubleArrayOf(goal[0], goal[1], Climb.IN_CAVE) else goal)
            }
            val out = tidy(pts)
            val onYd = attachYd[first.second.to + OUT] ?: 0.0
            val offYd = attachYd[last.first + IN] ?: 0.0
            val direct = arcs.size == 1
            val swims = out.count { Climb.atSwim(it) }
            return Result(out, true, (if (direct) "the ground's own walk (near)" else "on the shipped network") +
                ": ${length(out).toInt()} yd, ${max(0, out.size - 2)} bends, attach ${onYd.toInt()} / ${offYd.toInt()} yd" +
                (if (snaps == 2) " (snapped)" else " (long attach)") + (if (rmYd > 0.0) ", ${rmYd.toInt()} yd on the roadmap" else "") + (if (roadmap != null) ", $stitchWords" else "") +
                (if (swimOffered > 0) ", swim offered ($swims swum points)" else ""),
                attachFromYd = onYd, attachToYd = offYd, swims = swims, snapped = snaps == 2, roadmapYd = rmYd) to null
        }
    }

    // ---------------------------------------------------------------- THE ATTACH SEAM (helper U2 owns the attach; U1 the graph)

    /** a way an end may be cut into: [index] the graph's way, [line] its points from its node a to its node b, [len] its yards */
    class WayView(val index: Int, val line: List<DoubleArray>, val len: Double)
    /** what an [Attacher] sees: the nodes an end may snap to (id, world point; no cave's or lift's inside point), the ways it may be
     *  cut into (no cave, lift or unwalked rewalk way), the ground and danger the router has ([k] the danger's weight), and the long
     *  attach's limit [lim] (yd walked); [floor] the nodes on a floor layer (the roadmap's R_FLOOR, the caves' and city floors' inside
     *  nodes), [floorNodes] those inside nodes (id, world point; not in [nodes]: snapped to only from an end on their floor) */
    class AttachView(val nodes: List<Pair<Int, DoubleArray>>, val ways: List<WayView>, val ground: (Double, Double) -> Float,
                     val danger: ((Double, Double) -> Float)?, val k: Double, val lim: Double, val floor: Set<Int> = emptySet(),
                     val floorNodes: List<Pair<Int, DoubleArray>> = emptyList())
    /** one join of an end: onto graph node [node], or (node null) cut into way [way] ([WayView.index]) at [t] (0..1 of its yards) at
     *  [at]; [pts] the walk from the end to it (the end first, the joined point last), [cost] its cost (the ground's and the danger's;
     *  the router adds SafePath.OFF_NET), [yd] its walked yards */
    class Join(val node: Int?, val way: Int, val t: Double, val at: DoubleArray, val pts: List<DoubleArray>, val cost: Double, val yd: Double)
    /** an end's joins (none: no route, [why] said); [snapped]: a short snap (no long attach) */
    class Attach(val joins: List<Join>, val snapped: Boolean, val why: String? = null)
    /** THE SEAM: how an end ([p], world) joins the graph; called for the player (walked out from p) and the goal (walked in to p) */
    fun interface Attacher {
        fun attach(v: AttachView, p: DoubleArray): Attach
        /** THE NEAR-GOAL WALK, the graph's rival for a goal within [DIRECT_MAX_YD]: the ground's own walk from [from] to [to] (its
         *  points from first, to last; the router adds SafePath.DIRECT), null = none (the graph's way only). U1's default: [Leg] */
        fun direct(v: AttachView, from: DoubleArray, to: DoubleArray): Join? =
            Leg(v.ground, v.danger, v.k, from, DIRECT_WALK_YD).to(to)?.let { Join(null, -1, 0.0, to, it.pts, it.cost, it.yd) }
    }

    /** the attach as built by U1: the nearest nodes within [SNAP_YD] by the ground's walk ([Leg]); with none, a walk onto each of the
     *  nearest ways, [AttachView.lim] walked at most */
    val DefaultAttach = Attacher { v, p ->
        val snapLeg = Leg(v.ground, v.danger, v.k, p, SNAP_WALK_YD)
        val snaps = v.nodes.filter { hypot(it.second[0] - p[0], it.second[1] - p[1]) <= SNAP_YD }
            .sortedBy { hypot(it.second[0] - p[0], it.second[1] - p[1]) }.take(SNAP_CANDIDATES)
            .mapNotNull { (id, q) -> snapLeg.to(q)?.let { w -> Join(id, -1, 0.0, q, w.pts, w.cost, w.yd) } }
        if (snaps.isNotEmpty()) return@Attacher Attach(snaps, true)
        val cands = v.ways.map { w ->
            var best: Pair<Double, DoubleArray>? = null; var at = 0.0; var bestAt = 0.0
            for ((s, e) in w.line.zipWithNext()) {
                val dx = e[0] - s[0]; val dy = e[1] - s[1]; val l2 = dx * dx + dy * dy
                val t = if (l2 <= 0.0) 0.0 else (((p[0] - s[0]) * dx + (p[1] - s[1]) * dy) / l2).coerceIn(0.0, 1.0)
                val q = doubleArrayOf(s[0] + t * dx, s[1] + t * dy); val d = hypot(q[0] - p[0], q[1] - p[1])
                if (best == null || d < best.first) { best = d to q; bestAt = at + t * hypot(dx, dy) }
                at += hypot(dx, dy)
            }
            Triple(w, if (w.len <= 0.0) 0.0 else bestAt / w.len, best!!)
        }.sortedBy { it.third.first }
        val nearest = cands.firstOrNull()?.third?.first ?: Double.MAX_VALUE
        if (nearest > v.lim) return@Attacher Attach(emptyList(), false,
            "${nearest.toInt()} yd from the nearest way (no node within ${SNAP_YD.toInt()} yd; attach at most ${v.lim.toInt()} yd)")
        val leg = Leg(v.ground, v.danger, v.k, p, v.lim)
        val joins = cands.filter { it.third.first <= v.lim }.take(ATTACH_CANDIDATES).mapNotNull { (w, t, b) ->
            leg.to(b.second)?.let { walk -> Join(null, w.index, t, b.second, walk.pts, walk.cost, walk.yd) } }
        Attach(joins, false, if (joins.isEmpty()) "no walk of ${v.lim.toInt()} yd or less reaches a way (walls, deep water)" else null)
    }

    /** [line] split [at] yards along it: the points before (its first point included, the cut not), the points after (its last
     *  included, the cut not) */
    internal fun splitAt(line: List<DoubleArray>, at: Double): Pair<List<DoubleArray>, List<DoubleArray>> {
        var run = 0.0
        for (i in 1 until line.size) {
            val seg = hypot(line[i][0] - line[i - 1][0], line[i][1] - line[i - 1][1])
            if (run + seg >= at) return line.subList(0, i) to line.subList(i, line.size).let { if (run + seg - at < 0.25) it.drop(1).ifEmpty { it } else it }
            run += seg
        }
        return line.dropLast(1) to listOf(line.last())
    }

    /** an edge kind of NET1 v2: a way joining the net's pieces, or a piece to a cave's mouth (costed as a spur) */
    const val JOIN = "join"
    // the attach walks' keys by node: the walk out to it, the walk in from it (a node may be both ends' snap)
    private const val OUT = 1 shl 28
    private const val IN = 1 shl 29

    /** a way's points with no straight runs' middles (within half a yard of the line), the marks kept */
    private fun tidy(pts: List<DoubleArray>): List<DoubleArray> {
        // SHIPPED POINTS ARE FOLLOWED EXACTLY (W's Kalimdor 70450 parity: a collinear merge within half a yard dropped walked points -
        // Desolace's roadmap edge 1669-1720, Tanaris' 2374-2445, Thousand Needles' join 804 - and the shortcut crossed a wall): only a
        // repeat of the same point with the same mark goes
        if (pts.size <= 2) return pts
        val out = arrayListOf(pts[0])
        for (q in pts.drop(1)) { val l = out.last()
            if (hypot(q[0] - l[0], q[1] - l[1]) < SAME_PT_YD && q.size == l.size && (q.size < 3 || q[2] == l[2])) continue
            out.add(q) }
        if (out.size == 1) out.add(pts.last())
        return out
    }
    const val SAME_PT_YD = 0.01

    /**
     * THE ATTACH LEG's GROUND round [c] ([lim] yd each way, [ATTACH_STEP_YD] cells): Dijkstra from [c] over the ground's cost and the
     * danger. A cell of [Terrain.WALL] (a cliff, a building, deep water) or [ATTACH_WALL] (a steep bank) is refused - only when [c]
     * itself stands in one (a building's inside) are the walls within [DOOR_YD] of it passed (its door). [to] the walk to a point
     * (null: none within [lim] walked yards), pulled straight where the straight line crosses no wall and costs no more.
     */
    class Leg(private val ground: (Double, Double) -> Float, private val danger: ((Double, Double) -> Float)?, private val k: Double,
              private val c: DoubleArray, private val lim: Double) {
        class Walk(val pts: List<DoubleArray>, val cost: Double, val yd: Double)
        private val r = ceil(lim / ATTACH_STEP_YD).toInt()
        private val n = 2 * r + 1
        private val dist = DoubleArray(n * n) { Double.MAX_VALUE }
        private val walked = DoubleArray(n * n) { Double.MAX_VALUE }
        private val prev = IntArray(n * n) { -1 }
        private val inWall = wall(ground(c[0], c[1]))
        private fun wx(i: Int) = c[0] + (i - r) * ATTACH_STEP_YD
        private fun wy(j: Int) = c[1] + (j - r) * ATTACH_STEP_YD
        private fun wall(g: Float) = !g.isNaN() && g >= ATTACH_WALL
        private fun cellCost(x: Double, y: Double): Double {
            val gr = ground(x, y).let { if (it.isNaN()) 1f else it }
            if (wall(gr) && !(inWall && hypot(x - c[0], y - c[1]) <= DOOR_YD)) return Double.NaN
            val d = danger?.invoke(x, y)?.takeIf { !it.isNaN() } ?: 0f
            return minOf(gr, ATTACH_WALL).toDouble() + k * d
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
                    if (memo[v] < 0.0) memo[v] = cellCost(wx(vi), wy(vj)).let { if (it.isNaN()) Double.MAX_VALUE else it }
                    if (memo[v] == Double.MAX_VALUE) continue
                    // a diagonal step never cuts a wall's corner
                    if (di != 0 && dj != 0) {
                        val a = uj * n + vi; val b = vj * n + ui
                        if (memo[a] < 0.0) memo[a] = cellCost(wx(vi), wy(uj)).let { if (it.isNaN()) Double.MAX_VALUE else it }
                        if (memo[b] < 0.0) memo[b] = cellCost(wx(ui), wy(vj)).let { if (it.isNaN()) Double.MAX_VALUE else it }
                        if (memo[a] == Double.MAX_VALUE || memo[b] == Double.MAX_VALUE) continue
                    }
                    val nd = d + step * memo[v]
                    if (nd < dist[v]) { dist[v] = nd; walked[v] = w; prev[v] = u; q.add(nd to v) }
                }
            }
        }
        private fun cellOf(p: DoubleArray): Int {
            val i = Math.round((p[0] - c[0]) / ATTACH_STEP_YD).toInt() + r; val j = Math.round((p[1] - c[1]) / ATTACH_STEP_YD).toInt() + r
            return if (i in 0 until n && j in 0 until n) j * n + i else -1
        }
        /** the walk from [c] to [p], [c] first and [p] last; null = none within [lim] (or [p] beyond it, or in a wall) */
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
        /** a straight line from [a] to [b] crossing no wall, and its cost (NaN: it crosses one) */
        private fun lineCost(a: DoubleArray, b: DoubleArray): Double {
            val len = hypot(b[0] - a[0], b[1] - a[1]); val m = max(1, ceil(len / PULL_SAMPLE_YD).toInt()); var s = 0.0
            for (q in 0..m) {
                val t = q.toDouble() / m; val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
                val cc = cellCost(x, y)
                if (cc.isNaN()) { if (q == m || q == 0) continue; return Double.NaN }   // an end on a way's wall foot: its own
                if (q < m) s += cc * len / m
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
}
