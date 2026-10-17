package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE GAME'S OWN MINIMAP ART FOR BUILDINGS AND CAVES (b-interiors-game-art; the user, 2026-10-17, on High Fidelity World Map: "Do we
 * really need to be putting all of that work into recreating our indoor art? It seems like it's already there for us?" - the pick:
 * COMBINE, the game's tiles as the picture wherever they exist, Track B's geometry underneath). The client's WMOMinimapTexture table
 * (fileDataID [DB_WMO_MINIMAP_TEXTURE]; read on the device, never shipped) lists per building (its WMO id, the root's MOHD header) and
 * per group the minimap tiles the in-game minimap shows indoors: block x / y and the tile's fileDataID. Measured on the PC client:
 * 156027 tiles, 3091 buildings, 18371 groups; tiles mostly 256 x 256; Stormwind's root has art for 194 of its 281 groups, Ironforge's
 * for 103 of 104, the burnt Duskwood farmhouse none. Pure: the rows and root bytes in, the per-building tiles out.
 */
object WmoMinimap {
    const val DB_WMO_MINIMAP_TEXTURE = 1323241

    /** one tile: group [group], block ([bx], [by]), the texture [fid] */
    class Tile(val group: Int, val bx: Int, val by: Int, val fid: Int)

    /**
     * WHERE A TILE LIES (measured on the PC client, 2026-10-17, by laying each group's up-facing floor triangles over its tiles: 301
     * Stormwind / Ironforge groups, overlap 0.84-0.99, every multi-block group agreeing): the tiles are drawn at [PX_PER_YD] pixels a
     * yard from the group's bounding-box MINIMUM corner, in the building's own space; block ([bx], [by]) starts [BLOCK_YD] yards on in
     * x / y; a tile's image runs +x to the right and +y UP (its top row is the high-y side); a texture is the next power of two of its
     * content (at least 32), the slack on the high side. So a tile of [w] x [h] pixels covers x0..x0 + w / 2, y0..y0 + h / 2.
     */
    const val PX_PER_YD = 2.0
    const val BLOCK_YD = 128.0

    /** a tile's rectangle in the building's own space: [x0, y0, x1, y1]; [bb] the group's box (x1, y1, z1, x2, y2, z2) */
    fun tileRect(bb: FloatArray, bx: Int, by: Int, w: Int, h: Int): DoubleArray {
        val x0 = bb[0] + bx * BLOCK_YD; val y0 = bb[1] + by * BLOCK_YD
        return doubleArrayOf(x0, y0, x0 + w / PX_PER_YD, y0 + h / PX_PER_YD)
    }

    /** a WMO root's group boxes (MOGI: per group its flags, the box's six floats, a name offset), or empty */
    fun groupBoxes(root: ByteArray): List<FloatArray> {
        var o = 0
        while (o + 8 <= root.size) {
            val magic = String(root, o, 4, Charsets.ISO_8859_1)
            val size = Db2.le32(root, o + 4)
            if (size < 0 || o + 8 + size > root.size) return emptyList()
            if (magic == "IGOM") return (0 until size / 32).map { g ->
                FloatArray(6) { java.lang.Float.intBitsToFloat(Db2.le32(root, o + 8 + g * 32 + 4 + 4 * it)) }
            }
            o += 8 + size
        }
        return emptyList()
    }

    /**
     * WHICH FLOOR A GROUP'S TILES BELONG TO: the floor whose band ([bands]: each floor's low / high height, Track B's storeys, in the
     * building's own space) holds the group's bottom ([bottoms]); a bottom outside every band goes to the nearest. -1 = no floors.
     * (A group spanning two storeys shows on the one its floor is on.)
     */
    fun assign(bottoms: FloatArray, bands: List<FloatArray>): IntArray = IntArray(bottoms.size) { g ->
        val z = bottoms[g]
        if (bands.isEmpty()) -1
        else bands.indices.firstOrNull { z >= bands[it][0] - 0.5f && z < bands[it][1] }
            ?: bands.indices.minByOrNull { i -> minOf(Math.abs(z - bands[i][0]), Math.abs(z - bands[i][1])) }!!
    }

    /**
     * the table's rows by building (the row's parent = the WMO id). Fields (this build, measured): 0 the group, 1 block x, 2 block
     * y as 256 + y (the tiles' own file names, "_<group>_<x>_<y>", say so), 3 the texture.
     */
    fun byWmo(rows: List<Db2.Row>): Map<Int, List<Tile>> {
        val out = HashMap<Int, MutableList<Tile>>()
        for (r in rows) {
            val wmo = r.parent ?: continue
            val y = r.int(2).let { if (it >= 256) it - 256 else it }
            out.getOrPut(wmo) { ArrayList() }.add(Tile(r.int(0), r.int(1), y, r.int(3)))
        }
        return out
    }

    /** a WMO root's id (its MOHD header, the ninth uint32: after the counts and the ambient colour), or null */
    fun wmoId(root: ByteArray): Int? = header(root)?.get(8)
    /** a WMO root's group count (MOHD's second uint32), or null */
    fun groupCount(root: ByteArray): Int? = header(root)?.get(1)

    private fun header(root: ByteArray): IntArray? {
        var o = 0
        while (o + 8 <= root.size) {
            val magic = String(root, o, 4, Charsets.ISO_8859_1)
            val size = Db2.le32(root, o + 4)
            if (size < 0 || o + 8 + size > root.size) return null
            if (magic == "DHOM") return if (size >= 36) IntArray(9) { Db2.le32(root, o + 8 + 4 * it) } else null
            o += 8 + size
        }
        return null
    }

    /** the root fileDataIDs of the published interiors snapshot (its models' keys: "<rootFid>_d<set>_...") */
    fun snapshotRoots(interiors: java.io.File): Set<Int> {
        val cur = runCatching { JSONObject(java.io.File(interiors, "current.json").readText()) }.getOrNull() ?: return emptySet()
        val dir = java.io.File(interiors, "snapshots/" + cur.optString("generation"))
        val out = HashSet<Int>()
        dir.listFiles()?.filter { it.name.startsWith("models_") }?.forEach { f ->
            runCatching { JSONObject(f.readText()) }.getOrNull()?.keys()?.forEach { k -> k.substringBefore('_').toIntOrNull()?.let(out::add) }
        }
        return out
    }

    /**
     * one building's tiles for the device file: {"wmo": id, "groups": {"<g>": {"bb": [x1, y1, z1, x2, y2, z2], "t": [[bx, by, fid, w,
     * h], ...]}}}; [boxes] the root's group boxes, [size] a tile's pixels (null = not readable: the tile is left out)
     */
    fun toJson(wmo: Int, tiles: List<Tile>, boxes: List<FloatArray> = emptyList(), size: (Int) -> IntArray? = { intArrayOf(0, 0) }): JSONObject {
        val g = JSONObject()
        for ((group, ts) in tiles.groupBy { it.group }.entries.sortedBy { it.key }) {
            val a = JSONArray()
            for (t in ts.sortedWith(compareBy({ it.by }, { it.bx }))) {
                val wh = size(t.fid) ?: continue
                a.put(JSONArray().put(t.bx).put(t.by).put(t.fid).put(wh[0]).put(wh[1]))
            }
            if (a.length() == 0) continue
            val o = JSONObject().put("t", a)
            boxes.getOrNull(group)?.let { b -> o.put("bb", JSONArray().apply { for (v in b) put(v.toDouble()) }) }
            g.put(group.toString(), o)
        }
        return JSONObject().put("wmo", wmo).put("groups", g)
    }

    /** the device file's tile picture for texture [fid] (under the art root) */
    fun tileFile(fid: Int) = "wmomm/$fid.webp"

    /** one building's groups read back: per group its box and tiles ([bx, by, fid, w, h]); groups without a box are left out */
    class Group(val index: Int, val bb: FloatArray, val tiles: List<IntArray>)
    fun groupsOf(o: JSONObject?): List<Group> {
        val g = o?.optJSONObject("groups") ?: return emptyList()
        val out = ArrayList<Group>()
        for (k in g.keys()) {
            val e = g.optJSONObject(k) ?: continue
            val bb = e.optJSONArray("bb")?.takeIf { it.length() == 6 } ?: continue
            val t = e.optJSONArray("t") ?: continue
            val tiles = (0 until t.length()).mapNotNull { i -> t.optJSONArray(i)?.takeIf { it.length() >= 5 }?.let { a -> IntArray(5) { a.optInt(it) } } }
            out.add(Group(k.toIntOrNull() ?: continue, FloatArray(6) { bb.optDouble(it).toFloat() }, tiles))
        }
        return out.sortedBy { it.index }
    }
}
