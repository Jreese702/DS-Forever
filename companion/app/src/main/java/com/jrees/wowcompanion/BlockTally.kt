package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE LOOT TEST'S COUNT (feat/multi-radial-v2, 2026-11-13: the several spell radials come back without the cooldown sweep, and the user
 * loots 10+ times with spells on radials). Every "has been blocked" the game raised against a DS Forever add-on reaches the app as the
 * core's `cap` document's `hudBlocked` (ActionBar onBlocked: { ev = "blocked" | "forbidden", fn, who, n }, at most 20 a game session);
 * this keeps the run's totals for the problem report's settings: "Blocked actions this run: forbidden 0, blocked 0" or, with any,
 * "... forbidden 2, blocked 0 (last: WowCompanionSpells forbidden SetPreferredGamepadInteractTarget())". Pure enough for the tests.
 */
object BlockTally {
    /** the add-on stops reporting after this many in one game session (a storm must not become a flush storm) */
    const val ADDON_CAP = 20

    @Volatile var forbidden = 0; private set
    @Volatile var blocked = 0; private set
    @Volatile private var last: String? = null
    @Volatile private var capped = false

    /** one `hudBlocked` object */
    @Synchronized fun note(o: JSONObject) {
        val ev = o.optString("ev")
        when (ev) {
            "forbidden" -> forbidden++
            "blocked" -> blocked++
            else -> return
        }
        if (o.optInt("n", 0) >= ADDON_CAP) capped = true
        val who = o.optString("who").take(40)
        val fn = o.optString("fn").take(80)
        last = listOf(who, ev, fn).filter { it.isNotEmpty() }.joinToString(" ")
    }

    @Synchronized fun reset() { forbidden = 0; blocked = 0; last = null; capped = false }

    /** the report's line */
    fun line(): String {
        val more = if (capped) " (the add-on stops counting at $ADDON_CAP a session: maybe more)" else ""
        val tail = last?.let { " (last: $it)" } ?: ""
        return "Blocked actions this run: forbidden $forbidden, blocked $blocked$tail$more"
    }
}
