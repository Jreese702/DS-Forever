package com.jrees.wowcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * THE PROBLEM REPORT'S DIAGNOSTIC LOG (board f-problem-report): a rolling log of DS Forever's OWN events, kept in private storage
 * (two files of [FILE_BYTES], so about the last half hour survives a crash) for "Send a problem report" to bundle.
 * - [event]: a step (setup, sign-in step names, art / interiors / CDN, errors). Never a login or a password: callers pass step names.
 * - [doc]: every add-on document, SUMMARIZED: its type and its true / false and number fields only (never a string: no names, no chat).
 *   Normally only when a document's summary changes (at most once per [QUIET_MS] per type); while [recording], every change, one
 *   level deeper (the layout / grow / bar / fight flags inside).
 * - [startRecording]: the player's "Record 5 minutes": the detailed mode for [RECORD_MS], then it stops by itself.
 * Names the documents carry are given to [Redact.know], so the report's logcat lines lose them too.
 */
object DiagLog {
    const val FILE_BYTES = 256L * 1024
    const val QUIET_MS = 10_000L
    const val RECORD_MS = 5 * 60_000L

    private var dir: File? = null
    private val lock = Any()
    private val lastSummary = HashMap<String, String>()
    private val lastAt = HashMap<String, Long>()

    @Volatile var recordUntil = 0L
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    fun init(c: Context) { if (dir == null) useDir(File(c.applicationContext.filesDir, "diag")) }

    /** the log's folder (tests: a temp folder) */
    fun useDir(d: File) { synchronized(lock) { dir = d.also { it.mkdirs() } } }

    fun recording(now: Long = System.currentTimeMillis()): Boolean = now < recordUntil
    fun recordLeftMs(now: Long = System.currentTimeMillis()): Long = (recordUntil - now).coerceAtLeast(0L)

    fun startRecording(now: Long = System.currentTimeMillis()) {
        recordUntil = now + RECORD_MS
        synchronized(lock) { lastSummary.clear(); lastAt.clear() }     // the recording starts with every document's state
        event("report", "recording started (5 minutes)", now)
        changed()
    }

    fun stopRecording(now: Long = System.currentTimeMillis()) {
        if (recordUntil == 0L) return
        recordUntil = 0L
        event("report", "recording stopped", now)
        changed()
    }

    /** one line: time, tag, text (redacted when the report is made, not here: the log never leaves private storage by itself) */
    fun event(tag: String, text: String, now: Long = System.currentTimeMillis()) {
        if (recordUntil != 0L && now >= recordUntil) { recordUntil = 0L; append(now, "report", "recording ended by itself"); changed() }
        append(now, tag, text)
    }

    /** an add-on document: summarized (types and flags only), when it changed; names in it go to [Redact.know] */
    fun doc(msg: JSONObject, now: Long = System.currentTimeMillis()) {
        val type = msg.optString("type").ifEmpty { "?" }
        learnNames(msg, 0)
        val rec = recording(now)
        val s = summarize(msg, if (rec) 2 else 1)
        synchronized(lock) {
            if (lastSummary[type] == s) return
            if (!rec && now - (lastAt[type] ?: 0L) < QUIET_MS) { lastSummary[type] = s; return }
            lastSummary[type] = s; lastAt[type] = now
        }
        event(if (rec) "doc+" else "doc", "$type $s", now)
    }

    /**
     * A document's shape without its words: true / false and number fields (sorted), objects one [depth] deeper, arrays by their
     * length; strings are left out entirely (names, chat, quest text), whatever their key.
     */
    fun summarize(o: JSONObject, depth: Int = 1): String {
        val parts = ArrayList<String>()
        val keys = o.keys().asSequence().filter { it != "type" && !it.startsWith("_") }.sorted().toList()
        for (k in keys) {
            when (val v = o.opt(k)) {
                is Boolean -> parts.add("$k=${if (v) 1 else 0}")
                is Int, is Long -> parts.add("$k=$v")
                is Double -> parts.add("$k=${"%.1f".format(Locale.US, v)}")
                is JSONArray -> parts.add("$k[${v.length()}]")
                is JSONObject -> if (depth > 0) summarize(v, depth - 1).takeIf { it.isNotEmpty() }?.let { parts.add("$k{$it}") }
            }
        }
        return parts.joinToString(" ")
    }

    private val NAME_KEYS = setOf("name", "realm", "guild", "player", "char", "who", "sender", "author", "target", "leader", "friend",
        "member", "toon", "btag", "battletag", "account", "owner", "from", "to")

    private fun learnNames(o: JSONObject, depth: Int) {
        if (depth > 3) return
        for (k in o.keys()) {
            when (val v = o.opt(k)) {
                is String -> if (k.lowercase() in NAME_KEYS) Redact.know(v)
                is JSONObject -> learnNames(v, depth + 1)
                is JSONArray -> for (i in 0 until minOf(v.length(), 50)) (v.opt(i) as? JSONObject)?.let { learnNames(it, depth + 1) }
            }
        }
    }

    // ------------------------------------------------------------------ the two rolling files

    private val stampFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    private fun append(now: Long, tag: String, text: String) = synchronized(lock) {
        val d = dir ?: return@synchronized
        runCatching {
            var cur = File(d, "diag-0.log")
            if (cur.length() >= FILE_BYTES) {
                val old = File(d, "diag-1.log"); old.delete(); cur.renameTo(old); cur = File(d, "diag-0.log")
            }
            cur.appendText("${stampFmt.get()!!.format(Date(now))} $now $tag: ${text.replace('\n', ' ')}\n")
        }
    }

    /** the lines of the last [windowMs] (older file first), raw: the report redacts them */
    fun lines(windowMs: Long, now: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val d = dir ?: return@synchronized emptyList()
        listOf(File(d, "diag-1.log"), File(d, "diag-0.log")).filter { it.isFile }.flatMap { it.readLines() }.filter { l ->
            val ms = l.split(' ').getOrNull(2)?.toLongOrNull() ?: return@filter false
            ms >= now - windowMs
        }.map { l -> l.split(' ', limit = 4).let { p -> if (p.size == 4) "${p[0]} ${p[1]} ${p[3]}" else l } }   // the ms column goes
    }
}
