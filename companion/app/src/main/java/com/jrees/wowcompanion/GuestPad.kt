package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE GENERIC CONTROLLER GUESTS (LATER item 5, Home's decision 2026-09-30). A room WITHOUT an addon of its own holds the
 * controller through the quests addon's facility (Quests.lua q6) on the spare columns of the `qact` row, so the pad needs
 * no new row. The addon takes interact's shared capture as `pad:<id>` and relays every press as
 * {"type":"guestpad","id":..,"k":..,"n":..} (a lost hold as {"type":"guestpad","id":..,"lost":..}); its answers are
 * {"type":"guestack","id":..,"op":..,"ok":..}.
 *
 * Columns: 5 = the holder lets go with why "B" (the capture's document then takes the app Home), 6 / 7 = B leads home /
 * back (the Home button shows B for "home"), and per guest, in this FIXED order, hold = 8 + 2i and free = 9 + 2i.
 * 37 (Quests.lua q12) = the holder turned a press down because of the fight: Home pulses its red flash (capture v8).
 * Each call returns the cells for [TapBridge.taps], or null when the pad has no `qact` row (or the id is unknown).
 */
object GuestPad {
    /** Home's spec: the order is the column layout; never reorder it, new ids only go on the END (Quests.lua GUESTS) */
    val IDS = listOf("quests", "map", "settings", "chat", "bar", "home", "radial", "tool")   // tool: Home's Track Pad & Keyboard (q-tool)   // radial: the consumables radial (Quests.lua q15)

    private const val ROW = "qact"
    private const val LEAVE_B = 5
    private const val BNEXT_HOME = 6
    private const val BNEXT_BACK = 7
    private const val FIRST = 8
    private const val REFUSED = 37      // the quest actions grow down from 39 (39 untrack, 38 share), so this sits under them
    private const val PLAYER_MENU = 34  // Quests.lua q41: read the game's own player menu (36 / 35 are Start's; 26..33 its digits)

    /** hold (true) or free (false) the controller for guest [id] */
    fun cells(pad: JSONObject?, id: String, hold: Boolean): List<Pair<String, Int>>? {
        val i = IDS.indexOf(id)
        if (i < 0) return null
        return code(pad, FIRST + 2 * i + (if (hold) 0 else 1))
    }

    /** the holder lets go with why "B": the app's capture route then shows Home */
    fun leaveB(pad: JSONObject?): List<Pair<String, Int>>? = code(pad, LEAVE_B)

    /** where the holder's next B leads, for the Home button's B glyph */
    fun bnext(pad: JSONObject?, home: Boolean): List<Pair<String, Int>>? = code(pad, if (home) BNEXT_HOME else BNEXT_BACK)

    /** the holder turned a press down because of the fight: the addon calls the capture's refused(), Home flashes red */
    fun refused(pad: JSONObject?): List<Pair<String, Int>>? = code(pad, REFUSED)

    /** read the game's own player menu, the one the player's portrait opens (the map module's portrait): a {"type":"pmenu"} answers */
    fun playerMenu(pad: JSONObject?): List<Pair<String, Int>>? = code(pad, PLAYER_MENU)

    /** run entry [id] of the player menu the game last sent (Quests.lua q41: two digit taps, 33 - digit) */
    fun playerMenuRun(pad: JSONObject?, id: Int): List<Pair<String, Int>>? =
        if (pad?.optJSONObject("rows")?.has(ROW) == true) PlayerMenu.digitCols(id).map { ROW to it } else null

    private fun code(pad: JSONObject?, col: Int): List<Pair<String, Int>>? =
        if (pad?.optJSONObject("rows")?.has(ROW) == true) listOf(ROW to col) else null
}
