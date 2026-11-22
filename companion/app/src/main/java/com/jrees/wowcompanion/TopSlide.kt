package com.jrees.wowcompanion

/**
 * IN FIGHTS = TOP SCREEN: THE SLIDE (the user, 2026-11-22, after trying it: "the bottom [band] does not transition out during combat like
 * it should so it shows in both places. It should be a slick animation looking like it slides up to the top screen"; the pick "Chat and
 * map grow up"). With Top screen live ([TopCombatBar.live]) the fight's takeover lays the band ABOVE the page ([HomeLayoutSolver.away]):
 * HomeView's one motion then slides the band up off the bottom screen's top edge on the ease-in ([UP], what leaves the screen,
 * Motion.curveOut) and the chat / map / card columns grow up into the freed strip on the SAME curve, so their tops stay under the band's
 * foot every frame (the build's frames showed the columns on Motion.curve overtaking the band and covering its lower half mid-way: the
 * page then read as two objects); at the fight's end the reverse - the band comes back down on the ease-out ([DOWN], Motion.curveEnter),
 * the columns with it. The add-on's Wings slide in upward from the top screen's bottom edge at the same moment (ActionBar nbar11: a
 * 60 ms hold, 0.26 s ease-out), so the band reads as one object crossing the seam: accelerating off the bottom screen, decelerating onto
 * the top one. A reversal mid-way starts from where every part is (HomeView: from = now). "Animations off" (the system's duration
 * scale 0) ends the animator at once: the band jumps.
 *
 * Pure (no Android types; the curves are evaluated here as Android's PathInterpolator evaluates them), so the plain JVM tests pin them.
 */
object TopSlide {
    /** the way up (leaving the screen: Motion.curveOut's (0.4, 0, 1, 1)) */
    val UP = floatArrayOf(0.4f, 0f, 1f, 1f)
    /** the way down (coming in: Motion.curveEnter's (0, 0, 0.2, 1)) */
    val DOWN = floatArrayOf(0f, 0f, 0.2f, 1f)

    /** the page's motion is the slide: the band was laid away and is not now, or the other way */
    fun isSlide(wasAway: Boolean, isAway: Boolean): Boolean = wasAway != isAway

    /** the slide's length: up ([away] now) or back down */
    fun durationMs(away: Boolean): Long = if (away) Motion.TOP_SLIDE_UP else Motion.TOP_SLIDE_DOWN

    /** every part's progress at the linear fraction [f] of the slide ([up] or down): the band and the columns on one curve, so a column's
     *  top never passes the band's foot (the band's bottom and the columns' tops move by nearly the same distance) */
    fun partK(f: Float, up: Boolean): Float = cubic(f, if (up) UP else DOWN)

    /**
     * THE SLIDE CUT SHORT (the 11-22 review): HomeView.apply cancels the running motion on every re-lay, and inside the slide's 240 ms
     * plenty re-lay the page with the band's way unchanged (a fight parking edit mode's draft in the same tick, a quest / mail card, the
     * aura pane, the cast bar's side, the XP track's inset, Home coming back from a room). Such a re-lay goes ON with the slide: the
     * fraction it starts at (0..1), or -1 when it is not a slide's continuation - [slide] (a new slide or a reversal: from 0), a full
     * module before or now ([moduleInvolved]: the module's own time and curve), no slide cut by this re-lay ([cutAt] < 0), the cut one
     * going the other way, or one that had landed.
     */
    fun resumeAt(slide: Boolean, wasAway: Boolean, isAway: Boolean, moduleInvolved: Boolean, cutAt: Float, cutUp: Boolean): Float = when {
        slide || moduleInvolved || wasAway != isAway -> -1f
        cutAt < 0f || cutAt >= 1f || cutUp != isAway -> -1f
        else -> cutAt
    }

    /** the time left of a slide ([away] = up) that starts at the fraction [from] (0 = a whole slide); at least 1 ms */
    fun restMs(away: Boolean, from: Float): Long =
        Math.round(durationMs(away) * (1f - from.coerceIn(0f, 1f)).toDouble()).coerceAtLeast(1L)

    /** the slide's overall fraction at the linear fraction [u] of a motion that started at [from] */
    fun overall(u: Float, from: Float): Float = from + (1f - from) * u

    /** a part's progress between where it stood when the slide was cut at [from] (its new `from`) and its box, at the linear fraction [u]
     *  of the rest: the cut slide's own curve carried on (a part whose box did not change follows the very path it was on) */
    fun resumeK(u: Float, from: Float, up: Boolean): Float {
        if (from <= 0f) return partK(u, up)
        val p0 = partK(from, up)
        if (p0 >= 1f) return 1f
        return ((partK(overall(u, from), up) - p0) / (1f - p0)).coerceIn(0f, 1f)
    }

    /** one part's box at [f] between [from] and [to] (l, t, r, b) - HomeView.apply's frame, and the tests' */
    fun frame(from: FloatArray, to: FloatArray, f: Float, up: Boolean): FloatArray {
        val k = partK(f, up)
        return FloatArray(4) { from[it] + (to[it] - from[it]) * k }
    }

    /** a cubic bezier from (0, 0) to (1, 1) with the control points [c] (x1, y1, x2, y2) at x = [u], as PathInterpolator: x(t) = u by
     *  bisection, then y(t) */
    fun cubic(u: Float, c: FloatArray): Float {
        if (u <= 0f) return 0f
        if (u >= 1f) return 1f
        fun bz(t: Double, a: Float, b: Float): Double { val m = 1.0 - t; return 3 * m * m * t * a + 3 * m * t * t * b + t * t * t }
        var lo = 0.0; var hi = 1.0
        repeat(40) { val mid = (lo + hi) / 2; if (bz(mid, c[0], c[2]) < u) lo = mid else hi = mid }
        return bz((lo + hi) / 2, c[1], c[3]).toFloat()
    }
}
