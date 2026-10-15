package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-art-progress / b-art-update-size (DS Forever 1.0.8), off-device: the tables' progress line instead of "checking" */
class TablesProgressTest {
    private val MB = 1L shl 20

    @Test fun theLine() {
        assertEquals("Downloading the game's tables: 87 / 141 MB", TablesProgress.line(87 * MB, 141 * MB, metered = false))
        assertEquals("Downloading the game's tables: 0 / 141 MB (on mobile data: Wi-Fi is better)", TablesProgress.line(0, 141 * MB, metered = true))
        assertEquals(1, TablesProgress.mb(1))            // a started file never reads 0 MB
    }

    @Test fun theCountCoversEveryTableOfTheOpen() {
        var t = 0L
        val lines = ArrayList<String>()
        val c = TablesProgress.Counter(metered = false, emit = { lines += it }, clock = { t })
        c.start(141 * MB)                                  // ENCODING
        t += 1000; c.progress(87 * MB)
        assertEquals("Downloading the game's tables: 87 / 141 MB", lines.last())
        c.finish()
        c.start(39 * MB)                                   // ROOT: the total grows as its size is known
        t += 1000; c.progress(12 * MB)
        assertEquals("Downloading the game's tables: 153 / 180 MB", lines.last())
        c.finish()
        assertEquals("Downloading the game's tables: 180 / 180 MB", lines.last())
    }

    @Test fun linesAreThrottled() {
        var t = 0L
        var n = 0
        val c = TablesProgress.Counter(metered = false, emit = { n++ }, clock = { t })
        c.start(100 * MB)                                  // 1
        for (i in 1..50) { t += 10; c.progress(i * MB) }    // 500 ms: 2 more at most
        assertTrue("$n lines", n <= 3)
    }

    @Test fun theSetupShowsItInsteadOfCheckingOrZeroPercent() {
        val what = "Downloading the game's tables: 87 / 141 MB"
        val i = SetupFlow.Inputs(path = "/g", folderFound = true, loginSaved = true, art = SetupFlow.Art.RUNNING, artWhat = what)
        assertEquals(what, SetupFlow.screen(i).line)
        assertTrue(SetupFlow.checks(i).any { it.label == "Game art: $what" })
        val steps = i.copy(artWhat = "the kit", artPercent = 40)
        assertEquals("Preparing the game art: 40%", SetupFlow.screen(steps).line)
    }

    @Test fun theUpdatePopUpSaysWiFi() {
        val p = SetupFlow.screen(SetupFlow.Inputs(path = "/g", folderFound = true, loginSaved = true, art = SetupFlow.Art.READY,
            gameOutdated = "1.60.1.70400")).prompt!!
        assertEquals("client", p.id)
        assertTrue(p.text.contains("Update on Wi-Fi"))
    }
}
