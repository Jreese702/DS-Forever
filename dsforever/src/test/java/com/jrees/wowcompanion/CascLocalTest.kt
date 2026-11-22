package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * #577 (1.0.37, a Spanish report: damaged game files after an unclean stop, CascLib error 1000, no repair path), off-device: a game
 * folder's local storage (index buckets + a data segment) built here, checked file by file, and a damaged file written over in place
 * from the "CDN" copy; Repair's words by how the folder was installed.
 */
class CascLocalTest {
    private val tmp: File = Files.createTempDirectory("casclocal").toFile()
    private val root = File(tmp, "WoW Forever")
    private val data = File(root, "Data/data").apply { mkdirs() }
    @After fun down() { tmp.deleteRecursively() }

    private fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** a BLTE body with a frame table ('N' frames) and its encoding key (the MD5 of its header) */
    private fun framed(vararg parts: ByteArray): Pair<ByteArray, String> {
        val frames = parts.map { byteArrayOf('N'.code.toByte()) + it }
        val table = ByteArrayOutputStream()
        table.write(0x0F); table.write(0); table.write(0); table.write(frames.size)
        for (f in frames) { table.write(be32(f.size.toLong())); table.write(be32(f.size - 1L)); table.write(md5(f)) }
        val header = "BLTE".toByteArray() + be32(12L + 24L * frames.size) + table.toByteArray()
        val body = header + frames.fold(ByteArray(0)) { a, b -> a + b }
        return body to hex(md5(header))
    }

    /** a one-frame zlib BLTE body and its key (the MD5 of the whole body) */
    private fun zlib(text: ByteArray): Pair<ByteArray, String> {
        val d = java.util.zip.Deflater(); d.setInput(text); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        val body = "BLTE".toByteArray() + be32(0) + byteArrayOf('Z'.code.toByte()) + out.toByteArray()
        return body to hex(md5(body))
    }

    private data class Put(val body: ByteArray, val key: String, val offset: Long)

    /** data.000 holding [bodies] (each after a 30-byte local header of garbage, as CascLib allows), and bucket 00's index naming them */
    private fun storage(vararg bodies: Pair<ByteArray, String>, idxName: String = "0000000002.idx"): List<Put> {
        val seg = ByteArrayOutputStream()
        val puts = ArrayList<Put>()
        for ((b, k) in bodies) {
            val at = seg.size().toLong()
            seg.write(ByteArray(30) { 0x5a }); seg.write(b)
            puts.add(Put(b, k, at))
        }
        File(data, "data.000").writeBytes(seg.toByteArray())
        val entries = ByteArrayOutputStream()
        for (p in puts) {
            entries.write(p.key.chunked(2).take(9).map { it.toInt(16).toByte() }.toByteArray())
            val storage = (0L shl 30) or p.offset
            for (i in 4 downTo 0) entries.write((storage ushr (8 * i)).toInt() and 0xff)
            entries.write(le32(30L + p.body.size))
        }
        val header = ByteArrayOutputStream()
        header.write(le32(0x10)); header.write(le32(0))
        header.write(byteArrayOf(7, 0, 0, 0, 4, 5, 9, 30)); header.write(ByteArray(8))      // revision 7, bucket 0, widths, segment bits
        header.write(ByteArray(8))                                                         // padding to 0x20
        header.write(le32(entries.size().toLong())); header.write(le32(0))
        File(data, idxName).writeBytes(header.toByteArray() + entries.toByteArray())
        return puts
    }

    /**
     * #577 review: a real install's layout (measured on one, read only): data.000 starts with 16 segment headers of 30 bytes, which
     * the bucket names as a 30-byte file at offset 0; the local headers are zero-filled; and some files are named twice in their
     * bucket, as (offset, size) and again as (offset + 30, size - 30), the BLTE body itself. [aliased] = which bodies get that second name.
     */
    private fun realStorage(vararg bodies: Pair<ByteArray, String>, aliased: Set<Int> = emptySet()): List<Put> {
        val seg = ByteArrayOutputStream()
        seg.write(ByteArray(16 * 30) { 0x21 })
        val puts = ArrayList<Put>()
        for ((b, k) in bodies) { puts.add(Put(b, k, seg.size().toLong())); seg.write(ByteArray(30)); seg.write(b) }
        File(data, "data.000").writeBytes(seg.toByteArray())
        val entries = ByteArrayOutputStream()
        fun entry(key: String, offset: Long, size: Long) {
            entries.write(key.chunked(2).take(9).map { it.toInt(16).toByte() }.toByteArray())
            for (i in 4 downTo 0) entries.write((offset ushr (8 * i)).toInt() and 0xff)
            entries.write(le32(size))
        }
        entry("00ab12cd34ef560000", 0, 30)                                                  // the segment's header
        for ((i, p) in puts.withIndex()) {
            entry(p.key, p.offset, 30L + p.body.size)
            if (i in aliased) entry(p.key, p.offset + 30, p.body.size.toLong())             // the same file, by its body
        }
        val header = ByteArrayOutputStream()
        header.write(le32(0x10)); header.write(le32(0))
        header.write(byteArrayOf(7, 0, 0, 0, 4, 5, 9, 30)); header.write(ByteArray(8)); header.write(ByteArray(8))
        header.write(le32(entries.size().toLong())); header.write(le32(0))
        File(data, "0000000002.idx").writeBytes(header.toByteArray() + entries.toByteArray())
        return puts
    }

    @Test fun aRealLayoutIsWholeAndItsSecondNamesAreNotDamage() {
        val bodies = arrayOf(framed("one ".toByteArray(), "two".toByteArray()), zlib(ByteArray(3000) { (it % 5).toByte() }),
            framed(ByteArray(700) { 7 }), zlib("text".repeat(300).toByteArray()))
        val puts = realStorage(*bodies, aliased = setOf(0, 1, 3))
        assertEquals(1 + 4 + 3, CascLocal.readIndex(File(data, "0000000002.idx")).first.size)
        val scan = CascLocal.scan(data)
        assertTrue(scan.damaged.toString(), scan.damaged.isEmpty())
        assertEquals("each file checked once", 4, scan.entries)
        assertEquals("the segment's header and the three whole-body parts", 4, scan.skipped)
        assertEquals("all there", 0, scan.partial)
        // a damaged file there is found once (not again by its second name), mended, and the folder is whole again
        val seg = File(data, "data.000").readBytes()
        for (i in 0 until 40) seg[(puts[1].offset + 30 + 30 + i).toInt()] = 0
        File(data, "data.000").writeBytes(seg)
        val d = CascLocal.scan(data).damaged.single()
        assertEquals(puts[1].offset, d.entry.offset)
        assertEquals(bodies[1].second.take(18), d.entry.key9)
        assertNull(CascLocal.writeBack(data, d.entry, File(tmp, "cdn").apply { writeBytes(bodies[1].first) }, bodies[1].second))
        assertTrue(CascLocal.scan(data).damaged.isEmpty())
        // Repair on it: nothing "too much", nothing left over
        val r = GameRepair.runOn(root, {}, { false }) { null }
        assertTrue(r.toString(), r.whole)
        assertEquals("No damaged files found.", GameRepair.summary(r, 0))
    }

    @Test fun aFilesPartsAreItsOwnKeyInsideIt() {
        // an entry inside another of its key and segment is a resident part of that file; another key there is a file of its own
        val t = CascLocal.Table(4)
        fun add(arc: Int, key: Int, off: Long, size: Long) = t.add(ByteArray(9) { key.toByte() }, 0, arc, off, size)
        add(3, 1, 1000, 130)       // 0: a file
        add(3, 1, 1030, 100)       // 1: its whole body: all there
        add(3, 1, 1030, 99)        // 2: a part of it
        add(3, 1, 1000, 130)       // 3: the very same entry twice
        add(3, 9, 0, 30)           // 4: the segment's header
        add(3, 9, 2000, 30)        // 5: a 30-byte entry past the headers: still checked (and too short for a file)
        add(4, 5, 500, 130)        // 6: segment 4: a file
        add(4, 6, 530, 100)        // 7: another key 30 bytes in: a file of its own
        add(5, 7, 5000, 1000)      // 8: a file streamed in part: its header and first frame, and one frame further on
        add(5, 7, 5000, 130)       // 9
        add(5, 7, 5800, 200)       // 10
        add(6, 7, 100, 50)         // 11: the same key in another segment: a file of its own
        val plan = CascLocal.toCheck(t)
        assertEquals(4, plan.segments.size)
        assertEquals(listOf(1000L to 130L, 2000L to 30L), plan.segments[0].map { t.offset[it] to t.size[it] })
        assertEquals(listOf(500L to 130L, 530L to 100L), plan.segments[1].map { t.offset[it] to t.size[it] })
        assertEquals(listOf(8), plan.segments[2].toList())
        assertEquals(listOf(11), plan.segments[3].toList())
        assertEquals("3 parts of file 0, the segment's header, 2 parts of file 8", 6, plan.skipped)
        assertEquals("file 0 is all there", setOf(8), plan.partial.keys)
        assertEquals(listOf(0L, 100L, 770L, 970L), plan.partial[8]!!.toList())
        assertTrue(CascLocal.resident(plan.partial[8], 770, 970))
        assertFalse(CascLocal.resident(plan.partial[8], 100, 770))
        assertTrue(CascLocal.resident(null, 0, 1))
        assertEquals("010101010101010101", t.key9(0))
    }

    @Test fun aFileStreamedInPartIsCheckedInItsPartsOnly() {
        // the game streams files: a frame it has not read yet holds zeros (measured on a real install), no damage
        val f1 = ByteArray(300) { 1 }; val f2 = ByteArray(400) { 2 }; val f3 = ByteArray(200) { 3 }
        val (body, key) = framed(f1, f2, f3)
        val puts = realStorage(body to key)
        val hs = 12 + 24 * 3                                  // "BLTE", its size and the frame table
        assertEquals(body.size, hs + f1.size + f2.size + f3.size + 3)
        val at = puts[0].offset
        val seg = File(data, "data.000").readBytes()
        val frame2 = at + 30 + hs + f1.size + 1
        for (i in 0 until f2.size + 1) seg[(frame2 + i).toInt()] = 0
        File(data, "data.000").writeBytes(seg)
        assertEquals("all there, it is damage", 1, CascLocal.scan(data).damaged.size)
        // the index names its parts: the header with frame 1, and frame 3
        val idx = File(data, "0000000002.idx")
        val b = idx.readBytes()
        fun entry(offset: Long, size: Long) = key.chunked(2).take(9).map { it.toInt(16).toByte() }.toByteArray() +
            (4 downTo 0).map { ((offset ushr (8 * it)).toInt() and 0xff).toByte() }.toByteArray() + le32(size)
        val more = entry(at, 30L + hs + f1.size + 1) + entry(frame2 + f2.size + 1, f3.size + 1L)
        val n = b.size - 0x28 + more.size
        val withParts = b.copyOf(0x20) + le32(n.toLong()) + b.copyOfRange(0x24, b.size) + more
        idx.writeBytes(withParts)
        val scan = CascLocal.scan(data)
        assertTrue(scan.damaged.toString(), scan.damaged.isEmpty())
        assertEquals(1, scan.entries); assertEquals(1, scan.partial)
        // a resident frame zero-filled is damage, with the file's whole key
        for (i in 0 until 50) seg[(at + 30 + hs + 10 + i).toInt()] = 0
        File(data, "data.000").writeBytes(seg)
        val d = CascLocal.scan(data).damaged.single()
        assertEquals("frame 1 of 3 is damaged", d.why)
        assertEquals(key, d.fullKey)
        // mended with the CDN's whole body: then every frame is there and whole
        assertNull(CascLocal.writeBack(data, d.entry, File(tmp, "cdn").apply { writeBytes(body) }, key))
        assertTrue(CascLocal.scan(data).damaged.isEmpty())
    }

    @Test fun aPatchManifestIsNotBlteAndNotDamage() {
        // the real install keeps patch manifests as the CDN serves them ("PA", version 2), not BLTE-encoded
        val pa = byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 2, 16, 16, 16, 16, 0) + ByteArray(200) { (it * 13).toByte() }
        assertNull(CascLocal.bodyProblem(CascLocal.StreamSource(pa.inputStream()), pa.size.toLong()).why)
        val other = byteArrayOf('P'.code.toByte(), 'X'.code.toByte(), 2, 16, 16, 16, 16, 0) + ByteArray(200)
        assertEquals("no BLTE header (other bytes)", CascLocal.bodyProblem(CascLocal.StreamSource(other.inputStream()), other.size.toLong()).why)
    }

    @Test fun aRepairThatCannotRunSaysSo() {
        assertEquals("Repair could not run: disk gone", GameRepair.couldNotRun(java.io.IOException("disk gone")))
        assertEquals("could not run: disk gone", GameRepair.last)
        assertEquals(GameRepair.NO_MEMORY_LINE, GameRepair.couldNotRun(OutOfMemoryError("Java heap space")))
        assertEquals("could not run: not enough memory", GameRepair.last)
    }

    @Test fun aWholeStorageHasNothingDamaged() {
        storage(framed("hello ".toByteArray(), "world".toByteArray()), zlib(ByteArray(5000) { (it % 7).toByte() }))
        val scan = CascLocal.scan(data)
        assertEquals(2, scan.entries)
        assertTrue(scan.damaged.toString(), scan.damaged.isEmpty())
        assertTrue(scan.problems.isEmpty())
    }

    @Test fun onlyTheNewestIndexOfABucketIsRead() {
        File(data, "0000000001.idx").writeBytes(ByteArray(64))                 // an older, unreadable version: never read
        storage(framed("a".toByteArray()))
        assertEquals(listOf("0000000002.idx"), CascLocal.indexesInUse(data).map { it.name })
        assertTrue(CascLocal.scan(data).problems.isEmpty())
    }

    @Test fun aZeroFilledFrameIsFoundWithItsWholeKey() {
        val (body, key) = framed(ByteArray(200) { 1 }, ByteArray(300) { 2 })
        val puts = storage(body to key, zlib("ok".repeat(100).toByteArray()))
        // an unclean stop zero-filled the second frame
        val seg = File(data, "data.000").readBytes()
        val frame2 = puts[0].offset + 30 + (body.size - 301)
        for (i in 0 until 301) seg[(frame2 + i).toInt()] = 0
        File(data, "data.000").writeBytes(seg)
        val scan = CascLocal.scan(data)
        assertEquals(1, scan.damaged.size)
        val d = scan.damaged.single()
        assertEquals("frame 2 of 2 is damaged", d.why)
        assertEquals("the frame table names the whole key", key, d.fullKey)
        // the CDN's copy goes back where it was: the storage is whole again, and nothing else moved
        val cdn = File(tmp, "cdn").apply { writeBytes(body) }
        assertNull(CascLocal.writeBack(data, d.entry, cdn, key))
        assertTrue(CascLocal.scan(data).damaged.isEmpty())
        assertEquals(seg.size.toLong(), File(data, "data.000").length())
    }

    @Test fun aDamagedZlibBodyAndAMissingHeaderAreFound() {
        val (z, zk) = zlib(ByteArray(4000) { (it * 31).toByte() })
        val (f, fk) = framed("x".repeat(50).toByteArray())
        val puts = storage(z to zk, f to fk)
        val seg = File(data, "data.000").readBytes()
        for (i in 20 until 40) seg[(puts[0].offset + 30 + i).toInt()] = 0x11          // inside the deflate stream
        for (i in 0 until 8) seg[(puts[1].offset + 30 + i).toInt()] = 0                // "BLTE" and the header size gone
        File(data, "data.000").writeBytes(seg)
        val why = CascLocal.scan(data).damaged.map { it.why }
        assertEquals(2, why.size)
        assertTrue(why[0], why[0].startsWith("its zlib data"))
        assertEquals("no BLTE header (zero-filled)", why[1])
    }

    @Test fun aSegmentCutShortIsDamage() {
        val (b, k) = framed(ByteArray(1000) { 3 })
        storage(b to k)
        val seg = File(data, "data.000")
        seg.writeBytes(seg.readBytes().copyOf(500))
        val d = CascLocal.scan(data).damaged.single()
        assertTrue(d.why, d.why.startsWith("its segment data.000 ends early"))
    }

    @Test fun aDamagedIndexIsAProblemNotDamage() {
        storage(framed("a".toByteArray()))
        val idx = File(data, "0000000002.idx")
        val b = idx.readBytes(); b[0x20] = 0x7f; b[0x23] = 0x7f                       // the entries' block size runs past the file
        idx.writeBytes(b)
        val scan = CascLocal.scan(data)
        assertEquals(0, scan.entries)
        assertTrue(scan.problems.single(), scan.problems.single().contains("block is damaged"))
    }

    @Test fun theCdnsCopyMustBeTheSameFile() {
        val (b, k) = framed(ByteArray(100) { 9 })
        storage(b to k)
        val e = CascLocal.readIndex(File(data, "0000000002.idx")).first.single()
        assertTrue(CascLocal.writeBack(data, e, File(tmp, "short").apply { writeBytes(b.copyOf(b.size - 1)) }, k)!!.contains("bytes"))
        val other = framed(ByteArray(100) { 8 }).first
        assertEquals(b.size, other.size)
        assertEquals("the CDN's copy is another file", CascLocal.writeBack(data, e, File(tmp, "other").apply { writeBytes(other) }, k))
        assertEquals("another file's key", CascLocal.writeBack(data, e, File(tmp, "same").apply { writeBytes(b) }, "f".repeat(32)))
        assertNull(CascLocal.writeBack(data, e, File(tmp, "same").apply { writeBytes(b) }, k))
    }

    @Test fun keysComeFromTheBuildConfigAndTheCdnIndexes() {
        val enc = "0123456789abcdef0123456789abcdef"
        assertEquals(listOf(enc, "fedcba9876543210fedcba9876543210"), CascLocal.configKeys(
            "# Build Configuration\nroot = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nencoding = bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb $enc\n" +
                "encoding-size = 1 2\ninstall = cccccccccccccccccccccccccccccccc fedcba9876543210fedcba9876543210\n"))
        // a CDN archive index: one 4 KB block of key / size / offset entries, its table of contents and footer
        val key = "00112233445566778899aabbccddeeff"
        val block = ByteArray(4096)
        key.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(block, 0)
        val toc = ByteArray(16 + 8)
        val footer = ByteArray(8) + byteArrayOf(1, 0, 0, 4, 4, 4, 16, 8) + le32(1) + ByteArray(8)
        val indices = File(root, "Data/indices").apply { mkdirs() }
        File(indices, "ab.index").writeBytes(block + toc + footer)
        assertEquals(mapOf(key.take(18) to key), CascLocal.keysFromIndexes(indices, setOf(key.take(18), "ffffffffffffffffff")))
    }

    @Test fun repairMendsWhatTheCdnHasAndSaysWhatToDoWithTheRest() {
        val (b1, k1) = framed(ByteArray(400) { 4 }, ByteArray(400) { 5 })
        val (b2, k2) = framed(ByteArray(300) { 6 }, ByteArray(300) { 7 })
        val puts = storage(b1 to k1, b2 to k2)
        val seg = File(data, "data.000").readBytes()
        for (p in puts) for (i in 0 until 50) seg[(p.offset + 30 + p.body.size - 1 - i).toInt()] = 0    // the last frame of each
        File(data, "data.000").writeBytes(seg)
        File(root, DataStaleness.STREAMED).writeText("")
        val lines = ArrayList<String>()
        // the "CDN" has the first file only
        val r = GameRepair.runOn(root, { lines.add(it) }, { false }) { key ->
            if (key == k1) File(tmp, "dl-$key").apply { writeBytes(b1) } else null
        }
        assertEquals(2, r.damaged); assertEquals(1, r.mended); assertEquals(1, r.left.size)
        assertFalse(r.whole)
        assertTrue(lines.first(), lines.first().startsWith("Checking the game's files: "))
        assertTrue(lines.any { it == "Repairing the game's files: 2 of 2" })
        assertEquals("Repaired 1 of 2 damaged files. Install the game again: rename the WoW Forever folder, then Install directly",
            GameRepair.summary(r, 0))
        assertEquals(1, CascLocal.scan(data).damaged.size)
        // the second one mended too at the next Repair: whole
        val r2 = GameRepair.runOn(root, {}, { false }) { key -> File(tmp, "dl2-$key").apply { writeBytes(if (key == k1) b1 else b2) } }
        assertTrue(r2.whole)
        assertEquals("Repaired 1 damaged files.", GameRepair.summary(r2, 0))
        assertEquals("Repaired 3 damaged files.", GameRepair.summary(r2, 2))
        val r3 = GameRepair.runOn(root, {}, { false }) { null }
        assertEquals("No damaged files found.", GameRepair.summary(r3, 0))
        assertEquals("Repair could not check the game's own files: offline", GameRepair.summary(r3, -1, "offline"))
        assertTrue(GameRepair.last!!.startsWith("checked 2 files, 0 damaged"))
    }

    @Test fun soMuchDamageIsNotMendedOneByOne() {
        assertFalse(GameRepair.tooMuch(2, 2))
        assertFalse(GameRepair.tooMuch(100, 120))
        assertTrue(GameRepair.tooMuch(101, 120))
        assertFalse(GameRepair.tooMuch(1_500, 1_000_000))
        assertTrue(GameRepair.tooMuch(2_001, 1_000_000))
    }

    @Test fun repairWordsFollowTheInstallRoute() {
        val folder = GameRepair.Result(10, 2, 1, listOf("x"), emptyList(), CascHeal.Route.FOLDER)
        assertEquals("Repaired 1 of 2 damaged files. Copy the Data folder again from your PC", GameRepair.summary(folder, 0))
        val tooMuch = GameRepair.Result(10, 9, 0, List(9) { "x" }, emptyList(), CascHeal.Route.DIRECT, tooMuch = true)
        assertEquals("Too many files are damaged (9) to repair one by one. Install the game again: rename the WoW Forever folder, then " +
            "Install directly", GameRepair.summary(tooMuch, 0))
        val idx = GameRepair.Result(0, 0, 0, emptyList(), listOf("0000000002.idx: too short"), CascHeal.Route.FOLDER)
        assertEquals("Some of the game's index files are damaged. Copy the Data folder again from your PC", GameRepair.summary(idx, 0))
    }
}
