package com.jrees.wowcompanion

/**
 * HOLD TO CAST, THE GATE (the user, 2026-11-24: "give another option in the controller menu to "hold to cast" on a button so that we can
 * have 2 spells per button"; picks: "At the hold time, Settings: 0.3/0.4/0.55 s", "Every button incl. B"). The add-on cannot time a press
 * (secure code has no clock), so the APP does, in the launcher's controller path, before the game sees the press.
 *
 * Only a press of a button whose place has a hold spell NOW ([map]: layer x position, the add-on's `holdmap`) is held back:
 *   - let go before the hold time = a TAP: the press goes to the game then (down [TAP_MS], then up): the button's own action, on release;
 *   - still held at the hold time = the HOLD: the press goes to the game WITH THE MARKER - the virtual pad's Guide button, down [LEAD_MS]
 *     before the button and up [TAIL_MS] after it - and the add-on's router casts the slot's hold spell. It fires while still held.
 *     Let go exactly at the hold time counts as the hold.
 * Every other button, and every press while [on] is false or [map] is empty, passes straight through in the same call: no delay at all.
 *
 * The layer is the TRIGGERS AS HELD AT THE PRESS: while a gated press goes to the game the triggers are sent as they were then (a trigger
 * let go, or pulled, in between does not move the press onto another layer). A tap is never sent while the marker is down (it waits
 * [GAP_MS] past it), so a tap can never read as a hold. [reset] (the app lost focus) lets everything of ours go: nothing stays down, and a
 * gated button still held is ignored until it is let go. A gated press still waiting when the gate turns off or its place loses its hold
 * spell (a loading screen, a room took the pad, a page change) goes to the game at once as a plain press.
 *
 * Pure (the clock is the caller's, ms); the launcher calls [update] with the controller state at every send and again at [nextAt].
 * Bits: [A] [B] [X] [Y] (the game's PAD1..PAD4), the shoulders / Select / Start / sticks 4..9 (never gated), the D-pad [UP] [RIGHT] [DOWN]
 * [LEFT] 10..13, the marker [GUIDE] 15 (output only).
 */
class HoldGate {
    /** the hold time (Settings: Short 300 / Normal 400 / Long 550) */
    var holdMs: Long = NORMAL_MS
    /** hold to cast may act now (the probe said the marker works, the game screen has the pad); off = everything passes */
    var on: Boolean = false
        private set
    /** the places with a hold spell now: [key] (layer 0 none, 1 LT, 2 RT, 3 LT + RT; position 1..8) */
    var map: Set<Int> = emptySet()
        private set

    private enum class St { IDLE, PASS, PENDING, TAP, HOLD, PLAIN, SWALLOW }

    private class Ch(val bit: Int, val pos: Int) {
        var st = St.IDLE
        var t0 = 0L            // the physical press
        var layer = 0          // the layer at the press
        var downAt = 0L        // the press to the game (TAP / HOLD / PLAIN)
        var upAt = 0L          // a tap's end / the earliest up of a hold or a plain press (the game must see it down)
        var released = false   // let go (HOLD / PLAIN: up once past upAt)
        var reEdge = -1L       // pressed again while the last press was still going to the game
        var reUp = false       // ... and let go again before it had gone (the 1528 audit: a quick second tap, mashing)
        var trigFrom = Long.MAX_VALUE   // the triggers as at the press, sent over [trigFrom, trigTo)
        var trigTo = Long.MIN_VALUE
    }

    private val chs = GATED.map { (bit, pos) -> Ch(bit, pos) }
    private var prevRaw = 0
    private var ltOn = false
    private var rtOn = false
    private var guideFrom = Long.MAX_VALUE
    private var guideTo = Long.MIN_VALUE
    private var probeFrom = Long.MAX_VALUE
    private var probeTo = Long.MIN_VALUE
    private var lastNow = Long.MIN_VALUE                       // the last [update]'s clock: [nextAt] gives only later moments

    /** the output of the last [update] */
    var out: Int = 0
        private set
    var ltOut: Float = 0f
        private set
    var rtOut: Float = 0f
        private set
    /** the gate's last events, for the app's drawing: a hold fired / a tap went (game position, when) */
    var lastFire: Pair<Int, Long>? = null
        private set
    var lastTap: Pair<Int, Long>? = null
        private set

    /** turn the gate on / off and give it the places; a press waiting for a place no longer in it goes to the game as a plain press */
    fun configure(on: Boolean, map: Set<Int>, now: Long) {
        this.on = on
        this.map = map
        for (c in chs) if (c.st == St.PENDING && (!on || key(c.layer, c.pos) !in map)) plain(c, now, (prevRaw and (1 shl c.bit)) != 0)
    }

    /** the probe: the marker alone for [ms] (no other button); refused while a press is held back or going to the game */
    fun probePulse(now: Long, ms: Long = PROBE_MS): Boolean {
        if (busy() || guideTo != Long.MIN_VALUE) return false
        probeFrom = now; probeTo = now + ms
        return true
    }

    /** a press is held back or going to the game now */
    fun busy(): Boolean = chs.any { it.st == St.PENDING || it.st == St.TAP || it.st == St.HOLD || it.st == St.PLAIN }

    /** the app lost the game screen: everything of ours goes up; a gated button still held is ignored until it is let go */
    fun reset() {
        for (c in chs) {
            val down = (prevRaw and (1 shl c.bit)) != 0
            if (c.st != St.IDLE && c.st != St.PASS) c.st = if (down) St.SWALLOW else St.IDLE
            c.reEdge = -1L; c.reUp = false
            c.trigFrom = Long.MAX_VALUE; c.trigTo = Long.MIN_VALUE
            if (c.st != St.PASS) out = out and (1 shl c.bit).inv()
        }
        guideFrom = Long.MAX_VALUE; guideTo = Long.MIN_VALUE
        probeFrom = Long.MAX_VALUE; probeTo = Long.MIN_VALUE
        out = out and (1 shl GUIDE).inv()
    }

    /** the hold's progress of a press held back now at game position [pos] (0..1), or -1 */
    fun progress(pos: Int, now: Long): Float {
        val c = chs.firstOrNull { it.pos == pos && it.st == St.PENDING } ?: return -1f
        return ((now - c.t0).toFloat() / holdMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    }

    /** the layer the press held back now at game position [pos] was made on (0..3), or -1 */
    fun pendingLayer(pos: Int): Int = chs.firstOrNull { it.pos == pos && it.st == St.PENDING }?.layer ?: -1

    /**
     * One step: [raw] the controller's buttons now (bits above), [lt] / [rt] the triggers (0..1), at [now]. Returns the buttons for the
     * game ([out]); the triggers for the game are [ltOut] / [rtOut].
     */
    fun update(raw: Int, lt: Float, rt: Float, now: Long): Int {
        ltOn = if (ltOn) lt > TRIG_OFF else lt >= TRIG_ON
        rtOn = if (rtOn) rt > TRIG_OFF else rt >= TRIG_ON
        val layer = (if (ltOn) 1 else 0) + (if (rtOn) 2 else 0)
        val mods = raw and MOD_MASK != 0                                // a shoulder / Select / Start held: the press is a chord
        // the steps first, every channel, then the output: a hold fired in this step defers a tap another channel would send now
        for (c in chs) {
            val m = 1 shl c.bit
            val down = (raw and m) != 0
            val was = (prevRaw and m) != 0
            if (down && !was && c.st != St.IDLE && c.st != St.PASS && c.st != St.SWALLOW) { c.reEdge = now; c.reUp = false }
            if (!down && was && c.reEdge >= 0L) c.reUp = true
            step(c, down, was, layer, mods, now)
            if (c.st == St.IDLE && c.reEdge >= 0L && down) {   // pressed again while the last one was still going to the game
                val at = c.reEdge; c.reEdge = -1L; c.reUp = false
                begin(c, layer, mods, at)
                step(c, true, true, layer, mods, now)
            } else if (c.st == St.IDLE && c.reEdge >= 0L && c.reUp) {
                // the 1528 audit: pressed AND let go again while the last one was still going: a tap of its own (it was lost), sent
                // GAP_MS after the last one went up so the game sees the button up between the two
                val at = c.reEdge; c.reEdge = -1L; c.reUp = false
                begin(c, layer, mods, at)
                if (c.st == St.PENDING) tap(c, now) else plain(c, now, false)
                val d = now + GAP_MS - c.downAt
                if (d > 0L) { c.downAt += d; c.upAt += d; c.trigTo = maxOf(c.trigTo, c.upAt) }
            } else if (c.st == St.IDLE) { c.reEdge = -1L; c.reUp = false }
        }
        var o = raw and GATE_MASK.inv()                        // the shoulders, Select, Start, the sticks: never touched
        for (c in chs) if (shows(c, (raw and (1 shl c.bit)) != 0, now)) o = o or (1 shl c.bit)
        if (now in guideFrom until guideTo || now in probeFrom until probeTo) o = o or (1 shl GUIDE)
        if (probeTo != Long.MIN_VALUE && now >= probeTo) { probeFrom = Long.MAX_VALUE; probeTo = Long.MIN_VALUE }
        if (guideTo != Long.MIN_VALUE && now >= guideTo) { guideFrom = Long.MAX_VALUE; guideTo = Long.MIN_VALUE }
        // the triggers: as at the press of the press going to the game now (the latest one), else the real ones
        var tc: Ch? = null
        for (c in chs) {
            if (c.trigTo != Long.MIN_VALUE && now >= c.trigTo) { c.trigFrom = Long.MAX_VALUE; c.trigTo = Long.MIN_VALUE }
            if (now >= c.trigFrom && now < c.trigTo && (tc == null || c.downAt > tc.downAt)) tc = c
        }
        if (tc != null) {
            ltOut = if (tc.layer and 1 != 0) maxOf(lt, 1f) else 0f
            rtOut = if (tc.layer and 2 != 0) maxOf(rt, 1f) else 0f
        } else { ltOut = lt; rtOut = rt }
        prevRaw = raw
        lastNow = now
        out = o
        return o
    }

    /** when the gate next needs an [update] (Long.MAX_VALUE: nothing pending) */
    fun nextAt(): Long {
        var n = Long.MAX_VALUE
        fun at(t: Long) { if (t != Long.MIN_VALUE && t != Long.MAX_VALUE && t > lastNow && t < n) n = t }
        for (c in chs) {
            when (c.st) {
                St.PENDING -> at(c.t0 + holdMs)
                St.TAP -> { at(c.downAt); at(c.upAt) }
                St.HOLD, St.PLAIN -> { at(c.downAt); if (c.released) at(c.upAt) }
                else -> {}
            }
            at(c.trigFrom); at(c.trigTo)
        }
        at(guideFrom); at(guideTo)
        at(probeFrom); at(probeTo)
        return n
    }

    // ------------------------------------------------------------------------------------------------------------------------ inside

    /** a new press: held back when its place has a hold spell, else (or as part of a shoulder / Select / Start chord: LB is the
     *  game's emulated Ctrl here, a chord's key is another binding than the router's) straight through */
    private fun begin(c: Ch, layer: Int, mods: Boolean, at: Long) {
        if (on && !mods && key(layer, c.pos) in map) { c.st = St.PENDING; c.t0 = at; c.layer = layer; c.released = false }
        else c.st = St.PASS
    }

    private fun step(c: Ch, down: Boolean, was: Boolean, layer: Int, mods: Boolean, now: Long) {
        when (c.st) {
            St.IDLE -> if (down && !was) begin(c, layer, mods, now)
            St.PASS -> if (!down) c.st = St.IDLE
            St.SWALLOW -> if (!down) c.st = St.IDLE
            St.PENDING -> {
                if (!on || key(c.layer, c.pos) !in map) { plain(c, now, down); return }
                if (now - c.t0 >= holdMs) fire(c, now, !down)  // released exactly at the hold time counts as the hold
                else if (!down) tap(c, now)
            }
            St.TAP -> if (now >= c.upAt) c.st = St.IDLE
            St.HOLD, St.PLAIN -> {
                if (!down) c.released = true
                if (c.released && now >= c.upAt) c.st = St.IDLE
            }
        }
    }

    /** the hold: the marker first, the button [LEAD_MS] later, the marker up [TAIL_MS] after the button */
    private fun fire(c: Ch, now: Long, released: Boolean) {
        val joining = guideTo != Long.MIN_VALUE && now < guideTo      // another hold's marker is down: this press shares it
        val start = if (joining) guideFrom else now
        c.downAt = maxOf(now, start + LEAD_MS)
        c.upAt = c.downAt + TAP_MS
        c.released = released
        c.st = St.HOLD
        guideFrom = start
        guideTo = maxOf(guideTo, c.downAt + TAIL_MS)
        c.trigFrom = now; c.trigTo = maxOf(guideTo, c.upAt)
        lastFire = c.pos to now
        // a tap / plain press not sent yet waits for this marker to go up (it must never read as a hold)
        for (o in chs) if (o !== c && (o.st == St.TAP || o.st == St.PLAIN) && o.downAt >= now && o.downAt < guideTo + GAP_MS) {
            val d = guideTo + GAP_MS - o.downAt
            o.downAt += d; o.upAt += d; o.trigFrom = now; o.trigTo = maxOf(o.trigTo + d, o.upAt)
        }
    }

    /** when a press without the marker may go to the game: now, or [GAP_MS] past a marker down now - a hold's, or the probe's pulse (the
     *  1528 audit: a tap let go during the pulse went with the Guide down and read as the hold) */
    private fun clearOfMarker(now: Long): Long {
        var at = now
        if (guideTo != Long.MIN_VALUE && now < guideTo) at = maxOf(at, guideTo + GAP_MS)
        if (probeTo != Long.MIN_VALUE && now < probeTo) at = maxOf(at, probeTo + GAP_MS)
        return at
    }

    /** the tap: the press to the game now (or once the marker is up), [TAP_MS] long */
    private fun tap(c: Ch, now: Long) {
        c.downAt = clearOfMarker(now)
        c.upAt = c.downAt + TAP_MS
        c.st = St.TAP
        c.trigFrom = now; c.trigTo = c.upAt
        lastTap = c.pos to now
    }

    /** a waiting press the gate let go of (turned off / the place lost its hold spell): to the game now, no marker */
    private fun plain(c: Ch, now: Long, down: Boolean) {
        c.downAt = clearOfMarker(now)
        c.upAt = c.downAt + TAP_MS
        c.released = !down
        c.st = St.PLAIN
        c.trigFrom = now; c.trigTo = c.upAt
    }

    private fun shows(c: Ch, down: Boolean, now: Long): Boolean = when (c.st) {
        St.IDLE, St.SWALLOW, St.PENDING -> false
        St.PASS -> down
        St.TAP -> now >= c.downAt && now < c.upAt
        St.HOLD, St.PLAIN -> now >= c.downAt && (!c.released || now < c.upAt)
    }

    companion object {
        const val A = 0; const val B = 1; const val X = 2; const val Y = 3
        const val UP = 10; const val RIGHT = 11; const val DOWN = 12; const val LEFT = 13
        const val GUIDE = 15

        const val SHORT_MS = 300L; const val NORMAL_MS = 400L; const val LONG_MS = 550L
        /** the marker's lead before the button, and how long it stays after: the game reads it at the press */
        const val LEAD_MS = 24L
        const val TAIL_MS = 60L
        /** a press sent for a tap (or a hold let go at once) stays down this long, so the game's poll cannot miss it */
        const val TAP_MS = 70L
        /** a tap waits this long past the marker's release */
        const val GAP_MS = 24L
        /** the probe's marker */
        const val PROBE_MS = 200L
        /** an analog trigger counts as held over 35 % and lets go under 30 % (the app's pad reader's thresholds) */
        const val TRIG_ON = 0.35f
        const val TRIG_OFF = 0.30f

        /** the gated bits and their game positions (the router's: D-pad 1 left, 2 up, 3 right, 4 down; 5 X, 6 Y, 7 B, 8 A) */
        val GATED: List<Pair<Int, Int>> = listOf(LEFT to 1, UP to 2, RIGHT to 3, DOWN to 4, X to 5, Y to 6, B to 7, A to 8)
        private val GATE_MASK = GATED.fold(0) { m, (bit, _) -> m or (1 shl bit) }
        /** LB, RB, Select, Start: held at a press, the press is a chord (never held back) */
        private const val MOD_MASK = (1 shl 4) or (1 shl 5) or (1 shl 6) or (1 shl 7)

        /** the hold badge's side for a button DRAWN at [drawn] (HoldArt.outer; pure for the tests): the top button's upper left, the left
         *  and bottom ones' lower left, the right one's lower right, as a unit (x, y; y down) */
        fun badgeSide(drawn: Int): Pair<Float, Float> = when (drawn % 4) {
            1 -> -1f to -1f          // up / top: upper left
            2 -> 1f to 1f            // right: lower right
            else -> -1f to 1f        // left, down / bottom: lower left
        }

        /** the place's key: layer (0 none, 1 LT, 2 RT, 3 LT + RT) x 8 + position - 1 */
        fun key(layer: Int, pos: Int): Int = layer * 8 + (pos - 1)

        /** the add-on's words ("bare" / "lt" / "rt" / "ltrt") and the position -> the key, or null */
        fun keyOf(lay: String, pos: Int): Int? {
            val l = when (lay) { "bare" -> 0; "lt" -> 1; "rt" -> 2; "ltrt" -> 3; else -> return null }
            return if (pos in 1..8) key(l, pos) else null
        }
    }
}
