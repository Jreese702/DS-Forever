import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedNet
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * THE SHIPPED NETWORK'S EXTRA WAYS (NET1 v2; the user, 2026-10-19: "Ship our path network" - the device routes on the shipped net
 * WITHOUT the lattice, so the places goals go must be ON the net). Three kinds, each WALKED on the lattice (Climb.route: the terrain's
 * and the floor layers' own steps, deep water a wall), every segment re-checked by Climb.walkable, never a straight guess:
 *
 *  1. FLOOR ways ([FLOOR]): the walkable floor layers that matter (Climb.floorLayers' pieces of [MIN_PIECE] vertices or more: a bridge's
 *     deck, a tunnel's floor, a pier, a walkway; a capital's leveled streets), joined between their openings (where a piece meets the
 *     ground by a walkable step) and the places on them (QuestieDB's NPC spots, the spec's goals there) - each point with its floor
 *     layer's index (ShippedNet.Way.layers: 0 the ground, 1.. the pieces from the lowest up). A raised city on its OWN floor (Dalaran:
 *     FloorCity.floorWays, T1's streets) is not walked here; [cityCover] reports how near its places are to those streets.
 *  2. CAVE JOINS ([JOIN]): each cave or tunnel mouth of the net joined by a walked leg to its NEAREST GROUND WAY only (the ground net's
 *     own piece joins are ShippedNet.joins / helper T2's). The draft's straight mouth spur (SafePath.withCaves, src "cave", never
 *     checked) of a mouth joined here is dropped where its straight line crosses a wall.
 *  3. FAR-GOAL SPURS ([SPUR]): the places goals usually go - camps (this faction's hostile spawns at their thickest), cave mouths,
 *     flight masters and docks, the spec's own goals - more than [FAR_YD] from every way: a walked spur from the nearest way.
 *
 * OUR computed lines only (x, y and a layer index a point), never heights, WMO geometry, textures or file ids.
 *
 * USE (the exporter, RenderSafePath's SAFEPATH_EXPORT block; T1): after the floor streets and ShippedNet.joins,
 *   val plan = NetFloors.ways(NetFloors.Input(netC, floorNodes, extras, cg, deep, NetFloors.Layers(layers, cg), standable,
 *       { x, y -> danger(x, y) / DangerGrid.CAP }, NetFloors.places(...)))
 *   val (net2, nodes2, extras2) = NetFloors.apply(plan, netC, floorNodes, extras, cg, deep)
 *   ShippedNet.section(net2, ..., extraNodes = nodes2, extras = extras2)
 */
object NetFloors {
    const val FLOOR = "floor"; const val JOIN = "join"; const val SPUR = "spur"
    /** a capital's NPC spot (Thunder Bluff's mesas), a place joined as a floor's (within [FLOOR_NEAR_YD]) */
    const val CITY = "city"
    /** a goal place this far from every way gets a spur (the device's attach leg is 150-200 yd at most) */
    const val FAR_YD = 150.0
    /** a place on a floor this far from every way gets a floor way (the device's attach leg can't walk a floor's layers) */
    const val FLOOR_NEAR_YD = 20.0
    /** a cave mouth this near a ground way is joined already */
    const val JOINED_YD = 3.0
    /** no walked way longer than this */
    const val MAX_LEG_YD = 1500.0
    /** a floor piece this small (lattice vertices, about 17 sq yd each) is no floor that matters (a crate's top, a step) */
    const val MIN_PIECE = 30
    /** places this near are one (an inn's NPCs) */
    const val PLACE_GAP_YD = 20.0
    /** openings of one piece this near are one */
    const val OPENING_GAP_YD = 25.0
    /** the danger read every few yards along a way */
    const val DANGER_SAMPLE_YD = 4.0
    /** a camp: this many of this faction's hostile spawns within [CAMP_YD] of one, camps [CAMP_GAP_YD] apart */
    const val CAMP_MIN = 5; const val CAMP_YD = 60.0; const val CAMP_GAP_YD = 120.0

    /** a place a goal goes: [why] camp / mouth / flight / dock / goal / npc / opening; [floor] its floor layer (0 the ground) */
    class Place(val name: String, val x: Double, val y: Double, val why: String, val floor: Int = 0)

    /**
     * one extra way: [kind] FLOOR / JOIN / SPUR; [pts] world (x, y) from its start (the place, the mouth) to its end on the net;
     * [layers] each point's floor layer (0 the ground); [yd] its walked length; [danger] this faction's share of hostile mobs along it
     * (0..1, as SafePath.Edge.danger); [layer] the floor it is most on (0 none)
     */
    class Way(val kind: String, val name: String, val pts: List<DoubleArray>, val layers: IntArray, val yd: Double, val danger: Float) {
        val layer: Int get() = layers.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        /** NET1 v2 flags: a floor way and a cave join are walked joins on a trail (F_JOIN | F_TRAIL), a far-goal spur F_SPUR; all carry pts */
        val flags: Int get() = when (kind) { SPUR -> ShippedNet.F_SPUR; else -> ShippedNet.F_JOIN or ShippedNet.F_TRAIL }
        val edgeKind: String get() = if (kind == SPUR) SafePath.SPUR else SafePath.TRAIL
        val src: String get() = when (kind) { FLOOR -> "floor"; else -> "join" }
    }

    /**
     * the inputs: [net] the zone's network (the draft with the caves, walls dropped: RenderSafePath's netC); [extraNodes] / [extras]
     * the exporter's own (a city's streets, ShippedNet.joins'); [cg] the lattice with its floor layers, [deep] its deep water; [layers]
     * the floor layers' pieces; [standable] a spot one can stand on; [danger] this faction's danger 0..1 at a spot; [places] ([places])
     */
    class Input(val net: SafePath.Net, val extraNodes: List<SafePath.Node>, val extras: List<ShippedNet.Extra>, val cg: Climb.Grid,
                val deep: (Double, Double) -> Boolean, val layers: Layers, val standable: (Double, Double) -> Boolean,
                val danger: (Double, Double) -> Float, val places: List<Place>, val wayOk: (List<DoubleArray>) -> Boolean = { true },
                val log: (String) -> Unit = {})

    /** EDGE_UP (py/segcheck.py): a step on or off a floor's edge higher than this is a wall */
    const val EDGE_UP_YD = 2.0
    /**
     * THE SURFACES A WALKER IS ON, as py/segcheck.py's line_walk keeps them (the gate's check; Climb.walkable takes the layer's vertices,
     * segcheck the floors' own triangles beside them - a stair's riser, a ledge's drop): along the way every half yard, the floors
     * there ([floors]: each deck's height at the spot, a capital's street boxes not) where the floor layer ([layers], vkey -> height:
     * straight between a cell's four vertices when all four are on it) or a floor's edge cell is, else the terrain ([ground]); a spot the
     * lattice levels ([level]: a covered hole, a street) keeps no height. The walker keeps every surface within [EDGE_UP_YD] of one it
     * was on; none so is a wall. Returns whether the whole way passes.
     */
    class Surfaces(private val layers: Map<Long, Float>, private val floors: (Double, Double) -> DoubleArray,
                   private val ground: (Double, Double) -> Float, private val level: (Double, Double) -> Boolean) {
        // (the exact frame, as py/heights.py's: a line along a lattice row sits on the cells' edge, and MapHarvest.ADT_YD's rounding
        // flipped which cell it read - Elwynn's harbour W48)
        private val S = 1600.0 / 3.0 / 128.0; private val ORG = 32 * 1600.0 / 3.0
        var trace: ((String) -> Unit)? = null
        fun ok(pts: List<DoubleArray>): Boolean {
            var zs: List<Double>? = null
            for ((a, b) in pts.zipWithNext()) {
                val n = max(1, (hypot(b[0] - a[0], b[1] - a[1]) / 0.5).toInt())
                for (q in 0..n) {
                    val x = a[0] + (b[0] - a[0]) * q / n; val y = a[1] + (b[1] - a[1]) * q / n
                    val gx = (ORG - x) / S; val gy = (ORG - y) / S; val r0 = Math.floor(gx).toInt(); val c0 = Math.floor(gy).toInt()
                    val fr = gx - r0; val fc = gy - c0
                    val v = listOf(layers[Climb.vkey(r0, c0)], layers[Climb.vkey(r0, c0 + 1)], layers[Climb.vkey(r0 + 1, c0)], layers[Climb.vkey(r0 + 1, c0 + 1)])
                    var fl: Double? = if (v.all { it != null }) (v[0]!! * (1 - fc) + v[1]!! * fc) * (1 - fr) + (v[2]!! * (1 - fc) + v[3]!! * fc) * fr else null
                    val edge = v.any { it != null }
                    val fs = if (fl != null || edge) floors(x, y) else DoubleArray(0)
                    if (fl != null && fs.isEmpty()) fl = null
                    if (fl == null && fs.isEmpty() && level(x, y)) { zs = null; continue }
                    val g = ground(x, y).toDouble()
                    val cand = fs.toList() + listOf(fl ?: g)
                    val prev = zs
                    val live = if (prev == null) cand else cand.filter { c -> prev.any { Math.abs(c - it) <= EDGE_UP_YD } }
                    trace?.invoke("SURF ${"%.1f".format(x)},${"%.1f".format(y)} fl $fl edge $edge fs ${fs.map { "%.1f".format(it) }} g ${"%.1f".format(g)} live ${live.map { "%.1f".format(it) }}")
                    if (live.isEmpty()) return false
                    zs = live.filter { !it.isNaN() }.ifEmpty { null }
                }
            }
            return true
        }
    }

    /** where a way ends: on a net edge (its index, the point), on an earlier extra way of ours (its index, segment, point), or at a node */
    class End(val edge: Int = -1, val mine: Int = -1, val seg: Int = -1, val node: Int = -1, val p: DoubleArray)

    class Plan(val ways: List<Way>, val ends: List<End>, val dropped: List<String>, val unreached: List<String>) {
        fun count(kind: String) = ways.count { it.kind == kind }
        fun yd(kind: String? = null) = ways.filter { kind == null || it.kind == kind }.sumOf { it.yd }
    }

    // ---------------------------------------------------------------- the ways' index (the nearest way to a spot)

    /** a segment of a way: [target] a way to walk onto (else counted only as "near a way"); [mouth] a cave's or a lift's mouth node (a
     *  target for a floor's or a city's place only: a lift's top on a mesa); [edge] / [mine] / [seg] / [node] where a walk onto it ends */
    private class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double, val target: Boolean, val edge: Int = -1, val mine: Int = -1,
                      val seg: Int = -1, val node: Int = -1, val mouth: Boolean = false)
    private class Index {
        val cell = 64L
        val grid = HashMap<Long, ArrayList<Seg>>()
        fun key(i: Long, j: Long) = (i shl 32) xor (j and 0xffffffffL)
        fun add(s: Seg) {
            val i0 = Math.floorDiv(Math.floor(min(s.ax, s.bx)).toLong(), cell); val i1 = Math.floorDiv(Math.floor(max(s.ax, s.bx)).toLong(), cell)
            val j0 = Math.floorDiv(Math.floor(min(s.ay, s.by)).toLong(), cell); val j1 = Math.floorDiv(Math.floor(max(s.ay, s.by)).toLong(), cell)
            for (i in i0..i1) for (j in j0..j1) grid.getOrPut(key(i, j)) { ArrayList() }.add(s)
        }
        /** the segments within [r] of (x, y) passing [ok], nearest first: (distance, the nearest point on it, the segment) */
        fun near(x: Double, y: Double, r: Double, ok: (Seg) -> Boolean): List<Triple<Double, DoubleArray, Seg>> {
            val out = ArrayList<Triple<Double, DoubleArray, Seg>>(); val seen = HashSet<Seg>()
            val n = (r / cell).toLong() + 1; val ci = Math.floorDiv(Math.floor(x).toLong(), cell); val cj = Math.floorDiv(Math.floor(y).toLong(), cell)
            for (i in ci - n..ci + n) for (j in cj - n..cj + n) grid[key(i, j)]?.forEach { s ->
                if (!seen.add(s) || !ok(s)) return@forEach
                val p = project(x, y, s.ax, s.ay, s.bx, s.by); val d = hypot(p[0] - x, p[1] - y); if (d <= r) out.add(Triple(d, p, s)) }
            return out.sortedBy { it.first }
        }
        fun nearest(x: Double, y: Double, r: Double, ok: (Seg) -> Boolean = { true }) = near(x, y, r, ok).firstOrNull()
    }
    private fun project(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): DoubleArray {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-9) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return doubleArrayOf(ax + dx * t, ay + dy * t)
    }
    private fun tAlong(p: DoubleArray, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        return if (l2 < 1e-9) 0.0 else (((p[0] - ax) * dx + (p[1] - ay) * dy) / l2).coerceIn(0.0, 1.0)
    }
    private fun len(pts: List<DoubleArray>) = pts.zipWithNext { a, b -> hypot(b[0] - a[0], b[1] - a[1]) }.sum()

    // ---------------------------------------------------------------- the ways

    fun ways(inp: Input): Plan {
        val net = inp.net; val cg = inp.cg
        val byId = HashMap<Int, SafePath.Node>(); for (n in net.nodes) byId[n.id] = n; for (n in inp.extraNodes) byId[n.id] = n
        val idx = Index()
        for ((i, e) in net.edges.withIndex()) {
            if (e.state == SafePath.REMOVED) continue
            val a = byId[e.a] ?: continue; val b = byId[e.b] ?: continue
            val cave = e.kind == SafePath.CAVE || e.kind == SafePath.LIFT
            // a target to walk onto: a ground way whose straight line is the way (not a rewalk's lip: its ends only; not the draft's
            // unchecked straight spur from a mouth)
            val caveSpur = e.src == "cave" && e.kind == SafePath.SPUR
            idx.add(Seg(a.wx, a.wy, b.wx, b.wy, !cave && !caveSpur && e.state != SafePath.REWALK, edge = i))
        }
        // the exporter's extras (streets, joins): near-a-way only, walked onto at their nodes
        for (x in inp.extras) for ((u, v) in x.pts.zipWithNext()) idx.add(Seg(u[0], u[1], v[0], v[1], false))
        val live = HashSet<Int>().also { s -> for (e in net.edges) if (e.state != SafePath.REMOVED) { s.add(e.a); s.add(e.b) }; for (x in inp.extras) { s.add(x.a); s.add(x.b) } }
        for (n in byId.values) if (n.id in live && n.kind != SafePath.CAVE && n.kind != SafePath.LIFT && n.kind != SafePath.MOUTH)
            idx.add(Seg(n.wx, n.wy, n.wx, n.wy, true, node = n.id))
        for (n in net.nodes) if (n.id in live && n.kind == SafePath.MOUTH) idx.add(Seg(n.wx, n.wy, n.wx, n.wy, false, node = n.id, mouth = true))
        val out = ArrayList<Way>(); val ends = ArrayList<End>(); val dropped = ArrayList<String>(); val unreached = ArrayList<String>()

        fun stand(x: Double, y: Double, r: Double = 15.0): DoubleArray? {
            if (inp.standable(x, y) && !inp.deep(x, y)) return doubleArrayOf(x, y)
            val c = cg.at(x, y); val n = (r / Climb.STEP).toInt() + 1; var best: DoubleArray? = null; var bd = Double.MAX_VALUE
            for (dj in -n..n) for (di in -n..n) { val i = c[0] + di; val j = c[1] + dj
                if (i !in 0 until cg.nx || j !in 0 until cg.ny) continue
                val px = cg.wx(j); val py = cg.wy(i); val d = hypot(px - x, py - y)
                if (d < bd && d <= r && inp.standable(px, py) && !inp.deep(px, py)) { bd = d; best = doubleArrayOf(px, py) } }
            return best
        }
        /** the walk from [from] onto the nearest target way (of the [tries] nearest distinct ones, the shortest walk that arrives) */
        /** why the last [walkOnto] found none (for the unreached lines) */
        var whyNot = ""
        /** the walk from [from] onto the nearest target way: of the [tries] nearest distinct ones (40 yd apart), the shortest walk that
         *  arrives on it; then, failing those, the nearest of the net's nodes the walk reaches (a way's straight middle may stand on a
         *  lip the leg can't hop onto; its ends are walked) */
        fun walkOnto(from: DoubleArray, tries: Int = 4, mouths: Boolean = false): Pair<List<DoubleArray>, Seg>? {
            val onto: (Seg) -> Boolean = { it.target || (mouths && it.mouth) }
            val why = ArrayList<String>()
            fun pick(ok: (Seg) -> Boolean, n: Int): List<Triple<Double, DoubleArray, Seg>> {
                val picked = ArrayList<Triple<Double, DoubleArray, Seg>>()
                for (c in idx.near(from[0], from[1], MAX_LEG_YD, ok)) { if (picked.size >= n) break
                    if (picked.none { hypot(it.second[0] - c.second[0], it.second[1] - c.second[1]) < 40.0 }) picked.add(c) }
                return picked
            }
            fun tryAll(picked: List<Triple<Double, DoubleArray, Seg>>): Pair<List<DoubleArray>, Seg>? {
                var best: Pair<List<DoubleArray>, Seg>? = null; var bl = Double.MAX_VALUE
                for ((d, p, s) in picked) {
                    if (d >= bl) break                   // no walk is shorter than its straight line
                    val leg = Climb.route(cg, from, p, water = inp.deep)
                    if (leg == null) { why.add("${d.toInt()} yd: off the lattice"); continue }
                    val short = hypot(leg.pts.last()[0] - p[0], leg.pts.last()[1] - p[1])
                    if (!leg.reached || short > 0.5) { why.add("${d.toInt()} yd: ${if (leg.reached) "ends ${short.toInt()} yd short" else "not reached"}"); continue }
                    // (a hundredth of a yard, as NET1 v2 ships a way's points and the checks' file holds them: the checks see the points shipped)
                    val pts = leg.pts.map { doubleArrayOf(r2(it[0]), r2(it[1])) }
                    if (pts.zipWithNext().any { (u, v) -> !Climb.walkable(cg, u, v, inp.deep) }) { why.add("${d.toInt()} yd: a wall once rounded"); continue }
                    if (!inp.wayOk(pts)) { why.add("${d.toInt()} yd: off a floor's edge"); continue }
                    val l = len(pts); if (l > MAX_LEG_YD) { why.add("${d.toInt()} yd: walk ${l.toInt()} yd"); continue }
                    if (l < bl) { bl = l; best = pts to s }
                }
                return best
            }
            var r = tryAll(pick(onto, tries)) ?: tryAll(pick({ onto(it) && it.node >= 0 }, tries))
            // the walk's last bend (a ridge's end, a cliff's corner) has a nearer way than the one walked to: onto that one from the start
            // (the nearest straight from the start is not the nearest round the corner), as long as it is shorter
            r?.let { (pts, _) -> if (pts.size >= 3) {
                val bend = pts[pts.size - 2]; val lastLeg = hypot(pts.last()[0] - bend[0], pts.last()[1] - bend[1])
                val c = idx.nearest(bend[0], bend[1], lastLeg, onto)
                if (c != null && hypot(c.second[0] - pts.last()[0], c.second[1] - pts.last()[1]) > 5.0) {
                    val alt = tryAll(listOf(Triple(0.0, c.second, c.third)))
                    if (alt != null && len(alt.first) < len(pts)) r = alt } } }
            whyNot = if (r == null) "tried ${why.joinToString("; ").ifEmpty { "no way within ${MAX_LEG_YD.toInt()} yd" }}" else ""
            return r
        }
        fun add(kind: String, name: String, pts: List<DoubleArray>, end: Seg, quiet: Boolean = false): Boolean {
            if (pts.size < 2) return false
            // every step re-checked as the device and the gate walk it: no wall, no deep water
            val bad = pts.zipWithNext().indexOfFirst { (a, b) -> !Climb.walkable(cg, a, b, inp.deep) }
            if (bad >= 0) { if (!quiet) dropped.add("$kind $name: segment $bad crosses a wall or deep water"); return false }
            if (!inp.wayOk(pts)) { if (!quiet) dropped.add("$kind $name: off a floor's edge (the surfaces)"); return false }
            var dsum = 0.0; var dn = 0
            for ((a, b) in pts.zipWithNext()) { val n = max(1, (hypot(b[0] - a[0], b[1] - a[1]) / DANGER_SAMPLE_YD).toInt())
                for (q in 0 until n) { dsum += inp.danger(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n); dn++ } }
            val ls = IntArray(pts.size) { inp.layers.at(pts[it][0], pts[it][1]) }
            // a spur mostly on a floor is a floor way
            val k = if (kind == SPUR && ls.count { it > 0 } * 2 >= ls.size) FLOOR else kind
            val wi = out.size
            out.add(Way(k, name, pts, ls, len(pts), if (dn == 0) 0f else (dsum / dn).toFloat().coerceIn(0f, 1f)))
            ends.add(End(edge = end.edge, mine = end.mine, seg = end.seg, node = end.node, p = pts.last()))
            for ((q, ab) in pts.zipWithNext().withIndex()) idx.add(Seg(ab.first[0], ab.first[1], ab.second[0], ab.second[1], true, mine = wi, seg = q))
            return true
        }
        fun dist(p: Place, targetOnly: Boolean = false, mouths: Boolean = false) =
            idx.nearest(p.x, p.y, MAX_LEG_YD) { !targetOnly || it.target || (mouths && it.mouth) }?.first ?: Double.MAX_VALUE

        // 2. THE CAVES TO THE GROUND: each mouth of the net onto its nearest ground way (one join a mouth; a cave's mouths a few yards
        // apart are one opening: the nearest joined first, the next then finds that join near)
        val mouths = net.nodes.filter { it.kind == SafePath.MOUTH && it.id in live }.map { Place("mouth", it.wx, it.wy, "mouth") }
        for (m in mouths.sortedBy { dist(it, true) }) {
            if (dist(m, true) <= JOINED_YD) continue
            val from = stand(m.x, m.y, 8.0) ?: run { unreached.add("mouth ${m.x.toInt()},${m.y.toInt()}: no standing ground within 8 yd"); null } ?: continue
            val w = walkOnto(from) ?: run { unreached.add("mouth ${m.x.toInt()},${m.y.toInt()}: no walk onto a ground way ($whyNot)"); null } ?: continue
            val head = doubleArrayOf(r2(m.x), r2(m.y))
            // (from the mouth itself where the step from it onto the standing ground walks; else from that ground)
            val nm = "mouth ${m.x.toInt()},${m.y.toInt()}"
            if (hypot(head[0] - w.first[0][0], head[1] - w.first[0][1]) <= 0.5 || !add(JOIN, nm, listOf(head) + w.first, w.second, quiet = true)) add(JOIN, nm, w.first, w.second)
        }
        // 1. THE FLOORS: the places on a floor layer, the layers' openings and a capital's NPC spots (Thunder Bluff's mesas: their ways
        // onto the lifts' tops), each walked onto the nearest way or mouth (the ways already made count: a floor's tree grows from its
        // first way)
        for (p in inp.places.filter { it.floor > 0 || it.why == "opening" || it.why == CITY }.sortedBy { dist(it) }) {
            if (dist(p, true, mouths = true) <= FLOOR_NEAR_YD) continue
            val from = stand(p.x, p.y, 6.0) ?: continue
            val w = walkOnto(from, mouths = true) ?: run { unreached.add("${p.why} ${p.name} at ${p.x.toInt()},${p.y.toInt()} (floor ${p.floor}): no walk onto a way ($whyNot)"); null } ?: continue
            add(FLOOR, "${p.why} ${p.name}", w.first, w.second)
        }
        // 3. THE FAR GOALS: each place more than FAR_YD from every way (a cave's ways count: a camp in a cave is reached by its way)
        for (p in inp.places.filter { it.floor == 0 && it.why != "opening" && it.why != CITY }.sortedBy { dist(it) }) {
            if (dist(p) <= FAR_YD) continue
            val from = stand(p.x, p.y) ?: run { unreached.add("${p.why} ${p.name} at ${p.x.toInt()},${p.y.toInt()}: no standing ground within 15 yd"); null } ?: continue
            val w = walkOnto(from) ?: run { unreached.add("${p.why} ${p.name} at ${p.x.toInt()},${p.y.toInt()}: no walk onto a way ($whyNot)"); null } ?: continue
            add(SPUR, "${p.why} ${p.name}", w.first, w.second)
        }
        return Plan(out, ends, dropped, unreached)
    }

    // ---------------------------------------------------------------- the ways into the section's net and extras

    /**
     * [plan]'s ways into the section: [net] with the net ways they end on split there (each half keeps the way's kind, state, source,
     * danger and climb), [extraNodes] with the ways' own nodes (POINT, src "join" / "floor"), [extras] with each way as one Extra from
     * its start to its end (cut where a later way ends on it); and the draft's straight mouth spurs (src "cave") of the mouths joined here
     * that cross a wall ([cg], [deep]) dropped. Returns (net, extraNodes, extras).
     */
    fun apply(plan: Plan, net: SafePath.Net, extraNodes: List<SafePath.Node>, extras: List<ShippedNet.Extra>, cg: Climb.Grid,
              deep: (Double, Double) -> Boolean): Triple<SafePath.Net, List<SafePath.Node>, List<ShippedNet.Extra>> {
        var next = max(net.nodes.maxOfOrNull { it.id } ?: 0, extraNodes.maxOfOrNull { it.id } ?: 0) + 1
        val newNodes = ArrayList<SafePath.Node>()
        val byId = HashMap<Int, SafePath.Node>(); for (n in net.nodes + extraNodes) byId[n.id] = n
        fun newNode(p: DoubleArray, src: String): Int { val n = SafePath.Node(next++, p[0], p[1], SafePath.POINT, src); newNodes.add(n); byId[n.id] = n; return n.id }
        // the net's edges cut where the ways end on them
        val cutsE = HashMap<Int, ArrayList<Pair<Double, Int>>>()            // edge -> (t along it, the node there)
        val cutsW = HashMap<Int, ArrayList<Triple<Int, Double, Int>>>()     // our way -> (segment, t, node)
        // each way's start: (a join that starts on the standing ground beside its mouth, the step off the cave's floor no lattice walk:
        // that step is the cave's own, from the mouth's node - a cave way's end, walked on its floor) the mouth's node, else a new one
        val mouthOf = plan.ways.map { w -> if (w.kind == JOIN) net.nodes.filter { it.kind == SafePath.MOUTH && hypot(it.wx - w.pts[0][0], it.wy - w.pts[0][1]) < 8.5 }
            .minByOrNull { hypot(it.wx - w.pts[0][0], it.wy - w.pts[0][1]) } else null }
        val startIds = plan.ways.mapIndexed { wi, w -> mouthOf[wi]?.id ?: newNode(w.pts[0], w.src) }
        val endNode = IntArray(plan.ways.size)
        for ((wi, e) in plan.ends.withIndex()) {
            val w = plan.ways[wi]; val src = w.src
            endNode[wi] = when {
                e.node >= 0 -> e.node
                e.edge >= 0 -> { val ed = net.edges[e.edge]; val a = byId[ed.a]!!; val b = byId[ed.b]!!
                    val t = tAlong(e.p, a.wx, a.wy, b.wx, b.wy)
                    when { hypot(a.wx - e.p[0], a.wy - e.p[1]) < 0.5 -> a.id; hypot(b.wx - e.p[0], b.wy - e.p[1]) < 0.5 -> b.id
                        else -> cutsE.getOrPut(e.edge) { ArrayList() }.let { l -> l.firstOrNull { Math.abs(it.first - t) * hypot(a.wx - b.wx, a.wy - b.wy) < 0.5 }?.second
                            ?: newNode(e.p, src).also { l.add(t to it) } } } }
                hypot(plan.ways[e.mine].pts[0][0] - e.p[0], plan.ways[e.mine].pts[0][1] - e.p[1]) < 0.5 -> startIds[e.mine]
                hypot(plan.ways[e.mine].pts.last()[0] - e.p[0], plan.ways[e.mine].pts.last()[1] - e.p[1]) < 0.5 -> endNode[e.mine]
                else -> { val ow = plan.ways[e.mine]; val a = ow.pts[e.seg]; val b = ow.pts[e.seg + 1]
                    val t = tAlong(e.p, a[0], a[1], b[0], b[1])
                    cutsW.getOrPut(e.mine) { ArrayList() }.let { l -> l.firstOrNull { it.first == e.seg && Math.abs(it.second - t) * hypot(a[0] - b[0], a[1] - b[1]) < 0.5 }?.third
                        ?: newNode(e.p, ow.src).also { l.add(Triple(e.seg, t, it)) } } }
            }
        }
        val edges = ArrayList<SafePath.Edge>()
        for ((i, e) in net.edges.withIndex()) {
            val cs = cutsE[i]
            if (cs == null) { edges.add(e); continue }
            val chain = listOf(e.a) + cs.sortedBy { it.first }.map { it.second } + listOf(e.b)
            for ((u, v) in chain.zipWithNext()) edges.add(SafePath.Edge(u, v, e.kind, e.state, e.src, e.danger, e.climb))
        }
        // the mouths joined here: their straight draft spurs that cross a wall go
        val joined = plan.ways.filter { it.kind == JOIN }.mapNotNull { w -> net.nodes.firstOrNull { it.kind == SafePath.MOUTH && hypot(it.wx - w.pts[0][0], it.wy - w.pts[0][1]) < 1.0 }?.id }.toHashSet()
        for ((i, e) in edges.withIndex()) {
            if (e.src != "cave" || e.kind != SafePath.SPUR || e.state == SafePath.REMOVED || (e.a !in joined && e.b !in joined)) continue
            val a = byId[e.a] ?: continue; val b = byId[e.b] ?: continue
            if (!Climb.walkable(cg, doubleArrayOf(a.wx, a.wy), doubleArrayOf(b.wx, b.wy), deep))
                edges[i] = SafePath.Edge(e.a, e.b, e.kind, SafePath.REMOVED, e.src, e.danger, e.climb)
        }
        // our ways as extras, each cut where a later way ends on it
        val outX = ArrayList<ShippedNet.Extra>()
        for ((wi, w) in plan.ways.withIndex()) {
            // its start: the mouth's node (a join from a mouth), else a new node at the place
            val mouth = mouthOf[wi]; val startId = startIds[wi]
            val wpts = if (mouth != null && hypot(mouth.wx - w.pts[0][0], mouth.wy - w.pts[0][1]) >= 0.5) listOf(doubleArrayOf(mouth.wx, mouth.wy)) + w.pts else w.pts
            val wls = if (wpts.size > w.pts.size) intArrayOf(0) + w.layers else w.layers
            val off = wpts.size - w.pts.size
            // the points with the cuts put in: (point, layer, node at it or -1)
            val pts = ArrayList<Triple<DoubleArray, Int, Int>>()
            val cs = cutsW[wi]?.groupBy { it.first + off } ?: emptyMap()
            for (q in wpts.indices) {
                pts.add(Triple(wpts[q], wls[q], if (q == 0) startId else if (q == wpts.size - 1) endNode[wi] else -1))
                if (q < wpts.size - 1) for (c in cs[q].orEmpty().sortedBy { it.second }) {
                    // (a cut at a point itself: that point is the node)
                    when { c.second <= 1e-6 && q > 0 -> pts[pts.size - 1] = Triple(pts.last().first, pts.last().second, c.third)
                        c.second <= 1e-6 -> {}
                        c.second >= 1 - 1e-6 && q + 1 == wpts.size - 1 -> {}
                        else -> pts.add(Triple(byId[c.third]!!.let { doubleArrayOf(it.wx, it.wy) }, if (c.second < 0.5) wls[q] else wls[q + 1], c.third)) }
                }
            }
            var from = 0
            for (q in 1 until pts.size) {
                if (pts[q].third < 0) continue
                val piece = pts.subList(from, q + 1)
                if (piece.size >= 2 && piece.first().third != piece.last().third)
                    outX.add(ShippedNet.Extra(piece.first().third, piece.last().third, w.edgeKind, w.flags, piece.map { doubleArrayOf(it.first[0], it.first[1]) },
                        w.danger, w.src, piece.map { it.second }.toIntArray().takeIf { l -> l.any { it > 0 } }))
                from = q
            }
        }
        return Triple(SafePath.Net(net.map, net.cont, net.built, net.nodes, edges, net.exits, net.reviewed), extraNodes + newNodes, extras + outX)
    }

    // ---------------------------------------------------------------- the places goals go

    /**
     * where goals usually go, for one faction: camps (its [hostile] spawns, world x, y, name: the spawn with the most others within
     * [CAMP_YD], [CAMP_MIN] or more, camps [CAMP_GAP_YD] apart), the [net]'s cave mouths, flight masters and docks, the spec's own
     * [goals], the NPC spots ([npcs]) on a floor layer and a capital's ([cityNpcs]: those in a capital's own rect - Thunder Bluff on
     * its mesas), one a [PLACE_GAP_YD] cluster, and the floor layers' openings
     */
    fun places(net: SafePath.Net, hostile: List<Triple<Double, Double, String>>, npcs: List<Triple<Double, Double, String>>,
               goals: List<Pair<String, DoubleArray>>, layers: Layers, cityNpcs: List<Triple<Double, Double, String>> = emptyList()): List<Place> {
        val out = ArrayList<Place>()
        val left = hostile.toMutableList()
        while (left.isNotEmpty()) {
            val counts = left.map { p -> left.count { hypot(it.first - p.first, it.second - p.second) < CAMP_YD } }
            val k = counts.indices.maxByOrNull { counts[it] } ?: break
            if (counts[k] < CAMP_MIN) break
            val c = left[k]; out.add(Place(c.third, c.first, c.second, "camp", layers.at(c.first, c.second)))
            left.removeAll { hypot(it.first - c.first, it.second - c.second) < CAMP_GAP_YD }
        }
        for (n in net.nodes) when (n.kind) {
            SafePath.MOUTH -> out.add(Place("mouth", n.wx, n.wy, "mouth"))
            SafePath.FLIGHT -> out.add(Place(n.name ?: "flight", n.wx, n.wy, "flight"))
            SafePath.DOCK -> out.add(Place(n.name ?: "dock", n.wx, n.wy, "dock"))
        }
        for ((name, p) in goals) out.add(Place(name, p[0], p[1], "goal", layers.at(p[0], p[1])))
        val kept = ArrayList<Triple<Double, Double, String>>()
        for (n in npcs.filter { layers.at(it.first, it.second) > 0 }) if (kept.none { hypot(it.first - n.first, it.second - n.second) < PLACE_GAP_YD }) kept.add(n)
        for (n in kept) out.add(Place(n.third, n.first, n.second, "npc", layers.at(n.first, n.second)))
        val city = ArrayList<Triple<Double, Double, String>>()
        for (n in cityNpcs) if (layers.at(n.first, n.second) == 0 && city.none { hypot(it.first - n.first, it.second - n.second) < PLACE_GAP_YD }) city.add(n)
        for (n in city) out.add(Place(n.third, n.first, n.second, CITY))
        for (o in layers.openings) out.add(Place("opening", o[0], o[1], "opening", o[2].toInt()))
        return out
    }

    /**
     * the floor layers (Climb.floorLayers' map: vkey -> the floor's height at the vertex) as pieces: each piece of at least [MIN_PIECE]
     * vertices a layer index 1.. (from the lowest piece up, by its mean height; the heights stay here), 0 elsewhere; and each piece's
     * openings (a vertex a walkable step from a ground vertex, one a [OPENING_GAP_YD] cluster): x, y, layer
     */
    class Layers(layers: Map<Long, Float>, cg: Climb.Grid) {
        private val piece = HashMap<Long, Int>()
        val openings = ArrayList<DoubleArray>()
        val sizes = ArrayList<Int>()
        init {
            val seen = HashSet<Long>(); val members = ArrayList<List<Long>>()
            for (k0 in layers.keys) { if (!seen.add(k0)) continue
                val m = ArrayList<Long>(); val st = ArrayDeque<Long>(); st.add(k0)
                while (st.isNotEmpty()) { val k = st.removeLast(); m.add(k); val gr = (k shr 32).toInt(); val gc = k.toInt()
                    for (dr in -1..1) for (dc in -1..1) { val n = Climb.vkey(gr + dr, gc + dc); if (n in layers && seen.add(n)) st.add(n) } }
                members.add(m) }
            val big = members.filter { it.size >= MIN_PIECE }.sortedBy { m -> m.sumOf { layers[it]!!.toDouble() } / m.size }
            for ((i, m) in big.withIndex()) { sizes.add(m.size); for (k in m) piece[k] = i + 1 }
            for ((li, m) in big.withIndex()) {
                val ops = ArrayList<DoubleArray>()
                for (k in m) {
                    val gr = (k shr 32).toInt(); val gc = k.toInt(); val i = gc - cg.gc0; val j = gr - cg.gr0
                    if (i !in 1 until cg.nx - 1 || j !in 1 until cg.ny - 1) continue
                    val open = (-1..1).any { dj -> (-1..1).any { di -> (di != 0 || dj != 0) && Climb.vkey(gr + dj, gc + di) !in layers && cg.step(i, j, di, dj) != Climb.WALL } }
                    if (open) { val x = cg.wx(j); val y = cg.wy(i); if (ops.none { hypot(it[0] - x, it[1] - y) < OPENING_GAP_YD }) ops.add(doubleArrayOf(x, y, (li + 1).toDouble())) }
                }
                openings.addAll(ops)
            }
        }
        fun at(x: Double, y: Double): Int = piece[Climb.vkeyAt(x, y)] ?: 0
        val count get() = sizes.size
    }

    /** a raised city on its own floor (Dalaran, FloorCity.floorWays' streets: [streets] their points): how many of [places] lie within
     *  [FLOOR_NEAR_YD] of a street point, and those that don't (the streets' box mesh should hold them all) */
    fun cityCover(streets: List<DoubleArray>, places: List<Place>): Pair<Int, List<String>> {
        val far = places.filter { p -> streets.none { hypot(it[0] - p.x, it[1] - p.y) <= FLOOR_NEAR_YD } }
        return (places.size - far.size) to far.map { p -> "${p.name} at ${p.x.toInt()},${p.y.toInt()} (${streets.minOfOrNull { hypot(it[0] - p.x, it[1] - p.y) }?.toInt()} yd)" }
    }

    // ---------------------------------------------------------------- the checks' file and the report line

    /** the ways as a levels_routes file's "routes" (letters W1, W2, ...; points [x, y] to 0.01 yd) for py/segcheck.py and py/wetcheck.py, with the
     *  ways' own fields for the review */
    fun routesJson(tag: String, ways: List<Way>): JSONObject = JSONObject().put("zone", tag).put("routes", JSONArray(ways.mapIndexed { i, w ->
        JSONObject().put("letter", "W${i + 1}").put("name", "${w.kind} ${w.name}").put("reached", true).put("kind", w.kind).put("layer", w.layer)
            .put("yd", Math.round(w.yd * 10) / 10.0).put("danger", Math.round(w.danger * 100) / 100.0)
            .put("pts", JSONArray(w.pts.map { p -> JSONArray(listOf(r2(p[0]), r2(p[1]))) })) }))
    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    fun summary(tag: String, p: Plan) = "NETFLOORS $tag: ${p.count(FLOOR)} floor ways ${p.yd(FLOOR).toInt()} yd, ${p.count(JOIN)} cave joins ${p.yd(JOIN).toInt()} yd, " +
        "${p.count(SPUR)} far-goal spurs ${p.yd(SPUR).toInt()} yd; total ${p.yd().toInt()} yd; ${p.dropped.size} dropped (a wall), ${p.unreached.size} not walked onto the net"
}
