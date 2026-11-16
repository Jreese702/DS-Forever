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
     * Read straight from the preferences (no Settings.init: nothing else of the start-up moves for it). #384 recovery: saved On after
     * [MAX_FAILS] unsettled starts in a row = Off, saved Off too ([latch])
     */
    fun inUse(ctx: Context): Boolean {
        latched?.let { return it }
        latch(prefs(ctx))?.let { said(ctx, it) }
        return latched == true
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- #384 RECOVERY (the user, 2026-11-16: "make sure there IS a safe recovery path in the app ... if the UI is stuck"). A Swap
    // screens start whose bottom screen never settles counts: the activity kept closing, never came, display 0 kept the controller, a
    // crash or a force stop before [SETTLE_MS]. [MAX_FAILS] of them in a row and the next start is Off, the saved choice too (the row
    // shows Off: the player turns it On again by hand). DsForeverScreen counts ([tryStart]) and clears ([settled]); the start guard's
    // give-up turns it Off at once ([off]); a start with [EXTRA_OFF] (adb) is the by-hand way out.

    /** the count's key: Swap screens starts in a row whose bottom screen never settled */
    const val FAILS_PREF = "swapScreensFails"
    /** this many unsettled starts in a row: the next start is Off */
    const val MAX_FAILS = 2
    /** the bottom screen's activity up this long with the controller at the game: Swap screens works here (the count is cleared) */
    const val SETTLE_MS = 15_000L
    /** a start of the launcher with this extra saves Swap screens Off before the run takes it (DsForeverScreen.moveToTop) */
    const val EXTRA_OFF = "dsforever_swap_off"

    /** pure: Swap screens stays On for a start that comes after [fails] unsettled ones in a row */
    fun keepOn(fails: Int): Boolean = fails < MAX_FAILS

    /** why Swap screens went Off by itself in this run (the problem report's line), null = it did not */
    @Volatile var fellBack: String? = null
        private set

    /** the first ask of a run (tests: their own prefs): latches the choice; non-null = it went Off by itself (why) */
    internal fun latch(p: android.content.SharedPreferences): String? {
        val why = if (p.getBoolean(PREF, false) && !keepOn(p.getInt(FAILS_PREF, 0)))
            "the bottom screen did not settle on the last $MAX_FAILS starts".also { off(p, it) } else null
        latched = p.getBoolean(PREF, false)
        return why
    }

    /**
     * the bottom screen's activity is asked for (once per DS Forever): counted before it comes (commit: a crash or a force stop right
     * after still counts). False = [MAX_FAILS] unsettled already, nothing counted: the caller turns Swap screens Off ([off])
     */
    fun tryStart(ctx: Context): Boolean = tryStart(prefs(ctx))
    internal fun tryStart(p: android.content.SharedPreferences): Boolean {
        val n = p.getInt(FAILS_PREF, 0)
        if (!keepOn(n)) return false
        p.edit().putInt(FAILS_PREF, n + 1).commit()
        return true
    }

    /** the bottom screen settled ([SETTLE_MS] up, the controller at the game): the count starts over */
    fun settled(ctx: Context) = settled(prefs(ctx))
    internal fun settled(p: android.content.SharedPreferences) { if (p.getInt(FAILS_PREF, 0) != 0) p.edit().putInt(FAILS_PREF, 0).apply() }

    /** Swap screens Off by itself, saved (the next start is the Thor's arrangement); this run's arrangement stays latched */
    fun off(ctx: Context, why: String) { off(prefs(ctx), why); said(ctx, why) }
    internal fun off(p: android.content.SharedPreferences, why: String) {
        p.edit().putBoolean(PREF, false).putInt(FAILS_PREF, 0).commit()
        fellBack = why
    }

    /** DS Forever's own log, once per fall-back (before the host exists too: DiagLog.init is the host's same folder) */
    private fun said(ctx: Context, why: String) =
        runCatching { DiagLog.init(ctx); DiagLog.event("app", "Swap screens turned Off: $why") }

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
            (if (offered) "" else " (no second screen)") + "; $last" + (fellBack?.let { "; turned Off: $it" } ?: "")

    /** tests */
    internal fun reset() { latched = null; offered = false; restart = null; gameDisplay = MAIN; last = "not placed yet"; fellBack = null }
}
