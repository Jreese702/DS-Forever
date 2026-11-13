package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * THE GUARD'S FLAG (the user, 2026-11-13: "when asking a guard for directions, it does not put the arrow and flag to what I am looking
 * for on our map"). Map.lua gp1's map.gpoi = {m, x, y, n, id, tex, tc = [x1, x2, y1, y2], hx, hy}: the gossip POI the game holds now
 * (a guard's directions), read on the player's map or a parent ([m]; [hx] / [hy] = carried onto the player's map). The game's world
 * map draws it as one 16 x 16 cell of Interface/Minimap/POIIcons (UI Art's kit: map_gossip_poi, MapHarvestSteps.POI_ICONS), cut by
 * C_Minimap.GetPOITextureCoords; it is drawn while the document carries it and goes when the game clears it (no timer of ours).
 * Pure: MapView (Home's small and full map, the arrow) and WorldMapRoomView (the Map room) draw from it.
 */
object GuardFlag {
    /** the kit's picture: the game's POIIcons sheet as harvested */
    const val ART = "map_gossip_poi"
    /** the game's pin size (BaseMapPoiPinMixin:GetTextureSizeInfo: 16 x 16 for an icon with no atlas) */
    const val GAME_PX = 16f
    /** the arrow's "arrived" radius, yd: inside it the arrow goes for this flag (the flag itself stays while the game holds it) */
    const val ARRIVE_YD = 10.0
    /** C_Minimap.GetPOITextureCoords' grid when the game gave no cut: 14 columns of 16 px on a 256 px sheet (the old FrameXML
     *  GetPOITextureCoords: NUM_WORLDMAP_POI_COLUMNS, WORLDMAP_POI_TEXTURE_WIDTH) */
    const val COLUMNS = 14
    const val SHEET_PX = 256f

    /** [here] = the player's map the document was read on: what [hx] / [hy] are on */
    class Flag(
        val m: Int, val x: Double, val y: Double, val here: Int, val hx: Double, val hy: Double,
        val name: String?, val id: Int, val tex: Int, val tc: FloatArray?,
    ) {
        /** the same flag again: the same id, map and spot (a new direction is a new key) */
        val key: String = "$id:$m:${(x * 10000).roundToLong()}:${(y * 10000).roundToLong()}"

        /** where it sits on map [mapId] (0..1, may be outside on a parent's flag carried down), or null when not known there */
        fun on(mapId: Int): DoubleArray? = when {
            mapId == m -> doubleArrayOf(x, y)
            mapId == here && !hx.isNaN() && !hy.isNaN() -> doubleArrayOf(hx, hy)
            else -> null
        }

        /** the cell of the POIIcons sheet: x1, x2, y1, y2 (0..1), the game's own cut, else its grid; null = no picture to cut */
        fun cut(): FloatArray? = tc ?: if (tex >= 0) gridCut(tex) else null
    }

    fun gridCut(index: Int): FloatArray {
        val w = GAME_PX / SHEET_PX
        val x1 = (index % COLUMNS) * w; val y1 = floor(index / COLUMNS.toDouble()).toFloat() * w
        return floatArrayOf(x1, x1 + w, y1, y1 + w)
    }

    /** map.gpoi of map document [doc], or null (none held, or not a whole one) */
    fun parse(doc: JSONObject?): Flag? {
        val g = doc?.optJSONObject("gpoi") ?: return null
        val m = g.optInt("m", 0)
        val x = g.optDouble("x", Double.NaN); val y = g.optDouble("y", Double.NaN)
        if (m == 0 || x.isNaN() || y.isNaN()) return null
        val tc = g.optJSONArray("tc")?.takeIf { it.length() == 4 }?.let { a ->
            FloatArray(4) { a.optDouble(it, Double.NaN).toFloat() }.takeIf { c -> c.none { it.isNaN() } && c[1] > c[0] && c[3] > c[2] }
        }
        return Flag(m, x, y, doc.optInt("mapID", 0), g.optDouble("hx", Double.NaN), g.optDouble("hy", Double.NaN),
            g.optString("n", "").ifEmpty { null }, g.optInt("id", 0), g.optInt("tex", -1), tc)
    }

    /**
     * THE ARROW'S ARRIVAL: once the player has come within [ARRIVE_YD] of a flag, the arrow stays away for that flag (as the quest
     * arrow does inside its area) until the game holds a new or different one ([Flag.key]). The flag itself is not touched.
     */
    class Arrival {
        var doneKey: String? = null
            private set

        /** true while the arrow should point at [f]: [px] / [py] the player and [fx] / [fy] the flag on one map [w] x [h] yd */
        fun wants(f: Flag?, px: Double, py: Double, fx: Double, fy: Double, w: Double, h: Double): Boolean {
            if (f == null) { doneKey = null; return false }
            if (doneKey == f.key) return false
            doneKey = null
            if (!px.isNaN() && !py.isNaN() && w > 0 && h > 0 && hypot((fx - px) * w, (fy - py) * h) <= ARRIVE_YD) {
                doneKey = f.key; return false
            }
            return true
        }
    }
}
