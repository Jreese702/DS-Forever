package com.jrees.wowcompanion

/**
 * IN FIGHTS = TOP SCREEN: THE SLIDE (the user, 2026-11-22, after trying it: "the bottom [band] does not transition out during combat like
 * it should so it shows in both places. It should be a slick animation looking like it slides up to the top screen"; the pick "Chat and
 * map grow up"). With Top screen live ([TopCombatBar.live]) the fight's takeover lays the band ABOVE the page ([HomeLayoutSolver.away]):
 * HomeView's one motion then slides the band up off the bottom screen's top edge on the ease-in ([UP], what leaves the screen,
 * Motion.curveOut) and the chat / map / card columns grow up into the freed strip on the SAME curve, so their tops stay under the band's
 * foot every frame (the build's frames showed the columns on Motion.curve overtaking the band and covering its lower half mid-way: the
 * page then read as two objects); at the fight's end the reverse - the band comes back down, the columns with it. The add-on's Wings
 * slide in upward from the top screen's bottom edge at the same moment (ActionBar nbar11: a 60 ms hold, 0.26 s ease-out), so the band
 * reads as one object crossing the seam: accelerating off the bottom screen, decelerating onto the top one. THE 11-22 POLISH (the
 * user's ruling D): the columns no longer stop dead in the top gap - past [BRAKE_AT_MS] they brake evenly ([kAt]); the way down hands
 * back continuously - a [HAND_BACK_MS] hold, then the band from the page's top edge ([edgeShift]) on a slow-start curve ([DOWN]); a
 * reversal of the way up waits as long as the Wings need ([reversalHoldMs]). A reversal mid-way starts from where every part is
 * (HomeView: from = now). The hold is wall-clock time (HomeView posts the slide's start: the add-on's Wings run in real time, never on
 * the system's animator duration scale); the slide itself follows that scale - "Animations off" (0) lands it in one frame.
 *
 * Pure (no Android types; the curves are evaluated here as Android's PathInterpolator evaluates them), so the plain JVM tests pin them.
 */
object TopSlide {
    /** the band's way up (leaving the screen: Motion.curveOut's (0.4, 0, 1, 1)) */
    val UP = floatArrayOf(0.4f, 0f, 1f, 1f)
    /**
     * the way down, band and columns alike: Motion.curve's (0.4, 0, 0.2, 1) - THE HAND-BACK (the user's ruling D, 11-22, from the shots:
     * "the band's first moving frame drops ~49 px, reads as a drop-in"): the old ease-out (0, 0, 0.2, 1) started at full speed (55 px in
     * its first frame); this one starts slow (2.8, 13, 36 px after one, two, three frames) and still lands soft
     */
    val DOWN = floatArrayOf(0.4f, 0f, 0.2f, 1f)

    /** the page's motion is the slide: the band was laid away and is not now, or the other way */
    fun isSlide(wasAway: Boolean, isAway: Boolean): Boolean = wasAway != isAway

    /** the BAND's own slide length: up ([away] now) or back down */
    fun durationMs(away: Boolean): Long = if (away) Motion.TOP_SLIDE_UP else Motion.TOP_SLIDE_DOWN

    // ---------------------------------------------------------------- the columns' arrival (ruling D: "the columns hit the top gap at full
    // speed and stop dead - ease the arrival")

    /**
     * THE COLUMNS EASE INTO THE TOP GAP: on the way up the band accelerates off the page (ease-in, fastest as it leaves), and the columns
     * that rode its foot on the same curve hit the top gap at that top speed and stopped dead. Now every part but the band rides the
     * band's curve until [BRAKE_AT_MS] (k ~0.63, ~21 px a frame - still under the band's foot: its curve), then brakes EVENLY (a constant
     * deceleration from the speed it had: no kink in the speed) to rest at [COLS_UP_MS] (~311 ms), its last frame ~1.3 px. Behind the band
     * all the way: past the brake the band only speeds up, so a column's top never reaches its foot (the gap stays >= the page's 8 px).
     * The way down has no brake: band and columns on [DOWN] over [Motion.TOP_SLIDE_DOWN] (the gap 24 - 16 k, never under 8).
     */
    const val BRAKE_AT_MS = 180L
    /** the columns' progress and speed (per ms) at the brake: the band's curve there */
    private val BRAKE_K: Double = cubicD(BRAKE_AT_MS / Motion.TOP_SLIDE_UP.toDouble(), UP)
    private val BRAKE_V: Double = run {
        val h = 1e-5
        val u = BRAKE_AT_MS / Motion.TOP_SLIDE_UP.toDouble()
        (cubicD(u + h, UP) - cubicD(u - h, UP)) / (2 * h) / Motion.TOP_SLIDE_UP
    }
    /** the even brake's length: the rest (1 - k) covered at half the speed it starts with */
    private val BRAKE_MS: Double = 2.0 * (1.0 - BRAKE_K) / BRAKE_V
    /** the columns' whole way up (about 311 ms) - the way up's motion lasts this long; the band is off at [Motion.TOP_SLIDE_UP] */
    val COLS_UP_MS: Long = Math.round(BRAKE_AT_MS + BRAKE_MS)

    /** the whole motion's length (every part): up [COLS_UP_MS], down [Motion.TOP_SLIDE_DOWN] */
    fun totalMs(up: Boolean): Long = if (up) COLS_UP_MS else Motion.TOP_SLIDE_DOWN

    /** a part's progress (0..1) [ms] into the slide: the [band] on its own curve (up: [UP] over 240 ms; down: [DOWN]), every other part
     *  (the columns, the card, the party module, a bar above a column) on the columns' (up: the band's curve, then the even brake) */
    fun kAt(ms: Float, up: Boolean, band: Boolean): Float {
        if (ms <= 0f) return 0f
        if (!up) return cubic(ms / Motion.TOP_SLIDE_DOWN, DOWN)
        if (band) return cubic(ms / Motion.TOP_SLIDE_UP, UP)
        if (ms >= COLS_UP_MS) return 1f
        if (ms <= BRAKE_AT_MS) return cubic(ms / Motion.TOP_SLIDE_UP, UP)
        val s = (ms - BRAKE_AT_MS).toDouble()
        if (s >= BRAKE_MS) return 1f
        val a = BRAKE_V * BRAKE_V / (2.0 * (1.0 - BRAKE_K))
        return (BRAKE_K + BRAKE_V * s - 0.5 * a * s * s).toFloat().coerceIn(0f, 1f)
    }

    /** every part's progress at the linear fraction [f] of the whole slide ([up] or down, [totalMs] long): the [band] on its curve, the
     *  rest on the columns' (a column's top never passes the band's foot) */
    fun partK(f: Float, up: Boolean, band: Boolean = true): Float =
        if (f >= 1f) 1f else kAt(f * totalMs(up), up, band)

    /**
     * THE SLIDE CUT SHORT (the 11-22 review): HomeView.apply cancels the running motion on every re-lay, and inside the slide plenty
     * re-lay the page with the band's way unchanged (a fight parking edit mode's draft in the same tick, a quest / mail card, the
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

    /** the time left of a slide ([away] = up) that starts at the fraction [from] (0 = a whole slide, [totalMs]); at least 1 ms */
    fun restMs(away: Boolean, from: Float): Long =
        Math.round(totalMs(away) * (1f - from.coerceIn(0f, 1f)).toDouble()).coerceAtLeast(1L)

    /** the slide's overall fraction at the linear fraction [u] of a motion that started at [from] */
    fun overall(u: Float, from: Float): Float = from + (1f - from) * u

    /** a part's progress between where it stood when the slide was cut at [from] (its new `from`) and its box, at the linear fraction [u]
     *  of the rest: the cut slide's own curve carried on (a part whose box did not change follows the very path it was on) */
    fun resumeK(u: Float, from: Float, up: Boolean, band: Boolean = true): Float {
        if (from <= 0f) return partK(u, up, band)
        val p0 = partK(from, up, band)
        if (p0 >= 1f) return 1f
        return ((partK(overall(u, from), up, band) - p0) / (1f - p0)).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- the way down waits for the Wings

    /**
     * THE WAY DOWN WAITS FOR THE WINGS (the check sheet of 11-22: at the fight's end + 48 ms BOTH showed - the band came down at once
     * while the add-on's Wings were still leaving the top screen): the band is the Wings crossing the seam back, so it comes in over the
     * bottom screen's top edge as the Wings' LAST pixels leave the top screen's bottom edge. THE HAND-BACK (the user's ruling D, 11-22,
     * from the next shots: "~2 frames where neither the Wings nor the band shows, then the band's first moving frame drops ~49 px" - the
     * old 199 ms hold waited for the Wings 80 % off PLUS the channel's delay, and the old curve's first frame was a big one): a fresh way
     * down holds [HAND_BACK_MS] (109 ms) - the Wings' whole way down ([WINGS_OUT_MS] on [WINGS_OUT], ActionBar NB.TOP.OUT_S on
     * NB.easeIn) less the fastest channel ([CHANNEL_MIN_MS]: the app hears the fight's end 40-90 ms after the Wings start down), less the
     * animator's start ([START_FRAMES_MS]: its 0-frame and the first moving one), less one frame of overlap ([OVERLAP_MS]) - and the band
     * then starts from the page's top edge itself ([edgeShift]) on [DOWN]'s slow start: on a fast channel its first 2.6 px show in the
     * Wings' last frame, on a slow one it shows 2 frames or less after they are gone. Every part waits with the band (the columns stay
     * grown under it: one motion). A fight coming back inside the hold keeps the band away (nothing moved). The way up keeps no hold (the
     * Wings wait 60 ms for the band: ActionBar HOLD_IN).
     * A REVERSAL WAITS AS LONG AS THE WINGS NEED (the 11-22 review: an up-slide cut late - a one-hit kill 200-230 ms in - turned the band
     * back down at once while the Wings were still mostly in): an up-slide cut by the fight's end holds [reversalHoldMs] where it stands -
     * the same hand-back counted from where the Wings reversed ([wingsIn] at the up-slide's wall-clock age, [wingsOutAt]): nothing when
     * they had barely started (an early cut turns back at once), never more than [HAND_BACK_MS], continuous.
     */
    const val WINGS_OUT_MS = 200L
    /** the Wings' way down: NB.easeIn (0.4, 0, 1, 1), the same curve as [UP] */
    val WINGS_OUT = floatArrayOf(0.4f, 0f, 1f, 1f)
    /** the fastest channel: the app hears the fight's end this long after the Wings start down (TopCombatBar's 40-90 ms) */
    const val CHANNEL_MIN_MS = 40L
    /** from the animator's start to its first moving frame on the screen: its 0-frame (fraction 0) and the first moving one */
    const val START_FRAMES_MS = 34L
    /** the one frame the band's first pixels may share with the Wings' last (a fast channel); a slow one leaves 2 frames or less between */
    const val OVERLAP_MS = 17L
    /** the down-slide's hold (109 ms) */
    const val HAND_BACK_MS = WINGS_OUT_MS - CHANNEL_MIN_MS - START_FRAMES_MS - OVERLAP_MS
    /** how much sooner than the Wings' last pixels the hold ends (91 ms): the channel, the animator's start, the overlap */
    const val HAND_BACK_LEAD_MS = WINGS_OUT_MS - HAND_BACK_MS

    /** how far off the top screen the Wings are (0 at their spot .. 1 wholly under the edge) [ms] after they started down */
    fun wingsOff(ms: Long): Float = cubic(ms / WINGS_OUT_MS.toFloat(), WINGS_OUT)
    fun wingsOff(ms: Float): Float = cubic(ms / WINGS_OUT_MS.toFloat(), WINGS_OUT)

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
    /** an up-slide [upMs] old (wall clock) turned back down by the fight's end: the hand-back from where the Wings, reversed, stood -
     *  their way left less [HAND_BACK_LEAD_MS]; 0..[HAND_BACK_MS], continuous */
    fun reversalHoldMs(upMs: Long): Long =
        if (upMs < 0L) 0L else (WINGS_OUT_MS - wingsOutAt(wingsIn(upMs)) - HAND_BACK_LEAD_MS).coerceIn(0L, HAND_BACK_MS)

    /**
     * the hold before this motion's parts move: a fresh way down ([slide], not [isAway]) with the band wholly above the page now
     * ([bandOff]: its bottom at or over the frame's top edge less [HomeLayoutSolver.AWAY_PAST] - landed away, or held there) waits
     * [HAND_BACK_MS]; a reversal (an up-slide cut on its way, [cutUp], [upMs] old on the wall clock: the band partly on the page)
     * [reversalHoldMs]; a down-slide re-laid inside its hold ([resumeAt] >= 0, the cut one going down) waits what was left of it
     * ([cutHold]); the way up, a glide: none
     */
    fun holdMs(slide: Boolean, isAway: Boolean, bandOff: Boolean, cutUp: Boolean, cutHold: Long, resumeAt: Float, upMs: Long = -1L): Long = when {
        isAway -> 0L
        slide -> if (bandOff) HAND_BACK_MS else if (cutUp) reversalHoldMs(upMs) else 0L
        resumeAt >= 0f && !cutUp -> cutHold.coerceIn(0L, HAND_BACK_MS)
        else -> 0L
    }
    /** the band's box bottom [bottom] lies wholly above the page (laid away: -[HomeLayoutSolver.AWAY_PAST]) */
    fun bandOff(bottom: Float?): Boolean = bottom != null && bottom <= -HomeLayoutSolver.AWAY_PAST + 0.5f
    /** THE BAND STARTS AT THE EDGE: a way down from the band wholly above the page ([bandOff]) first moves it down (unseen) until its
     *  bottom is the page's top edge, so the curve's first, small frames ([DOWN]: 2.6, 12.6 px...) are ON the page - from 16 px past the
     *  edge its first two were spent above it and the first seen one showed 20 px. The dy to add to the band's start box (0 = none) */
    fun edgeShift(bottom: Float?): Float = if (bandOff(bottom)) -bottom!! else 0f

    /** the slide's own linear fraction at [t] ms into a motion that holds [hold] ms and then slides for [ms] */
    fun afterHold(t: Float, hold: Long, ms: Long): Float = ((t - hold) / ms.coerceAtLeast(1L)).coerceIn(0f, 1f)

    /** one part's box at [f] between [from] and [to] (l, t, r, b) - HomeView.apply's frame, and the tests' */
    fun frame(from: FloatArray, to: FloatArray, f: Float, up: Boolean, band: Boolean = true): FloatArray {
        val k = partK(f, up, band)
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
    /** [cubic] in doubles (the brake's constants) */
    private fun cubicD(u: Double, c: FloatArray): Double {
        if (u <= 0.0) return 0.0
        if (u >= 1.0) return 1.0
        fun bz(t: Double, a: Float, b: Float): Double { val m = 1.0 - t; return 3 * m * m * t * a + 3 * m * t * t * b + t * t * t }
        var lo = 0.0; var hi = 1.0
        repeat(60) { val mid = (lo + hi) / 2; if (bz(mid, c[0], c[2]) < u) lo = mid else hi = mid }
        return bz((lo + hi) / 2, c[1], c[3])
    }
}
