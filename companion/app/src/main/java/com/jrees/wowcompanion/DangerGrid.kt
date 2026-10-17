package com.jrees.wowcompanion

import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * THE DANGER LAYER (b-danger-layer; the user's plan picks, 2026-10-17: neutrals ignored, strength High by default, the tint only with
 * the "Danger areas" filter, elites x3). One zone's hostile spawns (QuestieNpcs) become a coarse grid of danger, [CELL_YD] cells:
 * each spawn hostile to the character adds its weight ([weight]: nothing when grey, more the higher above the player, elite x3)
 * over [AGGRO_YD] round it. The route's leg on this map bends round it: A* over the grid (a step costs its yards x (1 + strength x
 * danger)), then string-pulled so the line is a few straight legs again. The last [GOAL_FREE_YD] to the leg's end are never danger
 * (a kill objective, a giver among mobs: the route must reach them). Pure: map 0..1 coordinates, [ax] / [ay] the map's yards.
 */
class DangerGrid(val nx: Int, val ny: Int, private val d: FloatArray, val ax: Double, val ay: Double) {
    val cx get() = 1.0 / nx            // a cell, in the map's 0..1
    val cy get() = 1.0 / ny

    /** the danger at the map's ([x], [y]) (0 off the map) */
    fun at(x: Double, y: Double): Float {
        val i = (x * nx).toInt(); val j = (y * ny).toInt()
        return if (i in 0 until nx && j in 0 until ny) d[j * nx + i] else 0f
    }
    fun cell(i: Int, j: Int): Float = d[j * nx + i]
    val max: Float get() = d.maxOrNull() ?: 0f

    /** the cost of walking straight from [a] to [b] at strength [k]: its yards, each weighed by the danger it crosses (none within
     *  [GOAL_FREE_YD] of [goal]) */
    fun segCost(a: DoubleArray, b: DoubleArray, k: Double, goal: DoubleArray): Double {
        val len = hypot((b[0] - a[0]) * ax, (b[1] - a[1]) * ay)
        if (len < 1e-6) return 0.0
        val n = max(1, (len / (CELL_YD / 3.0)).toInt())
        var c = 0.0
        for (s in 0 until n) {
            val t = (s + 0.5) / n
            val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
            val free = hypot((x - goal[0]) * ax, (y - goal[1]) * ay) <= GOAL_FREE_YD
            c += (len / n) * (1.0 + (if (free) 0.0 else k * at(x, y)))
        }
        return c
    }

    /** a polyline's cost ([segCost] leg by leg) */
    fun cost(pts: List<DoubleArray>, k: Double, goal: DoubleArray = pts.last()): Double =
        (1 until pts.size).sumOf { segCost(pts[it - 1], pts[it], k, goal) }

    /** the greatest danger along [a] -> [b] (none within the goal's free yards) */
    fun peak(a: DoubleArray, b: DoubleArray, goal: DoubleArray = b): Float {
        val len = hypot((b[0] - a[0]) * ax, (b[1] - a[1]) * ay)
        val n = max(1, (len / (CELL_YD / 3.0)).toInt())
        var m = 0f
        for (s in 0..n) {
            val t = s.toDouble() / n
            val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
            if (hypot((x - goal[0]) * ax, (y - goal[1]) * ay) > GOAL_FREE_YD) m = max(m, at(x, y))
        }
        return m
    }

    /**
     * The way from [from] to [to] (this map's 0..1; [to] clamped onto the map) at strength [k]: the straight line when it crosses
     * no danger, else A* over the cells, string-pulled. [from] first, [to] last.
     */
    fun route(from: DoubleArray, to: DoubleArray, k: Double): List<DoubleArray> {
        val goal = doubleArrayOf(to[0].coerceIn(0.0, 1.0 - 1e-9), to[1].coerceIn(0.0, 1.0 - 1e-9))
        val straight = listOf(from, to)
        if (k <= 0.0 || peak(from, goal) < CALM) return straight
        val si = (from[0].coerceIn(0.0, 1.0 - 1e-9) * nx).toInt(); val sj = (from[1].coerceIn(0.0, 1.0 - 1e-9) * ny).toInt()
        val ti = (goal[0] * nx).toInt(); val tj = (goal[1] * ny).toInt()
        val n = nx * ny
        val g = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        fun h(i: Int, j: Int) = hypot((i - ti) * cx * ax, (j - tj) * cy * ay)
        val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
        val s = sj * nx + si; val t = tj * nx + ti
        g[s] = 0.0; open.add(doubleArrayOf(h(si, sj), s.toDouble()))
        while (open.isNotEmpty()) {
            val top = open.poll()
            val u = top[1].toInt()
            if (u == t) break
            if (top[0] - h(u % nx, u / nx) > g[u] + 1e-6) continue
            val ui = u % nx; val uj = u / nx
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val vi = ui + di; val vj = uj + dj
                if (vi !in 0 until nx || vj !in 0 until ny) continue
                val v = vj * nx + vi
                val len = hypot(di * cx * ax, dj * cy * ay)
                val free = hypot((vi + 0.5) * cx * ax - goal[0] * ax, (vj + 0.5) * cy * ay - goal[1] * ay) <= GOAL_FREE_YD
                val c = g[u] + len * (1.0 + (if (free) 0.0 else k * cell(vi, vj)))
                if (c < g[v]) { g[v] = c; prev[v] = u; open.add(doubleArrayOf(c + h(vi, vj), v.toDouble())) }
            }
        }
        if (prev[t] == -1 && s != t) return straight
        // the cells' centres, from the player to the goal
        val cells = ArrayList<DoubleArray>()
        var c = t
        while (c != -1 && c != s) { cells.add(doubleArrayOf((c % nx + 0.5) * cx, (c / nx + 0.5) * cy)); c = prev[c] }
        cells.reverse()
        val raw = ArrayList<DoubleArray>().apply { add(from); addAll(cells.dropLast(1)); add(to) }
        val pulled = pull(raw, k, goal)
        // a bent way only when it is clearly better than the straight one
        return if (cost(pulled, k, goal) < cost(straight, k, goal) * 0.97) pulled else straight
    }

    /** STRING-PULLING: from each kept point, the farthest later one a straight leg reaches for no more than the cells' way costs */
    fun pull(pts: List<DoubleArray>, k: Double, goal: DoubleArray): List<DoubleArray> {
        if (pts.size <= 2) return pts
        val legs = DoubleArray(pts.size)                                 // the cost from the start to each point, along the cells
        for (i in 1 until pts.size) legs[i] = legs[i - 1] + segCost(pts[i - 1], pts[i], k, goal)
        // the danger the cells' way itself meets between two points: a straight leg may meet no more (it must not cut a camp's
        // corner the cells went round, however cheap the corner)
        fun cellsPeak(i: Int, j: Int): Float { var m = 0f; for (q in i..j) if (hypot((pts[q][0] - goal[0]) * ax, (pts[q][1] - goal[1]) * ay) > GOAL_FREE_YD) m = max(m, at(pts[q][0], pts[q][1])); return m }
        val out = arrayListOf(pts[0])
        var i = 0
        while (i < pts.size - 1) {
            var j = pts.size - 1
            while (j > i + 1 && (segCost(pts[i], pts[j], k, goal) > (legs[j] - legs[i]) * 1.02 + 0.5 ||
                    peak(pts[i], pts[j], goal) > cellsPeak(i, j) + CALM)) j--
            out.add(pts[j]); i = j
        }
        return out
    }

    companion object {
        const val CELL_YD = 25.0
        const val AGGRO_YD = 30.0
        const val GOAL_FREE_YD = 60.0
        const val CALM = 0.05f          // a straight line crossing no more than this is left alone
        const val CAP = 4f              // a cell's danger at most (a dense camp is a wall, not an infinity)
        /** the user's strengths: "Avoid dangerous areas" Off / Low / High */
        fun strength(setting: String): Double = when (setting) { "off" -> 0.0; "low" -> 1.5; else -> 8.0 }

        /** the game's grey line (classic: a creature at or below it gives no experience) for a player of [lv] */
        fun greyLevel(lv: Int): Int = when {
            lv <= 5 -> 0
            lv <= 39 -> lv - 5 - lv / 10
            lv <= 59 -> lv - 1 - lv / 5
            else -> lv - 9
        }

        /** one spawn's danger to a player of level [plv]: 0 grey; green 0.3, yellow 0.7, orange 1.0, red 1.5 rising; elite x3 */
        fun weight(minLv: Int, maxLv: Int, elite: Boolean, plv: Int): Float {
            val lv = max(minLv, maxLv)
            if (plv <= 0 || lv <= greyLevel(plv)) return 0f
            val dl = lv - plv
            val w = when {
                dl <= -3 -> 0.3f
                dl <= 2 -> 0.7f
                dl <= 4 -> 1.0f
                else -> min(2.5f, 1.5f + 0.1f * (dl - 5))
            }
            return if (elite) w * 3f else w
        }

        /**
         * the grid of one map: [spawns] on it (x, y in 0..1) hostile to [mine] (TransportRoutes.ALLIANCE / HORDE; 0 = unknown: all
         * hostile spawns count), for a player of [plv]; [ax] / [ay] the map's yards
         */
        fun build(spawns: List<QuestieNpcs.Spawn>, mine: Int, plv: Int, ax: Double, ay: Double): DangerGrid {
            val nx = max(1, (ax / CELL_YD).toInt()); val ny = max(1, (ay / CELL_YD).toInt())
            val d = FloatArray(nx * ny)
            val rx = (AGGRO_YD / ax * nx).toInt() + 1; val ry = (AGGRO_YD / ay * ny).toInt() + 1
            for (s in spawns) {
                if (mine != 0 && s.hostile and mine == 0) continue
                val w = weight(s.minLv, s.maxLv, s.elite, plv)
                if (w <= 0f) continue
                val ci = (s.x * nx).toInt(); val cj = (s.y * ny).toInt()
                for (j in max(0, cj - ry)..min(ny - 1, cj + ry)) for (i in max(0, ci - rx)..min(nx - 1, ci + rx)) {
                    val dist = hypot(((i + 0.5) / nx - s.x) * ax, ((j + 0.5) / ny - s.y) * ay)
                    if (dist < AGGRO_YD) d[j * nx + i] = min(CAP, d[j * nx + i] + w * (1f - (dist / AGGRO_YD).toFloat() * 0.5f))
                }
            }
            return DangerGrid(nx, ny, d, ax, ay)
        }

        /** the yards of a polyline on a map of [ax] x [ay] yards */
        fun yards(pts: List<DoubleArray>, ax: Double, ay: Double): Double =
            (1 until pts.size).sumOf { hypot((pts[it][0] - pts[it - 1][0]) * ax, (pts[it][1] - pts[it - 1][1]) * ay) }
    }
}
