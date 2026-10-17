package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * BOATS, ZEPPELINS AND TRAMS FROM THE GAME'S OWN FILES (b-route-transports; the user, 2026-10-17: "If the game files are easy why
 * weren't we using those in the first place?"). The client's TaxiPathNode table (fileDataID [DB_TAXI_PATH_NODE]; read on the device by
 * the art harvest, never shipped) holds every path a taxi or a TRANSPORT moves along: per node its path, index, continent, flags and
 * delay, and its world position. A transport's path STOPS at its docks (flag [STOP], the delay its wait there; measured on the PC
 * client: 15 such paths, stops waiting 30-60 s), a flight's does not. So the stops are the docks, and two stops after each other on a
 * path are a ride. Pure: the rows in, the rides out.
 */
object TransportRoutes {
    const val DB_TAXI_PATH_NODE = 1000437
    const val STOP = 2                       // the node's flag: a stop (a dock)

    /** one node of TaxiPathNode */
    class Node(val path: Int, val index: Int, val cont: Int, val flags: Int, val delay: Int, val x: Float, val y: Float, val z: Float)

    /** the game's run speed, yards a second: a ride's time as walking yards */
    const val RUN_YD_S = 7.0

    /**
     * a ride: from dock [a] (continent [ca]) to dock [b] (continent [cb]) on [path]; positions in world yards (x north, y west).
     * [yd] its cost in walking yards, from the game's own path: the yards sailed between the two stops (a jump between continents
     * counts nothing) and the wait at the first dock (its delay, seconds at [RUN_YD_S]); 0 = not known (an older file).
     */
    class Ride(val path: Int, val ca: Int, val a: FloatArray, val cb: Int, val b: FloatArray, val yd: Double = 0.0) {
        /** the same boat the other way (the transports run both ways between their docks) */
        fun back() = Ride(path, cb, b, ca, a, yd)
    }

    /** the transport paths' rides: consecutive stops, and the last back to the first when the path ends where it started */
    fun rides(nodes: List<Node>): List<Ride> {
        val out = ArrayList<Ride>()
        for ((path, ns) in nodes.groupBy { it.path }) {
            val stops = ns.sortedBy { it.index }.filter { it.flags and STOP != 0 }
            if (stops.size < 2) continue
            val all = ns.sortedBy { it.index }
            for (i in 0 until stops.size - 1) out.add(ride(path, stops[i], stops[i + 1], all))
        }
        return out
    }

    private fun ride(path: Int, s: Node, t: Node, all: List<Node>): Ride {
        var yd = s.delay * RUN_YD_S
        val leg = all.filter { it.index in s.index..t.index }
        for (k in 1 until leg.size) {
            val p = leg[k - 1]; val q = leg[k]
            if (p.cont == q.cont) yd += Math.sqrt(((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y) + (q.z - p.z) * (q.z - p.z)).toDouble())
        }
        return Ride(path, s.cont, floatArrayOf(s.x, s.y, s.z), t.cont, floatArrayOf(t.x, t.y, t.z), yd)
    }

    /** the device file (harvest/transports.json): the rides and how the table read, for the log and the route planner */
    fun toJson(nodes: List<Node>, rides: List<Ride>): JSONObject {
        val a = JSONArray()
        for (r in rides) a.put(JSONObject().put("p", r.path).put("ca", r.ca).put("cb", r.cb)
            .put("a", JSONArray().put(r.a[0].toDouble()).put(r.a[1].toDouble()).put(r.a[2].toDouble()))
            .put("b", JSONArray().put(r.b[0].toDouble()).put(r.b[1].toDouble()).put(r.b[2].toDouble()))
            .put("yd", Math.round(r.yd).toInt()))
        return JSONObject().put("v", 2).put("nodes", nodes.size).put("paths", nodes.map { it.path }.toSet().size)
            .put("transportPaths", rides.map { it.path }.toSet().size).put("rides", a)
    }

    /** the device file read back: its rides (none when it is not there or does not read) */
    fun fromJson(o: JSONObject?): List<Ride> {
        val a = o?.optJSONArray("rides") ?: return emptyList()
        val out = ArrayList<Ride>()
        fun v(x: JSONArray?) = if (x == null || x.length() < 3) null else floatArrayOf(x.optDouble(0).toFloat(), x.optDouble(1).toFloat(), x.optDouble(2).toFloat())
        for (i in 0 until a.length()) {
            val d = a.optJSONObject(i) ?: continue
            val pa = v(d.optJSONArray("a")) ?: continue; val pb = v(d.optJSONArray("b")) ?: continue
            if (pa.any { it.isNaN() } || pb.any { it.isNaN() }) continue
            out.add(Ride(d.optInt("p"), d.optInt("ca", -1), pa, d.optInt("cb", -1), pb, d.optDouble("yd", 0.0)))
        }
        return out
    }

    /**
     * THE RIDES AS WAYS OUT (b-route-rides; the user, 2026-10-17: "GO on the route planner using the game's rides (boats / zeppelins
     * as ways out)"): every ride both ways from a dock on [cont]. A ride staying on [cont] ends at its far dock ([Hop.tailYd] NaN: the
     * planner measures on from there); a ride to another continent's quest ([spots]: [continent, wx, wy] each) carries the yards
     * from where it lands to the nearest spot there ([Hop.tailYd]). [spots] empty = the same-continent rides only.
     */
    class Hop(val ride: Ride, val tailYd: Double)
    fun hops(rides: List<Ride>, cont: Int, spots: List<DoubleArray>): List<Hop> {
        val out = ArrayList<Hop>()
        for (r0 in rides) for (rd in listOf(r0, r0.back())) {
            if (rd.ca != cont) continue
            if (rd.cb == cont) { out.add(Hop(rd, Double.NaN)); continue }
            val near = spots.filter { it.size >= 3 && it[0].toInt() == rd.cb }
                .minOfOrNull { Math.hypot(it[1] - rd.b[0], it[2] - rd.b[1]) } ?: continue
            out.add(Hop(rd, near))
        }
        return out
    }

    /** one line for the WCMAP log: how much the table gave */
    fun summary(nodes: List<Node>, rides: List<Ride>): String {
        val byCont = rides.groupBy { it.ca }.entries.sortedBy { it.key }.joinToString(", ") { "continent ${it.key}: ${it.value.size}" }
        return "transports: ${nodes.size} path nodes, ${nodes.map { it.path }.toSet().size} paths, " +
            "${rides.map { it.path }.toSet().size} transport paths, ${rides.size} rides ($byCont)"
    }
}
