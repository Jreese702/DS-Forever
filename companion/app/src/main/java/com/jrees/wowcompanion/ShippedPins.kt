package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * THE GAME PIN ON THE SHIPPED ROUTE (the user, 2026-10-19: "Ship our path network"; MapView.routePin, b-route-waypoint): the route's
 * CHECKPOINTS - its bends and the network's NAMED checkpoints it passes (towns, stops: [ShippedNet.Section.checkpoints]) - the pin on
 * the next one, the passed ones dropped for good ([DangerGrid.passed]; test54: "it makes me go all the way back even if I'm going the
 * right way"), planned again when the player strays [SafePathV2.OFF_ROUTE_YD] off it or walks [REPLAN_YD] on, a new way taken then only
 * when [SafePathV2.HYSTERESIS] shorter than the kept one's rest. Pure: world yards (x north, y west), MapView maps them.
 */
object ShippedPins {
    const val ON_ROUTE_YD = 8.0        // a named checkpoint this near a stretch of the route is on it
    const val REPLAN_YD = 30.0         // walked this far since the last plan: planned again (the danger leg's DANGER_MOVE_YD)
    /**
     * THE CLOSEST TWO ALWAYS (the user on test65: "sometimes I see the marker all the way at the very end, like 1000 yards away. I should
     * always see the closest two checkpoints"): a stretch longer than [STRAIGHT_MAX_YD] gets a WAYMARK every [WAYMARK_YD] from its own
     * start (fixed points: they never move with the player; passed and dropped as any checkpoint), so the pin and the next one are
     * never a long straight away and the objective is never shown in their place mid-route
     */
    const val WAYMARK_YD = 100.0
    const val STRAIGHT_MAX_YD = 150.0
    /** a waymark's name in [Leg.names] (not a place: the pin's log says "waymark") */
    const val WAYMARK = ""


    /** a route's checkpoints: [pts] world (the player first, the goal last), [names] beside them (null: a bend, the player, the goal) */
    class Leg(val pts: List<DoubleArray>, val names: List<String?>) {
        init { require(pts.size == names.size) }
        /** the next checkpoint's words for the pin's log: its name, else "bend i of n" */
        fun nextWords(): String? = if (pts.size <= 2) null else when (val n = names[1]) {
            null -> "bend 1 of ${pts.size - 2}"
            WAYMARK -> "a waymark on a long straight (1 of ${pts.size - 2})"
            else -> "\"$n\""
        }
        /** the yards from [player] to the next checkpoint (the pin), or to the goal when none is left */
        fun nextYd(player: DoubleArray): Double = pts.getOrNull(1)?.let { hypot(it[0] - player[0], it[1] - player[1]) } ?: 0.0
    }

    /** [sec]'s named checkpoints (id, name, world point); a v1 file with none listed: its named nodes */
    fun named(sec: ShippedNet.Section): List<Pair<String, DoubleArray>> {
        val ids = sec.checkpoints.toHashSet()
        return sec.net.nodes.filter { !it.name.isNullOrEmpty() && (ids.isEmpty() || it.id in ids) }
            .map { it.name!! to doubleArrayOf(it.wx, it.wy) }
    }

    /**
     * [pts] (a route, world) with the [named] checkpoints it passes put in at their places: a bend within [ON_ROUTE_YD] of one takes
     * its name; one beside a straight stretch is put in on it (a road's town, its bends tidied away). The player's own spot and the
     * goal's are never named. Each name once, at its first pass.
     */
    fun checkpoints(pts: List<DoubleArray>, named: List<Pair<String, DoubleArray>>): Leg {
        if (pts.size < 2) return Leg(pts, pts.map { null })
        val outP = ArrayList<DoubleArray>(); val outN = ArrayList<String?>()
        val used = HashSet<String>()
        outP.add(pts[0]); outN.add(null)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy; val len = Math.sqrt(l2)
            // the named ones beside this stretch (not at its ends), in order along it
            val on = named.mapNotNull { (n, q) ->
                if (n in used || l2 <= 0.0) return@mapNotNull null
                val t = ((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / l2
                if (t * len <= ON_ROUTE_YD || (1 - t) * len <= ON_ROUTE_YD) return@mapNotNull null
                val d = hypot(a[0] + t * dx - q[0], a[1] + t * dy - q[1])
                if (d <= ON_ROUTE_YD) Triple(n, t, doubleArrayOf(a[0] + t * dx, a[1] + t * dy)) else null
            }.sortedBy { it.second }
            for ((n, _, p) in on) if (used.add(n)) { outP.add(p); outN.add(n) }
            outP.add(b)
            if (i == pts.size - 1) { outN.add(null); continue }
            val at = named.filter { it.first !in used }.minByOrNull { hypot(it.second[0] - b[0], it.second[1] - b[1]) }
                ?.takeIf { hypot(it.second[0] - b[0], it.second[1] - b[1]) <= ON_ROUTE_YD }
            if (at != null) used.add(at.first)
            outN.add(at?.first)
        }
        return Leg(outP, outN)
    }

    /** [leg] with a [WAYMARK] every [WAYMARK_YD] along each stretch longer than [STRAIGHT_MAX_YD] (from the stretch's start; none within
     *  half a spacing of its end) */
    fun waymarks(leg: Leg): Leg {
        if (leg.pts.size < 2) return leg
        val outP = ArrayList<DoubleArray>(); val outN = ArrayList<String?>()
        outP.add(leg.pts[0]); outN.add(leg.names[0])
        for (i in 1 until leg.pts.size) {
            val a = leg.pts[i - 1]; val b = leg.pts[i]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            if (len > STRAIGHT_MAX_YD) {
                var k = 1
                while (len - k * WAYMARK_YD >= WAYMARK_YD / 2) {
                    val t = k * WAYMARK_YD / len
                    outP.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)); outN.add(WAYMARK); k++
                }
            }
            outP.add(b); outN.add(leg.names[i])
        }
        return Leg(outP, outN)
    }

    /**
     * [leg] less the checkpoints [player] has passed ([DangerGrid.passed], on a map of [ax] x [ay] yd through [onMap]): the same leg when
     * none is, else a new one (its first point kept: the plan's start). Never one already dropped comes back.
     */
    fun dropPassed(leg: Leg, player: DoubleArray, onMap: (DoubleArray) -> DoubleArray, ax: Double, ay: Double): Leg {
        if (leg.pts.size <= 2) return leg
        val m = listOf(onMap(player)) + leg.pts.drop(1).map(onMap)
        val left = DangerGrid.passed(m, ax, ay)?.size ?: 2
        val dropped = m.size - left
        if (dropped <= 0) return leg
        return Leg(listOf(leg.pts[0]) + leg.pts.drop(1 + dropped), listOf(leg.names[0]) + leg.names.drop(1 + dropped))
    }

    /** the kept leg's rest from [player] on (its first point skipped: the player stands past it) */
    fun restYd(leg: Leg, player: DoubleArray): Double = ShippedRoute.length(listOf(player) + leg.pts.drop(1))

    /** a replan's answer: the new way ([newYd]) taken over the kept one's rest ([keptYd]) - always when [strayed] off the kept one or
     *  nothing is kept, else only when [SafePathV2.HYSTERESIS] shorter */
    fun takeNew(keptYd: Double?, newYd: Double, strayed: Boolean): Boolean = strayed || keptYd == null || SafePathV2.takeNew(keptYd, newYd)

    /** the pin's log words for the shipped route: the next checkpoint, "(shipped network)" and the file's "may be out of date" mark */
    fun pinWhy(leg: Leg, to: String, stale: String?, player: DoubleArray? = null): String =
        "checkpoint: ${leg.nextWords() ?: "none"}" + (player?.let { ", ${leg.nextYd(it).toInt()} yd ahead" } ?: "") +
            " on the way to $to (shipped network)" + (stale?.let { "; may be out of date ($it)" } ?: "")
}
