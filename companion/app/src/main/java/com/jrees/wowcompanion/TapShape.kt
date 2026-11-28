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

    /**
     * PR #256 (KillgoreAlpha, measured on a Retroid Pocket Nova with the Dual Screen Add-on): why display 0's physical mode, landscape
     * [w] x [h], is NOT to be trusted as the top screen, or null when it is. The 2026-09-28 bug was display 0 reading back the COMPANION
     * screen's size, so that is what is refused: the [companion] screen's own panel when its display is known, else (PR review) the
     * panel of any [others] screen. The old rule ("display 0 must be the largest panel") refused every tap on a handheld whose second
     * screen is the larger one. Known limit, as with the PR's rule: two panels of exactly the same size are refused (none such today:
     * every Thor is 1920x1080 over 1240x1080). Pure.
     */
    fun panelRefusal(w: Int, h: Int, companion: Pair<Int, Int>?, others: List<Pair<Int, Int>>): String? = when {
        w <= 0 || h <= 0 -> "display 0's mode is ${w}x$h"
        companion != null -> if (companion == (w to h)) "display 0 read back the companion screen's ${w}x$h" else null
        (w to h) in others -> "display 0 read ${w}x$h, another screen's size, while the companion screen is not known"
        else -> null
    }

    /** tests */
    @Synchronized internal fun reset() { refused = 0; game = ""; top = "" }

    /**
     * game px ([gx], [gy]) of a [pw] x [ph] game screen on a [dw] x [dh] display of the same shape, display px (not rounded). Pure: the
     * render size test (2026-11-27: the game at 1280x720 or 1600x900 on the 1920x1080 top screen) walks every pad cell through it
     */
    fun displayOf(gx: Double, gy: Double, pw: Double, ph: Double, dw: Int, dh: Int): Pair<Float, Float> =
        (gx * dw / pw).toFloat() to (gy * dh / ph).toFloat()

    /** one tap's shell line for display px ([x], [y]): 2 decimals (1.0.21), InputSink makes a fraction of it. Pure */
    fun tapLine(x: Float, y: Float): String =
        "input -d 0 tap ${"%.2f".format(java.util.Locale.ROOT, x)} ${"%.2f".format(java.util.Locale.ROOT, y)}"
}
