package com.jrees.wowcompanion

/**
 * The last [SIZE] game-file reads: local or from the CDN (issues #10 / #11). GameFiles.prefetch only runs while [worthPrefetching]
 * (at least [MIN_MISSES] of them missed): after a game update, or on an out-of-date folder. On a complete folder nothing misses and a
 * prefetch would only read every file twice. Pure, thread-safe: the tests drive it.
 */
class MissWindow {
    private val ring = BooleanArray(SIZE)
    private var at = 0
    private var filled = 0

    @Synchronized fun note(miss: Boolean) {
        ring[at] = miss
        at = (at + 1) % SIZE
        if (filled < SIZE) filled++
    }

    @Synchronized fun misses(): Int = (0 until filled).count { ring[it] }

    fun worthPrefetching(): Boolean = misses() >= MIN_MISSES

    companion object {
        const val SIZE = 50
        const val MIN_MISSES = 10
    }
}
