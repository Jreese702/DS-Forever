package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #571 (1.0.37): a fresh install never got past "game art". The folder had never run (no Data/data indexes, no Config.wtf), but
 * the one-time first game run fired only with the direct install's marker, so the art failed "before Play" and held Play. Now the
 * folder itself says whether the game has run, whichever path made it; fixture folders for each case.
 */
class FirstRunFolderTest {
    private val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Product!STRING:0"
    private val row = "us|1|2582289d81e380163c7a13472df4cb81|6bff2e685995b214c2ff5d08f3344542|wowf"

    /** a game folder as an install leaves it: .build.info, Data/indices (the CDN's archive indexes), Data/data with [idx] buckets */
    private fun folder(idx: Int = 0, marker: Boolean = false, wtfDir: Boolean = false, configWtf: Boolean = false, indices: Int = 3): File {
        val root = Files.createTempDirectory("wow571").toFile()
        File(root, ".build.info").writeText("$head\n$row\n")
        File(root, "_forever_").mkdirs(); File(root, "_forever_/Wow-arm64.exe").writeText("exe")
        val ind = File(root, "Data/indices").apply { mkdirs() }
        repeat(indices) { File(ind, "%032x.index".format(it)).writeBytes(ByteArray(8)) }
        val data = File(root, "Data/data").apply { mkdirs() }
        repeat(idx) { File(data, "%02x00000001.idx".format(it)).writeBytes(ByteArray(64)) }
        File(data, "data.000").writeBytes(ByteArray(16))
        if (marker) File(root, DataStaleness.STREAMED).writeBytes(ByteArray(0))
        if (wtfDir || configWtf) File(root, "_forever_/WTF").mkdirs()
        if (configWtf) File(root, "_forever_/WTF/Config.wtf").writeText("SET portal \"us\"\n")
        return root
    }

    @Test fun neverRunWithNoMarkerGetsItsFirstRun() {
        // the #571 folder: Choose a folder / a copy / an upstream install, no marker, no indexes, no WTF
        val root = folder()
        assertEquals(DataStaleness.Run.NEVER, DataStaleness.runState(root))
        assertTrue(DataStaleness.firstRun(root))                           // before the fix: false (no marker) -> the art failed
        assertFalse(DataStaleness.indexesMade(root))
        val t = DataStaleness.traces(root)
        assertTrue(t, t.startsWith("never: index buckets 0/16, Data/indices 3 files, WTF no, Config.wtf no, marker no"))
        // it streams like a direct install: marked before its first run, once
        assertTrue(DataStaleness.adoptStreamed(root))
        assertTrue(File(root, DataStaleness.STREAMED).exists())
        assertFalse(DataStaleness.adoptStreamed(root))
        assertTrue(DataStaleness.firstRun(root))
    }

    @Test fun neverRunWithTheMarkerStillGetsItsFirstRun() {
        // Install directly: the marker, no indexes (the old rule's only case)
        val root = folder(marker = true, indices = 0)
        assertEquals(DataStaleness.Run.NEVER, DataStaleness.runState(root))
        assertTrue(DataStaleness.firstRun(root))
        assertFalse(DataStaleness.adoptStreamed(root))                     // already marked
    }

    @Test fun aFolderThatRanKeepsItsOldBehaviour() {
        val ran = folder(idx = 16, wtfDir = true, configWtf = true)
        assertEquals(DataStaleness.Run.RAN, DataStaleness.runState(ran))
        assertFalse(DataStaleness.firstRun(ran))
        assertFalse(DataStaleness.adoptStreamed(ran))
        assertFalse(File(ran, DataStaleness.STREAMED).exists())
        // a direct install after its first run, and a PC copy (indexes, no WTF): neither runs again
        assertFalse(DataStaleness.firstRun(folder(idx = 16, marker = true, configWtf = true)))
        assertFalse(DataStaleness.firstRun(folder(idx = 16)))
        assertTrue(DataStaleness.traces(ran).startsWith("ran: index buckets 16/16"))
    }

    @Test fun aPartlyRunFolderGetsItsFirstRunAgain() {
        // the first run stopped part-way: some buckets and a WTF folder
        val part = folder(idx = 5, wtfDir = true)
        assertEquals(DataStaleness.Run.PARTIAL, DataStaleness.runState(part))
        assertTrue(DataStaleness.firstRun(part))
        assertFalse(DataStaleness.adoptStreamed(part))                     // it has local indexes: never marked streamed by us
        // DS Forever wrote Config.wtf before a start that never made the indexes: Config.wtf alone is not a run
        val cfgOnly = folder(configWtf = true)
        assertEquals(DataStaleness.Run.PARTIAL, DataStaleness.runState(cfgOnly))
        assertTrue(DataStaleness.firstRun(cfgOnly))
        // the direct install's marker with a part-made set (the old rule: fingerprint not empty -> no first run, the art failed)
        assertTrue(DataStaleness.firstRun(folder(idx = 3, marker = true)))
    }

    @Test fun anUnreadableFolderIsNeverTakenForANewOne() {
        val root = folder(idx = 16, configWtf = true)
        File(root, ".build.info").delete()
        assertEquals(DataStaleness.Run.UNREADABLE, DataStaleness.runState(root))
        assertFalse(DataStaleness.firstRun(root))
        assertFalse(DataStaleness.adoptStreamed(root))
        assertFalse(DataStaleness.firstRun(File(root, "nothing-here")))
    }

    // ------------------------------------------------------------------ the start checks (SetupFlow)

    private val base = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, loginSaved = true)

    @Test fun anArtFailureOnANeverRunFolderNeverHoldsPlay() {
        val failed = base.copy(art = SetupFlow.Art.FAILED, artWhy = "the game files could not be opened (CascLib error 2)")
        assertFalse(SetupFlow.screen(failed).play!!.enabled)                // a folder that ran: still held (unchanged)
        assertTrue(SetupFlow.screen(failed.copy(firstRun = true)).play!!.enabled)
        assertEquals("art", SetupFlow.screen(failed.copy(firstRun = true)).prompt?.id)   // Try again stays (it runs the first run)
        val idle = base.copy(firstRun = true)
        assertTrue(SetupFlow.screen(idle).play!!.enabled)                  // Play leads to the first run
        assertEquals(SetupFlow.FIRST_RUN_LINE, SetupFlow.screen(idle).line)
        assertTrue(SetupFlow.checks(idle).any { it.label == "Game art: after the game's first run" && it.mark == SetupFlow.Mark.WAIT })
        assertFalse(SetupFlow.screen(base).play!!.enabled)                 // IDLE on a folder that ran: waits for the art (unchanged)
        // the art building after the first run holds Play as before; READY plays
        assertFalse(SetupFlow.screen(idle.copy(art = SetupFlow.Art.RUNNING, artPercent = 10)).play!!.enabled)
        assertTrue(SetupFlow.screen(base.copy(art = SetupFlow.Art.READY)).play!!.enabled)
        // the chooser (Local / Streaming) still waits for the art
        assertFalse(SetupFlow.checksPass(idle))
        assertTrue(SetupFlow.checksPass(base.copy(art = SetupFlow.Art.READY)))
    }

    @Test fun theGameFolderAndLoginChecksStillGate() {
        val fr = base.copy(firstRun = true, art = SetupFlow.Art.FAILED, artWhy = "x")
        // Game folder: not found, another game, no file access: Play held and the folder / storage question first
        val none = fr.copy(folderFound = false)
        assertFalse(SetupFlow.screen(none).play!!.enabled); assertEquals("folder", SetupFlow.screen(none).prompt?.id)
        val wrong = fr.copy(wrongGame = true, product = "wow")
        assertFalse(SetupFlow.screen(wrong).play!!.enabled); assertEquals("folder", SetupFlow.screen(wrong).prompt?.id)
        val noAccess = fr.copy(storage = false)
        assertFalse(SetupFlow.screen(noAccess).play!!.enabled); assertEquals("storage", SetupFlow.screen(noAccess).prompt?.id)
        // busy (the first run itself launching), the app update pop-up: Play held
        assertFalse(SetupFlow.screen(fr.copy(busy = "Preparing components...")).play!!.enabled)
        assertFalse(SetupFlow.screen(fr.copy(appUpdate = "v1.0.38")).play!!.enabled)
        // Battle.net login: the prompt and its row as before (asked before the real Play; the first run signs in nowhere)
        val noLogin = base.copy(firstRun = true, loginSaved = false)
        assertEquals("login", SetupFlow.screen(noLogin).prompt?.id)
        assertTrue(SetupFlow.checks(noLogin).any { it.label == "Battle.net login: not saved" && it.mark == SetupFlow.Mark.WAIT })
        assertTrue(SetupFlow.checks(base.copy(firstRun = true)).any { it.label == "Battle.net login: saved" && it.mark == SetupFlow.Mark.OK })
        assertTrue(SetupFlow.checks(fr).any { it.label == "Game folder: /storage/emulated/0/WoW Forever" && it.mark == SetupFlow.Mark.OK })
    }
}
