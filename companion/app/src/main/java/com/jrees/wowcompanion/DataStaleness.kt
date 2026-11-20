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
    /** in a directly installed game folder: its Data fills as the game streams it, so a read that misses says nothing about its age */
    const val STREAMED = ".ds-streamed"
    const val MIN_SAMPLE = 40

    /**
     * A DIRECT INSTALL BEFORE THE GAME'S FIRST RUN (direct3 on the Thor: CascLib error 2 at "loading index files"): the client makes
     * Data/data's .idx files as it starts, and the art cannot open the folder without them. So the setup starts the game once by itself,
     * behind the loading screen and with no sign-in, closes it when they exist, and goes on to the art (the user 2026-11-01). Local and
     * Streaming stay hidden until the art is done.
     */
    fun firstRun(gameRoot: File): Boolean = runState(gameRoot).let { it == Run.NEVER || it == Run.PARTIAL }

    /** every one of the client's 16 index buckets has its file */
    fun indexesMade(gameRoot: File): Boolean = idxBuckets(gameRoot) >= 16

    /** how many of the client's 16 index buckets (Data/data's "NN*.idx") have a file */
    fun idxBuckets(gameRoot: File): Int =
        File(gameRoot, "Data/data").listFiles { f -> f.isFile && f.name.endsWith(".idx") }.orEmpty().map { it.name.take(2) }.toSet().size

    /**
     * WHAT THE GAME FOLDER ITSELF SAYS ABOUT THE GAME'S RUNS (issue #571, 1.0.37: a fresh install sat at "Game art: failed" - "loading
     * index files 0/0", CascLib error 2 - because [firstRun] needed the [STREAMED] marker that only Install directly writes; a folder
     * from Choose a folder, a manual copy, an upstream install or an interrupted install never got its first run). Read from the folder,
     * whichever path made it:
     * - [Run.RAN]: all 16 of Data/data's index buckets exist. The client makes them as it starts, and the art cannot open the folder
     *   without them, so they are THE sign the game ran (a copy from a PC brings them too).
     * - [Run.NEVER]: no index file at all and no trace of a run (no WTF folder, no Config.wtf).
     * - [Run.PARTIAL]: some index buckets, or a WTF folder / Config.wtf without the indexes: a first run that stopped part-way. DS
     *   Forever writes Config.wtf itself before every start (GameConfigWtf.ensure), so Config.wtf alone never says the game ran.
     * - [Run.UNREADABLE]: no file access (.build.info or Data unreadable): nothing is said, so a folder that ran is never taken for new.
     * NEVER and PARTIAL are a first run ([firstRun]); the marker is no longer needed for it.
     */
    enum class Run { NEVER, PARTIAL, RAN, UNREADABLE }

    fun runState(gameRoot: File): Run {
        if (!File(gameRoot, ".build.info").canRead()) return Run.UNREADABLE
        val data = File(gameRoot, "Data")
        if (data.exists() && data.listFiles() == null) return Run.UNREADABLE
        val buckets = idxBuckets(gameRoot)
        if (buckets >= 16) return Run.RAN
        return if (buckets == 0 && !runTraces(gameRoot)) Run.NEVER else Run.PARTIAL
    }

    /** a WTF folder (or its Config.wtf) in the game folder or in one of its flavour folders (_forever_, _classic_beta_) */
    private fun runTraces(gameRoot: File): Boolean =
        (listOf(gameRoot) + gameRoot.listFiles { f -> f.isDirectory && f.name.length > 2 && f.name.startsWith("_") && f.name.endsWith("_") }.orEmpty())
            .any { File(it, "WTF").exists() }

    /** the report's / log's line: the run state and the folder's traces ("never run: index buckets 0/16, Data/indices 3 files, ...") */
    fun traces(gameRoot: File): String {
        val flavours = gameRoot.listFiles { f -> f.isDirectory && f.name.startsWith("_") && f.name.endsWith("_") }.orEmpty()
        val wtf = flavours.any { File(it, "WTF").isDirectory } || File(gameRoot, "WTF").isDirectory
        val cfg = flavours.any { File(it, "WTF/Config.wtf").isFile }
        val indices = File(gameRoot, "Data/indices").listFiles()?.size ?: 0
        return "${runState(gameRoot).name.lowercase()}: index buckets ${idxBuckets(gameRoot)}/16, Data/indices $indices files, " +
            "WTF ${if (wtf) "yes" else "no"}, Config.wtf ${if (cfg) "yes" else "no"}, marker ${if (File(gameRoot, STREAMED).exists()) "yes" else "no"}"
    }

    /**
     * A NEVER-RUN FOLDER WITH NO LOCAL INDEXES AT ALL IS A STREAMED ONE (issue #571): its Data fills as the game streams it, exactly as
     * a direct install's. The [STREAMED] marker is written for it before its first run, so the art fetches the build's tables
     * (GameFiles.fetchVfs) and its CDN reads are not taken for an out-of-date folder. True = the marker was written now.
     */
    fun adoptStreamed(gameRoot: File): Boolean {
        val m = File(gameRoot, STREAMED)
        if (m.exists() || idxBuckets(gameRoot) > 0 || runState(gameRoot) == Run.UNREADABLE) return false
        return runCatching { WowSwitch.replace(m, ByteArray(0)); true }.getOrDefault(false)
    }

    /** the art's words while the game folder waits for its first run (never a failure that holds Play) */
    const val FIRST_RUN_WHY = "the game has not run in this folder yet: it runs once first, then the art is built"
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
