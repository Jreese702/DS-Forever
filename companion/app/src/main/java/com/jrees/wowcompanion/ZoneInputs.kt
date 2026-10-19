package com.jrees.wowcompanion

/**
 * ONE ZONE'S GROUND, AS THE ROUTES READ IT (moved verbatim out of the harness's RenderSafePath, so the device's harvest and the harness
 * assemble it the same way): the water and the walkways' decks over it, the holes a building covers, the cells' middle vertices, the
 * floors a walker is on, the lattice, the rooms no line cuts through, the caves and the lifts. Every file comes through [src] (the
 * device: the player's own game files by fileDataID; the harness: the PC client's dumps), so nothing here is typed game data.
 */
class ZoneInputs(private val src: Source, val rect: MapHarvest.Rect, private val lethal: Set<Int>, private val capitalRects: List<DoubleArray>,
                 private val println: (String) -> Unit = {}) {
    /** the zone's files: the device reads them by fileDataID from the game, the harness from its dumps */
    interface Source {
        /** the root ADT of tile ([c] its column, [r] its row) */
        fun adt(c: Int, r: Int): ByteArray?
        /** the obj0 ADT of tile ([c], [r]) */
        fun obj0(c: Int, r: Int): ByteArray?
        /** a WMO's root */
        fun wmo(fid: Int): ByteArray?
        /** WMO [fid]'s group file [index] (its root's GFID order) */
        fun wmoGroup(fid: Int, index: Int): ByteArray?
        /** a doodad's model (M2) */
        fun m2(fid: Int): ByteArray?
        /** the tiles whose obj0 placements count (column, row), in order */
        fun objTiles(): List<IntArray>
    }

    // ---------------------------------------------------------------- the water and the decks (RenderSafePath.durotar, per map)

    // THE GRIDS REACH PAST THE ZONE'S RECT (SafePath.GRID_PAD_YD: an exit's neighbour ground, a tunnel's far mouth 226 yd beyond the
    // edge - Felwood's Timbermaw Hold, Desolace's Stonetalon and Feralas exits, Arathi's Thandol Span tunnels read as dead ends)
    val pad = SafePath.GRID_PAD_YD
    // the water: the root ADTs' MH2O over their ground
    private val wetTiles = HashMap<Pair<Int, Int>, Pair<FloatArray?, FloatArray?>>()
    fun wet(c: Int, r: Int) = wetTiles.getOrPut(c to r) { src.adt(c, r)?.let { b -> Terrain.water(b, lethal) to Terrain.tile(b)?.h } ?: (null to null) }
    // each walkway by its deck (its outdoor groups' floor triangles, the device's own WorldBuildings.deck), its box only where
    // its groups weren't dumped
    private val walks = HashMap<Pair<Int, Int>, List<(Double, Double) -> Boolean>>(); private val m2Said = HashSet<Int>()
    // A CAPITAL is a city WMO standing in a capital's own map (CityExits.CITIES' rects): its streets level whatever the ground under
    // them. Wide alone is no capital: Ashenvale's Talondeep tunnel, Desolace's caves and Hyjal's sky floors leveled the hills over or
    // under them, routes crossing 46-75 degrees (the Kalimdor segcheck helper)
    fun capital(p: WorldBuildings.Placement, gs: List<WorldBuildings.Group>) =
        WorldBuildings.isCity(p, gs) && capitalRects.any { r -> p.ox <= r[0] && p.ox >= r[1] && p.oy <= r[2] && p.oy >= r[3] }
    val unread = HashSet<Int>()
    val capitalDecks = HashSet<WorldBuildings.Deck>()   // a capital's read floors: leveled whatever their height (flag 2)
    val cityFeet = ArrayList<Buildings.Foot>()   // a capital's streets leveled by their boxes (py/segcheck.py: flag 2)
    val deckList = ArrayList<WorldBuildings.Deck>()     // every deck read (py/wetcheck.py: decks_<tag>.json)
    // per tile: the decks walked at their own floor's height - a bridge's or a pier's (no rooms), a fifth or more of its floor over
    // the water (a building's porch by the shore, its roof read as floor too, a cave's mouth, a city's streets stay the level
    // walkways they were)
    val tileDecks = HashMap<Pair<Int, Int>, ArrayList<WorldBuildings.Deck>>()
    fun overWater(d: WorldBuildings.Deck): Boolean {
        val ts = d.triangles; if (ts.isEmpty()) return false
        val wetN = ts.count { t -> val x = (t[0] + t[2] + t[4]) / 3; val y = (t[1] + t[3] + t[5]) / 3
            !SafePath.Water.depthOf(x, y, { c, r -> wet(c, r).first }, { c, r -> wet(c, r).second }).isNaN() }
        return wetN * 5 >= ts.size
    }
    fun keepHeights(c: Int, r: Int, d: WorldBuildings.Deck, any: Boolean = false) { if (any || overWater(d)) tileDecks.getOrPut(c to r) { ArrayList() }.add(d) }
    // the M2 bridges (the device's own WorldBuildings.m2Decks: a doodad by the water whose model's own name says bridge, dock,
    // pier or walkway), from the models dump/dump_m2bridges.py put in <dir>/m2
    fun m2DecksOf(c: Int, r: Int): List<(Double, Double) -> Boolean> {
        val obj = src.obj0(c, r) ?: return emptyList()
        val surface = wet(c, r).first ?: return emptyList()
        return WorldBuildings.doodads(obj).filter { Terrain.wetNear(surface, c, r, it.ox, it.oy, WorldBuildings.M2_NEAR_WATER_YD) }.mapNotNull { p ->
            val m = src.m2(p.fid) ?: return@mapNotNull null
            val name = WorldBuildings.m2Name(m)
            val d = WorldBuildings.m2Deck(p, m)
            if (m2Said.add(p.uid)) println("DECK M2 ${p.fid} '$name' at ${p.ox.toInt()},${p.oy.toInt()} z ${p.oz.toInt()} yaw ${Math.toDegrees(p.yaw).toInt()}: " +
                if (d == null) "not a bridge (or no collision floor)" else "a bridge, its deck read")
            d?.let { dk -> deckList.add(dk); keepHeights(c, r, dk); { x: Double, y: Double -> dk.holds(x, y) && dk.over(x, y, Terrain.surfaceAt(surface, c, r, x, y)) } }
        }
    }
    // a deck's floors all more than LEVEL_BURIED_YD under the terrain at a spot (its nearest outer vertex): buried, no ground there
    fun buriedAt(c: Int, r: Int, d: WorldBuildings.Deck, x: Double, y: Double): Boolean {
        val hs = d.heightsAt(x, y); if (hs.isEmpty()) return false
        val h = wet(c, r).second ?: return false
        val ci = (((32 * MapHarvest.ADT_YD - y) / MapHarvest.ADT_YD - c) * 128).toInt().coerceIn(0, 127)
        val cj = (((32 * MapHarvest.ADT_YD - x) / MapHarvest.ADT_YD - r) * 128).toInt().coerceIn(0, 127)
        val g = h[cj * 128 + ci]; if (g.isNaN()) return false
        return hs.all { it < g - LEVEL_BURIED_YD }
    }
    fun walksOf(c: Int, r: Int) = walks.getOrPut(c to r) {
        src.obj0(c, r)?.let { WorldBuildings.placements(it, tilted = true) }?.flatMap { p ->
            val root = src.wmo(p.fid)
            val feet = WorldBuildings.walkways(p, root?.let { WorldBuildings.groups(it) } ?: emptyList())
            if (feet.isEmpty() || root == null) emptyList() else {
                val gf = WorldBuildings.groupFiles(root)
                WorldBuildings.deck(p, root) { id -> gf.indexOf(id).takeIf { it >= 0 }?.let { src.wmoGroup(p.fid, it) } }
                    ?.takeIf { !it.empty }?.let { d -> deckList.add(d)
                        // a bridge or a pier is all outdoor groups: a cave's or a building's (with rooms) outdoor floor stays level
                        // (Zephras' Bandit Hideout: its mouth's floor by the water walled its own goal)
                        val gs = WorldBuildings.groups(root)
                        // ... over water or not: a dry ravine's bridge, a tunnel's floor under its hill (Wetlands' Dun Algaz: leveled, its
                        // hillside 60-75 degrees over it was climbed) are walked at their own floor's height too
                        if (!capital(p, gs) && gs.none { it.interior }) keepHeights(c, r, d, any = true)
                        // ... and a floor is the ground only where it is not buried: more than LEVEL_BURIED_YD under the terrain (Blasted
                        // Lands' Garrison Armory mine: its outdoor floors at 0-40 under a hill to 263 leveled it, a route climbed it)
                        // (a capital's streets level whatever the terrain under or over them: its ground reads steep or high everywhere)
                        val isCapital = capital(p, gs)
                        if (isCapital) capitalDecks.add(d)
                        listOf<(Double, Double) -> Boolean>({ x, y -> d.holds(x, y) && (isCapital || !buriedAt(c, r, d, x, y)) && d.over(x, y, Terrain.surfaceAt(wet(c, r).first, c, r, x, y)) }) }
                    // a WMO whose floors can't be read (its groups not dumped) levels nothing - the ground under its box is the
                    // terrain's (Silithus: Cenarion Hold's outpost, undumped, let a route over a 67-90 degree step) - but a capital's
                    // streets, whose ground under them reads steep everywhere
                    ?: if (capital(p, WorldBuildings.groups(root))) { cityFeet.addAll(feet); feet.map { f -> { x: Double, y: Double -> f.holds(x, y) } } }
                    else { if (unread.add(p.fid)) println("WARNING WMO ${p.fid} at ${p.ox.toInt()},${p.oy.toInt()}: " +
                        (if (src.wmoGroup(p.fid, 0) != null) "its groups hold no readable floor" else "its floors not dumped (dump/dump_cavegroups.py <dir> ${p.fid})") +
                        " - the ground under it is taken as the terrain"); emptyList() }
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
    /** a walkway's deck at world (x, y) (its floor over the water, or its box where its floors can't be read) */
    fun deckAt(x: Double, y: Double): Boolean = PlaceNames.locate(x, y).let { l -> walksOf(l[0], l[1]).any { it(x, y) } }
    val wg: SafePath.Water by lazy { SafePath.Water.build(rect.t + pad, rect.b - pad, rect.l + pad, rect.r - pad, { x, y -> PlaceNames.locate(x, y).let { l -> walksOf(l[0], l[1]).any { it(x, y) } } }) { x, y ->
        SafePath.Water.depthOf(x, y, { c, r -> wet(c, r).first }, { c, r -> wet(c, r).second }) } }
    /**
     * DEEP WATER, a wall to every route (the user: "Pathing shouldn't be in water"): at world (x, y) on no walkway's deck, deeper than the
     * ford, or a narrow stream's ([SafePath.Water.wadeable], the user's ruling) at most. A spot on a deck ([deckAt]) is never water, whatever
     * the water cell it is in reads (a narrow pier's edge in a cell whose middle is off it: Ratchet's dock tip); off the deck in a
     * bridge's cell, the water as it is ([rawDeep]: Felwood, a floor within 4 yd let a route step off the Bloodvenom dock into 2.6-4.7 yd
     * water)
     */
    val deep: (Double, Double) -> Boolean = { x, y -> val d = wg.at(x, y)
        if (d.isNaN() || deckAt(x, y)) false
        else if (d == SafePath.Water.BRIDGE) rawDeep(x, y)
        else !wg.wadeable(x, y) }
    /** the liquid's own surface over the ground at world (x, y) deeper than the ford (py/wetcheck.py's reading) */
    fun rawDeep(x: Double, y: Double): Boolean = SafePath.Water.depthOf(x, y, { c, r -> wet(c, r).first }, { c, r -> wet(c, r).second })
            .let { !it.isNaN() && it > SafePath.FORD_DEPTH }

    // ---------------------------------------------------------------- the terrain's heights

    private val tiles = HashMap<Pair<Int, Int>, Terrain.Tile?>()
    /** the root ADT of tile ([c], [r]), read */
    fun tile(c: Int, r: Int): Terrain.Tile? = tiles.getOrPut(c to r) { src.adt(c, r)?.let { Terrain.tile(it) } }
    /** an outer vertex's height (global row, column) */
    fun height(gr: Int, gc: Int): Float = tile(gc / 128, gr / 128)?.h?.get((gr % 128) * 128 + (gc % 128)) ?: Float.NaN
    /** the heights a walker stands on: a floating zone's void floor ([voidBelow]: Zephras Isle's flat ground far below the island) none */
    fun heightV(voidBelow: Double): (Int, Int) -> Float = { gr, gc ->
        height(gr, gc).let { if (!voidBelow.isNaN() && it < voidBelow) Float.NaN else it } }

    // ---------------------------------------------------------------- the lattice (RenderSafePath.levels2, the main zone)

    /** the zone's lattice and what stands on it, on the heights [heightV] (global row, column; NaN none: [ZoneInputs.heightV]) */
    inner class Lattice(val heightV: (Int, Int) -> Float) {
        val S = Climb.STEP; val ORG = 32 * MapHarvest.ADT_YD
        fun height(wx: Double, wy: Double): Float = heightV(Math.round((ORG - wx) / S).toInt(), Math.round((ORG - wy) / S).toInt())
        val t0 = System.nanoTime()
        // the ADTs' holes (the terrain opened into a cave), per global cell
        private val holeTiles = HashMap<Pair<Int, Int>, BooleanArray?>()
        // a hole under a placed building is its floor's place (a garrison's, a barn's: the WMO is the ground there), no wall; elsewhere
        // the terrain opened into a cave, its ground not there
        private val covers = HashMap<Long, ArrayList<Buildings.Foot>>()
        init {
            for (f in src.objTiles()) for (p in WorldBuildings.placements(src.obj0(f[0], f[1]) ?: continue)) {
            if (p.ox > rect.t + 100 || p.ox < rect.b - 100 || p.oy > rect.l + 100 || p.oy < rect.r - 100) continue
            val gs = src.wmo(p.fid)?.let { WorldBuildings.groups(it) } ?: continue
            val cs = Math.cos(p.yaw); val sn = Math.sin(p.yaw)
            for (g in gs) {
                val bx = g.box
                val ft = Buildings.Foot(p.ox, p.oy, cs, sn, minOf(bx[0], bx[3]) * p.scale, maxOf(bx[0], bx[3]) * p.scale,
                    minOf(bx[1], bx[4]) * p.scale, maxOf(bx[1], bx[4]) * p.scale)
                for (cx in Math.floorDiv(ft.wx0.toLong(), 64L)..Math.floorDiv(ft.wx1.toLong(), 64L)) for (cy in Math.floorDiv(ft.wy0.toLong(), 64L)..Math.floorDiv(ft.wy1.toLong(), 64L))
                    covers.getOrPut(cx * 100000 + cy) { ArrayList() }.add(ft)
            }
        }
        }
        fun covered(x: Double, y: Double) = covers[Math.floorDiv(x.toLong(), 64L) * 100000 + Math.floorDiv(y.toLong(), 64L)]?.any { it.holds(x, y) } == true
        fun holed(gr: Int, gc: Int): Boolean { val key = (gc / 128) to (gr / 128)
            if (key !in holeTiles) holeTiles[key] = src.adt(gc / 128, gr / 128)?.let { Terrain.holes(it) }
            return holeTiles[key]?.get((gr % 128) * 128 + (gc % 128)) == true }
        val hole: (Int, Int) -> Boolean = { gr, gc -> holed(gr, gc) && !covered(ORG - (gr + 0.5) * S, ORG - (gc + 0.5) * S) }
        // beside a hole a building or a cave covers, its floor is the ground: the terrain's heights there (under it) no wall
        val onFloor: (Double, Double) -> Boolean = { x, y -> val gr = Math.round((ORG - x) / S).toInt(); val gc = Math.round((ORG - y) / S).toInt()
            listOf(gr - 1 to gc - 1, gr - 1 to gc, gr to gc - 1, gr to gc).any { (r, c) -> holed(r, c) && covered(ORG - (r + 0.5) * S, ORG - (c + 0.5) * S) } }
        // the cells' middle vertices (the game's surface: four triangles a cell through each)
        val centre: (Int, Int) -> Float = { gr, gc -> tile(gc / 128, gr / 128)?.c?.get((gr % 128) * 128 + (gc % 128)) ?: Float.NaN }
        /** the holes a building's or a cave's floor covers (py/segcheck.py: no terrain there, the floor is the ground): global row, column */
        val coveredHoles: List<IntArray> by lazy { val gr0 = Math.floor((ORG - rect.t) / S).toInt(); val gr1 = Math.ceil((ORG - rect.b) / S).toInt()
            val gc0 = Math.floor((ORG - rect.l) / S).toInt(); val gc1 = Math.ceil((ORG - rect.r) / S).toInt()
            val cells = ArrayList<IntArray>()
            for (gr in gr0..gr1) for (gc in gc0..gc1) if (holed(gr, gc) && covered(ORG - (gr + 0.5) * S, ORG - (gc + 0.5) * S)) cells.add(intArrayOf(gr, gc))
            cells }
        // the floor a walker is on at each vertex under a bridge's, a tunnel's or a pier's floors (Climb.floorLayers): those steps classed by
        // its height; the floors no walker reaches (a roof, a parapet, a tunnel's floor from the hill over it) no ground. The other decks
        // (a city's streets, a building's porch) level the ground under them
        val layers = Climb.floorLayers(rect.t + pad, rect.b - pad, rect.l + pad, rect.r - pad, ::floorsAt, heightV)
        init { println("FLOORS ${layers.size} lattice vertices on a walkway's floor") }
        val deckZ: (Double, Double) -> Float = { x, y -> layers[Climb.vkeyAt(x, y)] ?: Float.NaN }
        val cg = Climb.build(rect.t + pad, rect.b - pad, rect.l + pad, rect.r - pad, heightV, level = { x, y -> onFloor(x, y) || (deckAt(x, y) && floorsAt(x, y).isEmpty()) }, hole = hole, centre = centre, deck = deckZ)
        // the buildings' rooms (WorldBuildings.feet: a room rising over the ground, not a city's, not a mine's under the hill): no straight
        // line through them (Climb.Grid.solid), and their ground costs as the mobs' middling danger (BUILDING_DANGER): a route goes round
        // a house unless its goal is in it (the user: "No cutting through")
        val roomFeet = HashMap<Pair<Int, Int>, List<Buildings.Foot>>()
        private val wmoGroups = HashMap<Int, List<WorldBuildings.Group>>()
        fun roomsAt(x: Double, y: Double): Boolean { val l = PlaceNames.locate(x, y)
            return roomFeet.getOrPut(l[0] to l[1]) { src.obj0(l[0], l[1])?.let { WorldBuildings.placements(it) }?.flatMap { p ->
                val gs = wmoGroups.getOrPut(p.fid) { src.wmo(p.fid)?.let { WorldBuildings.groups(it) } ?: emptyList() }
                WorldBuildings.feet(p, gs) { wx, wy -> height(wx, wy).toDouble() } } ?: emptyList() }.any { it.holds(x, y) } }
        init {
            cg.solid = { x, y -> roomsAt(x, y) }
            println("CLIMB lattice ${cg.nx} x ${cg.ny} in ${(System.nanoTime() - t0) / 1_000_000} ms; walls ${cg.cls.count { (it.toInt() and 3) == 3 } * 100 / cg.cls.size}% of the east steps")
        }
        // the placed buildings and caves (the obj0s' MODF), tilted ones too: their floors are placed by Placement.world (Stonetalon's
        // Talondeep Path tunnel is tilted 3 degrees and was dropped as "not level")
        val placed = src.objTiles().flatMap { f -> src.obj0(f[0], f[1])?.let { WorldBuildings.placements(it, tilted = true) } ?: emptyList() }.distinctBy { it.uid }
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
        // a WMO's own groups (MOHD's count, at most 400; the group list goes on with LOD copies): Stormwind's city WMO has 289, its harbour
        // group 282, the Undercity's 216 - 200 were read (the recheck worker's patch)
        fun groupsOf(fid: Int): List<ByteArray> {
            val n = src.wmo(fid)?.let { b -> var o = 0; var n = 200
                while (o + 8 <= b.size) { val len = Db2.le32(b, o + 4)
                    if (String(byteArrayOf(b[o + 3], b[o + 2], b[o + 1], b[o])) == "MOHD") { n = Db2.le32(b, o + 12).coerceIn(1, 400); break }
                    if (len < 0) break; o += 8 + len }
                n } ?: 200
            return (0 until n).asSequence().map { src.wmoGroup(fid, it) }.takeWhile { it != null }.map { it!! }.toList()
        }
        /** WMO [fid]'s groups can be read (the harness: dumped) */
        fun groupsRead(fid: Int) = src.wmoGroup(fid, 0) != null

        /**
         * the zone's caves and lifts: [travels] the elevators' rises (TransportAnimation, Caves.liftTravels), [cityRects] the capitals'
         * rects. The harness's own knobs (the dump's names): [kinds] the WMOs its manifest calls caves and walkways (null: every WMO, by
         * its shape alone - the device's way), [floorWmos] and [cityFids] its spec's, [measured] its measured lifts
         */
        inner class Caving(travels: List<Double>, cityRects: List<DoubleArray>, kinds: Map<Int, String>? = null, floorWmos: Set<Int> = emptySet(),
                           cityFids: Set<Int> = emptySet(), measured: List<Caves.Lift>? = null) {
        val derivedLifts = placed.filter { p -> p.ox <= rect.t && p.ox >= rect.b && p.oy <= rect.l && p.oy >= rect.r && groupsRead(p.fid) &&
            cityRects.any { r -> p.ox <= r[0] && p.ox >= r[1] && p.oy <= r[2] && p.oy >= r[3] } }.flatMap { p ->
            Caves.findLifts(p, groupsOf(p.fid), travels) }
        init {
            println("LIFTS derived: ${travels.size} elevator travels (${travels.sorted().joinToString { "%.1f".format(it) }} yd); ${derivedLifts.size} lifts: " +
            derivedLifts.joinToString { "${it.name} at ${it.x.toInt()},${it.y.toInt()} z ${it.zBottom.toInt()} -> ${it.zTop.toInt()}" })
        }
        val lifts = measured ?: derivedLifts
        init { measured?.let { a -> for (o in a) {
            val d = derivedLifts.minByOrNull { Math.hypot(it.x - o.x, it.y - o.y) }
            println("LIFTS measured ${o.name}: ${d?.let { "derived one ${Math.hypot(it.x - o.x, it.y - o.y).toInt()} yd off, landings ${it.zBottom.toInt()} / ${it.zTop.toInt()} vs ${o.zBottom.toInt()} / ${o.zTop.toInt()}" } ?: "NOT derived"}") } } }
        // within 300 yd of the zone's rect: a tunnel out may stand just over the border (Loch Modan's Dun Algaz, 1.5 yd outside)
        val caves = placed.filter { p -> p.fid !in cityFids && p.ox <= rect.t + pad && p.ox >= rect.b - pad && p.oy <= rect.l + pad && p.oy >= rect.r - pad && groupsRead(p.fid) &&
            (kinds == null || p.fid in kinds || p.fid in floorWmos || cityRects.any { r -> p.ox <= r[0] && p.ox >= r[1] && p.oy <= r[2] && p.oy >= r[3] }) }.map { p ->
            val gs = groupsOf(p.fid)
            Caves.cave(p, gs, ::bil, standable, lifts)
        }.filter { it.mouths.isNotEmpty() }
        }
    }

    companion object {
        /** a floor this far under the terrain or more is under it (a mine's, a tunnel's), never the ground there (py/segcheck.py: the same) */
        const val LEVEL_BURIED_YD = 8.0
    }
}
