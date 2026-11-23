package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #56 / #24 (11-23, logging only), off-device: the last 10 s of the numbers Android's low-memory killer reads (the 1 s sampler's ring,
 * on the exit stamp every second and as the 5 s line's lows), DS Forever's memory by owner (mem.log every 5 min and at each relief,
 * into the kill record's key lines), and the game's 30 s memory trend from the status counters instead of the page walk.
 */
class MemKillerNumbersTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val mem = File(src, "MemWatch.kt").readText()

    private val meminfo = """
        MemTotal:       11733720 kB
        MemFree:          317440 kB
        MemAvailable:    2677760 kB
        Buffers:            1024 kB
        Cached:          2100000 kB
        SwapCached:        10000 kB
        Active:          5000000 kB
        Inactive:        2000000 kB
        Active(anon):    4000000 kB
        Inactive(anon):   500000 kB
        Active(file):    1000000 kB
        Inactive(file):   843200 kB
        Shmem:            122880 kB
        SwapTotal:       4194304 kB
        SwapFree:        3174400 kB
    """.trimIndent()

    @Test fun oneSecondOfTheKillersNumbersComesFromTheSameMeminfoRead() {
        val s = MemWatch.lmkOf(meminfo.lineSequence(), 77)!!
        assertEquals(77L, s.upS)
        assertEquals(2677760L, s.availKb)
        assertEquals(317440L, s.freeKb)
        assertEquals(1843200L, s.fileKb)                                  // Active(file) + Inactive(file)
        assertEquals(122880L, s.shmemKb)
        assertEquals(3174400L, s.swapFreeKb)
        assertNull(MemWatch.lmkOf(sequenceOf("MemFree: 1 kB"), 1))         // no MemAvailable: no sample
        val partial = MemWatch.lmkOf(sequenceOf("MemAvailable: 2048 kB"), 1)!!
        assertEquals(-1L, partial.fileKb); assertEquals(-1L, partial.swapFreeKb)
        // the sampler reads it from the file it read before, one read a second (no new file: /proc/pressure stays at the reliefs)
        assertTrue(mem.contains("java.io.File(\"/proc/meminfo\").useLines { lmkOf(it, SystemClock.uptimeMillis() / 1000) }"))
    }

    @Test fun theFiveSecondLineCarriesTheLowsAndItsShortFormKeepsThem() {
        assertEquals("lmk fr 310 fc 1800 sh 120 sf 3100", MemWatch.lmkPart(317440, 1843200, 122880, 3174400))
        assertEquals("", MemWatch.lmkPart(-1, -1, -1, -1))
        assertEquals("lmk fr 310 fc ? sh ? sf ?", MemWatch.lmkPart(317440, -1, -1, -1))
        assertEquals("low 180 MB, jheap 31/512 MB, gpu 880 MB, lmk fr 310 fc 1800 sh 120 sf 3100",
            MemWatch.extras(184320, 31L shl 20, 512L shl 20, 880L shl 20, -1, 0, MemWatch.lmkPart(317440, 1843200, 122880, 3174400)))
        assertEquals("low 180 MB, jheap 31/512 MB, gpu 880 MB", MemWatch.extras(184320, 31L shl 20, 512L shl 20, 880L shl 20, -1, 0))
        val line = "11-23 18:50:13 pss 390 MB (java 31, native 210, gfx 40, code 19, stk 1, oth 40, sys 12; swp 13) heap 180/200 MB | " +
            "game 4120 MB (13, rss), free 216 MB, swap 3100/4096 MB | kit 6, scenes 41 | room home, game up, vcap 3072 | low 180 MB, " +
            "jheap 31/512 MB, gpu 880 MB, lmk fr 310 fc 1800 sh 120 sf 3100, main beat max 1.2 s"
        val short = MemWatch.shortLine(line)!!
        assertEquals("18:50:13 pss 390 (j 31 n 210 gx 40 sw 13) game 4120 free 216 low 180 gpu 880 vcap 3072 lmk fr 310 fc 1800 sh 120 sf 3100 " +
            "| scenes 41 kit 6", short)
        assertTrue(short.length < 230)
        // the line before 11-23 (no lmk part, no vcap): its short form as it was
        val old = "11-18 18:50:13 pss 390 MB (java 31, native 210, gfx 40, code 19) heap 180/200 MB | game 4120 MB (13), free 216 MB, " +
            "swap 3204/4096 MB | kit 6, scenes 41 | room home, game up | low 180 MB, jheap 31/512 MB, gpu 880 MB"
        assertEquals("18:50:13 pss 390 (j 31 n 210 gx 40) game 4120 free 216 low 180 gpu 880 | scenes 41 kit 6", MemWatch.shortLine(old))
    }

    private fun ring(n: Int, from: Long = 100, availMb: (Int) -> Long = { 2600L - it }) =
        (0 until n).map { MemWatch.Lmk(from + it, availMb(it) * 1024, 310L * 1024, 1800L * 1024, 120L * 1024, 3100L * 1024) }

    @Test fun theExitStampCarriesTheLastSecondsNewestFirstWithin128Bytes() {
        val head = MemWatch.stateSummary(1763900000L, 1058, 58, 707, 143, 3964, -1, -1, 2617, emptyList(), 13)
        assertEquals("dsf 1763900000 pss 1058 j 58 n 707 gx 143 sw 13 game 3964 gpu 2617", head)
        val s = MemWatch.ringStamp(head, 97, ring(10))
        assertTrue(s, s.startsWith("$head | +12 fr 310 fc 1800 sh 120 sf 3100 | av 2591 2592"))   // newest (100 + 9 = 109 s) first
        assertTrue(s.toByteArray().size <= MemWatch.SUMMARY_MAX)
        assertFalse(s.endsWith(" "))
        // a short head: all ten seconds fit
        val all = MemWatch.ringStamp("dsf 1 pss 300", 100, ring(10))
        assertEquals("dsf 1 pss 300 | +9 fr 310 fc 1800 sh 120 sf 3100 | av " + (9 downTo 0).joinToString(" ") { "${2600 - it}" }, all)
        // a burst shows: 2.5 GB gone in a second
        val burst = MemWatch.ringStamp("dsf 1 pss 300", 100, ring(3) { if (it == 2) 100L else 2600L })
        assertTrue(burst, burst.endsWith("| av 100 2600 2600"))
        // no sample yet: the head alone; a head too long: cut at a word
        assertEquals("dsf 1 pss 300", MemWatch.ringStamp("dsf 1 pss 300", 0, emptyList()))
        val long = MemWatch.ringStamp("dsf 1 " + "x ".repeat(80), 0, ring(10))
        assertTrue(long.length <= MemWatch.SUMMARY_MAX)
        // the 1 s sampler stamps it (a trim never refreshed the stamp before) and keeps 10 seconds
        assertEquals(10, MemWatch.RING)
        assertTrue(mem.contains("if (head.isNotEmpty()) { summaryNow = ringStamp(head, summaryHeadUpS, ringNow); runCatching { stampExit() } }"))
        // the diagnostics quote it as the "Mem stamp" key line, whole
        assertEquals("Mem stamp: $s", StartupDiag.memKeyLines(null, s)[0])
    }

    private val smaps = """
        12c00000-52c00000 rw-p 00000000 00:00 0                                  [anon:dalvik-main space (region space)]
        Size:            1048576 kB
        Pss:               40960 kB
        SwapPss:            1024 kB
        7000000000-7000400000 rw-p 00000000 00:00 0                              [anon:scudo:primary]
        Pss:              102400 kB
        SwapPss:           20480 kB
        7000400000-7000800000 rw-p 00000000 00:00 0                              [anon:scudo:secondary]
        Pss:               61440 kB
        7100000000-7100100000 rw-s 00000000 00:0d 1234                           /dev/kgsl-3d0
        Pss:              143360 kB
        7200000000-7200100000 r-xp 00000000 fd:05 99                             /data/app/~~Zx==/app.dsforever.companion-Yq==/lib/arm64/libwinlator.so
        Pss:                5120 kB
        7300000000-7300100000 rw-p 00000000 00:00 0
        Pss:               20480 kB
        7400000000-7400100000 rw-s 00000000 00:01 77                             /memfd:jit-cache (deleted)
        Pss:                 512 kB
        7500000000-7500100000 rw-s 00000000 00:0c 88                             /dmabuf:
        Pss:                8192 kB
    """.trimIndent()

    @Test fun ourMemoryByOwnerNamesTheKindsAndFilesNeverAPath() {
        assertEquals("dalvik-main", MemWatch.ownerName("[anon:dalvik-main space (region space)]"))
        assertEquals("dalvik-art", MemWatch.ownerName("[anon:dalvik-/system/framework/boot.art]"))
        assertEquals("scudo", MemWatch.ownerName("[anon:scudo:primary]"))
        assertEquals("libc_malloc", MemWatch.ownerName("[anon:libc_malloc]"))
        assertEquals("heap", MemWatch.ownerName("[heap]"))
        assertEquals("anon", MemWatch.ownerName(""))
        assertEquals("kgsl", MemWatch.ownerName("/dev/kgsl-3d0"))
        assertEquals("dmabuf", MemWatch.ownerName("/dmabuf:"))
        assertEquals("memfd:jit-cache", MemWatch.ownerName("/memfd:jit-cache (deleted)"))
        assertEquals("libwinlator.so", MemWatch.ownerName("/data/app/~~Zx==/app.dsforever.companion-Yq==/lib/arm64/libwinlator.so"))
        val o = MemWatch.owners(smaps.lineSequence())
        assertEquals("owners (pss+swap MB): scudo 180, kgsl 140, dalvik-main 41, anon 20, dmabuf 8, libwinlator.so 5; rest 1; 8 maps", o)
        assertFalse(o.contains("/data/app"))
        assertEquals("", MemWatch.owners(emptySequence()))
        assertTrue(MemWatch.isOwners(o))
        // its own file, read every 5 minutes and at each relief, timed (never the game's)
        assertTrue(mem.contains("java.io.File(\"/proc/self/smaps\").useLines { owners(it) }"))
        assertEquals(5 * 60_000L, MemWatch.OWNERS_EVERY_MS)
        assertEquals(2, Regex("ownersLine\\(\"relief\"\\)").findAll(mem).count())         // the loading-screen relief and the timed one
    }

    private fun at(t: String, l: String) = "11-23 $t $l"
    private val sample = "pss 390 MB (java 31, native 210, gfx 40, code 19) heap 180/200 MB | game 4120 MB (13, rss), free 216 MB, swap 3100/4096 MB | " +
        "kit 6 | room home, game up | low 180 MB, jheap 31/512 MB"

    @Test fun theKillRecordKeepsTheOwnersLinesBesideTheSamples() {
        val ownersA = "owners (pss+swap MB): scudo 120, kgsl 140; rest 40; 3400 maps in 30 ms"
        val ownersB = "owners (pss+swap MB): scudo 180, kgsl 141; rest 41; 3456 maps in 38 ms"
        val lines = listOf(at("18:00:00", ownersA), at("18:05:00", "relief (world): let go voice 300 MB"), at("18:10:00", ownersB),
            at("18:50:03", sample), at("18:50:08", sample), at("18:50:13", sample))
        val k = MemWatch.keyLines(lines)
        assertEquals(listOf("18:00:00 $ownersA", "18:05:00 relief (world): let go voice 300 MB", "18:10:00 $ownersB"), k.take(3))
        assertEquals(6, k.size)
        val record = listOf("DS Forever: how the last run ended", "", ReportUpload.MEMORY_MARK) + lines + listOf("", "---------- Trace ----------")
        val d = StartupDiag.memKeyLines(record.joinToString("\n"), null)
        assertEquals("Mem owners: 18:00:00 $ownersA", d[0])
        assertTrue(d[1].startsWith("Mem event: "))
        assertEquals("Mem owners: 18:10:00 $ownersB", d[2])
        assertTrue(d.drop(3).all { it.startsWith("Mem line: ") })
        // the report receiver quotes them (tools/report-worker trackers.js: /^Mem (stamp|line|event|owners): /)
        assertTrue(d.all { Regex("^Mem (stamp|line|event|owners): ").containsMatchIn(it) })
        val worker = listOf("../tools/report-worker/src/trackers.js", "tools/report-worker/src/trackers.js").map { File(it) }.first { it.isFile }.readText()
        assertTrue(worker.contains("/^Mem (stamp|line|event|owners): /"))
    }

    @Test fun anEarlierOwnersLineStaysInTheKilledRunsRecord() {
        val hist = buildString {
            append(at("17:00:00", "owners (pss+swap MB): scudo 100; rest 1; 1 maps in 1 ms")).append('\n')
            for (i in 0 until 100) append(at("17:%02d:%02d".format(10 + i / 12, (i % 12) * 5), sample)).append('\n')
        }
        val now = java.util.Calendar.getInstance().apply { set(java.util.Calendar.MONTH, 10); set(java.util.Calendar.DAY_OF_MONTH, 23)
            set(java.util.Calendar.HOUR_OF_DAY, 18); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        val ls = MemWatch.withEvents(hist, now)
        assertEquals(MemWatch.BEFORE_LINES + 1, ls.size)
        assertTrue(ls.first().contains("owners (pss+swap MB): scudo 100"))
    }

    @Test fun theGamesThirtySecondTrendReadsCountersAndTheWalkRunsOnlyWhenAsked() {
        val status = listOf("Name:\twine64", "Uid:\t10123\t10123\t10123\t10123", "VmRSS:\t 4000000 kB", "RssAnon:\t 3500000 kB",
            "RssFile:\t  400000 kB", "RssShmem:\t  100000 kB", "VmSwap:\t 0 kB")
        assertEquals(3600000L, MemWatch.statusKb(status))
        assertEquals(5L, MemWatch.statusKb(listOf("RssAnon: 5 kB")))
        assertNull(MemWatch.statusKb(listOf("Uid: 1 1 1 1")))
        // the page walk (smaps_rollup) only when [walk]: the host's silence, Light 8, a relief; the counters otherwise
        assertTrue(mem.contains("val v = if (walk) runCatching { java.io.File(d, \"smaps_rollup\").readLines() }"))
        assertTrue(mem.contains("else statusKb(st)"))
        assertTrue(mem.contains("val walk = light8() || runCatching { gameWalkWanted() }.getOrDefault(true)"))
        assertTrue(mem.contains("runCatching { gameNow(walk); if (walk) gameSample = gameMb to now }"))   // only a walk feeds the silence check
        val host = File(src, "CompanionHost.kt").readText()
        assertTrue(host.contains("MemWatch.gameWalkWanted = { silence.silent }"))
        assertTrue(host.contains("MemWatch.gameWalkWanted = { true }"))
    }
}
