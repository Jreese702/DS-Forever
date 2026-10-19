import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.SafePath
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * THE ROADMAP COVERAGE LAYER (NET2; the user, 2026-10-19: "Ship our path network"; DEVICE_INTEGRATION_PLAN.md SHIPPED NETWORK): a
 * graph over a zone's walkable ground, so the device - with no lattice - attaches within a short leg anywhere on the zone and routes
 * as the harness does. The parity test's failures (33 goals over 200 yd from any way; the 25 yd ground grid's attach legs through
 * walls) are what it is for.
 *
 * - NODES on a regular grid of cells [Roadmap.cellV] lattice steps wide (S yd ~ cellV x Climb.STEP), each snapped to the best
 *   walkable LATTICE VERTEX near its cell's centre: never deep water or lava (the water function's wall), never a wall-locked vertex,
 *   never inside a building's room; a walkway's floor (the lattice's deck: a bridge, a pier) is layer 1, the ground layer 0.
 * - EDGES to the neighbouring cells' nodes (east, south, south-east, south-west: within 1.5 S), kept only when the harness's own
 *   lattice leg ([Climb.route] with the water a wall, edge clearance on, the wade and room costs) reaches the other node within
 *   [DETOUR] x the straight distance, wades no more than [SafePath.NARROW_YD] on end (else routed again with no wading), crosses no
 *   room, and every final segment passes [Climb.walkable]. Its points are simplified (Douglas-Peucker within [SIMPLIFY_YD], each
 *   kept chord walkable) and stay LATTICE VERTICES, so the file stores integers and loses nothing.
 * - Per edge: its COST TIER (the lattice's own cost per yard along the walked points - the step classes' COST, edge clearance, k x
 *   the wade's and rooms' calm cost - as (ratio - 1) in tenths), FLAGS ([F_WADE], [F_FLOOR], [F_STEEP], [F_LIP]) and per faction
 *   a DANGER TIER (the mean mobs' danger along it, DangerInputs.Danger.mobs, in quarters: 0..16 = 0..DangerGrid.CAP).
 *   An edge's length is its points' own (never stored).
 * - NO HEIGHTS, no geometry, no file ids: vertex indices of the lattice (x = ORG - gr x STEP, y = ORG - gc x STEP).
 *
 * The entry point: [build] (one zone's geometry, shared by both factions; danger tiers per faction), [encode] / [decode] (the
 * compact gzip binary, "RDM1"), [routesJson] (each edge as a route, for py/segcheck.py and py/wetcheck.py).
 */
object NetRoadmap {
    const val VERSION = 1
    const val MAGIC = 0x52444D31          // "RDM1"
    /** an edge's leg longer than this x the straight line between its nodes is a way round: dropped (other cells join round it) */
    const val DETOUR = 2.0
    const val SIMPLIFY_YD = 1.0
    /** a node needs this many of its 8 lattice steps open (no wall, no deep water beyond) */
    const val MIN_OPEN = 6
    /** the A*'s window: narrowed to Climb.MIN_MARGIN_YD round the two ends (an edge is short) */
    const val LEG_WINDOW = 40_000

    const val F_WADE = 1        // the walked points wade (SafePath.Water.wading: a narrow stream)
    const val F_FLOOR = 2       // a vertex on a walkway's floor (the lattice's deck: a bridge, a pier)
    const val F_STEEP = 4       // a STEEP step (35-45 degrees, or a short 48) along it
    const val F_LIP = 8         // a vertex within 3 yd of a drop (edge clearance)
    /** the four neighbour cells an edge goes to (cell column, cell row): east, south, south-east, south-west */
    val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(-1, 1))

    /**
     * the zone's ground as the harness has it: the lattice [g]; [deep] deep water / lava (a wall: ZoneInputs.deep); [overFord] deeper
     * than the ford (SafeRoute.overFord: the strict retry's water; null = no retry); [wading] a narrow stream waded; [rooms] a
     * building's room footprint; [calm] the wade's and rooms' cost (DangerInputs.Danger.calm) at weight [k] (DangerInputs.Danger.k);
     * [inZone] the zone's own ground (its uiMap's areas).
     */
    class Ground(val g: Climb.Grid, val deep: (Double, Double) -> Boolean, val overFord: ((Double, Double) -> Boolean)?,
                 val wading: (Double, Double) -> Boolean, val rooms: (Double, Double) -> Boolean, val calm: (Double, Double) -> Float,
                 val k: Double, val inZone: (Double, Double) -> Boolean)

    /** a node: its cell (column [ci], row [cj]), its lattice vertex ([i], [j] of the grid), [layer] 0 ground / 1 a walkway's floor */
    class Node(val ci: Int, val cj: Int, val i: Int, val j: Int, val layer: Int)
    /** an edge from node [a] to [b] (indices) in direction [dir] of [DIRS]; [pts] the walked lattice vertices between them (grid
     *  i, j pairs, the ends left out); [cost] tier; [flags]; [danger] a tier per faction (the order of [Roadmap.factions]) */
    class Edge(val a: Int, val b: Int, val dir: Int, val pts: IntArray, val cost: Int, val flags: Int, val danger: IntArray)

    class Roadmap(val map: Int, val cellV: Int, val gr0: Int, val gc0: Int, val ncx: Int, val ncy: Int, val factions: IntArray,
                  val nodes: List<Node>, val edges: List<Edge>) {
        val spacingYd get() = cellV * Climb.STEP
        fun wx(n: Node) = ORG - (gr0 + n.j) * Climb.STEP
        fun wy(n: Node) = ORG - (gc0 + n.i) * Climb.STEP
        /** an edge's world points, ends included */
        fun points(e: Edge): List<DoubleArray> {
            val a = nodes[e.a]; val b = nodes[e.b]
            val out = ArrayList<DoubleArray>(e.pts.size / 2 + 2)
            out.add(doubleArrayOf(wx(a), wy(a)))
            for (q in 0 until e.pts.size / 2) out.add(doubleArrayOf(ORG - (gr0 + e.pts[2 * q + 1]) * Climb.STEP, ORG - (gc0 + e.pts[2 * q]) * Climb.STEP))
            out.add(doubleArrayOf(wx(b), wy(b)))
            return out
        }
        fun yd(e: Edge) = points(e).zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }.sum()
    }

    private const val ORG = 32.0 * com.jrees.wowcompanion.MapHarvest.ADT_YD     // Climb's own ORG

    /** what [build] did, for the logs */
    class Stats(var cells: Int = 0, var nodes: Int = 0, var tried: Int = 0, var kept: Int = 0, var noLeg: Int = 0, var detour: Int = 0,
                var room: Int = 0, var wade: Int = 0, var isolated: Int = 0, var strict: Int = 0, var ms: Long = 0) {
        override fun toString() = "$cells cells, $nodes nodes ($isolated isolated dropped), $kept of $tried edges kept " +
            "(no leg $noLeg, detour $detour, room $room, wade $wade; $strict by the no-wade retry), $ms ms"
    }

    /**
     * THE ENTRY POINT: the roadmap of map [map] over [ground] at [spacingYd] (rounded to whole lattice steps), the danger tiers for each
     * faction of [danger] (faction -> the mobs' danger at world x, y: DangerInputs.Danger.mobs). [stats] filled when given.
     */
    fun build(map: Int, ground: Ground, danger: Map<Int, (Double, Double) -> Float>, spacingYd: Double, stats: Stats? = null): Roadmap {
        val t0 = System.nanoTime()
        val g = ground.g
        val cellV = max(2, (spacingYd / Climb.STEP).roundToInt())
        val ncx = g.nx / cellV; val ncy = g.ny / cellV
        val st = stats ?: Stats()
        // per-vertex caches (the water and rooms asked once a vertex)
        val deepV = ByteArray(g.nx * g.ny)
        fun deepAt(i: Int, j: Int): Boolean {
            if (i !in 0 until g.nx || j !in 0 until g.ny) return true
            val q = j * g.nx + i
            if (deepV[q].toInt() == 0) deepV[q] = if (ground.deep(g.wx(j), g.wy(i))) 2 else 1
            return deepV[q].toInt() == 2
        }
        val edgeV = FloatArray(g.nx * g.ny) { Float.NaN }
        val er = kotlin.math.ceil(Climb.EDGE_YD / Climb.STEP).toInt()
        fun clearanceYd(i: Int, j: Int): Double {
            var d = g.dropYd(i, j)
            for (b in j - er..j + er) for (a in i - er..i + er) {
                if ((a == i && b == j) || !deepAt(a, b)) continue
                d = min(d, max(0.0, hypot((a - i).toDouble(), (b - j).toDouble()) * Climb.STEP - Climb.STEP / 2))
            }
            return d
        }
        // EDGE CLEARANCE as Climb.route's edgeOf: a walkway's own floor none
        fun edgeAt(i: Int, j: Int): Double {
            val q = j * g.nx + i
            if (edgeV[q].isNaN()) edgeV[q] = (if (g.deckZ?.get(q)?.isNaN() == false) 0.0 else Climb.edgeCost(clearanceYd(i, j))).toFloat()
            return edgeV[q].toDouble()
        }
        fun onDeck(i: Int, j: Int) = g.deckZ?.get(j * g.nx + i)?.isNaN() == false

        // ---- the nodes
        val nodeAt = IntArray(ncx * ncy) { -1 }
        val nodes = ArrayList<Node>()
        val r = 0.45 * cellV; val ri = r.toInt()
        val offs = ArrayList<IntArray>()
        for (dj in -ri..ri) for (di in -ri..ri) if (di * di + dj * dj <= r * r) offs.add(intArrayOf(di, dj))
        offs.sortBy { it[0] * it[0] + it[1] * it[1] }
        for (cj in 0 until ncy) for (ci in 0 until ncx) {
            val i0 = ci * cellV + cellV / 2; val j0 = cj * cellV + cellV / 2
            // the cell is the zone's when its middle or a corner is
            val h = cellV / 2
            if (listOf(0 to 0, -h to -h, h to -h, -h to h, h to h).none { (a, b) -> ground.inZone(g.wx(j0 + b), g.wy(i0 + a)) }) continue
            st.cells++
            var best = -1; var bestScore = Double.MAX_VALUE
            for ((oq, o) in offs.withIndex()) {
                val i = i0 + o[0]; val j = j0 + o[1]
                if (i !in 1 until g.nx - 1 || j !in 1 until g.ny - 1) continue
                val dist = hypot(o[0].toDouble(), o[1].toDouble()) * Climb.STEP
                if (dist >= bestScore) break                         // sorted by distance: nothing farther can score better
                if (deepAt(i, j)) continue
                val x = g.wx(j); val y = g.wy(i)
                if (ground.rooms(x, y)) continue
                var open = 0
                for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                    if (g.step(i, j, di, dj) != Climb.WALL && !deepAt(i + di, j + dj)) open++ }
                if (open < MIN_OPEN) continue
                val score = dist + 6.0 * edgeAt(i, j) + (8 - open) * 2.0 + (if (ground.wading(x, y)) 10.0 else 0.0)
                if (score < bestScore) { bestScore = score; best = oq }
            }
            if (best < 0) continue
            val i = i0 + offs[best][0]; val j = j0 + offs[best][1]
            nodeAt[cj * ncx + ci] = nodes.size
            nodes.add(Node(ci, cj, i, j, if (onDeck(i, j)) 1 else 0))
        }

        // ---- the edges
        val limits = Climb.Limits(LEG_WINDOW)
        val strictWater: ((Double, Double) -> Boolean)? = ground.overFord?.let { of -> { x, y -> ground.deep(x, y) || of(x, y) } }
        fun vx(n: Node) = doubleArrayOf(g.wx(n.j), g.wy(n.i))
        fun crossesRoom(p: DoubleArray, q: DoubleArray): Boolean {
            val n = max(1, hypot(q[0] - p[0], q[1] - p[1]).toInt())
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
        // Douglas-Peucker within SIMPLIFY_YD, a chord kept only when it is walkable itself
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
            return w.filterIndexed { q, _ -> keep[q] }
        }
        // the lattice's own cost along the points (Climb.route's lineCost: each step's class, edge clearance, k x calm; no danger fade)
        fun costOf(w: List<DoubleArray>): Pair<Double, Int> {
            var cost = 0.0; var len = 0.0; var flags = 0
            for ((pa, pb) in w.zipWithNext()) {
                val s = g.at(pa[0], pa[1]); val e = g.at(pb[0], pb[1])
                val n = max(abs(e[0] - s[0]), abs(e[1] - s[1]))
                var ci = s[0]; var cj = s[1]
                for (q in 1..n) {
                    val ni = s[0] + Math.round((e[0] - s[0]).toDouble() * q / n).toInt(); val nj = s[1] + Math.round((e[1] - s[1]).toDouble() * q / n).toInt()
                    val di = Integer.signum(ni - ci); val dj = Integer.signum(nj - cj)
                    if (di == 0 && dj == 0) continue
                    val cl = g.step(ci, cj, di, dj).coerceAtMost(Climb.STEEP)
                    if (cl == Climb.STEEP) flags = flags or F_STEEP
                    val sl = if (di != 0 && dj != 0) Climb.STEP * 1.41421356 else Climb.STEP
                    val x = g.wx(nj); val y = g.wy(ni)
                    cost += sl * (Climb.COST[cl] + edgeAt(ni, nj) + ground.k * ground.calm(x, y)); len += sl
                    if (onDeck(ni, nj)) flags = flags or F_FLOOR
                    if (g.dropYd(ni, nj) <= 3.0) flags = flags or F_LIP
                    ci = ni; cj = nj
                }
            }
            for ((pa, pb) in w.zipWithNext()) {
                val n = max(1, hypot(pb[0] - pa[0], pb[1] - pa[1]).toInt())
                for (q in 0..n) if (ground.wading(pa[0] + (pb[0] - pa[0]) * q / n, pa[1] + (pb[1] - pa[1]) * q / n)) { flags = flags or F_WADE; break }
            }
            val ratio = if (len > 0) cost / len else 1.0
            return ratio to flags
        }
        val factions = danger.keys.sorted().toIntArray()
        fun dangerOf(w: List<DoubleArray>, f: (Double, Double) -> Float): Int {
            var sum = 0.0; var len = 0.0
            for ((pa, pb) in w.zipWithNext()) {
                val L = hypot(pb[0] - pa[0], pb[1] - pa[1]); val n = max(1, (L / 2).toInt())
                for (q in 0 until n) { val t = (q + 0.5) / n; sum += f(pa[0] + (pb[0] - pa[0]) * t, pa[1] + (pb[1] - pa[1]) * t) * (L / n) }
                len += L
            }
            return if (len <= 0) 0 else (sum / len * 4).roundToInt().coerceIn(0, 16)
        }
        val edges = ArrayList<Edge>()
        for ((ai, a) in nodes.withIndex()) for ((d, dir) in DIRS.withIndex()) {
            val ci = a.ci + dir[0]; val cj = a.cj + dir[1]
            if (ci !in 0 until ncx || cj !in 0 until ncy) continue
            val bi = nodeAt[cj * ncx + ci]; if (bi < 0) continue
            val b = nodes[bi]; st.tried++
            val pa = vx(a); val pb = vx(b); val straight = hypot(pb[0] - pa[0], pb[1] - pa[1])
            fun legOn(water: (Double, Double) -> Boolean) = Climb.route(g, pa, pb, ground.calm, ground.k, water, emptyList(), true, limits)
                ?.takeIf { it.reached && it.pts.size >= 2 && hypot(it.pts.last()[0] - pb[0], it.pts.last()[1] - pb[1]) < 0.01 }
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
            if (SafeRoute_length(pts) > DETOUR * straight + 1.0) { st.detour++; continue }
            if (pts.zipWithNext().any { (p, q) -> crossesRoom(p, q) }) { st.room++; continue }
            pts = simplify(pts, water)
            // THE GATE'S OWN RULE on every final segment (no wall, no deep water)
            if (pts.zipWithNext().any { (p, q) -> !Climb.walkable(g, p, q, ground.deep) }) { st.noLeg++; continue }
            val inner = IntArray((pts.size - 2) * 2)
            for (q in 1 until pts.size - 1) { val v = g.at(pts[q][0], pts[q][1]); inner[2 * (q - 1)] = v[0]; inner[2 * (q - 1) + 1] = v[1] }
            val (ratio, flags) = costOf(pts)
            val tier = ((ratio - 1.0) * 10).roundToInt().coerceIn(0, 255)
            edges.add(Edge(ai, bi, d, inner, tier, flags, IntArray(factions.size) { dangerOf(pts, danger[factions[it]]!!) }))
            st.kept++
        }
        // nodes no edge reaches: dropped (an island of one vertex, a ledge)
        val used = BooleanArray(nodes.size); for (e in edges) { used[e.a] = true; used[e.b] = true }
        val remap = IntArray(nodes.size) { -1 }; val kept = ArrayList<Node>()
        for ((q, n) in nodes.withIndex()) if (used[q]) { remap[q] = kept.size; kept.add(n) } else st.isolated++
        val edges2 = edges.map { Edge(remap[it.a], remap[it.b], it.dir, it.pts, it.cost, it.flags, it.danger) }
        st.nodes = kept.size; st.ms = (System.nanoTime() - t0) / 1_000_000
        return Roadmap(map, cellV, g.gr0, g.gc0, ncx, ncy, factions, kept, edges2)
    }

    private fun SafeRoute_length(w: List<DoubleArray>) = w.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }.sum()

    // ------------------------------------------------------------------ the file (RDM1, gzip)

    private fun DataOutputStream.varint(v0: Int) { var v = v0; while (v and 0x7F.inv() != 0) { writeByte((v and 0x7F) or 0x80); v = v ushr 7 }; writeByte(v) }
    private fun DataOutputStream.zz(v: Int) = varint((v shl 1) xor (v shr 31))
    private fun DataInputStream.varint(): Int { var v = 0; var s = 0; while (true) { val b = readUnsignedByte(); v = v or ((b and 0x7F) shl s); if (b and 0x80 == 0) return v; s += 7 } }
    private fun DataInputStream.zz(): Int { val v = varint(); return (v ushr 1) xor -(v and 1) }

    /**
     * RDM1: gzip (MTIME 0) of: MAGIC, VERSION, map, cellV, gr0, gc0, ncx, ncy (varints; gr0 / gc0 zigzag), the factions (count, ids);
     * the nodes in cell order (count; each: the cell index's delta from the last node's, its vertex offset from the cell's middle vertex
     * di, dj (zigzag), its layer); then column streams - each node's 4-bit mask of its edges (DIRS order; an edge's other end is the
     * node of that neighbour cell), each edge's inner point count, its inner points (the delta from the point before, zigzag, from its
     * first node), its cost tier, its flags, and a danger tier per faction (a stream each). No heights.
     */
    fun encode(rm: Roadmap): ByteArray {
        val bo = ByteArrayOutputStream()
        val gz = object : GZIPOutputStream(bo) { init { def.setLevel(9) } }
        DataOutputStream(gz).use { o ->
            o.writeInt(MAGIC); o.varint(VERSION); o.varint(rm.map); o.varint(rm.cellV); o.zz(rm.gr0); o.zz(rm.gc0); o.varint(rm.ncx); o.varint(rm.ncy)
            o.varint(rm.factions.size); rm.factions.forEach { o.varint(it) }
            o.varint(rm.nodes.size)
            var last = 0
            for (n in rm.nodes) { val c = n.cj * rm.ncx + n.ci; o.varint(c - last); last = c
                o.zz(n.i - (n.ci * rm.cellV + rm.cellV / 2)); o.zz(n.j - (n.cj * rm.cellV + rm.cellV / 2)); o.writeByte(n.layer) }
            val byA = rm.edges.sortedWith(compareBy({ it.a }, { it.dir }))
            val mask = IntArray(rm.nodes.size); for (e in byA) mask[e.a] = mask[e.a] or (1 shl e.dir)
            for (q in rm.nodes.indices step 2) o.writeByte(mask[q] or ((if (q + 1 < mask.size) mask[q + 1] else 0) shl 4))
            for (e in byA) o.varint(e.pts.size / 2)
            for (e in byA) { val a = rm.nodes[e.a]; var pi = a.i; var pj = a.j
                for (q in 0 until e.pts.size / 2) { o.zz(e.pts[2 * q] - pi); o.zz(e.pts[2 * q + 1] - pj); pi = e.pts[2 * q]; pj = e.pts[2 * q + 1] } }
            for (e in byA) o.writeByte(e.cost)
            for (e in byA) o.writeByte(e.flags)
            for (f in rm.factions.indices) for (e in byA) o.writeByte(e.danger[f])
        }
        // gzip MTIME is 0 already (java's GZIPOutputStream writes none): byte-identical rewrites
        return bo.toByteArray()
    }

    fun decode(bytes: ByteArray): Roadmap? = runCatching {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { i ->
            if (i.readInt() != MAGIC) return null
            if (i.varint() != VERSION) return null
            val map = i.varint(); val cellV = i.varint(); val gr0 = i.zz(); val gc0 = i.zz(); val ncx = i.varint(); val ncy = i.varint()
            val factions = IntArray(i.varint()) { i.varint() }
            val nn = i.varint(); val nodes = ArrayList<Node>(nn); val at = HashMap<Int, Int>(nn * 2)
            var c = 0
            repeat(nn) { c += i.varint(); val ci = c % ncx; val cj = c / ncx
                val di = i.zz(); val dj = i.zz(); val layer = i.readUnsignedByte()
                at[c] = nodes.size; nodes.add(Node(ci, cj, ci * cellV + cellV / 2 + di, cj * cellV + cellV / 2 + dj, layer)) }
            val mask = IntArray(nn); for (q in 0 until nn step 2) { val b = i.readUnsignedByte(); mask[q] = b and 15; if (q + 1 < nn) mask[q + 1] = b shr 4 }
            val ends = ArrayList<IntArray>()
            for (a in 0 until nn) for (d in DIRS.indices) if (mask[a] and (1 shl d) != 0) {
                val n = nodes[a]; ends.add(intArrayOf(a, at[(n.cj + DIRS[d][1]) * ncx + n.ci + DIRS[d][0]] ?: error("RDM1: no node at an edge's end"), d)) }
            val counts = IntArray(ends.size) { i.varint() }
            val pts = ends.indices.map { q -> val a = nodes[ends[q][0]]; var pi = a.i; var pj = a.j
                IntArray(counts[q] * 2).also { arr -> for (k in 0 until counts[q]) { pi += i.zz(); pj += i.zz(); arr[2 * k] = pi; arr[2 * k + 1] = pj } } }
            val cost = IntArray(ends.size) { i.readUnsignedByte() }; val flags = IntArray(ends.size) { i.readUnsignedByte() }
            val dz = Array(factions.size) { IntArray(ends.size) { i.readUnsignedByte() } }
            Roadmap(map, cellV, gr0, gc0, ncx, ncy, factions, nodes, ends.indices.map { q ->
                Edge(ends[q][0], ends[q][1], ends[q][2], pts[q], cost[q], flags[q], IntArray(factions.size) { dz[it][q] }) })
        }
    }.getOrNull()

    /** each edge as a route ({letter, pts}: world x, y), for py/segcheck.py and py/wetcheck.py (a levels_routes_<tag>.json's "routes") */
    fun routesJson(rm: Roadmap): JSONArray = JSONArray().also { a ->
        for ((q, e) in rm.edges.withIndex()) a.put(JSONObject().put("letter", "E$q").put("pts", JSONArray(rm.points(e).map { p ->
            JSONArray(listOf(Math.round(p[0] * 100) / 100.0, Math.round(p[1] * 100) / 100.0)) }))) }
}
