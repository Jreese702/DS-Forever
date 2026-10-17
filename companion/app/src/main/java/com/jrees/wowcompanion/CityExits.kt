package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * THE WAYS OUT OF A MAP (b-route-city-exits; the user on test20 in Stormwind: "Pathing on quests is weird in stormwind" - a Westfall
 * quest pointed at the city map's bottom-left edge, an Ironforge one at its top-right edge; the user's picks: ALL of
 *   (a) LEARNED exits: where this player actually left a map on foot, and where a transport (the tram, a boat, a portal) set them
 *       down - read from the game's own positions at runtime, kept on the device ([Learner]);
 *   (b) a TABLE of only what was MEASURED on the Thor ([TABLE]; the user's later ruling: "There must be a better way to get these
 *       locations" - learn now, pathing later: no estimates);
 *   (c) a flight first in a city, "only if we know for sure that this character has the flight paths unlocked" (CursorRoute).
 * The game itself names no exits: C_Map.GetMapLinksForMap answered [] in Stormwind on test20, the taxi rows carry no transport kit.)
 * Pure: points are world yards ([Exit.wx] / [Exit.wy], the game's own axes) or a map's 0..1, converted by the caller.
 */
object CityExits {
    /** the capitals' uiMap ids (classic): Stormwind, Orgrimmar, Ironforge, Thunder Bluff, Darnassus, Undercity */
    val CITIES = setOf(1453, 1454, 1455, 1456, 1457, 1458)

    /**
     * One way out of [map]. [x] / [y] in that map's 0..1 (a table entry) or NaN when [wx] / [wy] (world yards) hold it (a learned one).
     * A transport sets down at [toMap]'s ([toX], [toY]) (0..1) or ([toWx], [toWy]) (world); [rideYd] its cost in walking yards.
     */
    data class Exit(val map: Int, val name: String, val x: Double = Double.NaN, val y: Double = Double.NaN,
                    val wx: Double = Double.NaN, val wy: Double = Double.NaN, val toMap: Int = 0,
                    val toX: Double = Double.NaN, val toY: Double = Double.NaN, val toWx: Double = Double.NaN,
                    val toWy: Double = Double.NaN, val rideYd: Double = 0.0, val learned: Boolean = false) {
        val transport get() = rideYd > 0.0          // a ride (the tram, a boat, a portal); a way on foot has none
    }

    /** a tram ride, in walking yards (about 1 minute aboard at ~7 yd/s) */
    const val TRAM_YD = 420.0

    /**
     * THE TABLE: only what was MEASURED (the user, 2026-10-16: "There must be a better way to get these locations. We can't do this
     * for every single intended zone transition" - learn now, pathing later). The Deeprun Tram's Stormwind entrance, read on the Thor
     * (69.0, 31.0, the player standing there); its Ironforge end is LEARNED from the first ride across (until then the route takes
     * Ironforge's own map centre, from the game's world map data, as where the ride ends). Every other way out is learned.
     */
    val TABLE = listOf(
        Exit(1453, "Deeprun Tram", x = 0.690, y = 0.310, toMap = 1455, rideYd = TRAM_YD),
    )

    /** a way out of the current map, in ITS 0..1: where to walk, and where the way ends (the transport's far end, or the same).
     *  [tailYd]: a ride to ANOTHER continent, the yards from where it lands to the quest there (its end cannot be put on this map) */
    class Way(val exit: Exit, val x: Double, val y: Double, val endX: Double, val endY: Double, val tailYd: Double = Double.NaN)

    /**
     * The best way from the player ([px], [py]) toward [far] (all this map's 0..1; [ax] / [ay] yards across / down): the least walk
     * to the way, plus its ride, plus the straight yards from where it ends to the quest (a way to another continent: its own
     * [Way.tailYd]). [far] null = the quest is on no map of this continent: only such ways count. Null when there is none.
     */
    fun pick(px: Double, py: Double, far: DoubleArray?, ways: List<Way>, ax: Double, ay: Double): Way? =
        choose(px, py, far, ways, ax, ay).way

    /** the choice and why: the [way] taken (null = none worth it), its [cost], the [runnerUp] and its cost, the straight [direct] */
    class Choice(val way: Way?, val cost: Double, val runnerUp: Way?, val runnerCost: Double, val direct: Double)

    /** a way's identity across documents (the hysteresis' memory): where it leads and where it starts, to the yard's tenth of a map */
    fun key(w: Way): String = "${w.exit.toMap}:${Math.round(w.x * 200)}:${Math.round(w.y * 200)}"

    /**
     * THE BEST WAY OUT, WEIGHED (the user on test40, Vanilla: "my marker is making me leave stormwind and then re-enter it over and
     * over for my quest to go to westfall" - in Elwynn the only way known was the learned gate BACK into Stormwind, and any way at
     * all used to win over simply heading for the quest). Rules:
     *  - BACK into [cameFrom] (the map the player just came from; 0 = no such rule: the target is in it) only when it makes real
     *    progress: a ride cheaper than going straight, or a way whose far end is nearer the target than the player is now by
     *    [PROGRESS] of the straight yards (at least [PROGRESS_YD]). Just through the Stormwind gate into Elwynn, the gate back gains
     *    nothing toward Westfall and is dropped; in Stormwind, come from Elwynn, the gate out still gains (Westfall lies past it);
     *  - a walk-only way outside a city ([inCity] false) only when its detour is small ([DETOUR] of the straight yards): no known
     *    way toward the target's zone = the straight bearing / the game's waypoint / the edge toward the TARGET instead;
     *  - a RIDE only when it beats going straight, anywhere (test42: in Elwynn a ride costing 18,757 yd from a far dock won over
     *    the quest 1,561 yd straight ahead: "It's saying to fly to hillsbrad but I'm standing outside stormwind");
     *  - HYSTERESIS: the way chosen last time ([keep], a [key]) stays while it costs at most [STICK] more than the best.
     */
    fun choose(px: Double, py: Double, far: DoubleArray?, ways: List<Way>, ax: Double, ay: Double, cameFrom: Int = 0,
               inCity: Boolean = true, keep: String? = null): Choice {
        fun d(x0: Double, y0: Double, x1: Double, y1: Double) = hypot((x1 - x0) * ax, (y1 - y0) * ay)
        fun tail(w: Way): Double? = if (!w.tailYd.isNaN()) w.tailYd else far?.let { d(w.endX, w.endY, it[0], it[1]) }
        val direct = far?.let { d(px, py, it[0], it[1]) } ?: Double.NaN
        val costed = ways.mapNotNull { w -> tail(w)?.let { t -> w to d(px, py, w.x, w.y) + w.exit.rideYd + t } }.filter { (w, c) ->
            val gains = !direct.isNaN() && far != null &&
                d(w.endX, w.endY, far[0], far[1]) < direct - maxOf(PROGRESS_YD, PROGRESS * direct)
            val back = cameFrom != 0 && w.exit.toMap == cameFrom &&
                !(if (w.exit.transport) !direct.isNaN() && c < direct else gains)
            val detour = !inCity && !w.exit.transport && !direct.isNaN() && c > direct * DETOUR
            val slowRide = w.exit.transport && !direct.isNaN() && c >= direct
            !back && !detour && !slowRide
        }.sortedBy { it.second }
        if (costed.isEmpty()) return Choice(null, Double.NaN, null, Double.NaN, direct)
        var best = costed[0]
        if (keep != null) costed.firstOrNull { key(it.first) == keep }?.let { kept -> if (kept.second <= best.second * STICK) best = kept }
        val runner = costed.firstOrNull { it !== best }
        return Choice(best.first, best.second, runner?.first, runner?.second ?: Double.NaN, direct)
    }

    const val DETOUR = 1.25      // a walk-only way out of open country: at most this much longer than straight to the target
    const val PROGRESS = 0.15    // a way back must bring its far end this much of the straight yards nearer the target ...
    const val PROGRESS_YD = 60.0 // ... and at least this many yards
    const val STICK = 1.15       // the way chosen before keeps its place until another is this much cheaper

    /**
     * THE LEARNER (a): one map document after another. When the map changes, the last place on the old map is a way out of it: on
     * foot (the two places close, the game still talking) an exit to the new map; far apart or across a loading screen a TRANSPORT
     * (the tram, a boat, a portal) setting down where the new map's first place is. Never on a flight (the game says so: Map.lua
     * `taxi`). [Store] keeps them per old map (a few each; a new one within MERGE_YD of a kept one replaces it).
     */
    class Learner(private val store: Store) {
        interface Store { fun get(k: String): String?; fun put(k: String, v: String?) }
        private var lastMap = 0; private var lastWx = Double.NaN; private var lastWy = Double.NaN; private var lastCont = -1
        private var lastAt = 0L; private var lastTaxi = false
        // a RIDE THROUGH ANOTHER CONTINENT (the tram runs in an instance of its own): where the first continent was left, until the
        // player is back on it - on another map there, that is the ride's far end
        private var rideMap = 0; private var rideWx = Double.NaN; private var rideWy = Double.NaN; private var rideCont = -1
        private var rideAt = 0L

        /** one map document: [map], the continent, world yards, game ms; [taxi] = on a flight. Returns the way it just learned, if any */
        fun onDoc(map: Int, cont: Int, wx: Double, wy: Double, taxi: Boolean, nowMs: Long): Exit? {
            if (map == 0 || wx.isNaN() || wy.isNaN()) return null
            var learned: Exit? = null
            // a crossing within one continent only: another continent's yards (an instance's, the tram's) cannot be compared here
            if (lastMap != 0 && map != lastMap && cont == lastCont && !taxi && !lastTaxi && nowMs - lastAt < MAX_GAP_MS) {
                val gap = if (cont == lastCont) hypot(wx - lastWx, wy - lastWy) else Double.MAX_VALUE
                learned = if (gap <= FOOT_YD) Exit(lastMap, "", wx = lastWx, wy = lastWy, toMap = map, learned = true)
                    else Exit(lastMap, "", wx = lastWx, wy = lastWy, toMap = map, toWx = wx, toWy = wy, rideYd = TRAM_YD, learned = true)
                keep(learned)
            }
            // leaving the continent on foot (a tram / boat / portal into its own instance): remember where
            if (lastMap != 0 && cont != lastCont && lastCont >= 0 && !taxi && !lastTaxi && rideMap == 0) {
                rideMap = lastMap; rideWx = lastWx; rideWy = lastWy; rideCont = lastCont; rideAt = nowMs
            }
            // back on that continent: on another map, a ride from where it was left to here
            if (rideMap != 0 && cont == rideCont) {
                if (map != rideMap && !taxi && nowMs - rideAt < RIDE_MAX_MS) {
                    learned = Exit(rideMap, "", wx = rideWx, wy = rideWy, toMap = map, toWx = wx, toWy = wy, rideYd = TRAM_YD, learned = true)
                    keep(learned)
                }
                rideMap = 0
            }
            if (rideMap != 0 && nowMs - rideAt >= RIDE_MAX_MS) rideMap = 0
            lastMap = map; lastCont = cont; lastWx = wx; lastWy = wy; lastAt = nowMs; lastTaxi = taxi
            return learned
        }

        /** the ways learned out of [map] */
        fun of(map: Int): List<Exit> = (store.get("x:$map") ?: "").split(';').mapNotNull { parse(map, it) }

        /**
         * test42: "learned a way out of map 1415 (Eastern Kingdoms) to 1453" - a document with the CONTINENT as the player's map (the
         * world map open, a load) taught a way. [notZone] says which maps are not a zone or a city: the ways out of them, and the
         * ways into them, go from [maps]' entries (the ones this device kept). Returns how many went.
         */
        fun purge(maps: Collection<Int>, notZone: (Int) -> Boolean): Int {
            var gone = 0
            for (m in maps) {
                val all = of(m)
                if (all.isEmpty()) continue
                val keep = if (notZone(m)) emptyList() else all.filterNot { notZone(it.toMap) }
                if (keep.size == all.size) continue
                gone += all.size - keep.size
                store.put("x:$m", if (keep.isEmpty()) null else keep.joinToString(";") { enc(it) })
            }
            return gone
        }

        private fun keep(e: Exit) {
            val kept = of(e.map).filterNot { it.toMap == e.toMap && hypot(it.wx - e.wx, it.wy - e.wy) < MERGE_YD }
            store.put("x:${e.map}", (listOf(e) + kept).take(MAX_PER_MAP).joinToString(";") { enc(it) })
        }

        private fun enc(e: Exit) = listOf(e.toMap, e.wx, e.wy, e.toWx, e.toWy).joinToString(",") { if (it is Double && it.isNaN()) "" else it.toString() }
        private fun parse(map: Int, s: String): Exit? {
            val p = s.split(',')
            if (p.size < 5) return null
            val to = p[0].toIntOrNull() ?: return null
            val wx = p[1].toDoubleOrNull() ?: return null; val wy = p[2].toDoubleOrNull() ?: return null
            val tx = p[3].toDoubleOrNull() ?: Double.NaN; val ty = p[4].toDoubleOrNull() ?: Double.NaN
            return Exit(map, "", wx = wx, wy = wy, toMap = to, toWx = tx, toWy = ty, rideYd = if (tx.isNaN()) 0.0 else TRAM_YD, learned = true)
        }

        companion object {
            const val MAX_GAP_MS = 60_000L     // a loading screen fits; a log-out and back in a day later does not
            const val RIDE_MAX_MS = 6 * 60_000L // a tram / boat ride through its own instance, loading screens included
            const val FOOT_YD = 60.0            // the two places of a walk across a map's border are this close
            const val MERGE_YD = 40.0
            const val MAX_PER_MAP = 8
        }
    }
}
