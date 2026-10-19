package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlin.math.hypot

/**
 * SAFEPATH V2 ROUTING ON THE DEVICE (Settings > Map > "SafePath v2 routing", default Off): a quest's way in the player's zone planned
 * by the harness's own route sequence ([SafeRoute]: the network's way with the ground's legs on the zone's lattice, the wade retry,
 * the detour cap) on the zone's CLB3 lattice ([ClimbStep]), its water ([SafePath.waters]) and the character's danger
 * ([DangerInputs]). Pure but for the zone files it reads (RouteWork.Files: never on the UI thread). Outside the zone, and wherever a
 * file is missing, null: the old routing goes on.
 *
 * Not yet on the device (the next drop): caves and lifts (CAV1), declared swims, a floating city's floors, the cross-zone planner. Off a
 * walkway's deck in a bridge's water cell the device reads the water grid's word "bridge" (no raw depth read yet).
 */
object SafePathV2 {
    /** a way planned: world points (x, y) from the start, whether it reached its goal, its yards, and what it was planned on */
    class Way(val pts: List<DoubleArray>, val reached: Boolean, val yd: Double, val stamp: String)

    /** why the last plan came out as it did (the log, the problem report) */
    @Volatile var lastWhy: String = ""; private set

    /** the zone files a plan rests on, changed: plan again */
    fun stamp(map: Int, mine: Int, level: Int): String =
        "${Climb.files.version}:${ClimbStep.zonesMade}:${SafePath.waters.version}:${SafePath.nets.version}:${SafePath.zones.version}:${DangerStore.version}:$mine:$level:${Settings.avoidDanger}:$map"

    private class Danger(val key: String, val costs: DangerInputs.Costs)
    @Volatile private var danger: Danger? = null

    /**
     * quest's way in zone [map] (world [rect], continent [rect].c) from world [from] to [to] ([toExit]: to the network's exit there) for
     * a character of side [mine] at [level]; [cancelled] asked as it runs (a newer plan). The route thread only. Null: no v2 way here
     * (a file not made yet - the lattice asked for - or a way the final check refuses); [lastWhy] says which
     */
    fun plan(ctx: Context?, map: Int, rect: MapHarvest.Rect, mine: Int, level: Int, from: DoubleArray, to: DoubleArray, toExit: Boolean,
             cancelled: () -> Boolean): Way? {
        val st = stamp(map, mine, level)
        val g = Climb.files.get(map) ?: run { ctx?.let { ClimbStep.want(it, map) }; lastWhy = "no lattice for map $map yet (asked for)"; return null }
        val wg = SafePath.waters.get(map)
        val net = SafePath.nets.get(map) ?: SafePath.Net(map, rect.c, "", emptyList(), emptyList(), emptyList())
        val zg = SafePath.zones.get(map); val ar = SafePath.areas()
        val rooms = g.solid ?: { _, _ -> false }
        val key = "$map:$mine:$level:${DangerStore.version}:${SafePath.waters.version}:${Climb.files.version}:${zg != null}"
        val dc = danger?.takeIf { it.key == key }?.costs ?: run {
            val here = DangerInputs.sided(DangerStore.spawns(map), rect, if (mine == TransportRoutes.HORDE) TransportRoutes.ALLIANCE else TransportRoutes.HORDE,
                DangerInputs.FRIENDLY_MIN_LEVEL, DangerInputs.FRIENDLY_NAMES[map] ?: emptyList())
            val own: (Double, Double) -> Boolean = if (zg != null && ar != null) { x, y -> ar.uiMap(zg.areaAt(x, y)) == map } else { _, _ -> true }
            DangerInputs.costs(here, mine, level, rect, own, { x, y -> wg?.wading(x, y) == true }, rooms).also { danger = Danger(key, it) }
        }
        // deep water a wall (the harness's rule, ZoneInputs.deep, on the device's water grid): no deck or raw depth read on the device yet,
        // so a bridge's cell is the bridge
        val deep: (Double, Double) -> Boolean = { x, y -> val d = wg?.at(x, y) ?: Float.NaN
            if (d.isNaN() || d == SafePath.Water.BRIDGE) false else !wg!!.wadeable(x, y) }
        val waterAt: (Double, Double) -> Float = { x, y -> wg?.at(x, y) ?: Float.NaN }
        val k = DangerGrid.strength(Settings.avoidDanger)
        val clear = Terrain.of(map)?.let { tg -> SafePath.clearOf { x, y -> Terrain.factor(tg.at(x, y)) } } ?: { _, _ -> true }
        val sr = SafeRoute(g, dc.danger, dc.calm, k, deep, waterAt, { _, _ -> false }, null, Climb.Limits(cancelled = cancelled))
        val t0 = System.nanoTime()
        val r = sr.plan(net, from, to, clear, emptyList(), toExit = toExit)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // THE HARD RULE, checked on what is handed out: every segment walkable on the lattice (a leg refused for its window falls back to a
        // straight piece - never shown)
        val bad = r.pts.zipWithNext().indexOfFirst { (a, b) -> !Climb.walkable(g, a, b, deep) }
        if (bad >= 0 || r.pts.size < 2) { lastWhy = "map $map: the way's segment $bad crosses a wall or deep water (${r.pts.size} points, $ms ms) - not shown"; return null }
        lastWhy = "map $map: ${r.pts.size - 2} flags, ${r.yd.toInt()} yd, ${if (r.reached) "reached" else "the goal not reached (to the nearest ground)"}, $ms ms"
        return Way(r.pts, r.reached, r.yd, st)
    }

    /** the route's memory given back when the device asks (onTrimMemory): the lattices and the leg's scratch */
    fun trim() { Climb.files.trim(); Climb.Scratch.release(); danger = null }
    @Volatile private var hooked = false
    fun init(ctx: Context) {
        if (hooked) return
        hooked = true
        ctx.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim() }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { trim() }
        })
    }

    // ---------------------------------------------------------------- when to plan again (MapView.safeLeg)

    /** off the way still ahead by more than this (yd): planned again from here */
    const val OFF_ROUTE_YD = 30.0
    /** a new way replaces the kept one only when this much shorter (the kept one's rest / this) */
    const val HYSTERESIS = 1.10
    /** at most one plan a quest this often (ms) */
    const val REPLAN_MS = 3_000L
    /** the goal moved this far (yd): planned again */
    const val GOAL_MOVED_YD = 20.0

    /** the player's distance (yd) from the polyline [way] (world points) */
    fun offWay(p: DoubleArray, way: List<DoubleArray>): Double {
        if (way.isEmpty()) return Double.MAX_VALUE
        if (way.size == 1) return hypot(p[0] - way[0][0], p[1] - way[0][1])
        var best = Double.MAX_VALUE
        for ((a, b) in way.zipWithNext()) {
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
            val u = if (l2 <= 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(a[0] + u * dx - p[0], a[1] + u * dy - p[1]))
        }
        return best
    }

    /** of the kept way's rest ([keptYd]) and a new one ([newYd]): the new one is taken */
    fun takeNew(keptYd: Double, newYd: Double): Boolean = newYd * HYSTERESIS < keptYd
}
