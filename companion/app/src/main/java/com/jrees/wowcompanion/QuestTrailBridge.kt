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
        val words = o.optString("t", "").replace("\n", " · ").takeIf { it.isNotEmpty() }
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

    /**
     * QUESTTRAIL'S STEP AS THE FOCUS (b-qtrail-focus; the user, test51: "Using quest trail, the focused quest should be overwritten by
     * the quest trail objective"). QuestTrail does not super-track its pick (with "hide Blizzard" it even clears the game's focus), so
     * the quest is found from its compass label (QuestTrail_Compass.lua 3.1.0): a first line with what to do ("Objective", "Turn in",
     * "Take", "Quest item", maybe "Fly to X, then ..."), then "<the quest's title>[ (estimate)][ – <the objective's text>]". [kind]:
     * QuestTrail's first line; [objective]: the objective's own words, null for a turn-in / take.
     */
    class Step(val questId: Int, val kind: String?, val objective: String?, val title: String? = null) {
        /** the card's one line: the objective's words, else what to do ("Turn in"); a step not in the log: "Take: <quest> from <giver>" */
        val words: String? get() = if (questId == 0) looseWords() else objective ?: kind
        private fun looseWords(): String? {
            val k = kind?.trimEnd(':')
            val what = listOfNotNull(k?.let { "$it:" }, title).joinToString(" ").ifEmpty { null } ?: return objective
            return if (objective == null) what else if (k.equals("Take", ignoreCase = true)) "$what from $objective" else "$what$DASH$objective"
        }
        /** a step for no quest of the log (a quest to take, a flight): QuestTrail's own words stand for it */
        val loose get() = questId == 0
    }

    private const val DASH = " – "
    private const val ESTIMATE = " (estimate)"

    /**
     * The quest of the log [titles] (questID to title) QuestTrail's label [label] names; null when it names none (flying, a boat, the
     * corpse, "Recomputing path", a quest to take that is not in the log yet). The longest title that the label's last line starts
     * with, followed by nothing, " (estimate)" or " – "; two quests of one title: the one with [objectives] holding the step's words.
     */
    fun step(label: String?, titles: Map<Int, String>, objectives: (Int) -> List<String> = { emptyList() }): Step? {
        if (label.isNullOrBlank()) return null
        val lines = label.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val line = lines.last()
        var best = emptyList<Int>(); var bestLen = 0
        for ((id, t0) in titles) {
            val t = t0.trim()
            if (t.isEmpty() || t.length < bestLen || !line.startsWith(t)) continue
            val rest = line.substring(t.length)
            if (rest.isNotEmpty() && !rest.startsWith(DASH) && !rest.startsWith(ESTIMATE)) continue
            if (t.length > bestLen) { best = listOf(id); bestLen = t.length } else best = best + id
        }
        if (best.isEmpty()) return null
        var rest = line.substring(bestLen)
        if (rest.startsWith(ESTIMATE)) rest = rest.substring(ESTIMATE.length)
        val obj = if (rest.startsWith(DASH)) rest.substring(DASH.length).trim().ifEmpty { null } else null
        val kind = if (lines.size >= 2) lines[lines.size - 2] else null
        val id = if (best.size == 1 || obj == null) best.minOrNull()!!
            else best.firstOrNull { q -> objectives(q).any { o -> o.trim().let { it.startsWith(obj) || obj.startsWith(it) } } } ?: best.minOrNull()!!
        return Step(id, kind, obj)
    }

    /** what a compass label is: a STEP (a quest's, or a target of its own: "Map pin", "Your corpse", the hearthstone), PASSING (on the
     *  way: "Recomputing path" while the needle spins, "Flying to X", "On the way to X" - the step before it stands), or IDLE
     *  ("QuestTrail: <why there is no arrow>": no target) */
    enum class Label { STEP, PASSING, IDLE }

    /** [label]'s kind, from QuestTrail 3.2.1's English texts (Locales/enUS.lua: compass_DrawNow_recomputingPath, _flyingTo, _onTheWayTo,
     *  compass_Idle_questtrail) */
    fun labelKind(label: String): Label {
        val t = label.trim()
        if (t.startsWith("QuestTrail:")) return Label.IDLE
        val last = t.split('\n').lastOrNull { it.isNotBlank() }?.trim() ?: return Label.IDLE
        if (last.startsWith("Recomputing path") || last.startsWith("Flying to ") || last.startsWith("On the way to ")) return Label.PASSING
        return Label.STEP
    }

    /**
     * QuestTrail's step when its label names no quest of the log (the user on test61: "the QuestTrail objective needs to trump the
     * focus objective" - a "Take" step's quest is not in the log yet): its own words, [Step.questId] 0. The last line's title (up to
     * " – " / " (estimate)") and what follows the dash (a giver, an objective); [Step.kind] its first line. Null for an empty label.
     */
    fun loose(label: String?): Step? {
        if (label.isNullOrBlank()) return null
        val lines = label.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        var line = lines.last()
        val dash = line.indexOf(DASH)
        val rest = if (dash >= 0) line.substring(dash + DASH.length).trim().ifEmpty { null } else null
        if (dash >= 0) line = line.substring(0, dash)
        val title = line.removeSuffix(ESTIMATE).trim().ifEmpty { null }
        val kind = if (lines.size >= 2) lines[lines.size - 2] else null
        return Step(0, kind, rest, title)
    }

    // ---- QUESTTRAIL'S GOLDEN PATH (b-qtrail-golden; the user, test55: "it should show the actual trail like it normally does")
    /** one stretch of it on this map (0..1): from ([x0], [y0]) to ([x1], [y1]); [kind] 0 walked (gold), 1 others' (pale), 2 straight */
    class Piece(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val kind: Int)

    /**
     * The golden path QuestTrail draws on the minimap (Map.lua's `qtrail.gp`: {w, yd, rot, s = [[x1, y1, x2, y2, k], ...]} in minimap
     * pixels, x east, y north, turned by [rot] when the minimap rotates) put on this map: the player at world ([pwx], [pwy]); the
     * map's world rectangle ([top], [left]) and its yards ([ax] across, [ay] down). Empty when there is none.
     */
    fun trail(gp: JSONObject?, pwx: Double, pwy: Double, top: Double, left: Double, ax: Double, ay: Double): List<Piece> {
        val a = gp?.optJSONArray("s") ?: return emptyList()
        val w = gp.optDouble("w", 0.0); val yd = gp.optDouble("yd", 0.0)
        if (w <= 0.0 || yd <= 0.0 || pwx.isNaN() || pwy.isNaN() || ax <= 0.0 || ay <= 0.0) return emptyList()
        val scale = w / yd                                     // minimap pixels a yard
        val rot = gp.optDouble("rot", 0.0); val cs = cos(rot); val sn = sin(rot)
        fun mapX(x: Double, y: Double): DoubleArray {
            val e = (x * cs - y * sn) / scale; val n = (x * sn + y * cs) / scale       // east, north (yards)
            val wx = pwx + n; val wy = pwy - e                                         // world: x north, y west
            return doubleArrayOf((left - wy) / ax, (top - wx) / ay)
        }
        val out = ArrayList<Piece>()
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            if (p.length() < 4) continue
            val s0 = mapX(p.optDouble(0), p.optDouble(1)); val s1 = mapX(p.optDouble(2), p.optDouble(3))
            if (s0.any { it.isNaN() } || s1.any { it.isNaN() }) continue
            out.add(Piece(s0[0], s0[1], s1[0], s1[1], p.optInt(4, 0)))
        }
        return out
    }
}
