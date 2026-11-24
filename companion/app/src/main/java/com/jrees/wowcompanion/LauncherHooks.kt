package com.jrees.wowcompanion

/**
 * WHAT THE PRIVATE LAUNCHER TELLS DS FOREVER ABOUT ITS OWN GAME (the rooms refactor S4, 2026-10-08). Inside "DS Forever Launcher" there
 * is no root, so the sign-in watcher cannot list processes or read /proc/net; the launcher knows its own game instead. Set only by the
 * launcher's glue; in the app [pkg] stays null and nothing here is read.
 */
object LauncherHooks {
    /** the launcher's own package (the login watcher's entry for it), null in the app */
    @Volatile var pkg: String? = null
    /** the launcher's game screen is up (its Wine session runs) */
    @Volatile var gameUp: () -> Boolean = { false }
    /** when that game screen came up (epoch seconds): a new value = a new client run */
    @Volatile var gameStartSec = 0L
    /** the game's flavour folder (`_forever_` / `_classic_beta_` under the picked game folder), null = no game folder picked yet:
     *  Settings > Add-ons > Import installs into its Interface/AddOns ([ImportedAddons]) */
    @Volatile var flavorRoot: () -> java.io.File? = { null }
    /** the game's screen is the one in front on the top display (the sign-in types only then) */
    @Volatile var inFront: () -> Boolean = { false }
    /** A CLEAN EXIT FIRST (the user's ruling after test20, "Both"; [CleanExit]): the launcher calls it before it ends the game itself;
     *  [done] runs on the main thread once the game went by itself (false) or the old close is needed (true). Null = no companion */
    @Volatile var cleanExit: ((why: String, done: (forceNeeded: Boolean) -> Unit) -> Unit)? = null
    /** the clean exit's last route line ("clean exit (safe close): ..."), for the safe close's "done" line */
    @Volatile var lastCleanExit: String = ""
    /**
     * feat/pause-power (the user, 2026-11-18: "we need a way to close the game from the app in game"): DS FOREVER CLOSES - its task
     * finished (every activity of it, Streaming's too), the game's environment shut down on the way (MainActivity.onDestroy), the
     * process ended once no task is left. [force] = the game did not exit by itself: the old close of it first. Set by the launcher's
     * MainActivity; null = no launcher (nothing closes)
     */
    @Volatile var quitApp: ((force: Boolean) -> Unit)? = null
    /**
     * feat/exit-now (the user, 2026-11-24: "I want our built in close game button to completly close the app immidiatly"): THE MENU'S
     * EXIT GAME - DS Forever closes at once, Local or Streaming ([ExitNow]: no Exit Game, no countdown; the game killed, every screen
     * and service gone, the process ended). [why] = who asked ([ExitNow.MENU]). Set by the launcher's MainActivity; null = no launcher
     */
    @Volatile var exitNow: ((why: String) -> Unit)? = null
    /** the container's graphics profile as the launcher picked it (the problem report's GPU / driver line), "" = not known yet */
    @Volatile var gpuLine: String = ""
    /** the launch's last step and its result ("preparing the container", "booting", "launch failed: ..."), "" = no launch yet */
    @Volatile var launchState: String = ""
    /** a launch step (the launcher's): kept as [launchState] and as a start-up stage ([StartupTrace]) */
    /** when [launchState] last changed (epoch ms), 0 = no launch yet: the launch window [PlayGate] holds the heavy work back in
     *  (issues #104 / #108 cluster, 2026-11-11: the game's processes grow while the container boots, before the game screen is up) */
    @Volatile var launchAtMs = 0L
    fun launchEvent(what: String) {
        launchState = what; launchAtMs = System.currentTimeMillis(); StartupTrace.mark("launch: ${what.take(60)}", what)
        // issue 568: a launch ends the indoor maps' quiet service at once (the builder lets go of the game files by itself)
        runCatching { IndoorKeepAlive.update(null, why = "launch: ${what.take(40)}") }
    }
    /**
     * No launch is under way any more (review of the #108-cluster fix, 2026-11-11): the launcher's screen is back in front (the game
     * screen went, or never came up: the container failed, the player backed out) or Play starts a new launch. Ends [PlayGate]'s launch
     * window; [launchState] stays for the problem report
     */
    fun endLaunchWindow() { launchAtMs = 0L; playHoldAtMs = 0L; playPressAtMs = 0L }

    /**
     * PLAY LETS THE INDOOR MAPS GO AT ONCE (the user, 2026-11-24: the indoor maps build on the first screen while people wait there;
     * "Pressing Play must not be delayed"): when Play was pressed (epoch ms; 0 = no press under way). Set at the press itself, before
     * Play's wait for the art; the indoor maps builder lets go of the game files at its next step ([PlayGate.pressHeld]) and Play waits for
     * that at most a second (IndoorSchedulerStep.letGoForPlay). Cleared by [endLaunchWindow] (the start screen back) and [playStopped]
     */
    @Volatile var playPressAtMs = 0L
    fun playPressed() { playPressAtMs = System.currentTimeMillis(); runCatching { IndoorSchedulerStep.playPressed() } }
    /** the launch stopped before the game came up (the art not ready, a refusal, a failure): the start screen builds again */
    fun playStopped(why: String) { playPressAtMs = 0L; runCatching { IndoorSchedulerStep.playStopped(why) } }

    /**
     * 2026-11-24: the game's own files are being written now (its update, an install, Repair: the launcher's screen): the indoor maps
     * never read beside that ([PlayGate.FILES_BUSY])
     */
    @Volatile var filesBusy: () -> Boolean = { false }

    /** when Play held the art ([holdForPlay], epoch ms), 0 = no hold: [PlayGate.held] */
    @Volatile var playHoldAtMs = 0L
    /**
     * PLAY HOLDS THE ART (test build 1489, #361 / #56): Play's wait for the art before Play is over and the game starts next - every
     * art step lets go of the game files now and waits for the game to close ([PlayGate.held] until the launch's first step takes over)
     */
    fun holdForPlay() { playHoldAtMs = System.currentTimeMillis(); runCatching { IndoorKeepAlive.gameStarting() } }
}
