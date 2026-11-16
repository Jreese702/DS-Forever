package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #306 (1.0.32: "after opening the pets wheels and closing them, the joystick dont respond for a few secs or require bashing B";
 * its log: "pause: free again (1) .. (4) sent", each answered "radial: guest free ok"): the pause's own free, sent after the wheel has
 * gone, gets its own answer; nothing else of the shared guest "radial" changes hands.
 */
class PauseAckRouteTest {
    @Test fun thePausesLateFreeAnswerIsThePauses() {
        assertTrue("open: every answer is the pause's (as before)", PauseAckRoute.toPause(true, "hold", false))
        assertTrue(PauseAckRoute.toPause(true, "free", false))
        assertTrue("gone, its free out: the free's answer is the pause's (#306)", PauseAckRoute.toPause(false, "free", true))
        assertFalse("gone, its free out: a hold's answer is the item wheel's", PauseAckRoute.toPause(false, "hold", true))
        assertFalse("gone, no free of its own out: the item wheel's (as before)", PauseAckRoute.toPause(false, "free", false))
        assertFalse(PauseAckRoute.toPause(false, "leave", true))
    }
}
