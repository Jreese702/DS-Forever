package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * Polestar's live objective, the Map list's light blue row (ps1), read from Map.lua's {"type":"pstep"}.
 * ps3 (the user, 2026-11-11: "The polestar tracked quest went missing from my quests"): a step with no fixed spot (talk / use / train
 * goals; Map.lua sends nl = true and no m / x / y) is still a step - the row stays, its A works, the step's quest is still focused;
 * only what needs a place (the goal sent to Polestar's router, the route, the distance) has none: [hasLoc] false.
 */
class PolestarStep(val title: String?, val words: String?, val kind: String?, val quest: Int, val map: Int, val x: Double, val y: Double) {
    /** the step has a spot of its own (Polestar's waypoint): map, x, y */
    val hasLoc: Boolean get() = map != 0 && !x.isNaN() && !y.isNaN()

    companion object {
        /** the doc's step, or null for {none = true}; a step with no location (nl, or no "m") keeps map 0 and NaN x / y */
        fun parse(d: JSONObject): PolestarStep? {
            if (d.optBoolean("none")) return null
            val title = d.optString("t", "").ifEmpty { null }
            val words = d.optString("g", "").ifEmpty { null }
            val loc = !d.optBoolean("nl") && d.has("m")
            if (!loc && title == null && words == null) return null       // (nothing to show: an older add-on's doc with no step)
            return PolestarStep(title, words, d.optString("k", "").ifEmpty { null }, d.optInt("q", 0),
                if (loc) d.optInt("m", 0) else 0,
                if (loc) d.optDouble("x", Double.NaN) else Double.NaN,
                if (loc) d.optDouble("y", Double.NaN) else Double.NaN)
        }
    }
}
