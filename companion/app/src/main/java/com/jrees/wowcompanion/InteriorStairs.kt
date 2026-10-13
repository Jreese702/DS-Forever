package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.abs

/**
 * A building's baked staircases, for the storey spine's connectors (MapView.drawSpine). Read from the building's OWN models.json
 * entry ("stairs": [{levels, zbot, ztop}]), which MapView already parses OFF the UI thread with the placements. Never from
 * interiors.json: that is the whole catalogue's index (4-5 MiB at full catalogue, D's report 2026-10-13), and parsing it inside
 * onDraw stalled a frame for every newly seen building.
 */
object InteriorStairs {
    /** A landing is at most this high between two flights of one dog-leg (yd). */
    const val LANDING_YD = 1.2f
    /** A run counts for a storey when its end is within this of the storey's floor band (yd). */
    const val BAND_SLOP_YD = 1.5f

    /** [model]'s stair runs as [zbot, ztop], plus every dog-leg chain; empty when it has none or they are unreadable */
    fun pairs(model: JSONObject?): List<FloatArray> {
        val st = model?.optJSONArray("stairs") ?: return emptyList()
        val out = ArrayList<FloatArray>(st.length())
        for (i in 0 until st.length()) {
            val o = st.optJSONObject(i) ?: continue
            val bot = o.optDouble("zbot", Double.NaN); val top = o.optDouble("ztop", Double.NaN)
            if (bot.isNaN() || top.isNaN()) continue
            out.add(floatArrayOf(bot.toFloat(), top.toFloat()))
        }
        return chain(out)
    }

    /**
     * A dog-leg is two flights with a landing between them, so the run that starts on the ground floor does not reach the storey
     * above on its own. Chain any two runs whose ends are within a landing's height of each other.
     */
    fun chain(runs: List<FloatArray>): List<FloatArray> {
        val merged = ArrayList<FloatArray>(runs)
        for (x in runs) for (y in runs) {
            if (x !== y && abs(x[1] - y[0]) < LANDING_YD) merged.add(floatArrayOf(x[0], y[1]))
        }
        return merged
    }

    /** Does a run in [pairs] link storey a (floor band [aLo, aHi]) to storey b ([bLo, bHi])? */
    fun joined(pairs: List<FloatArray>, aLo: Float, aHi: Float, bLo: Float, bHi: Float): Boolean =
        pairs.any {
            it[0] >= aLo - BAND_SLOP_YD && it[0] <= aHi + BAND_SLOP_YD &&
            it[1] >= bLo - BAND_SLOP_YD && it[1] <= bHi + BAND_SLOP_YD
        }
}
