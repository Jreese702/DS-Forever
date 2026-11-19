package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * DESKTOP CLICKS THE PAD (the user's pick 2026-11-18, "Desktop clicks on the PC"). While streaming, the companion's presses of the
 * add-on's hidden TapPad cells went as touches over the stream video (Moonlight touch -> Sunshine -> the PC's mouse), and the PC's WoW saw
 * each one +/- 1 px from where it was aimed (test 1496's taps.log: pnav aimed 15.5 seen 14.0, iact aimed 53.9 seen 54.0 = ibuy's)
 * whatever TapCal measured or nudged - the pad's rows are 2 px tall. Now, when DS Forever Desktop's welcome says "pad_click" (Desktop
 * 0.2.42) on a pinned wss connection, each batch goes to Desktop as ONE bridge message in the pad's own game px (the row's centre pixel,
 * the column's centre: [cellPoint]) with its waits, and Desktop clicks that exact pixel in the PC's WoW (PadClick.cs). The touch over
 * the video is no longer used for the pad; it stays only for an older Desktop (no caps) - [route]. Local is unchanged.
 *
 * NO CURSOR ON THE STREAM (the user, 2026-11-18: "the cursor is also visible for all of the clicks unlike when playing on the thor which
 * is really distracting"): Desktop first POSTS the click to WoW's window ("post": the PC's real cursor never moves). Whether WoW's UI
 * takes a posted click can't be known before it is tried, so every batch is checked against the add-on's own press ring (Hub's `rx`,
 * TapPad pad23): one posted press seen = posting works for the app's life; [MISSES] posted batches unseen after [VERIFY_MS] while none
 * was ever seen = WoW wants real input: "input" from then on (Desktop moves the real cursor to the pixel, clicks, puts it back) and the
 * last unseen batch is sent once more. [park] (Trackpad not open) lets Desktop move a resting cursor off the streamed screen.
 * A press that lands on another row than meant is still TapCal's note ("tap landed off ... (desktop click)"): it should not happen.
 * Pure but for [link] (BridgeLog sets it) and the tap log.
 */
object DeskClicks {
    /** Desktop's bridge connection while it says "pad_click" on a pinned wss session; null = none (the touch path, or Local) */
    fun interface Link { fun send(text: String): Boolean }
    @Volatile var link: Link? = null

    enum class Route { LOCAL, DESKTOP, STREAM_TOUCH }

    /** where a pad press goes: Local = the launcher (unchanged); streaming = Desktop when it clicks, else the old touch over the video */
    fun route(streaming: Boolean, linked: Boolean): Route = when { !streaming -> Route.LOCAL; linked -> Route.DESKTOP; else -> Route.STREAM_TOUCH }
    fun route(): Route = route(PlayGate.streamingNow(), link != null)

    const val VERIFY_MS = 3_500L
    const val MISSES = 2
    const val KEEP = 16
    /** TapPad's freelook park point (PARK_X, PARK_Y): where Desktop parks a resting cursor on a one-screen PC (the add-on's sink) */
    const val PARK_X = 0.5
    const val PARK_Y = 214.5 / 540

    @Volatile var mode = "post"
        private set
    @Volatile var postSeen = false
        private set
    private var misses = 0

    /** one cell's aim: game px (the pixel's middle) and its row (for TapCal's landing check) */
    data class Cell(val gx: Double, val gy: Double, val top: Double, val ch: Double)

    /**
     * Cell [op, col]'s point in the pad's own game px, or null (unknown cell, no geometry, outside the pad's screen): the column's
     * centre and the row's CENTRE PIXEL's middle (top + ch/2 + 0.5 = TapCal.cellY with no fit; a 2-px row's own pixel, never the edge it
     * shares). The row's top is the add-on's own word (pad23 `off`) when it names the row, else the grid's.
     */
    fun cellPoint(pad: JSONObject?, op: String, col: Int): Cell? {
        if (pad == null) return null
        val rows = pad.optJSONObject("rows") ?: return null
        if (!rows.has(op)) return null
        val row = rows.optInt(op, -1)
        val cw = pad.optDouble("cw", 0.0); val ch = pad.optDouble("ch", 0.0)
        val pw = pad.optDouble("pw", 0.0); val ph = pad.optDouble("ph", 0.0)
        if (row < 0 || cw <= 0 || ch <= 0 || pw <= 0 || ph <= 0) return null
        if (col < 0 || col >= pad.optInt("cols", 0)) return null
        val real = pad.optJSONObject("off")?.let { o -> if (o.has(op)) o.optDouble(op, Double.NaN).takeIf { !it.isNaN() } else null }
        val top = real ?: (pad.optDouble("y", 0.0) + row * ch)
        val gx = pad.optDouble("x", 0.0) + (col + 0.5) * cw + 0.5
        val gy = TapCal.cellY(top, ch, null)
        if (gx < 0 || gy < 0 || gx >= pw || gy >= ph) return null
        return Cell(gx, gy, top, ch)
    }

    /** a game point (a game control's own box) inside the pad's screen, or null */
    fun gamePoint(pad: JSONObject?, gx: Double, gy: Double): Pair<Double, Double>? {
        val pw = pad?.optDouble("pw", 0.0) ?: 0.0; val ph = pad?.optDouble("ph", 0.0) ?: 0.0
        if (pw <= 0 || ph <= 0 || gx < 0 || gy < 0 || gx >= pw || gy >= ph) return null
        return gx to gy
    }

    /** the "pad_click" message (pure): [taps] = (game x, game y, ms before it) */
    fun message(id: Long, mode: String, park: Boolean, pw: Double, ph: Double, taps: List<Triple<Double, Double, Long>>): JSONObject {
        val a = JSONArray()
        for ((x, y, w) in taps) a.put(JSONObject().put("x", r2(x)).put("y", r2(y)).put("w", w.coerceIn(0L, 1000L)))
        return JSONObject().put("t", "pad_click").put("id", id).put("mode", mode).put("park", park).put("pw", pw).put("ph", ph)
            .put("sink", JSONArray().put(r2(pw * PARK_X)).put(r2(ph * PARK_Y))).put("taps", a)
    }
    private fun r2(v: Double) = Math.round(v * 100.0) / 100.0

    private class Batch(val id: Long, val at: Long, val mode: String, val cells: List<Pair<String, Int>>?, val retry: Boolean,
                        val onFail: (String) -> Unit) {
        var landed = false
        var result: String? = null
        var checked = false
    }
    private val batches = object : LinkedHashMap<Long, Batch>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Batch>?) = size > KEEP
    }
    private val resends = LinkedHashSet<Long>()

    /**
     * Batch [id] to Desktop now (TapBridge's worker, after the quiet pad): [cells] = the pad cells (null = game points: never checked
     * against the ring, never sent again), [pts] their game px, [waits] the ms before each. False = no link (nothing sent).
     */
    fun send(id: Long, cells: List<Pair<String, Int>>?, pts: List<Pair<Double, Double>>, waits: List<Long>, pw: Double, ph: Double,
             park: Boolean, retry: Boolean, now: Long, onFail: (String) -> Unit): Boolean {
        val l = link ?: return false
        val m = mode
        synchronized(this) { batches[id] = Batch(id, now, m, cells, retry, onFail) }
        val msg = message(id, m, park, pw, ph, pts.mapIndexed { i, p -> Triple(p.first, p.second, waits.getOrElse(i) { 0L }) })
        val ok = runCatching { l.send(msg.toString()) }.getOrDefault(false)
        if (!ok) synchronized(this) { batches.remove(id) }
        return ok
    }

    /** Desktop's "pad_click_result": a refusal goes to the caller's onFail; the tap log's note, or null */
    fun onResult(o: JSONObject): String? {
        val id = o.optLong("id", -1L)
        val state = o.optString("state")
        val why = o.optString("why")
        val b = synchronized(this) { batches[id]?.also { it.result = state } } ?: return null
        if (state == "done") return null
        if (state == "refused" || state == "failed") runCatching { b.onFail(failText(why)) }
        return "desktop click: batch $id $state" + (if (why.isNotEmpty()) " ($why)" else "") + ", ${o.optInt("n", 0)} pressed"
    }

    fun failText(why: String): String = when (why) {
        "not_foreground" -> "WoW isn't in front on the PC: nothing was pressed"
        "not_streaming" -> "The PC isn't streaming: nothing was pressed"
        "no_wow" -> "WoW isn't running on the PC: nothing was pressed"
        "outside", "no_client" -> "The game's window on the PC doesn't fit the pad: nothing was pressed"
        "rate", "busy", "stale" -> "Too many presses at once: nothing was pressed"
        "not_pinned" -> "The PC's connection isn't secured: nothing was pressed"
        else -> "The PC didn't press it" + if (why.isNotEmpty()) " ($why)" else ""
    }

    /** the add-on saw a press of [op]:[col]: the newest unanswered batch holding that cell is seen (posting works, if it was posted) */
    @Synchronized fun landed(op: String, col: Int, now: Long) {
        val b = batches.values.reversed().firstOrNull { !it.landed && now - it.at <= VERIFY_MS * 3 && it.cells?.contains(op to col) == true } ?: return
        b.landed = true
        if (b.mode == "post") { postSeen = true; misses = 0 }
    }

    /**
     * After each current hub document ([verifiable] = the add-on reports its presses: pad24's strip is in the pad): the posted batches
     * Desktop pressed ("done") that the game never saw after [VERIFY_MS]. While no posted press was ever seen, [MISSES] of them switch
     * to "input" and the last one is queued to go again ([takeResends]). Returns the tap log's notes.
     */
    @Synchronized fun check(now: Long, verifiable: Boolean): List<String> {
        if (!verifiable) return emptyList()
        val out = ArrayList<String>()
        for (b in batches.values) {
            if (b.checked || b.landed || b.cells == null || b.result != "done" || now - b.at < VERIFY_MS) continue
            b.checked = true
            val what = b.cells.joinToString(" ") { "${it.first}:${it.second}" }
            if (b.mode == "post" && !postSeen) {
                misses++
                if (misses >= MISSES && mode == "post") {
                    mode = "input"
                    if (!b.retry) resends.add(b.id)
                    out += "desktop clicks: $misses posted batches not seen by the game ($what); real clicks from now" + if (!b.retry) ", sent again" else ""
                } else out += "desktop click not seen (posted): $what"
            } else out += "desktop click not seen (${b.mode}): $what"
        }
        return out
    }

    /** the batches to send once more (as "input"); forgets them */
    @Synchronized fun takeResends(): List<Long> { val l = resends.toList(); resends.clear(); return l }

    /** tests: forget everything */
    @Synchronized fun reset() { link = null; mode = "post"; postSeen = false; misses = 0; batches.clear(); resends.clear() }
}
