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
