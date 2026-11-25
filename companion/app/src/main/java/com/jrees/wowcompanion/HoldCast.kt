package com.jrees.wowcompanion

import android.os.SystemClock
import org.json.JSONObject

/**
 * HOLD TO CAST, THE APP'S HALF (PadMap m194; the user on test 1529, 2026-11-24: "When I use ours it feels baggy because of how its
 * delayed for the normal spell buttons"; the pick "Yes, rebuild it that way": all inside the game, real-time taps on release, no app
 * delay, no Guide check). The ADD-ON keeps the press of a button whose current tab has a hold spell, counts the game's 0.2 s
 * state-driver looks while it is kept, and on the release casts the hold spell (that many looks: [Watch.ticks]) or the button's own.
 *
 * ROUND 1533 (PadMap m199; the user on test 1532, 2026-11-25: "I also want this to cast right when the hold bar is filled, not when its
 * released"): THE FIRE AT THE THRESHOLD ([release], [HoldRelease]) - for a press the add-on says it KEPT (its `facecast` keep word), the
 * launcher writes that button's release to the game at the fire point ([fireMs]) while it is still held, so the router's release path
 * casts the hold there; letting go afterwards sends nothing. Every key-down, every release before the fire point and every button
 * without a kept press still goes to the game in the same call, as made (no delay). The picture: [watch] follows the app's OWN reading of
 * the pad (CastView's onButtons) for the places the add-on keeps now (its `holdmap`) - THE CHARGE RING round the small hold badge
 * ([Watch.charge]: nothing before [RING_DELAY_MS], then filling clockwise to the fire point, handing over to the takeover at the fire) -
 * and THE TAKEOVER ([takeover]): the hold spell on the big face in the game's own gold "checked" ring (the art Raptor Strike wears when
 * it is queued) until its cast and the GCD after it end, while a next-swing ability is queued (until the swing lands or it is
 * cancelled), at once back on a fail, a cancel or an instant that gave no GCD, no cast and no queue (the add-on's `holdcast` none).
 * And the hold time to the add-on ([ticksToSend]).
 *
 * THE TIMING: the game looks every 0.2 s (SecureStateDriver's throttle, reset at each look, which runs on the first frame past it); N
 * looks (Short 2, Normal 3, Long 4; the add-on's clock's own `cn`) come no later than N x (0.2 s + a frame) after the keep; the fire point
 * is F = N x (200 + f) + f + 10 ms (f = the game's longest recent frame, the add-on's `ft`; 25 when unknown): Normal 678 ms at 60 fps,
 * 710 ms at 40. Always the tap below [tapBelowMs].
 */
object HoldCast {
    const val TAG = "WCHOLD"

    /** the game's state-driver look (SecureStateDriver: every 0.2 s, in fights too) */
    const val TICK_MS = 200L
    /** the add-on's word that a release ran the hold, waited for this long after it */
    const val CONFIRM_MS = 1_500L
    /** the charge ring shows from this long held (quick taps show nothing) */
    const val RING_DELAY_MS = 250L

    /** held less than this: always the tap (the N-th look cannot have come) */
    fun tapBelowMs(n: Int): Long = (n - 1) * TICK_MS
    /** round 1533: the fire point after the press, N looks at a game frame of [ft] ms (null = unknown: 25) - [HoldRelease.fireMs] */
    fun fireMs(n: Int, ft: Int? = null): Long = HoldRelease.fireMs(n, ft)

    // ------------------------------------------------------------------------------------------------------------------ the places

    /** the place's key: layer (0 none, 1 LT, 2 RT, 3 LT + RT) x 8 + position - 1 (Home's band key) */
    fun key(layer: Int, pos: Int): Int = layer * 8 + (pos - 1)

    /** the add-on's words ("bare" / "lt" / "rt" / "ltrt") and the position -> the key, or null */
    fun keyOf(lay: String, pos: Int): Int? {
        val l = layerOf(lay) ?: return null
        return if (pos in 1..8) key(l, pos) else null
    }
    /** the add-on's layer word -> 0..3, null for none */
    fun layerOf(lay: String): Int? = when (lay) { "bare" -> 0; "lt" -> 1; "rt" -> 2; "ltrt" -> 3; else -> null }

    /** the hold badge's side for a button DRAWN at [drawn] (HoldArt.outer; pure for the tests): the top button's upper left, the left
     *  and bottom ones' lower left, the right one's lower right, as a unit (x, y; y down) */
    fun badgeSide(drawn: Int): Pair<Float, Float> = when (drawn % 4) {
        1 -> -1f to -1f          // up / top: upper left
        2 -> 1f to 1f            // right: lower right
        else -> -1f to 1f        // left, down / bottom: lower left
    }

    /**
     * ROUND 1533 (the user: "The red outline is an eyesore we should just shade the ability like we do with the glyphs"): the hold
     * badge's state as ONE shade of its icon (the game's own looks, actionbutton.lua: not enough power (0.5, 0.5, 1), unusable 0.4; our
     * big buttons' red glyph for range): GREY when not usable for a reason other than power, else RED out of range (red is range only),
     * else BLUE for not enough power, else none - so power + range shows red, unusable + range grey. [u] usable (0 = no), [m] not
     * enough power (1), [r] in range (0 = out; -1 unknown)
     */
    enum class Shade { NONE, UNUSABLE, RANGE, MANA }
    fun badgeShade(u: Int, m: Int, r: Int): Shade = when {
        u == 0 && m != 1 -> Shade.UNUSABLE
        r == 0 -> Shade.RANGE
        m == 1 -> Shade.MANA
        else -> Shade.NONE
    }

    /**
     * The add-on's `holdmap` ({ on, b = "bare:8,lt:6,...", why, cn, ft }): the places whose press the router KEEPS for a hold right now
     * (the router carries the key, the live slot has a hold text on the tab the press takes, Party / room mode off, not in a loading
     * screen; never B); null when it is not one. An empty set = no arc anywhere.
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
     * layer the triggers made then (the add-on reads them at the press too), [release] at its up, [fired] when the launcher let it go
     * for the game at its fire point (round 1533); the ring reads from it. The fire point is frozen at the press (the ring never changes
     * pace mid-press): [ticksOf] the clock's looks (the add-on's `cn`, else Settings), [ftOf] the game's frame (the add-on's `ft`).
     */
    class Watch(private val ticksOf: () -> Int = { need() }, private val ftOf: () -> Int? = { release.ft },
                private val bumpOf: () -> Long = { release.bump }) {
        /** the places the add-on keeps now (the `holdmap`) */
        var map: Set<Int> = emptySet()
        /** the hold time's looks (read each time, so Settings and a reset are followed at once) */
        val ticks: Int get() = ticksOf()
        private val t0 = LongArray(9) { -1L }
        private val lay = IntArray(9) { -1 }
        private val full = LongArray(9) { HoldRelease.fireMs(3, null) }
        private val firedAt = LongArray(9) { -1L }
        private val relAt = LongArray(9) { -1L }
        private val relLay = IntArray(9) { -1 }
        /** the fix pass: the last release at each position started the takeover by itself (its own timing said "sure") */
        private val relSure = BooleanArray(9)
        /** the last hold that fired: (game position, when) */
        var lastFire: Pair<Int, Long>? = null
            private set
        /** a hold fired at (game position, its layer) on the release (the fallback: no early fire): the takeover starts */
        var onFire: ((Int, Int) -> Unit)? = null
        /** the fix pass: how long before [onFire] the physical release it answers came (0 for a sure release, the rel word's lag for
         *  [confirm]) - the takeover's GCD look-back starts there */
        var fireLag = 0L
            private set
        /** the 1531 audit: the map is the Bind box's (why "box": its own timing - a long press places a hold, it casts nothing): the
         *  ring as always, no takeover */
        var box: Boolean = false

        /** the button at [pos] went down on layer [layer] at [now]: tracked only when the add-on keeps that place's press now. The fix
         *  pass: the fire point is the launcher's own - F plus the session's bump after a SHORT fire ([HoldRelease.bump], frozen at the
         *  press as its fireAt), so the ring is full exactly when the hold casts */
        fun press(pos: Int, layer: Int, now: Long) {
            if (pos !in 1..8 || pos == 7) return
            firedAt[pos] = -1L
            if (key(layer, pos) in map) { t0[pos] = now; lay[pos] = layer; full[pos] = HoldRelease.fireMs(ticks, ftOf()) + bumpOf().coerceAtLeast(0L) }
            else { t0[pos] = -1L; lay[pos] = -1 }
        }

        /** the fire point of the press held at [pos] (ms after its press, the bump in it), frozen at the press */
        fun fullMs(pos: Int): Long = full.getOrElse(pos) { HoldRelease.fireMs(3, null) }

        /** round 1533: the launcher let the press at [pos] go for the game at its fire point (the takeover is HoldCast's): its release
         *  starts nothing, the add-on's word for it starts nothing, the ring hands over */
        fun fired(pos: Int, now: Long) {
            if (pos !in 1..8 || pos == 7) return
            firedAt[pos] = now
            lastFire = pos to now
        }
        private fun firedNow(pos: Int): Boolean { val f = firedAt.getOrElse(pos) { -1L }; return f >= 0L && f >= t0[pos] }

        /** the button at [pos] came up at [now]; true = the hold surely fired on this release (no early fire: the takeover starts now) */
        fun release(pos: Int, now: Long): Boolean {
            if (pos !in 1..8) return false
            val s = t0[pos]
            val l = lay[pos]
            val was = firedAt[pos] >= 0L && s >= 0L && firedAt[pos] >= s
            t0[pos] = -1L; lay[pos] = -1
            relSure[pos] = false
            if (s < 0L) return false
            if (was) { relAt[pos] = -1L; return false }                    // fired at the fill: letting go does nothing
            relAt[pos] = now; relLay[pos] = l
            if (now - s >= full[pos]) { lastFire = pos to now; relSure[pos] = !box; fireLag = 0L; if (!box) onFire?.invoke(pos, l); return true }
            return false
        }

        /** the fix pass: the takeover at [pos] is the one the release just before [now] started by itself (no early fire; its own timing
         *  said "sure") - the only one the add-on's TAP word for that release may take back (a takeover the fire started - a queued
         *  Raptor Strike - is not that release's: a later tap of the same button leaves it) */
        fun sureRelease(pos: Int, now: Long): Boolean {
            if (pos !in 1..8) return false
            val r = relAt[pos]
            return relSure[pos] && r >= 0L && now - r <= CONFIRM_MS && lastFire?.let { it.first == pos && it.second == r } == true
        }

        /** the add-on's word: the release at [pos] ran its hold spell (its facecast). A release that was not surely long enough starts
         *  the takeover now; one already started (or fired at the fill), or no release of ours just before, nothing */
        fun confirm(pos: Int, now: Long): Boolean {
            if (pos !in 1..8 || box) return false                       // (the 1532 audit: the Bind box's release casts nothing)
            val r = relAt[pos]
            if (r < 0L || now - r > CONFIRM_MS) return false
            if (lastFire?.let { it.first == pos && it.second >= r } == true) return false
            lastFire = pos to now
            fireLag = (now - r).coerceIn(0L, CONFIRM_MS)
            onFire?.invoke(pos, relLay[pos])
            return true
        }

        /** the arc: 0..1 of the way to the fire point while the button at [pos] is held, -1 when it is not (or not kept) */
        fun progress(pos: Int, now: Long): Float {
            val s = t0.getOrElse(pos) { -1L }
            if (s < 0L) return -1f
            return ((now - s).toFloat() / full[pos]).coerceIn(0f, 1f)
        }

        /**
         * THE CHARGE RING round the small badge (m195; round 1533 it glows): -1 while hidden (not held, not kept, held under
         * [RING_DELAY_MS], or fired: the takeover has it), else 0..1 of the way from [RING_DELAY_MS] to the fire point; 1 = charged (the
         * fire waits for a late keep word, or there is no early fire: an old add-on, a gate - let go now and it is the hold)
         */
        fun charge(pos: Int, now: Long): Float {
            val s = t0.getOrElse(pos) { -1L }
            if (s < 0L || firedNow(pos)) return -1f
            val held = now - s
            if (held < RING_DELAY_MS) return -1f
            return ((held - RING_DELAY_MS).toFloat() / (full[pos] - RING_DELAY_MS).coerceAtLeast(1L)).coerceIn(0f, 1f)
        }
        /** held past the fire point and not fired (the ring's "charged" look) */
        fun charged(pos: Int, now: Long): Boolean =
            t0.getOrElse(pos) { -1L }.let { it >= 0L && now - it >= full[pos] } && !firedNow(pos)

        /** the layer the held button at [pos] was pressed on, -1 when none */
        fun layerOf(pos: Int): Int = if (t0.getOrElse(pos) { -1L } >= 0L) lay[pos] else -1

        /** nothing held any more, nothing just released (the pad went away; tests) */
        fun reset() { t0.fill(-1L); lay.fill(-1); relAt.fill(-1L); relLay.fill(-1); relSure.fill(false); firedAt.fill(-1L); lastFire = null; box = false }
    }

    val watch = Watch()

    /**
     * THE TAKEOVER (m195; round 1533): after a hold fires the button's big face shows the HOLD spell in the game's gold checked ring
     * until its cast and the GCD that follows end (the add-on's `holdcast`: "cast" with its seconds left, "gcd" with the GCD's, "ok"; the
     * GCD from the state document's `gd` too), WHILE it is queued for the next swing (`q`: Raptor Strike, Heroic Strike, Maul ... or a
     * spell waiting for its target / ground cursor; until the swing lands - "ok" - or it is unqueued - "fail"; capped at [QUEUE_CAP_MS]),
     * at once on a "fail", a [cancel] or "none" (an instant that gave no GCD, no cast and no queue: no lingering), and [FALLBACK_MS] after
     * the last word if nothing more comes (an older add-on); it eases back over [EASE_MS]. Pure, on the band's clock (elapsedRealtime ms,
     * the cast / GCD times' own).
     */
    class Takeover {
        var pos = -1
            private set
        var layer = -1
            private set
        private var t = 0L; private var last = 0L
        private var castEnd = -1L; private var gcdEnd = -1L; private var okAt = -1L; private var failAt = -1L
        private var noneAt = -1L; private var chk0 = -1L
        /** the fix pass: the earliest the hold's cast can have started (the fire / the release: [t]; the add-on's rel word: the physical
         *  release before it), the GCD look-back's anchor; when its queue ended, -1 none */
        private var castFrom = 0L; private var qEnd = -1L
        /** queued for the next swing (or its target / cursor) now */
        var queued = false
            private set

        /** when it started (the band's clock): a takeover's identity (CastView keeps the spell that fired by it) */
        val startedAt: Long get() = t
        /** [castFrom] the earliest the hold's cast can have started (the fix pass: the rel word's start passes the physical release it
         *  answers; the fire and a sure release start before the game's cast: [now]) */
        fun start(pos: Int, layer: Int, now: Long, castFrom: Long = now) {
            this.pos = pos; this.layer = layer; t = now; last = now; this.castFrom = minOf(castFrom, now)
            castEnd = -1L; gcdEnd = -1L; okAt = -1L; failAt = -1L; noneAt = -1L; chk0 = -1L; qEnd = -1L; queued = false
        }
        /** the hold's cast began: [leftMs] of it to run */
        fun cast(leftMs: Long, now: Long) { if (pos < 0) return; last = now; castEnd = now + leftMs.coerceAtLeast(0L); queued = false }
        /** an instant hold went off with its GCD (or a channel ended, or the queued swing / spell went off) */
        fun ok(now: Long) { if (pos < 0) return; last = now; okAt = now; if (queued) qEnd = now; queued = false }
        /** refused / interrupted / unqueued / not the hold after all: back at once */
        fun fail(now: Long) { if (pos < 0) return; last = now; failAt = now; queued = false }
        fun cancel(now: Long) = fail(now)
        /** round 1533: queued for the next swing (the add-on's `q`): kept until it lands or is unqueued */
        fun queue(now: Long) { if (pos < 0) return; last = now; queued = true; chk0 = -1L }
        /** round 1533: no GCD, no cast and no queue came (the add-on's `none`): back at once. The fix pass: queued too - PadMap m199
         *  says it for a hold that WENT OFF with no GCD of its own (an interrupt, a trinket; a next-swing ability that landed) */
        fun none(now: Long) { if (pos < 0) return; last = now; noneAt = now; queued = false }
        /** round 1533: the GCD the hold started has [leftMs] to run (the add-on's `gcd`: known before the state document's `gd`) */
        fun gcdFor(leftMs: Long, now: Long) { if (pos < 0) return; last = now; gcdEnd = now + leftMs.coerceAtLeast(0L) }
        /** belt and braces: the hold spell's own "checked" state as the state document read it ([chk] 1 / 0) at [now]: queued with it 0
         *  for over [CHK_LOST_MS] and no word = the queue's end was lost: back */
        fun chk(chk: Int, now: Long) {
            if (pos < 0 || !queued) return
            if (chk == 1) { chk0 = -1L; return }
            if (chk0 < 0L) chk0 = now else if (now - chk0 > CHK_LOST_MS) { queued = false; failAt = now }
        }
        /** nothing on any face (tests; a new session) */
        fun clear() { pos = -1; layer = -1; queued = false }
        /**
         * RULING A (the user's pick 2026-11-25, "Stay until it ends (Recommended)": a trigger-layer hold - LT + X - keeps its takeover on
         * the main circle after the trigger is let go, until its cast and GCD end or it fails): the band showing layer [li] (0 none,
         * 1 LT, 2 RT, 3 LT + RT) draws it when [li] only LACKS some of the fired layer's triggers - its own layer, the bare one after
         * LT + X, any after LT + RT + X - never a layer with a trigger the press did not have (that layer's own buttons). It ends as
         * ever ([endAt]: its cast / GCD, a fail, [FALLBACK_MS], 15 s), whatever the layer
         */
        fun showsOn(li: Int): Boolean = pos >= 0 && layer in 0..3 && li in 0..3 && (li and layer.inv()) == 0
        /** the running GCD ([startMs], [durMs]): the one that started with the hold counts. The fix pass: from [castFrom] -
         *  [GCD_BACK_MS] (the game's frame and the clocks' slack), not 0.5 s before the start (the fire comes BEFORE the game's cast now:
         *  a D-pad spell's GCD 0.3 s before an interrupt's fire is not the interrupt's) */
        fun gcd(startMs: Long, durMs: Long) {
            if (pos < 0 || durMs <= 0L) return
            // the glyph follow-up: and no later than the game's spell-queue window after it ([GCD_AHEAD_MS]: an instant queued there goes
            // off when the last GCD ends), or near a queue's end ([qEnd]: the GCD it went off with; its word comes after it) - the NEXT
            // spell's GCD (the player's next press, off at the hold's GCD's end while the face eases out) is not the hold's: no pop back
            val withCast = startMs >= castFrom - GCD_BACK_MS && startMs <= castFrom + GCD_AHEAD_MS
            val withQueue = qEnd >= 0L && startMs >= qEnd - QEND_BACK_MS && startMs <= qEnd + GCD_BACK_MS
            if (withCast || withQueue) gcdEnd = startMs + durMs
        }

        /** when the hold spell leaves the face */
        fun endAt(): Long {
            if (failAt >= 0L) return failAt
            if (noneAt >= 0L) return noneAt
            if (queued) return t + QUEUE_CAP_MS                        // queued: no fallback, until it lands or is unqueued
            val base = if (qEnd >= 0L) maxOf(t, qEnd) else t           // (a queue that went off: its caps from then, as the top screen's)
            var known = -1L
            for (v in longArrayOf(castEnd, gcdEnd, if (okAt >= 0L) okAt + 300L else -1L)) if (v >= 0L && v > known) known = v
            val e = if (known >= 0L) known else base + FALLBACK_MS
            return minOf(e, maxOf(castEnd, last) + FALLBACK_MS, base + 15_000L)
        }
        /** 1 while it has the face, easing to 0 after [endAt]; 0 (and cleared) when it is over */
        fun alpha(now: Long): Float {
            if (pos < 0) return 0f
            val e = endAt()
            if (now < e) return 1f
            val a = 1f - (now - e).toFloat() / EASE_MS
            if (a <= 0f) { pos = -1; layer = -1; queued = false; return 0f }
            return a
        }
        /** how far the GCD after it has run down (1 = all left), -1 none known */
        fun gcdLeft(now: Long, durMs: Long): Float = if (gcdEnd < 0L || durMs <= 0L || now >= gcdEnd) -1f else ((gcdEnd - now).toFloat() / durMs).coerceIn(0f, 1f)
    }
    const val FALLBACK_MS = 2_000L
    const val EASE_MS = 200L
    /** the fix pass: a GCD that started up to this long before the hold's cast could have is still its own (a game frame, the clocks) */
    const val GCD_BACK_MS = 100L
    /** the glyph follow-up: the hold's own GCD starts no later than this after its cast could have (the game's spell-queue window, 0.4 s,
     *  and the clocks) */
    const val GCD_AHEAD_MS = 600L
    /** ... or up to this long before its queue's end word (the swing's / spell's SUCCEEDED, then the channel) */
    const val QEND_BACK_MS = 1_000L
    /** a queued next-swing ability keeps the face at most this long */
    const val QUEUE_CAP_MS = 30_000L
    /** the state document saying "not checked" this long while the takeover is queued, with no word: the queue's end was lost */
    const val CHK_LOST_MS = 1_000L

    val takeover = Takeover()
    /** the band's clock (elapsedRealtime ms: the cast and GCD times'); tests set their own */
    internal var bandClock: () -> Long = { SystemClock.elapsedRealtime() }

    init {
        watch.onFire = { pos, layer ->
            // (the fix pass) the cast began at the physical release this start answers: a sure release is now, the rel word's after it
            val band = bandClock()
            takeover.start(pos, layer, band, band - watch.fireLag)
            onChange?.invoke()
        }
    }

    /** CompanionHost: a CURRENT `holdcast` (PadMap m195 / m199: the fired hold's own cast - "cast" with d its seconds left, "ok",
     *  "fail"; m199: "q" queued for the next swing, "gcd" with gl the GCD's seconds left, "none") */
    fun onHoldCast(msg: JSONObject) {
        if (msg.optString("type") != "holdcast") return
        val pos = msg.optInt("pos", 0)
        if (pos != takeover.pos) return
        val now = bandClock()
        when (msg.optString("st")) {
            "cast" -> takeover.cast((msg.optDouble("d", 0.0) * 1000.0).toLong(), now)
            "ok" -> takeover.ok(now)
            "fail" -> takeover.fail(now)
            "q" -> takeover.queue(now)
            "gcd" -> takeover.gcdFor((msg.optDouble("gl", 0.0) * 1000.0).toLong(), now)
            "none" -> takeover.none(now)
            else -> return
        }
        onChange?.invoke()
    }

    /** the drawing's clock (uptime ms; the band draws on it, the launcher's writes are on it); tests set their own */
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    /** the drawing (Home's band): told when a word or the fire starts / ends a takeover */
    @Volatile var onChange: (() -> Unit)? = null

    /** CompanionHost: a CURRENT `holdmap` (main thread) */
    fun onMap(msg: JSONObject) {
        val m = parseMap(msg) ?: return
        watch.map = if (PlayModes.current == PlayMode.STREAM) emptySet() else m      // a PC game's presses: not this pad's
        watch.box = msg.optString("why") == "box"
        mapWhy = msg.optString("why")
        // m199: the clock's own looks and the game's frame (absent: an add-on before m199 - never an early fire)
        release.ft = msg.optInt("ft", 0).takeIf { it > 0 }
        release.cn = msg.optInt("cn", 0).takeIf { it in 2..4 }
    }

    /** CompanionHost: a CURRENT `facecast` (main thread): m199 a kept key-down's keep word ("hk" keep), any other word for its position
     *  (closing it), a kept press's release ("hk" rel: path slothold = its hold ran, else its tap) */
    fun onFacecast(msg: JSONObject) {
        val pos = msg.optInt("pos", 0)
        val hk = msg.optString("hk")
        val now = clock()
        unanswered(now)
        if (hk == "keep") {
            val lay = layerOf(msg.optString("lay")) ?: 0
            val need = msg.optInt("need", 0).takeIf { it in 2..4 }
            if (release.keep(msg.optString("key").ifEmpty { null }, pos, lay, need, now)) fireNow(now)   // a late word fires at once
            return
        }
        release.closed(pos, now)
        if (hk != "rel") return
        // round 1533: the answer to an early fire of ours
        val fp = firedPress
        if (fp != null && fp.pos == pos && now - fp.at <= CONFIRM_MS) {
            firedPress = null
            if (msg.optString("kind") == "slothold") {
                val sl = msg.optDouble("sl", Double.NaN)
                log("fire ok n=${fp.n} ft=${fp.ft ?: "?"} slack=${if (sl.isNaN()) "?" else ((sl * 1000.0).toLong()).toString()}ms")
            } else {
                // the up beat the N-th look: the TAP ran. The takeover goes at once; later fires wait longer
                if (takeover.pos == pos) takeover.cancel(bandClock())
                release.short()
                log("fire SHORT hn=${msg.optInt("hn", -1)} need=${msg.optInt("need", -1)} ft=${fp.ft ?: "?"} bump=${release.bump}ms")
                onChange?.invoke()
            }
            return
        }
        if (msg.optString("kind") != "slothold") {
            // the kept press's release ran the TAP: a takeover the app started by itself on THAT release (its own timing said "sure") goes
            // back at once. The fix pass: only that one - a hold's takeover the fire started (a queued Raptor Strike, for up to 30 s) is
            // not this tap's; the game keeps it queued, the top screen keeps it, so does Home
            if (takeover.pos == pos && watch.sureRelease(pos, now)) { takeover.cancel(bandClock()); onChange?.invoke() }
            return
        }
        watch.confirm(pos, now)                                         // (a confirm starts the takeover: onFire tells the drawing)
    }

    // ------------------------------------------------------------------------------------------------------------------ the fire

    /** round 1533 THE FIRE AT THE THRESHOLD: player 1's A / X / Y / D-pad as the launcher writes them (WinHandler) */
    val release = HoldRelease()
    /** the holdmap's why ("on" / "box" / "party" / "loading" / "none") */
    @Volatile var mapWhy = ""
    /** the gates at the fire moment (true = open): not a PC game's presses, not the Bind box / Party mode / a loading screen, none of our
     *  pop-ups or wheels holding the pad; tests set their own */
    var gates: () -> Boolean = {
        PlayModes.current != PlayMode.STREAM && mapWhy != "party" && mapWhy != "box" && mapWhy != "loading" &&
            !runCatching { SetupBridge.holdsGamePad() }.getOrDefault(false)
    }
    /** the fire's timer (main looper, uptime) - [HoldRelease.NEVER] = none; tests set their own */
    var schedule: (Long) -> Unit = { at -> post(at) }
    /** a check [ms] from now (the unanswered fire); tests set their own */
    var later: (Long, () -> Unit) -> Unit = { ms, f -> runCatching { handler().postDelayed({ f() }, ms) } }
    /** the log (WCHOLD and the problem report's lines); tests set their own */
    internal var log: (String) -> Unit = { l -> runCatching { android.util.Log.i(TAG, l) }; runCatching { DiagLog.event("hold", l) }; PressLog.hold(l) }
    /** the launcher's resend of player 1's state (WinHandler's static: never the handler itself) */
    @Volatile private var resend: Runnable? = null
    @JvmStatic fun setResend(r: Runnable?) { resend = r }

    private class FiredPress(val pos: Int, val at: Long, val n: Int?, val ft: Int?)
    @Volatile private var firedPress: FiredPress? = null

    private var lastButtons = 0
    private val lastDpad = BooleanArray(4)
    private val inDpad = BooleanArray(4)

    init {
        release.gatesOpen = { gates() }
        release.onFire = { pos, layer -> fired(pos, layer) }
    }

    /** the clock's looks the picture and the fire use: the add-on's `cn` when known, else Settings' */
    fun need(): Int = release.cn ?: CastSettings.holdTime.ticks

    /** WinHandler (main thread, player 1): the state it is about to write - A / X / Y / the D-pad's edges onto [release] */
    @JvmStatic @Synchronized fun padIn(buttons: Int, up: Boolean, right: Boolean, down: Boolean, left: Boolean, now: Long) {
        val b = buttons and 0xFFFF
        inDpad[0] = up; inDpad[1] = right; inDpad[2] = down; inDpad[3] = left
        for (c in 0 until HoldRelease.CTLS) {
            val was = HoldRelease.isDown(c, lastButtons, lastDpad)
            val now2 = HoldRelease.isDown(c, b, inDpad)
            if (was != now2) release.edge(c, now2, now)
        }
        lastButtons = b
        System.arraycopy(inDpad, 0, lastDpad, 0, 4)
    }
    /** WinHandler: the controls forced up for the game now (bits 0 / 2 / 3 = buttons A / X / Y; 4..7 = dpad[0..3]); one volatile read */
    @JvmStatic fun forcedMask(): Int = release.forcedMask
    /** Home: the button at game position [pos] is up for the game now (fired at its fill, still held): drawn unpressed */
    fun forced(pos: Int): Boolean = release.forcedMask != 0 && release.forcedPos(pos)
    /** the app lost the game screen: nothing fires while away */
    @JvmStatic fun focusLost() { release.pause(); schedule(HoldRelease.NEVER) }
    /** back: forced controls that read up are let go */
    @JvmStatic @Synchronized fun focusBack() { release.resume { c -> HoldRelease.isDown(c, lastButtons, lastDpad) } }
    /** player 1's controller changed (or the session ended: [all]): pending fires off; a forced button stays up for the game until it
     *  reads up (never a second down for a press still held) */
    @JvmStatic @Synchronized fun padReset(all: Boolean) {
        if (all) { release.reset(); lastButtons = 0; java.util.Arrays.fill(lastDpad, false) } else release.cancelPending()
        schedule(HoldRelease.NEVER)
    }

    /** the fires due now: the output changed -> the launcher sends player 1's state again; then the next one's timer */
    fun fireNow(now: Long = clock()) {
        if (release.due(now)) resend?.run()
        schedule(release.nextAt())
    }
    private fun fired(pos: Int, layer: Int) {
        val now = clock()
        firedPress = FiredPress(pos, now, release.cn, release.ft)
        watch.fired(pos, now)
        if (!watch.box) takeover.start(pos, layer, bandClock())
        onChange?.invoke()
        later(CONFIRM_MS + 50L) { unanswered(clock()) }
    }
    /** a fire with no release word from the add-on (a window took the key): logged once */
    private fun unanswered(now: Long) {
        val fp = firedPress ?: return
        if (now - fp.at <= CONFIRM_MS) return
        firedPress = null
        log("fire unanswered pos=${fp.pos} n=${fp.n} ft=${fp.ft ?: "?"}")
    }

    private var mainHandler: android.os.Handler? = null
    private fun handler(): android.os.Handler = mainHandler ?: android.os.Handler(android.os.Looper.getMainLooper()).also { mainHandler = it }
    private val fireTask = Runnable { fireNow() }
    private fun post(at: Long) {
        runCatching {
            val h = handler()
            h.removeCallbacks(fireTask)
            if (at != HoldRelease.NEVER) h.postAtTime(fireTask, at)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the hold time

    /** Settings' row changed the hold time (the picture follows CastSettings by itself): the add-on, through [onTicksWanted] */
    fun holdTimeChanged() { onTicksWanted?.invoke() }
    /** the hold time's sends of one wish (CompanionHost's MIRROR_TRIES) */
    const val SEND_TRIES = 3
    /** CompanionHost's sender (weakly held there) */
    @Volatile var onTicksWanted: (() -> Unit)? = null

    /**
     * THE HOLD TIME TO THE ADD-ON (Book b82's page-3 codes 29 / 30 / 31): [want] the app's looks, [have] the padmap's `hold.n` (null =
     * an add-on before m194: nothing to send), [inFight] (the add-on takes it out of combat only: wait), [sinceSendMs] since the last send
     * of this wish (null = none). The looks to send, or null
     */
    fun ticksToSend(want: Int, have: Int?, inFight: Boolean, sinceSendMs: Long?, tries: Int = 0): Int? {
        if (have == null || have == want || want !in 2..4) return null
        if (tries >= SEND_TRIES) return null                            // the 1532 audit: this wish's sends spent (a later padmap: none)
        if (inFight || (sinceSendMs != null && sinceSendMs < TopCombatBar.RESEND_MS)) return null
        return want
    }

    /** the Controller room's hint and Settings' tile: the fire point as the room writes it (F at 60 fps, rounded: 0.5 / 0.7 / 0.9 s) */
    fun holdWord(): String = when (CastSettings.holdTime) {
        CastSettings.HoldTime.SHORT -> "0.5 s"; CastSettings.HoldTime.NORMAL -> "0.7 s"; CastSettings.HoldTime.LONG -> "0.9 s"
    }
}
