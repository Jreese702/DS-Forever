package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import java.io.Closeable
import java.io.File

/**
 * THE USER'S OWN GAME FILES, READ-ONLY (the art harvest, 2026-10-09): the launcher's game folder (its "wow_forever" / "game_path" setting,
 * default /storage/emulated/0/WoW Forever, the folder with .build.info and Data) opened through CascLib ([CascNative]). The harvest's steps
 * read by fileDataID or by game path, decode BLP textures ([Blp]) and DB2 tables ([Db2]). Nothing is ever written to the game's folder.
 */
class GameFiles private constructor(private val handle: Long, val root: File, val product: String, val buildKey: String,
                                    private val cache: File) : Closeable {

    /** the whole file by fileDataID, or null ([status] says why: CascNative.UNKNOWN / NOT_PRESENT / ENCRYPTED / OTHER) */
    fun read(fid: Int): ByteArray? = readAsset("file $fid") { CascNative.read(handle, fid) }
    /** the whole file by game path, or null */
    fun readName(path: String): ByteArray? = readAsset(path) { CascNative.readName(handle, path) }
    @Volatile var status = CascNative.OK
        private set
    @Volatile var lastReadWhy: String? = null
        private set

    private fun readAsset(label: String, load: () -> ByteArray?): ByteArray? = synchronized(accessLock) {
        lastReadWhy = null
        for (attempt in 0..4) {
            CascNative.takeNeed()
            val bytes = load()
            status = CascNative.lastStatus()
            // issues #8 / #9: is this game folder's Data the build's? (the first try: read here = local, a fetch needed = from the CDN)
            if (attempt == 0 && bytes != null) { DataStaleness.count(root, buildKey, local = true); missWindow.note(false) }
            if (bytes != null) return@synchronized bytes
            val need = CascNative.takeNeed() ?: break
            if (attempt == 0) { DataStaleness.count(root, buildKey, local = false); missWindow.note(true) }
            if (attempt == 4 || status == CascNative.ENCRYPTED) break
            if (!fetch(root, cache, File(need))) {
                lastReadWhy = "$label: not on this device, and downloading it failed (${File(need).name}: ${lastFetchWhy ?: "no answer"})"
                return@synchronized null
            }
        }
        val error = CascNative.lastReadError()
        // a damaged cached table (p-casclib-1000): the next open of the game files drops the cache first (once per run)
        if (CascHeal.damaged(error)) { readSawDamage = true; android.util.Log.w("WCHARVEST", "$label: CascLib error $error, the cache goes at the next open") }
        lastReadWhy = "$label: " + when (status) {
            CascNative.UNKNOWN -> "not in this game build's index (does the folder match its .build.info?)"
            CascNative.NOT_PRESENT -> "not on this device (is the game folder fully copied or downloaded?)"
            CascNative.ENCRYPTED -> "encrypted"
            else -> "read failed (status $status)"
        } + " (CascLib error $error)"
        null
    }

    /**
     * FETCH MANY MISSING GAME FILES AT ONCE (issues #10 / #11, 1.0.9: after a game update each missing file was fetched one at a time,
     * ~0.2 s of latency each, thousands of them). Of [fids], the ones that are not on the device are found (a cheap failed read names
     * the file CascLib needs), then fetched [PARALLEL] at a time with the same fetch as a read (archives, retries, checks), so the
     * reads that follow find them in the cache. Only while the recent reads show many CDN misses (a complete folder: nothing, no
     * second read). [onProgress] gets (done, total) of the fetches. The number fetched.
     */
    fun prefetch(fids: Collection<Int>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        if (!missWindow.worthPrefetching()) return 0
        val needs = LinkedHashSet<String>()
        synchronized(accessLock) {
            for (fid in fids.toSet()) {
                CascNative.takeNeed()
                if (CascNative.read(handle, fid) == null) CascNative.takeNeed()?.let { needs.add(it) }
            }
        }
        if (needs.isEmpty()) return 0
        val total = needs.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        val ok = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(PARALLEL, total))
        try {
            for (n in needs) pool.execute {
                if (runCatching { fetch(root, cache, File(n)) }.getOrDefault(false)) ok.incrementAndGet()
                onProgress(done.incrementAndGet(), total)
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
        }
        android.util.Log.i("WCHARVEST", "prefetch: ${ok.get()} of $total missing game files fetched ($PARALLEL at a time)")
        return ok.get()
    }

    /** Track B (interiors): the file's CASC content key without reading it, or null; digests a building's inputs. */
    fun contentKey(fid: Int): ByteArray? = synchronized(accessLock) { IndoorGeneratorCasc.contentKey(handle, fid) }
    /** a BLP texture (top mip) as a Bitmap, or null */
    fun blp(fid: Int): Bitmap? = read(fid)?.let { toBitmap(Blp.decode(it)) }
    fun blpName(path: String): Bitmap? = readName(path)?.let { toBitmap(Blp.decode(it)) }
    /** a DB2 table, or null (a layout the reader does not know throws inside and comes back null) */
    fun db2(fid: Int): Db2? = readAsset("table $fid") { CascNative.readDb2(handle, fid) }?.let {
        runCatching { Db2(it, "fid $fid") }.onFailure { error ->
            lastReadWhy = "table $fid: ${error.message ?: error.javaClass.simpleName}"
            android.util.Log.w("WCHARVEST", lastReadWhy, error)
        }.getOrNull()
    }

    /** Keys stay in this storage handle; no key material is persisted, logged, or bundled. */
    fun loadClientKeys(): Int = synchronized(accessLock) {
        val keys = (db2(1302850) ?: error(lastReadWhy ?: "TactKey table unreadable"))
            .byteRecords(0xCBA490FC.toInt(), 16)
        val names = (db2(1302851) ?: error(lastReadWhy ?: "TactKeyLookup table unreadable"))
            .byteRecords(0x4983962C, 8)
        check(keys.isNotEmpty() && names.isNotEmpty()) { "the game's key tables have no readable records" }
        var count = 0
        for ((id, key) in keys) {
            val bytes = names[id] ?: error("game key $id has no lookup record")
            val name = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            check(CascNative.addEncryptionKey(handle, name, key)) { "game key $id could not be registered" }
            count++
        }
        count
    }

    override fun close() { synchronized(accessLock) { CascNative.close(handle) } }

    companion object {
        // CascLib's cache destination and missing-file request are process globals, not handle-local.
        private val accessLock = Any()
        /** the launcher's game-folder choice (its GamePath): every game path comes from it ([GamePaths]) */
        const val PREFS = "wow_forever"
        const val KEY_GAME_PATH = "game_path"

        /** the launcher's game folder (the same setting its Locate / Change Location writes) */
        fun gameRoot(ctx: Context): File {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GAME_PATH, null)
            return if (p.isNullOrEmpty()) File(Environment.getExternalStorageDirectory(), "WoW Forever") else File(p)
        }

        /**
         * The .build.info row the game runs, as column -> value, or null: the launcher's own order ([WowProduct.choose], which
         * WowClientDownloader uses too). A folder copied from a PC can hold retail too (one shared Data, retail's row first, both Active,
         * Reddit report 2026-10-13): the first active row would read retail's tables, not the game's. The beta (wow_classic_beta) and
         * the launch client (wowf) are both the game; which one a folder runs is [WowProduct]'s choice.
         */
        fun buildRow(root: File): Map<String, String>? = runCatching { WowProduct.choose(root)?.row }.getOrNull()

        /** the game's build row of .build.info ([buildRow]): (product, build key), or null */
        fun activeBuild(root: File): Pair<String, String>? {
            val row = buildRow(root) ?: return null
            val product = row["Product"]?.takeIf { it.isNotEmpty() } ?: return null
            return product to (row["Build Key"] ?: "")
        }

        /** opens the user's game files, or null with the reason in [lastWhy] */
        fun open(ctx: Context): GameFiles? {
            return synchronized(accessLock) { openLocked(ctx) }
        }

        /** this run dropped the download cache once already (p-casclib-1000: never a loop) */
        @Volatile private var healed = false
        /** a read saw a damaged file (CascLib 1000 / 1002 / 1004): the next open drops the cache first */
        @Volatile internal var readSawDamage = false
        private var lastOpenErr = 0

        /**
         * THE SELF-HEAL (p-casclib-1000): an open that fails on a damaged file, or one after a read that saw one, drops the app's
         * download cache (cacheDir/casc and the archive indexes held in memory) ONCE per run and opens again; still damaged, the
         * message says plainly what to do, with CascLib's reason.
         */
        private fun openLocked(ctx: Context): GameFiles? {
            if (CascHeal.shouldHeal(0, healed, readSawDamage)) heal(ctx, "a read saw a damaged file")
            openOnce(ctx)?.let { return it }
            if (CascHeal.shouldHeal(lastOpenErr, healed)) {
                heal(ctx, "the open failed with CascLib error $lastOpenErr")
                openOnce(ctx)?.let { return it }
            }
            if (CascHeal.damaged(lastOpenErr)) lastWhy = CascHeal.damagedMessage(lastOpenErr)
            return null
        }

        private fun heal(ctx: Context, why: String) {
            healed = true; readSawDamage = false
            val cache = File(ctx.cacheDir, "casc")
            val bytes = runCatching { cache.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
            android.util.Log.w("WCHARVEST", "self-heal: $why; dropping the download cache (${bytes / 1024} KB) and opening again")
            cache.deleteRecursively()
            CdnArchives.forget()
        }

        /** the art's line while an open downloads the game's tables (ArtHarvest sets it around its open; b-art-progress) */
        @Volatile var onTables: ((String) -> Unit)? = null
        @Volatile private var tables: TablesProgress.Counter? = null

        /** mobile data (a metered network) now: the progress line says Wi-Fi is better */
        private fun metered(ctx: Context): Boolean = runCatching {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
        }.getOrDefault(false)

        private fun openOnce(ctx: Context): GameFiles? {
            tables = onTables?.let { emit -> TablesProgress.Counter(metered(ctx), emit) }
            lastOpenErr = 0
            if (!CascNative.available) { lastWhy = "this build has no file reader"; return null }
            val root = gameRoot(ctx)
            val (product, key) = activeBuild(root) ?: run { lastWhy = "no game files found in ${root.path}"; return null }
            // the build's ENCODING / ROOT tables are not kept on the device after the launcher's update (the client fetches them):
            // CascLib downloads them once per build into the app's own cache (h1, 2026-10-09: error 2 without this)
            val cache = File(ctx.cacheDir, "casc").apply { mkdirs() }
            CascNative.setDownloadRoot(cache.path)
            // h2's stage probe (2026-10-09): after the launcher's update the build's DOWNLOAD / INSTALL / VFS tables are only on the CDN,
            // and CascLib's own sockets cannot fetch them on Android. So: CascLib names the file it needs, the phone's HTTPS fetches it
            // into the cache, and the open runs again (a handful of files, once per game build)
            var h = 0L
            var downloadFailure: String? = null
            for (attempt in 0 until MAX_FETCHES) {
                CascNative.takeNeed()
                h = CascNative.open(root.path, product, localeMask(root))
                if (h != 0L) break
                val need = CascNative.takeNeed() ?: break
                if (!fetch(root, cache, File(need))) {
                    downloadFailure = "a game table could not be downloaded (${File(need).name}: ${lastFetchWhy ?: "no answer"})"
                    break
                }
            }
            if (h == 0L) {
                val err = CascNative.lastOpenError()
                lastOpenErr = if (downloadFailure == null) err else 0
                lastWhy = downloadFailure ?: "the game files could not be opened (CascLib error $err${if (err == 2) ": a file it needs is missing" else ""})"
                android.util.Log.w("WCHARVEST", "open ${root.path} $product: error $err")
                return null
            }
            lastWhy = null
            return GameFiles(h, root, product, key, cache)
        }
        private const val MAX_FETCHES = 40          // the build's DOWNLOAD / INSTALL tables + the root's VFS parts: a handful, once per game build

        /**
         * One file CascLib needs, fetched over HTTPS from the CDN hosts the game's own .build.info names ("CDN Hosts" / "CDN Path"):
         * [to] is CascLib's cache path (<cache>/data/xx/yy/<key> or config/…); the same relative path on the CDN. Written whole or not at all.
         */
        private fun fetch(root: File, cache: File, to: File): Boolean {
            val rel = to.path.removePrefix(cache.path).trimStart('/')
            if (rel == to.path || rel.isEmpty()) return false
            val cols = buildRow(root) ?: return false
            val hosts = cols["CDN Hosts"].orEmpty().split(' ').filter { it.isNotBlank() }
            val path = cols["CDN Path"].orEmpty().trim('/')
            // a game file (data/xx/yy/<encoding key>) the CDN may keep only inside an archive: find it and fetch its byte range. Once one
            // loose game file has answered 404, the rest go to the archives first (issue #7: the loose try cost about 6 s per file)
            val ekey = Regex("""data/[0-9a-f]{2}/[0-9a-f]{2}/([0-9a-f]{32})""").matchEntire(rel)?.groupValues?.get(1)
                ?: return download(hosts, path, rel, to)
            val archive = {
                val log = { m: String -> android.util.Log.i("WCHARVEST", m); Unit }
                CdnArchives.locate(root, cache, cols["CDN Key"].orEmpty(),
                    ekey.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), { r, f -> download(hosts, path, r, f) }, log)
                    ?.let { CdnArchives.fetch(hosts, path, it, to, log) } ?: false
            }
            val archiveFirst = looseMissing
            if (archiveFirst && archive()) return true
            if (download(hosts, path, rel, to, missingIsFinal = true)) return true
            if (lastFetchWhy?.startsWith("HTTP 404") == true) looseMissing = true
            return !archiveFirst && archive()
        }

        /** the art's fetches at once (prefetch): the CDN answers each in ~0.2 s, so a handful in flight hides most of it */
        const val PARALLEL = 6

        /** the last reads' misses (prefetch only pays when many files are missing: a complete folder never pays twice) */
        internal val missWindow = MissWindow()

        /** a loose game file has answered 404 (the CDN keeps them inside archives): the next ones try the archives first */
        @Volatile private var looseMissing = false

        /** why the last CDN fetch failed (HTTP 503 from <host>, timed out...): the art's message keeps the real reason */
        @Volatile var lastFetchWhy: String? = null
            internal set

        /** one loose CDN file ([rel] under the CDN path) to [to], whole or not at all; every host, twice (CdnRetry) */
        private fun download(hosts: List<String>, path: String, rel: String, to: File, missingIsFinal: Boolean = false): Boolean {
            to.parentFile?.mkdirs()
            val tmp = File(to.path + ".part")
            val why = CdnRetry.run(hosts, final = { missingIsFinal && it == "HTTP 404" }) { host ->
                val c = java.net.URL("https://$host/$path/$rel").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 60_000
                try {
                    if (c.responseCode != 200) return@run "HTTP ${c.responseCode}"
                    // the big tables (ENCODING, ROOT) say how far they are (b-art-progress)
                    val counter = tables?.takeIf { c.contentLengthLong >= TablesProgress.MIN_BYTES }
                    counter?.start(c.contentLengthLong)
                    c.inputStream.use { i -> tmp.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16); var n = 0L
                        while (true) { val k = i.read(buf); if (k < 0) break; o.write(buf, 0, k); n += k; counter?.progress(n) }
                    } }
                    // whole and the right kind before it is used: a short or damaged answer never lands in the cache (p-casclib-1000)
                    CascHeal.problem(rel, tmp, c.contentLengthLong)?.let { return@run "a damaged download ($it)" }
                    if (!tmp.renameTo(to)) { to.delete(); if (!tmp.renameTo(to)) return@run "could not save it" }
                    android.util.Log.i("WCHARVEST", "fetched $rel (${to.length()} bytes) from $host")
                    counter?.finish()
                    null
                } finally { c.disconnect(); tmp.delete() }
            }
            if (why != null) { lastFetchWhy = why; android.util.Log.w("WCHARVEST", "could not fetch $rel: $why") }
            return why == null
        }

        /** CASC's locale flags (CascLib's CASC_LOCALE_*) */
        private val LOCALES = mapOf("enUS" to 0x2, "koKR" to 0x4, "frFR" to 0x10, "deDE" to 0x20, "zhCN" to 0x40, "esES" to 0x80,
            "zhTW" to 0x100, "enGB" to 0x200, "esMX" to 0x1000, "ruRU" to 0x2000, "ptBR" to 0x4000, "itIT" to 0x8000, "ptPT" to 0x10000)

        /**
         * The install's TEXT locale (Map's trap, 2026-10-09: the map art carries baked-in labels, so it must be the player's own language):
         * .build.info's active Tags ("enUS text?"), else Config.wtf's textLocale, else enUS.
         */
        fun localeMask(root: File): Int {
            val tags = buildRow(root)?.get("Tags").orEmpty()
            Regex("""([a-z]{2}[A-Z]{2}) text\?""").find(tags)?.groupValues?.get(1)?.let { LOCALES[it] }?.let { return it }
            val wtf = runCatching { File(WowProduct.flavorDir(root), "WTF/Config.wtf").readText() }.getOrDefault("")
            Regex("""SET textLocale "([a-zA-Z]{4})"""").find(wtf)?.groupValues?.get(1)?.let { LOCALES[it] }?.let { return it }
            return 0x2
        }

        @Volatile var lastWhy: String? = null
            private set

        fun toBitmap(img: Blp.Image?): Bitmap? = img?.let { Bitmap.createBitmap(it.argb, it.w, it.h, Bitmap.Config.ARGB_8888) }
    }
}
