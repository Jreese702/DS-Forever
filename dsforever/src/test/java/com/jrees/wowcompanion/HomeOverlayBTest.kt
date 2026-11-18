package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #540 (1.0.34: "Bar would not go back to combat mode while in combat and got stuck"): in a fight the B that closed one of the app's
 * own overlays over Home (the context / pause radial, the item / spell wheel, the class wheel, the D-pad bar cursor) also reached
 * Home's B watch through the Cast view's reader and shrank the big controller - "until the next start", so the fight's layout never
 * came back while the attack ran on (the report's log: "context radial: CLOSED (target, B)" in the fight, the layout back at rest).
 */
class HomeOverlayBTest {
    @Test fun aBThatClosesAnOverlayIsTheOverlays() {
        assertTrue("open now", HomeOverlayB.owns(openNow = true, closedAt = 0L, now = 10_000L))
        // the wheel's own reader saw the same B first and closed it a moment ago
        assertTrue("closed by this B", HomeOverlayB.owns(openNow = false, closedAt = 9_900L, now = 10_000L))
        assertFalse("closed long ago: Home's B again", HomeOverlayB.owns(openNow = false, closedAt = 5_000L, now = 10_000L))
        assertFalse("never opened", HomeOverlayB.owns(openNow = false, closedAt = 0L, now = 10_000L))
    }

    @Test fun theFightsLayoutStaysAfterAWheelsB() {
        val grow = HomeGrow()
        val t0 = 1_000_000L
        grow.noteFight(true, current = true, now = t0)
        grow.noteAttack(true, current = true, now = t0)
        assertTrue(grow.shown(t0 + 3_000L))
        // the context radial opened and closed with B in the fight: that B is the wheel's, Home's shrink never runs
        val owned = HomeOverlayB.owns(openNow = false, closedAt = t0 + 3_000L, now = t0 + 3_050L)
        if (!owned) grow.minimize(t0 + 3_050L)
        assertTrue("the big controller still shows while the attack runs on", grow.shown(t0 + 4_000L))
        // the player's own B later (no overlay) still shrinks it, as designed
        if (!HomeOverlayB.owns(openNow = false, closedAt = t0 + 3_000L, now = t0 + 6_000L)) grow.minimize(t0 + 6_000L)
        assertFalse(grow.shown(t0 + 6_100L))
    }
}
