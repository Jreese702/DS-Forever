package com.jrees.wowcompanion

import kotlin.math.ceil
import kotlin.math.floor

/**
 * MAP'S ART HARVEST, THE PURE HALF (the user 2026-10-09, phase 1: the APK carries no game art; the first run builds it from the user's
 * own game files). Plans what [MapHarvestSteps] draws, from the game's own tables, so a game update that changes the art changes the
 * output. No Android types: tools/map_harvest_test runs it against the PC's copies of the same tables.
 *
 * THE WORLD MAP SET (the Map room's painted maps) - the PC chain was tools/icons/worldmap (build_worldmap_set.py and friends), fed by
 * the add-on's live /wcart sweep. On the device the tables give the same thing; MEASURED 2026-10-09 on this build: all 60 maps, their
 * art tiles, layer sizes, every overlay and every highlight came out identical to the PC set (E:/wc-scratch-c9979a42/harvest/
 * verify_route.py):
 *   UiMapXMapArt (PhaseID 0): uiMap -> art;  UiMapArt (+ its COPY TABLE: Forever's arts are copies of the classic rows): the art's
 *   highlight picture (field 0) and style (field 2);  UiMapArtStyleLayer (LayerIndex 0, parent = the style): width, height, tile size;
 *   UiMapArtTile (LayerIndex 0, parent = the art): row, col, fileDataID;  WorldMapOverlay (field 1 = the art): its texture size,
 *   offset and hit box;  WorldMapOverlayTile (parent = the overlay): row, col, fileDataID;  UiMap: the name (string field 0), the
 *   parent (2) and the type (5).
 * The world RECT of each map is not in a table this reader can split (UiMapAssignment's array fields): it is the recipe's.
 *
 * THE MINIMAP TILES (Home's small map) - the PC took the tiles' fileDataIDs from the community listfile; the device takes them from the
 * game's own WDT: Map.db2 (field 0 the folder, field 21 the WDT's fileDataID) -> the WDT's MAID chunk (64 x 64 entries of 8 uint32,
 * row-major [row][col]; the 8th is the minimap texture). MEASURED: the same fileDataIDs as the listfile on every tile MAID has (6 of 7
 * maps checked; Kalimdor's MAID lacks 18 of the PC's 866, its stale ocean tiles).
 */
object MapHarvest {
    // the game's tables (DB2 fileDataIDs; a table keeps its id across builds)
    const val DB_UIMAP = 1957206
    const val DB_UIMAP_X_ART = 1957217
    const val DB_UIMAP_ART = 1957202
    const val DB_ART_STYLE_LAYER = 1957208
    const val DB_ART_TILE = 1957210
    const val DB_OVERLAY = 1134579
    const val DB_OVERLAY_TILE = 1957212
    const val DB_MAP = 1349477
    const val MAP_DIRECTORY = 0
    const val MAP_WDT = 21

    /** one ADT on the minimap grid, in yards */
    const val ADT_YD = 533.33333
    /** a map whose tile box (with its ring) is bigger than this is a continent / the world: its painted art stays (the PC's rule) */
    const val MAX_ZONE_TILES = 500
    const val OVERLAY_TILE_PX = 256

    class Tile(val row: Int, val col: Int, val fid: Int)
    class Overlay(val id: Int, val tiles: List<Tile>, val x: Int, val y: Int, val w: Int, val h: Int, val hit: IntArray) {
        val fid: Int get() = tiles.first().fid
    }
    class Art(val uiMap: Int, val name: String, val type: Int, val parent: Int, val artId: Int, val w: Int, val h: Int, val tile: Int,
              val tiles: List<Tile>, val overlays: List<Overlay>, val hl: Int)

    /**
     * Every map with art, in the UiMap table's order: [uiMap] read with string field 0, [artCopies] = UiMapArt's copy table. The
     * same rules as the PC chain: the art layer's tiles by their own row / col (the add-on's array order was row-major, the same
     * layout), an overlay's tiles by (row, col), overlays with no tile left out.
     */
    fun worldMaps(uiMap: List<Db2.Row>, xArt: List<Db2.Row>, art: List<Db2.Row>, artCopies: Map<Int, Int>, style: List<Db2.Row>,
                  artTile: List<Db2.Row>, overlay: List<Db2.Row>, overlayTile: List<Db2.Row>): List<Art> {
        val artOf = HashMap<Int, Int>()
        for (r in xArt) if (r.int(0) == 0) r.parent?.let { artOf[it] = r.int(1) }
        val arts = HashMap<Int, Db2.Row>()
        for (r in art) arts[r.id] = r
        for ((copy, src) in artCopies) if (copy !in arts) arts[src]?.let { arts[copy] = it }
        val layer = HashMap<Int, Db2.Row>()
        for (r in style) if (r.int(0) == 0) r.parent?.let { layer[it] = r }
        val tilesOf = HashMap<Int, MutableList<Tile>>()
        for (r in artTile) if (r.int(2) == 0) r.parent?.let { tilesOf.getOrPut(it) { ArrayList() }.add(Tile(r.int(0), r.int(1), r.int(3))) }
        val overlaysOf = LinkedHashMap<Int, MutableList<Db2.Row>>()
        for (r in overlay) overlaysOf.getOrPut(r.int(1)) { ArrayList() }.add(r)
        val ovTiles = HashMap<Int, MutableList<Tile>>()
        for (r in overlayTile) r.parent?.let { ovTiles.getOrPut(it) { ArrayList() }.add(Tile(r.int(0), r.int(1), r.int(3))) }
        val byRowCol = compareBy<Tile>({ it.row }, { it.col }, { it.fid })
        val out = ArrayList<Art>()
        for (m in uiMap) {
            val aid = artOf[m.id] ?: continue
            val a = arts[aid] ?: continue
            val st = layer[a.int(2)] ?: continue
            val tiles = tilesOf[aid]?.sortedWith(byRowCol) ?: continue
            if (tiles.isEmpty()) continue
            val ovs = ArrayList<Overlay>()
            for (o in overlaysOf[aid].orEmpty()) {
                val ts = ovTiles[o.id]?.sortedWith(byRowCol) ?: continue
                if (ts.isEmpty()) continue
                ovs.add(Overlay(o.id, ts, o.int(4), o.int(5), o.int(2), o.int(3), intArrayOf(o.int(6), o.int(7), o.int(8), o.int(9))))
            }
            out.add(Art(m.id, m.str(0), m.int(5), m.int(2), aid, st.int(1), st.int(2), st.int(3), tiles, ovs, a.int(0)))
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ the minimap tiles

    /** a tile's key: its ADT column and row */
    fun key(col: Int, row: Int): Int = (col shl 8) or row
    fun colOf(key: Int): Int = key shr 8
    fun rowOf(key: Int): Int = key and 0xFF

    /** the WDT's minimap texture per ADT (key -> fileDataID), or null when it has no MAID chunk */
    fun maidMinimap(wdt: ByteArray): Map<Int, Int>? {
        var o = 0
        while (o + 8 <= wdt.size) {
            val tag = String(charArrayOf(wdt[o + 3].toInt().toChar(), wdt[o + 2].toInt().toChar(), wdt[o + 1].toInt().toChar(), wdt[o].toInt().toChar()))
            val n = Db2.le32(wdt, o + 4)
            if (n < 0 || o + 8 + n > wdt.size) return null
            if (tag == "MAID") {
                val out = HashMap<Int, Int>()
                for (i in 0 until n / 32) {
                    val fid = Db2.le32(wdt, o + 8 + i * 32 + 28)              // the 8th uint32: the minimap texture
                    if (fid != 0) out[key(i % 64, i / 64)] = fid           // MAID is [row][col]
                }
                return out
            }
            o += 8 + n
        }
        return null
    }

    /** a map's world rect: continent, top / bottom (world X, north), left / right (world Y, west) - the recipe's [c, t, b, l, r] */
    class Rect(val c: Int, val t: Double, val b: Double, val l: Double, val r: Double)

    /** the ADT tiles under [rect] and a one-tile ring (the PC's rule), or null for a continent-sized map */
    fun tilesUnder(rect: Rect): List<Int>? {
        val col0 = floor(32.0 - rect.l / ADT_YD).toInt() - 1
        val col1 = ceil(32.0 - rect.r / ADT_YD).toInt() + 1
        val row0 = floor(32.0 - rect.t / ADT_YD).toInt() - 1
        val row1 = ceil(32.0 - rect.b / ADT_YD).toInt() + 1
        if ((col1 - col0) * (row1 - row0) > MAX_ZONE_TILES) return null
        val out = ArrayList<Int>()
        for (row in row0 until row1) for (col in col0 until col1) if (col in 0..63 && row in 0..63) out.add(key(col, row))
        return out
    }

    /** the tiles to make per continent: every zone's tiles that the continent's WDT has */
    fun wanted(rects: Collection<Rect>, maid: Map<Int, Map<Int, Int>>): Map<Int, Set<Int>> {
        val out = LinkedHashMap<Int, MutableSet<Int>>()
        for (r in rects) {
            val m = maid[r.c] ?: continue
            val under = tilesUnder(r) ?: continue
            val s = out.getOrPut(r.c) { LinkedHashSet() }
            for (k in under) if (k in m) s.add(k)
        }
        return out
    }

    /**
     * NEAREST ZONE FIRST (the user's pick): the player's continent [nearC] first, its tiles by distance from the player's world spot
     * ([nearX] north, [nearY] west), then the other continents in id order, each by row / col. Null [nearC]: every continent in id order.
     */
    fun order(want: Map<Int, Set<Int>>, nearC: Int?, nearX: Double, nearY: Double): List<Pair<Int, Int>> {
        val pc = 32.0 - nearY / ADT_YD
        val pr = 32.0 - nearX / ADT_YD
        val out = ArrayList<Pair<Int, Int>>()
        val conts = want.keys.sortedWith(compareBy({ it != nearC }, { it }))
        for (c in conts) {
            val keys = want[c].orEmpty()
            val sorted = if (c == nearC) keys.sortedBy { k ->
                val dc = colOf(k) + 0.5 - pc; val dr = rowOf(k) + 0.5 - pr
                dc * dc + dr * dr
            } else keys.sortedWith(compareBy({ rowOf(it) }, { colOf(it) }))
            for (k in sorted) out.add(c to k)
        }
        return out
    }

    /** the smallest zone whose rect holds a tile's middle, for the progress line (null: none) */
    fun zoneOf(c: Int, k: Int, rects: Map<Int, Rect>): Int? {
        val wy = (32.0 - (colOf(k) + 0.5)) * ADT_YD
        val wx = (32.0 - (rowOf(k) + 0.5)) * ADT_YD
        return rects.entries.filter { (_, r) -> r.c == c && wx <= r.t && wx >= r.b && wy <= r.l && wy >= r.r }
            .minByOrNull { (_, r) -> (r.t - r.b) * (r.l - r.r) }?.key
    }

    // ------------------------------------------------------------------------------------------------ the POI icon strip

    /** one cell of the strip (the recipe's): "oi" = ObjectIcons' cell [row, col]; "ico" = Interface\Icons\<name>; "cur" =
     *  Interface\Cursor\<name>, its pointer tip cut away; "blp" = a texture by path */
    class Cell(val src: String, val name: String, val row: Int, val col: Int)

    /** true where a cursor icon's pointer tip is cut away (the top-left triangle, the PC's x + y <= 10 * w / 32) */
    fun cursorTip(x: Int, y: Int, w: Int): Boolean = x + y <= 10 * w / 32
}
