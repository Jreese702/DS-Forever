package com.jrees.wowcompanion

import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ACROSS THE ZONES ON THE GROUND (b-route-cross-zone; the user on test58: "I can very clearly see the safe path that I want to take ...
 * it still has it sending me up a mountain" - from Goldshire to a Westfall quest the line aimed at "the edge toward the quest", a
 * stretch of Elwynn's border whose Westfall side is mountain; the road over the river bridge is elsewhere on that border). Every zone's
 * slope and building grid now exists (the terrain step, before Play), and a world point reads the same in any zone's grid that holds
 * it (one continent's heights), so the zones STITCH: a coarse [CELL] yd grid in world yards over the player, the quest and a margin,
 * each cell the cost the route's own grid gives (slope, cliffs and buildings as walls, roads and walked ground cheaper, danger), and
 * A* across it from the player to the QUEST's own point. Where the way leaves the player's map is the border crossing to aim at: it
 * falls where both sides really are walkable (the bridge, the road), from the game's own data, no hand table. Pure.
 */
object CrossZone {
    const val CELL = 50.0
    const val MARGIN = 1200.0
    const val MAX_SPAN = 12000.0          // a plan this wide (yd) at most (a continent's quest far off: the one-map choice stands)
    const val UNKNOWN = 2f                // ground no zone's grid covers (the sea, a gap): dearer than walking, not a wall
    const val ROAD = 0.45f                // a learned road: the user can SEE the road; strongly preferred

    /** one zone's grids for the stitch: its slopes and building cover (Terrain), its learned roads, the cells walked */
    class Zone(val id: Int, val slope: Terrain.Grid?, val bld: Terrain.Grid?, val road: (Double, Double) -> Boolean = { _, _ -> false })

    /** the ground's cost at world ([wx], [wy]) from the first of [zones] whose slope grid holds it; NaN = a wall (a cliff, a building) */
    fun cost(zones: List<Zone>, wx: Double, wy: Double, walked: (Double, Double) -> Boolean = { _, _ -> false }): Float {
        for (z in zones) {
            val s = z.slope?.at(wx, wy) ?: continue
            if (s == Terrain.NO_DATA) continue
            val f = Terrain.factor(s)
            if (f >= Terrain.WALL) return Float.NaN
            val b = z.bld?.at(wx, wy)?.takeIf { it != Terrain.NO_DATA } ?: 0
            if (b >= 5) return Float.NaN
            var c = if (b >= 2) f * Buildings.PART_COST else f
            if (f < 3f && z.road(wx, wy)) c *= ROAD
            else if (f < 3f && walked(wx, wy)) c *= Terrain.Walked.WALKED
            return c
        }
        return UNKNOWN
    }

    /** a hostile spawn in world yards and its weight (DangerGrid.weight) */
    class Threat(val wx: Double, val wy: Double, val w: Float)

    /** the plan: world points from the player to the goal (cell centres, the ends exact), and its cost */
    class Plan(val pts: List<DoubleArray>, val cost: Double)

    /**
     * A* from world ([sx], [sy]) to ([gx], [gy]) over [CELL] yd cells ([cost] each, NaN = wall) with [threats] spread over
     * DangerGrid.AGGRO_YD at strength [k]. The start's and goal's own cells are never walls. Null = too far apart or no way.
     */
    fun plan(sx: Double, sy: Double, gx: Double, gy: Double, cost: (Double, Double) -> Float, threats: List<Threat>, k: Double): Plan? {
        if (hypot(gx - sx, gy - sy) > MAX_SPAN) return null
        val x0 = min(sx, gx) - MARGIN; val x1 = max(sx, gx) + MARGIN
        val y0 = min(sy, gy) - MARGIN; val y1 = max(sy, gy) + MARGIN
        val nx = ((y1 - y0) / CELL).toInt() + 1; val ny = ((x1 - x0) / CELL).toInt() + 1   // i along world y, j along world x
        val n = nx * ny
        fun cx(j: Int) = x0 + (j + 0.5) * CELL
        fun cy(i: Int) = y0 + (i + 0.5) * CELL
        val c = FloatArray(n)
        for (j in 0 until ny) for (i in 0 until nx) c[j * nx + i] = cost(cx(j), cy(i)).let { if (it.isNaN()) Float.NaN else it }
        // the danger, spread like the route's own grid
        if (k > 0.0) {
            val d = FloatArray(n)
            val r = (DangerGrid.AGGRO_YD / CELL).toInt() + 1
            for (t in threats) {
                val ti = floor((t.wy - y0) / CELL).toInt(); val tj = floor((t.wx - x0) / CELL).toInt()
                for (j in max(0, tj - r)..min(ny - 1, tj + r)) for (i in max(0, ti - r)..min(nx - 1, ti + r)) {
                    val dist = hypot(cx(j) - t.wx, cy(i) - t.wy)
                    if (dist < DangerGrid.AGGRO_YD) d[j * nx + i] = min(DangerGrid.CAP, d[j * nx + i] + t.w * (1f - (dist / DangerGrid.AGGRO_YD).toFloat() * 0.5f))
                }
            }
            for (q in 0 until n) if (!c[q].isNaN()) c[q] += (k * d[q]).toFloat()
        }
        val si = floor((sy - y0) / CELL).toInt(); val sj = floor((sx - x0) / CELL).toInt()
        val gi = floor((gy - y0) / CELL).toInt(); val gj = floor((gx - x0) / CELL).toInt()
        val s = sj * nx + si; val t = gj * nx + gi
        if (c[s].isNaN()) c[s] = UNKNOWN
        if (c[t].isNaN()) c[t] = UNKNOWN
        val g = DoubleArray(n) { Double.MAX_VALUE }; val prev = IntArray(n) { -1 }
        val hk = min(ROAD, Terrain.Walked.WALKED).toDouble()
        fun h(q: Int) = hypot(((q / nx) - gj) * CELL, ((q % nx) - gi) * CELL) * hk
        val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
        g[s] = 0.0; open.add(doubleArrayOf(h(s), s.toDouble()))
        while (open.isNotEmpty()) {
            val top = open.poll(); val u = top[1].toInt()
            if (u == t) break
            if (top[0] - h(u) > g[u] + 1e-6) continue
            val ui = u % nx; val uj = u / nx
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val vi = ui + di; val vj = uj + dj
                if (vi !in 0 until nx || vj !in 0 until ny) continue
                val v = vj * nx + vi
                if (c[v].isNaN()) continue
                // no corner-cutting between two walls
                if (di != 0 && dj != 0 && (c[uj * nx + vi].isNaN() || c[vj * nx + ui].isNaN())) continue
                val step = (if (di != 0 && dj != 0) 1.4142 else 1.0) * CELL * (c[u] + c[v]) / 2
                val nd = g[u] + step
                if (nd < g[v]) { g[v] = nd; prev[v] = u; open.add(doubleArrayOf(nd + h(v), v.toDouble())) }
            }
        }
        if (prev[t] == -1 && s != t) return null
        val cells = ArrayList<DoubleArray>()
        var q = t
        while (q != -1 && q != s) { cells.add(doubleArrayOf(cx(q / nx), cy(q % nx))); q = prev[q] }
        cells.reverse()
        val pts = ArrayList<DoubleArray>().apply { add(doubleArrayOf(sx, sy)); addAll(cells.dropLast(1)); add(doubleArrayOf(gx, gy)) }
        return Plan(pts, g[t])
    }

    /**
     * where [plan] first leaves the box of the player's map (world [top] / [bottom] x, [left] / [right] y): the last point inside and
     * the first outside, or null when it never leaves
     */
    fun exit(plan: Plan, top: Double, bottom: Double, left: Double, right: Double): Pair<DoubleArray, DoubleArray>? {
        fun inside(p: DoubleArray) = p[0] <= top && p[0] >= bottom && p[1] <= left && p[1] >= right
        for (i in 1 until plan.pts.size) if (inside(plan.pts[i - 1]) && !inside(plan.pts[i])) return plan.pts[i - 1] to plan.pts[i]
        return null
    }
}
