package com.jrees.wowcompanion

import android.content.Context

/**
 * SWAP SCREENS (GitHub #232, a player: "Add an option to swap the main and touchscreen displays", for devices whose controls sit next
 * to the other screen; the user, 2026-11-14: build it, untested - there is no such device here). Settings > General > Look > Swap
 * screens, Off (the default) / On: On puts the game (the launcher's activity) on the SECOND screen and DS Forever on the main one
 * (display 0). The Thor's arrangement (Off) is unchanged: the game on display 0, DS Forever's Presentation on the bottom screen
 * (DsForeverScreen.pickScreen, the #196 rule: a TV never takes it).
 *
 * The choice applies from a start of DS Forever: [inUse] latches the saved choice for this run at its first ask (the launcher's first
 * start / resume), so a change never moves a running game between screens. A change shows Restart under the row ([pending]); its
 * button is [restart], the launcher started again on the other screen (DsForeverScreen), never while the game runs.
 *
 * This object is the shared state only (the companion's views read it); the launcher's display choice is DsForeverScreen's
 * (gameScreen / bottomScreen, tested off-device).
 */
object ScreenSwap {
    /** the saved choice's key in Settings' preferences (Settings.swapScreens) */
    const val PREF = "swapScreens"

    /** android.view.Display.DEFAULT_DISPLAY: the main screen */
    const val MAIN = 0

    /** the launcher saw a second screen (the row shows only then: a single-screen device never sees it) */
    @Volatile var offered = false

    @Volatile private var latched: Boolean? = null

    /**
     * the arrangement THIS run uses: the saved choice at the first ask, then the same until [restart] (a change waits for a start).
     * Read straight from the preferences (no Settings.init: nothing else of the start-up moves for it)
     */
    fun inUse(ctx: Context): Boolean = latched ?: ctx.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, false).also { latched = it }

    /** the arrangement in use, null = not asked yet (no launcher has started) */
    val inUseNow: Boolean? get() = latched

    /** the saved choice becomes this run's (the restart, just before the launcher starts again) */
    fun take(saved: Boolean) { latched = saved }

    /** the restart that applies the saved choice (the launcher's DsForeverScreen sets it); null = none here */
    @Volatile var restart: (() -> Unit)? = null

    /** the display the game is on while Swap screens is in use (Streaming and the report's browser open there); [MAIN] otherwise */
    @Volatile var gameDisplay = MAIN

    /** what the row shows under itself */
    enum class Pending { NONE, RESTART, AFTER_GAME }

    /**
     * the Restart row (pure, for the tests): nothing when the saved choice is the one in use (or no launcher); Restart with no game;
     * with the game up only "after the game closes" (a restart would end it)
     */
    fun pending(saved: Boolean, inUse: Boolean?, gameRunning: Boolean, canRestart: Boolean): Pending = when {
        inUse == null || saved == inUse || !canRestart -> Pending.NONE
        gameRunning -> Pending.AFTER_GAME
        else -> Pending.RESTART
    }

    /** the last placement DsForeverScreen made, for the problem report (the display ids, what Android offered) */
    @Volatile var last = "not placed yet"
        private set

    /** a new placement: kept for the report; true when it differs from the one before (the caller logs it once) */
    fun note(placement: String): Boolean {
        if (placement == last) return false
        last = placement
        return true
    }

    /** the placement words (pure): "game on display 0, DS Forever on display 4 (presentation); presentation displays 4:in:Built-in" */
    fun placement(game: Int, bottom: Int?, asActivity: Boolean, seen: String): String =
        "game on display $game, DS Forever " + (if (bottom == null) "on no screen" else "on display $bottom (${if (asActivity) "activity" else "presentation"})") +
            "; presentation displays ${seen.ifEmpty { "none" }}"

    /** the problem report's line */
    fun line(saved: Boolean): String =
        "Swap screens: saved ${if (saved) "on" else "off"}, in use ${when (latched) { null -> "-"; true -> "on"; false -> "off" }}" +
            (if (offered) "" else " (no second screen)") + "; $last"

    /** tests */
    internal fun reset() { latched = null; offered = false; restart = null; gameDisplay = MAIN; last = "not placed yet" }
}
