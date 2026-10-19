import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.DangerGrid
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedNet
import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * THE SHIPPED NETWORK'S JOIN WAYS (the user, 2026-10-19: "Ship our path network"; DEVICE_INTEGRATION_PLAN.md "SHIPPED NETWORK"): the
 * device routes on the shipped net WITHOUT the lattice, so the net itself must be one piece wherever the ground is. With its walled ways
 * dropped a zone's net falls into pieces (the ground's net in several, every cave a piece of its own when its mouth's spur crossed a
 * wall); this finds the pieces and joins them by LATTICE-WALKED legs:
 *
 * 1. the pieces: the net's nodes joined by its ways (every state the section keeps), and by its in-zone links (a portal's, an updraft's,
 *    a declared swim's: [ShippedNet.Link], each end on the piece of the nearest node within [LINK_SNAP_YD]);
 * 2. one multi-source walk over the whole lattice from every ground node (not a cave's or a lift's floor node: a mouth is ground) and
 *    from points every [SAMPLE_YD] along every ground way, the harness's own step rule and costs (Climb.route's: wall steps never, each
 *    step's straight line [Climb.walkable] off deep water, the class's cost, k x danger, EDGE CLEARANCE); where two pieces' walks meet,
 *    the cheapest join between them (Mehlhorn: the tree over those meetings is a minimum tree over the pieces' walked distances);
 * 3. Kruskal over those joins by walked cost: each tried in turn as [Climb.route] between its two ends (the harness's own leg, every
 *    segment re-checked by [Climb.walkable]), the cheapest that walks taken; then the extra joins that cut a detour on the net by
 *    [SHORTCUT_FACTOR] and [SHORTCUT_MIN_YD] or more;
 * 4. what still stands apart, and why ([Apart]): an island (deep water all round: a declared swim's), a walled pocket, a cave with no
 *    mouth on the ground, a walk that never checked.
 *
 * A join's end is a node, or a point on a way (the way is split there: [apply]). Entry point: [joins]; [apply] gives the net with them.
 */
object NetJoins {
    const val SAMPLE_YD = 20.0             // a seed every this far along a ground way (a way passing close to another piece is joined there)
    const val LINK_SNAP_YD = 30.0          // a link's end joins the piece of the nearest node this near
    const val LINK_CHAIN_YD = 3.0          // two links' ends this near are one (an air chain: portal, stairs, deck, updraft, portal out)
    const val SHORTCUT_FACTOR = 3.0        // an extra join where the net's own way between its ends is this many times longer ...
    const val SHORTCUT_MIN_YD = 600.0      // ... and longer by this much
    const val ALT_APART_YD = 150.0         // a pair's fallback join: its ends this far from the best one's
    const val ALT_END_YD = 80.0          // a failed end's fallbacks: the same piece's points this near ...
    const val ALT_ENDS = 3               // ... this many of them
    const val ALT_TRIES = 6              // pairs of ends tried a meeting
    const val FLAGGED_YD = 2.5            // a segment segcheck flags: its ground this near kept off on the next pass
    const val END_YD = 0.75                // a join's walk must start and end this near its two ends (else it is no join of THEM)
    val GROUND_EDGE = setOf(SafePath.ROAD, SafePath.TRAIL, SafePath.SPUR)

    /** a join's end: node [node] (>= 0), or the point [x], [y] on the net's edge [edge] (its index in net.edges; node -1: split it) */
    class End(val node: Int, val edge: Int, val x: Double, val y: Double) {
        fun pt() = doubleArrayOf(x, y)
        fun json(): JSONObject = JSONObject().put("node", node).put("edge", edge).put("x", r1(x)).put("y", r1(y))
    }
    /**
     * a join way: from [a] to [b] (pieces [pieceA], [pieceB] of [Result.pieces]), walked as [pts] (world x, y; every segment
     * Climb.walkable with the deep water), [yd] long, [cost] its walked cost (the lattice's: class, danger, edges), [flags] (F_JOIN, F_TRAIL
     * and what it crosses: F_WADE, F_BRIDGE), [danger] this faction's share along it (0..1: danger / DangerGrid.CAP, the mean a yard),
     * [tier] 0 safe, 1 low, 2 middling, 3 high ([tierOf]), [why] "tree" (it joins two pieces) or "shortcut" (it saves [saved] yd)
     */
    class Join(val a: End, val b: End, val pieceA: Int, val pieceB: Int, val pts: List<DoubleArray>, val yd: Double, val cost: Double,
               val flags: Int, val danger: Float, val tier: Int, val why: String, val saved: Double = 0.0) {
        fun json(): JSONObject = JSONObject().put("a", a.json()).put("b", b.json()).put("pieces", JSONArray(listOf(pieceA, pieceB)))
            .put("yd", r1(yd)).put("cost", r1(cost)).put("flags", flags).put("flagNames", JSONArray(flagNames(flags)))
            .put("danger", Math.round(danger * 100) / 100.0).put("tier", tier).put("why", why).put("saved", r1(saved))
            .put("pts", JSONArray(pts.map { JSONArray(listOf(r1(it[0]), r1(it[1]))) }))
    }
    /** a piece of the net: its [nodes] (ids), its ways' length [yd], its ground seeds' count [seeds] (0: only a cave's floor) */
    class Piece(val id: Int, val nodes: List<Int>, val ways: Int, val groundWays: Int, val yd: Double, val seeds: Int, val kinds: Map<String, Int>, val x: Double, val y: Double,
                val named: List<String>)
    /** a piece (or a group of pieces already joined) still apart from the main one after the joins: [why] */
    class Apart(val pieces: List<Int>, val nodes: Int, val yd: Double, val x: Double, val y: Double, val why: String, val ground: Boolean = true)
    class Result(val pieces: List<Piece>, val linked: List<String>, val joins: List<Join>, val apart: List<Apart>, val rejected: List<String>,
                 val candidates: Int, val ms: Long) {
        val tree get() = joins.filter { it.why == "tree" }
        val after get() = apart.size + 1
        /** pieces with a ground way (road, trail, spur: not only a cave's floor) before, and groups holding one after */
        val groundBefore get() = pieces.count { it.groundWays > 0 }
        val groundAfter get() = 1 + apart.count { it.ground }
        fun summary(tag: String) = "JOINS $tag: pieces ${pieces.size} -> $after (with ground ways $groundBefore -> $groundAfter); joins ${joins.size} (tree ${tree.size}, shortcut ${joins.size - tree.size}), " +
            "${joins.sumOf { it.yd }.toInt()} yd; candidates $candidates, rejected ${rejected.size}; $ms ms"
        fun json(): JSONObject = JSONObject().put("pieces", pieces.size).put("after", after).put("groundBefore", groundBefore).put("groundAfter", groundAfter).put("ms", ms).put("candidates", candidates)
            .put("joins", JSONArray(joins.map { it.json() }))
            .put("apart", JSONArray(apart.map { JSONObject().put("pieces", JSONArray(it.pieces)).put("nodes", it.nodes).put("yd", r1(it.yd))
                .put("x", r1(it.x)).put("y", r1(it.y)).put("why", it.why).put("ground", it.ground) }))
            .put("linked", JSONArray(linked)).put("rejected", JSONArray(rejected))
            .put("pieceList", JSONArray(pieces.map { p -> JSONObject().put("id", p.id).put("nodes", p.nodes.size).put("ways", p.ways).put("yd", r1(p.yd))
                .put("seeds", p.seeds).put("x", r1(p.x)).put("y", r1(p.y)).put("kinds", JSONObject(p.kinds as Map<*, *>)).put("named", JSONArray(p.named)) }))
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
    fun flagNames(f: Int) = ShippedNet.flagNames(f)
    /** a join's danger tier by its share (0..1) */
    fun tierOf(share: Float): Int = when { share < 0.05f -> 0; share < 0.25f -> 1; share < 0.5f -> 2; else -> 3 }

    private class Dsu(n: Int) { val p = IntArray(n) { it }
        fun find(x: Int): Int { var a = x; while (p[a] != a) { p[a] = p[p[a]]; a = p[a] }; return a }
        fun union(a: Int, b: Int): Boolean { val x = find(a); val y = find(b); if (x == y) return false; p[x] = y; return true } }

    /** each node's piece (index into [SafePath.Net.nodes]) by the net's ways alone */
    fun pieceOf(net: SafePath.Net): IntArray {
        val idx = HashMap<Int, Int>(); net.nodes.forEachIndexed { i, n -> idx[n.id] = i }
        val d = Dsu(net.nodes.size)
        for (e in net.edges) { if (e.state == SafePath.REMOVED) continue; val a = idx[e.a] ?: continue; val b = idx[e.b] ?: continue; d.union(a, b) }
        val roots = HashMap<Int, Int>()
        return IntArray(net.nodes.size) { i -> roots.getOrPut(d.find(i)) { roots.size } }
    }

    private class Seed(val piece: Int, val end: End)
    private class Cand(val cost: Double, val sa: Int, val sb: Int, val pa: Int, val pb: Int)

    /**
     * the joins of [net] (one faction's section net: REMOVED ways already gone) on the zone's lattice [g], walked by the harness's rules:
     * [deep] deep water (a wall: ZoneInputs.deep), [danger] the character's danger (the grid's 0..DangerGrid.CAP: DangerInputs.grid(..).danger)
     * and its weight [k], [edges] EDGE CLEARANCE on; [links] the section's in-zone links (portal, updraft, swim...: they join pieces);
     * [wetOf] the exporter's wet flags of a straight piece (F_WADE / F_BRIDGE / F_SWIM; RenderSafePath's lambda for ShippedNet.section);
     * [log] the lines. The result's [Result.joins] go into the net by [apply].
     */
    fun joins(net: SafePath.Net, g: Climb.Grid, deep: (Double, Double) -> Boolean, danger: (Double, Double) -> Float, k: Double,
              links: List<ShippedNet.Link> = emptyList(), edges: Boolean = true, wetOf: (DoubleArray, DoubleArray) -> Int = { _, _ -> 0 },
              refuse: (DoubleArray, DoubleArray) -> Boolean = { _, _ -> false }, log: (String) -> Unit = {}): Result {
        val t0 = System.currentTimeMillis()
        val nodes = net.nodes; val idx = HashMap<Int, Int>(); nodes.forEachIndexed { i, n -> idx[n.id] = i }
        val pieceOfNode = pieceOf(net)
        val nP = (pieceOfNode.maxOrNull() ?: -1) + 1
        // the seeds: ground nodes, and points along ground ways
        val seeds = ArrayList<Seed>()
        fun ground(n: SafePath.Node) = n.kind != SafePath.CAVE && n.kind != SafePath.LIFT
        for ((i, n) in nodes.withIndex()) if (ground(n)) seeds.add(Seed(pieceOfNode[i], End(n.id, -1, n.wx, n.wy)))
        for ((ei, e) in net.edges.withIndex()) {
            if (e.state == SafePath.REMOVED || e.state == SafePath.REWALK || e.kind !in GROUND_EDGE) continue
            val a = idx[e.a]?.let { nodes[it] } ?: continue; val b = idx[e.b]?.let { nodes[it] } ?: continue
            if (!ground(a) || !ground(b)) continue
            val len = hypot(b.wx - a.wx, b.wy - a.wy); val n = (len / SAMPLE_YD).toInt()
            for (q in 1 until n) { val t = q.toDouble() / n
                seeds.add(Seed(pieceOfNode[idx[e.a]!!], End(-1, ei, a.wx + (b.wx - a.wx) * t, a.wy + (b.wy - a.wy) * t))) }
        }
        // the pieces' own facts
        val pieceNodes = (0 until nP).map { ArrayList<Int>() }; nodes.forEachIndexed { i, _ -> pieceNodes[pieceOfNode[i]].add(i) }
        val pieceWays = IntArray(nP); val pieceGround = IntArray(nP); val pieceYd = DoubleArray(nP)
        for (e in net.edges) { val a = idx[e.a] ?: continue; val b = idx[e.b] ?: continue; val p = pieceOfNode[a]
            pieceWays[p]++; if (e.kind in GROUND_EDGE) pieceGround[p]++; pieceYd[p] += hypot(nodes[a].wx - nodes[b].wx, nodes[a].wy - nodes[b].wy) }
        val pieceSeeds = IntArray(nP); for (s in seeds) pieceSeeds[s.piece]++
        val pieces = (0 until nP).map { p -> val ns = pieceNodes[p]
            Piece(p, ns.map { nodes[it].id }, pieceWays[p], pieceGround[p], pieceYd[p], pieceSeeds[p], ns.groupingBy { nodes[it].kind }.eachCount(),
                ns.map { nodes[it].wx }.average(), ns.map { nodes[it].wy }.average(), ns.mapNotNull { nodes[it].name }) }
        // the links join pieces (a portal, an updraft, a declared swim; an air chain's links one after another): each link's two ends
        // are one, an end on another link's end (within [LINK_CHAIN_YD]) is that end, an end within [LINK_SNAP_YD] of a node is its piece
        val dsu = Dsu(nP + 2 * links.size)
        val linked = ArrayList<String>()
        val okLinks = links.withIndex().filter { it.value.pts.size >= 2 }
        fun endPt(li: Int, last: Boolean) = links[li].pts.let { if (last) it.last() else it.first() }
        for ((li, _) in okLinks) dsu.union(nP + 2 * li, nP + 2 * li + 1)
        for ((li, _) in okLinks) for ((lj, _) in okLinks) { if (lj <= li) continue
            for (ea in listOf(false, true)) for (eb in listOf(false, true)) { val p = endPt(li, ea); val q = endPt(lj, eb)
                if (hypot(p[0] - q[0], p[1] - q[1]) <= LINK_CHAIN_YD) dsu.union(nP + 2 * li + (if (ea) 1 else 0), nP + 2 * lj + (if (eb) 1 else 0)) } }
        for ((li, l) in okLinks) {
            val onto = ArrayList<String>()
            for (last in listOf(false, true)) { val p = endPt(li, last)
                val nn = nodes.indices.minByOrNull { hypot(nodes[it].wx - p[0], nodes[it].wy - p[1]) }
                    ?.takeIf { hypot(nodes[it].wx - p[0], nodes[it].wy - p[1]) <= LINK_SNAP_YD }
                if (nn != null) { dsu.union(nP + 2 * li + (if (last) 1 else 0), pieceOfNode[nn]); onto.add("${if (last) "last" else "first"} on piece ${pieceOfNode[nn]}") } }
            linked.add("${l.kind} ${l.name}".trim() + (if (l.oneWay) " (one way)" else "") + ": " + onto.ifEmpty { listOf("no end on a node (a chain's middle)") }.joinToString())
        }
        // THE WALK: multi-source Dijkstra over the lattice, Climb.route's step rule and costs (no goal fade: no goal)
        val nx = g.nx; val ny = g.ny; val N = nx * ny
        val dist = DoubleArray(N) { Double.MAX_VALUE }; val src = IntArray(N) { -1 }
        val wet = ByteArray(N)                // 0 unknown, 1 dry, 2 deep
        fun deepAt(i: Int, j: Int): Boolean { if (i !in 0 until nx || j !in 0 until ny) return false; val q = j * nx + i
            if (wet[q].toInt() == 0) wet[q] = if (deep(g.wx(j), g.wy(i))) 2 else 1; return wet[q].toInt() == 2 }
        val er = ceil(Climb.EDGE_YD / Climb.STEP).toInt(); val dz = g.deckZ
        val extra = DoubleArray(N) { Double.NaN }
        fun extraOf(i: Int, j: Int): Double { val q = j * nx + i
            if (extra[q].isNaN()) extra[q] = if (deepAt(i, j)) -1.0 else {
                var e = 0.0
                if (edges && (dz == null || dz[q].isNaN())) { var d = g.dropYd(i, j)
                    for (b in j - er..j + er) for (a in i - er..i + er) if ((a != i || b != j) && deepAt(a, b))
                        d = min(d, max(0.0, hypot((a - i).toDouble(), (b - j).toDouble()) * Climb.STEP - Climb.STEP / 2))
                    e = Climb.edgeCost(d) }
                k * danger(g.wx(j), g.wy(i)) + e }
            return extra[q] }
        /** a step's cost from (i, j) by (di, dj), or -1 when it is no step */
        fun stepCost(i: Int, j: Int, di: Int, dj: Int): Double {
            val vi = i + di; val vj = j + dj
            if (vi !in 0 until nx || vj !in 0 until ny) return -1.0
            val c = g.step(i, j, di, dj); if (c == Climb.WALL) return -1.0
            val x = extraOf(vi, vj); if (x < 0) return -1.0
            if (!Climb.walkable(g, doubleArrayOf(g.wx(j), g.wy(i)), doubleArrayOf(g.wx(vj), g.wy(vi)), deep, ends = false)) return -1.0
            return (if (di != 0 && dj != 0) Climb.STEP * 1.41421356 else Climb.STEP) * (Climb.COST[c] + x)
        }
        // per pair of pieces its cheapest meetings (the best, and fallbacks with other ends [ALT_APART_YD] off)
        val cands = HashMap<Long, ArrayList<Cand>>()
        fun addCand(cost: Double, sa: Int, sb: Int) {
            val pa = seeds[sa].piece; val pb = seeds[sb].piece; if (pa == pb) return
            val c = Cand(cost, sa, sb, pa, pb)
            val l = cands.getOrPut(min(pa, pb).toLong() * 1_000_000 + max(pa, pb)) { ArrayList() }
            val ca = seeds[sa].end; val cb = seeds[sb].end
            val same = l.indexOfFirst { o -> val oa = seeds[o.sa].end; val ob = seeds[o.sb].end
                (hypot(oa.x - ca.x, oa.y - ca.y) < ALT_APART_YD && hypot(ob.x - cb.x, ob.y - cb.y) < ALT_APART_YD) ||
                (hypot(oa.x - cb.x, oa.y - cb.y) < ALT_APART_YD && hypot(ob.x - ca.x, ob.y - ca.y) < ALT_APART_YD) }
            if (same >= 0) { if (l[same].cost > c.cost) l[same] = c } else l.add(c)
            if (l.size > 3) { l.sortBy { it.cost }; l.removeAt(l.size - 1) }
        }
        val open = Heap()
        var offLattice = 0
        val seedsOnLattice = IntArray(nP); val seedsDeep = IntArray(nP)
        for ((si, s) in seeds.withIndex()) { val v = g.at(s.end.x, s.end.y); val q = v[1] * nx + v[0]
            val d0 = hypot(g.wx(v[1]) - s.end.x, g.wy(v[0]) - s.end.y)
            if (deepAt(v[0], v[1]) || d0 > Climb.STEP) { offLattice++; if (d0 <= Climb.STEP) seedsDeep[s.piece]++; continue }
            seedsOnLattice[s.piece]++
            // two pieces' points on one vertex: a meeting already (a cave's mouth on a road's point)
            if (src[q] >= 0 && seeds[src[q]].piece != s.piece) addCand(hypot(seeds[src[q]].end.x - s.end.x, seeds[src[q]].end.y - s.end.y), src[q], si)
            if (d0 < dist[q]) { dist[q] = d0; src[q] = si; open.add(d0, q) } }
        // why a piece's walk stopped (for the pieces left apart): steps refused by deep water, by a wall
        val blockedDeep = IntArray(nP); val blockedWall = IntArray(nP)
        while (open.size > 0) {
            val du = open.topKey(); val u = open.pop(); if (du > dist[u]) continue
            val ui = u % nx; val uj = u / nx; val pu = seeds[src[u]].piece
            for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                val sc = stepCost(ui, uj, di, dj)
                if (sc < 0) { val vi = ui + di; val vj = uj + dj
                    if (vi in 0 until nx && vj in 0 until ny) { if (deepAt(vi, vj)) blockedDeep[pu]++ else blockedWall[pu]++ }; continue }
                val v = (uj + dj) * nx + ui + di; val nd = dist[u] + sc
                if (nd < dist[v]) { dist[v] = nd; src[v] = src[u]; open.add(nd, v) } }
        }
        // where two pieces' walks meet
        for (u in 0 until N) { if (src[u] < 0) continue; val ui = u % nx; val uj = u / nx
            for (dd in 0 until 4) { val di = FWD_I[dd]; val dj = FWD_J[dd]
                val vi = ui + di; val vj = uj + dj; if (vi !in 0 until nx || vj !in 0 until ny) continue
                val v = vj * nx + vi; if (src[v] < 0 || seeds[src[u]].piece == seeds[src[v]].piece) continue
                val sc = stepCost(ui, uj, di, dj); if (sc < 0) continue
                addCand(dist[u] + sc + dist[v], src[u], src[v])
            } }
        val all = cands.values.flatten().sortedBy { it.cost }
        log("JOINS walk: ${seeds.size} seeds ($offLattice in deep water or off the lattice) on $nP pieces, ${N} lattice vertices, ${dist.count { it < Double.MAX_VALUE }} reached; ${cands.size} piece pairs meet, ${all.size} candidates; ${System.currentTimeMillis() - t0} ms")

        // a candidate as a walked join: Climb.route between its two ends, every segment re-checked
        val rejected = ArrayList<String>()
        val seedsOf = seeds.indices.groupBy { seeds[it].piece }
        /** a seed and the same piece's next nearest within [ALT_END_YD] (a way's point on a wall face: its neighbour's) */
        fun endsNear(si: Int): List<Int> = listOf(si) + (seedsOf[seeds[si].piece] ?: emptyList()).filter { it != si }
            .map { it to hypot(seeds[it].end.x - seeds[si].end.x, seeds[it].end.y - seeds[si].end.y) }.filter { it.second <= ALT_END_YD }
            .sortedBy { it.second }.take(ALT_ENDS).map { it.first }
        val tried = HashMap<Long, Any>()                      // (seed a, seed b) -> its Join or its refusal
        fun walkSeeds(sa: Int, sb: Int, c: Cand, why: String, saved: Double): Join? {
            val key = sa.toLong() * 10_000_000 + sb
            tried[key]?.let { return (it as? Join)?.let { j -> Join(j.a, j.b, j.pieceA, j.pieceB, j.pts, j.yd, j.cost, j.flags, j.danger, j.tier, why, saved) } }
            val ea = seeds[sa].end; val eb = seeds[sb].end
            fun bad(m: String): Join? { tried[key] = m; return null }
            val leg = Climb.route(g, ea.pt(), eb.pt(), danger, k, deep, edges = edges)
            if (leg == null || leg.pts.size < 2) return bad("no leg")
            if (!leg.reached) return bad("not reached (ends ${hypot(leg.pts.last()[0] - eb.x, leg.pts.last()[1] - eb.y).toInt()} yd short)")
            val pts = leg.pts
            if (hypot(pts.first()[0] - ea.x, pts.first()[1] - ea.y) > END_YD || hypot(pts.last()[0] - eb.x, pts.last()[1] - eb.y) > END_YD)
                return bad("the walk starts or ends off its ends (a wall face at a way's point)")
            val walls = pts.zipWithNext().indices.filter { q -> !Climb.walkable(g, pts[q], pts[q + 1], deep) }
            if (walls.isNotEmpty()) return bad("segments $walls not walkable")
            val refused = pts.zipWithNext().indices.filter { q -> refuse(pts[q], pts[q + 1]) }
            if (refused.isNotEmpty()) return bad("segments $refused refused (segcheck)")
            if (pts.any { Climb.inCave(it) }) return bad("walks a link")
            val yd = pts.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }.sum()
            var share = 0.0; var f = ShippedNet.F_JOIN or ShippedNet.F_TRAIL
            for ((p, q) in pts.zipWithNext()) { val l = hypot(q[0] - p[0], q[1] - p[1]); val n = max(1, (l / 2).toInt())
                for (t in 0 until n) share += l / n * (danger(p[0] + (q[0] - p[0]) * (t + 0.5) / n, p[1] + (q[1] - p[1]) * (t + 0.5) / n) / DangerGrid.CAP)
                f = f or wetOf(p, q) }
            val sh = if (yd > 0) (share / yd).toFloat().coerceIn(0f, 1f) else 0f
            return Join(ea, eb, seeds[sa].piece, seeds[sb].piece, pts.map { doubleArrayOf(it[0], it[1]) }, yd, c.cost, f, sh, tierOf(sh), why, saved)
                .also { tried[key] = it }
        }
        /** a candidate as a walked join: Climb.route between its two ends (or, failing, the nearest other ends of the same two pieces) */
        fun walk(c: Cand, why: String, saved: Double = 0.0): Join? {
            val aa = endsNear(c.sa); val bb = endsNear(c.sb)
            val pairs = aa.indices.flatMap { i -> bb.indices.map { j -> Triple(i + j, aa[i], bb[j]) } }.sortedBy { it.first }.take(ALT_TRIES)
            for ((_, sa, sb) in pairs) walkSeeds(sa, sb, c, why, saved)?.let { return it }
            val ea = seeds[c.sa].end; val eb = seeds[c.sb].end
            rejected.add("${c.pa}-${c.pb} ${ea.x.toInt()},${ea.y.toInt()} -> ${eb.x.toInt()},${eb.y.toInt()} (${c.cost.toInt()}): ${tried[c.sa.toLong() * 10_000_000 + c.sb]}" +
                (if (pairs.size > 1) " (and ${pairs.size - 1} pairs of nearby ends)" else ""))
            return null
        }
        // KRUSKAL by walked cost
        val joins = ArrayList<Join>()
        for (c in all) { if (dsu.find(c.pa) == dsu.find(c.pb)) continue
            val j = walk(c, "tree") ?: continue
            joins.add(j); dsu.union(j.pieceA, j.pieceB) }
        // THE SHORTCUTS: a meeting whose ends are far apart on the net (the joins in) by [SHORTCUT_FACTOR] and [SHORTCUT_MIN_YD]
        val graph = NetGraph(net, idx)
        for (j in joins) graph.add(j)
        for (c in all) {
            val ea = seeds[c.sa].end; val eb = seeds[c.sb].end
            if (joins.any { j -> (hypot(j.a.x - ea.x, j.a.y - ea.y) < ALT_APART_YD && hypot(j.b.x - eb.x, j.b.y - eb.y) < ALT_APART_YD) ||
                    (hypot(j.a.x - eb.x, j.a.y - eb.y) < ALT_APART_YD && hypot(j.b.x - ea.x, j.b.y - ea.y) < ALT_APART_YD) }) continue
            val direct = c.cost
            val onNet = graph.dist(ea, eb)
            if (onNet < SHORTCUT_FACTOR * direct || onNet - direct < SHORTCUT_MIN_YD) continue
            val j = walk(c, "shortcut", if (onNet == Double.MAX_VALUE) -1.0 else onNet - direct) ?: continue
            if (onNet != Double.MAX_VALUE && (onNet < SHORTCUT_FACTOR * j.yd || onNet - j.yd < SHORTCUT_MIN_YD)) continue
            joins.add(j); graph.add(j); dsu.union(j.pieceA, j.pieceB)
        }
        // WHAT STANDS APART: the groups other than the biggest (by its ways' yards), and why
        val groups = (0 until nP).groupBy { dsu.find(it) }.values.sortedByDescending { ps -> ps.sumOf { pieceYd[it] } }
        val apart = groups.drop(1).map { ps ->
            val ns = ps.sumOf { pieceNodes[it].size }
            val x = ps.flatMap { pieceNodes[it] }.map { nodes[it].wx }.average(); val y = ps.flatMap { pieceNodes[it] }.map { nodes[it].wy }.average()
            val seedsN = ps.sumOf { pieceSeeds[it] }
            val bd = ps.sumOf { blockedDeep[it] }; val bw = ps.sumOf { blockedWall[it] }
            val met = all.filter { (it.pa in ps) != (it.pb in ps) }
            val why = when {
                seedsN == 0 -> "a cave's or a lift's floor with no node on the ground (no mouth)"
                ps.sumOf { seedsOnLattice[it] } == 0 -> if (ps.sumOf { seedsDeep[it] } > 0) "its ground points (a mouth) are in deep water: an underwater cave, a swim's"
                    else "its ground points are off the zone's lattice (another map's)"
                met.isNotEmpty() -> "its ${met.size} meetings with the rest never walked (${rejected.filter { r -> met.any { r.startsWith("${it.pa}-${it.pb} ") } }.take(2).joinToString("; ")})"
                bd > bw -> "an island: deep water stops its walk (${bd} steps by water, ${bw} by walls) - a declared swim's, or none"
                else -> "a walled pocket: walls stop its walk (${bw} steps by walls, ${bd} by water)"
            }
            Apart(ps, ns, ps.sumOf { pieceYd[it] }, x, y, why, ps.any { pieceGround[it] > 0 })
        }
        return Result(pieces, linked, joins, apart, rejected.distinct(), all.size, System.currentTimeMillis() - t0)
    }

    /**
     * [joins], then [check] on its joins (py/segcheck.py's verdict: [segcheck]) - each segment it flags refused and the joins walked
     * again (another leg, other ends), at most [passes] times. A join still flagged after them is dropped (its pieces stay apart, the
     * rejected list says so). The exporter's entry point when segcheck must say "none" on the joins too.
     */
    fun joinsChecked(net: SafePath.Net, g: Climb.Grid, deep: (Double, Double) -> Boolean, danger: (Double, Double) -> Float, k: Double,
                     links: List<ShippedNet.Link> = emptyList(), edges: Boolean = true, wetOf: (DoubleArray, DoubleArray) -> Int = { _, _ -> 0 },
                     check: (List<Join>) -> List<Pair<Int, Int>>, passes: Int = 3, log: (String) -> Unit = {}): Result {
        // a flagged segment's ground is kept off as deep water is (a wall to the walk and to Climb.route): within [FLAGGED_YD] of it
        val bad = ArrayList<DoubleArray>()
        fun segD(x: Double, y: Double, s: DoubleArray): Double {
            val dx = s[2] - s[0]; val dy = s[3] - s[1]; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((x - s[0]) * dx + (y - s[1]) * dy) / l2).coerceIn(0.0, 1.0)
            return hypot(x - s[0] - t * dx, y - s[1] - t * dy) }
        val deepX: (Double, Double) -> Boolean = { x, y -> deep(x, y) || bad.any { s ->
            x >= minOf(s[0], s[2]) - FLAGGED_YD && x <= maxOf(s[0], s[2]) + FLAGGED_YD && y >= minOf(s[1], s[3]) - FLAGGED_YD &&
                y <= maxOf(s[1], s[3]) + FLAGGED_YD && segD(x, y, s) < FLAGGED_YD } }
        var r = joins(net, g, deep, danger, k, links, edges, wetOf, log = log)
        for (pass in 1..passes) {
            val flagged = check(r.joins)
            log("JOINS segcheck pass $pass: ${flagged.size} segments flagged${flagged.take(6).joinToString("") { (j, q) -> " ${r.joins[j].pieceA}-${r.joins[j].pieceB}#$q" }}")
            if (flagged.isEmpty()) return r
            for ((j, q) in flagged) { val p0 = r.joins[j].pts[q]; val p1 = r.joins[j].pts[q + 1]; bad.add(doubleArrayOf(p0[0], p0[1], p1[0], p1[1])) }
            if (pass == passes) {
                // still flagged: those joins dropped (the pieces they joined counted apart again)
                val drop = flagged.map { it.first }.toSet()
                val bans = drop.map { i -> r.joins[i] }
                val again = joins(net, g, deepX, danger, k, links, edges, wetOf, { a, b -> bans.any { j -> j.pts.zipWithNext().any { (p, q) ->
                    hypot(p[0] - a[0], p[1] - a[1]) < 0.5 && hypot(q[0] - b[0], q[1] - b[1]) < 0.5 } } }, log)
                val still = check(again.joins).map { it.first }.toSet()
                return Result(again.pieces, again.linked, again.joins.filterIndexed { i, _ -> i !in still }, again.apart, again.rejected +
                    drop.map { i -> "${r.joins[i].pieceA}-${r.joins[i].pieceB}: dropped, segcheck still flags it after $passes passes" } +
                    still.map { i -> "${again.joins[i].pieceA}-${again.joins[i].pieceB}: DROPPED at the end, segcheck flags it (its pieces are apart: not in the apart list)" },
                    again.candidates, again.ms)
            }
            r = joins(net, g, deepX, danger, k, links, edges, wetOf, log = log)
        }
        return r
    }

    /** a join's route letter in the segcheck file: JA, JB, ... JZ, JAA, ... (letters only: segcheck names a segment letter + index) */
    fun letter(i: Int): String { var n = i; val sb = StringBuilder(); do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0); return "J$sb" }

    /** [joins] as routes for py/segcheck.py and py/wetcheck.py: [dir]/levels_routes_[tag].json, a route a join ([letter]) */
    fun routesFile(dir: java.io.File, tag: String, joins: List<Join>): java.io.File = java.io.File(dir, "levels_routes_$tag.json").also { f ->
        f.writeText(JSONObject().put("zone", tag).put("routes", JSONArray(joins.mapIndexed { i, j -> JSONObject().put("letter", letter(i))
            .put("name", "join ${j.pieceA}-${j.pieceB} (${j.why})").put("reached", true).put("pts", JSONArray(j.pts.map { JSONArray(listOf(it[0], it[1])) })) })).toString()) }

    /**
     * py/segcheck.py as a [joinsChecked] check: the joins written to [dir] (beside the zone's floors_ / holes_ / layers_ files, which
     * the caller puts there: RenderSafePath writes them into its out folder), segcheck run from [py] with ZDIR [zdir], its flagged
     * segments back as (join index, segment index)
     */
    fun segcheck(py: java.io.File, dir: java.io.File, tag: String, zdir: String, work: java.io.File): (List<Join>) -> List<Pair<Int, Int>> = { js ->
        if (js.isEmpty()) emptyList() else {
            val f = routesFile(dir, tag, js)
            val out = ParityCompare.runPy(py, "segcheck.py", f, zdir, work)
            val last = out.lines().lastOrNull { "crossing a" in it } ?: error("segcheck said nothing: ${out.takeLast(300)}")
            val byLetter = js.indices.associateBy { letter(it) }
            Regex("(J[A-Z]+)(\\d+)-J[A-Z]+\\d+").findAll(last.substringAfter(":")).mapNotNull { m ->
                byLetter[m.groupValues[1]]?.let { it to m.groupValues[2].toInt() } }.distinct().toList()
        }
    }

    private val FWD_I = intArrayOf(1, 0, 1, 1); private val FWD_J = intArrayOf(0, 1, 1, -1)
    /** a binary min-heap of (key, vertex), primitive arrays (a zone's lattice walk pushes millions) */
    private class Heap { var k = DoubleArray(1 shl 16); var v = IntArray(1 shl 16); var size = 0
        fun add(key: Double, x: Int) { if (size == k.size) { k = k.copyOf(size * 2); v = v.copyOf(size * 2) }
            var i = size++; while (i > 0) { val p = (i - 1) / 2; if (k[p] <= key) break; k[i] = k[p]; v[i] = v[p]; i = p }; k[i] = key; v[i] = x }
        fun topKey() = k[0]
        fun pop(): Int { val r = v[0]; size--; if (size == 0) return r
            val key = k[size]; val x = v[size]; var i = 0
            while (true) { var c = 2 * i + 1; if (c >= size) break; if (c + 1 < size && k[c + 1] < k[c]) c++; if (k[c] >= key) break; k[i] = k[c]; v[i] = v[c]; i = c }
            k[i] = key; v[i] = x; return r } }

    /** the net as a graph of its ways' lengths, with [End]s (a node, or a point on a way) and the joins added: for the shortcuts */
    private class NetGraph(val net: SafePath.Net, val idx: Map<Int, Int>) {
        val adj = HashMap<Int, ArrayList<Pair<Int, Double>>>()
        var next = -1                                         // virtual nodes (a point on a way): negative ids
        fun link(a: Int, b: Int, w: Double) { adj.getOrPut(a) { ArrayList() }.add(b to w); adj.getOrPut(b) { ArrayList() }.add(a to w) }
        init { for (e in net.edges) { val a = idx[e.a] ?: continue; val b = idx[e.b] ?: continue
            link(e.a, e.b, hypot(net.nodes[a].wx - net.nodes[b].wx, net.nodes[a].wy - net.nodes[b].wy)) } }
        fun nodeOf(e: End): Int {
            if (e.node >= 0) return e.node
            val ed = net.edges[e.edge]; val a = net.nodes[idx[ed.a]!!]; val b = net.nodes[idx[ed.b]!!]
            val id = next--; link(id, a.id, hypot(a.wx - e.x, a.wy - e.y)); link(id, b.id, hypot(b.wx - e.x, b.wy - e.y)); return id
        }
        fun add(j: Join) = link(nodeOf(j.a), nodeOf(j.b), j.yd)
        fun dist(a: End, b: End): Double {
            val s = nodeOf(a); val t = nodeOf(b)
            val d = HashMap<Int, Double>(); val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first }); d[s] = 0.0; pq.add(0.0 to s)
            while (pq.isNotEmpty()) { val (du, u) = pq.poll(); if (du > (d[u] ?: Double.MAX_VALUE)) continue; if (u == t) return du
                for ((v, w) in adj[u] ?: emptyList<Pair<Int, Double>>()) { val nd = du + w; if (nd < (d[v] ?: Double.MAX_VALUE)) { d[v] = nd; pq.add(nd to v) } } }
            return Double.MAX_VALUE
        }
    }

    /**
     * THE EXPORTER'S WIRING (ShippedNet.section's `extras`): [net] with each way a join ends on split there (a [SafePath.POINT] node, src
     * "join"; both halves keep the way's kind, state, src, danger and climb) and one [ShippedNet.Extra] a join: a [SafePath.TRAIL] from its
     * end node to the other, walked by its points (ends included), flags [Join.flags] (F_JOIN | F_TRAIL and what it crosses), its danger.
     * [net] must be the net [joins] ran on (its edges' order: a join's [End.edge] indexes them) - or one whose first edges are those (the
     * exporter's netC, when the joins ran on netC + the city's floor streets). New node ids from [firstId] (default past the net's).
     */
    fun extras(net: SafePath.Net, joins: List<Join>, firstId: Int = (net.nodes.maxOfOrNull { it.id } ?: 0) + 1): Pair<SafePath.Net, List<ShippedNet.Extra>> {
        val nodes = net.nodes.toMutableList(); var next = firstId
        val idx = HashMap<Int, Int>(); net.nodes.forEachIndexed { i, n -> idx[n.id] = i }
        val splits = HashMap<Int, ArrayList<Pair<Double, Int>>>()
        val endNode = HashMap<End, Int>()
        for (j in joins) for (e in listOf(j.a, j.b)) {
            if (e.node >= 0) { endNode[e] = e.node; continue }
            val ed = net.edges[e.edge]; val a = net.nodes[idx[ed.a]!!]
            val t = hypot(e.x - a.wx, e.y - a.wy)
            val l = splits.getOrPut(e.edge) { ArrayList() }
            val have = l.firstOrNull { abs(it.first - t) < 0.5 }
            endNode[e] = have?.second ?: next++.also { id -> nodes.add(SafePath.Node(id, e.x, e.y, SafePath.POINT, "join")); l.add(t to id) }
        }
        val edges = ArrayList<SafePath.Edge>()
        for ((ei, e) in net.edges.withIndex()) {
            val sp = splits[ei]?.sortedBy { it.first }
            if (sp == null) { edges.add(e); continue }
            var last = e.a
            for ((_, id) in sp) { edges.add(SafePath.Edge(last, id, e.kind, e.state, e.src, e.danger, e.climb)); last = id }
            edges.add(SafePath.Edge(last, e.b, e.kind, e.state, e.src, e.danger, e.climb))
        }
        val xs = joins.map { j -> ShippedNet.Extra(endNode[j.a]!!, endNode[j.b]!!, SafePath.TRAIL, j.flags, j.pts.map { doubleArrayOf(it[0], it[1]) }, j.danger, "join") }
        return SafePath.Net(net.map, net.cont, net.built, nodes, edges, net.exits, net.reviewed) to xs
    }

    /** [net] with [joins] in as plain ways (its splits, then each join one straight [SafePath.TRAIL] between its ends, src "join"): the
     *  connectivity checks' */
    fun apply(net: SafePath.Net, joins: List<Join>): SafePath.Net {
        val (split, xs) = extras(net, joins)
        return SafePath.Net(split.map, split.cont, split.built, split.nodes, split.edges + xs.map { SafePath.Edge(it.a, it.b, it.kind, SafePath.OK, it.src, it.danger) },
            split.exits, split.reviewed)
    }
}
