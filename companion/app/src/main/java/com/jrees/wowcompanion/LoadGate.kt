package com.jrees.wowcompanion

/**
 * IS A LOADING SCREEN UP? (2026-11-19, a Thor log streaming from a PC: the login's top-screen taps - the one restore tap among them - went while its
 * loading screen was still up, and every one was lost; the app then fell back to ~20 single switch taps.) A tap on a loading screen
 * reaches nothing, and a fixed wait after the hello ([GameUiPolicy.SETTLE_MS]) is too short whenever the load takes longer.
 *
 * The add-ons load UNDER the login's (or a /reload's) loading screen, so they never see its start: a current hello starts one here
 * ([hello]), but only once the add-ons have been seen reporting loading screens at all ([reports], kept by the app): an older add-on set
 * without Core.lua core1 never sends an end, and its logins keep the old fixed wait instead of the [maxMs] cap.
 * Core.lua's {"type":"load","on":true} (core1, LOADING_SCREEN_ENABLED) starts one too (a zone), and its {"on":false} ends either.
 * A lost end never blocks the taps for long: a loading screen counts for at most [maxMs].
 *
 * Pure Kotlin (no android.*), so the unit tests run the real file.
 */
class LoadGate(private val maxMs: Long = 60_000L) {
    @Volatile private var since = 0L
    @Volatile private var overAt = 0L
    /** the add-ons report loading screens (a {"type":"load"} document was seen; the app keeps this across runs) */
    @Volatile var reports = false

    /** a current hello (a login or a /reload) at [now]: its loading screen is still up, if the add-ons will report its end */
    fun hello(now: Long) { if (reports) since = now }

    /** the add-on's load on at [now]: a loading screen came up (a zone) */
    fun start(now: Long) { since = now }

    /** the add-on's load off at [now]: ends the loading screen (an end without a start is ignored) */
    fun end(now: Long) {
        if (since != 0L) overAt = now
        since = 0L
    }

    /** a loading screen is up at [now] */
    fun loading(now: Long): Boolean = since != 0L && now - since < maxMs

    /** when the last loading screen went (0 = none seen) */
    fun overAt(): Long = overAt

    /**
     * When the top-screen taps of a load that began with the hello at [helloAt] may start: never while loading, then [settleMs] after
     * the hello or [afterMs] after the loading screen went, whichever is later. An add-on set that never reports loading screens
     * (before core1) gets the hello rule alone, as before.
     */
    fun settleAt(now: Long, helloAt: Long, settleMs: Long, afterMs: Long): Long {
        if (loading(now)) return Long.MAX_VALUE / 2
        val over = overAt.takeIf { it >= helloAt }?.let { it + afterMs } ?: 0L
        return maxOf(helloAt + settleMs, over)
    }
}
