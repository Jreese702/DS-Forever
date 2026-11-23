package com.winlator.xserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #153 review: a sender's press-to-release gap (DS Forever's 30 ms cell tap, InputSink's 40 ms key taps) is kept on the input thread
 * when the queue ran behind and both would otherwise run back to back - a secure cell acting on the release got no hold at all.
 * Nothing waits when the queue kept up, and a long hold is never replayed past [XServer.MAX_KEPT_HOLD_MS].
 */
class XInputHoldTest {
    private val ms = 1_000_000L

    @Test fun aLateReleaseWaitsOutTheMissingHold() {
        // sent 30 ms apart, the press ran 5 s late and the release right after it
        val left = XServer.holdLeftNs(1_000 * ms, 1_030 * ms, 6_000 * ms, 6_000 * ms + 200_000L)
        assertTrue("left ${left / ms} ms", left in 29 * ms..30 * ms)
    }

    @Test fun noWaitWhenTheQueueKeptUp() {
        // the press ran at once, the release 30 ms later: the hold already happened
        assertEquals(0L, XServer.holdLeftNs(1_000 * ms, 1_030 * ms, 1_000 * ms, 1_031 * ms))
        // a jitter of under a millisecond is not slept
        assertEquals(0L, XServer.holdLeftNs(1_000 * ms, 1_030 * ms, 1_000 * ms, 1_029 * ms + 500_000L))
    }

    @Test fun aLongHoldIsCapped() {
        val left = XServer.holdLeftNs(1L, 2_000 * ms, 5_000 * ms, 5_000 * ms)
        assertEquals(XServer.MAX_KEPT_HOLD_MS * ms, left)
    }

    @Test fun noPressForThisReleaseMeansNoWait() {
        assertEquals(0L, XServer.holdLeftNs(0L, 1_030 * ms, 6_000 * ms, 6_000 * ms))          // never pressed
        assertEquals(0L, XServer.holdLeftNs(1_000 * ms, 1_030 * ms, 0L, 6_000 * ms))          // the press never ran
        assertEquals(0L, XServer.holdLeftNs(1_000 * ms, 1_030 * ms, 900 * ms, 6_000 * ms))    // an older press ran, not this one
    }

    @Test fun keepHoldSleepsWhatIsMissing() {
        val now = System.nanoTime()
        XServer.keepHold(now - 10 * ms, now + 30 * ms, now)       // 40 ms meant, the press just ran
        val waited = (System.nanoTime() - now) / ms
        assertTrue("waited $waited ms", waited in 35L..300L)
    }
}
