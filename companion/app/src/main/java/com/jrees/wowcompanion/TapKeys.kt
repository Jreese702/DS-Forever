package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * L1, THE KEY CHANNEL (the user's pick 2026-10-09, "L1 key channel"; the add-on half is TapPad.lua pad10c, both Quests'). A pad
 * action goes out at the controller PRESS as keys instead of a click that waits for the face button's release ([TapQuiet]:
 * press -> tap p50 196 ms / p90 358 ms in Home's lat.log, nearly all of it that wait). A key binding never meets the game's
 * parked cursor, so keys need no quiet pad.
 *
 * pad10 CLOSED THE GAME (2026-10-09 13:50 / 14:03 / 14:04): Ctrl+Alt+Shift+F4 contains ALT+F4, Wine's window close. So pad10c:
 * CTRL-SHIFT only, and no Alt, F-key or system key EVER -- checked here again against [systemChord] (an independent copy of the
 * add-on's rule, and InputSink refuses them a third time).
 *
 * The add-on publishes in describe(): keys = { mods = "CTRL-SHIFT", ttl, rows = { <row name> = <row key> }, cols = [ 40 column keys,
 * "" where a key of the player's is taken ], s = this game session's token }. One cell = TWO keys, the row key (a no-action pick)
 * then the column key. A KEY IS NEVER SENT UNBOUND: WoW falls back to a key's PLAIN binding when a modified chord has none (Shift+1
 * fires action button 1), so a stale document -- the app replays the last one at its start -- or a rolled-back add-on could turn
 * CTRL-SHIFT-1 into the player's "1". So [script] needs the LIVE token from this session's handshake (TapBridge.onKeyTest) and the
 * document's token to match. [script] returns ONE `wckeys CTRL-SHIFT ...` line for InputSink, or null: then the whole batch stays
 * clicks (all or nothing, as [TapBridge.taps]). Only names from the add-on's alphabets are passed on.
 */
object TapKeys {
    const val MODS = "CTRL-SHIFT"
    val ROW_KEYS = setOf("INSERT", "HOME", "END", "PAGEUP", "PAGEDOWN", "UP", "DOWN", "LEFT", "RIGHT")
    val COL_KEYS: Set<String> = (('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } +
        listOf("MINUS", "EQUALS", "[", "]")).toSet()
    /** what Windows / Wine / the launcher treat as system keys (with any Alt or Meta, every F-key, the numpad, the controller) */
    private val SYSTEM = setOf("ESCAPE", "ESC", "TAB", "ENTER", "RETURN", "SPACE", "BACKSPACE", "DELETE", "PRINTSCREEN", "SCROLLLOCK",
        "PAUSE", "NUMLOCK", "CAPSLOCK", "LWIN", "RWIN", "APPS", "MENU", "ALT", "LALT", "RALT")
    private val F_KEY = Regex("^F[0-9]+$")

    fun systemChord(mods: String, key: String): Boolean {
        val m = mods.uppercase().split('-').toSet()
        val k = key.uppercase()
        return "ALT" in m || "META" in m || k.isEmpty() || k in SYSTEM || F_KEY.matches(k) || k.startsWith("NUMPAD") || k.startsWith("PAD")
    }

    /** the document's session token, or null */
    fun session(pad: JSONObject?): String? = pad?.optJSONObject("keys")?.optString("s", "")?.takeIf { it.isNotEmpty() }

    private fun usable(pad: JSONObject?, liveSession: String?): JSONObject? {
        val keys = pad?.optJSONObject("keys") ?: return null
        if (keys.optString("mods") != MODS) return null
        if (liveSession == null || session(pad) != liveSession) return null
        return keys
    }

    /** the keys of one cell (row key, column key), or null when it has none */
    fun cellKeys(pad: JSONObject?, op: String, col: Int, liveSession: String?): Pair<String, String>? {
        val keys = usable(pad, liveSession) ?: return null
        val rk = keys.optJSONObject("rows")?.optString(op, "") ?: return null
        val cols = keys.optJSONArray("cols") ?: return null
        if (col < 0 || col >= cols.length()) return null
        val ck = cols.optString(col)
        if (rk !in ROW_KEYS || ck !in COL_KEYS || systemChord(MODS, rk) || systemChord(MODS, ck)) return null
        return rk to ck
    }

    private fun gapLine(gapMs: Long) = "\nsleep ${"%.3f".format(java.util.Locale.ROOT, gapMs / 1000.0)}\n"

    /**
     * The batch as keys, or null (clicks). [gapMs] > 0 (a sequence that may press the SAME cell twice: its 0.25 s repeat guard):
     * one `wckeys` line per cell with the sleep between, as the clicks are spaced; else all the cells in one line.
     */
    fun script(pad: JSONObject?, cells: List<Pair<String, Int>>, gapMs: Long, liveSession: String?): String? {
        if (cells.isEmpty()) return null
        val pairs = cells.map { (op, col) -> cellKeys(pad, op, col, liveSession) ?: return null }
        if (gapMs <= 0L) return "wckeys $MODS " + pairs.joinToString(" ") { "${it.first} ${it.second}" }
        return pairs.joinToString(gapLine(gapMs)) { "wckeys $MODS ${it.first} ${it.second}" }
    }

    /**
     * THE SWEEP (before keys go live, on the user's word): every row key ALONE (a pick that just expires), then the add-on's own
     * "keytest" row key with each column key (keytest's cells do nothing in the game), [gapMs] apart. Each key is traced by the
     * add-on ("padkeytest") while its trace is on (the handshake's click starts it). Null without a live handshake or a keytest key.
     */
    fun sweep(pad: JSONObject?, gapMs: Long, liveSession: String?): String? {
        val keys = usable(pad, liveSession) ?: return null
        val rowsObj = keys.optJSONObject("rows") ?: return null
        val kt = rowsObj.optString("keytest", "")
        if (kt !in ROW_KEYS) return null
        val rows = ArrayList<String>()
        for (name in rowsObj.keys()) {
            val k = rowsObj.optString(name, "")
            if (k in ROW_KEYS && !systemChord(MODS, k) && k !in rows) rows.add(k)
        }
        rows.sort()
        val cols = keys.optJSONArray("cols") ?: return null
        val lines = rows.map { "wckeys $MODS $it" }.toMutableList()
        for (i in 0 until cols.length()) {
            val c = cols.optString(i)
            if (c in COL_KEYS && !systemChord(MODS, c)) lines.add("wckeys $MODS $kt $c")
        }
        return lines.joinToString(gapLine(gapMs.coerceIn(100L, 1000L)))
    }
}
