package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * A CLEAN EXIT BEFORE DS FOREVER ENDS THE GAME (the user's ruling after test20, "Both (Rec.)"; Tracking proved on the Thor that the
 * game had never written WowCompanionSpells.lua since the fresh install: SavedVariables are written only when the game logs out or
 * exits cleanly, and the launcher's close kills it). Whenever DS Forever itself ends the game - the quick menu's Exit, Restart to sign
 * in - it first asks the GAME to exit: ONE tap on Spells' secure "gamemenu" row, its Exit Game cell (the game's own "/quit", the same
 * cell the pause menu's Exit Game uses; no key is ever typed - the Alt / F-key ban). In a rested area the game exits at once; elsewhere
 * after its 20 s count. The caller waits for the game to go ([WAIT_MS] at most) and only then closes the old way.
 * No clean route (the game not past its login screen, a fight - the game refuses a log out then - no Exit cell): the old close at once.
 * Pure: home_test runs it.
 */
object CleanExit {
    /** the game's own count outside a rested area is 20 s; the wait gives it room, then the old close */
    const val WAIT_MS = 35_000L
    /** the add-on spoke this recently = the game is past its login screen (in the world) */
    const val ALIVE_MS = 15_000L

    sealed class Route {
        /** tap the gamemenu row's Exit Game cell [col], then wait for the game to go */
        data class Quit(val col: Int) : Route()
        /** close at once, the old way; [why] for the log */
        data class Force(val why: String) : Route()
    }

    /** the Exit Game cell from Spells' latest "gamemenu" document (the game's own list), or the usual cell before one came */
    fun quitCol(gamemenu: JSONObject?): Int? =
        GameMenuModel.items(gamemenu).firstOrNull { it.key == "quit" }?.takeIf { it.on }?.col?.takeIf { it >= 0 }

    fun route(lastAddonMs: Long, nowMs: Long, inFight: Boolean, quitCol: Int?): Route = when {
        lastAddonMs <= 0L || nowMs - lastAddonMs > ALIVE_MS -> Route.Force("the game is not past its login screen")
        inFight -> Route.Force("in a fight the game refuses to log out")
        quitCol == null -> Route.Force("no Exit Game cell")
        else -> Route.Quit(quitCol)
    }
}
