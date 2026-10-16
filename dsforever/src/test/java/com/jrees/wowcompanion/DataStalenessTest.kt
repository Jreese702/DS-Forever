package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** issues #8 / #9 (the user's pick: say plainly when the game folder's Data is out of date), off-device */
class DataStalenessTest {

    @Test fun theVerdict() {
        assertNull(DataStaleness.verdict(10, 20))                      // not enough reads yet
        assertEquals(false, DataStaleness.verdict(40, 0))             // the Thor's full install: everything local
        assertEquals(false, DataStaleness.verdict(35, 5))             // a small game update: a few files from the CDN
        assertEquals(true, DataStaleness.verdict(3, 57))              // #9: nearly everything from the CDN
        assertEquals(true, DataStaleness.verdict(20, 20))             // half
    }

    @Test fun aRecopiedDataFolderChangesTheFingerprint() {
        val root = Files.createTempDirectory("wow").toFile()
        assertEquals("", DataStaleness.fingerprint(root))             // no Data/data at all
        val data = File(root, "Data/data").apply { mkdirs() }
        File(data, "0000000001.idx").writeBytes(ByteArray(100))
        File(data, "0100000002.idx").writeBytes(ByteArray(200))
        File(data, "data.000").writeBytes(ByteArray(10))               // archives do not count, only the indexes
        val before = DataStaleness.fingerprint(root)
        File(data, "data.001").writeBytes(ByteArray(10))
        assertEquals(before, DataStaleness.fingerprint(root))
        File(data, "0100000002.idx").writeBytes(ByteArray(300))        // the recopy's newer index
        assertNotEquals(before, DataStaleness.fingerprint(root))
    }

    @Test fun theSetupSaysItPlainlyAndPlayStays() {
        val msg = DataStaleness.message("1.60.1.70425")
        assertTrue(msg.contains("(1.60.1.70425)")); assertTrue(msg.contains("Copy the Data folder again from your PC"))
        val i = SetupFlow.Inputs(path = "/storage/emulated/0/Wow", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true, staleData = msg)
        val s = SetupFlow.screen(i)
        assertEquals("stale", s.prompt?.id)
        assertEquals("Your game folder is out of date", s.prompt?.title)
        assertEquals(listOf(SetupFlow.Action.STALE_OK), s.prompt?.buttons?.map { it.action })
        assertTrue(s.play!!.enabled)                                   // it may still start: Play is not taken away
        assertTrue(SetupFlow.checks(i).any { it.mark == SetupFlow.Mark.BAD && it.label.contains("out of date") })
        assertNull(SetupFlow.screen(i.copy(staleData = null)).prompt)   // a fine folder: nothing
        assertFalse(SetupFlow.screen(i.copy(folderFound = false)).prompt?.id == "stale")   // the folder question first
    }
}
