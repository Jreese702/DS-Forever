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

    @Test fun theIndexesAreMadeWithAllSixteenBuckets() {
        val root = java.nio.file.Files.createTempDirectory("direct").toFile()
        try {
            val data = java.io.File(root, "Data/data").apply { mkdirs() }
            for (b in 0 until 15) java.io.File(data, "%02x00000001.idx".format(b)).writeText("x")
            assertTrue(!DataStaleness.indexesMade(root))
            java.io.File(data, "0f00000001.idx").writeText("x")
            assertTrue(DataStaleness.indexesMade(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun theVfsKeysAreTheEncodingKeysOnly() {
        val config = "root = aa\nvfs-root = c0 e0\nvfs-root-size = 1 2\nvfs-1 = c1 e1\r\nvfs-1-size = 3 4\nvfs-583 = c2 e2\n"
        assertEquals(listOf("e0", "e1", "e2"), GameFiles.vfsKeys(config))
    }

    @Test fun theMainShortcutOpensTheChooser() {
        assertEquals(StartMode.Route.Chooser, StartMode.resolve(StartMode.MAIN_SHORTCUT.extra))
        val main = StartMode.Cell(StartMode.Mode.LOCAL, StartMode.Row.MAIN)
        assertEquals(main, StartMode.move(StartMode.Cell(StartMode.Mode.STREAM, StartMode.Row.SHORTCUT), StartMode.Dir.DOWN))
        assertEquals(main, StartMode.move(main, StartMode.Dir.RIGHT))
        assertEquals(StartMode.Row.SHORTCUT, StartMode.move(main, StartMode.Dir.UP).row)
        assertEquals(StartMode.Press.AddMain, StartMode.press(main))
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
