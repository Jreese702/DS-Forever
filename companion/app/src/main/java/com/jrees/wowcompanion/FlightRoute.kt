package com.jrees.wowcompanion

/**
 * THE FLIGHT SUGGESTION (pure: tools/map_test). The user, 2026-10-07: "if the quest was far or another zone, if it gave me the
 * shortest path to that zone, whether that be flight pat or walking (a marker to a flight master ...)"; their picks: the edge arrow
 * wears the step's icon, a flight only "when clearly faster", walking stays the straight arrow (the game gives no zone exits).
 *
 * MEASURED: the game names the continent's known flight points (Map.lua map.fly, MapTaxiNodeInfo.isUndiscovered). GUESSED: which
 * of them connect (only readable at a flight master: known points on one continent are taken as connected, as classic's network
 * mostly is) and how long a flight takes (by distance: [FLY_YD_S], plus [OVERHEAD_S] for the flight master and the landing).
 */
object FlightRoute {
    const val RUN_YD_S = 7.0          // on foot (the player's level-6 character; a mount comes later)
    const val FLY_YD_S = 30.0         // a classic flight, roughly
    const val OVERHEAD_S = 30.0       // talking to the flight master, take-off, landing
    const val CLEARLY = 0.7           // "clearly faster": the flight takes at most 70 % of the walk
    const val MIN_WALK_YD = 300.0     // nearer than this, always walk

    /**
     * From ([px], [py]) to the quest at ([qx], [qy]), with the known flight points [nodes] (x, y each), all in ONE map's 0..1 (the
     * nodes may lie outside it), [ydX] / [ydY] yards per unit: the index of the node to fly FROM, or null = walk. The flight goes
     * from the known point nearest the player to the known point nearest the quest, and is chosen only when clearly faster.
     */
    fun pick(px: Double, py: Double, qx: Double, qy: Double, nodes: List<DoubleArray>, ydX: Double, ydY: Double): Int? {
        if (nodes.size < 2 || ydX <= 0.0 || ydY <= 0.0) return null
        fun d(ax: Double, ay: Double, bx: Double, by: Double) = Math.hypot((ax - bx) * ydX, (ay - by) * ydY)
        val walk = d(px, py, qx, qy)
        if (walk < MIN_WALK_YD) return null
        val a = nodes.indices.minByOrNull { d(px, py, nodes[it][0], nodes[it][1]) } ?: return null
        val b = nodes.indices.minByOrNull { d(qx, qy, nodes[it][0], nodes[it][1]) } ?: return null
        if (a == b) return null
        val na = nodes[a]; val nb = nodes[b]
        val fly = d(px, py, na[0], na[1]) / RUN_YD_S + d(na[0], na[1], nb[0], nb[1]) / FLY_YD_S + d(nb[0], nb[1], qx, qy) / RUN_YD_S + OVERHEAD_S
        return if (fly <= CLEARLY * walk / RUN_YD_S) a else null
    }
}
