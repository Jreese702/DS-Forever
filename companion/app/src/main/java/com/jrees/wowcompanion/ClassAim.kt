package com.jrees.wowcompanion

/**
 * f-class-wheel-sticks (the user 2026-11-16: "one other thing to note on this would be its awkward only working from the right stick. I
 * think that either stick should work those wheels."; pick: "Both sticks aim, no walking"): the class wheels aim with EITHER stick, as the
 * item / spell / pause wheels do (TargetRadialView / HomePauseView's aimStick), and the add-on's stick catch keeps both (Radial.lua r22,
 * which replaces 1.0.9's r15 "On the class radial only the right control stick should be locked"). Pure: CastView feeds it PadInput's
 * vectors (stick 0 = left, 1 = right; x right +, y DOWN +, -1..1) and draws / casts from [aimX] / [aimY].
 *   - a stick is OUT past [neutral] (CastView.CLASS_NEUTRAL), else in the middle
 *   - the aiming stick is the one that went OUT most recently (the other wheels' rule: the stick the player just moved wins; "the one
 *     pushed further" would flick between the two when both are held about as far)
 *   - SAFETY: a stick already OUT when the wheel opens ([open]: the player was walking, or turning the camera) neither aims nor casts
 *     until it has been back in the middle once (it is not ARMED); nothing casts unless a stick aimed after the open
 *   - [move] answers true when the aim ENDS: the aiming stick came back to the middle and no other armed stick is out (Tap + Release's
 *     cast). The aiming stick back while the other armed stick is out HANDS the aim over: no cast. A stick still held from before the
 *     open (not armed) does not hold the cast back: it is not aiming (today's right-stick cast with the walking stick still held)
 *   - [freeOk]: the RIGHT (camera) stick in the middle (armed or not): a wheel already closed lets the controller go then
 *     (CastView.freeLater). The left stick does not hold the free back: handed back pushed it is only the walk the player wants
 *     (walking resumes the moment the wheel closes, as before r22); a right stick handed back pushed would swing the camera (the 1476
 *     fix "class wheels release the stick at once, no B bashing")
 */
internal class ClassAim(private val neutral: Float) {
    private val x = FloatArray(2)
    private val y = FloatArray(2)
    private val out = BooleanArray(2)
    private val armed = booleanArrayOf(true, true)
    /** the stick aiming now (0 left, 1 right), -1 none */
    var aim = -1; private set
    val aimX: Float get() = if (aim < 0) 0f else x[aim]
    val aimY: Float get() = if (aim < 0) 0f else y[aim]

    /** the wheel opened: a stick out now waits for the middle before it can aim */
    fun open() { for (s in 0..1) armed[s] = !out[s]; aim = -1 }

    /** stick [st] at ([nx], [ny]); true = the aim ended (Tap + Release's moment) */
    fun move(st: Int, nx: Float, ny: Float): Boolean {
        if (st !in 0..1) return false
        x[st] = nx; y[st] = ny
        val was = out[st]
        out[st] = kotlin.math.hypot(nx, ny) >= neutral
        if (!out[st]) armed[st] = true
        if (!armed[st]) return false
        if (out[st]) { if (!was || aim < 0) aim = st; return false }
        if (aim != st) return false
        val other = 1 - st
        if (armed[other] && out[other]) { aim = other; return false }
        aim = -1
        return true
    }

    /** the right (camera) stick in the middle: the controller may go */
    fun freeOk(): Boolean = !out[1]
}
