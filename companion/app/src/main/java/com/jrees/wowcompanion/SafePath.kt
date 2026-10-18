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
    const val BORDER_END_YD = 320.0        // a road ending this near another zone's ground goes on to it (an exit) ...
    const val ROAD_ON_YD = 250.0           // ... where that zone's own road goes on this near (a bridge's far end, a gate's road)        // crossings into one neighbour this near are one exit
    const val OFF_ZONE = 3f                // a spur or a trail through a neighbour's ground costs this much more
    const val DANGER_YD = 40.0             // a hostile creature this near makes a cell dangerous ...
    const val DANGER_COST = 4f             // ... and a dangerous cell costs up to this much more to a spur or a trail
    const val HUB_YD = 110.0               // the player's side's creatures this near each other are one town ...
    const val HUB_MIN = 5                  // ... when there are this many

    class Node(val id: Int, val wx: Double, val wy: Double, val kind: String, val src: String = "auto", val name: String? = null)
    class Edge(val a: Int, val b: Int, val kind: String, val state: String = DRAFT, val src: String = "auto", val danger: Float = 0f)
    /** [node] leads into uiMap [toMap] ([how]: walk / gate / tunnel / boat / zeppelin / tram / portal), named [name] */
    class Exit(val node: Int, val toMap: Int, val how: String, val name: String)

    /** one zone's network: world yards on the game's own axes (x north, y west), as the routes, the danger grid and CrossZone use them */
    class Net(val map: Int, val cont: Int, val built: String, val nodes: List<Node>, val edges: List<Edge>, val exits: List<Exit>,
              val reviewed: Boolean = false) {
        fun toJson(): JSONObject {
            fun r1(v: Double) = (v * 10).roundToInt() / 10.0
            return JSONObject().put("v", 1).put("map", map).put("cont", cont).put("built", built).put("reviewed", reviewed)
                .put("nodes", JSONArray(nodes.map { n -> JSONArray(listOfNotNull(n.id, r1(n.wx), r1(n.wy), n.kind, n.src, n.name)) }))
                .put("edges", JSONArray(edges.map { e -> JSONArray(listOf(e.a, e.b, e.kind, e.state, e.src, (e.danger * 100).roundToInt() / 100.0)) }))
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
                        Edge(a.optInt(0), a.optInt(1), a.optString(2), a.optString(3, DRAFT), a.optString(4, "auto"), a.optDouble(5, 0.0).toFloat()) } },
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
                val danger: ((Double, Double) -> Float)? = null, val capitals: Set<Int> = CityExits.CITIES)

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
        val cost = FloatArray(n) { k -> inp.cost(wxOf(k), wyOf(k)) }
        val danger = FloatArray(n) { k -> inp.danger?.invoke(wxOf(k), wyOf(k)) ?: 0f }
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
            roadAll = m.copyOf()
            close(m); dropSmall(m); thin(m); prune(m)
            chains(m)
            borderEnds()
            mergeExits()
            val roadless = edges.isEmpty()
            for (f in inp.fixed) fixed(f)
            // a zone with no road of its own (a capital: its streets are buildings, its ground plain dirt): its points joined by trails,
            // its ways out found from them
            if (roadless) { linkPoints(); waysOut(); mergeExits() }
            joinPieces()
            val out = edges.map { e ->
                // its danger: the share of its length that passes hostile creatures (two or more near)
                val d = if (e.cells.isEmpty()) 0f else e.cells.count { danger[it] >= 0.6f }.toFloat() / e.cells.size
                Edge(e.a, e.b, e.kind, danger = d)
            }
            // only the nodes something uses (the merged exits' old ones go), numbered again
            val used = (out.flatMap { listOf(it.a, it.b) } + exits.map { it.node } + fixedIds).toSortedSet()
            val renum = HashMap<Int, Int>()
            val ns = used.mapIndexed { i, id -> renum[id] = i; nodes[id].let { Node(i, it.wx, it.wy, it.kind, it.src, it.name) } }
            return Net(inp.map, inp.cont, built, ns, out.map { Edge(renum[it.a]!!, renum[it.b]!!, it.kind, it.state, it.src, it.danger) },
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
                    val c = (step * cost[o] * (1f + DANGER_COST * danger[o]) * (if (zone[o] == own) 1f else OFF_ZONE)).toFloat()
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
            if (roadCells == 0) return@run "no learned roads yet"
            val tg = Terrain.of(map) ?: return@run "no terrain yet"
            val zg = zones.get(map) ?: return@run "no zone ground yet"
            val ar = areas() ?: return@run "no areas table yet"
            val built = "roads $roadCells, ${zg.nx}x${zg.ny} chunks"
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
            val friendly = spawns.filter { it.hostile != mine && it.hostile != 3 }.map { doubleArrayOf(t - it.y * ay, l - it.x * ax) }
            val net = draft(Input(map, cont, t, b, l, r, { wx, wy -> Roads.isRoad(map, wx, wy) },
                { wx, wy -> Terrain.factor(tg.at(wx, wy)) }, { wx, wy -> ar.uiMap(zg.areaAt(wx, wy)) },
                fixed + hubs(friendly) { x, y -> PlaceNames.nameAt(x, y, ar.rows) { a, b2 -> zg.areaAt(a, b2) } }, ::nameOf, dangerOf(hostile)), built)
            ArtHarvest.file("safepath/$map.draft.json").also { it.parentFile?.mkdirs() }.writeText(net.toJson().toString())
            drafts.drop(map)
            "drafted: ${net.nodes.size} nodes, ${net.edges.size} edges (${net.edges.count { it.kind == TRAIL }} trails), " +
                "${net.exits.size} exits (${net.exits.joinToString { it.name.ifEmpty { it.toMap.toString() } }})"
        }) { said -> android.util.Log.i("WCMAP", "safepath $map: $said") }
    }

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
        for (ride in TransportRoutes.fromJson(o)) {
            for (end in 0..1) {
                val c = if (end == 0) ride.ca else ride.cb
                val p = if (end == 0) ride.a else ride.b
                val x = p[0].toDouble(); val y = p[1].toDouble()
                if (c != cont || !inRect(x, y) || out.any { it.kind == DOCK && hypot(it.wx - x, it.wy - y) < 30.0 && it.how == (if (ride.air) "zeppelin" else "boat") }) continue
                val other = if (end == 0) ride.nb else ride.na
                out.add(Fixed(x, y, DOCK, (if (ride.air) "Zeppelin to " else "Boat to ") + other, 0, if (ride.air) "zeppelin" else "boat"))
            }
        }
        return out
    }

    /** the towns: where this many of the player's side's creatures stand this near each other (QuestieDB's spawns) */
    fun hubs(friendly: List<DoubleArray>, nameAt: (Double, Double) -> String? = { _, _ -> null }): List<Fixed> {
        val left = friendly.toMutableList()
        val out = ArrayList<Fixed>()
        while (left.isNotEmpty()) {
            // the densest spot first
            val best = left.maxByOrNull { p -> left.count { hypot(it[0] - p[0], it[1] - p[1]) < HUB_YD } } ?: break
            val group = left.filter { hypot(it[0] - best[0], it[1] - best[1]) < HUB_YD }
            if (group.size < HUB_MIN) break
            val x = group.map { it[0] }.average(); val y = group.map { it[1] }.average()
            out.add(Fixed(x, y, HUB, nameAt(x, y) ?: "Town"))
            left.removeAll { hypot(it[0] - best[0], it[1] - best[1]) < HUB_YD * 2 }
        }
        return out
    }

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
}
