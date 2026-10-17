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

    /** one building's tiles for the device file: {"wmo": id, "groups": {"<g>": [[bx, by, fid], ...]}} */
    fun toJson(wmo: Int, tiles: List<Tile>): JSONObject {
        val g = JSONObject()
        for ((group, ts) in tiles.groupBy { it.group }.entries.sortedBy { it.key })
            g.put(group.toString(), JSONArray().apply { for (t in ts.sortedWith(compareBy({ it.by }, { it.bx }))) put(JSONArray().put(t.bx).put(t.by).put(t.fid)) })
        return JSONObject().put("wmo", wmo).put("groups", g)
    }
}
