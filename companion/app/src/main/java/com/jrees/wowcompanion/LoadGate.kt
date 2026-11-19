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
class LoadGate(private val maxMs: Long = 60_000L, private val orderMs: Long = ORDER_MS) {
    @Volatile private var since = 0L
    @Volatile private var overAt = 0L
    /** when the last load off came, a start seen or not (the order guard in [hello]) */
    @Volatile private var endSeenAt = 0L
    /** the add-ons report loading screens (a {"type":"load"} document was seen; the app keeps this across runs) */
    @Volatile var reports = false

    /**
     * A current hello (a login or a /reload) at [now]: its loading screen is still up, if the add-ons will report its end.
     * THE ORDER GUARD (PR #567's review): the hello (PLAYER_LOGIN) is written before the login's load off (LOADING_SCREEN_DISABLED),
     * but should the app ever read that end FIRST (a log switch, a late read), this hello's loading screen is already over: a load off
     * in the last [orderMs] means no loading screen starts here (else every tap would wait the [maxMs] cap); the hello's own
     * SETTLE_MS still holds the taps.
     */
    fun hello(now: Long) {
        if (!reports) return
        if (endSeenAt != 0L && now - endSeenAt in 0 until orderMs) return
        since = now
    }

    /** the add-on's load on at [now]: a loading screen came up (a zone) */
    fun start(now: Long) { since = now }

    /** the add-on's load off at [now]: ends the loading screen (an end without a start is ignored) */
    fun end(now: Long) {
        endSeenAt = now
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

    companion object {
        /** a load off read at most this long before a hello is that hello's own loading screen's end (the order guard) */
        const val ORDER_MS = 10_000L
    }
}
