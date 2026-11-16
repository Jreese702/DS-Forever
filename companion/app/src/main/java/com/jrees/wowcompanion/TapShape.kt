package com.jrees.wowcompanion

/**
 * TAPS REFUSED FOR THE GAME'S SHAPE (GitHub #305, 1.0.32: "controls not working on bottom screen"). Every bottom-screen room reaches the
 * game through taps on the add-on's pad, and [TapBridge] refuses them all when the game's screen (the pad's pw x ph, the game's own
 * GetPhysicalScreenSize) is not the top screen's shape: there a tap would land on the world instead of its cell. The report showed
 * pw=1542 ph=1080 against a 1920x1080 top screen, every hold "NOT sent", and nothing saying why. This counts the refusals and keeps the
 * two sizes for the report's line; [refuse] says when a pair is new, so the log names it once. Pure.
 */
object TapShape {
    private var refused = 0
    private var game = ""
    private var top = ""

    /** one refused tap: the game's [gw] x [gh] against the top screen's [dw] x [dh]; true = a pair not seen just before (log it) */
    @Synchronized fun refuse(gw: Int, gh: Int, dw: Int, dh: Int): Boolean {
        refused++
        val g = "${gw}x$gh"; val t = "${dw}x$dh"
        val fresh = g != game || t != top
        game = g; top = t
        return fresh
    }

    /** the report's line, null when nothing was refused for the shape this run */
    @Synchronized fun line(): String? = if (refused == 0) null else
        "Taps into the game refused for the screen's shape: $refused (the game's screen $game, the top screen $top)"

    /** tests */
    @Synchronized internal fun reset() { refused = 0; game = ""; top = "" }
}
