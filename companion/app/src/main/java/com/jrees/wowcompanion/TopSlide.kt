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
 * the top one. The way down HOLDS first until the Wings are mostly off the top screen ([DOWN_HOLD_MS]; a reversal of the way up as long
 * as the Wings need, [reversalHoldMs]). A reversal mid-way starts from where every part is (HomeView: from = now). The hold is wall-clock
 * time (HomeView posts the slide's start: the add-on's Wings run in real time, never on the system's animator duration scale); the slide
 * itself follows that scale - "Animations off" (0) lands it in one frame: the band jumps, after the hold on the way down.
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

    // ---------------------------------------------------------------- the way down waits for the Wings

    /**
     * THE WAY DOWN WAITS FOR THE WINGS (the check sheet of 11-22: at the fight's end + 48 ms BOTH showed - the band came down at once on
     * its fast-start curve while the add-on's Wings were still leaving the top screen on their slow-start one, two objects). The band is
     * the Wings crossing the seam back, so it may only come in over the bottom screen's top edge once the Wings are mostly off the top
     * screen's bottom edge: a down-slide that starts with the band landed above the page HOLDS [DOWN_HOLD_MS] first - the time the Wings
     * take to be [WINGS_MOSTLY_OFF] off ([WINGS_OUT_MS] on [WINGS_OUT], ActionBar NB.TOP.OUT_S on NB.easeIn) counted from the moment the
     * app hears the fight's end, as if it heard it the very frame the Wings start (the add-on sends the edge on its next frame, the
     * channel adds its 40-90 ms: the app always hears it later, so the Wings are further off than that), plus [LATENCY_ALLOWANCE_MS] for
     * the add-on stepping its slide by whole game frames (a frame late on a slow one). Every part waits with the band (the columns stay
     * grown under it: one motion). A fight coming back inside the hold keeps the band away (nothing moved). The way up keeps no hold
     * (the Wings wait 60 ms for the band: ActionBar HOLD_IN).
     * A REVERSAL WAITS AS LONG AS THE WINGS NEED (the 11-22 review: an up-slide cut late - a one-hit kill 200-230 ms in - left the band
     * 93 % off the page and the Wings 93 % in, and the band turned back down at once: at +48 ms it was half back while the Wings were
     * still 85 % shown). An up-slide cut by the fight's end holds [reversalHoldMs] where it stands: the time the Wings, reversed from
     * as far in as they had got ([wingsIn] at the up-slide's wall-clock age), take to be mostly off, plus the same allowance - the
     * whole [DOWN_HOLD_MS] when they were all in, nothing when they had barely started (an early cut turns back at once, as before).
     */
    const val WINGS_OUT_MS = 200L
    /** the Wings' way down: NB.easeIn (0.4, 0, 1, 1), the same curve as [UP] */
    val WINGS_OUT = floatArrayOf(0.4f, 0f, 1f, 1f)
    /** "mostly off": the share of the Wings' way down they have gone when the band may start in */
    const val WINGS_MOSTLY_OFF = 0.8f
    /** the add-on's frame stepping (one slow game frame) */
    const val LATENCY_ALLOWANCE_MS = 24L

    /** how far off the top screen the Wings are (0 at their spot .. 1 wholly under the edge) [ms] after they started down */
    fun wingsOff(ms: Long): Float = cubic(ms / WINGS_OUT_MS.toFloat(), WINGS_OUT)
    /** the first whole ms at which the Wings are [WINGS_MOSTLY_OFF] off */
    val WINGS_MOSTLY_OFF_MS: Long = (0L..WINGS_OUT_MS).first { wingsOff(it) >= WINGS_MOSTLY_OFF }
    /** the down-slide's hold (about 199 ms) */
    val DOWN_HOLD_MS: Long = WINGS_MOSTLY_OFF_MS + LATENCY_ALLOWANCE_MS

    /** the Wings' way up: ActionBar NB.TOP.HOLD_IN (0.06 s) then NB.TOP.IN_S (0.26 s) on NB.easeOut (0, 0, 0.2, 1), the same curve as [DOWN] */
    const val WINGS_HOLD_IN_MS = 60L
    const val WINGS_IN_MS = 260L
    val WINGS_IN = floatArrayOf(0f, 0f, 0.2f, 1f)
    /** how far in the Wings are (0 under the edge .. 1 at their spot) [upMs] after the fight's start, counted on the band's clock: the
     *  add-on hears both edges of the fight before the app by about the same channel delay, so the Wings' age at their reversal is the
     *  band's up-slide age at its own */
    fun wingsIn(upMs: Long): Float = cubic((upMs - WINGS_HOLD_IN_MS) / WINGS_IN_MS.toFloat(), WINGS_IN)
    /** where on a whole way down ([wingsOff]'s ms) the Wings reversed from [k] in stand: the add-on goes on from where they are (NB.slideT) */
    fun wingsOutAt(k: Float): Long = (0L..WINGS_OUT_MS).first { 1f - wingsOff(it) <= k }
    /** an up-slide [upMs] old (wall clock) turned back down by the fight's end: the hold until the Wings, reversed from where they
     *  stood, are mostly off (+ the allowance); 0..[DOWN_HOLD_MS], continuous (no step where the Wings cross "mostly") */
    fun reversalHoldMs(upMs: Long): Long =
        if (upMs < 0L) 0L else (WINGS_MOSTLY_OFF_MS - wingsOutAt(wingsIn(upMs)) + LATENCY_ALLOWANCE_MS).coerceIn(0L, DOWN_HOLD_MS)

    /**
     * the hold before this motion's parts move: a fresh way down ([slide], not [isAway]) with the band wholly above the page now
     * ([bandOff]: its bottom at or over the frame's top edge less [HomeLayoutSolver.AWAY_PAST] - landed away, or held there) waits
     * [DOWN_HOLD_MS]; a reversal (an up-slide cut on its way, [cutUp], [upMs] old on the wall clock: the band partly on the page)
     * [reversalHoldMs]; a down-slide re-laid inside its hold ([resumeAt] >= 0, the cut one going down) waits what was left of it
     * ([cutHold]); the way up, a glide: none
     */
    fun holdMs(slide: Boolean, isAway: Boolean, bandOff: Boolean, cutUp: Boolean, cutHold: Long, resumeAt: Float, upMs: Long = -1L): Long = when {
        isAway -> 0L
        slide -> if (bandOff) DOWN_HOLD_MS else if (cutUp) reversalHoldMs(upMs) else 0L
        resumeAt >= 0f && !cutUp -> cutHold.coerceIn(0L, DOWN_HOLD_MS)
        else -> 0L
    }
    /** the band's box bottom [bottom] lies wholly above the page (laid away: -[HomeLayoutSolver.AWAY_PAST]) */
    fun bandOff(bottom: Float?): Boolean = bottom != null && bottom <= -HomeLayoutSolver.AWAY_PAST + 0.5f

    /** the slide's own linear fraction at [t] ms into a motion that holds [hold] ms and then slides for [ms] */
    fun afterHold(t: Float, hold: Long, ms: Long): Float = ((t - hold) / ms.coerceAtLeast(1L)).coerceIn(0f, 1f)

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
