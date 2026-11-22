package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #56 (11-22): the memory line counts stk / oth / sys / swp on every Thor (counting only), and long sessions get a timed relief of
 * DS Forever's own rebuildable caches - every 20 min since the last relief, in the world, no loading screen, a calm world, out of a
 * fight - measured (PSS before and after) and logged. Off-device: the pure rules, what a relief at its level lets go, the wiring.
 */
class MemTimedReliefTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private fun text(f: String) = File(src, f).readText()

    @Test fun dueOnlyInACalmWorldAfterTwentyMinutes() {
        val every = MemWatch.TIMED_EVERY_MS; val calm = MemWatch.TIMED_CALM_MS
        assertTrue(MemWatch.timedDue(every, inWorld = true, loading = false, calmMs = calm))
        assertFalse(MemWatch.timedDue(every - 1, true, false, calm))            // too soon after the last relief
        assertFalse(MemWatch.timedDue(every, false, false, calm))               // not in the world (login, character list, streaming)
        assertFalse(MemWatch.timedDue(every, true, true, calm))                 // a loading screen up (its own relief comes)
        assertFalse(MemWatch.timedDue(every, true, false, calm - 1))            // just after a load
        assertEquals(20 * 60_000L, every)
        assertEquals("timed, 24 min since the last", MemWatch.timedWhy(24 * 60_000L + 5_000L))
        assertEquals("pss 612 -> 540 MB", MemWatch.pssNote(612, 540))
    }

    @Test fun theReliefLineSaysWhatWentAndThePss() {
        val l = MemWatch.trimLine("relief (timed, 24 min since the last)", listOf("minimap" to 24L * 1024 * 1024, "kit" to 0L, "plans" to 9L * 1024 * 1024),
            listOf("java 140 -> 31 MB", "native purge 40 MB", MemWatch.pssNote(612, 540)).joinToString("; "), "psi some 2.10")
        assertEquals("relief (timed, 24 min since the last): let go minimap 24 MB, plans 9 MB; java 140 -> 31 MB; native purge 40 MB; " +
            "pss 612 -> 540 MB; psi some 2.10", l)
        assertTrue(MemWatch.isEvent(l))                                          // kept with the killed run's record (withEvents)
    }

    @Test fun aReliefLetsGoOfWhatIsNotOnShowOnly() {
        // the same level as the loading screens' relief: a hidden cache lets go, a shown one keeps what is on show
        assertTrue(MemWatch.hiddenLetsGo(MemWatch.RELIEF_LEVEL, shown = false))
        assertFalse(MemWatch.hiddenLetsGo(MemWatch.RELIEF_LEVEL, shown = true))
        // measured: a registered cache's bytes through the relief's own let-go
        var held = 30L * 1024 * 1024
        MemWatch.onTrim("timedtest") { level -> if (level >= MemWatch.RELIEF_LEVEL) held.also { held = 0L } else 0L }
        @Suppress("UNCHECKED_CAST")
        val freed = MemWatch::class.java.getDeclaredMethod("letGo", Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(MemWatch, MemWatch.RELIEF_LEVEL) as List<Pair<String, Long>>
        assertEquals(30L * 1024 * 1024, freed.first { it.first == "timedtest" }.second)
        assertEquals(0L, held)
        MemWatch.onTrim("timedtest") { 0L }
    }

    @Test fun wiredIntoTheWatchAndTheHost() {
        val mem = text("MemWatch.kt")
        assertTrue(mem.contains("runCatching { timedTick(h) }"))
        // every relief resets the clock (the world entry's, the loading screens', the art's)
        val relieve = mem.substring(mem.indexOf("fun relieve(why: String"), mem.indexOf("fun relieve(why: String") + 400)
        assertTrue(relieve.contains("reliefAt = SystemClock.uptimeMillis()"))
        // the fight check on the UI thread, the work on the watch's thread, PSS before and after, never the game's settings
        val tick = mem.substring(mem.indexOf("private fun timedTick("), mem.indexOf("private fun pssMbNow()"))
        assertTrue(tick.contains("Handler(Looper.getMainLooper()).post"))
        assertTrue(tick.contains("fightNow()"))
        assertTrue(tick.contains("letGo(RELIEF_LEVEL)"))
        assertTrue(tick.contains("pssNote(before, after)"))
        assertFalse(tick.contains("Config.wtf") || tick.contains("SetCVar"))
        assertTrue(text("CompanionHost.kt").contains("MemWatch.fightNow = { homeView.inFight() || lastBook?.combat == true }"))
    }

    @Test fun theCountingFieldsOnEveryThor() {
        val mem = text("MemWatch.kt")
        assertTrue(mem.contains("val swp = mb(stat(\"summary.total-swap\"))"))
        assertTrue(mem.contains("\"code \${mb(stat(\"summary.code\"))}\" + light8Parts(mb(stat(\"summary.stack\"))"))
        // a 16 GB line in short and its exit stamp carry the swap
        val line = "11-22 10:00:00 pss 880 MB (java 60, native 610, gfx 80, code 30, stk 1, oth 52, sys 47; swp 310) heap 600/640 MB | " +
            "game 4400 MB (13), free 5200 MB, swap 2100/4096 MB | kit 6, scenes 41 | room home, game up | low 5100 MB, jheap 60/512 MB"
        assertEquals("10:00:00 pss 880 (j 60 n 610 gx 80 sw 310) game 4400 free 5200 low 5100 | scenes 41 kit 6", MemWatch.shortLine(line))
        assertEquals("dsf 1 pss 880 j 60 n 610 gx 80 sw 310", MemWatch.stateSummary(1L, 880, 60, 610, 80, -1, -1, -1, -1, emptyList(), swp = 310))
    }
}
