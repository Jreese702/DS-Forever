import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedNet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/**
 * THE ROADMAP'S BUILDER (NET1 v2, ShippedNet.Roadmap; the coordinator, 2026-10-19: "a coverage graph of the zone's walkable ground"):
 * on the zone's own lattice [cg], one node a cell of [cell] lattice steps (the walkable vertex nearest the cell's middle: off deep
 * water, standable, out of a building's rooms, with open steps round it), joined to its four forward neighbours ([ShippedNet.DIRS])
 * when the straight line is walkable (Climb.walkable, the one check) or else the lattice's own leg joins them no longer than
 * [WALK_FACTOR] the line (its inner vertices shipped). Each edge's cost flags (wade, bridge, building, the edge-clearance and climb
 * tiers) are the lattice's; each faction's danger a byte an edge ([dangerBytes]).
 */
class RoadmapBuild(private val cg: Climb.Grid, private val deep: (Double, Double) -> Boolean, private val standable: (Double, Double) -> Boolean,
                   private val rooms: (Double, Double) -> Boolean, private val waterAt: (Double, Double) -> Float, private val layers: Map<Long, Float>,
                   t: Double, b: Double, l: Double, r: Double, val cell: Int = 10, padYd: Double = 150.0) {
    companion object {
        const val WALK_FACTOR = 1.6
        const val WALK_SLACK_YD = 10.0
        const val LEG_WINDOW = 40_000
        const val OPEN_STEPS = 4
    }
    private val grLo = max(cg.gr0, ShippedNet.gr(t + padYd)); private val grHi = minOf(cg.gr0 + cg.ny - 1, ShippedNet.gr(b - padYd))
    private val gcLo = max(cg.gc0, ShippedNet.gc(l + padYd)); private val gcHi = minOf(cg.gc0 + cg.nx - 1, ShippedNet.gc(r - padYd))
    val nx = max(0, ceil((gcHi - gcLo + 1) / cell.toDouble()).toInt()); val ny = max(0, ceil((grHi - grLo + 1) / cell.toDouble()).toInt())
    private fun vx(gr: Int) = cg.wx(gr - cg.gr0); private fun vy(gc: Int) = cg.wy(gc - cg.gc0)
    /** the cell's offsets nearest its middle first */
    private val order = (0 until cell).flatMap { dr -> (0 until cell).map { dc -> intArrayOf(dr, dc) } }
        .sortedBy { hypot(it[0] - (cell - 1) / 2.0, it[1] - (cell - 1) / 2.0) }

    private fun ok(gr: Int, gc: Int): Boolean {
        if (gr !in grLo..grHi || gc !in gcLo..gcHi) return false
        val x = vx(gr); val y = vy(gc)
        if (deep(x, y) || rooms(x, y) || !standable(x, y)) return false
        val i = gc - cg.gc0; val j = gr - cg.gr0
        var open = 0
        for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
            if (cg.step(i, j, di, dj) != Climb.WALL && !deep(cg.wx(j + dj), cg.wy(i + di))) open++ }
        return open >= OPEN_STEPS
    }

    class Built(val rm: ShippedNet.Roadmap, val samples: List<List<DoubleArray>>, val straight: Int, val walked: Int, val dropped: Int, val ms: Long)

    fun build(log: (String) -> Unit = {}): Built {
        val t0 = System.nanoTime()
        val nodeCell = ArrayList<Int>(); val ngr = ArrayList<Int>(); val ngc = ArrayList<Int>(); val nf = ArrayList<Int>()
        val at = IntArray(nx * ny) { -1 }
        for (c in 0 until nx * ny) {
            val r0 = grLo + (c / nx) * cell; val c0 = gcLo + (c % nx) * cell
            val o = order.firstOrNull { ok(r0 + it[0], c0 + it[1]) } ?: continue
            val gr = r0 + o[0]; val gc = c0 + o[1]; val x = vx(gr); val y = vy(gc)
            at[c] = ngr.size; nodeCell.add(c); ngr.add(gr); ngc.add(gc)
            var f = 0
            if (layers.containsKey(Climb.vkey(gr, gc))) f = f or ShippedNet.R_FLOOR
            if (listOf(-3.0 to 0.0, 3.0 to 0.0, 0.0 to 3.0, 0.0 to -3.0).any { (a, b2) -> rooms(x + a, y + b2) }) f = f or ShippedNet.R_BUILDING
            nf.add(f)
        }
        val ea = ArrayList<Int>(); val eb = ArrayList<Int>(); val ef = ArrayList<Int>(); val ep = ArrayList<IntArray?>(); val samples = ArrayList<List<DoubleArray>>()
        var straight = 0; var walked = 0; var dropped = 0
        for (n in ngr.indices) {
            val c = nodeCell[n]; val pa = doubleArrayOf(vx(ngr[n]), vy(ngc[n]))
            for (d in ShippedNet.DIRS) {
                val ci = c % nx + d[0]; val cj = c / nx + d[1]
                if (ci !in 0 until nx || cj !in 0 until ny) continue
                val m = at[cj * nx + ci]; if (m < 0) continue
                val pb = doubleArrayOf(vx(ngr[m]), vy(ngc[m]))
                val line = hypot(pb[0] - pa[0], pb[1] - pa[1])
                var pts: List<DoubleArray>? = listOf(pa, pb); var inner: IntArray? = null
                if (!Climb.walkable(cg, pa, pb, deep)) {
                    val leg = Climb.route(cg, pa, pb, water = deep, edges = false, limits = Climb.Limits(LEG_WINDOW))
                    val yd = leg?.pts?.zipWithNext { u, v -> hypot(v[0] - u[0], v[1] - u[1]) }?.sum() ?: Double.MAX_VALUE
                    pts = if (leg != null && leg.reached && yd <= WALK_FACTOR * line + WALK_SLACK_YD &&
                        leg.pts.zipWithNext().all { (u, v) -> Climb.walkable(cg, u, v, deep) }) leg.pts else null
                    if (pts != null) {
                        // its inner points must be lattice vertices (they are: the leg's bends) - else no edge
                        val mid = pts.subList(1, pts.size - 1)
                        val q = IntArray(mid.size * 2); var exact = true
                        for ((k, p) in mid.withIndex()) { val gr = ShippedNet.gr(p[0]); val gc = ShippedNet.gc(p[1])
                            if (abs(vx(gr) - p[0]) > 1e-6 || abs(vy(gc) - p[1]) > 1e-6) exact = false
                            q[2 * k] = gr; q[2 * k + 1] = gc }
                        // the ends must be the nodes themselves (the leg may stop at its last vertex short of an exact end: they are vertices)
                        if (!exact || hypot(pts.first()[0] - pa[0], pts.first()[1] - pa[1]) > 1e-6 || hypot(pts.last()[0] - pb[0], pts.last()[1] - pb[1]) > 1e-6) pts = null
                        else inner = q.takeIf { it.isNotEmpty() }
                    }
                }
                if (pts == null) { dropped++; continue }
                // the shipped shape, as the decoder will give it: the nodes and the inner vertices
                val shape = listOf(pa) + (inner?.let { q -> (0 until q.size / 2).map { k -> doubleArrayOf(vx(q[2 * k]), vy(q[2 * k + 1])) } } ?: emptyList()) + listOf(pb)
                if (inner == null && shape.size == 2 && pts.size == 2) straight++ else walked++
                ea.add(n); eb.add(m); ep.add(inner); ef.add(flags(shape)); samples.add(sample(shape))
            }
        }
        val rm = ShippedNet.Roadmap(grLo, gcLo, cell, nx, ny, nodeCell.toIntArray(), ngr.toIntArray(), ngc.toIntArray(), nf.toIntArray(),
            ea.toIntArray(), eb.toIntArray(), ef.toIntArray(), ep.toTypedArray())
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("ROADMAP ${nx} x ${ny} cells of ${cell} steps: ${rm.nodes} nodes, ${rm.edges} edges ($straight straight, $walked walked, $dropped neighbour pairs not joined); $ms ms")
        return Built(rm, samples, straight, walked, dropped, ms)
    }

    /** the points a yard apart along [shape] */
    private fun sample(shape: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for ((a, b) in shape.zipWithNext()) { val n = max(1, hypot(b[0] - a[0], b[1] - a[1]).toInt())
            for (q in 0 until n) out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n)) }
        out.add(shape.last()); return out
    }

    private fun flags(shape: List<DoubleArray>): Int {
        var f = 0; var edge = 0.0; var climb = Climb.LEVEL
        for (p in sample(shape)) { val d = waterAt(p[0], p[1])
            if (d == SafePath.Water.BRIDGE) f = f or ShippedNet.RE_BRIDGE
            else if (!d.isNaN() && d > 0f && !deep(p[0], p[1])) f = f or ShippedNet.RE_WADE
            if (rooms(p[0] + 2, p[1]) || rooms(p[0] - 2, p[1]) || rooms(p[0], p[1] + 2) || rooms(p[0], p[1] - 2)) f = f or ShippedNet.RE_BUILDING }
        for ((a, b) in shape.zipWithNext()) {
            val s = cg.at(a[0], a[1]); val e = cg.at(b[0], b[1]); val n = max(abs(e[0] - s[0]), abs(e[1] - s[1]))
            var ci = s[0]; var cj = s[1]; edge = max(edge, Climb.edgeCost(cg.dropYd(ci, cj)))
            for (q in 1..n) { val ni = s[0] + Math.round((e[0] - s[0]).toDouble() * q / n).toInt(); val nj = s[1] + Math.round((e[1] - s[1]).toDouble() * q / n).toInt()
                val di = sign((ni - ci).toDouble()).toInt(); val dj = sign((nj - cj).toDouble()).toInt()
                if (di == 0 && dj == 0) continue
                val k = cg.step(ci, cj, di, dj); if (k != Climb.WALL) climb = max(climb, k)
                ci = ni; cj = nj; edge = max(edge, Climb.edgeCost(cg.dropYd(ci, cj))) }
        }
        val et = minOf(3, ceil(edge / ShippedNet.EDGE_TIER_COST - 1e-9).toInt())
        return f or (et shl 2) or (minOf(3, climb) shl 4)
    }

    /** a faction's danger a byte an edge: the mean of [danger] (the danger grid's units, the zone's typical taken off) along it */
    fun dangerBytes(b: Built, danger: (Double, Double) -> Float): ByteArray = ByteArray(b.rm.edges) { e ->
        val s = b.samples[e]; val m = s.sumOf { danger(it[0], it[1]).toDouble() } / s.size
        minOf(255, Math.round(m * ShippedNet.ROADMAP_DANGER_SCALE).toInt()).coerceAtLeast(0).toByte() }
}
