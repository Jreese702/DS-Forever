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
            if (bytes != null) return@synchronized bytes
            val need = CascNative.takeNeed() ?: break
            if (attempt == 4 || status == CascNative.ENCRYPTED) break
            if (!fetch(root, cache, File(need))) {
                lastReadWhy = "$label: download failed (${File(need).name})"
                return@synchronized null
            }
        }
        val error = CascNative.lastReadError()
        lastReadWhy = "$label: " + when (status) {
            CascNative.UNKNOWN -> "not indexed in this game build"
            CascNative.NOT_PRESENT -> "not available locally or in the download cache"
            CascNative.ENCRYPTED -> "encrypted"
            else -> "read failed (status $status)"
        } + " (CascLib error $error)"
        null
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

        /** the active build row of .build.info: (product, build key), or null */
        fun activeBuild(root: File): Pair<String, String>? {
            val f = File(root, ".build.info")
            if (!f.isFile) return null
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return null
            val head = lines[0].split('|').map { it.substringBefore('!') }
            val rows = lines.drop(1).map { it.split('|') }
            val ai = head.indexOf("Active"); val pi = head.indexOf("Product"); val bi = head.indexOf("Build Key")
            val row = rows.firstOrNull { ai >= 0 && it.getOrNull(ai) == "1" } ?: rows.first()
            val product = row.getOrNull(pi)?.takeIf { it.isNotEmpty() } ?: return null
            return product to (row.getOrNull(bi) ?: "")
        }

        /** opens the user's game files, or null with the reason in [lastWhy] */
        fun open(ctx: Context): GameFiles? {
            return synchronized(accessLock) { openLocked(ctx) }
        }

        private fun openLocked(ctx: Context): GameFiles? {
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
                    downloadFailure = "a game table could not be downloaded (${File(need).name})"
                    break
                }
            }
            if (h == 0L) {
                val err = CascNative.lastOpenError()
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
            val cols = runCatching {
                val lines = File(root, ".build.info").readLines().filter { it.isNotBlank() }
                val head = lines[0].split('|').map { it.substringBefore('!') }
                val ai = head.indexOf("Active")
                val row = lines.drop(1).map { it.split('|') }.let { rows -> rows.firstOrNull { ai >= 0 && it.getOrNull(ai) == "1" } ?: rows.first() }
                head.indices.associate { head[it] to row.getOrElse(it) { "" } }
            }.getOrNull() ?: return false
            val hosts = cols["CDN Hosts"].orEmpty().split(' ').filter { it.isNotBlank() }
            val path = cols["CDN Path"].orEmpty().trim('/')
            to.parentFile?.mkdirs()
            for (host in hosts) {
                val ok = runCatching {
                    val c = java.net.URL("https://$host/$path/$rel").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 60_000
                    try {
                        if (c.responseCode != 200) return@runCatching false
                        val tmp = File(to.path + ".part")
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                        if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to) }
                        true
                    } finally { c.disconnect() }
                }.getOrDefault(false)
                if (ok) { android.util.Log.i("WCHARVEST", "fetched $rel (${to.length()} bytes) from $host"); return true }
            }
            android.util.Log.w("WCHARVEST", "could not fetch $rel from ${hosts.joinToString()}")
            return false
        }

        /** CASC's locale flags (CascLib's CASC_LOCALE_*) */
        private val LOCALES = mapOf("enUS" to 0x2, "koKR" to 0x4, "frFR" to 0x10, "deDE" to 0x20, "zhCN" to 0x40, "esES" to 0x80,
            "zhTW" to 0x100, "enGB" to 0x200, "esMX" to 0x1000, "ruRU" to 0x2000, "ptBR" to 0x4000, "itIT" to 0x8000, "ptPT" to 0x10000)

        /**
         * The install's TEXT locale (Map's trap, 2026-10-09: the map art carries baked-in labels, so it must be the player's own language):
         * .build.info's active Tags ("enUS text?"), else Config.wtf's textLocale, else enUS.
         */
        fun localeMask(root: File): Int {
            val tags = runCatching {
                val lines = File(root, ".build.info").readLines().filter { it.isNotBlank() }
                val head = lines[0].split('|').map { it.substringBefore('!') }
                val rows = lines.drop(1).map { it.split('|') }
                val ai = head.indexOf("Active"); val ti = head.indexOf("Tags")
                (rows.firstOrNull { ai >= 0 && it.getOrNull(ai) == "1" } ?: rows.first()).getOrNull(ti).orEmpty()
            }.getOrDefault("")
            Regex("""([a-z]{2}[A-Z]{2}) text\?""").find(tags)?.groupValues?.get(1)?.let { LOCALES[it] }?.let { return it }
            val wtf = runCatching { File(root, "_classic_beta_/WTF/Config.wtf").readText() }.getOrDefault("")
            Regex("""SET textLocale "([a-zA-Z]{4})"""").find(wtf)?.groupValues?.get(1)?.let { LOCALES[it] }?.let { return it }
            return 0x2
        }

        @Volatile var lastWhy: String? = null
            private set

        fun toBitmap(img: Blp.Image?): Bitmap? = img?.let { Bitmap.createBitmap(it.argb, it.w, it.h, Bitmap.Config.ARGB_8888) }
    }
}
