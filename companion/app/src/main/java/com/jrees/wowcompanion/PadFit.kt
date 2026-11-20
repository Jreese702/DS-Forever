package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * PAD ROWS THE GAME'S LAYOUT CANNOT PLACE (GitHub #550, with #564, #570, #249, #260: "top screen ui doesnt update when editing in
 * settings"; the reporter: only with the game at about 960x540). The add-on places a pad row only while it ends above the game's pop-up
 * dialogs (TapPad fits()), so a smaller game resolution or a lowered UI scale places fewer than its 56 rows. TapPad pad26 gives the top
 * screen's switches ("uisw") and its restore / layout row ("ui") the first rows, which fit at any layout, and says in describe()'s `drop`
 * which registered rows this layout cannot place. This turns that into ONE short note per layout (never a silent wait) and tells a
 * caller when the row it would tap is not on the game's screen. A pad from an older add-on (no `drop`) reads as nothing dropped. Pure.
 */
object PadFit {
    /** the note when the game's resolution is under 720 lines */
    const val NOTE_RES = "Game resolution too small for some buttons: 1280x720+"
    /** the note at 720 lines or more (the game's UI scale lowered, #249) */
    const val NOTE_SCALE = "Game UI scale too small for some buttons: raise it"
    /** a switch tile's note when its row is not on the game's screen (the tile's "!" badge, never the hourglass) */
    const val NO_ROW_NOTE = "Game resolution or UI scale too small"

    private val told = HashSet<String>()

    /** the registered rows [pad] cannot place, by row (pad26 `drop`); empty for a pad that places them all, or an older add-on's */
    fun dropped(pad: JSONObject?): List<String> {
        val d = pad?.optJSONObject("drop") ?: return emptyList()
        return d.keys().asSequence().toList().sortedBy { d.optInt(it, Int.MAX_VALUE) }
    }

    /** the note for [pad]'s layout, or null when every row is placed */
    fun note(pad: JSONObject?): String? {
        if (pad == null || dropped(pad).isEmpty()) return null
        return if (pad.optInt("ph", 0) in 1 until 720) NOTE_RES else NOTE_SCALE
    }

    /** [note], but only the first time this run sees [pad]'s layout (its size and pop-up top); null after that */
    @Synchronized fun once(pad: JSONObject?): String? {
        val n = note(pad) ?: return null
        val sig = "${pad?.optInt("pw")}x${pad?.optInt("ph")}@${pad?.optDouble("popupTop")}"
        return if (told.add(sig)) n else null
    }

    /** [pad] is published and says [row] is registered but not placed: a tap there can never land (wait on it, never) */
    fun missing(pad: JSONObject?, row: String): Boolean {
        val rows = pad?.optJSONObject("rows") ?: return false
        if (rows.has(row)) return false
        return pad.optJSONObject("drop")?.has(row) == true
    }

    /** for the tests */
    @Synchronized fun reset() { told.clear() }
}
