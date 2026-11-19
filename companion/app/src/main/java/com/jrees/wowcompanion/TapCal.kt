package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

/**
 * fix/stream-row-offset (the user, 2026-11-18, test 1494 streamed: "testing stream, ui still didnt hide"): THE STREAM'S TAPS MEASURED.
 *
 * What test 1494's tap log proved (TapPad pad23's ring of presses beside the taps sent): the app aimed every cell exactly where the pad
 * document says (the click log: y = 12 + 2 x row + 1.5 for every row), Sunshine put the PC's cursor there (its capture 1920x1080 at 0,0,
 * virtual desktop 3840x1080, the same as Windows'), WoW's window was the whole screen (a borderless 1920x1080 popup at 0,0, client
 * 1920x1080) - and still every "ui" tap (row 51, aimed y 115.5) pressed "uisw" (row 50) and every "pnav" tap (row 1, y 15.5) pressed
 * "chq" (row 2), while qact / auracancel / gf / idlg / gamemenu / uisw (rows 11 to 50) were right. One shape fits all of it, and no
 * constant offset or row-list disagreement can (ui and uisw are neighbours in both lists, and taps aimed at both pressed uisw): the
 * game sees a press at a x the cursor's pixel + b with a 0.9745-0.980 and b 1.3-1.9 (a ~2.2 % squeeze toward y ~65), which moves the
 * band's top rows one row DOWN and its bottom rows one row UP. That is inside the PC's game, where the app cannot look, and no
 * assumption about it can be trusted at another resolution or UI scale.
 *
 * So the stream MEASURES it: TapPad pad24 publishes a calibration strip (`calib`: a plain button of ours left of the grid over the
 * band's height, that does nothing but keep where the game saw each press). The app taps it twice, near its top and its bottom, reads
 * where the game saw those presses and fits seen = a x aim + b; every later cell (and game point) is aimed through the fit. A press the
 * add-on reports on another row than the one the app meant is noted in the tap log (terse) and drops the fit, so it is measured again.
 * Local (the launcher) is unchanged: no strip taps, no fit.
 */
object TapCal {
    /** the strip as the pad document describes it, in game px; [seen] = where the game saw its last presses (oldest first) */
    data class Strip(val x: Double, val y: Double, val w: Double, val h: Double, val n: Int, val seen: List<Pair<Double, Double>>)

    /** the game sees a press the app aims at game y `aim` at [a] x aim + [b] (game px, the pad's own); [key] = the layout it is for */
    data class Fit(val a: Double, val b: Double, val key: String) {
        fun seen(aim: Double) = a * aim + b
        fun word() = "y x${"%.4f".format(java.util.Locale.ROOT, a)} ${if (b >= 0) "+" else "-"}${"%.2f".format(java.util.Locale.ROOT, abs(b))}"
    }

    /** one tap the app sent on a cell: what it meant and where it aimed */
    data class Aim(val op: String, val col: Int, val top: Double, val ch: Double, val aimY: Double, val at: Long)

    private data class Pending(val key: String, val n0: Int, val at: Long, val aimY: List<Double>)

    const val WAIT_MS = 3_000L            // a strip tap's answer rides the next hub flush
    const val MAX_TRIES = 3               // per layout: then the stream's taps go as before, and that is said once
    const val AIM_KEEP_MS = 5_000L        // a landing is matched to a tap sent at most this long before
    const val NOTE_EVERY_MS = 30_000L     // one landing note per meant row at most this often
    const val SPAN_MIN = 20.0             // the two strip taps' least distance apart (game px)

    @Volatile var fit: Fit? = null
        private set
    /** the hub document's boot (every /reload): a fit is for one load of the game */
    @Volatile var boot: Long = 0L
    private var pend: Pending? = null
    private var tries = 0
    private var triesKey = ""
    private var gaveUpSaid = ""
    private val aims = ArrayDeque<Aim>()
    private val noteAt = HashMap<String, Long>()

    // ------------------------------------------------------------------------------------------------------------ pure

    fun strip(pad: JSONObject?): Strip? {
        val c = pad?.optJSONObject("calib") ?: return null
        val w = c.optDouble("w", 0.0); val h = c.optDouble("h", 0.0)
        if (!c.has("x") || !c.has("y") || w <= 0 || h <= 0) return null
        val seen = c.optString("p", "").split(' ').mapNotNull { t ->
            val xy = t.split(',')
            val x = xy.getOrNull(0)?.toDoubleOrNull(); val y = xy.getOrNull(1)?.toDoubleOrNull()
            if (x != null && y != null) x to y else null
        }
        return Strip(c.optDouble("x"), c.optDouble("y"), w, h, c.optInt("n", 0), seen)
    }

    /** the layout a fit is for: the game's screen, the strip's box, the load */
    fun key(pad: JSONObject?, boot: Long): String? {
        val s = strip(pad) ?: return null
        return "${pad!!.optDouble("pw")}x${pad.optDouble("ph")}|${s.x},${s.y},${s.h}|$boot"
    }

    /** the strip's two taps (game px): its middle column, the pixel centres 4 px inside each end */
    fun aimsFor(s: Strip): List<Pair<Double, Double>> {
        val cx = floor(s.x + s.w / 2) + 0.5
        return listOf(cx to floor(s.y + 4) + 0.5, cx to floor(s.y + s.h - 5) + 0.5)
    }

    /** the fit from the two strip taps aimed at [aimY] and seen at [seenY], or null when it cannot be trusted */
    fun fitOf(aimY: List<Double>, seenY: List<Double>, key: String): Fit? {
        if (aimY.size != 2 || seenY.size != 2) return null
        val dA = aimY[1] - aimY[0]
        if (abs(dA) < SPAN_MIN) return null
        val a = (seenY[1] - seenY[0]) / dA
        val b = seenY[0] - a * aimY[0]
        if (a.isNaN() || a < 0.8 || a > 1.25 || abs(b) > 40.0) return null
        return Fit(a, b, key)
    }

    /**
     * Where to aim (game y) for a cell whose row starts at game y [top], [ch] tall. Without a fit: the cell's lower pixel (1.0.21, as
     * before). With one: the centre of the pixel whose press the game sees nearest the row's centre - within half a pixel x a of it
     * whether the PC's cursor lands on whole pixels or not (an identity fit gives the same point as no fit).
     */
    fun cellY(top: Double, ch: Double, f: Fit?): Double =
        if (f == null) top + ch / 2 + 0.5 else floor((top + ch / 2 - f.b) / f.a) + 0.5

    /** where to aim for a game point (a game control's own box, a layout spot) */
    fun pointY(gy: Double, f: Fit?): Double = if (f == null) gy else (gy - f.b) / f.a

    // ------------------------------------------------------------------------------------------------------------ state

    /** the fit for [pad]'s layout, or null (no strip, another layout or load, not measured) */
    fun current(pad: JSONObject?): Fit? {
        val f = fit ?: return null
        return if (f.key == key(pad, boot)) f else null
    }

    /** a measurement under way (its answer not in yet) */
    @Synchronized fun busy(now: Long): Boolean = pend?.let { now - it.at < WAIT_MS } ?: false

    /**
     * The strip taps to send now (game px), or null: the stream ([streaming]) wants a fit for [pad]'s layout and has none, nothing is
     * under way and this layout has tries left. Marks the measurement as under way.
     */
    @Synchronized fun wanted(pad: JSONObject?, streaming: Boolean, now: Long): List<Pair<Double, Double>>? {
        if (!streaming) return null
        val s = strip(pad) ?: return null
        val k = key(pad, boot) ?: return null
        if (fit?.key == k) return null
        if (pend?.let { now - it.at < WAIT_MS } == true) return null
        if (triesKey != k) { triesKey = k; tries = 0 }
        if (tries >= MAX_TRIES) return null
        tries++
        val pts = aimsFor(s)
        pend = Pending(k, s.n, now, pts.map { it.second })
        return pts
    }

    /** the strip taps were not sent after all */
    @Synchronized fun cancel() { pend = null; if (tries > 0) tries-- }

    /**
     * A pad document arrived: completes a measurement whose two presses are in (sets the fit), or one that never got them (said once
     * the tries are spent). Returns the tap log's note, or null.
     */
    @Synchronized fun settle(pad: JSONObject?, now: Long): String? {
        val p = pend ?: return null
        val s = strip(pad)
        val k = key(pad, boot)
        if (s == null || k != p.key) { pend = null; return null }                   // another layout: measured again for it
        if (s.n >= p.n0 + 2 && s.seen.size >= 2) {
            pend = null
            val seen = s.seen.takeLast(2).map { it.second }
            val f = fitOf(p.aimY, seen, k)
            if (f == null) return "tap calibration refused: aimed y ${p.aimY.joinToString("/") { fmt(it) }}, seen ${seen.joinToString("/") { fmt(it) }}"
            fit = f
            return "tap calibration (stream): seen = ${f.word()} (aimed ${p.aimY.joinToString("/") { fmt(it) }}, seen ${seen.joinToString("/") { fmt(it) }})"
        }
        if (now - p.at >= WAIT_MS) {
            pend = null
            if (tries >= MAX_TRIES && gaveUpSaid != k) { gaveUpSaid = k; return "tap calibration: no answer from the strip ($tries tries); taps aimed as before" }
        }
        return null
    }

    /** taps sent on cells (TapBridge): kept a few seconds, to tell where each landed */
    @Synchronized fun aimed(list: List<Aim>) {
        for (a in list) aims.addLast(a)
        while (aims.size > 32) aims.removeFirst()
    }

    /**
     * The add-on says a press landed on [name]:[col], seen at game y [seenY]. A note when the latest tap on that column was meant for
     * another row (or the press was seen outside the row it named), else null. A miss while a fit is in use drops it (measured again).
     */
    @Synchronized fun landed(name: String, col: Int, seenY: Double?, now: Long): String? {
        val a = aims.lastOrNull { it.col == col && now - it.at <= AIM_KEEP_MS } ?: return null
        val off = a.op != name || (seenY != null && (seenY < a.top || seenY >= a.top + a.ch))
        if (!off) return null
        if (a.op != name) fit = null
        val k = a.op + ">" + name
        val last = noteAt[k]
        if (last != null && now - last < NOTE_EVERY_MS) return null
        noteAt[k] = now
        return "tap landed off: ${a.op}:$col (rows y ${fmt(a.top)}-${fmt(a.top + a.ch)}, aimed ${fmt(a.aimY)}) pressed $name:$col" +
            (seenY?.let { " seen at y ${fmt(it)}" } ?: "")
    }

    /** tests: forget everything */
    @Synchronized fun reset() {
        fit = null; boot = 0L; pend = null; tries = 0; triesKey = ""; gaveUpSaid = ""; aims.clear(); noteAt.clear()
    }

    private fun fmt(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)
}
