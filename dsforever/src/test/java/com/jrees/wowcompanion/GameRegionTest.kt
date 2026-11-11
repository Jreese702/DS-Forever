package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2026-11-07: the player's region (the login prompt's Region row) is the portal the game signs in to */
class GameRegionTest {
    private fun folder(config: String?): File {
        val root = Files.createTempDirectory("region").toFile()
        if (config != null) File(root, "_forever_/WTF").apply { mkdirs() }.resolve("Config.wtf").writeText(config)
        return root
    }
    private fun portal(root: File) = File(root, "_forever_/WTF/Config.wtf").readLines().first { it.startsWith("SET portal") }

    @Test fun aChosenRegionReplacesThePortal() {
        val root = folder("SET portal \"us\"\nSET agentUID \"wowf\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), "eu")
        assertEquals("SET portal \"eu\"", portal(root))
        assertEquals("eu", GameRegion.portalOf(root))
    }

    @Test fun noChoiceKeepsTheInstallsPortal() {
        val root = folder("SET portal \"US\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), null)
        assertEquals("SET portal \"US\"", portal(root))
    }

    @Test fun aNewFolderStartsOnTheChosenRegion() {
        val root = folder(null)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), "kr")
        assertEquals("SET portal \"kr\"", portal(root))
    }

    @Test fun theCountryGuess() {
        assertEquals("eu", GameRegion.guess("fr")); assertEquals("eu", GameRegion.guess("ES"))
        assertEquals("us", GameRegion.guess("MX")); assertEquals("kr", GameRegion.guess("KR")); assertEquals("tw", GameRegion.guess("HK"))
        assertTrue(GameRegion.ALL.map { it.code } == listOf("us", "eu", "kr", "tw"))
    }

    // the user, 2026-11-11: regions fix - the Middle East, Africa and the rest of Europe are EU; Macau is Taiwan's; the rest stays US
    @Test fun theWiderCountryTable() {
        listOf("SA", "AE", "IL", "EG", "ZA", "NG", "MA", "RS", "BA", "BY", "MD", "KZ", "GE", "CH", "NO", "TR", "RU").forEach {
            assertEquals(it, "eu", GameRegion.guess(it))
        }
        assertEquals("tw", GameRegion.guess("MO")); assertEquals("tw", GameRegion.guess("TW"))
        listOf("US", "CA", "BR", "AU", "NZ", "SG", "JP", "", "ZZ").forEach { assertEquals(it, "us", GameRegion.guess(it)) }
    }

    // regions fix: there is no tw.actual.battle.net; Taiwan signs in through kr and keeps Taiwan's build
    @Test fun taiwanSignsInThroughKorea() {
        assertEquals("kr", GameRegion.portal("tw")); assertEquals("eu", GameRegion.portal("EU")); assertEquals("us", GameRegion.portal("us"))
        val fresh = folder(null)
        GameConfigWtf.ensure(fresh, WowProduct.LAUNCH, mapOf("Branch" to "us"), "tw")
        assertEquals("SET portal \"kr\"", portal(fresh))
        val chosen = folder("SET portal \"us\"\n")
        GameConfigWtf.ensure(chosen, WowProduct.LAUNCH, mapOf("Branch" to "us"), "tw")
        assertEquals("SET portal \"kr\"", portal(chosen))
        // a Taiwan install with no choice made: its own Branch decides, and the portal is kr
        val own = folder(null)
        GameConfigWtf.ensure(own, WowProduct.LAUNCH, mapOf("Branch" to "tw"), null)
        assertEquals("SET portal \"kr\"", portal(own))
    }

    @Test fun aDeadTaiwanPortalIsReplacedEvenWithoutAChoice() {
        val root = folder("SET portal \"tw\"\nSET agentUID \"wowf\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "tw"), null)
        assertEquals("SET portal \"kr\"", portal(root))
        val again = File(root, "_forever_/WTF/Config.wtf").readText()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "tw"), "tw")       // a kr portal for a Taiwan pick stays
        assertEquals(again, File(root, "_forever_/WTF/Config.wtf").readText())
    }

    @Test fun theFoldersOwnRegion() {
        fun withBranch(root: File, branch: String) = root.also {
            File(it, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Version!STRING:0|Product!STRING:0\n$branch|1|1.60.1.70862|wowf\n")
        }
        assertEquals("tw", GameRegion.regionOf(withBranch(folder("SET portal \"kr\"\n"), "tw")))     // kr portal on a Taiwan build
        assertEquals("kr", GameRegion.regionOf(withBranch(folder("SET portal \"kr\"\n"), "kr")))
        assertEquals("eu", GameRegion.regionOf(withBranch(folder("SET portal \"eu\"\n"), "us")))     // the portal says the account's region
        assertEquals("tw", GameRegion.regionOf(withBranch(folder("SET portal \"tw\"\n"), "tw")))     // a dead portal: the Branch
        assertEquals("us", GameRegion.regionOf(withBranch(folder(null), "us")))
        assertEquals(null, GameRegion.regionOf(folder(null)))
    }
}
