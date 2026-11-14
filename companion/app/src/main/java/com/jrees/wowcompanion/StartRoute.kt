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

/**
 * START OVER GAME WINDOWS, THE SETTING (the user's pick, 2026-11-13: "Make it a setting toggle defaulted to our wheel"): Settings >
 * Controller > Pause menu (Start). Our wheel (the default) = PadMap m160: Start stays ours over a game window, Home's wheel opens. Game's
 * wheel = a game window open with no room holding: Start is the game's own radial, Home's wheel does not open (the padmap's start.why
 * "window": StartRoute sends the press to the game). Never both. App-wide (HomeSettings.pauseOverGame); the add-on keeps the value
 * account-wide and says it back (start.over), so the app taps padtab 39 then 32 / 33 (Book b61) whenever a current padmap disagrees -
 * at every connect, after a change, after a /reload - at most every [RESEND_MS]. Pure.
 */
object PauseOver {
    const val ROW_ID = "Start over game windows"
    val SEGMENTS = listOf("Our wheel", "Game's wheel")
    const val PADTAB = "padtab"
    const val PICK = 39
    const val OURS_CODE = 32
    const val GAME_CODE = 33
    const val RESEND_MS = 3_000L

    /** the row's one line under its name */
    fun line(game: Boolean): String = if (game) "A game window open: Start opens the game's wheel." else "A game window open: Start opens Home's wheel."

    /** the "What it does" picture's caption */
    fun caption(game: Boolean): List<String> = if (game) listOf("With a game window open, Start opens", "the game's own wheel on the top screen.")
        else listOf("With a game window open, Start opens", "Home's wheel on the bottom screen.")

    /** the taps that tell the add-on the value */
    fun cells(game: Boolean): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to if (game) GAME_CODE else OURS_CODE)

    /** whether the add-on must be told now: [start] the current padmap's start (null = an add-on before m37: nothing to tell);
     *  [over] its `over` ("game" / null) is the add-on's value only from m161 on ([knows]: the padmap's version says so) */
    fun needsTell(game: Boolean, start: PadMapModel.StartState?, knows: Boolean, lastSentAt: Long, now: Long): Boolean {
        if (start == null || !knows) return false
        if ((start.over == "game") == game) return false
        return lastSentAt == 0L || now - lastSentAt >= RESEND_MS
    }

    /** the padmap's version ("m161") knows the setting */
    fun knows(version: String?): Boolean = (version?.removePrefix("m")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0) >= 161
}
