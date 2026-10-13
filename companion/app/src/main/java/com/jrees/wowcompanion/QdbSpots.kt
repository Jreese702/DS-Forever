package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * QUESTIEDB'S GUESSES ON THE MAP: which spots to draw (pure: tools/map_test runs it off-device against the org.json double).
 *
 * The user, 2026-10-06 via tracking: "Yes, Quests room + map", then "Looks good, build it" on the look (qdb_look.png). Quests'
 * add-on reads QuestieDB and puts every quest's spots on the quests document's list entries: "qdb" {"src":"qdb", "obj":
 * [{m, x, y, i}] objective spots, "turnin": [{m, x, y, n}] the finisher}, in each uiMap's 0..1 space. The rules:
 *   - only a TRACKED quest (the caller hands those in), and only when the GAME shows nothing for it on this map (no point and no
 *     turn-in in the map document's `quests`): the game's answer always wins;
 *   - a quest ready to turn in shows its turn-in, any other its objective spots; another map's spots are not drawn;
 *   - one spot per quest is its LEAD, the one nearest the player in yards (the first when the player's place is unknown): it wears
 *     the quest's number, takes taps and is framed by the auto-zoom.
 */
object QdbSpots {
    class Spot(val qid: Int, val x: Double, val y: Double, val turnIn: Boolean, val lead: Boolean)

    /**
     * [m] the map document (its mapID, the player's x / y, the game's `quests`); [tracked] the tracked quests' list entries;
     * [ax] / [ay] the yards across / down the map's 0..1, so "nearest" is measured on the ground (a zone map is not square).
     */
    fun derive(m: JSONObject, tracked: List<JSONObject>, ax: Double, ay: Double): List<Spot> {
        val out = ArrayList<Spot>()
        val mapId = m.optInt("mapID", Int.MIN_VALUE)
        val px = m.optDouble("x", Double.NaN); val py = m.optDouble("y", Double.NaN)
        val gameShows = HashSet<Int>()               // the quests the game itself places on this map
        m.optJSONArray("quests")?.let { qs ->
            for (i in 0 until qs.length()) {
                val q = qs.optJSONObject(i) ?: continue
                val pt = !q.optDouble("x", Double.NaN).isNaN() && !q.optDouble("y", Double.NaN).isNaN()
                val ti = !q.optDouble("tx", Double.NaN).isNaN() && !q.optDouble("ty", Double.NaN).isNaN()
                if (pt || ti) gameShows.add(q.optInt("questID", 0))
            }
        }
        for (e in tracked) {
            val id = e.optInt("id", 0)
            if (id == 0 || id in gameShows) continue
            val qdb = e.optJSONObject("qdb") ?: continue
            val ready = e.optBoolean("ready") || e.optBoolean("done")
            val pts = qdb.optJSONArray(if (ready) "turnin" else "obj") ?: continue
            val first = out.size
            var lead = -1; var best = Double.MAX_VALUE
            for (j in 0 until pts.length()) {
                val pt = pts.optJSONObject(j) ?: continue
                if (pt.optInt("m", Int.MIN_VALUE) != mapId) continue      // another map's spot: the room names it, the map does not
                val x = pt.optDouble("x", Double.NaN); val y = pt.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                val d = if (px.isNaN() || py.isNaN()) 0.0 else ((x - px) * ax).let { it * it } + ((y - py) * ay).let { it * it }
                if (lead < 0 || d < best) { lead = out.size; best = d }
                out.add(Spot(id, x, y, ready, false))
            }
            if (lead >= first) out[lead] = out[lead].let { Spot(it.qid, it.x, it.y, it.turnIn, true) }
        }
        return out
    }
}
