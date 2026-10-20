import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedNet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * EVERY SHIPPED SEGMENT CHECKED (the coordinator, 2026-10-19, from W's v2 parity: "EVERY shipped segment passes segcheck at export"):
 * the section's ways (straight or walked; joins too), the roadmap's edges and the stitches, written as one routes file in a folder of
 * its own (with the zone's floors, holes and layers beside it) and read by py/segcheck.py and py/wetcheck.py, gate.py's own readings.
 * What fails is WALKED: its lattice leg ([leg]: the routes' own, deep water a wall) stored as its points and checked again; only what
 * no walkable leg mends is dropped. After [passes] passes, anything still crossing is [Result.left] (the export fails).
 *
 * The STITCHES (the coordinator: "move them to export time"): each ground node of the section's network to its [STITCH_N] nearest
 * roadmap nodes within [STITCH_YD], lattice-walked, checked here like the rest.
 */
class ShipCheck(private val cg: Climb.Grid, private val deep: (Double, Double) -> Boolean,
                private val leg: (DoubleArray, DoubleArray) -> List<DoubleArray>?, private val rmLeg: (DoubleArray, DoubleArray) -> List<DoubleArray>?,
                private val wetOf: (DoubleArray, DoubleArray) -> Int, private val py: File, private val dir: File, private val tag: String,
                private val zdir: String, private val work: File, private val log: (String) -> Unit = {}) {
    companion object {
        const val STITCH_N = 2
        const val STITCH_YD = 35.0
        const val NARROW = 2.2
        const val NARROW_YD = 6
        /** the route letters: a layer's letter and the item's index in letters (segcheck names a segment letters + index) */
        fun letter(layer: Char, i: Int): String { var n = i; val sb = StringBuilder(); do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0); return "$layer$sb" }
    }
    class Result(val sec: ShippedNet.Section, val rm: ShippedNet.Roadmap?, val walked: Map<Char, Int>, val dropped: Map<Char, Int>, val left: List<String>, val passes: Int)

    private fun ground(n: SafePath.Node) = n.kind != SafePath.CAVE && n.kind != SafePath.LIFT && n.src != "floor"

    /** the stitches: each ground node to its nearest roadmap nodes, walked by [leg] (every piece walkable) */
    fun stitches(sec: ShippedNet.Section, rm: ShippedNet.Roadmap): List<ShippedNet.Stitch> {
        val cell = rm.cell * Climb.STEP
        val byCell = HashMap<Long, ArrayList<Int>>()
        fun key(x: Double, y: Double) = (Math.floorDiv(Math.round(x), Math.round(cell)) shl 32) xor (Math.floorDiv(Math.round(y), Math.round(cell)) and 0xFFFFFFFFL)
        for (n in 0 until rm.nodes) byCell.getOrPut(key(rm.x(n), rm.y(n))) { ArrayList() }.add(n)
        val out = ArrayList<ShippedNet.Stitch>()
        val used = HashSet<Int>().also { s -> for (e in sec.net.edges) { s.add(e.a); s.add(e.b) } }
        for (n in sec.net.nodes) {
            if (!ground(n) || n.id !in used) continue
            val near = ArrayList<Pair<Double, Int>>()
            for (dx in -1..1) for (dy in -1..1) byCell[key(n.wx + dx * cell, n.wy + dy * cell)]?.forEach { r ->
                val d = hypot(rm.x(r) - n.wx, rm.y(r) - n.wy); if (d <= STITCH_YD) near.add(d to r) }
            for ((_, r) in near.distinctBy { it.second }.sortedBy { it.first }.take(STITCH_N)) {
                val a = doubleArrayOf(n.wx, n.wy); val b = doubleArrayOf(rm.x(r), rm.y(r))
                // (its points begin at the net node and end at the roadmap node exactly: ShippedNet.anchored - the hop a leg leaves out is checked too)
                val pts = if (Climb.walkable(cg, a, b, deep)) listOf(a, b) else leg(a, b)?.let { ShippedNet.anchored(it, a, b).first } ?: continue
                out.add(ShippedNet.Stitch(n.id, r, ShippedNet.F_JOIN or pts.zipWithNext().fold(0) { f, (u, v) -> f or wetOf(u, v) }, pts.map { doubleArrayOf(it[0], it[1]) }))
            }
        }
        return out
    }

    private class Item(val layer: Char, val idx: Int, val pts: List<DoubleArray>)

    /** the items' failures: segcheck's flagged segments and wetcheck's swims, by item */
    private fun check(items: List<Item>): Map<Item, String> {
        if (items.isEmpty()) return emptyMap()
        val byLetter = HashMap<String, Item>()
        val counts = HashMap<Char, Int>()
        val routes = JSONArray()
        for (it in items) { val k = counts.getOrDefault(it.layer, 0); counts[it.layer] = k + 1; val l = letter(it.layer, k); byLetter[l] = it
            routes.put(JSONObject().put("letter", l).put("name", "${it.layer} ${it.idx}").put("reached", true)
                .put("pts", JSONArray(it.pts.map { p -> JSONArray(if (p.size > 2 && p[2] != 0.0) listOf(p[0], p[1], p[2].toInt()) else listOf(p[0], p[1])) }))) }
        val f = File(dir, "levels_routes_$tag.json"); f.writeText(JSONObject().put("zone", tag).put("routes", routes).toString())
        val bad = HashMap<Item, String>()
        val seg = ParityCompare.runPy(py, "segcheck.py", f, zdir, work, 60)
        val last = seg.lines().lastOrNull { "crossing a" in it } ?: error("segcheck said nothing: ${seg.takeLast(300)}")
        for (m in Regex("([A-Z]+)(\\d+)-[A-Z]+\\d+ \\(([^)]*)\\)").findAll(last.substringAfter(":"))) byLetter[m.groupValues[1]]?.let { bad.putIfAbsent(it, "wall ${m.groupValues[3]}") }
        val wet = ParityCompare.runPy(py, "wetcheck.py", f, zdir, work, 60)
        var read = 0
        for (line in wet.lines()) {
            val m = Regex("WET ([A-Z]+): deepest ([\\d.]+) yd.*?, (\\d+) yd deeper than .*?longest wade (\\d+) yd.*?, (\\d+) yd deeper than").find(line) ?: continue
            read++
            val it = byLetter[m.groupValues[1]] ?: continue
            if (m.groupValues[5].toInt() > 0) bad.putIfAbsent(it, "swims: ${m.groupValues[5]} yd deeper than $NARROW")
            else if (m.groupValues[4].toInt() > NARROW_YD) bad.putIfAbsent(it, "swims: a wade ${m.groupValues[4]} yd on end")
        }
        if (read < items.size) error("wetcheck read $read of ${items.size} routes: ${wet.takeLast(300)}")
        return bad
    }

    /** a way the checks need (a cave's, a lift's and a floor's ways are on their own floors: not the terrain's) */
    private fun checked(sec: ShippedNet.Section, i: Int): Boolean {
        val e = sec.net.edges[i]; val f = sec.ways[i].flags
        return e.kind != SafePath.CAVE && e.kind != SafePath.LIFT && f and ShippedNet.F_FLOOR == 0
    }
    private fun wayPts(sec: ShippedNet.Section, byId: Map<Int, SafePath.Node>, i: Int): List<DoubleArray> {
        val e = sec.net.edges[i]; val a = byId[e.a]!!; val b = byId[e.b]!!
        // a mouth's end on the cave's floor is marked (segcheck: no terrain check onto the floor)
        fun mark(n: SafePath.Node) = if (n.kind == SafePath.MOUTH) 0.0 else 0.0
        return sec.ways[i].pts ?: listOf(doubleArrayOf(a.wx, a.wy, mark(a)), doubleArrayOf(b.wx, b.wy, mark(b)))
    }

    fun run(sec0: ShippedNet.Section, rm0: ShippedNet.Roadmap?, passes: Int = 3): Result {
        var sec = sec0; var rm = rm0
        val walked = HashMap<Char, Int>(); val dropped = HashMap<Char, Int>()
        val walkedWays = HashSet<Int>(); val walkedRm = HashSet<Int>()
        var left: List<String> = emptyList(); var pass = 0
        // the items still to check: all at first; then only those changed (walked) - what passed once is not changed again
        var toWays: Set<Int>? = null; var toRm: Set<Int>? = null; var toSt: Set<Int>? = null
        while (pass < passes) {
            pass++
            val byId = sec.net.nodes.associateBy { it.id }
            val items = ArrayList<Item>()
            for (i in sec.ways.indices) if (checked(sec, i) && (toWays == null || i in toWays)) items.add(Item('N', i, wayPts(sec, byId, i)))
            rm?.let { r -> for (e in 0 until r.edges) if (toRm == null || e in toRm) items.add(Item('R', e, r.points(e))) }
            for ((i, st) in sec.stitches.withIndex()) if (toSt == null || i in toSt) items.add(Item('S', i, st.pts))
            val bad = check(items)
            log("SHIPCHECK $tag pass $pass: ${items.size} pieces checked (${items.count { it.layer == 'N' }} ways, ${items.count { it.layer == 'R' }} roadmap edges, " +
                "${items.count { it.layer == 'S' }} stitches); ${bad.size} fail (${bad.keys.groupingBy { it.layer }.eachCount()})")
            if (bad.isEmpty()) { left = emptyList(); break }
            // mend: walk each failing piece once; a walked one that fails again goes
            val dropW = HashSet<Int>(); val newPts = HashMap<Int, List<DoubleArray>>()
            val dropR = HashSet<Int>(); val newRm = HashMap<Int, IntArray?>()
            val dropS = HashSet<Int>()
            for ((it, why) in bad) when (it.layer) {
                'N' -> { val p = it.pts
                    val w = if (it.idx in walkedWays) null else leg(doubleArrayOf(p.first()[0], p.first()[1]), doubleArrayOf(p.last()[0], p.last()[1]))
                    if (w != null && w.size >= 2) { newPts[it.idx] = ShippedNet.anchored(w.map { q -> doubleArrayOf(q[0], q[1]) }, p.first(), p.last()).first
                        walkedWays.add(it.idx); walked.merge('N', 1, Int::plus) }
                    else { dropW.add(it.idx); dropped.merge('N', 1, Int::plus); log("SHIPCHECK $tag drop way ${it.idx} (${sec.net.edges[it.idx].kind}, ${ShippedNet.flagNames(sec.ways[it.idx].flags)}): $why") } }
                'R' -> { val r = rm!!; val a = r.edgeA[it.idx]; val b = r.edgeB[it.idx]
                    val w = if (it.idx in walkedRm) null else rmLeg(doubleArrayOf(r.x(a), r.y(a)), doubleArrayOf(r.x(b), r.y(b)))
                    val inner = w?.takeIf { it.size >= 2 && abs(it.first()[0] - r.x(a)) < 1e-6 && abs(it.first()[1] - r.y(a)) < 1e-6 &&
                        abs(it.last()[0] - r.x(b)) < 1e-6 && abs(it.last()[1] - r.y(b)) < 1e-6 }?.let { q -> q.subList(1, q.size - 1) }
                        ?.takeIf { q -> q.all { p -> abs(ShippedNet.vx(ShippedNet.gr(p[0])) - p[0]) < 1e-6 && abs(ShippedNet.vy(ShippedNet.gc(p[1])) - p[1]) < 1e-6 } }
                    if (inner != null) { newRm[it.idx] = inner.flatMap { p -> listOf(ShippedNet.gr(p[0]), ShippedNet.gc(p[1])) }.toIntArray().takeIf { q -> q.isNotEmpty() }
                        walkedRm.add(it.idx); walked.merge('R', 1, Int::plus) }
                    else { dropR.add(it.idx); dropped.merge('R', 1, Int::plus)
                        log("SHIPCHECK $tag drop roadmap edge ${it.idx} (${r.x(a).toInt()},${r.y(a).toInt()} - ${r.x(b).toInt()},${r.y(b).toInt()}${if (it.idx in walkedRm) ", walked" else ""}): $why") } }
                else -> { dropS.add(it.idx); dropped.merge('S', 1, Int::plus)
                    log("SHIPCHECK $tag drop stitch ${it.idx} (node ${sec.stitches[it.idx].node} - roadmap node ${sec.stitches[it.idx].rm}): $why") }
            }
            // the section again: walked ways' points, dropped ways out
            val keep = sec.ways.indices.filter { it !in dropW }
            val ways = keep.map { i -> newPts[i]?.let { p -> val w = sec.ways[i]
                ShippedNet.Way((w.flags and (ShippedNet.F_WADE or ShippedNet.F_BRIDGE or ShippedNet.F_SWIM).inv()) or p.zipWithNext().fold(0) { f, (u, v) -> f or wetOf(u, v) },
                    p.zipWithNext { u, v -> hypot(v[0] - u[0], v[1] - u[1]) }.sum(), p) } ?: sec.ways[i] }
            val idxMap = HashMap<Int, Int>().also { m -> keep.forEachIndexed { k, i -> m[i] = k } }
            val net = SafePath.Net(sec.net.map, sec.net.cont, sec.net.built, sec.net.nodes, keep.map { sec.net.edges[it] }, sec.net.exits, sec.net.reviewed)
            // the roadmap again: walked edges' inner vertices, dropped edges out (their order kept: the encoder's)
            var rmMap: Map<Int, Int>? = null
            rm?.let { r -> val kr = (0 until r.edges).filter { it !in dropR }; rmMap = HashMap<Int, Int>().also { m -> kr.forEachIndexed { k, e -> m[e] = k } }
                rm = ShippedNet.Roadmap(r.gr0, r.gc0, r.cell, r.nx, r.ny, r.nodeCell, r.nodeGr, r.nodeGc, r.nodeFlags,
                    kr.map { r.edgeA[it] }.toIntArray(), kr.map { r.edgeB[it] }.toIntArray(),
                    kr.map { e -> if (e in newRm) r.edgeFlags[e] or ShippedNet.RE_WALKED else r.edgeFlags[e] }.toIntArray(),
                    kr.map { e -> if (e in newRm) newRm[e] else r.edgePts[e] }.toTypedArray()) }
            val sts = sec.stitches.filterIndexed { i, _ -> i !in dropS }
            sec = sec.copy(net = net, ways = ways, stitches = sts, wayTag = sec.wayTag?.let { t -> keep.map { t[it] }.toIntArray() })
            // the next pass: only what was walked
            toWays = newPts.keys.mapNotNull { idxMap[it] }.toSet()
            toRm = newRm.keys.mapNotNull { rmMap?.get(it) }.toSet()
            toSt = emptySet()
            val newWalkedW = walkedWays.mapNotNull { idxMap[it] }; walkedWays.clear(); walkedWays.addAll(newWalkedW)
            val newWalkedR = walkedRm.mapNotNull { rmMap?.get(it) }; walkedRm.clear(); walkedRm.addAll(newWalkedR)
            if (toWays!!.isEmpty() && toRm!!.isEmpty()) { left = emptyList(); break }
            if (pass == passes) left = (toWays!!.map { "way $it (walked, not re-checked)" } + toRm!!.map { "roadmap edge $it (walked, not re-checked)" })
        }
        log("SHIPCHECK $tag: walked ${walked}, dropped ${dropped}; ${if (left.isEmpty()) "every shipped segment passes" else "LEFT ${left.size}: ${left.take(10)}"}")
        return Result(sec, rm, walked, dropped, left, pass)
    }
}
