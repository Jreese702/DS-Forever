package com.jrees.wowcompanion

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * A GAME FILE THAT IS ONLY IN BLIZZARD'S CDN ARCHIVES (1.0.2, 2026-10-13, the Reddit report "atlas table (897470) did not read":
 * a game folder copied before the game updated itself on the device; the game streams the changed files and never stores them).
 * CascLib names such a file as a loose CDN file (data/xx/yy/<encoding key>), but the CDN only serves it inside one of the build's
 * archives: 404 / 403 as a loose file. This finds the archive and the offset in the archive indices (.index: the game's own
 * Data/indices first, else downloaded once into the app's cache) and downloads just that byte range, which is the whole
 * BLTE-encoded file CascLib then reads as the loose file it asked for. Pure JVM (java.io / java.net) so the PC test runs it as is.
 */
object CdnArchives {
    class Hit(val archive: String, val size: Int, val offset: Long)

    /** above this many missing archive indexes, one archive-group index is downloaded instead */
    private const val MAX_INDEX_DOWNLOADS = 64

    private class Index(val block: Int, val offsetBytes: Int, val sizeBytes: Int, val keyBytes: Int, val lastKeys: List<ByteArray>)

    /** parsed index TOCs by file path; an index file never changes (its name is its hash) */
    private val indexes = HashMap<String, Index?>()
    private val lock = Any()

    /** the self-heal dropped the cache (GameFiles, p-casclib-1000): the indexes read from it go too */
    fun forget() = synchronized(lock) { indexes.clear() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun cdnRel(kind: String, key: String, ext: String = "") = "$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key$ext"

    /**
     * The archive and range that hold [ekey] in the build named by [cdnKey] (.build.info's "CDN Key"), or null.
     * [download] fetches one loose CDN file (its path under the CDN path, e.g. "config/ab/cd/<key>") to a local file.
     * The game's own indexes are searched first (no downloads); then the build's indexes the device lacks, downloaded one by one.
     */
    fun locate(root: File, cache: File, cdnKey: String, ekey: ByteArray, download: (String, File) -> Boolean,
               log: (String) -> Unit = {}): Hit? {
        if (!Regex("[0-9a-f]{32}").matches(cdnKey)) return null
        val config = File(root, "Data/" + cdnRel("config", cdnKey)).takeIf { it.isFile }
            ?: File(cache, cdnRel("config", cdnKey)).takeIf { it.isFile || run { it.parentFile?.mkdirs(); download(cdnRel("config", cdnKey), it) } }
            ?: return null.also { log("cdn archives: the build's CDN config $cdnKey is not available") }
        val archives = config.readLines().firstOrNull { it.startsWith("archives =") }
            ?.substringAfter('=')?.trim()?.split(' ')?.filter { Regex("[0-9a-f]{32}").matches(it) }.orEmpty()
        if (archives.isEmpty()) return null.also { log("cdn archives: no archives in the CDN config") }
        val local = archives.filter { File(root, "Data/indices/$it.index").isFile }
        for (a in local) find(File(root, "Data/indices/$a.index"), ekey)?.let { return Hit(a, it.first, it.second) }
        val missing = archives - local.toSet()
        // many indexes missing (a folder without them): the build's one combined index (archive-group) instead of hundreds
        if (missing.count { !File(cache, "indices/$it.index").isFile } > MAX_INDEX_DOWNLOADS) {
            val group = config.readLines().firstOrNull { it.startsWith("archive-group =") }?.substringAfter('=')?.trim()
                ?.takeIf { Regex("[0-9a-f]{32}").matches(it) } ?: return null.also { log("cdn archives: no archive-group in the CDN config") }
            val f = File(root, "Data/indices/$group.index").takeIf { it.isFile } ?: File(cache, "indices/$group.index")
            if (!f.isFile) { f.parentFile?.mkdirs(); if (!download(cdnRel("data", group, ".index"), f)) return null.also { log("cdn archives: the archive-group index did not download") } }
            val (size, packed) = find(f, ekey) ?: return null.also { log("cdn archives: ${hex(ekey)} is not in the archive-group index") }
            val which = (packed ushr 32).toInt()                   // a group entry's offset: archive number (2 bytes) + offset (4 bytes)
            return archives.getOrNull(which)?.let { Hit(it, size, packed and 0xffffffffL) }
        }
        var fetched = 0
        for (a in missing) {
            val f = File(cache, "indices/$a.index")
            if (!f.isFile) {
                f.parentFile?.mkdirs()
                if (!download(cdnRel("data", a, ".index"), f)) continue
                fetched++
            }
            find(f, ekey)?.let { log("cdn archives: found in $a after $fetched index downloads"); return Hit(a, it.first, it.second) }
        }
        log("cdn archives: ${hex(ekey)} is in none of ${archives.size} archives ($fetched indexes downloaded)")
        return null
    }

    /** (encoded size, offset) of [ekey] in one archive index, or null; reads the footer and TOC once, then one block per lookup */
    private fun find(file: File, ekey: ByteArray): Pair<Int, Long>? {
        val ix = synchronized(lock) { indexes.getOrPut(file.path) { runCatching { toc(file) }.getOrNull() } } ?: return null
        val key = ekey.copyOf(ix.keyBytes)
        var lo = 0; var hi = ix.lastKeys.size                       // the first block whose last key >= key
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (compare(ix.lastKeys[mid], key) < 0) lo = mid + 1 else hi = mid }
        if (lo == ix.lastKeys.size) return null
        val block = ByteArray(ix.block)
        RandomAccessFile(file, "r").use { it.seek(lo.toLong() * ix.block); it.readFully(block) }
        val step = ix.keyBytes + ix.sizeBytes + ix.offsetBytes
        var p = 0
        while (p + step <= block.size) {
            if (block.copyOfRange(p, p + ix.keyBytes).contentEquals(key)) {
                val size = be(block, p + ix.keyBytes, ix.sizeBytes)
                val offset = be(block, p + ix.keyBytes + ix.sizeBytes, ix.offsetBytes)
                if (size <= 0 || size > Int.MAX_VALUE) return null
                return size.toInt() to offset
            }
            p += step
        }
        return null
    }

    /**
     * An archive index's layout: blocks of entries (key, size, offset; big-endian), then the TOC (each block's last key, then each
     * block's hash), then the footer: hash, version, 2 unknown bytes, block size in KiB, offset bytes, size bytes, key bytes,
     * checksum bytes, element count (little-endian), footer hash.
     */
    private fun toc(file: File): Index {
        RandomAccessFile(file, "r").use { f ->
            val len = f.length()
            val probe = ByteArray(1); f.seek(len - 13); f.readFully(probe)             // the checksum size, for 8-byte checksums
            val chk = probe[0].toInt() and 0xff
            require(chk in 1..16) { "${file.name}: not an archive index" }
            val footer = ByteArray(chk + 12 + chk); f.seek(len - footer.size); f.readFully(footer)
            val o = chk + 3
            val blockKb = footer[o].toInt() and 0xff
            val offsetBytes = footer[o + 1].toInt() and 0xff
            val sizeBytes = footer[o + 2].toInt() and 0xff
            val keyBytes = footer[o + 3].toInt() and 0xff
            require((footer[o + 4].toInt() and 0xff) == chk && blockKb > 0 && keyBytes in 1..16 && offsetBytes in 1..8 && sizeBytes in 1..8) {
                "${file.name}: unexpected index footer"
            }
            val block = blockKb * 1024
            val blocks = ((len - footer.size) / (block + keyBytes + chk)).toInt()
            val toc = ByteArray(blocks * keyBytes); f.seek(blocks.toLong() * block); f.readFully(toc)
            return Index(block, offsetBytes, sizeBytes, keyBytes, List(blocks) { toc.copyOfRange(it * keyBytes, (it + 1) * keyBytes) })
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
        return 0
    }

    private fun be(b: ByteArray, at: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xff); return v }

    /** downloads [hit]'s byte range from the first of [hosts] that serves it, to [to] whole or not at all; checks the BLTE header */
    fun fetch(hosts: List<String>, cdnPath: String, hit: Hit, to: File, log: (String) -> Unit = {}): Boolean {
        val rel = cdnRel("data", hit.archive)
        // every host, twice (CdnRetry: a player's "worked after a few retries"); the last reason is kept for the art's message
        val why = CdnRetry.run(hosts) { host ->
            val c = URL("https://$host/${cdnPath.trim('/')}/$rel").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("Range", "bytes=${hit.offset}-${hit.offset + hit.size - 1}")
            try {
                if (c.responseCode != 206) return@run "HTTP ${c.responseCode}"
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size != hit.size || !bytes.copyOf(4).contentEquals("BLTE".toByteArray())) return@run "a damaged range (${bytes.size} bytes)"
                to.parentFile?.mkdirs()
                val tmp = File(to.path + ".part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(to)) { to.delete(); if (!tmp.renameTo(to)) { tmp.delete(); return@run "could not save it" } }
                log("cdn archives: fetched ${to.name} (${hit.size} bytes) from archive ${hit.archive} on $host")
                null
            } finally { c.disconnect() }
        }
        if (why != null) { GameFiles.lastFetchWhy = why; log("cdn archives: could not fetch ${to.name} from archive ${hit.archive}: $why") }
        return why == null
    }
}
