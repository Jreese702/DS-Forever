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
    /** the game's screen is the one in front on the top display (the sign-in types only then) */
    @Volatile var inFront: () -> Boolean = { false }
    /** A CLEAN EXIT FIRST (the user's ruling after test20, "Both"; [CleanExit]): the launcher calls it before it ends the game itself;
     *  [done] runs on the main thread once the game went by itself (false) or the old close is needed (true). Null = no companion */
    @Volatile var cleanExit: ((why: String, done: (forceNeeded: Boolean) -> Unit) -> Unit)? = null
    /** the clean exit's last route line ("clean exit (safe close): ..."), for the safe close's "done" line */
    @Volatile var lastCleanExit: String = ""
}
