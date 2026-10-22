package com.jrees.wowcompanion

/**
 * NO ROOM EVER WITHOUT A WORKING B (tracking, after the map module: "The rule: no room may ever stay open without a working B"). Every
 * room and Home module takes the controller through a hold the game can refuse in a fight (Quests' guests, the add-ons' room captures);
 * then nothing of the controller reaches the app and B does only the game's own Back. The host's guard: while something on screen
 * expects the controller ([needs]) and NOBODY holds it (the shared capture's holder empty) for [GRACE_MS], Home reads the controller
 * itself (PadInput: read only) and B steps back - a room goes Home, a Home module / list / tool closes. Out of the fight the hold is
 * asked for again every [RETRY_MS] (Home's own modules; a room asks again when it is entered again). Pure.
 */
object ExitGuard {
    const val GRACE_MS = 2_000L
    const val RETRY_MS = 3_000L

    /**
     * Something on screen expects the controller: a room other than Home, or one of Home's own controller layers (a module full screen,
     * the aura list, the type tool). Not the wheels (the pause / item / spell wheels and the class radials read the controller
     * themselves) and not Home's map module (its own guard, MapExitGuard).
     */
    fun needs(roomIsHome: Boolean, homeLayer: Boolean, wheelOpen: Boolean, mapModuleGuarded: Boolean): Boolean =
        !wheelOpen && !mapModuleGuarded && (!roomIsHome || homeLayer)

    /** the guard's reader runs: expected, unheld, for the grace */
    fun on(needs: Boolean, holder: String, unheldForMs: Long): Boolean = needs && holder.isEmpty() && unheldForMs >= GRACE_MS

    /** ask Home's own hold again: the guard on, the fight over, the last ask long enough ago */
    fun retry(guardOn: Boolean, inFight: Boolean, sinceAskMs: Long): Boolean = guardOn && !inFight && sinceAskMs >= RETRY_MS
}
