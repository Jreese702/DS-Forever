package com.jrees.wowcompanion

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * THE ONE MOTION TABLE (the Home harmony pass, LATER item 18; the user: "We need a similar smooth transitions accross the board
 * for things like the keyboard and combat"). Every Home transition reads its duration and its curve here, so the module opening,
 * the combat band, the keyboard and the bar cursor move alike. Home's proposal, for UI Art to refine and the user to pick
 * (the one picker); until then only the map's morph uses it.
 */
object Motion {
    /** a module (the map, the chat) opening to the frame / closing back */
    const val MODULE_OPEN = 240L     // smoothness D (the user 2026-10-08, "(c) Also shorter animations"): 320 -> 240
    const val MODULE_CLOSE = 200L    // smoothness D: 260 -> 200
    /** the controller band growing for combat / shrinking after */
    const val COMBAT_GROW = 240L
    const val COMBAT_SHRINK = 200L
    /** Chat's controller keyboard sliding up / down */
    const val KEYBOARD_UP = 200L
    const val KEYBOARD_DOWN = 160L
    /** the bar cursor fading in / out */
    const val BAR_IN = 80L       // b98 (the user: the bar's opening was "really slow"): 120 -> 80 ms
    const val BAR_OUT = 100L

    /** the one curve: fast out, slow in (Material's standard easing) */
    fun curve(): Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** for what leaves the screen (the keyboard going down, HARMONY_SPEC section 3): fast out, linear in */
    fun curveOut(): Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
}
