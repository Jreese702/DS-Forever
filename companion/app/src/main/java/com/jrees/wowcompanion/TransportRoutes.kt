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

    /** a ride: from dock [a] (continent [ca]) to dock [b] (continent [cb]) on [path]; positions in world yards (x north, y west) */
    class Ride(val path: Int, val ca: Int, val a: FloatArray, val cb: Int, val b: FloatArray)

    /** the transport paths' rides: consecutive stops, and the last back to the first when the path ends where it started */
    fun rides(nodes: List<Node>): List<Ride> {
        val out = ArrayList<Ride>()
        for ((path, ns) in nodes.groupBy { it.path }) {
            val stops = ns.sortedBy { it.index }.filter { it.flags and STOP != 0 }
            if (stops.size < 2) continue
            for (i in 0 until stops.size - 1) out.add(ride(path, stops[i], stops[i + 1]))
        }
        return out
    }

    private fun ride(path: Int, s: Node, t: Node) =
        Ride(path, s.cont, floatArrayOf(s.x, s.y, s.z), t.cont, floatArrayOf(t.x, t.y, t.z))

    /** the device file (harvest/transports.json): the rides and how the table read, for the log and the route planner */
    fun toJson(nodes: List<Node>, rides: List<Ride>): JSONObject {
        val a = JSONArray()
        for (r in rides) a.put(JSONObject().put("p", r.path).put("ca", r.ca).put("cb", r.cb)
            .put("a", JSONArray().put(r.a[0].toDouble()).put(r.a[1].toDouble()).put(r.a[2].toDouble()))
            .put("b", JSONArray().put(r.b[0].toDouble()).put(r.b[1].toDouble()).put(r.b[2].toDouble())))
        return JSONObject().put("v", 1).put("nodes", nodes.size).put("paths", nodes.map { it.path }.toSet().size)
            .put("transportPaths", rides.map { it.path }.toSet().size).put("rides", a)
    }

    /** one line for the WCMAP log: how much the table gave */
    fun summary(nodes: List<Node>, rides: List<Ride>): String {
        val byCont = rides.groupBy { it.ca }.entries.sortedBy { it.key }.joinToString(", ") { "continent ${it.key}: ${it.value.size}" }
        return "transports: ${nodes.size} path nodes, ${nodes.map { it.path }.toSet().size} paths, " +
            "${rides.map { it.path }.toSet().size} transport paths, ${rides.size} rides ($byCont)"
    }
}
