package app.gamenative.ui.screen.wow

import com.jrees.wowcompanion.WowProduct
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
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
