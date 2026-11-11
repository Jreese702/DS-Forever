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
