package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TEST BUILD 1529, "8 GB memory fixes" (the user's pick 2026-11-24: "Drop the 30 s game memory walk, read our own memory less often"):
 * Debug.getMemoryInfo (a walk of DS Forever's own memory map) every [MemWatch.MEMINFO_WORLD_MS] while the game is in the world, not at
 * every 5 s line. Measured here: the walks in an hour in the world, before and after.
 */
class MemSplitCadenceTest {
    @Test fun inTheWorldTheSplitIsReadEveryThirtySeconds() {
        assertTrue(MemWatch.memInfoDue(5_000L, 0L, inWorld = true))                  // none yet
        assertFalse(MemWatch.memInfoDue(10_000L, 5_000L, inWorld = true))
        assertFalse(MemWatch.memInfoDue(34_999L, 5_000L, inWorld = true))
        assertTrue(MemWatch.memInfoDue(35_000L, 5_000L, inWorld = true))
        assertTrue(MemWatch.memInfoDue(10_000L, 5_000L, inWorld = false))            // the menus, a load: every line as before
        assertTrue(MemWatch.memInfoDue(1_000L, 5_000L, inWorld = true))              // the clock went back
    }

    @Test fun anHourInTheWorldWalksOurMemory120TimesNot720() {
        fun walks(inWorld: Boolean): Int {
            var last = 0L; var n = 0
            var t = 5_000L
            while (t <= 3_600_000L) { if (MemWatch.memInfoDue(t, last, inWorld)) { n++; last = t }; t += 5_000L }
            return n
        }
        val before = walks(inWorld = false)          // = 1528's cadence: every 5 s line
        val after = walks(inWorld = true)
        println("MemWatch: Debug.getMemoryInfo per hour in the world: $before before, $after after")
        assertEquals(720, before)
        assertEquals(120, after)
    }

    @Test fun aKeptSplitSaysHowOldItIsAndOurResidentSize() {
        assertEquals("", MemWatch.splitNote(true, 0L, 600L * 1024))
        assertEquals(", split 15 s old, own rss 612 MB", MemWatch.splitNote(false, 15_400L, 612L * 1024))
        assertEquals(", split 5 s old", MemWatch.splitNote(false, 5_000L, null))
        // the short form for the report's key lines still reads a line with the note
        val l = "11-24 10:00:00 pss 390 MB (java 31, native 210, gfx 40, code 19, stk 1, oth 40, sys 12; swp 128) heap 180/200 MB | game 4120 MB (13, rss), " +
            "free 216 MB, swap 3204/4096 MB | climb 9 | room home, game up | low 180 MB, jheap 31/512 MB, split 15 s old, own rss 612 MB"
        assertEquals("10:00:00 pss 390 (j 31 n 210 gx 40 sw 128) game 4120 free 216 low 180 | climb 9", MemWatch.shortLine(l))
    }

    @Test fun theLineKeepsTheSplitBetweenReadsInTheWorld() {
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        val mem = File(src, "MemWatch.kt").readText()
        val line = mem.substring(mem.indexOf("    fun line(): String {"), mem.indexOf("     * 1.0.19 (the 1.0.18 reports"))
        assertTrue(line.contains("val fresh = kept == null || memInfoDue(nowUp, miAt, inWorld)"))
        assertTrue(line.contains("val mi = if (fresh) Debug.MemoryInfo().also { Debug.getMemoryInfo(it); lastMi = it; miAt = nowUp } else kept!!"))
        assertEquals("one getMemoryInfo in the line, behind the cadence", 1, Regex("Debug\\.getMemoryInfo\\(").findAll(line).count())
    }
}
