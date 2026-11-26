package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE READER RESUMES FROM ITS BYTE POSITION (the bottom screen comes straight back, 2026-11-25): a park PAUSES the same LogTail (its
 * offset, carry and assembler kept, its thread never quit) and the return resumes it - only what the add-on wrote while away is read.
 * A host made again starts its new LogTail at the old one's place ([LogTail.Place]) when it is the same log and the bytes before it are
 * still the same; another log, a shorter one or other bytes there (a client restart that wrote past it) read from byte 0 as before.
 */
class LogTailResumeTest {
    private val tail = byteArrayOf(10, 87, 67, 66, 49, 124, 57)
    private val place = LogTail.Place("/g/Logs/General.log", 5_000L, "WCB1|12|1/1|{\"type\":\"po", ByteArray(0), tail)

    @Test fun theSameFileResumesAtItsPlace() {
        assertEquals(5_000L, LogTail.resumeOffset(place, "/g/Logs/General.log", len = 9_000L, there = tail.copyOf()))
        assertEquals("nothing written since", 5_000L, LogTail.resumeOffset(place, "/g/Logs/General.log", len = 5_000L, there = tail.copyOf()))
    }

    @Test fun anotherPathAShorterFileOrOtherBytesReadFromTheStart() {
        assertEquals(0L, LogTail.resumeOffset(place, "/other/General.log", 9_000L, tail.copyOf()))
        assertEquals("truncated (a client restart)", 0L, LogTail.resumeOffset(place, "/g/Logs/General.log", 4_999L, tail.copyOf()))
        assertEquals("a restart that wrote past the old place", 0L,
            LogTail.resumeOffset(place, "/g/Logs/General.log", 9_000L, byteArrayOf(1, 2, 3, 4, 5, 6, 7)))
        assertEquals("not read", 0L, LogTail.resumeOffset(place, "/g/Logs/General.log", 9_000L, null))
        assertEquals(0L, LogTail.resumeOffset(null, "/g/Logs/General.log", 9_000L, tail.copyOf()))
        assertEquals(0L, LogTail.resumeOffset(place.copy(offset = 0L, tail = ByteArray(0)), "/g/Logs/General.log", 9_000L, ByteArray(0)))
    }

    @Test fun aPauseKeepsTheReaderAndNeverQuitsItsThread() {
        val t = File(srcDir(), "LogTail.kt").readText()
        val pause = t.substringAfter("override fun pause() {").substringBefore("\n    }\n")
        assertTrue(pause.contains("running = false") && pause.contains("handler.removeCallbacks(pump)"))
        for (bad in listOf("quitSafely", "offset = 0", "carry = \"\"", "docs.reset()")) assertFalse("pause: $bad", pause.contains(bad))
        val resume = t.substringAfter("override fun resume() {").substringBefore("\n    }\n")
        assertTrue(resume.contains("handler.post(pump)"))
        for (bad in listOf("offset = 0", "carry = \"\"", "docs.reset()")) assertFalse("resume: $bad", resume.contains(bad))
        // the place a new reader resumes at: kept after each feed; the first plain read checks the bytes before it
        assertTrue(t.substringAfter("private fun feed(").substringBefore("\n    }\n").contains("placeNow = "))
        assertTrue(t.substringAfter("private fun poll() {").substringBefore("\n    }\n").contains("resumeOffset("))
        // the source interface's defaults (Streaming's bridge keeps no place)
        val s = File(srcDir(), "LogSource.kt").readText()
        assertTrue(s.contains("fun pause() {}") && s.contains("fun resume() {}") && s.contains("fun place(): LogTail.Place? = null"))
    }

    @Test fun theHostStartsTheReaderAtTheSavedPlace() {
        val host = File(srcDir(), "CompanionHost.kt").readText()
        val st = host.substringAfter("private fun startTail() {").substringBefore("\n    }\n")
        assertTrue(st.contains("resume = resumeAt"))
        // a park pauses, the return resumes: never a new reader (never from byte 0) on a return
        val park = host.substringAfter("fun park(why: String) {").substringBefore("\n    }\n")
        assertTrue(park.contains("tail?.pause()"))
        assertFalse(park.contains("startTail()") || park.contains("tail?.stop()"))
        val unpark = host.substringAfter("fun unpark(why: String) {").substringBefore("\n    }\n")
        assertTrue(unpark.contains("tail?.resume()"))
        assertFalse(unpark.contains("startTail()") || unpark.contains("LogTail("))
        // the old host's place goes to the snapshot once its reader stopped
        val d = host.substringAfter("fun onDestroy() {").substringBefore("\n    }\n")
        assertTrue(d.indexOf("tail?.stop()") in 0 until d.indexOf("BottomSync.place("))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
}
