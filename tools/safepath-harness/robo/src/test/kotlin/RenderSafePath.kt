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
import com.jrees.wowcompanion.MapHarvest
import com.jrees.wowcompanion.PlaceNames
import com.jrees.wowcompanion.QuestieNpcs
import com.jrees.wowcompanion.Roads
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.Terrain
import com.jrees.wowcompanion.TransportRoutes
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
        val areas = Db2(File(db2, "AreaTable.db2").readBytes()).rows(strings = setOf(1)).associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
        val uimap = HashMap<Int, Int>().also { o -> for (r in Db2(File(db2, "UiMapAssignment.db2").readBytes()).rows()) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m } }
        val chunkAreas = HashMap<Pair<Int, Int>, Map<Int, Int>>()
        fun areaAt(wx: Double, wy: Double): Int? {
            val l = PlaceNames.locate(wx, wy)
            val m = chunkAreas.getOrPut(l[0] to l[1]) { File(dir, "adt/${l[0]}_${l[1]}.adt").takeIf { it.isFile }?.let { PlaceNames.chunkAreas(it.readBytes()) } ?: emptyMap() }
            return m[l[2]]
        }
        val tex = HashMap<Pair<Int, Int>, Roads.Tile?>()
        val tiles = HashMap<Pair<Int, Int>, Terrain.Tile?>()
        val transports = JSONObject(File(art, "transports.json").readText())
        val spawns = QuestieNpcs.read(toc)
        fun nameOf(m: Int) = runCatching { JSONObject(File(art, "worldmap/$m/index.json").readText()).optString("name") }.getOrDefault("")
        val recipe = JSONObject(File(System.getenv("RECIPE")).readText()).optJSONObject("rects")!!
        class Done(val net: SafePath.Net, val rect: MapHarvest.Rect, val zg: SafePath.Zones, val ar: SafePath.Areas, val hostile: List<DoubleArray>)
        val done = HashMap<Int, Done>()
        for ((map, area) in zoneList) {
            tex.clear(); tiles.clear(); System.gc()
            val a = recipe.optJSONArray(map.toString())!!
            val rect = MapHarvest.Rect(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optDouble(4))
            val rg = Roads.grid(rect) { col, row -> tex.getOrPut(col to row) { File(dir, "tex0/${col}_$row.adt").takeIf { it.isFile }?.let { Roads.tile(it.readBytes()) } } }
            // the roads as the device counts them (Roads.isRoad): a road texture showing most in a cell, or showing thinly there (at
            // Roads.THIN_MIN of its 36 points: Teldrassil's 4-6 yd path, Dun Morogh's road under the snow)
            val roadAt: (Double, Double) -> Boolean = { wx, wy -> rg.shows(wx, wy, roadTex) }
            val tg = Terrain.grid(rect) { gr, gc ->
                val t = tiles.getOrPut((gc / 128) to (gr / 128)) { File(dir, "adt/${gc / 128}_${gr / 128}.adt").takeIf { it.isFile }?.let { Terrain.tile(it.readBytes()) } }
                t?.h?.get((gr % 128) * 128 + (gc % 128)) ?: Float.NaN
            }
            val zg = SafePath.Zones.build(rect.t, rect.b, rect.l, rect.r, PlaceNames.CHUNK_YD, ::areaAt)
            // the water: the root ADTs' MH2O over their ground
            val wetTiles = HashMap<Pair<Int, Int>, Pair<FloatArray?, FloatArray?>>()
            fun wet(c: Int, r: Int) = wetTiles.getOrPut(c to r) { File(dir, "adt/${c}_$r.adt").takeIf { it.isFile }?.readBytes()?.let { b -> Terrain.water(b) to Terrain.tile(b)?.h } ?: (null to null) }
            // each walkway by its deck (its outdoor groups' floor triangles, the device's own WorldBuildings.deck), its box only where
            // its groups weren't dumped
            val walks = HashMap<Pair<Int, Int>, List<(Double, Double) -> Boolean>>(); val m2Said = HashSet<Int>()
            val unread = HashSet<Int>()
            val deckList = ArrayList<com.jrees.wowcompanion.WorldBuildings.Deck>()     // every deck read (py/wetcheck.py: decks_<tag>.json)
            // per tile: the decks walked at their own floor's height - a bridge's or a pier's (no rooms), a fifth or more of its floor over
            // the water (a building's porch by the shore, its roof read as floor too, a cave's mouth, a city's streets stay the level
            // walkways they were)
            val tileDecks = HashMap<Pair<Int, Int>, ArrayList<com.jrees.wowcompanion.WorldBuildings.Deck>>()
            fun overWater(d: com.jrees.wowcompanion.WorldBuildings.Deck): Boolean {
                val ts = d.triangles; if (ts.isEmpty()) return false
                val wetN = ts.count { t -> val x = (t[0] + t[2] + t[4]) / 3; val y = (t[1] + t[3] + t[5]) / 3
                    !SafePath.Water.depthOf(x, y, { c, r -> wet(c, r).first }, { c, r -> wet(c, r).second }).isNaN() }
                return wetN * 5 >= ts.size
            }
            fun keepHeights(c: Int, r: Int, d: com.jrees.wowcompanion.WorldBuildings.Deck, any: Boolean = false) { if (any || overWater(d)) tileDecks.getOrPut(c to r) { ArrayList() }.add(d) }
            // the M2 bridges (the device's own WorldBuildings.m2Decks: a doodad by the water whose model's own name says bridge, dock,
            // pier or walkway), from the models dump/dump_m2bridges.py put in <dir>/m2
            fun m2DecksOf(c: Int, r: Int): List<(Double, Double) -> Boolean> {
                val obj = File(dir, "obj0/${c}_$r.adt").takeIf { it.isFile }?.readBytes() ?: return emptyList()
                val surface = wet(c, r).first ?: return emptyList()
                return com.jrees.wowcompanion.WorldBuildings.doodads(obj).filter { Terrain.wetNear(surface, c, r, it.ox, it.oy, com.jrees.wowcompanion.WorldBuildings.M2_NEAR_WATER_YD) }.mapNotNull { p ->
                    val m = File(dir, "m2/${p.fid}").takeIf { it.isFile }?.readBytes() ?: return@mapNotNull null
                    val name = com.jrees.wowcompanion.WorldBuildings.m2Name(m)
                    val d = com.jrees.wowcompanion.WorldBuildings.m2Deck(p, m)
                    if (m2Said.add(p.uid)) println("DECK M2 ${p.fid} '$name' at ${p.ox.toInt()},${p.oy.toInt()} z ${p.oz.toInt()} yaw ${Math.toDegrees(p.yaw).toInt()}: " +
                        if (d == null) "not a bridge (or no collision floor)" else "a bridge, its deck read")
                    d?.let { dk -> deckList.add(dk); keepHeights(c, r, dk); { x: Double, y: Double -> dk.holds(x, y) } }
                }
            }
            // a deck's floors all more than LEVEL_BURIED_YD under the terrain at a spot (its nearest outer vertex): buried, no ground there
            fun buriedAt(c: Int, r: Int, d: com.jrees.wowcompanion.WorldBuildings.Deck, x: Double, y: Double): Boolean {
                val hs = d.heightsAt(x, y); if (hs.isEmpty()) return false
                val h = wet(c, r).second ?: return false
                val ci = (((32 * MapHarvest.ADT_YD - y) / MapHarvest.ADT_YD - c) * 128).toInt().coerceIn(0, 127)
                val cj = (((32 * MapHarvest.ADT_YD - x) / MapHarvest.ADT_YD - r) * 128).toInt().coerceIn(0, 127)
                val g = h[cj * 128 + ci]; if (g.isNaN()) return false
                return hs.all { it < g - LEVEL_BURIED_YD }
            }
            fun walksOf(c: Int, r: Int) = walks.getOrPut(c to r) {
                File(dir, "obj0/${c}_$r.adt").takeIf { it.isFile }?.readBytes()?.let { com.jrees.wowcompanion.WorldBuildings.placements(it, tilted = true) }?.flatMap { p ->
                    val root = File(dir, "wmo/${p.fid}").takeIf { it.isFile }?.readBytes()
                    val feet = com.jrees.wowcompanion.WorldBuildings.walkways(p, root?.let { com.jrees.wowcompanion.WorldBuildings.groups(it) } ?: emptyList())
                    if (feet.isEmpty() || root == null) emptyList() else {
                        val gf = com.jrees.wowcompanion.WorldBuildings.groupFiles(root)
                        com.jrees.wowcompanion.WorldBuildings.deck(p, root) { id -> gf.indexOf(id).takeIf { it >= 0 }?.let { File(dir, "wmogroup/${p.fid}_$it") }?.takeIf { it.isFile }?.readBytes() }
                            ?.takeIf { !it.empty }?.let { d -> deckList.add(d)
                                // a bridge or a pier is all outdoor groups: a cave's or a building's (with rooms) outdoor floor stays level
                                // (Zephras' Bandit Hideout: its mouth's floor by the water walled its own goal)
                                val gs = com.jrees.wowcompanion.WorldBuildings.groups(root)
                                // ... over water or not: a dry ravine's bridge, a tunnel's floor under its hill (Wetlands' Dun Algaz: leveled, its
                                // hillside 60-75 degrees over it was climbed) are walked at their own floor's height too
                                if (!com.jrees.wowcompanion.WorldBuildings.isCity(p, gs) && gs.none { it.interior }) keepHeights(c, r, d, any = true)
                                // ... and a floor is the ground only where it is not buried: more than LEVEL_BURIED_YD under the terrain (Blasted
                                // Lands' Garrison Armory mine: its outdoor floors at 0-40 under a hill to 263 leveled it, a route climbed it)
                                listOf<(Double, Double) -> Boolean>({ x, y -> d.holds(x, y) && !buriedAt(c, r, d, x, y) }) }
                            // a WMO whose floors can't be read (its groups not dumped) levels nothing - the ground under its box is the
                            // terrain's (Silithus: Cenarion Hold's outpost, undumped, let a route over a 67-90 degree step) - but a capital's
                            // streets, whose ground under them reads steep everywhere
                            ?: if (com.jrees.wowcompanion.WorldBuildings.isCity(p, com.jrees.wowcompanion.WorldBuildings.groups(root))) feet.map { f -> { x: Double, y: Double -> f.holds(x, y) } }
                            else { if (unread.add(p.fid)) println("WARNING WMO ${p.fid} at ${p.ox.toInt()},${p.oy.toInt()}: its floors not dumped (dump/dump_cavegroups.py <dir> ${p.fid}) - the ground under it is taken as the terrain"); emptyList() }
                    }
                }.orEmpty() + m2DecksOf(c, r)
            }
            // a bridge's or a pier's floor height at a spot: its lowest floor over the water's surface and the ground there (a covered
            // bridge's roof over its deck, its pillars' feet in the river), NaN off every such deck
            // every height-classed floor's height at a spot (a bridge's deck, its arches and parapets; a tunnel's floor; a pier's planks):
            // Climb.floorLayers picks the one a walker is on
            fun floorsAt(x: Double, y: Double): DoubleArray = PlaceNames.locate(x, y).let { l -> walksOf(l[0], l[1])
                val ds = tileDecks[l[0] to l[1]] ?: return@let DoubleArray(0)
                if (ds.size == 1) ds[0].heightsAt(x, y) else ds.flatMap { it.heightsAt(x, y).toList() }.toDoubleArray() }
            val wg = SafePath.Water.build(rect.t, rect.b, rect.l, rect.r, { x, y -> PlaceNames.locate(x, y).let { l -> walksOf(l[0], l[1]).any { it(x, y) } } }) { x, y ->
                SafePath.Water.depthOf(x, y, { c, r -> wet(c, r).first }, { c, r -> wet(c, r).second }) }
            println("SAFEPATH water of $map: ${wg.depth.count { it.toInt() != 0 }} wet cells of ${wg.depth.size}, shallow ${wg.depth.count { (it.toInt() and 0xFF) in 1..16 }}, bridged ${wg.depth.count { (it.toInt() and 0xFF) == 253 }}")
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
            val friendlyMin = spec?.optInt("friendlyMinLevel", Int.MAX_VALUE) ?: Int.MAX_VALUE
            // and a zone's own peoples the guides call friendly to all (the spec's friendlyNames, regexes: Zephras' Windshapers and High
            // Order - the isle shared by both factions; QuestieDB's kill links to them are beta leftovers)
            val friendlyNames = spec?.optJSONArray("friendlyNames")?.let { a -> (0 until a.length()).map { Regex(a.getString(it)) } } ?: emptyList()
            val here = spawns.filter { it.area == area }.map { sp -> if ((sp.minLv >= friendlyMin || friendlyNames.any { it.containsMatchIn(sp.name) }) && sp.hostile != foe)
                QuestieNpcs.Spawn(sp.area, sp.x, sp.y, sp.minLv, sp.maxLv, sp.elite, foe, sp.name, sp.sub) else sp }
            val hostile = here.filter { it.hostile == mine || it.hostile == 3 }.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
            val friendly = here.filter { it.hostile == foe }.map { SafePath.Npc(rect.t - it.y * ay, rect.l - it.x * ax, it.sub) }
            val fixed = SafePath.fixedPoints(map, rect.c, rect.t, rect.b, rect.l, rect.r, mine, transports) + SafePath.hubs(friendly, { x, y -> PlaceNames.nameAt(x, y, areas) { a, b2 -> zg.areaAt(a, b2) } }, nameOf(map), SafePath.districtFor(map, rect.t, rect.b, rect.l, rect.r))
            val t0 = System.nanoTime()
            val net = SafePath.draft(SafePath.Input(map, rect.c, rect.t, rect.b, rect.l, rect.r, roadAt,
                { wx, wy -> Terrain.factor(tg.at(wx, wy)) }, { wx, wy -> ar.uiMap(zg.areaAt(wx, wy)) }, fixed, ::nameOf, SafePath.dangerOf(hostile),
                ground = if (map in cities) artGround(art, map, rect) else null, painted = painted(art, map, rect), water = { x, y -> wg.at(x, y) }), "off-device")
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("SAFEPATH ${nameOf(map)}: ${net.nodes.size} nodes, ${net.edges.size} edges (${net.edges.groupBy { it.kind }.mapValues { it.value.size }}), " +
                "${net.exits.size} exits: ${net.exits.joinToString { "${it.name} (${it.how})" }}; fixed ${fixed.joinToString { it.name }}; " +
                "${hostile.size} hostile, ${friendly.size} friendly spawns; $ms ms")
            File(out, "safepath_$map.draft.json").writeText(net.toJson().toString(1))
            draw(net, rect, art, map, zg, ar, nameOf(map))
            // a floating zone's void floor (Zephras Isle's flat ground far below the island: falling, not walking) is no ground
            val voidBelow = spec?.optDouble("voidBelow", Double.NaN) ?: Double.NaN
            if (map == main) levels2(net, rect, tg, { gr, gc ->
                val t = tiles.getOrPut((gc / 128) to (gr / 128)) { File(dir, "adt/${gc / 128}_${gr / 128}.adt").takeIf { it.isFile }?.let { Terrain.tile(it.readBytes()) } }
                (t?.h?.get((gr % 128) * 128 + (gc % 128)) ?: Float.NaN).let { if (!voidBelow.isNaN() && it < voidBelow) Float.NaN else it } }, roadAt, zg, areas, here, dir, { x, y -> wg.at(x, y) }, wg,
                { x, y -> PlaceNames.locate(x, y).let { l -> walksOf(l[0], l[1]).any { it(x, y) } } }, ::nameOf, floorsAt = ::floorsAt,
                mainArea, spec, tag, mine, ar, map, rawDeep = { x, y -> SafePath.Water.depthOf(x, y, { c, r -> wet(c, r).first }, { c, r -> wet(c, r).second })
                    .let { !it.isNaN() && it > SafePath.FORD_DEPTH } })
            if (map == main) File(out, "decks_$tag.json").writeText(org.json.JSONArray(deckList.flatMap { d -> d.triangles.map { org.json.JSONArray(it.toList()) } }).toString())
            // and their floors in 3D for py/segcheck.py (the surface a walker is on: each triangle's corners x, y, z, and 1 where its
            // height classes the lattice's steps, 0 where it levels them - a city's, a building's porch)
            if (map == main) { val classed = tileDecks.values.flatten().toSet()
                File(out, "floors_$tag.json").writeText(org.json.JSONArray(deckList.flatMap { d -> val hs = d.heights ?: return@flatMap emptyList<org.json.JSONArray>()
                    d.triangles.mapIndexed { k, t -> org.json.JSONArray(listOf(t[0], t[1], hs[k][0], t[2], t[3], hs[k][1], t[4], t[5], hs[k][2], if (d in classed) 1 else 0)) } }).toString()) }
            done[map] = Done(net, rect, zg, ar, hostile)
        }
        if (done.containsKey(borderPair.first) && done.containsKey(borderPair.second))
            border(art, borderPair.first, borderPair.second, done.mapValues { it.value.net }, done.mapValues { it.value.rect }, done.mapValues { it.value.zg },
                done.mapValues { it.value.ar }, done.mapValues { it.value.hostile }, ::nameOf)
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
    private val EXIT_NEAR_YD = 400.0       // an exit's crossing: the neighbour's ground reached within this walk of it

    private val WADE_DANGER = 2f
    /** a floor this far under the terrain or more is under it (a mine's, a tunnel's), never the ground there (py/segcheck.py: the same) */
    private val LEVEL_BURIED_YD = 8.0
    private fun levels2(net: SafePath.Net, rect: MapHarvest.Rect, tg: Terrain.Grid, heightV: (Int, Int) -> Float, roadAt: (Double, Double) -> Boolean,
                        zg: SafePath.Zones, areas: Map<Int, PlaceNames.Area>, spawns: List<QuestieNpcs.Spawn>, dir: File, waterAt: (Double, Double) -> Float, waterGrid: SafePath.Water?,
                        deckAt: (Double, Double) -> Boolean = { _, _ -> false }, mapName: (Int) -> String = { "" },
                        floorsAt: (Double, Double) -> DoubleArray = { _, _ -> DoubleArray(0) }, zoneArea: Int = 14, spec: JSONObject? = null, tag: String = "durotar", mine: Int = TransportRoutes.HORDE,
                        ar: SafePath.Areas? = null, map: Int = 1411, rawDeep: ((Double, Double) -> Boolean)? = null) {
        val S = Climb.STEP; val ORG = 32 * MapHarvest.ADT_YD
        fun height(wx: Double, wy: Double): Float = heightV(Math.round((ORG - wx) / S).toInt(), Math.round((ORG - wy) / S).toInt())
        val ax = rect.l - rect.r; val ay = rect.t - rect.b
        fun uv(wx: Double, wy: Double) = doubleArrayOf((rect.l - wy) / ax, (rect.t - wx) / ay)
        val t0 = System.nanoTime()
        // the ADTs' holes (the terrain opened into a cave), per global cell
        val holeTiles = HashMap<Pair<Int, Int>, BooleanArray?>()
        // a hole under a placed building is its floor's place (a garrison's, a barn's: the WMO is the ground there), no wall; elsewhere
        // the terrain opened into a cave, its ground not there
        val covers = HashMap<Long, ArrayList<com.jrees.wowcompanion.Buildings.Foot>>()
        for (f in (File(dir, "obj0").listFiles() ?: emptyArray())) for (p in WorldBuildings.placements(f.readBytes())) {
            if (p.ox > rect.t + 100 || p.ox < rect.b - 100 || p.oy > rect.l + 100 || p.oy < rect.r - 100) continue
            val gs = File(dir, "wmo/${p.fid}").takeIf { it.isFile }?.readBytes()?.let { WorldBuildings.groups(it) } ?: continue
            val cs = Math.cos(p.yaw); val sn = Math.sin(p.yaw)
            for (g in gs) {
                val bx = g.box
                val ft = com.jrees.wowcompanion.Buildings.Foot(p.ox, p.oy, cs, sn, minOf(bx[0], bx[3]) * p.scale, maxOf(bx[0], bx[3]) * p.scale,
                    minOf(bx[1], bx[4]) * p.scale, maxOf(bx[1], bx[4]) * p.scale)
                for (cx in Math.floorDiv(ft.wx0.toLong(), 64L)..Math.floorDiv(ft.wx1.toLong(), 64L)) for (cy in Math.floorDiv(ft.wy0.toLong(), 64L)..Math.floorDiv(ft.wy1.toLong(), 64L))
                    covers.getOrPut(cx * 100000 + cy) { ArrayList() }.add(ft)
            }
        }
        fun covered(x: Double, y: Double) = covers[Math.floorDiv(x.toLong(), 64L) * 100000 + Math.floorDiv(y.toLong(), 64L)]?.any { it.holds(x, y) } == true
        fun holed(gr: Int, gc: Int): Boolean { val key = (gc / 128) to (gr / 128)
            if (key !in holeTiles) holeTiles[key] = File(dir, "adt/${gc / 128}_${gr / 128}.adt").takeIf { it.isFile }?.let { Terrain.holes(it.readBytes()) }
            return holeTiles[key]?.get((gr % 128) * 128 + (gc % 128)) == true }
        val hole: (Int, Int) -> Boolean = { gr, gc -> holed(gr, gc) && !covered(ORG - (gr + 0.5) * S, ORG - (gc + 0.5) * S) }
        // beside a hole a building or a cave covers, its floor is the ground: the terrain's heights there (under it) no wall
        val onFloor: (Double, Double) -> Boolean = { x, y -> val gr = Math.round((ORG - x) / S).toInt(); val gc = Math.round((ORG - y) / S).toInt()
            listOf(gr - 1 to gc - 1, gr - 1 to gc, gr to gc - 1, gr to gc).any { (r, c) -> holed(r, c) && covered(ORG - (r + 0.5) * S, ORG - (c + 0.5) * S) } }
        // the cells' middle vertices (the game's surface: four triangles a cell through each)
        val ctiles = HashMap<Pair<Int, Int>, FloatArray?>()
        val centre: (Int, Int) -> Float = { gr, gc -> val key = (gc / 128) to (gr / 128)
            if (key !in ctiles) ctiles[key] = File(dir, "adt/${gc / 128}_${gr / 128}.adt").takeIf { it.isFile }?.let { Terrain.tile(it.readBytes())?.c }
            ctiles[key]?.get((gr % 128) * 128 + (gc % 128)) ?: Float.NaN }
        // a walkway's deck (a bridge, a pier, a dock: its floor, over water or a drop) is level ground, whatever lies under it
        // the holes a building's or a cave's floor covers (py/segcheck.py: no terrain there, the floor is the ground)
        run { val gr0 = Math.floor((ORG - rect.t) / S).toInt(); val gr1 = Math.ceil((ORG - rect.b) / S).toInt()
            val gc0 = Math.floor((ORG - rect.l) / S).toInt(); val gc1 = Math.ceil((ORG - rect.r) / S).toInt()
            val cells = org.json.JSONArray()
            for (gr in gr0..gr1) for (gc in gc0..gc1) if (holed(gr, gc) && covered(ORG - (gr + 0.5) * S, ORG - (gc + 0.5) * S)) cells.put(org.json.JSONArray(listOf(gr, gc)))
            File(out, "holes_$tag.json").writeText(cells.toString()) }
        // the floor a walker is on at each vertex under a bridge's, a tunnel's or a pier's floors (Climb.floorLayers): those steps classed by
        // its height; the floors no walker reaches (a roof, a parapet, a tunnel's floor from the hill over it) no ground. The other decks
        // (a city's streets, a building's porch) level the ground under them
        val layers = Climb.floorLayers(rect.t, rect.b, rect.l, rect.r, floorsAt, heightV)
        println("FLOORS ${layers.size} lattice vertices on a walkway's floor")
        File(out, "layers_$tag.json").writeText(org.json.JSONArray(layers.map { (k, z) -> org.json.JSONArray(listOf((k shr 32).toInt(), k.toInt(), z)) }).toString())
        val deckZ: (Double, Double) -> Float = { x, y -> layers[Climb.vkeyAt(x, y)] ?: Float.NaN }
        val cg = Climb.build(rect.t, rect.b, rect.l, rect.r, heightV, level = { x, y -> onFloor(x, y) || (deckAt(x, y) && floorsAt(x, y).isEmpty()) }, hole = hole, centre = centre, deck = deckZ)
        println("CLIMB lattice ${cg.nx} x ${cg.ny} in ${(System.nanoTime() - t0) / 1_000_000} ms; walls ${cg.cls.count { (it.toInt() and 3) == 3 } * 100 / cg.cls.size}% of the east steps")
        val ground: (Double, Double) -> Float = { u, v -> val wx = rect.t - v * ay; val wy = rect.l - u * ax; Terrain.factor(tg.at(wx, wy)) }
        val level = spec?.optInt("level", 8) ?: 8
        val g = DangerGrid.build(spawns, mine, level, ax, ay, ground)
        val k = DangerGrid.strength("high")
        // a narrow stream waded (SafePath.NARROW_DEPTH) costs as the mobs' middling danger does: a drier way wins where there is one
        val danger: (Double, Double) -> Float = { x, y -> uv(x, y).let { g.at(it[0], it[1]) } + if (waterGrid?.wading(x, y) == true) WADE_DANGER else 0f }
        // a bridge whose WMO floor is read (its groups dumped: Mulgore's Stonebull Lake bridge) is walked on its own floor, so the water
        // grid's bridge cells (the walkway's whole bounding box) are water again off that floor: the lake beside the deck is no bridge
        var floorCells: Set<Long> = emptySet()
        fun fkey(x: Double, y: Double) = Math.floorDiv(Math.round(x), 4L) * 100000 + Math.floorDiv(Math.round(y), 4L)
        // and a spot on a walkway's deck (its floor triangles, [deckAt]) is never water, whatever the 8 yd water cell it is in reads (a
        // narrow pier's edge in a cell whose middle is off it: Ratchet's dock tip); off the deck in a bridge's cell, the water there
        val deep: (Double, Double) -> Boolean = { x, y -> val d = waterAt(x, y)
            if (d.isNaN() || deckAt(x, y)) false
            else if (d == SafePath.Water.BRIDGE) rawDeep != null && rawDeep(x, y) &&
                (floorCells.isEmpty() || (-1..1).none { a -> (-1..1).any { b -> fkey(x + a * 4.0, y + b * 4.0) in floorCells } })
            else !(waterGrid?.wadeable(x, y) ?: (d <= SafePath.FORD_DEPTH)) }
        val reachedOf = HashMap<String, Boolean>()
        var lastReached = true
        var links: List<Climb.Link> = emptyList()
        val leg: (DoubleArray, DoubleArray) -> List<DoubleArray>? = { a, b -> Climb.route(cg, a, b, danger, k, deep, links)?.also { lastReached = it.reached }?.pts }
        // the placed buildings and caves (the obj0s' MODF), tilted ones too: their floors are placed by Placement.world (Stonetalon's
        // Talondeep Path tunnel is tilted 3 degrees and was dropped as "not level")
        val placed = (File(dir, "obj0").listFiles() ?: emptyArray()).flatMap { f -> WorldBuildings.placements(f.readBytes(), tilted = true) }.distinctBy { it.uid }
        // the caves: every placed cave / tunnel / mine WMO in the zone (its groups dumped), its floor, mouths and ways
        fun bil(wx: Double, wy: Double): Float {
            val gx = (ORG - wy) / S; val gy = (ORG - wx) / S; val i = Math.floor(gx).toInt(); val j = Math.floor(gy).toInt(); val tx = gx - i; val ty = gy - j
            val a = heightV(j, i); val b = heightV(j, i + 1).let { if (it.isNaN()) a else it }; val c = heightV(j + 1, i).let { if (it.isNaN()) a else it }
            val d = heightV(j + 1, i + 1).let { if (it.isNaN()) a else it }
            return ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty).toFloat()
        }
        // a mouth is where one can stand: off any wall face (the game's own triangles), a walkable step away from it
        val standable: (Double, Double) -> Boolean = { x, y -> val v = cg.at(x, y)
            cg.lineClear(doubleArrayOf(x, y), doubleArrayOf(x, y)) && (0..3).any { q -> cg.step(v[0], v[1], listOf(1, -1, 0, 0)[q], listOf(0, 0, 1, -1)[q]) != Climb.WALL } }
        val gdir = File(dir, "wmogroup")
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
        val derivedLifts = placed.filter { p -> p.ox <= rect.t && p.ox >= rect.b && p.oy <= rect.l && p.oy >= rect.r && File(gdir, "${p.fid}_0").isFile &&
            cityRects.any { r -> p.ox <= r[0] && p.ox >= r[1] && p.oy <= r[2] && p.oy >= r[3] } }.flatMap { p ->
            Caves.findLifts(p, (0 until 200).map { File(gdir, "${p.fid}_$it") }.takeWhile { it.isFile }.map { it.readBytes() }, travels) }
        println("LIFTS derived: ${travels.size} elevator travels (${travels.sorted().joinToString { "%.1f".format(it) }} yd); ${derivedLifts.size} lifts: " +
            derivedLifts.joinToString { "${it.name} at ${it.x.toInt()},${it.y.toInt()} z ${it.zBottom.toInt()} -> ${it.zTop.toInt()}" })
        // the routes ride the spec's measured lifts while the derivation is being finished (a harness fixture, never in the app); else the derived
        val measured = spec?.optJSONArray("lifts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Caves.Lift(o.getString("name"), o.getDouble("x"), o.getDouble("y"), o.getDouble("zBottom"), o.getDouble("zTop")) } }
        val lifts = measured ?: derivedLifts
        spec?.optJSONArray("lifts")?.let { a -> for (k in 0 until a.length()) { val o = a.getJSONObject(k)
            val d = derivedLifts.minByOrNull { Math.hypot(it.x - o.getDouble("x"), it.y - o.getDouble("y")) }
            println("LIFTS measured ${o.getString("name")}: ${d?.let { "derived one ${Math.hypot(it.x - o.getDouble("x"), it.y - o.getDouble("y")).toInt()} yd off, landings ${it.zBottom.toInt()} / ${it.zTop.toInt()} vs ${o.getDouble("zBottom").toInt()} / ${o.getDouble("zTop").toInt()}" } ?: "NOT derived"}") } }
        // the WMOs whose floors are ways: the dump's manifest (a cave, a walkway, one asked for by id); without it, every dumped one
        val kinds = File(gdir, "kinds.txt").takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split(' ').takeIf { it.size >= 2 }?.let { it[0].toInt() to it[1] } }?.toMap()
        // ... and a capital's own buildings (Thunder Bluff's rises and lift landings), and those the spec names ("floorWmos")
        val floorWmos = spec?.optJSONArray("floorWmos")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        val capRects = JSONObject(File(System.getenv("RECIPE")).readText()).optJSONObject("rects").let { rr ->
            com.jrees.wowcompanion.CityExits.CITIES.mapNotNull { c -> rr?.optJSONArray(c.toString())?.let { a -> doubleArrayOf(a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4)) } } }
        // a city walked on its own floors (the spec's "floorCity": Dalaran over Alterac) is no cave: its rock's underside meets the
        // crater's ground, and those "mouths" would join the city to the ground below
        val cityFids = spec?.optJSONObject("floorCity")?.optJSONArray("fids")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        // within 300 yd of the zone's rect: a tunnel out may stand just over the border (Loch Modan's Dun Algaz, 1.5 yd outside)
        val caves = placed.filter { p -> p.fid !in cityFids && p.ox <= rect.t + 300 && p.ox >= rect.b - 300 && p.oy <= rect.l + 300 && p.oy >= rect.r - 300 && File(gdir, "${p.fid}_0").isFile &&
            (kinds == null || p.fid in kinds || p.fid in floorWmos || capRects.any { r -> p.ox <= r[0] && p.ox >= r[1] && p.oy <= r[2] && p.oy >= r[3] }) }.map { p ->
            val gs = (0 until 200).map { File(gdir, "${p.fid}_$it") }.takeWhile { it.isFile }.map { it.readBytes() }
            Caves.cave(p, gs, ::bil, standable, lifts)
        }.filter { it.mouths.isNotEmpty() }
        for (c in caves) println("CAVE ${c.fid} at ${c.wx.toInt()},${c.wy.toInt()} (uiMap ${ar?.uiMap(zg.areaAt(c.wx, c.wy))}, ${areas[zg.areaAt(c.wx, c.wy)]?.name}): ${c.mouths.size} mouths ${c.mouths.joinToString { "${it[0].toInt()},${it[1].toInt()} z ${it[2].toInt()}" }}; ${c.ways.size} ways ${c.ways.joinToString { "${it.yd.toInt()} yd" }}")
        links = caves.flatMap { c -> c.ways.map { Climb.Link(it.pts, it.yd) } }
        // AN AIR CHAIN (the spec's "airChain": Zephras' Rohashi Spires, floating): a portal from the ground to the lowest floor of the
        // place built in the air, its own floors (stairs, perimeter paths) up to a stair top, an updraft across to a platform, and the
        // way out (a portal back down). Manual links - the game keeps portals and updrafts server-side
        val manual = org.json.JSONArray()
        var platformAt: DoubleArray? = null
        spec?.optJSONObject("airChain")?.let { ac ->
            fun xyOf(k: String) = ac.optJSONArray(k)?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) }
            val fids = ac.getJSONArray("floor").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            val near = xyOf("near")!!
            val parts = placed.filter { it.fid in fids && Math.hypot(it.ox - near[0], it.oy - near[1]) < 600 }.map { p ->
                p to (0 until 200).map { File(gdir, "${p.fid}_$it") }.takeWhile { it.isFile }.map { it.readBytes() } }
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
        fun camp(name: String): DoubleArray? {
            val a = areas.entries.firstOrNull { it.value.name.equals(name, true) && it.value.parent == zoneArea }?.key ?: return null
            val pts = spawns.filter { it.hostile == mine || it.hostile == 3 }.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
                .filter { zg.areaAt(it[0], it[1]) == a && !deep(it[0], it[1]) && height(it[0], it[1]) > 0 }
            return pts.maxByOrNull { p -> pts.count { Math.hypot(it[0] - p[0], it[1] - p[1]) < 60 } }
        }
        // a node by its name, or by its base name when towns sharing it were told apart ("Deathknell · Inn", "Gustberry Lowlands (2)")
        fun at(name: String) = (net.nodes.firstOrNull { it.name == name } ?: net.nodes.firstOrNull { it.name?.startsWith("$name ") == true })
            ?.let { doubleArrayOf(it.wx, it.wy) }
        /** a named mob's spawns at their thickest (near [near] when given: within 500 yd of it) */
        fun mob(name: String, near: DoubleArray?): DoubleArray? {
            val pts = spawns.filter { it.name.equals(name, true) }.map { doubleArrayOf(rect.t - it.y * ay, rect.l - it.x * ax) }
                .filter { !deep(it[0], it[1]) && (near == null || Math.hypot(it[0] - near[0], it[1] - near[1]) < 500) }
            return pts.maxByOrNull { p -> pts.count { Math.hypot(it[0] - p[0], it[1] - p[1]) < 60 } }
        }
        /** where an exit's way opens onto this zone's own level ground (a gate's tunnel or a lift is the other map's: its top not ours) */
        fun exitStart(name: String): DoubleArray? = net.exits.firstOrNull { it.name.equals(name, true) }?.let { x -> net.nodes.first { it.id == x.node } }?.let { gn ->
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
                to.has("camp") -> camp(to.getString("camp"))
                to.has("mob") -> mob(to.getString("mob"), near)
                to.has("node") -> at(to.getString("node"))
                to.optBoolean("airChainTop") -> platformAt
                to.has("exit") -> net.exits.firstOrNull { it.name.equals(to.getString("exit"), true) && (!to.has("how") || it.how == to.getString("how")) }
                    ?.let { x -> net.nodes.firstOrNull { it.id == x.node } }?.let { doubleArrayOf(it.wx, it.wy) }
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
        val trips = (spec?.optJSONArray("goals")?.let { specTrips(it) } ?: durotarTrips()).filter { it.second != null && it.third.second != null }
            .map { (fn, f, g) -> Triple(fn, stand(f), g.first to stand(g.second)) }
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
        val netW = SafePath.dropWalled(net, { a, b -> Climb.walkable(cg, a, b, deep) }) { a, b ->
            Climb.route(cg, a, b, water = deep)?.takeIf { it.reached }?.pts?.zipWithNext { p, q -> Math.hypot(q[0] - p[0], q[1] - p[1]) }?.sum() }
        println("DROPWALLED ${netW.edges.count { it.state == SafePath.REMOVED } - net.edges.count { it.state == SafePath.REMOVED }} drafted ways cross a wall on the fine ground: removed; " +
            "${netW.edges.count { it.state == SafePath.REWALK }} kept, walked by the ground's leg (a lip)")
        val uiAt: (Double, Double) -> Int = { x, y -> ar?.uiMap(zg.areaAt(x, y)) ?: 0 }
        val netCv = SafePath.withCaves(netW, caves, { x, y -> danger(x, y) / DangerGrid.CAP }, uiAt, mapName)
        // the ground's own crossings (a walkable corridor over the border, no road): exits too
        val netC = SafePath.groundExits(netCv, cg, uiAt, { x, y -> !deep(x, y) }, mapName)
        println("EXITS added: ${netCv.exits.size - netW.exits.size} through tunnels, ${netC.exits.size - netCv.exits.size} on the ground's own crossings")
        // THE EXITS CHECKED ON THE GROUND: from each, the lattice flooded (no wall step, no deep water, the caves' links taken) - how
        // much of this zone and of the neighbour it leads to it reaches. A way out that reaches only a pocket on either side is no way.
        val exitChecks = org.json.JSONArray()
        run {
            val n = cg.nx * cg.ny
            val dry = BooleanArray(n) { q -> !deep(cg.wx(q / cg.nx), cg.wy(q % cg.nx)) }
            val jump = HashMap<Int, ArrayList<Int>>()
            for (lk in links) { val a0 = cg.at(lk.pts.first()[0], lk.pts.first()[1]); val b0 = cg.at(lk.pts.last()[0], lk.pts.last()[1])
                val qa = a0[1] * cg.nx + a0[0]; val qb = b0[1] * cg.nx + b0[0]
                jump.getOrPut(qa) { ArrayList() }.add(qb); jump.getOrPut(qb) { ArrayList() }.add(qa) }
            val mapOf = IntArray(n) { q -> ar?.uiMap(zg.areaAt(cg.wx(q / cg.nx), cg.wy(q % cg.nx))) ?: 0 }
            // the zone's DRY lattice: an exit can't reach the sea floor (Teldrassil's rect is over half sea)
            val ownAll = (0 until n).count { mapOf[it] == map && dry[it] }
            for (x in netC.exits) {
                val node = netC.nodes.firstOrNull { it.id == x.node } ?: continue
                // a breadth-first flood (its depth the walk from the exit, in steps): the neighbour's ground must be reached NEAR the
                // exit - a real crossing is local; an unbounded flood called Mulgore's Feralas exit walkable by a 6,943 yd loop through Desolace
                val s0 = cg.at(node.wx, node.wy); val seen = BooleanArray(n); val st = IntArray(n); var head = 0; var tail = 0
                val depth = IntArray(n); val nearSteps = (EXIT_NEAR_YD / S).toInt()
                val q0 = s0[1] * cg.nx + s0[0]; seen[q0] = true; st[tail++] = q0
                var own = 0; var theirs = 0
                while (head < tail) {
                    val q = st[head++]; val i = q % cg.nx; val j = q / cg.nx
                    if (mapOf[q] == map) own++ else if (mapOf[q] == x.toMap && depth[q] <= nearSteps) theirs++
                    for (dj in -1..1) for (di in -1..1) {
                        if (di == 0 && dj == 0) continue
                        val vi = i + di; val vj = j + dj; if (vi !in 0 until cg.nx || vj !in 0 until cg.ny) continue
                        val v = vj * cg.nx + vi; if (seen[v] || !dry[v] || cg.step(i, j, di, dj) == Climb.WALL) continue
                        seen[v] = true; depth[v] = depth[q] + 1; st[tail++] = v
                    }
                    jump[q]?.forEach { v -> if (!seen[v]) { seen[v] = true; depth[v] = depth[q] + 1; st[tail++] = v } }
                }
                val ownYd = own * S * S; val theirYd = theirs * S * S
                val ok = own > ownAll / 4 && theirs * S * S > 20000
                println("EXIT ${x.name} (${x.how}) at ${node.wx.toInt()},${node.wy.toInt()} h ${height(node.wx, node.wy).toInt()}: reaches ${own * 100 / maxOf(1, ownAll)}% of this zone, " +
                    "${(theirYd / 1000).toInt()}k sq yd of ${x.name}'s ground within ${EXIT_NEAR_YD.toInt()} yd of it - ${if (ok) "WALKABLE" else "NOT WALKABLE"}")
                exitChecks.put(org.json.JSONArray(listOf(node.wx, node.wy, x.name, x.how, ok, own * 100 / maxOf(1, ownAll), (theirYd / 1000).toInt())))
            }
        }
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
            val p0 = (if (isl.has("node")) at(isl.getString("node")) else xy(isl.optJSONArray("xy"))) ?: run { println("SWIM ${o.optString("name")}: its island's spot not found"); null } ?: continue
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
            // the swim: from the island's ground through the water, nearest first, to ground of a far bigger piece
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
                        if (flood(ni, nj, 20_000).size < 20_000) continue             // another islet, not the mainland
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
            manual.put(org.json.JSONObject().put("kind", "swim").put("name", o.optString("name")).put("pts", org.json.JSONArray(pts.map { org.json.JSONArray(listOf(it[0], it[1], 0.0)) })))
        } }
        val baseLinks = links
        val export = org.json.JSONObject(); val rs = org.json.JSONArray()
        for ((i, trip) in trips.withIndex()) {
            val (fromName, f, goal) = trip; val (goalName, gp) = goal
            lastReached = true
            // a declared swim only for a goal on its island
            val gv = cg.at(gp!![0], gp[1]); val gk = (gv[1].toLong() shl 32) or (gv[0].toLong() and 0xFFFFFFFFL)
            val mySwims = swims.filter { gk in it.island }
            links = baseLinks + mySwims.map { it.link }
            val way = SafePath.routeOn(netC, f!![0], f[1], gp!![0], gp[1], leg, clear = clear, walkable = { a, b -> Climb.walkable(cg, a, b, deep) }) ?: leg(f, gp) ?: listOf(f, gp)
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
            rs.put(org.json.JSONObject().put("letter", letter).put("name", "$fromName → $goalName").put("reached", lastReached)
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
            .put("exits", org.json.JSONArray(netC.exits.mapNotNull { x -> byId[x.node]?.let { n -> org.json.JSONArray(listOf(n.wx, n.wy, x.name, x.how)) } }))
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
