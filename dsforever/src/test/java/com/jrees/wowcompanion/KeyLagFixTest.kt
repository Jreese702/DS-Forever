package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1.0.29 key-lag fix (the user: "insane input lag on my face buttons"): every pad key reached the game only after LatLog wrote
 * lat.log through FUSE on the main thread; mem.log was rewritten whole every 5 s; the kgsl read was tried every 5 s.
 */
class KeyLagFixTest {
    private fun tmp(): File = Files.createTempDirectory("keylag").toFile()

    @Test fun aSlowWriterNeverDelaysTheCaller() {
        val gate = CountDownLatch(1)
        val wrote = ArrayList<String>()
        val log = BgAppend({ File(tmp(), "lat.log") }, flushMs = 1L, write = { _, lines, _ ->
            gate.await(10, TimeUnit.SECONDS)                         // a FUSE write that hangs
            synchronized(wrote) { wrote.addAll(lines) }
        })
        log.add("first")
        Thread.sleep(50)                                             // the writer is now stuck inside the first batch
        val t0 = System.nanoTime()
        repeat(1000) { log.add("line $it") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("1000 marks took $ms ms while the writer hung", ms < 200)
        gate.countDown()
        log.flushNow()
        synchronized(wrote) { assertEquals(1001, wrote.size); assertEquals("first", wrote[0]); assertEquals("line 999", wrote.last()) }
    }

    @Test fun aFullQueueDropsAndCounts() {
        val gate = CountDownLatch(1)
        val wrote = ArrayList<String>()
        val log = BgAppend({ File(tmp(), "x.log") }, cap = 10, flushMs = 60_000L, write = { _, lines, _ -> gate.await(); wrote.addAll(lines) })
        repeat(25) { log.add("l$it") }
        assertEquals(15L, log.dropped.get())
        assertEquals(10, log.pending())
        gate.countDown()
        log.flushNow()
        assertEquals(11, wrote.size)
        assertTrue(wrote.last(), wrote.last().startsWith("(15 lines dropped"))
    }

    @Test fun latLogMarksOnlyQueue() {
        val f = File(tmp(), "logs/lat.log")
        val was = LatLog.out; val wasOn = LatLog.on
        try {
            LatLog.out = BgAppend({ f }, flushMs = 60_000L)
            LatLog.on = true
            LatLog.mark("pad", "k96")
            assertTrue("nothing written on the caller's thread", !f.exists())
            LatLog.out.flushNow()
            assertTrue(f.readText().matches(Regex("\\d+ pad k96\n")))
        } finally { LatLog.out = was; LatLog.on = wasOn }
    }

    @Test fun theWriterRotatesPastItsSize() {
        val f = File(tmp(), "r.log")
        f.writeText("x".repeat(2000))
        BgAppend.appendLines(f, listOf("a", "b"), 1000L)
        assertEquals("a\nb\n", f.readText())
        assertEquals(2000L, File(f.path + ".1").length())
    }

    @Test fun memLogAppendsAndTrimsOnlySometimes() {
        val f = File(tmp(), "logs/mem.log")
        val log = CappedLog(f, keep = 10, trimEveryMs = 600_000L)
        for (i in 1..20) log.append("line $i", 1_000L + i)
        assertEquals("appended, not cut yet", 20, f.readLines().size)
        assertEquals(0, log.trims)
        log.append("line 21", 2_000L)                               // past twice the cap: cut back to the newest 10
        assertEquals(1, log.trims)
        assertEquals((12..21).map { "line $it" }, f.readLines())
        log.append("line 22", 3_000L)
        assertEquals(11, f.readLines().size)                         // over the cap, but not ten minutes yet
        log.append("line 23", 3_000L + 600_000L)                     // ten minutes later: cut back
        assertEquals((14..23).map { "line $it" }, f.readLines())
        assertTrue(!File(f.path + ".part").exists())
        assertEquals((19..23).joinToString("") { "line $it\n" }, CappedLog.lastLines(f, 5))
    }

    @Test fun aNewRunCountsTheOldFileFirst() {
        val f = File(tmp(), "mem.log")
        f.writeText((1..19).joinToString("") { "old $it\n" })
        val log = CappedLog(f, keep = 10)
        log.append("new 1", 0L)
        assertEquals(20, f.readLines().size)
        log.append("new 2", 1L)
        assertEquals(10, f.readLines().size)
        assertEquals("new 2", f.readLines().last())
    }

    @Test fun theCrashRecordStillFindsTheKilledRunsLines() {
        val f = File(tmp(), "mem.log")
        val log = CappedLog(f, keep = 5)
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.MONTH, 5); set(java.util.Calendar.DAY_OF_MONTH, 10)
            set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        for (s in 0 until 8) log.append("06-10 12:00:%02d pss ${100 + s} MB".format(s), s.toLong())
        val got = MemWatch.linesUpTo(CappedLog.lastLines(f, 5), cal.timeInMillis + 5_000L, 72)
        assertEquals(listOf("06-10 12:00:03 pss 103 MB", "06-10 12:00:04 pss 104 MB", "06-10 12:00:05 pss 105 MB"), got)
    }

    @Test fun theKgslReadIsTriedOnceWhenItFails() {
        var reads = 0
        val was = MemWatch.gpuRead
        try {
            MemWatch.gpuReset()
            MemWatch.gpuRead = { reads++; null }
            assertNull(MemWatch.gpuBytes(0L))
            for (i in 1..100) assertNull(MemWatch.gpuBytes(i * 5_000L))
            assertEquals(1, reads)
            // a read that works: at most once a minute, the last value between
            MemWatch.gpuReset(); reads = 0
            MemWatch.gpuRead = { reads++; 880L shl 20 }
            for (i in 0 until 24) assertEquals(880L shl 20, MemWatch.gpuBytes(i * 5_000L))
            assertEquals(2, reads)
        } finally { MemWatch.gpuRead = was; MemWatch.gpuReset() }
    }

    @Test fun keyLagLinesAreRateLimited() {
        KeyLag.reset()
        var lines = 0
        var last: String? = null
        for (i in 0 until KeyLag.WINDOW) KeyLag.note(3L, 20_000L + i)?.let { lines++; last = it }
        assertEquals(1, lines)
        assertEquals("keylag over 32 presses: avg 3 ms, max 3 ms, 0 over 100 ms", last)
        assertNull("a slow press within 10 s of the last line waits", KeyLag.note(1_500L, 21_000L))
        assertNull("a quick press, the window not full", KeyLag.note(2L, 31_000L))
        assertEquals("keylag over 3 presses: avg 550 ms, max 1500 ms, 2 over 100 ms", KeyLag.note(150L, 31_000L))
        assertEquals("Key lag to the game: 35 presses, avg 49 ms, max 1500 ms, 2 over 100 ms", KeyLag.line())
        KeyLag.reset()
        assertEquals("Key lag to the game: no gamepad key press this run", KeyLag.line())
    }

    @Test fun releasesAreCountedApartFromPresses() {
        KeyLag.reset()
        for (i in 0 until KeyLag.WINDOW - 1) assertNull(KeyLag.note(2L, 40_000L + i))
        assertEquals("a slow release has its own line at once", "keylag over 1 release: avg 900 ms, max 900 ms, 1 over 100 ms",
            KeyLag.noteUp(900L, 40_100L))
        assertNull("rate-limited per kind", KeyLag.noteUp(800L, 41_000L))
        assertEquals("keylag over 32 presses: avg 2 ms, max 2 ms, 0 over 100 ms", KeyLag.note(2L, 41_000L))
        assertEquals("Key lag to the game: 32 presses, avg 2 ms, max 2 ms, 0 over 100 ms; 2 releases, avg 850 ms, max 900 ms, 2 over 100 ms",
            KeyLag.line())
        assertEquals(2L, KeyLag.releases); assertEquals(900L, KeyLag.worstRelease)
        KeyLag.reset()
    }

    @Test fun aSlowSubscriberIsNamedAndRateLimited() {
        PadSlow.reset()
        assertNull("quick deliveries say nothing", PadSlow.note("HomeView", PadSlow.SLOW_MS - 1, 10_000L))
        assertEquals("pad slow: DirectPad:chat 23 ms", PadSlow.note("DirectPad:chat", 23L, 10_000L))
        assertNull(PadSlow.note("CastView", 41L, 11_000L))
        assertNull(PadSlow.note("HomeView", 9L, 12_000L))
        assertEquals("pad slow: PressFeedback 12 ms (3 slow, worst 41 ms by CastView since the last line)",
            PadSlow.note("PressFeedback", 12L, 15_000L))
        assertEquals("Pad deliveries over 8 ms: 4 this run, worst 41 ms (CastView)", PadSlow.line())
        PadSlow.reset()
        assertEquals("Pad deliveries over 8 ms: none this run", PadSlow.line())
    }

    @Test fun aSubscribersNameIsItsCallbacksClass() {
        assertEquals("KeyLagFixTest", PadSlow.nameOf({ x: Int -> x + 1 }))
        assertEquals("String", PadSlow.nameOf("x"))
        assertEquals("?", PadSlow.nameOf(null))
    }

    @Test fun diagLogNeverWritesOnTheCallersThread() {
        DiagLog.useDir(tmp())
        val gate = CountDownLatch(1)
        BgAppend.EXEC.execute { gate.await(10, TimeUnit.SECONDS) }        // the writer stuck in a slow write
        val t0 = System.nanoTime()
        repeat(200) { DiagLog.event("pad", "press $it") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("200 events took $ms ms while the writer hung", ms < 200)
        gate.countDown()
        val lines = DiagLog.lines(60_000L)                                  // waits for the writer, then reads
        assertEquals(200, lines.count { it.contains("pad: press ") })
        assertTrue(lines.last().endsWith("pad: press 199"))
    }
}
