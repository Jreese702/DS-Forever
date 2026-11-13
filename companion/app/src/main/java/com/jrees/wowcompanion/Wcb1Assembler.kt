package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE ADD-ON'S DOCUMENTS FROM RAW LOG LINES, whoever reads the log (Streaming, S2: one parser for both [LogSource]s). Moved here
 * unchanged from LogTail.handleLine, so the local log ([LogTail]) and the Desktop bridge's copy of the PC's log ([LogSource] Bridge)
 * reassemble exactly the same way:
 *  - only lines with the WCB1 magic are the add-on's (the engine's own [N][Lua] chatter shares the file);
 *  - "WCB1|<seq>|<i>/<n>|<payload>": long documents come as n parts and are joined before parsing;
 *  - the add-on's sequence number increments BEFORE each write, so a gap in seq means a line was refused (a secret leaked into it):
 *    surfaced through onStatus, never silently;
 *  - each document carries the game's own clock from the line's timestamp prefix as "_gt" (see [gameTime]).
 * Not thread-safe: one reader thread feeds it.
 */
class Wcb1Assembler(
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val frags = HashMap<Int, MutableList<String?>>()   // seq of part 1 -> parts
    private var lastSeq = -1
    var gaps = 0
        private set
    /** issue #231 (diagnostics only): log lines read, lines with the WCB1 magic, documents handed on (read off the reader thread: approximate) */
    @Volatile var linesSeen = 0L
        private set
    @Volatile var magicLines = 0L
        private set
    @Volatile var docsOut = 0L
        private set

    /** the log restarted (truncated, or another log): half-assembled documents and the sequence are dropped */
    fun reset() { frags.clear(); lastSeq = -1 }

    /** documents waiting for more parts (for tests and the status line) */
    fun pending(): Int = frags.size

    fun line(rawIn: String) {
        // The client is a Windows app (under Wine on the Thor, natively on the PC), so General.log uses CRLF. Splitting on '\n'
        // leaves a '\r' on the tail of every line -- invisible, and it lands exactly at each fragment boundary, corrupting the
        // reassembled JSON there. Strip it.
        val raw = rawIn.trimEnd('\r', '\n')
        linesSeen++
        val i = raw.indexOf(MAGIC)
        if (i < 0) return                          // engine chatter, ignore
        magicLines++
        val body = raw.substring(i + MAGIC.length) // "<seq>|<part>/<n>|<payload>"
        if (body.startsWith("pad|")) return        // flush padding

        val p1 = body.indexOf('|'); if (p1 < 0) return
        val seq = body.substring(0, p1).toIntOrNull() ?: return
        val p2 = body.indexOf('|', p1 + 1); if (p2 < 0) return
        val partSpec = body.substring(p1 + 1, p2)
        val payload = body.substring(p2 + 1)

        if (lastSeq >= 0 && seq != lastSeq + 1) {
            gaps++
            onStatus("gap at seq $seq (expected ${lastSeq + 1})")
        }
        lastSeq = seq

        val slash = partSpec.indexOf('/')
        if (slash < 0) return
        val idx = partSpec.substring(0, slash).toIntOrNull() ?: return
        val total = partSpec.substring(slash + 1).toIntOrNull() ?: return

        val gt = gameTime(raw, i)
        if (total == 1) { emit(payload, gt); return }

        val key = seq - (idx - 1)                  // all parts of one document share this
        val slots = frags.getOrPut(key) { MutableList(total) { null } }
        if (idx in 1..slots.size) slots[idx - 1] = payload
        if (slots.all { it != null }) {
            frags.remove(key)
            emit(slots.joinToString("") { it!! }, gt)
        }
    }

    private fun emit(json: String, gt: Long) {
        try {
            val o = JSONObject(json)
            if (gt >= 0) o.put("_gt", gt)
            docsOut++
            onMessage(o)
        }
        catch (t: Throwable) { onStatus("bad json (${json.length}b): ${t.message}") }
    }

    companion object {
        const val MAGIC = "WCB1|"
        private val DAYS_BEFORE = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

        /**
         * The line's own timestamp from the log prefix ("9/27 08:41:53.221  [N][Lua] "), as ms on a day-of-year + time-of-day scale,
         * or -1 if it does not parse.
         *
         * WHY THE APP NEEDS THE GAME'S CLOCK. The addon writes a position line every ~107 ms but the log only reaches us when a flush
         * forces it out, ~every 216 ms, so 95% of position lines arrive TWO AT A TIME in one read (measured 2026-09-27 over a real
         * session). Timed by arrival, both lines look simultaneous: the map kept the older one stamped as current and threw most of
         * the newer one away, and the arrow's speed estimate was wrong by half again -- which the prediction turned into overshoot
         * and snap-back. The log stamps every line when the game wrote it, to the millisecond; that is the real spacing.
         * Only differences matter (the map maps it onto its own clock), so the year is not needed; a year boundary is one backwards
         * jump the map treats as a clock reset.
         */
        fun gameTime(raw: String, magicAt: Int): Long {
            return try {
                val head = raw.substring(0, magicAt).trim()
                val sp = head.indexOf(' ')
                val date = head.substring(0, sp)
                val time = head.substring(sp + 1).trim().substringBefore(' ')
                val slash = date.indexOf('/')
                val mon = date.substring(0, slash).toInt()
                val day = date.substring(slash + 1).toInt()
                val c1 = time.indexOf(':'); val c2 = time.indexOf(':', c1 + 1)
                val h = time.substring(0, c1).toInt()
                val mi = time.substring(c1 + 1, c2).toInt()
                val secMs = (time.substring(c2 + 1).toDouble() * 1000.0).toLong()
                val doy = DAYS_BEFORE[(mon - 1).coerceIn(0, 11)] + day
                ((doy * 24L + h) * 60L + mi) * 60_000L + secMs
            } catch (t: Throwable) { -1L }
        }
    }
}
