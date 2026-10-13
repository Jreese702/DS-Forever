package com.jrees.wowcompanion

/**
 * THE COMPANION'S SELF-SETUP, the decisions (Home, 2026-10-02; the user: "would it be possible to have the addon install and Config
 * change as part of our apps install ... we can make it a prompt that happens if its missing and then it goes and fixes it", "it would
 * be sweet to have it just close the game and reopen it after its all set up", "it should say installing or something while it does
 * that", "lets get that all added"). Pure (home_test). The work itself is the shipped script `assets/companion/companion_setup.sh`
 * (SetupRunner), the screen is SetupView.
 *
 * THE SAME FOR EVERY USER (the user: "most people are going to be using this from a fresh set up standpoint and I dont want to just
 * migrate stuff over, I want to set defaults and for this to set it up for people"): REQUIRED settings, then fixed DEFAULTS written
 * only where the game has no value yet (the user's picks: turn with camera "Moving + combat", hide tooltips in gamepad mode).
 * Nothing is copied from another install, and there is no checkbox.
 *
 * WHAT IS NEVER OVERWRITTEN: an install whose add-ons differ from the app's set but carry no older set stamp. Owners push straight to
 * GameNative between batches, so a difference alone may mean the GAME is ahead of the app; only a missing folder, a stamp older than
 * the app's set, or a missing setting asks.
 */
object CompanionSetup {

    enum class Game(val id: String, val pkg: String, val label: String) {
        GAMENATIVE("gn", "app.gamenative", "GameNative"),
        WOWFOREVER("wf", "app.wowforever", "WoW Forever"),
        /** the private fork (the user 2026-10-08: "Teach DS Forever the fork's app name" (a)) */
        DSLAUNCHER("dl", "app.dsforever.launcher", "DS Forever Launcher"),
    }

    /** what `companion_setup.sh check` reported for one game */
    data class Check(
        val installed: Boolean,
        val running: Boolean,
        val stamp: Int?,
        val missing: List<String>,
        val diff: Int,
        /** REQUIRED Config.wtf settings not "1" (developerLog, InputDeviceInterfaceStyle) */
        val configMissing: List<String>,
        /** REQUIRED: the game app's touch settings that are wrong ("touchscreenMode", "shooterMode"; no pad tap lands without them) */
        val touchMissing: List<String> = emptyList(),
        /** DEFAULTS the game has no value for yet (GamepadDisableTooltips, GamePadTurnWithCamera) */
        val defaultsMissing: List<String> = emptyList(),
    )

    enum class Need { NONE, SETUP, UPDATE, SETTINGS }

    /** the script's lines -> a Check; null unless the answer is complete (ends with END and says INSTALLED) */
    fun parse(lines: List<String>): Check? {
        if (lines.none { it.trim() == "END" }) return null
        var installed: Boolean? = null
        var running = false
        var stamp: Int? = null
        var missing = emptyList<String>()
        var diff = 0
        var cfg = emptyList<String>()
        var touch = emptyList<String>()
        var defs = emptyList<String>()
        for (raw in lines) {
            val l = raw.trim()
            val key = l.substringBefore(' ')
            val rest = l.substringAfter(' ', "").trim()
            when (key) {
                "INSTALLED" -> installed = rest == "1"
                "RUNNING" -> running = rest == "1"
                "STAMP" -> stamp = rest.toIntOrNull()
                "MISSING" -> missing = words(rest)
                "DIFF" -> diff = rest.toIntOrNull() ?: 0
                "CONFIG_MISSING" -> cfg = words(rest)
                "TOUCH_MISSING" -> touch = words(rest)
                "DEFAULTS_MISSING" -> defs = words(rest)
            }
        }
        return Check(installed ?: return null, running, stamp, missing, diff, cfg, touch, defs)
    }

    private fun words(s: String) = s.split(' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** "set 40" (the manifest's first line) -> 40 */
    fun setId(manifestFirstLine: String?): Int? =
        manifestFirstLine?.trim()?.takeIf { it.startsWith("set ") }?.removePrefix("set ")?.trim()?.toIntOrNull()

    /** what [c] needs, given the app's own set [appSet] (null = the app carries no add-ons: only the settings can be offered) */
    fun need(c: Check, appSet: Int?): Need = when {
        !c.installed -> Need.NONE
        appSet != null && c.missing.isNotEmpty() -> Need.SETUP
        appSet != null && c.stamp != null && c.stamp < appSet && c.diff > 0 -> Need.UPDATE
        c.touchMissing.isNotEmpty() || c.configMissing.isNotEmpty() || c.defaultsMissing.isNotEmpty() -> Need.SETTINGS
        else -> Need.NONE
    }

    /** which game to ask about first: the one running, else WoW Forever before GameNative (the newer install is the likelier gap) */
    fun first(needs: Map<Game, Pair<Check, Need>>): Game? =
        needs.filter { it.value.second != Need.NONE }.entries
            .sortedWith(compareBy({ !it.value.first.running }, { when (it.key) { Game.DSLAUNCHER -> 0; Game.WOWFOREVER -> 1; else -> 2 } }))
            .firstOrNull()?.key

    /** the prompt: a short title, one bullet per change THIS run makes (only the missing ones), one closing line */
    data class Prompt(val title: String, val bullets: List<String>, val footer: String)

    /** the user, 2026-10-02: "the button pop up is also very verbos, can we just give it a bullet list of the chnages that it wants to make?" */
    fun prompt(g: Game, n: Need, c: Check): Prompt {
        val b = ArrayList<String>()
        if (n == Need.SETUP) b.add("Install the companion add-ons")
        if (n == Need.UPDATE) b.add("Update the companion add-ons")
        if ("developerLog" in c.configMissing) b.add("Turn on the game's log for the companion")
        if ("InputDeviceInterfaceStyle" in c.configMissing) b.add("Turn on the game's controller mode")
        if (c.touchMissing.isNotEmpty()) b.add("${g.label}: Touchscreen mode on, Shooter mode off")
        if ("GamepadDisableTooltips" in c.defaultsMissing) b.add("Controller tooltips off")
        if ("GamePadTurnWithCamera" in c.defaultsMissing) b.add("Turn with camera: moving + combat")
        val footer = if (c.running) "The game will close and restart." else "The game isn't running, so nothing closes."
        return Prompt("Set up ${g.label} for the companion?", b, footer)
    }

    /** the one line while it works (the user: "it should say installing or something while it does that") */
    const val CLOSING = "Closing the game…"
    const val INSTALLING = "Installing the companion…"
    const val STARTING = "Starting the game…"
    const val IN_FIGHT = "Not during a fight. Try again when it's over."
    const val DONE_IDLE = "Done. The companion is ready when you start the game."

    /** a FAIL line from the script -> what the screen says (the backup is back in place by then) */
    fun failLine(fail: String, restored: Boolean): String {
        val why = fail.removePrefix("FAIL").trim().substringAfter(' ', "").ifEmpty { "something went wrong" }
        return "Setup stopped: $why." + if (restored) " Your files were put back." else ""
    }
}
