package com.jrees.wowcompanion

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * FOLLOWS THE GAME'S COMBAT LOG (CombatLog: why). The game starts a new file, Logs/WoWCombatLog-<date>_<time>.txt, each time logging is
 * switched on (the add-on switches it on at every login, PadMap m92), so the newest file in the folder is followed, from its END (the
 * past says nothing about now). Small reads (128 KB a poll, the memory rule of 2026-11-07), on its own low-priority thread.
 * It also MEASURES the delay: the gap between each line's own time and the moment it was read, logged every 30 s (WCCLOG), the
 * question the user's test has to answer - whether the game writes the file fast enough for health.
 */
class CombatLogTail(private val logsDir: File, private val onChange: () -> Unit) {
    private val thread = HandlerThread("combatlog", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var running = false
    private var file: File? = null
    private var offset = 0L
    private var carry = ""
    private var pickedAt = 0L
    private val lags = ArrayList<Long>()
    private var lagAt = 0L
    private var lastNotify = 0L
    private var pending = false
    private var lines = 0L

    fun start() { running = true; handler.post(pump) }
    fun stop() { running = false; handler.removeCallbacksAndMessages(null); thread.quitSafely() }

    private val pump = object : Runnable {
        override fun run() {
            if (!running) return
            try { poll() } catch (t: Throwable) { Log.w(TAG, "poll: ${t.message}") }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** a file the add-on just stopped (m95: stopping writes it out): read to its end for a few seconds after the switch */
    private class Drain(val f: File, var offset: Long, var carry: String, val until: Long)
    private val draining = ArrayList<Drain>()
    private var first = true

    /** the newest combat log file. m95: in a fight the add-on stops and starts the log every 3 s, each start a NEW file: the old one is
     *  drained (its last lines are written by the stop), the new one read from its START; only the very first pick starts at the end */
    private fun pick(now: Long) {
        if (file != null && now - pickedAt < PICK_MS) return
        pickedAt = now
        val newest = logsDir.listFiles { f -> f.name.startsWith("WoWCombatLog") && f.name.endsWith(".txt") }?.maxByOrNull { stampOf(it.name) }
        if (newest != null && newest != file) {
            file?.let { old -> draining.add(Drain(old, offset, carry, now + DRAIN_MS)) }
            file = newest; offset = if (first) newest.length() else 0L; carry = ""
            if (first || switches % 50 == 0L) Log.i(TAG, "following ${newest.name} from $offset bytes (switch $switches)")
            first = false; switches++
        }
    }
    private var switches = 0L
    /** a file's start from its name (WoWCombatLog-MMDDYY_HHMMSS.txt) as YYMMDDHHMMSS: the stopped file is written AFTER the new one
     *  appears, so its modified time cannot say which is newest; a name that does not read sorts first */
    private fun stampOf(n: String): Long = Regex("""(\d{2})(\d{2})(\d{2})_(\d{6})""").find(n)?.destructured?.let { (mm, dd, yy, t) ->
        "$yy$mm$dd$t".toLongOrNull() } ?: 0L

    /** reads [f] from [from]; returns the new offset and the carried partial line; true in [out] = a unit's health changed */
    private fun readFrom(f: File, from: Long, carryIn: String, now: Long, out: BooleanArray): Pair<Long, String> {
        val len = f.length()
        var off = if (len < from) 0L else from
        var car = if (len < from) "" else carryIn
        if (len <= off) return off to car
        val n = minOf(len - off, MAX_READ).toInt()
        val buf = ByteArray(n)
        RandomAccessFile(f, "r").use { it.seek(off); it.readFully(buf) }
        off += n
        val text = car + String(buf, Charsets.UTF_8)
        val end = text.lastIndexOf('\n')
        car = if (end < 0) text else text.substring(end + 1)
        if (end >= 0) {
            var i = 0
            while (i <= end) {
                var j = text.indexOf('\n', i); if (j < 0 || j > end) j = end
                val line = text.substring(i, j).trimEnd('\r')
                if (line.isNotEmpty()) {
                    lines++
                    val t = CombatLog.split(line)?.first
                    if (t != null) synchronized(lags) { if (lags.size < 4000) lags.add(now - t) }
                    if (CombatHealth.store.line(line, now)) out[0] = true
                }
                i = j + 1
            }
        }
        return off to car
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        pick(now)
        val out = booleanArrayOf(false)
        // the stopped files first (their lines are older than the new file's)
        val it = draining.iterator()
        while (it.hasNext()) {
            val d = it.next()
            val (o, c) = readFrom(d.f, d.offset, d.carry, now, out); d.offset = o; d.carry = c
            if (now > d.until) it.remove()
        }
        val f = file ?: return
        val (o, c) = readFrom(f, offset, carry, now, out); offset = o; carry = c
        if (out[0]) pending = true
        // the regen true-up moves with no new line: a redraw every 2 s while the log is followed
        if (now - lastNotify >= TICK_MS) pending = true
        if (pending && now - lastNotify >= NOTIFY_MS) { pending = false; lastNotify = now; onChange() }
        if (now - lagAt >= LAG_EVERY_MS) { lagAt = now; sayLag() }
    }

    private fun sayLag() {
        val l = synchronized(lags) { lags.sorted().also { lags.clear() } }
        if (l.isEmpty()) { if (file != null) Log.i(TAG, "no new lines (${file?.name}); units ${CombatHealth.store.size()}"); return }
        fun p(q: Double) = l[((l.size - 1) * q).toInt()]
        val line = "delay: ${l.size} lines, p50 ${p(0.5)} ms, p90 ${p(0.9)} ms, max ${l.last()} ms; units ${CombatHealth.store.size()}"
        CombatHealth.lastDelay = line
        Log.i(TAG, line)
    }

    companion object {
        const val TAG = "WCCLOG"
        const val POLL_MS = 100L
        const val PICK_MS = 400L
        /** how long a stopped file is still read after the switch */
        const val DRAIN_MS = 3_000L
        const val MAX_READ = 128L * 1024
        const val NOTIFY_MS = 200L
        const val TICK_MS = 2_000L
        const val LAG_EVERY_MS = 30_000L
    }
}
