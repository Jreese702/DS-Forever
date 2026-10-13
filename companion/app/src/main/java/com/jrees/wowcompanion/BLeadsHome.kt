package com.jrees.wowcompanion

/**
 * A ROOM SAYS WHERE ITS B LEADS RIGHT NOW (the user 2026-10-09: the B button's flash "doesnt do the actual flash yellow and removal of
 * the B glyph until after its already returned home instead of when it gets pushed"; "Now, before release"). A room whose B goes
 * through the game (its own capture) implements this; CompanionHost reads it on the B PRESS from the app's own controller reader:
 * true = this B leaves for Home (the disc's flash, the glyph and the room's exit all start in that frame, Home takes the input at
 * once; the game's own release that follows is the echo), false = B is the room's "back" (a menu, a confirm, a sub-screen).
 */
interface BLeadsHome {
    fun bLeadsHome(): Boolean
}
