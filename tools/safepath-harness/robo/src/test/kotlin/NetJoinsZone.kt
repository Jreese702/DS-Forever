import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.Db2
import com.jrees.wowcompanion.DangerInputs
import com.jrees.wowcompanion.MapHarvest
import com.jrees.wowcompanion.PlaceNames
import com.jrees.wowcompanion.QuestieNpcs
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedNet
import com.jrees.wowcompanion.Terrain
import com.jrees.wowcompanion.TransportRoutes
import com.jrees.wowcompanion.ZoneInputs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * THE JOINS ON A ZONE (NetJoins' proof): the zone's lattice and water built as RenderSafePath builds them (ZoneInputs, its Lattice;
 * voidBelow), each exported section's net (net1_<tag>.json: one faction's) joined by NetJoins with THAT faction's danger
 * (DangerInputs, the spec's rules, the section's level), and the joins written as routes for py/segcheck.py and py/wetcheck.py.
 *
 *   JOINS_SPEC=zones/<tag>.json JOINS_NET="<dir>/net1_<tag>.json;<dir>/net1_<tag>_<other>.json" SAFEPATH_TEST=NetJoinsZone \
 *     bash tools/safepath-harness/run.sh "" "JOINS"
 *
 * Writes <out>/joins/joins_<tag>.json (the result: pieces, joins, what stands apart) and <out>/joins/levels_routes_<tag>.json (the
 * joins as routes J1, J2, ...; the floors_/holes_/layers_ files are copied beside it from JOINS_FLOORS, default the sections' folder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetJoinsZone {
    private val out = File(System.getProperty("out.dir") ?: "out").also { it.mkdirs() }
    @Test fun zone() {
        val spec = JSONObject(File(System.getenv("JOINS_SPEC") ?: return).readText())
        val secs = (System.getenv("JOINS_NET") ?: return).split(';').filter { it.isNotBlank() }.map { File(it.trim()) }
        val work = File(System.getenv("SAFEPATH_WORK") ?: "E:/safepath-work")
        val dir = File(work, spec.optString("dir", "kalimdor"))
        val db2 = File(work, "db2")
        val map = spec.getInt("zone"); val area = spec.getInt("area")
        val recipe = JSONObject(File(System.getenv("RECIPE")).readText()).getJSONObject("rects")
        val a = recipe.getJSONArray(map.toString())
        val rect = MapHarvest.Rect(a.getInt(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4))
        val lethal = File(db2, "LiquidType.db2").takeIf { it.isFile }?.let { f -> Terrain.lethalLiquids(Db2(f.readBytes()).rows(strings = setOf(0)).associate { it.id to it.str(0) }) } ?: emptySet()
        if (lethal.isNotEmpty()) Terrain.lethal = lethal
        val capitalRects = com.jrees.wowcompanion.CityExits.CITIES.mapNotNull { c -> recipe.optJSONArray(c.toString())?.let { r -> doubleArrayOf(r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4)) } }
        val t0 = System.currentTimeMillis()
        val zi = ZoneInputs(DumpSource(dir), rect, lethal, capitalRects) { }
        val zl = zi.Lattice(zi.heightV(spec.optDouble("voidBelow", Double.NaN)))
        val cg = zl.cg; val wg = zi.wg; val deep = zi.deep
        val tg = Terrain.grid(rect) { gr, gc -> zi.height(gr, gc) }
        val areas = Db2(File(db2, "AreaTable.db2").readBytes()).rows(strings = setOf(1)).associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
        val uimap = HashMap<Int, Int>().also { o -> for (r in Db2(File(db2, "UiMapAssignment.db2").readBytes()).rows()) { val x = r.int(7); val m = r.int(4); if (x != 0 && m != 0 && x !in o) o[x] = m } }
        val ar = SafePath.Areas(areas, uimap)
        val chunkAreas = HashMap<Pair<Int, Int>, Map<Int, Int>>()
        fun areaAt(wx: Double, wy: Double): Int? { val l = PlaceNames.locate(wx, wy)
            return chunkAreas.getOrPut(l[0] to l[1]) { File(dir, "adt/${l[0]}_${l[1]}.adt").takeIf { it.isFile }?.let { PlaceNames.chunkAreas(it.readBytes()) } ?: emptyMap() }[l[2]] }
        val pad = SafePath.GRID_PAD_YD
        val zg = SafePath.Zones.build(rect.t + pad, rect.b - pad, rect.l + pad, rect.r - pad, PlaceNames.CHUNK_YD, ::areaAt)
        val spawns = QuestieNpcs.read(File(System.getenv("QDBTOC")))
        val rules = DangerInputs.Rules(spec.optInt("friendlyMinLevel", Int.MAX_VALUE),
            spec.optJSONArray("friendlyNames")?.let { q -> (0 until q.length()).map { Regex(q.getString(it)) } } ?: emptyList())
        val wading: (Double, Double) -> Boolean = { x, y -> wg.wading(x, y) }
        // the exporter's wet flags (RenderSafePath's lambda for ShippedNet.section)
        val wetOf: (DoubleArray, DoubleArray) -> Int = { pa, pb ->
            var f = 0; val n = maxOf(1, (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) / 2).toInt())
            for (t in 0..n) { val x = pa[0] + (pb[0] - pa[0]) * t / n; val y = pa[1] + (pb[1] - pa[1]) * t / n; val d = wg.at(x, y)
                f = f or when { d == SafePath.Water.BRIDGE -> ShippedNet.F_BRIDGE; d.isNaN() || d <= 0f -> 0; deep(x, y) -> ShippedNet.F_SWIM; else -> ShippedNet.F_WADE } }
            f }
        println("JOINS lattice ${cg.nx} x ${cg.ny} for $map in ${System.currentTimeMillis() - t0} ms")
        val jd = File(out, "joins").also { it.mkdirs() }
        for (f in secs) {
            val pack = ShippedNet.Pack.fromJson(JSONObject(f.readText())) ?: error("not NET1: $f")
            for (sec in pack.sections) {
                val mine = sec.faction
                val here = DangerInputs.sided(spawns.filter { it.area == area }, rect, mine, rules)
                val dz = DangerInputs.grid(here, rect, mine, sec.level, "high", { wx, wy -> Terrain.factor(tg.at(wx, wy)) }, { wx, wy -> ar.uiMap(zg.areaAt(wx, wy)) == map })
                val danger = dz.danger(wading, zl::roomsAt)
                val fl = File(System.getenv("JOINS_FLOORS") ?: f.parent)
                for (k in listOf("floors", "holes", "layers", "decks")) File(fl, "${k}_${sec.tag}.json").takeIf { it.isFile }?.copyTo(File(jd, "${k}_${sec.tag}.json"), overwrite = true)
                val py = File(File(System.getProperty("user.dir")).parentFile, "py")
                val r = if (System.getenv("JOINS_SEGCHECK") == "0") NetJoins.joins(sec.net, cg, deep, danger, dz.k, sec.links, wetOf = wetOf) { println(it) }
                    else NetJoins.joinsChecked(sec.net, cg, deep, danger, dz.k, sec.links, wetOf = wetOf,
                        check = NetJoins.segcheck(py, jd, sec.tag, spec.optString("dir", "kalimdor"), work)) { println(it) }
                println(r.summary("${sec.tag} (${if (mine == TransportRoutes.HORDE) "horde" else "alliance"})"))
                for (p in r.pieces.sortedByDescending { it.yd }.take(12)) println("JOINS ${sec.tag} piece ${p.id}: ${p.nodes.size} nodes, ${p.ways} ways (${p.groundWays} ground), ${p.yd.toInt()} yd, ${p.seeds} seeds at ${p.x.toInt()},${p.y.toInt()} ${p.kinds} ${p.named.take(3)}")
                for (l in r.linked) println("JOINS ${sec.tag} link $l")
                for (j in r.joins) println("JOINS ${sec.tag} ${j.why} ${j.pieceA}-${j.pieceB}: ${j.yd.toInt()} yd (cost ${j.cost.toInt()}), ${j.pts.size - 1} segments, ${NetJoins.flagNames(j.flags)}, danger ${"%.2f".format(j.danger)} tier ${j.tier}" +
                    (if (j.why == "shortcut") ", saves ${j.saved.toInt()} yd" else "") + "; ${j.a.x.toInt()},${j.a.y.toInt()} -> ${j.b.x.toInt()},${j.b.y.toInt()}")
                for (x in r.apart) println("JOINS ${sec.tag} APART${if (x.ground) " (ground)" else ""} pieces ${x.pieces} (${x.nodes} nodes, ${x.yd.toInt()} yd) at ${x.x.toInt()},${x.y.toInt()}: ${x.why}")
                for (x in r.rejected.take(15)) println("JOINS ${sec.tag} rejected $x")
                // every join walkable step by step (the hard rule), and applied
                val bad = r.joins.sumOf { j -> j.pts.zipWithNext().count { (p, q) -> !Climb.walkable(cg, p, q, deep) } }
                val joined = NetJoins.apply(sec.net, r.joins)
                val left = NetJoins.pieceOf(joined).let { pc -> pc.toSet().size }
                println("JOINS ${sec.tag} CHECK walls on joins $bad; applied: ${joined.nodes.size} nodes, ${joined.edges.size} ways; pieces by ways alone ${r.pieces.size} -> $left, with the links ${r.after}")
                // T1's reference (ShippedNet.joins, 92314db) on the same net and legs, for the comparison (JOINS_PROTO=1)
                if (System.getenv("JOINS_PROTO") == "1") { val tp = System.currentTimeMillis()
                    val legOf: (DoubleArray, DoubleArray) -> Pair<List<DoubleArray>, Boolean>? = { p, q ->
                        Climb.route(cg, p, q, danger, dz.k, deep)?.let { lg -> lg.pts to (lg.reached && lg.pts.zipWithNext().all { (u, v) -> Climb.walkable(cg, u, v, deep) }) } }
                    val js = ShippedNet.joins(sec.net, legOf)
                    val pj = NetJoins.pieceOf(SafePath.Net(sec.net.map, sec.net.cont, sec.net.built, sec.net.nodes, sec.net.edges + js.map { SafePath.Edge(it.first, it.second, SafePath.TRAIL) }, sec.net.exits))
                    println("JOINS ${sec.tag} PROTO ShippedNet.joins: ${js.size} joins, ${js.sumOf { t -> t.third.zipWithNext { u, v -> Math.hypot(v[0] - u[0], v[1] - u[1]) }.sum() }.toInt()} yd; pieces ${r.pieces.size} -> ${pj.toSet().size}; ${System.currentTimeMillis() - tp} ms") }
                File(jd, "joins_${sec.tag}.json").writeText(r.json().put("tag", sec.tag).put("faction", mine).put("map", map).toString())
                NetJoins.routesFile(jd, sec.tag, r.joins)
            }
        }
    }
}
