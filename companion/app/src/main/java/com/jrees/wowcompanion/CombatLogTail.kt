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

    /** the newest combat log file, looked for every few seconds (a /combatlog or a login starts a new one) */
    private fun pick(now: Long) {
        if (file != null && now - pickedAt < PICK_MS) return
        pickedAt = now
        val newest = logsDir.listFiles { f -> f.name.startsWith("WoWCombatLog") && f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }
        if (newest != null && newest != file) {
            file = newest; offset = newest.length(); carry = ""
            Log.i(TAG, "following ${newest.name} from ${offset} bytes")
        }
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        pick(now)
        val f = file ?: return
        val len = f.length()
        if (len < offset) { offset = 0; carry = "" }                         // a new file under the same name
        if (len > offset) {
            val n = minOf(len - offset, MAX_READ).toInt()
            val buf = ByteArray(n)
            RandomAccessFile(f, "r").use { it.seek(offset); it.readFully(buf) }
            offset += n
            val text = carry + String(buf, Charsets.UTF_8)
            val end = text.lastIndexOf('\n')
            carry = if (end < 0) text else text.substring(end + 1)
            var changed = false
            if (end >= 0) {
                var i = 0
                while (i <= end) {
                    var j = text.indexOf('\n', i); if (j < 0 || j > end) j = end
                    val line = text.substring(i, j).trimEnd('\r')
                    if (line.isNotEmpty()) {
                        lines++
                        val t = CombatLog.split(line)?.first
                        if (t != null) synchronized(lags) { if (lags.size < 4000) lags.add(now - t) }
                        if (CombatHealth.store.line(line, now)) changed = true
                    }
                    i = j + 1
                }
            }
            if (changed) { pending = true }
        }
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
        const val PICK_MS = 3_000L
        const val MAX_READ = 128L * 1024
        const val NOTIFY_MS = 200L
        const val TICK_MS = 2_000L
        const val LAG_EVERY_MS = 30_000L
    }
}

/** the combat log's health, for the party module (and anything else that wants it): GUID -> percent while the reading is fresh */
object CombatHealth {
    val store = CombatLog.Health()
    @Volatile var lastDelay: String = ""
    /** a reading older than this (no line about the unit) gives way to the add-on's own value */
    const val FRESH_MS = 60_000L

    /** [guid]'s health in percent, null = the log has nothing fresh on it */
    fun pct(guid: String?, now: Long = System.currentTimeMillis()): Int? {
        if (guid.isNullOrEmpty()) return null
        val e = store.get(guid) ?: return null
        if (e.max <= 0) return null
        // a learned regen keeps the value true out of a fight (CombatLog.Hp.hpAt); without one a quiet reading gives way after FRESH_MS
        if (now - e.at > FRESH_MS && e.rate <= 0.0) return null
        val hp = e.hpAt(now)
        return ((hp * 100 + e.max / 2) / e.max).toInt().coerceIn(0, 100)
    }
}
