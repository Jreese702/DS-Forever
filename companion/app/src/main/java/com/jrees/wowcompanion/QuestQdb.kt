package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * QUESTIEDB IN THE QUESTS ROOM (the user's picks 2026-10-06 via tracking: "Read Questie in the game", "QuestieDB only, nothing of
 * Questie's on top", "Yes, Quests room + map"). QuestieDB, Questie's data-only add-on, is an OPTIONAL source the add-on reads when the
 * user installed it (Quests.lua q18+); it fills in only where the GAME shows nothing, and live game data always wins.
 *
 * The add-on puts a block on each quest of the quests document, and the map names once on the document (Map's and Quests' shape):
 *   "qdb":{"src":"qdb","turnin":[{"m":uiMapID,"x":0..1,"y":0..1,"n":"finisher's name"}],"obj":[{"m":..,"x":..,"y":..,"i":1}, ...]}
 *   "qdbMaps":{"<uiMapID>":"Elwynn Forest", ...}
 * "i" is the GAME's objective number (1-based, the order of the quest's own objectives); per objective the points on the player's
 * current map come nearest first, or else one point, the nearest anywhere. Pure (no Android imports): the off-device test compiles it.
 */
object QuestQdb {
    data class Spot(val m: Int, val x: Double, val y: Double, val name: String = "")

    /** what QuestieDB adds to one quest: the finisher (its first spot), and per game objective number its nearest spot */
    data class Info(val turnin: Spot?, val obj: Map<Int, Spot>)

    /** the quest's QuestieDB block, or null when there is none (no QuestieDB, or nothing known) */
    fun parse(q: JSONObject?): Info? {
        val b = q?.optJSONObject("qdb") ?: return null
        if (b.optString("src") != "qdb") return null
        fun spot(o: JSONObject?): Spot? {
            if (o == null || !o.has("m") || !o.has("x") || !o.has("y")) return null
            val x = o.optDouble("x", Double.NaN); val y = o.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) return null
            return Spot(o.optInt("m"), x, y, o.optString("n", ""))
        }
        val turnin = b.optJSONArray("turnin")?.let { a -> (0 until a.length()).firstNotNullOfOrNull { spot(a.optJSONObject(it)) } }
        val obj = LinkedHashMap<Int, Spot>()
        b.optJSONArray("obj")?.let { a ->
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val i = o.optInt("i", 0)
                if (i < 1 || obj.containsKey(i)) continue          // nearest first: the first point of each objective is the one
                spot(o)?.let { obj[i] = it }
            }
        }
        if (turnin == null && obj.isEmpty()) return null
        return Info(turnin, obj)
    }

    /** the GAME shows a point for this quest (its own POI / waypoint): then QuestieDB's spots stay out of the room */
    fun gameShowsPoint(q: JSONObject?): Boolean = q != null && q.has("x") && q.has("y")

    /** a map's name from the document's "qdbMaps", or null */
    fun mapName(doc: JSONObject?, m: Int): String? = doc?.optJSONObject("qdbMaps")?.optString(m.toString(), "")?.takeIf { it.isNotEmpty() }

    /** the turn-in line's words: "Turn in: <name>", or null when QuestieDB does not know the finisher */
    fun turninWho(info: Info?): String? = info?.turnin?.name?.takeIf { it.isNotEmpty() }?.let { "Turn in: $it" }

    /** where to say a spot is when it is NOT on the player's map: "in Westfall", or null without a name */
    fun elsewhere(doc: JSONObject?, s: Spot): String? = mapName(doc, s.m)?.let { "in $it" }
}
