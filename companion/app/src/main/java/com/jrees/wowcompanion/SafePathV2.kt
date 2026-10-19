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

    /** the device's friend rules (typed, no game coordinates): WoW Forever's placeholder level for every zone, and a zone's own peoples
     *  the guides call friendly to all by uiMap (our words: regexes over QuestieDB's names; the specs' "friendlyNames", the same lists) */
    const val FRIENDLY_MIN_LEVEL = 100
    val FRIENDLY_NAMES: Map<Int, List<Regex>> = mapOf(
        2521 to listOf("^Windshaper", "^High Order"),                                                     // Zephras Isle
        2652 to listOf("^Shen'dral", "^Pylon Protector$", "^Shard Guard$", "^Magister ", "^Mordent Evenshade$",
            "^(Adarien|Mithera|Liranne|Arondel|Kerelle|Jarael)$", "^Gelkis Captive$"),                     // Shen'dralar
        1450 to listOf("^Orokai$", "^Elaren Stargrove$", "^Cenarion Warden$", "^Slumbering Druid$"),      // Moonglade
        2548 to listOf("^Sentry ", "^Lieutenant (Maclan|Prenish|Shelby)$", "^Cavalry ", "^Farholde ", "^Brother Lowley$",
            "^Wounded Soldier$", "^(Tessa|Gray) Dawnbright$", "^Barracks Master Harlan$", "^Scoutmaster Vargas$", "^Scout Bolain$",
            "^Watcher Cornelius$", "^Sergeant Riftan$", "^Innkeeper Zizplink$", "^Wharfmaster Whizzbuzz$", "^Fimbo Greasemitz$",
            "^Tallow Sparksocket$", "^Thrash$", "^Spek$", "^Jasper Geartoggle$", "^Mizzy$", "^Mia Tanglewrench$", "^Argo Brinewhistle$",
            "^Rex Hardwire$", "^Sleebo Fizzlespout$", "^Meadowsbrook Farmhand$", "^Turner's Mill Lumberjack$", "^Woodworker$",
            "^Halligan Turner$", "^Benjy Wheeler$", "^(Robbie|Eleanor) Holmsby$", "^Dunston Willis$", "^Cedric Dalton$", "^Melanie Sable$",
            "^Wheatley$", "^Steed$"),                                                                       // the Riverglades
    ).mapValues { (_, l) -> l.map { Regex(it) } }
    fun rulesFor(map: Int) = DangerInputs.Rules(FRIENDLY_MIN_LEVEL, FRIENDLY_NAMES[map] ?: emptyList())

    /** a route's danger and its no-mobs rival's (SafeRoute's danger and calm) */
    class Costs(val danger: (Double, Double) -> Float, val calm: (Double, Double) -> Float, val typical: Float)
    /** the character's grids, by (map, faction, level, the spawns' version, the avoid setting): DangerInputs' own cache */
    private val dangers = DangerInputs.Cache()
    private class Walled(val key: String, val net: SafePath.Net)
    @Volatile private var walled: Walled? = null

    /**
     * quest's way in zone [map] (world [rect], continent [rect].c) from world [from] to [to] ([toExit]: to the network's exit there) for
     * a character of side [mine] at [level]; [cancelled] asked as it runs (a newer plan). The route thread only. Null: no v2 way here
     * (a file not made yet - the lattice asked for - or a way the final check refuses); [lastWhy] says which
     */
    fun plan(ctx: Context?, map: Int, rect: MapHarvest.Rect, mine: Int, level: Int, from: DoubleArray, to: DoubleArray, toExit: Boolean,
             cancelled: () -> Boolean): Way? {
        val st = stamp(map, mine, level)
        val g = Climb.files.get(map) ?: run {
            ctx?.let { ClimbStep.want(it, map) }; lastWhy = "no lattice for map $map yet (asked for)"
            SafePathDiag.coverage(ArtHarvest.rootDir, listOf(map))
            return null }
        val wg = SafePath.waters.get(map)
        val net0 = SafePath.nets.get(map) ?: SafePath.Net(map, rect.c, "", emptyList(), emptyList(), emptyList())
        val zg = SafePath.zones.get(map); val ar = SafePath.areas()
        val own: (Double, Double) -> Boolean = if (zg != null && ar != null) { x, y -> ar.uiMap(zg.areaAt(x, y)) == map } else { _, _ -> true }
        val d = dangers.get(map, DangerStore.version, DangerStore.spawns(map), rect, mine, level, rulesFor(map), Settings.avoidDanger, inZone = own)
        val dc = costs(d, g, wg)
        val deep = deepOf(wg)
        val nkey = "$map:${SafePath.nets.version}:${Climb.files.version}:${ClimbStep.zonesMade}:${SafePath.waters.version}:${net0.edges.size}"
        val net = walled?.takeIf { it.key == nkey }?.net ?: unwalled(net0, g, deep).also { walled = Walled(nkey, it) }
        val clear = Terrain.of(map)?.let { tg -> SafePath.clearOf { x, y -> Terrain.factor(tg.at(x, y)) } } ?: { _, _ -> true }
        val t0 = System.nanoTime()
        Climb.Scratch.resetPeak()
        val r = route(g, wg, net, dc, DangerGrid.strength(Settings.avoidDanger), clear, from, to, toExit, Climb.Limits(cancelled = cancelled))
        val ms = (System.nanoTime() - t0) / 1_000_000
        SafePathDiag.plan(map, ms, Climb.Scratch.peakHere().toLong(), Climb.Scratch.peakHere() * 24L, r?.reached == true, "safeLeg")
        if (r == null) { lastWhy = "map $map: the way crosses a wall or deep water by the final check ($ms ms) - not shown"; return null }
        lastWhy = "map $map: ${r.pts.size - 2} flags, ${r.yd.toInt()} yd, ${if (r.reached) "reached" else "the goal not reached (to the nearest ground)"}, $ms ms"
        return Way(r.pts, r.reached, r.yd, st)
    }

    // ---------------------------------------------------------------- the device's way, pure (the parity test runs it too)

    /** deep water a wall (the harness's rule, ZoneInputs.deep, on the device's water grid): no deck or raw depth read on the device yet,
     *  so a bridge's water cell is the bridge */
    fun deepOf(wg: SafePath.Water?): (Double, Double) -> Boolean = { x, y -> val d = wg?.at(x, y) ?: Float.NaN
        if (d.isNaN() || d == SafePath.Water.BRIDGE) false else !wg!!.wadeable(x, y) }

    /** the character's danger [d] (DangerInputs) with the wade ([wg]) and the rooms ([g]'s solid footprints) as a middling danger */
    fun costs(d: DangerInputs.Danger, g: Climb.Grid, wg: SafePath.Water?): Costs {
        val rooms = g.solid ?: { _, _ -> false }
        val wading: (Double, Double) -> Boolean = { x, y -> wg?.wading(x, y) == true }
        return Costs(d.danger(wading, rooms), d.calm(wading, rooms), d.typical)
    }

    /** the network's drafted ways that cross a wall on the lattice: walked by the ground's leg when it joins their ends within 1.5x
     *  (a lip), else left out (SafePath.dropWalled, as the harness does before its routes) */
    fun unwalled(net: SafePath.Net, g: Climb.Grid, deep: (Double, Double) -> Boolean): SafePath.Net =
        SafePath.dropWalled(net, { a, b -> Climb.walkable(g, a, b, deep) }) { a, b ->
            Climb.route(g, a, b, water = deep, limits = Climb.Limits())?.takeIf { it.reached }?.pts?.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }?.sum() }

    /** the way from [from] to [to]: SafeRoute on lattice [g], water [wg], network [net], danger [dc] at strength [k]; null when a segment
     *  of it fails the final check (every segment walkable on the lattice, off deep water - the hard rule, on what is handed out) */
    fun route(g: Climb.Grid, wg: SafePath.Water?, net: SafePath.Net, dc: Costs, k: Double, clear: (DoubleArray, DoubleArray) -> Boolean,
              from: DoubleArray, to: DoubleArray, toExit: Boolean, limits: Climb.Limits?): SafeRoute.Result? {
        val deep = deepOf(wg)
        val waterAt: (Double, Double) -> Float = { x, y -> wg?.at(x, y) ?: Float.NaN }
        val sr = SafeRoute(g, dc.danger, dc.calm, k, deep, waterAt, { _, _ -> false }, null, limits)
        val r = sr.plan(net, from, to, clear, emptyList(), toExit = toExit)
        if (r.pts.size < 2 || r.pts.zipWithNext().any { (a, b) -> !Climb.walkable(g, a, b, deep) }) return null
        return r
    }

    /** the route's memory given back when the device asks (onTrimMemory): the lattices and the leg's scratch */
    fun trim() { Climb.files.trim(); Climb.Scratch.release(); dangers.clear() }
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
