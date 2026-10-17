package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * THE WAYS OUT OF A MAP (b-route-city-exits; the user on test20 in Stormwind: "Pathing on quests is weird in stormwind" - a Westfall
 * quest pointed at the city map's bottom-left edge, an Ironforge one at its top-right edge; the user's picks: ALL of
 *   (a) LEARNED exits: where this player actually left a map on foot, and where a transport (the tram, a boat, a portal) set them
 *       down - read from the game's own positions at runtime, kept on the device ([Learner]);
 *   (b) a small hand TABLE of the capitals' gates and the Deeprun Tram ([TABLE]; the user approved it 2026-10-16). Its points are
 *       ESTIMATES until checked on the Thor (the map header's coordinates, or a learned crossing replacing them);
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
     * THE APPROVED TABLE (the user, 2026-10-16: "(b) the hand table of each capital's gates (approved now)", and the Deeprun Tram in
     * it). ESTIMATES (my reading of the classic maps), to be checked on the Thor: a learned crossing of the same way replaces its
     * entry. Only the ways I am fairly sure of are here; the rest are learned.
     */
    val TABLE = listOf(
        Exit(1453, "Stormwind Gates", x = 0.715, y = 0.90),
        Exit(1453, "Deeprun Tram", x = 0.642, y = 0.082, toMap = 1455, toX = 0.762, toY = 0.512, rideYd = TRAM_YD),
        Exit(1455, "Deeprun Tram", x = 0.762, y = 0.512, toMap = 1453, toX = 0.642, toY = 0.082, rideYd = TRAM_YD),
    )

    /** a way out of the current map, in ITS 0..1: where to walk, and where the way ends (the transport's far end, or the same) */
    class Way(val exit: Exit, val x: Double, val y: Double, val endX: Double, val endY: Double)

    /**
     * The best way from the player ([px], [py]) toward [far] (all this map's 0..1; [ax] / [ay] yards across / down): the least walk
     * to the way, plus its ride, plus the straight yards from where it ends to the quest. Null when there is none.
     */
    fun pick(px: Double, py: Double, far: DoubleArray, ways: List<Way>, ax: Double, ay: Double): Way? {
        fun d(x0: Double, y0: Double, x1: Double, y1: Double) = hypot((x1 - x0) * ax, (y1 - y0) * ay)
        return ways.minByOrNull { w -> d(px, py, w.x, w.y) + w.exit.rideYd + d(w.endX, w.endY, far[0], far[1]) }
    }

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
            lastMap = map; lastCont = cont; lastWx = wx; lastWy = wy; lastAt = nowMs; lastTaxi = taxi
            return learned
        }

        /** the ways learned out of [map] */
        fun of(map: Int): List<Exit> = (store.get("x:$map") ?: "").split(';').mapNotNull { parse(map, it) }

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
            const val FOOT_YD = 60.0            // the two places of a walk across a map's border are this close
            const val MERGE_YD = 40.0
            const val MAX_PER_MAP = 8
        }
    }
}
