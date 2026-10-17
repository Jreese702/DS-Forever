package com.jrees.wowcompanion

import kotlin.math.floor
import kotlin.math.hypot

/**
 * THE GAME'S OWN PLACE NAMES AT A SPOT (b-dock-names; the user, via tracking on test54: "exact names ... Dalaran, Skywatcher Plateau,
 * Steamwheedle Port, Valanaar"). Every 33 yd chunk of the ground (a root ADT's MCNK) carries its area id (the header's 0x34); the
 * client's AreaTable names it (its AreaName_lang) and says whether it is a subzone (a parent area) or a zone. Read on the device by
 * the transports step; never shipped. Pure: the bytes and the rows in, a name out.
 */
object PlaceNames {
    const val DB_AREA_TABLE = 1353545
    const val CHUNK_YD = MapHarvest.ADT_YD / 16
    /** how far round a spot (in chunks, about 266 yd) a subzone is looked for when the spot itself is a zone's open ground or sea */
    const val NEAR_CHUNKS = 8

    /** an area: its name, its parent (0 = a zone itself) */
    class Area(val name: String, val parent: Int)

    /** a root ADT's chunks' area ids, by [chunk] (ix, iy: 0..15 east, south) */
    fun chunkAreas(adt: ByteArray): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        var o = 0
        while (o + 8 <= adt.size) {
            val n = Db2.le32(adt, o + 4)
            if (n < 0 || o + 8 + n > adt.size) break
            if (adt[o + 3] == 'M'.code.toByte() && adt[o + 2] == 'C'.code.toByte() && adt[o + 1] == 'N'.code.toByte() && adt[o] == 'K'.code.toByte()
                && n >= 0x38) {
                val d = o + 8
                val ix = Db2.le32(adt, d + 4); val iy = Db2.le32(adt, d + 8)
                if (ix in 0..15 && iy in 0..15) out[chunk(ix, iy)] = Db2.le32(adt, d + 0x34)
            }
            o += 8 + n
        }
        return out
    }
    fun chunk(ix: Int, iy: Int) = iy * 16 + ix

    /** where world ([wx], [wy]) lies: its tile (column, row of the WDT's 64 x 64) and its chunk in that tile */
    fun locate(wx: Double, wy: Double): IntArray {
        val gx = (32 * MapHarvest.ADT_YD - wy) / MapHarvest.ADT_YD; val gy = (32 * MapHarvest.ADT_YD - wx) / MapHarvest.ADT_YD
        val tc = floor(gx).toInt(); val tr = floor(gy).toInt()
        return intArrayOf(tc, tr, chunk(((gx - tc) * 16).toInt().coerceIn(0, 15), ((gy - tr) * 16).toInt().coerceIn(0, 15)))
    }

    /**
     * the place at world ([wx], [wy]): the subzone there, else the nearest subzone within [NEAR_CHUNKS], else the zone there; null when
     * nothing reads. [areaAt] (wx, wy) the area id of the chunk there (0 / null = none).
     */
    fun nameAt(wx: Double, wy: Double, areas: Map<Int, Area>, areaAt: (Double, Double) -> Int?): String? {
        val here = areaAt(wx, wy)?.let { areas[it] }
        if (here != null && here.parent != 0 && here.name.isNotEmpty()) return here.name
        var best: Area? = null; var bestD = Double.MAX_VALUE
        for (dy in -NEAR_CHUNKS..NEAR_CHUNKS) for (dx in -NEAR_CHUNKS..NEAR_CHUNKS) {
            val d = hypot(dx.toDouble(), dy.toDouble())
            if (d >= bestD || d > NEAR_CHUNKS) continue
            val a = areaAt(wx + dy * CHUNK_YD, wy + dx * CHUNK_YD)?.let { areas[it] } ?: continue
            if (a.parent != 0 && a.name.isNotEmpty()) { best = a; bestD = d }
        }
        return best?.name ?: here?.name?.takeIf { it.isNotEmpty() }
    }
}
