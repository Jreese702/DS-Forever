package com.jrees.wowcompanion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * EDIT MODE'S MOTION (the user, 2026-11-21: "I want the edit mode to resemble more of like an iPhone app edit mode with apps kind of
 * animating zoomed smaller a bit and being more animated generally"). Pure (no Android): every value is closed form at a given time, so
 * a test, a shot or a pinned clock reads any frame exactly.
 *
 *  - every widget (and every room on the room bar) springs down to [Motion.REST_SCALE] as edit mode opens, rippling out from the widget
 *    it opened on, and back to 1 as it leaves;
 *  - each wiggles (a small turn and bob, its own phase and pace; a room turns [Motion.WIGGLE_DEG], a big module [Motion.WIGGLE_DEG_BIG],
 *    no corner past [Motion.WIGGLE_PX]) the whole time edit mode is open (no idle rest: the user's pick 2026-11-21, "Never rest");
 *  - a lifted widget grows to [Motion.LIFT] (a little past it) and stops wiggling; dropped, it plops a hair below its rest and its finger
 *    offset springs into the dock;
 *  - the gold cursor's box flies between widgets; the menus, the Add tray, the X chip and the edit ribbon pop or slide in.
 *
 * Time runs at t / [scale] (the system's animator duration scale); 0 = no motion at all (the static shrink). [still] is identity at
 * once (a fight, a room over Home). The model's geometry never sees any of this: it is drawn only (HomeView's views' properties and the
 * chrome's canvas).
 */
class HomeEditMotion {

    /** the animator duration scale (Settings > Developer options; 0 = animations off) */
    var scale = 1f
        set(v) { field = if (v.isNaN()) 1f else v.coerceAtLeast(0f); if (field == 0f) snapAll() }

    /** a session's motion is on (from [enter] until a [leave] lands or [still]) */
    var on = false
        private set
    private var leaving = false
    private var epoch = 0L

    // ---------------------------------------------------------------- a spring

    /** one retargetable spring: closed form from (from, v0) at [at] to [to]; [eps] = close enough to snap (its own units) */
    inner class Spring(initial: Float, private val eps: Float) {
        var to = initial; private set
        private var from = initial
        private var v0 = 0f
        private var at = 0L
        private var zeta = 1f
        private var omega = 20f
        /** the time (s, scaled) past which it is within [eps] of [to] and lands there exactly */
        private var tEnd = 0f
        private val out = FloatArray(2)

        private fun eval(now: Long) {
            if (scale <= 0f || tEnd <= 0f) { out[0] = to; out[1] = 0f; return }
            val t = (now - at).toFloat() / 1000f / scale
            if (t <= 0f) { out[0] = from; out[1] = v0; return }
            if (t >= tEnd) { out[0] = to; out[1] = 0f; return }
            springAt(from, to, v0, zeta, omega, t, out)
        }
        fun value(now: Long): Float { eval(now); return out[0] }
        fun velocity(now: Long): Float { eval(now); return out[1] }
        fun done(now: Long): Boolean = scale <= 0f || tEnd <= 0f || (now - at).toFloat() / 1000f / scale >= tEnd

        /** to [target] from where it is now (its speed carried), starting [delay] ms from [now] */
        fun set(target: Float, now: Long, z: Float, w: Float, delay: Long = 0L) {
            eval(now)
            jump(out[0], target, if (delay > 0L) 0f else out[1], now + scaled(delay), z, w)
        }
        /** from [x] (speed [v]) to [target] */
        fun jump(x: Float, target: Float, v: Float, start: Long, z: Float, w: Float) {
            from = x; to = target; v0 = v; at = start; zeta = z; omega = w
            tEnd = if (scale <= 0f) 0f else settleTime(from - to, v0, zeta, omega, eps)
        }
        fun snap(x: Float) { from = x; to = x; v0 = 0f; tEnd = 0f }
        internal fun land() { snap(to) }
    }

    // ---------------------------------------------------------------- per widget

    /** the shrink (1 <-> REST) and the lift (1 <-> LIFT / REST) of each key, the drop's offset into its dock */
    private val base = Array(KEYS) { Spring(1f, 0.0005f) }
    private val lift = Array(KEYS) { Spring(1f, 0.0005f) }
    private val offX = Array(KEYS) { Spring(0f, 0.25f) }
    private val offY = Array(KEYS) { Spring(0f, 0.25f) }
    /** the wiggle's envelope per key (0 .. 1): a ramp [envFrom] -> [envTo] from [envAt] over [envMs] */
    private val envFrom = FloatArray(KEYS)
    private val envTo = FloatArray(KEYS)
    private val envAt = LongArray(KEYS)
    private val envMs = LongArray(KEYS)
    /** quieter while a menu / the Add tray is open (1 .. CALM) */
    private var calmFrom = 1f
    private var calmTo = 1f
    private var calmAt = 0L
    /** the lifted key (-1 = none) */
    var lifted = -1
        private set

    /** a delay (ms) on the scaled clock */
    private fun scaled(ms: Long): Long = (ms * scale).toLong()
    private fun ramp(from: Float, to: Float, at: Long, ms: Long, now: Long): Float {
        if (scale <= 0f || ms <= 0L) return to
        val k = ((now - at).toFloat() / ms / scale).coerceIn(0f, 1f)
        return from + (to - from) * (k * k * (3f - 2f * k))
    }
    private fun env(k: Int, now: Long) = ramp(envFrom[k], envTo[k], envAt[k], envMs[k], now)
    private fun envSet(k: Int, to: Float, now: Long, ms: Long, delay: Long = 0L) {
        envFrom[k] = env(k, now); envTo[k] = to; envAt[k] = now + scaled(delay); envMs[k] = ms
    }
    private fun calm(now: Long) = ramp(calmFrom, calmTo, calmAt, Motion.WIGGLE_OUT, now)

    // ---------------------------------------------------------------- the session

    /**
     * Edit mode opens (or comes back after a fight / a room): every key springs to REST, [delays] (ms per key, the ripple out from the
     * widget it opened on; null = all at once), and starts to wiggle.
     */
    fun enter(now: Long, delays: LongArray? = null) {
        val was = on && !leaving
        on = true; leaving = false
        epoch = now
        calmFrom = 1f; calmTo = 1f; calmAt = now
        for (k in 0 until KEYS) {
            val d = delays?.getOrNull(k) ?: 0L
            base[k].set(Motion.REST_SCALE, now, Motion.ENTER_ZETA, Motion.ENTER_W, d)
            envFrom[k] = 0f; envTo[k] = if (k == lifted) 0f else 1f; envAt[k] = now + scaled(Motion.ENTER_RIPPLE + d); envMs[k] = Motion.WIGGLE_IN
        }
        // the ribbon slides up over the XP track (from where it is when edit mode comes back mid-leave)
        pops[RIBBON].jump(if (was || leavingRibbon(now)) pops[RIBBON].value(now) else 0f, 1f, 0f, now, Motion.RIBBON_ZETA, Motion.RIBBON_W)
        if (scale <= 0f) snapAll()
    }
    private fun leavingRibbon(now: Long) = !pops[RIBBON].done(now)

    /** edit mode ends: back to 1, the wiggle out, the ribbon down; [on] goes false once it has landed ([busy] false) */
    fun leave(now: Long) {
        if (!on) return
        leaving = true
        for (k in 0 until KEYS) {
            base[k].set(1f, now, Motion.LEAVE_ZETA, Motion.LEAVE_W)
            lift[k].set(1f, now, Motion.LEAVE_ZETA, Motion.LEAVE_W)
            envSet(k, 0f, now, Motion.WIGGLE_OUT)
        }
        lifted = -1
        pops[RIBBON].set(0f, now, Motion.RIBBON_ZETA, Motion.RIBBON_W)
        if (scale <= 0f) { snapAll(); on = false }
    }

    /** identity at once (a fight, a room over Home, Home gone): nothing moves until [enter] */
    fun still() {
        on = false; leaving = false; lifted = -1
        for (k in 0 until KEYS) { base[k].snap(1f); lift[k].snap(1f); offX[k].snap(0f); offY[k].snap(0f); envFrom[k] = 0f; envTo[k] = 0f; envMs[k] = 0L }
        for (p in pops) p.snap(1f)
        for (c in cursor) c.snap(0f)
    }

    /** a menu or the Add tray open: the wiggle calms to [Motion.WIGGLE_CALM] */
    fun modal(open: Boolean, now: Long) {
        val to = if (open) Motion.WIGGLE_CALM else 1f
        if (to == calmTo) return
        calmFrom = calm(now); calmTo = to; calmAt = now
    }

    /** [k] picked up: it grows to LIFT and stops wiggling */
    fun lift(k: Int, now: Long) {
        if (k !in 0 until KEYS) return
        lifted = k
        lift[k].set(Motion.LIFT / Motion.REST_SCALE, now, Motion.LIFT_ZETA, Motion.LIFT_W)
        envSet(k, 0f, now, Motion.WIGGLE_OUT * 2 / 3)
    }

    /** [k] dropped ([ox], [oy] = its finger offset at the release, 0 for the controller): it plops into its rest and its dock */
    fun drop(k: Int, ox: Float, oy: Float, now: Long) {
        if (k !in 0 until KEYS) return
        if (lifted == k) lifted = -1
        lift[k].set(1f, now, Motion.DROP_ZETA, Motion.DROP_W)
        if (ox != 0f || oy != 0f) {
            offX[k].jump(ox, 0f, 0f, now, Motion.DROP_OFFSET_ZETA, Motion.DROP_OFFSET_W)
            offY[k].jump(oy, 0f, 0f, now, Motion.DROP_OFFSET_ZETA, Motion.DROP_OFFSET_W)
        }
        envSet(k, if (on && !leaving) 1f else 0f, now, Motion.WIGGLE_IN, DROP_WIGGLE_AFTER)
    }

    /** the lift's share now (0 .. 1: the shadow, the depth) of [k] */
    fun liftK(k: Int, now: Long): Float = if (k in 0 until KEYS && on) liftShare(lift[k].value(now)) else 0f
    private fun liftShare(v: Float) = ((v - 1f) / (Motion.LIFT / Motion.REST_SCALE - 1f)).coerceIn(0f, 1f)
    /** [k]'s lift spring has landed (its plop included) */
    fun liftDone(k: Int, now: Long): Boolean = k !in 0 until KEYS || lift[k].done(now)
    /** [k]'s drop offset is still springing into its dock */
    fun settling(k: Int, now: Long): Boolean = k in 0 until KEYS && (!offX[k].done(now) || !offY[k].done(now))

    /** one key's pose now; [halfDiag] = half its box's diagonal (px: the wiggle's turn by its size, [amp]) */
    fun pose(k: Int, now: Long, halfDiag: Float, out: Pose): Pose {
        if (!on || k !in 0 until KEYS) return out.identity()
        out.scale = base[k].value(now) * lift[k].value(now)
        out.dx = offX[k].value(now)
        out.dy = offY[k].value(now)
        out.rot = 0f; out.reach = 0f; out.bob = 0f; out.bobMax = 0f
        if (scale > 0f) {
            val e = env(k, now) * calm(now)
            if (e > 0f) {
                val t = (now - epoch).toFloat() / 1000f / scale
                val ph = 2f * PI.toFloat() * hzFor(k, halfDiag) * t + phase(k)
                out.reach = amp(halfDiag) * e
                out.rot = out.reach * sin(ph)
                out.bobMax = Motion.WIGGLE_BOB * e
                out.bob = out.bobMax * cos(ph)
                out.dy += out.bob
            }
        }
        return out
    }

    /**
     * a pose: the scale about its widget's centre, the turn (deg), the offset (px). [reach] = the most it turns at its wiggle's envelope
     * now (deg), [bob] = the bob's share of [dy] now, [bobMax] its most: [keepIn] keeps the whole swing inside the frame, so its shift is
     * steady over a wiggle's cycle (no corner pressing on the frame's edge)
     */
    class Pose {
        var scale = 1f; var rot = 0f; var dx = 0f; var dy = 0f
        var reach = 0f; var bob = 0f; var bobMax = 0f
        fun identity(): Pose { scale = 1f; rot = 0f; dx = 0f; dy = 0f; reach = 0f; bob = 0f; bobMax = 0f; return this }
        val isIdentity: Boolean get() = scale == 1f && rot == 0f && dx == 0f && dy == 0f
    }

    // ---------------------------------------------------------------- the cursor, the pops

    /** the gold box's flight: its four edges (l, t, r, b, px), then its turn (deg) */
    private val cursor = Array(5) { Spring(0f, if (it == TURN) 0.01f else 0.25f) }
    /**
     * the gold box leaves the box it was drawn at: [d] = old drawn edge - new edge (l, t, r, b), [turn] = the old box's drawn angle - the
     * new widget's (deg: the two widgets wiggle on their own phases, up to 1.6 deg apart at 0.8 each), each springing to 0 on the same
     * spring (its speed carried), so its first frame is the old drawn box, corners too
     */
    fun cursorFrom(dl: Float, dt: Float, dr: Float, db: Float, now: Long, turn: Float = 0f) {
        cursorEdge(0, dl, now); cursorEdge(1, dt, now); cursorEdge(2, dr, now); cursorEdge(3, db, now); cursorEdge(TURN, turn, now)
    }
    private fun cursorEdge(i: Int, d: Float, now: Long) =
        cursor[i].jump(d, 0f, cursor[i].velocity(now), now, Motion.CURSOR_ZETA, Motion.CURSOR_W)
    /** the cursor's offset now per edge (l, t, r, b) */
    fun cursorOffset(now: Long, out: FloatArray) { for (i in 0..3) out[i] = if (on) cursor[i].value(now) else 0f }
    /** the cursor's turn now (deg) over its widget's: HomeEditView turns the gold box by it about the box's centre */
    fun cursorTurn(now: Long): Float = if (on) cursor[TURN].value(now) else 0f

    private val pops = Array(POPS) { Spring(1f, 0.002f) }
    /** a menu / the Add tray / the X chip opening: its pop from 0 (1 = landed; past 1 a little on its overshoot) */
    fun pop(kind: Int, now: Long) {
        if (kind == RIBBON) return
        val (z, w) = when (kind) {
            MENU -> Motion.MENU_ZETA to Motion.MENU_W
            SHEET -> Motion.SHEET_ZETA to Motion.SHEET_W
            else -> Motion.CHIP_ZETA to Motion.CHIP_W
        }
        pops[kind].jump(0f, 1f, 0f, now, z, w)
    }
    fun popK(kind: Int, now: Long): Float = if (!on && kind != RIBBON) 1f else pops[kind].value(now)

    // ---------------------------------------------------------------- the clock

    /** anything still moving at [now] (the ticker runs while true) */
    fun busy(now: Long): Boolean {
        if (!on) return false
        if (scale <= 0f) return false
        for (k in 0 until KEYS) {
            if (!base[k].done(now) || !lift[k].done(now) || !offX[k].done(now) || !offY[k].done(now)) return true
        }
        for (p in pops) if (!p.done(now)) return true
        for (c in cursor) if (!c.done(now)) return true
        if (leaving) { on = false; return false }
        return wiggling(now)
    }
    /** the wiggle still shows (an envelope over 0): the whole time edit mode is open (it never rests) */
    fun wiggling(now: Long): Boolean {
        if (!on || scale <= 0f) return false
        for (k in 0 until KEYS) if (env(k, now) > 0f || envTo[k] > 0f) return true
        return false
    }

    private fun snapAll() {
        for (k in 0 until KEYS) {
            base[k].land(); lift[k].land(); offX[k].land(); offY[k].land()
            envFrom[k] = envTo[k]; envMs[k] = 0L
        }
        for (p in pops) p.land()
        for (c in cursor) c.land()
        calmFrom = calmTo
        if (leaving) on = false
    }

    companion object {
        /** keys: an EditStop's ordinal (0 .. 9), the party module, then the room bar's rooms ([roomKey]) */
        const val KEYS = 48
        const val PARTY = 10
        fun roomKey(ordinal: Int): Int = (ROOM0 + ordinal).coerceAtMost(KEYS - 1)
        private const val ROOM0 = 16
        /** 1518 build 2: the room bar's "+" spot wiggles as its own app (the key after the last room's; worked out once: the review) */
        @JvmField val PLUS_ROOM: Int = roomKey(Room.values().size)
        /** P2 (the user's ruling 2026-11-22): the clock's extras wiggle as apps, each on its own key (its place in
         *  HomeEditModel.EXTRA_KEYS: the key stays whatever else is shown), the extras' "+" spot after them; past the room bar's keys */
        private const val EXTRA0 = 32
        fun extraKey(i: Int): Int = (EXTRA0 + i).coerceAtMost(KEYS - 1)
        @JvmField val PLUS_EXTRA: Int = extraKey(HomeEditModel.EXTRA_KEYS.size)
        /** the pops */
        const val MENU = 0
        const val SHEET = 1
        /** (2 was 1517's Save / Discard prompt: gone in 1518, the indices kept) */
        const val CHIP = 3
        const val RIBBON = 4
        private const val POPS = 5
        /** the cursor's turn spring (after its four edges) */
        private const val TURN = 4
        /** a dropped widget wiggles again after this (ms) */
        const val DROP_WIGGLE_AFTER = 120L
        /** the share of a glide still to go when its animator ends: the glide's length = GLIDE_END / (zeta omega) */
        private const val GLIDE_END = 7.0f

        /**
         * A damped spring's position and speed at [t] s, from [from] (speed [v0]) to [to]: under-damped (zeta < 1) or critically
         * damped (zeta >= 1, treated as 1). Into [out] (x, v) when given; returns x.
         */
        fun springAt(from: Float, to: Float, v0: Float, zeta: Float, omega: Float, t: Float, out: FloatArray? = null): Float {
            val a = from - to
            val x: Float; val v: Float
            if (zeta < 1f) {
                val wd = omega * sqrt(1f - zeta * zeta)
                val b = (v0 + zeta * omega * a) / wd
                val e = exp(-zeta * omega * t)
                val c = cos(wd * t); val s = sin(wd * t)
                x = to + e * (a * c + b * s)
                v = e * (-zeta * omega * (a * c + b * s) + (-a * wd * s + b * wd * c))
            } else {
                val c = v0 + omega * a
                val e = exp(-omega * t)
                x = to + (a + c * t) * e
                v = e * (c - omega * (a + c * t))
            }
            if (out != null) { out[0] = x; out[1] = v }
            return x
        }

        /** when (s) a spring from offset [a] (speed [v0]) is within [eps] of its target for good (a bound on its envelope) */
        fun settleTime(a: Float, v0: Float, zeta: Float, omega: Float, eps: Float): Float {
            if (abs(a) < eps && abs(v0) < eps) return 0f
            val (m, rate) = if (zeta < 1f) {
                val wd = omega * sqrt(1f - zeta * zeta)
                (abs(a) + abs((v0 + zeta * omega * a) / wd)) to zeta * omega
            } else (abs(a) + 2f * abs(v0 + omega * a) / omega) to 0.5f * omega
            return if (m <= eps) 1f / omega else ln(m / eps) / rate
        }

        /** a glide of the old [ms] gets this omega: 95 % of the way there at [ms] */
        fun omegaFor(ms: Long): Float = 4.74f / (max(1L, ms) / 1000f)
        /** a spring glide's animator length for the old [ms] */
        fun glideMs(ms: Long): Long = (GLIDE_END / (Motion.GLIDE_ZETA * omegaFor(ms)) * 1000f).toLong()

        /**
         * One edge pair of a part's box gliding under edit mode at [t] s: its centre on GLIDE_ZETA (a little overshoot), its size
         * ([size]) on the same spring kept inside [from, to] (a module is never laid past the size it is going to). (x, v) into [out].
         */
        fun glide(from: Float, to: Float, v0: Float, ms: Long, t: Float, size: Boolean, out: FloatArray) {
            if (t <= 0f) { out[0] = from; out[1] = v0; return }
            springAt(from, to, v0, Motion.GLIDE_ZETA, omegaFor(ms), t, out)
            if (size) {
                val lo = min(from, to); val hi = max(from, to)
                if (out[0] < lo || out[0] > hi) { out[0] = out[0].coerceIn(lo, hi); out[1] = 0f }
            }
        }

        /**
         * the wiggle's largest turn (deg) for a box of half-diagonal [halfDiag] (the user's pick 2026-11-21, "Noticeable"): a room's
         * picture (WIGGLE_SMALL or less) WIGGLE_DEG, a big module (WIGGLE_BIG or more) WIGGLE_DEG_BIG, a size between on the line between;
         * no corner travels past WIGGLE_PX (only a box wider than the chat and the map, the band across the page, turns a little less)
         */
        fun amp(halfDiag: Float): Float {
            if (halfDiag <= Motion.WIGGLE_SMALL) return Motion.WIGGLE_DEG
            val k = ((halfDiag - Motion.WIGGLE_SMALL) / (Motion.WIGGLE_BIG - Motion.WIGGLE_SMALL)).coerceIn(0f, 1f)
            val deg = Motion.WIGGLE_DEG + (Motion.WIGGLE_DEG_BIG - Motion.WIGGLE_DEG) * k
            return min(deg, (Motion.WIGGLE_PX / halfDiag) * (180f / PI.toFloat()))
        }

        /**
         * the user's pick 2026-11-21 (the shot pass: a lifted full-height map at 1.04 grew past Home's foot, under the room bar and the
         * ribbon, its bottom edge cut off): the shift (px) that keeps a widget laid at [l], [t], [r], [b], drawn through [pose] (its scale
         * about its centre, its turn as far as it [Pose.reach]es, its bob), inside the frame [fl], [ft], [fr], [fb], added to the pose's
         * dx / dy. The pose's own offsets (the drop's slide) and a finger's carry are left out: the shift follows the size and the swing
         * only, so it never jumps. A box laid past the frame on an axis (a bar slid away) is left alone on it; one too big for the
         * frame is centred in it. Drawn only: touch reads the laid box.
         */
        fun keepIn(l: Float, t: Float, r: Float, b: Float, fl: Float, ft: Float, fr: Float, fb: Float, pose: Pose) {
            if (r <= l || b <= t) return
            val a = max(abs(pose.rot), pose.reach) * (PI.toFloat() / 180f)
            val co = cos(a); val si = sin(a)
            val hw = (r - l) / 2f; val hh = (b - t) / 2f
            val inX = l >= fl - 0.5f && r <= fr + 0.5f
            val inY = t >= ft - 0.5f && b <= fb + 0.5f
            // a box laid (nearly) as wide or as tall as the frame (the tap-to-cast bar across the page, 8 .. 1232): its lift draws it no
            // bigger than the frame holds (the bar at 1.04 ran 16 px past both page edges). Its cap follows the box and its swing only,
            // so it never jumps; a box with room for its growth is never capped
            val wx = hw * co + hh * si; val wy = hw * si + hh * co
            if (inX && wx > 0f) pose.scale = min(pose.scale, (fr - fl) / (2f * wx))
            if (inY && wy > 0f) pose.scale = min(pose.scale, max(0f, fb - ft - 2f * pose.bobMax) / (2f * wy))
            val ex = pose.scale * wx
            val ey = pose.scale * wy + pose.bobMax
            if (inX) pose.dx += shift((l + r) / 2f, ex, fl, fr)
            if (inY) pose.dy += shift((t + b) / 2f, ey, ft, fb)
        }
        private fun shift(c: Float, e: Float, lo: Float, hi: Float): Float = when {
            2f * e >= hi - lo -> (lo + hi) / 2f - c
            c - e < lo -> lo - (c - e)
            c + e > hi -> hi - (c + e)
            else -> 0f
        }
        /** each key's pace (WIGGLE_HZ +- 6 %) and phase (the golden angle apart) */
        fun hz(k: Int): Float = Motion.WIGGLE_HZ * (1f + 0.06f * sin(k * 1.7f + 0.4f))
        /** 1518 feel pass: [k]'s pace for a box of half-diagonal [halfDiag] - a small picture's [hz], a big module's the calmer
         *  [Motion.WIGGLE_HZ_BIG] (the same per-key spread; a step, never a slide: no widget's box crosses the split while it wiggles,
         *  so its phase never jumps) */
        fun hzFor(k: Int, halfDiag: Float): Float =
            if (halfDiag < Motion.WIGGLE_PACE_SPLIT) hz(k) else hz(k) * (Motion.WIGGLE_HZ_BIG / Motion.WIGGLE_HZ)
        fun phase(k: Int): Float = k * 2.39996f

        /** the open's ripple: a widget [d] px from the one it opened on (of [far] at most) starts this many ms later */
        fun ripple(d: Float, far: Float): Long = if (far <= 0f) 0L else (Motion.ENTER_RIPPLE * (d / far).coerceIn(0f, 1f)).toLong()

        /**
         * The map's pose while its glide's crop holds it (laid at [lw] x [lh] at [left], [top], scaled by [s] about 0,0 and moved by
         * [tx], [ty]; the drawn box's centre [cx], [cy]): the view's pivot (local px) and translation so that the pose's turn and scale go
         * about the drawn centre. Into [out]: pivotX, pivotY, translationX, translationY (the view's scale = s x the pose's).
         */
        fun composeMapGlide(left: Float, top: Float, tx: Float, ty: Float, s: Float, cx: Float, cy: Float, out: FloatArray) {
            val px = (cx - left - tx) / s; val py = (cy - top - ty) / s
            out[0] = px; out[1] = py
            out[2] = cx - left - px; out[3] = cy - top - py
        }
    }
}
