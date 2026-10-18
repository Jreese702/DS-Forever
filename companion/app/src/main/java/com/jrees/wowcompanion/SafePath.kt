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
    const val DRAFT = "draft"; const val OK = "ok"; const val AVOID = "avoid"; const val REMOVED = "removed"

    const val CELL_YD = 25.0               // the draft's grid (Roads' and Terrain's cells)
    const val MIN_ROAD_CELLS = 8           // a road patch smaller than this is a texture's speck, not a road
    const val PRUNE_CELLS = 3              // a stub off a road this short is the thinning's whisker
    const val SIMPLIFY_YD = 14.0           // a line keeps its points where it bends more than this
    const val SPUR_MAX_YD = 700.0          // a travel point or a town joins the network this near, else it stands alone (the review sees it)
    const val TRAIL_MAX_YD = 1600.0        // a piece of the network joins the rest by a trail this near
    const val EXIT_MERGE_YD = 160.0
    const val WELD_YD = 60.0               // a road's end this near another piece's way is welded to it
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
                val ground: ((Double, Double) -> Float)? = null, val painted: ((Double, Double) -> Boolean)? = null)

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
        fun passable(k: Int) = !cost[k].isNaN() && cost[k] < Terrain.WALL

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
            // a zone with no road of its own (a capital: its streets are buildings, its ground plain dirt): its points joined by trails,
            // its ways out found from them
            if (roadless) { linkPoints(); waysOut(); mergeExits() }
            joinPieces()
            // the clean-up: trails that only double a road, the pieces they leave apart, the stubs that lead nowhere
            dropDoubles()
            joinPieces()
            dropStrays()
            trimStubs()
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
        fun borderEnds() {
            val deg = HashMap<Int, Int>()
            for (e in edges) { deg[e.a] = (deg[e.a] ?: 0) + 1; deg[e.b] = (deg[e.b] ?: 0) + 1 }
            val exitNodes = exits.map { it.node }.toHashSet()
            for ((id, d) in deg.toList()) {
                if (d != 1 || id in exitNodes) continue
                val c = cellOf(nodes[id].wx, nodes[id].wy).takeIf { it >= 0 } ?: continue
                val path = search(listOf(c), BORDER_END_YD) { k -> zone[k] != own && zone[k] != 0 && roadNear(k, zone[k], ROAD_ON_YD) } ?: continue
                if (path.size < 2) continue
                val x = crossing(path.last(), path[path.size - 2])
                emit(id, x, path.dropLast(1), SPUR)
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
            for ((id, d) in deg.toList()) {
                if (d != 1 || id in exitNodes) continue
                val c = cellOf(nodes[id].wx, nodes[id].wy).takeIf { it >= 0 } ?: continue
                val path = search(listOf(c), WELD_YD) { k -> cellEdge[k]?.let { e -> e.a != id && e.b != id && find(e.a) != find(id) } == true } ?: continue
                val e = cellEdge[path.last()] ?: continue
                val to = split(e, path.last())
                emit(id, to, path, ROAD)
                union(id, to)
            }
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
        fun search(starts: List<Int>, maxYd: Double, goal: (Int) -> Boolean): List<Int>? {
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
                    if (!passable(o)) continue
                    val step = if (o % nx != k % nx && o / nx != k / nx) CELL_YD * 1.4142 else CELL_YD
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
            val path = search(listOf(k), SPUR_MAX_YD) { c -> c in cellEdge } ?: return
            val at = cellEdge[path.last()]?.let { split(it, path.last()) } ?: return
            emit(id, at, path, SPUR)
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
                val path = search(starts, TRAIL_MAX_YD) { k -> zone[k] != own && zone[k] != 0 && zone[k] !in done && roadNear(k, zone[k], ROAD_ON_YD) } ?: return
                if (path.size < 2) return
                done.add(zone[path.last()])
                val first = path.first()
                val from = nodeAtCell[first] ?: cellEdge[first]?.let { split(it, first) } ?: return
                val x = crossing(path.last(), path[path.size - 2])
                emit(from, x, path.dropLast(1), TRAIL)
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
                    if (yd < STUB_YD || (bad * 2 >= cells && yd < DANGER_STUB_YD) || viaTrail) { edges.removeAll(chain.toSet()); removed = true }
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

            /** over world rect ([t], [b], [l], [r]) by [chunkYd] cells: each cell's area from [areaAt] */
            fun build(t: Double, b: Double, l: Double, r: Double, chunkYd: Double, areaAt: (Double, Double) -> Int?): Zones {
                val nx = max(1, ceil((l - r) / chunkYd).toInt()); val ny = max(1, ceil((t - b) / chunkYd).toInt())
                return Zones(t, b, l, r, nx, ny, IntArray(nx * ny) { k ->
                    areaAt(t - (k / nx + 0.5) * (t - b) / ny, l - (k % nx + 0.5) * (l - r) / nx) ?: 0
                })
            }
        }
    }
    val zones = RouteWork.Files { map -> Zones.read(ArtHarvest.file("safepath/zones/$map.bin")) }
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
        override val version = 1
        override val beforePlay = false
        override val weight = 40
        override fun run(job: ArtHarvest.Job) {
            val g = job.game
            val rects = MapHarvestSteps.zoneRects(job).filterValues { MapHarvest.tilesUnder(it) != null }
            val areas = g.db2(PlaceNames.DB_AREA_TABLE)?.rows(strings = setOf(1))?.associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
                ?: error("AreaTable unreadable: ${g.lastReadWhy}")
            val uimap = g.db2(DangerStore.DB_UIMAP_ASSIGNMENT)?.rows()?.let { rows ->
                val o = HashMap<Int, Int>(); for (r in rows) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m }; o
            } ?: error("UiMapAssignment unreadable: ${g.lastReadWhy}")
            val mapRows = g.db2(MapHarvest.DB_MAP)?.rows()?.associateBy { it.id } ?: error("Map.db2 unreadable: ${g.lastReadWhy}")
            val roots = HashMap<Int, Map<Int, Int>>()
            val tiles = object : LinkedHashMap<Long, Map<Int, Int>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Map<Int, Int>>?) = size > 24
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
    fun refresh(map: Int, cont: Int, t: Double, b: Double, l: Double, r: Double, mine: Int) {
        if (!RouteWork.onMain()) return
        val stamp = "${Roads.version}:${Roads.files.version}:${Terrain.files.version}:${zones.version}:${DangerStore.version}:$mine"
        if (tried[map] == stamp) return
        tried[map] = stamp
        RouteWork.run({
            Roads.of(map) ?: return@run "no road textures yet"
            val roadCells = Roads.roadCells(map)
            if (roadCells == 0 && map !in CityExits.CITIES) return@run "no learned roads yet"
            val tg = Terrain.of(map) ?: return@run "no terrain yet"
            val zg = zones.get(map) ?: return@run "no zone ground yet"
            val ar = areas() ?: return@run "no areas table yet"
            val built = "roads $roadCells, ${zg.nx}x${zg.ny} chunks, v2"
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
            val net = draft(Input(map, cont, t, b, l, r, { wx, wy -> Roads.isRoad(map, wx, wy) },
                { wx, wy -> Terrain.factor(tg.at(wx, wy)) }, { wx, wy -> ar.uiMap(zg.areaAt(wx, wy)) },
                fixed + hubs(friendly, { x, y -> PlaceNames.nameAt(x, y, ar.rows) { a, b2 -> zg.areaAt(a, b2) } }, nameOf(map),
                    districtFor(map, t, b, l, r)), ::nameOf,
                dangerOf(hostile), ground = ground, painted = painted), built)
            // the crossings checked from both sides, against the neighbours' drafts there are (the user: "a low danger path back")
            val neighbours = net.exits.map { it.toMap }.distinct().mapNotNull { m -> drafts.get(m) }
            val checked = checkCrossings(net, neighbours)
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
        val out = ArrayList<Fixed>()
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
            left.removeAll { hypot(it.wx - best.wx, it.wy - best.wy) < HUB_YD * 2 }
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
        fun factor(e: Edge) = (when (e.kind) { ROAD -> 1.0; SPUR -> 1.3; else -> 2.2 }) * (if (e.state == AVOID) 6.0 else 1.0) *
            (1.0 + e.danger) * (if (e.kind == ROAD) 1.0 else e.climb.toDouble())
        val pos = HashMap<Int, DoubleArray>()
        for (n in net.nodes) pos[n.id] = doubleArrayOf(n.wx, n.wy)
        val adj = HashMap<Int, ArrayList<Pair<Int, Double>>>()
        fun link(a: Int, b: Int, c: Double) { adj.getOrPut(a) { ArrayList() }.add(b to c); adj.getOrPut(b) { ArrayList() }.add(a to c) }
        for (e in usable) { val a = pos[e.a]!!; val b = pos[e.b]!!; link(e.a, e.b, hypot(a[0] - b[0], a[1] - b[1]) * factor(e)) }
        // the walk on and off: the nearest points of the nearest ways (a few), each a virtual node cut into its way
        var nextVirtual = -10
        // the walk on / off goes AHEAD: a way behind the player (away from the goal), or behind the goal, costs more to reach
        fun attach(px: Double, py: Double, virt: Int, toward: DoubleArray): Boolean {
            val cands = usable.map { e ->
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
        // the loops: from each point, the farthest later one it comes back to
        var i = 0
        while (i < w.size - 2) {
            val j = (w.size - 1 downTo i + 2).firstOrNull { j -> d(w[i], w[j]) < LOOP_YD && arc(i, j) > 2.5 * d(w[i], w[j]) + LOOP_YD }
            if (j != null) w = w.subList(0, i + 1) + w.subList(j, w.size)
            i++
        }
        // its zig-zags: where the ground between two of its points is level, the straight walk when the way is far longer
        if (clear != null) {
            var a = 0
            while (a < w.size - 2) {
                val j = (w.size - 1 downTo a + 2).firstOrNull { j -> d(w[a], w[j]) <= SHORTCUT_YD && arc(a, j) > 1.6 * d(w[a], w[j]) && clear(w[a], w[j]) }
                if (j != null) w = w.subList(0, a + 1) + w.subList(j, w.size)
                a++
            }
        }
        // the ends: straight to the farthest point near enough when the way there hooks round
        if (w.size > 2) {
            val k = (w.size - 2 downTo 1).firstOrNull { k -> (d(w[0], w[k]) <= ATTACH_YD && arc(0, k) > 1.3 * d(w[0], w[k]) + 20.0) ||
                (clear != null && d(w[0], w[k]) <= SHORTCUT_YD && arc(0, k) > 1.6 * d(w[0], w[k]) && clear(w[0], w[k])) }
            if (k != null) w = listOf(w[0]) + w.subList(k, w.size)
        }
        if (w.size > 2) {
            val last = w.size - 1
            val k = (1 until last).firstOrNull { k -> (d(w[last], w[k]) <= ATTACH_YD && arc(k, last) > 1.3 * d(w[last], w[k]) + 20.0) ||
                (clear != null && d(w[last], w[k]) <= SHORTCUT_YD && arc(k, last) > 1.6 * d(w[last], w[k]) && clear(w[last], w[k])) }
            if (k != null) w = w.subList(0, k + 1) + listOf(w[last])
        }
        return w
    }
    const val LOOP_YD = 70.0               // a way that comes back this near where it was, the long way round, is a there-and-back
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
            if (target) return if (bad[k]!! <= DANGER_LINK_YD) Link.LOW else Link.DANGER
            for ((o, e) in adj[k] ?: continue) {
                val l = len(e); val nd = d + l
                if (nd < (dist[o] ?: Double.MAX_VALUE)) { dist[o] = nd; bad[o] = bad[k]!! + l * e.danger; q.add(nd to o) }
            }
        }
        return Link.NONE
    }
    const val LINK_FROM_YD = 150.0         // the way back counts from a place this far from the crossing
    const val DANGER_LINK_YD = 150.0       // at most this much of it among hostile mobs is a low-danger way back
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
                    var cur = x.node
                    while (true) {
                        val at = edges.filter { it.a == cur || it.b == cur }
                        if (at.size != 1) break
                        val e = at[0]; edges.remove(e)
                        cur = if (e.a == cur) e.b else e.a
                        if (exits.any { it.node == cur }) break
                    }
                }
                mine == Link.DANGER || far == Link.DANGER ->
                    edges = edges.map { e -> if ((e.a == x.node || e.b == x.node) && e.state == DRAFT) Edge(e.a, e.b, e.kind, AVOID, e.src, e.danger, e.climb) else e }.toMutableList()
            }
        }
        val used = (edges.flatMap { listOf(it.a, it.b) } + exits.map { it.node }).toHashSet()
        val keepNodes = own.nodes.filter { it.id in used || it.kind == HUB || it.kind == FLIGHT || it.kind == DOCK }
        return Net(own.map, own.cont, own.built, keepNodes, edges, exits, own.reviewed)
    }

}
