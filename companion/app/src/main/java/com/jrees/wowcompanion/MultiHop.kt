package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * MORE THAN ONE WAY OUT (b-route-multihop; the user on test51, SafePath: "One of my quests is directing me to go back to the human
 * starting zone and over the mountain instead of back to Stormwind to take the tram back to Ironforge. I feel like the pathing needs
 * to really factor walkable terrain"). The ways out of ONE map ([CityExits]) could not chain: from Elwynn the tram is no way out (it
 * starts in Stormwind), so an Ironforge quest got "the edge toward the quest" - north, over the mountains.
 *
 * Here the known ways of EVERY map of the continent are hops of one graph: a learned crossing (walk to it, the next map begins
 * there), the tram and other rides (walk to the near end, ride, go on from the far end). On foot the player only moves inside a map:
 * from one map to another only by a hop, never straight across a border nobody has crossed (mountains are borders). The quest is
 * reached from a point that shares a zone with it ([reaches]: a zone's rectangle holding both). The cheapest chain of at most
 * [MAX_HOPS] hops, in yards (walks straight, each ride its own yards). Pure: world yards in, the chain out.
 */
object MultiHop {
    /** one way from [fromMap] at world ([ax], [ay]) into [toMap] at ([bx], [by]) (the same place for a crossing on foot) */
    class Hop(val fromMap: Int, val ax: Double, val ay: Double, val toMap: Int, val bx: Double, val by: Double, val rideYd: Double,
              val label: String, val key: String)

    /** the chain, first hop first, and its yards in all; an empty chain = the quest is reached on foot from where the player is */
    class Plan(val hops: List<Hop>, val cost: Double) {
        fun describe(): String = if (hops.isEmpty()) "straight" else hops.joinToString(", then ") { it.label }
    }

    const val MAX_HOPS = 4
    /** the walk to or from a link's end with no position (a portal's landing not in the data): reckoned this long */
    const val UNKNOWN_YD = 500.0

    /**
     * The cheapest way from the player on [startMap] at world ([sx], [sy]) to the quest at ([gx], [gy]). [reaches] (map, wx, wy):
     * from that point on that map the quest is a walk. Null = no chain reaches it (and the player cannot walk there either).
     */
    fun plan(startMap: Int, sx: Double, sy: Double, gx: Double, gy: Double, hops: List<Hop>,
             reaches: (Int, Double, Double) -> Boolean, maxHops: Int = MAX_HOPS): Plan? {
        // a link's end nobody has measured (NaN: a portal's landing) is taken at [UNKNOWN_YD] - a plan through it says so
        fun walk(x0: Double, y0: Double, x1: Double, y1: Double) = if (x0.isNaN() || x1.isNaN()) UNKNOWN_YD else hypot(x1 - x0, y1 - y0)
        var best: Plan? = if (reaches(startMap, sx, sy)) Plan(emptyList(), walk(sx, sy, gx, gy)) else null
        // layer by layer (at most [maxHops]): the cheapest arrival at the far end of each hop, and the chain that got there
        val n = hops.size
        // the hops out of each map (b-travel-layer: a continent's known flights are many)
        val byFrom = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) byFrom.getOrPut(hops[i].fromMap) { ArrayList() }.add(i)
        val cost = DoubleArray(n) { Double.MAX_VALUE }
        val via = arrayOfNulls<List<Int>>(n)
        for (i in 0 until n) {
            val h = hops[i]
            if (h.fromMap != startMap) continue
            cost[i] = walk(sx, sy, h.ax, h.ay) + h.rideYd; via[i] = listOf(i)
        }
        for (layer in 1..maxHops) {
            // the chains found so far: is the quest a walk from the far end?
            for (i in 0 until n) {
                val chain = via[i] ?: continue
                val h = hops[i]
                if (!reaches(h.toMap, h.bx, h.by)) continue
                val total = cost[i] + walk(h.bx, h.by, gx, gy)
                if (best == null || total < best.cost) best = Plan(chain.map { hops[it] }, total)
            }
            if (layer == maxHops) break
            // one hop more: from each far end, the hops out of the map it lies on (never through a map the chain has been on)
            var moved = false
            val next = cost.copyOf(); val nextVia = via.copyOf()
            for (i in 0 until n) {
                val chain = via[i] ?: continue
                if (chain.size != layer) continue
                val h = hops[i]
                val seen = chain.map { hops[it].fromMap }.toSet() + h.toMap
                for (j in byFrom[h.toMap] ?: continue) {
                    val g = hops[j]
                    if (g.toMap in seen) continue
                    val c = cost[i] + walk(h.bx, h.by, g.ax, g.ay) + g.rideYd
                    if (c < next[j]) { next[j] = c; nextVia[j] = chain + j; moved = true }
                }
            }
            if (!moved) break
            next.copyInto(cost); nextVia.copyInto(via)
        }
        return best
    }
}

/**
 * THE EDGE ONLY WHERE ONE CAN WALK OUT (b-route-multihop): a point on a map's border ([x], [y] in its 0..1; [ax] / [ay] yards across
 * / down) slid along that border, nearest first, a cell at a time up to [maxYd], to where [walkable] says the ground a few cells in
 * (12, 37 and 62 yd) is walkable. Pure: [walkable] (x, y in 0..1) is the caller's slope grid.
 */
object EdgeWalk {
    const val CELL_YD = 25.0
    private val INWARD_YD = doubleArrayOf(12.0, 37.0, 62.0)

    /** [x, y, the yards slid] (0 = the point itself), or null when no stretch of the border within [maxYd] is walkable */
    fun slide(x: Double, y: Double, ax: Double, ay: Double, maxYd: Double, walkable: (Double, Double) -> Boolean): DoubleArray? {
        val dists = doubleArrayOf(x, 1.0 - x, y, 1.0 - y)
        val side = dists.indices.minByOrNull { dists[it] }!!       // 0 west, 1 east, 2 north, 3 south
        val alongY = side < 2                                      // a west / east border runs north - south
        val step = CELL_YD / (if (alongY) ay else ax)
        fun ok(ex: Double, ey: Double): Boolean = INWARD_YD.all { d ->
            val ix = when (side) { 0 -> ex + d / ax; 1 -> ex - d / ax; else -> ex }
            val iy = when (side) { 2 -> ey + d / ay; 3 -> ey - d / ay; else -> ey }
            walkable(ix, iy)
        }
        val n = (maxYd / CELL_YD).toInt()
        for (k in 0..n) for (sgn in if (k == 0) intArrayOf(1) else intArrayOf(1, -1)) {
            val t = (if (alongY) y else x) + sgn * k * step
            if (t < 0.0 || t > 1.0) continue
            val ex = if (alongY) x else t; val ey = if (alongY) t else y
            if (ok(ex, ey)) return doubleArrayOf(ex, ey, k * CELL_YD)
        }
        return null
    }
}
