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
        // the user (test49): "Add another quest tracking option called safepath and apply our current logic set to that, and then make
        // the old vanilla quest tracker the vanilla option" - a saved "vanilla" from before WAS our route logic: it becomes SafePath,
        // once, so nobody silently loses the routes
        prefs?.let { p ->
            if (!p.contains(QT_MIGRATED)) {
                val e = p.edit()
                if (p.getString("questTracking", null) == "vanilla") e.putString("questTracking", "safepath")
                e.putBoolean(QT_MIGRATED, true).apply()
            }
            dropRetired(p)
        }
    }

    /** the retired switches' saved values removed (once: nothing is left to remove after); true when one was there */
    fun dropRetired(p: SharedPreferences): Boolean {
        if (!p.contains(RETIRED_SAFEPATH_V2)) return false
        p.edit().remove(RETIRED_SAFEPATH_V2).apply()
        return true
    }
    private const val QT_MIGRATED = "questTrackingSafePath"

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
    /**
     * QUEST TRACKING (the user, 2026-10-17: "Quest tracking: Vanilla | QuestTrail | Polestar"; "Depends on which one is set in our
     * settings"): what the map's dotted line, the arrows and the game's pin follow. "vanilla" = our own route (learned ways, the tram,
     * proven flights); "polestar" = the focused quest's goal through Polestar's walking router (downloaded on opt-in, DependencyAddons);
     * "questtrail" = QuestTrail's route (player-installed; until its probe is done it routes as vanilla).
     */
    var questTracking: String
        get() = if (!ROUTE_MODES_ON) "vanilla"
                else prefs?.getString("questTracking", "safepath")?.takeIf { it in QUEST_TRACKING } ?: "safepath"
        set(v) {
            if (!ROUTE_MODES_ON) return                     // held back for 1.0.9: the stored choice is kept for when they return
            prefs?.edit()?.putString("questTracking", if (v in QUEST_TRACKING) v else "safepath")?.apply(); changed()
        }
    /** the user (1.0.9 release): "Disable the safe path, quest trail and polestar buttons for now" - Vanilla only until this is true */
    const val ROUTE_MODES_ON = true
    /** VANILLA: the game's own way, the line straight at the quest, no route, no pin; SAFEPATH: DS Forever's route (the default) */
    val QUEST_TRACKING = listOf("vanilla", "safepath", "questtrail", "polestar")

    /** "Avoid dangerous areas" (b-danger-layer): how hard the route bends round hostile mobs; High by default (the user's pick) */
    var avoidDanger: String
        get() = prefs?.getString("avoidDanger", "high")?.takeIf { it in AVOID_DANGER } ?: "high"
        set(v) { prefs?.edit()?.putString("avoidDanger", if (v in AVOID_DANGER) v else "high")?.apply(); changed() }
    val AVOID_DANGER = listOf("off", "low", "high")

    /** RETIRED 2026-10-19 (test20; the user: "also change my in game settings to just launch with the new safe path, remove the v1 vs
     *  v2 options"): the "SafePath v2 routing" switch is gone. Quest tracking = SafePath routes on the zone's shipped network
     *  (ShippedRoute) wherever the APK has one; the on-device lattice (SafePathV2, ClimbStep) is dormant behind [SafePathV2.enabled], an
     *  internal flag. A saved "safePathV2" from before is removed on first launch ([dropRetired]), so no stale toggle changes routing. */
    const val RETIRED_SAFEPATH_V2 = "safePathV2"

    /** "World map" (b-seamless-map; the user: "Give an option in settings"): the stitched continent shows every zone revealed ("all",
     *  the default) or only what this character has explored ("explored") */
    var worldMapFog: String
        get() = prefs?.getString("worldMapFog", "all")?.takeIf { it == "all" || it == "explored" } ?: "all"
        set(v) { prefs?.edit()?.putString("worldMapFog", if (v == "explored") "explored" else "all")?.apply(); changed() }

    var chatDefaultInput: String
        // the default is the keyboard (the user's pick, 2026-10-07)
        get() = prefs?.getString("chatDefaultInput", "keys")?.takeIf { it == "quick" || it == "keys" } ?: "keys"
        set(v) { prefs?.edit()?.putString("chatDefaultInput", if (v == "quick") "quick" else "keys")?.apply(); changed() }

    /**
     * INDOOR MAPS IN THE BACKGROUND (board b-interiors-stutter; the user's pick: "a Settings switch to turn background building off").
     * On (the default) = built only while the player stands still and the CPU is cool; Off = no building at all (ready maps still show).
     */
    var indoorBuild: Boolean
        get() = prefs?.getBoolean("indoorBuild", true) ?: true
        set(v) { if (v != indoorBuild) { prefs?.edit()?.putBoolean("indoorBuild", v)?.apply(); changed() } }

    /** the next step from [cur] in direction [dir] (+1 = wider view, -1 = closer) */
    fun stepYd(cur: Int, dir: Int): Int {
        var i = ZOOM_STEPS_YD.indices.minByOrNull { kotlin.math.abs(ZOOM_STEPS_YD[it] - cur) } ?: 0
        i = (i + dir).coerceIn(0, ZOOM_STEPS_YD.size - 1)
        return ZOOM_STEPS_YD[i]
    }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) l() }
}
