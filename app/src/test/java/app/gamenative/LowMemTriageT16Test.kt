package app.gamenative

import com.jrees.wowcompanion.MemWatch
import com.jrees.wowcompanion.ProblemReport
import com.jrees.wowcompanion.ReportUpload
import com.jrees.wowcompanion.StartupDiag
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue triage 2026-11-16, the 1.0.32 low-memory cluster (#279 - #425, 100 reports from 34 Thors on 1.0.32): no regression in
 * DS Forever's own memory (12 GB, per device: 348 MB at the kill against 453 MB on 1.0.29 - 1.0.31) nor in the game's (3580 / 3565 MB);
 * what the next reports need: (1) the diagnostics' crash excerpt showed the OLDEST 21 of the killed run's 72 memory lines, so every
 * excerpt ended about 4 minutes before its kill; (2) half the kills came with the device's swap 85 % full or more, and no line said
 * whose swap it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LowMemTriageT16Test {
    private val memStamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
    private val kill = java.util.GregorianCalendar(2026, 10, 15, 20, 4, 12).timeInMillis + 400L
    private fun memLine(ms: Long, pss: Int) = "${memStamp.format(java.util.Date(ms))} pss $pss MB (java 1) | game 4684 MB (12), free 1476 MB"
    /** the killed run's lines every 5 s for 10 minutes up to the kill: pss 1000 (oldest) .. 1120 (the kill's second) */
    private val history = (120 downTo 0).joinToString("\n") { memLine(kill - it * 5_000L, 1000 + (120 - it)) }
    /** a record as CrashLogs writes it: its head, the memory part (72 lines), optionally a trace */
    private fun record(trace: Boolean = false) = buildString {
        appendLine("DS Forever: how the last run ended")
        appendLine("When: 2026-11-15_20-04-12")
        appendLine("Reason: low-memory (3), status 0")
        appendLine("Description: null")
        appendLine("Process: app.dsforever.companion (pid 3377), importance 100")
        appendLine("Memory: pss 460000 KB, rss 560000 KB")
        appendLine("App: 1.0.32; device AYN AYN Thor, Android 13")
        append(CrashLogs.memoryPart(history, kill))
        if (trace) { appendLine(); appendLine("---------- Trace ----------"); (1..500).forEach { appendLine("  at trace.frame$it(F.kt:$it)") } }
    }

    @Test fun theCrashExcerptEndsAtTheKillNotFourMinutesBefore() {
        val r = record()
        val old = r.lineSequence().take(30).toList()                                        // 1.0.32's excerpt
        assertTrue(old.last(), old.last().contains("pss 1069 MB"))                          // the 21st of 72: 51 lines (~4 min) short of the kill
        val ex = StartupDiag.crashExcerpt(r.lineSequence())
        assertTrue(ex.size <= StartupDiag.CRASH_LINES)
        assertEquals("DS Forever: how the last run ended", ex.first())
        assertTrue(ex.contains("Memory: pss 460000 KB, rss 560000 KB"))                     // the head is kept whole
        assertTrue(ex.contains("---------- Memory before the end (mem.log): the last 22 of 72 lines ----------"))
        assertTrue(ex.last(), ex.last().contains("pss 1120 MB"))                            // the line in the kill's own second
        assertTrue(ex.none { it.contains("pss 1049 MB") || it.contains("pss 1098 MB") })    // the oldest ones are what goes
        assertTrue(ex.any { it.contains("pss 1099 MB") })                                   // the newest 22: 1099 .. 1120
        assertFalse(ex.contains(ReportUpload.MEMORY_MARK))                                  // the record's own mark is never copied out
    }

    @Test fun aTraceAfterTheMemoryIsNotRead() {
        var read = 0
        val ex = StartupDiag.crashExcerpt(record(trace = true).lineSequence().onEach { read++ })
        assertTrue(ex.last(), ex.last().contains("pss 1120 MB"))
        assertTrue(ex.none { it.contains("trace.frame") })
        assertTrue("$read", read < 100)                                                     // stopped at the trace's heading
    }

    @Test fun aRecordWithoutMemoryLinesShowsItsFirstLinesAsBefore() {
        val anr = buildString {
            appendLine("DS Forever: how the last run ended"); appendLine("Reason: anr (6), status 0")
            appendLine(); appendLine("---------- Trace ----------"); (1..2000).forEach { appendLine("  at f$it") }
        }
        assertEquals(anr.lineSequence().take(30).toList(), StartupDiag.crashExcerpt(anr.lineSequence()))
        assertEquals(listOf("a", "b"), StartupDiag.crashExcerpt(sequenceOf("a", "b")))
        assertEquals(emptyList<String>(), StartupDiag.crashExcerpt(emptySequence()))
    }

    @Test fun theDiagnosticsShowTheNewestRecordsLastLines() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = ProblemReport.crashDir(ctx).also { it.mkdirs() }
        val f = File(dir, "2026-11-15_20-04-12_low-memory.txt")
        try {
            f.writeText(record(trace = true))
            val section = StartupDiag.text(ctx, emptyList()).substringAfter("=== Crashes and exits ===").substringBefore("\n===")
            assertTrue(section, section.contains("  | ---------- Memory before the end (mem.log): the last 22 of 72 lines ----------"))
            assertTrue(section, section.contains("pss 1120 MB"))
            assertFalse(section, section.contains("pss 1069 MB"))
        } finally { f.delete() }
    }

    @Test fun theGamesSwapIsReadFromTheSameRollup() {
        val rollup = """
            00400000-7fffd0d1d000 ---p 00000000 00:00 0                              [rollup]
            Rss:             3912340 kB
            Pss:             3870112 kB
            Pss_Anon:        3401220 kB
            Pss_File:         468892 kB
            Pss_Shmem:             0 kB
            Shared_Clean:      44120 kB
            Swap:            2254000 kB
            SwapPss:         2251980 kB
            Locked:                0 kB
        """.trimIndent().lines()
        assertEquals(3870112L to 2251980L, MemWatch.rollup(rollup))
        assertEquals(1024L to 0L, MemWatch.rollup(listOf("Rss: 2048 kB", "Pss:   1024 kB")))  // an older kernel: no SwapPss line
        assertNull(MemWatch.rollup(listOf("Rss: 2048 kB", "Pss_Anon: 1024 kB")))           // no Pss: the process is skipped
        assertNull(MemWatch.rollup(emptyList()))
    }

    @Test fun theSwapNoteSaysWhoseSwapItIs() {
        assertEquals("swapped ours 40 MB, game 2199 MB", MemWatch.swapNote(40L * 1024, 2251980L))
        assertEquals("swapped game 0 MB", MemWatch.swapNote(-1L, 0L))                       // no game running: its part is 0
        assertEquals("swapped ours 12 MB", MemWatch.swapNote(12L * 1024, -1L))              // before the first game sample
        assertEquals("", MemWatch.swapNote(-1L, -1L))
    }
}
