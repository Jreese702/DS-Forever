package com.jrees.wowcompanion

/**
 * TEST BUILD 1489'S PICTURE CUTS, the pure part (the user's picks 2026-11-18, #56; off-device tests: LowMemCutsTest). Scene (the
 * loading pictures) and UiKit (the kit) hold Android objects from their first touch, so their rules live here.
 */
object LowMemCuts {
    // ------------------------------------------------------------------ the loading pictures (Scene)

    /** at most this many loading pictures stay decoded (the reveal draws three passes at once) */
    const val SCENE_MAX_HELD = 4
    /** the bottom screen's size: no loading picture is decoded larger than needs to cover it */
    const val SCENE_TARGET_W = 1240
    const val SCENE_TARGET_H = 1080

    /** the decoder's power-of-two sample for a [w] x [h] picture that must still cover [tw] x [th] */
    fun sampleFor(w: Int, h: Int, tw: Int = SCENE_TARGET_W, th: Int = SCENE_TARGET_H): Int {
        var s = 1
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }

    /** the held pictures beyond [cap], least recently asked first ([lru] in that order) */
    fun <K> overCap(lru: Collection<K>, cap: Int = SCENE_MAX_HELD): List<K> = lru.take((lru.size - cap).coerceAtLeast(0))

    // ------------------------------------------------------------------ the kit (UiKit)

    /** the kit's pictures while the game runs, bytes */
    const val KIT_GAME_CAP = 24L * 1024 * 1024
    /** a picture drawn within this long always stays (no re-reading per frame) */
    const val KIT_IDLE_MS = 30_000L

    /** the names to let go so [sizes] (bytes) is under [cap]: idle ones only (not drawn since [idleMs]), the oldest first */
    fun kitOverCap(sizes: Map<String, Long>, used: Map<String, Long>, nowMs: Long, cap: Long = KIT_GAME_CAP,
                   idleMs: Long = KIT_IDLE_MS): List<String> {
        var total = sizes.values.sum()
        if (total <= cap) return emptyList()
        val out = ArrayList<String>()
        for (n in sizes.keys.filter { nowMs - (used[it] ?: 0L) >= idleMs }.sortedBy { used[it] ?: 0L }) {
            if (total <= cap) break
            out.add(n); total -= sizes.getValue(n)
        }
        return out
    }
}
