package com.jrees.wowcompanion

/**
 * THE START-UP CHOOSER'S RULES (streaming phase 2, S1 "Chooser", 2026-10-23; STREAMING_PLAN.md section 1): the app starts in one of two
 * modes, LOCAL (the game in the container on this device, exactly today's start-up) or STREAM (WoW on the PC, streamed from DS Forever
 * Desktop). Pure: no Android here, the tests drive it ([ModeBridge] holds the live state, [ChooserPanel] draws it).
 *
 * - The normal app icon (no [EXTRA]) ALWAYS opens the chooser (the user's ruling 2026-10-23: "any time the base app is opened it
 *   should show the full menu"). Nothing is remembered: a mode used before never changes what the icon opens.
 * - Only a home-screen shortcut's intent ([EXTRA] = "local" / "stream") skips the chooser, straight into its mode.
 * - B or the swirl on a mode's start screen brings the chooser back.
 * - The required installs depend on the mode ([needs]): Streaming never asks for the local game folder or the game art.
 */
object StartMode {
    /** the shortcut intent's extra (its value: [Mode.id]) */
    const val EXTRA = "dsf.mode"

    enum class Mode(val id: String, val title: String) { LOCAL("local", "Local"), STREAM("stream", "Streaming") }

    /** how a mode was reached: a home-screen shortcut, or picked on the chooser */
    enum class By { SHORTCUT, PICKED }

    sealed class Route {
        /** the two big options on the bottom screen */
        object Chooser : Route() { override fun toString() = "chooser" }
        data class Into(val mode: Mode, val by: By) : Route() { override fun toString() = "${mode.id} (${by.name.lowercase()})" }
    }

    /** "local" / "stream" (any case, spaces round it) -> the mode; anything else (null, "", an old or foreign value) -> null */
    fun parse(s: String?): Mode? {
        val v = s?.trim()?.lowercase() ?: return null
        return Mode.values().firstOrNull { it.id == v }
    }

    /** the route of a fresh open: a shortcut's extra = its mode; the normal icon (no / an unknown extra) = the chooser, every time */
    fun resolve(shortcutExtra: String?): Route =
        parse(shortcutExtra)?.let { Route.Into(it, By.SHORTCUT) } ?: Route.Chooser

    /**
     * The launcher's activity was created ([current] the route this process already had, null = none yet). [recreated] = Android
     * rebuilt the SAME activity (a configuration change: it has a saved state; or our own move to the top screen), which keeps its
     * mode. A new open: a shortcut's extra goes through [onShortcut]'s guards when a route exists; no extra = the chooser - unless the
     * game runs, a launch is busy or a restart is under way (never taken away by an icon tap).
     */
    fun onCreate(current: Route?, extra: String?, recreated: Boolean, gameUp: Boolean, restarting: Boolean, busy: Boolean = false): Route? {
        if (current == null) return resolve(extra)
        if (parse(extra) != null) return onShortcut(current, extra, gameUp, restarting, busy)
        if (recreated || gameUp || restarting || busy) return null
        return if (current == Route.Chooser) null else Route.Chooser
    }

    /**
     * A shortcut tapped while the app already runs ([current] the route now): it moves to its mode, unless the game runs, a launch /
     * update is busy or a restart is under way (a running game is never taken away by a home-screen tap), or the app is already in
     * that mode. null = stay.
     */
    fun onShortcut(current: Route?, shortcutExtra: String?, gameUp: Boolean, restarting: Boolean, busy: Boolean = false): Route? {
        val m = parse(shortcutExtra) ?: return null
        if (gameUp || restarting || busy) return null
        if (current is Route.Into && current.mode == m) return null
        return Route.Into(m, By.SHORTCUT)
    }

    /**
     * The launcher's "Booting into World of Warcraft..." splash at a cold start: TODAY'S rule (auto-launch on and the game folder ready)
     * and only for a SHORTCUT start into LOCAL (the coordinator, 2026-10-23). The normal icon opens the chooser, Streaming boots
     * nothing: no splash.
     */
    fun splashAtStart(autoLaunch: Boolean, gameReady: Boolean, route: Route?): Boolean =
        autoLaunch && gameReady && route == Route.Into(Mode.LOCAL, By.SHORTCUT)

    /** the Local start screen (WoWForeverScreen, unchanged) is shown for this route */
    fun showsLocal(route: Route?): Boolean = (route as? Route.Into)?.mode == Mode.LOCAL

    /**
     * The start-up checks run (WoWForeverScreen is composed) on this route: Local, and the chooser too (the user, 2026-10-28: the app's
     * loading must finish before the choice, Streaming included). Its launch waits for Local ([showsLocal]).
     */
    fun runsChecks(route: Route?): Boolean = route == Route.Chooser || showsLocal(route)

    /**
     * B (or the swirl) on a mode's start screen goes back to the chooser: never while the game runs, a launch / update is busy, a
     * restart is under way, or our login form is open (its own B closes it).
     */
    fun canGoBack(route: Route?, gameUp: Boolean, busy: Boolean, restarting: Boolean, loginFormOpen: Boolean): Boolean =
        route is Route.Into && !gameUp && !busy && !restarting && !loginFormOpen

    // ------------------------------------------------------------------ the required installs, per mode

    enum class Need { STORAGE, GAME_FOLDER, GAME_ART, ADDONS, PC_LINK }

    /**
     * What a mode needs before it can play. LOCAL = today's start-up checks (SetupFlow: file access, the game folder, the art, our
     * add-ons in the game folder); STREAM = the PC link only (S2's pairing: the code shown in DS Forever Desktop). The chooser itself
     * comes after the start-up checks (2026-10-28, [runsChecks]; [SetupFlow.checksPass]).
     */
    fun needs(mode: Mode): List<Need> = when (mode) {
        Mode.LOCAL -> listOf(Need.STORAGE, Need.GAME_FOLDER, Need.GAME_ART, Need.ADDONS)
        Mode.STREAM -> listOf(Need.PC_LINK)
    }

    /** the local game folder / art checks (SetupFlow's) run for this mode */
    fun localChecks(mode: Mode): Boolean = Need.GAME_FOLDER in needs(mode)

    // ------------------------------------------------------------------ home-screen shortcuts

    data class Shortcut(val id: String, val shortLabel: String, val longLabel: String, val extra: String)

    /** the pinned shortcut of [mode] (one id per mode: pinning it again replaces it, never a second copy) */
    fun shortcut(mode: Mode): Shortcut = when (mode) {
        Mode.LOCAL -> Shortcut("dsf-mode-local", "DSF Local", "DS Forever: Local", mode.id)
        Mode.STREAM -> Shortcut("dsf-mode-stream", "DSF Stream", "DS Forever: Streaming", mode.id)
    }

    /**
     * THE CHOOSER'S OWN SHORTCUT (the user 2026-11-01): Cocoon force-stops the plain app entry when it is reopened, its Local and Stream
     * shortcuts it does not (tested on the Thor). This one opens the chooser: its extra names no mode, so [resolve] gives the chooser.
     */
    val MAIN_SHORTCUT = Shortcut("dsf-main", "DS Forever", "DS Forever", "chooser")

    // ------------------------------------------------------------------ the chooser's cursor

    enum class Dir { UP, DOWN, LEFT, RIGHT }
    enum class Row { CARD, SHORTCUT, MAIN }

    /** a place of the gold box: the column is the mode (Local left, Streaming right), the row the big card or its shortcut button */
    data class Cell(val mode: Mode, val row: Row)

    /** where the box starts: on Local's card, every time (nothing is remembered) */
    fun startCell(): Cell = Cell(Mode.LOCAL, Row.CARD)

    /** the D-pad: left / right between the two columns (same row), up / down between a card and its shortcut button; no wrap */
    fun move(c: Cell, d: Dir): Cell = when (d) {
        Dir.LEFT -> if (c.row == Row.MAIN) c else c.copy(mode = Mode.LOCAL)
        Dir.RIGHT -> if (c.row == Row.MAIN) c else c.copy(mode = Mode.STREAM)
        Dir.UP -> c.copy(row = if (c.row == Row.MAIN) Row.SHORTCUT else Row.CARD)
        Dir.DOWN -> if (c.row == Row.CARD) c.copy(row = Row.SHORTCUT) else Cell(Mode.LOCAL, Row.MAIN)
    }

    sealed class Press {
        data class Pick(val mode: Mode) : Press()
        data class AddShortcut(val mode: Mode) : Press()
        object AddMain : Press()
    }

    /** A on the box: a card enters its mode, a shortcut button asks the home screen to pin that mode's shortcut */
    fun press(c: Cell): Press = when (c.row) {
        Row.CARD -> Press.Pick(c.mode)
        Row.SHORTCUT -> Press.AddShortcut(c.mode)
        Row.MAIN -> Press.AddMain
    }
}
