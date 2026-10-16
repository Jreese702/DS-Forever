package com.jrees.wowcompanion

/**
 * THE FLIGHT SUGGESTION (pure: tools/map_test). The user, 2026-10-07: "if the quest was far or another zone, if it gave me the
 * shortest path to that zone, whether that be flight pat or walking (a marker to a flight master ...)"; their picks: the edge arrow
 * wears the step's icon, a flight only "when clearly faster", walking stays the straight arrow (the game gives no zone exits).
 *
 * MEASURED: the game names the continent's known flight points (Map.lua map.fly, MapTaxiNodeInfo.isUndiscovered), and which of
 * them CONNECT at a flight master (b-route-unknown-taxi, TaxiNet: the user was sent to a flight path with none connected where they
 * were; the old guess took every known point of a continent as connected). A flight is chosen only between two points [connected]
 * says a flight joins. GUESSED: how long a flight takes (by distance: [FLY_YD_S], plus [OVERHEAD_S] for the flight master and the
 * landing).
 */
object FlightRoute {
    const val RUN_YD_S = 7.0          // on foot (the player's level-6 character; a mount comes later)
    const val FLY_YD_S = 30.0         // a classic flight, roughly
    const val OVERHEAD_S = 30.0       // talking to the flight master, take-off, landing
    const val CLEARLY = 0.7           // "clearly faster": the flight takes at most 70 % of the walk
    const val MIN_WALK_YD = 300.0     // nearer than this, always walk

    /**
     * From ([px], [py]) to the quest at ([qx], [qy]), with the known flight points [nodes] (x, y each), all in ONE map's 0..1 (the
     * nodes may lie outside it), [ydX] / [ydY] yards per unit, and [connected] (index, index): a flight joins them. The index of the
     * node to fly FROM, or null = walk. Of the pairs a flight joins, the quickest (walk to the first, fly, walk from the second), and
     * only when clearly faster than walking.
     */
    fun pick(px: Double, py: Double, qx: Double, qy: Double, nodes: List<DoubleArray>, ydX: Double, ydY: Double,
             connected: (Int, Int) -> Boolean): Int? {
        if (nodes.size < 2 || ydX <= 0.0 || ydY <= 0.0) return null
        fun d(ax: Double, ay: Double, bx: Double, by: Double) = Math.hypot((ax - bx) * ydX, (ay - by) * ydY)
        val walk = d(px, py, qx, qy)
        if (walk < MIN_WALK_YD) return null
        var best: Int? = null
        var bestT = Double.MAX_VALUE
        for (a in nodes.indices) for (b in nodes.indices) {
            if (a == b || !connected(a, b)) continue
            val na = nodes[a]; val nb = nodes[b]
            val t = d(px, py, na[0], na[1]) / RUN_YD_S + d(na[0], na[1], nb[0], nb[1]) / FLY_YD_S + d(nb[0], nb[1], qx, qy) / RUN_YD_S + OVERHEAD_S
            if (t < bestT) { bestT = t; best = a }
        }
        return if (best != null && bestT <= CLEARLY * walk / RUN_YD_S) best else null
    }
}
