package com.jrees.wowcompanion

/**
 * WHEN THE MAP ROOM ASKS THE CAPTURE FOR THE CONTROLLER AGAIN (pure, no Android types: tools/map_test runs it off-device).
 *
 * Home's consistency pass (2026-10-06, the user's yes; AGENT_LOG 5b35c63): while a room shows it holds the controller the whole
 * time. b74 measured a leak: D-pad Down 0.8 s after the Map room opened from the bar reached Home's bar. The Map room used to ask
 * for its guest hold ONCE on show, so a lost tap, or a show before the pad document was known, left nothing asked. Now:
 *   - on show it asks, and asks again every [EVERY_MS] while it has no answer, [MAX] asks at most;
 *   - an answer stops it: the hold's ack (either way: the add-on has spoken), the first guestpad press, or the room hiding;
 *   - a tap that could not go out (no pad document yet, the game not live) ends the round without counting;
 *   - the FIRST pad document after the show asks once more if there is still no answer (even after the last retry).
 */
class HoldAsk(private val max: Int = MAX) {
    private var waiting = false          // the room shows and has no answer yet
    private var asks = 0                 // taps that went out since the show
    private var padSince = false         // a pad document arrived since the show

    fun shown() { waiting = true; asks = 0; padSince = false }
    fun answered() { waiting = false }
    fun hidden() { waiting = false }

    /** a pad document arrived; true = ask now (the first one since the show while still unanswered) */
    fun padArrived(): Boolean {
        if (!waiting || padSince) return false
        padSince = true
        if (asks >= max) asks = max - 1
        return true
    }

    /** true = tap the hold now */
    fun due(): Boolean = waiting && asks < max

    /** the hold tap went out ([sent]) or not; true = ask again in [EVERY_MS] */
    fun asked(sent: Boolean): Boolean {
        if (!sent) return false
        asks++
        return waiting && asks < max
    }

    companion object {
        const val MAX = 4
        const val EVERY_MS = 700L
    }
}
