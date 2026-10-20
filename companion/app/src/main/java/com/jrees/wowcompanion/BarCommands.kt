package com.jrees.wowcompanion

/**
 * THE BAR COMMANDS' ROUTES (Track C, test24; the user: "when I press the spell radial button, nothing happens still"). Hub.lua's plain
 * buttons send a `barcmd` with a key; [HomeBarSelect.onCommand] moves its cursor for left / right / open / home and hands the rest to the
 * host. "sradial" (hub8, the Spell Radial's button) was missing from that hand-over: the command was dropped, the in-game button's own
 * radial hold was freed 0.4 s later by the watchdog ("its room is not on screen"), and no wheel ever opened (test23 logcat 22:15:39-50).
 * One list now, and home_test checks every key the bundled Hub.lua sends against it. Pure.
 */
object BarCommands {
    /** the keys the HOST opens itself (HomeBarSelect.onDpad): the map / chat rooms, the two radials, the pause wheel */
    val HOST = setOf("map", "chat", "radial", "sradial", "pause", "smartor")      // + "smartor": Smart buffs' OR wheel (Hub hub12)

    enum class Route { CURSOR, OPEN, HOME, HOST, UNKNOWN }

    fun route(k: String): Route = when (k) {
        "left", "right" -> Route.CURSOR
        "open" -> Route.OPEN
        "home" -> Route.HOME
        in HOST -> Route.HOST
        else -> Route.UNKNOWN
    }

    /** the keys a Hub.lua source's buttons send (its `{ <ButtonName> = "<key>", ... }` table) */
    fun hubKeys(hubLua: String): Set<String> =
        Regex("""WowCompanion(?:Bar|Pad)\w+\s*=\s*"([a-z]+)"""").findAll(hubLua).map { it.groupValues[1] }.toSet()
}
