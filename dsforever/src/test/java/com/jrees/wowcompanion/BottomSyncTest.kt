package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHEN A REBUILD CANNOT BE AVOIDED (the bottom display truly gone and back, Android closing the window by itself, a memory release):
 * the old host's small snapshot (its room, whether the game was in the world, the reader's place) for the new one. The user's pick
 * (2026-11-25, Q3): the SAME room only while the game still has that room's window open (the capture's holder holds for it), else Home.
 * Another game run or an old snapshot = no restore. Nothing in it holds a view, a context or a host.
 */
class BottomSyncTest {
    @After fun clean() = BottomSync.clear()

    private fun snap(at: Long = 1_000L, run: Long = 77L, room: String = "BAGS", inWorld: Boolean = true) =
        BottomSync.Snapshot(at = at, gameRun = run, room = room, inWorld = inWorld, place = null, why = "test")

    @Test fun freshOnlyForTheSameGameRunAndNotTooOld() {
        val s = snap()
        assertTrue(BottomSync.fresh(s, now = 2_000L, gameRun = 77L))
        assertFalse("another game run", BottomSync.fresh(s, 2_000L, 78L))
        assertFalse("too old", BottomSync.fresh(s, 1_000L + BottomSync.RESTORE_MAX_MS, 77L))
        assertTrue(BottomSync.fresh(s, 1_000L + BottomSync.RESTORE_MAX_MS - 1, 77L))
        assertFalse(BottomSync.fresh(null, 2_000L, 77L))
        assertEquals(30 * 60_000L, BottomSync.RESTORE_MAX_MS)
    }

    @Test fun theSnapshotRoundTripIsTakenOnce() {
        val s = snap()
        BottomSync.keep(s)
        assertSame(s, BottomSync.last)
        // the old host's reader place joins it at that host's end (its onDestroy, after the reader stopped)
        val p = LogTail.Place("/g/General.log", 123_456L, "WCB1|9|1/2|{\"ty", ByteArray(0), byteArrayOf(1, 2, 3))
        BottomSync.place(p, gameRun = 77L)
        assertEquals(p, BottomSync.last?.place)
        val t = BottomSync.take(now = 2_000L, gameRun = 77L)
        assertEquals("BAGS", t?.room)
        assertEquals(p, t?.place)
        assertNull("once", BottomSync.take(2_000L, 77L))
        // a stale one is dropped by take
        BottomSync.keep(snap(run = 1L))
        assertNull(BottomSync.take(2_000L, 77L))
        assertNull(BottomSync.last)
        // another run's place never joins
        BottomSync.keep(snap())
        BottomSync.place(p, gameRun = 5L)
        assertNull(BottomSync.last?.place)
    }

    @Test fun theRoomOnlyWhileTheGameStillHasItsWindowOpen() {
        val roomsOf: (String) -> Set<String> = { HomeBack.roomsOf(it) }
        assertEquals("BAGS", BottomSync.roomAfter("BAGS", holder = "bags", roomsOf = roomsOf))
        assertEquals("TALENTS", BottomSync.roomAfter("TALENTS", "spells", roomsOf))
        assertEquals("SETTINGS", BottomSync.roomAfter("SETTINGS", "pad:settings", roomsOf))
        assertEquals("MAP", BottomSync.roomAfter("MAP", "map", roomsOf))
        assertNull("the game's window closed meanwhile: Home", BottomSync.roomAfter("BAGS", "", roomsOf))
        assertNull("another room's window: Home", BottomSync.roomAfter("BAGS", "spells", roomsOf))
        assertNull("Home is Home", BottomSync.roomAfter("HOME", "bags", roomsOf))
        assertNull(BottomSync.roomAfter(null, "bags", roomsOf))
    }

    @Test fun theWaitingScreenStartsGoneOnlyInTheWorld() {
        assertTrue(BottomSync.waitingGone(snap(inWorld = true), gameUp = true, now = 2_000L, nowUp = 2_000L))
        assertFalse(BottomSync.waitingGone(snap(inWorld = false), gameUp = true, now = 2_000L, nowUp = 2_000L))
        assertFalse(BottomSync.waitingGone(snap(inWorld = true), gameUp = false, now = 2_000L, nowUp = 2_000L))
        assertFalse(BottomSync.waitingGone(null, gameUp = true, now = 2_000L, nowUp = 2_000L))
        assertTrue(BottomSync.inWorld(lastCurrentAt = 10_000L, now = 10_000L + BottomSync.IN_WORLD_MS - 1))
        assertFalse(BottomSync.inWorld(lastCurrentAt = 10_000L, now = 10_000L + BottomSync.IN_WORLD_MS))
        assertFalse(BottomSync.inWorld(lastCurrentAt = 0L, now = 5L))
    }

    @Test fun theReportSaysThePath() {
        BottomSync.parked(); BottomSync.parked()
        BottomSync.rebuilt("closed by Android")
        BottomSync.back(awayMs = 60_000L, workMs = 3L)
        BottomSync.caughtUp(240L)
        BottomSync.released()
        BottomSync.reopenLook()
        val l = BottomSync.line()
        assertTrue(l, l.startsWith("Bottom screen: kept 2"))
        assertTrue(l, l.contains("shown again 1 (the last after 1 min away, 3 ms of ours, caught up in 240 ms)"))
        assertTrue(l, l.contains("rebuilt 1 (closed by Android 1)"))
        assertTrue(l, l.contains("let go for memory 1"))
        assertTrue(l, l.contains("reopen looks 1"))
        BottomSync.clear()
        assertEquals("Bottom screen: kept 0, shown again 0, rebuilt 0", BottomSync.line())
    }

    @Test fun theSnapshotAndTheDocumentMemoryHoldNoHost() {
        val held = listOf(BottomSync::class.java, BottomSync.Snapshot::class.java, LogTail.Place::class.java, DocMemory::class.java,
            DocMemory.Store::class.java).flatMap { c -> c.declaredFields.map { c.simpleName + "." + it.name to it.type } }
        for ((n, t) in held) for (bad in listOf("android.view.", "android.content.Context", "CompanionHost", "android.app."))
            assertFalse("$n holds ${t.name}", t.name.startsWith(bad) || t.name.contains("CompanionHost"))
        for (f in listOf("BottomSync.kt", "DocMemory.kt")) {
            val src = File(srcDir(), f).readText()
            assertFalse("$f: pure (no android.*)", src.contains("import android."))
            assertFalse("$f: never written to disk", src.contains("java.io.File") || src.contains("FileOutputStream") || src.contains("SharedPreferences"))
        }
        assertTrue("the report's line", File(srcDir(), "ProblemReport.kt").readText().contains("add(BottomSync.line())"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
}
