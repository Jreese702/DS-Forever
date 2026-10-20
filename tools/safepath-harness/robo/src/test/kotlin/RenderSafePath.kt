import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.jrees.wowcompanion.Caves
import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.Db2
import com.jrees.wowcompanion.WorldBuildings
import com.jrees.wowcompanion.DangerGrid
import com.jrees.wowcompanion.DangerInputs
import com.jrees.wowcompanion.MapHarvest
import com.jrees.wowcompanion.PlaceNames
import com.jrees.wowcompanion.QuestieNpcs
import com.jrees.wowcompanion.Roads
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.SafeRoute
import com.jrees.wowcompanion.ShippedNet
import com.jrees.wowcompanion.Terrain
import com.jrees.wowcompanion.TransportRoutes
import com.jrees.wowcompanion.ZoneInputs
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * THE SAFEPATH ZONE HARNESS (tools/safepath-harness; docs/safepath/SAFEPATH_SPEC.md, ZONE_PLAYBOOK.md): a zone's network drafted by
 * the app's own SafePath from the PC client's dumps, its exits checked on the ground, its caves found, its test routes walked the
 * device's way (the network first, Climb's legs on the game's triangles), all exported for the renders. ZONESPEC names the zone
 * (tools/safepath-harness/zones/<tag>.json); none = Durotar's own goals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderSafePath {
    private val out = File(System.getProperty("out.dir") ?: "out").also { it.mkdirs() }
    /** every QuestieDB spawn (NetFloors: a capital's own NPCs, on its own map's coordinates) */
    private var allSpawns: List<QuestieNpcs.Spawn> = emptyList()
    @Test fun durotar() {
        val spec = System.getenv("ZONESPEC")?.let { JSONObject(File(it).readText()) }
        // the work folder (outside the repo: the client's dumps, the art, the tables, the pictures): SAFEPATH_WORK
        val work = File(System.getenv("SAFEPATH_WORK") ?: return)
        val dir = File(work, spec?.optString("dir")?.takeIf { it.isNotEmpty() } ?: "kalimdor")
        val art = File(System.getenv("ART_ROOT") ?: File(work, "artroot").path)
        val zoneList = spec?.optJSONArray("maps")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { q -> q.getInt(0) to q.getInt(1) } } }
            ?: listOf(1411 to 14, 1454 to 1637, 1413 to 17)
        val main = spec?.optInt("zone") ?: 1411
        val mainArea = spec?.optInt("area") ?: 14
        val mine = if (spec?.optString("faction") == "alliance") TransportRoutes.ALLIANCE else TransportRoutes.HORDE
        val foe = if (mine == TransportRoutes.HORDE) TransportRoutes.ALLIANCE else TransportRoutes.HORDE
        val cities = spec?.optJSONArray("cities")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: setOf(1454)
        val tag = spec?.optString("tag") ?: "durotar"
        val borderPair = spec?.optJSONArray("border")?.let { it.getInt(0) to it.getInt(1) } ?: (1411 to 1413)
        val db2 = File(System.getenv("DB2_DIR") ?: File(work, "db2").path); val toc = File(System.getenv("QDBTOC") ?: return)
        val names = File(dir, "tex0/names.txt").readLines().mapNotNull { l -> l.split(';', limit = 2).takeIf { it.size == 2 }?.let { it[0].toInt() to it[1] } }.toMap()
        // the device LEARNS its road textures from the player's walking (Roads.learn); here the client's own names stand in for it
        // (a zone whose road texture is named otherwise lists it: the spec's "roadTextures", e.g. Dun Morogh's "browncracks")
        val extraRoad = spec?.optJSONArray("roadTextures")?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() } } ?: emptyList()
        val roadTex = names.filterValues { n -> (listOf("road", "path", "cobble", "trail", "footprint") + extraRoad).any { it in n.lowercase() } }.keys
        println("SAFEPATH road textures: ${roadTex.joinToString { names[it]!!.substringAfterLast('/') }}")
        // the liquids no one wades (magma, slime: LiquidType's names), for the water grid and py/wetcheck.py (liquids.json)
        val lethal = File(db2, "LiquidType.db2").takeIf { it.isFile }?.let { f -> Terrain.lethalLiquids(Db2(f.readBytes()).rows(strings = setOf(0)).associate { it.id to it.str(0) }) } ?: emptySet()
        println("SAFEPATH lethal liquids: ${lethal.sorted()}")
        if (lethal.isNotEmpty()) Terrain.lethal = lethal
        File(work, "db2/liquids.json").writeText(org.json.JSONObject().put("lethal", org.json.JSONArray(lethal.sorted())).toString())
        val areas = Db2(File(db2, "AreaTable.db2").readBytes()).rows(strings = setOf(1)).associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
        val uimap = HashMap<Int, Int>().also { o -> for (r in Db2(File(db2, "UiMapAssignment.db2").readBytes()).rows()) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m } }
        val chunkAreas = HashMap<Pair<Int, Int>, Map<Int, Int>>()
        fun areaAt(wx: Double, wy: Double): Int? {
            val l = PlaceNames.locate(wx, wy)
            val m = chunkAreas.getOrPut(l[0] to l[1]) { File(dir, "adt/${l[0]}_${l[1]}.adt").takeIf { it.isFile }?.let { PlaceNames.chunkAreas(it.readBytes()) } ?: emptyMap() }
            return m[l[2]]
        }
        val tex = HashMap<Pair<Int, Int>, Roads.Tile?>()
        val transports = JSONObject(File(art, "transports.json").readText())
        val spawns = QuestieNpcs.read(toc)
        allSpawns = spawns
        fun nameOf(m: Int) = runCatching { JSONObject(File(art, "worldmap/$m/index.json").readText()).optString("name") }.getOrDefault("")
        val recipe = JSONObject(File(System.getenv("RECIPE")).readText()).optJSONObject("rects")!!
        class Done(val net: SafePath.Net, val rect: MapHarvest.Rect, val zg: SafePath.Zones, val ar: SafePath.Areas, val hostile: List<DoubleArray>)
        val done = HashMap<Int, Done>()
        for ((map, area) in zoneList) {
            tex.clear(); System.gc()
            val a = recipe.optJSONArray(map.toString())!!
            val rect = MapHarvest.Rect(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optDouble(4))
            // A CAPITAL is a city WMO standing in a capital's own map (CityExits.CITIES' rects): ZoneInputs.capital
            val capitalRects = JSONObject(File(System.getenv("RECIPE")).readText()).optJSONObject("rects").let { rr ->
                com.jrees.wowcompanion.CityExits.CITIES.mapNotNull { c -> rr?.optJSONArray(c.toString())?.let { a -> doubleArrayOf(a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4)) } } }
            // the zone's ground as the device assembles it (the app's ZoneInputs), from the dumps
            val zi = ZoneInputs(DumpSource(dir), rect, lethal, capitalRects) { println(it) }
            val rg = Roads.grid(rect) { col, row -> tex.getOrPut(col to row) { File(dir, "tex0/${col}_$row.adt").takeIf { it.isFile }?.let { Roads.tile(it.readBytes()) } } }
            // the roads as the device counts them (Roads.isRoad): a road texture showing most in a cell, or showing thinly there (at
            // Roads.THIN_MIN of its 36 points: Teldrassil's 4-6 yd path, Dun Morogh's road under the snow)
            val roadAt: (Double, Double) -> Boolean = { wx, wy -> rg.shows(wx, wy, roadTex) }
            val tg = Terrain.grid(rect) { gr, gc -> zi.height(gr, gc) }
            val pad = SafePath.GRID_PAD_YD
            val zg = SafePath.Zones.build(rect.t + pad, rect.b - pad, rect.l + pad, rect.r - pad, PlaceNames.CHUNK_YD, ::areaAt)
            // the water and the walkways' decks over it (ZoneInputs: the root ADTs' MH2O over their ground; each walkway by its deck)
            val wg = zi.wg
            println("SAFEPATH water of $map: ${wg.depth.count { it.toInt() != 0 }} wet cells of ${wg.depth.size}, shallow ${wg.depth.count { (it.toInt() and 0xFF) in 1..16 }}, bridged ${wg.bridge.count { it.toInt() != 0 }} cells")
            val ar = SafePath.Areas(areas, uimap)
            run {
                var rc = 0; var tot = 0; var steep = 0; val hist = HashMap<Int, Int>()
                for (j in 0 until rg.ny) for (i in 0 until rg.nx) { val cc = rg.centre(i, j); if (ar.uiMap(zg.areaAt(cc[0], cc[1])) != map) continue; tot++
                    val tx = rg.tex[j * rg.nx + i]; hist[tx] = (hist[tx] ?: 0) + 1; if (roadAt(cc[0], cc[1])) rc++; if (Terrain.factor(tg.at(cc[0], cc[1])) >= Terrain.WALL) steep++ }
                println("SAFEPATH $map own cells $tot road $rc walls $steep top textures ${hist.entries.sortedByDescending { it.value }.take(6).map { (names[it.key] ?: it.key.toString()).substringAfterLast('/') + "=" + it.value }}")
            }
            val ax = rect.l - rect.r; val ay = rect.t - rect.b
            // a zone's own friendly NPCs QuestieDB codes hostile to both (WoW Forever's new factions): those at a placeholder level
            // (the spec's friendlyMinLevel, e.g. 9999) are no mobs - made the player's side's, so they count for the towns, not the danger
            // and a zone's own peoples the guides call friendly to all (the spec's friendlyNames, regexes: Zephras' Windshapers and High
            // Order - the isle shared by both factions; QuestieDB's kill links to them are beta leftovers)
            val rules = DangerInputs.Rules(spec?.optInt("friendlyMinLevel", Int.MAX_VALUE) ?: Int.MAX_VALUE,
                spec?.optJSONArray("friendlyNames")?.let { a -> (0 until a.length()).map { Regex(a.getString(it)) } } ?: emptyList())
            // a town's faction-less people (no level, or a guard's name) take the side of the faction-tagged ones round them
            // (QuestieNpcs.townSided), then the rules' friends made this side's: the app's DangerInputs, the device's own
            val here = DangerInputs.sided(spawns.filter { it.area == area }, rect, mine, rules)
            val hostile = DangerInputs.hostile(here, mine).map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
            val friendly = here.filter { it.hostile == foe }.map { SafePath.Npc(rect.t - it.y * ay, rect.l - it.x * ax, it.sub) }
            val fixed = SafePath.fixedPoints(map, rect.c, rect.t, rect.b, rect.l, rect.r, mine, transports) + SafePath.hubs(friendly, { x, y -> PlaceNames.nameAt(x, y, areas) { a, b2 -> zg.areaAt(a, b2) } }, nameOf(map), SafePath.districtFor(map, rect.t, rect.b, rect.l, rect.r))
            fun draftFor(fx: List<SafePath.Fixed>, foes: List<DoubleArray>) = SafePath.draft(SafePath.Input(map, rect.c, rect.t, rect.b, rect.l, rect.r, roadAt,
                { wx, wy -> Terrain.factor(tg.at(wx, wy)) }, { wx, wy -> ar.uiMap(zg.areaAt(wx, wy)) }, fx, ::nameOf, SafePath.dangerOf(foes),
                ground = if (map in cities) artGround(art, map, rect) else null, painted = painted(art, map, rect), water = { x, y -> wg.at(x, y) }), "off-device")
            // the other side's towns and stops where its own run's draft puts them (their names: Grom'gol for the Alliance, Theramore and
            // "Theramore Isle · Inn, Stables" for the Horde) - none of them nodes here, only the places a spec's goals name
            val otherPlaces = if (map != main || spec == null) emptyList() else {
                val otherFriendly = here.filter { it.hostile == mine }.map { SafePath.Npc(rect.t - it.y * ay, rect.l - it.x * ax, it.sub) }
                val otherFoes = here.filter { it.hostile == foe || it.hostile == 3 }.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
                val ofx = SafePath.fixedPoints(map, rect.c, rect.t, rect.b, rect.l, rect.r, foe, transports) + SafePath.hubs(otherFriendly,
                    { x, y -> PlaceNames.nameAt(x, y, areas) { a, b2 -> zg.areaAt(a, b2) } }, nameOf(map), SafePath.districtFor(map, rect.t, rect.b, rect.l, rect.r))
                draftFor(ofx, otherFoes).nodes.mapNotNull { n -> n.name?.let { it to doubleArrayOf(n.wx, n.wy) } } }
            val t0 = System.nanoTime()
            val net = draftFor(fixed, hostile)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("SAFEPATH ${nameOf(map)}: ${net.nodes.size} nodes, ${net.edges.size} edges (${net.edges.groupBy { it.kind }.mapValues { it.value.size }}), " +
                "${net.exits.size} exits: ${net.exits.joinToString { "${it.name} (${it.how})" }}; fixed ${fixed.joinToString { it.name }}; " +
                "${hostile.size} hostile, ${friendly.size} friendly spawns; $ms ms")
            File(out, "safepath_$map.draft.json").writeText(net.toJson().toString(1))
            draw(net, rect, art, map, zg, ar, nameOf(map))
            // a floating zone's void floor (Zephras Isle's flat ground far below the island: falling, not walking) is no ground
            val voidBelow = spec?.optDouble("voidBelow", Double.NaN) ?: Double.NaN
            if (map == main) levels2(net, rect, tg, zi.heightV(voidBelow), roadAt, zg, areas, here, dir, { x, y -> wg.at(x, y) }, wg,
                zi::deckAt, ::nameOf, floorsAt = zi::floorsAt,
                mainArea, spec, tag, mine, ar, map, otherPlaces, rawDeep = zi::rawDeep, zi = zi)
            if (map == main) writeFloors(zi, tag)
            done[map] = Done(net, rect, zg, ar, hostile)
        }
        if (done.containsKey(borderPair.first) && done.containsKey(borderPair.second))
            border(art, borderPair.first, borderPair.second, done.mapValues { it.value.net }, done.mapValues { it.value.rect }, done.mapValues { it.value.zg },
                done.mapValues { it.value.ar }, done.mapValues { it.value.hostile }, ::nameOf)
    }

    /** the walkways' decks (decks_<tag>.json) and the floors in 3D (floors_<tag>.json) for py/segcheck.py and py/wetcheck.py */
    private fun writeFloors(zi: ZoneInputs, tag: String) {
        File(out, "decks_$tag.json").writeText(org.json.JSONArray(zi.deckList.flatMap { d -> d.triangles.map { org.json.JSONArray(it.toList()) } }).toString())
            // and their floors in 3D for py/segcheck.py (the surface a walker is on: each triangle's corners x, y, z, and 1 where its
            // height classes the lattice's steps, 0 where it levels them - a city's, a building's porch)
            // (3: a capital's read floor - leveled whatever its height, as a street box's 2, but a real floor: a pier over the sea is a deck
            // for py/wetcheck.py, Theramore's and Auberdine's docks)
        run { val classed = zi.tileDecks.values.flatten().toSet()
                File(out, "floors_$tag.json").writeText(org.json.JSONArray(zi.deckList.flatMap { d -> val hs = d.heights ?: return@flatMap emptyList<org.json.JSONArray>()
                    d.triangles.mapIndexed { k, t -> org.json.JSONArray(listOf(t[0], t[1], hs[k][0], t[2], t[3], hs[k][1], t[4], t[5], hs[k][2], if (d in classed) 1 else if (d in zi.capitalDecks) 3 else 0)) } }
                    // and a capital's street boxes (no floor read: leveled whatever their height - flag 2), two triangles a box
                    + zi.cityFeet.flatMap { f -> val c = listOf(doubleArrayOf(f.worldX(f.lx0, f.ly0), f.worldY(f.lx0, f.ly0)), doubleArrayOf(f.worldX(f.lx1, f.ly0), f.worldY(f.lx1, f.ly0)),
                        doubleArrayOf(f.worldX(f.lx1, f.ly1), f.worldY(f.lx1, f.ly1)), doubleArrayOf(f.worldX(f.lx0, f.ly1), f.worldY(f.lx0, f.ly1)))
                        listOf(listOf(0, 1, 2), listOf(0, 2, 3)).map { q -> org.json.JSONArray(q.flatMap { listOf(c[it][0], c[it][1], 0.0) } + 2) } }).toString()) }
    }

    /** per 25 yd cell (Roads.grid's cells): does a road texture show at any of its 6 x 6 points */
    private fun painted(art: File, map: Int, rect: MapHarvest.Rect): (Double, Double) -> Boolean {
        val dir = File(art, "worldmap/$map")
        val idx = JSONObject(File(dir, "index.json").readText())
        val bm = BitmapFactory.decodeFile(File(dir, "base.webp").path).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bm)
        val ovs = idx.optJSONArray("overlays")!!
        for (i in 0 until ovs.length()) { val o = ovs.getJSONObject(i); BitmapFactory.decodeFile(File(dir, "ov_${o.getInt("id")}.webp").path)?.let {
            c.drawBitmap(it, null, RectF(o.getInt("x").toFloat(), o.getInt("y").toFloat(), (o.getInt("x") + o.getInt("w")).toFloat(), (o.getInt("y") + o.getInt("h")).toFloat()), null) } }
        val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        return SafePath.paintedRoads(px, bm.width, bm.height, rect.t, rect.b, rect.l, rect.r)
    }

    /**
     * THE TEST ROUTES, SECOND ROUND (the user: "The whole skull rock path actually makes 0 sense, it goes straight into and up the
     * mountain instead of along the zeplin path and then into the skull rock cave"; "Some of the paths are still going through a wall"):
     * goals where players go - a cave's mouth (the ground at its own floor's height beside the cave WMO), a building, a camp, the
     * quest mobs' thickest spot - and every route the device's new way: the SafePath network as far as it helps, the ground's own leg
     * (Climb: the 4 yd lattice's A*, cliffs walls, the danger and deep water in) to the goal. Exported for the 3D and height-map renders.
     */

    /** yards of [w] along a lip: where the nearest lattice vertex is within 3 yd of a drop (Climb.dropsOf: the cliff's or the rim's lip
     *  vertex itself), sampled every half yard, off caves' floors (EDGE CLEARANCE's measure, the DEVICE lines) */
    private fun edgeYards(cg: Climb.Grid, w: List<DoubleArray>): Double {
        var tot = 0.0
        for ((pa, pb) in w.zipWithNext()) { if (Climb.inCave(pa) || Climb.inCave(pb)) continue
            val n = maxOf(1, (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) * 2).toInt())
            for (q in 0 until n) { val v = cg.at(pa[0] + (pb[0] - pa[0]) * q / n, pa[1] + (pb[1] - pa[1]) * q / n); if (cg.dropYd(v[0], v[1]) <= 3.0) tot += 0.5 } }
        return tot
    }
    /** an island's ground this big (lattice vertices: about 2.6 sq km) is the mainland's: no declared swim to it */
    private val SWIM_ISLAND_MAX = 150_000
    private fun levels2(net: SafePath.Net, rect: MapHarvest.Rect, tg: Terrain.Grid, heightV: (Int, Int) -> Float, roadAt: (Double, Double) -> Boolean,
                        zg: SafePath.Zones, areas: Map<Int, PlaceNames.Area>, spawns: List<QuestieNpcs.Spawn>, dir: File, waterAt: (Double, Double) -> Float, waterGrid: SafePath.Water?,
                        deckAt: (Double, Double) -> Boolean = { _, _ -> false }, mapName: (Int) -> String = { "" },
                        floorsAt: (Double, Double) -> DoubleArray = { _, _ -> DoubleArray(0) }, zoneArea: Int = 14, spec: JSONObject? = null, tag: String = "durotar", mine: Int = TransportRoutes.HORDE,
                        ar: SafePath.Areas? = null, map: Int = 1411, otherPlaces: List<Pair<String, DoubleArray>> = emptyList(),
                        rawDeep: ((Double, Double) -> Boolean)? = null, zi: ZoneInputs) {
        val S = Climb.STEP; val ORG = 32 * MapHarvest.ADT_YD
        // the lattice and what stands on it (ZoneInputs.Lattice, the device's own: the holes a building covers, the cells' middles, the
        // walkways' floor layers, the rooms no line cuts through)
        val zl = zi.Lattice(heightV)
        fun height(wx: Double, wy: Double): Float = zl.height(wx, wy)
        val ax = rect.l - rect.r; val ay = rect.t - rect.b
        fun uv(wx: Double, wy: Double) = doubleArrayOf((rect.l - wy) / ax, (rect.t - wx) / ay)
        // the holes a building's or a cave's floor covers (py/segcheck.py: no terrain there, the floor is the ground)
        File(out, "holes_$tag.json").writeText(org.json.JSONArray(zl.coveredHoles.map { org.json.JSONArray(listOf(it[0], it[1])) }).toString())
        val pad = SafePath.GRID_PAD_YD
        val layers = zl.layers
        File(out, "layers_$tag.json").writeText(org.json.JSONArray(layers.map { (k, z) -> org.json.JSONArray(listOf((k shr 32).toInt(), k.toInt(), z)) }).toString())
        val cg = zl.cg
        fun roomsAt(x: Double, y: Double) = zl.roomsAt(x, y)
        // EDGE CLEARANCE (Climb.dropsOf / edgeCost) on unless SAFEPATH_EDGES=0 (the before pictures); the lips (a vertex within 3 yd of a
        // drop) written for the renders
        val edgesOn = System.getenv("SAFEPATH_EDGES") != "0"
        File(out, "drops_$tag.json").writeText(org.json.JSONArray((0 until cg.nx * cg.ny).filter { cg.dropYd(it % cg.nx, it / cg.nx) <= 3.0 }
            .map { org.json.JSONArray(listOf(cg.gr0 + it / cg.nx, cg.gc0 + it % cg.nx)) }).toString())
        // each WMO's groups read once (the renders' rooms, the device's way)
        val wmoGroups = HashMap<Int, List<WorldBuildings.Group>>()
        val level = spec?.optInt("level", 8) ?: 8
        // the character's danger (the app's DangerInputs, the device's own): the hostile spawns' grid for this faction and level, the
        // zone's typical danger taken off (an all-red zone steers round its worst camps only), and a narrow stream waded
        // (DangerInputs.WADE_DANGER, as the mobs' middling danger: a drier way wins) and a building's rooms (BUILDING_DANGER) added
        val dz = DangerInputs.grid(spawns, rect, mine, level, "high", { wx, wy -> Terrain.factor(tg.at(wx, wy)) },
            { wx, wy -> ar?.uiMap(zg.areaAt(wx, wy)) == map })
        val g = dz.grid; val k = dz.k; val typical = dz.typical
        println("DANGER typical ${"%.2f".format(typical)} of ${"%.2f".format(g.max)}")
        println("DANGERINPUTS ${tag}: ${g.nx} x ${g.ny} cells, ${DangerInputs.hostile(spawns, mine).size} hostile of ${spawns.size} spawns, ${"%.1f".format(dz.ms)} ms")
        val wading: (Double, Double) -> Boolean = { x, y -> waterGrid?.wading(x, y) == true }
        val danger = dz.danger(wading, ::roomsAt)
        val calm = dz.calm(wading, ::roomsAt)
        // a bridge whose WMO floor is read (its groups dumped: Mulgore's Stonebull Lake bridge) is walked on its own floor, so the water
        // grid's bridge cells (the walkway's whole bounding box) are water again off that floor: the lake beside the deck is no bridge
        var floorCells: Set<Long> = emptySet()
        fun fkey(x: Double, y: Double) = Math.floorDiv(Math.round(x), 4L) * 100000 + Math.floorDiv(Math.round(y), 4L)
        // deep water, a wall (ZoneInputs.deep: off a walkway's deck, deeper than a ford or a narrow stream)
        val deep: (Double, Double) -> Boolean = zi.deep
        val reachedOf = HashMap<String, Boolean>()
        var links: List<Climb.Link> = emptyList()
        // the route sequence (the app's SafeRoute: the network's way, the wade retry, the detour cap), the device's own
        val sr = SafeRoute(cg, danger, calm, k, deep, waterAt, deckAt, rawDeep, edges = edgesOn)
        // the placed buildings and caves (the obj0s' MODF), tilted ones too (ZoneInputs.Lattice.placed)
        val placed = zl.placed
        // a mouth is where one can stand: off any wall face (the game's own triangles), a walkable step away from it
        val standable = zl.standable
        val gdir = File(dir, "wmogroup")
        fun groupsOf(fid: Int): List<ByteArray> = zl.groupsOf(fid)
        // the lifts (Caves.Lift): measured from their WMOs' own landings (py/liftfind.py), never estimated - the spec's "lifts"
        // DERIVED, as the device must (no lift typed into the app): the elevators' travels from TransportAnimation, each placed WMO's
        // floor searched for a landing straight over another at such a rise (Caves.findLifts). The spec's "lifts" (measured by hand
        // off-device) are only compared against
        val travels = File(System.getenv("DB2_DIR") ?: File(dir.parentFile, "db2").path, "TransportAnimation.db2").takeIf { it.isFile }?.let { f ->
            Caves.liftTravels(Db2(f.readBytes()).rows().mapNotNull { r -> val v = r.vec3(0) ?: return@mapNotNull null; (r.parent ?: return@mapNotNull null) to v }) } ?: emptyList()
        // only the buildings inside a capital's map (Thunder Bluff's mesas, their feet on Mulgore's ground; the Undercity): lifts are a city's
        val recipeRects = JSONObject(File(System.getenv("RECIPE")).readText()).optJSONObject("rects")
        val cityRects = com.jrees.wowcompanion.CityExits.CITIES.mapNotNull { c -> recipeRects?.optJSONArray(c.toString())?.let { a ->
            doubleArrayOf(a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4)) } }
        // the routes ride the spec's measured lifts while the derivation is being finished (a harness fixture, never in the app); else the derived
        val measured = spec?.optJSONArray("lifts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Caves.Lift(o.getString("name"), o.getDouble("x"), o.getDouble("y"), o.getDouble("zBottom"), o.getDouble("zTop")) } }
        // the WMOs whose floors are ways: the dump's manifest (a cave, a walkway, one asked for by id); without it, every dumped one
        val kinds = File(gdir, "kinds.txt").takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split(' ').takeIf { it.size >= 2 }?.let { it[0].toInt() to it[1] } }?.toMap()
        // ... and a capital's own buildings (Thunder Bluff's rises and lift landings), and those the spec names ("floorWmos")
        val floorWmos = spec?.optJSONArray("floorWmos")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        // a city walked on its own floors (the spec's "floorCity": Dalaran over Alterac) is no cave: its rock's underside meets the
        // crater's ground, and those "mouths" would join the city to the ground below
        val cityFids = spec?.optJSONObject("floorCity")?.optJSONArray("fids")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        // the caves and the lifts (ZoneInputs.Lattice.Caving: every placed WMO within 300 yd of the zone's rect whose groups are read)
        val zc = zl.Caving(travels, cityRects, kinds, floorWmos, cityFids, measured)
        val derivedLifts = zc.derivedLifts
        val lifts = zc.lifts
        val caves = zc.caves
        for (c in caves) println("CAVE ${c.fid} at ${c.wx.toInt()},${c.wy.toInt()} (uiMap ${ar?.uiMap(zg.areaAt(c.wx, c.wy))}, ${areas[zg.areaAt(c.wx, c.wy)]?.name}): ${c.mouths.size} mouths ${c.mouths.joinToString { "${it[0].toInt()},${it[1].toInt()} z ${it[2].toInt()}" }}; ${c.ways.size} ways ${c.ways.joinToString { "${it.yd.toInt()} yd" }}")
        links = caves.flatMap { c -> c.ways.map { Climb.Link(it.pts, it.yd) } }
        // AN AIR CHAIN (the spec's "airChain": Zephras' Rohashi Spires, floating): a portal from the ground to the lowest floor of the
        // place built in the air, its own floors (stairs, perimeter paths) up to a stair top, an updraft across to a platform, and the
        // way out (a portal back down). Manual links - the game keeps portals and updrafts server-side
        val manual = org.json.JSONArray()
        // the same links for the shipped network (NET1, ShippedNet.Link): points marked ground / floor / swim, never their heights
        val shipLinks = ArrayList<com.jrees.wowcompanion.ShippedNet.Link>()
        var platformAt: DoubleArray? = null
        spec?.optJSONObject("airChain")?.let { ac ->
            fun xyOf(k: String) = ac.optJSONArray(k)?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) }
            val fids = ac.getJSONArray("floor").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            val near = xyOf("near")!!
            val parts = placed.filter { it.fid in fids && Math.hypot(it.ox - near[0], it.oy - near[1]) < 600 }.map { p ->
                p to groupsOf(p.fid) }
            val fl = Caves.floor(parts)
            val pieceSize = fl.piece.toList().groupingBy { it }.eachCount()
            /** the floor at world xy: of the vertices within 6 yd, the one on the largest piece (a deck, a platform - not a ledge) */
            fun onLargest(x: Double, y: Double): Int = (0 until fl.size).filter { Math.hypot(fl.xs[it] - x, fl.ys[it] - y) < 6.0 }
                .maxWithOrNull(compareBy<Int> { pieceSize[fl.piece[it]] ?: 0 }.thenBy { -Math.hypot(fl.xs[it] - x, fl.ys[it] - y) }) ?: fl.near(x, y)
            val baseXY = xyOf("base")!!; val topXY = xyOf("stairTop")!!; val platXY = xyOf("platform")!!
            val base = fl.near(baseXY[0], baseXY[1], high = true)                  // the sanctuary's top floor
            val top = onLargest(topXY[0], topXY[1])                                // the Peacekeeper's deck
            val deckIn = (0 until fl.size).filter { fl.piece[it] == fl.piece[top] }.minByOrNull { Math.hypot(fl.xs[it] - fl.xs[base], fl.ys[it] - fl.ys[base]) }!!
            val plat = onLargest(platXY[0], platXY[1])                             // Lorthuna's platform
            fun pt(v: Int) = doubleArrayOf(fl.xs[v], fl.ys[v], fl.zs[v])
            val stairsRead = fl.path(base, deckIn)
            val deck = fl.path(deckIn, top)
            println("AIRCHAIN floor ${fl.size} vertices, ${pieceSize.size} pieces, ${parts.size} placements; sanctuary ${pt(base).map { it.toInt() }}; " +
                "deck (${pieceSize[fl.piece[top]]} vertices) from ${pt(deckIn).map { it.toInt() }} to the stair top ${pt(top).map { it.toInt() }}; " +
                "platform (${pieceSize[fl.piece[plat]]} vertices) ${pt(plat).map { it.toInt() }}; stairs ${if (stairsRead != null) "read: ${fl.length(stairsRead).toInt()} yd" else "NOT in the floors (a manual link)"}")
            val from = xyOf("portalFrom")!!; val exit = xyOf("exitTo")!!
            val chain = listOf(
                Triple("portal", listOf(doubleArrayOf(from[0], from[1], height(from[0], from[1]).toDouble()), pt(base)), true),
                Triple(if (stairsRead != null) "stairs" else "stairs (not read)", stairsRead ?: listOf(pt(base), pt(deckIn)), false),
                Triple("deck", deck ?: listOf(pt(deckIn), pt(top)), false),
                Triple("updraft", listOf(pt(top), pt(plat)), true),
                Triple("portal (out)", listOf(pt(plat), doubleArrayOf(exit[0], exit[1], height(exit[0], exit[1]).toDouble())), true))
            for ((kind, pts, oneWay) in chain) {
                if (pts.size < 2) continue
                val yd = if (oneWay) 15.0 else fl.length(pts)                  // a portal or an updraft: a few seconds
                links = links + Climb.Link(pts, yd, oneWay)
                manual.put(org.json.JSONObject().put("kind", kind).put("pts", org.json.JSONArray(pts.map { org.json.JSONArray(listOf(it[0], it[1], it[2])) })))
                // (a portal's ground end is the ground; every other point is on the place's own floors)
                shipLinks.add(com.jrees.wowcompanion.ShippedNet.Link(kind, "", oneWay, yd, pts.mapIndexed { q, p ->
                    val ground = (kind == "portal" && q == 0) || (kind == "portal (out)" && q == pts.size - 1)
                    doubleArrayOf(p[0], p[1], if (ground) 0.0 else Climb.IN_CAVE) }))
            }
            platformAt = doubleArrayOf(fl.xs[plat], fl.ys[plat])

        }
        val city = spec?.optJSONObject("floorCity")?.let { fc -> FloorCity.build(fc, placed.filter { it.fid in cityFids }, gdir) }
        floorCells = caves.flatMap { c -> c.floor.map { fkey(it[0], it[1]) } }.toHashSet()
        for (l in lifts) {
            val ws = caves.flatMap { c -> c.ways.filter { w -> w.pts.any { it.size > 3 && it[3] == 1.0 && Math.hypot(it[0] - l.x, it[1] - l.y) < Caves.LIFT_SNAP_YD + 3 } }.map { c to it } }
            println("LIFT ${l.name} at ${l.x.toInt()},${l.y.toInt()} z ${l.zBottom.toInt()} -> ${l.zTop.toInt()}: ${if (ws.isEmpty()) "NOT JOINED (no landing on a dumped floor)" else
                "${ws.size} ways through it, e.g. ${ws.take(3).joinToString { (c, w) -> "${c.fid} ${w.a[0].toInt()},${w.a[1].toInt()} z ${w.a[2].toInt()} -> ${w.b[0].toInt()},${w.b[1].toInt()} z ${w.b[2].toInt()} ${w.yd.toInt()} yd" }}"}")
        }
        /** a cave's mouth as a goal: the nearest to the start of those with walkable ground all round (no wall face within 2 yd: a
         *  den's opening, not a spot on its roof where the floor happens to meet a slope) */
        fun caveMouth(fid: Int, nearX: Double, nearY: Double, from: DoubleArray?): DoubleArray? {
            val cave = caves.filter { it.fid == fid }.minByOrNull { Math.hypot(it.wx - nearX, it.wy - nearY) } ?: return null
            fun open(m: DoubleArray) = listOf(2.0 to 0.0, -2.0 to 0.0, 0.0 to 2.0, 0.0 to -2.0).all { (dx, dy) ->
                cg.lineClear(doubleArrayOf(m[0], m[1]), doubleArrayOf(m[0] + dx, m[1] + dy)) }
            val ms = cave.mouths.filter { open(it) }.ifEmpty { cave.mouths }
            return ms.minByOrNull { m -> from?.let { Math.hypot(m[0] - it[0], m[1] - it[1]) } ?: 0.0 }?.let { doubleArrayOf(it[0], it[1]) }
        }
        fun wmo(fid: Int, nearX: Double, nearY: Double) = placed.filter { it.fid == fid }.minByOrNull { Math.hypot(it.ox - nearX, it.oy - nearY) }
        /** a cave's mouth: the level ground beside it at its floor's own height, the nearest to the cave */
        fun mouth(p: WorldBuildings.Placement): DoubleArray {
            val c = cg.at(p.ox, p.oy); val r = (160 / S).toInt()
            var best: DoubleArray? = null; var bs = Double.MAX_VALUE
            for (j in (c[1] - r)..(c[1] + r)) for (i in (c[0] - r)..(c[0] + r)) {
                if (i !in 0 until cg.nx || j !in 0 until cg.ny) continue
                if (cg.east(i, j) > Climb.CLIMB || cg.south(i, j) > Climb.CLIMB) continue
                val x = cg.wx(j); val y = cg.wy(i); val h = height(x, y)
                if (h.isNaN() || h < p.oz - 2) continue
                val sc = Math.abs(h - p.oz) + 0.04 * Math.hypot(x - p.ox, y - p.oy)
                if (sc < bs) { bs = sc; best = doubleArrayOf(x, y) }
            }
            return best ?: doubleArrayOf(p.ox, p.oy)
        }
        /** a subzone's quest mobs at their thickest: the hostile spawn with the most others within 60 yd */
        // a subzone's own ground by its AreaTable name (an island: the Westfall Lighthouse, Purgation Isle): the dry spot one can stand on
        // nearest the middle of its chunks
        // (a name as the spec says it and as AreaTable keeps it: "the Westfall Lighthouse" is "Westfall Lighthouse"; failing the whole
        // name, the one subzone it begins: "Theramore" is "Theramore Isle")
        fun plain(n: String) = n.trim().lowercase().removePrefix("the ").trim()
        fun areaSpot(name: String): DoubleArray? {
            val own = areas.entries.filter { it.value.parent == zoneArea || it.key == zoneArea }
            val a = (own.firstOrNull { plain(it.value.name) == plain(name) }
                ?: own.filter { plain(it.value.name).startsWith(plain(name) + " ") }.singleOrNull())?.key ?: return null
            val pts = ArrayList<DoubleArray>()
            var j = 0; while (j < cg.ny) { var i = 0; while (i < cg.nx) { val x = cg.wx(j); val y = cg.wy(i)
                if (zg.areaAt(x, y) == a) pts.add(doubleArrayOf(x, y)); i += 2 }; j += 2 }
            if (pts.isEmpty()) return null
            val mx = pts.sumOf { it[0] } / pts.size; val my = pts.sumOf { it[1] } / pts.size
            return pts.filter { standable(it[0], it[1]) && !deep(it[0], it[1]) }.minByOrNull { Math.hypot(it[0] - mx, it[1] - my) }
        }
        fun camp(name: String): DoubleArray? {
            val a = areas.entries.firstOrNull { plain(it.value.name) == plain(name) && it.value.parent == zoneArea }?.key ?: return null
            val pts = spawns.filter { it.hostile == mine || it.hostile == 3 }.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
                .filter { zg.areaAt(it[0], it[1]) == a && !deep(it[0], it[1]) && !height(it[0], it[1]).isNaN() }      // ground there (Un'Goro's crater is under the sea's level)
            return pts.maxByOrNull { p -> pts.count { Math.hypot(it[0] - p[0], it[1] - p[1]) < 60 } }
        }
        // a node by its name, or by its base name when towns sharing it were told apart ("Deathknell · Inn", "Gustberry Lowlands (2)")
        // ... or, a town that is no hub for this faction (the other side's: Theramore for the Horde), the place itself: its subzone's ground
        // ... or, a town that is no hub for this faction (the other side's: Theramore for the Horde), the other side's own town as its
        /** the walkable ground round world [p]: the lattice vertices a walk from it reaches within 40 yd, off walls and deep water */
        fun openGround(p: DoubleArray): Int {
            val v0 = cg.at(p[0], p[1]); val seen = HashSet<Long>(); val q = ArrayDeque<IntArray>(); q.add(v0); seen.add(v0[0].toLong() * 100000 + v0[1])
            val r = (40 / S).toInt()
            while (q.isNotEmpty() && seen.size < 4000) { val (i, j) = q.removeFirst()
                for (di in -1..1) for (dj in -1..1) { if (di == 0 && dj == 0) continue
                    val ni = i + di; val nj = j + dj; val k = ni.toLong() * 100000 + nj
                    if (k in seen || Math.abs(ni - v0[0]) > r || Math.abs(nj - v0[1]) > r || cg.step(i, j, di, dj) == Climb.WALL || deep(cg.wx(nj), cg.wy(ni))) continue
                    seen.add(k); q.add(intArrayOf(ni, nj)) } }
            return seen.size
        }
        // draft places it (otherPlaces), else the place itself: its subzone's ground
        /** a town of the other side's as a route's end: its spot, or - when the ground from there (the lattice's dry steps, no wall)
         *  is a pocket (Auberdine's hub spot on its terrace, Dolanaar's subzone middle) - the nearest spot within 60 yd on open ground */
        fun openSpot(p: DoubleArray): DoubleArray {
            val nb = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1); val want = 3000
            fun reach(i0: Int, j0: Int): Int {
                if (i0 !in 0 until cg.nx || j0 !in 0 until cg.ny || deep(cg.wx(j0), cg.wy(i0))) return 0
                val seen = HashSet<Long>(); val qq = ArrayDeque<IntArray>(); seen.add((j0.toLong() shl 32) or i0.toLong()); qq.add(intArrayOf(i0, j0))
                while (qq.isNotEmpty() && seen.size < want) { val (i, j) = qq.removeFirst()
                    for ((di, dj) in nb) { val ni = i + di; val nj = j + dj; val k = (nj.toLong() shl 32) or ni.toLong()
                        if (ni !in 0 until cg.nx || nj !in 0 until cg.ny || k in seen || deep(cg.wx(nj), cg.wy(ni)) || cg.step(i, j, di, dj) == Climb.WALL) continue
                        seen.add(k); qq.add(intArrayOf(ni, nj)) } }
                return seen.size
            }
            val v = cg.at(p[0], p[1]); if (reach(v[0], v[1]) >= want) return p
            val r = (60 / Climb.STEP).toInt()
            val best = (-r..r).flatMap { dj -> (-r..r).map { di -> intArrayOf(v[0] + di, v[1] + dj) } }
                .sortedBy { Math.hypot((it[0] - v[0]).toDouble(), (it[1] - v[1]).toDouble()) }.firstOrNull { reach(it[0], it[1]) >= want } ?: return p
            val q = doubleArrayOf(cg.wx(best[1]), cg.wy(best[0]))
            println("GOALPLACE ${p[0].toInt()},${p[1].toInt()} is a pocket: the open ground at ${q[0].toInt()},${q[1].toInt()} taken")
            return q
        }
        fun at(name: String): DoubleArray? = (net.nodes.firstOrNull { it.name?.trim() == name.trim() } ?: net.nodes.firstOrNull { it.name?.trim()?.startsWith("${name.trim()} ") == true })
            ?.let { doubleArrayOf(it.wx, it.wy) }
            ?: run {
                // the other side's town spot, or its subzone's ground: whichever opens onto more walkable ground round it (Auberdine's town
                // spot boxed the Horde in; Dolanaar's subzone middle did)
                val town = (otherPlaces.firstOrNull { it.first.trim() == name.trim() } ?: otherPlaces.firstOrNull { it.first.trim().startsWith("${name.trim()} ") })?.second?.let { openSpot(it) }
                val sub = areaSpot(name.substringBefore(" ·").substringBefore(" (").trim())?.let { openSpot(it) }
                val pick = listOfNotNull(town, sub).maxByOrNull { openGround(it) }
                pick?.also { println("GOALPLACE $name: ${if (pick === town) "the other side's town" else "its subzone's ground"} at ${it[0].toInt()},${it[1].toInt()} (open ${openGround(it)})") } }
        /** a named mob's spawns at their thickest (near [near] when given: within 500 yd of it) */
        fun mob(name: String, near: DoubleArray?): DoubleArray? {
            val pts = spawns.filter { it.name.equals(name, true) }.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
                .filter { !deep(it[0], it[1]) && (near == null || Math.hypot(it[0] - near[0], it[1] - near[1]) < 500) }
            return pts.maxByOrNull { p -> pts.count { Math.hypot(it[0] - p[0], it[1] - p[1]) < 60 } }
        }
        /** a goal's exit by its name (and [how]): the checked network's first (its tunnel and ground exits too), else the draft's (an exit
         *  the ground check dropped: the goal stays where it was, and the line says so) */
        var goalNet: SafePath.Net = net
        fun exitNamed(name: String, how: String? = null): SafePath.Node? {
            fun inNet(n: SafePath.Net) = n.exits.firstOrNull { it.name.equals(name, true) && (how == null || it.how == how) }?.let { x -> n.nodes.firstOrNull { it.id == x.node } }
            // (its kind when the spec names one; else, one exit a stretch now, any of that name: Elwynn's Stormwind "bridge" is its gate's)
            return inNet(goalNet) ?: inNet(net)?.also { println("GOALEXIT $name${how?.let { " ($it)" } ?: ""}: dropped by the ground check, its draft spot ${it.wx.toInt()},${it.wy.toInt()} kept as the goal") }
                ?: if (how != null) exitNamed(name, null)?.also { println("GOALEXIT $name ($how): none of that kind, the stretch's exit taken") } else null
        }
        /** where an exit's way opens onto this zone's own level ground (a gate's tunnel or a lift is the other map's: its top not ours) */
        fun exitStart(name: String): DoubleArray? = exitNamed(name)?.let { gn ->
            val c = cg.at(gn.wx, gn.wy); val r = (150 / S).toInt(); var best: DoubleArray? = null; var bd = Double.MAX_VALUE
            for (j in (c[1] - r)..(c[1] + r)) for (i in (c[0] - r)..(c[0] + r)) {
                if (i !in 1 until cg.nx - 1 || j !in 1 until cg.ny - 1) continue
                if (cg.east(i, j) != Climb.LEVEL || cg.south(i, j) != Climb.LEVEL || cg.east(i - 1, j) != Climb.LEVEL || cg.south(i, j - 1) != Climb.LEVEL) continue
                val x = cg.wx(j); val y = cg.wy(i); if (ar != null && ar.uiMap(zg.areaAt(x, y)) != map) continue
                val d = Math.hypot(x - gn.wx, y - gn.wy); if (d < bd) { bd = d; best = doubleArrayOf(x, y) }
            }
            best }
        fun xy(a: org.json.JSONArray?) = a?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) }
        val exact = ArrayList<DoubleArray>()
        // "within": how far a start or a goal may move onto ground one can stand on (a boat's or a zeppelin's stop is beside its pier,
        // over the water: the cross-zone routes' rides) - 15 yd otherwise
        val within = java.util.IdentityHashMap<DoubleArray, Double>()
        /** a spot over a cave's floor, the terrain well above it (Dun Morogh's Rockjaw Raiders, height 514 over the Coldridge tunnel):
         *  the creatures are in the cave - the goal is its nearest mouth */
        fun inCaveMouth(p: DoubleArray): DoubleArray? {
            val h = height(p[0], p[1]); if (h.isNaN()) return null
            for (c in caves) {
                val under = c.floor.filter { Math.hypot(it[0] - p[0], it[1] - p[1]) < 8.0 }.minByOrNull { it[2] } ?: continue
                if (h - under[2] < 8.0) continue
                val m = c.mouths.minByOrNull { Math.hypot(it[0] - p[0], it[1] - p[1]) } ?: continue
                println("GOAL over cave ${c.fid}'s floor (terrain ${h.toInt()}, floor ${under[2].toInt()}): its mouth ${m[0].toInt()},${m[1].toInt()}")
                return doubleArrayOf(m[0], m[1])
            }
            return null
        }
        fun specTrips(goals: org.json.JSONArray): List<Triple<String, DoubleArray?, Pair<String, DoubleArray?>>> = (0 until goals.length()).map { gi ->
            val o = goals.getJSONObject(gi); val fr = o.getJSONObject("from"); val to = o.getJSONObject("to")
            val f = when { fr.has("node") -> at(fr.getString("node")); fr.has("exit") -> exitStart(fr.getString("exit")); else -> xy(fr.optJSONArray("xy")) }
            val near = xy(to.optJSONArray("near"))
            val gp = when {
                to.has("cave") -> caveMouth(to.getInt("cave"), near!![0], near[1], f) ?: wmo(to.getInt("cave"), near[0], near[1])?.let { mouth(it) }
                to.has("wmo") -> wmo(to.getInt("wmo"), near!![0], near[1])?.let { doubleArrayOf(it.ox, it.oy) }
                // (a camp of none hostile to this side - the other side's town, Zoram'gar Outpost for the Horde: the town itself)
                to.has("camp") -> camp(to.getString("camp")) ?: at(to.getString("camp"))?.also { println("GOALCAMP ${to.getString("camp")}: no mobs of this side's foes there - the town itself") }
                to.has("mob") -> mob(to.getString("mob"), near)
                to.has("node") -> at(to.getString("node"))
                to.has("area") -> areaSpot(to.getString("area"))
                to.optBoolean("airChainTop") -> platformAt
                to.has("exit") -> exitNamed(to.getString("exit"), if (to.has("how")) to.getString("how") else null)?.let { doubleArrayOf(it.wx, it.wy) }
                else -> xy(to.optJSONArray("xy"))
            }
            if (f == null || gp == null) println("GOAL ${o.optString("name")}: from ${f?.toList()} to ${gp?.toList()} - not found, left out")
            // "exact": the goal is where it says, never moved to a cave under it (Thunder Bluff's trainers stand on Spirit Rise, over the undead cave)
            if (gp != null && to.optBoolean("exact")) exact.add(gp)
            if (f != null && fr.has("within")) within[f] = fr.getDouble("within")
            if (gp != null && to.has("within")) within[gp] = to.getDouble("within")
            Triple(fr.optString("name", fr.optString("node", fr.optString("exit"))), f, o.optString("name") to gp)
        }
        fun durotarTrips(): List<Triple<String, DoubleArray?, Pair<String, DoubleArray?>>> {
        // the gate's tunnel is the city's WMO, the terrain over it the mountain: start where it opens onto Durotar's own level ground
        val gate = net.exits.firstOrNull { it.name == "Orgrimmar" }?.let { x -> net.nodes.first { it.id == x.node } }?.let { gn ->
            val c = cg.at(gn.wx, gn.wy); val r = (150 / S).toInt(); var best: DoubleArray? = null; var bd = Double.MAX_VALUE
            for (j in (c[1] - r)..(c[1] + r)) for (i in (c[0] - r)..(c[0] + r)) {
                if (i !in 1 until cg.nx - 1 || j !in 1 until cg.ny - 1) continue
                if (cg.east(i, j) != Climb.LEVEL || cg.south(i, j) != Climb.LEVEL || cg.east(i - 1, j) != Climb.LEVEL || cg.south(i, j - 1) != Climb.LEVEL) continue
                val x = cg.wx(j); val y = cg.wy(i); if (!(height(x, y) < 40f) || x > gn.wx) continue
                val d = Math.hypot(x - gn.wx, y - gn.wy); if (d < bd) { bd = d; best = doubleArrayOf(x, y) }
            }
            println("GATE exit node ${gn.wx.toInt()},${gn.wy.toInt()} h ${height(gn.wx, gn.wy).toInt()}; start ${best?.let { "${it[0].toInt()},${it[1].toInt()} h ${height(it[0], it[1]).toInt()}" }}")
            best }
        val rh = at("Razor Hill")!!; val sj = at("Sen'jin Village"); val vt = at("Valley of Trials")
        return listOf(
            Triple("Razor Hill", rh, "the Razor Hill watchtower" to wmo(113091, 388.0, -4591.0)?.let { doubleArrayOf(it.ox, it.oy) }),
            Triple("Razor Hill", rh, "Thunder Ridge's lizards" to camp("Thunder Ridge")),
            Triple("Razor Hill", rh, "Dustwind Cave's mouth (Drygulch)" to (caveMouth(110589, 843.0, -4705.0, rh) ?: wmo(110589, 843.0, -4705.0)?.let { mouth(it) })),
            Triple("Razor Hill", rh, "Tiragarde Keep" to wmo(112746, -166.0, -5111.0)?.let { doubleArrayOf(it.ox, it.oy) }),
            Triple("Sen'jin Village", sj, "the Kolkar camp (Kolkar Crag)" to wmo(113293, -967.0, -4419.0)?.let { doubleArrayOf(it.ox, it.oy) }),
            Triple("the Orgrimmar gate", gate, "Skull Rock's cave mouth" to (caveMouth(111290, 1504.0, -4854.0, gate) ?: wmo(111290, 1504.0, -4854.0)?.let { mouth(it) })),
            Triple("Razor Hill", rh, "the Razormane camp" to camp("Razormane Grounds")),
            Triple("Sen'jin Village", sj, "Darkspear Strand's mobs" to camp("Darkspear Strand")),
            Triple("Razor Hill", rh, "Drygulch Ravine's deep camp" to camp("Drygulch Ravine")),
            Triple("Valley of Trials", vt, "the Burning Blade Coven's mouth" to (caveMouth(111290, -135.0, -4371.0, vt) ?: wmo(111290, -135.0, -4371.0)?.let { mouth(it) })),
        )
        }
        /** a start or a goal on ground one can stand on: itself, or the nearest such spot within 15 yd (a camp's spawn on a slope) */
        fun stand(p0: DoubleArray?): DoubleArray? {
            val p = p0?.let { if (exact.any { e -> e === it }) it else inCaveMouth(it) ?: it }
            if (p == null || (standable(p[0], p[1]) && !deep(p[0], p[1]))) return p
            val c = cg.at(p[0], p[1]); val r = ((p0?.let { within[it] } ?: 15.0) / S).toInt(); var best: DoubleArray? = null; var bd = Double.MAX_VALUE
            for (j in (c[1] - r)..(c[1] + r)) for (i in (c[0] - r)..(c[0] + r)) {
                if (i !in 0 until cg.nx || j !in 0 until cg.ny) continue
                val x = cg.wx(j); val y = cg.wy(i); val d = Math.hypot(x - p[0], y - p[1])
                if (d < bd && standable(x, y) && !deep(x, y)) { bd = d; best = doubleArrayOf(x, y) }
            }
            return best ?: p
        }
        // ROADS ARE MADE TO BE WALKED (the user: "where the rule might trip up is when there are actual mountains that need to be
        // climbed or actually have a path up"): of the zone's own road-textured vertices, how many stand on a wall face
        run {
            var onRoad = 0; var walled = 0; val where = ArrayList<String>()
            for (j in 0 until cg.ny) for (i in 0 until cg.nx) {
                val x = cg.wx(j); val y = cg.wy(i)
                if (!roadAt(x, y) || (ar != null && ar.uiMap(zg.areaAt(x, y)) != map)) continue
                onRoad++
                if (!cg.lineClear(doubleArrayOf(x, y), doubleArrayOf(x, y))) { walled++; if (where.size < 12) where.add("${x.toInt()},${y.toInt()} h ${height(x, y).toInt()}") }
            }
            println("ROADWALLS $onRoad road-textured vertices, $walled on a wall face (${if (onRoad > 0) walled * 1000 / onRoad / 10.0 else 0.0}%): $where")
        }
        val clear = SafePath.clearOf { a, b -> Terrain.factor(tg.at(a, b)) }
        // (the ground's own length joining a walled way's ends: a reachability check, measured with no EDGE CLEARANCE - a comfort cost
        // must not drop or keep the network's ways; Mulgore G lost a way and looped 376 yd round)
        val netW = SafePath.dropWalled(net, { a, b -> Climb.walkable(cg, a, b, deep) }) { a, b ->
            Climb.route(cg, a, b, water = deep, edges = false)?.takeIf { it.reached }?.pts?.zipWithNext { p, q -> Math.hypot(q[0] - p[0], q[1] - p[1]) }?.sum() }
        println("DROPWALLED ${netW.edges.count { it.state == SafePath.REMOVED } - net.edges.count { it.state == SafePath.REMOVED }} drafted ways cross a wall on the fine ground: removed; " +
            "${netW.edges.count { it.state == SafePath.REWALK }} kept, walked by the ground's leg (a lip)")
        val uiAt: (Double, Double) -> Int = { x, y -> ar?.uiMap(zg.areaAt(x, y)) ?: 0 }
        val netCv = SafePath.withCaves(netW, caves, { x, y -> danger(x, y) / DangerGrid.CAP }, uiAt, mapName)
        // THE EXITS CHECKED ON THE GROUND, the app's own check (SafePath.Ground / checkExits, as the device runs it on its terrain step):
        // from each, the lattice flooded (no wall step, no deep water, the caves' links taken) - a "walk" exit whose crossing is not
        // walkable (a pocket on either side, the neighbour not reached within EXIT_NEAR_YD) is false and goes with its dead-end way;
        // then the ground's own crossings (a walkable corridor over the border, no road) become exits too
        val gr = SafePath.Ground(cg, map, uiAt, { x, y -> !deep(x, y) }, links)
        val netK = SafePath.checkExits(netCv, gr)
        // a neighbour's own map: its world rect (worldmap.json's rects, as the device's MapHarvest has them)
        val onMap: (Int, Double, Double) -> Boolean = { m, x, y -> recipeRects?.optJSONArray(m.toString())?.let { r ->
            x <= r.getDouble(1) && x >= r.getDouble(2) && y <= r.getDouble(3) && y >= r.getDouble(4) } ?: true }
        val netC = SafePath.groundExits(netK, gr, mapName, onMap = onMap) { println("GROUNDCROSS $it") }
        println("EXITS added: ${netCv.exits.size - netW.exits.size} through tunnels, ${netC.exits.size - netK.exits.size} on the ground's own crossings; " +
            "${netCv.exits.size - netK.exits.size} false walk exits dropped (${netCv.exits.filter { x -> netK.exits.none { it.node == x.node } }.joinToString { x ->
                netCv.nodes.first { it.id == x.node }.let { "${x.name} at ${it.wx.toInt()},${it.wy.toInt()}" } }})")
        val exitChecks = org.json.JSONArray()
        for (x in netCv.exits + netC.exits.filter { e -> netCv.exits.none { it.node == e.node } }) {
            val node = netC.nodes.firstOrNull { it.id == x.node } ?: netCv.nodes.firstOrNull { it.id == x.node } ?: continue
            val c = gr.check(node.wx, node.wy, x.toMap)
            val kept = netC.exits.any { it.node == x.node }
            println("EXIT ${x.name} (${x.how}) at ${node.wx.toInt()},${node.wy.toInt()} h ${height(node.wx, node.wy).toInt()}: " +
                "reaches ${(c.ownShare * 100).toInt()}% of this zone, ${(c.theirsSqYd / 1000).toInt()}k sq yd of ${x.name}'s ground within ${SafePath.EXIT_NEAR_YD.toInt()} yd of it${if (node.src == "ground") " [a ground exit]" else ""}${if (kept) "" else " [dropped]"} - " +
                if (c.walkable) "WALKABLE" else "NOT WALKABLE")
            exitChecks.put(org.json.JSONArray(listOf(node.wx, node.wy, x.name, x.how, c.walkable, (c.ownShare * 100).toInt(), (c.theirsSqYd / 1000).toInt(), kept, node.src)))
        }
        exitSheet(cg, gr, netC, tag, mapName, deep)
        // the goals, now that the exits are checked: an {"exit": name} goal can name a tunnel or a ground exit
        goalNet = netC
        val trips = (spec?.optJSONArray("goals")?.let { specTrips(it) } ?: durotarTrips()).filter { it.second != null && it.third.second != null }
            .map { (fn, f, g) -> Triple(fn, stand(f), g.first to stand(g.second)) }
        println("NET with caves: ${netC.nodes.size} nodes, ${netC.edges.size} edges (${netC.edges.count { it.kind == SafePath.CAVE }} cave)")
        // PROBES (a spec's "probes": [[x1, y1, x2, y2], ...]): a leg between two spots with and without the water, for finding what walls
        // a zone in two
        spec?.optJSONArray("probes")?.let { a -> for (k in 0 until a.length()) { val q = a.getJSONArray(k)
            val pa = doubleArrayOf(q.getDouble(0), q.getDouble(1)); val pb = doubleArrayOf(q.getDouble(2), q.getDouble(3))
            val withW = Climb.route(cg, pa, pb, water = deep); val noW = Climb.route(cg, pa, pb)
            val v0 = cg.at(pa[0], pa[1])
            println("PROBE start vertex ${cg.wx(v0[1]).toInt()},${cg.wy(v0[0]).toInt()}: neighbours " + (0 until 8).joinToString(" ") { q ->
                val di = listOf(1, 1, 0, -1, -1, -1, 0, 1)[q]; val dj = listOf(0, 1, 1, 1, 0, -1, -1, -1)[q]
                val x = cg.wx(v0[1] + dj); val y = cg.wy(v0[0] + di)
                "${cg.step(v0[0], v0[1], di, dj)}/${"%.1f".format(waterAt(x, y))}/${if (deep(x, y)) "D" else "-"}" } +
                "; always-dry water: ${Climb.route(cg, pa, pb, water = { _, _ -> false })?.reached}")
            run { val hits = ArrayList<String>(); var calls = 0
                Climb.route(cg, pa, pb, water = { x, y -> calls++; deep(x, y).also { if (it && hits.size < 8) hits.add("${"%.1f".format(x)},${"%.1f".format(y)} w ${waterAt(x, y)}") } })
                println("PROBE the water the route asked: $calls calls, deep at ${hits}")
                println("PROBE the dry way: " + (noW?.pts ?: emptyList()).zipWithNext().joinToString(" ") { (u, v) -> val n = maxOf(1, (Math.hypot(v[0] - u[0], v[1] - u[1]) * 2).toInt())
                    "${u[0].toInt()},${u[1].toInt()}" + ((0..n).map { t -> val x = u[0] + (v[0] - u[0]) * t / n; val y = u[1] + (v[1] - u[1]) * t / n; x to y }.firstOrNull { deep(it.first, it.second) }?.let { "[DEEP ${it.first.toInt()},${it.second.toInt()} ${waterAt(it.first, it.second)}]" } ?: "") }) }
            println("PROBE the faces along the line (class at the point / gentlest within a quarter yard / 45-48 bit), a yard apart: " +
                (0..Math.hypot(pb[0] - pa[0], pb[1] - pa[1]).toInt()).joinToString(" ") { t -> val n = Math.hypot(pb[0] - pa[0], pb[1] - pa[1]).coerceAtLeast(1e-9)
                    val x = pa[0] + (pb[0] - pa[0]) * t / n; val y = pa[1] + (pb[1] - pa[1]) * t / n
                    "${cg.faceAt(x, y)}${minOf(cg.faceAt(x, y), cg.faceAt(x + 0.25, y), cg.faceAt(x - 0.25, y), cg.faceAt(x, y + 0.25), cg.faceAt(x, y - 0.25))}${if (cg.short48(x, y)) "s" else ""}" } +
                "; lineClear ${cg.lineClear(pa, pb)}; walkable ${Climb.walkable(cg, pa, pb, deep)}")
            println("PROBE the vertices around the start (depth / D deep / class of the step east, south), north row first: | " + (-6..6).joinToString(" | ") { dj -> (-6..6).joinToString(" ") { di ->
                val vi = v0[0] + di; val vj = v0[1] + dj; val x = cg.wx(vj); val y = cg.wy(vi); val w = waterAt(x, y)
                (if (w.isNaN()) "  . " else "%4.1f".format(w)) + (if (deep(x, y)) "D" else " ") + cg.step(vi, vj, -1, 0) + cg.step(vi, vj, 0, 1) } })
            println("PROBE ${pa.map { it.toInt() }} -> ${pb.map { it.toInt() }}: with water ${withW?.reached} (ends ${withW?.pts?.last()?.map { it.toInt() }}), without ${noW?.reached}" +
                "; deep cells on the straight line ${(0..40).count { t -> deep(pa[0] + (pb[0] - pa[0]) * t / 40, pa[1] + (pb[1] - pa[1]) * t / 40) }}/41; depths " +
                (0..20).joinToString(" ") { t -> waterAt(pa[0] + (pb[0] - pa[0]) * t / 20, pa[1] + (pb[1] - pa[1]) * t / 20).let { if (it.isNaN()) "." else "%.1f".format(it) } } +
                "; around the start: " + (-2..2).joinToString(" | ") { dx -> (-2..2).joinToString(" ") { dy -> waterAt(pa[0] + dx * 4.17, pa[1] + dy * 4.17).let { if (it.isNaN()) "." else "%.1f".format(it) } } }) } }
        // DECLARED SWIMS (the spec's "swims"; the user, 2026-10-18, Fenris Isle: "Swim link, flagged"): an island no walk reaches. Its
        // ground (the lattice's open steps off deep water from the spec's spot on it), then the shortest swim from it to the mainland's
        // ground (through the water only, onto a shore step that is no wall): one link, its points marked SWIM. Offered only to a route
        // whose goal is on the island - never a shortcut elsewhere
        class Swim(val name: String, val island: Set<Long>, val link: Climb.Link, val from: DoubleArray, val to: DoubleArray)
        val swims = ArrayList<Swim>()
        spec?.optJSONArray("swims")?.let { a -> for (q in 0 until a.length()) {
            val o = a.getJSONObject(q); val isl = o.getJSONObject("island")
            val p0 = (if (isl.has("node")) at(isl.getString("node")) else if (isl.has("area")) areaSpot(isl.getString("area")) else xy(isl.optJSONArray("xy"))) ?: run { println("SWIM ${o.optString("name")}: its island's spot not found"); null } ?: continue
            fun key(i: Int, j: Int) = (j.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
            fun dryAt(i: Int, j: Int) = i in 0 until cg.nx && j in 0 until cg.ny && !deep(cg.wx(j), cg.wy(i))
            val nb = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
            fun flood(i0: Int, j0: Int, cap: Int): Set<Long> {
                val seen = HashSet<Long>(); val qq = ArrayDeque<IntArray>(); seen.add(key(i0, j0)); qq.add(intArrayOf(i0, j0))
                while (qq.isNotEmpty() && seen.size < cap) { val (i, j) = qq.removeFirst()
                    for ((di, dj) in nb) { val ni = i + di; val nj = j + dj; val k = key(ni, nj)
                        if (k in seen || !dryAt(ni, nj) || cg.step(i, j, di, dj) == Climb.WALL) continue
                        seen.add(k); qq.add(intArrayOf(ni, nj)) } }
                return seen
            }
            val v0 = cg.at(p0[0], p0[1]); val island = flood(v0[0], v0[1], 400_000)
            // an "island" a walk reaches the mainland from is none: no swim offered (the review's M8)
            if (island.size >= SWIM_ISLAND_MAX) { println("SWIM ${o.optString("name")}: its ground (${island.size} vertices) joins the mainland - no swim"); continue }
            // the swim: from the island's ground through the water, nearest first, to ground of a far bigger piece - or, when the spec names
            // its "shore" (a spot on a small beach a walk reaches, as Faldir's Cove's tunnel beach), to that piece's ground
            val shore = o.optJSONObject("shore")?.let { xy(it.optJSONArray("xy")) }?.let { s -> val v = cg.at(s[0], s[1]); flood(v[0], v[1], 400_000) }
                ?.takeIf { it.isNotEmpty() && it.none { k -> k in island } }
            if (o.has("shore") && shore == null) println("SWIM ${o.optString("name")}: its shore's ground not found (or the island's own) - the mainland instead")
            val dist = HashMap<Long, Double>(); val prev = HashMap<Long, Long>()
            val pq = java.util.PriorityQueue<Pair<Double, Long>>(compareBy { it.first })
            for (k in island) { val i = k.toInt(); val j = (k shr 32).toInt()
                for ((di, dj) in nb) if (!dryAt(i + di, j + dj)) { dist[k] = 0.0; pq.add(0.0 to k); break } }
            var landed: Long? = null
            while (pq.isNotEmpty()) { val (d, k) = pq.poll(); if (d > (dist[k] ?: Double.MAX_VALUE)) continue
                if (d > 1500.0) break
                val i = k.toInt(); val j = (k shr 32).toInt()
                for ((di, dj) in nb) { val ni = i + di; val nj = j + dj; val nk = key(ni, nj)
                    if (ni !in 0 until cg.nx || nj !in 0 until cg.ny || nk in island) continue
                    val nd = d + Climb.STEP * Math.hypot(di.toDouble(), dj.toDouble())
                    if (dryAt(ni, nj)) {
                        if (cg.step(i, j, di, dj) == Climb.WALL || deep(cg.wx(j), cg.wy(i)).not()) continue
                        if (shore != null) { if (nk !in shore) continue }
                        else if (flood(ni, nj, 20_000).size < 20_000) continue        // another islet, not the mainland
                        prev[nk] = k; dist[nk] = nd; landed = nk; break
                    }
                    if (nd < (dist[nk] ?: Double.MAX_VALUE)) { dist[nk] = nd; prev[nk] = k; pq.add(nd to nk) }
                }
                if (landed != null) break
            }
            val land = landed ?: run { println("SWIM ${o.optString("name")}: no shore of the mainland within 1500 yd of the island"); null } ?: continue
            val path = ArrayList<Long>(); var c: Long? = land; while (c != null) { path.add(c); c = prev[c] }; path.reverse()
            val pts = path.map { k -> doubleArrayOf(cg.wx((k shr 32).toInt()), cg.wy(k.toInt()), 0.0, 2.0) }
            val yd = pts.zipWithNext { u, w -> Math.hypot(w[0] - u[0], w[1] - u[1]) }.sum()
            swims.add(Swim(o.optString("name"), island, Climb.Link(pts, yd), pts.first(), pts.last()))
            println("SWIM ${o.optString("name")}: the island's ground ${island.size} vertices; the swim ${yd.toInt()} yd from ${pts.first()[0].toInt()},${pts.first()[1].toInt()} to ${pts.last()[0].toInt()},${pts.last()[1].toInt()}")
            run { val ix = island.map { cg.wx((it shr 32).toInt()) }; val iy = island.map { cg.wy(it.toInt()) }
                val cx = ix.average(); val cy = iy.average(); val r = ix.indices.maxOf { Math.hypot(ix[it] - cx, iy[it] - cy) }
                shipLinks.add(com.jrees.wowcompanion.ShippedNet.Link("swim", o.optString("name"), false, yd,
                    pts.map { doubleArrayOf(it[0], it[1], Climb.SWIM) }, doubleArrayOf(cx, cy, r))) }
            manual.put(org.json.JSONObject().put("kind", "swim").put("name", o.optString("name")).put("pts", org.json.JSONArray(pts.map { org.json.JSONArray(listOf(it[0], it[1], 0.0)) })))
        } }
        val baseLinks = links
        // the buildings for the renders: each placement's rooms (WorldBuildings.feet, the solid footprints) and its whole box, by file id
        run { val rooms = org.json.JSONArray()
            fun corners(f: com.jrees.wowcompanion.Buildings.Foot) = listOf(f.lx0 to f.ly0, f.lx1 to f.ly0, f.lx1 to f.ly1, f.lx0 to f.ly1).flatMap { (a, b2) -> listOf(f.worldX(a, b2), f.worldY(a, b2)) }
            for (p in placed) { if (p.ox > rect.t + 100 || p.ox < rect.b - 100 || p.oy > rect.l + 100 || p.oy < rect.r - 100) continue
                val gs = wmoGroups.getOrPut(p.fid) { File(dir, "wmo/${p.fid}").takeIf { it.isFile }?.readBytes()?.let { WorldBuildings.groups(it) } ?: emptyList() }
                if (gs.isEmpty()) continue
                for (f in WorldBuildings.feet(p, gs) { wx, wy -> height(wx, wy).toDouble() }) rooms.put(org.json.JSONArray(listOf(p.fid, 1) + corners(f)))
                val cs = Math.cos(p.yaw); val sn = Math.sin(p.yaw)
                val all = com.jrees.wowcompanion.Buildings.Foot(p.ox, p.oy, cs, sn, gs.minOf { minOf(it.box[0], it.box[3]) } * p.scale, gs.maxOf { maxOf(it.box[0], it.box[3]) } * p.scale,
                    gs.minOf { minOf(it.box[1], it.box[4]) } * p.scale, gs.maxOf { maxOf(it.box[1], it.box[4]) } * p.scale)
                rooms.put(org.json.JSONArray(listOf(p.fid, 0) + corners(all)))
                WorldBuildings.standing(p, gs) { wx, wy -> height(wx, wy).toDouble() }?.let { rooms.put(org.json.JSONArray(listOf(p.fid, 2) + corners(it))) } }
            File(out, "rooms_$tag.json").writeText(rooms.toString()) }
        // THE DEVICE'S OWN WAY TODAY (no lattice on the device yet: MapView.netLeg, else MapView.dangerLeg): the drafted network by
        // SafePath.route (its straight walks on and off, its smooth()'s cuts over level 25 yd cells), else the 25 yd danger grid's leg with
        // the terrain step's building cover (Buildings: a cell 55% covered a wall but within 40 yd of the start and the goal, 20% dearer)
        // - drawn beside each route (DEVICE lines, "device" in the export) to show what a player on the device walks
        // (the before run, SAFEPATH_EDGES=0, is 2d37f15's device: Terrain.factor, every outdoor group a leveled street; after: Terrain.legFactor,
        // an outdoor-only building - WorldBuildings.standing - no street but a part-covered cell, Terrain.STAND_COVER)
        val devNew = edgesOn
        val devWalks = ArrayList<com.jrees.wowcompanion.Buildings.Foot>(); val devStands = ArrayList<com.jrees.wowcompanion.Buildings.Foot>()
        for (p in placed) { if (p.ox > rect.t + 100 || p.ox < rect.b - 100 || p.oy > rect.l + 100 || p.oy < rect.r - 100) continue
            val gs = wmoGroups.getOrPut(p.fid) { File(dir, "wmo/${p.fid}").takeIf { it.isFile }?.readBytes()?.let { WorldBuildings.groups(it) } ?: emptyList() }
            val st = if (devNew) WorldBuildings.standing(p, gs) { wx, wy -> height(wx, wy).toDouble() } else null
            if (st != null) devStands.add(st) else devWalks.addAll(WorldBuildings.walkways(p, gs)) }
        println("DEVICE ${if (devNew) "after" else "before"}: ${devStands.size} outdoor-only buildings (WorldBuildings.standing), ${devWalks.size} walkway boxes leveled")
        val devGround: (Double, Double) -> Float = { u, v -> val wx = rect.t - v * ay; val wy = rect.l - u * ax
            val hx = DangerGrid.CELL_YD / 2; val hy = DangerGrid.CELL_YD / 2
            val leveled = com.jrees.wowcompanion.Buildings.coverWorld(devWalks, wx, wy, hx, hy) >= 5f / 9f
            val slope = if (leveled) 0 else tg.at(wx, wy)
            val f = if (devNew) Terrain.legCost(tg, wx, wy, slope) else Terrain.factor(slope)
            var n = 0; for (a in -1..1) for (b in -1..1) if (roomsAt(wx + a * hx * 2 / 3, wy + b * hy * 2 / 3)) n++
            val stand = if (devNew) minOf(Terrain.STAND_COVER, Math.round(com.jrees.wowcompanion.Buildings.coverWorld(devStands, wx, wy, hx, hy) * 9)) else 0
            val built = maxOf(n, stand) / 9f; val depth = waterAt(wx, wy)
            when {
                !depth.isNaN() && depth != SafePath.Water.BRIDGE && depth > SafePath.FORD_DEPTH -> Terrain.WALL
                !depth.isNaN() && depth != SafePath.Water.BRIDGE -> maxOf(f, 1f) * SafePath.FORD_COST.toFloat()
                built >= com.jrees.wowcompanion.Buildings.WALL_COVER -> Terrain.WALL
                f >= 3f -> f
                built >= com.jrees.wowcompanion.Buildings.PART_COVER -> f * com.jrees.wowcompanion.Buildings.PART_COST
                else -> f } }
        val devGrid = DangerGrid.build(spawns, mine, level, ax, ay, devGround)
        fun deviceWay(f: DoubleArray, gp: DoubleArray): Pair<String, List<DoubleArray>> {
            val offNet = { a: DoubleArray, b: DoubleArray -> devGrid.route(uv(a[0], a[1]), uv(b[0], b[1]), k).map { doubleArrayOf(rect.t - it[1] * ay, rect.l - it[0] * ax) } }
            (if (devNew) SafePath.deviceLeg(net, f[0], f[1], gp[0], gp[1], false, clear, offNet) else SafePath.route(net, f[0], f[1], gp[0], gp[1], false, clear))
                ?.let { return "network" to (listOf(f) + it.drop(1).dropLast(1) + listOf(it.last())) }
            val pts = devGrid.route(uv(f[0], f[1]), uv(gp[0], gp[1]), k)
            return "grid" to pts.map { doubleArrayOf(rect.t - it[1] * ay, rect.l - it[0] * ax) }
        }
        /** yards of [w] inside a building's rooms (WorldBuildings.feet), sampled every half yard, and where the longest stretch is */
        fun roomYards(w: List<DoubleArray>): Pair<Double, String> {
            var tot = 0.0; var run = 0.0; var best = 0.0; var at = ""
            for ((pa, pb) in w.zipWithNext()) { val n = maxOf(1, (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) * 2).toInt())
                for (q in 0 until n) { val x = pa[0] + (pb[0] - pa[0]) * q / n; val y = pa[1] + (pb[1] - pa[1]) * q / n
                    if (roomsAt(x, y)) { tot += 0.5; run += 0.5; if (run > best) { best = run; at = "${x.toInt()},${y.toInt()}" } } else run = 0.0 } }
            return tot to (if (best > 0) "longest ${best.toInt()} yd at $at" else "")
        }
        val export = org.json.JSONObject(); val rs = org.json.JSONArray()
        for ((i, trip) in trips.withIndex()) {
            val (fromName, f, goal) = trip; val (goalName, gp) = goal
            // a declared swim only for a goal on its island
            val gv = cg.at(gp!![0], gp[1]); val gk = (gv[1].toLong() shl 32) or (gv[0].toLong() and 0xFFFFFFFFL)
            val mySwims = swims.filter { gk in it.island }
            links = baseLinks + mySwims.map { it.link }
            val planned = sr.plan(netC, f!!, gp, clear, links, ('A' + i).toString()) { println(it) }
            val way = planned.pts; val lastReached = planned.reached
            if (way.size < 2) { val r0 = Climb.route(cg, f, gp, danger, k, deep, links); val r1 = Climb.route(cg, f, gp)
                println("EMPTY ${f.toList()} -> ${gp.toList()}: full ${r0?.reached} ${r0?.pts?.size}, bare ${r1?.reached} ${r1?.pts?.size}; deep at start ${deep(f[0], f[1])} goal ${deep(gp[0], gp[1])}") }
            if (!lastReached) {
                fun around(p: DoubleArray): String { val v = cg.at(p[0], p[1]); val open = (0 until 8).count { q -> val di = listOf(1, 1, 0, -1, -1, -1, 0, 1)[q]; val dj = listOf(0, 1, 1, 1, 0, -1, -1, -1)[q]
                    cg.step(v[0], v[1], di, dj) != Climb.WALL && !deep(cg.wx(v[1] + dj), cg.wy(v[0] + di)) }
                    return "deep ${deep(p[0], p[1])} water ${waterAt(p[0], p[1])} standable ${standable(p[0], p[1])} open steps $open/8" }
                println("UNREACHED ${('A' + i)}: start ${f.map { it.toInt() }} (${around(f)}); goal ${gp.map { it.toInt() }} (${around(gp)}); ended ${way.last().map { it.toInt() }}")
            }
            val yd = way.zipWithNext { a, b -> Math.hypot(a[0] - b[0], a[1] - b[1]) }.sum()
            val letter = ('A' + i).toString()
            // the hard rule: no final segment crosses a wall step of the 4 yd lattice
            val walls = way.zipWithNext().withIndex().filter { (_, ab) -> !Climb.walkable(cg, ab.first, ab.second, deep) }.map { it.index }
            if (walls.isNotEmpty()) println("WALLS route $letter crosses a wall on segments $walls")
            // the gate's water check: the deepest water the route's own segments cross (each yard; a bridge's deck and a cave's floor none)
            var wmax = 0.0; var wAt = ""
            for ((pa, pb) in way.zipWithNext()) {
                if (Climb.inCave(pa) || Climb.inCave(pb)) continue
                val nn = maxOf(1, Math.hypot(pb[0] - pa[0], pb[1] - pa[1]).toInt())
                for (t in 0..nn) { val x = pa[0] + (pb[0] - pa[0]) * t / nn; val y = pa[1] + (pb[1] - pa[1]) * t / nn
                    val d = waterAt(x, y)
                    // within 3 yd of a bridge's deck it is the bridge (a step along a narrow footbridge's edge)
                    if (!d.isNaN() && d != SafePath.Water.BRIDGE && d > wmax &&
                        (-1..1).none { a2 -> (-1..1).any { b2 -> waterAt(x + a2 * 3.0, y + b2 * 3.0) == SafePath.Water.BRIDGE } }) { wmax = d.toDouble(); wAt = "${x.toInt()},${y.toInt()}" } }
            }
            println("WATERMAX route $letter ${"%.1f".format(wmax)} yd${if (wAt.isNotEmpty()) " at $wAt" else ""}")
            // and no water: no final segment over deep water (a bridge's walkway is no water)
            val wet = way.zipWithNext().withIndex().filter { (_, ab) -> Climb.walkable(cg, ab.first, ab.second, deep) && !Climb.walkable(cg, ab.first, ab.second, deep) }.map { it.index }
            if (wet.isNotEmpty()) println("WATER route $letter crosses deep water on segments ${wet.map { q -> val a = way[q]; val b = way[q + 1]
                val n = maxOf(1, (Math.hypot(b[0] - a[0], b[1] - a[1]) / 0.5).toInt())
                "$q (${(0..n).count { t -> deep(a[0] + (b[0] - a[0]) * t / n, a[1] + (b[1] - a[1]) * t / n) } / 2} yd of ${Math.hypot(b[0] - a[0], b[1] - a[1]).toInt()} at ${a[0].toInt()},${a[1].toInt()})" }}")
            if (way.any { Climb.atSwim(it) }) for (sw in mySwims) println("SWIM route $letter: the declared swim to ${sw.name}, ${sw.link.yd.toInt()} yd from ${sw.from[0].toInt()},${sw.from[1].toInt()} to ${sw.to[0].toInt()},${sw.to[1].toInt()} - swim here")
            println("ROUTE2 $letter $fromName -> $goalName: ${yd.toInt()} yd, ${way.size - 2} flags${way.count { Climb.atLift(it) }.let { if (it > 0) ", ${it / 2} lift ride(s)" else "" }}${if (lastReached) "" else " (the goal unreachable: to the nearest ground)"}; goal ${gp[0].toInt()},${gp[1].toInt()} h ${height(gp[0], gp[1]).toInt()}")
            val (devHow, dev) = deviceWay(f, gp)
            val devWalls = dev.zipWithNext().count { (a, b) -> !Climb.walkable(cg, a, b, deep) }
            run { val u = { w: List<DoubleArray> -> w.map { uv(it[0], it[1]) } }
                val gl = uv(gp[0], gp[1])
                println("DEVCOST $letter on the device's grid (ground + danger): its own way ${devGrid.cost(u(dev), 0.0, gl).toInt()} + ${(devGrid.cost(u(dev), k, gl) - devGrid.cost(u(dev), 0.0, gl)).toInt()}; " +
                    "the harness way ${devGrid.cost(u(way), 0.0, gl).toInt()} + ${(devGrid.cost(u(way), k, gl) - devGrid.cost(u(way), 0.0, gl)).toInt()}") }
            println("DEVICE $letter by the $devHow: ${dev.zipWithNext { a, b -> Math.hypot(a[0] - b[0], a[1] - b[1]) }.sum().toInt()} yd, ${dev.size - 2} bends; " +
                "in rooms ${roomYards(dev).let { "${it.first.toInt()} yd ${it.second}" }}; ${devWalls} segments over a wall on the lattice; " +
                "harness route in rooms ${roomYards(way).let { "${it.first.toInt()} yd ${it.second}" }}; edge yd ${edgeYards(cg, way).toInt()} (device ${edgeYards(cg, dev).toInt()})")
            rs.put(org.json.JSONObject().put("letter", letter).put("name", "$fromName → $goalName").put("reached", lastReached)
                .put("device", org.json.JSONArray(dev.map { org.json.JSONArray(listOf(it[0], it[1])) })).put("deviceHow", devHow)
                .put("pts", org.json.JSONArray(way.map { org.json.JSONArray(if (Climb.inCave(it)) listOf(it[0], it[1], it[2].toInt()) else listOf(it[0], it[1])) })))
        }
        // the city's own routes, on its floor (their points marked in-cave: the floor, not the terrain under it)
        city?.routes(rs.length())?.forEach { rs.put(it) }
        city?.links?.forEach { manual.put(it) }
        export.put("routes", rs)
        export.put("caves", org.json.JSONArray(caves.map { c -> org.json.JSONObject().put("fid", c.fid).put("wx", c.wx).put("wy", c.wy)
            .put("mouths", org.json.JSONArray(c.mouths.map { org.json.JSONArray(listOf(it[0], it[1], it[2])) }))
            .put("ways", org.json.JSONArray(c.ways.map { w -> org.json.JSONArray(w.pts.map { org.json.JSONArray(it.toList()) }) })) }).also { a -> city?.asCave()?.let { a.put(it) } })
        city?.let { export.put("floorCity", it.export()) }
        val cells = HashMap<Int, ArrayList<DoubleArray>>()
        for (j in 0 until zg.ny) for (i in 0 until zg.nx) {
            val a = zg.area[j * zg.nx + i]; if (a == 0 || areas[a]?.parent != zoneArea) continue
            cells.getOrPut(a) { ArrayList() }.add(doubleArrayOf(rect.t - (j + 0.5) * ay / zg.ny, rect.l - (i + 0.5) * ax / zg.nx))
        }
        // the drafted network (its ways and exits) for the renders; and a catalogue for picking goals: the named nodes, the mobs
        val byId = netC.nodes.associateBy { it.id }
        export.put("net", org.json.JSONObject()
            .put("edges", org.json.JSONArray(netC.edges.filter { it.state != SafePath.REMOVED }.mapNotNull { e -> val a = byId[e.a] ?: return@mapNotNull null; val b = byId[e.b] ?: return@mapNotNull null
                org.json.JSONArray(listOf(a.wx, a.wy, b.wx, b.wy, e.kind)) }))
            .put("exits", org.json.JSONArray(netC.exits.mapNotNull { x -> byId[x.node]?.let { n -> org.json.JSONArray(listOf(n.wx, n.wy, x.name, x.how, n.src, x.toMap)) } }))
            .put("nodes", org.json.JSONArray(netC.nodes.filter { it.name != null }.map { n -> org.json.JSONArray(listOf(n.wx, n.wy, n.name, n.kind)) })))
        val mobs = spawns.filter { it.hostile == mine || it.hostile == 3 }.groupBy { it.name }.map { (n, l) ->
            val p = l.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
            org.json.JSONArray(listOf(n, l.size, l.minOf { it.minLv }, l.maxOf { it.maxLv }, p.map { it[0] }.average(), p.map { it[1] }.average(),
                p.groupBy { zg.areaAt(it[0], it[1]) }.maxByOrNull { it.value.size }?.key?.let { areas[it]?.name } ?: "")) }
        export.put("mobs", org.json.JSONArray(mobs))
        export.put("exitChecks", exitChecks)
        export.put("manualLinks", manual)
        export.put("zone", tag).put("rect", org.json.JSONArray(listOf(rect.t, rect.b, rect.l, rect.r)))
        export.put("subzones", org.json.JSONArray(cells.map { (a, cs0) -> org.json.JSONObject().put("name", areas[a]?.name ?: "")
            .put("wx", cs0.map { it[0] }.average()).put("wy", cs0.map { it[1] }.average()).put("n", cs0.size) }))
        // the danger grid (QuestieDB's hostile mobs for a level 8 Horde player, "high"): its cells for the renders
        export.put("danger", org.json.JSONObject().put("t", rect.t).put("b", rect.b).put("l", rect.l).put("r", rect.r).put("nx", g.nx).put("ny", g.ny)
            .put("d", org.json.JSONArray((0 until g.nx * g.ny).map { q -> Math.round(g.cell(q % g.nx, q / g.nx) * 100) / 100.0 })))
        File(out, "levels_routes_$tag.json").writeText(export.toString())
        // THE SHIPPED NETWORK (the user: "Ship our path network"; NET1, ShippedNet): the network the routes ran on (the draft, walls
        // dropped, the caves' and lifts' ways, the checked exits) and the links (an air chain's, the declared swims), one faction's
        // section, for tools/safepath-harness/export.sh to gate (segcheck / wetcheck none, no WALLS / EMPTY) and pack
        if (System.getenv("SAFEPATH_EXPORT") == "1") {
            val wetOf: (DoubleArray, DoubleArray) -> Int = { pa, pb ->
                var f = 0; val n = maxOf(1, (Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) / 2).toInt())
                for (t in 0..n) { val x = pa[0] + (pb[0] - pa[0]) * t / n; val y = pa[1] + (pb[1] - pa[1]) * t / n; val d = waterAt(x, y)
                    f = f or when {
                        // (the water grid's bridge: a walkway's deck over water - not any floor, a cave's mouth on its own floor)
                        d == SafePath.Water.BRIDGE -> ShippedNet.F_BRIDGE
                        d.isNaN() || d <= 0f -> 0
                        deep(x, y) -> ShippedNet.F_SWIM
                        else -> ShippedNet.F_WADE } }
                f }
            // the lattice's own leg between two points, as the routes walk (the danger, the edge clearance; deep water a wall), every piece
            // of it walkable by the one check - or none
            val legOf: (DoubleArray, DoubleArray) -> Pair<List<DoubleArray>, Boolean>? = { a, b ->
                Climb.route(cg, a, b, danger, k, deep, edges = edgesOn)?.let { r -> r.pts to (r.reached && r.pts.zipWithNext().all { (u, v) -> Climb.walkable(cg, u, v, deep) }) } }
            fun meanDanger(pts: List<DoubleArray>): Float { var sum = 0.0; var n = 0
                for ((u, v) in pts.zipWithNext()) { val m = maxOf(1, Math.hypot(v[0] - u[0], v[1] - u[1]).toInt() / 4)
                    for (q in 0 until m) { sum += danger(u[0] + (v[0] - u[0]) * q / m, u[1] + (v[1] - u[1]) * q / m); n++ } }
                return if (n == 0) 0f else (sum / n / DangerGrid.CAP).toFloat().coerceIn(0f, 1f) }
            // A CITY'S STREETS (the spec's floorCity: Dalaran) as ways on its own floor: their points marked in-floor; a street point at the
            // terrain's own height, standable, is a mouth (the ground's way on)
            val floorNodes = ArrayList<SafePath.Node>(); val extras = ArrayList<ShippedNet.Extra>()
            var nextId = (netC.nodes.maxOfOrNull { it.id } ?: 0) + 1
            city?.floorWays()?.let { (reps, ways) ->
                val ids = reps.map { p -> val onGround = !height(p[0], p[1]).isNaN() && Math.abs(height(p[0], p[1]) - p[2]) < 1.5 && standable(p[0], p[1])
                    val id = nextId++; floorNodes.add(SafePath.Node(id, p[0], p[1], if (onGround) SafePath.MOUTH else SafePath.CAVE, "floor")); id }
                for ((a, b, pts) in ways) extras.add(ShippedNet.Extra(ids[a], ids[b], SafePath.CAVE, ShippedNet.F_FLOOR or ShippedNet.F_CAVE,
                    pts.map { doubleArrayOf(it[0], it[1]) }, 0f, "floor"))
                println("NET1 floor: ${floorNodes.size} street points (${floorNodes.count { it.kind == SafePath.MOUTH }} at the ground), ${extras.size} street ways")
            }
            // THE JOINS: the network's pieces (the city's streets with it) joined where the lattice's leg joins them
            val netF = SafePath.Net(netC.map, netC.cont, netC.built, netC.nodes + floorNodes,
                netC.edges + extras.map { SafePath.Edge(it.a, it.b, it.kind, SafePath.OK, it.src) }, netC.exits)
            // (T2's NetJoins: one walk over the lattice from every piece, Kruskal over the meetings, each join walked by Climb.route and
            // re-checked by py/segcheck.py in a folder of its own - its routes file is not the zone's; the floors written first)
            writeFloors(zi, tag)
            val jdir = File(out, "joins_$tag").also { it.mkdirs() }
            for (k2 in listOf("floors", "holes", "layers")) File(out, "${k2}_$tag.json").takeIf { it.isFile }?.copyTo(File(jdir, "${k2}_$tag.json"), true)
            val nj = NetJoins.joinsChecked(netF, cg, deep, danger, k, shipLinks, edgesOn, wetOf,
                check = NetJoins.segcheck(File(File(System.getProperty("user.dir")).parentFile, "py"), jdir, tag, dir.name, dir.parentFile)) { println("NET1 $it") }
            println(nj.summary(tag)); nj.apart.forEach { println("NET1 APART ${it.pieces} at ${it.x.toInt()},${it.y.toInt()}: ${it.why}") }
            jdir.deleteRecursively()
            val (netJ, jx) = NetJoins.extras(netC, nj.joins, nextId)
            extras.addAll(jx)
            // THE ROADMAP: the coverage graph of the walkable ground, this faction's danger along each of its edges
            val rb = RoadmapBuild(cg, deep, standable, ::roomsAt, waterAt, layers, rect.t, rect.b, rect.l, rect.r)
            val built = rb.build { println("NET1 $it") }
            // NET2 (NetFloors, helper T4): the floor layers' ways, the caves' mouths joined to the ground, the far goals' spurs - all walked
            // (SAFEPATH_NETFLOORS=0: left out)
            var netS = netJ; var nodesS: List<SafePath.Node> = floorNodes; var extrasS: List<ShippedNet.Extra> = extras
            if (System.getenv("SAFEPATH_NETFLOORS") != "0") {
                val nl = NetFloors.Layers(layers, cg)
                fun w(sp: QuestieNpcs.Spawn) = Triple(rect.t - sp.y * ay, rect.l - sp.x * ax, sp.name)
                val hostileW = com.jrees.wowcompanion.DangerInputs.hostile(spawns, mine).map { w(it) }
                val npcW = spawns.filter { it.hostile != mine && it.hostile != 3 }.map { w(it) }
                val goalsW = trips.mapNotNull { (_, _, g) -> g.second?.let { g.first to it } } +
                    (city?.export()?.optJSONArray("places")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.getString("name") to doubleArrayOf(it.getDouble("x"), it.getDouble("y")) } } ?: emptyList())
                // (a capital's own NPCs - Thunder Bluff's on its mesas over Mulgore - by its own map's coordinates: QuestieDB's area -> the
                // capital's uiMap -> its world rect; those inside this zone's lattice, not hostile to this side)
                val cityW = if (ar == null) emptyList() else allSpawns.filter { it.hostile != mine && it.hostile != 3 }.mapNotNull { sp ->
                    val m = ar.uiMap(sp.area); if (m !in com.jrees.wowcompanion.CityExits.CITIES || m == map) return@mapNotNull null
                    val r = recipeRects?.optJSONArray(m.toString()) ?: return@mapNotNull null
                    val t = r.getDouble(1); val b = r.getDouble(2); val l = r.getDouble(3); val rr = r.getDouble(4)
                    Triple(t - sp.y * (t - b), l - sp.x * (l - rr), sp.name) }.filter { p -> p.first <= rect.t && p.first >= rect.b && p.second <= rect.l && p.second >= rect.r }
                val pls = NetFloors.places(netJ, hostileW, npcW, goalsW, nl, cityW)
                // the floors' triangles as py/segcheck.py reads floors_<tag>.json (every deck with heights; a capital's street boxes not)
                val deckCells = HashMap<Long, ArrayList<com.jrees.wowcompanion.WorldBuildings.Deck>>()
                for (d in zi.deckList) { if (d.heights == null || d in zi.capitalDecks || d.triangles.isEmpty()) continue
                    val xs = d.triangles.flatMap { listOf(it[0], it[2], it[4]) }; val ys = d.triangles.flatMap { listOf(it[1], it[3], it[5]) }
                    for (i in Math.floorDiv(xs.min().toLong() - 2, 64L)..Math.floorDiv(xs.max().toLong() + 2, 64L))
                        for (j in Math.floorDiv(ys.min().toLong() - 2, 64L)..Math.floorDiv(ys.max().toLong() + 2, 64L)) deckCells.getOrPut(i * 1000003L + j) { ArrayList() }.add(d) }
                val surf = NetFloors.Surfaces(layers, { x, y -> deckCells[Math.floorDiv(x.toLong(), 64L) * 1000003L + Math.floorDiv(y.toLong(), 64L)]
                    ?.flatMap { it.heightsAt(x, y).toList() }?.toDoubleArray() ?: DoubleArray(0) }, { x, y -> zl.bil(x, y) },
                    { x, y -> zl.onFloor(x, y) || (zi.deckAt(x, y) && zi.floorsAt(x, y).isEmpty()) })
                val t0 = System.nanoTime()
                val plan = NetFloors.ways(NetFloors.Input(netJ, floorNodes, extras, cg, deep, nl, standable, { x, y -> danger(x, y) / DangerGrid.CAP }, pls,
                    wayOk = surf::ok))
                val applied = NetFloors.apply(plan, netJ, floorNodes, extras, cg, deep)
                netS = applied.first; nodesS = applied.second; extrasS = applied.third
                println(NetFloors.summary(tag, plan) + "; ${nl.count} floor layers (${nl.openings.size} openings), ${pls.size} places " +
                    "(${pls.groupingBy { it.why }.eachCount()}); ${(System.nanoTime() - t0) / 1_000_000} ms; extras ${extras.size} -> ${extrasS.size}, " +
                    "draft mouth spurs dropped ${netS.edges.count { it.state == SafePath.REMOVED } - netJ.edges.count { it.state == SafePath.REMOVED }}")
                for (d in plan.dropped) println("NETFLOORS dropped $d")
                for (u in plan.unreached) println("NETFLOORS unreached $u")
                if (floorNodes.isNotEmpty()) NetFloors.cityCover(floorNodes.map { doubleArrayOf(it.wx, it.wy) }, pls.filter { it.why == "goal" || it.why == "npc" }).let { (n, far) ->
                    println("NETFLOORS city streets: $n places within ${NetFloors.FLOOR_NEAR_YD.toInt()} yd of a street point; far: $far") }
                File(out, "nf").mkdirs()
                File(out, "nf/levels_routes_$tag.json").writeText(NetFloors.routesJson(tag, plan.ways).toString())
            }
            val sec = ShippedNet.section(netS, mine, level, tag, shipLinks, wetOf,
                walk = { a, b -> legOf(a, b)?.takeIf { it.second }?.first?.map { doubleArrayOf(it[0], it[1]) } },
                extraNodes = nodesS, extras = extrasS)
            val head = ShippedNet.Header(map, rect.c, Climb.RULES_VERSION, System.getenv("SAFEPATH_RULES_COMMIT") ?: "",
                System.getenv("SAFEPATH_BUILD") ?: "", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
            // EVERY SHIPPED SEGMENT CHECKED (ShipCheck): the ways, the joins, the roadmap's edges and the stitches through py/segcheck.py and
            // py/wetcheck.py in a folder of their own; what fails walked by its lattice leg, or dropped; anything left fails the export
            val cdir = File(out, "ship_$tag").also { it.mkdirs() }
            for (k2 in listOf("floors", "holes", "layers", "decks")) File(out, "${k2}_$tag.json").takeIf { it.isFile }?.copyTo(File(cdir, "${k2}_$tag.json"), true)
            val rmLeg: (DoubleArray, DoubleArray) -> List<DoubleArray>? = { a, b ->
                Climb.route(cg, a, b, water = deep, edges = false, limits = Climb.Limits(RoadmapBuild.LEG_WINDOW))
                    ?.takeIf { r -> r.reached && r.pts.zipWithNext().all { (u, v) -> Climb.walkable(cg, u, v, deep) } }?.pts }
            val sc = ShipCheck(cg, deep, { a, b -> legOf(a, b)?.takeIf { it.second }?.first }, rmLeg, wetOf,
                File(File(System.getProperty("user.dir")).parentFile, "py"), cdir, tag, dir.name, dir.parentFile) { println("NET1 $it") }
            val checked = sc.run(sec.copy(stitches = sc.stitches(sec, built.rm)), built.rm)
            cdir.deleteRecursively()
            if (checked.left.isNotEmpty()) println("NET1 CROSSING $tag: ${checked.left.size} shipped pieces still cross a wall or deep water: ${checked.left.take(10)}")
            // this faction's danger along each roadmap edge (the checked roadmap's): the mean along its line, a yard apart
            val rmDanger = checked.rm?.let { r -> ByteArray(r.edges) { e -> var sum = 0.0; var n = 0
                for ((u, v) in r.points(e).zipWithNext()) { val m = maxOf(1, Math.hypot(v[0] - u[0], v[1] - u[1]).toInt())
                    for (q in 0 until m) { sum += danger(u[0] + (v[0] - u[0]) * q / m, u[1] + (v[1] - u[1]) * q / m); n++ } }
                minOf(255, Math.round(sum / maxOf(1, n) * ShippedNet.ROADMAP_DANGER_SCALE).toInt()).coerceAtLeast(0).toByte() } }
            val pack = ShippedNet.Pack(head, listOf(checked.sec.copy(roadmapDanger = rmDanger)), checked.rm)
            File(out, "net1_$tag.json").writeText(pack.toJson().toString())
            val fin = pack.sections[0]
            val rmBytes = checked.rm?.let { ShippedNet.pack64(it.encode()).length } ?: 0
            println("NET1 $tag map $map faction $mine: ${fin.net.nodes.size} nodes, ${fin.net.edges.size} ways " +
                "(${ShippedNet.FLAG_NAMES.joinToString { (b, nm) -> "$nm ${fin.ways.count { it.flags and b != 0 }}" }}; ${fin.ways.count { it.pts != null }} walked), " +
                "${fin.net.exits.size} exits, ${fin.links.size} links, ${fin.starts.size} starts, ${fin.checkpoints.size} checkpoints, ${fin.stitches.size} stitches; " +
                "roadmap ${(checked.rm?.nodes ?: 0)} nodes ${(checked.rm?.edges ?: 0)} edges, $rmBytes bytes (base64), danger ${ShippedNet.pack64(rmDanger ?: ByteArray(0)).length} bytes")
        }
    }


    /** THE EXITS ON THE GROUND, one close-up each (1000 yd round it, 2 px a lattice step): grey = walkable (darker the steeper), dark
     *  red = a wall, blue = deep water, green tint = the zone's main ground, white = the zones' borders; gold road, blue spur, orange
     *  trail, purple cave; magenta = a ground exit, orange = a drafted / tunnel / bridge exit. exits_<tag>.png */
    private fun exitSheet(cg: Climb.Grid, gr: SafePath.Ground, net: SafePath.Net, tag: String, nameOf: (Int) -> String, deep: (Double, Double) -> Boolean) {
        val exits = net.exits.mapNotNull { x -> net.nodes.firstOrNull { it.id == x.node }?.let { x to it } }
        if (exits.isEmpty()) return
        val S = Climb.STEP; val ORG = 32 * MapHarvest.ADT_YD; val half = (500 / S).toInt(); val px = 2; val T = (2 * half + 1) * px
        val cols = 4; val rows = (exits.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cols * T, rows * T, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); c.drawColor(Color.BLACK)
        val p = Paint(); val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.WHITE; setShadowLayer(4f, 0f, 0f, Color.BLACK); isFakeBoldText = true }
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        val byId = net.nodes.associateBy { it.id }
        for ((k, xn) in exits.withIndex()) {
            val (x, n) = xn
            val ox = (k % cols) * T; val oy = (k / cols) * T
            val ctr = cg.at(n.wx, n.wy); val i0 = ctr[0] - half; val j0 = ctr[1] - half
            for (dj in 0..2 * half) for (di in 0..2 * half) {
                val i = i0 + di; val j = j0 + dj
                p.color = if (i !in 0 until cg.nx || j !in 0 until cg.ny) Color.rgb(10, 10, 10) else {
                    val q = j * cg.nx + i
                    val cl = maxOf(cg.east(i, j), cg.south(i, j), cg.east(i - 1, j), cg.south(i, j - 1))
                    if (!gr.dryAt[q]) Color.rgb(40, 70, 160)
                    else if (cl == Climb.WALL) Color.rgb(110, 25, 20)
                    else { val v = when (cl) { Climb.LEVEL -> 200; Climb.CLIMB -> 160; else -> 120 }
                        if (gr.isMain(q)) Color.rgb(v - 40, v, v - 40) else Color.rgb(v, v, v) }
                }
                if (i in 0 until cg.nx - 1 && j in 0 until cg.ny - 1) { val q = j * cg.nx + i
                    if (gr.zoneOf[q] != gr.zoneOf[q + 1] || gr.zoneOf[q] != gr.zoneOf[q + cg.nx]) p.color = Color.WHITE }
                c.drawRect((ox + di * px).toFloat(), (oy + dj * px).toFloat(), (ox + di * px + px).toFloat(), (oy + dj * px + px).toFloat(), p)
            }
            fun sx(wy: Double) = (ox + ((ORG - wy) / S - cg.gc0 - i0) * px).toFloat()
            fun sy(wx: Double) = (oy + ((ORG - wx) / S - cg.gr0 - j0) * px).toFloat()
            c.save(); c.clipRect(ox.toFloat(), oy.toFloat(), (ox + T).toFloat(), (oy + T).toFloat())
            for (e in net.edges) { if (e.state == SafePath.REMOVED) continue; val a = byId[e.a] ?: continue; val b = byId[e.b] ?: continue
                lp.color = when (e.kind) { SafePath.ROAD -> Color.rgb(255, 209, 0); SafePath.SPUR -> Color.rgb(140, 220, 255); SafePath.CAVE -> Color.rgb(170, 90, 255); else -> Color.rgb(255, 140, 60) }
                c.drawLine(sx(a.wy), sy(a.wx), sx(b.wy), sy(b.wx), lp) }
            for ((y, m) in exits) { p.color = Color.BLACK; c.drawCircle(sx(m.wy), sy(m.wx), 9f, p)
                p.color = if (m.src == "ground") Color.rgb(255, 0, 255) else Color.rgb(255, 120, 40); c.drawCircle(sx(m.wy), sy(m.wx), 7f, p) }
            c.restore()
            c.drawText("${k + 1}. ${x.name} (${x.how}${if (n.src == "ground") ", ground" else ""}) ${n.wx.toInt()},${n.wy.toInt()}", (ox + 8).toFloat(), (oy + 26).toFloat(), tp)
        }
        FileOutputStream(File(out, "exits_$tag.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** Durotar and the Barrens side by side, joined at their border: both drafts, the crossings paired, the danger heat on both */
    private fun border(art: File, a: Int, b: Int, nets: Map<Int, SafePath.Net>, rects: Map<Int, MapHarvest.Rect>, zgs: Map<Int, SafePath.Zones>,
                       ars: Map<Int, SafePath.Areas>, hostile: Map<Int, List<DoubleArray>>, names: (Int) -> String) {
        // the frame: Durotar whole and the Barrens' eastern half (world x north = up, world y west = left)
        val ra = rects[a]!!; val rb = rects[b]!!
        val top = maxOf(ra.t, rb.t) - 200.0; val bot = minOf(ra.b, rb.b + 1600.0)
        val left = minOf(ra.l, rb.l) - 0.0; val right = ra.r
        val west = (rb.l + rb.r) / 2 + 600.0                                // the Barrens' east half
        val L = minOf(west, left); val R = right
        val yd = L - R; val H0 = top - bot
        val W = 2400; val H = (W * H0 / yd).toInt()
        val bmp = Bitmap.createBitmap(W, H + 70, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.rgb(20, 16, 12))
        fun sx(wy: Double) = ((L - wy) / yd * W).toFloat()
        fun sy(wx: Double) = ((top - wx) / H0 * H).toFloat()
        fun artOf(map: Int): Bitmap {
            val dir = File(art, "worldmap/$map"); val idx = JSONObject(File(dir, "index.json").readText())
            val bm = BitmapFactory.decodeFile(File(dir, "base.webp").path).copy(Bitmap.Config.ARGB_8888, true)
            val cc = Canvas(bm); val ovs = idx.optJSONArray("overlays")!!
            for (i in 0 until ovs.length()) { val o = ovs.getJSONObject(i); BitmapFactory.decodeFile(File(dir, "ov_${o.getInt("id")}.webp").path)?.let {
                cc.drawBitmap(it, null, RectF(o.getInt("x").toFloat(), o.getInt("y").toFloat(), (o.getInt("x") + o.getInt("w")).toFloat(), (o.getInt("y") + o.getInt("h")).toFloat()), null) } }
            return bm
        }
        // each zone's own art on its own ground only (its chunks), the Barrens first
        for (m in listOf(b, a)) {
            val r = rects[m]!!; val zg = zgs[m]!!; val ar = ars[m]!!
            val clip = Path()
            for (j in 0 until zg.ny) for (i in 0 until zg.nx) if (ar.uiMap(zg.area[j * zg.nx + i]) == m) {
                val x0 = r.l - i * (r.l - r.r) / zg.nx; val x1 = r.l - (i + 1) * (r.l - r.r) / zg.nx
                val y0 = r.t - j * (r.t - r.b) / zg.ny; val y1 = r.t - (j + 1) * (r.t - r.b) / zg.ny
                clip.addRect(sx(x0), sy(y0), sx(x1) + 1f, sy(y1) + 1f, Path.Direction.CW)
            }
            c.save(); c.clipPath(clip)
            c.drawBitmap(artOf(m), null, RectF(sx(r.l), sy(r.t), sx(r.r), sy(r.b)), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }
        // the danger heat: two or more hostile mobs within 40 yd, by 20 yd cells
        val heat = Paint()
        for (m in listOf(a, b)) {
            val dz = SafePath.dangerOf(hostile[m]!!)
            val r = rects[m]!!; val zg = zgs[m]!!; val ar = ars[m]!!
            var x = top; while (x > bot) { var y = L; while (y > R) {
                if (ar.uiMap(zg.areaAt(x, y)) == m) { val d = dz(x, y); if (d >= 0.34f) { heat.color = Color.argb((60 + 110 * d).toInt(), 230, 30, 20)
                    c.drawRect(sx(y), sy(x), sx(y - 20) + 1f, sy(x - 20) + 1f, heat) } }
                y -= 20.0 }; x -= 20.0 }
        }
        // the crossings checked from both sides (the user: "as long as there is a low danger path back to another path")
        val raw = nets
        val checked = mapOf(a to SafePath.checkCrossings(raw[a]!!, listOf(raw[b]!!)), b to SafePath.checkCrossings(raw[b]!!, listOf(raw[a]!!)))
        @Suppress("NAME_SHADOWING") val nets = checked
        // the two networks
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        for (m in listOf(a, b)) { val net = nets[m]!!; val byId = net.nodes.associateBy { it.id }
            for (pass in 0..1) for (e in net.edges) { val n0 = byId[e.a]!!; val n1 = byId[e.b]!!
                p.pathEffect = if (e.kind == SafePath.TRAIL) DashPathEffect(floatArrayOf(14f, 10f), 0f) else null
                if (pass == 0) { p.color = Color.argb(200, 0, 0, 0); p.strokeWidth = 8f } else { p.strokeWidth = 4.5f
                    p.color = when (e.kind) { SafePath.ROAD -> Color.rgb(255, 209, 0); SafePath.SPUR -> Color.rgb(140, 220, 255); else -> Color.rgb(255, 140, 60) }
                    if (e.state == SafePath.AVOID) { p.color = Color.rgb(255, 40, 200); p.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f); p.strokeWidth = 6f } }
                c.drawLine(sx(n0.wy), sy(n0.wx), sx(n1.wy), sy(n1.wx), p) } }
        p.pathEffect = null
        // the crossings: each zone's exits into the other, paired where they meet (within 400 yd)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.WHITE; setShadowLayer(5f, 0f, 0f, Color.BLACK); isFakeBoldText = true }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fun exitsOf(m: Int, to: Int) = raw[m]!!.let { net -> net.exits.filter { it.toMap == to }.map { x -> net.nodes.first { it.id == x.node } } }
        val ea = exitsOf(a, b); val eb = exitsOf(b, a)
        val pairs = ArrayList<Pair<SafePath.Node?, SafePath.Node?>>()
        val usedB = HashSet<Int>()
        for (x in ea) { val y = eb.filter { it.id !in usedB }.minByOrNull { Math.hypot(it.wx - x.wx, it.wy - x.wy) }?.takeIf { Math.hypot(it.wx - x.wx, it.wy - x.wy) < 400 }
            if (y != null) usedB.add(y.id); pairs.add(x to y) }
        for (y in eb) if (y.id !in usedB) pairs.add(null to y)
        for ((i, pr) in pairs.sortedBy { (it.first ?: it.second)!!.wx.unaryMinus() }.withIndex()) {
            val (x, y) = pr
            for (n in listOfNotNull(x, y)) { fill.color = Color.BLACK; c.drawCircle(sx(n.wy), sy(n.wx), 18f, fill)
                fill.color = if (n === x) Color.rgb(255, 120, 40) else Color.rgb(80, 200, 255); c.drawCircle(sx(n.wy), sy(n.wx), 14f, fill) }
            if (x != null && y != null) {
                val fordA = nets[a]!!.exits.any { it.node == x.id && (it.how == "ford" || it.how == "bridge") } || nets[b]!!.exits.any { it.node == y.id && (it.how == "ford" || it.how == "bridge") }
                p.color = if (fordA) Color.rgb(120, 255, 255) else Color.WHITE; p.strokeWidth = if (fordA) 7f else 3f
                c.drawLine(sx(x.wy), sy(x.wx), sx(y.wy), sy(y.wx), p)
            }
            val n = (x ?: y)!!
            val la = x?.let { SafePath.link(raw[a]!!, it.id) }; val lb = y?.let { SafePath.link(raw[b]!!, it.id) }
            val kept = x != null && nets[a]!!.exits.any { it.node == x.id } || y != null && nets[b]!!.exits.any { it.node == y.id }
            val avoided = x != null && nets[a]!!.edges.any { (it.a == x.id || it.b == x.id) && it.state == SafePath.AVOID }
            val how = (x?.let { xx -> raw[a]!!.exits.firstOrNull { it.node == xx.id }?.how } ?: y?.let { yy -> raw[b]!!.exits.firstOrNull { it.node == yy.id }?.how })
            val verdict = when { !kept -> "DROPPED"; avoided -> "AVOID (last resort)"; else -> "KEPT" } + if (how == "ford" || how == "bridge") ", a $how" else ""
            fun w(l: SafePath.Link?) = when (l) { SafePath.Link.LOW -> "low danger"; SafePath.Link.DANGER -> "through mobs"; SafePath.Link.NONE -> "no way back"; null -> "no exit" }
            c.drawText("Crossing ${i + 1}: $verdict", sx(n.wy) + 26f, sy(n.wx) - 22f, tp)
            c.drawText("${names(a)}: ${w(la)}, ${names(b)}: ${w(lb)}", sx(n.wy) + 26f, sy(n.wx) + 12f, tp.apply { textSize = 24f }); tp.textSize = 30f
            println("BORDER crossing ${i + 1}: $verdict - ${names(a)} ${x?.let { "${it.wx.toInt()},${it.wy.toInt()}" }} ${w(la)}, ${names(b)} ${y?.let { "${it.wx.toInt()},${it.wy.toInt()}" }} ${w(lb)}")
        }
        // the towns
        for (m in listOf(a, b)) for (n in nets[m]!!.nodes) if (n.kind == SafePath.HUB || n.kind == SafePath.FLIGHT || n.kind == SafePath.DOCK) {
            fill.color = Color.BLACK; c.drawCircle(sx(n.wy), sy(n.wx), 11f, fill)
            fill.color = when (n.kind) { SafePath.FLIGHT -> Color.rgb(80, 170, 255); SafePath.DOCK -> Color.rgb(200, 120, 255); else -> Color.rgb(120, 220, 110) }
            c.drawCircle(sx(n.wy), sy(n.wx), 8f, fill)
            c.drawText(n.name ?: "", sx(n.wy) + 14f, sy(n.wx) + 10f, tp.apply { textSize = 24f }); tp.textSize = 30f
        }
        c.drawText("${names(b)} (left) and ${names(a)} (right): both SafePath drafts; gold road, blue spur, orange trail; red = hostile mobs; " +
            "orange / blue dot = ${names(a)}'s / ${names(b)}'s exit (on its bank); cyan = a bridge or ford; magenta dashed = avoided", 16f, H + 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.WHITE })
        FileOutputStream(File(out, "safepath_border_${a}_$b.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun artGround(art: File, map: Int, rect: MapHarvest.Rect): (Double, Double) -> Float {
        val dir = File(art, "worldmap/$map")
        val idx = JSONObject(File(dir, "index.json").readText())
        val bm = BitmapFactory.decodeFile(File(dir, "base.webp").path).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bm)
        val ovs = idx.optJSONArray("overlays")!!
        for (i in 0 until ovs.length()) { val o = ovs.getJSONObject(i); BitmapFactory.decodeFile(File(dir, "ov_${o.getInt("id")}.webp").path)?.let {
            c.drawBitmap(it, null, RectF(o.getInt("x").toFloat(), o.getInt("y").toFloat(), (o.getInt("x") + o.getInt("w")).toFloat(), (o.getInt("y") + o.getInt("h")).toFloat()), null) } }
        val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        return SafePath.artGround(px, bm.width, bm.height, rect.t, rect.b, rect.l, rect.r)
    }

    private fun draw(net: SafePath.Net, rect: MapHarvest.Rect, art: File, map: Int, zg: SafePath.Zones, ar: SafePath.Areas, title: String) {
        val W = 2004; val H = 1336
        val b = Bitmap.createBitmap(W, H + 70, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 16, 12))
        val dir = File(art, "worldmap/$map")
        val idx = JSONObject(File(dir, "index.json").readText())
        BitmapFactory.decodeFile(File(dir, "base.webp").path)?.let { c.drawBitmap(it, null, RectF(0f, 0f, W.toFloat(), H.toFloat()), null) }
        val ovs = idx.optJSONArray("overlays")
        if (ovs != null) for (i in 0 until ovs.length()) {
            val o = ovs.getJSONObject(i)
            BitmapFactory.decodeFile(File(dir, "ov_${o.getInt("id")}.webp").path)?.let { bm ->
                val s = W / 1002f
                c.drawBitmap(bm, null, RectF(o.getInt("x") * s, o.getInt("y") * s, (o.getInt("x") + o.getInt("w")) * s, (o.getInt("y") + o.getInt("h")) * s), null)
            }
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // the ground that is not this zone's, shaded (where the exits are found)
        p.color = Color.argb(70, 0, 0, 0)
        val zw = W / zg.nx.toFloat(); val zh = H / zg.ny.toFloat()
        for (j in 0 until zg.ny) for (i in 0 until zg.nx) if (ar.uiMap(zg.area[j * zg.nx + i]) != map) c.drawRect(i * zw, j * zh, (i + 1) * zw, (j + 1) * zh, p)
        fun sx(wy: Double) = ((rect.l - wy) / (rect.l - rect.r) * W).toFloat()
        fun sy(wx: Double) = ((rect.t - wx) / (rect.t - rect.b) * H).toFloat()
        val byId = net.nodes.associateBy { it.id }
        for (pass in 0..1) for (e in net.edges) {
            val a = byId[e.a]!!; val bb = byId[e.b]!!
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            p.pathEffect = if (e.kind == SafePath.TRAIL) DashPathEffect(floatArrayOf(18f, 12f), 0f) else null
            if (pass == 0) { p.color = Color.argb(200, 0, 0, 0); p.strokeWidth = if (e.kind == SafePath.ROAD) 11f else 8f }
            else {
                p.strokeWidth = if (e.kind == SafePath.ROAD) 6f else 4f
                p.color = when (e.kind) { SafePath.ROAD -> Color.rgb(255, 209, 0); SafePath.SPUR -> Color.rgb(140, 220, 255); else -> Color.rgb(255, 140, 60) }
                if (e.danger >= 0.5f) p.color = Color.rgb(230, 40, 40)
            }
            c.drawLine(sx(a.wy), sy(a.wx), sx(bb.wy), sy(bb.wx), p)
        }
        p.pathEffect = null; p.style = Paint.Style.FILL
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.WHITE; setShadowLayer(4f, 0f, 0f, Color.BLACK); isFakeBoldText = true }
        val taken = ArrayList<RectF>()
        fun label(text: String, x: Float, y: Float) {
            var yy = y
            val w = tp.measureText(text)
            var r = RectF(x, yy - 28f, x + w, yy + 6f)
            var guard = 0
            while (taken.any { RectF.intersects(it, r) } && guard++ < 12) { yy += 34f; r = RectF(x, yy - 28f, x + w, yy + 6f) }
            taken.add(r)
            c.drawText(text, x, yy, tp)
        }
        for (n in net.nodes) {
            val x = sx(n.wy); val y = sy(n.wx)
            when (n.kind) {
                SafePath.JUNCTION -> { p.color = Color.BLACK; c.drawCircle(x, y, 7f, p); p.color = Color.rgb(255, 230, 120); c.drawCircle(x, y, 5f, p) }
                SafePath.FLIGHT, SafePath.DOCK, SafePath.HUB -> {
                    p.color = Color.BLACK; c.drawCircle(x, y, 17f, p)
                    p.color = when (n.kind) { SafePath.FLIGHT -> Color.rgb(80, 170, 255); SafePath.DOCK -> Color.rgb(200, 120, 255); else -> Color.rgb(120, 220, 110) }
                    c.drawCircle(x, y, 14f, p)
                    label(n.name ?: n.kind, x + 20f, y + 10f)
                }
                else -> {}
            }
        }
        for (x in net.exits) {
            val n = byId[x.node]!!
            val px = sx(n.wy); val py = sy(n.wx)
            val path = Path().apply { moveTo(px, py - 22f); lineTo(px + 20f, py + 14f); lineTo(px - 20f, py + 14f); close() }
            p.color = Color.BLACK; c.drawCircle(px, py, 25f, p)
            p.color = if (x.how == "gate") Color.rgb(255, 80, 80) else Color.rgb(255, 120, 40); c.drawPath(path, p)
            label("To ${x.name} (${x.how})", px + 28f, py - 10f)
        }
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; color = Color.WHITE }
        c.drawText("$title - SafePath first draft: ${net.edges.count { it.kind == SafePath.ROAD }} road, ${net.edges.count { it.kind == SafePath.SPUR }} spur, " +
            "${net.edges.count { it.kind == SafePath.TRAIL }} trail edges, ${net.exits.size} exits.  Gold road, blue spur, orange dashed trail, red = danger; " +
            "shaded = another zone's ground", 16f, H + 46f, hp)
        FileOutputStream(File(out, "safepath_draft_$map.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
