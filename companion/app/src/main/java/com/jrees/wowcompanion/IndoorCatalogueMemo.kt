package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * THE BUILDING LIST'S MEMORY ACROSS GAME UPDATES (1525; the user's pick, 2026-11-24: "Reuse what didn't change": "Right after an update,
 * check each building's files. Ones that didn't change are carried over straight away, and only changed buildings get rebuilt. Most maps
 * show again within minutes."). Listing the buildings ([NativeIndoorGenerator.catalogue]) reads every map's WDT, every object ADT and every
 * placed root WMO and asks the builder's library about each one (a WDT's tiles, a file's placements, a root's work units, the files a unit
 * reads): minutes on a Thor, after every game update. Each answer is a pure function of ONE file's bytes and a few numbers, and that
 * file's CASC content key is the hash of those bytes, so the answer is kept under the key ([memo]) and computed again only for a file
 * whose content key changed - an unchanged file is not even read. A building's own signature still covers the content key of every file
 * it reads (IndoorBakeStore.Work.signature), so nothing about a building is taken on trust from here: only the listing gets cheaper.
 *
 * Held for one builder ([builder]: its algorithm and its native library's bytes; another one starts empty, so an answer of an older
 * library is never reused). [save] after a complete listing keeps only the answers it used (one game build's worth); after an interrupted
 * one it keeps the older answers too. Written atomically (a kill leaves the old file), at most [MAX_BYTES]; a damaged file is an empty
 * memory - and so is one whose closing line ([END]: the count and the SHA-256 of everything above it) does not match (review of 1525:
 * an answer here becomes a building's spec, and a spec is sealed under its own signature, so a spliced or cut file must never answer). Read from disk only when a listing first asks it, and let go after one ([release]: a few MB of text, not held for a whole bake).
 * Not thread-safe: one listing at a time uses it. Pure (java.io + org.json): the tests drive it.
 */
internal class IndoorCatalogueMemo(private val file: File, val builder: String) {
    private val old = HashMap<String, String>()
    private val used = HashMap<String, String>()
    /** this listing's answers from memory / computed (the log line) */
    var hits = 0; private set
    var misses = 0; private set

    /** the file was read ([load] on the first ask, again after a [release]) */
    private var loaded = false

    private fun ensure() {
        if (loaded) return
        loaded = true
        runCatching { load() }.onFailure { old.clear() }
    }

    /** how many answers it holds */
    val size: Int get() { ensure(); return old.size + used.size }

    /** the answers let go from memory (after a listing has saved them); the next ask reads the file again */
    fun release() { old.clear(); used.clear(); loaded = false }

    /**
     * the answer for [kind] of the file whose content key is [ckey] (hex) with [params]: from memory, else [compute] (kept when it gave
     * one). [ckey] null (the content key is not known): computed and never kept
     */
    fun <T : Any> memo(kind: String, ckey: String?, params: String, encode: (T) -> String, decode: (String) -> T?, compute: () -> T?): T? {
        if (ckey.isNullOrEmpty()) return compute()
        ensure()
        val key = "$kind:$ckey|$params"
        val known = used[key] ?: old.remove(key)?.also { used[key] = it }
        if (known != null) {
            val v = runCatching { decode(known) }.getOrNull()
            if (v != null) { hits++; return v }
            used.remove(key)
        }
        misses++
        val v = compute() ?: return null
        used[key] = encode(v)
        return v
    }

    fun text(kind: String, ckey: String?, params: String, compute: () -> String?): String? = memo(kind, ckey, params, { it }, { it }, compute)

    fun ints(kind: String, ckey: String?, params: String, compute: () -> IntArray?): IntArray? =
        memo(kind, ckey, params, { a -> a.joinToString(",") }, { t -> if (t.isEmpty()) IntArray(0) else t.split(',').map { it.toInt() }.toIntArray() }, compute)

    /**
     * to disk: [complete] = the listing ran to its end (only what it used is kept: older builds' answers go), else everything known.
     * False when it was not written (too big, or the disk said no: the old file stays as it was)
     */
    fun save(complete: Boolean): Boolean {
        if (!loaded) return false                                  // never asked (or let go): nothing new to write, the file stays
        val all = if (complete) used else HashMap(old).apply { putAll(used) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".pending_" + file.name)
        return try {
            var bytes = 0L
            val sum = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tmp).use { out ->
                val w = out.bufferedWriter(Charsets.UTF_8)
                fun put(line: String) { sum.update(line.toByteArray(Charsets.UTF_8)); w.write(line) }
                put(JSONObject().put("schema", SCHEMA).put("builder", builder).toString() + "\n")
                for ((k, v) in all) {
                    val line = escape(k) + "\t" + escape(v) + "\n"
                    bytes += line.length
                    if (bytes > MAX_BYTES) error("the listing's memory is over ${MAX_BYTES shr 20} MB")
                    put(line)
                }
                // the closing line: how many answers and the checksum of everything above it ([load] answers nothing without it)
                w.write(END + all.size + "\t" + hex(sum.digest()) + "\n")
                w.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
    }

    private fun load() {
        if (!file.isFile || file.length() > MAX_BYTES + END_MAX) return
        file.bufferedReader(Charsets.UTF_8).use { r ->
            val first = r.readLine() ?: return
            val head = JSONObject(first)
            if (head.optInt("schema") != SCHEMA || head.optString("builder") != builder || builder.isEmpty()) return
            val sum = MessageDigest.getInstance("SHA-256")
            sum.update((first + "\n").toByteArray(Charsets.UTF_8))
            val read = HashMap<String, String>()
            var count = 0
            while (true) {
                val line = r.readLine() ?: error("no closing line: the file was cut")
                // the closing line: the count and the checksum of every line above it, and nothing after it
                if (line.startsWith(END)) {
                    require(line == END + count + "\t" + hex(sum.digest()) && r.readLine() == null) { "the checksum does not match" }
                    break
                }
                sum.update((line + "\n").toByteArray(Charsets.UTF_8))
                count++
                val tab = line.indexOf('\t')
                if (tab <= 0) error("damaged line")
                read[unescape(line.substring(0, tab))] = unescape(line.substring(tab + 1))
            }
            // every answer, or none
            old.putAll(read)
        }
    }

    companion object {
        /** 2 (review of 1525): the closing checksum line ([END]); a schema 1 file (never shipped) is an empty memory */
        const val SCHEMA = 2
        /** the closing line's start ("#end", TAB, the count, TAB, the SHA-256 in hex); an answer's key starts with its kind, never '#' */
        internal const val END = "#end\t"
        private const val END_MAX = 128L

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
        /** the file's name beside the scheduler's catalogue (interiors_sched/; not "catalogue-*": a new listing deletes the old ones) */
        const val FILE = "listing-memory.txt"
        /** a whole game build's answers are a few MB; past this it is not kept (the listing still works, only slower) */
        const val MAX_BYTES = 64L shl 20

        internal fun escape(s: String): String {
            if (s.none { it == '\\' || it == '\n' || it == '\r' || it == '\t' }) return s
            val b = StringBuilder(s.length + 8)
            for (c in s) when (c) {
                '\\' -> b.append("\\\\"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
                else -> b.append(c)
            }
            return b.toString()
        }

        internal fun unescape(s: String): String {
            if ('\\' !in s) return s
            val b = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\') { b.append(c); i++; continue }
                require(i + 1 < s.length) { "damaged escape" }
                b.append(when (s[i + 1]) { '\\' -> '\\'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> error("damaged escape") })
                i += 2
            }
            return b.toString()
        }

        /**
         * the memory for [builder] (the scheduler's builder key, IndoorScheduler.builderKey: its library's bytes) and [algorithm], or
         * null when the library's bytes could not be read ("lib:unread": an older library's answers could be taken for this one's)
         */
        fun forBuilder(dir: File, builder: String, algorithm: Int): IndoorCatalogueMemo? =
            if (builder.isEmpty() || builder.endsWith("lib:unread")) null else IndoorCatalogueMemo(File(dir, FILE), "$builder|a$algorithm")
    }
}
