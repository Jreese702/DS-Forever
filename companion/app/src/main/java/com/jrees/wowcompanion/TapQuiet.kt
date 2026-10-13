package com.jrees.wowcompanion

/**
 * THE QUIET PAD: no tap reaches the game while the controller is busy (step 0 of DirectPad; the user's yes 2026-10-06, "start with
 * step 0 + slice 1"). Measured by Home (b67 / b70, HomeRadialView.settled): a tap that reaches the game within a few ms of a
 * controller BUTTON change, or while a FACE button is held, lands on TapPad's freelook-park sink (`padstray`) instead of its cell:
 * the game parks its hidden cursor on a button press. The D-pad (a hat) does not park it, and the sticks never did. With DirectPad a
 * room's A taps ~10 ms after the press, so without this every A action would stray.
 *
 * Home's rule, applied centrally by [TapBridge] to every batch: a tap waits until no non-D-pad button is held and no button has
 * changed for [SETTLE_MS]. MEASURED (Home's test C, 2026-10-06 17:46-17:47, 56 probes, each matched to a game answer): taps sent 0-150
 * ms after the A RELEASE landed 28 of 28; taps sent after the A PRESS, with A still down, strayed 18 times even at +150 ms. So it is
 * not a time window after a change: the game keeps its cursor parked for as long as a FACE BUTTON IS HELD, and a tap lands the moment
 * it is let go. [SETTLE_MS] is only a margin for the release's report (it was 150 ms, Home's first guess; ~120 ms off every A). Caps: [MAX_HELD_MS] for a held button; then [MAX_SETTLE_MS] for the changes, counted from the moment
 * nothing was held any more (Home's view counted it from the queueing, so a tap asked for during a long hold went at the release
 * itself, which is a button change); and [MAX_HELD_MS] + [MAX_SETTLE_MS] in all, so a mashed pad can never hold a tap back for good.
 * After the caps it taps anyway: a late tap beats none. Pure (no Android imports), so the off-device test compiles it.
 */
object TapQuiet {
    const val SETTLE_MS = 30L
    const val MAX_SETTLE_MS = 700L
    const val MAX_HELD_MS = 3_000L
    const val POLL_MS = 10L             // a held button is checked this often, so the tap follows its release at once

    /**
     * How long to wait before tapping; 0 = tap now. One clock for all: [now], [queuedAt] (when the tap was asked for),
     * [lastChangeAt] (the last button change; 0 = none seen, e.g. no controller reader), [held] (a non-D-pad button is held now),
     * [freeSince] (when a button was last seen held while waiting, else [queuedAt]).
     */
    fun wait(now: Long, queuedAt: Long, lastChangeAt: Long, held: Boolean, freeSince: Long): Long {
        if (now - queuedAt >= MAX_HELD_MS + MAX_SETTLE_MS) return 0L
        if (lastChangeAt <= 0L && !held) return 0L      // no controller reader (or nothing pressed yet): as before, at once
        if (held && now - queuedAt < MAX_HELD_MS) return POLL_MS
        val quiet = now - lastChangeAt
        if (quiet >= SETTLE_MS) return 0L
        if (now - maxOf(queuedAt, freeSince) >= MAX_SETTLE_MS) return 0L
        return SETTLE_MS - quiet            // the caller asks again then: a change meanwhile restarts the wait
    }
}
