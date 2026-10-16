package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * WHERE THE CURSOR'S DOTTED LINE GOES (pure; the user agreed the design, 2026-10-16 via tracking: "it does"):
 *   1. it follows the highlighted quest;
 *   2. to the NEAREST STILL-NEEDED GOAL on this map: the game's own point for the quest and QuestieDB's spots of its UNFINISHED
 *      objectives, the nearest on the ground; once every objective is done, the turn-in (the game's, else QuestieDB's);
 *   3. by the ROUTE when the goal is not on this map: a known AND connected flight master when flying is clearly faster
 *      ([FlightRoute]; "Flight to <node>"), else the game's own next waypoint when it gives one (the focused quest's), else the
 *      map's edge toward the quest ("Exit east"). Boats / trams / zeppelins wait for the transport probe.
 * Points are in the map's 0..1 space; [ax] / [ay] are the yards across / down it, so "nearest" and the yards are on the ground.
 */
object CursorRoute {
    enum class Kind { GOAL, FLIGHT, WAYPOINT, EXIT }

    /** where the dots go ([x], [y], this map's 0..1), what kind of step that is, its words (null = none) */
    data class Route(val kind: Kind, val x: Double, val y: Double, val label: String?)

    /** a known flight point on this map's continent, in this map's coordinates */
    class Node(val x: Double, val y: Double, val id: Int, val name: String)

    /**
     * The still-needed goals of quest [qid] ON THIS MAP ([mapId]): from the map document's `quests` entry [mq] (the game's point, or
     * its turn-in when done) and the quests document's entry [e] (QuestieDB's `qdb` spots: the unfinished objectives' by their index,
     * or the turn-in once ready; `obj[i].f` = objective i + 1 done).
     */
    fun goals(mq: JSONObject?, e: JSONObject?, mapId: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val ready = e?.optBoolean("ready") == true || e?.optBoolean("done") == true || mq?.optBoolean("done") == true
        fun add(x: Double, y: Double) { if (!x.isNaN() && !y.isNaN() && x in 0.0..1.0 && y in 0.0..1.0) out.add(doubleArrayOf(x, y)) }
        if (mq != null) {
            if (ready) add(mq.optDouble("tx", Double.NaN), mq.optDouble("ty", Double.NaN))
            else add(mq.optDouble("x", Double.NaN), mq.optDouble("y", Double.NaN))
        }
        val qdb = e?.optJSONObject("qdb")
        if (qdb != null) {
            val done = HashSet<Int>()
            e.optJSONArray("obj")?.let { a -> for (k in 0 until a.length()) if (a.optJSONObject(k)?.optBoolean("f") == true) done.add(k + 1) }
            val pts = qdb.optJSONArray(if (ready) "turnin" else "obj")
            if (pts != null && !(ready && out.isNotEmpty())) for (k in 0 until pts.length()) {
                val p = pts.optJSONObject(k) ?: continue
                if (p.optInt("m", Int.MIN_VALUE) != mapId) continue
                if (!ready && p.has("i") && p.optInt("i") in done) continue          // that objective is finished: not a goal
                add(p.optDouble("x", Double.NaN), p.optDouble("y", Double.NaN))
            }
        }
        return out
    }

    /**
     * The step for the line. [px] / [py] the player; [goals] this map's still-needed goals ([goals]); [far] the quest's place when it
     * is not on this map (this map's coordinates, outside 0..1), null = unknown; [way] / [wayText] the game's next waypoint for it on
     * this map and its words; [nodes] the known flight points, [connected] whether a flight joins two of them.
     */
    fun plan(px: Double, py: Double, ax: Double, ay: Double, goals: List<DoubleArray>, far: DoubleArray?, way: DoubleArray?,
             wayText: String?, nodes: List<Node>, connected: (Int, Int) -> Boolean): Route? {
        if (px.isNaN() || py.isNaN()) return null
        if (goals.isNotEmpty()) {
            val g = goals.minByOrNull { hypot((it[0] - px) * ax, (it[1] - py) * ay) }!!
            return Route(Kind.GOAL, g[0], g[1], null)
        }
        if (far == null) return null
        val k = FlightRoute.pick(px, py, far[0], far[1], nodes.map { doubleArrayOf(it.x, it.y) }, ax, ay) { i, j ->
            nodes[i].id != 0 && nodes[j].id != 0 && connected(nodes[i].id, nodes[j].id)
        }
        if (k != null) {
            val n = nodes[k]
            return Route(Kind.FLIGHT, n.x, n.y, if (n.name.isNotEmpty()) "Flight to ${n.name}" else "Flight master")
        }
        if (way != null) return Route(Kind.WAYPOINT, way[0], way[1], wayText?.takeIf { it.isNotEmpty() } ?: "Exit ${compass(px, py, way[0], way[1], ax, ay)}")
        val e = edge(px, py, far[0], far[1]) ?: return null
        return Route(Kind.EXIT, e[0], e[1], "Exit ${compass(px, py, far[0], far[1], ax, ay)}")
    }

    /** where the way from ([px], [py]) to ([qx], [qy]) leaves the map's 0..1 square, or null when it does not */
    fun edge(px: Double, py: Double, qx: Double, qy: Double): DoubleArray? {
        val dx = qx - px; val dy = qy - py
        var t = 1.0
        if (dx > 0) t = minOf(t, (1.0 - px) / dx) else if (dx < 0) t = minOf(t, (0.0 - px) / dx)
        if (dy > 0) t = minOf(t, (1.0 - py) / dy) else if (dy < 0) t = minOf(t, (0.0 - py) / dy)
        if (t >= 1.0 || t < 0.0) return null
        return doubleArrayOf(px + dx * t, py + dy * t)
    }

    /** the way's compass word, on the ground (the map's y runs south) */
    fun compass(px: Double, py: Double, qx: Double, qy: Double, ax: Double, ay: Double): String {
        val e = (qx - px) * ax; val n = -(qy - py) * ay
        if (abs(e) < 1e-9 && abs(n) < 1e-9) return "here"
        val deg = (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0          // 0 = north, 90 = east
        return listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((deg + 22.5) / 45.0).toInt() % 8]
    }

    /** yards on the ground between two points of this map */
    fun yards(px: Double, py: Double, qx: Double, qy: Double, ax: Double, ay: Double): Int = hypot((qx - px) * ax, (qy - py) * ay).toInt()
}
