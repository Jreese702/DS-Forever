package com.jrees.wowcompanion

import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * THE WORLD'S WAYS BETWEEN MAPS, PER CHARACTER (SafePath device plan 3.4, build step 11; the user: "daleran has one way portals to
 * stormwind added in wow forever. Anything like that need to be getting factored in on the big picture pathing"). A DIRECTED link graph
 * for one character, every way from one map (a uiMap, or a place of its own the caller's [Inputs.mapAt] names) to another that is not a
 * walk inside one map, then the world's [MultiHop] over it ([plan], at most [MAX_HOPS] hops, across continents):
 *  - WALK: the zones' exits on foot (SafePath's checked crossings: walk / gate / bridge / ford / tunnel), both ways; a crossing
 *    [SafePath.checkCrossings] marked AVOID (it joins its network only through danger) costs [AVOID_K] x its [CROSS_YD]; and the
 *    crossings this character walked (CityExits.Learner, as walked);
 *  - RIDE: the boats, zeppelins and airships (TransportRoutes, from the game's own TaxiPathNode), both ways where the path is one crossing,
 *    a round of three stops or more one way ([TransportRoutes.legs]); never from or to a dock in a town hostile to the character;
 *  - FLIGHT: TaxiNet's flights only, between two nodes a flight master showed this character known AND connected;
 *  - TRAM: CityExits.TABLE's measured near end; the far end learned (CityExits.Learner) - until then not known;
 *  - PORTAL (one way, never reversed): QuestieDB objects named "... Portal to <place>" (QuestieObjects), from their spawn; the LANDING
 *    learned on the first trip (CityExits.Learner: a ride out of the portal's map starting at the portal); until then it is not known,
 *    costed at [MultiHop.UNKNOWN_YD], and the step says "landing not yet known";
 *  - LEARNED: any other ride this character took (a lift, a door) and LINK: the caller's own links, with a requirement ([Link.requires]:
 *    a key, a quest) gated by what the character has ([Character.has]) and a faction.
 * Never estimated: an end nobody has measured stays NaN. No game coordinates live here: every position is the player's own data at run
 * time (TaxiNodes, TaxiPathNode, QuestieDB, the learned ways, the drafted nets). Pure.
 */
object TravelLinks {
    enum class Kind { WALK, RIDE, FLIGHT, TRAM, PORTAL, LEARNED, LINK }

    /**
     * one directed way from [fromMap] at world ([ax], [ay]) to [toMap] at ([bx], [by]) (NaN = an end nobody has measured), costing [yd]
     * walking yards besides the walks to and from it; [words] the step's ("Take the portal to Stormwind (one way)"), [key] its identity
     * ("r302" a ride, "f12>34" a flight, "w:1429>1453" a walk, "o631299" a portal by its object id, "tram:1453>1455", "l:..." learned).
     * [requires]: a key / quest the character must have; [faction]: ALLIANCE / HORDE only (0 = anyone).
     */
    class Link(val kind: Kind, val fromMap: Int, val ax: Double, val ay: Double, val toMap: Int, val bx: Double, val by: Double,
               val yd: Double, val words: String, val key: String, val oneWay: Boolean = false, val requires: String? = null,
               val faction: Int = 0, val avoid: Boolean = false) {
        /** an end nobody has measured (a portal's landing, a tram's far end not yet learned) */
        val gap get() = ax.isNaN() || ay.isNaN() || bx.isNaN() || by.isNaN()
        /** the same way back (for a link that runs both ways) */
        fun back(words: String = this.words, key: String = this.key) =
            Link(kind, toMap, bx, by, fromMap, ax, ay, yd, words, key, oneWay, requires, faction, avoid)
    }

    /** a portal: QuestieDB object [id] named [name], standing on [fromMap] at world ([x], [y]); it sends to [toPlace] ([toMap] its map, null
     *  = not known until learned) */
    class Portal(val id: Int, val name: String, val fromMap: Int, val x: Double, val y: Double, val toPlace: String, val toMap: Int?,
                 val faction: Int = 0)

    /** the character: its [faction] (TransportRoutes.ALLIANCE / HORDE), what it [has] (keys, quests done), whether [flights] count */
    class Character(val faction: Int, val has: Set<String> = emptySet(), val flights: Boolean = true)

    /**
     * What the graph is built from. [mapAt] (continent, wx, wy) -> the map a spot is on (the caller's zone lookup; a place of its own too),
     * null = none; [mapName] a map's words; [nets] the drafted, crossing-checked zone networks; [learnedMaps] / [learned] the ways
     * CityExits.Learner kept (the maps it holds ways out of, and those ways); [rides] TransportRoutes' (with their docks' towns);
     * [masters] the TaxiNodes flight masters, [taxiKnown] / [taxiConnected] TaxiNet's; [table] CityExits.TABLE's measured ways and
     * [tableAt] (map, x, y in 0..1) -> world [wx, wy]; [portals] QuestieObjects.portals'; [manual] the caller's own links.
     */
    class Inputs(
        val mapAt: (Int, Double, Double) -> Int?,
        val mapName: (Int) -> String = { "map $it" },
        val nets: List<SafePath.Net> = emptyList(),
        val learnedMaps: Collection<Int> = emptyList(),
        val learned: (Int) -> List<CityExits.Exit> = { emptyList() },
        val rides: List<TransportRoutes.Ride> = emptyList(),
        val masters: List<TransportRoutes.FlightMaster> = emptyList(),
        val taxiKnown: (Int) -> Boolean = { false },
        val taxiConnected: (Int, Int) -> Boolean = { _, _ -> false },
        val table: List<CityExits.Exit> = CityExits.TABLE,
        val tableAt: ((Int, Double, Double) -> DoubleArray?)? = null,
        val portals: List<Portal> = emptyList(),
        val manual: List<Link> = emptyList(),
    )

    /** the graph: its [links], and the ways [left] out and why (a hostile dock, a missing key), for the log */
    class Graph(val links: List<Link>, val left: List<String>)

    const val MAX_HOPS = 8
    const val AVOID_K = 6.0                         // an avoided crossing's way through, dearer this many times (SafePath's own)
    const val CROSS_YD = SafePath.LINK_FROM_YD      // the stretch of a crossing checkCrossings judged (150 yd)
    const val FLIGHT_FIX_YD = 150.0                 // a flight: the talking and landing, in walking yards ...
    const val FLIGHT_K = 0.25                       // ... and its yards at the taxi's speed (about 4x a run)
    const val PORTAL_AT_YD = 30.0                   // a learned ride out of a portal's map starting this near the portal is the portal's
    const val TAKEN_YD = 300.0                      // a learned ride this near a known way's ends is that way (MapView's)
    const val NOT_KNOWN = "landing not yet known"
    /** a drafted exit's ways on foot (a boat's, a tram's or a portal's exit is a ride, built from its own data) */
    val FOOT = setOf("walk", "gate", "bridge", "ford", "tunnel")

    fun build(inp: Inputs, who: Character): Graph {
        val out = ArrayList<Link>(); val left = ArrayList<String>()
        val keys = HashSet<String>()
        fun add(l: Link) { if (l.fromMap != l.toMap && keys.add("${l.key}|${l.fromMap}>${l.toMap}|${l.ax.toInt()},${l.ay.toInt()}")) out.add(l) }
        fun near(x0: Double, y0: Double, x1: Double, y1: Double, r: Double) = !x0.isNaN() && !x1.isNaN() && hypot(x1 - x0, y1 - y0) <= r

        // WALK: the zones' checked crossings, both ways (avoid: the way through at AVOID_K)
        for (net in inp.nets) {
            val byId = net.nodes.associateBy { it.id }
            for (x in net.exits) {
                if (x.how !in FOOT) continue
                val n = byId[x.node] ?: continue
                val avoid = net.edges.any { (it.a == x.node || it.b == x.node) && it.state == SafePath.AVOID }
                val yd = if (avoid) (AVOID_K - 1.0) * CROSS_YD else 0.0
                val words = "To ${inp.mapName(x.toMap)}" + if (avoid) " (a dangerous crossing)" else ""
                val l = Link(Kind.WALK, net.map, n.wx, n.wy, x.toMap, n.wx, n.wy, yd, words, "w:${net.map}>${x.toMap}", avoid = avoid)
                add(l)
                add(l.back("To ${inp.mapName(net.map)}" + if (avoid) " (a dangerous crossing)" else "", "w:${x.toMap}>${net.map}"))
            }
        }

        // RIDE: the game's boats / zeppelins / airships; a round runs one way; a hostile town's dock never boarded
        val rides = ArrayList<Link>()
        for (r0 in inp.rides) for ((k, rd) in TransportRoutes.legs(r0, inp.rides).withIndex()) {
            val ax = rd.a[0].toDouble(); val ay = rd.a[1].toDouble(); val bx = rd.b[0].toDouble(); val by = rd.b[1].toDouble()
            val bad = rd.hostileDock(who.faction)
            if (bad != 0) { left.add("r${rd.path}${if (k == 1) " back" else ""} (its ${if (bad == 1) "near" else "far"} dock is a hostile town's)"); continue }
            val fm = inp.mapAt(rd.ca, ax, ay) ?: continue; val tm = inp.mapAt(rd.cb, bx, by) ?: continue
            val to = rd.nb ?: inp.mapName(tm)
            val l = Link(Kind.RIDE, fm, ax, ay, tm, bx, by, rd.yd.coerceAtLeast(1.0), "Board the ${if (rd.air) "zeppelin" else "boat"} to $to",
                "r${rd.path}", oneWay = k == 0 && TransportRoutes.legs(r0, inp.rides).size == 1)
            rides.add(l); add(l)
        }
        // the rides of a hostile town (learned or not, never taken)
        val hostile = inp.rides.flatMap { TransportRoutes.legs(it, inp.rides) }.filter { it.hostileDock(who.faction) != 0 }

        // FLIGHT: known AND connected nodes only (TaxiNet), the faction's real masters on one continent
        if (who.flights) {
            val known = inp.masters.filter { it.real && it.flags and who.faction != 0 && inp.taxiKnown(it.id) }
            val at = known.associateWith { inp.mapAt(it.cont, it.x.toDouble(), it.y.toDouble()) }
            for (a in known) for (b in known) {
                if (a === b || a.cont != b.cont || !inp.taxiConnected(a.id, b.id)) continue
                val fm = at[a] ?: continue; val tm = at[b] ?: continue
                val d = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
                add(Link(Kind.FLIGHT, fm, a.x.toDouble(), a.y.toDouble(), tm, b.x.toDouble(), b.y.toDouble(), FLIGHT_FIX_YD + d * FLIGHT_K,
                    "Fly to ${b.town.ifEmpty { inp.mapName(tm) }}", "f${a.id}>${b.id}"))
            }
        }

        // the learned ways, by the map they leave
        val learned = inp.learnedMaps.toSet().associateWith { inp.learned(it) }
        val claimed = HashSet<CityExits.Exit>()

        // TRAM: the measured table's near end; the far end learned (a ride there, or a ride back from it), else not known
        val at = inp.tableAt
        if (at != null) for (e in inp.table) {
            if (!e.transport || e.x.isNaN() || e.y.isNaN()) continue
            val w = at(e.map, e.x, e.y) ?: continue
            val there = learned[e.map].orEmpty().firstOrNull { it.transport && it.toMap == e.toMap && !it.toWx.isNaN() && near(it.wx, it.wy, w[0], w[1], TAKEN_YD) }
            val back = learned[e.toMap].orEmpty().firstOrNull { it.transport && it.toMap == e.map && near(it.toWx, it.toWy, w[0], w[1], TAKEN_YD) }
            there?.let { claimed.add(it) }; back?.let { claimed.add(it) }
            val fx = there?.toWx ?: back?.wx ?: Double.NaN; val fy = there?.toWy ?: back?.wy ?: Double.NaN
            val words = "Take the ${e.name.ifEmpty { "tram" }} to ${inp.mapName(e.toMap)}"
            val l = Link(Kind.TRAM, e.map, w[0], w[1], e.toMap, fx, fy, e.rideYd, words + if (fx.isNaN()) "; its far end not yet known" else "",
                "tram:${e.map}>${e.toMap}")
            add(l)
            add(l.back("Take the ${e.name.ifEmpty { "tram" }} to ${inp.mapName(e.map)}" + if (fx.isNaN()) "; where it is boarded not yet known" else "",
                "tram:${e.toMap}>${e.map}"))
        }

        // PORTAL: one way; the landing learned at the portal, else not known (costed at UNKNOWN_YD, said so)
        for (p in inp.portals) {
            if (p.faction != 0 && p.faction and who.faction == 0) { left.add("o${p.id} (${p.name}: the other side's)"); continue }
            val got = learned[p.fromMap].orEmpty().firstOrNull { near(it.wx, it.wy, p.x, p.y, PORTAL_AT_YD) }
            got?.let { claimed.add(it) }
            val tm = got?.toMap ?: p.toMap
            if (tm == null) { left.add("o${p.id} (${p.name}: where it sends is not known yet)"); continue }
            if (tm == p.fromMap) continue                                 // a portal inside one map (Dalaran's own rooms)
            val bx = got?.let { if (it.transport) it.toWx else it.wx } ?: Double.NaN
            val by = got?.let { if (it.transport) it.toWy else it.wy } ?: Double.NaN
            val words = "Take the portal to ${p.toPlace} (one way)" + if (bx.isNaN()) "; $NOT_KNOWN" else ""
            add(Link(Kind.PORTAL, p.fromMap, p.x, p.y, tm, bx, by, 0.0, words, "o${p.id}", oneWay = true, faction = p.faction))
        }

        // LEARNED: every other way this character took, as it took it (a walk across a border; a lift, a door, a ride nobody listed)
        for ((from, ways) in learned) for (e in ways) {
            if (e in claimed) continue
            if (e.transport) {
                if (e.toWx.isNaN()) continue
                // a hostile town's ride stays off, however it was learned; a listed ride's own learning is that ride
                if (hostile.any { near(e.wx, e.wy, it.a[0].toDouble(), it.a[1].toDouble(), TAKEN_YD) && near(e.toWx, e.toWy, it.b[0].toDouble(), it.b[1].toDouble(), TAKEN_YD) }) continue
                if (rides.any { it.fromMap == from && it.toMap == e.toMap && near(e.wx, e.wy, it.ax, it.ay, TAKEN_YD) && near(e.toWx, e.toWy, it.bx, it.by, TAKEN_YD) }) continue
                add(Link(Kind.LEARNED, from, e.wx, e.wy, e.toMap, e.toWx, e.toWy, e.rideYd, "Ride to ${inp.mapName(e.toMap)}",
                    "l:$from>${e.toMap}:${e.wx.toInt()}:${e.wy.toInt()}", oneWay = true))
            } else add(Link(Kind.WALK, from, e.wx, e.wy, e.toMap, e.wx, e.wy, 0.0, "To ${inp.mapName(e.toMap)}",
                "l:$from>${e.toMap}:${e.wx.toInt()}:${e.wy.toInt()}", oneWay = true))
        }

        // LINK: the caller's own, gated by what the character has and its side
        for (l in inp.manual) {
            if (l.requires != null && l.requires !in who.has) { left.add("${l.key} (needs ${l.requires})"); continue }
            if (l.faction != 0 && l.faction and who.faction == 0) { left.add("${l.key} (the other side's)"); continue }
            add(l)
        }
        return Graph(out, left)
    }

    /** one hop of a plan: its [link] and the step's [words] */
    class Step(val link: Link, val words: String)
    /** the chain, first hop first ([steps]; empty = a walk on the start's map), its [cost] in yards, and the [gaps]: its links with an
     *  end nobody has measured */
    class Route(val steps: List<Step>, val cost: Double) {
        val gaps: List<Link> get() = steps.map { it.link }.filter { it.gap }
        val keys: List<String> get() = steps.map { it.link.key }
        fun describe(): String = if (steps.isEmpty()) "straight" else steps.joinToString(", then ") { it.words }
    }

    /**
     * The world's cheapest chain from [startMap] at world ([sx], [sy]) to the goal at ([gx], [gy]) over [g] (directed: a one-way link is
     * one hop and never walked back), at most [maxHops] hops. [reaches] (map, wx, wy): from there the goal is a walk (wx NaN: a landing
     * not yet known - decide by the map). Null = no chain.
     */
    fun plan(g: Graph, startMap: Int, sx: Double, sy: Double, gx: Double, gy: Double, reaches: (Int, Double, Double) -> Boolean,
             maxHops: Int = MAX_HOPS): Route? {
        val of = IdentityHashMap<MultiHop.Hop, Link>()
        val hops = g.links.map { l -> MultiHop.Hop(l.fromMap, l.ax, l.ay, l.toMap, l.bx, l.by, l.yd, l.words, l.key).also { of[it] = l } }
        val p = MultiHop.plan(startMap, sx, sy, gx, gy, hops, reaches, maxHops) ?: return null
        return Route(p.hops.map { h -> of[h]!!.let { Step(it, it.words) } }, p.cost)
    }

    /**
     * A portal's place words to a map ([names]: map -> its name, the game's): the one named so ("the " aside, any case), else the one
     * whose name begins with them ("Stormwind" -> "Stormwind City"); null when none or more than one
     */
    fun placeMap(place: String, names: Map<Int, String>): Int? {
        fun norm(s: String) = s.trim().lowercase().removePrefix("the ").trim()
        val p = norm(place)
        if (p.isEmpty()) return null
        names.entries.filter { norm(it.value) == p }.let { if (it.size == 1) return it[0].key; if (it.size > 1) return null }
        return names.entries.filter { norm(it.value).startsWith("$p ") }.singleOrNull()?.key
    }
}
