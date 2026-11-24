package com.jrees.wowcompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * HOLD TO CAST, THE APP'S HALF (the user, 2026-11-24: "give another option in the controller menu to "hold to cast" on a button so that we
 * can have 2 spells per button"). The design study (C:/dsf-work/design-hold-cast) and the user's picks: the APP times the press ([HoldGate],
 * in the launcher's controller path), the marker is the virtual pad's Guide button, the add-on's router (PadMap m190) casts the slot's
 * hold text when it reads the marker at the press.
 *
 * THE PROBE ("Yes, a quick test first"): once per game session, out of combat, a few seconds after the add-on says it is ready (its
 * `holdprobe` "ready": it READ the game's bindings on the Guide button's names first - any binding there and it says so and nothing is
 * pulsed), the app pulses the Guide button alone for [HoldGate.PROBE_MS]; the add-on reports whether it SAW it (the game's press event,
 * its controller state, the router's secure read) and whether the game did anything with it ("done"). Nothing is cast, nothing bound,
 * nothing undone. One WCHOLD line says the outcome; it is kept ([Store]) and hold to cast uses the Guide marker only while the last
 * probe said "seen, unbound" ([usable]); otherwise the Controller room says "Hold to cast isn't available yet". The key-channel
 * fallback (a Ctrl / Shift letter) stays parked (TapBridge.KEYS_ON = false).
 *
 * Main thread: the launcher's input callbacks and [main]'s posts; the add-on's documents are posted here.
 */
object HoldCast {
    const val TAG = "WCHOLD"

    // ------------------------------------------------------------------------------------------------------------------ the probe

    /** what a probe found */
    enum class Result(val word: String) { SEEN("seen"), UNSEEN("not seen"), BOUND("bound"), ACTED("the game reacted"), NO_ANSWER("no answer") }

    /** the probe's own state, once per game session. Pure (the clock is the caller's) */
    class Probe {
        enum class St { IDLE, ARMED, PULSED, DONE }
        var st = St.IDLE
            private set
        var session = Long.MIN_VALUE
            private set
        var pulseAt = 0L
            private set
        var pulsedAt = 0L
            private set
        private var bn: String? = null

        /** the add-on's "ready" (out of combat; bindings read). A bound Guide = done at once, NEVER pulsed; else armed [DELAY_MS] on */
        fun onReady(session: Long, bound: Boolean, bn: String?, ba: String?, now: Long): Outcome? {
            if (session != this.session) { this.session = session; st = St.IDLE }
            if (st != St.IDLE) return null                          // once per game session (a /reload says ready again: ignored)
            this.bn = bn
            if (bound) {
                st = St.DONE
                return Outcome(Result.BOUND, null, "probe: the Guide button is bound in the game (${word(bn)} = ${word(ba)}): " +
                    "not pulsed -> hold to cast unavailable")
            }
            st = St.ARMED
            pulseAt = now + DELAY_MS
            return null
        }

        /** time to pulse: armed, its time come, out of a fight, the game screen in front */
        fun due(now: Long, fighting: Boolean, focused: Boolean): Boolean = st == St.ARMED && now >= pulseAt && !fighting && focused

        /** not now (a fight, the game screen away, a press going on): again in [RETRY_MS] */
        fun later(now: Long) { if (st == St.ARMED) pulseAt = now + RETRY_MS }

        fun pulsed(now: Long) { if (st == St.ARMED) { st = St.PULSED; pulsedAt = now } }

        /**
         * The add-on's "done": [ev] the name its press event gave, [sn] the name its controller state read gave, [rn] the name the
         * router's secure read gave (the one hold to cast needs), [gb] a binding found on that name then, [acted] what the game did
         * ("menu"). Only after OUR pulse (a "done" from a /reload's window with no pulse says nothing).
         */
        fun onDone(ev: String?, sn: String?, rn: String?, gb: String?, acted: String?, now: Long): Outcome? {
            // the add-on's watch ended before our pulse (a fight / the game screen away kept it back): no pulse this session, the kept
            // answer stays (a pulse now would find nobody watching and read as "no answer")
            if (st == St.ARMED) { st = St.DONE; return null }
            if (st != St.PULSED) return null
            st = St.DONE
            val name = rn ?: sn ?: ev
            val r = when {
                acted != null -> Result.ACTED
                gb != null -> Result.BOUND
                rn != null -> Result.SEEN
                else -> Result.UNSEEN
            }
            val ok = r == Result.SEEN
            val line = "probe: Guide ${r.word} (event ${word(ev)}, state ${word(sn)}, router ${word(rn)}; binding ${gb ?: "none"}" +
                (if (acted != null) "; the game reacted: $acted" else "") + ") -> hold to cast ${if (ok) "on" else "unavailable"}"
            return Outcome(r, if (ok) name else null, line)
        }

        /** no "done" [ANSWER_MS] after the pulse */
        fun timedOut(now: Long): Outcome? {
            if (st != St.PULSED || now - pulsedAt < ANSWER_MS) return null
            st = St.DONE
            return Outcome(Result.NO_ANSWER, null, "probe: no answer from the add-on ${ANSWER_MS / 1000} s after the pulse -> hold to cast unavailable")
        }

        private fun word(s: String?) = s?.takeIf { it.isNotEmpty() } ?: "-"
    }

    data class Outcome(val result: Result, val guide: String?, val line: String)

    /** a few seconds after the add-ons report in; a fight / a busy pad puts it off; the add-on's own watch lasts 12 s */
    const val DELAY_MS = 3_000L
    const val RETRY_MS = 1_000L
    const val ANSWER_MS = 15_000L

    /** the kept outcome (SharedPreferences "holdcast"): the last probe's result word and the Guide's name in the game */
    object Store {
        private var prefs: android.content.SharedPreferences? = null
        fun init(c: Context) { if (prefs == null) prefs = c.applicationContext.getSharedPreferences("holdcast", Context.MODE_PRIVATE) }
        var result: String?
            get() = prefs?.getString("probe", null)
            set(v) { prefs?.edit()?.putString("probe", v)?.apply() }
        var guide: String?
            get() = prefs?.getString("guide", null)
            set(v) { prefs?.edit()?.putString("guide", v)?.apply() }
        /** off the device (tests): no prefs, the values here */
        internal var testResult: String? = null
    }

    /** the last probe said "seen, unbound" (kept across sessions; the next session's probe replaces it) */
    val usable: Boolean get() = (Store.result ?: Store.testResult) == Result.SEEN.word

    // ------------------------------------------------------------------------------------------------------------------ the add-on's map

    /**
     * The add-on's `holdmap` ({ on, b = "bare:8,lt:6,..." }): the places whose press the router turns into the hold spell NOW (the router
     * carries the key, the live slot has a hold text, Party / room mode off, not in a loading screen); null when it is not one. An empty
     * set = nothing gated.
     */
    fun parseMap(msg: JSONObject): Set<Int>? {
        if (msg.optString("type") != "holdmap") return null
        if (!msg.optBoolean("on", false)) return emptySet()
        val out = HashSet<Int>()
        for (w in msg.optString("b", "").split(',')) {
            val p = w.trim().split(':')
            if (p.size != 2) continue
            val pos = p[1].toIntOrNull() ?: continue
            HoldGate.keyOf(p[0], pos)?.let { out += it }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------------------------ the live gate

    val gate = HoldGate()
    val probe = Probe()
    private var mapNow: Set<Int> = emptySet()
    /** the game screen has the pad (the launcher's lifecycle) */
    @Volatile var focused = true
    /** a fight now (the padmap's word) */
    @Volatile var fighting = false
    /** the launcher's re-send of the controller state (WinHandler registers it): the gate's output changes without a press */
    @JvmStatic @Volatile var resend: Runnable? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** the drawing (Home's band): told when a press starts / fires / ends */
    @Volatile var onChange: (() -> Unit)? = null

    private fun now() = SystemClock.uptimeMillis()

    private fun configure() {
        gate.holdMs = CastSettings.holdTime.ms
        gate.configure(usable && focused, mapNow, now())
        resend?.run()
    }

    /**
     * THE LAUNCHER'S CALL (WinHandler, player 1 only, every controller send): [buttons] GamepadState's bits (0 A, 1 B, 2 X, 3 Y, 4 LB,
     * 5 RB, 6 Select, 7 Start, 8 L3, 9 R3), [up] / [right] / [down] / [left] the D-pad, [lt] / [rt] the triggers, at [now] (uptime ms).
     * Returns the bits for the game, the D-pad at 10..13 and the Guide marker at 15; the triggers for the game in [ltOut] / [rtOut].
     */
    @JvmStatic
    fun filter(buttons: Int, up: Boolean, right: Boolean, down: Boolean, left: Boolean, lt: Float, rt: Float, now: Long): Int {
        var raw = buttons and 0x3FF
        if (up) raw = raw or (1 shl HoldGate.UP)
        if (right) raw = raw or (1 shl HoldGate.RIGHT)
        if (down) raw = raw or (1 shl HoldGate.DOWN)
        if (left) raw = raw or (1 shl HoldGate.LEFT)
        val wasBusy = gate.busy()
        val o = gate.update(raw, lt, rt, now)
        if (wasBusy != gate.busy() || gate.busy()) onChange?.invoke()
        return o
    }
    @JvmStatic fun ltOut(): Float = gate.ltOut
    @JvmStatic fun rtOut(): Float = gate.rtOut
    /** when the launcher must send again (uptime ms), or Long.MAX_VALUE */
    @JvmStatic fun nextAt(): Long = gate.nextAt()

    /** the launcher lost / got back the game screen (its pause / resume, the window's focus) */
    @JvmStatic fun focusLost() {
        focused = false
        gate.reset()
        configure()
        onChange?.invoke()
    }
    @JvmStatic fun focusBack() { focused = true; configure() }

    /** CompanionHost: a CURRENT `holdmap` */
    fun onMap(msg: JSONObject) {
        val m0 = parseMap(msg) ?: return
        val m = if (PlayModes.current == PlayMode.STREAM) emptySet() else m0     // a PC game's map: never the local gate's
        main.post { if (m != mapNow) { mapNow = m; configure() } }
    }

    /** CompanionHost: a CURRENT `holdprobe` ("ready" / "done") */
    fun onProbe(msg: JSONObject) {
        if (PlayModes.current == PlayMode.STREAM) return                       // the PC's game: not probed from here
        val st = msg.optString("st")
        main.post {
            val now = now()
            val out = when (st) {
                "ready" -> probe.onReady(LauncherHooks.gameStartSec, msg.optBoolean("bound", false), w(msg, "bn"), w(msg, "ba"), now)
                "done" -> probe.onDone(w(msg, "ev"), w(msg, "sn"), w(msg, "rn"), w(msg, "gb"), w(msg, "acted"), now)
                else -> null
            }
            if (out != null) finish(out)
            if (probe.st == Probe.St.ARMED) main.postDelayed(probeTick, maxOf(0L, probe.pulseAt - now))
        }
    }

    private val probeTick: Runnable = object : Runnable {
        override fun run() {
            val now = now()
            when (probe.st) {
                Probe.St.ARMED -> {
                    // the game in THIS launcher (a Streaming session's game is the PC's: our gate is not on its road; never probed there)
                    val local = InputSink.sink != null && PlayModes.current == PlayMode.LOCAL
                    if (probe.due(now, fighting, focused) && local && gate.probePulse(now)) {
                        probe.pulsed(now)
                        Log.i(TAG, "probe: the Guide button pulsed alone for ${HoldGate.PROBE_MS} ms")
                        resend?.run()
                        main.postDelayed({ resend?.run() }, HoldGate.PROBE_MS + 5)
                        main.postDelayed(this, ANSWER_MS + 50)
                    } else {
                        if (now >= probe.pulseAt) probe.later(now)
                        main.postDelayed(this, maxOf(50L, probe.pulseAt - now))
                    }
                }
                Probe.St.PULSED -> probe.timedOut(now)?.let { finish(it) } ?: main.postDelayed(this, 1_000L)
                else -> {}
            }
        }
    }

    private fun finish(o: Outcome) {
        Log.i(TAG, o.line)
        runCatching { DiagLog.event("hold", o.line) }
        Store.result = o.result.word
        Store.guide = o.guide
        configure()
    }

    private fun w(msg: JSONObject, k: String): String? =
        msg.optString(k, "").takeIf { it.isNotEmpty() && it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }

    /** Settings' row changed the hold time */
    fun holdTimeChanged() = main.post { configure() }

    /** the app's start */
    fun init(c: Context) {
        Store.init(c)
        CastSettings.onChange { main.post { if (gate.holdMs != CastSettings.holdTime.ms) configure() } }
    }

    /** the Controller room's hint line: the time in seconds as the room writes it ("0.4 s") */
    fun holdWord(): String = when (CastSettings.holdTime) {
        CastSettings.HoldTime.SHORT -> "0.3 s"; CastSettings.HoldTime.NORMAL -> "0.4 s"; CastSettings.HoldTime.LONG -> "0.55 s"
    }
}
