package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-cast-predict-menus (the user on 1.0.9: "the predictive cast is triggering when I am navigating menus") */
class CastPredictMenusTest {
    private fun r(holder: String = "", onHome: Boolean = true, homeGuest: Boolean = false, pause: Boolean = false, cursor: Boolean = false,
                  overlay: Boolean = false, bars: Boolean = true, radial: Boolean = false) =
        CastPredict.reaches(holder, onHome, homeGuest, pause, cursor, overlay, bars, radial)

    @Test fun plainHomeReachesTheBars() = assertTrue(r())
    @Test fun aRoomOrModuleHoldingThePad() { assertFalse(r(holder = "pad:home")); assertFalse(r(holder = "npc")); assertFalse(r(holder = "pad:radial")) }
    @Test fun homesOwnGuest() = assertFalse(r(homeGuest = true))
    @Test fun anotherRoomOnScreen() = assertFalse(r(onHome = false))
    @Test fun thePauseWheel() = assertFalse(r(pause = true))
    @Test fun theBarCursor() = assertFalse(r(cursor = true))
    @Test fun aPopUpOrWindowOverTheScreen() = assertFalse(r(overlay = true))
    @Test fun aGameWindowOwnsThePad() = assertFalse(r(bars = false))
    @Test fun theClassRadialPicks() = assertFalse(r(radial = true))
}
