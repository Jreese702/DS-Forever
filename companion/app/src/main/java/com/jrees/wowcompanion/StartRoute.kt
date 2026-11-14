package com.jrees.wowcompanion

/**
 * WHERE ONE START PRESS GOES (the user relaying a player, 2026-11-13: "the pause radial is appearing on the top and bottom. They have some
 * ui elements showing on the top and I think that the problem is linked"). The app reads Start itself (DirectPad) and opened Home's pause
 * wheel whenever the LAST padmap said the DS WoW toolbar was on - also a padmap REPLAYED from the log's backlog, and also while the
 * router did not hold Start. The game's Start is OPENRADIAL in its Core binding set (always live), so each of those presses opened the
 * game's own radial on the top screen too:
 *  - the add-ons silent (not loaded, a Lua error, a set that never installed): nothing holds Start and nothing hides the game's frames
 *    (the "ui elements showing on the top"), while a replayed padmap still says "toolbar on";
 *  - a game window open with nobody holding (PadMap m159 left Start to the game then; m160 holds it there too).
 * Now the wheel opens only while the add-ons speak in this game run and its own padmap says Start is the router's (or a guest holds it,
 * q35); otherwise the press is the game's alone, and with the add-ons silent past [NOTICE_AFTER_MS] Home says so (the add-ons notice). Pure.
 */
object StartRoute {
    enum class Kind { WHEEL, GAME, NOTICE }
    data class Route(val kind: Kind, val why: String)

    /** the add-ons may still be loading before this (the game's start, character select): no notice, the press is the game's */
    const val NOTICE_AFTER_MS = AddonSilence.AFTER_MS

    /**
     * [toolbarWant] the padmap's dpad.want (null = never told); [docsLive] a CURRENT document of any of our add-ons came in this game run;
     * [runPadmap] a padmap OF THIS GAME RUN came (current, or replayed from this run's own backlog: a host that came up mid-game);
     * [start] that padmap's `start` (null = an add-on before m37); [capHolder] the capture's holder ("pad:<guest>" = a guest that binds
     * Start itself); [gameUpMs] how long the game has run (0 = unknown); [spellsOff] the login probe named WowCompanionSpells NOT loaded.
     */
    fun decide(toolbarWant: Boolean?, docsLive: Boolean, runPadmap: Boolean, start: PadMapModel.StartState?, capHolder: String,
               gameUpMs: Long, spellsOff: Boolean = false): Route {
        if (!docsLive) {
            return if (gameUpMs >= NOTICE_AFTER_MS) Route(Kind.NOTICE, "no current document ${GameLife.minutes(gameUpMs)} into the game: the add-ons are silent")
            else Route(Kind.GAME, "no current document yet (the add-ons not heard from)")
        }
        // the others speak but Spells' router never did this run (its add-on off, or a Lua error in it): nothing holds Start
        // issue #278: the login probe said so ("WowCompanionSpells:DISABLED"): Home says which add-on is off
        if (!runPadmap) return if (spellsOff) Route(Kind.NOTICE, "DS Forever: Spells is not loaded (the login probe)")
            else Route(Kind.GAME, "no padmap this game run (Spells' router not heard from)")
        if (toolbarWant != true) return Route(Kind.GAME, "the DS WoW toolbar is off: the game's own wheel")
        if (start == null) return Route(Kind.WHEEL, "an add-on without the start state")
        if (start.bound != null) return Route(Kind.WHEEL, "Start is the router's (${start.bound})")
        if (start.why == "guest" || capHolder.startsWith("pad:")) return Route(Kind.WHEEL, "a guest holds Start (${capHolder.ifEmpty { "guest" }})")
        return Route(Kind.GAME, "Start is the game's now (${start.why ?: "not bound"}${start.aside?.let { ", aside $it" } ?: ""})")
    }
}
