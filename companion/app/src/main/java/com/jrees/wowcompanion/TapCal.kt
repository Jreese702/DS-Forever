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
 *
 * fix/stream-row-center (the user, 2026-11-18, test 1495 streamed: "I'm trying to sync my polestar up to where I'm actually at and its
 * saying polestar did not answer"): THE ROW'S CENTRE, AND A NUDGE WHERE IT STILL MISSES. Test 1495's landings (taps.log 18:38-18:40):
 * the game sees each press on a WHOLE pixel (the strip: aimed 16.5/119.5, seen 16.0/118.0), so a 2-px row has exactly ONE pixel of its
 * own - its centre (top + 1); the two edge pixels are shared with the neighbours, and which of the two buttons takes a press there is
 * the game's frame order. Every miss of 1495 was seen exactly ON a row edge: pnav (14-16) aimed 15.5 seen 16.0 = chq's, every time;
 * iact (52-54) aimed 53.5 seen 52.0 = ipick's; mapw (94-96) aimed 96.5 seen 96.0 (mapw's, by luck). And the stream's map is a
 * staircase, not a line (15.5 -> 16 but 53.5 -> 52 and 96.5 -> 96: the touch's whole-pixel steps on the way to the PC), so no
 * two-point fit can say on which side of a pixel edge one row's aim falls. So: (1) the aim is the inverse of the fit at the row's
 * centre pixel, unsnapped ([cellY]: (centre - b) / a; the fit was taken with pixel-centre aims, so that IS the centre pixel's middle -
 * the old floor + 0.5 re-snap could only move it toward an edge); (2) where a stream tap is still seen off its row's centre, that
 * row's aim is NUDGED half a row toward it ([nudge], kept for the app's life, per row and top), and a tap that pressed ANOTHER row
 * is sent again once with the nudge ([takeRetries], the whole batch: Polestar's pin digits are one sequence). A near miss no longer
 * drops the fit (a new measurement gives the same two whole pixels); a press seen more than a row off still does. Local unchanged.
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
    data class Aim(val op: String, val col: Int, val top: Double, val ch: Double, val aimY: Double, val at: Long,
                   /** fix/stream-row-center: its batch (TapBridge; 0 = none), sent to the stream, a retry, the nudge it carried */
                   val batch: Long = 0L, val stream: Boolean = false, val retry: Boolean = false, val nudge: Double = 0.0,
                   /** feat/desktop-clicks: Desktop clicked it on the PC (DeskClicks): an exact pixel, so a miss is only ever noted */
                   val desk: Boolean = false)

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
    /** fix/stream-row-center: the stream's learned nudge per row ("op@top"), game px added to the aim */
    private val nudges = HashMap<String, Double>()
    /** fix/stream-row-center: batches to send once more (their nudge learned), oldest first; every batch ever queued */
    private val retries = LinkedHashSet<Long>()
    private val retried = HashSet<Long>()

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
     * before). With one (fix/stream-row-center): the exact inverse of the fit at the row's centre pixel, (top + ch/2 - b) / a. The fit
     * maps a pixel-CENTRE aim (the strip's x.5 taps) to the whole pixel the game saw, so this is the middle of the aims that land on the
     * row's centre pixel, as far as two whole pixels can tell (an identity PC, seen = floor(aim), gives the same point as no fit). No
     * re-snap to x.5: that only moved the aim toward a pixel edge (test 1495's pnav: 15.49 -> 15.5).
     */
    fun cellY(top: Double, ch: Double, f: Fit?): Double =
        if (f == null) top + ch / 2 + 0.5 else (top + ch / 2 - f.b) / f.a

    /** fix/stream-row-center: the stream's learned nudge for row [op] standing at [top] (0 = none) */
    @Synchronized fun nudge(op: String, top: Double): Double = nudges[nkey(op, top)] ?: 0.0
    private fun nkey(op: String, top: Double) = op + "@" + fmt(top)

    /** fix/stream-row-center: the batches whose taps pressed another row and are to go once more (nudged); forgets them */
    @Synchronized fun takeRetries(): List<Long> { val l = retries.toList(); retries.clear(); return l }

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
     * another row (or the press was seen outside the row it named), else null.
     *
     * fix/stream-row-center: a STREAM tap ([Aim.stream]) seen off its row's centre pixel (an edge pixel is shared with the neighbour)
     * nudges that row's aim half a row toward the centre - once per lesson: only a tap aimed with the row's CURRENT nudge teaches, so
     * the rest of its batch and taps already in flight do not pile on (the nudge stays within one row height). A tap that pressed
     * another row has its batch queued to go once more with the new nudge ([takeRetries]; never a retry's retry, never a batch twice).
     * The fit is dropped (measured again) only for a press on another row seen more than a row off its centre, or with no seen y (as
     * before). Local taps: the note only, as before.
     */
    @Synchronized fun landed(name: String, col: Int, seenY: Double?, now: Long): String? {
        val a = aims.lastOrNull { it.col == col && now - it.at <= AIM_KEEP_MS } ?: return null
        val centre = a.top + a.ch / 2
        val edge = seenY != null && abs(seenY - centre) >= a.ch / 4               // on (or past) a shared edge pixel, not the centre one
        val wrongRow = a.op != name
        val off = wrongRow || (seenY != null && (seenY < a.top || seenY >= a.top + a.ch))
        if (!off && !(a.stream && edge)) return null
        if (wrongRow && (seenY == null || abs(seenY - centre) > a.ch * 1.5)) fit = null
        var learned: String? = null
        if (a.stream && seenY != null && edge) {
            val k = nkey(a.op, a.top)
            val cur = nudges[k] ?: 0.0
            if (abs(a.nudge - cur) < 1e-9) {
                val next = (cur + if (seenY > centre) -a.ch / 2 else a.ch / 2).coerceIn(-a.ch, a.ch)
                if (abs(next - cur) > 1e-9) {
                    nudges[k] = next
                    val again = wrongRow && !a.retry && a.batch != 0L && retried.add(a.batch)
                    if (again) retries.add(a.batch)
                    learned = "${a.op} aim nudged ${if (next >= 0) "+" else "-"}${fmt(abs(next))}" + (if (again) ", sent again" else "")
                }
            }
        }
        if (!off) return learned?.let {
            "tap on a row edge: ${a.op}:$col (rows y ${fmt(a.top)}-${fmt(a.top + a.ch)}, aimed ${fmt(a.aimY)}) seen at y ${fmt(seenY!!)}; $it"
        }
        val k = a.op + ">" + name
        val last = noteAt[k]
        if (learned == null && last != null && now - last < NOTE_EVERY_MS) return null
        noteAt[k] = now
        return "tap landed off: ${a.op}:$col (rows y ${fmt(a.top)}-${fmt(a.top + a.ch)}, aimed ${fmt(a.aimY)}) pressed $name:$col" +
            (seenY?.let { " seen at y ${fmt(it)}" } ?: "") + (learned?.let { "; $it" } ?: "") + (if (a.desk) " (desktop click)" else "")
    }

    /** tests: forget everything */
    @Synchronized fun reset() {
        fit = null; boot = 0L; pend = null; tries = 0; triesKey = ""; gaveUpSaid = ""; aims.clear(); noteAt.clear()
        nudges.clear(); retries.clear(); retried.clear()
    }

    private fun fmt(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)
}
