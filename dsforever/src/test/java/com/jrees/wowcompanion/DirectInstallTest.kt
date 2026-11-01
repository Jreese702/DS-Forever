package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectInstallTest {
    private val missing = SetupFlow.Inputs(path = "/g", folderFound = false)

    @Test fun aMissingGameOffersBoth() {
        assertEquals(listOf(SetupFlow.Action.INSTALL_GAME, SetupFlow.Action.CHOOSE_FOLDER), SetupFlow.screen(missing).prompt!!.buttons.map { it.action })
    }

    @Test fun anotherGameOnlyOffersTheFolder() {
        val p = SetupFlow.screen(missing.copy(folderFound = true, wrongGame = true, product = "wow")).prompt!!
        assertEquals(listOf(SetupFlow.Action.CHOOSE_FOLDER), p.buttons.map { it.action })
    }

    @Test fun theInstallShowsItsProgressAndNoButtons() {
        val p = SetupFlow.screen(missing.copy(busy = "Fetching")).prompt!!
        assertEquals("Fetching", p.text)
        assertTrue(p.buttons.isEmpty())
    }

    @Test fun theInstallNeedsSpaceAndWifi() {
        assertNull(SetupFlow.installRefusal(SetupFlow.INSTALL_FREE_BYTES, false))
        assertNotNull(SetupFlow.installRefusal(SetupFlow.INSTALL_FREE_BYTES - 1, false))
        assertNotNull(SetupFlow.installRefusal(SetupFlow.INSTALL_FREE_BYTES, true))
    }

    @Test fun theFirstRunIsADirectInstallWithoutLocalIndexes() {
        val root = java.nio.file.Files.createTempDirectory("direct").toFile()
        try {
            java.io.File(root, "Data/data").mkdirs()
            assertTrue(!DataStaleness.firstRun(root))                     // a copied folder is never one
            java.io.File(root, DataStaleness.STREAMED).writeText("")
            assertTrue(DataStaleness.firstRun(root))
            java.io.File(root, "Data/data/0000000001.idx").writeText("x")
            assertTrue(!DataStaleness.firstRun(root))                     // the game has run: the art can open it
        } finally { root.deleteRecursively() }
    }

    @Test fun aFreshBuildInfoIsTheLaunchClient() {
        val text = WowSwitch.freshBuildInfo(WowSwitch.Remote(version = "1.60.1.70707", buildConfig = "b", cdnConfig = "c",
            cdnHosts = listOf("h1", "h2"), cdnPath = "tpr/wow", cdnServers = "s", keyRing = "k"), "us")
        val row = WowProduct.parse(text).single()
        assertEquals("wowf", row["Product"])
        assertEquals("1", row["Active"])
        assertEquals("h1 h2", row["CDN Hosts"])
        assertEquals("us", row["Branch"])
        assertEquals("Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?", row["Tags"])
    }
}
