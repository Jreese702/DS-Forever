package com.jrees.wowcompanion

/**
 * NO HEAVY COMPANION WORK WHILE THE GAME RUNS, ON ANY THOR (issues #108 #109 #100 #92 #128 #98 #85 #104 #86 #94 #80 #82, 2026-11-11).
 * The crash reports' WCMEM lines: on the 16 GB Thor Max (lighter mode off) the indoor-map bake opened the game files in the world
 * (#108: CascNative.open +630 MB native in 5 s, killed the same second) and baked beside a 3.5-4 GB game (#100, #128: app native
 * 500-1100 MB); the background art ran beside it too (#94 safepathzones, java 250-415 MB; #86 / #104 allicons ~550 MB). Only the 8 / 12 GB
 * models ("lighter mode") held them back. Now every tier does: the background art and the indoor maps wait OUTSIDE the game files while
 * the game is up or being launched, and go on once it has closed. The art before Play is untouched (Play waits for it, so the game is
 * never up while it runs). Pure: the off-device tests drive it (PlayGateTest).
 */
internal object PlayGate {
    /** a launch that never brought the game up (it hung, or the launcher went away) stops counting after this */
    const val LAUNCH_WINDOW_MS = 5 * 60_000L

    /**
     * The game is up, or a launch is under way (the container preparing / booting: the game's processes already grow, #104 measured
     * 1.8 GB before the game screen was up). [launchState] / [launchAtMs]: the launcher's last launch step and when it came;
     * [gameStartSec]: when the game screen last came up, in whole seconds (a launch not older than that is over: the screen
     * comes up within the same second as the last step).
     */
    fun gameActive(gameUp: Boolean, launchState: String, launchAtMs: Long, gameStartSec: Long, nowMs: Long): Boolean =
        gameUp || launching(launchState, launchAtMs, gameStartSec, nowMs)

    fun launching(launchState: String, launchAtMs: Long, gameStartSec: Long, nowMs: Long): Boolean =
        launchAtMs > 0L && (launchState.startsWith("preparing") || launchState.startsWith("booting")) &&
            gameStartSec < launchAtMs / 1000L && nowMs - launchAtMs in 0L until LAUNCH_WINDOW_MS

    /** the same, from the launcher's live hooks */
    fun gameActiveNow(): Boolean = gameActive(LauncherHooks.gameUp(), LauncherHooks.launchState, LauncherHooks.launchAtMs,
        LauncherHooks.gameStartSec, System.currentTimeMillis())

    /** a background art step running now leaves the run (the game files close) and the worker waits outside them */
    fun artMustPause(backgroundPhase: Boolean, gameActive: Boolean): Boolean = backgroundPhase && gameActive

    /**
     * A new art run (or the next pass of the worker) while the game is up: it does not open the game files at all (a required step
     * asked for in game, e.g. a new icon, is built once the game has closed, not beside it).
     */
    fun artMayOpen(gameActive: Boolean): Boolean = !gameActive

    /**
     * Why the indoor maps wait, null = they may build. [streaming]: the game runs on the PC (the Thor only shows video): it builds,
     * after an art run. Otherwise never while the game is up or launching; and (the user 2026-11-07: "sluggish ... before I ever press
     * update") not on DS Forever's own screens before the first game of this run: it builds once a game has closed ([playedThisRun]).
     */
    fun indoorWait(streaming: Boolean, artRunning: Boolean, gameActive: Boolean, playedThisRun: Boolean): String? = when {
        streaming -> if (artRunning) GAME_BUSY else null
        gameActive -> GAME_RUNNING
        !playedThisRun -> GAME_BUSY
        artRunning -> GAME_BUSY
        else -> null
    }

    const val GAME_RUNNING = "game running"
    const val GAME_BUSY = "game busy"
}
