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
    /** In fights = Top screen (TopSlide; the user 2026-11-22: "a slick animation looking like it slides up to the top screen"): the band
     *  sliding up off the bottom screen's top edge as the columns grow up, and back down after the fight */
    const val TOP_SLIDE_UP = 240L
    const val TOP_SLIDE_DOWN = 240L
    /** Chat's controller keyboard sliding up / down */
    const val KEYBOARD_UP = 200L
    const val KEYBOARD_DOWN = 160L
    /** loot-pop (the user, 2026-11-16: "centered and then animate over to the side to make space for the tooltip"): a window sliding
     *  aside for its tooltip, and back (the keyboard's quick pace) */
    const val SLIDE = 180L
    /** the bar cursor fading in / out */
    const val BAR_IN = 80L       // b98 (the user: the bar's opening was "really slow"): 120 -> 80 ms
    const val BAR_OUT = 100L
    /** bags-gear1 THE EQUIP SLIDE-OUT (the user, 2026-11-16: "When pressed, X animates the options sliding out"; the pick D5a "Quick"):
     *  the icons slide out of the slot in [RAIL_OUT], each [RAIL_STAGGER] after the one before it, and back in [RAIL_IN] (the chosen
     *  one flies into the slot) */
    const val RAIL_OUT = 160L
    const val RAIL_STAGGER = 15L
    const val RAIL_IN = 120L

    // ---------------------------------------------------------------- edit mode's springs (HomeEditMotion; the user, 2026-11-21:
    // "resemble more of like an iPhone app edit mode with apps kind of animating zoomed smaller a bit and being more animated")
    // Each spring is a damping ratio (ZETA) and a natural frequency (W, rad/s); HomeEditMotion evaluates them in closed form.

    /** every widget at rest in edit mode, zoomed out a little; a lifted one */
    const val REST_SCALE = 0.94f
    const val LIFT = 1.04f
    /** edit mode opening (the shrink, ~330 ms, a hair past 0.94) / leaving (back to 1) */
    const val ENTER_ZETA = 0.72f; const val ENTER_W = 17f
    const val LEAVE_ZETA = 0.80f; const val LEAVE_W = 19f
    /** the pick-up (peaks ~1.048) and the drop's plop (~0.93, then 0.94) and its slide into the dock (~2 % past it) */
    const val LIFT_ZETA = 0.62f; const val LIFT_W = 24f
    const val DROP_ZETA = 0.55f; const val DROP_W = 22f
    const val DROP_OFFSET_ZETA = 0.78f; const val DROP_OFFSET_W = 20f
    /** the gold cursor flying between widgets (~180 ms) */
    const val CURSOR_ZETA = 0.85f; const val CURSOR_W = 30f
    /** a widget's menu, the Add tray, the edit ribbon, the red X chip */
    const val MENU_ZETA = 0.70f; const val MENU_W = 26f
    const val SHEET_ZETA = 0.85f; const val SHEET_W = 22f
    const val RIBBON_ZETA = 0.80f; const val RIBBON_W = 22f
    const val CHIP_ZETA = 0.50f; const val CHIP_W = 30f
    /** a layout glide under edit mode: the box's centre (a little overshoot) and its size (none: never laid past its target) */
    const val GLIDE_ZETA = 0.82f
    /** the wiggle (the user's pick 2026-11-21, "Noticeable"): Hz; a room's small picture turns [WIGGLE_DEG], a big module (the chat, the
     *  map, the party module, the card...) [WIGGLE_DEG_BIG] - a box of half-diagonal [WIGGLE_SMALL] or less the first, [WIGGLE_BIG] or
     *  more the second, a size between in between - and no corner travels past [WIGGLE_PX] (the band across the page a hair under
     *  0.8 deg); the bob (px).
     *  1518 feel pass (the user, 2026-11-22: "a bit too fast and jarring especially on the bigger things"): slower everywhere - a small
     *  picture (a room, a chip: half-diagonal under [WIGGLE_PACE_SPLIT]) at [WIGGLE_HZ], a big module at the calmer [WIGGLE_HZ_BIG] - and
     *  a big module turns less ([WIGGLE_DEG_BIG], its corners no further than [WIGGLE_PX]) */
    const val WIGGLE_HZ = 4.5f
    const val WIGGLE_HZ_BIG = 2.2f
    const val WIGGLE_PACE_SPLIT = 100f
    const val WIGGLE_DEG = 1.2f
    const val WIGGLE_DEG_BIG = 0.45f
    const val WIGGLE_SMALL = 40f
    const val WIGGLE_BIG = 160f
    const val WIGGLE_PX = 4.5f
    const val WIGGLE_BOB = 0.6f
    /** the wiggle fading in after the open / out on a lift, a leave; quieter while a menu or the Add tray is open. It never rests
     *  while edit mode is open (the user's pick 2026-11-21, "Never rest"): a fight, Home off screen and the animations off stop it */
    const val WIGGLE_IN = 140L
    const val WIGGLE_OUT = 90L
    const val WIGGLE_CALM = 0.4f
    /** the open's ripple out from the held widget */
    const val ENTER_RIPPLE = 60L

    /** the one curve: fast out, slow in (Material's standard easing) */
    fun curve(): Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** for what leaves the screen (the keyboard going down, HARMONY_SPEC section 3): fast out, linear in */
    fun curveOut(): Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** for what comes onto the screen (the band sliding back down after a Top screen fight, TopSlide): fast in, slow at the end */
    fun curveEnter(): Interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)
}
