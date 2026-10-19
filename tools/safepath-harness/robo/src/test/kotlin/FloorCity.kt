import com.jrees.wowcompanion.Caves
import com.jrees.wowcompanion.WorldBuildings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A CITY WALKED ON ITS OWN FLOORS (the spec's "floorCity"; Dalaran, WoW Forever's floating city over Alterac): no terrain under its
 * streets, so no ground lattice and no cave mouths - the routes are the WMO's own floor (Caves.floor: collision faces no steeper than
 * 45 degrees, welded, stair treads joined), searched by the floor's shortest way. The airship, the glide and the portals are MANUAL
 * LINKS (server-side): each end placed from data where the data has it, else marked "position unknown" and never routed through.
 *
 *   "floorCity": {"fids": [wmo file ids], "places": [{"name", "xy": [x, y], "z"?: height, "group"?: group index, "source"}],
 *                 "links": [{"kind", "name", "from": place, "to"?: place, "toName"?, "toAt"?: [x, y, z], "oneWay", "cost"?, "source"}],
 *                 "routes": [{"name", "from": place, "to": place}]}
 *
 * A harness fixture only (the app holds no game coordinates).
 */
class FloorCity private constructor(private val spec: JSONObject, private val fl: Caves.Floor, private val groupVerts: Map<Int, IntArray>) {
    class Place(val name: String, val v: Int, val off: Double, val source: String, val how: String)

    private val pieceSize: Map<Int, Int> = fl.piece.toList().groupingBy { it }.eachCount()
    private val places = LinkedHashMap<String, Place>()
    /** the in-city links routed through: from vertex -> (to vertex, cost, link name) */
    private val extra = HashMap<Int, ArrayList<Triple<Int, Double, String>>>()
    val links = ArrayList<JSONObject>()
    private val routeExport = ArrayList<JSONObject>()
    private val wayPts = ArrayList<List<DoubleArray>>()

    private fun pt(v: Int) = doubleArrayOf(fl.xs[v], fl.ys[v], fl.zs[v])
    private fun fmt(v: Int) = "%.1f, %.1f, %.1f".format(fl.xs[v], fl.ys[v], fl.zs[v])

    private fun resolve(o: JSONObject): Place? {
        val name = o.getString("name"); val src = o.optString("source")
        val xy = o.optJSONArray("xy")?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) }
        val z = if (o.has("z")) o.getDouble("z") else Double.NaN
        val gi = if (o.has("group")) o.getInt("group") else -1
        if (gi >= 0) {
            val gv = groupVerts[gi]?.takeIf { it.isNotEmpty() } ?: return null.also { println("FLOORCITY place $name: group $gi has no floor") }
            println("FLOORCITY group $gi ($name): ${gv.size} floor vertices on pieces " + gv.groupBy { fl.piece[it] }.entries.sortedByDescending { it.value.size }.take(5)
                .joinToString { (p, vs) -> "#$p (${vs.size} here, ${pieceSize[p]} in all, z ${vs.minOf { fl.zs[it] }.toInt()}..${vs.maxOf { fl.zs[it] }.toInt()})" })
            val v = if (xy != null) gv.minByOrNull { hypot(fl.xs[it] - xy[0], fl.ys[it] - xy[1]) + (if (z.isNaN()) 0.0 else 3 * abs(fl.zs[it] - z)) }!!
            else {
                // the group's main floor: its vertices on the city's own (largest) floor piece when it reaches there (a shop's ground
                // floor: its upper storey and roof are pieces of their own), else on the piece holding most of them; the one nearest their middle
                val city = pieceSize.maxByOrNull { it.value }!!.key
                val main = gv.filter { fl.piece[it] == city }.takeIf { it.isNotEmpty() } ?: gv.groupBy { fl.piece[it] }.maxByOrNull { it.value.size }!!.value
                val cx = main.map { fl.xs[it] }.average(); val cy = main.map { fl.ys[it] }.average(); val cz = main.map { fl.zs[it] }.average()
                main.minByOrNull { hypot(fl.xs[it] - cx, fl.ys[it] - cy) + abs(fl.zs[it] - cz) }!!
            }
            return Place(name, v, xy?.let { hypot(fl.xs[v] - it[0], fl.ys[v] - it[1]) } ?: 0.0, src, "group $gi's floor")
        }
        xy ?: return null
        val v: Int; val how: String
        if (!z.isNaN()) {
            v = (0 until fl.size).filter { hypot(fl.xs[it] - xy[0], fl.ys[it] - xy[1]) < 15.0 }
                .minByOrNull { hypot(fl.xs[it] - xy[0], fl.ys[it] - xy[1]) + 3 * abs(fl.zs[it] - z) } ?: fl.near(xy[0], xy[1])
            how = "the floor nearest it at height ${z.toInt()}"
        } else {
            // a 2D spot (QuestieDB's): of the floor within 6 yd, the one on the largest piece (the streets, not a roof or a ledge)
            val c = (0 until fl.size).filter { hypot(fl.xs[it] - xy[0], fl.ys[it] - xy[1]) < 6.0 }
            v = c.maxWithOrNull(compareBy<Int> { pieceSize[fl.piece[it]] ?: 0 }.thenBy { -hypot(fl.xs[it] - xy[0], fl.ys[it] - xy[1]) }) ?: fl.near(xy[0], xy[1])
            how = if (c.isEmpty()) "NO floor within 6 yd: the nearest" else "the largest floor piece within 6 yd"
        }
        return Place(name, v, hypot(fl.xs[v] - xy[0], fl.ys[v] - xy[1]), src, how)
    }

    private fun init() {
        val big = pieceSize.entries.sortedByDescending { it.value }.take(8)
        println("FLOORCITY floor ${fl.size} vertices, ${pieceSize.size} pieces; largest: " + big.joinToString { (p, n) ->
            val vs = (0 until fl.size).filter { fl.piece[it] == p }
            "#$p $n verts z ${vs.minOf { fl.zs[it] }.toInt()}..${vs.maxOf { fl.zs[it] }.toInt()} mid ${vs.map { fl.xs[it] }.average().toInt()},${vs.map { fl.ys[it] }.average().toInt()}" })
        val ps = spec.optJSONArray("places") ?: JSONArray()
        for (i in 0 until ps.length()) {
            val o = ps.getJSONObject(i); val p = resolve(o) ?: continue
            places[p.name] = p
            println("FLOORCITY place ${p.name}: ${fmt(p.v)} (${p.how}, ${"%.1f".format(p.off)} yd off; piece #${fl.piece[p.v]} of ${pieceSize[fl.piece[p.v]]}) - ${p.source}")
        }
        val ls = spec.optJSONArray("links") ?: JSONArray()
        for (i in 0 until ls.length()) {
            val o = ls.getJSONObject(i)
            val a = places[o.getString("from")]; val b = o.optString("to").takeIf { it.isNotEmpty() }?.let { places[it] }
            val at = o.optJSONArray("toAt")?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2)) }
            val oneWay = o.optBoolean("oneWay", true); val cost = o.optDouble("cost", 15.0)
            val routed = a != null && b != null
            if (routed) {
                extra.getOrPut(a!!.v) { ArrayList() }.add(Triple(b!!.v, cost, o.getString("name")))
                if (!oneWay) extra.getOrPut(b.v) { ArrayList() }.add(Triple(a.v, cost, o.getString("name")))
            }
            val far = when { b != null -> pt(b.v); at != null -> at; else -> null }
            println("LINK ${o.getString("kind")} ${o.getString("name")}: from ${a?.let { fmt(it.v) } ?: "?"} to ${far?.let { "%.1f, %.1f, %.1f".format(it[0], it[1], it[2]) } ?: "POSITION UNKNOWN"}" +
                " (${o.optString("toName", b?.name ?: "")}); ${if (oneWay) "one way" else "both ways"}; ${if (routed) "routed in the city" else "not routed (the far end is off the city's floor or unknown)"} - ${o.optString("source")}")
            if (a != null && far != null) links.add(JSONObject().put("kind", o.getString("kind")).put("pts", JSONArray(listOf(JSONArray(pt(a.v).toList()), JSONArray(far.toList())))))
        }
    }

    /** the floor's shortest way from [a] to [b], the in-city links taken: the vertex chain and the links used, or null */
    private fun search(a: Int, b: Int): Pair<List<Int>, List<String>>? {
        val dist = HashMap<Int, Double>(); val prev = HashMap<Int, Int>(); val via = HashMap<Int, String>()
        val pq = PriorityQueue<DoubleArray>(compareBy { it[0] }); dist[a] = 0.0; pq.add(doubleArrayOf(0.0, a.toDouble()))
        while (pq.isNotEmpty()) {
            val top = pq.poll(); val u = top[1].toInt(); if (top[0] > (dist[u] ?: Double.MAX_VALUE)) continue; if (u == b) break
            fun relax(w: Int, d: Double, link: String?) {
                if (d < (dist[w] ?: Double.MAX_VALUE)) { dist[w] = d; prev[w] = u; if (link != null) via[w] = link else via.remove(w); pq.add(doubleArrayOf(d, w.toDouble())) }
            }
            for (w in fl.adj[u]) relax(w, top[0] + sqrt((fl.xs[w] - fl.xs[u]).let { it * it } + (fl.ys[w] - fl.ys[u]).let { it * it } + (fl.zs[w] - fl.zs[u]).let { it * it }), null)
            extra[u]?.forEach { (w, c, n) -> relax(w, top[0] + c, n) }
        }
        if (!dist.containsKey(b)) return null
        val chain = ArrayList<Int>(); val used = ArrayList<String>(); var c = b
        while (c != a) { chain.add(c); via[c]?.let { used.add(it) }; c = prev[c]!! }
        chain.add(a); chain.reverse(); used.reverse()
        return chain to used
    }

    /** the routes, lettered from [first]: printed, exported (points marked in-cave: walked on the city's floor) */
    fun routes(first: Int): List<JSONObject> {
        val rs = spec.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until rs.length()) {
            val o = rs.getJSONObject(i); val letter = ('A' + first + i).toString()
            val a = places[o.getString("from")]; val b = places[o.getString("to")]
            if (a == null || b == null) { println("GOAL ${o.optString("name")}: from ${a?.name} to ${b?.name} - not found, left out"); continue }
            val r = search(a.v, b.v)
            if (r == null) {
                println("ROUTE2 $letter ${a.name} -> ${b.name}: NOT JOINED on the floor (pieces #${fl.piece[a.v]} of ${pieceSize[fl.piece[a.v]]} / #${fl.piece[b.v]} of ${pieceSize[fl.piece[b.v]]}) (the goal unreachable)")
                continue
            }
            val (chain, used) = r
            // the walk: 3D length (a link's cost where one is taken), its steepest triangle-edge climb, its stair steps (tread joins)
            var yd = 0.0; var steep = 0.0; var stairs = 0; var maxRise = 0.0
            val linkEdges = HashSet<Int>()
            for (q in 0 until chain.size - 1) {
                val u = chain[q]; val w = chain[q + 1]
                val run = hypot(fl.xs[w] - fl.xs[u], fl.ys[w] - fl.ys[u]); val dz = abs(fl.zs[w] - fl.zs[u])
                val isLink = w !in fl.adj[u] && extra[u]?.any { it.first == w } == true
                if (isLink) { linkEdges.add(q); yd += extra[u]!!.first { it.first == w }.second; continue }
                yd += sqrt(run * run + dz * dz)
                val deg = Math.toDegrees(atan2(dz, run))
                if (run <= Caves.STEP_JOIN_YD + 1e-6 && dz > 0.3 && deg > 45.0) { stairs++; maxRise = maxOf(maxRise, dz) } else steep = maxOf(steep, deg)
            }
            // the points kept SIMPLIFY_YD apart (each link's two ends kept: the flag where it is taken)
            val keep = ArrayList<Int>()
            for ((q, v) in chain.withIndex()) {
                val atLink = q in linkEdges || (q - 1) in linkEdges
                if (q == 0 || q == chain.size - 1 || atLink || hypot(fl.xs[v] - fl.xs[keep.last()], fl.ys[v] - fl.ys[keep.last()]) >= Caves.SIMPLIFY_YD ||
                    abs(fl.zs[v] - fl.zs[keep.last()]) >= Caves.SIMPLIFY_YD) keep.add(v)
            }
            val zs = chain.map { fl.zs[it] }
            println("ROUTE2 $letter ${a.name} -> ${b.name}: ${yd.toInt()} yd, ${keep.size - 2} flags (on the city's floor; z ${zs.min().toInt()}..${zs.max().toInt()}; " +
                "steepest face-edge ${"%.0f".format(steep)} deg; $stairs stair steps up to ${"%.1f".format(maxRise)} yd${if (used.isNotEmpty()) "; links: ${used.joinToString()}" else ""}); goal ${fmt(b.v)}")
            println("WATERMAX route $letter 0.0 yd (on the city's floor)")
            wayPts.add(keep.map { pt(it) })
            routeExport.add(JSONObject().put("letter", letter).put("name", "${a.name} → ${b.name}").put("reached", true)
                .put("pts", JSONArray(keep.map { v -> JSONArray(listOf(fl.xs[v], fl.ys[v], 1)) })))
        }
        // the "checks": is a place reached on foot at all (not drawn)
        val cs = spec.optJSONArray("checks") ?: JSONArray()
        for (i in 0 until cs.length()) {
            val o = cs.getJSONObject(i); val a = places[o.getString("from")]; val b = places[o.getString("to")]
            if (a == null || b == null) { println("CHECK ${o.optString("name")}: a place not found"); continue }
            val r = search(a.v, b.v)
            println("CHECK ${o.optString("name")}: " + if (r == null) "NOT JOINED on foot (piece #${fl.piece[b.v]} of ${pieceSize[fl.piece[b.v]]}, z ${fl.zs[b.v].toInt()})"
                else "joined, ${r.first.zipWithNext { u, w -> sqrt((fl.xs[w] - fl.xs[u]).let { it * it } + (fl.ys[w] - fl.ys[u]).let { it * it } + (fl.zs[w] - fl.zs[u]).let { it * it }) }.sum().toInt()} yd")
        }
        return routeExport
    }

    /** the city as one "cave" for the renders: its floor's ways (the routes, with heights) and its link ends as mouths */
    fun asCave(): JSONObject = JSONObject().put("fid", -1).put("wx", places.values.firstOrNull()?.let { fl.xs[it.v] } ?: 0.0)
        .put("wy", places.values.firstOrNull()?.let { fl.ys[it.v] } ?: 0.0)
        .put("mouths", JSONArray(places.values.map { JSONArray(pt(it.v).toList()) }))
        .put("ways", JSONArray(wayPts.map { w -> JSONArray(w.map { JSONArray(it.toList()) }) }))

    /**
     * THE CITY'S STREETS AS WAYS (NET1 v2, the coordinator: "export the city's floor ways as ordinary ways"): on each floor piece of
     * [minPiece] vertices or more, one vertex a [cellYd] box (x, y, and [layerYd] of height: a street over a street is its own) - the one
     * nearest the box's middle - joined to those of the boxes round it by the floor's own shortest way (no longer than [maxFactor] x
     * their distance). Returns the boxes' vertices (x, y, z) and each join (a, b: their indices; its points x, y, z kept
     * [Caves.SIMPLIFY_YD] apart, the floor's own vertices).
     */
    fun floorWays(cellYd: Double = 15.0, layerYd: Double = 6.0, minPiece: Int = 60, maxFactor: Double = 2.0): Pair<List<DoubleArray>, List<Triple<Int, Int, List<DoubleArray>>>> {
        fun box(v: Int) = Triple(Math.floor(fl.xs[v] / cellYd).toInt(), Math.floor(fl.ys[v] / cellYd).toInt(), Math.floor(fl.zs[v] / layerYd).toInt())
        val boxes = HashMap<Triple<Int, Int, Int>, Int>()
        for (v in 0 until fl.size) { if ((pieceSize[fl.piece[v]] ?: 0) < minPiece) continue
            val k = box(v); val cx = (k.first + 0.5) * cellYd; val cy = (k.second + 0.5) * cellYd
            val o = boxes[k]; if (o == null || hypot(fl.xs[v] - cx, fl.ys[v] - cy) < hypot(fl.xs[o] - cx, fl.ys[o] - cy)) boxes[k] = v }
        val reps = boxes.values.toList(); val idx = HashMap<Int, Int>().also { m -> reps.forEachIndexed { i, v -> m[v] = i } }
        val repBox = reps.map { box(it) }
        val out = ArrayList<Triple<Int, Int, List<DoubleArray>>>()
        val limit = cellYd * 1.5 * maxFactor
        for ((ia, a) in reps.withIndex()) {
            val ka = repBox[ia]
            val dist = HashMap<Int, Double>(); val prev = HashMap<Int, Int>()
            val pq = PriorityQueue<DoubleArray>(compareBy { it[0] }); dist[a] = 0.0; pq.add(doubleArrayOf(0.0, a.toDouble()))
            while (pq.isNotEmpty()) {
                val top = pq.poll(); val u = top[1].toInt(); if (top[0] > (dist[u] ?: Double.MAX_VALUE)) continue
                if (top[0] > limit) break
                val ib = idx[u]
                if (ib != null && ib > ia) { val kb = repBox[ib]
                    if (abs(kb.first - ka.first) <= 1 && abs(kb.second - ka.second) <= 1 && abs(kb.third - ka.third) <= 1 &&
                        top[0] <= maxFactor * hypot(fl.xs[u] - fl.xs[a], fl.ys[u] - fl.ys[a]) + 3.0) {
                        val chain = ArrayList<Int>(); var c = u; while (c != a) { chain.add(c); c = prev[c]!! }; chain.add(a); chain.reverse()
                        val keep = ArrayList<Int>()
                        for ((q, v) in chain.withIndex()) if (q == 0 || q == chain.size - 1 || hypot(fl.xs[v] - fl.xs[keep.last()], fl.ys[v] - fl.ys[keep.last()]) >= Caves.SIMPLIFY_YD ||
                            abs(fl.zs[v] - fl.zs[keep.last()]) >= Caves.SIMPLIFY_YD) keep.add(v)
                        out.add(Triple(ia, ib, keep.map { pt(it) }))
                    }
                }
                for (w in fl.adj[u]) { val nd = top[0] + sqrt((fl.xs[w] - fl.xs[u]).let { it * it } + (fl.ys[w] - fl.ys[u]).let { it * it } + (fl.zs[w] - fl.zs[u]).let { it * it })
                    if (nd < (dist[w] ?: Double.MAX_VALUE)) { dist[w] = nd; prev[w] = u; pq.add(doubleArrayOf(nd, w.toDouble())) } }
            }
        }
        println("FLOORWAYS ${reps.size} street points (boxes of ${cellYd.toInt()} yd, pieces of $minPiece+ vertices), ${out.size} ways")
        return reps.map { pt(it) } to out
    }

    fun export(): JSONObject = JSONObject()
        .put("places", JSONArray(places.values.map { p -> JSONObject().put("name", p.name).put("x", fl.xs[p.v]).put("y", fl.ys[p.v]).put("z", fl.zs[p.v])
            .put("piece", fl.piece[p.v]).put("pieceSize", pieceSize[fl.piece[p.v]]).put("how", p.how).put("source", p.source) }))
        .put("links", JSONArray(links))

    companion object {
        fun build(spec: JSONObject, placed: List<WorldBuildings.Placement>, gdir: File): FloorCity {
            // only the root's own groups (MOHD's count): the group list (GFID) goes on with the LOD copies of the same floors
            fun nGroups(fid: Int): Int = File(gdir.parentFile, "wmo/$fid").takeIf { it.isFile }?.readBytes()?.let { b ->
                var o = 0; var n = 400
                while (o + 8 <= b.size) { val len = java.nio.ByteBuffer.wrap(b, o + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    if (String(byteArrayOf(b[o + 3], b[o + 2], b[o + 1], b[o])) == "MOHD") { n = java.nio.ByteBuffer.wrap(b, o + 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int; break }
                    if (len < 0) break; o += 8 + len }
                n } ?: 400
            val parts = placed.distinctBy { it.uid }.map { p -> p to (0 until nGroups(p.fid)).map { File(gdir, "${p.fid}_$it") }.takeWhile { it.isFile }.map { it.readBytes() } }
            println("FLOORCITY ${parts.size} placements: ${parts.joinToString { "${it.first.fid} at ${it.first.ox.toInt()},${it.first.oy.toInt()},${it.first.oz.toInt()} (${it.second.size} groups)" }}")
            val fl = Caves.floor(parts)
            // each group's floor vertices (of the first placement): the group's walkable faces, found among the welded floor's vertices
            val bucket = HashMap<Long, ArrayList<Int>>()
            fun key(x: Double, y: Double, z: Double) = (Math.floor(x).toLong() and 0x1FFFFF) or ((Math.floor(y).toLong() and 0x1FFFFF) shl 21) or ((Math.floor(z).toLong() and 0x1FFFFF) shl 42)
            for (v in 0 until fl.size) bucket.getOrPut(key(fl.xs[v], fl.ys[v], fl.zs[v])) { ArrayList() }.add(v)
            fun find(x: Double, y: Double, z: Double): Int {
                var best = -1; var bd = Caves.WELD_YD * Caves.WELD_YD + 1e-6
                for (i in -1..1) for (j in -1..1) for (k in -1..1) bucket[key(x + i, y + j, z + k)]?.forEach { v ->
                    val d = (fl.xs[v] - x).let { it * it } + (fl.ys[v] - y).let { it * it } + (fl.zs[v] - z).let { it * it }; if (d <= bd) { bd = d; best = v } }
                return best
            }
            val groupVerts = HashMap<Int, IntArray>()
            parts.firstOrNull()?.let { (p, gs) ->
                val cs = cos(p.yaw); val sn = sin(p.yaw)
                for ((gi, g) in gs.withIndex()) {
                    val t = Caves.floorTris(g); val set = HashSet<Int>()
                    for (q in 0 until t.size / 3) { val lx = t[q * 3]; val ly = t[q * 3 + 1]; val lz = t[q * 3 + 2]
                        val v = find(p.ox - lx * p.scale * cs + ly * p.scale * sn, p.oy - lx * p.scale * sn - ly * p.scale * cs, p.oz + lz * p.scale); if (v >= 0) set.add(v) }
                    groupVerts[gi] = set.toIntArray()
                }
            }
            return FloorCity(spec, fl, groupVerts).also { it.init() }
        }
    }
}
