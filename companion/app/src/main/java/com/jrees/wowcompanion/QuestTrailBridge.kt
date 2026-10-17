package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * QUESTTRAIL'S ARROW AS THE ROUTE (b-route-questtrail; the user, 2026-10-17: "(a) + (b)"). With Settings' "Quest tracking: QuestTrail"
 * the map's line and rim arrow follow QuestTrail's own guidance arrow (QuestTrail by Krom, MIT, player-chosen; it plans its own next
 * step, the best XP a second). QuestTrail 3.1.0 has no public API, so Map.lua reads its compass frame on every position tick: the
 * bearing (0 = north, counter-clockwise, like the game's facing), its words and its distance in metres, sent as the map's `qtrail`
 * {src, b, yd, t, d}; should QuestTrail ever offer QuestTrail_GetTarget(), its exact point comes as `qtrail.api` {m, x, y, q, k, t}
 * and is taken first. No game pin in this mode (QuestTrail sets the super-track itself). Pure: the document in, a point out.
 */
object QuestTrailBridge {
    /** where QuestTrail points, on this map (0..1, may lie off it); [directionOnly] = no distance known (the point is [FAR_YD] on) */
    class Target(val x: Double, val y: Double, val label: String?, val exact: Boolean, val directionOnly: Boolean)

    const val FAR_YD = 3000.0       // a direction without a distance: far enough that the line runs to the field's edge
    const val STALE_MS = 3_000L     // the position ticks carry it ten times a second: older = QuestTrail stopped showing it

    /**
     * QuestTrail's target from the map's `qtrail` [o], the player at ([px], [py]) on map [mapId] ([ax] / [ay] yards across / down).
     * The API's point when it is on this map; else the arrow's bearing and distance; null = nothing to follow.
     */
    fun target(o: JSONObject?, mapId: Int, px: Double, py: Double, ax: Double, ay: Double): Target? {
        if (o == null || px.isNaN() || py.isNaN() || ax <= 0.0 || ay <= 0.0) return null
        val words = o.optString("t", "").takeIf { it.isNotEmpty() }
        o.optJSONObject("api")?.let { a ->
            val x = a.optDouble("x", Double.NaN); val y = a.optDouble("y", Double.NaN)
            if (a.optInt("m", 0) == mapId && !x.isNaN() && !y.isNaN())
                return Target(x, y, a.optString("t", "").takeIf { it.isNotEmpty() } ?: words, exact = true, directionOnly = false)
        }
        val b = o.optDouble("b", Double.NaN)
        if (b.isNaN()) return null
        val yd = o.optDouble("yd", Double.NaN)
        val dirOnly = yd.isNaN() || yd <= 0.0
        val d = if (dirOnly) FAR_YD else yd
        // the bearing as the game's facing: 0 = north, counter-clockwise (QuestTrail: atan2(-east, north))
        val east = -sin(b) * d; val north = cos(b) * d
        val extra = if (dirOnly) o.optString("d", "").takeIf { it.isNotEmpty() } else null
        val label = listOfNotNull(words, extra).joinToString(" · ").takeIf { it.isNotEmpty() }
        return Target(px + east / ax, py - north / ay, label, exact = false, directionOnly = dirOnly)
    }

    /** the API's planned path on this map (the full map document's `qtrailPath`: [uiMapID, x, y] each), as x, y pairs; null = none */
    fun path(m: JSONObject?, mapId: Int): DoubleArray? {
        val a = m?.optJSONArray("qtrailPath") ?: return null
        val out = ArrayList<Double>()
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            if (p.length() < 3 || p.optInt(0) != mapId) continue
            val x = p.optDouble(1, Double.NaN); val y = p.optDouble(2, Double.NaN)
            if (!x.isNaN() && !y.isNaN()) { out.add(x); out.add(y) }
        }
        return if (out.size >= 4) out.toDoubleArray() else null
    }
}
