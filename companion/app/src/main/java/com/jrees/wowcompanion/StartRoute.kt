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
 *
 * ISSUE 588 (the user's ruling 2026-11-23, reversing 2026-10-08's Q7 "Follow toolbar switch"): with the DS WoW toolbar OFF Start opens
 * Home's wheel too, by the same rules as with it on (the Pause menu (Start) settings: Over windows = Game's wheel keeps a game window's
 * Start the game's). The add-on decides it (PadMap m186 binds Start on or off: its padmap's start.bound); an OLDER add-on set (a Desktop
 * that streams the PC's add-ons and was not updated) still leaves Start to the game with the toolbar off (start.why "toolbar"), so the
 * press stays the game's then - never both wheels.
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
        // an add-on before m37 (no start state) bound Start only with the toolbar on
        if (start == null) return if (toolbarWant == true) Route(Kind.WHEEL, "an add-on without the start state")
            else Route(Kind.GAME, "the DS WoW toolbar is off and the add-on has no start state: the game's own wheel")
        if (start.bound != null) return Route(Kind.WHEEL, "Start is the router's (${start.bound})")
        // issue 588: only an add-on before m186 says "toolbar" (it leaves Start to the game with the toolbar off; a guest was told so)
        if (start.why == "toolbar") return Route(Kind.GAME, "the DS WoW toolbar is off and these add-ons are older (before m186): the game's own wheel")
        // 1521 audit: an add-on before m160 sends no `why` and never binds Start with the toolbar off (a room's guest was told 35, Start to
        // the game): the game's, as up to 1.0.38. m160+ always says why when Start is not bound (m186 "guest" for a guest), so no change there
        if (toolbarWant != true && start.why == null) return Route(Kind.GAME, "the DS WoW toolbar is off and these add-ons are older (before m160, no start.why): the game's own wheel")
        if (start.why == "guest" || capHolder.startsWith("pad:")) return Route(Kind.WHEEL, "a guest holds Start (${capHolder.ifEmpty { "guest" }})")
        return Route(Kind.GAME, "Start is the game's now (${start.why ?: "not bound"}${start.aside?.let { ", aside $it" } ?: ""})")
    }
}

/** issue 588: whether this padmap's add-on (its version, "m186") binds Start with the DS WoW toolbar off; then the rooms' guests keep Start
 *  (Quests q35: told 36) whatever the toolbar, else they follow the toolbar (35 while it is off). Pure. */
fun startFreeOfToolbar(version: String?): Boolean = (version?.removePrefix("m")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0) >= 186

/** what the rooms' guests are told for Start (true = our wheel, qact 36; false = the game, 35); an older add-on whose padmap never said the toolbar's state
 *  (null) is told 35, as with it off */
fun startToRooms(toolbarWant: Boolean?, version: String?): Boolean = toolbarWant == true || startFreeOfToolbar(version)

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
