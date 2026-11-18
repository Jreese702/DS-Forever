package com.jrees.wowcompanion

/**
 * WHOSE B IS IT: one of the app's own overlays over Home (the context / pause radial, the item / spell wheel, the class wheel, the
 * D-pad bar cursor) or Home's. Pure (HomeOverlayBTest).
 *
 * #540 (1.0.34: "Bar would not go back to combat mode while in combat and got stuck"): Home's B watch reads the Cast view's reader, so
 * the B that closed an overlay in a fight reached HomeView.onB too and shrank the big controller until the next start (HomeGrow) - the
 * fight's layout never came back while the attack ran on. The overlay's own reader often sees that B first and has closed it by then,
 * so a B within [GRACE_MS] of a close is the overlay's as well.
 */
object HomeOverlayB {
    const val GRACE_MS = 350L

    /** B now (uptime [now]) is an overlay's: one is open, or one closed at [closedAt] (0 = never) a moment ago */
    fun owns(openNow: Boolean, closedAt: Long, now: Long): Boolean =
        openNow || (closedAt > 0L && now >= closedAt && now - closedAt <= GRACE_MS)
}
