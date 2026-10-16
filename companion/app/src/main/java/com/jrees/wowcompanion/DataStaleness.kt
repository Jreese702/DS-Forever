package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/**
 * AN OUT-OF-DATE GAME FOLDER, SAID PLAINLY (issues #8 / #9: the game folder's Data was from an older build than its .build.info, so
 * nearly every file the art read had to come from the CDN, and the game itself crashed at launch; our updater fetches the client and
 * its indexes, never the data archives). The user's pick: setup says "copy the Data folder again from your PC".
 *
 * - [count]: every game-file read of the art (and the indoor maps) is a local HIT or a MISS (it had to be fetched from the CDN).
 * - [verdict]: after [MIN_SAMPLE] reads, STALE when at least half missed. A complete folder misses almost nothing (the Thor's full
 *   install: no downloads at all).
 * - Kept per game build and Data folder FINGERPRINT (its index files' names and sizes): a recopied Data folder changes the
 *   fingerprint and clears the verdict. A later run that looks fine does not clear it: files our own CDN cache already holds read as
 *   hits, so only a new build or a recopy can.
 */
object DataStaleness {
    const val MIN_SAMPLE = 40
    const val STALE_RATIO = 0.5

    /** STALE (true), fine (false), or not enough reads yet (null) */
    fun verdict(hits: Int, misses: Int): Boolean? {
        val n = hits + misses
        if (n < MIN_SAMPLE) return null
        return misses >= n * STALE_RATIO
    }

    /** the Data folder's identity: its local index files (Data/data/[*].idx), names and sizes; "" = none */
    fun fingerprint(gameRoot: File): String {
        val idx = File(gameRoot, "Data/data").listFiles { f -> f.isFile && f.name.endsWith(".idx") }.orEmpty()
            .sortedBy { it.name }.joinToString("\n") { "${it.name}:${it.length()}" }
        if (idx.isEmpty()) return ""
        return MessageDigest.getInstance("MD5").digest(idx.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun message(version: String): String =
        "This game folder's Data is older than the game build it says it is${if (version.isNotBlank()) " ($version)" else ""}, so the " +
            "game may crash at launch and the game art downloads almost everything. Copy the Data folder again from your PC's " +
            "World of Warcraft folder."

    // ------------------------------------------------------------------ on the device

    private var prefs: SharedPreferences? = null
    @Volatile private var root: File? = null
    private val hits = java.util.concurrent.atomic.AtomicInteger()
    private val misses = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var decided = false

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("data_staleness", Context.MODE_PRIVATE)
        root = GameFiles.gameRoot(c)
    }

    /** one game-file read on [gameRoot] for [buildKey]: [local] = read from the device, false = fetched from the CDN */
    fun count(gameRoot: File, buildKey: String, local: Boolean) {
        if (decided) return
        if (local) hits.incrementAndGet() else misses.incrementAndGet()
        val v = verdict(hits.get(), misses.get()) ?: return
        decided = true
        if (v) prefs?.edit()?.putString("build", buildKey)?.putString("fingerprint", fingerprint(gameRoot))
            ?.putString("path", gameRoot.path)?.putInt("hits", hits.get())?.putInt("misses", misses.get())?.apply()
        android.util.Log.i("WCHARVEST", "game folder: ${hits.get()} local / ${misses.get()} from the CDN -> ${if (v) "OUT OF DATE" else "fine"}")
    }

    private var cachedAt = 0L
    private var cached: String? = null

    /**
     * The setup's message, or null: a stale verdict for THIS folder, build and fingerprint (re-checked at most every 30 s; a recopy or
     * a new build clears it).
     */
    fun current(now: Long = System.currentTimeMillis()): String? {
        if (now - cachedAt < 30_000L) return cached
        cachedAt = now
        cached = runCatching {
            val p = prefs ?: return@runCatching null
            val r = root ?: return@runCatching null
            val build = p.getString("build", null) ?: return@runCatching null
            val (_, liveBuild) = GameFiles.activeBuild(r) ?: return@runCatching null
            if (p.getString("path", null) != r.path || build != liveBuild || p.getString("fingerprint", "") != fingerprint(r)) {
                p.edit().clear().apply()                        // a recopied folder, another folder or a new build: forget it
                return@runCatching null
            }
            message(GameFiles.buildRow(r)?.get("Version").orEmpty())
        }.getOrNull()
        return cached
    }

    /** tests: a fresh run */
    internal fun resetRun() { hits.set(0); misses.set(0); decided = false; cachedAt = 0L; cached = null }
}
