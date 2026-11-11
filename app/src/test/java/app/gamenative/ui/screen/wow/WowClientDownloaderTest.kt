package app.gamenative.ui.screen.wow

import com.jrees.wowcompanion.WowProduct
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** Launch support, off-device: the updater follows the folder's client (no network: only the pure parts). */
class WowClientDownloaderTest {
    private val versions = listOf(
        mapOf("Region" to "us", "VersionsName" to "1.60.1.70245"),
        mapOf("Region" to "eu", "VersionsName" to "1.60.1.70246"),
    )

    @Test fun remoteRowIsTheInstallsRegion() {
        assertEquals("1.60.1.70246", WowClientDownloader.remoteRow(versions, "Region", "EU")!!["VersionsName"])
        assertEquals("1.60.1.70245", WowClientDownloader.remoteRow(versions, "Region", "kr")!!["VersionsName"])
        assertEquals("1.60.1.70245", WowClientDownloader.remoteRow(versions, "Region", null)!!["VersionsName"])
        assertNull(WowClientDownloader.remoteRow(emptyList(), "Region", "us"))
    }

    private fun md5(b: ByteArray) = java.security.MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }

    // CDN integrity (the user, 2026-11-11: updates on slow links): a sign-in page is never game data
    @Test fun aRangedAnswerMustBeA206OfTheAskedLength() {
        assertTrue(WowClientDownloader.rangedOk(206, 100, 0L until 100L))
        assertTrue(WowClientDownloader.rangedOk(206, 8, 40L until 48L, "bytes 40-47/9999"))
        assertFalse(WowClientDownloader.rangedOk(200, 100, 0L until 100L))           // a whole body (a portal, a proxy dropping Range)
        assertFalse(WowClientDownloader.rangedOk(206, 99, 0L until 100L))            // cut short
        assertFalse(WowClientDownloader.rangedOk(206, 8, 40L until 48L, "bytes 0-7/9999"))
    }

    @Test fun aConfigIsNamedByItsMd5() {
        val config = "# Build Configuration\n\nroot = 0123\n".toByteArray()
        assertTrue(WowClientDownloader.configOk(config, md5(config)))
        assertTrue(WowClientDownloader.configOk(config, md5(config).uppercase()))
        assertFalse(WowClientDownloader.configOk("<html>Sign in to the hotel Wi-Fi</html>".toByteArray(), md5(config)))
    }

    @Test fun anIndexIsNamedByItsFootersMd5() {
        // a 4 KB block + the 28-byte footer: toc hash (8), version, 2 x unknown, block KB, offset / size / key bytes, checksum size 8, count, checksum
        val footer = ByteArray(8) { it.toByte() } + byteArrayOf(1, 0, 0, 4, 4, 4, 16, 8) + byteArrayOf(0, 0, 0, 1) + ByteArray(8) { 9 }
        val index = ByteArray(4096) + footer
        val name = md5(footer)
        assertTrue(WowClientDownloader.indexOk(index, name))
        assertFalse(WowClientDownloader.indexOk(index.copyOf(index.size - 1), name))                  // cut
        assertFalse(WowClientDownloader.indexOk("<html>portal</html>".toByteArray(), name))
        val other = index.copyOf().also { it[it.size - 13] = 16 }                                      // not the checksum size we know
        assertFalse(WowClientDownloader.indexOk(other, md5(other.copyOfRange(other.size - 28, other.size))))
    }

    @Test fun emptyCdnFieldsAreRepairedBeforePlay() {
        val root = Files.createTempDirectory("dsf-repair").toFile()
        val saved = WowClientDownloader.patchUrlFor
        try {
            WowClientDownloader.patchUrlFor = { "http://127.0.0.1:1/none" }           // the patch server unreachable (a flaky network)
            File(root, "_forever_").mkdirs(); File(root, "_forever_/Wow-arm64.exe").writeText("x")
            val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Version!STRING:0|Product!STRING:0"
            File(root, ".build.info").writeText("$head\nus|1|b|c|tpr/wow|||1.60.1.70888|wowf\n")
            val known = WowClientDownloader.VersionCheckResult("1.60.1.70888", "1.60.1.70888", false,
                remoteCdnHosts = listOf("eu.cdn.blizzard.com"), product = WowProduct.LAUNCH, remoteCdnServers = "https://eu.cdn.blizzard.com/?maxhosts=4")
            assertTrue(WowClientDownloader.repairBuildInfo(root, known))
            val row = WowClientDownloader.readBuildInfo(File(root, ".build.info"))!!
            assertEquals("eu.cdn.blizzard.com", row["CDN Hosts"]); assertEquals("https://eu.cdn.blizzard.com/?maxhosts=4", row["CDN Servers"])
            assertFalse(WowClientDownloader.repairBuildInfo(root, known))           // healthy now: left alone
            // no check, no patch server: the built-in hosts
            File(root, ".build.info").writeText("$head\nus|1|b|c|tpr/wow|||1.60.1.70888|wowf\n")
            assertTrue(WowClientDownloader.repairBuildInfo(root))
            assertEquals("level3.blizzard.com us.cdn.blizzard.com", WowClientDownloader.readBuildInfo(File(root, ".build.info"))!!["CDN Hosts"])
        } finally {
            WowClientDownloader.patchUrlFor = saved
            root.deleteRecursively()
        }
    }

    @Test fun pathsFollowTheFoldersClient() {
        val root = Files.createTempDirectory("dsf-dl").toFile()
        try {
            File(root, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Version!STRING:0|Product!STRING:0\nus|1|1.60.1.70245|wowf\n")
            assertEquals(WowProduct.LAUNCH, WowClientDownloader.product(root))
            assertEquals(File(root, "_forever_"), WowClientDownloader.flavorDir(root))
            assertEquals(File(root, "_forever_/Wow-arm64.exe"), WowClientDownloader.exe(root))
            assertEquals("wowf", WowClientDownloader.readBuildInfo(File(root, ".build.info"))!!["Product"])
            assertNull(WowClientDownloader.checkVersion(File(root, "missing")))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- issue #70 (2026-11-11): CDN bodies streamed, never one byte array (the 68 MB OOM at ResponseBody.bytes()) ----

    private fun body(b: ByteArray) = com.jrees.wowcompanion.CdnStream.copy(b.inputStream(), java.io.OutputStream.nullOutputStream())

    @Test fun aStreamedBodyKeeps8838e9e9sChecks() {
        val ten = body(ByteArray(10))
        assertNull(WowClientDownloader.bodyProblem(206, "10", "bytes 40-49/999", 40L until 50L, ten))
        assertNull(WowClientDownloader.bodyProblem(200, "10", null, null, ten))
        assertNull(WowClientDownloader.bodyProblem(200, null, null, null, ten))                    // chunked: no length said
        assertTrue(WowClientDownloader.bodyProblem(200, "10", null, 40L until 50L, ten)!!.startsWith("not the asked range"))  // a 200 to a Range
        assertTrue(WowClientDownloader.bodyProblem(206, "10", null, 40L until 51L, ten)!!.startsWith("not the asked range"))  // cut short
        assertTrue(WowClientDownloader.bodyProblem(206, null, "bytes 0-9/999", 40L until 50L, ten)!!.startsWith("not the asked range"))
        assertTrue(WowClientDownloader.bodyProblem(200, "12", null, null, ten)!!.startsWith("a short answer"))
    }

    @Test fun anIndexIsCheckedByTheStreamedTail() {
        val footer = ByteArray(8) { it.toByte() } + byteArrayOf(1, 0, 0, 4, 4, 4, 16, 8) + byteArrayOf(0, 0, 0, 1) + ByteArray(8) { 9 }
        val index = ByteArray(300_000) { (it * 7).toByte() } + footer                              // many pieces: the tail is the last 28 B
        assertTrue(WowClientDownloader.indexOk(body(index).tail, md5(footer)))
        assertFalse(WowClientDownloader.indexOk(body(index.copyOf(index.size - 1)).tail, md5(footer)))
        assertFalse(WowClientDownloader.indexOk(body("<html>portal</html>".toByteArray()).tail, md5(footer)))
    }

    private fun z(b: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(b) } }.toByteArray()

    /** a BLTE with a chunk table: each chunk its mode byte + body */
    private fun blte(vararg chunks: ByteArray): ByteArray {
        val headerSize = 12 + 24 * chunks.size
        val b = java.nio.ByteBuffer.allocate(headerSize + chunks.sumOf { it.size })
        b.put("BLTE".toByteArray()).putInt(headerSize).putInt(0x0F000000 or chunks.size)
        for (c in chunks) b.putInt(c.size).putInt(0).put(ByteArray(16))
        for (c in chunks) b.put(c)
        return b.array()
    }

    private fun decode(b: ByteArray) = java.io.ByteArrayOutputStream().also { WowClientDownloader.blteDecode(b.inputStream(), it) }.toByteArray()

    @Test fun blteDecodesChunkByChunkFromAStream() {
        val a = ByteArray(200_000) { (it % 251).toByte() }
        val b = "second chunk".toByteArray()
        val c = ByteArray(70_000) { (it % 13).toByte() }
        val nested = blte(byteArrayOf('N'.code.toByte()) + "inner".toByteArray())
        val data = blte(byteArrayOf('Z'.code.toByte()) + z(a), byteArrayOf('N'.code.toByte()) + b,
            byteArrayOf('Z'.code.toByte()) + z(c), byteArrayOf('F'.code.toByte()) + nested)
        assertTrue(decode(data).contentEquals(a + b + c + "inner".toByteArray()))
        // no chunk table: one chunk, the rest of the stream
        val bare = "BLTE".toByteArray() + ByteArray(4) + byteArrayOf('Z'.code.toByte()) + z(a)
        assertTrue(decode(bare).contentEquals(a))
    }

    @Test fun aCutBlteStreamIsAnError() {
        val a = ByteArray(100_000) { (it % 97).toByte() }
        val data = blte(byteArrayOf('Z'.code.toByte()) + z(a), byteArrayOf('N'.code.toByte()) + "tail".toByteArray())
        for (cut in listOf(data.size - 2, data.size / 2, 30)) {
            try { decode(data.copyOf(cut)); org.junit.Assert.fail("cut at $cut decoded") } catch (_: java.io.IOException) {}
        }
        try { decode("<html>sign in</html>".toByteArray()); org.junit.Assert.fail("a portal decoded") } catch (_: IllegalStateException) {}
    }

    @Test fun aBigClientFileDecodesWithoutBeingHeld() {
        // 3 x 8 MB 'N' chunks (24 MB, bigger than any byte array the old reader could take next to the game) from a stream made on the
        // fly: decoded straight into an MD5, nothing held
        val chunk = 8L shl 20
        val header = java.nio.ByteBuffer.allocate(12 + 24 * 3).apply {
            put("BLTE".toByteArray()).putInt(12 + 24 * 3).putInt(0x0F000003)
            repeat(3) { putInt((chunk + 1).toInt()).putInt(chunk.toInt()).put(ByteArray(16)) }
        }.array()
        fun piece() = java.io.SequenceInputStream(byteArrayOf('N'.code.toByte()).inputStream(), Zeros(chunk))
        val input = java.io.SequenceInputStream(java.util.Collections.enumeration(listOf(header.inputStream(), piece(), piece(), piece())))
        var n = 0L
        val count = object : java.io.OutputStream() {
            override fun write(b: Int) { n++ }
            override fun write(b: ByteArray, off: Int, len: Int) { n += len }
        }
        WowClientDownloader.blteDecode(input, count)
        assertEquals(3 * chunk, n)
    }

    /** [n] zero bytes, made as they are read */
    private class Zeros(private var n: Long) : java.io.InputStream() {
        override fun read(): Int = if (n-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (n <= 0) return -1
            val k = minOf(len.toLong(), n).toInt(); java.util.Arrays.fill(b, off, off + k, 0); n -= k; return k
        }
    }
}
