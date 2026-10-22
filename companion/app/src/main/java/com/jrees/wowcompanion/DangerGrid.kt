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
class DangerGrid(val nx: Int, val ny: Int, private val d: FloatArray, val ax: Double, val ay: Double,
                 /** the ground's cost per cell (b-route-terrain: Terrain.factor x the walked discount; 1 = flat, unknown) */
                 private val terr: FloatArray = FloatArray(nx * ny) { 1f },
                 /** the ghost's own ground (CorpseRoute), read per cell the first time a ghost's leg asks; null: [terr] */
                 private val ghostSrc: GhostSrc? = null) {
    val cx get() = 1.0 / nx            // a cell, in the map's 0..1
    val cy get() = 1.0 / ny

    /**
     * THE GHOST'S GROUND (CorpseRoute; the user's rulings "Walk on water" and "Yes, add drops"), at the map's (x, y): [ground] the cost
     * a yard - [Terrain.WALL] for a wall a ghost never passes (a building, lava), [CLIFF] for a cliff (a slope wall: passed DOWN only,
     * by more than a step, when the [height]s say so), else the ground's cost with deep water walked as dry ground; [height] the
     * ground's height there (NaN unknown; null: no heights, a cliff is a wall); [wet] water there (a drop never lands on water only)
     */
    class GhostSrc(val ground: (Double, Double) -> Float, val height: ((Double, Double) -> Float)? = null,
                   val wet: ((Double, Double) -> Boolean)? = null)
    private class Ghost(val cost: FloatArray, val cliff: BooleanArray, val h: FloatArray?, val wet: BooleanArray?)
    private val ghost: Ghost? by lazy {
        val src = ghostSrc ?: return@lazy null
        val n = nx * ny
        val cost = FloatArray(n); val cliff = BooleanArray(n)
        val h = src.height?.let { FloatArray(n) }; val wet = src.wet?.let { BooleanArray(n) }
        for (j in 0 until ny) for (i in 0 until nx) {
            val x = (i + 0.5) / nx; val y = (j + 0.5) / ny; val c = j * nx + i
            val g = src.ground(x, y)
            if (g == CLIFF) { cliff[c] = true; cost[c] = 1f } else cost[c] = if (g.isNaN()) 1f else g
            h?.set(c, src.height!!(x, y)); wet?.set(c, src.wet!!(x, y))
        }
        Ghost(cost, cliff, h, wet)
    }
    /** this grid with the ghost's ground [src] (its danger and living ground the same arrays) */
    fun withGhost(src: GhostSrc?): DangerGrid = if (src == null) this else DangerGrid(nx, ny, d, ax, ay, terr, src)
    /** the ghost's ground cost at the map's ([x], [y]) for a router with no drops (a cliff a wall); [ground] with no ghost ground */
    fun ghostGround(x: Double, y: Double): Float {
        val gh = ghost ?: return ground(x, y)
        val i = (x * nx).toInt(); val j = (y * ny).toInt()
        if (i !in 0 until nx || j !in 0 until ny) return 1f
        val c = j * nx + i
        return if (gh.cliff[c]) Terrain.WALL else gh.cost[c]
    }

    /** the danger at the map's ([x], [y]) (0 off the map) */
    fun at(x: Double, y: Double): Float {
        val i = (x * nx).toInt(); val j = (y * ny).toInt()
        return if (i in 0 until nx && j in 0 until ny) d[j * nx + i] else 0f
    }
    fun cell(i: Int, j: Int): Float = d[j * nx + i]
    /** the ground's cost at the map's ([x], [y]) (1 off the map) */
    fun ground(x: Double, y: Double): Float {
        val i = (x * nx).toInt(); val j = (y * ny).toInt()
        return if (i in 0 until nx && j in 0 until ny) terr[j * nx + i] else 1f
    }
    val max: Float get() = d.maxOrNull() ?: 0f
    /**
     * THE ZONE'S TYPICAL DANGER (the user's "silly detours": a level 52 in Un'Goro, every cell red, routes went 2-4 times the straight
     * way): the median danger of the cells [inZone] (the map's 0..1 middle of each cell) - a route's cost counts only the danger over it,
     * so it steers round the worst camps, not round the whole zone. 0 when the zone is mostly quiet.
     */
    fun typical(inZone: (Double, Double) -> Boolean): Float {
        val v = ArrayList<Float>()
        for (j in 0 until ny) for (i in 0 until nx) if (inZone((i + 0.5) / nx, (j + 0.5) / ny)) v.add(d[j * nx + i])
        if (v.isEmpty()) return 0f
        v.sort(); return v[v.size / 2]
    }

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
            c += (len / n) * (ground(x, y) + (if (free) 0.0 else k * at(x, y)))
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
     * no danger, else A* over the cells, string-pulled. [from] first, [to] last. [groundOnly] (a ghost's corpse run, CorpseRoute): the
     * ground's walls and costs alone, the danger never read (with it, k 0 still bends round a cliff; without it, k 0 is straight).
     */
    fun route(from: DoubleArray, to: DoubleArray, k: Double, groundOnly: Boolean = false): List<DoubleArray> {
        if (groundOnly) return ghostRoute(from, to)
        val goal = doubleArrayOf(to[0].coerceIn(0.0, 1.0 - 1e-9), to[1].coerceIn(0.0, 1.0 - 1e-9))
        val straight = listOf(from, to)
        if (k <= 0.0) return straight
        // calm and level: straight (the ground's cost along it no more than a few percent over its yards)
        val len = hypot((goal[0] - from[0]) * ax, (goal[1] - from[1]) * ay)
        if (peak(from, goal) < CALM && segCost(from, goal, 0.0, goal) <= len * 1.05) return straight
        val si = (from[0].coerceIn(0.0, 1.0 - 1e-9) * nx).toInt(); val sj = (from[1].coerceIn(0.0, 1.0 - 1e-9) * ny).toInt()
        val ti = (goal[0] * nx).toInt(); val tj = (goal[1] * ny).toInt()
        val n = nx * ny
        val g = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        // the estimate never more than the cheapest ground could cost (a learned road: Roads.ROAD of its yards)
        fun h(i: Int, j: Int) = hypot((i - ti) * cx * ax, (j - tj) * cy * ay) * minOf(Roads.ROAD, Terrain.Walked.WALKED)
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
                // a cliff or a building is a wall, not a cost (test51, test54); not near the start or the goal (a door: leaving or
                // reaching a building, a quest giver inside one)
                if (terr[v] >= Terrain.WALL && v != t && hypot((vi - si) * cx * ax, (vj - sj) * cy * ay) > WALL_FREE_YD &&
                    hypot((vi - ti) * cx * ax, (vj - tj) * cy * ay) > WALL_FREE_YD) continue
                val len = hypot(di * cx * ax, dj * cy * ay)
                val free = hypot((vi + 0.5) * cx * ax - goal[0] * ax, (vj + 0.5) * cy * ay - goal[1] * ay) <= GOAL_FREE_YD
                val c = g[u] + len * (terr[v] + (if (free) 0.0 else k * cell(vi, vj)))
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

    /**
     * THE GHOST'S WAY (CorpseRoute): A* over the ghost's ground ([GhostSrc]; none: [terr]) with no danger - walls (buildings, lava; a
     * cliff with no heights) never passed, water walked, and a CLIFF only DOWN: into or out of a cliff cell only to a cell lower by more
     * than [DROP_YD], never up, never landing on water only. A drop is reached only on a way that goes on to the goal (the A* ends
     * there: no dead-end pit). String-pulled over straight lines that meet no wall or cliff cell. [from] first, [to] last.
     */
    private fun ghostRoute(from: DoubleArray, to: DoubleArray): List<DoubleArray> {
        val gh = ghost
        val tc = gh?.cost ?: terr
        val cliff = gh?.cliff; val hs = gh?.h; val wet = gh?.wet
        val goal = doubleArrayOf(to[0].coerceIn(0.0, 1.0 - 1e-9), to[1].coerceIn(0.0, 1.0 - 1e-9))
        val straight = listOf(from, to)
        val si = (from[0].coerceIn(0.0, 1.0 - 1e-9) * nx).toInt(); val sj = (from[1].coerceIn(0.0, 1.0 - 1e-9) * ny).toInt()
        val ti = (goal[0] * nx).toInt(); val tj = (goal[1] * ny).toInt()
        fun freeCell(i: Int, j: Int) = hypot((i - si) * cx * ax, (j - sj) * cy * ay) <= WALL_FREE_YD ||
            hypot((i - ti) * cx * ax, (j - tj) * cy * ay) <= WALL_FREE_YD
        fun cellOf(x: Double, y: Double) = ((y.coerceIn(0.0, 1.0 - 1e-9) * ny).toInt()) * nx + (x.coerceIn(0.0, 1.0 - 1e-9) * nx).toInt()
        // a straight line: clear of every wall and cliff cell (the free cells by the ends aside), and its cost
        fun clear(a: DoubleArray, b: DoubleArray): Boolean {
            val len = hypot((b[0] - a[0]) * ax, (b[1] - a[1]) * ay); val n = max(1, (len / (CELL_YD / 4.0)).toInt())
            for (q in 0..n) {
                val t = q.toDouble() / n; val c = cellOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                if ((tc[c] >= Terrain.WALL || cliff?.get(c) == true) && !freeCell(c % nx, c / nx)) return false
            }
            return true
        }
        fun lineCost(a: DoubleArray, b: DoubleArray): Double {
            val len = hypot((b[0] - a[0]) * ax, (b[1] - a[1]) * ay); if (len < 1e-6) return 0.0
            val n = max(1, (len / (CELL_YD / 3.0)).toInt()); var c = 0.0
            for (q in 0 until n) { val t = (q + 0.5) / n; c += (len / n) * minOf(tc[cellOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)], Terrain.WALL) }
            return c
        }
        val len0 = hypot((goal[0] - from[0]) * ax, (goal[1] - from[1]) * ay)
        if (clear(from, goal) && lineCost(from, goal) <= len0 * 1.05) return straight
        val n = nx * ny
        val g = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        fun h(i: Int, j: Int) = hypot((i - ti) * cx * ax, (j - tj) * cy * ay) * minOf(Roads.ROAD, Terrain.Walked.WALKED)
        val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
        val s = sj * nx + si; val t = tj * nx + ti
        g[s] = 0.0; open.add(doubleArrayOf(h(si, sj), s.toDouble()))
        while (open.isNotEmpty()) {
            val top = open.poll()
            val u = top[1].toInt()
            if (u == t) break
            if (top[0] - h(u % nx, u / nx) > g[u] + 1e-6) continue
            val ui = u % nx; val uj = u / nx
            val cu = cliff?.get(u) == true && !freeCell(ui, uj)
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val vi = ui + di; val vj = uj + dj
                if (vi !in 0 until nx || vj !in 0 until ny) continue
                val v = vj * nx + vi
                val vFree = v == t || freeCell(vi, vj)
                val cv = cliff?.get(v) == true && !vFree
                if (cu || cv) {
                    // A DROP: down only, by more than a step, never up; never landing on water only; no heights, no drop
                    if (hs == null) continue
                    val hu = hs[u]; val hv = hs[v]
                    if (hu.isNaN() || hv.isNaN() || hv > hu - DROP_YD) continue
                    if (!cv && wet?.get(v) == true) continue
                } else if (tc[v] >= Terrain.WALL && !vFree) continue
                val c = g[u] + hypot(di * cx * ax, dj * cy * ay) * minOf(tc[v], Terrain.WALL)
                if (c < g[v]) { g[v] = c; prev[v] = u; open.add(doubleArrayOf(c + h(vi, vj), v.toDouble())) }
            }
        }
        if (prev[t] == -1 && s != t) return straight
        val cells = ArrayList<DoubleArray>()
        var c = t
        while (c != -1 && c != s) { cells.add(doubleArrayOf((c % nx + 0.5) * cx, (c / nx + 0.5) * cy)); c = prev[c] }
        cells.reverse()
        val pts = ArrayList<DoubleArray>().apply { add(from); addAll(cells.dropLast(1)); add(to) }
        // string-pulled: the farthest later point a CLEAR straight line reaches for no more than the cells' way costs (a drop's cells
        // are kept as they are: no straight line crosses a cliff)
        val legs = DoubleArray(pts.size)
        for (i in 1 until pts.size) legs[i] = legs[i - 1] + lineCost(pts[i - 1], pts[i])
        val out = arrayListOf(pts[0])
        var i = 0
        while (i < pts.size - 1) {
            var j = pts.size - 1
            while (j > i + 1 && (!clear(pts[i], pts[j]) || lineCost(pts[i], pts[j]) > (legs[j] - legs[i]) * 1.02 + 0.5)) j--
            out.add(pts[j]); i = j
        }
        return out
    }

    companion object {
        const val CELL_YD = 25.0
        /** a spawn's danger spreads this far round it (the one knob; the user on test53: "increasing the size of the danger areas by
         *  like 1.5x to see if that fits the safe pathing": 30 -> 45; then "Make the danger areas about 2x": 60) */
        const val AGGRO_YD = 60.0
        const val GOAL_FREE_YD = 60.0
        /** walls (cliffs, buildings) do not hold within this of the start or the goal: a building's door, a quest giver inside */
        const val WALL_FREE_YD = 40.0
        const val CALM = 0.05f          // a straight line crossing no more than this is left alone
        /** [GhostSrc.ground]'s mark for a cliff cell (a ghost drops down it, never climbs it) */
        const val CLIFF = -1f
        /** a ghost's drop: the far cell lower by more than this (a step: Climb's drop) */
        const val DROP_YD = Climb.DROP_YD
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
        fun build(spawns: List<QuestieNpcs.Spawn>, mine: Int, plv: Int, ax: Double, ay: Double,
                  ground: ((Double, Double) -> Float)? = null): DangerGrid {
            val nx = max(1, (ax / CELL_YD).toInt()); val ny = max(1, (ay / CELL_YD).toInt())
            val d = FloatArray(nx * ny)
            // the ground's cost at each cell's middle (b-route-terrain), 1 without it
            val terr = FloatArray(nx * ny) { 1f }
            if (ground != null) for (j in 0 until ny) for (i in 0 until nx) terr[j * nx + i] = ground((i + 0.5) / nx, (j + 0.5) / ny)
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
            return DangerGrid(nx, ny, d, ax, ay, terr)
        }

        /** a checkpoint within this many yards of the player is passed (the user on test45: the next one then leads). test68 (the user:
         *  the nearer marker full until 25 yd, gone when you reach it): 4 yd; test70 (the user: the fade 25 -> 10, gone at 10) - 10 yd,
         *  the add-on's C.RT.NEAR: the pin moves on exactly as its marker is gone. A runner (about 7 yd between position reads) who
         *  steps past it between two reads is caught by [PAST_YD] at the next one; one walking a few yards beside it too */
        const val PASS_YD = 10.0
        /** past the line across the leg through the checkpoint by this much (the next stretch's way): passed (a margin, so a player
         *  coming up beside a sharp bend is not taken as past it; 8 -> 5 yd with test68's reached radius) */
        const val PAST_YD = 5.0
        /** nearer the next point than the checkpoint is by this much, while within [SKIRT_YD] of it: passed (skirted, the right way) */
        const val NEARER_YD = 15.0
        const val SKIRT_YD = 80.0

        /**
         * [leg] (the player first) less the bends already passed; null = no bend left (the line goes straight). The user on test54: "I
         * sometimes walk past them by accident and it makes me go all the way back even if I'm going the right way". A bend is passed
         * when the player is within [PASS_YD] of it, OR past the line across the leg through it (by [PAST_YD] along the next stretch),
         * OR nearer the next point than the bend itself is (by [NEARER_YD], within [SKIRT_YD] of it).
         */
        fun passed(leg: List<DoubleArray>, ax: Double, ay: Double): List<DoubleArray>? {
            var l = leg
            while (l.size > 2 && isPassed(l[0], l[1], l[2], ax, ay)) l = listOf(l[0]) + l.drop(2)
            return l.takeIf { it.size > 2 }
        }

        /** the player [p] has passed checkpoint [c] on the way to [n] */
        fun isPassed(p: DoubleArray, c: DoubleArray, n: DoubleArray, ax: Double, ay: Double): Boolean {
            val pc = hypot((c[0] - p[0]) * ax, (c[1] - p[1]) * ay)
            if (pc <= PASS_YD) return true
            val vx = (n[0] - c[0]) * ax; val vy = (n[1] - c[1]) * ay
            val vl = hypot(vx, vy)
            if (vl > 1e-6) {
                val along = ((p[0] - c[0]) * ax * vx + (p[1] - c[1]) * ay * vy) / vl
                if (along >= PAST_YD) return true
            }
            val pn = hypot((n[0] - p[0]) * ax, (n[1] - p[1]) * ay)
            return pc <= SKIRT_YD && pn < vl - NEARER_YD
        }

        /** the yards of a polyline on a map of [ax] x [ay] yards */
        fun yards(pts: List<DoubleArray>, ax: Double, ay: Double): Double =
            (1 until pts.size).sumOf { hypot((pts[it][0] - pts[it - 1][0]) * ax, (pts[it][1] - pts[it - 1][1]) * ay) }
    }
}
