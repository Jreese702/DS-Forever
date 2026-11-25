package com.jrees.wowcompanion

import android.os.SystemClock
import org.json.JSONObject

/**
 * HOLD TO CAST, THE APP'S HALF: DISPLAY ONLY (PadMap m194; the user on test 1529, 2026-11-24: "When I use ours it feels baggy because of
 * how its delayed for the normal spell buttons"; the pick "Yes, rebuild it that way": all inside the game, real-time taps on release,
 * no app delay, no Guide check). The app no longer touches the pad: the launcher's controller path is exactly as before hold to cast
 * (no gate, no marker, no probe). The ADD-ON keeps the press of a button whose current tab has a hold spell, counts the game's 0.2 s
 * state-driver looks while it is kept, and on the release casts the hold spell (that many looks: [ticks]) or the button's own (the tap).
 *
 * What is left here is the picture: [watch] follows the app's OWN reading of the pad (PadInput, the copy the launcher hands us; the
 * game still gets every press untouched) for the places the add-on keeps now (its `holdmap`). m195 (the user on test 1529: "after like
 * .25s of holding I want a charge up circle to appear around the little hold icon"; "it needs to take over the main spell circle until
 * the [GCD] completes following it or if its canceled"): the CHARGE RING round the small hold badge ([Watch.charge]: nothing before
 * [RING_DELAY_MS], then filling clockwise to the hold point, then "charged"), and THE TAKEOVER ([takeover]): a release that ran the hold
 * (at once when the press was surely long enough, else on the add-on's word, its release's facecast) puts the hold spell on the big
 * face until its cast and the GCD after it end, at once back on a fail / a cancel (the add-on's `holdcast`), never stuck ([Takeover]).
 * And the hold time to the add-on ([ticksToSend]).
 *
 * THE TIMING (exact): the game looks every 0.2 s (SecureStateDriver's throttle, the first frame past it); a press made between two looks
 * gets its first one 0 - 0.2 s later. N looks ([CastSettings.HoldTime.ticks]: Short 2, Normal 3, Long 4) = a hold when held from
 * (N - 1) x 0.2 s to N x 0.2 s on (+ up to a frame per look): Short 0.2 - 0.4 s, Normal 0.4 - 0.6 s, Long 0.6 - 0.8 s; always the tap
 * below [tapBelowMs], always the hold from [holdFromMs]. The arc is full at [holdFromMs]: a full ring = let go now and it is the hold.
 */
object HoldCast {
    const val TAG = "WCHOLD"

    /** the game's state-driver look (SecureStateDriver: every 0.2 s, in fights too) */
    const val TICK_MS = 200L
    /** a look lands on the first frame past its 0.2 s: up to one frame later (60 fps) */
    const val FRAME_MS = 17L
    /** the add-on's word that a release ran the hold, waited for this long after it */
    const val CONFIRM_MS = 1_500L
    /** the charge ring shows from this long held (quick taps show nothing) */
    const val RING_DELAY_MS = 250L

    /** held less than this: always the tap (the N-th look cannot have come) */
    fun tapBelowMs(n: Int): Long = (n - 1) * TICK_MS
    /** held this long: always the hold (the N-th look has come whatever the phase) */
    fun holdFromMs(n: Int): Long = n * (TICK_MS + FRAME_MS)

    // ------------------------------------------------------------------------------------------------------------------ the places

    /** the place's key: layer (0 none, 1 LT, 2 RT, 3 LT + RT) x 8 + position - 1 (Home's band key) */
    fun key(layer: Int, pos: Int): Int = layer * 8 + (pos - 1)

    /** the add-on's words ("bare" / "lt" / "rt" / "ltrt") and the position -> the key, or null */
    fun keyOf(lay: String, pos: Int): Int? {
        val l = when (lay) { "bare" -> 0; "lt" -> 1; "rt" -> 2; "ltrt" -> 3; else -> return null }
        return if (pos in 1..8) key(l, pos) else null
    }

    /** the hold badge's side for a button DRAWN at [drawn] (HoldArt.outer; pure for the tests): the top button's upper left, the left
     *  and bottom ones' lower left, the right one's lower right, as a unit (x, y; y down) */
    fun badgeSide(drawn: Int): Pair<Float, Float> = when (drawn % 4) {
        1 -> -1f to -1f          // up / top: upper left
        2 -> 1f to 1f            // right: lower right
        else -> -1f to 1f        // left, down / bottom: lower left
    }

    /**
     * The add-on's `holdmap` ({ on, b = "bare:8,lt:6,..." }): the places whose press the router KEEPS for a hold right now (the router
     * carries the key, the live slot has a hold text on the tab the press takes, Party / room mode off, not in a loading screen; never
     * B); null when it is not one. An empty set = no arc anywhere.
     */
    fun parseMap(msg: JSONObject): Set<Int>? {
        if (msg.optString("type") != "holdmap") return null
        if (!msg.optBoolean("on", false)) return emptySet()
        val out = HashSet<Int>()
        for (w in msg.optString("b", "").split(',')) {
            val p = w.trim().split(':')
            if (p.size != 2) continue
            val pos = p[1].toIntOrNull() ?: continue
            if (pos == 7) continue                                          // never B
            keyOf(p[0], pos)?.let { out += it }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------------------------ the picture

    /**
     * One press per game position (1..8, B never): pure, the clock the caller's (uptime ms). [press] at the button's down with the
     * layer the triggers made then (the add-on reads them at the press too), [release] at its up; the arc and the flash read from it.
     */
    class Watch(private val ticksOf: () -> Int = { CastSettings.holdTime.ticks }) {
        /** the places the add-on keeps now (the `holdmap`) */
        var map: Set<Int> = emptySet()
        /** the hold time's looks (CastSettings.holdTime: read each time, so Settings and a reset are followed at once) */
        val ticks: Int get() = ticksOf()
        private val t0 = LongArray(9) { -1L }
        private val lay = IntArray(9) { -1 }
        private val relAt = LongArray(9) { -1L }
        private val relLay = IntArray(9) { -1 }
        /** the last hold that fired: (game position, when) */
        var lastFire: Pair<Int, Long>? = null
            private set
        /** a hold fired at (game position, its layer): the takeover starts */
        var onFire: ((Int, Int) -> Unit)? = null
        /** the 1531 audit: the map is the Bind box's (why "box": its own timing - a long press places a hold, it casts nothing): the
         *  ring as always, no takeover */
        var box: Boolean = false

        /** the button at [pos] went down on layer [layer] at [now]: tracked only when the add-on keeps that place's press now */
        fun press(pos: Int, layer: Int, now: Long) {
            if (pos !in 1..8 || pos == 7) return
            if (key(layer, pos) in map) { t0[pos] = now; lay[pos] = layer } else { t0[pos] = -1L; lay[pos] = -1 }
        }

        /** the button at [pos] came up at [now]; true = the hold surely fired (the flash starts now) */
        fun release(pos: Int, now: Long): Boolean {
            if (pos !in 1..8) return false
            val s = t0[pos]
            val l = lay[pos]
            t0[pos] = -1L; lay[pos] = -1
            if (s < 0L) return false
            relAt[pos] = now; relLay[pos] = l
            if (now - s >= holdFromMs(ticks)) { lastFire = pos to now; if (!box) onFire?.invoke(pos, l); return true }
            return false
        }

        /** the add-on's word: the release at [pos] ran its hold spell (its facecast). A release that was not surely long enough flashes
         *  now; one already flashed, or no release of ours just before, nothing */
        fun confirm(pos: Int, now: Long): Boolean {
            if (pos !in 1..8) return false
            val r = relAt[pos]
            if (r < 0L || now - r > CONFIRM_MS) return false
            if (lastFire?.let { it.first == pos && it.second >= r } == true) return false
            lastFire = pos to now
            onFire?.invoke(pos, relLay[pos])
            return true
        }

        /** the arc: 0..1 of the way to [holdFromMs] while the button at [pos] is held, -1 when it is not (or not kept) */
        fun progress(pos: Int, now: Long): Float {
            val s = t0.getOrElse(pos) { -1L }
            if (s < 0L) return -1f
            return ((now - s).toFloat() / holdFromMs(ticks)).coerceIn(0f, 1f)
        }

        /**
         * THE CHARGE RING round the small badge (m195): -1 while hidden (not held, not kept, or held under [RING_DELAY_MS]), else 0..1 of
         * the way from [RING_DELAY_MS] to the hold point ([holdFromMs]); 1 = charged (held past it: let go now and it is the hold)
         */
        fun charge(pos: Int, now: Long): Float {
            val s = t0.getOrElse(pos) { -1L }
            if (s < 0L) return -1f
            val held = now - s
            if (held < RING_DELAY_MS) return -1f
            val full = holdFromMs(ticks)
            return ((held - RING_DELAY_MS).toFloat() / (full - RING_DELAY_MS).coerceAtLeast(1L)).coerceIn(0f, 1f)
        }
        /** held past the hold point (the ring's "charged" look) */
        fun charged(pos: Int, now: Long): Boolean = t0.getOrElse(pos) { -1L }.let { it >= 0L && now - it >= holdFromMs(ticks) }

        /** the layer the held button at [pos] was pressed on, -1 when none */
        fun layerOf(pos: Int): Int = if (t0.getOrElse(pos) { -1L } >= 0L) lay[pos] else -1

        /** nothing held any more, nothing just released (the pad went away; tests) */
        fun reset() { t0.fill(-1L); lay.fill(-1); relAt.fill(-1L); relLay.fill(-1); lastFire = null; box = false }
    }

    val watch = Watch()

    /**
     * THE TAKEOVER (m195): after a hold fires the button's big face shows the HOLD spell until its cast and the GCD that follows end
     * (the add-on's `holdcast`: "cast" with its seconds left, "ok"; the GCD from the state document's `gd`), at once on a "fail" (the
     * cast refused, interrupted) or a [cancel], and [FALLBACK_MS] after the last word if nothing more comes; it eases back over [EASE_MS].
     * Pure, on the band's clock (elapsedRealtime ms, the cast / GCD times' own).
     */
    class Takeover {
        var pos = -1
            private set
        var layer = -1
            private set
        private var t = 0L; private var last = 0L
        private var castEnd = -1L; private var gcdEnd = -1L; private var okAt = -1L; private var failAt = -1L

        fun start(pos: Int, layer: Int, now: Long) {
            this.pos = pos; this.layer = layer; t = now; last = now
            castEnd = -1L; gcdEnd = -1L; okAt = -1L; failAt = -1L
        }
        /** the hold's cast began: [leftMs] of it to run */
        fun cast(leftMs: Long, now: Long) { if (pos < 0) return; last = now; castEnd = now + leftMs.coerceAtLeast(0L) }
        /** an instant hold went off (or a channel ended) */
        fun ok(now: Long) { if (pos < 0) return; last = now; okAt = now }
        /** refused / interrupted / not the hold after all: back at once */
        fun fail(now: Long) { if (pos < 0) return; last = now; failAt = now }
        fun cancel(now: Long) = fail(now)
        /** nothing on any face (tests; a new session) */
        fun clear() { pos = -1; layer = -1 }
        /**
         * RULING A (the user's pick 2026-11-25, "Stay until it ends (Recommended)": a trigger-layer hold - LT + X - keeps its takeover on
         * the main circle after the trigger is let go, until its cast and GCD end or it fails): the band showing layer [li] (0 none,
         * 1 LT, 2 RT, 3 LT + RT) draws it when [li] only LACKS some of the fired layer's triggers - its own layer, the bare one after
         * LT + X, any after LT + RT + X - never a layer with a trigger the press did not have (that layer's own buttons). It ends as
         * ever ([endAt]: its cast / GCD, a fail, [FALLBACK_MS], 15 s), whatever the layer
         */
        fun showsOn(li: Int): Boolean = pos >= 0 && layer in 0..3 && li in 0..3 && (li and layer.inv()) == 0
        /** the running GCD ([startMs], [durMs]): the one that started with the hold counts */
        fun gcd(startMs: Long, durMs: Long) { if (pos >= 0 && durMs > 0L && startMs >= t - 500L) gcdEnd = startMs + durMs }

        /** when the hold spell leaves the face */
        fun endAt(): Long {
            if (failAt >= 0L) return failAt
            var known = -1L
            for (v in longArrayOf(castEnd, gcdEnd, if (okAt >= 0L) okAt + 300L else -1L)) if (v >= 0L && v > known) known = v
            val e = if (known >= 0L) known else t + FALLBACK_MS
            return minOf(e, maxOf(castEnd, last) + FALLBACK_MS, t + 15_000L)
        }
        /** 1 while it has the face, easing to 0 after [endAt]; 0 (and cleared) when it is over */
        fun alpha(now: Long): Float {
            if (pos < 0) return 0f
            val e = endAt()
            if (now < e) return 1f
            val a = 1f - (now - e).toFloat() / EASE_MS
            if (a <= 0f) { pos = -1; layer = -1; return 0f }
            return a
        }
        /** how far the GCD after it has run down (1 = all left), -1 none known */
        fun gcdLeft(now: Long, durMs: Long): Float = if (gcdEnd < 0L || durMs <= 0L || now >= gcdEnd) -1f else ((gcdEnd - now).toFloat() / durMs).coerceIn(0f, 1f)
    }
    const val FALLBACK_MS = 2_000L
    const val EASE_MS = 200L

    val takeover = Takeover()
    /** the band's clock (elapsedRealtime ms: the cast and GCD times'); tests set their own */
    internal var bandClock: () -> Long = { SystemClock.elapsedRealtime() }

    init { watch.onFire = { pos, layer -> takeover.start(pos, layer, bandClock()); onChange?.invoke() } }

    /** CompanionHost: a CURRENT `holdcast` (PadMap m195: the fired hold's own cast - "cast" with d its seconds left, "ok", "fail") */
    fun onHoldCast(msg: JSONObject) {
        if (msg.optString("type") != "holdcast") return
        val pos = msg.optInt("pos", 0)
        if (pos != takeover.pos) return
        val now = bandClock()
        when (msg.optString("st")) {
            "cast" -> takeover.cast((msg.optDouble("d", 0.0) * 1000.0).toLong(), now)
            "ok" -> takeover.ok(now)
            "fail" -> takeover.fail(now)
            else -> return
        }
        onChange?.invoke()
    }

    /** the drawing's clock (uptime ms; the band draws on it); tests set their own */
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    /** the drawing (Home's band): told when the add-on's word starts a flash */
    @Volatile var onChange: (() -> Unit)? = null

    /** CompanionHost: a CURRENT `holdmap` (main thread) */
    fun onMap(msg: JSONObject) {
        val m = parseMap(msg) ?: return
        watch.map = if (PlayModes.current == PlayMode.STREAM) emptySet() else m      // a PC game's presses: not this pad's
        watch.box = msg.optString("why") == "box"
    }

    /** CompanionHost: a CURRENT `facecast` (main thread): a kept press's release that ran its hold ("hk" rel, path slothold) */
    fun onFacecast(msg: JSONObject) {
        if (msg.optString("hk") != "rel") return
        val pos = msg.optInt("pos", 0)
        if (msg.optString("kind") != "slothold") {
            // the kept press's release ran the TAP: a takeover the app started by itself (its own timing said "sure") goes back at once
            if (takeover.pos == pos) { takeover.cancel(bandClock()); onChange?.invoke() }
            return
        }
        watch.confirm(pos, clock())                                     // (a confirm starts the takeover: onFire tells the drawing)
    }

    // ------------------------------------------------------------------------------------------------------------------ the hold time

    /** Settings' row changed the hold time (the picture follows CastSettings by itself): the add-on, through [onTicksWanted] */
    fun holdTimeChanged() { onTicksWanted?.invoke() }
    /** CompanionHost's sender (weakly held there) */
    @Volatile var onTicksWanted: (() -> Unit)? = null

    /**
     * THE HOLD TIME TO THE ADD-ON (Book b82's page-3 codes 29 / 30 / 31): [want] the app's looks, [have] the padmap's `hold.n` (null =
     * an add-on before m194: nothing to send), [inFight] (the add-on takes it out of combat only: wait), [sinceSendMs] since the last send
     * of this wish (null = none). The looks to send, or null
     */
    fun ticksToSend(want: Int, have: Int?, inFight: Boolean, sinceSendMs: Long?): Int? {
        if (have == null || have == want || want !in 2..4) return null
        if (inFight || (sinceSendMs != null && sinceSendMs < TopCombatBar.RESEND_MS)) return null
        return want
    }

    /** the Controller room's hint and Settings' tile: the hold point as the room writes it (about 0.4 / 0.6 / 0.8 s) */
    fun holdWord(): String = when (CastSettings.holdTime) {
        CastSettings.HoldTime.SHORT -> "0.4 s"; CastSettings.HoldTime.NORMAL -> "0.6 s"; CastSettings.HoldTime.LONG -> "0.8 s"
    }
}
