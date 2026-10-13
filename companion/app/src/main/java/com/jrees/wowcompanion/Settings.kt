package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences

/**
 * User settings, persisted across restarts. One place so every room reads the same values.
 *
 * Zoom levels are stored as YARDS ACROSS THE MAP, not as a zoom factor: the map's zoom factor
 * is relative to the zone (1 = whole zone), so "8x" is ~430 yd in Elwynn and far less in a
 * small zone. Yards mean the same thing in every zone and every building.
 */
object Settings {
    private var prefs: SharedPreferences? = null
    private val listeners = ArrayList<() -> Unit>()

    /** the values the indoor / outdoor steppers move through, yards across the map */
    val ZOOM_STEPS_YD = intArrayOf(20, 30, 40, 60, 80, 120, 160, 250, 400, 600, 1000, 1600)

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** RETIRED 2026-10-02 (the user removed the switch): the map always frames itself. Kept only so old prefs read. */
    var dynamicZoom: Boolean
        get() = prefs?.getBoolean("dynamicZoom", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("dynamicZoom", v)?.apply(); changed() }

    /** RETIRED with dynamic zoom's switch (nothing reads it). */
    var indoorYd: Int
        get() = prefs?.getInt("indoorYd", 60) ?: 60
        set(v) { prefs?.edit()?.putInt("indoorYd", v)?.apply(); changed() }

    /** RETIRED with dynamic zoom's switch (nothing reads it). */
    var outdoorYd: Int
        get() = prefs?.getInt("outdoorYd", 400) ?: 400
        set(v) { prefs?.edit()?.putInt("outdoorYd", v)?.apply(); changed() }

    /** Width in yards of Home's minimap (MapView's compact mode) OUTDOORS. Closer than the Map room. */
    var minimapYd: Int
        get() = prefs?.getInt("minimapYd", 80) ?: 80      // 80 yd: the user's own value (Settings rework, 2026-10-06)
        set(v) { prefs?.edit()?.putInt("minimapYd", v)?.apply(); changed() }

    /**
     * Width in yards of Home's minimap INDOORS (the user, 2026-10-02: "split the minimap default zoom toggle into an indoor and
     * outdoor"). 60 yd until the user sets it (the user, 2026-10-02: "make the default zoom for indoors 60 yards to start").
     */
    var minimapIndoorYd: Int
        get() = prefs?.getInt("minimapIndoorYd", 60) ?: 60
        set(v) { prefs?.edit()?.putInt("minimapIndoorYd", v)?.apply(); changed() }

    /**
     * What opens when the player goes into the chat's message (the user on b121: "I want to have a keyboard vs quick chat default
     * in the settings, so that I can toggle which one I want to appear when clicking into the message"): "quick" | "keys", default "keys".
     * ChatView reads the same prefs key ("settings" / "chatDefaultInput") each time the input opens.
     */
    var chatDefaultInput: String
        // the default is the keyboard (the user's pick, 2026-10-07)
        get() = prefs?.getString("chatDefaultInput", "keys")?.takeIf { it == "quick" || it == "keys" } ?: "keys"
        set(v) { prefs?.edit()?.putString("chatDefaultInput", if (v == "quick") "quick" else "keys")?.apply(); changed() }

    /** the next step from [cur] in direction [dir] (+1 = wider view, -1 = closer) */
    fun stepYd(cur: Int, dir: Int): Int {
        var i = ZOOM_STEPS_YD.indices.minByOrNull { kotlin.math.abs(ZOOM_STEPS_YD[it] - cur) } ?: 0
        i = (i + dir).coerceIn(0, ZOOM_STEPS_YD.size - 1)
        return ZOOM_STEPS_YD[i]
    }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) l() }
}
