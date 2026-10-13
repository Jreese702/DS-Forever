package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Launch support, off-device: which client a game folder runs, from its .build.info (the beta wow_classic_beta until 21 Oct 2026, the
 * launch client wowf from 4 Nov 2026). The rows are shaped like the launcher's real .build.info; no game data.
 */
class WowProductTest {
    private val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|" +
        "CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|" +
        "KeyRing!HEX:16|Product!STRING:0"

    private fun row(product: String, active: Int, version: String = "1.60.1.70338", branch: String = "us") =
        "$branch|$active|bk$product|ck||0|tpr/wow|level3.blizzard.com us.cdn.blizzard.com|||enUS text?||$version||$product"

    private fun info(vararg rows: String) = (listOf(head) + rows).joinToString("\n") + "\n"

    private val during = LocalDate.of(2026, 10, 13)
    private val after = LocalDate.of(2026, 11, 4)

    private lateinit var root: File

    @Before fun setUp() { root = Files.createTempDirectory("dsf-product").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun pick(text: String, today: LocalDate = during, installed: Set<WowProduct> = WowProduct.values().toSet()) =
        WowProduct.choose(WowProduct.parse(text), { it in installed }, today)

    @Test fun betaOnlyFolderRunsTheBeta() {
        val c = pick(info(row("wow_classic_beta", 1)), today = after)!!
        assertEquals(WowProduct.BETA, c.product)
        assertEquals("1.60.1.70338", c.row["Version"])
    }

    @Test fun launchOnlyFolderRunsTheLaunchClient() {
        val c = pick(info(row("wowf", 1, "1.60.1.70245")))!!
        assertEquals(WowProduct.LAUNCH, c.product)
        assertEquals("bkwowf", c.row["Build Key"])
    }

    @Test fun retailRowFirstIsSkipped() {
        // a folder copied from a PC: retail's row first, both active (the Reddit report the old order fixed)
        val c = pick(info(row("wow", 1, "12.0.5.70000"), row("wowf", 1)))!!
        assertEquals(WowProduct.LAUNCH, c.product)
    }

    @Test fun activeRowWinsOverAnInactiveOne() {
        val c = pick(info(row("wow_classic_beta", 0), row("wowf", 1)))!!
        assertEquals(WowProduct.LAUNCH, c.product)
        val d = pick(info(row("wowf", 0), row("wow_classic_beta", 1)), today = after)!!
        assertEquals(WowProduct.BETA, d.product)
    }

    @Test fun bothActiveAndInstalledFollowTheCalendar() {
        val text = info(row("wowf", 1), row("wow_classic_beta", 1))
        assertEquals(WowProduct.BETA, pick(text, today = during)!!.product)
        assertEquals(WowProduct.BETA, pick(text, today = WowProduct.BETA_LAST_DAY)!!.product)
        assertEquals(WowProduct.LAUNCH, pick(text, today = WowProduct.BETA_LAST_DAY.plusDays(1))!!.product)
        assertEquals(WowProduct.LAUNCH, pick(text, today = after)!!.product)
    }

    @Test fun bothActiveOnlyOneExeInstalledRunsThatOne() {
        val text = info(row("wow_classic_beta", 1), row("wowf", 1))
        assertEquals(WowProduct.BETA, pick(text, today = after, installed = setOf(WowProduct.BETA))!!.product)
        assertEquals(WowProduct.LAUNCH, pick(text, today = during, installed = setOf(WowProduct.LAUNCH))!!.product)
    }

    @Test fun noSupportedRowKeepsTheOldOrderAndNamesNoProduct() {
        val c = pick(info(row("wow", 0, "12.0.5.1"), row("wow_classic_era", 1, "1.15.9.1")))!!
        assertNull(c.product)
        assertEquals("wow_classic_era", c.row["Product"])
        assertNull(pick(head + "\n"))
    }

    @Test fun shortRowsArePaddedAndCrlfIsRead() {
        val rows = WowProduct.parse("Active!DEC:1|Product!STRING:0|Version!STRING:0\r\n1|wowf\r\n")
        assertEquals("", rows.single()["Version"])
        assertEquals(WowProduct.LAUNCH, WowProduct.choose(rows, { true }, during)!!.product)
    }

    @Test fun productFactsMatchBlizzardsProductConfig() {
        assertEquals("_forever_", WowProduct.LAUNCH.flavorDir)
        assertEquals("_classic_beta_", WowProduct.BETA.flavorDir)
        assertEquals("http://us.patch.battle.net:1119/wowf", WowProduct.LAUNCH.patchUrl)
        assertEquals("http://us.patch.battle.net:1119/wow_classic_beta", WowProduct.BETA.patchUrl)
        assertEquals(WowProduct.LAUNCH, WowProduct.forCode("wowf"))
        assertNull(WowProduct.forCode("wow_classic"))
    }

    @Test fun portalIsTestForTheBetaAndTheRegionForLaunch() {
        assertEquals("test", WowProduct.BETA.portalFor(mapOf("Branch" to "eu")))
        assertEquals("eu", WowProduct.LAUNCH.portalFor(mapOf("Branch" to "EU")))
        assertEquals("us", WowProduct.LAUNCH.portalFor(null))
    }

    @Test fun folderOnDiskChoosesByInstalledExeWhateverItsCase() {
        File(root, ".build.info").writeText(info(row("wow_classic_beta", 1), row("wowf", 1)))
        File(root, "_forever_").mkdirs()
        File(root, "_forever_/WOW-ARM64.EXE").writeText("x")
        // only the launch exe is there: it runs even during the beta, and its exe is found as it is named on disk
        assertEquals(WowProduct.LAUNCH, WowProduct.of(root, during))
        assertEquals("WOW-ARM64.EXE", WowProduct.LAUNCH.exe(root).name)
        assertEquals(File(root, "_forever_"), WowProduct.flavorDir(root))
        assertEquals("WowB-ARM64.exe", WowProduct.BETA.exe(root).name)        // not installed: the expected name
    }

    @Test fun folderWithoutBuildInfoExpectsTheClientOfTheDay() {
        assertNull(WowProduct.choose(root, during))
        assertEquals(WowProduct.BETA, WowProduct.of(root, during))
        assertEquals(WowProduct.LAUNCH, WowProduct.of(root, after))
    }

    @Test fun gameLogFollowsTheFoldersClient() {
        File(root, ".build.info").writeText(info(row("wowf", 1)))
        assertEquals("${root.path}/_forever_/Logs/General.log", GamePaths.log(root.path))
        assertEquals("/g/_classic_beta_/Logs/General.log", GamePaths.log("/g/", WowProduct.BETA))
        assertTrue(GamePaths.follow(true, root.path, "/located/log").endsWith("/_forever_/Logs/General.log"))
    }

    @Test fun gameFilesReadsTheSameRow() {
        File(root, ".build.info").writeText(info(row("wow", 1, "12.0.5.1"), row("wowf", 1, "1.60.1.70245")))
        assertEquals("1.60.1.70245", GameFiles.buildRow(root)!!["Version"])
        assertEquals("wowf" to "bkwowf", GameFiles.activeBuild(root))
    }
}
