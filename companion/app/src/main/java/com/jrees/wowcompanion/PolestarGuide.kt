package com.jrees.wowcompanion

/**
 * THE POLESTAR QUEST GUIDE, PART OF THE BASE INSTALL (the user, 2026-11-12: "Our new pole star integration is pretty good. Lets start
 * including this addon to the base install like questie and give a prompt asking players if they want to use the new polestar quest
 * guide integration. If they say no let's add a toggle in settings to turn it on and off. We need to remove safe path and quest trail
 * from the options. If anyone used these default them over to the polestar quest tracking").
 *
 * ONE CHOICE, [Settings.polestarGuide]: "on" / "off", or null = not asked yet. It drives both halves together:
 *  - the add-on: on = Polestar downloaded (its pinned official release, [ThirdPartyAddons.POLESTAR]) and moved into AddOns; off = its
 *    folders moved out to the backup at the next start with the game closed (never deleted, never the game's own add-on list: the
 *    Leave-no-trace ruling); not asked yet = whatever is there stays as it is (the package is fetched ahead, so a Yes is quick);
 *  - Quest tracking: on = "polestar", anything else = "vanilla" ([mode]). SafePath and QuestTrail are no longer choices: a stored
 *    "safepath" / "questtrail" (or any value this build does not know) never reaches the map.
 *
 * The question is asked once per install, on the launcher's bottom screen before Play (SetupFlow's "polestar" prompt); an existing
 * player is asked once too, unless they already used SafePath, QuestTrail or Polestar: they count as Yes ([migrate]). The answer can
 * be changed in Settings > Add-ons (Polestar quest guide On / Off) or Home > Map's Quest tracking (Vanilla / Polestar).
 */
object PolestarGuide {
    const val ON = "on"
    const val OFF = "off"

    /** the quest-tracking mode for a choice: Polestar only while the guide is on (the user, 2026-11-12: the modes are Vanilla and Polestar) */
    fun mode(guide: String?): String = if (guide == ON) "polestar" else "vanilla"

    /** the launcher's prompt shows: not asked yet */
    fun ask(guide: String?): Boolean = guide != ON && guide != OFF

    /** the add-on is wanted in AddOns: true / false; null = not asked yet (what is there stays) */
    fun wanted(guide: String?): Boolean? = when (guide) { ON -> true; OFF -> false; else -> null }

    /**
     * THE ONE-TIME MOVE (2026-11-12, the user: "If anyone used these default them over to the polestar quest tracking"): the choice to
     * store for a player opening this version for the first time, or null = leave it unset (they are asked). [existing] = this device
     * ran an earlier DS Forever ([Settings] had its earlier marks); [stored] = the saved quest tracking (null = never changed: the
     * default, which was SafePath); [guide] = a choice already made (kept).
     *  - a new install: asked;
     *  - SafePath (saved, or the default), QuestTrail, Polestar, or a value this build does not know: on, no question;
     *  - Vanilla: asked.
     */
    fun migrate(existing: Boolean, stored: String?, guide: String?): String? = when {
        guide == ON || guide == OFF -> guide
        !existing -> null
        stored == "vanilla" -> null
        else -> ON
    }

    /** the press on the prompt's Yes / No, the Settings toggle, or Quest tracking's segment: the choice, and the add-on follows it */
    fun set(on: Boolean) {
        val v = if (on) ON else OFF
        if (Settings.polestarGuide != v) Settings.polestarGuide = v
        ThirdPartyAddons.choiceChanged(ThirdPartyAddons.POLESTAR)
    }

    /** the prompt's words (terse: a title and two buttons, the user's terse rule) */
    const val TITLE = "Use the Polestar quest guide?"
}
