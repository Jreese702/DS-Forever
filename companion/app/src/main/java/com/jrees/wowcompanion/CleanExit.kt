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

    /** the Exit cell answers its tap at once (Radial.lua r12's "gmack"): none by then = the tap did not land; tapped once more */
    const val ACK_MS = 4_000L
    /** while the game's own QUIT countdown shows (its "gamemenu" count), the wait goes on up to this */
    const val COUNT_WAIT_MS = 60_000L

    sealed class Wait {
        object On : Wait()
        /** no answer from the Exit cell: tap it once more */
        object Retap : Wait()
        /** give up: the old close; [why] for the log */
        data class GiveUp(val why: String) : Wait()
    }

    /**
     * THE WAIT AFTER THE EXIT TAP (test12's install: "Exit Game asked", then "still running after 35 s" with no word on why). [acked]
     * = the Exit cell answered since the tap; [retapped] = the one retry went out; [countLeft] = the seconds the game's own QUIT
     * countdown showed last (null = none seen). The game's countdown ends by itself, so it is waited out (up to COUNT_WAIT_MS).
     */
    fun waitAfterTap(elapsedMs: Long, acked: Boolean, retapped: Boolean, countLeft: Int?): Wait = when {
        !acked && !retapped && elapsedMs >= ACK_MS -> Wait.Retap
        countLeft != null && elapsedMs < COUNT_WAIT_MS -> Wait.On
        elapsedMs > WAIT_MS || (countLeft != null && elapsedMs >= COUNT_WAIT_MS) -> Wait.GiveUp(
            when {
                !acked -> "the Exit cell never answered (the tap did not reach the game: not in front, screen off?)"
                countLeft == null -> "the Exit cell answered but no QUIT countdown came (the game refused, or it is paused)"
                else -> "the game's QUIT countdown stopped at ${countLeft}s (the game paused?)"
            })
        else -> Wait.On
    }

    fun route(lastAddonMs: Long, nowMs: Long, inFight: Boolean, quitCol: Int?): Route = when {
        lastAddonMs <= 0L || nowMs - lastAddonMs > ALIVE_MS -> Route.Force("the game is not past its login screen")
        inFight -> Route.Force("in a fight the game refuses to log out")
        quitCol == null -> Route.Force("no Exit Game cell")
        else -> Route.Quit(quitCol)
    }
}
