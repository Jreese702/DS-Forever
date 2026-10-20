import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedNet
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * THE ROADMAP COVERAGE LAYER (NET1 v2's [ShippedNet.Roadmap], RM1; the user, 2026-10-19: "Ship our path network";
 * DEVICE_INTEGRATION_PLAN.md SHIPPED NETWORK): a graph over a zone's walkable ground, so the device - with no lattice - attaches
 * within a short leg anywhere on the zone and routes as the harness does. What the parity test failed on (33 goals over 200 yd from
 * any way; the 25 yd ground grid's attach legs through walls) is what it is for. Hardens T1's prototype (RoadmapBuild.kt):
 *
 * - NODES: one a cell of [cell] lattice steps (S yd = cell x Climb.STEP), at the cell's best walkable LATTICE VERTEX (nearest its
 *   middle, off a lip, open all round): never deep water or lava ([Ground.deep]: ZoneInputs.deep, lethal liquids are deep), never
 *   with fewer than [MIN_OPEN] of its 8 steps open, never on a wall face, never inside a building's room. Only the zone's own cells
 *   ([Ground.inZone] at the cell's middle, corners or edges' middles). [ShippedNet.R_FLOOR] on a walkway's floor (the lattice's floor
 *   layer), [ShippedNet.R_BUILDING] within 3 yd of a room.
 * - EDGES to the four forward neighbour cells ([ShippedNet.DIRS]), kept only when THE HARNESS'S OWN LATTICE LEG joins them:
 *   [Climb.route] with the deep water a wall, edge clearance on and the wade's / rooms' calm cost (SafeRoute's own direct leg),
 *   reaching the other node exactly within [DETOUR] x the straight line; wading no more than [SafePath.NARROW_YD] on end (else
 *   routed again with no wading, SafeRoute's strict retry; still more = no edge); never crossing a room's footprint (each half yard
 *   along); then simplified (Douglas-Peucker within [SIMPLIFY_YD], a chord kept only when [Climb.walkable] itself, off the rooms and
 *   no longer a wade) and every final segment checked by [Climb.walkable] with the deep water (the gate's rule). The points stay
 *   lattice vertices, so RM1's integers lose nothing.
 * - Edge flags as RM1's: [ShippedNet.RE_WADE], [ShippedNet.RE_BRIDGE] (the water grid's), [ShippedNet.RE_BUILDING] (within 2 yd of
 *   a room), the edge-clearance tier (the most [Climb.edgeCost] along it - drops and deep water's banks, as Climb.route's edgeOf)
 *   and the climb tier (its steepest lattice step).
 * - Danger per faction: [dangerBytes] - the mean of a faction's danger along each edge, [ShippedNet.ROADMAP_DANGER_SCALE] a unit.
 *   The geometry is the factions' shared part (nothing faction-specific goes into [build]: the calm costs are the same for both).
 * - NO HEIGHTS, no geometry, no file ids.
 *
 * Entry points: [build] (-> [Built]: the RM1 roadmap, ready for [ShippedNet.Pack]), [dangerBytes], [routesJson] (each edge as a
 * route, for py/segcheck.py and py/wetcheck.py).
 */
object NetRoadmap {
    /** an edge's leg longer than this x the straight line between its nodes is a way round: dropped (other cells join round it) */
    const val DETOUR = 2.0
    const val SIMPLIFY_YD = 1.0
    /** a node needs this many of its 8 lattice steps open (no wall, no deep water beyond) */
    const val MIN_OPEN = 6
    /** the A*'s window: narrowed to Climb.MIN_MARGIN_YD round the two ends (an edge is short) */
    const val LEG_WINDOW = 40_000
    /** THE CONNECTIVITY REPAIR (the coordinator, 2026-10-19, Durotar's Dustwind Cave): two pieces of the roadmap joined by a
     *  neighbour's lattice leg up to this x the straight line, or by a leg to a node two cells away through the cell between */
    const val REPAIR_DETOUR = 3.0
    /** the two-cell neighbours a repair tries (each through the forward neighbour cell between: DIRS twice) */
    /** a chain's reach: a node this many cells away at most (Chebyshev), through the cells between */
    const val CHAIN_CELLS = 4
    /** a pair of pieces is tried by this many chains at most (their nearest nodes first) */
    const val CHAIN_TRIES = 6
    /** the cells RM1 nodes use by default: 10 lattice steps, 41.7 yd */
    const val CELL = 10

    /**
     * the zone's ground as the harness has it: the lattice [g]; [deep] deep water / lava (a wall: ZoneInputs.deep); [overFord] deeper
     * than the ford (SafeRoute.overFord: the strict retry's water; null = no retry); [waterAt] the water grid's depth
     * (SafePath.Water.at: NaN dry, BRIDGE a walkway's deck); [rooms] a building's room footprint (ZoneInputs.Lattice.roomsAt);
     * [calm] the wade's and rooms' cost (DangerInputs.Danger.calm) at weight [k] (DangerInputs.Danger.k); [inZone] the zone's own
     * ground (its uiMap's areas; null = the whole lattice).
     */
    class Ground(val g: Climb.Grid, val deep: (Double, Double) -> Boolean, val overFord: ((Double, Double) -> Boolean)?,
                 val waterAt: (Double, Double) -> Float, val rooms: (Double, Double) -> Boolean, val calm: (Double, Double) -> Float,
                 val k: Double, val inZone: ((Double, Double) -> Boolean)?)

    /** what [build] did, for the logs */
    class Stats(var cells: Int = 0, var nodes: Int = 0, var tried: Int = 0, var kept: Int = 0, var noLeg: Int = 0, var detour: Int = 0,
                var room: Int = 0, var wade: Int = 0, var isolated: Int = 0, var strict: Int = 0, var walked: Int = 0, var ms: Long = 0,
                var checkFailed: Int = 0, var mended: Int = 0, var checkDropped: Int = 0,
                var compBefore: Int = 0, var compAfter: Int = 0, var repaired1: Int = 0, var repaired2: Int = 0, var repairDropped: Int = 0,
                var regions: Int = 0) {
        override fun toString() = "$cells zone cells, $nodes nodes ($isolated without an edge dropped), $kept of $tried neighbour pairs joined " +
            "($walked walked; not joined: no leg $noLeg, detour $detour, room $room, wade $wade; $strict by the no-wade retry; " +
            "segcheck/wetcheck failed $checkFailed: $mended walked again, $checkDropped dropped); " +
            "CONNECTIVITY $compBefore pieces before the repair, $compAfter after ($repaired1 neighbour legs to ${REPAIR_DETOUR}x, " +
            "$repaired2 chains of up to $CHAIN_CELLS cells through new nodes; $repairDropped repair edges dropped by the check); " +
            "the lattice's walkable regions holding a node: $regions), $ms ms"
    }

    /** the roadmap and each edge's points a yard apart (for [dangerBytes]) */
    class Built(val rm: ShippedNet.Roadmap, val samples: List<List<DoubleArray>>, val stats: Stats)

    /**
     * THE ENTRY POINT: the roadmap over [ground] with cells of [cell] lattice steps (S yd = cell x Climb.STEP). [check] (optional; e.g.
     * [pyCheck]: py/segcheck.py and py/wetcheck.py): given every edge's points, the indices that fail - each walked again (its leg
     * unsimplified, then the lattice's leg with no edge clearance) and checked once more, else dropped.
     */
    fun build(ground: Ground, cell: Int = CELL, check: ((List<List<DoubleArray>>) -> Set<Int>)? = null, log: (String) -> Unit = {}): Built {
        val t0 = System.nanoTime()
        val g = ground.g
        val nx = ceil(g.nx / cell.toDouble()).toInt(); val ny = ceil(g.ny / cell.toDouble()).toInt()
        val st = Stats()
        // per-vertex caches (the water asked once a vertex; the edge clearance)
        val deepV = ByteArray(g.nx * g.ny)
        fun deepAt(i: Int, j: Int): Boolean {
            if (i !in 0 until g.nx || j !in 0 until g.ny) return true
            val q = j * g.nx + i
            if (deepV[q].toInt() == 0) deepV[q] = if (ground.deep(g.wx(j), g.wy(i))) 2 else 1
            return deepV[q].toInt() == 2
        }
        val edgeV = FloatArray(g.nx * g.ny) { Float.NaN }
        val er = ceil(Climb.EDGE_YD / Climb.STEP).toInt()
        fun onDeck(i: Int, j: Int) = g.deckZ?.get(j * g.nx + i)?.isNaN() == false
        // EDGE CLEARANCE as Climb.route's edgeOf: the nearest drop or deep water's bank; a walkway's own floor none
        fun edgeAt(i: Int, j: Int): Double {
            if (i !in 0 until g.nx || j !in 0 until g.ny) return 0.0
            val q = j * g.nx + i
            if (edgeV[q].isNaN()) {
                var d = g.dropYd(i, j)
                for (b in j - er..j + er) for (a in i - er..i + er) {
                    if ((a == i && b == j) || !deepAt(a, b)) continue
                    d = min(d, max(0.0, hypot((a - i).toDouble(), (b - j).toDouble()) * Climb.STEP - Climb.STEP / 2))
                }
                edgeV[q] = (if (onDeck(i, j)) 0.0 else Climb.edgeCost(d)).toFloat()
            }
            return edgeV[q].toDouble()
        }
        fun near(x: Double, y: Double, r: Double, f: (Double, Double) -> Boolean) = f(x, y) || f(x + r, y) || f(x - r, y) || f(x, y + r) || f(x, y - r)

        // ---- the nodes, in cell order
        val order = (0 until cell).flatMap { dr -> (0 until cell).map { dc -> intArrayOf(dr, dc) } }
            .sortedBy { hypot(it[0] - (cell - 1) / 2.0, it[1] - (cell - 1) / 2.0) }
        val at = IntArray(nx * ny) { -1 }
        val nCell = ArrayList<Int>(); val nI = ArrayList<Int>(); val nJ = ArrayList<Int>(); val nF = ArrayList<Int>()
        for (c in 0 until nx * ny) {
            val j0 = (c / nx) * cell; val i0 = (c % nx) * cell
            val inZ = ground.inZone
            if (inZ != null) {
                val ci = listOf(i0, min(g.nx - 1, i0 + cell / 2), min(g.nx - 1, i0 + cell - 1))
                val cj = listOf(j0, min(g.ny - 1, j0 + cell / 2), min(g.ny - 1, j0 + cell - 1))
                if (ci.none { i -> cj.any { j -> inZ(g.wx(j), g.wy(i)) } }) continue
            }
            st.cells++
            var best = -1; var bestScore = Double.MAX_VALUE
            for ((oq, o) in order.withIndex()) {
                val j = j0 + o[0]; val i = i0 + o[1]
                if (i !in 1 until g.nx - 1 || j !in 1 until g.ny - 1) continue
                val dist = hypot(o[0] - (cell - 1) / 2.0, o[1] - (cell - 1) / 2.0) * Climb.STEP
                if (dist >= bestScore) break                         // nearest first: nothing farther can score better
                if (deepAt(i, j)) continue
                val x = g.wx(j); val y = g.wy(i)
                if (ground.rooms(x, y)) continue
                var open = 0
                for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                    if (g.step(i, j, di, dj) != Climb.WALL && !deepAt(i + di, j + dj)) open++ }
                if (open < MIN_OPEN) continue
                if (!g.lineClear(doubleArrayOf(x, y), doubleArrayOf(x, y))) continue      // on a wall face
                val w = ground.waterAt(x, y)
                val score = dist + 6.0 * edgeAt(i, j) + (8 - open) * 2.0 + (if (!w.isNaN() && w > 0f && w != SafePath.Water.BRIDGE) 10.0 else 0.0)
                if (score < bestScore) { bestScore = score; best = oq }
            }
            if (best < 0) continue
            val j = j0 + order[best][0]; val i = i0 + order[best][1]; val x = g.wx(j); val y = g.wy(i)
            at[c] = nCell.size; nCell.add(c); nI.add(i); nJ.add(j)
            nF.add((if (onDeck(i, j)) ShippedNet.R_FLOOR else 0) or (if (near(x, y, 3.0, ground.rooms)) ShippedNet.R_BUILDING else 0))
        }

        // ---- the edges, in node order, DIRS order within a node
        val limits = Climb.Limits(LEG_WINDOW)
        val strictWater: ((Double, Double) -> Boolean)? = ground.overFord?.let { of -> { x, y -> ground.deep(x, y) || of(x, y) } }
        fun crossesRoom(p: DoubleArray, q: DoubleArray): Boolean {
            val n = max(1, hypot(q[0] - p[0], q[1] - p[1]).toInt() * 2)
            for (t in 0..n) if (ground.rooms(p[0] + (q[0] - p[0]) * t / n, p[1] + (q[1] - p[1]) * t / n)) return true
            return false
        }
        fun longestWade(w: List<DoubleArray>): Double {
            val of = ground.overFord ?: return 0.0
            var run = 0.0; var best = 0.0
            for ((pa, pb) in w.zipWithNext()) {
                val n = max(1, (hypot(pb[0] - pa[0], pb[1] - pa[1]) * 2).toInt())
                for (q in 0 until n) { val x = pa[0] + (pb[0] - pa[0]) * q / n; val y = pa[1] + (pb[1] - pa[1]) * q / n
                    if (of(x, y)) { run += 0.5; best = max(best, run) } else run = 0.0 }
            }
            return best
        }
        fun chordOk(p: DoubleArray, q: DoubleArray, water: (Double, Double) -> Boolean) =
            Climb.walkable(g, p, q, water) && !crossesRoom(p, q) && longestWade(listOf(p, q)) <= SafePath.NARROW_YD
        // Douglas-Peucker within SIMPLIFY_YD, a chord kept only when it is walkable itself (the wade along the whole re-checked after)
        fun simplify(w: List<DoubleArray>, water: (Double, Double) -> Boolean): List<DoubleArray> {
            if (w.size <= 2) return w
            val keep = BooleanArray(w.size); keep[0] = true; keep[w.size - 1] = true
            fun dp(a: Int, b: Int) {
                if (b <= a + 1) return
                val pa = w[a]; val pb = w[b]; val L = hypot(pb[0] - pa[0], pb[1] - pa[1])
                var far = -1; var fd = -1.0
                for (q in a + 1 until b) {
                    val p = w[q]
                    val d = if (L < 1e-9) hypot(p[0] - pa[0], p[1] - pa[1]) else abs((pb[0] - pa[0]) * (pa[1] - p[1]) - (pa[0] - p[0]) * (pb[1] - pa[1])) / L
                    if (d > fd) { fd = d; far = q }
                }
                if (fd <= SIMPLIFY_YD && chordOk(pa, pb, water)) return
                keep[far] = true; dp(a, far); dp(far, b)
            }
            dp(0, w.size - 1)
            val s = w.filterIndexed { q, _ -> keep[q] }
            return if (longestWade(s) <= SafePath.NARROW_YD) s else w
        }
        fun sample(shape: List<DoubleArray>): List<DoubleArray> {
            val out = ArrayList<DoubleArray>()
            for ((a, b) in shape.zipWithNext()) { val n = max(1, hypot(b[0] - a[0], b[1] - a[1]).toInt())
                for (q in 0 until n) out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n)) }
            out.add(shape.last()); return out
        }
        // RM1's flags: wade / bridge (the water grid's), building (within 2 yd of a room), the edge-clearance and climb tiers (the lattice's)
        fun flags(shape: List<DoubleArray>, samples: List<DoubleArray>): Int {
            var f = 0; var edge = 0.0; var climb = Climb.LEVEL
            for (p in samples) { val d = ground.waterAt(p[0], p[1])
                if (d == SafePath.Water.BRIDGE) f = f or ShippedNet.RE_BRIDGE
                else if (!d.isNaN() && d > 0f && !ground.deep(p[0], p[1])) f = f or ShippedNet.RE_WADE
                if (near(p[0], p[1], 2.0, ground.rooms)) f = f or ShippedNet.RE_BUILDING }
            for ((a, b) in shape.zipWithNext()) {
                val s = g.at(a[0], a[1]); val e = g.at(b[0], b[1]); val n = max(abs(e[0] - s[0]), abs(e[1] - s[1]))
                var ci = s[0]; var cj = s[1]; edge = max(edge, edgeAt(ci, cj))
                for (q in 1..n) { val ni = s[0] + Math.round((e[0] - s[0]).toDouble() * q / n).toInt(); val nj = s[1] + Math.round((e[1] - s[1]).toDouble() * q / n).toInt()
                    val di = Integer.signum(ni - ci); val dj = Integer.signum(nj - cj)
                    if (di == 0 && dj == 0) continue
                    val c = g.step(ci, cj, di, dj); if (c != Climb.WALL) climb = max(climb, c)
                    ci = ni; cj = nj; edge = max(edge, edgeAt(ci, cj)) }
            }
            val et = min(3, ceil(edge / ShippedNet.EDGE_TIER_COST - 1e-9).toInt())
            return f or (et shl 2) or (min(3, climb) shl 4)
        }
        val eA = ArrayList<Int>(); val eB = ArrayList<Int>(); val eF = ArrayList<Int>(); val eP = ArrayList<IntArray?>(); val samples = ArrayList<List<DoubleArray>>()
        val shapes = ArrayList<List<DoubleArray>>(); val raws = ArrayList<List<DoubleArray>>(); val waters = ArrayList<(Double, Double) -> Boolean>()
        // a shape's inner points as RM1's lattice vertices, null when one is no vertex
        fun innerOf(pts: List<DoubleArray>): IntArray? {
            val inner = IntArray((pts.size - 2) * 2)
            for (q in 1 until pts.size - 1) { val gr = ShippedNet.gr(pts[q][0]); val gc = ShippedNet.gc(pts[q][1])
                if (abs(ShippedNet.vx(gr) - pts[q][0]) > 1e-6 || abs(ShippedNet.vy(gc) - pts[q][1]) > 1e-6) return null
                inner[2 * (q - 1)] = gr; inner[2 * (q - 1) + 1] = gc }
            return inner
        }
        fun shapeOf(pa: DoubleArray, pb: DoubleArray, inner: IntArray) =
            listOf(pa) + (0 until inner.size / 2).map { q -> doubleArrayOf(ShippedNet.vx(inner[2 * q]), ShippedNet.vy(inner[2 * q + 1])) } + listOf(pb)
        // THE GATE'S OWN RULE on every segment (no wall, no deep water), off the rooms, no long wade
        fun fine(shape: List<DoubleArray>) = shape.zipWithNext().all { (p, q) -> Climb.walkable(g, p, q, ground.deep) && !crossesRoom(p, q) } &&
            longestWade(shape) <= SafePath.NARROW_YD
        class Walk(val shape: List<DoubleArray>, val raw: List<DoubleArray>, val water: (Double, Double) -> Boolean)
        // THE EDGE'S LEG from [pa] to [pb]: the lattice's own (calm costs, edge clearance), the no-wade retry, at most [detour] x the line,
        // off the rooms, simplified, every segment walkable; null (counted in the stats when [count]) when there is none
        fun walk(pa: DoubleArray, pb: DoubleArray, detour: Double, count: Boolean): Walk? {
            val line = hypot(pb[0] - pa[0], pb[1] - pa[1])
            fun legOn(water: (Double, Double) -> Boolean) = Climb.route(g, pa, pb, ground.calm, ground.k, water, emptyList(), true, limits)
                ?.takeIf { it.reached && it.pts.size >= 2 && hypot(it.pts.first()[0] - pa[0], it.pts.first()[1] - pa[1]) < 1e-6 &&
                    hypot(it.pts.last()[0] - pb[0], it.pts.last()[1] - pb[1]) < 1e-6 }
            var water: (Double, Double) -> Boolean = ground.deep
            var leg = legOn(water)
            if (leg == null) { if (count) st.noLeg++; return null }
            if (longestWade(leg.pts) > SafePath.NARROW_YD) {
                val sw = strictWater
                val l2 = sw?.let { legOn(it) }
                if (l2 == null || longestWade(l2.pts) > SafePath.NARROW_YD) { if (count) st.wade++; return null }
                leg = l2; water = sw; if (count) st.strict++
            }
            val raw = leg.pts.map { doubleArrayOf(it[0], it[1]) }
            if (raw.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }.sum() > detour * line + 1.0) { if (count) st.detour++; return null }
            if (raw.zipWithNext().any { (p, q) -> crossesRoom(p, q) }) { if (count) st.room++; return null }
            // the inner points are lattice vertices (the leg's bends), the ends the nodes
            val inner = innerOf(simplify(raw, water))
            val shape = inner?.let { shapeOf(pa, pb, it) }
            if (inner == null || shape == null || !fine(shape)) { if (count) st.noLeg++; return null }
            return Walk(shape, raw, water)
        }
        for (n in nCell.indices) {
            val c = nCell[n]; val pa = doubleArrayOf(g.wx(nJ[n]), g.wy(nI[n]))
            for (d in ShippedNet.DIRS) {
                val ci = c % nx + d[0]; val cj = c / nx + d[1]
                if (ci !in 0 until nx || cj !in 0 until ny) continue
                val m = at[cj * nx + ci]; if (m < 0) continue
                st.tried++
                val w = walk(pa, doubleArrayOf(g.wx(nJ[m]), g.wy(nI[m])), DETOUR, true) ?: continue
                eA.add(n); eB.add(m); eP.add(innerOf(w.shape)); shapes.add(w.shape); raws.add(w.raw); waters.add(w.water)
            }
        }
        // THE CHECKS (py/segcheck.py, py/wetcheck.py): a failing edge walked again, else dropped
        val drop = BooleanArray(eA.size)
        if (check != null && eA.isNotEmpty()) {
            val bad = check(shapes).filter { it in eA.indices }
            st.checkFailed = bad.size
            var left = bad
            fun same(u: List<DoubleArray>, v: List<DoubleArray>) = u.size == v.size && u.zip(v).all { (p, q) -> p[0] == q[0] && p[1] == q[1] }
            val alts: List<(Int) -> List<DoubleArray>?> = listOf(
                { e -> raws[e] },
                { e -> val pa = shapes[e].first(); val pb = shapes[e].last()
                    Climb.route(g, pa, pb, water = waters[e], edges = false, limits = limits)
                        ?.takeIf { it.reached && hypot(it.pts.last()[0] - pb[0], it.pts.last()[1] - pb[1]) < 1e-6 }?.pts?.map { doubleArrayOf(it[0], it[1]) } })
            for (alt in alts) {
                if (left.isEmpty()) break
                val tried = left.mapNotNull { e -> alt(e)?.let { p -> innerOf(p)?.let { inner -> shapeOf(shapes[e].first(), shapes[e].last(), inner) } }
                    ?.takeIf { fine(it) && !same(it, shapes[e]) }?.let { e to it } }
                if (tried.isEmpty()) continue
                val again = check(tried.map { it.second })
                val ok = tried.filterIndexed { q, _ -> q !in again }
                for ((e, sh) in ok) { shapes[e] = sh; eP[e] = innerOf(sh) }
                st.mended += ok.size
                val okSet = ok.map { it.first }.toSet()
                left = left.filter { it !in okSet }
            }
            for (e in left) drop[e] = true
            st.checkDropped = left.size
        }
        val keep = BooleanArray(eA.size) { !drop[it] }
        // ---- THE CONNECTIVITY REPAIR: the roadmap's pieces (nodes with an edge) joined where the lattice joins them
        val cap = nCell.size + nx * ny                     // room for the repair's new nodes (one a cell at most)
        val par = IntArray(cap) { it }
        fun find(x0: Int): Int { var x = x0; while (par[x] != x) { par[x] = par[par[x]]; x = par[x] }; return x }
        fun union(a0: Int, b0: Int) { par[find(a0)] = find(b0) }
        fun pieces(): Int { val deg = IntArray(nCell.size); for (e in eA.indices) if (keep.getOrElse(e) { true }) { deg[eA[e]]++; deg[eB[e]]++ }
            return nCell.indices.filter { deg[it] > 0 }.map { find(it) }.toSet().size }
        for (e in eA.indices) if (keep[e]) union(eA[e], eB[e])
        st.compBefore = pieces()
        val has = HashSet<Long>(); for (e in eA.indices) if (keep[e]) has.add(eA[e].toLong() * 1_000_000 + eB[e])
        val deg = IntArray(cap); for (e in eA.indices) if (keep[e]) { deg[eA[e]]++; deg[eB[e]]++ }
        val firstRepair = eA.size
        fun addEdge(a0: Int, b0: Int, w: Walk) {
            eA.add(a0); eB.add(b0); eP.add(innerOf(w.shape)); shapes.add(w.shape); raws.add(w.raw); waters.add(w.water)
            has.add(a0.toLong() * 1_000_000 + b0); deg[a0]++; deg[b0]++; union(a0, b0)
        }
        // 1. a forward neighbour in another piece, by a leg up to REPAIR_DETOUR x the line
        for (n in nCell.indices) {
            val c = nCell[n]
            for (d in ShippedNet.DIRS) {
                val ci = c % nx + d[0]; val cj = c / nx + d[1]
                if (ci !in 0 until nx || cj !in 0 until ny) continue
                val m = at[cj * nx + ci]; if (m < 0 || find(m) == find(n) || has.contains(n.toLong() * 1_000_000 + m)) continue
                val w = walk(doubleArrayOf(g.wx(nJ[n]), g.wy(nI[n])), doubleArrayOf(g.wx(nJ[m]), g.wy(nI[m])), REPAIR_DETOUR, false) ?: continue
                addEdge(n, m, w); st.repaired1++
            }
        }
        // 2. A CHAIN: a node 2 to [CHAIN_CELLS] cells away in another piece, by a lattice leg up to REPAIR_DETOUR x the line, through
        //    cells that hold no node with an edge: a node in each (a new one, or one with no edge moved onto the leg) splits the leg
        //    into forward edges between neighbour cells
        fun chain(n: Int, m: Int): Boolean {
            val pa = doubleArrayOf(g.wx(nJ[n]), g.wy(nI[n])); val pb = doubleArrayOf(g.wx(nJ[m]), g.wy(nI[m]))
            val w = walk(pa, pb, REPAIR_DETOUR, false) ?: return false
            // the leg a yard apart: each spot's segment, lattice vertex and cell
            class Spot(val q: Int, val i: Int, val j: Int, val c: Int)
            val spots = ArrayList<Spot>()
            for (q in 0 until w.shape.size - 1) {
                val u = w.shape[q]; val v = w.shape[q + 1]; val k2 = max(1, hypot(v[0] - u[0], v[1] - u[1]).toInt())
                for (t in 0 until k2) { val vx = g.at(u[0] + (v[0] - u[0]) * t / k2, u[1] + (v[1] - u[1]) * t / k2)
                    val ci = vx[0] / cell; val cj = vx[1] / cell
                    if (ci !in 0 until nx || cj !in 0 until ny) return false
                    spots.add(Spot(q, vx[0], vx[1], cj * nx + ci)) }
            }
            // the cells in the order the leg goes through them, each once (a loop back into a cell cut out: its spots join that cell's),
            // each the neighbour of the one before
            val runs = ArrayList<MutableList<Spot>>()
            for (sp in spots) {
                val k = runs.indexOfFirst { it[0].c == sp.c }
                if (k < 0) runs.add(mutableListOf(sp)) else { while (runs.size > k + 1) runs.removeAt(runs.size - 1); runs[k].add(sp) }
            }
            if (runs.isEmpty() || runs.first()[0].c != nCell[n] || runs.last()[0].c != nCell[m]) return false
            for ((r1, r2) in runs.zipWithNext()) { val c1 = r1[0].c; val c2 = r2[0].c
                if (abs(c1 % nx - c2 % nx) > 1 || abs(c1 / nx - c2 / nx) > 1) return false }
            if (runs.size < 3) return false
            // a vertex for each cell between: the leg's spot nearest the cell's middle (dry, off the rooms)
            val picks = ArrayList<Spot>()
            for (r in runs.subList(1, runs.size - 1)) {
                val c = r[0].c; val mid = at[c]; if (mid >= 0 && deg[mid] > 0) return false
                val cx = (c % nx) * cell + (cell - 1) / 2.0; val cy = (c / nx) * cell + (cell - 1) / 2.0
                picks.add(r.filter { !deepAt(it.i, it.j) && !ground.rooms(g.wx(it.j), g.wy(it.i)) }.minByOrNull { hypot(it.i - cx, it.j - cy) } ?: return false)
            }
            // the leg cut at each pick: the shape's own vertices between, the pick's vertex at each end
            val cuts = ArrayList<List<DoubleArray>>()
            var prevQ = 0; var prev = pa
            for (pk in picks + listOf(Spot(w.shape.size - 2, -1, -1, nCell[m]))) {
                val end = if (pk.i < 0) pb else doubleArrayOf(g.wx(pk.j), g.wy(pk.i))
                val part = mutableListOf(prev)
                for (q in prevQ + 1..pk.q) { val v = w.shape[q]; if (hypot(v[0] - part.last()[0], v[1] - part.last()[1]) > 1e-6) part.add(v) }
                if (hypot(end[0] - part.last()[0], end[1] - part.last()[1]) > 1e-6) part.add(end)
                if (part.size < 2 || innerOf(part) == null || !fine(part)) return false
                cuts.add(part); prevQ = pk.q; prev = end
            }
            // commit: the nodes in the cells between, the edges each from the cell whose forward neighbour the next is
            val chainN = ArrayList<Int>(); chainN.add(n)
            for (pk in picks) {
                val k = at[pk.c].takeIf { it >= 0 } ?: nCell.size.also { at[pk.c] = it; nCell.add(pk.c); nI.add(pk.i); nJ.add(pk.j); nF.add(0) }
                nI[k] = pk.i; nJ[k] = pk.j
                val x = g.wx(pk.j); val y = g.wy(pk.i)
                nF[k] = (if (onDeck(pk.i, pk.j)) ShippedNet.R_FLOOR else 0) or (if (near(x, y, 3.0, ground.rooms)) ShippedNet.R_BUILDING else 0)
                chainN.add(k)
            }
            chainN.add(m)
            for ((q, part) in cuts.withIndex()) {
                val u = chainN[q]; val v = chainN[q + 1]; val cu = nCell[u]; val cv = nCell[v]
                val fwd = ShippedNet.DIRS.any { it[0] == cv % nx - cu % nx && it[1] == cv / nx - cu / nx }
                if (fwd) addEdge(u, v, Walk(part, part, w.water)) else addEdge(v, u, Walk(part.reversed(), part.reversed(), w.water))
            }
            st.repaired2++
            return true
        }
        // the candidates: every pair of nodes 2 to CHAIN_CELLS cells apart in two pieces, nearest first; each pair of pieces tried
        // [CHAIN_TRIES] times at most
        val triedPair = HashMap<Long, Int>()
        val rr = (CHAIN_CELLS * cell).toDouble()
        val cands = ArrayList<Triple<Double, Int, Int>>()
        for (n in nCell.indices.toList()) {
            val c = nCell[n]; val ci0 = c % nx; val cj0 = c / nx
            for (cj in cj0..cj0 + CHAIN_CELLS) for (ci in ci0 - CHAIN_CELLS..ci0 + CHAIN_CELLS) {
                if (ci !in 0 until nx || cj !in 0 until ny || max(abs(ci - ci0), abs(cj - cj0)) < 2 || (cj == cj0 && ci < ci0)) continue
                val m = at[cj * nx + ci]; if (m < 0 || find(m) == find(n)) continue
                val d = hypot((nI[m] - nI[n]).toDouble(), (nJ[m] - nJ[n]).toDouble())
                if (d <= rr) cands.add(Triple(d, n, m))
            }
        }
        cands.sortBy { it.first }
        for ((_, n, m) in cands) {
            val ra = find(n); val rb = find(m); if (ra == rb) continue
            val key = min(ra, rb).toLong() * 10_000_000 + max(ra, rb)
            val t = triedPair.getOrDefault(key, 0); if (t >= CHAIN_TRIES) continue
            triedPair[key] = t + 1
            chain(n, m)
        }
        // (the arrays grown with the new nodes)
        val keepAll = BooleanArray(eA.size) { it >= firstRepair || keep[it] }
        // the repairs checked as every edge is (no re-walk: a failing repair is dropped)
        if (check != null && eA.size > firstRepair) {
            val bad = check(shapes.subList(firstRepair, eA.size)).map { it + firstRepair }
            for (e in bad) keepAll[e] = false
            st.repairDropped = bad.size
        }
        val par2 = IntArray(nCell.size) { it }
        fun find2(x0: Int): Int { var x = x0; while (par2[x] != x) { par2[x] = par2[par2[x]]; x = par2[x] }; return x }
        val deg2 = IntArray(nCell.size)
        for (e in eA.indices) if (keepAll[e]) { par2[find2(eA[e])] = find2(eB[e]); deg2[eA[e]]++; deg2[eB[e]]++ }
        st.compAfter = nCell.indices.filter { deg2[it] > 0 }.map { find2(it) }.toSet().size
        // the lattice's own walkable regions (its 8 steps, no wall, off deep water) that hold a node: one roadmap piece each at best
        run {
            val lab = IntArray(g.nx * g.ny) { -1 }; var nr = 0
            val stack = IntArray(g.nx * g.ny)
            for (k in nCell.indices) { if (deg2[k] == 0) continue
                val s0 = nJ[k] * g.nx + nI[k]; if (lab[s0] >= 0) continue
                var sp = 0; stack[sp++] = s0; lab[s0] = nr
                while (sp > 0) { val v = stack[--sp]; val i = v % g.nx; val j = v / g.nx
                    for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                        val a2 = i + di; val b2 = j + dj; if (a2 !in 0 until g.nx || b2 !in 0 until g.ny) continue
                        val u = b2 * g.nx + a2; if (lab[u] >= 0 || g.step(i, j, di, dj) == Climb.WALL || deepAt(a2, b2)) continue
                        lab[u] = nr; stack[sp++] = u } }
                nr++ }
            st.regions = nr
        }
        // ---- THE ROADMAP: the nodes with an edge in cell order, the edges in their node a's order, DIRS order within
        val keepN = nCell.indices.filter { deg2[it] > 0 }.sortedBy { nCell[it] }
        st.isolated = nCell.size - keepN.size
        val remap = IntArray(nCell.size) { -1 }; for ((q, n) in keepN.withIndex()) remap[n] = q
        fun dirOf(e: Int): Int { val ca = nCell[eA[e]]; val cb = nCell[eB[e]]; val di = cb % nx - ca % nx; val dj = cb / nx - ca / nx
            return ShippedNet.DIRS.indexOfFirst { it[0] == di && it[1] == dj }.also { require(it >= 0) { "edge $e no forward neighbour" } } }
        val keepE = eA.indices.filter { keepAll[it] }.sortedWith(compareBy({ remap[eA[it]] }, { dirOf(it) }))
        for (e in keepE) {
            val sm = sample(shapes[e]); samples.add(sm); eF.add(flags(shapes[e], sm))
            st.kept++; if (eP[e]!!.isNotEmpty()) st.walked++
        }
        val kept = keepN.size
        val rm = ShippedNet.Roadmap(g.gr0, g.gc0, cell, nx, ny, keepN.map { nCell[it] }.toIntArray(), keepN.map { g.gr0 + nJ[it] }.toIntArray(),
            keepN.map { g.gc0 + nI[it] }.toIntArray(), keepN.map { nF[it] }.toIntArray(),
            keepE.map { remap[eA[it]] }.toIntArray(), keepE.map { remap[eB[it]] }.toIntArray(), eF.toIntArray(),
            keepE.map { eP[it]!!.takeIf { p -> p.isNotEmpty() } }.toTypedArray())
        st.nodes = kept; st.ms = (System.nanoTime() - t0) / 1_000_000
        log("ROADMAP $nx x $ny cells of $cell steps (${"%.1f".format(cell * Climb.STEP)} yd): ${rm.nodes} nodes, ${rm.edges} edges; $st")
        return Built(rm, samples, st)
    }

    /** a faction's danger a byte an edge: the mean of [danger] (the danger grid's units: DangerInputs.Danger.mobs, or its .danger with
     *  the calm costs in) along it, [ShippedNet.ROADMAP_DANGER_SCALE] a unit (Section.roadmapDanger) */
    fun dangerBytes(b: Built, danger: (Double, Double) -> Float): ByteArray = ByteArray(b.rm.edges) { e ->
        val s = b.samples[e]; val m = s.sumOf { danger(it[0], it[1]).toDouble() } / s.size
        min(255, Math.round(m * ShippedNet.ROADMAP_DANGER_SCALE).toInt()).coerceAtLeast(0).toByte() }

    /** a route's letter for py/wetcheck.py's readers ([A-Z]+): R, then the index in base 26 */
    fun letter(i: Int): String { var v = i; val sb = StringBuilder(); do { sb.append('A' + v % 26); v /= 26 } while (v > 0); return "R" + sb.reverse() }
    private fun routes(shapes: List<List<DoubleArray>>): JSONArray = JSONArray().also { a ->
        for ((e, sh) in shapes.withIndex()) a.put(JSONObject().put("letter", letter(e)).put("reached", true).put("pts", JSONArray(sh.map { p ->
            JSONArray(listOf(Math.round(p[0] * 1000) / 1000.0, Math.round(p[1] * 1000) / 1000.0)) }))) }

    /** each edge as a route ({letter, pts}: world x, y), for py/segcheck.py and py/wetcheck.py (a levels_routes_<tag>.json's "routes") */
    fun routesJson(rm: ShippedNet.Roadmap): JSONArray = routes((0 until rm.edges).map { rm.points(it) })

    /**
     * py/segcheck.py and py/wetcheck.py as [build]'s check: the edges written to [dir]/levels_routes_<tag>.json (the caller puts the
     * zone's floors_ / holes_ / layers_ files beside it), run from [py] with ZDIR [zdir]; the edges with a flagged segment or a swim
     * (deeper than 2.2 yd at all, or deeper than the ford for more than 6 yd on end)
     */
    fun pyCheck(py: java.io.File, dir: java.io.File, tag: String, zdir: String, work: java.io.File, log: (String) -> Unit = {}): (List<List<DoubleArray>>) -> Set<Int> = { shapes ->
        val f = java.io.File(dir, "levels_routes_$tag.json"); f.writeText(JSONObject().put("zone", tag).put("routes", routes(shapes)).toString())
        val idx = shapes.indices.associateBy { letter(it) }
        val bad = HashSet<Int>()
        val seg = ParityCompare.runPy(py, "segcheck.py", f, zdir, work, 120)
        val last = seg.lines().lastOrNull { "crossing a" in it } ?: error("segcheck said nothing: ${seg.takeLast(300)}")
        for (m in Regex("(R[A-Z]+)\\d+-R[A-Z]+\\d+").findAll(last.substringAfter(":"))) idx[m.groupValues[1]]?.let { bad.add(it) }
        val nSeg = bad.size
        val wet = ParityCompare.runPy(py, "wetcheck.py", f, zdir, work, 120)
        var read = 0
        for (line in wet.lines()) {
            val m = Regex("WET (R[A-Z]+): deepest ([\\d.]+) yd.*?, (\\d+) yd deeper than .*?longest wade (\\d+) yd.*?, (\\d+) yd deeper than").find(line) ?: continue
            read++
            val e = idx[m.groupValues[1]] ?: continue
            if (m.groupValues[5].toInt() > 0 || m.groupValues[4].toInt() > SafePath.NARROW_YD) bad.add(e)
        }
        if (read < shapes.size) error("wetcheck read $read of ${shapes.size} routes: ${wet.takeLast(300)}")
        log("ROADMAP CHECK ${shapes.size} edges: segcheck flags $nSeg, wetcheck ${bad.size - nSeg} more")
        f.delete()
        bad
    }
}
