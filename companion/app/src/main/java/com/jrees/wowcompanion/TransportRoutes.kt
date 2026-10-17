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

    /** the game's run speed, yards a second: a ride's time as walking yards */
    const val RUN_YD_S = 7.0

    /**
     * a ride: from dock [a] (continent [ca]) to dock [b] (continent [cb]) on [path]; positions in world yards (x north, y west).
     * [yd] its cost in walking yards, from the game's own path: the yards sailed between the two stops (a jump between continents
     * counts nothing) and the wait at the first dock (its delay, seconds at [RUN_YD_S]); 0 = not known (an older file).
     */
    class Ride(val path: Int, val ca: Int, val a: FloatArray, val cb: Int, val b: FloatArray, val yd: Double = 0.0,
               val fa: Int = 0, val fb: Int = 0, val air: Boolean = false, val na: String? = null, val nb: String? = null) {
        /** the same boat the other way (the transports run both ways between their docks) */
        fun back() = Ride(path, cb, b, ca, a, yd, fb, fa, air, nb, na)
        /** a dock in a town hostile to [mine] (ALLIANCE / HORDE): 1 = the near dock, 2 = the far one, 0 = neither (or unknown) */
        fun hostileDock(mine: Int): Int = when {
            mine == 0 -> 0
            fa != 0 && fa and mine == 0 -> 1
            fb != 0 && fb and mine == 0 -> 2
            else -> 0
        }
    }

    // ---- WHOSE TOWN A DOCK IS IN (b-route-faction; the user's pick: "the hostile-town rule ... using the game's own data, no hand
    // table"). The game's TaxiNodes table ([DB_TAXI_NODES], read on the device) marks every flight master for the Alliance map
    // (flag [ALLIANCE]), the Horde map ([HORDE]) or both; a dock's town is whoever's flight masters stand within [DOCK_TOWN_YD] of it
    // (measured on the PC client: Grom'gol, Orgrimmar's and Undercity's towers Horde; Menethil, Theramore, Rut'theran, Auberdine,
    // Southshore, Feathermoon, Stormwind's harbour Alliance; Ratchet and Booty Bay both). None near = unknown = no rule.
    const val DB_TAXI_NODES = 1068100
    const val DB_TAXI_PATH = 1067802             // TaxiPath: the flight routes between the nodes (b-travel-layer)
    const val ALLIANCE = 1
    const val HORDE = 2
    const val DOCK_TOWN_YD = 900.0
    /** a TaxiNodes row: [id] the node's id (the game's nodeID), [name] its words ("Menethil Harbor, Wetlands") */
    class FlightMaster(val cont: Int, val x: Float, val y: Float, val flags: Int, val id: Int = 0, val name: String = "") {
        /** a flight master a player can use: a faction's (flag 1 / 2) and not one of the table's retired or quest-path rows */
        val real get() = flags and 3 != 0 && !name.startsWith("zzOLD") && !name.startsWith("Quest Path")
        /** the town: the name before its zone ("Menethil Harbor") */
        val town get() = name.substringBefore(',').trim()
    }

    /**
     * A DOCK'S NAME FROM THE GAME'S OWN DATA (b-travel-layer, b-dock-names): the town of the nearest flight master within
     * [DOCK_NAME_YD] (a pier at sea reads its bay: Menethil's "Baradin Bay"), else the place there ([place]: the ground's own area,
     * PlaceNames: "Steamwheedle Port", "Skywatcher Plateau", "Dalaran", "Valanaar"), else the nearest other named node (the table's
     * own transport points), else null (the app names the zone)
     */
    const val DOCK_NAME_YD = 500.0             // Undercity's and Orgrimmar's towers: their masters 494 - 496 yd off
    fun dockName(nodes: List<FlightMaster>, cont: Int, x: Float, y: Float, place: ((Int, Float, Float) -> String?)? = null): String? {
        fun d(n: FlightMaster) = Math.hypot((n.x - x).toDouble(), (n.y - y).toDouble())
        val near = nodes.filter { it.cont == cont && it.name.isNotEmpty() && d(it) <= DOCK_NAME_YD }
        near.filter { it.real }.minByOrNull { d(it) }?.let { return it.town }
        place?.invoke(cont, x, y)?.let { return it }
        return near.filter { n -> listOf("Transport", "Generic", "Quest Path", "zzOLD").none { n.name.startsWith(it) } }
            .minByOrNull { d(it) }?.town
    }

    /** the factions served at ([x], [y]) on [cont]: an OR of ALLIANCE / HORDE, 0 = no flight master near */
    fun townFaction(masters: List<FlightMaster>, cont: Int, x: Float, y: Float): Int = masters
        .filter { it.cont == cont && it.flags and 3 != 0 && Math.hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= DOCK_TOWN_YD }
        .fold(0) { m, f -> m or (f.flags and 3) }

    /** [rides] with their docks' towns */
    fun withFactions(rides: List<Ride>, masters: List<FlightMaster>, place: ((Int, Float, Float) -> String?)? = null): List<Ride> = rides.map { r ->
        Ride(r.path, r.ca, r.a, r.cb, r.b, r.yd, townFaction(masters, r.ca, r.a[0], r.a[1]), townFaction(masters, r.cb, r.b[0], r.b[1]),
            r.air, dockName(masters, r.ca, r.a[0], r.a[1], place), dockName(masters, r.cb, r.b[0], r.b[1], place))
    }

    /** "Alliance" / "Horde" (the game's UnitFactionGroup) as [ALLIANCE] / [HORDE], else 0 */
    fun factionOf(name: String?): Int = when (name) { "Alliance" -> ALLIANCE; "Horde" -> HORDE; else -> 0 }

    /** the transport paths' rides: consecutive stops, and the last back to the first when the path ends where it started */
    fun rides(nodes: List<Node>): List<Ride> {
        val out = ArrayList<Ride>()
        for ((path, ns) in nodes.groupBy { it.path }) {
            val stops = ns.sortedBy { it.index }.filter { it.flags and STOP != 0 }
            if (stops.size < 2) continue
            val all = ns.sortedBy { it.index }
            for (i in 0 until stops.size - 1) out.add(ride(path, stops[i], stops[i + 1], all))
        }
        return out
    }

    /** a ride whose path rises this far over the sea between its docks flies (a zeppelin, an airship); a boat's stays at 0 (measured
     *  on the PC client: the boats' paths 0 +- 1, the zeppelins' 59 - 275) */
    const val AIR_Z = 20f

    private fun ride(path: Int, s: Node, t: Node, all: List<Node>): Ride {
        var yd = s.delay * RUN_YD_S
        val leg = all.filter { it.index in s.index..t.index }
        val air = leg.filter { it.index != s.index && it.index != t.index }.maxOfOrNull { it.z }?.let { it > AIR_Z } ?: false
        for (k in 1 until leg.size) {
            val p = leg[k - 1]; val q = leg[k]
            if (p.cont == q.cont) yd += Math.sqrt(((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y) + (q.z - p.z) * (q.z - p.z)).toDouble())
        }
        return Ride(path, s.cont, floatArrayOf(s.x, s.y, s.z), t.cont, floatArrayOf(t.x, t.y, t.z), yd, air = air)
    }

    /** the device file (harvest/transports.json): the rides and how the table read, for the log and the route planner */
    fun toJson(nodes: List<Node>, rides: List<Ride>, masters: List<FlightMaster> = emptyList(), flights: List<IntArray> = emptyList()): JSONObject {
        val a = JSONArray()
        for (r in rides) a.put(JSONObject().put("p", r.path).put("ca", r.ca).put("cb", r.cb)
            .put("a", JSONArray().put(r.a[0].toDouble()).put(r.a[1].toDouble()).put(r.a[2].toDouble()))
            .put("b", JSONArray().put(r.b[0].toDouble()).put(r.b[1].toDouble()).put(r.b[2].toDouble()))
            .put("yd", Math.round(r.yd).toInt()).put("fa", r.fa).put("fb", r.fb).put("air", r.air)
            .apply { r.na?.let { put("na", it) }; r.nb?.let { put("nb", it) } })
        // the flight masters (b-travel-layer: the real ones, their towns) and the game's flight routes between them (TaxiPath)
        val fm = JSONArray()
        for (m in masters.filter { it.real }) fm.put(JSONArray().put(m.id).put(m.cont).put(m.x.toDouble()).put(m.y.toDouble()).put(m.flags).put(m.town))
        val ids = masters.filter { it.real }.map { it.id }.toSet()
        val tp = JSONArray()
        for (f in flights) if (f[0] in ids && f[1] in ids) tp.put(JSONArray().put(f[0]).put(f[1]))
        return JSONObject().put("v", 4).put("nodes", nodes.size).put("paths", nodes.map { it.path }.toSet().size)
            .put("transportPaths", rides.map { it.path }.toSet().size).put("rides", a).put("fm", fm).put("tp", tp)
    }

    /** the device file's flight masters (b-travel-layer) */
    fun mastersFromJson(o: JSONObject?): List<FlightMaster> {
        val a = o?.optJSONArray("fm") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONArray(i) ?: return@mapNotNull null
            if (r.length() < 6) return@mapNotNull null
            FlightMaster(r.optInt(1), r.optDouble(2).toFloat(), r.optDouble(3).toFloat(), r.optInt(4), r.optInt(0), r.optString(5))
        }
    }

    /** the device file's flight routes, [from, to] node ids (the game's TaxiPath) */
    fun flightsFromJson(o: JSONObject?): List<IntArray> {
        val a = o?.optJSONArray("tp") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { intArrayOf(it.optInt(0), it.optInt(1)) } }
    }

    /** the device file read back: its rides (none when it is not there or does not read) */
    fun fromJson(o: JSONObject?): List<Ride> {
        val a = o?.optJSONArray("rides") ?: return emptyList()
        val out = ArrayList<Ride>()
        fun v(x: JSONArray?) = if (x == null || x.length() < 3) null else floatArrayOf(x.optDouble(0).toFloat(), x.optDouble(1).toFloat(), x.optDouble(2).toFloat())
        for (i in 0 until a.length()) {
            val d = a.optJSONObject(i) ?: continue
            val pa = v(d.optJSONArray("a")) ?: continue; val pb = v(d.optJSONArray("b")) ?: continue
            if (pa.any { it.isNaN() } || pb.any { it.isNaN() }) continue
            out.add(Ride(d.optInt("p"), d.optInt("ca", -1), pa, d.optInt("cb", -1), pb, d.optDouble("yd", 0.0), d.optInt("fa", 0), d.optInt("fb", 0),
                d.optBoolean("air", false), d.optString("na", "").ifEmpty { null }, d.optString("nb", "").ifEmpty { null }))
        }
        return out
    }

    /**
     * THE RIDES AS WAYS OUT (b-route-rides; the user, 2026-10-17: "GO on the route planner using the game's rides (boats / zeppelins
     * as ways out)"): every ride both ways from a dock on [cont]. A ride staying on [cont] ends at its far dock ([Hop.tailYd] NaN: the
     * planner measures on from there); a ride to another continent's quest ([spots]: [continent, wx, wy] each) carries the yards
     * from where it lands to the nearest spot there ([Hop.tailYd]). [spots] empty = the same-continent rides only.
     */
    class Hop(val ride: Ride, val tailYd: Double)
    fun hops(rides: List<Ride>, cont: Int, spots: List<DoubleArray>): List<Hop> {
        val out = ArrayList<Hop>()
        for (r0 in rides) for (rd in listOf(r0, r0.back())) {
            if (rd.ca != cont) continue
            if (rd.cb == cont) { out.add(Hop(rd, Double.NaN)); continue }
            val near = spots.filter { it.size >= 3 && it[0].toInt() == rd.cb }
                .minOfOrNull { Math.hypot(it[1] - rd.b[0], it[2] - rd.b[1]) } ?: continue
            out.add(Hop(rd, near))
        }
        return out
    }

    /** one line for the WCMAP log: how much the table gave */
    fun summary(nodes: List<Node>, rides: List<Ride>): String {
        val byCont = rides.groupBy { it.ca }.entries.sortedBy { it.key }.joinToString(", ") { "continent ${it.key}: ${it.value.size}" }
        return "transports: ${nodes.size} path nodes, ${nodes.map { it.path }.toSet().size} paths, " +
            "${rides.map { it.path }.toSet().size} transport paths, ${rides.size} rides ($byCont)"
    }
}
