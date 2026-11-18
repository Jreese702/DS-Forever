package com.jrees.wowcompanion

import com.jrees.wowcompanion.stream.StreamQuality

/**
 * THE STREAM'S SETTINGS AS THE BOTTOM SCREEN SEES THEM (the user, 2026-11-18 on test 1488: "the streaming settings appear on the top
 * screen and the controller inputs get all wacky, they should probably appear on the bottom screen in the stream settings box").
 * StreamTop keeps what they do (the stored quality, the 120 fps check, the reconnect after a change, Test connection); Settings >
 * Performance > Stream settings draws them in its band ([PerformanceTiles]) and presses them here. Main thread.
 */
interface StreamSettingsLink {
    /** the stored choice as this Thor plays it (a stored 120 it can't show reads 60) */
    fun quality(): StreamQuality
    /** this Thor shows [q]'s size at 120 fps */
    fun can120(q: StreamQuality): Boolean
    /** a new choice, stored at once (it applies when the band closes) */
    fun change(q: StreamQuality)
    /** the band opened / closed: a change made while streaming connects again on the close, as the old page's Done did */
    fun opened()
    fun closed()
    /** Test connection: runs, its rows come through [tested] */
    fun test()
    /** the test of this visit, null = none run */
    fun tested(): StreamTest?
    /** a change waits for the close: the stream connects again then */
    fun restartsOnClose(): Boolean
    /** a test line in the player's language (its words translated, its numbers kept) */
    fun say(line: String): String = Tr.t(line)
}

/** Test connection's rows as the band lists them: one per step, then the first failure's reason and fix */
data class StreamTest(val rows: List<Row>, val reason: String = "", val fix: String = "", val running: Boolean = false) {
    enum class Mark { WAIT, WORKING, PASS, FAIL, SKIP }
    data class Row(val label: String, val line: String, val mark: Mark)
}
