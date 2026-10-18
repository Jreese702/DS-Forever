package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #12 ("dont load"), off-device: the game's life in DS Forever's own log, the launcher's note, DXVK's state cache */
class GameLifeTest {

    @Test fun theEndInWords() {
        assertEquals("closed normally", GameLife.describe(0))
        assertEquals("was killed (signal 9: Android may have needed the memory)", GameLife.describe(137))   // 128 + 9
        assertEquals("was killed (signal 9: Android may have needed the memory)", GameLife.describe(-9))
        assertEquals("crashed (signal 11)", GameLife.describe(139))
        assertEquals("ended with error status 3", GameLife.describe(3))
        assertFalse(GameLife.abnormal(0)); assertTrue(GameLife.abnormal(137))
        assertEquals("12 min", GameLife.minutes(12 * 60_000L + 5_000)); assertEquals("40 s", GameLife.minutes(40_000))
    }

    @Test fun memAvailableFromMeminfo() {
        val f = File(Files.createTempDirectory("mem").toFile(), "meminfo").apply {
            writeText("MemTotal:       11536228 kB\nMemFree:          301234 kB\nMemAvailable:    1572864 kB\n")
        }
        assertEquals(1536L, GameLife.memAvailableMiB(f))
        assertNull(GameLife.memAvailableMiB(File("/no/such/meminfo")))
    }

    @Test fun theLifeGoesToTheRollingLogAndAnAbnormalEndLeavesTheNote() {
        DiagLog.useDir(Files.createTempDirectory("diag").toFile())
        val t0 = 1_000_000_000_000L
        GameLife.started(t0)
        GameLife.inWorld(t0 + 45_000)
        GameLife.inWorld(t0 + 50_000)                                  // only the first
        GameLife.ended(137, t0 + 12 * 60_000L)
        GameLife.ended(137, t0 + 13 * 60_000L)                         // only once
        val lines = DiagLog.lines(60 * 60_000L, t0 + 13 * 60_000L)
        assertTrue(lines.any { it.endsWith("game: started") })
        assertEquals(1, lines.count { it.contains("game: in the world after 45 s") })
        val end = lines.single { it.contains("game: ended:") }
        assertTrue(end, end.contains("was killed (signal 9") && end.contains("(status 137) after 12 min") && end.contains("MemAvailable") && end.contains("indoor maps:"))
        assertEquals("The game was killed (signal 9: Android may have needed the memory) after 12 min. If that wasn't you, a problem report helps us find out why.", GameLife.note)
        GameLife.started(t0 + 20 * 60_000L)                            // a new game: the note goes
        assertNull(GameLife.note)
        GameLife.ended(0, t0 + 21 * 60_000L)                           // a normal close: no note
        assertNull(GameLife.note)
    }

    @Test fun theNoteIsTouchOnly() {
        val s = SetupFlow.screen(SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true,
            gameClosed = "The game crashed (signal 11) after 3 min."))
        val p = s.prompt!!
        assertEquals("The game closed", p.title)
        assertEquals(listOf(SetupFlow.Action.REPORT, SetupFlow.Action.GAME_CLOSED_OK), p.buttons.map { it.action })
        assertTrue(p.buttons.none(SetupFlow::padTarget))               // neither is the cursor's
        assertTrue(SetupFlow.padTarget(s.play!!))
    }

    @Test fun dxvksStateCacheGetsAWindowsPath() {
        val env = DxvkLogs.ENV.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("E:/dxvk-cache", env["DXVK_STATE_CACHE_PATH"])      // not the Unix path the Windows DLL could never open
        assertEquals(File("/data/x/storage/dxvk-cache"), DxvkLogs.cacheDir(File("/data/x")))
    }
}
