package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SAFEPATH'S NETWORK (b-safepath-net; the user, 2026-10-18: "We cant rely on height maps and borders and danger heaters on their own.
 * I think we need to designate safe pathing on every map and then clearly highlight the intended entry/exits to the surrounding zones.
 * Once we have that then the pathing should be easy"; their pick: "Auto-draft, then review"). Every zone gets a network of the ways to
 * walk it - its roads, the spurs to its flight master, docks and towns, trails where the roads leave pieces apart - and its EXITS, the
 * crossings into each neighbouring zone (a capital's are its gates). This is phase (a): the DRAFT, made on the device from what the
 * device already reads from the game - the learned road cells (Roads), the slopes (Terrain), the ground's own areas (the root ADTs'
 * MCNK area ids through AreaTable and UiMapAssignment: [ZONES_STEP]), the travel points (TransportRoutes) and QuestieDB's creatures
 * (the towns are where the player's side's NPCs gather; the hostile ones are the danger a draft edge is scored by). The user reviews
 * and corrects each zone later (b), the route runs on it (c). Kept on the device (safepath/<map>.draft.json), never shipped.
 * Pure but for [ZONES_STEP] and [refresh]: the zone's grids in, a network out.
 */
object SafePath {
    // a node's kind
    const val JUNCTION = "junction"; const val POINT = "point"; const val GATE = "gate"; const val FLIGHT = "flight"
    const val DOCK = "dock"; const val TRAM = "tram"; const val HUB = "hub"; const val EXIT = "exit"
    // an edge's kind, and its state (the review's)
    const val ROAD = "road"; const val SPUR = "spur"; const val TRAIL = "trail"
    const val CAVE = "cave"                // a way through a cave or a tunnel, on its own floor (Caves); its inside nodes are this kind too
    const val MOUTH = "mouth"              // a cave's mouth: where its floor meets the terrain
    const val LIFT = "lift"                // a lift's ride between its two landings (Caves.Lift; an edge, and its landings' nodes)
    const val DRAFT = "draft"; const val OK = "ok"; const val AVOID = "avoid"; const val REMOVED = "removed"
    const val REWALK = "rewalk"            // a way whose straight line crosses a wall, its ends joined by the ground's own leg (a lip on a road)

    const val CELL_YD = 25.0               // the draft's grid (Roads' and Terrain's cells)
    const val MIN_ROAD_CELLS = 8           // a road patch smaller than this is a texture's speck, not a road
    const val PRUNE_CELLS = 3              // a stub off a road this short is the thinning's whisker
    const val SIMPLIFY_YD = 14.0           // a line keeps its points where it bends more than this
    const val SPUR_MAX_YD = 700.0          // a travel point or a town joins the network this near, else it stands alone (the review sees it)
    const val TRAIL_MAX_YD = 1600.0        // a piece of the network joins the rest by a trail this near
    const val EXIT_MERGE_YD = 160.0
    const val WATER_MARGIN_YD = 10.0       // the ways keep this far off the water
    const val FORD_DEPTH = 1.5f            // water no deeper than this (yards) is a ford a border crossing may wade
    /** THE NARROW STREAM (the user's ruling, 2026-10-18, on Silverpine's stream by the Sepulcher: "Yes, you wade it"): water up to
     *  [NARROW_DEPTH] deep is crossed where it is no wider than [NARROW_YD]; [FORD_DEPTH] stays the limit for anything longer */
    const val NARROW_DEPTH = 2.2f
    /** the ground's grids (the lattice, the water, the zones) reach this far past a zone's rect: an exit's neighbour ground, a tunnel's
     *  far mouth just over the edge */
    const val GRID_PAD_YD = 450.0
    /** THE DETOUR CAP (the user: "Mobs are a cost, not a wall"; "Some of the paths ... seem overly complicated"): a way round the mobs
     *  longer than [DETOUR_FACTOR] times the way with no danger counted, plus [DETOUR_SLACK_YD], loses to that way */
    const val DETOUR_FACTOR = 2.5
    const val DETOUR_SLACK_YD = 1000.0
    /** of a route round the danger ([round]) and the one ignoring it ([direct]): the one to take ([DETOUR_FACTOR]) */
    fun capDetour(round: List<DoubleArray>, direct: List<DoubleArray>?): List<DoubleArray> {
        if (direct == null || direct.size < 2) return round
        fun len(w: List<DoubleArray>) = w.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }.sum()
        return if (len(round) > DETOUR_FACTOR * len(direct) + DETOUR_SLACK_YD) direct else round
    }
    const val NARROW_YD = 6.0
    const val FORD_COST = 4.0              // a ford's yard costs this much
    const val CLIMB_STUB = 1.8             // a dead-end spur or trail climbing this hard (its mean walking factor) ...
    const val CLIMB_STUB_YD = 900.0        // ... and shorter than this goes
    const val WELD_YD = 60.0               // a road's end this near another piece's way is welded to it
    const val WELD_ROUND_FACTOR = 4.0      // ... or its own piece's way, when the way round is this many times the gap ...
    const val WELD_ROUND_MIN_YD = 150.0    // ... and at least this long
    const val PAINT_FILL_CELLS = 5         // the painted road fills a learned road's gap this many cells along it
    const val PAINTED_COST = 0.5f          // a step on the painted road costs this much of the ground's (a trail follows the paint)
    const val BORDER_END_YD = 320.0        // a road ending this near another zone's ground goes on to it (an exit) ...
    const val ROAD_ON_YD = 250.0           // ... where that zone's own road goes on this near (a bridge's far end, a gate's road)        // crossings into one neighbour this near are one exit
    const val OFF_ZONE = 3f                // a spur or a trail through a neighbour's ground costs this much more
    const val DANGER_YD = 40.0             // a hostile creature this near makes a cell dangerous ...
    const val DANGER_COST = 4f             // ... and a dangerous cell costs up to this much more to a spur or a trail
    const val HUB_YD = 110.0               // the player's side's creatures this near each other are one town ...
    const val HUB_MIN = 5                  // ... when there are this many
    const val HUB_BY_STOP_YD = 150.0       // a town this near a flight master or a dock is that stop's (its NPCs are its crew)
    const val DOCK_SAME_YD = 60.0          // docks this near are one tower / pier
    const val STUB_YD = 250.0              // a dead end shorter than this goes (a road's fragment that leads nowhere) ...
    const val DANGER_STUB_YD = 600.0       // ... and a mostly dangerous one shorter than this
    const val TRAIL_STUB_YD = 400.0        // ... and one reached by a trail with less road than this at its end
    const val STRAY_CELLS = 24             // a piece left apart this small, with no stop or exit on it, goes

    class Node(val id: Int, val wx: Double, val wy: Double, val kind: String, val src: String = "auto", val name: String? = null)
    /** a way between nodes [a] and [b]; [danger] the share of it among hostile mobs; [climb] the ground's mean walking factor along it
     *  (Terrain.factor: 1 level, more over hills) */
    class Edge(val a: Int, val b: Int, val kind: String, val state: String = DRAFT, val src: String = "auto", val danger: Float = 0f,
               val climb: Float = 1f)
    /** [node] leads into uiMap [toMap] ([how]: walk / gate / tunnel / boat / zeppelin / tram / portal), named [name] */
    class Exit(val node: Int, val toMap: Int, val how: String, val name: String)

    /** one zone's network: world yards on the game's own axes (x north, y west), as the routes, the danger grid and CrossZone use them */
    class Net(val map: Int, val cont: Int, val built: String, val nodes: List<Node>, val edges: List<Edge>, val exits: List<Exit>,
              val reviewed: Boolean = false) {
        fun toJson(): JSONObject {
            fun r1(v: Double) = (v * 10).roundToInt() / 10.0
            return JSONObject().put("v", 1).put("map", map).put("cont", cont).put("built", built).put("reviewed", reviewed)
                .put("nodes", JSONArray(nodes.map { n -> JSONArray(listOfNotNull(n.id, r1(n.wx), r1(n.wy), n.kind, n.src, n.name)) }))
                .put("edges", JSONArray(edges.map { e -> JSONArray(listOf(e.a, e.b, e.kind, e.state, e.src, (e.danger * 100).roundToInt() / 100.0,
                    (e.climb * 100).roundToInt() / 100.0)) }))
                .put("exits", JSONArray(exits.map { x -> JSONArray(listOf(x.node, x.toMap, x.how, x.name)) }))
        }
        companion object {
            fun fromJson(o: JSONObject): Net? {
                if (o.optInt("v") != 1) return null
                fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
                val ns = arr("nodes"); val es = arr("edges"); val xs = arr("exits")
                return Net(o.optInt("map"), o.optInt("cont"), o.optString("built"),
                    (0 until ns.length()).mapNotNull { i -> ns.optJSONArray(i)?.let { a ->
                        Node(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optString(3), a.optString(4, "auto"), a.optString(5, "").ifEmpty { null }) } },
                    (0 until es.length()).mapNotNull { i -> es.optJSONArray(i)?.let { a ->
                        Edge(a.optInt(0), a.optInt(1), a.optString(2), a.optString(3, DRAFT), a.optString(4, "auto"), a.optDouble(5, 0.0).toFloat(),
                            a.optDouble(6, 1.0).toFloat()) } },
                    (0 until xs.length()).mapNotNull { i -> xs.optJSONArray(i)?.let { a -> Exit(a.optInt(0), a.optInt(1), a.optString(2), a.optString(3)) } },
                    o.optBoolean("reviewed"))
            }
        }
    }

    /** a point the network must reach: a flight master, a dock, a town; [toMap] / [how] = it also leads out of the zone (a boat) */
    class Fixed(val wx: Double, val wy: Double, val kind: String, val name: String, val toMap: Int = 0, val how: String? = null)

    /**
     * a zone's draft input: uiMap [map] on continent [cont], its world rect ([t] / [b] world x, [l] / [r] world y: the grids' frame);
     * [road] a learned road shows most at a spot; [cost] the ground's walking factor there (Terrain.factor: [Terrain.WALL] = no way, NaN
     * = nothing known); [zoneAt] the uiMap whose ground it is (0 = none known); [danger] 0..1 there; [nameOf] a uiMap's name
     */
    class Input(val map: Int, val cont: Int, val t: Double, val b: Double, val l: Double, val r: Double,
                val road: (Double, Double) -> Boolean, val cost: (Double, Double) -> Float, val zoneAt: (Double, Double) -> Int,
                val fixed: List<Fixed> = emptyList(), val nameOf: (Int) -> String = { "" },
                val danger: ((Double, Double) -> Float)? = null, val capitals: Set<Int> = CityExits.CITIES,
                val ground: ((Double, Double) -> Float)? = null, val painted: ((Double, Double) -> Boolean)? = null,
                val water: ((Double, Double) -> Float)? = null)

    /** the zone's first draft */
    fun draft(inp: Input, built: String = ""): Net = Builder(inp).build(built)

    // ---------------------------------------------------------------- the draft's work

    private class Builder(val inp: Input) {
        val nx = max(1, ceil((inp.l - inp.r) / CELL_YD).toInt())
        val ny = max(1, ceil((inp.t - inp.b) / CELL_YD).toInt())
        val cw = (inp.l - inp.r) / nx
        val chh = (inp.t - inp.b) / ny
        fun wxOf(k: Int) = inp.t - (k / nx + 0.5) * chh
        fun wyOf(k: Int) = inp.l - (k % nx + 0.5) * cw
        fun cellOf(x: Double, y: Double): Int {
            val i = floor((inp.l - y) / cw).toInt(); val j = floor((inp.t - x) / chh).toInt()
            return if (i in 0 until nx && j in 0 until ny) j * nx + i else -1
        }
        val n = nx * ny
        val own = inp.map
        val zone = IntArray(n) { k -> inp.zoneAt(wxOf(k), wyOf(k)) }
        val cost = FloatArray(n) { k ->
            val c = inp.cost(wxOf(k), wyOf(k)); val g = inp.ground?.invoke(wxOf(k), wyOf(k)) ?: 1f
            if (g >= Terrain.WALL) Terrain.WALL else if (c.isNaN()) g else c * g
        }
        val danger = FloatArray(n) { k -> inp.danger?.invoke(wxOf(k), wyOf(k)) ?: 0f }
        /** the zone's map paints a road here (the user: "its pretty clearly shown on the map where the safe walking trails are") */
        val painted = BooleanArray(n) { k -> inp.painted?.invoke(wxOf(k), wyOf(k)) ?: false }
        /** a cell's water, with a [WATER_MARGIN_YD] margin round it: 0 dry, 1 a shallow ford (no deeper than [FORD_DEPTH]), 2 deep,
         *  3 a bridge over it (its middle on a building's walkway: the margin's open water either side does not wall it) */
        val wet = ByteArray(n) { k ->
            val f = inp.water ?: return@ByteArray 0
            var deepest = Float.NaN
            // a bridge's landing too: a cell whose margin reaches the walkway is its way on (never walled by the water beside it)
            val m0 = CELL_YD / 2 + WATER_MARGIN_YD
            for (a in -2..2) for (bb in -2..2) if (f(wxOf(k) + a * m0 / 2, wyOf(k) + bb * m0 / 2) == Water.BRIDGE) return@ByteArray 3
            val m = CELL_YD / 2 + WATER_MARGIN_YD
            for (a in -2..2) for (bb in -2..2) {
                val d = f(wxOf(k) + a * m / 2, wyOf(k) + bb * m / 2).let { if (it == Water.BRIDGE) Float.NaN else it }
                if (!d.isNaN() && (deepest.isNaN() || d > deepest)) deepest = d
            }
            (if (deepest.isNaN()) 0 else if (deepest <= FORD_DEPTH) 1 else 2).toByte()
        }
        /** dry ground the ways may take (water is a wall: the user, "Pathing shouldn't be in water") */
        fun passable(k: Int) = !cost[k].isNaN() && cost[k] < Terrain.WALL && (wet[k].toInt() == 0 || wet[k].toInt() == 3)

        inner class E(val a: Int, val b: Int, val kind: String, val cells: List<Int>)
        val nodes = ArrayList<Node>()
        val edges = ArrayList<E>()
        val exits = ArrayList<Exit>()
        val fixedIds = ArrayList<Int>()
        val nodeAtCell = HashMap<Int, Int>()
        var roadAll = BooleanArray(0)
        /** a road of zone [to] within [yd] of cell [c] (the way goes on there: a bridge's, a gate's other side) */
        fun roadNear(c: Int, to: Int, yd: Double): Boolean {
            val rr = ceil(yd / CELL_YD).toInt(); val i0 = c % nx; val j0 = c / nx
            for (dj in -rr..rr) for (di in -rr..rr) {
                val i = i0 + di; val j = j0 + dj
                if (i !in 0 until nx || j !in 0 until ny || hypot(di.toDouble(), dj.toDouble()) > rr) continue
                val k = j * nx + i
                if (roadAll[k] && zone[k] == to) return true
            }
            return false
        }
        val cellEdge = HashMap<Int, E>()

        fun addNode(x: Double, y: Double, kind: String, name: String? = null): Int {
            val id = nodes.size
            nodes.add(Node(id, x, y, kind, name = name))
            cellOf(x, y).takeIf { it >= 0 && it !in nodeAtCell }?.let { nodeAtCell[it] = id }
            return id
        }
        fun nodeAt(k: Int, kind: String): Int = nodeAtCell[k] ?: addNode(wxOf(k), wyOf(k), kind).also { nodeAtCell[k] = it }
        fun addEdge(a: Int, b: Int, kind: String, cells: List<Int>) {
            if (a == b) return
            val e = E(a, b, kind, cells)
            edges.add(e)
            for (c in cells) if (c !in cellEdge) cellEdge[c] = e
        }

        fun build(built: String): Net {
            val m = BooleanArray(n) { k -> passable(k) && inp.road(wxOf(k), wyOf(k)) }
            // the painted road fills the learned road's gaps (a texture's blend, a bridge): painted cells near a learned road count too
            fillFromPaint(m)
            roadAll = m.copyOf()
            close(m); dropSmall(m); thin(m); prune(m)
            chains(m)
            weld()
            borderEnds()
            mergeExits()
            val roadless = edges.isEmpty()
            for (f in stops(inp.fixed)) fixed(f)
            // a town by the border (Far Watch Post by its bridge): on to the neighbour's ground too, unless an exit there already is
            borderEnds(stopsToo = true)
            // a zone with no road of its own (a capital: its streets are buildings, its ground plain dirt): its points joined by trails,
            // its ways out found from them
            if (roadless) { linkPoints(); waysOut(); mergeExits() }
            joinPieces()
            // the clean-up: trails that only double a road, the pieces they leave apart, the stubs that lead nowhere
            dropDoubles()
            joinPieces()
            dropStrays()
            trimStubs()
            joinOrphanExits()
            val out = edges.map { e ->
                // its danger: the share of its length that passes hostile creatures (two or more near)
                val d = if (e.cells.isEmpty()) 0f else e.cells.count { danger[it] >= 0.6f }.toFloat() / e.cells.size
                // its climb: the ground's mean walking factor along it (a trail over the hills costs what they cost)
                val cl = if (e.cells.isEmpty()) 1f else e.cells.map { k -> cost[k].let { c -> if (c.isNaN()) 1f else min(c, Terrain.STEEP * 4) } }.average().toFloat()
                Edge(e.a, e.b, e.kind, danger = d, climb = max(1f, cl))
            }
            // only the nodes something uses (the merged exits' old ones go), numbered again
            val used = (out.flatMap { listOf(it.a, it.b) } + exits.map { it.node } + fixedIds).toSortedSet()
            val renum = HashMap<Int, Int>()
            val ns = used.mapIndexed { i, id -> renum[id] = i; nodes[id].let { Node(i, it.wx, it.wy, it.kind, it.src, it.name) } }
            return Net(inp.map, inp.cont, built, ns, out.map { Edge(renum[it.a]!!, renum[it.b]!!, it.kind, it.state, it.src, it.danger, it.climb) },
                exits.map { Exit(renum[it.node]!!, it.toMap, it.how, it.name) })
        }

        // ---- the road cells to one-cell lines
        fun nb8(k: Int): IntArray {
            val i = k % nx; val j = k / nx
            val out = IntArray(8); var c = 0
            for (dj in -1..1) for (di in -1..1) {
                if (di == 0 && dj == 0) continue
                val a = i + di; val bb = j + dj
                if (a in 0 until nx && bb in 0 until ny) out[c++] = bb * nx + a
            }
            return out.copyOf(c)
        }
        /** a closing (dilate, then erode): a road's one-cell gaps (a texture's blend) filled */
        fun close(m: BooleanArray) {
            val d = BooleanArray(n) { k -> m[k] || (passable(k) && nb8(k).any { m[it] }) }
            for (k in 0 until n) m[k] = d[k] && nb8(k).size == 8 && nb8(k).all { d[it] }
        }
        fun dropSmall(m: BooleanArray) {
            val seen = BooleanArray(n)
            for (s in 0 until n) {
                if (!m[s] || seen[s]) continue
                val comp = ArrayList<Int>(); val q = ArrayDeque<Int>(); q.add(s); seen[s] = true
                while (q.isNotEmpty()) { val k = q.removeFirst(); comp.add(k); for (o in nb8(k)) if (m[o] && !seen[o]) { seen[o] = true; q.add(o) } }
                if (comp.size < MIN_ROAD_CELLS) for (k in comp) m[k] = false
            }
        }
        /** the 8 around (k): P2..P9 clockwise from north, as Zhang-Suen numbers them; off the grid = false */
        fun ring(m: BooleanArray, k: Int): BooleanArray {
            val i = k % nx; val j = k / nx
            fun at(di: Int, dj: Int): Boolean { val a = i + di; val bb = j + dj; return a in 0 until nx && bb in 0 until ny && m[bb * nx + a] }
            return booleanArrayOf(at(0, -1), at(1, -1), at(1, 0), at(1, 1), at(0, 1), at(-1, 1), at(-1, 0), at(-1, -1))
        }
        fun crossings(p: BooleanArray): Int { var c = 0; for (t in 0 until 8) if (!p[t] && p[(t + 1) % 8]) c++; return c }
        /** Zhang-Suen thinning: the roads as one-cell centre lines */
        fun thin(m: BooleanArray) {
            var changed = true
            while (changed) {
                changed = false
                for (step in 0..1) {
                    val del = ArrayList<Int>()
                    for (k in 0 until n) {
                        if (!m[k]) continue
                        val p = ring(m, k)
                        val b = p.count { it }
                        if (b < 2 || b > 6 || crossings(p) != 1) continue
                        val ok = if (step == 0) !(p[0] && p[2] && p[4]) && !(p[2] && p[4] && p[6]) else !(p[0] && p[2] && p[6]) && !(p[0] && p[4] && p[6])
                        if (ok) del.add(k)
                    }
                    for (k in del) m[k] = false
                    if (del.isNotEmpty()) changed = true
                }
            }
        }
        fun isEnd(m: BooleanArray, k: Int) = crossings(ring(m, k)) == 1 && ring(m, k).count { it } <= 2
        fun isJunction(m: BooleanArray, k: Int) = crossings(ring(m, k)) >= 3
        /** the thinning's whiskers: a stub from an end to a junction this short goes */
        fun prune(m: BooleanArray) {
            for (k in 0 until n) {
                if (!m[k] || !isEnd(m, k)) continue
                val path = arrayListOf(k); var cur = k; var prev = -1
                while (path.size <= PRUNE_CELLS) {
                    val next = nb8(cur).filter { m[it] && it != prev && it !in path }
                    if (next.size != 1) break
                    prev = cur; cur = next[0]
                    if (isJunction(m, cur)) { for (c in path) m[c] = false; break }
                    path.add(cur)
                }
            }
        }

        // ---- the lines to a graph: the nodes where a line ends or meets another, the lines between them cut at the zone's border
        fun chains(m: BooleanArray) {
            val isNode = BooleanArray(n) { k -> m[k] && (isEnd(m, k) || isJunction(m, k) || nb8(k).none { m[it] }) }
            // a junction's cells together are one node: its middle cell stands for it
            val cluster = IntArray(n) { -1 }
            val rep = ArrayList<Int>()
            for (s in 0 until n) {
                if (!isNode[s] || cluster[s] >= 0) continue
                val comp = ArrayList<Int>(); val q = ArrayDeque<Int>(); q.add(s); cluster[s] = rep.size
                while (q.isNotEmpty()) { val k = q.removeFirst(); comp.add(k); for (o in nb8(k)) if (isNode[o] && cluster[o] < 0) { cluster[o] = rep.size; q.add(o) } }
                val cx = comp.map { it % nx }.average(); val cy = comp.map { it / nx }.average()
                rep.add(comp.minByOrNull { hypot(it % nx - cx, it / nx - cy) }!!)
            }
            val visited = BooleanArray(n)
            fun walk(start: Int, first: Int): List<Int> {
                val path = arrayListOf(start, first)
                if (!isNode[first]) visited[first] = true
                var cur = first
                while (!isNode[cur]) {
                    val back = path[path.size - 2]
                    val ns = nb8(cur).filter { m[it] && it != back }
                    val stop = ns.firstOrNull { isNode[it] && !(cluster[it] == cluster[start] && path.size <= 2) }
                    if (stop != null) { path.add(stop); break }
                    val i0 = cur % nx; val j0 = cur / nx
                    val nxt = ns.filter { !visited[it] && !isNode[it] }.minByOrNull { abs(it % nx - i0) + abs(it / nx - j0) } ?: break
                    visited[nxt] = true; path.add(nxt); cur = nxt
                }
                return path
            }
            val lines = ArrayList<List<Int>>()
            for (k in 0 until n) if (isNode[k]) for (q in nb8(k)) if (m[q] && !isNode[q] && !visited[q]) lines.add(walk(k, q))
            // a ring with no end or junction (a road round a lake): one of its cells made a node
            for (k in 0 until n) if (m[k] && !isNode[k] && !visited[k]) {
                isNode[k] = true; cluster[k] = rep.size; rep.add(k)
                for (q in nb8(k)) if (m[q] && !isNode[q] && !visited[q]) lines.add(walk(k, q))
            }
            for (line in lines) addLine(line.map { c -> if (isNode[c]) rep[cluster[c]] else c }, isNode, m)
        }

        /** one line (cells): its runs on this zone's ground become edges; where it crosses into a neighbour, an exit */
        fun addLine(cells: List<Int>, isNode: BooleanArray, m: BooleanArray) {
            var i = 0
            while (i < cells.size) {
                if (zone[cells[i]] != own) { i++; continue }
                var k = i
                while (k + 1 < cells.size && zone[cells[k + 1]] == own) k++
                val start = if (i == 0) endNode(cells[0], isNode, m) else crossing(cells[i - 1], cells[i])
                val end = if (k == cells.size - 1) endNode(cells[k], isNode, m) else crossing(cells[k + 1], cells[k])
                emit(start, end, cells.subList(i, k + 1), ROAD)
                i = k + 1
            }
        }
        fun endNode(c: Int, isNode: BooleanArray, m: BooleanArray): Int = nodeAt(c, if (isNode[c] && isJunction(m, c)) JUNCTION else POINT)
        /** where a line leaves this zone's ground ([inside] the last cell on it, [outside] the next): an exit into that neighbour, or
         *  (nothing known there, the sea) the line just ends */
        fun crossing(outside: Int, inside: Int): Int {
            val to = zone[outside]
            if (wet[inside].toInt() != 0 && to != 0 && to != own) {
                // on a bridge: back along the road to its bank is the walk's own business; the exit names the bridge
                val id = addNode((wxOf(outside) + wxOf(inside)) / 2, (wyOf(outside) + wyOf(inside)) / 2, EXIT)
                exits.add(Exit(id, to, "bridge", inp.nameOf(to)))
                return id
            }
            if (to == 0 || to == own) return nodeAt(inside, POINT)
            val id = addNode((wxOf(outside) + wxOf(inside)) / 2, (wyOf(outside) + wyOf(inside)) / 2, EXIT)
            exits.add(Exit(id, to, if (to in inp.capitals || own in inp.capitals) "gate" else "walk", inp.nameOf(to)))
            return id
        }
        /** a run of cells from node [a] to node [b], its bends kept as points (Douglas-Peucker) */
        fun emit(a: Int, b: Int, cells: List<Int>, kind: String) {
            if (cells.isEmpty()) { addEdge(a, b, kind, emptyList()); return }
            val keep = BooleanArray(cells.size); keep[0] = true; keep[cells.size - 1] = true
            fun dp(s: Int, e: Int) {
                if (e <= s + 1) return
                val ax = wxOf(cells[s]); val ay = wyOf(cells[s]); val bx = wxOf(cells[e]); val by = wyOf(cells[e])
                val len = hypot(bx - ax, by - ay)
                var worst = -1; var wd = 0.0
                for (t in s + 1 until e) {
                    val px = wxOf(cells[t]); val py = wyOf(cells[t])
                    val d = if (len < 1e-6) hypot(px - ax, py - ay) else abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
                    if (d > wd) { wd = d; worst = t }
                }
                if (wd > SIMPLIFY_YD) { keep[worst] = true; dp(s, worst); dp(worst, e) }
            }
            dp(0, cells.size - 1)
            var from = a; var fromAt = 0
            for (t in 1 until cells.size) {
                if (!keep[t]) continue
                val to = if (t == cells.size - 1) b else nodeAt(cells[t], POINT)
                addEdge(from, to, kind, cells.subList(fromAt, t + 1).toList())
                from = to; fromAt = t
            }
            if (cells.size == 1) addEdge(a, b, kind, cells.toList())
        }

        /** a road that ends short of the border (a bridge, a city's gate: buildings, no road texture): on to the neighbour's ground if
         *  it is this near, an exit there */
        fun borderEnds(stopsToo: Boolean = false) {
            val deg = HashMap<Int, Int>()
            for (e in edges) { deg[e.a] = (deg[e.a] ?: 0) + 1; deg[e.b] = (deg[e.b] ?: 0) + 1 }
            val exitNodes = exits.map { it.node }.toHashSet()
            for ((id, d) in deg.toList()) {
                if (d != 1 || id in exitNodes) continue
                if (stopsToo && nodes[id].kind != HUB && nodes[id].kind != FLIGHT) continue
                if (stopsToo && exits.any { x -> nodes[x.node].let { hypot(it.wx - nodes[id].wx, it.wy - nodes[id].wy) } < PAIR_YD }) continue
                val c = cellOf(nodes[id].wx, nodes[id].wy).takeIf { it >= 0 } ?: continue
                val path = search(listOf(c), BORDER_END_YD, ford = true) { k -> zone[k] != own && zone[k] != 0 && passable(k) && roadNear(k, zone[k], ROAD_ON_YD) } ?: continue
                if (path.size < 2) continue
                bankExit(id, path, SPUR)
            }
        }

        /** the painted road's cells within [PAINT_FILL_CELLS] of a learned road cell, grown along the paint (a gap closed where the
         *  map paints the road on), never the paint far from any learned road (its speckles over the hills) */
        fun fillFromPaint(m: BooleanArray) {
            if (painted.none { it }) return
            val near = IntArray(n) { Int.MAX_VALUE }
            val q = ArrayDeque<Int>()
            for (k in 0 until n) if (m[k]) { near[k] = 0; q.add(k) }
            while (q.isNotEmpty()) {
                val k = q.removeFirst()
                if (near[k] >= PAINT_FILL_CELLS) continue
                for (o in nb8(k)) if (painted[o] && passable(o) && near[o] > near[k] + 1) { near[o] = near[k] + 1; q.add(o) }
            }
            for (k in 0 until n) if (near[k] in 1..PAINT_FILL_CELLS) m[k] = true
        }

        /** road pieces apart by a texture's gap: an end this near another piece's way is welded to it (one network: the user on
         *  test9's route, "why is it looping down and around an up onto the mountains?") */
        fun weld() {
            val parent = HashMap<Int, Int>()
            fun find(x: Int): Int { var a = x; while ((parent[a] ?: a) != a) a = parent[a]!!; return a }
            fun union(a: Int, b: Int) { val x = find(a); val y = find(b); if (x != y) parent[x] = y }
            for (e in edges) union(e.a, e.b)
            val deg = HashMap<Int, Int>()
            for (e in edges) { deg[e.a] = (deg[e.a] ?: 0) + 1; deg[e.b] = (deg[e.b] ?: 0) + 1 }
            val exitNodes = exits.map { it.node }.toHashSet()
            // the network's own lengths from a node (its edges' cells): a gap within one piece is closed too when the way round is far
            // longer than the gap (Westfall's main road: its halves 25 yd apart, joined only round the west loop)
            fun netDist(from: Int): Map<Int, Double> {
                val adj = HashMap<Int, ArrayList<Pair<Int, Double>>>()
                for (e in edges) { val w = e.cells.size * CELL_YD; adj.getOrPut(e.a) { ArrayList() }.add(e.b to w); adj.getOrPut(e.b) { ArrayList() }.add(e.a to w) }
                val dist = HashMap<Int, Double>(); val pq = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
                dist[from] = 0.0; pq.add(0.0 to from)
                while (pq.isNotEmpty()) { val (d0, u) = pq.poll(); if (d0 > (dist[u] ?: Double.MAX_VALUE)) continue
                    for ((v, w) in adj[u] ?: continue) { val nd = d0 + w; if (nd < (dist[v] ?: Double.MAX_VALUE)) { dist[v] = nd; pq.add(nd to v) } } }
                return dist
            }
            for ((id, d) in deg.toList()) {
                if (d != 1 || id in exitNodes) continue
                val c = cellOf(nodes[id].wx, nodes[id].wy).takeIf { it >= 0 } ?: continue
                val round by lazy { netDist(id) }
                val path = search(listOf(c), WELD_YD) { k -> cellEdge[k]?.let { e -> e.a != id && e.b != id && (find(e.a) != find(id) ||
                    minOf(round[e.a] ?: Double.MAX_VALUE, round[e.b] ?: Double.MAX_VALUE) >= maxOf(WELD_ROUND_MIN_YD, WELD_ROUND_FACTOR * hypot(wxOf(k) - nodes[id].wx, wyOf(k) - nodes[id].wy))) } == true } ?: continue
                val e = cellEdge[path.last()] ?: continue
                val to = split(e, path.last())
                emit(id, to, path, ROAD)
                union(id, to)
            }
        }

        /** a way [path] from node [from] onto a neighbour's ground: over water (a bridge, or a shallow ford), the exit stands on THIS
         *  bank (the last dry cell before it: never a node in the water) and says which; else at the border, as a road's crossing */
        fun bankExit(from: Int, path: List<Int>, kind: String) {
            val firstWet = path.indexOfFirst { wet[it].toInt() != 0 }
            val how = if (path.any { wet[it].toInt() == 1 }) "ford" else "bridge"
            if (firstWet < 0) { val x = crossing(path.last(), path[path.size - 2]); emit(from, x, path.dropLast(1), kind); return }
            if (firstWet == 0) return
            val bank = path[firstWet - 1]
            val to = zone[path.last()]
            val id = if (bank == path[0]) from else addNode(wxOf(bank), wyOf(bank), EXIT)
            if (id != from) emit(from, id, path.subList(0, firstWet), kind) else {
                val i = nodes.indexOfFirst { it.id == from }; nodes[i].let { nodes[i] = Node(it.id, it.wx, it.wy, EXIT, it.src, it.name) }
            }
            exits.add(Exit(id, to, how, inp.nameOf(to)))
        }

        /** the crossings into one neighbour this near each other: one exit (their middle), the edges moved onto it */
        fun mergeExits() {
            val gone = HashMap<Int, Int>()
            val kept = ArrayList<Exit>()
            for (x in exits) {
                val nx0 = nodes[x.node]
                val k = kept.firstOrNull { y -> y.toMap == x.toMap && nodes[y.node].let { hypot(it.wx - nx0.wx, it.wy - nx0.wy) } < EXIT_MERGE_YD }
                if (k == null) kept.add(x) else gone[x.node] = k.node
            }
            if (gone.isEmpty()) return
            // the kept one at the middle of its group
            for (k in kept) {
                val group = listOf(k.node) + gone.filterValues { it == k.node }.keys
                if (group.size < 2) continue
                val old = nodes[k.node]
                nodes[k.node] = Node(old.id, group.map { nodes[it].wx }.average(), group.map { nodes[it].wy }.average(), EXIT)
            }
            val moved = edges.map { e -> E(gone[e.a] ?: e.a, gone[e.b] ?: e.b, e.kind, e.cells) }.filter { it.a != it.b }
                .distinctBy { if (it.a < it.b) it.a to it.b else it.b to it.a }
            edges.clear(); cellEdge.clear()
            for (e in moved) { edges.add(e); for (c in e.cells) if (c !in cellEdge) cellEdge[c] = e }
            exits.clear(); exits.addAll(kept)
        }

        /** edge [e] cut at its cell [c]: the node there */
        fun split(e: E, c: Int): Int {
            val idx = e.cells.indexOf(c)
            if (idx <= 0) return e.a
            if (idx >= e.cells.size - 1) return e.b
            val mid = nodeAt(c, POINT)
            edges.remove(e)
            val e1 = E(e.a, mid, e.kind, e.cells.subList(0, idx + 1)); val e2 = E(mid, e.b, e.kind, e.cells.subList(idx, e.cells.size))
            edges.add(e1); edges.add(e2)
            for (x in e1.cells) cellEdge[x] = e1
            for (x in e2.cells) cellEdge[x] = e2
            return mid
        }

        /** the cheapest walk on the ground from the [starts] to a cell [goal] says yes to, within [maxYd]; null = none */
        fun search(starts: List<Int>, maxYd: Double, ford: Boolean = false, goal: (Int) -> Boolean): List<Int>? {
            val dist = HashMap<Int, Float>(); val walked = HashMap<Int, Double>(); val from = HashMap<Int, Int>()
            val q = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
            for (s in starts) { dist[s] = 0f; walked[s] = 0.0; q.add(0f to s) }
            val startSet = starts.toHashSet()
            while (q.isNotEmpty()) {
                val (d, k) = q.poll()
                if (d > (dist[k] ?: Float.MAX_VALUE)) continue
                if (k !in startSet && goal(k)) {
                    val path = arrayListOf(k); var c = k
                    while (c in from) { c = from[c]!!; path.add(c) }
                    return path.reversed()
                }
                for (o in nb8(k)) {
                    // a ford's shallow water only where the search may cross a river (a border), and dearer
                    val shallow = ford && wet[o].toInt() == 1 && !cost[o].isNaN() && cost[o] < Terrain.WALL
                    if (!passable(o) && !shallow) continue
                    val step = (if (o % nx != k % nx && o / nx != k / nx) CELL_YD * 1.4142 else CELL_YD) * (if (shallow) FORD_COST else 1.0)
                    val w = walked[k]!! + step
                    if (w > maxYd) continue
                    val c = (step * cost[o] * (1f + DANGER_COST * danger[o]) * (if (zone[o] == own) 1f else OFF_ZONE) *
                        (if (painted[o]) PAINTED_COST else 1f)).toFloat()
                    val nd = d + c
                    if (nd < (dist[o] ?: Float.MAX_VALUE)) { dist[o] = nd; walked[o] = w; from[o] = k; q.add(nd to o) }
                }
            }
            return null
        }

        /** a travel point / a town: its node, joined to the nearest of the network by a spur; a boat's dock an exit too */
        fun fixed(f: Fixed) {
            val k = cellOf(f.wx, f.wy)
            if (k < 0 || zone[k] != own) return
            val id = addNode(f.wx, f.wy, f.kind, f.name)
            fixedIds.add(id)
            if (f.toMap != 0) exits.add(Exit(id, f.toMap, f.how ?: "boat", f.name))
            if (edges.isEmpty()) return
            val path = search(listOf(k), SPUR_MAX_YD, ford = wet[k].toInt() != 0) { c -> c in cellEdge } ?: return
            val at = cellEdge[path.last()]?.let { split(it, path.last()) } ?: return
            emit(id, at, path, SPUR)
        }

        /**
         * an exit no way reaches (the merges and the clean-up can leave one: Durotar's bridge to the Barrens, the user's accepted
         * crossing at Far Watch Post, after the thin roads): joined by a spur to the nearest way, as a travel point is
         */
        fun joinOrphanExits() {
            reindex()
            val linked = edges.flatMap { listOf(it.a, it.b) }.toHashSet()
            for (x in exits.toList()) {
                if (x.node in linked || edges.isEmpty()) continue
                val c = cellOf(nodes[x.node].wx, nodes[x.node].wy).takeIf { it >= 0 } ?: continue
                val path = search(listOf(c), SPUR_MAX_YD, ford = true) { k -> k in cellEdge } ?: continue
                val at = cellEdge[path.last()]?.let { split(it, path.last()) } ?: continue
                emit(x.node, at, path, SPUR); reindex(); linked.add(x.node)
            }
        }

        /** a roadless zone: each of its points joined to the nearest other point or trail */
        fun linkPoints() {
            for (id in fixedIds.toList()) {
                val c = cellOf(nodes[id].wx, nodes[id].wy).takeIf { it >= 0 } ?: continue
                val path = search(listOf(c), TRAIL_MAX_YD) { k -> k in cellEdge || (nodeAtCell[k]?.let { it != id && it in fixedIds } == true) } ?: continue
                val end = path.last()
                val to = nodeAtCell[end]?.takeIf { it in fixedIds } ?: cellEdge[end]?.let { split(it, end) } ?: continue
                emit(id, to, path, TRAIL)
            }
        }
        /** a roadless zone's ways out: from its trails, the nearest ground of each neighbour whose own road goes on there (a gate) */
        fun waysOut() {
            val done = HashSet<Int>()
            repeat(4) {
                val starts = (cellEdge.keys + fixedIds.mapNotNull { cellOf(nodes[it].wx, nodes[it].wy).takeIf { c -> c >= 0 } }).distinct()
                if (starts.isEmpty()) return
                val path = search(starts, TRAIL_MAX_YD, ford = true) { k -> zone[k] != own && zone[k] != 0 && zone[k] !in done && passable(k) && roadNear(k, zone[k], ROAD_ON_YD) } ?: return
                if (path.size < 2) return
                done.add(zone[path.last()])
                val first = path.first()
                val from = nodeAtCell[first] ?: cellEdge[first]?.let { split(it, first) } ?: return
                bankExit(from, path, TRAIL)
            }
        }

        /** the stops: a town this near a flight master or a dock is that stop's (the zeppelin tower's crew is not a town) */
        fun stops(all: List<Fixed>): List<Fixed> = all.filter { f ->
            f.kind != HUB || all.none { o -> o.kind == DOCK && hypot(o.wx - f.wx, o.wy - f.wy) < HUB_BY_STOP_YD }
        }
        fun reindex() { cellEdge.clear(); for (e in edges) for (c in e.cells) if (c !in cellEdge) cellEdge[c] = e }
        /** a trail between two places the network already joins without it only doubles a way (the longest such first); a trail
         *  filling a road's gap (its texture missing on a bridge or a bend) joins what nothing else does, and stays */
        fun dropDoubles() {
            for (e in edges.filter { it.kind == TRAIL }.sortedByDescending { it.cells.size }) {
                val adj = HashMap<Int, MutableList<Int>>()
                for (o in edges) if (o !== e) { adj.getOrPut(o.a) { ArrayList() }.add(o.b); adj.getOrPut(o.b) { ArrayList() }.add(o.a) }
                val seen = hashSetOf(e.a); val q = ArrayDeque(listOf(e.a))
                while (q.isNotEmpty() && e.b !in seen) for (x in adj[q.removeFirst()] ?: emptyList()) if (seen.add(x)) q.add(x)
                if (e.b in seen) edges.remove(e)
            }
            reindex()
        }
        fun pieces(): Map<Int, List<E>> {
            val parent = HashMap<Int, Int>()
            fun find(x: Int): Int { var a = x; while ((parent[a] ?: a) != a) a = parent[a]!!; return a }
            for (e in edges) { val a = find(e.a); val b = find(e.b); if (a != b) parent[a] = b }
            return edges.groupBy { find(it.a) }
        }
        /** a small piece left apart with no stop or exit on it */
        fun dropStrays() {
            val keep = exits.map { it.node }.toHashSet() + fixedIds
            val ps = pieces()
            val main = ps.maxByOrNull { (_, es) -> es.sumOf { it.cells.size } }?.key
            for ((root, es) in ps) {
                if (root == main || es.sumOf { it.cells.size } >= STRAY_CELLS) continue
                if (es.any { it.a in keep || it.b in keep }) continue
                edges.removeAll(es.toSet())
            }
            reindex()
        }
        /** a dead end that leads nowhere: short, or mostly among hostile creatures and not long (never one ending at a stop or an exit) */
        fun trimStubs() {
            val keep = exits.map { it.node }.toHashSet() + fixedIds
            repeat(6) {
                val deg = HashMap<Int, Int>()
                for (e in edges) { deg[e.a] = (deg[e.a] ?: 0) + 1; deg[e.b] = (deg[e.b] ?: 0) + 1 }
                var removed = false
                for ((start, d) in deg.toList()) {
                    if (d != 1 || start in keep) continue
                    val chain = ArrayList<E>(); var cur = start
                    while (true) {
                        val e = edges.firstOrNull { (it.a == cur || it.b == cur) && it !in chain } ?: break
                        chain.add(e)
                        cur = if (e.a == cur) e.b else e.a
                        if (deg[cur] != 2 || cur in keep) break
                    }
                    if (chain.isEmpty() || chain.any { it !in edges }) continue
                    val cells = chain.sumOf { it.cells.size }
                    val bad = chain.sumOf { e -> e.cells.count { danger[it] >= 0.6f } }
                    val yd = cells * CELL_YD
                    // a road's fragment reached only by a trail and leading nowhere goes too (the trail was only there to join it)
                    val roadYd = chain.filter { it.kind == ROAD }.sumOf { it.cells.size } * CELL_YD
                    val viaTrail = chain.any { it.kind == TRAIL } && roadYd < TRAIL_STUB_YD
                    // a spur or a trail that only climbs to nothing (test11: "shooting up into the mountain")
                    val climb = chain.flatMap { it.cells }.map { k -> cost[k].let { c -> if (c.isNaN()) 1f else c } }.average()
                    val uphill = chain.all { it.kind != ROAD } && climb >= CLIMB_STUB && yd < CLIMB_STUB_YD
                    if (yd < STUB_YD || (bad * 2 >= cells && yd < DANGER_STUB_YD) || viaTrail || uphill) { edges.removeAll(chain.toSet()); removed = true }
                }
                reindex()
                if (!removed) return
            }
        }

        /** the network's pieces: each smaller one joined to the biggest by a trail (the review looks at every trail) */
        fun joinPieces() {
            repeat(12) {
                val parent = IntArray(nodes.size) { it }
                fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
                for (e in edges) parent[find(e.a)] = find(e.b)
                val members = edges.flatMap { listOf(it.a, it.b) }.distinct().groupBy { find(it) }
                if (members.size < 2) return
                val main = members.maxByOrNull { (_, v) -> v.size }!!.key
                var joined = false
                for ((root, ids) in members.entries.sortedByDescending { it.value.size }) {
                    if (root == main) continue
                    val mine = edges.filter { find(it.a) == root }.flatMap { it.cells }.toHashSet()
                    val starts = (ids.mapNotNull { id -> cellOf(nodes[id].wx, nodes[id].wy).takeIf { it >= 0 } } + mine).distinct()
                    val path = search(starts, TRAIL_MAX_YD) { c -> cellEdge[c]?.let { find(it.a) == main } == true } ?: continue
                    val from = path.first().let { c -> nodeAtCell[c] ?: cellEdge[c]?.let { split(it, c) } } ?: continue
                    val to = cellEdge[path.last()]?.let { split(it, path.last()) } ?: continue
                    emit(from, to, path, TRAIL)
                    joined = true
                    break
                }
                if (!joined) return
            }
        }
    }

    // ---------------------------------------------------------------- the ground's zones (the device's harvest)

    /** the game's areas for the drafts: each area's name and parent (AreaTable) and, through its top zone, its uiMap (UiMapAssignment) */
    class Areas(val rows: Map<Int, PlaceNames.Area>, val uiMapOf: Map<Int, Int>) {
        private val memo = HashMap<Int, Int>()
        /** area [a]'s zone's uiMap: the area itself when it is a zone (no parent) with a map, else up its parents; 0 = none */
        fun uiMap(a: Int): Int = if (a == 0) 0 else synchronized(memo) { memo.getOrPut(a) {
            var cur = a; var guard = 0
            while (guard++ < 6) {
                val row = rows[cur]
                uiMapOf[cur]?.let { m -> if (row == null || row.parent == 0) return@getOrPut m }
                cur = row?.parent?.takeIf { it != 0 } ?: break
            }
            uiMapOf[cur] ?: 0
        } }
        fun toJson(): JSONObject = JSONObject()
            .put("a", JSONObject().also { o -> for ((id, r) in rows) o.put(id.toString(), JSONArray(listOf(r.name, r.parent))) })
            .put("m", JSONObject().also { o -> for ((a, m) in uiMapOf) o.put(a.toString(), m) })
        companion object {
            fun fromJson(o: JSONObject): Areas {
                val a = o.optJSONObject("a") ?: JSONObject(); val m = o.optJSONObject("m") ?: JSONObject()
                val rows = HashMap<Int, PlaceNames.Area>()
                for (k in a.keys()) a.optJSONArray(k)?.let { r -> k.toIntOrNull()?.let { rows[it] = PlaceNames.Area(r.optString(0), r.optInt(1)) } }
                val maps = HashMap<Int, Int>()
                for (k in m.keys()) k.toIntOrNull()?.let { maps[it] = m.optInt(k) }
                return Areas(rows, maps)
            }
        }
    }

    /** a zone's ground by 33 yd chunk: each chunk's area id (the root ADT's MCNK; 0 = none known), over the zone's world rect */
    class Zones(val t: Double, val b: Double, val l: Double, val r: Double, val nx: Int, val ny: Int, val area: IntArray) {
        fun areaAt(wx: Double, wy: Double): Int {
            val i = floor((l - wy) / (l - r) * nx).toInt(); val j = floor((t - wx) / (t - b) * ny).toInt()
            return if (i in 0 until nx && j in 0 until ny) area[j * nx + i] else 0
        }
        fun write(f: File) {
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC); o.writeDouble(t); o.writeDouble(b); o.writeDouble(l); o.writeDouble(r); o.writeInt(nx); o.writeInt(ny)
                for (v in area) o.writeInt(v)
            }
        }
        companion object {
            const val MAGIC = 0x5A4E5332                       // "ZNS2"
            fun read(f: File): Zones? = runCatching {
                DataInputStream(f.inputStream().buffered()).use { i ->
                    if (i.readInt() != MAGIC) return null
                    val t = i.readDouble(); val b = i.readDouble(); val l = i.readDouble(); val r = i.readDouble()
                    val nx = i.readInt(); val ny = i.readInt()
                    Zones(t, b, l, r, nx, ny, IntArray(nx * ny) { i.readInt() })
                }
            }.getOrNull()

            /** over world rect ([t], [b], [l], [r]) by [chunkYd] cells: each cell's area from [areaAt]. THE CELLS ARE THE WORLD'S OWN
             *  CHUNKS (the rect grown out to the chunk lines, counted from the map's corner): a cell set off the chunks read its middle in
             *  the chunk beside it wherever its drift (the rect's own offset, a cell a hair smaller than a chunk) passed half a chunk -
             *  the zones' border moved a chunk, and with the grids padded 450 yd (13.5 chunks) Durotar's Southfury bridge ended a chunk
             *  short of the Barrens: no bridge exit, the Barrens' one reaching 5k sq yd of Durotar */
            fun build(t: Double, b: Double, l: Double, r: Double, chunkYd: Double, areaAt: (Double, Double) -> Int?): Zones {
                val e = 1e-6
                val j0 = floor((ORG - t) / chunkYd + e); val j1 = max(j0 + 1, ceil((ORG - b) / chunkYd - e))
                val i0 = floor((ORG - l) / chunkYd + e); val i1 = max(i0 + 1, ceil((ORG - r) / chunkYd - e))
                val nx = (i1 - i0).toInt(); val ny = (j1 - j0).toInt()
                val t2 = ORG - j0 * chunkYd; val l2 = ORG - i0 * chunkYd
                return Zones(t2, ORG - j1 * chunkYd, l2, ORG - i1 * chunkYd, nx, ny, IntArray(nx * ny) { k ->
                    areaAt(t2 - (k / nx + 0.5) * chunkYd, l2 - (k % nx + 0.5) * chunkYd) ?: 0
                })
            }
            private const val ORG = 32.0 * MapHarvest.ADT_YD
        }
    }
    val zones = RouteWork.Files { map -> Zones.read(ArtHarvest.file("safepath/zones/$map.bin")) }

    /**
     * a zone's WATER (b-safepath-water: no way or node in water but over a bridge or a shallow ford), as the game holds it: per vertex
     * of the climb lattice ([Climb.STEP] yd, the terrain's own outer vertices; [j0] / [i0] its first row / column from the world's
     * corner) the liquid's surface over the ground there, in tenths of a yard (1..252; 0 = dry, 255 = deeper than 25 yd): the surface
     * there the mean of the wet liquid cells meeting at it (a stream falling down its bed: the deepest cell's read its upper neighbour's
     * surface and walled Silverpine's ford at 834,1132), and between vertices the depth runs straight ([at]). [bridge] per cell (row-major,
     * (nx - 1) x (ny - 1)): a walkway's deck over the water (a bridge, a pier).
     * One depth a coarse cell read only the cell on one side of its border, and the lattice's steps run on those borders: Darkshore's
     * route B walked x = 7008.3 up the Cliffspring River reading the 1.0 yd cell east of the line, the water there 5.8 yd deep (the
     * user: "Pathing shouldn't be in water"); measured over a cell's highest corner, it let Silverpine's routes swim a stream's mouth
     * 3.6 yd deep where a ford 8 yd off is shallow
     */
    class Water(val j0: Int, val i0: Int, val nx: Int, val ny: Int, val depth: ByteArray, val bridge: ByteArray) {
        private fun v(j: Int, i: Int): Float {
            if (i !in 0 until nx || j !in 0 until ny) return Float.NaN
            val d = depth[j * nx + i].toInt() and 0xFF
            return when (d) { 0 -> Float.NaN; 255 -> 99f; else -> (d - 1) / 10f }
        }
        /** the depth at world ([wx], [wy]) in yards: its cell's four vertices', straight between them; NaN = dry (or off the grid),
         *  [BRIDGE] = on a bridge's deck over the water */
        fun at(wx: Double, wy: Double): Float {
            val fj = (ORG - wx) / Climb.STEP - j0; val fi = (ORG - wy) / Climb.STEP - i0
            val j = floor(fj).toInt(); val i = floor(fi).toInt()
            if (i !in 0 until nx - 1 || j !in 0 until ny - 1) return Float.NaN
            if (bridge[j * (nx - 1) + i].toInt() != 0) return BRIDGE
            val a = v(j, i); val b2 = v(j, i + 1); val c = v(j + 1, i); val d = v(j + 1, i + 1)
            if (a.isNaN() && b2.isNaN() && c.isNaN() && d.isNaN()) return Float.NaN
            val u = (fi - i).toFloat(); val w = (fj - j).toFloat()
            fun z(x: Float) = if (x.isNaN()) 0f else x
            return (z(a) * (1 - u) + z(b2) * u) * (1 - w) + (z(c) * (1 - u) + z(d) * u) * w
        }
        /** whether a walker may stand at world ([wx], [wy]) in its water: dry, a bridge, a ford ([FORD_DEPTH]), or a narrow stream - no
         *  deeper than [NARROW_DEPTH], a ford or the bank on both sides within [NARROW_YD] across it (along one of four ways), never
         *  deeper between */
        fun wadeable(wx: Double, wy: Double): Boolean {
            val d = at(wx, wy)
            if (d.isNaN() || d == BRIDGE || d <= FORD_DEPTH) return true
            if (d > NARROW_DEPTH) return false
            val r = Math.sqrt(0.5)
            for ((ux, uy) in listOf(1.0 to 0.0, 0.0 to 1.0, r to r, r to -r)) {
                fun shore(sg: Double): Double {
                    var t = 0.5
                    while (t <= NARROW_YD) {
                        val q = at(wx + sg * ux * t, wy + sg * uy * t)
                        if (q.isNaN() || q == BRIDGE || q <= FORD_DEPTH) return t
                        if (q > NARROW_DEPTH) return Double.MAX_VALUE
                        t += 0.5
                    }
                    return Double.MAX_VALUE
                }
                val a = shore(1.0); if (a == Double.MAX_VALUE) continue
                if (a + shore(-1.0) <= NARROW_YD) return true
            }
            return false
        }
        /** a narrow stream's water at world ([wx], [wy]) (deeper than a ford, [wadeable]): crossed, but a drier way preferred */
        fun wading(wx: Double, wy: Double): Boolean { val d = at(wx, wy); return !d.isNaN() && d != BRIDGE && d > FORD_DEPTH && wadeable(wx, wy) }
        fun write(f: File) {
            f.parentFile?.mkdirs()
            DataOutputStream(java.util.zip.DeflaterOutputStream(f.outputStream().buffered())).use { o ->
                o.writeInt(MAGIC); o.writeInt(j0); o.writeInt(i0); o.writeInt(nx); o.writeInt(ny); o.write(depth); o.write(bridge)
            }
        }
        companion object {
            const val MAGIC = 0x57545232                       // "WTR2": per vertex, deflated
            private const val ORG = 32.0 * MapHarvest.ADT_YD
            fun read(f: File): Water? = runCatching {
                DataInputStream(java.util.zip.InflaterInputStream(f.inputStream().buffered())).use { i ->
                    if (i.readInt() != MAGIC) return null
                    val j0 = i.readInt(); val i0 = i.readInt(); val nx = i.readInt(); val ny = i.readInt()
                    Water(j0, i0, nx, ny, ByteArray(nx * ny).also { i.readFully(it) }, ByteArray((nx - 1) * (ny - 1)).also { i.readFully(it) })
                }
            }.getOrNull()
            /** over world rect ([t], [b], [l], [r]), on the lattice's vertices: [depthAt] the water's depth at a spot (NaN dry: [depthOf]),
             *  [bridgeAt] a building's outdoor walkway over it (a bridge, a pier) */
            fun build(t: Double, b: Double, l: Double, r: Double, bridgeAt: (Double, Double) -> Boolean = { _, _ -> false },
                      depthAt: (Double, Double) -> Float): Water {
                val s = Climb.STEP
                val j0 = floor((ORG - t) / s).toInt(); val i0 = floor((ORG - l) / s).toInt()
                val ny = max(2, ceil((ORG - b) / s).toInt() - j0 + 1); val nx = max(2, ceil((ORG - r) / s).toInt() - i0 + 1)
                // a vertex is the corner of four liquid cells: the mean of the wet ones there (read a hair inside each)
                val e = s * 0.01
                val depth = ByteArray(nx * ny) { k ->
                    val x = ORG - (j0 + k / nx) * s; val y = ORG - (i0 + k % nx) * s
                    var sum = 0f; var n = 0
                    for (dx in listOf(-e, e)) for (dy in listOf(-e, e)) { val q = depthAt(x + dx, y + dy); if (!q.isNaN()) { sum += q; n++ } }
                    val d = if (n == 0) Float.NaN else sum / n
                    (if (d.isNaN()) 0 else if (d >= 25.15f) 255 else (1 + (max(0f, d) * 10).roundToInt()).coerceIn(1, 252)).toByte()
                }
                // a bridge where the deck holds the cell's middle or one of its quarters' (a deck narrower than the cell, an M2 bridge's
                // 4 yd footbridge, a pier's edge: its middle alone missed it), over water
                val bridge = ByteArray((nx - 1) * (ny - 1)) { k ->
                    val j = k / (nx - 1); val i = k % (nx - 1)
                    val wet = depth[j * nx + i].toInt() != 0 || depth[j * nx + i + 1].toInt() != 0 || depth[(j + 1) * nx + i].toInt() != 0 || depth[(j + 1) * nx + i + 1].toInt() != 0
                    val x = ORG - (j0 + j + 0.5) * s; val y = ORG - (i0 + i + 0.5) * s
                    (if (wet && (bridgeAt(x, y) || listOf(-1.0, 1.0).any { dj -> listOf(-1.0, 1.0).any { di -> bridgeAt(x + dj * s / 4, y + di * s / 4) } })) 1 else 0).toByte()
                }
                return Water(j0, i0, nx, ny, depth, bridge)
            }
            const val BRIDGE = -1f
            /** the water's depth at world ([wx], [wy]) of continent tiles: the surface of the liquid cell there over the ground's height
             *  at the spot itself (straight between the terrain's outer vertices). [surfaceOf] a tile's water grid (Terrain.water: per
             *  liquid cell, the instance's own heights where it keeps them), [heightOf] its ground (Terrain.Tile.h); NaN = dry */
            fun depthOf(wx: Double, wy: Double, surfaceOf: (Int, Int) -> FloatArray?, heightOf: (Int, Int) -> FloatArray?): Float {
                val gx = (ORG - wy) / MapHarvest.ADT_YD; val gy = (ORG - wx) / MapHarvest.ADT_YD
                val tc = floor(gx).toInt(); val tr = floor(gy).toInt()
                if (tc !in 0..63 || tr !in 0..63) return Float.NaN
                val w = surfaceOf(tc, tr) ?: return Float.NaN
                val fx = ((gx - tc) * 128).coerceIn(0.0, 127.999); val fy = ((gy - tr) * 128).coerceIn(0.0, 127.999)
                val cx = fx.toInt(); val cy = fy.toInt()
                val top = w[cy * 128 + cx]
                if (top.isNaN()) return Float.NaN
                // the ground at the spot: its cell's four outer vertices (the 129th row / column is the next tile's first)
                fun h(row: Int, col: Int): Float {
                    val t2 = tc + col / 128; val r2 = tr + row / 128
                    if (t2 !in 0..63 || r2 !in 0..63) return Float.NaN
                    val hs = heightOf(t2, r2) ?: return Float.NaN
                    return hs[(row % 128) * 128 + (col % 128)]
                }
                val a = h(cy, cx); if (a.isNaN()) return 10f
                fun or(x: Float) = if (x.isNaN()) a else x
                val b1 = or(h(cy, cx + 1)); val c = or(h(cy + 1, cx)); val d = or(h(cy + 1, cx + 1))
                val u = (fx - cx).toFloat(); val v = (fy - cy).toFloat()
                val g = (a * (1 - u) + b1 * u) * (1 - v) + (c * (1 - u) + d * u) * v
                return max(0f, top - g)
            }
        }
    }
    const val WATER_YD = Climb.STEP         // the water's vertices: the lattice's own (4.2 yd, the liquid's own cell)
    val waters = RouteWork.Files { map -> Water.read(ArtHarvest.file("safepath/water/$map.bin")) }
    const val AREAS_FILE = "safepath/areas.json"
    @Volatile private var areasRead: Areas? = null
    /** the areas table (read once, off the UI thread) */
    fun areas(): Areas? = areasRead ?: runCatching { Areas.fromJson(JSONObject(ArtHarvest.file(AREAS_FILE).readText())) }.getOrNull()?.also { areasRead = it }

    /**
     * "safepathzones", in the background: each zone's ground by chunk (the root ADTs' MCNK area ids, AreaTable's parents, the
     * UiMapAssignment table the "areas" step wrote) - where its borders and its neighbours are, the exits' ground truth. Reads the
     * roots again (the terrain step keeps no areas); waits while the player walks, as the road textures do.
     */
    val ZONES_STEP = object : ArtHarvest.Step {
        override val id = "safepathzones"
        override val version = 8                       // v4: decks; v5: M2 bridges (by model name) as decks; v6: the water per vertex (WTR2); v7: lava and slime a wall; v8: name-less flat decks, a deck only over its water
        override val beforePlay = false
        override val weight = 40
        override fun run(job: ArtHarvest.Job) {
            val g = job.game
            val rects = MapHarvestSteps.zoneRects(job).filterValues { MapHarvest.tilesUnder(it) != null }
            val areas = g.db2(PlaceNames.DB_AREA_TABLE)?.rows(strings = setOf(1))?.associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
                ?: error("AreaTable unreadable: ${g.lastReadWhy}")
            // the liquids no one wades (magma, slime): a wall at any depth
            val lethal = g.db2(Terrain.DB_LIQUID_TYPE)?.rows(strings = setOf(0))?.associate { it.id to it.str(0) }?.let { Terrain.lethalLiquids(it) }
                ?: Terrain.lethal.also { android.util.Log.w("WCMAP", "safepath: LiquidType unreadable (${g.lastReadWhy}) - no liquid read as lethal") }
            Terrain.lethal = lethal
            val uimap = g.db2(DangerStore.DB_UIMAP_ASSIGNMENT)?.rows()?.let { rows ->
                val o = HashMap<Int, Int>(); for (r in rows) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m }; o
            } ?: error("UiMapAssignment unreadable: ${g.lastReadWhy}")
            val mapRows = g.db2(MapHarvest.DB_MAP)?.rows()?.associateBy { it.id } ?: error("Map.db2 unreadable: ${g.lastReadWhy}")
            val roots = HashMap<Int, Map<Int, Int>>()
            val tiles = object : LinkedHashMap<Long, Map<Int, Int>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Map<Int, Int>>?) = size > 24
            }
            // each root's water and ground, kept while near (the water grid reads them cell by cell)
            class Wet(val surface: FloatArray?, val ground: FloatArray?, val walks: List<(Double, Double) -> Boolean>)
            val objs = HashMap<Int, Map<Int, Int>>()
            val wmoGroups = HashMap<Int, List<WorldBuildings.Group>>()
            val m2Floors = HashMap<Int, FloatArray?>()            // each doodad model read once: its floor when it is a bridge, else null
            val wets = object : LinkedHashMap<Long, Wet>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Wet>?) = size > 24
            }
            var built = 0
            for ((i, e) in rects.entries.withIndex()) {
                val (map, rect) = e.key to e.value
                val root = roots.getOrPut(rect.c) { mapRows[rect.c]?.let { g.read(it.int(MapHarvest.MAP_WDT)) }?.let { Terrain.maidRoot(it) } ?: emptyMap() }
                val z = Zones.build(rect.t, rect.b, rect.l, rect.r, PlaceNames.CHUNK_YD) { wx, wy ->
                    val (tc, tr, ch) = PlaceNames.locate(wx, wy).let { Triple(it[0], it[1], it[2]) }
                    if (tc !in 0..63 || tr !in 0..63) null else {
                        val key = (rect.c.toLong() shl 32) or MapHarvest.key(tc, tr).toLong()
                        val chunks = tiles[key] ?: run {
                            var waited = 0
                            while (IndoorSchedulerStep.playerMoving() && waited < 600) { Thread.sleep(1000); waited++ }
                            (root[MapHarvest.key(tc, tr)]?.let { g.read(it) }?.let { PlaceNames.chunkAreas(it) } ?: emptyMap()).also { tiles[key] = it }
                        }
                        chunks[ch]
                    }
                }
                z.write(job.file("safepath/zones/$map.bin"))
                zones.drop(map)
                fun wet(tc: Int, tr: Int): Wet {
                    val key = (rect.c.toLong() shl 32) or MapHarvest.key(tc, tr).toLong()
                    return wets[key] ?: run {
                        val b = root[MapHarvest.key(tc, tr)]?.let { g.read(it) } ?: return@run Wet(null, null, emptyList())
                        val surface = Terrain.water(b, lethal)
                        // the outdoor walkways over the water (a bridge, a pier): only where the tile has water at all
                        // each walkway by its DECK (its outdoor groups' own floor triangles), the box only where the groups can't be read
                        val walks: List<(Double, Double) -> Boolean> = if (surface == null) emptyList() else {
                            val obj = objs.getOrPut(rect.c) { mapRows[rect.c]?.let { g.read(it.int(MapHarvest.MAP_WDT)) }?.let { Terrain.maidObj0(it) } ?: emptyMap() }
                            val ob = obj[MapHarvest.key(tc, tr)]?.let { g.read(it) }
                            ob?.let { WorldBuildings.placements(it, tilted = true) }?.flatMap { p ->
                                val root = g.read(p.fid)
                                val feet = WorldBuildings.walkways(p, wmoGroups.getOrPut(p.fid) { root?.let { WorldBuildings.groups(it) } ?: emptyList() })
                                if (feet.isEmpty()) emptyList()
                                else root?.let { WorldBuildings.deck(p, it) { id -> g.read(id) } }?.takeIf { !it.empty }?.let { d -> listOf<(Double, Double) -> Boolean>({ x, y -> d.holds(x, y) && d.over(x, y, Terrain.surfaceAt(surface, tc, tr, x, y)) }) }
                                    ?: feet.map { f -> { x: Double, y: Double -> f.holds(x, y) } }
                            }.orEmpty() + ob?.let { o ->
                                // and the M2 bridges (Duskwood's covered bridge into Westfall), by their models' own names
                                WorldBuildings.m2Decks(o, { x, y -> Terrain.wetNear(surface, tc, tr, x, y, WorldBuildings.M2_NEAR_WATER_YD) }) { fid ->
                                    if (fid in m2Floors) m2Floors[fid] else g.read(fid)?.let { WorldBuildings.m2BridgeFloor(it) }.also { m2Floors[fid] = it } }
                                    .map { d -> { x: Double, y: Double -> d.holds(x, y) && d.over(x, y, Terrain.surfaceAt(surface, tc, tr, x, y)) } }
                            }.orEmpty()
                        }
                        Wet(surface, Terrain.tile(b)?.h, walks)
                    }.also { wets[key] = it }
                }
                fun tileOf(wx: Double, wy: Double) = PlaceNames.locate(wx, wy).let { it[0] to it[1] }
                Water.build(rect.t, rect.b, rect.l, rect.r, { wx, wy -> tileOf(wx, wy).let { (c, r) -> wet(c, r).walks.any { it(wx, wy) } } }) { wx, wy ->
                    Water.depthOf(wx, wy, { c, r -> wet(c, r).surface }, { c, r -> wet(c, r).ground }) }
                    .write(job.file("safepath/water/$map.bin"))
                waters.drop(map)
                built++
                job.progress(i + 1, rects.size, "")
            }
            job.writeBytes(AREAS_FILE, Areas(areas, uimap).toJson().toString().toByteArray())
            areasRead = null
            android.util.Log.i("WCMAP", "safepath: the ground's zones for $built maps")
        }
    }

    // ---------------------------------------------------------------- the draft on the device

    /** a zone's draft, as last made (null: none yet) */
    val drafts = RouteWork.Files { map -> runCatching { Net.fromJson(JSONObject(ArtHarvest.file("safepath/$map.draft.json").readText())) }.getOrNull() }
    private val tried = HashMap<Int, String>()

    /**
     * Draft zone [map] (continent [cont], world rect [t] / [b] / [l] / [r]) on the route thread when its inputs are there and changed
     * since the last draft (the learned roads above all): the player's faction [mine] decides which creatures are its towns' and which
     * its danger. Cheap to call often (the map's draw does).
     */
    fun refresh(map: Int, cont: Int, t: Double, b: Double, l: Double, r: Double, mine: Int,
                onMap: ((Int, Double, Double) -> Boolean)? = null) {
        if (!RouteWork.onMain()) return
        val stamp = "${Roads.version}:${Roads.files.version}:${Terrain.files.version}:${Climb.files.version}:${zones.version}:${DangerStore.version}:$mine"
        if (tried[map] == stamp) return
        tried[map] = stamp
        RouteWork.run({
            Roads.of(map) ?: return@run "no road textures yet"
            val roadCells = Roads.roadCells(map)
            if (roadCells == 0 && map !in CityExits.CITIES) return@run "no learned roads yet"
            val tg = Terrain.of(map) ?: return@run "no terrain yet"
            val zg = zones.get(map) ?: return@run "no zone ground yet"
            val ar = areas() ?: return@run "no areas table yet"
            // the lattice (the terrain step's, when it has run): the exits checked on the ground, as the harness does
            val cg = Climb.files.get(map)
            val built = "roads $roadCells, ${zg.nx}x${zg.ny} chunks, water ${waters.get(map) != null} v${ZONES_STEP.version}, ground ${cg != null}, v3"
            val old = drafts.get(map)
            if (old != null && old.built == built) return@run null
            val names = HashMap<Int, String>()
            fun nameOf(m: Int) = names.getOrPut(m) {
                runCatching { JSONObject(File(ArtHarvest.dir("worldmap"), "$m/index.json").readText()).optString("name", "") }.getOrDefault("")
            }
            val fixed = fixedPoints(map, cont, t, b, l, r, mine)
            val spawns = DangerStore.spawns(map)
            val ax = l - r; val ay = t - b
            val hostile = spawns.filter { it.hostile == mine || it.hostile == 3 }.map { doubleArrayOf(t - it.y * ay, l - it.x * ax) }
            val friendly = spawns.filter { it.hostile != mine && it.hostile != 3 }.map { Npc(t - it.y * ay, l - it.x * ax, it.sub) }
            val artPx = artPixels(map)
            val ground = if (map in CityExits.CITIES) artPx?.let { (px, w, h) -> artGround(px, w, h, t, b, l, r) } else null
            val painted = artPx?.let { (px, w, h) -> paintedRoads(px, w, h, t, b, l, r) }
            val water = waters.get(map)
            val net = draft(Input(map, cont, t, b, l, r, { wx, wy -> Roads.isRoad(map, wx, wy) },
                { wx, wy -> Terrain.factor(tg.at(wx, wy)) }, { wx, wy -> ar.uiMap(zg.areaAt(wx, wy)) },
                fixed + hubs(friendly, { x, y -> PlaceNames.nameAt(x, y, ar.rows) { a, b2 -> zg.areaAt(a, b2) } }, nameOf(map),
                    districtFor(map, t, b, l, r)), ::nameOf,
                dangerOf(hostile), ground = ground, painted = painted, water = water?.let { wg -> { x, y -> wg.at(x, y) } }), built)
            // the crossings checked from both sides, against the neighbours' drafts there are (the user: "a low danger path back")
            // THE EXITS ON THE GROUND (the zone workers' false exits: Loch Modan's ridge over the South Gate tunnels, Stranglethorn's on
            // the Dagger Hills slope): with the terrain step's lattice, a walk exit whose crossing is not walkable goes, and the ground's
            // own crossings (no road to them: the Badlands from Loch Modan) are added - the same code the harness runs
            val grounded = if (cg == null) net else {
                val gr = Ground(cg, map, { x, y -> ar.uiMap(zg.areaAt(x, y)) }, { x, y -> water?.at(x, y)?.let { d ->
                    d.isNaN() || d == Water.BRIDGE || d <= FORD_DEPTH } ?: true })
                groundExits(checkExits(net, gr), gr, ::nameOf, onMap = onMap)
            }
            val neighbours = grounded.exits.map { it.toMap }.distinct().mapNotNull { m -> drafts.get(m) }
            val checked = checkCrossings(grounded, neighbours)
            ArtHarvest.file("safepath/$map.draft.json").also { it.parentFile?.mkdirs() }.writeText(checked.toJson().toString())
            drafts.drop(map); nets.drop(map)
            "drafted: ${checked.nodes.size} nodes, ${checked.edges.size} edges (${checked.edges.count { it.kind == TRAIL }} trails), " +
                "${checked.exits.size} exits (${checked.exits.joinToString { it.name.ifEmpty { it.toMap.toString() } }}; " +
                "${net.exits.size - checked.exits.size} dropped, checked against ${neighbours.size} neighbours)"
        }) { said -> android.util.Log.i("WCMAP", "safepath $map: $said") }
    }

    /** capital [map]'s districts by world spot (its world rect [t] / [b] / [l] / [r]), or null for any other map */
    fun districtFor(map: Int, t: Double, b: Double, l: Double, r: Double): ((Double, Double) -> String?)? =
        if (map !in DISTRICTS) null else { x, y -> district(map, (l - y) / (l - r), (t - x) / (t - b)) }

    /** the zone's travel points from the transports file: the flight masters of the player's side, the boats' and zeppelins' docks */
    fun fixedPoints(map: Int, cont: Int, t: Double, b: Double, l: Double, r: Double, mine: Int, o: JSONObject? = runCatching {
        JSONObject(ArtHarvest.file("transports.json").readText()) }.getOrNull()): List<Fixed> {
        if (o == null) return emptyList()
        fun inRect(x: Double, y: Double) = x <= t && x >= b && y <= l && y >= r
        val out = ArrayList<Fixed>()
        for (fm in TransportRoutes.mastersFromJson(o)) {
            if (fm.cont != cont || !inRect(fm.x.toDouble(), fm.y.toDouble())) continue
            // the game's TaxiNodes flags: 1 the Alliance's, 2 the Horde's (Stormwind's 1025)
            if (!fm.real || (mine != 0 && (fm.flags and (if (mine == TransportRoutes.HORDE) 2 else 1)) == 0)) continue
            out.add(Fixed(fm.x.toDouble(), fm.y.toDouble(), FLIGHT, fm.name.ifEmpty { "Flight master" }))
        }
        // the docks: a tower's (or a pier's) rides together, one stop named for all of them ("Zeppelin tower: Grom'gol, Undercity")
        class D(val x: Double, val y: Double, val air: Boolean, val to: ArrayList<String>)
        val docks = ArrayList<D>()
        for (ride in TransportRoutes.fromJson(o)) {
            for (end in 0..1) {
                val c = if (end == 0) ride.ca else ride.cb
                val p = if (end == 0) ride.a else ride.b
                val x = p[0].toDouble(); val y = p[1].toDouble()
                if (c != cont || !inRect(x, y)) continue
                val other = (if (end == 0) ride.nb else ride.na) ?: continue
                val d = docks.firstOrNull { it.air == ride.air && hypot(it.x - x, it.y - y) < DOCK_SAME_YD } ?: D(x, y, ride.air, ArrayList()).also { docks.add(it) }
                if (other !in d.to) d.to.add(other)
            }
        }
        for (d in docks) out.add(Fixed(d.x, d.y, DOCK, (if (d.air) "Zeppelin tower: " else "Dock: ") + d.to.joinToString(", "), 0, if (d.air) "zeppelin" else "boat"))
        return out
    }

    /** one of the player's side's creatures: where it stands, its title ("Banker", "Warrior Trainer"; QuestieDB's subName) */
    class Npc(val wx: Double, val wy: Double, val sub: String = "")

    /**
     * the towns: where this many of the player's side's creatures stand this near each other (QuestieDB's spawns), named by the
     * ground's own subzone ([nameAt]); where that is only the zone's own name ([generic]: a capital's districts are no areas in the
     * game's tables) by what its creatures are ([roleName]: "Bank, Auction House", "Warrior trainers")
     */
    fun hubs(friendly: List<Npc>, nameAt: (Double, Double) -> String? = { _, _ -> null }, generic: String? = null,
             districtAt: ((Double, Double) -> String?)? = null): List<Fixed> {
        val left = friendly.toMutableList()
        val out = ArrayList<Fixed>(); val roles = ArrayList<String?>()
        while (left.isNotEmpty()) {
            // the densest spot first
            val best = left.maxByOrNull { p -> left.count { hypot(it.wx - p.wx, it.wy - p.wy) < HUB_YD } } ?: break
            val group = left.filter { hypot(it.wx - best.wx, it.wy - best.wy) < HUB_YD }
            if (group.size < HUB_MIN) break
            val x = group.map { it.wx }.average(); val y = group.map { it.wy }.average()
            val role = roleName(group)
            val name = districtAt?.invoke(x, y)?.let { d -> if (role != null) "$d · $role" else d }
                ?: nameAt(x, y)?.takeIf { it.isNotEmpty() && it != generic } ?: role ?: generic ?: "Town"
            out.add(Fixed(x, y, HUB, name))
            roles.add(role)
            left.removeAll { hypot(it.wx - best.wx, it.wy - best.wy) < HUB_YD * 2 }
        }
        // towns sharing a name (Tirisfal: three "Deathknell"s): each told apart by what its creatures are, then numbered north to south
        for ((nm, idx) in out.indices.groupBy { out[it].name }) {
            if (idx.size < 2) continue
            val named = idx.associateWith { k -> roles[k]?.takeIf { r -> !nm.contains(r) }?.let { "$nm · $it" } ?: nm }
            for ((nm2, same) in idx.groupBy { named[it]!! }) {
                val sorted = same.sortedByDescending { out[it].wx }
                for ((n, k) in sorted.withIndex()) out[k] = Fixed(out[k].wx, out[k].wy, HUB, if (sorted.size > 1) "$nm2 (${n + 1})" else nm2)
            }
        }
        return out
    }
    private val ROLES = listOf("Banker" to "Bank", "Auctioneer" to "Auction House", "Innkeeper" to "Inn", "Wind Rider Master" to "Wind riders",
        "Gryphon Master" to "Gryphons", "Battlemaster" to "Battlemasters", "Stable Master" to "Stables", "Zeppelin Master" to "Zeppelins")
    /** a town by what its creatures are: its services (the bank, the auction house, the inn), else its most common trainers */
    fun roleName(group: List<Npc>): String? {
        val subs = group.map { it.sub }.filter { it.isNotEmpty() }
        val roles = ROLES.filter { (k, _) -> subs.any { it.contains(k, ignoreCase = true) } }.map { it.second }
        if (roles.isNotEmpty()) return roles.take(2).joinToString(", ")
        val trainer = subs.filter { it.endsWith("Trainer", ignoreCase = true) }.map { it.removeSuffix("Trainer").removeSuffix("trainer").trim() }
            .filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return trainer?.let { "$it trainers" }
    }

    /**
     * A CAPITAL'S STREETS FROM ITS OWN MAP ART (the user on the first Orgrimmar draft: "lay the trails on its streets, not through
     * buildings or the Cleft of Shadow"): its ground reads as plain dirt and its walls are one city building the route counts as level,
     * so the map's own painting tells them apart - its buildings red / brown (and the orange round roofs), the Cleft's pink, the pools'
     * green-blue, against the golden streets. [px] the art (w x [h], over the zone's world rect [t] / [b] / [l] / [r]): a ground factor
     * at a spot - [Terrain.WALL] where most of a cell is painted building, else dearer the more of it is.
     */
    fun artGround(px: IntArray, w: Int, h: Int, t: Double, b: Double, l: Double, r: Double): (Double, Double) -> Float {
        // a summed table of the blocked pixels: any window's count at once
        val sum = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0; for (x in 0 until w) { if (blockedPaint(px[y * w + x])) row++; sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row } }
        val hw = max(1, (CELL_YD / (l - r) * w / 2).roundToInt()); val hh = max(1, (CELL_YD / (t - b) * h / 2).roundToInt())
        return { wx, wy ->
            val x = ((l - wy) / (l - r) * w).toInt(); val y = ((t - wx) / (t - b) * h).toInt()
            val x0 = (x - hw).coerceIn(0, w); val x1 = (x + hw).coerceIn(0, w); val y0 = (y - hh).coerceIn(0, h); val y1 = (y + hh).coerceIn(0, h)
            val area = (x1 - x0) * (y1 - y0)
            if (area <= 0) 1f else {
                val k = sum[y1 * (w + 1) + x1] - sum[y0 * (w + 1) + x1] - sum[y1 * (w + 1) + x0] + sum[y0 * (w + 1) + x0]
                val f = k.toFloat() / area
                if (f > 0.55f) Terrain.WALL else 1f + 4f * f
            }
        }
    }
    /** a pixel of painted building, the Cleft or water (an ARGB colour), by its hue, saturation and brightness */
    fun blockedPaint(c: Int): Boolean {
        if ((c ushr 24) < 128) return false
        val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val bl = (c and 0xFF) / 255f
        val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl)); val d = mx - mn
        val s = if (mx <= 0f) 0f else d / mx; val v = mx
        if (d <= 0f) return false
        var hue = when (mx) { r -> 60f * (((g - bl) / d) % 6f); g -> 60f * ((bl - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        if (hue < 0f) hue += 360f
        return ((hue < 22f || hue > 330f) && s > 0.25f) ||          // the red / brown buildings, the Cleft's pink
            (hue < 29f && s > 0.95f && v > 0.78f) ||                 // the orange round roofs
            (hue in 60f..200f && s > 0.15f)                          // the pools
    }

    /**
     * THE MAP'S PAINTED ROADS (the user on test9: "its pretty clearly shown on the map where the safe walking trails are all at"): the
     * zone's own painting draws its roads as a pale golden band (hue about 40, its middle 0.6 - 0.8 saturated, 0.62 - 0.82 bright)
     * between two dark outlines (darker than 0.53, more than 0.86 saturated) - the mountains' pale tops have their stroke on one side
     * only. [px] the art (w x [h] over the zone's world rect [t] / [b] / [l] / [r]): true where a [CELL_YD] cell holds such a pixel.
     */
    fun paintedRoads(px: IntArray, w: Int, h: Int, t: Double, b: Double, l: Double, r: Double): (Double, Double) -> Boolean {
        val hue = FloatArray(w * h); val sat = FloatArray(w * h); val bri = FloatArray(w * h)
        for (k in px.indices) { val c = px[k]; hsv(c).let { hue[k] = it[0]; sat[k] = it[1]; bri[k] = it[2] } }
        fun core(k: Int) = hue[k] in 38.5f..44.5f && sat[k] in 0.6f..0.8f && bri[k] in 0.62f..0.82f
        fun edge(k: Int) = bri[k] < 0.53f && sat[k] > 0.86f
        val R = 5
        val road = BooleanArray(w * h)
        for (y in R until h - R) for (x in R until w - R) {
            val k = y * w + x
            if (!core(k)) continue
            val lr = (1..R).any { edge(k - it) } && (1..R).any { edge(k + it) }
            val ud = (1..R).any { edge(k - it * w) } && (1..R).any { edge(k + it * w) }
            if (lr || ud) road[k] = true
        }
        val hw = max(1, (CELL_YD / (l - r) * w / 2).roundToInt()); val hh = max(1, (CELL_YD / (t - b) * h / 2).roundToInt())
        return { wx, wy ->
            val x = ((l - wy) / (l - r) * w).toInt(); val y = ((t - wx) / (t - b) * h).toInt()
            var hit = false
            for (yy in max(0, y - hh)..min(h - 1, y + hh)) { for (xx in max(0, x - hw)..min(w - 1, x + hw)) if (road[yy * w + xx]) { hit = true; break }; if (hit) break }
            hit
        }
    }
    private fun hsv(c: Int): FloatArray {
        val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val bl = (c and 0xFF) / 255f
        val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl)); val d = mx - mn
        val s = if (mx <= 0f) 0f else d / mx
        if (d <= 0f) return floatArrayOf(0f, s, mx)
        var hue = when (mx) { r -> 60f * (((g - bl) / d) % 6f); g -> 60f * ((bl - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        if (hue < 0f) hue += 360f
        return floatArrayOf(hue, s, mx)
    }

    /** a map's whole painting (its base and every overlay) as ARGB pixels at its own size, or null */
    fun artPixels(map: Int): Triple<IntArray, Int, Int>? = runCatching {
        val dir = File(ArtHarvest.dir("worldmap"), "$map")
        val idx = JSONObject(File(dir, "index.json").readText())
        val base = android.graphics.BitmapFactory.decodeFile(File(dir, "base.webp").path) ?: return null
        val bm = base.copy(android.graphics.Bitmap.Config.ARGB_8888, true); base.recycle()
        val c = android.graphics.Canvas(bm)
        val ovs = idx.optJSONArray("overlays") ?: JSONArray()
        val sx = bm.width / idx.optDouble("w", 1002.0).toFloat(); val sy = bm.height / idx.optDouble("h", 668.0).toFloat()
        for (i in 0 until ovs.length()) {
            val o = ovs.optJSONObject(i) ?: continue
            val ob = android.graphics.BitmapFactory.decodeFile(File(dir, "ov_${o.optInt("id")}.webp").path) ?: continue
            c.drawBitmap(ob, null, android.graphics.RectF(o.optInt("x") * sx, o.optInt("y") * sy, (o.optInt("x") + o.optInt("w")) * sx,
                (o.optInt("y") + o.optInt("h")) * sy), null)
            ob.recycle()
        }
        val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        Triple(px, bm.width, bm.height).also { bm.recycle() }
    }.getOrNull()

    /** danger 0..1 at a spot: how many hostile creatures stand within [DANGER_YD] (three or more: the most) */
    fun dangerOf(hostile: List<DoubleArray>): (Double, Double) -> Float {
        val cell = DANGER_YD
        val grid = HashMap<Long, MutableList<DoubleArray>>()
        fun key(x: Double, y: Double) = (floor(x / cell).toLong() shl 32) xor (floor(y / cell).toLong() and 0xffffffffL)
        for (p in hostile) grid.getOrPut(key(p[0], p[1])) { ArrayList() }.add(p)
        return { x, y ->
            var c = 0
            for (dx in -1..1) for (dy in -1..1) grid[key(x + dx * cell, y + dy * cell)]?.forEach { if (hypot(it[0] - x, it[1] - y) < DANGER_YD) c++ }
            min(1f, c / 3f)
        }
    }

    // ---------------------------------------------------------------- phase (b): the review's corrections

    /**
     * THE REVIEW'S WORKING COPY (b-safepath-review; the user's pick "Auto-draft, then review"): a zone's network as the user corrects
     * it - an edge approved, avoided, removed (kept, marked, so a later draft cannot bring it back) or made a road / a trail; a node
     * moved, its edges removed, made an exit into a neighbour; a new way drawn node by node, or the player's own last walk added.
     * Every change can be undone. [net] is what is saved (safepath/<map>.json): the draft is never written over it.
     */
    class Edit(from: Net) {
        val map = from.map; val cont = from.cont; val built = from.built
        var reviewed = from.reviewed
        val nodes = from.nodes.toMutableList()
        val edges = from.edges.toMutableList()
        val exits = from.exits.toMutableList()
        private class Snap(val nodes: List<Node>, val edges: List<Edge>, val exits: List<Exit>, val reviewed: Boolean)
        private val undo = ArrayList<Snap>()
        val canUndo get() = undo.isNotEmpty()
        private fun snap() { undo.add(Snap(nodes.toList(), edges.toList(), exits.toList(), reviewed)); if (undo.size > 200) undo.removeAt(0) }
        fun undo(): Boolean {
            val s = undo.removeLastOrNull() ?: return false
            nodes.clear(); nodes.addAll(s.nodes); edges.clear(); edges.addAll(s.edges); exits.clear(); exits.addAll(s.exits); reviewed = s.reviewed
            return true
        }
        fun node(id: Int) = nodes.firstOrNull { it.id == id }
        private fun nextId() = (nodes.maxOfOrNull { it.id } ?: -1) + 1
        private fun with(e: Edge, state: String = e.state, kind: String = e.kind) = Edge(e.a, e.b, kind, state, "user", e.danger, e.climb)

        fun setState(i: Int, state: String) { val e = edges.getOrNull(i) ?: return; if (e.state == state) return; snap(); edges[i] = with(e, state = state) }
        fun setKind(i: Int, kind: String) { val e = edges.getOrNull(i) ?: return; if (e.kind == kind) return; snap(); edges[i] = with(e, kind = kind) }
        /** every edge still a draft approved as it is */
        fun approveAll() { if (edges.none { it.state == DRAFT }) return; snap(); for (i in edges.indices) if (edges[i].state == DRAFT) edges[i] = with(edges[i], state = OK) }
        /** node [id]'s edges all to [state] */
        fun setNodeEdges(id: Int, state: String) {
            if (edges.none { (it.a == id || it.b == id) && it.state != state }) return
            snap(); for (i in edges.indices) if (edges[i].a == id || edges[i].b == id) edges[i] = with(edges[i], state = state)
        }
        fun move(id: Int, wx: Double, wy: Double) {
            val i = nodes.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
            snap(); val n = nodes[i]; nodes[i] = Node(n.id, wx, wy, n.kind, "user", n.name)
        }
        /** a new node at ([wx], [wy]) joined to [from] (when given) by a user's [kind] edge, approved; its id */
        fun draw(from: Int?, wx: Double, wy: Double, kind: String = TRAIL): Int {
            snap()
            val id = nextId()
            nodes.add(Node(id, wx, wy, POINT, "user"))
            if (from != null && node(from) != null) edges.add(Edge(from, id, kind, OK, "user"))
            return id
        }
        /** an approved edge between two nodes (an existing one approved, none twice) */
        fun join(a: Int, b: Int, kind: String = TRAIL) {
            if (a == b || node(a) == null || node(b) == null) return
            val i = edges.indexOfFirst { (it.a == a && it.b == b) || (it.a == b && it.b == a) }
            snap()
            if (i >= 0) edges[i] = with(edges[i], state = OK) else edges.add(Edge(a, b, kind, OK, "user"))
        }
        /** node [id] an exit into [toMap] (named [name], by [how]), or no longer one ([toMap] 0) */
        fun exit(id: Int, toMap: Int, name: String, how: String = "walk") {
            snap()
            exits.removeAll { it.node == id }
            if (toMap != 0) {
                exits.add(Exit(id, toMap, how, name))
                val i = nodes.indexOfFirst { it.id == id }
                if (i >= 0 && (nodes[i].kind == POINT || nodes[i].kind == JUNCTION)) nodes[i].let { nodes[i] = Node(it.id, it.wx, it.wy, EXIT, "user", it.name) }
            }
        }
        fun markReviewed(v: Boolean) { if (reviewed == v) return; snap(); reviewed = v }
        /**
         * the player's own walk ([pts]: world x, y pairs in order) as an approved road: its bends kept, its ends on the nearest node
         * within [WALK_JOIN_YD] (else new points); false when it is too short to be a way
         */
        fun addWalk(pts: List<DoubleArray>): Boolean {
            if (pts.size < 2) return false
            val keep = simplify(pts, SIMPLIFY_YD)
            if (keep.size < 2 || hypot(keep.first()[0] - keep.last()[0], keep.first()[1] - keep.last()[1]) < 30.0) return false
            snap()
            fun near(p: DoubleArray) = nodes.filter { hypot(it.wx - p[0], it.wy - p[1]) < WALK_JOIN_YD }.minByOrNull { hypot(it.wx - p[0], it.wy - p[1]) }?.id
            var prev = near(keep.first()) ?: nextId().also { nodes.add(Node(it, keep.first()[0], keep.first()[1], POINT, "user")) }
            for ((k, p) in keep.withIndex()) {
                if (k == 0) continue
                val id = (if (k == keep.size - 1) near(p) else null) ?: nextId().also { nodes.add(Node(it, p[0], p[1], POINT, "user")) }
                if (id != prev) edges.add(Edge(prev, id, ROAD, OK, "user"))
                prev = id
            }
            return true
        }
        fun net(): Net = Net(map, cont, built, nodes.toList(), edges.toList(), exits.toList(), reviewed)
    }
    const val WALK_JOIN_YD = 60.0          // a walk's end this near a node joins it

    /** Douglas-Peucker over world points: the ones where the line bends more than [tol] yards */
    fun simplify(pts: List<DoubleArray>, tol: Double): List<DoubleArray> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        fun dp(s: Int, e: Int) {
            if (e <= s + 1) return
            val a = pts[s]; val b = pts[e]; val len = hypot(b[0] - a[0], b[1] - a[1])
            var worst = -1; var wd = 0.0
            for (t in s + 1 until e) {
                val p = pts[t]
                val d = if (len < 1e-6) hypot(p[0] - a[0], p[1] - a[1]) else abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / len
                if (d > wd) { wd = d; worst = t }
            }
            if (wd > tol) { keep[worst] = true; dp(s, worst); dp(worst, e) }
        }
        dp(0, pts.size - 1)
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** a zone's reviewed network (the user's corrections), else its draft; null = neither yet (off the UI thread) */
    fun load(map: Int): Net? = runCatching { Net.fromJson(JSONObject(ArtHarvest.file("safepath/$map.json").readText())) }.getOrNull()
        ?: runCatching { Net.fromJson(JSONObject(ArtHarvest.file("safepath/$map.draft.json").readText())) }.getOrNull()
    /** the user has corrected zone [map] (its own file) */
    fun hasReview(map: Int) = ArtHarvest.file("safepath/$map.json").isFile
    fun save(net: Net) { ArtHarvest.file("safepath/${net.map}.json").also { it.parentFile?.mkdirs() }.writeText(net.toJson().toString()); nets.drop(net.map) }
    /** drop the corrections: the draft again */
    fun forget(map: Int) { ArtHarvest.file("safepath/$map.json").delete(); nets.drop(map) }
    /** the zones with a draft or a review */
    fun zonesDrafted(): List<Int> = (ArtHarvest.file("safepath").listFiles() ?: emptyArray()).mapNotNull { f ->
        f.name.removeSuffix(".draft.json").removeSuffix(".json").toIntOrNull()
    }.distinct().sorted()

    // ---- the player's walk, for "Add my last walk": the positions as they come (8 yd apart), a run broken by a pause or a map change
    private class Step(val map: Int, val wx: Double, val wy: Double, val at: Long)
    private val walk = ArrayDeque<Step>()
    @Volatile var lastMap = 0; private set
    /** a position on map [map] (the map document's): kept when it moved [WALK_STEP_YD] or more */
    @Synchronized fun noteWalk(map: Int, wx: Double, wy: Double, nowMs: Long) {
        if (wx.isNaN() || wy.isNaN() || map == 0) return
        lastMap = map
        val last = walk.lastOrNull()
        if (last != null && last.map == map && hypot(last.wx - wx, last.wy - wy) < WALK_STEP_YD) return
        walk.addLast(Step(map, wx, wy, nowMs))
        while (walk.size > 3000 || (walk.isNotEmpty() && nowMs - walk.first().at > WALK_KEEP_MS)) walk.removeFirst()
    }
    /** the last walk on map [map]: the latest run of positions there with no pause over [WALK_PAUSE_MS] (world x, y each) */
    @Synchronized fun lastWalk(map: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var t = Long.MAX_VALUE
        for (s in walk.reversed()) {
            if (s.map != map) { if (out.isEmpty()) continue else break }
            if (out.isNotEmpty() && t - s.at > WALK_PAUSE_MS) break
            out.add(doubleArrayOf(s.wx, s.wy)); t = s.at
        }
        return out.reversed()
    }
    const val WALK_STEP_YD = 8.0
    const val WALK_PAUSE_MS = 90_000L
    const val WALK_KEEP_MS = 30 * 60_000L


    // ---------------------------------------------------------------- phase (c): the route on the network

    /**
     * THE WAY ON THE NETWORK (b-safepath-route; the user's plan: "Once we have that then the pathing should be easy"): from the player
     * at ([fx], [fy]) onto the nearest way (a short walk off it, [ATTACH_YD] at most), along the network - roads first, then spurs,
     * then trails, an avoided way only when nothing else goes, its danger dearer - and off at the way nearest the goal ([tx], [ty]).
     * A goal that is a crossing out of the zone (the route's exit) with one of the network's exits within [EXIT_SNAP_YD] goes to that
     * exit instead. World points (x, y) from the player to the goal; null = the network does not help (too far from it, or the
     * straight walk is as good): the caller keeps its own way.
     */
    fun route(net: Net, fx: Double, fy: Double, tx: Double, ty: Double, toExit: Boolean = false,
              clear: ((DoubleArray, DoubleArray) -> Boolean)? = null): List<DoubleArray>? {
        val byId = net.nodes.associateBy { it.id }
        val usable = net.edges.filter { it.state != REMOVED && byId.containsKey(it.a) && byId.containsKey(it.b) }
        if (usable.isEmpty()) return null
        var gx = tx; var gy = ty
        if (toExit) net.exits.mapNotNull { x -> byId[x.node] }.minByOrNull { hypot(it.wx - tx, it.wy - ty) }
            ?.takeIf { hypot(it.wx - tx, it.wy - ty) <= EXIT_SNAP_YD }?.let { gx = it.wx; gy = it.wy }
        // a road first; a trail only where no road joins the two places (its base and its climb: over the hills it costs what they do)
        fun factor(e: Edge) = (when (e.kind) { ROAD -> 1.0; SPUR, CAVE -> 1.3; else -> 2.2 }) * (if (e.state == AVOID) 6.0 else 1.0) *
            (1.0 + e.danger) * (if (e.kind == ROAD) 1.0 else e.climb.toDouble())
        val pos = HashMap<Int, DoubleArray>()
        for (n in net.nodes) pos[n.id] = when (n.kind) { CAVE -> doubleArrayOf(n.wx, n.wy, Climb.IN_CAVE); LIFT -> doubleArrayOf(n.wx, n.wy, Climb.LIFT)
            else -> doubleArrayOf(n.wx, n.wy) }
        val adj = HashMap<Int, ArrayList<Pair<Int, Double>>>()
        fun link(a: Int, b: Int, c: Double) { adj.getOrPut(a) { ArrayList() }.add(b to c); adj.getOrPut(b) { ArrayList() }.add(a to c) }
        // a lift's ride: its wait, whatever its (nil) length
        for (e in usable) { val a = pos[e.a]!!; val b = pos[e.b]!!
            link(e.a, e.b, hypot(a[0] - b[0], a[1] - b[1]) * factor(e) + if (e.kind == LIFT) Caves.LIFT_WAIT_YD else 0.0) }
        // the walk on and off: the nearest points of the nearest ways (a few), each a virtual node cut into its way
        var nextVirtual = -10
        // the walk on / off goes AHEAD: a way behind the player (away from the goal), or behind the goal, costs more to reach
        fun attach(px: Double, py: Double, virt: Int, toward: DoubleArray): Boolean {
            // never cut into a cave's way (as routeOn's walk on and off): its point would stand on the terrain over the floor, unmarked, and
            // the leg on from it started there on the terrain (Loch Modan B: off Stonewrought Dam's floor onto a 49 degree face)
            val cands = (usable.filter { it.kind != LIFT && it.kind != CAVE }.ifEmpty { usable.filter { it.kind != LIFT } }).map { e ->
                val a = pos[e.a]!!; val b = pos[e.b]!!
                val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
                val t = if (l2 <= 0.0) 0.0 else (((px - a[0]) * dx + (py - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
                val q = doubleArrayOf(a[0] + t * dx, a[1] + t * dy)
                Triple(e, t, q)
            }.filter { hypot(it.third[0] - px, it.third[1] - py) <= ATTACH_YD }.sortedBy { hypot(it.third[0] - px, it.third[1] - py) }.take(4)
            if (cands.isEmpty()) return false
            pos[virt] = doubleArrayOf(px, py)
            for ((e, t, q) in cands) {
                val id = nextVirtual--
                pos[id] = q
                val a = pos[e.a]!!; val b = pos[e.b]!!; val len = hypot(a[0] - b[0], a[1] - b[1]); val f = factor(e)
                link(id, e.a, t * len * f); link(id, e.b, (1 - t) * len * f)
                val dx = q[0] - px; val dy = q[1] - py
                val behind = dx * (toward[0] - px) + dy * (toward[1] - py) < 0.0 && hypot(dx, dy) > 20.0
                link(virt, id, hypot(dx, dy) * OFF_NET * (if (behind) BEHIND else 1.0))
            }
            return true
        }
        val start = -1; val goal = -2
        if (!attach(fx, fy, start, doubleArrayOf(gx, gy)) || !attach(gx, gy, goal, doubleArrayOf(fx, fy))) return null
        // the straight walk, the network's rival only for a goal near the player (farther, the network IS the way)
        if (hypot(gx - fx, gy - fy) <= DIRECT_YD) link(start, goal, hypot(gx - fx, gy - fy) * DIRECT)
        val dist = HashMap<Int, Double>(); val from = HashMap<Int, Int>()
        val q = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[start] = 0.0; q.add(0.0 to start)
        while (q.isNotEmpty()) {
            val (d, k) = q.poll()
            if (k == goal) break
            if (d > (dist[k] ?: Double.MAX_VALUE)) continue
            for ((o, c) in adj[k] ?: continue) {
                val nd = d + c
                if (nd < (dist[o] ?: Double.MAX_VALUE)) { dist[o] = nd; from[o] = k; q.add(nd to o) }
            }
        }
        if (goal !in dist) return null
        val path = arrayListOf(goal); var c = goal
        while (c in from) { c = from[c]!!; path.add(c) }
        path.reverse()
        if (path.size <= 2) return null                                   // straight: the caller's own way
        return smooth(path.map { pos[it]!! }, clear)
    }

    /** the way without its there-and-backs (a loop that comes back within [LOOP_YD] of where it was) and with its ends walked
     *  straight where the way would hook back first: [ATTACH_YD] at most, the way far longer than the straight walk; up to
     *  [SHORTCUT_YD] where the ground on the straight walk is [clear] (no climb: a camp's road round it, never over a hill) */
    fun smooth(pts: List<DoubleArray>, clear: ((DoubleArray, DoubleArray) -> Boolean)? = null): List<DoubleArray> {
        var w = pts
        fun d(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])
        fun arc(from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += d(w[i], w[i + 1]); return s }
        // a cut never starts or ends inside a cave or at a lift (its floor is not the terrain the straight walk would cross: Thunder Bluff's
        // way cut from the mesa's foot straight onto a ramp's floor 100 yd up, the walk's check passing it as a cave's)
        fun cut(i: Int, j: Int) = !Climb.inCave(w[i]) && !Climb.inCave(w[j])
        var i = 0
        while (i < w.size - 2) {
            val j = (w.size - 1 downTo i + 2).firstOrNull { j -> cut(i, j) && d(w[i], w[j]) < LOOP_YD && arc(i, j) > 2.5 * d(w[i], w[j]) + LOOP_YD }
            if (j != null) w = w.subList(0, i + 1) + w.subList(j, w.size)
            i++
        }
        // its zig-zags: where the ground between two of its points is level, the straight walk when the way is far longer
        if (clear != null) {
            var a = 0
            while (a < w.size - 2) {
                val j = (w.size - 1 downTo a + 2).firstOrNull { j -> cut(a, j) && d(w[a], w[j]) <= SHORTCUT_YD && arc(a, j) > 1.6 * d(w[a], w[j]) && clear(w[a], w[j]) }
                if (j != null) w = w.subList(0, a + 1) + w.subList(j, w.size)
                a++
            }
        }
        // the ends: straight to the farthest point near enough when the way there hooks round
        if (w.size > 2) {
            // (never onto a cave's, a lift's or a swim's point, by either branch: the review's H2, the shortcut's skipped the guard)
            val k = (w.size - 2 downTo 1).firstOrNull { k -> cut(0, k) && ((d(w[0], w[k]) <= ATTACH_YD && arc(0, k) > 1.3 * d(w[0], w[k]) + 20.0) ||
                (clear != null && d(w[0], w[k]) <= SHORTCUT_YD && arc(0, k) > 1.6 * d(w[0], w[k]) && clear(w[0], w[k]))) }
            if (k != null) w = listOf(w[0]) + w.subList(k, w.size)
        }
        if (w.size > 2) {
            val last = w.size - 1
            val k = (1 until last).firstOrNull { k -> cut(k, last) && ((d(w[last], w[k]) <= ATTACH_YD && arc(k, last) > 1.3 * d(w[last], w[k]) + 20.0) ||
                (clear != null && d(w[last], w[k]) <= SHORTCUT_YD && arc(k, last) > 1.6 * d(w[last], w[k]) && clear(w[last], w[k]))) }
            if (k != null) w = w.subList(0, k + 1) + listOf(w[last])
        }
        return w
    }
    const val LOOP_YD = 70.0               // a way that comes back this near where it was, the long way round, is a there-and-back
    const val HOOK_YD = 20.0               // ... longer than 2.5 times the straight step plus this (Desolace's Ghost Walker Post: 25 yd out to a
                                           // junction and back; a mountain path's switchbacks stay, their straight step no walk)
    const val SHORTCUT_YD = 250.0          // a hooked end walked straight this far where the ground is clear

    /** the straight walk from [a] to [b] (world x, y) on level ground: every [CELL_YD] of it under [Terrain.STEEP] by [factorAt] */
    fun clearOf(factorAt: (Double, Double) -> Float): (DoubleArray, DoubleArray) -> Boolean = { a, b ->
        val n = max(1, (hypot(b[0] - a[0], b[1] - a[1]) / CELL_YD).toInt())
        (0..n).all { i -> val t = i.toDouble() / n; factorAt(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t).let { it.isNaN() || it < Terrain.STEEP } }
    }
    const val BEHIND = 3.0                 // the walk onto a way behind the player costs this much more
    const val ATTACH_YD = 150.0            // the walk onto / off the network at most this long
    const val OFF_NET = 1.6                // a yard of it costs this much (off the ways, on the ground's own risks)
    const val DIRECT = 1.35                // the straight walk's yard, the network's rival ...
    const val DIRECT_YD = 250.0            // ... for a goal this near the player (it needs no network)
    const val EXIT_SNAP_YD = 300.0         // a crossing out of the zone this near one of the network's exits goes to that exit

    /** the networks for the routes, as their files read (the review's corrections, else the draft), kept in memory */
    val nets = RouteWork.Files { map -> load(map) }

    // ---------------------------------------------------------------- the capitals' districts (our own table; the user: "Both")

    /**
     * THE CAPITALS' DISTRICTS, typed by us (the game's tables name none of them: Orgrimmar's ground reads only "Orgrimmar", its city
     * building's groups have inner names): each district's name and its middle on the city's map (0..1, read off the map's own
     * painted labels). A town there is named "<district> · <its NPCs>" (the user's pick, 2026-10-18: "Valley of Wisdom · Shaman trainers").
     */
    private class District(val name: String, val u: Double, val v: Double)
    private val DISTRICTS: Map<Int, List<District>> = mapOf(
        1454 to listOf(District("Valley of Wisdom", 0.38, 0.38), District("The Drag", 0.59, 0.43), District("Valley of Honor", 0.70, 0.24),
            District("Valley of Strength", 0.52, 0.69), District("Valley of Spirits", 0.36, 0.83), District("The Cleft of Shadow", 0.47, 0.50)),
        1453 to listOf(District("Stormwind Harbor", 0.15, 0.40), District("The Park", 0.35, 0.65), District("Cathedral Square", 0.53, 0.50),
            District("Dwarven District", 0.66, 0.36), District("Stormwind Keep", 0.84, 0.40), District("Old Town", 0.74, 0.58),
            District("Trade District", 0.63, 0.68), District("Mage Quarter", 0.47, 0.80), District("Valley of Heroes", 0.71, 0.86)),
        1455 to listOf(District("The Forlorn Cavern", 0.49, 0.18), District("Hall of Explorers", 0.71, 0.20), District("The Mystic Ward", 0.27, 0.20),
            District("The Great Forge", 0.49, 0.43), District("Tinker Town", 0.69, 0.47), District("The Deeprun Tram", 0.85, 0.53),
            District("The Commons", 0.29, 0.62), District("The Military Ward", 0.64, 0.73), District("The Gates of Ironforge", 0.18, 0.80)),
        1456 to listOf(District("Spirit Rise", 0.25, 0.19), District("Elder Rise", 0.77, 0.28), District("Hunter Rise", 0.58, 0.82)),
        1457 to listOf(District("Cenarion Enclave", 0.36, 0.12), District("Craftsmen's Terrace", 0.60, 0.18), District("Warrior's Terrace", 0.63, 0.40),
            District("The Temple Gardens", 0.39, 0.44), District("Tradesmen's Terrace", 0.63, 0.65), District("Temple of the Moon", 0.40, 0.78)),
        1458 to listOf(District("Ruins of Lordaeron", 0.66, 0.15), District("The War Quarter", 0.54, 0.30), District("The Magic Quarter", 0.79, 0.30),
            District("The Apothecarium", 0.53, 0.60), District("The Rogues' Quarter", 0.80, 0.60), District("The Sewers", 0.25, 0.32)),
    )
    const val DISTRICT_NEAR = 0.20         // a town this near a district's middle (in the map's height, across scaled 3:2) is in it

    /** the district of capital [map] at its ([u], [v]) (0..1), or null (not a capital, or no district near) */
    fun district(map: Int, u: Double, v: Double): String? = DISTRICTS[map]?.minByOrNull { hypot((it.u - u) * 1.5, it.v - v) }
        ?.takeIf { hypot((it.u - u) * 1.5, it.v - v) <= DISTRICT_NEAR }?.name


    // ---------------------------------------------------------------- the crossings checked from both sides

    /** how an exit joins the rest of its zone's network: [LOW] by a way with little danger on it, [DANGER] only through hostile
     *  ground, [NONE] not at all */
    enum class Link { LOW, DANGER, NONE }

    /**
     * exit node [node]'s link to the rest of [net]: the shortest way (by length, never a removed one) from it to the nearest stop (a
     * town, a flight master, a dock) or road junction (three roads or more) at least [LINK_FROM_YD] away; [Link.LOW] when at most
     * [DANGER_LINK_YD] of that way lies among hostile mobs (its edges' danger share x their length)
     */
    fun link(net: Net, node: Int): Link {
        val byId = net.nodes.associateBy { it.id }
        val start = byId[node] ?: return Link.NONE
        val usable = net.edges.filter { it.state != REMOVED && byId.containsKey(it.a) && byId.containsKey(it.b) }
        val roadDeg = HashMap<Int, Int>()
        for (e in usable) if (e.kind == ROAD) { roadDeg[e.a] = (roadDeg[e.a] ?: 0) + 1; roadDeg[e.b] = (roadDeg[e.b] ?: 0) + 1 }
        val adj = HashMap<Int, ArrayList<Pair<Int, Edge>>>()
        for (e in usable) { adj.getOrPut(e.a) { ArrayList() }.add(e.b to e); adj.getOrPut(e.b) { ArrayList() }.add(e.a to e) }
        fun len(e: Edge) = byId[e.a]!!.let { a -> byId[e.b]!!.let { b -> hypot(a.wx - b.wx, a.wy - b.wy) } }
        val dist = HashMap<Int, Double>(); val bad = HashMap<Int, Double>()
        val q = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[node] = 0.0; bad[node] = 0.0; q.add(0.0 to node)
        while (q.isNotEmpty()) {
            val (d, k) = q.poll()
            if (d > (dist[k] ?: Double.MAX_VALUE)) continue
            val n = byId[k]!!
            val far = hypot(n.wx - start.wx, n.wy - start.wy) >= LINK_FROM_YD
            val target = k != node && far && (n.kind == HUB || n.kind == FLIGHT || n.kind == DOCK || (roadDeg[k] ?: 0) >= 3)
            // low danger: at most [DANGER_LINK_YD] of the way back among hostile mobs, or a [DANGER_LINK_SHARE] of a long one (a road
            // past camps for a mile is still the way the game made)
            if (target) return if (bad[k]!! <= max(DANGER_LINK_YD, DANGER_LINK_SHARE * d)) Link.LOW else Link.DANGER
            for ((o, e) in adj[k] ?: continue) {
                val l = len(e); val nd = d + l
                if (nd < (dist[o] ?: Double.MAX_VALUE)) { dist[o] = nd; bad[o] = bad[k]!! + l * e.danger; q.add(nd to o) }
            }
        }
        return Link.NONE
    }
    const val LINK_FROM_YD = 150.0         // the way back counts from a place this far from the crossing
    const val DANGER_LINK_YD = 150.0       // at most this much of it among hostile mobs is a low-danger way back ...
    const val DANGER_LINK_SHARE = 0.25     // ... or this share of a longer one
    const val PAIR_YD = 400.0              // two zones' exits into each other this near are one crossing

    /**
     * THE CROSSINGS, CHECKED FROM BOTH SIDES (the user, 2026-10-18: "I don't mind them, as long as there is a low danger path back to
     * another path"): each of [own]'s exits into a zone of [others] (their drafts) is a crossing with that zone's exit near it; it
     * stays when both sides join their own network by a low-danger way, is AVOIDED (its ways there marked avoid: a route takes it
     * only when nothing else goes) when a side only joins through danger, and is dropped (with the dead-end way to it) when the far
     * side has no exit near it or no way back at all. A neighbour not drafted yet: the exit stays as it is.
     */
    fun checkCrossings(own: Net, others: List<Net>): Net {
        var edges = own.edges.toMutableList()
        val exits = own.exits.toMutableList()
        val byId = own.nodes.associateBy { it.id }
        for (x in own.exits) {
            val other = others.firstOrNull { it.map == x.toMap } ?: continue
            val n = byId[x.node] ?: continue
            // the far side's exit that is this one's nearest AND has this one as its nearest (two crossings 200 yd apart stay two)
            val mineTo = own.exits.filter { it.toMap == other.map }.mapNotNull { y -> byId[y.node] }
            val near = other.exits.filter { it.toMap == own.map }.mapNotNull { y -> other.nodes.firstOrNull { it.id == y.node } }
                .minByOrNull { hypot(it.wx - n.wx, it.wy - n.wy) }?.takeIf { f -> hypot(f.wx - n.wx, f.wy - n.wy) <= PAIR_YD &&
                    mineTo.minByOrNull { hypot(it.wx - f.wx, it.wy - f.wy) }?.id == n.id }
            val mine = link(own, x.node)
            val far = if (near == null) Link.NONE else link(other, near.id)
            when {
                mine == Link.NONE || far == Link.NONE -> {
                    exits.remove(x)
                    // the dead-end way to it goes too (back to the first place it meets another way)
                    dropDeadEnd(edges, exits, x.node)
                }
                mine == Link.DANGER || far == Link.DANGER ->
                    edges = edges.map { e -> if ((e.a == x.node || e.b == x.node) && e.state == DRAFT) Edge(e.a, e.b, e.kind, AVOID, e.src, e.danger, e.climb) else e }.toMutableList()
            }
        }
        val used = (edges.flatMap { listOf(it.a, it.b) } + exits.map { it.node }).toHashSet()
        val keepNodes = own.nodes.filter { it.id in used || it.kind == HUB || it.kind == FLIGHT || it.kind == DOCK }
        return Net(own.map, own.cont, own.built, keepNodes, edges, exits, own.reviewed)
    }


    // ---------------------------------------------------------------- the network, then the ground's own leg (b-route-climb)

    /**
     * THE NETWORK AS FAR AS IT HELPS, THE GROUND'S OWN LEG AFTER (the user on Skull Rock: "it goes straight into and up the mountain
     * instead of along the zeplin path and then into the skull rock cave"): from [f] to the way nearest it ([leg]: the lattice's A*,
     * Climb.route, when it is more than a few yards off), along the network to the way nearest the goal [t] - however far that is
     * from the goal - and the ground's own [leg] from there to it. A goal near the player ([DIRECT_YD]): the leg alone. World points;
     * null = no network.
     */
    /**
     * [net] without the ways that cross a wall on the fine ground ([walkable]: Climb's lattice; the user: "I cant walk up walls"): the
     * draft draws from 25 yd cells, so a trail can still run over a ridge (Dun Morogh's Brewnall and Coldridge Pass trails) - such a
     * way is marked [REMOVED] (the review still sees it; no route takes it). A cave's way is its own floor's, kept.
     */
    fun dropWalled(net: Net, walkable: (DoubleArray, DoubleArray) -> Boolean, legYd: ((DoubleArray, DoubleArray) -> Double?)? = null): Net {
        val byId = net.nodes.associateBy { it.id }
        return Net(net.map, net.cont, net.built, net.nodes, net.edges.map { e ->
            val a = byId[e.a]; val b = byId[e.b]
            if (e.kind == CAVE || e.kind == LIFT || e.state == REMOVED || a == null || b == null || a.kind == MOUTH || b.kind == MOUTH) return@map e
            val pa = doubleArrayOf(a.wx, a.wy); val pb = doubleArrayOf(b.wx, b.wy)
            if (walkable(pa, pb)) return@map e
            // one lip on a main road (Westfall's, a 50 degree step with level ground a few yards off) is no reason to lose the road: kept
            // when the ground's own leg joins its ends hardly longer, walked by that leg ([REWALK])
            val yd = legYd?.invoke(pa, pb)
            if (yd != null && yd <= REWALK_FACTOR * hypot(a.wx - b.wx, a.wy - b.wy)) Edge(e.a, e.b, e.kind, REWALK, e.src, e.danger, e.climb)
            else Edge(e.a, e.b, e.kind, REMOVED, e.src, e.danger, e.climb)
        }, net.exits, net.reviewed)
    }
    const val REWALK_FACTOR = 1.5

    /**
     * [net] with [caves]' ways (b-route-climb; the user: "Caves like this need to be logically detected and factored"): each way a
     * chain of [CAVE] nodes between two [MOUTH] nodes, [CAVE] edges along its floor ([danger] there, 0..1: they are often full of mobs);
     * a mouth joined by a [SPUR] to the network's nearest node within [ATTACH_YD] (a mouth far from every way stands alone: the
     * review sees it, and the routes reach it by the ground's own legs).
     */
    fun withCaves(net: Net, caves: List<Caves.Cave>, danger: ((Double, Double) -> Float)? = null,
                  zoneAt: ((Double, Double) -> Int)? = null, nameOf: (Int) -> String = { "" }): Net {
        if (caves.none { it.ways.isNotEmpty() }) return net
        val nodes = net.nodes.toMutableList(); val edges = net.edges.toMutableList()
        var next = (nodes.maxOfOrNull { it.id } ?: 0) + 1
        val land = net.nodes.filter { it.kind != CAVE && it.kind != MOUTH }
        val mouths = HashMap<String, Int>()
        fun mouth(p: DoubleArray): Int = mouths.getOrPut("${Math.round(p[0])},${Math.round(p[1])}") {
            val id = next++; nodes.add(Node(id, p[0], p[1], MOUTH, "cave"))
            land.minByOrNull { hypot(it.wx - p[0], it.wy - p[1]) }?.takeIf { hypot(it.wx - p[0], it.wy - p[1]) <= ATTACH_YD }
                ?.let { edges.add(Edge(it.id, id, SPUR, DRAFT, "cave", danger?.invoke(p[0], p[1]) ?: 0f)) }
            id
        }
        fun lifted(p: DoubleArray) = p.size > 3 && p[3] == 1.0
        for (c in caves) for (w in c.ways) {
            var last = mouth(w.a); var lastLift = false
            // every third point of its floor kept, and a lift's two landings ([LIFT] nodes, the ride between them a [LIFT] edge)
            for (p in w.pts.subList(1, w.pts.size - 1).filterIndexed { i, q -> i % 3 == 2 || lifted(q) }) {
                val lf = lifted(p)
                val id = next++; nodes.add(Node(id, p[0], p[1], if (lf) LIFT else CAVE, if (lf) "lift" else "cave"))
                edges.add(Edge(last, id, if (lf && lastLift) LIFT else CAVE, DRAFT, "cave", danger?.invoke(p[0], p[1]) ?: 0f)); last = id; lastLift = lf
            }
            edges.add(Edge(last, mouth(w.b), CAVE, DRAFT, "cave", danger?.invoke(w.b[0], w.b[1]) ?: 0f))
        }
        // A TUNNEL OUT (Dun Morogh's South Gate, Loch Modan's gates: the road texture runs through the pass, so the draft drew the road
        // over the ridge and its exits stood on the ridge): a cave way whose two mouths are on different maps' ground leads into the
        // other - its far mouth is an exit ([zoneAt]: the uiMap at a spot), "tunnel"
        val exits = net.exits.toMutableList()
        if (zoneAt != null) for (c in caves) for (w in c.ways) {
            val za = zoneAt(w.a[0], w.a[1]); val zb = zoneAt(w.b[0], w.b[1])
            if (za == zb || za == 0 || zb == 0 || (za != net.map && zb != net.map)) continue
            val far = if (za == net.map) w.b else w.a; val to = if (za == net.map) zb else za
            val id = mouths["${Math.round(far[0])},${Math.round(far[1])}"] ?: continue
            // one exit a tunnel's far end (its floor's pieces meet the terrain at several spots a few yards apart: one opening)
            if (exits.none { it.node == id } && exits.none { x -> x.toMap == to && x.how == "tunnel" &&
                    nodes.firstOrNull { it.id == x.node }?.let { hypot(it.wx - far[0], it.wy - far[1]) <= TUNNEL_SAME_YD } == true })
                exits.add(Exit(id, to, "tunnel", nameOf(to)))
        }
        return Net(net.map, net.cont, net.built, nodes, edges, exits, net.reviewed)
    }

    // ---------------------------------------------------------------- the exits checked on the ground (the device's, and the harness's)

    /**
     * a zone's lattice as its exits see it: [g]'s vertices with their uiMap ([zoneAt]) and whether they are [dry] (deep water a wall), the
     * caves' [links] (a tunnel's two mouths one step apart), and [own]'s MAIN ground - its walkable regions (no wall step, dry; caves not
     * counted) holding a quarter of its dry lattice or more (a pocket walled off by cliffs, a ridge top, crosses nowhere a player goes)
     */
    class Ground(val g: Climb.Grid, val own: Int, zoneAt: (Double, Double) -> Int, dry: (Double, Double) -> Boolean,
                 links: List<Climb.Link> = emptyList()) {
        val n = g.nx * g.ny
        val zoneOf = IntArray(n) { q -> zoneAt(g.wx(q / g.nx), g.wy(q % g.nx)) }
        val dryAt = BooleanArray(n) { q -> dry(g.wx(q / g.nx), g.wy(q % g.nx)) }
        val ownDry = (0 until n).count { zoneOf[it] == own && dryAt[it] }
        private val jump = HashMap<Int, IntArray>().also { m ->
            for (lk in links) {
                val a = q(lk.pts.first()[0], lk.pts.first()[1]); val b = q(lk.pts.last()[0], lk.pts.last()[1])
                m[a] = (m[a] ?: IntArray(0)) + b; m[b] = (m[b] ?: IntArray(0)) + a
            }
        }
        fun q(x: Double, y: Double): Int = g.at(x, y).let { it[1] * g.nx + it[0] }
        /** a step from vertex [q] by ([di], [dj]): its vertex, or -1 (off the grid, deep water, a wall) */
        fun next(q: Int, di: Int, dj: Int): Int {
            val i = q % g.nx; val j = q / g.nx; val vi = i + di; val vj = j + dj
            if (vi !in 0 until g.nx || vj !in 0 until g.ny) return -1
            val v = vj * g.nx + vi
            return if (!dryAt[v] || g.step(i, j, di, dj) == Climb.WALL) -1 else v
        }
        /** each of the zone's own vertices' piece walkable on ITS OWN GROUND alone (-1 = wet or another zone's) */
        private val piece: IntArray by lazy {
            val r = IntArray(n) { -1 }; val st = IntArray(n); var nr = 0
            for (q0 in 0 until n) {
                if (r[q0] >= 0 || !dryAt[q0] || zoneOf[q0] != own) continue
                var sp = 0; st[sp++] = q0; r[q0] = nr
                while (sp > 0) { val q = st[--sp]
                    for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                        val v = next(q, di, dj); if (v >= 0 && r[v] < 0 && zoneOf[v] == own) { r[v] = nr; st[sp++] = v } } }
                nr++
            }
            r
        }
        /** each vertex's walk OFF the zone's own ground (in steps) from its main pieces (a quarter of its dry lattice or more, else the
         *  largest): steps on its own ground are free (a 0-1 breadth-first search); Int.MAX_VALUE = not reached */
        private val size: IntArray by lazy { IntArray(n).also { c -> for (q in 0 until n) if (piece[q] >= 0) c[piece[q]]++ } }
        private val offWalk: IntArray by lazy {
            val big = size.withIndex().filter { it.value * 4 >= ownDry }.map { it.index }.toHashSet()
                .ifEmpty { setOfNotNull(size.withIndex().maxByOrNull { it.value }?.index) }
            val d = IntArray(n) { Int.MAX_VALUE }; val dq = java.util.ArrayDeque<Int>()
            for (q in 0 until n) if (piece[q] >= 0 && piece[q] in big) { d[q] = 0; dq.add(q) }
            while (dq.isNotEmpty()) {
                val q = dq.pollFirst()
                for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                    val v = next(q, di, dj); if (v < 0) continue
                    val w = if (zoneOf[v] == own) 0 else 1
                    if (d[q] + w < d[v]) { d[v] = d[q] + w; if (w == 0) dq.addFirst(v) else dq.addLast(v) } }
            }
            d
        }
        /** vertex [q] is the zone's MAIN ground: its own, and on a piece of its own ground of some size (a [MAIN_PIECE_DIV]th of its
         *  dry lattice: Westfall's south, joined to the rest round through Stranglethorn), or joined to its main pieces with at most
         *  [MAIN_OFF_YD] of walking over a neighbour's ground - never a strip like Duskwood's west bank, reached only round through
         *  Elwynn and over Westfall's bridge */
        fun isMain(q: Int) = zoneOf[q] == own && piece[q] >= 0 &&
            (size[piece[q]] * MAIN_PIECE_DIV >= ownDry || offWalk[q] <= (MAIN_OFF_YD / Climb.STEP).toInt())
        /** for the pictures: each vertex's walkable region as main (0) or not (-1) */
        val region: IntArray by lazy { IntArray(n) { if (isMain(it)) 0 else -1 } }
        val main: Set<Int> = setOf(0)
        /** on (or within [ON_MAIN_STEPS] lattice steps of: an exit node stands on the border, maybe a cell over) the main ground */
        fun onMain(x: Double, y: Double): Boolean { val c = g.at(x, y)
            for (dj in -ON_MAIN_STEPS..ON_MAIN_STEPS) for (di in -ON_MAIN_STEPS..ON_MAIN_STEPS) { val i = c[0] + di; val j = c[1] + dj
                if (i in 0 until g.nx && j in 0 until g.ny && isMain(j * g.nx + i)) return true }
            return false }

        /** THE EXIT'S CROSSING ON THE GROUND (the zone workers: Loch Modan's ridge exits, Stranglethorn's on the Dagger Hills slope): from
         *  world ([x], [y]) the lattice flooded breadth first (no wall step, no deep water, the caves' links taken) - the share of this
         *  zone's dry lattice it reaches, whether it reaches this zone's MAIN ground and how much of [to]'s ground, both within
         *  [EXIT_NEAR_YD] of walking. A real crossing is local on BOTH sides: an unbounded flood called Mulgore's Feralas exit walkable by a
         *  6,943 yd loop through Desolace, and Duskwood's Westfall exits (on the far bank of an 8 yd deep river) by one round through
         *  Elwynn and over its bridge to Westfall */
        fun check(x: Double, y: Double, to: Int): Crossing {
            val seen = BooleanArray(n); val queue = IntArray(n); var head = 0; var tail = 0
            val q0 = q(x, y); seen[q0] = true; queue[tail++] = q0
            val nearSteps = (EXIT_NEAR_YD / Climb.STEP).toInt()
            var level = 0; var levelEnd = tail; var ownN = 0; var theirs = 0; var ownNear = false
            while (head < tail) {
                if (head == levelEnd) { level++; levelEnd = tail }
                val q = queue[head++]
                if (zoneOf[q] == own) { ownN++; if (level <= nearSteps && !ownNear && isMain(q)) ownNear = true }
                else if (zoneOf[q] == to && level <= nearSteps) theirs++
                for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                    val v = next(q, di, dj); if (v >= 0 && !seen[v]) { seen[v] = true; queue[tail++] = v } }
                jump[q]?.forEach { v -> if (!seen[v]) { seen[v] = true; queue[tail++] = v } }
            }
            val sq = Climb.STEP * Climb.STEP
            return Crossing(if (ownDry > 0) ownN.toDouble() / ownDry else 0.0, theirs * sq, ownNear, ownNear && theirs * sq > EXIT_THEIRS_SQYD)
        }
        /** the straight line from world ([x], [y]) to ([tx], [ty]) stays out of deep water (sampled each 2 yd) */
        fun dryLine(x: Double, y: Double, tx: Double, ty: Double): Boolean {
            val k = max(1, (hypot(tx - x, ty - y) / 2.0).toInt())
            return (0..k).all { i -> dryAt[q(x + (tx - x) * i / k, y + (ty - y) * i / k)] }
        }
        /** whether the neighbour [to]'s ground at vertex [from] (a crossing's far side) is joined, walking within [maxYd] on [to]'s
         *  ground only (no wall, dry), to within [JOIN_NEAR_YD] of world ([tx], [ty]) (another exit into [to]): two crossings onto one
         *  open stretch of the neighbour's ground */
        fun joins(from: Int, tx: Double, ty: Double, to: Int, maxYd: Double): Boolean {
            val seen = HashSet<Int>(); var front = arrayListOf(from); seen.add(from)
            fun near(q: Int) = hypot(g.wx(q / g.nx) - tx, g.wy(q % g.nx) - ty) <= JOIN_NEAR_YD
            for (level in 0..(maxYd / Climb.STEP).toInt()) {
                if (front.any { near(it) }) return true
                val nx = ArrayList<Int>()
                for (q in front) for (dj in -1..1) for (di in -1..1) { if (di == 0 && dj == 0) continue
                    val v = next(q, di, dj); if (v < 0 || zoneOf[v] != to || !seen.add(v)) continue; nx.add(v) }
                if (nx.isEmpty()) return false
                front = nx
            }
            return false
        }
    }
    /** an exit's ground check: the [ownShare] of the zone's dry lattice it reaches, the [theirsSqYd] of the neighbour's ground near it */
    const val MAIN_PIECE_DIV = 20
    const val MAIN_OFF_YD = 400.0          // a piece of a zone's own ground joined to its main ground by this much walking over a neighbour's is main ground too
    const val ON_MAIN_STEPS = 5            // about 20 yd
    class Crossing(val ownShare: Double, val theirsSqYd: Double, val ownNear: Boolean, val walkable: Boolean)
    const val EXIT_NEAR_YD = 400.0         // a crossing is local: the neighbour's ground reached within this walk of the exit ...
    const val EXIT_THEIRS_SQYD = 20000.0   // ... at least this much of it (and more than a quarter of the zone's own dry lattice)

    /**
     * [net] with its exits checked on [ground] (the device's terrain step, as the harness does): a "walk" exit whose crossing is not
     * walkable is false (Loch Modan's four on the ridge over the South Gate tunnels, Stranglethorn's on the Dagger Hills slope, Tirisfal's
     * ridge) and goes, with the dead-end way to it. A bridge, a gate, a tunnel, a boat is a structure's way: kept whatever the lattice
     * says (a bridge's deck, a gate's tunnel are not the terrain under them). [verdicts] gets each exit's check.
     */
    fun checkExits(net: Net, ground: Ground, verdicts: ((Exit, Crossing) -> Unit)? = null): Net {
        val byId = net.nodes.associateBy { it.id }
        val edges = net.edges.toMutableList(); val exits = net.exits.toMutableList()
        for (x in net.exits) {
            val n = byId[x.node] ?: continue
            val c = ground.check(n.wx, n.wy, x.toMap)
            verdicts?.invoke(x, c)
            // a walk exit: both sides near it; a drafted bridge, gate or ford (a structure's way, not the terrain under it): the
            // neighbour's ground reached at all near it (Hillsbrad's Hinterlands exit on the snow peaks; Elwynn's gate over the wall)
            val bad = when (x.how) { "walk" -> !c.walkable; "gate", "bridge", "ford" -> c.theirsSqYd <= EXIT_THEIRS_SQYD; else -> false }
            if (!bad) continue
            exits.remove(x)
            dropDeadEnd(edges, exits, x.node)
        }
        val used = (edges.flatMap { listOf(it.a, it.b) } + exits.map { it.node }).toHashSet()
        return Net(net.map, net.cont, net.built, net.nodes.filter { it.id in used || it.kind == HUB || it.kind == FLIGHT || it.kind == DOCK },
            edges, exits, net.reviewed)
    }
    /** the dead-end way to node [from] (back to the first place it meets another way, or another exit), out of [edges] */
    private fun dropDeadEnd(edges: MutableList<Edge>, exits: List<Exit>, from: Int) {
        var cur = from
        while (true) {
            val at = edges.filter { it.a == cur || it.b == cur }
            if (at.size != 1) break
            val e = at[0]; edges.remove(e)
            cur = if (e.a == cur) e.b else e.a
            if (exits.any { it.node == cur }) break
        }
    }

    /**
     * GROUND EXITS (the zone workers: the Barrens' way to Stonetalon by Greatwood Vale, Loch Modan's to the Badlands - walkable, but no
     * road reaches the border, so the draft made no exit): where the zone's main walkable ground ([ground]: no wall step, dry) crosses
     * the border into a neighbour along a corridor at least [GROUND_EXIT_MIN] crossings wide, an exit on this side of it ("walk"),
     * joined by a trail to the nearest node. Only the crossings a player would take (the review of the first 33: most stood on a
     * mountain band, a cliff's foot or a plateau's top a few yards over the border):
     * - never into the [capitals] given (none by default: Thunder Bluff's map holds the plain at its mesas' feet, walked to the lifts;
     *   a walled city's gate is its drafted exit);
     * - its own crossing walkable ([Ground.check]: the neighbour's ground reached within [EXIT_NEAR_YD]) - not a pocket over the line;
     * - onto the neighbour's own map ([onMap]: inside its world rect), never the world's rim beyond its map's edge;
     * - one per open stretch of border: crossings within [GROUND_EXIT_GAP_YD] of each other, or whose far sides walk to each other
     *   within [GROUND_EXIT_JOIN_YD] on the neighbour's own ground, are one stretch (found the same in any order); a stretch an exit
     *   already crosses has its crossings within [GROUND_EXIT_SERVE_YD] served by it; the rest get one exit each that far apart (a
     *   capital's ground, entered at many points: no exit within the gap only);
     * A drafted "walk" exit off the zone's main ground (on a ridge over a tunnel: Loch Modan's four) is dropped (a bridge, a gate, a
     * tunnel, a boat is a structure's way: kept).
     */
    fun groundExits(net: Net, g: Climb.Grid, zoneAt: (Double, Double) -> Int, dry: (Double, Double) -> Boolean, nameOf: (Int) -> String = { "" },
                    links: List<Climb.Link> = emptyList(), capitals: Set<Int> = emptySet(), onMap: ((Int, Double, Double) -> Boolean)? = null): Net =
        groundExits(net, Ground(g, net.map, zoneAt, dry, links), nameOf, capitals, onMap)

    fun groundExits(net: Net, ground: Ground, nameOf: (Int) -> String = { "" }, capitals: Set<Int> = emptySet(),
                    onMap: ((Int, Double, Double) -> Boolean)? = null, said: ((String) -> Unit)? = null): Net {
        val g = ground.g
        class X(val v: Int, val x: Double, val y: Double, val to: Int)
        val cross = ArrayList<X>()
        for (j in 0 until g.ny) for (i in 0 until g.nx) {
            val q = j * g.nx + i
            if (!ground.isMain(q)) continue
            for ((di, dj) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
                val v = ground.next(q, di, dj); if (v < 0) continue
                val zv = ground.zoneOf[v]
                if (zv == 0 || zv == net.map) continue
                cross.add(X(v, g.wx(j), g.wy(i), zv)); break
            }
        }
        val byId = net.nodes.associateBy { it.id }.toMutableMap()
        val nodes = net.nodes.toMutableList(); val edges = net.edges.toMutableList()
        val exits = net.exits.filter { e -> e.how != "walk" || byId[e.node]?.let { ground.onMain(it.wx, it.wy) || ground.check(it.wx, it.wy, e.toMap).walkable } != false }.toMutableList()
        var next = (nodes.maxOfOrNull { it.id } ?: 0) + 1
        // the corridors: crossings into one neighbour side by side; each one's crossing at its middle, and its width
        val left = cross.toMutableList(); val corridors = ArrayList<Pair<X, Int>>()
        while (left.isNotEmpty()) {
            val seed = left.removeAt(0); val group = arrayListOf(seed); var grew = true
            while (grew) { grew = false
                val more = left.filter { c -> c.to == seed.to && group.any { hypot(it.x - c.x, it.y - c.y) <= Climb.STEP * 1.5 } }
                if (more.isNotEmpty()) { group.addAll(more); left.removeAll(more.toSet()); grew = true } }
            if (group.size < GROUND_EXIT_MIN || seed.to in capitals) continue
            val cx = group.map { it.x }.average(); val cy = group.map { it.y }.average()
            corridors.add(group.minByOrNull { hypot(it.x - cx, it.y - cy) }!! to group.size)
        }
        fun say(at: X, why: String) { said?.invoke("${nameOf(at.to)} at ${at.x.toInt()},${at.y.toInt()}: $why") }
        // the ones a player could take: onto the neighbour's own map (its rect: never the land beyond its map's edge - Loch Modan's
        // east, "Wetlands" by its areas but off the Wetlands' map), and walkable (its ground reached near it, not a pocket)
        val good = corridors.filter { (at, _) ->
            if (onMap != null && !onMap(at.to, g.wx(at.v / g.nx), g.wy(at.v % g.nx))) { say(at, "off ${nameOf(at.to)}'s own map"); return@filter false }
            val ck = ground.check(at.x, at.y, at.to)
            if (!ck.walkable) say(at, "not walkable (${(ck.theirsSqYd / 1000).toInt()}k sq yd of its ground within ${EXIT_NEAR_YD.toInt()} yd)")
            ck.walkable
        }
        // only VERIFIED exits (their crossing walkable on the ground) stand for a stretch or a gap: a false one (Hillsbrad's Hinterlands
        // exit on the snow peaks, 329 yd from the real crossing) never hides a real crossing
        val verified = exits.filter { e -> byId[e.node]?.let { ground.check(it.wx, it.wy, e.toMap).walkable } == true }.map { it.node }.toHashSet()
        fun add(at: X, why: String) {
            say(at, why)
            val id = next++; nodes.add(Node(id, at.x, at.y, EXIT, "ground")); byId[id] = nodes.last()
            exits.add(Exit(id, at.to, "walk", nameOf(at.to)))
            verified.add(id)
            // joined to the nearest node whose straight way there stays out of deep water (Duskwood: trails drawn over an 8 yd deep river)
            net.nodes.filter { it.kind != CAVE && it.kind != MOUTH && it.kind != LIFT }.sortedBy { hypot(it.wx - at.x, it.wy - at.y) }.take(12)
                .firstOrNull { ground.dryLine(at.x, at.y, it.wx, it.wy) }?.let { edges.add(Edge(it.id, id, TRAIL, DRAFT, "ground")) }
        }
        // a capital's ground is entered at many points (Thunder Bluff's plain at its mesas' feet, walked to its lifts): a crossing into
        // it with no exit into it within [GROUND_EXIT_GAP_YD], in the lattice's order
        for ((at, w) in good) if (at.to in CityExits.CITIES) {
            val close = exits.filter { it.toMap == at.to && it.node in verified }.mapNotNull { byId[it.node] }.firstOrNull { hypot(it.wx - at.x, it.wy - at.y) <= GROUND_EXIT_GAP_YD }
            if (close != null) say(at, "an exit into the city ${hypot(close.wx - at.x, close.wy - at.y).toInt()} yd away") else add(at, "an exit (a corridor $w vertices wide)")
        }
        // THE OPEN STRETCHES (any other neighbour): crossings into one neighbour within [GROUND_EXIT_GAP_YD] of each other, or whose far
        // sides walk to each other on the neighbour's ground ([Ground.joins]), are one stretch of border, whatever the order they are
        // found in; a stretch an exit already crosses (a road's, a bridge's, a tunnel's) needs none, any other gets one, at its first
        // corridor in the lattice's order (the same spot as before this rule: the routes that walk its trail keep it)
        fun oneStretch(a: X, bx: Double, by: Double) = hypot(a.x - bx, a.y - by) <= GROUND_EXIT_GAP_YD ||
            ground.joins(a.v, bx, by, a.to, GROUND_EXIT_JOIN_YD)
        val open = good.filter { it.first.to !in CityExits.CITIES }
        val roads = net.edges.filter { it.kind == ROAD && it.state != REMOVED }.mapNotNull { e -> byId[e.a]?.let { a -> byId[e.b]?.let { b -> a to b } } }
        fun roadYd(at: X): Double = roads.minOfOrNull { (a, b) ->
            val dx = b.wx - a.wx; val dy = b.wy - a.wy; val l2 = dx * dx + dy * dy
            val u = if (l2 <= 0.0) 0.0 else (((at.x - a.wx) * dx + (at.y - a.wy) * dy) / l2).coerceIn(0.0, 1.0)
            hypot(at.x - a.wx - u * dx, at.y - a.wy - u * dy) } ?: Double.MAX_VALUE
        val parent = IntArray(open.size) { it }
        fun find(k: Int): Int { var a = k; while (parent[a] != a) a = parent[a]; return a }
        for (a in open.indices) for (b in a + 1 until open.size) if (open[a].first.to == open[b].first.to && find(a) != find(b) &&
            (oneStretch(open[a].first, open[b].first.x, open[b].first.y) || oneStretch(open[b].first, open[a].first.x, open[a].first.y))) parent[find(a)] = find(b)
        for ((_, ks) in open.indices.groupBy { find(it) }) {
            val to = open[ks[0]].first.to
            // the exits already on this stretch; each serves its crossings within [GROUND_EXIT_SERVE_YD] (a long open border keeps one
            // every so far: Mulgore's Red Rocks, 1070 yd up the border from its gate road, keeps its own)
            val servers = exits.filter { it.toMap == to && it.node in verified }.mapNotNull { byId[it.node] }.filter { e -> ks.any { oneStretch(open[it].first, e.wx, e.wy) } }
                .map { doubleArrayOf(it.wx, it.wy) }.toMutableList()
            // in the lattice's order, but a corridor a road comes near first (Hillsbrad's way up to the Hinterlands, 430 yd from its
            // road, before the snow peaks 334 yd along the stretch, 729 yd from any)
            for (k in ks.sortedWith(compareBy({ roadYd(open[it].first) > GROUND_EXIT_ROAD_YD }, { it }))) {
                val at = open[k].first
                val by = servers.firstOrNull { hypot(it[0] - at.x, it[1] - at.y) <= GROUND_EXIT_SERVE_YD }
                if (by != null) { say(at, "the same open stretch as the exit at ${by[0].toInt()},${by[1].toInt()} (${hypot(by[0] - at.x, by[1] - at.y).toInt()} yd)"); continue }
                add(at, "an exit (a corridor ${open[k].second} vertices wide, ${ks.size} in its stretch)")
                servers.add(doubleArrayOf(at.x, at.y))
            }
        }
        return Net(net.map, net.cont, net.built, nodes, edges, exits, net.reviewed)
    }
    const val TUNNEL_SAME_YD = 60.0        // a tunnel's far mouths into one neighbour this near are one exit
    const val GROUND_EXIT_MIN = 5          // a crossing corridor this many lattice vertices wide (about 20 yd) ...
    const val GROUND_EXIT_GAP_YD = 600.0   // ... with no exit into that neighbour this near ...
    const val GROUND_EXIT_JOIN_YD = 800.0  // ... nor one its far side reaches within this walk on the neighbour's ground (one open border) ...
    const val GROUND_EXIT_ROAD_YD = 700.0  // ... its corridors this near a road taken first ...
    const val GROUND_EXIT_SERVE_YD = 1000.0 // ... an exit on a stretch serving its crossings this near
    const val JOIN_NEAR_YD = 100.0         // ... to within this of it (a bridge's or a road's exit stands on this zone's bank or side)

    fun routeOn(net: Net, fx: Double, fy: Double, tx: Double, ty: Double, leg: (DoubleArray, DoubleArray) -> List<DoubleArray>?,
                toExit: Boolean = false, clear: ((DoubleArray, DoubleArray) -> Boolean)? = null,
                walkable: ((DoubleArray, DoubleArray) -> Boolean)? = null): List<DoubleArray>? {
        val f = doubleArrayOf(fx, fy); val t = doubleArrayOf(tx, ty)
        if (hypot(tx - fx, ty - fy) <= DIRECT_YD) return leg(f, t) ?: listOf(f, t)
        val byId = net.nodes.associateBy { it.id }
        val usable = net.edges.filter { it.state != REMOVED && it.state != AVOID && byId.containsKey(it.a) && byId.containsKey(it.b) }
        if (usable.isEmpty()) return null
        val onLand = usable.filter { it.kind != CAVE && it.kind != LIFT }.ifEmpty { usable }          // a walk on / off never into a hill's cave
        fun nearestOnWay(px: Double, py: Double): DoubleArray = onLand.map { e ->
            val a = byId[e.a]!!; val b = byId[e.b]!!
            val dx = b.wx - a.wx; val dy = b.wy - a.wy; val l2 = dx * dx + dy * dy
            val u = if (l2 <= 0.0) 0.0 else (((px - a.wx) * dx + (py - a.wy) * dy) / l2).coerceIn(0.0, 1.0)
            doubleArrayOf(a.wx + u * dx, a.wy + u * dy)
        }.minByOrNull { hypot(it[0] - px, it[1] - py) }!!
        val on = nearestOnWay(fx, fy)
        val off = if (toExit) net.exits.mapNotNull { x -> byId[x.node] }.minByOrNull { hypot(it.wx - tx, it.wy - ty) }
            ?.takeIf { hypot(it.wx - tx, it.wy - ty) <= EXIT_SNAP_YD }?.let { doubleArrayOf(it.wx, it.wy) } ?: nearestOnWay(tx, ty) else nearestOnWay(tx, ty)
        // the goal no farther from the start than from the network: the ground's own leg straight there
        if (hypot(tx - fx, ty - fy) < hypot(tx - off[0], ty - off[1])) return leg(f, t) ?: listOf(f, t)
        // the network's own ways that climb a wall on the fine ground (drawn from the coarse cells: a cliff they averaged away) are
        // no way at all: left out, so the network's search goes round by the ways that are (a cave's own floor is its own)
        val walked = if (walkable == null) net else Net(net.map, net.cont, net.built, net.nodes, net.edges.filter { e ->
            val a = byId[e.a]; val b = byId[e.b]
            e.kind == CAVE || e.kind == LIFT || e.state == REWALK || a == null || b == null || a.kind == MOUTH || b.kind == MOUTH || walkable(doubleArrayOf(a.wx, a.wy), doubleArrayOf(b.wx, b.wy))
        }, net.exits, net.reviewed)
        val mid = route(walked, on[0], on[1], off[0], off[1], clear = clear) ?: listOf(on, off)
        val exitEnd = toExit && hypot(off[0] - tx, off[1] - ty) > 1.0 && net.exits.any { x -> byId[x.node]?.let { it.wx == off[0] && it.wy == off[1] } == true }
        val chain = ArrayList<DoubleArray>()
        for (p in listOf(f) + mid + (if (exitEnd) emptyList() else listOf(t))) if (chain.isEmpty() || hypot(chain.last()[0] - p[0], chain.last()[1] - p[1]) > 0.5) chain.add(p)
        // walked a piece at a time, each from where the last one truly ended: the walk on and off by the ground's own leg; the network's
        // pieces straight unless they cross a wall on the fine ground (a cliff its coarse cells missed: the ground's leg instead); a
        // point no leg reaches (a way's node on a ledge) passed by, the next leg going on from where it stopped
        val out = arrayListOf(f)
        val reachedAt = IntArray(chain.size)                              // the index in [out] where each of the chain's points was walked to
        for (q in 1 until chain.size) {
            val cur = out.last(); val tgt = chain[q]
            val ends = q == 1 || (q == chain.size - 1 && !exitEnd)
            val straight = (!ends || hypot(tgt[0] - cur[0], tgt[1] - cur[1]) <= JOIN_YD) && walkable?.invoke(cur, tgt) ?: true
            if (straight || Climb.inCave(cur) && Climb.inCave(tgt)) { out.add(tgt); reachedAt[q] = out.size - 1; continue }
            val lg = leg(cur, tgt)
            if (lg == null) { if (walkable == null) out.add(tgt); reachedAt[q] = out.size - 1; continue }
            // the walk on: a leg that starts at its first vertex, not at the exact start (the hop from it crosses a wall face), starts the
            // way there too - [f] kept would be joined straight to that vertex, the very hop the leg refused (Blasted Lands' Horde A, a
            // start 2.4 yd over 45-48 degree faces)
            if (out.size == 1 && hypot(lg[0][0] - f[0], lg[0][1] - f[1]) > 0.5 && walkable?.invoke(f, lg[0]) == false) out.clear()
            // the leg's every point: one that starts at its first vertex (the hop from [cur] crosses a wall) never loses that vertex, else
            // [cur] would be joined straight to the next one, a line nobody checked (Loch Modan B, out of a mine's mouth)
            for (p in lg) if (out.isEmpty() || hypot(out.last()[0] - p[0], out.last()[1] - p[1]) > 0.5) out.add(p)
            reachedAt[q] = out.size - 1
        }
        // the ground's own way straight there, when it reaches the goal and the network's way round is far longer (a valley walled
        // round, its goal at its own far end: the network leaves it by its gate and comes back)
        fun yd(w: List<DoubleArray>, from: Int = 0) = (from until w.size - 1).sumOf { k -> hypot(w[k + 1][0] - w[k][0], w[k + 1][1] - w[k][1]) }
        if (walkable != null && hypot(tx - fx, ty - fy) <= DIRECT_LEG_YD) {
            val direct = leg(f, t)
            if (direct != null && hypot(direct.last()[0] - tx, direct.last()[1] - ty) <= 3.0 && yd(direct) * NET_FAVOUR < yd(out)) return direct
        }
        // A WAY OUT AND STRAIGHT BACK (a network point passed by, its node off the walk: out to it and back to the same spot) is no way:
        // left out. Mulgore's J went 1.6 yd off the Spirit Rise walkway's edge to a node on the ground 2.3 yd under it and climbed back
        // (back to the very same spot only, the line on from it the one checked from there; never the goal's own point; after the
        // choice of the ground's own way over this one, which it leaves as it was)
        fun dropBack(w: List<DoubleArray>): List<DoubleArray> { val o = ArrayList(w); var sp = 1
            while (sp < o.size - 2) {
                val a = o[sp - 1]; val c = o[sp + 1]
                if (!Climb.inCave(o[sp]) && !Climb.inCave(a) && !Climb.inCave(c) && a[0] == c[0] && a[1] == c[1]) { o.removeAt(sp); o.removeAt(sp); if (sp > 1) sp-- }
                else sp++
            }
            return o }
        var best = unloop(dropBack(out), walkable)
        // THE WALK OFF BEFORE THE TURN (Teldrassil's Fel Rock, gate run 7: +404 yd): the walk off is at the way's point nearest the goal,
        // which can lie past the turn - the network runs on along the road and the ground's leg walks back. unloop cuts that only where
        // the straight join is itself walkable; past a ridge or a town's houses (no cutting through buildings) it is not, so the
        // there-and-back stayed. The network's last few points before the walk off are each tried as the walk off too, by the ground's
        // own leg (every rule of the leg holds): taken when the walk from that point as drawn is [NET_FAVOUR] times longer than the
        // leg (a there-and-back, not a road preferred) and the whole way, unlooped, is shorter
        val end = chain.size - 2                                           // the walk off (the goal's chain point is after it)
        if (walkable != null && !exitEnd && end >= 2) {
            val offYd = hypot(chain[end][0] - tx, chain[end][1] - ty)
            for (q in end - 1 downTo maxOf(1, end - OFF_BEFORE_TRIES)) {
                val k = reachedAt[q]; val p = out[k]
                if (Climb.inCave(p) || hypot(p[0] - tx, p[1] - ty) > offYd + LOOP_YD) continue
                val lg = leg(p, t) ?: continue
                if (hypot(lg.last()[0] - tx, lg.last()[1] - ty) > 3.0) continue
                val alt = ArrayList(out.subList(0, k + 1)); for (r in lg) if (hypot(alt.last()[0] - r[0], alt.last()[1] - r[1]) > 0.5) alt.add(r)
                if (yd(alt, k) * NET_FAVOUR >= yd(out, k)) continue
                val altU = unloop(dropBack(alt), walkable)
                if (yd(altU) < yd(best)) best = altU
            }
        }
        return best
    }

    /**
     * the WALKED way's there-and-backs (Teldrassil's Fel Rock: the network ran on to the way's point nearest the goal, past the turn,
     * and the ground's leg walked back the same road): smooth() sees only the network's points, so the walked way is cut here too - a
     * point it comes back to within [LOOP_YD] the long way round, joined straight when that straight step is [walkable] (never a wall)
     * and neither end is in a cave (a cave's floor is its own way)
     */
    fun unloop(pts: List<DoubleArray>, walkable: ((DoubleArray, DoubleArray) -> Boolean)?): List<DoubleArray> {
        if (pts.size < 3) return pts
        var w = pts
        var i = 0
        while (i < w.size - 2) {
            val cum = DoubleArray(w.size); for (k in 1 until w.size) cum[k] = cum[k - 1] + hypot(w[k][0] - w[k - 1][0], w[k][1] - w[k - 1][1])
            val a = w[i]
            val j = if (Climb.inCave(a)) null else (w.size - 1 downTo i + 2).firstOrNull { j ->
                val d = hypot(w[j][0] - a[0], w[j][1] - a[1])
                d < LOOP_YD && cum[j] - cum[i] > 2.5 * d + HOOK_YD && !Climb.inCave(w[j]) && (walkable?.invoke(a, w[j]) ?: true)
            }
            if (j != null) w = w.subList(0, i + 1) + w.subList(j, w.size)
            i++
        }
        return w
    }
    const val DIRECT_LEG_YD = 1500.0       // a goal this near: the ground's own way there is the network's rival ...
    const val NET_FAVOUR = 1.5             // ... taken when the network's way is this many times longer
    const val OFF_BEFORE_TRIES = 4         // the network's points before its walk off tried as the walk off (the walk off before the turn)
    const val JOIN_YD = 12.0               // a start or a goal this near the way needs no leg of its own

}
