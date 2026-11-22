package com.jrees.wowcompanion

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * THE GAME FOLDER'S OWN STORAGE, CHECKED AND MENDED IN PLACE (#577, 1.0.37, a Spanish report: an unclean stop while the game streamed its
 * data left Data/data damaged; every open then failed at "loading manifest ENCODING" with CascLib error 1000, the self-heal (the app's own
 * cache dropped) could not help, and the only advice was to copy the Data folder again - for a folder DS Forever had installed itself).
 *
 * Data/data holds the client's local storage: 16 index buckets ("BBVVVVVVVV.idx", bucket BB, version V; the newest of each bucket is the
 * one in use) naming, for each file's encoding key (its first 9 bytes), where it sits in a data.NNN segment and how long it is. Each file
 * there is a 30-byte header (CascLib: since 2023 it may hold zeros or garbage, so it is never judged) and the file's BLTE body, the same
 * bytes the CDN serves under its encoding key. So:
 * - [scan]: every entry of the buckets in use is read back and its BLTE body checked: its header, every frame against the MD5 its frame
 *   table names, a one-frame zlib body inflated to its end (zlib's own checksum), the segment long enough. Pure file reads.
 * - a damaged body's full encoding key: the MD5 of its BLTE header when that matches the index's 9 bytes ([Damage.fullKey]), else the
 *   build config's tables ([configKeys]) or the CDN archive indexes in Data/indices ([keysFromIndexes]).
 * - [writeBack]: the CDN's body for that key, checked the same way and exactly the entry's length, written over the damaged one at the
 *   same place. Nothing else in the folder changes: no index is rewritten, no segment moved, nothing deleted.
 */
object CascLocal {
    /** the local header before each BLTE body in a data.NNN segment */
    const val LOCAL_HEADER = 30

    /** one entry of an index bucket: [key9] the encoding key's first 9 bytes (hex), in segment [archive] at [offset], [size] with the header */
    data class Entry(val key9: String, val archive: Int, val offset: Long, val size: Long) {
        val bodyLen: Long get() = size - LOCAL_HEADER
    }

    /** a damaged entry: [why] in words; [fullKey] its whole encoding key (hex) when its own BLTE header gives it, else null */
    data class Damage(val entry: Entry, val why: String, val fullKey: String? = null)

    /** what a scan found: [entries] checked, [bytes] read, [damaged], [problems] (an index that could not be read, said in words) */
    data class Scan(val entries: Int, val bytes: Long, val damaged: List<Damage>, val problems: List<String>, val stopped: Boolean = false)

    private val IDX = Regex("""^([0-9a-fA-F]{2})([0-9a-fA-F]{8})\.idx$""")

    /** the index file in use for each bucket of [dataDir] (the newest version), bucket order */
    fun indexesInUse(dataDir: File): List<File> =
        dataDir.listFiles { f -> f.isFile && IDX.matches(f.name) }.orEmpty()
            .groupBy { IDX.find(it.name)!!.groupValues[1].lowercase() }
            .map { (_, fs) -> fs.maxByOrNull { IDX.find(it.name)!!.groupValues[2].toLong(16) }!! }
            .sortedBy { it.name.lowercase() }

    /** an index bucket's entries, or the reason it could not be read (CascLib's version 7 layout: a guarded header, then the entries) */
    fun readIndex(f: File): Pair<List<Entry>, String?> {
        val b = runCatching { f.readBytes() }.getOrElse { return emptyList<Entry>() to "${f.name}: unreadable (${it.message})" }
        if (b.size < 0x28) return emptyList<Entry>() to "${f.name}: too short (${b.size} bytes)"
        val headerSize = le32(b, 0)
        if (headerSize != 0x10) return emptyList<Entry>() to "${f.name}: not a version 7 index"
        val o = 8
        val revision = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        val sizeBytes = b[o + 4].toInt() and 0xff
        val offsetBytes = b[o + 5].toInt() and 0xff
        val keyBytes = b[o + 6].toInt() and 0xff
        val segmentBits = b[o + 7].toInt() and 0xff
        if (revision != 7 || sizeBytes != 4 || offsetBytes != 5 || keyBytes != 9 || segmentBits !in 1..39)
            return emptyList<Entry>() to "${f.name}: an index layout this check does not read (revision $revision)"
        val blockSize = le32(b, 0x20).toLong() and 0xffffffffL
        val entryLen = keyBytes + offsetBytes + sizeBytes
        if (blockSize <= 0 || 0x28 + blockSize > b.size || blockSize % entryLen != 0L)
            return emptyList<Entry>() to "${f.name}: its entries' block is damaged (size $blockSize of ${b.size - 0x28})"
        val out = ArrayList<Entry>((blockSize / entryLen).toInt())
        var p = 0x28
        val end = 0x28 + blockSize.toInt()
        while (p + entryLen <= end) {
            val key = hex(b, p, keyBytes)
            var storage = 0L
            for (i in 0 until offsetBytes) storage = (storage shl 8) or (b[p + keyBytes + i].toLong() and 0xff)
            val size = le32(b, p + keyBytes + offsetBytes).toLong() and 0xffffffffL
            out.add(Entry(key, (storage ushr segmentBits).toInt(), storage and ((1L shl segmentBits) - 1), size))
            p += entryLen
        }
        return out to null
    }

    /**
     * Every entry of [dataDir]'s buckets in use, read back and checked ([bodyProblem]); [threads] segments at a time. [progress] (bytes
     * read, of the total) now and then; [stop] asked between entries (true = the scan ends, what it found so far kept, [Scan.stopped]).
     */
    fun scan(dataDir: File, threads: Int = 4, progress: (Long, Long) -> Unit = { _, _ -> }, stop: () -> Boolean = { false }): Scan {
        val problems = ArrayList<String>()
        val all = ArrayList<Entry>()
        for (idx in indexesInUse(dataDir)) {
            val (es, why) = readIndex(idx)
            if (why != null) problems.add(why) else all.addAll(es)
        }
        // an entry seen in two buckets (never expected) is checked once
        val entries = all.distinctBy { Triple(it.archive, it.offset, it.size) }
        val total = entries.sumOf { it.size.coerceAtLeast(0L) }
        val done = java.util.concurrent.atomic.AtomicLong()
        val damaged = java.util.Collections.synchronizedList(ArrayList<Damage>())
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val byArchive = entries.groupBy { it.archive }.toSortedMap()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads.coerceIn(1, maxOf(1, byArchive.size)))
        val lastTold = java.util.concurrent.atomic.AtomicLong(0L)
        try {
            val jobs = byArchive.map { (archive, es) ->
                pool.submit {
                    val seg = File(dataDir, "data.%03d".format(archive))
                    val ch = runCatching { RandomAccessFile(seg, "r").channel }.getOrNull()
                    try {
                        val reader = Reader(ByteArray(1 shl 18))
                        for (e in es.sortedBy { it.offset }) {
                            if (stopped.get() || stop()) { stopped.set(true); break }
                            val d = check(ch, seg, e, reader)
                            if (d != null) damaged.add(d)
                            val now = done.addAndGet(e.size.coerceAtLeast(0L))
                            if (now - lastTold.get() >= 64L * 1024 * 1024) { lastTold.set(now); runCatching { progress(now, total) } }
                        }
                    } finally { runCatching { ch?.close() } }
                }
            }
            for (j in jobs) j.get()
        } finally { pool.shutdown() }
        progress(done.get(), total)
        return Scan(entries.size, done.get(), damaged.sortedWith(compareBy({ it.entry.archive }, { it.entry.offset })), problems, stopped.get())
    }

    /** one entry's body, checked where it sits; null = whole */
    private fun check(ch: FileChannel?, seg: File, e: Entry, reader: Reader): Damage? {
        if (e.size < LOCAL_HEADER + 8) return Damage(e, "an entry too short for a file (${e.size} bytes)")
        if (ch == null) return Damage(e, "its segment ${seg.name} is missing")
        val segLen = runCatching { ch.size() }.getOrDefault(0L)
        if (e.offset + e.size > segLen) return Damage(e, "its segment ${seg.name} ends early (${segLen} of ${e.offset + e.size} bytes)")
        reader.open(ch, e.offset + LOCAL_HEADER, e.bodyLen)
        val r = runCatching { bodyProblem(reader, e.bodyLen) }.getOrElse { return Damage(e, "unreadable (${it.message})") }
        return r.why?.let { Damage(e, it, r.key?.takeIf { k -> k.startsWith(e.key9) }) }
    }

    /** reads one region of a segment through a reused buffer (one per scan thread) */
    class Reader(private val buf: ByteArray) : Source {
        private var ch: FileChannel? = null
        private var pos = 0L
        private var left = 0L
        private var bufAt = 0
        private var bufEnd = 0
        fun open(c: FileChannel, start: Long, len: Long) { ch = c; pos = start; left = len; bufAt = 0; bufEnd = 0 }
        override fun read(out: ByteArray, off: Int, len: Int): Int {
            if (bufAt >= bufEnd) {
                if (left <= 0) return -1
                val want = minOf(buf.size.toLong(), left).toInt()
                val bb = ByteBuffer.wrap(buf, 0, want)
                var got = 0
                while (bb.hasRemaining()) { val n = ch!!.read(bb, pos + got); if (n < 0) break; got += n }
                if (got <= 0) { left = 0; return -1 }
                pos += got; left -= got; bufAt = 0; bufEnd = got
            }
            val n = minOf(len, bufEnd - bufAt)
            System.arraycopy(buf, bufAt, out, off, n)
            bufAt += n
            return n
        }
    }

    /** a sequential source of a body's bytes (a segment's region, a downloaded file) */
    interface Source { fun read(out: ByteArray, off: Int, len: Int): Int }

    class StreamSource(private val i: java.io.InputStream) : Source {
        override fun read(out: ByteArray, off: Int, len: Int): Int = i.read(out, off, len)
    }

    private fun readFully(s: Source, out: ByteArray, off: Int, len: Int): Boolean {
        var o = 0
        while (o < len) { val n = s.read(out, off + o, len - o); if (n <= 0) return false; o += n }
        return true
    }

    /**
     * What [bodyProblem] found: [why] null = whole, else what is wrong; [key] the encoding key its own bytes give (a frame table: the
     * MD5 of the header, [framed]; one frame: the MD5 of the whole body), null when it did not get that far
     */
    data class Body(val why: String?, val key: String?, val framed: Boolean)

    /**
     * A BLTE body of [len] bytes from [s], by CascLib's rules: "BLTE", the header size, the 0x0F flag and the frame count; the frames'
     * sizes add up to the body; every frame with a frame-table MD5 matches it (an all-zero MD5 is not checked, as CascLib does); a
     * one-frame body's frame type is known and a zlib one inflates to its end (zlib's own checksum).
     */
    fun bodyProblem(s: Source, len: Long): Body {
        if (len < 9) return Body("too short for a BLTE file ($len bytes)", null, false)
        val head = ByteArray(8)
        if (!readFully(s, head, 0, 8)) return Body("ends early", null, false)
        if (!(head[0] == 'B'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'T'.code.toByte() && head[3] == 'E'.code.toByte()))
            return Body("no BLTE header (${if (head.all { it == 0.toByte() }) "zero-filled" else "other bytes"})", null, false)
        val headerSize = be32(head, 4).toLong() and 0xffffffffL
        val buf = ByteArray(1 shl 16)
        if (headerSize == 0L) {
            // one frame: the rest is the frame (its type byte first); the key is the whole body's MD5
            val md = MessageDigest.getInstance("MD5").apply { update(head) }
            val first = ByteArray(1)
            if (!readFully(s, first, 0, 1)) return Body("ends early", null, false)
            md.update(first)
            val type = first[0].toInt().toChar()
            if (type !in "NZ4FE") return Body("an unknown frame type (0x%02x)".format(first[0].toInt() and 0xff), null, false)
            var left = len - 9
            val inf = if (type == 'Z') java.util.zip.Inflater() else null
            val sink = ByteArray(1 shl 16)
            try {
                while (left > 0) {
                    val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) return Body("ends early", null, false)
                    md.update(buf, 0, n); left -= n
                    if (inf != null && !inf.finished()) {
                        inf.setInput(buf, 0, n)
                        while (!inf.needsInput() && !inf.finished()) {
                            val k = runCatching { inf.inflate(sink) }.getOrElse { return Body("its zlib data is damaged", null, false) }
                            if (k == 0 && (inf.needsDictionary() || (!inf.needsInput() && !inf.finished()))) return Body("its zlib data is damaged", null, false)
                        }
                    }
                }
                if (inf != null && !inf.finished()) return Body("its zlib data ends early", null, false)
            } finally { inf?.end() }
            return Body(null, hex(md.digest(), 0, 16), false)
        }
        if (headerSize < 12 || (headerSize - 12) % 24 != 0L || headerSize > len) return Body("a damaged BLTE header (size $headerSize)", null, true)
        val table = ByteArray((headerSize - 8).toInt())
        if (!readFully(s, table, 0, table.size)) return Body("ends early", null, true)
        if ((table[0].toInt() and 0xff) != 0x0F) return Body("a damaged BLTE header (flag 0x%02x)".format(table[0].toInt() and 0xff), null, true)
        val count = ((table[1].toInt() and 0xff) shl 16) or ((table[2].toInt() and 0xff) shl 8) or (table[3].toInt() and 0xff)
        if (12L + count * 24L != headerSize) return Body("a damaged BLTE header ($count frames in $headerSize bytes)", null, true)
        var sum = 0L
        for (i in 0 until count) sum += be32(table, 4 + i * 24).toLong() and 0xffffffffL
        val key = MessageDigest.getInstance("MD5").run { update(head); update(table); hex(digest(), 0, 16) }
        if (sum != len - headerSize) return Body("its frames do not fill it (${sum} of ${len - headerSize} bytes)", key, true)
        val md = MessageDigest.getInstance("MD5")
        for (i in 0 until count) {
            val at = 4 + i * 24
            var left = be32(table, at).toLong() and 0xffffffffL
            val want = table.copyOfRange(at + 8, at + 24)
            md.reset()
            while (left > 0) {
                val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) return Body("ends early (frame ${i + 1} of $count)", key, true)
                md.update(buf, 0, n); left -= n
            }
            val got = md.digest()
            if (want.any { it != 0.toByte() } && !got.contentEquals(want)) return Body("frame ${i + 1} of $count is damaged", key, true)
        }
        return Body(null, key, true)
    }

    /** the full encoding keys a build config names (its "name = content-key encoding-key" lines: encoding, install, download, size, vfs-*) */
    fun configKeys(buildConfig: String): List<String> = buildConfig.lineSequence().mapNotNull { l ->
        val m = Regex("""^([a-z0-9-]+)\s*=\s*([0-9a-f]{32})\s+([0-9a-f]{32})\s*$""").find(l.trim()) ?: return@mapNotNull null
        m.groupValues[3]
    }.toList()

    /**
     * The full encoding keys for [prefixes] (9-byte hex prefixes) from the CDN's archive indexes in [indices] (Data/indices/<hash>.index:
     * blocks of key / size / offset entries and a footer naming their widths), read once each until every prefix is found.
     */
    fun keysFromIndexes(indices: File, prefixes: Set<String>, stop: () -> Boolean = { false }): Map<String, String> {
        val want = prefixes.toMutableSet()
        val out = HashMap<String, String>()
        for (f in indices.listFiles { x -> x.isFile && x.name.endsWith(".index") }.orEmpty()) {
            if (want.isEmpty() || stop()) break
            runCatching { scanIndex(f, want, out) }
        }
        return out
    }

    private fun scanIndex(f: File, want: MutableSet<String>, out: MutableMap<String, String>) {
        val b = f.readBytes()
        if (b.size < 64) return
        val chk = b[b.size - 13].toInt() and 0xff
        if (chk !in 1..16) return
        val footerLen = chk + 12 + chk
        if (footerLen >= b.size) return
        val o = b.size - footerLen + chk + 3
        val blockKb = b[o].toInt() and 0xff
        val offsetBytes = b[o + 1].toInt() and 0xff
        val sizeBytes = b[o + 2].toInt() and 0xff
        val keyBytes = b[o + 3].toInt() and 0xff
        if (blockKb == 0 || keyBytes != 16) return
        val block = blockKb * 1024
        val entry = keyBytes + sizeBytes + offsetBytes
        val dataEnd = b.size - footerLen
        // the blocks, then a table of contents (each block's last key and an MD5): only whole blocks before it hold entries
        val blocks = dataEnd / (block + keyBytes + chk)
        for (bi in 0 until blocks) {
            var p = bi * block
            val end = p + block
            while (p + entry <= end) {
                if ((0 until keyBytes).all { b[p + it] == 0.toByte() }) break
                val pre = hex(b, p, 9)
                if (pre in want) { out[pre] = hex(b, p, 16); want.remove(pre); if (want.isEmpty()) return }
                p += entry
            }
        }
    }

    /**
     * The CDN's body [body], fetched by the whole encoding key [fullKey], written over [e]'s damaged one where it sits: only when [fullKey]
     * is the entry's, the body is exactly the entry's body length and whole ([bodyProblem]) and, with a frame table, its header's MD5 is
     * [fullKey]; then read back and checked again. null = written and whole, else why not.
     */
    fun writeBack(dataDir: File, e: Entry, body: File, fullKey: String): String? {
        if (!fullKey.startsWith(e.key9)) return "another file's key"
        if (body.length() != e.bodyLen) return "the CDN's copy is ${body.length()} bytes, the entry ${e.bodyLen}"
        val got = body.inputStream().buffered(1 shl 16).use { bodyProblem(StreamSource(it), e.bodyLen) }
        if (got.why != null) return "the CDN's copy is damaged too (${got.why})"
        if (got.framed && got.key != fullKey) return "the CDN's copy is another file"
        val seg = File(dataDir, "data.%03d".format(e.archive))
        if (!seg.isFile) return "its segment ${seg.name} is missing"
        RandomAccessFile(seg, "rw").use { raf ->
            body.inputStream().use { i ->
                val buf = ByteArray(1 shl 16)
                var at = e.offset + LOCAL_HEADER
                while (true) { val n = i.read(buf); if (n < 0) break; raf.seek(at); raf.write(buf, 0, n); at += n }
            }
            raf.fd.sync()
            val again = check(raf.channel, seg, e, Reader(ByteArray(1 shl 18)))
            if (again != null) return "still damaged after the write (${again.why})"
        }
        return null
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    internal fun hex(b: ByteArray, o: Int, n: Int): String {
        val sb = StringBuilder(n * 2)
        for (i in o until o + n) { val v = b[i].toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15]) }
        return sb.toString()
    }
}
