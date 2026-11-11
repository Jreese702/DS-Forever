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
}
