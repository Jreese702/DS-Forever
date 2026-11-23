package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * A WOW SETTING MOVED A BAR ONTO LT / RT (the tester, 2026-11-23: "couldn't use any macros on LT or RT with a face button"; the user:
 * "I wonder if he changed a ... setting for lt+rt in the wow settings and if thats something that we need to consider"). The game's
 * Gamepad settings Stance Bar and Possess Bar (GamepadStanceBarOverride / GamepadPossessBarOverride: 2 / 3 / 4 = Page 1 Left / Right /
 * Bottom, the LT / RT / LT + RT layers) lay that bar over a trigger layer: in a form (a vehicle) LT / RT + a face button then cast the
 * form's (vehicle's) bar, not the player's own. That is the game's own behaviour; Home says so once, in a short note that goes by itself
 * after [HOLD_MS] (or on B), when the setting CHANGES onto a trigger layer for this character.
 *
 * The values come read only in ActionBar's hud document (`bars.st.v` / `bars.ps.v`, [GameBars]); this keeps each character's last seen
 * pair (prefs `padnote`, "bars.<character key>": numbers only, never a name in the value). The first sight of a character is kept
 * silently (the game's defaults, and a setting the player made long ago, are not news); a change onto LT / RT / LT + RT is one note.
 * Pure apart from [Store]: the tests run it off-device.
 */
object PadLayerNote {
    const val HOLD_MS = 5_000L
    const val IN_MS = 150L
    const val OUT_MS = 300L
    const val SHOW_MS = IN_MS + HOLD_MS + OUT_MS

    /** the per-character memory ("st=3;ps=1"); the host's is the `padnote` prefs file */
    interface Store {
        fun get(who: String): String?
        fun put(who: String, memo: String)
    }

    /** the layer a setting's value puts the bar on, as the controller says it; null = not a trigger layer (none, the special page, pages 2-3) */
    fun layer(v: Int?): String? = when (v) { 2 -> "LT"; 3 -> "RT"; 4 -> "LT + RT"; else -> null }

    fun memo(st: Int?, ps: Int?): String = "st=${st ?: "-"};ps=${ps ?: "-"}"

    private fun read(memo: String?, key: String): Int? =
        memo?.split(';')?.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.toIntOrNull()

    /** the hud document's `bars` ({st: {v}, ps: {v}}): the two values, null where absent */
    fun values(bars: JSONObject?): Pair<Int?, Int?> {
        fun v(o: JSONObject?) = o?.optInt("v", 0)?.takeIf { it > 0 }
        return v(bars?.optJSONObject("st")) to v(bars?.optJSONObject("ps"))
    }

    /** the note's words for the Stance Bar on [layer] (English: the key Tr.t translates where it is drawn) */
    fun stanceWords(layer: String): String = "WoW's Stance Bar is on $layer: in a form, $layer + buttons cast your form bar"

    /** the note's words for the Possess Bar on [layer] */
    fun possessWords(layer: String): String = "WoW's Possess Bar is on $layer: in a vehicle, $layer + buttons cast its bar"

    /** every note's words (the translations' keys; a test proves es / fr have each) */
    val ALL: List<String> = listOf("LT", "RT", "LT + RT").flatMap { listOf(stanceWords(it), possessWords(it)) }

    /**
     * the notes for this character's values now ([st], [ps]) against its last seen ones ([prev] memo; null = never seen); the memo to
     * keep. A value that is not known (null) keeps the last seen one and says nothing
     */
    fun decide(prev: String?, st: Int?, ps: Int?): Pair<String, List<String>> {
        val pst = read(prev, "st"); val pps = read(prev, "ps")
        val keep = memo(st ?: pst, ps ?: pps)
        if (prev == null) return keep to emptyList()
        val notes = ArrayList<String>()
        if (st != null && st != pst) layer(st)?.let { notes += stanceWords(it) }
        if (ps != null && ps != pps) layer(ps)?.let { notes += possessWords(it) }
        return keep to notes
    }

    /** one hud document for [who] (a character key; null = not known yet: nothing kept, nothing said): the notes to show */
    fun onBars(store: Store, who: String?, bars: JSONObject?): List<String> {
        if (who.isNullOrEmpty() || bars == null) return emptyList()
        val (st, ps) = values(bars)
        if (st == null && ps == null) return emptyList()
        val prev = store.get(who)
        val (keep, notes) = decide(prev, st, ps)
        if (keep != prev) store.put(who, keep)
        return notes
    }

    /** the note's alpha [ms] after it first showed (0 once it has gone) */
    fun alpha(ms: Long): Float = when {
        ms < 0 || ms >= SHOW_MS -> 0f
        ms < IN_MS -> ms / IN_MS.toFloat()
        ms < IN_MS + HOLD_MS -> 1f
        else -> 1f - (ms - IN_MS - HOLD_MS) / OUT_MS.toFloat()
    }

    /** the log line for a note (our words only) */
    fun logLine(words: String): String = "game setting note: $words"
}
