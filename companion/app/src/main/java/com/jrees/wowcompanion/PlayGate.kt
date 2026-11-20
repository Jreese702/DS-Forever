package com.jrees.wowcompanion

/**
 * NO HEAVY COMPANION WORK WHILE THE GAME RUNS, ON ANY THOR (issues #108 #109 #100 #92 #128 #98 #85 #104 #86 #94 #80 #82, 2026-11-11).
 * The crash reports' WCMEM lines: on the 16 GB Thor Max (lighter mode off) the indoor-map bake opened the game files in the world
 * (#108: CascNative.open +630 MB native in 5 s, killed the same second) and baked beside a 3.5-4 GB game (#100, #128: app native
 * 500-1100 MB); the background art ran beside it too (#94 safepathzones, java 250-415 MB; #86 / #104 allicons ~550 MB). Only the 8 / 12 GB
 * models ("lighter mode") held them back. Now every tier does: the background art and the indoor maps wait OUTSIDE the game files while
 * the game is up or being launched, and go on once it has closed. Test build 1489 (#361, 2026-11-18): the art before Play too (a
 * required icon asked for in game ran beside the game), and Play holds every step from its press ([held]). Pure: the off-device tests
 * drive it (PlayGateTest, ArtStopAtPlayTest).
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
    fun gameActive(gameUp: Boolean, launchState: String, launchAtMs: Long, gameStartSec: Long, nowMs: Long, holdAtMs: Long = 0L): Boolean =
        gameUp || launching(launchState, launchAtMs, gameStartSec, nowMs) || held(holdAtMs, launchAtMs, gameStartSec, nowMs)

    /**
     * PLAY HOLDS THE ART (test build 1489, the user's approval 2026-11-18, #361 / #56: "stop ALL art work hard at Play"): from the press
     * ([LauncherHooks.holdForPlay], once the art before Play is done) until the launch's first step takes over ([launching], or its
     * failure ends it), or the game screen came up ([gameStartSec]: then gameUp decides); never past [LAUNCH_WINDOW_MS]. It bridges the
     * seconds between Play and "preparing the container" (the add-ons, the build info), when the background art used to go on.
     */
    fun held(holdAtMs: Long, launchAtMs: Long, gameStartSec: Long, nowMs: Long): Boolean =
        holdAtMs > 0L && launchAtMs < holdAtMs && gameStartSec < holdAtMs / 1000L && nowMs - holdAtMs in 0L until LAUNCH_WINDOW_MS

    fun launching(launchState: String, launchAtMs: Long, gameStartSec: Long, nowMs: Long): Boolean =
        launchAtMs > 0L && (launchState.startsWith("preparing") || launchState.startsWith("booting")) &&
            gameStartSec < launchAtMs / 1000L && nowMs - launchAtMs in 0L until LAUNCH_WINDOW_MS

    /**
     * The same, from the launcher's live hooks. 2026-11-11 (review of this fix): NOT while streaming - the stream's attach sets the
     * launcher's "game up" for the whole stream, but the game runs on the PC and the Thor only shows video, so the background art and
     * the indoor maps go on then (as before on the Thor Max; 1.0.22's "indoor maps stuck" while streaming).
     */
    fun gameActiveNow(): Boolean = localGameActive(streamingNow(), LauncherHooks.gameUp(), LauncherHooks.launchState,
        LauncherHooks.launchAtMs, LauncherHooks.gameStartSec, System.currentTimeMillis(), LauncherHooks.playHoldAtMs)

    /** [gameActive] for the game on THIS device: false while [streaming] (the game is on the PC) */
    fun localGameActive(streaming: Boolean, gameUp: Boolean, launchState: String, launchAtMs: Long, gameStartSec: Long, nowMs: Long,
                        holdAtMs: Long = 0L): Boolean =
        !streaming && gameActive(gameUp, launchState, launchAtMs, gameStartSec, nowMs, holdAtMs)

    /** the companion's taps go to the stream (StreamKeys): the game runs on the PC */
    fun streamingNow(): Boolean = (InputSink.sink as? TapSink)?.route == "stream"

    /**
     * An art step running now leaves the run (the game files close) and the worker waits outside them. [onWorker]: only on the
     * harvest's own worker thread (review of this fix, 2026-11-11: a step's progress report also comes from GameFiles.prefetch's
     * "art-fetch" pool threads, where an uncaught throw ends the process - #138; the worker's next report pauses it instead).
     * Test build 1489 (the user's approval 2026-11-18, #361: "art icons" - a before-Play step - built beside a game that was up, native
     * heap 582 MB, until Android killed both): EVERY step, the art before Play too. Play's own wait for that art is never held: Play
     * ends any old launch window first (LauncherHooks.endLaunchWindow) and holds the art only once its wait is over.
     */
    fun artMustPause(gameActive: Boolean, onWorker: Boolean = true, beside: Boolean = false): Boolean =
        gameActive && onWorker && !beside

    // ------------------------------------------------------------------ the world map beside the game, on the Thor Max only

    /**
     * THE WORLD MAP BUILDS BESIDE THE GAME ON THE 16 GB THOR MAX (the user's pick, 2026-11-16, issues #316 #317 #284: "Let ONLY the
     * world-map art step build beside the running game on the 16 GB Thor Max (DeviceTier / RAM >= ~15 GB); every other background art
     * step keeps pausing while the game runs; keep the memory relief and the pause points; log it"). #316's player pressed Play 2.4 s
     * after the app opened, so the background art never had its game-closed minutes. Now [BESIDE_STEP] alone may build with the game
     * up, on [DeviceTier.besideModel] alone; every other step, and every other model, pauses as before.
     *
     * The pause points stay: the world map too lets go of the game files (with the art's relief, ArtHarvest's PausedForGame) at the
     * launch and at each loading screen - the moments the game takes its gigabytes (1.0.32's relief points) - and opens them again
     * only once the game has been in the world, with no loading screen, for [BESIDE_CALM_MS]; never again in this game once Android
     * warned running-low or worse ([MemWatch.pressureSince]). Review of this pick: a logout to the character list or a /reload (Hub's
     * "bye") is a pause point too, the next world's load unseen by the add-ons: no calm until their next hello (GameLife.worldLeft).
     */
    const val BESIDE_STEP = "worldmap"
    /** after the world entry or a loading screen's end (the 1.0.29 - 1.0.31 kills came 7 - 36 s after the world entry) */
    const val BESIDE_CALM_MS = 60_000L

    /**
     * May [step] build with the game up? [model]: [DeviceTier.besideModel]; [inWorld]: the game is in the world and running;
     * [loading]: a loading screen is up; [calmMs]: since the world entry or the last loading screen's end; [pressure]: Android warned
     * running-low or worse in this game. Pure (the tests).
     */
    fun mayBuildBeside(step: String?, model: Boolean, inWorld: Boolean, loading: Boolean, calmMs: Long, pressure: Boolean): Boolean =
        step == BESIDE_STEP && model && inWorld && !loading && calmMs >= BESIDE_CALM_MS && !pressure

    /** the same from the live state; cheap (a step's every report and stop asks it) */
    fun besideNow(step: String?): Boolean = step == BESIDE_STEP && mayBuildBeside(step, DeviceTier.besideModel(), GameLife.worldNow,
        GameLife.loadingNow, GameLife.calmMs(System.currentTimeMillis()), MemWatch.pressureSince(GameLife.startedAtMs))

    /**
     * A new art run (or the next pass of the worker) while the game is up, launching or held by Play ([gameActiveNow]): it does not
     * open the game files at all (a required step asked for in game, e.g. a new icon, is built once the game has closed, not beside
     * it). Test build 1489 (#361): the launch window holds back the art before Play too. 2026-11-11's worry (a launch that reached
     * "booting" and never came up, then Play again, would sit at "Preparing your game art...") is met by Play itself: it ends any old
     * window (LauncherHooks.endLaunchWindow) before its wait, as does the launcher's screen back in front.
     */
    fun artMayOpen(gameActive: Boolean): Boolean = !gameActive

    /** the launch window right now (false while streaming) */
    fun launchingNow(): Boolean = !streamingNow() && launching(LauncherHooks.launchState, LauncherHooks.launchAtMs,
        LauncherHooks.gameStartSec, System.currentTimeMillis())

    /**
     * Why the indoor maps wait, null = they may build. [streaming]: the game runs on the PC (the Thor only shows video): it builds,
     * after an art run. Otherwise never while the game is up or launching; and (the user 2026-11-07: "sluggish ... before I ever press
     * update") not on DS Forever's own screens before the first game of this run: it builds once a game has closed ([playedThisRun]).
     * [quiet]: review of this fix, 2026-11-11 - after a game, DS Forever's launcher screen is the same screen the user called sluggish
     * (interiors-bake 74 % CPU there), so it builds only while nobody looks at it: the screen off, or the launcher not in front.
     * [artRunning] = an art run that is really working (one parked for the game does not count).
     */
    fun indoorWait(streaming: Boolean, artRunning: Boolean, gameActive: Boolean, playedThisRun: Boolean, quiet: Boolean = true): String? = when {
        streaming -> if (artRunning) ART_FIRST else null
        gameActive -> GAME_RUNNING
        !playedThisRun -> AFTER_PLAY
        !quiet -> IN_BACKGROUND
        artRunning -> ART_FIRST
        else -> null
    }

    /*
     * #568 / #573 (2026-11-20): every wait used to read "game busy", also on the Ready screen with the game closed. Each reason now
     * says what it waits for (Settings' row, the start checks' row and the log line show it as it is; terse, the user's page rule).
     */
    /** the game is up, launching or held by Play: nothing is open, nothing builds */
    const val GAME_RUNNING = "waiting: game running"
    /** no game yet in this run (the 2026-11-07 ruling: not on DS Forever's own screens before Play) */
    const val AFTER_PLAY = "builds after you play"
    /** after a game, DS Forever's launcher screen is in front (the 2026-11-11 review): it builds once minimized or the screen is off */
    const val IN_BACKGROUND = "builds when minimized or screen off"
    /** an art run that really works goes first */
    const val ART_FIRST = "waiting: game art"
    /** a [IndoorScheduler.Signals] that only says busy, no reason (the add-on's fight / loading word; the off-device fakes) */
    const val GAME_BUSY = "waiting: game busy"
    const val PLAYER_MOVING = "waiting: player moving"
    const val DEVICE_WARM = "waiting: device warm"
}
