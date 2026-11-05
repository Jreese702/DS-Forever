package com.jrees.wowcompanion

/**
 * THE GAME'S CURSOR ON THE TOP SCREEN (the user, 2026-11-04: "would we possibly just make the cursor like 1 pixel while not using the
 * track pad or touching the top screen?"). WoW Forever has no hardware-cursor setting (gxCursor answered nothing) and its own cursor shows
 * after every mouse click, which every app tap is (TapBridge). The launcher's renderer draws that cursor, so the launcher decides: it is
 * drawn only for [SHOW_MS] after a REAL touch on the top screen (the Track Pad forces its own arrow as before). An app tap is never a real
 * touch: TapBridge marks [tapUntil] around its batches. Pure state; the launcher's XServerScreen applies it.
 */
object CursorPolicy {
    const val SHOW_MS = 3_000L
    /** an app tap batch is running until then (uptime ms): touches before it are ours, not the player's */
    @Volatile var tapUntil = 0L
    /** the cursor is drawn until then (uptime ms): a real touch's [SHOW_MS] */
    @Volatile var showUntil = 0L
    /** a real top-screen touch at [now]: whether it counts (not an app tap) */
    fun realTouch(now: Long): Boolean = now > tapUntil
    /** TapBridge: a batch of [n] taps starts at [now] (each about 70 ms; a margin for the shell) */
    fun tapping(now: Long, n: Int) { tapUntil = maxOf(tapUntil, now + 1_000L + n * 120L) }
}
