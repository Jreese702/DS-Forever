package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * CROSS-ZONE ROUTING OVER THE SHIPPED NETWORK (the user, 2026-10-19: "Ship our path network"; DEVICE_INTEGRATION_PLAN.md, SHIPPED
 * NETWORK step 4). TravelLinks' walks come from the shipped NET1 nets ([ShippedNet.Section]: the zone's exits with their kinds and
 * points, for the character's side) instead of the device's drafts, and the sections' cross-zone LINK STARTS that no game table gives
 * (a "Portal to ..." or "Wind Bridge" the harness found, an updraft, a lift to an air place) become one-way links whose far end is
 * learned the first time they are taken ("the way up is learned the first time you take it"; the user's "Route to the portal": no typed
 * air chain). The rides, flights (TaxiNet known AND connected), the tram and QuestieDB's portals are TravelLinks' own, as helper O built
 * them; this only feeds and reads it.
 *
 * Then [plan]: TravelLinks.plan's hop chain, the zones on it that have NO shipped net ([Chain.missing]: the caller keeps its old
 * continentHops way unless every zone on the chain is shipped), each hop's words as the steps, and each in-zone leg (the walk on the
 * start's map to the first hop, between hops, from the last hop to the goal) with its yards on the shipped network by the caller's
 * [LegYards] (the device's shipped-net route), straight where it has none. No game coordinates here: every point is the shipped net's
 * or the player's own data at run time. Pure.
 */
object ShippedCross {
    /** a zone's shipped section ([map] its uiMap); [stale] = exported from another game build ("may be out of date") */
    class Zone(val map: Int, val section: ShippedNet.Section, val stale: Boolean = false) {
        val net get() = section.net
    }

    /** the words for a link start whose far end is not known until taken (the user's ruling on air places) */
    const val LEARNED_UP = "the way up is learned the first time you take it"
    /** a link start this near one of QuestieDB's portals on its map is that portal (TravelLinks builds it, with its landing learned) */
    const val START_PORTAL_YD = 60.0
    /** a link start's learned landing: a ride out of its map that started this near it */
    const val START_TAKEN_YD = 40.0

    /** the start kinds TravelLinks already builds from the game's own data (boats, zeppelins, flights, the tram) */
    val RIDE_KINDS = setOf(SafePath.FLIGHT, SafePath.DOCK, SafePath.TRAM, "boat", "zeppelin", "airship", "ride")

    /** a start's kind (or its name) that is a portal by the user's link-kind words ("Portal to ...") */
    fun isPortal(kind: String, name: String) = kind == "portal" || QuestieObjects.portalPlace(name) != null
    /** "Wind Bridge" (the user's typed link-kind words) */
    fun isWindBridge(kind: String, name: String) = kind.equals("wind bridge", true) || kind == "windbridge" || name.contains("Wind Bridge", true)

    /**
     * [base] with its walks from the shipped [zones] (a zone shipped replaces the device's draft for that map; a map with none keeps
     * [base]'s net) and the zones' link starts as one-way links ([startLinks]); everything else (rides, flights, tram, portals, the
     * learned ways, the caller's links) as [base] has it
     */
    fun inputs(base: TravelLinks.Inputs, zones: Collection<Zone>): TravelLinks.Inputs {
        val shipped = zones.map { it.map }.toSet()
        val claimed = HashSet<CityExits.Exit>()
        val starts = startLinks(zones, base, claimed)
        // a learned ride that is a start's own trip gave only its landing: not a link of its own (as TravelLinks' portals)
        val learned: (Int) -> List<CityExits.Exit> = if (claimed.isEmpty()) base.learned else { m -> base.learned(m).filter { it !in claimed } }
        return TravelLinks.Inputs(base.mapAt, base.mapName, base.nets.filter { it.map !in shipped } + zones.map { it.net },
            base.learnedMaps, learned, base.rides, base.masters, base.taxiKnown, base.taxiConnected, base.table, base.tableAt,
            base.portals, base.manual + starts)
    }

    /**
     * the zones' cross-zone link starts that are no ride the game's tables give and no QuestieDB portal (those TravelLinks builds): one
     * way, from the start's node into its [ShippedNet.Start.toMap], the far end the learned ride out of the map at the start (a map
     * change there, CityExits.Learner) or not known yet (costed at MultiHop.UNKNOWN_YD, said so). A start into several maps (toMap 0) or
     * into its own map (an air place on the zone's own map: the in-zone route goes to it) is no cross-zone link.
     */
    fun startLinks(zones: Collection<Zone>, base: TravelLinks.Inputs, claimed: MutableSet<CityExits.Exit> = HashSet()): List<TravelLinks.Link> {
        val out = ArrayList<TravelLinks.Link>()
        for (z in zones) {
            val byId = z.net.nodes.associateBy { it.id }
            val learned = if (z.map in base.learnedMaps) base.learned(z.map) else emptyList()
            for (s in z.section.starts) {
                if (s.toMap == 0 || s.toMap == z.map || s.kind in RIDE_KINDS || s.kind in TravelLinks.FOOT) continue
                val n = byId[s.node] ?: continue
                if (base.portals.any { it.fromMap == z.map && hypot(it.x - n.wx, it.y - n.wy) <= START_PORTAL_YD }) continue
                val got = learned.firstOrNull { hypot(it.wx - n.wx, it.wy - n.wy) <= START_TAKEN_YD && it.toMap == s.toMap }
                got?.let { claimed.add(it) }
                val bx = got?.let { if (it.transport) it.toWx else it.wx } ?: Double.NaN
                val by = got?.let { if (it.transport) it.toWy else it.wy } ?: Double.NaN
                val name = s.name.ifEmpty { base.mapName(s.toMap) }
                val words = when {
                    isPortal(s.kind, s.name) -> "Take the ${QuestieObjects.portalPlace(s.name)?.let { "portal to $it" } ?: name} (one way)"
                    isWindBridge(s.kind, s.name) -> "Take the Wind Bridge to ${base.mapName(s.toMap)}"
                    else -> "Take the ${s.kind.ifEmpty { "way" }} to ${base.mapName(s.toMap)}"
                } + if (bx.isNaN()) "; $LEARNED_UP" else ""
                out.add(TravelLinks.Link(TravelLinks.Kind.LINK, z.map, n.wx, n.wy, s.toMap, bx, by, 0.0, words, "s:${z.map}:${s.node}", oneWay = true))
            }
        }
        return out
    }

    /** the yards of an in-zone leg on [map] from [a] to [b] (world x, y) on its shipped network; null = it has none (then straight) */
    fun interface LegYards { fun yards(map: Int, a: DoubleArray, b: DoubleArray, toExit: Boolean): Double? }

    /** one walk inside one map: from [a] to [b], [yd] its yards ([onNet] = by the shipped network, else straight) */
    class Leg(val map: Int, val a: DoubleArray, val b: DoubleArray, val yd: Double, val onNet: Boolean)

    /**
     * the plan: the [route] (TravelLinks'), the [legs] (one more than its hops: before each hop and after the last), the maps on it
     * with no shipped net ([missing]: empty = every zone of the chain is shipped) and the [stale] ones
     */
    class Chain(val route: TravelLinks.Route, val legs: List<Leg>, val maps: List<Int>, val missing: List<Int>, val stale: List<Int>,
                val graph: TravelLinks.Graph) {
        val covered get() = missing.isEmpty()
        val first: TravelLinks.Link? get() = route.steps.firstOrNull()?.link
        /** the yards in all: the legs' and the hops' own */
        val yards: Double get() = legs.sumOf { it.yd } + route.steps.sumOf { it.link.yd }
        /** the steps, in order: each leg ("walk 420 yd on the network") and each hop's words */
        fun steps(mapName: (Int) -> String): List<String> {
            val out = ArrayList<String>()
            for ((i, leg) in legs.withIndex()) {
                val walk = if (leg.yd.isNaN() || leg.yd >= MultiHop.UNKNOWN_YD && leg.a[0].isNaN()) "walk on from where it lands"
                    else "walk ${leg.yd.toInt()} yd${if (leg.onNet) " on the network" else ""}"
                if (leg.yd > 1.0 || i == legs.size - 1) out.add("In ${mapName(leg.map)}: $walk" + if (i == legs.size - 1) " to the goal" else "")
                route.steps.getOrNull(i)?.let { out.add(it.words) }
            }
            return out
        }
        fun describe(mapName: (Int) -> String) = steps(mapName).withIndex().joinToString("; ") { (i, s) -> "${i + 1}. $s" }
    }

    /**
     * The chain from [startMap] at ([sx], [sy]) to the goal at ([gx], [gy]) on [goalMap] for [who] over [inp] (fed by [inputs]); [shipped]
     * the maps with a shipped net (and [stale] those marked out of date); [legs] the in-zone legs' yards. Null = no chain (or the goal is
     * a walk on the start's map: an empty route, which the caller's in-zone route handles).
     */
    fun plan(inp: TravelLinks.Inputs, who: TravelLinks.Character, startMap: Int, sx: Double, sy: Double, goalMap: Int, gx: Double, gy: Double,
             shipped: Set<Int>, stale: Set<Int> = emptySet(), legs: LegYards? = null,
             reaches: (Int, Double, Double) -> Boolean = { m, _, _ -> m == goalMap }): Chain? {
        val g = TravelLinks.build(inp, who)
        val r = TravelLinks.plan(g, startMap, sx, sy, gx, gy, reaches) ?: return null
        if (r.steps.isEmpty()) return null
        val maps = listOf(startMap) + r.steps.map { it.link.toMap }
        val out = ArrayList<Leg>()
        var map = startMap; var at = doubleArrayOf(sx, sy)
        fun leg(m: Int, a: DoubleArray, b: DoubleArray, toExit: Boolean) {
            if (a[0].isNaN() || b[0].isNaN()) { out.add(Leg(m, a, b, MultiHop.UNKNOWN_YD, false)); return }
            val y = legs?.yards(m, a, b, toExit)
            out.add(Leg(m, a, b, y ?: hypot(b[0] - a[0], b[1] - a[1]), y != null))
        }
        for (s in r.steps) {
            leg(map, at, doubleArrayOf(s.link.ax, s.link.ay), s.link.kind == TravelLinks.Kind.WALK)
            map = s.link.toMap; at = doubleArrayOf(s.link.bx, s.link.by)
        }
        leg(map, at, doubleArrayOf(gx, gy), false)
        return Chain(r, out, maps, maps.filter { it !in shipped }.distinct(), maps.filter { it in stale }.distinct(), g)
    }

    /** the shipped exit a walk link leaves by (its name: "the Orgrimmar gate"), for the steps' words; null = none */
    fun exitName(zones: Collection<Zone>, l: TravelLinks.Link): String? {
        if (l.kind != TravelLinks.Kind.WALK) return null
        val z = zones.firstOrNull { it.map == l.fromMap } ?: zones.firstOrNull { it.map == l.toMap } ?: return null
        val byId = z.net.nodes.associateBy { it.id }
        return z.net.exits.filter { it.toMap == (if (z.map == l.fromMap) l.toMap else l.fromMap) }
            .minByOrNull { x -> byId[x.node]?.let { hypot(it.wx - l.ax, it.wy - l.ay) } ?: Double.MAX_VALUE }?.name?.takeIf { it.isNotEmpty() }
    }
}
