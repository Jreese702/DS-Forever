package com.jrees.wowcompanion

import kotlin.math.hypot

/**
 * TRAVEL ON THE MAPS (b-travel-layer; the user, via tracking on test53: "draw TRAVEL on the maps, all from the game's own data (no
 * hand table)"). The docks of the game's boats and zeppelins with their crossings, the Deeprun Tram's two ends, every flight master
 * (the ones this character knows lit, the others dim) and this character's known flight routes. All from the device's own harvest
 * (transports.json: TaxiPathNode's rides, TaxiNodes' masters, TaxiPath's routes) and what the game says live (the known nodes, the
 * faction). Pure: the data in, the scene (points and lines in world yards) out; drawing and tapping are the views'.
 */
object Travel {
    enum class Kind { BOAT, AIR, TRAM, FLIGHT }

    /** a point to draw and tap: [lit] known / friendly, [text] what a tap shows (its name and where it goes) */
    class Point(val kind: Kind, val cont: Int, val wx: Double, val wy: Double, val name: String, val text: String, val lit: Boolean,
                val id: Int = 0, val fac: Int = 0)          // fac: a flight master's side (TransportRoutes.ALLIANCE / HORDE, both = 3)

    /** a line: a crossing (dashed), the tram, a known flight; [lit] false = a hostile town's (dimmed) */
    class Line(val kind: Kind, val cont: Int, val ax: Double, val ay: Double, val bx: Double, val by: Double, val lit: Boolean)

    class Scene(val points: List<Point>, val lines: List<Line>) {
        /** the point nearest ([wx], [wy]) on [cont] within [yd], or null */
        fun near(cont: Int, wx: Double, wy: Double, yd: Double): Point? =
            points.filter { it.cont == cont }.minByOrNull { hypot(it.wx - wx, it.wy - wy) }?.takeIf { hypot(it.wx - wx, it.wy - wy) <= yd }
    }

    /** two of a town's docks this close are one point */
    const val DOCK_MERGE_YD = 60.0

    /**
     * The scene. [rides] the game's (TransportRoutes, both ways); [masters] the real flight masters; [flights] the game's routes
     * ([from, to] node ids); [mine] the character's faction (TransportRoutes.ALLIANCE / HORDE, 0 = unknown: all friendly); [known] a
     * node this character has discovered; [connected] a flight between two known nodes is known (TaxiNet); [tram] the Deeprun Tram's
     * ends ([cont, ax, ay, bx, by]) or null; [zoneName] a place's zone, for a dock the table names nothing near.
     */
    fun scene(rides: List<TransportRoutes.Ride>, masters: List<TransportRoutes.FlightMaster>, flights: List<IntArray>, mine: Int,
              known: (Int) -> Boolean, connected: (Int, Int) -> Boolean, tram: DoubleArray?,
              zoneName: (Int, Double, Double) -> String?): Scene {
        val points = ArrayList<Point>()
        val lines = ArrayList<Line>()
        fun friendly(f: Int) = mine == 0 || f == 0 || f and mine != 0
        // ---- the docks: one point per dock (a town's rides from one pier merged), its destinations listed
        class Dock(val cont: Int, val x: Double, val y: Double, val name: String, val air: Boolean, val friendly: Boolean) {
            val to = LinkedHashSet<String>()
        }
        val docks = ArrayList<Dock>()
        fun dockAt(cont: Int, x: Double, y: Double, name: String, air: Boolean, fr: Boolean): Dock =
            docks.firstOrNull { it.cont == cont && it.air == air && hypot(it.x - x, it.y - y) <= DOCK_MERGE_YD }
                ?: Dock(cont, x, y, name, air, fr).also { docks.add(it) }
        val seen = HashSet<String>()
        for (r0 in rides) for (r in listOf(r0, r0.back())) {
            val ax = r.a[0].toDouble(); val ay = r.a[1].toDouble(); val bx = r.b[0].toDouble(); val by = r.b[1].toDouble()
            val nameA = r.na ?: zoneName(r.ca, ax, ay) ?: "Dock"
            val nameB = r.nb ?: zoneName(r.cb, bx, by) ?: "a dock"
            val d = dockAt(r.ca, ax, ay, nameA, r.air, friendly(r.fa))
            d.to.add(nameB)
            // the crossing's line once a pair, only when both docks are on one continent (another continent's dock is off this map)
            val k = listOf("${r.ca}:${ax.toInt()}:${ay.toInt()}", "${r.cb}:${bx.toInt()}:${by.toInt()}").sorted().joinToString(">")
            if (r.ca == r.cb && seen.add(k)) lines.add(Line(if (r.air) Kind.AIR else Kind.BOAT, r.ca, ax, ay, bx, by, friendly(r.fa) && friendly(r.fb)))
        }
        for (d in docks) {
            val what = if (d.air) "Zeppelin" else "Boat"
            points.add(Point(if (d.air) Kind.AIR else Kind.BOAT, d.cont, d.x, d.y, d.name,
                "${d.name}: ${what.lowercase()}s to ${d.to.joinToString(", ")}" + (if (!d.friendly) " (a hostile town)" else ""), d.friendly))
        }
        // ---- the Deeprun Tram
        if (tram != null && tram.size >= 5) {
            val c = tram[0].toInt()
            lines.add(Line(Kind.TRAM, c, tram[1], tram[2], tram[3], tram[4], true))
            val a = zoneName(c, tram[1], tram[2]) ?: "Stormwind"; val b = zoneName(c, tram[3], tram[4]) ?: "Ironforge"
            points.add(Point(Kind.TRAM, c, tram[1], tram[2], "Deeprun Tram", "Deeprun Tram: to $b", true))
            points.add(Point(Kind.TRAM, c, tram[3], tram[4], "Deeprun Tram", "Deeprun Tram: to $a", true))
        }
        // ---- the flight masters this faction can use, and the known routes between known ones
        val usable = masters.filter { it.real && friendly(it.flags and 3) }
        val byId = usable.associateBy { it.id }
        val routes = HashMap<Int, MutableSet<String>>()
        val drawn = HashSet<Long>()
        for (f in flights) {
            val a = byId[f[0]] ?: continue; val b = byId[f[1]] ?: continue
            if (a.cont != b.cont || !known(a.id) || !known(b.id) || !connected(a.id, b.id)) continue
            routes.getOrPut(a.id) { LinkedHashSet() }.add(b.town); routes.getOrPut(b.id) { LinkedHashSet() }.add(a.town)
            val key = minOf(a.id, b.id).toLong() shl 32 or maxOf(a.id, b.id).toLong()
            if (drawn.add(key)) lines.add(Line(Kind.FLIGHT, a.cont, a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble(), true))
        }
        for (m in usable) {
            val k = known(m.id)
            val text = when {
                !k -> "Flight master, ${m.town}: not discovered yet"
                routes[m.id].isNullOrEmpty() -> "Flight master, ${m.town}: discovered (its flights show once you open a flight master in its network)"
                else -> "Flight master, ${m.town}: flights to ${routes[m.id]!!.joinToString(", ")}"
            }
            points.add(Point(Kind.FLIGHT, m.cont, m.x.toDouble(), m.y.toDouble(), m.town, text, k, m.id, m.flags and 3))
        }
        return Scene(points, lines)
    }

    /** Home's small map shows the points this near the player only, and no lines (the user's pick) */
    const val SMALL_NEAR_YD = 300.0
}
