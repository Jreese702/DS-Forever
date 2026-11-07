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
}
