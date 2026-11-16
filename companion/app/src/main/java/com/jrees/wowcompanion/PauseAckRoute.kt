package com.jrees.wowcompanion

/**
 * WHOSE ANSWER IS IT (GitHub #306, 1.0.32: "after opening the pets wheels and closing them, the joystick dont respond for a few secs or
 * require bashing B"). The pause wheel and the item / spell wheels share the guest "radial"; the host gave its answers to the pause only
 * while the pause was OPEN. The pause lets the controller go AFTER it has gone (its stick back in the middle first), so the answer to that
 * free went to the item wheel: the pause sent its free four more times (each a tap into the game, over ~1.5 s) and its Game Menu pick
 * waited out the 600 ms fallback. Pure, so the route is tested off-device.
 */
object PauseAckRoute {
    /** the shared guest's answer [op] is the pause's: while it is open, or a free's answer while its own free is out ([awaitsFree]) */
    fun toPause(pauseOpen: Boolean, op: String, awaitsFree: Boolean): Boolean = pauseOpen || (op == "free" && awaitsFree)
}
