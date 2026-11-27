package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue 612 (and its duplicates 609 and 611): DS Forever ran out of Java heap on the start screen with the game closed. Two parts:
 * (1) a fresh reader on the plain-IO path read the LAST session's whole General.log from byte 0, every old position document going
 * through the map; (2) the map's pin-kind memo was keyed by Poi IDENTITY, and every position update builds new Poi objects, so it
 * grew with every update and kept them all alive.
 */
class StartScreenHeapTest {
    private val mb = 1024L * 1024L

    @Test fun aFreshReaderWithTheGameClosedReadsOnlyTheLogsLastPart() {
        // a 30 MB log of the last session, the game closed: only its last MAX_READ (1 MB), as the root reader's own cap
        assertEquals(30 * mb - LogTail.MAX_READ, LogTail.freshStart(30 * mb, capBacklog = true))
        assertEquals("a short log is read whole", 0L, LogTail.freshStart(200_000L, capBacklog = true))
        assertEquals(0L, LogTail.freshStart(0L, capBacklog = true))
        // the game up (or a relaunch under way): its own session's log from the start, as before (its one-off state documents)
        assertEquals(0L, LogTail.freshStart(30 * mb, capBacklog = false))
    }

    @Test fun thePlainReadCapsOnlyAFreshStartNeverAResumeOrARestart() {
        val t = File(srcDir(), "LogTail.kt").readText()
        val poll = t.substringAfter("private fun poll() {").substringBefore("\n    }\n")
        // the cap is decided once, after the BottomSync resume check (a resumed reader keeps its place and reads all it missed)
        val resume = poll.indexOf("resumeOffset(")
        val cap = poll.indexOf("freshStart(")
        assertTrue("the plain read asks freshStart", cap > 0)
        assertTrue("after the resume check", resume in 0 until cap)
        assertTrue(poll.contains("if (fresh)"))
        // a follow switch to another log is a fresh start too ("from its end-capped backlog")
        assertTrue(t.substringAfter("private fun follow() {").substringBefore("\n    }\n").contains("fresh = true"))
        // the host caps only with the game closed: the start screen's replay of an old session
        val host = File(srcDir(), "CompanionHost.kt").readText()
        val st = host.substringAfter("private fun startTail() {").substringBefore("\n    }\n")
        assertTrue(st.contains("capBacklog = {"))
        assertTrue(st.contains("!(LauncherHooks.gameUp() || BottomSync.relaunchPending("))
    }

    @Test fun theKindMemoIsBoundedByKindsNotByUpdates() {
        var computed = 0
        val memo = PoiKindMemo { flags, title -> computed++; (flags * 31 + title.hashCode()) and 0xff }
        val kinds = listOf(0 to "", 2 to "Innkeeper", 2 to "Weapon Merchant")
        // 20 000 position updates, each with NEW Poi objects for the same three NPCs (zoneRows / qdbRows build them per call)
        repeat(20_000) { u ->
            for ((i, k) in kinds.withIndex()) {
                val p = PoiStore.Poi(100 + i, "npc$i", k.second, k.first, u.toDouble(), 0.0, 0, 1)
                assertEquals((k.first * 31 + k.second.hashCode()) and 0xff, memo.of(p))
            }
        }
        assertEquals(3, memo.size)
        assertEquals("the regex once per kind", 3, computed)
    }

    @Test fun theKindMemoNeverGrowsPastItsCap() {
        val memo = PoiKindMemo { _, _ -> 1 }
        repeat(PoiKindMemo.MAX * 3) { memo.of(it, "t") }
        assertTrue(memo.size <= PoiKindMemo.MAX)
    }

    @Test fun theMapUsesTheBoundedMemo() {
        val m = File(srcDir(), "MapView.kt").readText()
        assertFalse(m.contains("IdentityHashMap<PoiStore.Poi"))
        assertTrue(m.contains("PoiKindMemo {"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
}
