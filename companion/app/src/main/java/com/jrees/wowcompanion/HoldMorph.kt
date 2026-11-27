package com.jrees.wowcompanion

/**
 * feat/hold-morph (the user, 2026-11-27: "I want there to be a slick transition on a button hold, I want the button hold to transition
 * that small circle into the big one and then back after the cast is complete or canceled"): THE MORPH's clock - 0 = the hold spell on
 * its small corner badge and the tap spell on the big face, 1 = the hold spell has the big face (the takeover). Going in it eases out
 * over [inMs], going back over [outMs] (both an ease-out cubic: quick off the mark, soft landing). A turn mid-way ([toward]) starts from
 * the value shown at that moment, over the part of the way left - never a jump, so a new press or a cancel mid-morph retargets smoothly.
 * Pure (the caller's clock, ms); the drawing only reads it: it never holds a cast or an input back.
 */
class HoldMorph(private val inMs: Long = IN_MS, private val outMs: Long = OUT_MS) {
    /** 1 going in (to the big face), -1 going back (to the badge), 0 at rest on the badge */
    var dir = 0
        private set
    private var from = 0f
    private var at = 0L

    /** the morph at [now]: 0..1 */
    fun value(now: Long): Float {
        if (dir == 0) return 0f
        val e = ease(((now - at).toFloat() / dur()).coerceIn(0f, 1f))
        return if (dir > 0) from + (1f - from) * e else from * (1f - e)
    }

    /** this way's length: the part of the way left of the whole [inMs] / [outMs] (at least [MIN_MS]) */
    private fun dur(): Float = maxOf(MIN_MS, if (dir > 0) inMs * (1f - from) else outMs * from)

    /** head in ([inside]) or back from [now], from the value shown then; already heading that way: nothing changes */
    fun toward(inside: Boolean, now: Long) {
        val want = if (inside) 1 else -1
        if (dir == want) return
        if (!inside && dir == 0) return                                  // at rest already
        start(value(now), inside, now)
    }

    /** head in ([inside]) or back from [v] at [now] (a ghost handed back to the takeover keeps its own value) */
    fun start(v: Float, inside: Boolean, now: Long) {
        from = v.coerceIn(0f, 1f); at = now; dir = if (inside) 1 else -1
        if (!inside && from <= 0f) dir = 0
    }

    /** still moving at [now] (not at either end yet) */
    fun moving(now: Long): Boolean = dir != 0 && (now - at) < dur()

    /** when this way lands (the caller's clock), or [Long.MIN_VALUE] at rest */
    fun endsAt(): Long = if (dir == 0) Long.MIN_VALUE else at + dur().toLong()

    fun clear() { dir = 0; from = 0f }

    companion object {
        /** the way in: the badge grows into the big face (the brief: about 160-220 ms) */
        const val IN_MS = 180L
        /** the way back: the same as the takeover's own ease ([HoldCast.EASE_MS]), so it lands as the takeover lets go */
        const val OUT_MS = HoldCast.EASE_MS
        const val MIN_MS = 1f

        /** the ease-out cubic */
        fun ease(x: Float): Float { val k = 1f - x.coerceIn(0f, 1f); return 1f - k * k * k }

        /** the hermite smoothstep of [x] between [a] and [b] */
        fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

        /** the big face's own picture (the checked slot) over the moving circle: fades in between these morph values */
        const val FACE_FROM = 0.15f
        const val FACE_TO = 0.65f
        /** the tap spell's art at the full morph: this much smaller (and gone) */
        const val TAP_SHRINK = 0.4f
        /** the face's share of the moving circle's look at morph [m] (0 = all badge, 1 = all checked face) */
        fun faceShare(m: Float): Float = smooth(FACE_FROM, FACE_TO, m)
        /** the charge ring rides the moving circle but fades as it grows (gone by this morph: its gold would be too heavy at the face's
         *  size; the checked face's own gold ring takes over) */
        const val RING_TO = 0.7f
        fun ringAlpha(m: Float): Float = 1f - smooth(0f, RING_TO, m)
        /** the tap spell's scale and alpha at morph [m] */
        fun tapScale(m: Float): Float = 1f - TAP_SHRINK * m.coerceIn(0f, 1f)
        fun tapAlpha(m: Float): Float = 1f - m.coerceIn(0f, 1f)
    }
}
