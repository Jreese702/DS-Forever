package app.gamenative

import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import com.jrees.wowcompanion.LogSieve
import com.jrees.wowcompanion.MemWatch
import com.jrees.wowcompanion.ProblemReport
import com.jrees.wowcompanion.ReportUpload
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0.28, the crash-report cluster (low-memory kills #159 - #193, ANRs #153 #177 #191, native crash #114): what the next reports carry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashDiagTest {
    private val facts = ProblemReport.Facts("1.0.28 (1440)", "set 376", "launch", "AYN Thor, Android 13", emptyList())

    /** an ANR record as CrashLogs writes it: the runtime's ~20 KB of stats first, the main thread last (#177's shape) */
    private fun anr(): String = buildString {
        appendLine("DS Forever: how the last run ended")
        appendLine("Reason: anr (6), status 0")
        appendLine()
        appendLine("---------- Trace ----------")
        appendLine()
        appendLine("----- pid 4732 at 2026-11-12 07:20:11 -----")
        repeat(600) { appendLine("Process mark stacks and References:\tSum: 228.727ms Avg: 14.295ms ($it)") }
        appendLine()
        appendLine("\"Signal Catcher\" daemon prio=10 tid=5 Runnable")
        appendLine("  native: #00 pc 000000000055fdcc  /apex/com.android.art/lib64/libart.so (art::DumpNativeStack)")
        appendLine()
        appendLine("\"WCMEM\" prio=5 tid=31 Native")
        appendLine("  at java.io.FileInputStream.read(Native method)")
        appendLine("  at com.jrees.wowcompanion.MemWatch.sampleFree(MemWatch.kt:95)")
        appendLine()
        appendLine("\"Binder:4732_1\" prio=5 tid=8 Native")
        appendLine("  native: #00 pc 00000000000a5f2c  /apex/com.android.runtime/lib64/bionic/libc.so (ioctl+12)")
        appendLine()
        appendLine("\"main\" prio=5 tid=1 Native")
        appendLine("  | group=\"main\" sCount=1")
        appendLine("  native: #00 pc 00000000000a5f2c  /apex/com.android.runtime/lib64/bionic/libc.so (fstatat+12)")
        appendLine("  at java.io.UnixFileSystem.getBooleanAttributes0(Native method)")
        appendLine("  at java.io.File.isDirectory(File.java:845)")
        appendLine("  at com.jrees.wowcompanion.MinimapTiles.has(MinimapTiles.kt:57)")
        appendLine()
        appendLine("----- end 4732 -----")
    }

    @Test fun anAnrsMainThreadComesFirst() {
        val t = LogSieve.traceFirst(anr())
        val main = t.indexOf("\"main\" prio=5")
        assertTrue(main in 0 until t.indexOf("Process mark stacks"))
        assertTrue(t.indexOf("\"WCMEM\" prio=5") in main until t.indexOf("Process mark stacks"))     // our code's threads next
        assertTrue(t.indexOf("\"Binder:4732_1\"") > t.indexOf("Process mark stacks"))               // the rest keep their place
        assertEquals(1, Regex("\"main\" prio=5").findAll(t).count())                                   // moved, not copied
        assertTrue(t.contains("----- end 4732 -----"))
        assertTrue(t.startsWith("DS Forever: how the last run ended"))
    }

    @Test fun otherRecordsAreUnchanged() {
        val lm = "DS Forever: how the last run ended\nReason: low-memory (3), status 0\nMemory: pss 359027 KB\n"
        assertEquals(lm, LogSieve.traceFirst(lm))
        val noMain = "x\n---------- Trace ----------\n\"Signal Catcher\" daemon prio=10 tid=5 Runnable\n  at a.b(C.kt:1)\n"
        assertEquals(noMain, LogSieve.traceFirst(noMain))
    }

    @Test fun theUploadKeepsTheMainThreadAndTheMemoryHistory() {
        val mem = (1..200).joinToString("\n") { "11-12 12:25:%02d pss 4$it MB (java 1) heap 1/2 MB | game 3406 MB (12), free 2632 MB".format(it % 60) }
        val r = ProblemReport.build(0L, "Automatic crash report", facts, emptyList(),
            listOf("10-14 12:00:00.000  1  2 I WCHARVEST: x"), listOf("2026-11-12_07-21-22_anr.txt" to anr()), false, emptyList(),
            memory = mem)
        val logs = JSONObject(ReportUpload.request(r, "Automatic crash report", facts, false, emptyList())).getString("logs")
        assertTrue(logs.contains("at com.jrees.wowcompanion.MinimapTiles.has(MinimapTiles.kt:57)"))  // was beyond the 15 000 cut
        assertTrue(logs.contains("===== memory.txt (the newest: 72 lines) ====="))
        assertTrue(logs.contains("pss 4200 MB")); assertFalse(logs.contains("pss 4128 MB"))         // the newest 72 of 200
        assertTrue(logs.indexOf("===== crashes/") > logs.indexOf("===== memory.txt"))              // the crash records stay last
        assertEquals("", ReportUpload.memoryTail("\n \n"))
    }

    @Test fun theMemoryLinesNewFacts() {
        assertEquals(500L, MemWatch.lowest(-1L, 500L)); assertEquals(300L, MemWatch.lowest(300L, 500L)); assertEquals(200L, MemWatch.lowest(300L, 200L))
        assertEquals("low 317 MB, jheap 133/512 MB, gpu 880 MB, trim running-low 12 s ago",
            MemWatch.extras(317L * 1024, 133L shl 20, 512L shl 20, 880L shl 20, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 12_400L))
        assertEquals("jheap 1/2 MB", MemWatch.extras(-1L, 1L shl 20, 2L shl 20, null, -1, 0L))     // no low yet, no GPU file, no trim
        assertEquals("running-critical", MemWatch.trimName(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals("low-memory", MemWatch.trimName(MemWatch.LOW_MEMORY))
    }

    @Test fun aStallIsLoggedOnceWithTheMainThreadsStack() {
        assertFalse(MemWatch.stalled(2_499L, 10L, -1L))
        assertTrue(MemWatch.stalled(2_500L, 10L, -1L))
        assertFalse(MemWatch.stalled(9_000L, 10L, 10L))                                                // the same stall: once
        val l = MemWatch.stallLine(3_040L, listOf("java.io.File.isDirectory(File.java:845)", "com.jrees.wowcompanion.MinimapTiles.has(MinimapTiles.kt:57)"))
        assertEquals("stall: the main thread blocked 3.0 s: at java.io.File.isDirectory(File.java:845) <- at com.jrees.wowcompanion.MinimapTiles.has(MinimapTiles.kt:57)", l)
        assertTrue(MemWatch.stallLine(3_000L, (1..30).map { "f$it" }).endsWith("at f24 <- ..."))
    }

    @Test fun theMapRoomsPicturesGoOnlyWhenHiddenAndAndroidIsLow() {
        assertTrue(MemWatch.hiddenLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, shown = false))
        assertTrue(MemWatch.hiddenLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, shown = false))
        assertTrue(MemWatch.hiddenLetsGo(MemWatch.LOW_MEMORY, shown = false))
        assertFalse(MemWatch.hiddenLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, shown = false))
        assertFalse(MemWatch.hiddenLetsGo(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, shown = true))   // on screen: kept
    }

    @Test fun aTrimIsLoggedAndHandedOn() {
        var got = -1
        MemWatch.onTrim("test") { level -> got = level; 5L shl 20 }
        MemWatch.trimmed(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, got)
        MemWatch.onTrim("test") { 0L }
    }

    // ---------------------------------------------------------------- the killed run's memory lines (the review: #164 #167 #171 #182)

    private val memStamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
    private fun memLine(ms: Long, pss: Int) = "${memStamp.format(java.util.Date(ms))} pss $pss MB (java 1) | game 3406 MB (12), free 2632 MB"
    /** a kill at [kill]: the killed run's lines every 5 s for 10 minutes up to it (pss 1000 + n), then [after] lines of the new run
     *  from 4 hours later (pss 5000 + n), mem.log's shape when #182 was reported */
    private fun memAround(kill: Long, after: Int = 200): String =
        ((120 downTo 0).map { memLine(kill - it * 5_000L, 1000 + (120 - it)) } +
            (0 until after).map { memLine(kill + 4 * 3_600_000L + it * 5_000L, 5000 + it) }).joinToString("\n")

    private val kill = java.util.GregorianCalendar(2026, 10, 12, 11, 13, 5).timeInMillis + 400L

    @Test fun theKilledRunsLinesAreTheOnesUpToTheKill() {
        val ls = MemWatch.linesUpTo(memAround(kill), kill)
        assertEquals(72, ls.size)
        assertTrue(ls.last(), ls.last().contains("pss 1120 MB"))                                     // the line in the kill's own second
        assertTrue(ls.first().contains("pss 1049 MB"))
        assertTrue(ls.none { it.contains("pss 5") })                                                 // nothing of the new run
        assertEquals(emptyList<String>(), MemWatch.linesUpTo("", kill))
        assertEquals(emptyList<String>(), MemWatch.linesUpTo("not a line\n\n", kill))
        // a kill just after New Year: December's lines are last year's, not eleven months ahead
        val ny = java.util.GregorianCalendar(2027, 0, 1, 0, 0, 30).timeInMillis
        val h = listOf(memLine(ny - 40_000L, 1), memLine(ny - 5_000L, 2), memLine(ny + 60_000L, 3)).joinToString("\n")
        assertEquals(listOf(memLine(ny - 40_000L, 1), memLine(ny - 5_000L, 2)), MemWatch.linesUpTo(h, ny))
    }

    @Test fun theUploadCarriesTheKilledRunsLinesEvenHoursLater() {
        val record = "DS Forever: how the last run ended\nWhen: 2026-11-12_11-13-05\nReason: low-memory (3), status 0\n" +
            "App: 1.0.28\n" + CrashLogs.memoryPart(memAround(kill), kill)
        assertTrue(record.contains(CrashLogs.MEMORY_MARK))
        val r = ProblemReport.build(0L, "Automatic crash report", facts, emptyList(), listOf("10-14 12:00:00.000  1  2 I WCHARVEST: x"),
            listOf("2026-11-12_11-13-05_low-memory.txt" to record), false, emptyList(), memory = memAround(kill))
        val logs = JSONObject(ReportUpload.request(r, "Automatic crash report", facts, false, emptyList())).getString("logs")
        assertTrue(logs, logs.contains("===== memory.txt (before the end, from 2026-11-12_11-13-05_low-memory.txt: 72 lines) ====="))
        assertTrue(logs.contains("pss 1120 MB")); assertFalse(logs.contains("pss 1048 MB"))
        assertFalse(logs.contains("pss 5199 MB"))                                                    // the new run's tail is not sent
        assertFalse(logs.contains(CrashLogs.MEMORY_MARK))                                            // taken out of the record's own cut
        assertTrue(logs.contains("Reason: low-memory (3)"))
        assertTrue(logs.indexOf("===== crashes/") > logs.indexOf("===== memory.txt"))
    }

    @Test fun anAnrsMemoryPartLeavesItsTraceAlone() {
        val a = anr()
        val mark = a.indexOf("\n---------- Trace ----------")
        val withMem = a.substring(0, mark).trimEnd('\n') + "\n" + CrashLogs.memoryPart(memAround(kill), kill) + a.substring(mark)
        val (rest, mem) = ReportUpload.splitMemory(withMem)
        assertEquals(a, rest)
        assertEquals(72, mem!!.lines().size)
        assertEquals(a to null, ReportUpload.splitMemory(a))
        assertEquals("", CrashLogs.memoryPart("", kill))
    }

    // ---------------------------------------------------------------- the tombstone (#114)

    private fun varint(o: ByteArrayOutputStream, v: Long) { var x = v; while (true) { val b = (x and 0x7F).toInt(); x = x ushr 7; if (x == 0L) { o.write(b); return }; o.write(b or 0x80) } }
    private fun num(no: Int, v: Long) = ByteArrayOutputStream().also { varint(it, (no.toLong() shl 3)); varint(it, v) }.toByteArray()
    private fun bytes(no: Int, b: ByteArray) = ByteArrayOutputStream().also { varint(it, (no.toLong() shl 3) or 2L); varint(it, b.size.toLong()); it.write(b) }.toByteArray()
    private fun str(no: Int, s: String) = bytes(no, s.toByteArray())
    private fun msg(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
    private fun fixed64(no: Int, v: Long) = ByteArrayOutputStream().also { varint(it, (no.toLong() shl 3) or 1L); for (k in 0 until 8) it.write(((v ushr (8 * k)) and 0xFF).toInt()) }.toByteArray()

    private fun frame(relPc: Long, file: String, fn: String, off: Long) = msg(num(1, relPc), str(4, fn), num(5, off), str(6, file), str(8, "95e1eafb"))
    private fun thread(id: Long, name: String, vararg frames: ByteArray) = msg(num(1, id), str(2, name), *frames.map { bytes(4, it) }.toTypedArray())
    private fun entry(id: Long, th: ByteArray) = bytes(16, msg(num(1, id), bytes(2, th)))

    @Test fun aNativeCrashsTombstoneReadsAsText() {
        val tomb = msg(
            num(1, 1L), str(2, "qti/kalama/kalama:13"), str(4, "2026-11-10 22:50:42"), num(5, 28738L), num(6, 28800L), num(7, 10122L),
            str(9, "app.dsforever.companion"),
            bytes(10, msg(num(1, 11L), str(2, "SIGSEGV"), num(3, 1L), str(4, "SEGV_MAPERR"), num(8, 1L), num(9, 0x8L))),
            bytes(15, msg(str(1, "null pointer dereference"))),
            entry(28738L, thread(28738L, "main", frame(0x9ab0, "/apex/com.android.runtime/lib64/bionic/libc.so", "syscall", 32))),
            entry(28800L, thread(28800L, "rumble-poller-2",
                frame(0x1a2c, "/data/app/x/lib/arm64/libevshim.so", "Java_com_winlator_winhandler_WinHandler_waitForRumble", 44),
                frame(0x22c0, "/apex/com.android.art/lib64/libart.so", "art_quick_generic_jni_trampoline", 148))),
            fixed64(23, 7L))                                                  // an unknown field is skipped
        val t = Tombstone.text(tomb)!!
        assertTrue(t, t.contains("pid 28738, tid 28800, command app.dsforever.companion"))
        assertTrue(t, t.contains("Signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault address 0x8"))
        assertTrue(t, t.contains("Cause: null pointer dereference"))
        assertTrue(t, t.contains("The crashing thread: tid 28800 \"rumble-poller-2\"\n  #00 pc 0000000000001a2c  /data/app/x/lib/arm64/libevshim.so (Java_com_winlator_winhandler_WinHandler_waitForRumble+44) (BuildId: 95e1eafb)\n  #01 pc 00000000000022c0"))
        assertTrue(t, t.indexOf("tid 28738 \"main\"") > t.indexOf("The other threads"))
        // through CrashLogs: decoded for a native crash, text as it was for anything else
        assertTrue(CrashLogs.traceText(ApplicationExitInfo.REASON_CRASH_NATIVE, tomb).startsWith("Native crash (the tombstone, decoded)"))
        assertEquals("plain", CrashLogs.traceText(ApplicationExitInfo.REASON_ANR, "plain".toByteArray()))
        assertEquals("*** *** ***\nold text", CrashLogs.traceText(ApplicationExitInfo.REASON_CRASH_NATIVE, "*** *** ***\nold text".toByteArray()))
        assertNull(Tombstone.text(byteArrayOf(0x0A, 0x7F)))                       // cut short: not a tombstone
    }
}
