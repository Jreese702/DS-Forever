package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE MAP ROOM'S OWN TAP-PAD ROW "mapw" and the room's hold of the game's world map window (Map.lua mr1; the pad owner's yes,
 * 2026-10-07). Pure (no Android types): tools/map_test runs it off-device.
 *
 * The user's v2 (2026-10-07): the Map room works like the game's own map AND, like every room, opens the game's window behind it
 * (2026-10-01: "it should act the EXACT same for all rooms"). Map.lua holds the shared capture as "map" for both ways in: the app's
 * hold cell (touch / the bar) and the game's own WorldMapFrame (M, the radial, the open cell), which it makes invisible.
 * Columns: 0 hold, 1 free, 2 bh (B leads home), 3 bb (B leads back) - no game action, on the press; 4 open (a secure click of the
 * game's QuestLogMicroButton, armed only while the room holds and the map is closed); 5 close (the game's own close button); 6-39 the
 * map pin's digits: two lanes of 17, a pin sent in alternating lanes A, B, A, ...: op (A: 0 place-or-remove, 1 clear), the uiMapID as
 * 3 base-17 digits, x and y as 2 each (1/289 steps). Taps go out through TapBridge.tapsSpaced (the pad drops a repeat on one cell
 * within 0.25 s, and a pin's same-lane digit can come back two taps later).
 */
object MapRoomPad {
    const val ROW = "mapw"
    const val HOLD = 0
    const val FREE = 1
    const val BH = 2
    const val BB = 3
    const val OPEN = 4
    const val CLOSE = 5
    const val WP_A = 6
    const val WP_B = 23
    const val BASE = 17
    const val GAP_MS = 120L

    /** the add-on publishes the row (an older Map.lua does not: the room keeps the generic guest "map") */
    fun has(pad: JSONObject?): Boolean = pad?.optJSONObject("rows")?.has(ROW) == true

    fun cell(col: Int): List<Pair<String, Int>> = listOf(ROW to col)

    /** the minimap probe's options (Map.lua mp1; bits 1 the probe, 2 the Find switch, 4 sparkles): op digit 2 on lane A, the bits on lane B */
    fun optCells(bits: Int): List<Pair<String, Int>> = listOf(ROW to WP_A + 2, ROW to WP_B + bits.coerceIn(0, 7))

    /** a pin at ([x], [y]) on map [mapId]: 8 taps; null when it cannot be said (a map id over 3 digits, a spot off the map) */
    fun pinCells(mapId: Int, x: Float, y: Float): List<Pair<String, Int>>? {
        if (mapId <= 0 || mapId >= BASE * BASE * BASE) return null
        if (x.isNaN() || y.isNaN() || x < 0f || x > 1f || y < 0f || y > 1f) return null
        val n2 = BASE * BASE
        val xi = (x * n2).toInt().coerceIn(0, n2 - 1)
        val yi = (y * n2).toInt().coerceIn(0, n2 - 1)
        val d = listOf(0, mapId / n2, (mapId / BASE) % BASE, mapId % BASE, xi / BASE, xi % BASE, yi / BASE, yi % BASE)
        return d.mapIndexed { i, v -> ROW to ((if (i % 2 == 0) WP_A else WP_B) + v) }
    }

    /** clear the game's pin: op 1, one tap */
    fun clearCells(): List<Pair<String, Int>> = listOf(ROW to WP_A + 1)

    /** the room's state as Map.lua last said it (the "mapwin" document) */
    class Win(
        val held: Boolean = false, val via: String? = null, val pend: Boolean = false, val open: Boolean = false,
        val openN: Int = 0, val armed: Boolean = false, val note: String? = null, val bnext: String? = null,
    ) {
        /** the room really holds (not queued behind another holder, not a fight's pending ENTER) */
        val active get() = held && !pend
        val byWindow get() = active && via == "window"

        companion object {
            fun from(d: JSONObject) = Win(
                held = d.optBoolean("held", false), via = d.optString("via", "").ifEmpty { null },
                pend = d.optBoolean("pend", false), open = d.optBoolean("open", false), openN = d.optInt("openN", 0),
                armed = d.optBoolean("armed", false), note = d.optString("note", "").ifEmpty { null },
                bnext = d.optString("bnext", "").ifEmpty { null },
            )
        }
    }

    /**
     * THE WAY IN, after the hold (Character's lesson, Home's audit 10-07: a secure cell acts on the RELEASE, which a controller
     * press can move off the cell, so the hold goes first and the game's window is opened only once the hold is real): tap the
     * open cell once, while the room holds, the map is closed and the add-on says the cell is armed.
     */
    fun wantOpen(w: Win, openTapped: Boolean): Boolean = !openTapped && w.active && !w.open && w.armed

    /** THE WAY OUT the app takes (a room change, B with nothing left): the game's close while its map shows, else the free */
    fun leaveCol(w: Win): Int = if (w.open) CLOSE else FREE

    /**
     * THE WAY OUT with the room's own OPEN tap maybe still on its way ([openTapped]: tapped, the map not yet said open). The user, 2026-10-09:
     * "sometimes when closing the quests room the map opens on the top screen" - the same race here: the free alone lets go, the
     * late open then opens the game's map with nothing hiding it. So the close AND then the free: the open lands first and opens the
     * map, the close shuts it (Map.lua lets go on its OnHide; the free then finds nothing held); if nothing opened, the close carries
     * nothing and the free lets go. Sent spaced ([GAP_MS]): the close cell is armed a frame after the map shows.
     */
    fun leaveCols(w: Win, openTapped: Boolean): List<Int> = when {
        w.open -> listOf(CLOSE)
        openTapped -> listOf(CLOSE, FREE)
        else -> listOf(FREE)
    }

    /** a new open by the GAME itself (M, the radial): the app brings the Map room up (Social's openN rule) */
    fun gameOpened(before: Win?, now: Win): Boolean = before != null && now.openN > before.openN && now.open
}
