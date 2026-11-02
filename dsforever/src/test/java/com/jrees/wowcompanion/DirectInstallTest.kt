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
        assertEquals(main, StartMode.moveShortcuts(StartMode.Cell(StartMode.Mode.STREAM, StartMode.Row.CARD), StartMode.Dir.DOWN))
        assertEquals(main, StartMode.moveShortcuts(main, StartMode.Dir.RIGHT))
        assertEquals(StartMode.Row.CARD, StartMode.moveShortcuts(main, StartMode.Dir.UP).row)
        assertEquals(StartMode.Press.AddMain, StartMode.pressShortcut(main))
        assertEquals(StartMode.Press.AddShortcut(StartMode.Mode.STREAM), StartMode.pressShortcut(StartMode.Cell(StartMode.Mode.STREAM, StartMode.Row.CARD)))
        assertEquals(StartMode.Press.OpenMenu, StartMode.press(StartMode.Cell(StartMode.Mode.LOCAL, StartMode.Row.FOOT)))
        assertEquals(StartMode.Press.OpenShortcuts, StartMode.press(StartMode.Cell(StartMode.Mode.STREAM, StartMode.Row.FOOT)))
    }

    @Test fun theMenuCursorGoesToTheNearestInItsDirection() {
        val places = listOf(Triple(1, 100f, 100f), Triple(2, 500f, 100f), Triple(3, 100f, 300f), Triple(4, 500f, 300f))
        assertEquals(2, StartMenu.nearest(places, 100f, 100f, 1, 0))
        assertEquals(3, StartMenu.nearest(places, 100f, 100f, 0, 1))
        assertEquals(4, StartMenu.nearest(places, 500f, 100f, 0, 1))
        assertNull(StartMenu.nearest(places, 100f, 100f, -1, 0))
        assertNull(StartMenu.nearest(places, 100f, 100f, 0, -1))
    }

    @Test fun theTimeLeftIsPerStepAndInTotal() {
        assertEquals("This step: about 9 min" to "Total: about 32 min", SetupFlow.times(SetupFlow.Step.INSTALL, 0.0))
        assertEquals("This step: about 10 min" to "Total: about 10 min", SetupFlow.times(SetupFlow.Step.ART, 600.0, 0.5f))
        assertEquals("This step: under a minute" to "Total: under a minute", SetupFlow.times(SetupFlow.Step.ART, 600.0, 0.99f))
        assertEquals(0.5f, SetupFlow.timeBar(SetupFlow.Step.INSTALL, 262.5), 0.01f)
        assertEquals(0.95f, SetupFlow.timeBar(SetupFlow.Step.INSTALL, 9999.0), 0.01f)
    }

    @Test fun aStepShowsOneCardAndNoStatusLine() {
        val p = SetupFlow.Progress("Installing", "Fetching", "This step: 1", "Total: 2")
        val s = SetupFlow.screen(SetupFlow.Inputs(path = "/g", folderFound = false, busy = "Fetching", progress = p))
        assertEquals("Fetching\nThis step: 1\nTotal: 2", s.prompt!!.text)
        assertEquals("", s.line)
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
