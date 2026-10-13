package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * The XP bar and the reputation bar in the bottom bar, on every room (user, 2026-09-28 ~22:05: no numbers on the
 * bars; "a larger xp bar that's cut in half if a rep bar is tracked and it gets replaced by rep bar only when the xp
 * bar is gone"; the numbers are seen in the Character room). UI Art draws them in Strip.kt; this model and the addon
 * part it reads belong to the character session, and the Character room shows the numbers from it.
 *
 * Fed by the Character addon's `{"type":"character","c":{...}}` document, parts `xp` and `watch` (every other part
 * is the Character room's). The addon sends `xp` after every kill and `watch` when the watched faction changes;
 * the heartbeat re-sends both every 15 s, whatever room is open.
 *
 * A number the game makes secret (or that did not arrive) keeps the last one seen and marks the bar [Xp.stale] /
 * [Watch.stale]: the bar is drawn as it last was, never guessed. Nothing here sends anything to the game.
 */
object CharacterBars {

    /**
     * The XP bar. [show] is the game's own rule: false at the level cap, with XP switched off, or when a game rule
     * hides it. [rest] = rested XP still to earn (0 when none); [rested] = the game colours the bar as rested.
     * For the numbers in the Character room (user, 22:05: "Numbers can be seen in the charactor page"; the bars carry
     * none): [text] is the game's own bar text
     * ("XP: 1200/2000") and [lines] the lines of the game's rested tooltip, both as the game words them.
     */
    data class Xp(
        val show: Boolean, val level: Int, val cur: Long, val max: Long, val rest: Long,
        val rested: Boolean, val resting: Boolean, val stale: Boolean = false,
        val text: String = "", val lines: List<String> = emptyList(),
    ) {
        /** 0..1, how much of the bar is filled */
        val fill: Float get() = if (max > 0) (cur.toFloat() / max).coerceIn(0f, 1f) else 0f
        /** 0..1, where the rested reach ends (the game's lighter overlay), clamped to the bar's end */
        val restReach: Float get() = if (max > 0) ((cur + rest).toFloat() / max).coerceIn(0f, 1f) else 0f
    }

    /**
     * The reputation bar: the faction the player set to "Show as Experience Bar". [standing] 1..8 (Hated .. Exalted),
     * 0 when the addon did not send one. [kind] is "" for a standard reputation, "friend" or "major" (renown).
     * [colour] is the game's colour index "1".."8" or "blue" (renown). [cur] / [max] are the value within the
     * standing and the standing's span (a full bar at Exalted). [label] is the game's standing text ("Friendly").
     * [text] is the game's own bar text, for the Character room ("Stormwind 1200 / 6000"; the name alone at the top).
     */
    data class Watch(
        val name: String, val factionId: Int, val standing: Int, val kind: String, val colour: String,
        val cur: Long, val max: Long, val label: String, val stale: Boolean = false, val text: String = "",
    ) {
        val fill: Float get() = if (max > 0) (cur.toFloat() / max).coerceIn(0f, 1f) else 0f
    }

    /** null until the first document; [watch] is null while no faction is shown as the experience bar. */
    @Volatile var xp: Xp? = null
        private set
    @Volatile var watch: Watch? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** [fn] runs after a bar changed, on the thread that called [update] (MainActivity's UI thread). The bottom
     *  bar (Strip.kt) listens; so will the Character room. */
    fun listen(fn: () -> Unit) { listeners += fn }

    private fun tell() { for (fn in listeners.toList()) fn() }

    /** Take a `character` document. True when a bar changed. */
    fun update(msg: JSONObject): Boolean {
        val c = msg.optJSONObject("c") ?: return false
        var changed = false
        c.optJSONObject("xp")?.let { j ->
            val next = readXp(j, xp)
            if (next != xp) { xp = next; changed = true }
        }
        if (c.has("watch")) {
            val next = c.optJSONObject("watch")?.let { readWatch(it, watch) }   // false = none watched
            if (next != watch) { watch = next; changed = true }
        }
        if (changed) tell()
        return changed
    }

    /** Forget both bars (a new game session: the log was truncated). */
    fun clear() {
        if (xp == null && watch == null) return
        xp = null; watch = null
        tell()
    }

    // A number the addon left out (secret, or it threw) keeps the last one, and the bar says it is stale.
    private fun readXp(j: JSONObject, last: Xp?): Xp {
        var stale = false
        fun long(k: String, prev: Long?): Long =
            if (j.has(k) && !j.optDouble(k).isNaN()) j.optLong(k) else { stale = true; prev ?: 0L }
        val cur = long("cur", last?.cur)
        val max = long("max", last?.max)
        val level = long("lvl", last?.level?.toLong()).toInt()
        return Xp(
            show = j.optBoolean("show", last?.show ?: false),
            level = level, cur = cur, max = max,
            rest = if (j.has("rest")) j.optLong("rest") else 0L,
            rested = j.optInt("state") == 1,
            resting = j.optBoolean("resting"),
            stale = stale,
            text = j.optString("text"),
            lines = j.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        )
    }

    private fun readWatch(j: JSONObject, last: Watch?): Watch {
        val id = j.optInt("id")
        val same = last != null && last.factionId == id
        val bar = j.optJSONObject("bar")
        var stale = false
        fun long(k: String, prev: Long?): Long =
            if (bar != null && bar.has(k) && !bar.optDouble(k).isNaN()) bar.optLong(k)
            else { stale = true; if (same) prev ?: 0L else 0L }
        val cur = long("cur", last?.cur)
        val max = long("max", last?.max)
        return Watch(
            name = j.optString("n"), factionId = id, standing = j.optInt("r"),
            kind = bar?.optString("kind") ?: "", colour = bar?.optString("col") ?: "",
            cur = cur, max = max, label = bar?.optString("st") ?: "", stale = stale,
            text = j.optString("text", j.optString("n")),
        )
    }
}
