package com.jrees.wowcompanion

/**
 * THE PLAYER STAYS ON SCREEN (p-map-center; a player on v1.0.5: "The map doesn't seem to keep my character centered"). The full
 * map's auto-framer centres the box of the player and the tracked objectives, but caps how far out it zooms (MapView's
 * MAX_FRAME_YD): with objectives spread wider than the cap, the box's middle stood far from the player and the capped view left
 * the player at the edge or off it. [keep] slides that centre toward the player until the player is within [share] of the
 * centre-to-edge distance, on one axis. Pure (no Android) so it is tested off-device.
 */
object FrameKeep {
    /** of the visible half-extent, how far from the centre the player may stand */
    const val SHARE = 0.7f

    /**
     * [mid] the frame's centre, [p] the player, [half] the view's visible half-extent on this axis (same units).
     * Returns the centre to use, and whether it was moved (then it follows the player one for one: anchor 1).
     */
    fun keep(mid: Float, p: Float, half: Float, share: Float = SHARE): Pair<Float, Boolean> {
        if (half <= 0f || mid.isNaN() || p.isNaN()) return Pair(mid, false)
        val lim = half * share
        return when {
            mid - p > lim -> Pair(p + lim, true)
            p - mid > lim -> Pair(p - lim, true)
            else -> Pair(mid, false)
        }
    }
}
