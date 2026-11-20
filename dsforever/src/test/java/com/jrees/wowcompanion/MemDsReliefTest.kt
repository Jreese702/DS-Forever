package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory work #56, D2 / D4 / D5 (the user's yes 2026-11-19), off-device: the map plans, minimap tiles, the kit's side caches and
 * QuestieDB's spawns in the memory line and MemRelief's pressure; the icon caches bounded by the Thor; the voice model let go at the
 * reliefs but never in a session's middle; the reports' memory key lines and the exit record's stamp.
 */
class MemDsReliefTest {
    private val LOW = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    private val CRIT = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    private val MOD = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE

    // ---------------------------------------------------------------- D2: what each cache keeps under pressure

    @Test fun thePlansKeepTwoOnScreenAndNoneOffScreen() {
        assertNull(LowMemCuts.plansKept(MOD, true))
        assertEquals(2, LowMemCuts.plansKept(LOW, true))                          // the relief's level: the storey and its ghost
        assertEquals(0, LowMemCuts.plansKept(LOW, false))
        assertEquals(0, LowMemCuts.plansKept(CRIT, true))
        assertEquals(0, LowMemCuts.plansKept(MemWatch.LOW_MEMORY, true))
    }

    @Test fun theMinimapKeepsAScreensWorthThenNone() {
        assertNull(LowMemCuts.minimapKept(MOD))
        assertEquals(LowMemCuts.MINIMAP_FULL to LowMemCuts.MINIMAP_SMALL, LowMemCuts.minimapKept(LOW))
        assertEquals(0 to 0, LowMemCuts.minimapKept(CRIT))
        assertEquals(0 to 0, LowMemCuts.minimapKept(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test fun theKitsSideCachesFollowItsPictures() {
        assertEquals(listOf("gone", "also"), LowMemCuts.sideGone(listOf("kept", "gone", "also"), setOf("kept", "other")))
        assertTrue(LowMemCuts.sideGone(listOf("a"), setOf("a")).isEmpty())
    }

    @Test fun theIconCachesAreSizedByTheThor() {
        assertEquals(160, LowMemCuts.iconsKept(7191))                             // Thor Base (8 GB)
        assertEquals(160, LowMemCuts.iconsKept(11265, lite = true))
        assertEquals(240, LowMemCuts.iconsKept(11265))                            // Thor Pro (12 GB)
        assertEquals(400, LowMemCuts.iconsKept(15255))                            // Thor Max (16 GB)
        assertEquals(400, LowMemCuts.iconsKept(0))                                // the RAM not known yet
        // every one under the old 600 (~10 MB)
        assertTrue(listOf(7191L, 11265L, 15255L).all { LowMemCuts.iconsKept(it) < 600 })
    }

    @Test fun aSizedLruHoldsItsBoundTheLeastRecentlyUsedGoingFirst() {
        var max = 3
        val m = LowMemCuts.SizedLru<Int, String> { max }
        for (i in 1..3) m[i] = "i$i"
        m[1]                                                                       // drawn again: kept
        m[4] = "i4"
        assertEquals(setOf(1, 3, 4), m.keys)
        max = 2                                                                    // the budget once the RAM is known
        m[5] = "i5"
        assertEquals(2, m.size)
        assertEquals(setOf(4, 5), m.keys)
        for (i in 6..1000) m[i] = "i$i"
        assertEquals(2, m.size)
        max = 0                                                                    // never below one
        m[2000] = "x"
        assertEquals(setOf(2000), m.keys)
    }

    @Test fun questieDbsSpawnsKeepTheRecentMapsUnderPressureAndAreReadAgainLater() {
        assertNull(DangerStore.keepAt(MOD, setOf(1, 2), listOf(1)))
        assertEquals(setOf(1), DangerStore.keepAt(LOW, setOf(1, 2), listOf(9, 1)))
        assertEquals(emptySet<Int>(), DangerStore.keepAt(CRIT, setOf(1, 2), listOf(1)))
        assertFalse(DangerStore.rereadDue(59_999L, 0L))
        assertTrue(DangerStore.rereadDue(60_000L, 0L))
        // the store itself: counted, then a trim keeps the maps asked for last
        fun sp(n: Int) = List(n) { QuestieNpcs.Spawn(12, 0.5f, 0.5f, 5, 7, false, 1, "Kobold", npc = 1) }
        DangerStore.seed(7001, sp(100)); DangerStore.seed(7002, sp(50)); DangerStore.seed(7003, sp(10))
        val v = DangerStore.version
        DangerStore.spawns(7003)                                                   // the map asked for last
        val before = DangerStore.bytes()
        assertTrue(before >= 160 * DangerStore.SPAWN_BYTES)
        assertEquals(0L, DangerStore.letGo(MOD))
        val freed = DangerStore.letGo(LOW)
        assertTrue(freed >= 150 * DangerStore.SPAWN_BYTES)
        assertEquals(10, DangerStore.spawns(7003).size)
        assertTrue(DangerStore.spawns(7001).isEmpty())
        assertEquals(v, DangerStore.version)                                       // the routes' grids stay as built
        assertTrue(DangerStore.bytes() < before)
    }

    // ---------------------------------------------------------------- D4: the voice model

    @Test fun theVoiceModelGoesAtEveryReliefButNeverInASessionsMiddle() {
        assertTrue(VoiceHold.letsGo(MemWatch.RELIEF_LEVEL))                        // Play, the world, every loading screen
        assertTrue(VoiceHold.letsGo(CRIT)); assertTrue(VoiceHold.letsGo(MemWatch.LOW_MEMORY))
        assertFalse(VoiceHold.letsGo(MOD))
        val h = VoiceHold()
        assertTrue(h.free())                                                       // no session: it goes now
        h.begin(1)                                                                 // a push to talk listens
        assertFalse(h.free())                                                      // a loading screen meanwhile: it waits
        assertTrue(h.pending)
        assertFalse(h.idle())
        assertFalse(h.end(0))                                                      // another session's end is not this one's
        assertTrue(h.end(1))                                                       // the words are out: the free runs now
        assertFalse(h.pending)
        assertTrue(h.idle())
        assertFalse(h.end(1))                                                      // once
    }

    @Test fun aNewerSessionHoldsTheFreeUntilItsOwnEnd() {
        val h = VoiceHold()
        h.begin(1)
        h.begin(2)                                                                 // a second press replaced the first (its cancel)
        assertFalse(h.free())
        assertFalse(h.end(1))                                                      // the old one's late end frees nothing
        assertTrue(h.end(2))
        h.begin(3)
        assertFalse(h.end(3))                                                      // no free asked: nothing to run
    }

    @Test fun theSessionsEndsAreWiredOnEveryWayOut() {
        // the engine's every way out of a session ends it (so a free waiting for it runs): the recording's early returns, the hand-off
        // to the words' work and its end, a cancel; and the idle free never runs while one is under way
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion/WhisperEngine.kt", "companion/app/src/main/java/com/jrees/wowcompanion/WhisperEngine.kt")
            .map { java.io.File(it) }.first { it.isFile }.readText()
        assertTrue(src.contains("hold.begin(s)"))
        assertTrue(src.contains("try { handed = recordInner(s, pick, prompt, sink) } finally { if (!handed) ended(s) }"))
        assertTrue(src.contains("try { transcribe(s, pick, pcm, count, prompt, sink, recEnd) } finally { ended(s) }"))
        assertTrue(src.contains("ended(was)"))
        assertTrue(src.contains("&& hold.idle()) work.execute { if (!running && hold.idle()) freeNow() }"))
        assertTrue(src.contains("if (!warmFor(pick.first)) post(s) { sink.loading() }"))
        val inner = src.substring(src.indexOf("private fun recordInner("), src.indexOf("private fun transcribe("))
        assertFalse("a bare return in recordInner", Regex("\\breturn(\\s*[}\\n])").containsMatchIn(inner))
        assertTrue(src.contains("MemWatch.source(\"voice\")"))
    }

    // ---------------------------------------------------------------- D5: the reports

    @Test fun theExitStampIsShortAsciiAndSaysTheBiggestCaches() {
        val s = MemWatch.stateSummary(1763600000L, 390, 31, 210, 40, 4120, 216, 180, 880,
            listOf("scenes" to 41L, "kit" to 6L, "voice" to 0L, "safepath" to 24L))
        assertEquals("dsf 1763600000 pss 390 j 31 n 210 gx 40 game 4120 free 216 low 180 gpu 880 | scenes 41 safepath 24 kit 6", s)
        assertTrue(s.toByteArray().size <= MemWatch.SUMMARY_MAX)
        val unread = MemWatch.stateSummary(1L, 300, 20, 100, 10, -1, -1, -1, -1, emptyList())
        assertEquals("dsf 1 pss 300 j 20 n 100 gx 10", unread)
        val long = MemWatch.stateSummary(1763600000L, 390, 31, 210, 40, 4120, 216, 180, 880, (1..40).map { "cache$it" to it.toLong() })
        assertTrue(long.toByteArray().size <= MemWatch.SUMMARY_MAX)
        assertTrue(long.contains("| cache40 40"))                                  // the biggest kept first
        assertFalse(long.endsWith(" "))
    }

    private val sample = "11-18 18:50:13 pss 390 MB (java 31, native 210, gfx 40, code 19) heap 180/200 MB | game 4120 MB (13), free 216 MB, " +
        "swap 3204/4096 MB | plans 0, worldroom 0, cast 2, kit 6, icons 1, scenes 41, minimap 3, safepath 24, danger 7, voice 0 | room home, " +
        "game up | low 180 MB, jheap 31/512 MB, gpu 880 MB"

    @Test fun aMemoryLineInShortKeepsTheNumbersAndTheFiveBiggestCaches() {
        assertEquals("18:50:13 pss 390 (j 31 n 210 gx 40) game 4120 free 216 low 180 gpu 880 | scenes 41 safepath 24 danger 7 kit 6 minimap 3",
            MemWatch.shortLine(sample))
        assertEquals("18:44:10 relief (world): let go voice 300 MB; java 140 -> 31 MB",
            MemWatch.shortLine("11-18 18:44:10 relief (world): let go voice 300 MB; java 140 -> 31 MB"))
        assertEquals("18:40:00 trim from Android: running-low: let go safepath 12 MB",
            MemWatch.shortLine("11-18 18:40:00 trim from Android: running-low: let go safepath 12 MB"))
        assertNull(MemWatch.shortLine("11-18 18:40:00 loading screen 3 over after 4 s"))
        assertNull(MemWatch.shortLine("short"))
        assertTrue(MemWatch.shortLine(sample)!!.length < 230)
    }

    private fun at(t: String, l: String) = "11-18 $t $l"
    private fun samp(t: String, pss: Int) = at(t, sample.substring(15).replace("pss 390", "pss $pss"))

    @Test fun theKeyLinesAreTheLastThreeSamplesAndTheLastTrimsAndReliefsInTimeOrder() {
        val lines = listOf(
            at("18:30:00", "relief (game start)"),
            samp("18:30:05", 300),
            at("18:31:00", "relief (world): let go voice 300 MB"),
            samp("18:49:58", 380), samp("18:50:03", 385),
            at("18:50:05", "trim from Android: running-low: let go safepath 12 MB"),
            samp("18:50:08", 388), samp("18:50:13", 390),
            at("18:50:14", "loading screen 3 over after 4 s"))
        val k = MemWatch.keyLines(lines)
        assertEquals(6, k.size)
        assertTrue(k[0].startsWith("18:30:00 relief (game start)"))
        assertTrue(k[1].startsWith("18:31:00 relief (world)"))
        assertTrue(k[2].startsWith("18:50:03 pss 385"))
        assertTrue(k[3].startsWith("18:50:05 trim from Android"))
        assertTrue(k[4].startsWith("18:50:08 pss 388"))
        assertTrue(k[5].startsWith("18:50:13 pss 390"))
    }

    @Test fun theKilledRunsEarlierReliefsStayInItsRecord() {
        val old = (0 until 200).map { i -> samp("17:%02d:%02d".format(i / 60, i % 60), 300 + i) }
        val history = (listOf(at("16:59:00", "relief (world): let go voice 300 MB")) + old + listOf(at("17:10:00", "relief (loading screen 2)")))
            .joinToString("\n") + "\n"
        val now = java.util.Calendar.getInstance().apply { set(java.util.Calendar.MONTH, 10); set(java.util.Calendar.DAY_OF_MONTH, 18)
            set(java.util.Calendar.HOUR_OF_DAY, 17); set(java.util.Calendar.MINUTE, 20); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        val ls = MemWatch.withEvents(history, now)
        assertEquals(MemWatch.BEFORE_LINES + 1, ls.size)                                   // the 72 newest and the earlier relief before them
        assertTrue(ls.first().contains("relief (world)"))
        assertTrue(ls.last().contains("relief (loading screen 2)"))
        assertEquals(MemWatch.linesUpTo(history, now), ls.drop(1))
    }

    @Test fun theDiagnosticsKeyLinesCarryTheStampTheSamplesAndTheEvents() {
        val mem = listOf(at("18:31:00", "relief (world): let go voice 300 MB"), samp("18:50:03", 385), samp("18:50:08", 388),
            samp("18:50:13", 390))
        val record = listOf("DS Forever: how the last run ended", "When: 2026-11-18_18-50-18", "Reason: low-memory (3), status 0",
            "State at the end: dsf 1763600000 pss 390 j 31", "", ReportUpload.MEMORY_MARK) + mem + listOf("", "---------- Trace ----------", "x")
        val k = StartupDiag.memKeyLines(record.joinToString("\n"), null)
        assertEquals("Mem stamp: dsf 1763600000 pss 390 j 31", k[0])
        assertTrue(k[1].startsWith("Mem event: 18:31:00 relief (world)"))
        assertTrue(k[2].startsWith("Mem line: 18:50:03 pss 385"))
        assertTrue(k[4].startsWith("Mem line: 18:50:13 pss 390"))
        assertEquals(5, k.size)
        assertTrue(k.all { it.length <= 230 })
        // Android's own exit record's stamp wins over the record's copy; no record: the stamp alone; neither: nothing
        assertEquals("Mem stamp: dsf 1 pss 1", StartupDiag.memKeyLines(record.joinToString("\n"), "dsf 1 pss 1")[0])
        assertEquals(listOf("Mem stamp: dsf 2"), StartupDiag.memKeyLines(null, "dsf 2"))
        assertTrue(StartupDiag.memKeyLines(null, null).isEmpty())
        // the key lines match the report receiver's low-memory tracker (tools/report-worker trackers.js)
        assertTrue(k.all { Regex("^Mem (stamp|line|event): ").containsMatchIn(it) })
    }

    @Test fun theNewSourcesAreInTheMemoryLineAndThePressure() {
        // every cache D1 / D2 / D4 names registers a memory-line source and a trim (MemWatch.source / onTrim)
        val dir = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { java.io.File(it) }.first { it.isDirectory }
        fun f(n: String) = java.io.File(dir, n).readText()
        assertTrue(f("ShippedNets.kt").contains("MemWatch.source(\"safepath\")") && f("ShippedNets.kt").contains("MemWatch.onTrim(\"safepath\")"))
        assertTrue(f("MinimapTiles.kt").contains("MemWatch.onTrim(\"minimap\")"))
        assertTrue(f("MapView.kt").contains("MemWatch.onTrim(\"plans\")"))
        assertTrue(f("DangerStore.kt").contains("MemWatch.source(\"danger\")") && f("DangerStore.kt").contains("MemWatch.onTrim(\"danger\")"))
        assertTrue(f("UiKit.kt").contains("+ sideBytes()"))
        assertTrue(f("WhisperEngine.kt").contains("MemWatch.onTrim(\"voice\")"))
        assertFalse("no start-up read of every file", f("ShippedCrossStore.kt").contains("for (m in ids)"))
    }
}
