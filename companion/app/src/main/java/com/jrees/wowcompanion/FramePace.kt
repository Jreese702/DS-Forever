package com.jrees.wowcompanion

/**
 * PERFORMANCE MODE FOR THE BOTTOM SCREEN (test build 1529; the user's pick 2026-11-24, after players saw better performance in a launcher
 * whose bottom screen is a static pad: "Performance mode (Recommended)" = "In fights and while moving, the bottom screen redraws at ~20-30 Hz
 * instead of every frame. Map arrow and swing still move, just less smoothly."). Automatic, no setting.
 *
 * The bottom screen shares the GPU and the big cores with the game. At rest it costs nothing (it draws on a change), but while the player
 * moves (Home's map: the trail and the arrow glide) and in a fight (the cast bar, the swing, the cooldowns, Home's flashes) its views ask
 * for a frame at EVERY vsync (postInvalidateOnAnimation from their onDraw): 60 bottom-screen frames a second beside the game's.
 *
 * WHAT CHANGES: the paced views (MapView, CastView, HomeView, FightCastStripView) send their animation requests through a [Gate]
 * (their postInvalidateOnAnimation override). While [busy] - Home on show (no room open), the player moving or fighting, and no touch on
 * the bottom screen for [TOUCH_HOLD_MS] - a request is answered at most every [INTERVAL_MS] (one delayed invalidate, coalesced: any
 * number of requests in between share it), so a 60 Hz loop draws ~30 times a second. Otherwise (at rest, in the rooms, right after a
 * touch) every request goes to the next vsync as before.
 *
 * WHAT DOES NOT CHANGE: a view's own invalidate() (a touch, a pad press, a new document) still draws at the next vsync - only the
 * self-repeating animation frames are paced, and a request after a quiet spell of [INTERVAL_MS] goes at once (so a pad press's first
 * glow frame is not delayed). The animations read the clock when they draw, so they run at the same speed, in fewer steps.
 *
 * Pure (the clock, the posts and the state are passed in) so the off-device test counts the frames: FramePaceTest.
 */
object FramePace {
    /** the paced frame interval: 30 Hz (two vsyncs at 60 Hz) */
    const val INTERVAL_MS = 33L
    /** the delayed invalidate lands this much before the interval ends, so the draw takes the next vsync and not the one after */
    const val SLACK_MS = 4L
    /** a touch on the bottom screen gives full rate for this long (a pan, a drag, a press's ripple) */
    const val TOUCH_HOLD_MS = 1_500L
    /** the moving / fighting read is cached this long (it is asked by every animation request) */
    const val PROBE_MS = 100L

    /** moving or fighting with Home on show, read on the UI thread (the host sets it; null = never paced) */
    @Volatile var probe: (() -> Boolean)? = null
    @Volatile private var touchAt = Long.MIN_VALUE / 2
    private var probedAt = Long.MIN_VALUE / 2
    private var probed = false

    /** the frames paced so far (the WCMEM-style counter for a log line; never reset) */
    @Volatile var pacedFrames = 0L; private set

    /** a touch on the bottom screen (the host's root, every event) */
    fun touched(now: Long) { touchAt = now }

    /** the paced state now (UI thread): moving or fighting on Home, and no touch for [TOUCH_HOLD_MS] */
    fun busy(now: Long): Boolean {
        if (now - touchAt < TOUCH_HOLD_MS) return false
        if (now - probedAt >= PROBE_MS || now < probedAt) {
            probedAt = now
            probed = runCatching { probe?.invoke() == true }.getOrDefault(false)
        }
        return probed
    }

    /** tests: as at the process's start */
    internal fun reset() { touchAt = Long.MIN_VALUE / 2; probedAt = Long.MIN_VALUE / 2; probed = false; probe = null; pacedFrames = 0L }

    /**
     * ONE VIEW'S PACING. [frame] is the view's animation request; [vsync] its own next-vsync request (super.postInvalidateOnAnimation),
     * [later] posts [Runnable] after ms on the view (postDelayed), [redraw] its invalidate(), [clock] uptime ms, [busy] the paced state.
     */
    class Gate(
        private val clock: () -> Long,
        private val busy: (Long) -> Boolean,
        private val vsync: () -> Unit,
        private val later: (Runnable, Long) -> Unit,
        private val redraw: () -> Unit,
    ) {
        private var pending = false
        /** the last frame drawn for a request let through (the draw's own request, at its time: the next interval counts from it) */
        private var lastAt = Long.MIN_VALUE / 2
        /** a request was let through ([firedAt]) and its draw has not asked again yet */
        private var fired = false
        private var firedAt = Long.MIN_VALUE / 2
        private val tick = Runnable { pending = false; fired = true; firedAt = clock(); redraw() }

        fun frame() {
            val now = clock()
            // the first request since one was let through comes from its draw: the interval counts from the DRAW, not from when the
            // tick ran (a tick that fired just after a vsync would otherwise put the next one a single vsync later)
            // (a request long after it is not that draw's: a view that stopped animating, asked again later - it counts from the let-through)
            if (fired) { fired = false; lastAt = if (now - firedAt <= INTERVAL_MS) now else firedAt }
            if (!busy(now)) { lastAt = now; fired = true; firedAt = now; vsync(); return }
            if (pending) return                                  // coalesced: the tick already asked for covers this request
            val due = lastAt + INTERVAL_MS - SLACK_MS
            if (now >= due) { lastAt = now; fired = true; firedAt = now; vsync(); return }    // nothing drawn for an interval: at once
            pending = true
            pacedFrames++
            later(tick, due - now)
        }
    }

    /** a [Gate] for an Android view: [vsync] = its super.postInvalidateOnAnimation */
    fun gate(v: android.view.View, vsync: () -> Unit): Gate =
        Gate({ android.os.SystemClock.uptimeMillis() }, ::busy, vsync, { r, ms -> v.postDelayed(r, ms) }, { v.invalidate() })
}
