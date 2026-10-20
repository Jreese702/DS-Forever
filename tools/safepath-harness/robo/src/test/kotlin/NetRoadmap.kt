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
                var room: Int = 0, var wade: Int = 0, var isolated: Int = 0, var strict: Int = 0, var walked: Int = 0, var ms: Long = 0) {
        override fun toString() = "$cells zone cells, $nodes nodes ($isolated without an edge dropped), $kept of $tried neighbour pairs joined " +
            "($walked walked; not joined: no leg $noLeg, detour $detour, room $room, wade $wade; $strict by the no-wade retry), $ms ms"
    }

    /** the roadmap and each edge's points a yard apart (for [dangerBytes]) */
    class Built(val rm: ShippedNet.Roadmap, val samples: List<List<DoubleArray>>, val stats: Stats)

    /** THE ENTRY POINT: the roadmap over [ground] with cells of [cell] lattice steps (S yd = cell x Climb.STEP) */
    fun build(ground: Ground, cell: Int = CELL, log: (String) -> Unit = {}): Built {
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
        for (n in nCell.indices) {
            val c = nCell[n]; val pa = doubleArrayOf(g.wx(nJ[n]), g.wy(nI[n]))
            for (d in ShippedNet.DIRS) {
                val ci = c % nx + d[0]; val cj = c / nx + d[1]
                if (ci !in 0 until nx || cj !in 0 until ny) continue
                val m = at[cj * nx + ci]; if (m < 0) continue
                st.tried++
                val pb = doubleArrayOf(g.wx(nJ[m]), g.wy(nI[m])); val line = hypot(pb[0] - pa[0], pb[1] - pa[1])
                fun legOn(water: (Double, Double) -> Boolean) = Climb.route(g, pa, pb, ground.calm, ground.k, water, emptyList(), true, limits)
                    ?.takeIf { it.reached && it.pts.size >= 2 && hypot(it.pts.first()[0] - pa[0], it.pts.first()[1] - pa[1]) < 1e-6 &&
                        hypot(it.pts.last()[0] - pb[0], it.pts.last()[1] - pb[1]) < 1e-6 }
                var water: (Double, Double) -> Boolean = ground.deep
                var leg = legOn(water)
                if (leg == null) { st.noLeg++; continue }
                if (longestWade(leg.pts) > SafePath.NARROW_YD) {
                    val sw = strictWater
                    val l2 = sw?.let { legOn(it) }
                    if (l2 == null || longestWade(l2.pts) > SafePath.NARROW_YD) { st.wade++; continue }
                    leg = l2; water = sw; st.strict++
                }
                var pts = leg.pts.map { doubleArrayOf(it[0], it[1]) }
                if (pts.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }.sum() > DETOUR * line + 1.0) { st.detour++; continue }
                if (pts.zipWithNext().any { (p, q) -> crossesRoom(p, q) }) { st.room++; continue }
                pts = simplify(pts, water)
                // the inner points are lattice vertices (the leg's bends), the ends the nodes; and THE GATE'S OWN RULE on every final segment
                val inner = IntArray((pts.size - 2) * 2); var exact = true
                for (q in 1 until pts.size - 1) { val gr = ShippedNet.gr(pts[q][0]); val gc = ShippedNet.gc(pts[q][1])
                    if (abs(ShippedNet.vx(gr) - pts[q][0]) > 1e-6 || abs(ShippedNet.vy(gc) - pts[q][1]) > 1e-6) exact = false
                    inner[2 * (q - 1)] = gr; inner[2 * (q - 1) + 1] = gc }
                val shape = listOf(pa) + (0 until inner.size / 2).map { q -> doubleArrayOf(ShippedNet.vx(inner[2 * q]), ShippedNet.vy(inner[2 * q + 1])) } + listOf(pb)
                if (!exact || shape.zipWithNext().any { (p, q) -> !Climb.walkable(g, p, q, ground.deep) || crossesRoom(p, q) }) { st.noLeg++; continue }
                val sm = sample(shape)
                eA.add(n); eB.add(m); eP.add(inner.takeIf { it.isNotEmpty() }); eF.add(flags(shape, sm)); samples.add(sm)
                st.kept++; if (inner.isNotEmpty()) st.walked++
            }
        }
        // nodes no edge reaches: dropped (an island of one vertex, a ledge); the order kept (cell order, node a's order)
        val used = BooleanArray(nCell.size); for (e in eA.indices) { used[eA[e]] = true; used[eB[e]] = true }
        val remap = IntArray(nCell.size) { -1 }; var kept = 0
        for (q in nCell.indices) if (used[q]) remap[q] = kept++ else st.isolated++
        val keepN = nCell.indices.filter { used[it] }
        val rm = ShippedNet.Roadmap(g.gr0, g.gc0, cell, nx, ny, keepN.map { nCell[it] }.toIntArray(), keepN.map { g.gr0 + nJ[it] }.toIntArray(),
            keepN.map { g.gc0 + nI[it] }.toIntArray(), keepN.map { nF[it] }.toIntArray(),
            eA.map { remap[it] }.toIntArray(), eB.map { remap[it] }.toIntArray(), eF.toIntArray(), eP.toTypedArray())
        st.nodes = kept; st.ms = (System.nanoTime() - t0) / 1_000_000
        log("ROADMAP $nx x $ny cells of $cell steps (${"%.1f".format(cell * Climb.STEP)} yd): ${rm.nodes} nodes, ${rm.edges} edges; $st")
        return Built(rm, samples, st)
    }

    /** a faction's danger a byte an edge: the mean of [danger] (the danger grid's units: DangerInputs.Danger.mobs, or its .danger with
     *  the calm costs in) along it, [ShippedNet.ROADMAP_DANGER_SCALE] a unit (Section.roadmapDanger) */
    fun dangerBytes(b: Built, danger: (Double, Double) -> Float): ByteArray = ByteArray(b.rm.edges) { e ->
        val s = b.samples[e]; val m = s.sumOf { danger(it[0], it[1]).toDouble() } / s.size
        min(255, Math.round(m * ShippedNet.ROADMAP_DANGER_SCALE).toInt()).coerceAtLeast(0).toByte() }

    /** each edge as a route ({letter, pts}: world x, y), for py/segcheck.py and py/wetcheck.py (a levels_routes_<tag>.json's "routes") */
    fun routesJson(rm: ShippedNet.Roadmap): JSONArray = JSONArray().also { a ->
        for (e in 0 until rm.edges) a.put(JSONObject().put("letter", "E$e").put("pts", JSONArray(rm.points(e).map { p ->
            JSONArray(listOf(Math.round(p[0] * 1000) / 1000.0, Math.round(p[1] * 1000) / 1000.0)) }))) }
}
