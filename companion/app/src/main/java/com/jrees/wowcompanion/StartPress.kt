package com.jrees.wowcompanion

/**
 * THE START-UP PAGES' PRESS, BY THE PAD AS BY A FINGER (the user, 2026-10-28: "it should have actual feedback when I press the A button
 * without touching it"). Pure (clock passed in, no Android): the start-up pages (SetupPanel, ChooserPanel, LoadingTopView) keep one.
 *
 * A press of [key] (a button's action, a chooser cell): the button is DOWN for [DOWN_MS] (the kit's pushed-in look, as a touch), then
 * it WAITS (the game's spinner on the button) at least until [MIN_WAIT_MS] and for as long as the page says its action is under way
 * (`busy`: an update running or queued, a download), [MAX_WAIT_MS] at most. A different press replaces it.
 */
class StartPress {
    var key: Any? = null
        private set
    private var at = 0L

    fun press(key: Any, now: Long) { this.key = key; at = now }

    fun clear() { key = null }

    /** [k] is pushed in now */
    fun down(k: Any, now: Long): Boolean = key == k && now - at < DOWN_MS

    /** [k] shows the waiting look now ([busy] = its page's action is under way) */
    fun waiting(k: Any, now: Long, busy: Boolean): Boolean {
        if (key != k) return false
        val dt = now - at
        return dt >= DOWN_MS && dt < MAX_WAIT_MS && (dt < MIN_WAIT_MS || busy)
    }

    /** something still changes on screen (the host keeps redrawing) */
    fun animating(now: Long, busy: Boolean): Boolean = key?.let { down(it, now) || waiting(it, now, busy) } ?: false

    companion object {
        const val DOWN_MS = 150L
        const val MIN_WAIT_MS = 1200L
        const val MAX_WAIT_MS = 120_000L
    }
}
