package com.jrees.wowcompanion

/**
 * THE "safepathzones" STEP'S WATER (moved verbatim out of SafePath.ZONES_STEP so the parity test runs the device's own): a zone's water
 * grid (WTR2: per lattice vertex the liquid's depth, a bridge per cell) from its root ADTs' MH2O, each walkway over the water by its
 * deck (its outdoor groups' floor triangles; its box only where the groups can't be read) and the M2 bridges by their models' own
 * names. [adt] / [obj] a tile's root / obj0 ADT (column, row), [read] a file by its fileDataID; the tiles' water kept while near.
 */
class ZoneWater(private val lethal: Set<Int>, private val adt: (Int, Int) -> ByteArray?, private val obj: (Int, Int) -> ByteArray?,
                private val read: (Int) -> ByteArray?) {
            // each root's water and ground, kept while near (the water grid reads them cell by cell)
            class Wet(val surface: FloatArray?, val ground: FloatArray?, val walks: List<(Double, Double) -> Boolean>)
            private val wmoGroups = HashMap<Int, List<WorldBuildings.Group>>()
            private val m2Floors = HashMap<Int, FloatArray?>()            // each doodad model read once: its floor when it is a bridge, else null
            private val wets = object : LinkedHashMap<Long, Wet>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Wet>?) = size > 24
            }
                fun wet(tc: Int, tr: Int): Wet {
                    val key = MapHarvest.key(tc, tr).toLong()
                    return wets[key] ?: run {
                        val b = adt(tc, tr) ?: return@run Wet(null, null, emptyList())
                        val surface = Terrain.water(b, lethal)
                        // the outdoor walkways over the water (a bridge, a pier): only where the tile has water at all
                        // each walkway by its DECK (its outdoor groups' own floor triangles), the box only where the groups can't be read
                        val walks: List<(Double, Double) -> Boolean> = if (surface == null) emptyList() else {
                            val ob = obj(tc, tr)
                            ob?.let { WorldBuildings.placements(it, tilted = true) }?.flatMap { p ->
                                val root = read(p.fid)
                                val feet = WorldBuildings.walkways(p, wmoGroups.getOrPut(p.fid) { root?.let { WorldBuildings.groups(it) } ?: emptyList() })
                                if (feet.isEmpty()) emptyList()
                                else root?.let { WorldBuildings.deck(p, it) { id -> read(id) } }?.takeIf { !it.empty }?.let { d -> listOf<(Double, Double) -> Boolean>({ x, y -> d.holds(x, y) && d.over(x, y, Terrain.surfaceAt(surface, tc, tr, x, y)) }) }
                                    ?: feet.map { f -> { x: Double, y: Double -> f.holds(x, y) } }
                            }.orEmpty() + ob?.let { o ->
                                // and the M2 bridges (Duskwood's covered bridge into Westfall), by their models' own names
                                WorldBuildings.m2Decks(o, { x, y -> Terrain.wetNear(surface, tc, tr, x, y, WorldBuildings.M2_NEAR_WATER_YD) }) { fid ->
                                    if (fid in m2Floors) m2Floors[fid] else read(fid)?.let { WorldBuildings.m2BridgeFloor(it) }.also { m2Floors[fid] = it } }
                                    .map { d -> { x: Double, y: Double -> d.holds(x, y) && d.over(x, y, Terrain.surfaceAt(surface, tc, tr, x, y)) } }
                            }.orEmpty()
                        }
                        Wet(surface, Terrain.tile(b)?.h, walks)
                    }.also { wets[key] = it }
                }
    /** zone [rect]'s water grid */
    fun build(rect: MapHarvest.Rect): SafePath.Water {
                fun tileOf(wx: Double, wy: Double) = PlaceNames.locate(wx, wy).let { it[0] to it[1] }
                return SafePath.Water.build(rect.t, rect.b, rect.l, rect.r, { wx, wy -> tileOf(wx, wy).let { (c, r) -> wet(c, r).walks.any { it(wx, wy) } } }) { wx, wy ->
                    SafePath.Water.depthOf(wx, wy, { c, r -> wet(c, r).surface }, { c, r -> wet(c, r).ground }) }
    }
}
