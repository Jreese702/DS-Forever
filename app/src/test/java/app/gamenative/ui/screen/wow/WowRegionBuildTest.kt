package app.gamenative.ui.screen.wow

import com.jrees.wowcompanion.WowProduct
import java.io.File
import java.nio.file.Files
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The user, 2026-11-11: regions fix - the region the player picked decides the build checked, updated and written to .build.info
 * (regions patch on different days). A local server plays the patch server's versions / cdns; no network, no game data.
 */
class WowRegionBuildTest {
    private val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|" +
        "CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|" +
        "KeyRing!HEX:16|Product!STRING:0"
    private val usRow = "us|1|usbuild|uscdn|inst|100|tpr/wow|us.cdn.blizzard.com level3.blizzard.com|http://us.cdn.blizzard.com/?maxhosts=4|" +
        "Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?||2026-11-01T10:00:00Z|1.60.1.70888||wowf"

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private var versions = ""

    @Before fun setUp() {
        root = Files.createTempDirectory("dsf-region").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/patch/wowf/versions" -> MockResponse().setBody(versions)
                "/patch/wowf/cdns" -> MockResponse().setBody("Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n" +
                    "us|tpr/wow|us.cdn.blizzard.com|http://us.cdn.blizzard.com/?maxhosts=4|tpr/configs/data\n" +
                    "eu|tpr/wow|eu.cdn.blizzard.com|http://eu.cdn.blizzard.com/?maxhosts=4|tpr/configs/data\n")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        WowClientDownloader.patchUrlFor = { "http://127.0.0.1:${server.port}/patch/${it.code}" }
        File(root, ".build.info").writeText("$head\n$usRow\n")
    }

    @After fun tearDown() {
        WowClientDownloader.patchUrlFor = { it.patchUrl }
        server.shutdown()
        root.deleteRecursively()
    }

    private fun versionsOf(vararg rows: String) =
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\n" +
            "## seqn = 1\n" + rows.joinToString("\n") + "\n"

    @Test fun thePickedRegionsRowIsChecked() {
        versions = versionsOf("us|usbuild|uscdn||70888|1.60.1.70888|p", "eu|eubuild|eucdn||70862|1.60.1.70862|p")
        val us = WowClientDownloader.checkVersion(root)!!
        assertFalse(us.isOutdated)
        assertEquals("us", us.region)
        val eu = WowClientDownloader.checkVersion(root, "eu")!!
        assertTrue(eu.isOutdated)                            // a US-made install played on EU gets EU's build (a downgrade on patch day)
        assertEquals("1.60.1.70862", eu.remoteVersion)
        assertEquals("eubuild", eu.remoteBuildConfig)
        assertEquals("eu", eu.region)
        assertEquals(listOf("eu.cdn.blizzard.com"), eu.remoteCdnHosts)
        assertEquals("http://eu.cdn.blizzard.com/?maxhosts=4", eu.remoteCdnServers)
    }

    @Test fun aPickWithNoRowFallsBackToTheInstallsOwn() {
        versions = versionsOf("us|usbuild|uscdn||70888|1.60.1.70888|p")
        val tw = WowClientDownloader.checkVersion(root, "tw")!!       // no hard failure: the install's own row
        assertEquals("us", tw.region)
        assertFalse(tw.isOutdated)
    }

    @Test fun rowPickOrder() {
        val rows = listOf(mapOf("Region" to "us", "V" to "1"), mapOf("Region" to "eu", "V" to "2"), mapOf("Region" to "kr", "V" to "3"))
        assertEquals("2", WowClientDownloader.remoteRow(rows, "Region", "EU", "kr")!!["V"])
        assertEquals("3", WowClientDownloader.remoteRow(rows, "Region", "tw", "kr")!!["V"])        // the pick's row missing: the install's
        assertEquals("1", WowClientDownloader.remoteRow(rows, "Region", "tw", "cn")!!["V"])        // then us's
        assertEquals("1", WowClientDownloader.remoteRow(rows, "Region", null, null)!!["V"])
        assertEquals("3", WowClientDownloader.remoteRow(rows.drop(1).reversed(), "Region", "tw", "cn")!!["V"])   // no us row: the first
    }

    @Test fun anotherRegionsBuildRewritesBranchTagsAndCdn() {
        val target = WowClientDownloader.VersionCheckResult(
            localVersion = "1.60.1.70888", remoteVersion = "1.60.1.70862", isOutdated = true, remoteBuildConfig = "eubuild",
            remoteCdnConfig = "eucdn", remoteCdnHosts = listOf("eu.cdn.blizzard.com"), remoteCdnPath = "tpr/wow", product = WowProduct.LAUNCH,
            remoteCdnServers = "http://eu.cdn.blizzard.com/?maxhosts=4", region = "eu",
        )
        val raw = "$head\r\n$usRow\r\n"
        val out = WowClientDownloader.withBuild(raw, target, mapOf("install" to listOf("ck", "newinst"), "install-size" to listOf("1", "200")))
        assertTrue(out.endsWith("\r\n"))
        val row = WowProduct.parse(out).single()
        assertEquals("eu", row["Branch"])
        assertEquals("Windows arm64 EU? enUS speech?:Windows arm64 EU? enUS text?", row["Tags"])
        assertEquals("eu.cdn.blizzard.com", row["CDN Hosts"])
        assertEquals("http://eu.cdn.blizzard.com/?maxhosts=4", row["CDN Servers"])
        assertEquals("eubuild", row["Build Key"])
        assertEquals("1.60.1.70862", row["Version"])
        assertEquals("newinst", row["Install Key"])
        assertEquals("200", row["IM Size"])
    }

    @Test fun noRegionOrBlankServersKeepWhatIsThere() {
        val target = WowClientDownloader.VersionCheckResult(
            localVersion = "1.60.1.70870", remoteVersion = "1.60.1.70888", isOutdated = true, remoteBuildConfig = "usbuild2",
            remoteCdnConfig = "uscdn", remoteCdnHosts = listOf("us.cdn.blizzard.com"), product = WowProduct.LAUNCH,
            remoteCdnServers = "", region = null,
        )
        val row = WowProduct.parse(WowClientDownloader.withBuild("$head\n$usRow\n", target)).single()
        assertEquals("us", row["Branch"])
        assertEquals("http://us.cdn.blizzard.com/?maxhosts=4", row["CDN Servers"])       // never emptied (an empty one crashes the client)
        assertEquals("Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?", row["Tags"])
        assertEquals("usbuild2", row["Build Key"])
    }

    @Test fun regionTags() {
        assertEquals("Windows arm64 TW? enUS speech?:Windows arm64 TW? enUS text?",
            WowClientDownloader.regionTags("Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?", "tw"))
        assertEquals("Windows arm64 KR? enUS text?", WowClientDownloader.regionTags("Windows arm64 EU? enUS text?", "kr"))
        assertEquals("Windows arm64 enUS", WowClientDownloader.regionTags("Windows arm64 enUS", "eu"))
        assertEquals("", WowClientDownloader.regionTags("", "eu"))
    }
}
