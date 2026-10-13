package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * THE MAP ROOM'S WORLD MAP: maps, their family and what this character has explored (pure: tools/map_test runs it off-device).
 *
 * The user, 2026-10-07: "the map room should function like wows in game maps", on "the normal zone maps", unexplored areas "Like
 * the game: only what I've explored"; Home's map module stays as it is. The painted art set (60 maps, built from the client's own
 * map art: a base parchment + one picture per WorldMapOverlay, each overlay known by its first tile fdid) is described by the
 * per-map index.json files; Map.lua's "mapexpl" documents name the explored overlays (their first tile fdids), each map's rect on
 * its parent and its level range. This model answers the room's questions: which overlays to paint, a map's parent / children,
 * the child under a point of a continent, the next child in a D-pad direction, and the way BACK through what the player browsed.
 */
class WorldMapModel {
    class Overlay(val id: Int, val fid: Int, val x: Int, val y: Int, val w: Int, val h: Int)
    class MapInfo(val id: Int, val name: String, val type: Int, val parent: Int, val w: Int, val h: Int, val overlays: List<Overlay>,
                  val hl: Int = 0, val world: World? = null)

    /** a map's WORLD rectangle (the client's own; art set index.json "rect" = [continent, top, bottom, left, right]; PoiStore.Rect's
     *  order: world X north top..bottom, world Y west left..right). nx is fed by world Y, ny by world X. */
    class World(val continent: Int, val top: Double, val bottom: Double, val left: Double, val right: Double) {
        val valid get() = top - bottom > 1.0 && left - right > 1.0
        fun nx(wy: Double) = (left - wy) / (left - right)
        fun ny(wx: Double) = (top - wx) / (top - bottom)
        fun wy(nx: Double) = left - nx * (left - right)
        fun wx(ny: Double) = top - ny * (top - bottom)
    }
    /** the game's box for a zone's highlight picture on a parent map (all fractions of that map): texCoord 0..[tpx] / 0..[tpy] */
    class Highlight(val fid: Int, val tpx: Float, val tpy: Float, val w: Float, val h: Float, val x: Float, val y: Float)

    val maps = HashMap<Int, MapInfo>()
    private val explored = HashMap<Int, Set<Int>>()          // map -> explored overlays' first fids
    private val rects = HashMap<Int, FloatArray>()           // map -> minX, maxX, minY, maxY on its parent
    private val parents = HashMap<Int, Int>()                // from mapexpl (the game's answer) over the index's
    private val levels = HashMap<Int, IntArray>()
    private val highlights = HashMap<Int, HashMap<Int, Highlight>>()   // parent map -> highlight fid -> the game's box (maphl)

    /** one map's index.json */
    fun addIndex(o: JSONObject) {
        val id = o.optInt("uiMapID", 0)
        if (id == 0) return
        val ovs = ArrayList<Overlay>()
        o.optJSONArray("overlays")?.let { a ->
            for (i in 0 until a.length()) {
                val v = a.optJSONObject(i) ?: continue
                ovs.add(Overlay(v.optInt("id"), v.optInt("fid"), v.optInt("x"), v.optInt("y"), v.optInt("w"), v.optInt("h")))
            }
        }
        maps[id] = MapInfo(id, o.optString("name", ""), o.optInt("mapType", 0), o.optInt("parent", 0),
            o.optInt("w", 1002), o.optInt("h", 668), ovs, o.optInt("hl", 0),
            o.optJSONArray("rect")?.takeIf { it.length() == 5 }?.let { r ->
                World(r.optInt(0), r.optDouble(1), r.optDouble(2), r.optDouble(3), r.optDouble(4)).takeIf { it.valid }
            })
    }

    /** a {"type":"mapexpl"} document: all = the whole set (what was not named is unexplored), else an update of the maps named */
    fun onMapExpl(d: JSONObject) {
        val all = d.optBoolean("all", false)
        val m = d.optJSONObject("maps") ?: return
        if (all) explored.clear()
        val keys = m.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = k.toIntOrNull() ?: continue
            val row = m.optJSONObject(k) ?: continue
            row.optJSONArray("e")?.let { a -> explored[id] = (0 until a.length()).map { a.optInt(it) }.toSet() }
            if (row.has("p")) parents[id] = row.optInt("p")
            row.optJSONArray("r")?.let { a -> if (a.length() == 4) rects[id] = FloatArray(4) { a.optDouble(it).toFloat() } }
            row.optJSONArray("lv")?.let { a -> if (a.length() == 2) levels[id] = intArrayOf(a.optInt(0), a.optInt(1)) }
        }
    }

    fun knowsExploration(id: Int) = explored.containsKey(id)

    /**
     * a {"type":"maphl"} document (Map.lua's sweep of C_Map.GetMapHighlightInfoAtPosition): per parent map, each highlight picture's
     * box as the game places it, {fid, atlas, texPctX, texPctY, texW, texH, x, y}. Atlas answers are left out (the art set holds
     * pictures by fileDataID). Each document replaces what it names.
     */
    fun onMapHl(d: JSONObject) {
        val m = d.optJSONObject("maps") ?: return
        val keys = m.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = k.toIntOrNull() ?: continue
            val out = HashMap<Int, Highlight>()
            m.optJSONArray(k)?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONArray(i) ?: continue
                    if (p.length() < 8) continue
                    val fid = p.optInt(0, 0)
                    val v = FloatArray(6) { p.optDouble(it + 2, Double.NaN).toFloat() }
                    if (fid <= 0 || v.any { it.isNaN() } || v[2] <= 0f || v[3] <= 0f) continue
                    out[fid] = Highlight(fid, v[0], v[1], v[2], v[3], v[4], v[5])
                }
            }
            highlights[id] = out
        }
    }

    /** [child]'s highlight on [parent], as the game draws it; null = not known (the room's soft glow stands in) */
    fun highlightOf(parent: Int, child: Int): Highlight? {
        val fid = maps[child]?.hl ?: return null
        if (fid <= 0) return null
        return highlights[parent]?.get(fid)
    }

    /** the overlays to paint on map [id]: those the character has explored (none before the first mapexpl, like a blank map) */
    fun paintedOverlays(id: Int): List<Overlay> {
        val m = maps[id] ?: return emptyList()
        val e = explored[id] ?: return emptyList()
        return m.overlays.filter { it.fid in e }
    }

    /** a capital's parent is the ZONE it sits in (the user after b104), else the game's parent (mapexpl, else the index) */
    fun parentOf(id: Int): Int? = (CITY_ZONE[id]?.takeIf { maps.containsKey(it) } ?: parents[id] ?: maps[id]?.parent)
        ?.takeIf { it > 0 && maps.containsKey(it) }

    /** the maps with art directly under [id] that have a rect on it (a continent's capitals live in their zones now) */
    fun children(id: Int): List<Int> = maps.keys.filter { parentOf(it) == id && rects.containsKey(it) && CITY_ZONE[it] == null }.sorted()

    /** the capitals in zone [zone], each with its area in the zone's 0..1 (minX, maxX, minY, maxY), from the two world rects */
    fun citiesIn(zone: Int): List<Pair<Int, FloatArray>> {
        val z = maps[zone]?.world ?: return emptyList()
        return CITY_ZONE.filter { it.value == zone && maps.containsKey(it.key) }.keys.sorted().mapNotNull { city ->
            val c = maps[city]?.world ?: return@mapNotNull null
            if (c.continent != z.continent) return@mapNotNull null
            val x0 = z.nx(c.left).toFloat(); val x1 = z.nx(c.right).toFloat()
            val y0 = z.ny(c.top).toFloat(); val y1 = z.ny(c.bottom).toFloat()
            city to floatArrayOf(min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1))
        }
    }

    fun world(id: Int): World? = maps[id]?.world

    /** World ... [id] */
    fun ancestors(id: Int): List<Int> {
        val out = ArrayList<Int>()
        var cur: Int? = id
        var guard = 0
        while (cur != null && guard++ < 8) { out.add(0, cur); cur = parentOf(cur) }
        return out
    }

    fun rectOf(id: Int): FloatArray? = rects[id]
    fun levelsOf(id: Int): IntArray? = levels[id]
    fun name(id: Int): String = maps[id]?.name ?: ""

    /** the child of [parent] under ([x], [y]) in the parent's 0..1: the SMALLEST rect wins (a city inside its zone) */
    fun childAt(parent: Int, x: Float, y: Float): Int? =
        children(parent).filter { c -> rects[c]!!.let { x >= it[0] && x <= it[1] && y >= it[2] && y <= it[3] } }
            .minByOrNull { c -> rects[c]!!.let { (it[1] - it[0]) * (it[3] - it[2]) } }

    fun centre(id: Int): FloatArray? = rects[id]?.let { floatArrayOf((it[0] + it[1]) / 2f, (it[2] + it[3]) / 2f) }

    /**
     * What a continent / the world can select, and where: every child map (not a capital) at its rect's centre, or - when the game
     * gave no rect for it on this map (the world map is a composite) - at the centre of its highlight box (maphl). The user after
     * b106: "the Joystick is not working for the zone selection in the continent or Azeroth maps".
     */
    fun selectables(parent: Int): List<Triple<Int, Float, Float>> =
        maps.keys.filter { parentOf(it) == parent && CITY_ZONE[it] == null }.sorted().mapNotNull { id ->
            centre(id)?.let { Triple(id, it[0], it[1]) }
                ?: highlightOf(parent, id)?.let { h -> Triple(id, h.x + h.w / 2f, h.y + h.h / 2f) }
        }

    /** the child under a point: by rect (the smallest wins), else by highlight box */
    fun childAtAny(parent: Int, x: Float, y: Float): Int? = childAt(parent, x, y)
        ?: maps.keys.filter { parentOf(it) == parent && CITY_ZONE[it] == null && rects[it] == null }.firstOrNull { id ->
            highlightOf(parent, id)?.let { h -> x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h } ?: false
        }

    /**
     * The D-pad on a continent / the world: the child nearest [from] in direction ([dx], [dy]) (screen axes, y down), within 60°
     * of it; distance weighted by the angle so a zone straight ahead beats a nearer one off to the side. null = none that way.
     */
    fun step(parent: Int, from: Int?, dx: Int, dy: Int): Int? {
        val kids = children(parent)
        if (kids.isEmpty()) return null
        val c0 = from?.let { centre(it) } ?: return kids.first()
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        for (k in kids) {
            if (k == from) continue
            val c = centre(k) ?: continue
            val vx = (c[0] - c0[0]).toDouble() * 1.5; val vy = (c[1] - c0[1]).toDouble()     // 3:2 maps: across counts 1.5x
            val d = Math.hypot(vx, vy)
            if (d < 1e-6) continue
            val cos = (vx * dx + vy * dy) / d
            if (cos < 0.5) continue                                                      // more than 60° off
            val score = d * (2.0 - cos)
            if (score < bestScore) { bestScore = score; best = k }
        }
        return best
    }

    /** the player's spot ([x], [y] on [mapId]) carried up to [target] (an ancestor) through the rects, or null */
    fun carryUp(mapId: Int, x: Float, y: Float, target: Int): FloatArray? {
        val a = maps[mapId]?.world; val b = maps[target]?.world
        if (a != null && b != null && a.continent == b.continent && target in ancestors(mapId)) {
            val wy = a.wy(x.toDouble()); val wx = a.wx(y.toDouble())
            return floatArrayOf(b.nx(wy).toFloat(), b.ny(wx).toFloat())
        }
        var id = mapId; var px = x; var py = y
        var guard = 0
        while (id != target && guard++ < 8) {
            val r = rects[id] ?: return null
            val p = parentOf(id) ?: return null
            px = r[0] + px * (r[1] - r[0]); py = r[2] + py * (r[3] - r[2])
            id = p
        }
        return if (id == target) floatArrayOf(px, py) else null
    }

    /** a spot on [from] carried onto [to] through the two world rects (same continent; the maps need not be related), or null */
    fun carryAcross(from: Int, x: Float, y: Float, to: Int): FloatArray? {
        val a = maps[from]?.world ?: return null
        val b = maps[to]?.world ?: return null
        if (a.continent != b.continent) return null
        return floatArrayOf(b.nx(a.wy(x.toDouble())).toFloat(), b.ny(a.wx(y.toDouble())).toFloat())
    }

    /** a zone's own shape carried onto another map: the game's highlight picture [fid] (its crop 0..[tpx] x 0..[tpy]) over [box] =
     *  minX, maxX, minY, maxY in that map's 0..1 */
    class Shape(val id: Int, val fid: Int, val tpx: Float, val tpy: Float, val box: FloatArray)

    /** [zone]'s highlight picture on its parent (maphl: the game's own zone shape), carried into [into]'s 0..1 through the world rects */
    fun shapeIn(zone: Int, into: Int): Shape? {
        val parent = parentOf(zone) ?: return null
        val h = highlightOf(parent, zone) ?: return null
        val p = maps[parent]?.world ?: return null
        val z = maps[into]?.world ?: return null
        if (p.continent != z.continent) return null
        val x0 = z.nx(p.wy(h.x.toDouble())).toFloat(); val x1 = z.nx(p.wy((h.x + h.w).toDouble())).toFloat()
        val y0 = z.ny(p.wx(h.y.toDouble())).toFloat(); val y1 = z.ny(p.wx((h.y + h.h).toDouble())).toFloat()
        return Shape(zone, h.fid, h.tpx, h.tpy, floatArrayOf(min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1)))
    }

    /**
     * A ZONE'S NEIGHBOURS as its own art shows them (the user after b109: "when looking at a zone, I should be able to select nearby
     * zones to open that map"): its sibling zones (the same continent, not capitals) whose game shape reaches into its art.
     */
    fun neighbours(zone: Int): List<Shape> {
        if ((maps[zone]?.type ?: 0) < 3 || CITY_ZONE.containsKey(zone)) return emptyList()
        val parent = parentOf(zone) ?: return emptyList()
        return maps.keys.filter { it != zone && parentOf(it) == parent && !CITY_ZONE.containsKey(it) }.sorted().mapNotNull { id ->
            shapeIn(id, zone)?.takeIf { b -> b.box[1] > 0f && b.box[0] < 1f && b.box[3] > 0f && b.box[2] < 1f }
        }
    }

    /** ([x], [y]) inside [s]'s shape: in its box, and [lit] there (the picture sampled at its own 0..1) */
    fun inShape(s: Shape, x: Float, y: Float, lit: (Int, Float, Float) -> Boolean): Boolean {
        val b = s.box
        if (b[1] <= b[0] || b[3] <= b[2] || x < b[0] || x > b[1] || y < b[2] || y > b[3]) return false
        return lit(s.fid, (x - b[0]) / (b[1] - b[0]) * s.tpx, (y - b[2]) / (b[3] - b[2]) * s.tpy)
    }

    /** the neighbour under ([x], [y]) of a zone's art: never inside the zone's [own] shape, else the first of [near] whose shape is lit */
    fun neighbourAt(x: Float, y: Float, near: List<Shape>, own: Shape?, lit: (Int, Float, Float) -> Boolean): Int? {
        if (own != null && inShape(own, x, y, lit)) return null
        return near.firstOrNull { inShape(it, x, y, lit) }?.id
    }

    /** the way back through what the player browsed: B steps back, and with nothing left the room leaves (Home) */
    class History {
        private val stack = ArrayList<Int>()
        fun push(from: Int) { if (stack.lastOrNull() != from) stack.add(from) }
        fun back(): Int? = if (stack.isEmpty()) null else stack.removeAt(stack.size - 1)
        fun clear() = stack.clear()
        val canGoBack get() = stack.isNotEmpty()
    }

    companion object {
        /**
         * The spot of [spots] (each [continent, world x, world y]) on [continent] nearest the player's world spot ([pwx], [pwy]); with
         * no player spot the first on that continent; null = none on it. (Home's minimap arrow for a quest in another zone.)
         */
        fun nearestWorld(spots: List<DoubleArray>, continent: Int, pwx: Double, pwy: Double): DoubleArray? {
            val on = spots.filter { it.size >= 3 && it[0].toInt() == continent }
            if (pwx.isNaN() || pwy.isNaN()) return on.firstOrNull()
            return on.minByOrNull { Math.hypot(it[1] - pwx, it[2] - pwy) }
        }

        /** THE CAPITALS AND THE ZONES THEY SIT IN (the game's geography; their uiMaps are children of the continent, so the
         *  world rects' overlap cannot tell: Ironforge's box falls inside Wetlands', Undercity's inside Silverpine's) */
        val CITY_ZONE = mapOf(1453 to 1429, 1455 to 1426, 1454 to 1411, 1456 to 1412, 1457 to 1438, 1458 to 1420)

        /** the art set's manifest.json -> its map ids */
        fun manifestIds(o: JSONObject): List<Int> {
            val out = ArrayList<Int>()
            val k = o.keys()
            while (k.hasNext()) k.next().toIntOrNull()?.let { out.add(it) }
            return out.sorted()
        }

        @Suppress("unused") private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }
    }
}
