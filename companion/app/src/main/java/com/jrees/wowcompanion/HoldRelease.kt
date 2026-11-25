package com.jrees.wowcompanion

/**
 * HOLD TO CAST, ROUND 1533: THE FIRE AT THE THRESHOLD (PadMap m199; the user on test 1532, 2026-11-25: "I also want this to cast right
 * when the hold bar is filled, not when its released"). Pure: the clock is the caller's (uptime ms), nothing of Android's.
 *
 * WHY THE APP: the add-on cannot cast on one of the game's 0.2 s looks - a look is the state driver's own OnUpdate, its snippet may set
 * attributes but has no clock and no cast, and a held key sends the game no new edge. The only edges that can cast are the key's own down
 * and up; the down is spent on the keep (the router's PRE snippet keeps the press), so the hold casts on the up. To cast when the ring
 * fills the game has to see the up THEN: every press the game sees is already the launcher's own write of the controller's state, so at
 * the fire point the launcher writes that button as up (the [forcedMask] over player 1's writes, WinHandler) while it is still held, and
 * the router's release path runs the hold text in the secure click exactly as in 1532. Letting go afterwards changes nothing the game
 * sees. Every key-down, every release before the fire point and every button without a kept press is written in the same call, as made.
 *
 * ONLY A PRESS THE ADD-ON SAYS IT KEPT: the router's post-click sends `facecast { hk = "keep", key, pos, lay, need }` for a kept key-down
 * ([keep]); any other word for that position closes it ([closed]). No word by [fireAt] + [KEEP_LATE_MS]: nothing is touched (the release
 * decides, as 1532). An add-on before m199 sends no keep word: nothing is ever touched.
 *
 * THE FIRE POINT [fireMs]: F(n, f) = n x (200 + f) + f + 10 ms - the n-th look comes no later than the keep + n x (200 + f) (the
 * throttle resets at each look, which runs on the first frame past it; events only bring looks earlier), and the up must not be read in
 * the frame of the n-th look (input is handled before OnUpdate: that read counts n - 1, the tap). n = the hold clock's real need (the
 * add-on's `cn`, its wc-hn, not the stored wish), f = the game's longest recent frame (the add-on's `ft`, 25 when unknown), frozen at
 * the press; [bump] (+34 ms per SHORT fire, up to +102) for the rest of the session.
 *
 * Controls (never B): A, X, Y and the D-pad's four, keyed by the keep word's game key (PAD1 / PAD3 / PAD4 / PADDUP ...; else its
 * position). The mask: bits 0 / 2 / 3 = GamepadState.buttons' A / X / Y, bits 4..7 = dpad[0..3] (up, right, down, left) of its own -
 * never bits 1 (B), 4..15 of the buttons (the shoulders, Select, Start, the sticks, L2 / R2), the axes or the sticks.
 */
class HoldRelease {
    companion object {
        const val TICK_MS = 200L
        const val JITTER_MS = 10L
        /** the game's longest frame when the add-on has not said it (40 fps) */
        const val FT_UNKNOWN = 25
        /** a keep word may come this long after the fire point and still fire (a slow log flush) */
        const val KEEP_LATE_MS = 300L
        const val BUMP_STEP_MS = 34L
        const val BUMP_MAX_MS = 102L
        const val NEVER = Long.MAX_VALUE

        const val A = 0; const val X = 1; const val Y = 2; const val UP = 3; const val RIGHT = 4; const val DOWN = 5; const val LEFT = 6
        const val CTLS = 7

        const val IDLE = 0; const val HELD = 1; const val CONFIRMED = 2; const val FORCED = 3; const val GAVE_UP = 4

        /** the fire point after the press (ms): [n] the hold clock's looks, [ft] the game's longest frame (null: unknown) */
        fun fireMs(n: Int, ft: Int?): Long { val f = (ft?.takeIf { it > 0 } ?: FT_UNKNOWN).toLong(); return n * (TICK_MS + f) + f + JITTER_MS }

        /** the game's key -> the control, -1 for none (PAD2 = B: never) */
        fun ctlOfKey(key: String?): Int = when (key) {
            "PAD1" -> A; "PAD3" -> X; "PAD4" -> Y
            "PADDUP" -> UP; "PADDRIGHT" -> RIGHT; "PADDDOWN" -> DOWN; "PADDLEFT" -> LEFT
            else -> -1
        }
        /** the router's position (1 left, 2 up, 3 right, 4 down, 5 X, 6 Y, 8 A; 7 = B: never) -> the control, -1 for none */
        fun ctlOfPos(pos: Int): Int = when (pos) { 1 -> LEFT; 2 -> UP; 3 -> RIGHT; 4 -> DOWN; 5 -> X; 6 -> Y; 8 -> A; else -> -1 }
        /** the control's mask bit */
        fun bitOf(ctl: Int): Int = when (ctl) {
            A -> 1; X -> 1 shl 2; Y -> 1 shl 3; UP -> 1 shl 4; RIGHT -> 1 shl 5; DOWN -> 1 shl 6; LEFT -> 1 shl 7; else -> 0
        }
        /** the mask's buttons part (A / X / Y only) */
        const val BUTTON_BITS = 0x0D

        /** GamepadState.buttons as the game gets it under [mask]: A / X / Y up where forced, every other bit as it is */
        fun outButtons(buttons: Int, mask: Int): Int = buttons and (mask and BUTTON_BITS).inv()
        /** dpad[i] (0 up, 1 right, 2 down, 3 left) as the game gets it under [mask] */
        fun outDpad(i: Int, v: Boolean, mask: Int): Boolean = v && (mask and (1 shl (4 + i))) == 0
        /** a control's physical state in an input: [buttons] GamepadState's, [dpad] up / right / down / left */
        fun isDown(ctl: Int, buttons: Int, dpad: BooleanArray): Boolean = when (ctl) {
            A -> buttons and 1 != 0; X -> buttons and (1 shl 2) != 0; Y -> buttons and (1 shl 3) != 0
            UP -> dpad[0]; RIGHT -> dpad[1]; DOWN -> dpad[2]; LEFT -> dpad[3]; else -> false
        }
    }

    private val st = IntArray(CTLS)
    private val t0 = LongArray(CTLS)
    private val fireAt = LongArray(CTLS) { NEVER }
    private val cnAt = IntArray(CTLS)
    /** the keep word's position and layer (0 none, 1 LT, 2 RT, 3 LT + RT) of the press on each control */
    private val kpos = IntArray(CTLS)
    private val klay = IntArray(CTLS)

    /** the hold clock's looks as the add-on says them (`cn`); null = unknown (never an early fire) */
    var cn: Int? = null
        @Synchronized set(v) {
            field = v?.takeIf { it in 2..4 }
            // a need that changed while a press is held: that press's early release is off (the release decides)
            for (c in 0 until CTLS) if ((st[c] == HELD || st[c] == CONFIRMED) && cnAt[c] != (field ?: -1)) giveUp(c)
        }
    /** the game's longest recent frame (ms) as the add-on says it (`ft`); null = unknown */
    @Volatile var ft: Int? = null
    /** the session's correction after a SHORT fire (ms) */
    var bump = 0L
        private set
    /** the gates (true = open): read at the fire moment; closed = no early release, the release decides */
    var gatesOpen: () -> Boolean = { true }
    /** a hold fired at (game position, its layer): the picture's takeover */
    var onFire: ((Int, Int) -> Unit)? = null
    private var paused = false

    /** the controls forced up now (read on every write: volatile, read once per write) */
    @Volatile var forcedMask = 0
        private set

    @Synchronized fun state(ctl: Int): Int = st.getOrElse(ctl) { IDLE }
    @Synchronized fun fireAtOf(ctl: Int): Long = fireAt.getOrElse(ctl) { NEVER }

    /** the control [ctl] went down / up at [now] (the write that carries it follows at once) */
    @Synchronized fun edge(ctl: Int, down: Boolean, now: Long) {
        if (ctl !in 0 until CTLS) return
        if (down) {
            if (st[ctl] == FORCED) unforce(ctl)                          // an up we never saw: this is a new press, it passes at once
            st[ctl] = HELD; t0[ctl] = now; kpos[ctl] = 0; klay[ctl] = 0
            val n = cn
            cnAt[ctl] = n ?: -1
            fireAt[ctl] = if (n == null || paused) NEVER else now + fireMs(n, ft) + bump
        } else {
            if (st[ctl] == FORCED) unforce(ctl)                          // the physical up after the fire: nothing new for the game
            st[ctl] = IDLE; fireAt[ctl] = NEVER
        }
    }

    /** the add-on kept a press (its keep word: [key] the game's key, else [pos]; [lay] 0..3; [need] its clock's looks): true when
     *  that press is now CONFIRMED (the caller schedules [nextAt]) */
    @Synchronized fun keep(key: String?, pos: Int, lay: Int, need: Int?, now: Long): Boolean {
        if (key == "PAD2" || pos == 7) return false                      // never B
        val ctl = ctlOfKey(key).takeIf { it >= 0 } ?: (if (key.isNullOrEmpty()) ctlOfPos(pos) else -1)
        if (ctl < 0 || st[ctl] != HELD) return false
        if (need != null && need != cnAt[ctl]) { giveUp(ctl); return false }
        if (fireAt[ctl] == NEVER || now > fireAt[ctl] + KEEP_LATE_MS) { giveUp(ctl); return false }
        st[ctl] = CONFIRMED; kpos[ctl] = pos; klay[ctl] = lay.coerceIn(0, 3)
        return true
    }

    /** another word for position [pos] (a press that was not kept, an up, a kept press's release): a confirmed press there waits for
     *  its own keep word again (a stale keep of a quick tap before it is closed by that tap's own words) */
    @Synchronized fun closed(pos: Int, now: Long) {
        for (c in 0 until CTLS) if (st[c] == CONFIRMED && kpos[c] == pos) { st[c] = HELD; kpos[c] = 0 }
    }

    /** the fires due at [now]; true = the output changed (send the state again) */
    @Synchronized fun due(now: Long): Boolean {
        var changed = false
        for (c in 0 until CTLS) {
            when (st[c]) {
                CONFIRMED -> if (now >= fireAt[c]) {
                    if (paused || !gatesOpen()) { giveUp(c); continue }
                    st[c] = FORCED
                    forcedMask = forcedMask or bitOf(c)
                    changed = true
                    onFire?.invoke(kpos[c], klay[c])
                }
                HELD -> if (fireAt[c] != NEVER && now > fireAt[c] + KEEP_LATE_MS) giveUp(c)
            }
        }
        return changed
    }

    /** the next moment [due] has work: a confirmed press's fire point */
    @Synchronized fun nextAt(): Long {
        var n = NEVER
        for (c in 0 until CTLS) if (st[c] == CONFIRMED && fireAt[c] < n) n = fireAt[c]
        return n
    }

    /** the button at game position [pos] is forced up now (Home draws it as the game has it) */
    @Synchronized fun forcedPos(pos: Int): Boolean { for (c in 0 until CTLS) if (st[c] == FORCED && kpos[c] == pos) return true; return false }

    /** a fire the add-on answered with the TAP (the up beat the n-th look): later fires wait [BUMP_STEP_MS] longer */
    @Synchronized fun short() { bump = (bump + BUMP_STEP_MS).coerceAtMost(BUMP_MAX_MS) }

    /** the app lost the game screen: nothing fires while away (a forced button stays up for the game until its physical up) */
    @Synchronized fun pause() { paused = true; cancelPending() }
    /** back: a forced control that reads up now ([isDown]: the physical state) is let go */
    @Synchronized fun resume(isDown: (Int) -> Boolean) {
        paused = false
        for (c in 0 until CTLS) if (st[c] == FORCED && !isDown(c)) { unforce(c); st[c] = IDLE }
    }
    /** every pending fire off (the release decides); forced controls stay up for the game until their physical up */
    @Synchronized fun cancelPending() { for (c in 0 until CTLS) if (st[c] == HELD || st[c] == CONFIRMED) giveUp(c) }
    /** everything clean (a new session, the tests) */
    @Synchronized fun reset() {
        st.fill(IDLE); fireAt.fill(NEVER); kpos.fill(0); klay.fill(0); forcedMask = 0; paused = false; bump = 0L
    }

    private fun giveUp(c: Int) { st[c] = GAVE_UP; fireAt[c] = NEVER }
    private fun unforce(c: Int) { forcedMask = forcedMask and bitOf(c).inv() }
}
