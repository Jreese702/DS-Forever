package app.gamenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory work #56, D5 (the user's yes 2026-11-19), off-device: the exit record's stamp (MemWatch.stateSummary, set with
 * ActivityManager.setProcessStateSummary at every memory line) read back into the next report's head, and the killed run's earlier
 * reliefs kept in its memory part.
 */
class CrashStateLineTest {
    @Test fun theStampIsReadBackAsOneLine() {
        val s = com.jrees.wowcompanion.MemWatch.stateSummary(1763600000L, 390, 31, 210, 40, 4120, 216, 180, 880, listOf("scenes" to 41L))
        assertEquals("State at the end: $s", CrashLogs.stateLine(s.toByteArray()))
        assertNull(CrashLogs.stateLine(null))                                    // an older run, Android 10: nothing
        assertNull(CrashLogs.stateLine(ByteArray(0)))
        assertEquals("State at the end: dsf 1 pss 2", CrashLogs.stateLine("dsf 1\npss 2\u0000".toByteArray()))
        assertTrue(CrashLogs.stateLine("dsf\n1".toByteArray())!!.none { it == '\n' })    // never a second line in the head
    }

    @Test fun theMemoryPartKeepsTheEarlierReliefs() {
        val day = java.util.Calendar.getInstance().apply { set(java.util.Calendar.MONTH, 10); set(java.util.Calendar.DAY_OF_MONTH, 18)
            set(java.util.Calendar.HOUR_OF_DAY, 18); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
        val kill = day.timeInMillis
        val lines = listOf("11-18 17:00:00 relief (world): let go voice 300 MB") +
            (0 until 100).map { i -> "11-18 17:%02d:%02d pss ${300 + i} MB (java 1, native 2, gfx 3, code 4)".format(10 + i / 60, i % 60) }
        val part = CrashLogs.memoryPart(lines.joinToString("\n") + "\n", kill)
        assertTrue(part.contains("relief (world)"))
        assertTrue(part.contains("pss 399 MB"))
        assertTrue(part.indexOf("relief (world)") < part.indexOf("pss 399 MB"))
    }
}
