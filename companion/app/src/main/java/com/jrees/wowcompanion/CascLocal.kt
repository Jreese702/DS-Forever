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
 * bytes the CDN serves under its encoding key. A key named more than once holds its file's resident parts (the game streams files a
 * frame at a time: what it has not read yet is zeros); the segments' own headers have entries too ([toCheck]). So:
 * - [scan]: every file of the buckets in use is read back and its BLTE body checked: its header, every frame against the MD5 its frame
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

    /**
     * what a scan found: [entries] checked, [bytes] read, [damaged], [problems] (an index that could not be read, said in words);
     * [skipped] = index entries that name no file of their own ([toCheck]: a segment's header, a resident part of a file); [partial] =
     * files only partly on this device (the game streams them), checked in their resident parts
     */
    data class Scan(val entries: Int, val bytes: Long, val damaged: List<Damage>, val problems: List<String>, val stopped: Boolean = false,
                    val skipped: Int = 0, val partial: Int = 0)

    private val IDX = Regex("""^([0-9a-fA-F]{2})([0-9a-fA-F]{8})\.idx$""")

    /** the index file in use for each bucket of [dataDir] (the newest version), bucket order */
    fun indexesInUse(dataDir: File): List<File> =
        dataDir.listFiles { f -> f.isFile && IDX.matches(f.name) }.orEmpty()
            .groupBy { IDX.find(it.name)!!.groupValues[1].lowercase() }
            .map { (_, fs) -> fs.maxByOrNull { IDX.find(it.name)!!.groupValues[2].toLong(16) }!! }
            .sortedBy { it.name.lowercase() }

    /**
     * Index entries in plain arrays (#577 review: a real folder's buckets hold 0.5 to 2 million entries; as objects with String keys,
     * de-duplicated through boxed Triples, that was hundreds of MB in an app whose heap is 512 MB). [Entry] objects only for what is said.
     */
    internal class Table(cap: Int = 1024) {
        var n = 0; private set
        private var keyHi = LongArray(cap)
        private var keyLo = ByteArray(cap)
        var archive = IntArray(cap); private set
        var offset = LongArray(cap); private set
        var size = LongArray(cap); private set

        fun ensure(more: Int) {
            if (n + more <= archive.size) return
            val c = maxOf(n + more, archive.size * 2)
            keyHi = keyHi.copyOf(c); keyLo = keyLo.copyOf(c); archive = archive.copyOf(c); offset = offset.copyOf(c); size = size.copyOf(c)
        }

        /** the entries from [to] on dropped (an index that turned out damaged part-way) */
        fun truncate(to: Int) { n = to.coerceIn(0, n) }

        /** one entry: its 9-byte key at [b]`[p]` */
        fun add(b: ByteArray, p: Int, arc: Int, off: Long, sz: Long) {
            ensure(1)
            var hi = 0L
            for (i in 0 until 8) hi = (hi shl 8) or (b[p + i].toLong() and 0xff)
            keyHi[n] = hi; keyLo[n] = b[p + 8]; archive[n] = arc; offset[n] = off; size[n] = sz
            n++
        }

        fun sameKey(i: Int, j: Int): Boolean = keyHi[i] == keyHi[j] && keyLo[i] == keyLo[j]
        fun hi(i: Int): Long = keyHi[i]
        fun lo(i: Int): Byte = keyLo[i]

        fun key9(i: Int): String {
            val b = ByteArray(9)
            for (k in 0 until 8) b[k] = (keyHi[i] ushr (56 - 8 * k)).toByte()
            b[8] = keyLo[i]
            return hex(b, 0, 9)
        }

        fun entry(i: Int) = Entry(key9(i), archive[i], offset[i], size[i])
    }

    /** an index bucket's entries, or the reason it could not be read (CascLib's version 7 layout: a guarded header, then the entries) */
    fun readIndex(f: File): Pair<List<Entry>, String?> {
        val t = Table()
        val why = readIndexInto(f, t)
        return (if (why != null) emptyList() else List(t.n) { t.entry(it) }) to why
    }

    private fun readIndexInto(f: File, t: Table): String? {
        val b = runCatching { f.readBytes() }.getOrElse { return "${f.name}: unreadable (${it.message})" }
        if (b.size < 0x28) return "${f.name}: too short (${b.size} bytes)"
        val headerSize = le32(b, 0)
        if (headerSize != 0x10) return "${f.name}: not a version 7 index"
        val o = 8
        val revision = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        val sizeBytes = b[o + 4].toInt() and 0xff
        val offsetBytes = b[o + 5].toInt() and 0xff
        val keyBytes = b[o + 6].toInt() and 0xff
        val segmentBits = b[o + 7].toInt() and 0xff
        if (revision != 7 || sizeBytes != 4 || offsetBytes != 5 || keyBytes != 9 || segmentBits !in 1..39)
            return "${f.name}: an index layout this check does not read (revision $revision)"
        val blockSize = le32(b, 0x20).toLong() and 0xffffffffL
        val entryLen = keyBytes + offsetBytes + sizeBytes
        if (blockSize <= 0 || 0x28 + blockSize > b.size || blockSize % entryLen != 0L)
            return "${f.name}: its entries' block is damaged (size $blockSize of ${b.size - 0x28})"
        t.ensure((blockSize / entryLen).toInt())
        var p = 0x28
        val end = 0x28 + blockSize.toInt()
        while (p + entryLen <= end) {
            var storage = 0L
            for (i in 0 until offsetBytes) storage = (storage shl 8) or (b[p + keyBytes + i].toLong() and 0xff)
            val size = le32(b, p + keyBytes + offsetBytes).toLong() and 0xffffffffL
            t.add(b, p, (storage ushr segmentBits).toInt(), storage and ((1L shl segmentBits) - 1), size)
            p += entryLen
        }
        return null
    }

    /** the segment headers at the start of each data.NNN: 16 of [LOCAL_HEADER] bytes (one per bucket) */
    const val SEGMENT_HEADERS = 16L * LOCAL_HEADER

    /**
     * What a scan reads: [segments] the files to check, per segment (archive order), each in offset order; [skipped] index entries
     * that name no file of their own; [partial] the files only partly on this device (table index -> their resident parts, body
     * offsets as start / end pairs: only those parts are checked).
     */
    internal class Plan(val segments: List<IntArray>, val skipped: Int, val partial: Map<Int, LongArray>)

    /**
     * Which index entries are files (#577 review, measured on a real install, read only: 1.94 million entries in 16 buckets, 135 GB):
     * - a segment's header: each bucket names the 30-byte header at the start of every data.NNN (2,272 entries there), never a file;
     * - THE RESIDENT PARTS OF A FILE: a key named more than once in its bucket is one file, the entry holding the others (the space
     *   the file has in its segment); the entries inside it say which of its bytes are on this device. About 10% of the keys have one
     *   part (offset + 30, size - 30), the whole BLTE body: the file is all there. A file the game streamed only partly has the
     *   header with its first frame and the frames it read so far; the frames between hold zeros until the game reads them, which is
     *   no damage. A key named once is a file all there;
     * - the same (offset, size) named twice under other keys (never seen) is checked once.
     * With these, every whole file of the real install was found whole and every partly-there file whole in its resident parts.
     */
    internal fun toCheck(t: Table): Plan {
        if (t.n == 0) return Plan(emptyList(), 0, emptyMap())
        val part = BooleanArray(t.n)
        val partial = HashMap<Int, LongArray>()
        residentParts(t, part, partial)
        val arcs = t.archive.copyOf(t.n).also { it.sort() }.distinct().toIntArray()
        val count = IntArray(arcs.size)
        val slot = IntArray(t.n) { i -> java.util.Arrays.binarySearch(arcs, t.archive[i]).also { count[it]++ } }
        val groups = Array(arcs.size) { IntArray(count[it]) }
        val fill = IntArray(arcs.size)
        for (i in 0 until t.n) { val s = slot[i]; groups[s][fill[s]++] = i }
        var skipped = 0
        val out = ArrayList<IntArray>(arcs.size)
        for (g in groups) {
            // offset order (ties in index order): offsets are under 2^39 and a segment's entries under 2^24, so both fit in one long
            val sorted = if (g.size < (1 shl 24)) {
                val k = LongArray(g.size) { (t.offset[g[it]] shl 24) or it.toLong() }
                k.sort()
                IntArray(g.size) { g[(k[it] and 0xffffff).toInt()] }
            } else g.sortedWith(compareBy({ t.offset[it] }, { it })).toIntArray()
            val keep = IntArray(sorted.size)
            var m = 0
            for (i in sorted) {
                if (part[i]) { skipped++; continue }
                if (t.size[i] == LOCAL_HEADER.toLong() && t.offset[i] < SEGMENT_HEADERS) { skipped++; continue }
                // the same place and length as a file kept just before it (another key: the same bytes, checked once)
                var q = m - 1
                var same = false
                while (q >= 0 && t.offset[keep[q]] == t.offset[i]) { if (t.size[keep[q]] == t.size[i]) { same = true; break }; q-- }
                if (same) { skipped++; continue }
                keep[m++] = i
            }
            if (m > 0) out.add(keep.copyOf(m))
        }
        return Plan(out, skipped, partial)
    }

    /**
     * The keys named more than once: each entry inside another of its key and segment is a resident part of that one ([part] marks it);
     * a file whose parts do not cover its whole body gets them in [partial] (body offsets, merged, as start / end pairs).
     */
    private fun residentParts(t: Table, part: BooleanArray, partial: MutableMap<Int, LongArray>) {
        // the keys' first 8 bytes named more than once (a primitive sort; only those entries are boxed below)
        val his = LongArray(t.n) { t.hi(it) }.also { it.sort() }
        var dups = LongArray(1024)
        var nd = 0
        var i = 1
        while (i < his.size) {
            if (his[i] == his[i - 1] && (nd == 0 || dups[nd - 1] != his[i])) { if (nd == dups.size) dups = dups.copyOf(nd * 2); dups[nd++] = his[i] }
            i++
        }
        if (nd == 0) return
        val multi = (0 until t.n).filter { java.util.Arrays.binarySearch(dups, 0, nd, t.hi(it)) >= 0 }
            .sortedWith(compareBy<Int>({ t.hi(it) }, { t.lo(it) }, { t.archive[it] }, { t.offset[it] }, { -t.size[it] }, { it }))
        var a = 0
        while (a < multi.size) {
            var b = a + 1
            while (b < multi.size && t.sameKey(multi[a], multi[b]) && t.archive[multi[a]] == t.archive[multi[b]]) b++
            if (b - a > 1) group(t, multi.subList(a, b), part, partial)
            a = b
        }
    }

    /** one key's entries in one segment, offset order (the larger first at the same offset) */
    private fun group(t: Table, es: List<Int>, part: BooleanArray, partial: MutableMap<Int, LongArray>) {
        val holders = ArrayList<Int>()
        val parts = HashMap<Int, ArrayList<Long>>()
        for (e in es) {
            val s = t.offset[e]
            val end = s + t.size[e]
            val h = holders.lastOrNull { t.offset[it] <= s && end <= t.offset[it] + t.size[it] }
            if (h == null) { holders.add(e); continue }
            part[e] = true
            parts.getOrPut(h) { ArrayList() }.apply { add(s); add(end) }
        }
        for ((h, p) in parts) {
            val body = t.offset[h] + LOCAL_HEADER
            val len = t.size[h] - LOCAL_HEADER
            // to body offsets (a part with the local header starts at the body's start), sorted, merged
            val spans = (0 until p.size / 2).map { maxOf(0L, p[2 * it] - body) to (p[2 * it + 1] - body).coerceAtMost(len) }
                .filter { it.second > it.first }.sortedBy { it.first }
            val merged = ArrayList<Long>()
            for ((s, e) in spans) {
                if (merged.isNotEmpty() && s <= merged[merged.size - 1]) merged[merged.size - 1] = maxOf(merged[merged.size - 1], e)
                else { merged.add(s); merged.add(e) }
            }
            if (!(merged.size == 2 && merged[0] == 0L && merged[1] >= len)) partial[h] = merged.toLongArray()
        }
    }

    /** [s] to [e] (body offsets) lie in one of the resident parts [r] (null = the whole file is there) */
    internal fun resident(r: LongArray?, s: Long, e: Long): Boolean {
        if (r == null) return true
        var k = 0
        while (k + 1 < r.size) { if (r[k] <= s && e <= r[k + 1]) return true; k += 2 }
        return false
    }

    /**
     * Every file of [dataDir]'s buckets in use ([toCheck]), read back and checked ([bodyProblem]); [threads] segments at a time.
     * [progress] (bytes read, of the total) now and then; [stop] asked between entries (true = the scan ends, what it found so far kept,
     * [Scan.stopped]).
     */
    fun scan(dataDir: File, threads: Int = 4, progress: (Long, Long) -> Unit = { _, _ -> }, stop: () -> Boolean = { false }): Scan {
        val problems = ArrayList<String>()
        val indexes = indexesInUse(dataDir)
        // sized once from the indexes' own entry counts: no growing copies of a million-entry table
        val t = Table(indexes.sumOf { entryCount(it) }.coerceIn(16L, 1L shl 26).toInt())
        for (idx in indexes) {
            val before = t.n
            val why = readIndexInto(idx, t)
            if (why != null) { problems.add(why); t.truncate(before) }
        }
        val plan = toCheck(t)
        val bySegment = plan.segments
        val count = bySegment.sumOf { it.size }
        var total = 0L
        for (g in bySegment) for (i in g) total += t.size[i].coerceAtLeast(0L)
        val done = java.util.concurrent.atomic.AtomicLong()
        val damaged = java.util.Collections.synchronizedList(ArrayList<Damage>())
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads.coerceIn(1, maxOf(1, bySegment.size)))
        val lastTold = java.util.concurrent.atomic.AtomicLong(0L)
        try {
            val jobs = bySegment.map { es ->
                pool.submit {
                    val seg = File(dataDir, "data.%03d".format(t.archive[es[0]]))
                    val ch = runCatching { RandomAccessFile(seg, "r").channel }.getOrNull()
                    try {
                        val reader = Reader(ByteArray(1 shl 18))
                        for (i in es) {
                            if (stopped.get() || stop()) { stopped.set(true); break }
                            val r = check(ch, seg, t.offset[i], t.size[i], reader, plan.partial[i])
                            if (r != null) {
                                val e = t.entry(i)
                                damaged.add(Damage(e, r.first, r.second?.takeIf { k -> k.startsWith(e.key9) }))
                            }
                            val now = done.addAndGet(t.size[i].coerceAtLeast(0L))
                            if (now - lastTold.get() >= 64L * 1024 * 1024) { lastTold.set(now); runCatching { progress(now, total) } }
                        }
                    } finally { runCatching { ch?.close() } }
                }
            }
            for (j in jobs) j.get()
        } finally { pool.shutdown() }
        progress(done.get(), total)
        return Scan(count, done.get(), damaged.sortedWith(compareBy({ it.entry.archive }, { it.entry.offset })), problems, stopped.get(),
            plan.skipped, plan.partial.size)
    }

    /** an index file's entry count from its header (0 = not readable: [readIndexInto] says why) */
    private fun entryCount(f: File): Long = runCatching {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < 0x28) return 0L
            val b = ByteArray(4); r.seek(0x20); r.readFully(b)
            // never more than the file holds (a damaged header can name any size)
            minOf(le32(b, 0).toLong() and 0xffffffffL, r.length() - 0x28) / 18
        }
    }.getOrDefault(0L)

    /**
     * One file's body, checked where it sits ([resident]: its parts on this device, null = all of it): null = whole, else what is
     * wrong and the key its own bytes give (null = none)
     */
    private fun check(ch: FileChannel?, seg: File, offset: Long, size: Long, reader: Reader, resident: LongArray? = null): Pair<String, String?>? {
        if (size < LOCAL_HEADER + 8) return "an entry too short for a file ($size bytes)" to null
        if (ch == null) return "its segment ${seg.name} is missing" to null
        val segLen = runCatching { ch.size() }.getOrDefault(0L)
        if (offset + size > segLen) return "its segment ${seg.name} ends early (${segLen} of ${offset + size} bytes)" to null
        val bodyLen = size - LOCAL_HEADER
        reader.open(ch, offset + LOCAL_HEADER, bodyLen)
        val r = runCatching { bodyProblem(reader, bodyLen, resident) }.getOrElse { return "unreadable (${it.message})" to null }
        return r.why?.let { it to r.key }
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
        /** a part that is not on this device: passed over, never read */
        override fun skip(n: Long): Boolean {
            val inBuf = (bufEnd - bufAt).toLong()
            if (n <= inBuf) { bufAt += n.toInt(); return true }
            val rest = n - inBuf
            bufAt = bufEnd
            if (rest > left) { left = 0; return false }
            pos += rest; left -= rest
            return true
        }
    }

    /** a sequential source of a body's bytes (a segment's region, a downloaded file) */
    interface Source {
        fun read(out: ByteArray, off: Int, len: Int): Int
        /** [n] bytes passed over: false = it ended first */
        fun skip(n: Long): Boolean {
            val b = ByteArray(1 shl 16)
            var left = n
            while (left > 0) { val k = read(b, 0, minOf(b.size.toLong(), left).toInt()); if (k <= 0) return false; left -= k }
            return true
        }
    }

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
    fun bodyProblem(s: Source, len: Long, resident: LongArray? = null): Body {
        if (len < 9) return Body("too short for a BLTE file ($len bytes)", null, false)
        // a file only partly on this device ([resident]: its parts, body offsets): only what is there is checked; a one-frame file
        // not all there, or one whose header is not there, is not checked at all (its zeros wait for the game's streaming)
        if (resident != null && !resident(resident, 0, 8)) return Body(null, null, false)
        val head = ByteArray(8)
        if (!readFully(s, head, 0, 8)) return Body("ends early", null, false)
        // a patch manifest ("PA", version 1 or 2) is stored as the CDN serves it, not BLTE-encoded (2 of the real install's files):
        // nothing in it to check against
        if (head[0] == 'P'.code.toByte() && head[1] == 'A'.code.toByte() && (head[2].toInt() == 1 || head[2].toInt() == 2)) return Body(null, null, false)
        if (!(head[0] == 'B'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'T'.code.toByte() && head[3] == 'E'.code.toByte()))
            return Body("no BLTE header (${if (head.all { it == 0.toByte() }) "zero-filled" else "other bytes"})", null, false)
        val headerSize = be32(head, 4).toLong() and 0xffffffffL
        val buf = ByteArray(1 shl 16)
        if (headerSize == 0L) {
            if (resident != null && !resident(resident, 0, len)) return Body(null, null, false)
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
        if (resident != null && !resident(resident, 0, headerSize)) return Body(null, null, true)
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
        var frameAt = headerSize
        for (i in 0 until count) {
            val at = 4 + i * 24
            var left = be32(table, at).toLong() and 0xffffffffL
            val start = frameAt
            frameAt += left
            if (!resident(resident, start, frameAt)) {
                // not on this device yet (the game streams it): passed over
                if (!s.skip(left)) return Body("ends early (frame ${i + 1} of $count)", key, true)
                continue
            }
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
            val again = check(raf.channel, seg, e.offset, e.size, Reader(ByteArray(1 shl 18)))
            if (again != null) return "still damaged after the write (${again.first})"
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
