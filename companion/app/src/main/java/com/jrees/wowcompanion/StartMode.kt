package com.jrees.wowcompanion

/**
 * THE START-UP CHOOSER'S RULES (streaming phase 2, S1 "Chooser", 2026-10-23; STREAMING_PLAN.md section 1): the app starts in one of two
 * modes, LOCAL (the game in the container on this device, exactly today's start-up) or STREAM (WoW on the PC, streamed from DS Forever
 * Desktop). Pure: no Android here, the tests drive it ([ModeBridge] holds the live state, [ChooserPanel] draws it).
 *
 * - A home-screen shortcut's intent carries [EXTRA] = "local" / "stream": that launch skips the chooser.
 * - Otherwise the remembered pick (the last mode entered) goes straight into its mode; B or the swirl on a mode's start screen brings
 *   the chooser back.
 * - No shortcut and nothing remembered (a fresh install, or the first start after the update that brought the chooser): the chooser.
 * - The required installs depend on the mode ([needs]): Streaming never asks for the local game folder or the game art.
 */
object StartMode {
    /** the shortcut intent's extra (its value: [Mode.id]) */
    const val EXTRA = "dsf.mode"
    const val PREFS = "dsf_start"
    const val KEY_LAST = "last_mode"

    enum class Mode(val id: String, val title: String) { LOCAL("local", "Local"), STREAM("stream", "Streaming") }

    /** how a mode was reached (the log, and what is remembered) */
    enum class By { SHORTCUT, REMEMBERED, PICKED }

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

    /** the route of a fresh start: the shortcut's extra first, then the remembered pick, else the chooser */
    fun resolve(shortcutExtra: String?, remembered: String?): Route {
        parse(shortcutExtra)?.let { return Route.Into(it, By.SHORTCUT) }
        parse(remembered)?.let { return Route.Into(it, By.REMEMBERED) }
        return Route.Chooser
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

    /** what to remember after entering [r] (null = keep what is remembered: the chooser itself never forgets the last pick) */
    fun remember(r: Route): String? = (r as? Route.Into)?.takeIf { it.by != By.REMEMBERED }?.mode?.id

    /**
     * The launcher's "Booting into World of Warcraft..." splash at a cold start: TODAY'S rule (auto-launch on and the game folder ready)
     * and only when the start goes straight into LOCAL. With the chooser up (or Streaming) there is nothing to boot. [route] null = the
     * mode was never started (no launcher onCreate ran ModeBridge.start): today's behaviour, as Local.
     */
    fun splashAtStart(autoLaunch: Boolean, gameReady: Boolean, route: Route?): Boolean =
        autoLaunch && gameReady && (route == null || showsLocal(route))

    /** the Local start screen (WoWForeverScreen, unchanged) is shown for this route */
    fun showsLocal(route: Route?): Boolean = (route as? Route.Into)?.mode == Mode.LOCAL

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
     * comes first and needs nothing, so a player with no local game folder can still pick Streaming.
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

    // ------------------------------------------------------------------ the chooser's cursor

    enum class Dir { UP, DOWN, LEFT, RIGHT }
    enum class Row { CARD, SHORTCUT }

    /** a place of the gold box: the column is the mode (Local left, Streaming right), the row the big card or its shortcut button */
    data class Cell(val mode: Mode, val row: Row)

    /** where the box starts: on the remembered pick's card, else on Local's */
    fun startCell(remembered: String?): Cell = Cell(parse(remembered) ?: Mode.LOCAL, Row.CARD)

    /** the D-pad: left / right between the two columns (same row), up / down between a card and its shortcut button; no wrap */
    fun move(c: Cell, d: Dir): Cell = when (d) {
        Dir.LEFT -> c.copy(mode = Mode.LOCAL)
        Dir.RIGHT -> c.copy(mode = Mode.STREAM)
        Dir.UP -> c.copy(row = Row.CARD)
        Dir.DOWN -> c.copy(row = Row.SHORTCUT)
    }

    sealed class Press {
        data class Pick(val mode: Mode) : Press()
        data class AddShortcut(val mode: Mode) : Press()
    }

    /** A on the box: a card enters its mode, a shortcut button asks the home screen to pin that mode's shortcut */
    fun press(c: Cell): Press = if (c.row == Row.CARD) Press.Pick(c.mode) else Press.AddShortcut(c.mode)
}
