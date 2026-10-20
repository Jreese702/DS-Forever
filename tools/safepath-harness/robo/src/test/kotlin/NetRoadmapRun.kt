import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.Db2
import com.jrees.wowcompanion.DangerInputs
import com.jrees.wowcompanion.MapHarvest
import com.jrees.wowcompanion.PlaceNames
import com.jrees.wowcompanion.QuestieNpcs
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.SafeRoute
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
 * THE ROADMAP'S SIZE AND PROOF RUN (NetRoadmap; NET2): one zone's ground as RenderSafePath assembles it (the app's ZoneInputs and its
 * Lattice, DangerInputs for BOTH factions at the spec's level), then [NetRoadmap.build] at each spacing of RM_SPACINGS (yards, comma
 * separated; default 40, the user's choice: 10 lattice steps), each checked at build time by py/segcheck.py and py/wetcheck.py
 * (NetRoadmap.pyCheck; RM_CHECK=0 none), written to <out>/roadmap_<tag>_<S>.rm1 (RM1, ShippedNet.Roadmap), read back, and its edges
 * checked again as routes (<out>/levels_routes_<tag>.json, the floors / holes / layers files beside it): the FINAL CHECK line.
 * Prints ROADMAP lines (SIZE, COVERAGE, FINAL CHECK).
 *   SAFEPATH_TEST=NetRoadmapRun RM_SPACINGS=40 bash tools/safepath-harness/run.sh tools/safepath-harness/zones/durotar.json ROADMAP
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetRoadmapRun {
    private val out = File(System.getProperty("out.dir") ?: "out").also { it.mkdirs() }

    @Test fun run() {
        val spec = JSONObject(File(System.getenv("ZONESPEC") ?: return).readText())
        val work = File(System.getenv("SAFEPATH_WORK") ?: return)
        val t0 = System.nanoTime()
        val dir = File(work, spec.optString("dir", "kalimdor"))
        val db2 = File(System.getenv("DB2_DIR") ?: File(work, "db2").path)
        val toc = File(System.getenv("QDBTOC") ?: return)
        val tag = spec.getString("tag"); val map = spec.getInt("zone"); val area = spec.getInt("area"); val level = spec.optInt("level", 8)
        val lethal = File(db2, "LiquidType.db2").takeIf { it.isFile }?.let { f -> Terrain.lethalLiquids(Db2(f.readBytes()).rows(strings = setOf(0)).associate { it.id to it.str(0) }) } ?: emptySet()
        if (lethal.isNotEmpty()) Terrain.lethal = lethal
        val areas = Db2(File(db2, "AreaTable.db2").readBytes()).rows(strings = setOf(1)).associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
        val uimap = HashMap<Int, Int>().also { o -> for (r in Db2(File(db2, "UiMapAssignment.db2").readBytes()).rows()) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m } }
        val chunkAreas = HashMap<Pair<Int, Int>, Map<Int, Int>>()
        fun areaAt(wx: Double, wy: Double): Int? {
            val l = PlaceNames.locate(wx, wy)
            val m = chunkAreas.getOrPut(l[0] to l[1]) { File(dir, "adt/${l[0]}_${l[1]}.adt").takeIf { it.isFile }?.let { PlaceNames.chunkAreas(it.readBytes()) } ?: emptyMap() }
            return m[l[2]]
        }
        val recipeJ = JSONObject(File(System.getenv("RECIPE")).readText()).optJSONObject("rects")!!
        val a = recipeJ.optJSONArray(map.toString())!!
        val rect = MapHarvest.Rect(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optDouble(4))
        val capitalRects = com.jrees.wowcompanion.CityExits.CITIES.mapNotNull { c -> recipeJ.optJSONArray(c.toString())?.let { q -> doubleArrayOf(q.getDouble(1), q.getDouble(2), q.getDouble(3), q.getDouble(4)) } }
        val zi = ZoneInputs(DumpSource(dir), rect, lethal, capitalRects) { }
        val tg = Terrain.grid(rect) { gr, gc -> zi.height(gr, gc) }
        val pad = SafePath.GRID_PAD_YD
        val zg = SafePath.Zones.build(rect.t + pad, rect.b - pad, rect.l + pad, rect.r - pad, PlaceNames.CHUNK_YD, ::areaAt)
        val ar = SafePath.Areas(areas, uimap)
        val inZone: (Double, Double) -> Boolean = { x, y -> ar.uiMap(zg.areaAt(x, y)) == map }
        val voidBelow = spec.optDouble("voidBelow", Double.NaN)
        val zl = zi.Lattice(zi.heightV(voidBelow))
        val cg = zl.cg
        val tLattice = (System.nanoTime() - t0) / 1_000_000
        val wg = zi.wg
        val wading: (Double, Double) -> Boolean = { x, y -> wg.wading(x, y) }
        val rooms: (Double, Double) -> Boolean = { x, y -> zl.roomsAt(x, y) }
        // the danger, both factions at the spec's level (the spec's friend rules)
        val spawns = QuestieNpcs.read(toc).filter { it.area == area }
        val rules = DangerInputs.Rules(spec.optInt("friendlyMinLevel", Int.MAX_VALUE),
            spec.optJSONArray("friendlyNames")?.let { q -> (0 until q.length()).map { Regex(q.getString(it)) } } ?: emptyList())
        val dangers = listOf(TransportRoutes.HORDE, TransportRoutes.ALLIANCE).associateWith { f ->
            DangerInputs.grid(DangerInputs.sided(spawns, rect, f, rules), rect, f, level, "high", { wx, wy -> Terrain.factor(tg.at(wx, wy)) }, inZone) }
        val d0 = dangers.values.first()
        val sr = SafeRoute(cg, d0.danger(wading, rooms), d0.calm(wading, rooms), d0.k, zi.deep, { x, y -> wg.at(x, y) }, zi::deckAt, zi::rawDeep)
        val ground = NetRoadmap.Ground(cg, zi.deep, sr::overFord, { x, y -> wg.at(x, y) }, rooms, d0.calm(wading, rooms), d0.k, inZone)
        val tSetup = (System.nanoTime() - t0) / 1_000_000
        println("ROADMAP $tag: lattice ${cg.nx} x ${cg.ny} in $tLattice ms, inputs ready in $tSetup ms")
        // the files segcheck / wetcheck read beside the routes (the floors a walker is on, the holes a floor covers, the floor layers)
        File(out, "holes_$tag.json").writeText(JSONArray(zl.coveredHoles.map { JSONArray(listOf(it[0], it[1])) }).toString())
        File(out, "layers_$tag.json").writeText(JSONArray(zl.layers.map { (k, z) -> JSONArray(listOf((k shr 32).toInt(), k.toInt(), z)) }).toString())
        val classed = zi.tileDecks.values.flatten().toSet()
        File(out, "floors_$tag.json").writeText(JSONArray(zi.deckList.flatMap { d -> val hs = d.heights ?: return@flatMap emptyList<JSONArray>()
            d.triangles.mapIndexed { k, t -> JSONArray(listOf(t[0], t[1], hs[k][0], t[2], t[3], hs[k][1], t[4], t[5], hs[k][2], if (d in classed) 1 else if (d in zi.capitalDecks) 3 else 0)) } }
            + zi.cityFeet.flatMap { f -> val c = listOf(doubleArrayOf(f.worldX(f.lx0, f.ly0), f.worldY(f.lx0, f.ly0)), doubleArrayOf(f.worldX(f.lx1, f.ly0), f.worldY(f.lx1, f.ly0)),
                doubleArrayOf(f.worldX(f.lx1, f.ly1), f.worldY(f.lx1, f.ly1)), doubleArrayOf(f.worldX(f.lx0, f.ly1), f.worldY(f.lx0, f.ly1)))
                listOf(listOf(0, 1, 2), listOf(0, 2, 3)).map { q -> JSONArray(q.flatMap { listOf(c[it][0], c[it][1], 0.0) } + 2) } }).toString())
        // the checks at build time (RM_CHECK=0: none): py/segcheck.py and py/wetcheck.py on every edge
        val check = if (System.getenv("RM_CHECK") == "0") null else NetRoadmap.pyCheck(File(File(System.getProperty("user.dir")).parentFile, "py"), out, tag,
            dir.name, work) { println("ROADMAP $tag $it") }
        // the spacings in yards, each the nearest whole number of lattice steps (30: 7 = 29.2 yd, 40: 10 = 41.7, 60: 14 = 58.3)
        val spacings = (System.getenv("RM_SPACINGS") ?: "40").split(',').map { it.trim().toDouble() }
        for (s in spacings) {
            val cell = Math.round(s / Climb.STEP).toInt()
            val built = NetRoadmap.build(ground, cell, check) { println("ROADMAP $tag S=${s.toInt()} $it") }
            val rm = built.rm
            val raw = rm.encode()
            val back = com.jrees.wowcompanion.ShippedNet.Roadmap.decode(raw)
            val ok = back.encode().contentEquals(raw) && back.edges == rm.edges && (0 until rm.edges).all { e -> back.points(e).size == rm.points(e).size }
            val rmB = com.jrees.wowcompanion.ShippedNet.pack64(raw).length
            val dBytes = dangers.mapValues { (_, d) -> NetRoadmap.dangerBytes(built, d.danger(wading, rooms)) }
            val dB = dBytes.mapValues { com.jrees.wowcompanion.ShippedNet.pack64(it.value).length }
            val yd = (0 until rm.edges).sumOf { rm.yd(it) }
            File(out, "roadmap_${tag}_${s.toInt()}.rm1").writeBytes(raw)
            println("ROADMAP $tag S=${s.toInt()} SIZE cell $cell (${"%.1f".format(cell * Climb.STEP)} yd): ${rm.nodes} nodes, ${rm.edges} edges " +
                "(${built.stats.walked} walked, ${(0 until rm.edges).sumOf { (rm.edgePts[it]?.size ?: 0) / 2 }} inner points), ${(yd / 1000).toInt()} km; " +
                "RM1 ${raw.size} raw, ${rmB} zlib+base64; danger zlib+base64 H ${dB[TransportRoutes.HORDE]} A ${dB[TransportRoutes.ALLIANCE]}; " +
                "round trip ${if (ok) "OK" else "FAIL"}; build ${built.stats.ms} ms; " +
                "danger>0 H ${dBytes[TransportRoutes.HORDE]!!.count { it.toInt() != 0 }} A ${dBytes[TransportRoutes.ALLIANCE]!!.count { it.toInt() != 0 }}; " +
                "flags wade ${rm.edgeFlags.count { it and com.jrees.wowcompanion.ShippedNet.RE_WADE != 0 }} building ${rm.edgeFlags.count { it and com.jrees.wowcompanion.ShippedNet.RE_BUILDING != 0 }} " +
                "bridge ${rm.edgeFlags.count { it and com.jrees.wowcompanion.ShippedNet.RE_BRIDGE != 0 }} floor nodes ${rm.nodeFlags.count { it and com.jrees.wowcompanion.ShippedNet.R_FLOOR != 0 }}")
            // COVERAGE: the zone's open lattice vertices (every 4th) and the nearest node to each
            run {
                val ds = ArrayList<Double>()
                val byCell = HashMap<Int, ArrayList<Int>>()
                for (n in 0 until rm.nodes) byCell.getOrPut(rm.nodeCell[n]) { ArrayList() }.add(n)
                for (j in 0 until cg.ny step 4) for (i in 0 until cg.nx step 4) {
                    val x = cg.wx(j); val y = cg.wy(i)
                    if (!inZone(x, y) || zi.deep(x, y) || rooms(x, y) || (0 until 8).none { q -> cg.step(i, j, listOf(1, 1, 0, -1, -1, -1, 0, 1)[q], listOf(0, 1, 1, 1, 0, -1, -1, -1)[q]) != Climb.WALL }) continue
                    val ci = i / rm.cell; val cj = j / rm.cell; var best = Double.MAX_VALUE
                    for (b in cj - 2..cj + 2) for (c in ci - 2..ci + 2) { if (c !in 0 until rm.nx) continue
                        byCell[b * rm.nx + c]?.forEach { n -> best = minOf(best, Math.hypot(rm.x(n) - x, rm.y(n) - y)) } }
                    ds.add(best)
                }
                ds.sort()
                fun pc(p: Double) = ds.getOrNull(((ds.size - 1) * p).toInt())?.let { if (it == Double.MAX_VALUE) ">2 cells" else "%.0f".format(it) } ?: "-"
                println("ROADMAP $tag S=${s.toInt()} COVERAGE ${ds.size} open vertices sampled; nearest node yd p50 ${pc(0.5)} p90 ${pc(0.9)} p99 ${pc(0.99)}; " +
                    "within 40 yd ${"%.1f".format(ds.count { it <= 40 } * 100.0 / maxOf(1, ds.size))}%")
            }
            // THE PROOF: every edge of the final roadmap through py/segcheck.py and py/wetcheck.py once more
            val fails = check?.invoke((0 until rm.edges).map { rm.points(it) })
            println("ROADMAP $tag S=${s.toInt()} FINAL CHECK ${rm.edges} edges: ${fails?.let { if (it.isEmpty()) "segcheck none, wetcheck none" else "${it.size} FAIL ${it.take(20)}" } ?: "not run (RM_CHECK=0)"}")
            File(out, "levels_routes_$tag.json").writeText(JSONObject().put("routes", NetRoadmap.routesJson(rm)).toString())
        }
    }
}
