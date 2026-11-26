package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.BottomSync
import com.jrees.wowcompanion.DocMemory
import com.jrees.wowcompanion.LogTail
import com.jrees.wowcompanion.Wcb1Assembler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK - THE REVIEW (2026-11-26) of feat/bottom-sync: what the independent reviewers found, each as a
 * pure check or a check of the source (the real lifecycle ones are app/BottomParkLifecycleTest's): a configuration relaunch keeps the
 * game run; a failed open never uses up the snapshot; a second rebuild before the first settled carries the restore forward; a
 * document split by the reader's place is joined by the next reader; the snapshot's age counts deep sleep; the report's line says each
 * return's own catch-up and every rebuild; the document memory counts its bytes, keeps only this game run's documents and is on the
 * memory line; the top window's browser page types again after a return; an activity over the bottom screen never shows a parked
 * window; a parked host that goes gives the game's UI back; F5's look again never moves a launcher the user left; a page that cannot
 * be made never takes the window down; a host that goes is let go (its ticks off the main looper); Chat's watch stops while away.
 */
class BottomSyncReviewTest {
    @After fun clean() { BottomSync.clear(); DocMemory.shared.clear(); DocMemory.on = false }

    private fun snap(at: Long = 1_000L, run: Long = 77L, room: String = "BAGS", inWorld: Boolean = true, place: LogTail.Place? = null) =
        BottomSync.Snapshot(at = at, gameRun = run, room = room, inWorld = inWorld, place = place, why = "test")
    private val place = LogTail.Place("/g/Logs/General.log", 5_000L, "", ByteArray(0), byteArrayOf(1, 2, 3))

    // ---- a configuration relaunch keeps the game run (findings 1 and 7: D4)

    @Test fun aConfigurationRelaunchKeepsTheGameRun() {
        BottomSync.keep(snap(run = 1_000L))
        BottomSync.relaunching(1_000L, now = 10_000L)
        assertTrue("the same game", BottomSync.attached(1_764_000_000L, now = 10_500L))
        assertEquals("re-keyed to the new stamp", 1_764_000_000L, BottomSync.last?.gameRun)
        assertNotNull(BottomSync.take(now = 11_000L, gameRun = 1_764_000_000L, nowUp = 11_000L))
        // the second relaunch (the move back to the top screen): stamped again, re-keyed again
        BottomSync.keep(snap(run = 2L)); BottomSync.relaunching(2L, 20_000L)
        assertTrue(BottomSync.attached(3L, 20_100L)); assertEquals(3L, BottomSync.last?.gameRun)
        // a new game: no relaunch mark, a mark too old, another run's snapshot - nothing re-keyed
        BottomSync.keep(snap(run = 4L))
        assertFalse(BottomSync.attached(5L, 30_000L)); assertEquals(4L, BottomSync.last?.gameRun)
        BottomSync.relaunching(4L, 30_000L)
        assertFalse(BottomSync.attached(6L, 30_000L + BottomSync.RELAUNCH_MS)); assertEquals(4L, BottomSync.last?.gameRun)
        BottomSync.relaunching(9L, 40_000L)
        assertTrue("the same game, but the snapshot is another run's", BottomSync.attached(10L, 40_001L)); assertEquals(4L, BottomSync.last?.gameRun)
        // the mark is used once
        BottomSync.relaunching(4L, 50_000L)
        assertTrue(BottomSync.attached(11L, 50_001L)); assertFalse(BottomSync.attached(12L, 50_002L))
        // DsForeverScreen: stop() marks it (the game running) before detachGame; attachGame stamps, then asks; a new run's documents go
        val f = source()
        val stop = f.substringAfter("    fun stop() {").substringBefore("\n    }\n")
        assertTrue(stop.indexOf("BottomSync.relaunching(") in 0 until stop.indexOf("detachGame()"))
        assertTrue(stop.contains("if (relaunching && gameOn)"))
        val attach = f.substringAfter("fun attachGame(sink: InputSink.Sink) {").substringBefore("\n    }\n")
        assertTrue(attach.indexOf("LauncherHooks.gameStartSec = ") in 0 until attach.indexOf("BottomSync.attached("))
        assertTrue("a new run keeps nothing of before", attach.contains("DocMemory.shared.clear()"))
    }

    // ---- a failed open never uses up the snapshot (finding 8)

    @Test fun aFailedOpenGivesTheSnapshotBack() {
        BottomSync.hostMade(null)                                   // the run's first host
        val s = snap(); BottomSync.keep(s)
        BottomSync.expectRebuild("test")                            // (round 3: counted only after a comeback path's mark)
        val t = BottomSync.take(now = 2_000L, gameRun = 77L, nowUp = 2_000L)        // the new host's onCreate (hostRestore)
        val mark = BottomSync.hostMade(t)
        assertNull(BottomSync.last)
        BottomSync.hostAbandoned(t, mark)                           // the window manager refused the display: its host goes
        assertSame("given back for the watchdog's next try", t, BottomSync.last)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 0"))
        BottomSync.hostMade(BottomSync.take(3_000L, 77L, 3_000L))           // the next try: restored
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (test 1)"))
        // the windows' abandon goes through the host's (the snapshot back BEFORE its teardown joins the reader's place)
        val p = source().substringAfter("private class HostPresentation(")
        assertTrue(p.substringAfter("fun abandon(shownOk: Boolean = false) {").substringBefore("\n    }\n").contains("host?.abandon()"))
        assertTrue(source("TopWindow.kt").substringAfter("fun abandon() {").substringBefore("\n    }\n").contains("host?.abandon()"))
        val ab = host().substringAfter("    fun abandon() {").substringBefore("\n    }\n")
        assertTrue(ab.indexOf("BottomSync.hostAbandoned(restored, rebuildMark)") in 0 until ab.indexOf("onDestroy()"))
    }

    // ---- a second rebuild before the first settled (finding 9)

    @Test fun aSecondRebuildBeforeTheFirstSettledCarriesTheRestore() {
        val r = snap(at = 1_000L, room = "BAGS", inWorld = true, place = place)
        // Android closes the window again at the wake: no current document yet, the room still waiting for the adopt rule
        val s = BottomSync.snapshotOf(r, settled = false, room = "HOME", restoreRoom = "BAGS", inWorldNow = false, place = null,
            now = 9_000L, gameRun = 77L, why = "closed by Android")
        assertEquals("BAGS", s.room); assertTrue(s.inWorld); assertSame(place, s.place); assertEquals("its age is the first's", 1_000L, s.at)
        assertEquals("closed by Android", s.why)
        // its own reader's place wins over the old one
        val p2 = place.copy(offset = 7_000L)
        assertSame(p2, BottomSync.snapshotOf(r, false, "HOME", "BAGS", false, p2, 9_000L, 77L, "x").place)
        // the user picked a room meanwhile (show() cleared the restore): that room
        assertEquals("MAP", BottomSync.snapshotOf(r, false, "MAP", null, false, null, 9_000L, 77L, "x").room)
        // settled (a current document came): what it shows now
        val n = BottomSync.snapshotOf(r, settled = true, room = "HOME", restoreRoom = null, inWorldNow = false, place = p2, now = 9_000L, gameRun = 77L, why = "x")
        assertEquals("HOME", n.room); assertFalse(n.inWorld); assertEquals(9_000L, n.at); assertSame(p2, n.place)
        // a host that was never restored: as before
        val plain = BottomSync.snapshotOf(null, false, "SOCIAL", null, true, null, 9_000L, 77L, "x")
        assertEquals("SOCIAL", plain.room); assertTrue(plain.inWorld); assertNull(plain.place); assertEquals(9_000L, plain.at)
        // the host builds it so; its reader reports the place it resumed from before it has read anything new
        val snapFn = host().substringAfter("    fun snapshot(why: String): BottomSync.Snapshot? {").substringBefore("\n    }\n")
        assertTrue(snapFn.contains("BottomSync.snapshotOf(restored, docLiveAt > 0L, room.name, restoreRoom,"))
        val resume = tail().substringAfter("resumeFrom?.let { p ->").substringBefore("\n        }\n")
        assertTrue(resume.contains("placeNow = p"))
    }

    // ---- a multi-part document split by the place (finding 10)

    @Test fun aDocumentSplitByThePlaceIsJoinedByTheNextReader() {
        val first = ArrayList<JSONObject>()
        val a = Wcb1Assembler({ first += it }, {})
        a.line("9/27 08:41:53.221  [N][Lua] WCB1|7|1/2|{\"type\":\"quests\",\"q\":")
        assertTrue("half of it", first.isEmpty())
        val parts = a.pendingParts(); val seq = a.seqNow
        // the old reader goes on unchanged (a copy)
        assertEquals(1, a.pending())
        val got = ArrayList<JSONObject>(); val said = ArrayList<String>()
        val b = Wcb1Assembler({ got += it }, { said += it })
        b.adopt(parts, seq)
        b.line("9/27 08:41:53.300  [N][Lua] WCB1|8|2/2|{\"n\":3}}")
        assertEquals("joined", 1, got.size)
        assertEquals("quests", got[0].optString("type")); assertEquals(3, got[0].optJSONObject("q")?.optInt("n"))
        assertTrue("no gap: the sequence goes on ($said)", said.isEmpty())
        // a reader that starts empty loses it (what a rebuilt host did before)
        val lost = ArrayList<JSONObject>()
        Wcb1Assembler({ lost += it }, {}).line("9/27 08:41:53.300  [N][Lua] WCB1|8|2/2|{\"n\":3}}")
        assertTrue(lost.isEmpty())
        // the place carries them; the resume gives them to the new reader's assembler
        val t = tail()
        assertTrue(t.substringAfter("private fun feed(").substringBefore("\n    }\n").contains("docs.pendingParts(), docs.seqNow)"))
        assertTrue(t.substringAfter("resumeFrom?.let { p ->").substringBefore("\n        }\n").contains("docs.adopt(p.parts, p.seq)"))
    }

    // ---- the snapshot's age counts deep sleep (finding 11)

    @Test fun theSnapshotAgeCountsDeepSleep() {
        // round 2 (2026-11-26): the time ASLEEP since (elapsedRealtime less uptime) decides the waiting screen, never the time away -
        // a minimize of any length (the game ran on) shows none, a sleep of IN_WORLD_MS does
        val m = BottomSync.IN_WORLD_MS * 5
        assertTrue(BottomSync.waitingGone(snap(at = 1_000L), gameUp = true, now = 1_000L + m, nowUp = 1_000L + m))
        assertTrue(BottomSync.waitingGone(snap(at = 1_000L), gameUp = true, now = 1_000L + BottomSync.IN_WORLD_MS - 1, nowUp = 1_000L))
        assertFalse("a long sleep shows the waiting screen until the first current document",
            BottomSync.waitingGone(snap(at = 1_000L), gameUp = true, now = 1_000L + BottomSync.IN_WORLD_MS, nowUp = 1_000L))
        assertFalse(BottomSync.waitingGone(snap(inWorld = false), true, 1_000L, 1_000L))
        assertFalse(BottomSync.waitingGone(snap(), false, 1_000L, 1_000L))
        // elapsedRealtime (it counts the sleep) wherever a snapshot is stamped or aged; uptime beside it only to measure the sleep
        val h = host()
        val snapFn = h.substringAfter("    fun snapshot(why: String): BottomSync.Snapshot? {").substringBefore("\n    }\n")
        assertTrue(snapFn.contains("val now = SystemClock.elapsedRealtime(); val nowUp = SystemClock.uptimeMillis()"))
        assertTrue(h.contains("SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())) startView.visibility = View.GONE"))
        for ((name, s) in listOf("DsForeverScreen.kt" to source(), "TopWindow.kt" to source("TopWindow.kt"))) {
            val restore = s.substringAfter("override fun hostRestore() = ").substringBefore("\n")
            assertTrue(name, restore.contains("BottomSync.take(android.os.SystemClock.elapsedRealtime()"))
        }
        val start = source().substringAfter("    fun start() {").substringBefore("\n    }\n")
        assertTrue(start.contains("BottomSync.last, android.os.SystemClock.elapsedRealtime()"))
        assertTrue(File(srcDir(), "BottomSync.kt").readText().contains("[at] SystemClock.elapsedRealtime ms"))
    }

    // ---- the report's line (finding 14)

    @Test fun eachReturnSaysItsOwnCatchUpAndEveryRebuildIsCounted() {
        BottomSync.back(awayMs = 5_000L, workMs = 2L); BottomSync.caughtUp(180L)
        assertTrue(BottomSync.line(), BottomSync.line().contains("caught up in 180 ms"))
        BottomSync.back(awayMs = 1_800_000L, workMs = 3L)           // a long sleep: no current document yet
        assertFalse("the last return's own time only", BottomSync.line().contains("caught up"))
        BottomSync.caughtUp(90L)
        assertTrue(BottomSync.line(), BottomSync.line().contains("caught up in 90 ms"))
        BottomSync.hostMade(null)                                  // the run's first host: no rebuild
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 0"))
        BottomSync.hostMade(null)                                  // round 2: no comeback path closed the window (the single screen switch)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 0"))
        BottomSync.expectRebuild("closed by Android")
        BottomSync.hostMade(null)                                  // a host made again with no snapshot (the game not up, another run)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (no snapshot 1)"))
        assertFalse("a rebuild's time is its own", BottomSync.line().contains("rebuild caught up"))
        assertTrue("the last return's stays in its brackets", BottomSync.line().contains("caught up in 90 ms)"))
        BottomSync.hostMade(snap())                                // round 3: a snapshot with no comeback path's mark (a host's own start over)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (no snapshot 1)"))
        BottomSync.expectRebuild("test"); BottomSync.hostMade(snap())
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 2 (no snapshot 1, test 1)"))
        assertTrue(host().substringAfter("restored = shell.hostRestore()").substringBefore("\n").contains("BottomSync.hostMade(restored)"))
    }

    // ---- the document memory (findings 11 / DocMemory and 15)

    @Test fun theDocumentMemoryCountsBytesKeepsThisRunAndIsOnTheMemoryLine() {
        assertEquals(10L, DocMemory.bytesOf("abcdefghij"))
        assertEquals("a letter past ASCII: two bytes a char", 20L, DocMemory.bytesOf("abcdefghié"))
        val s = DocMemory.Store(capBytes = 30)
        s.put("a", "ñ".repeat(10)); s.put("b", "x".repeat(10))
        assertEquals(listOf("a", "b"), s.keys()); assertEquals(30L, s.bytes)
        s.put("c", "y".repeat(5))
        assertEquals("the oldest goes", listOf("b", "c"), s.keys()); assertEquals(15L, s.bytes)
        // a client restart (the log truncated) keeps nothing of the old client's documents
        DocMemory.on = true
        DocMemory.note(JSONObject().put("type", "quests"))
        DocMemory.readerSaid("following the game's log: WoW Forever")
        assertEquals(1, DocMemory.shared.size())
        DocMemory.readerSaid(LogTail.RESTARTED)
        assertEquals(0, DocMemory.shared.size())
        val t = tail()
        assertEquals(2, Regex("onStatus\\(RESTARTED\\)").findAll(t).count())
        val h = host()
        val st = h.substringAfter("private fun startTail() {").substringBefore("\n    }\n")
        assertTrue("only with the game up (the start screens read the last session's log; round 2: a relaunch's game is up)",
            st.contains("else if (LauncherHooks.gameUp() || BottomSync.relaunchPending(android.os.SystemClock.elapsedRealtime())) DocMemory.note(msg)"))
        assertTrue("on the reader thread, before the hand-off", st.contains("if (!streaming) DocMemory.readerSaid(s); ui.post {"))
        assertTrue("on Light 8's memory line", h.contains("MemWatch.source8(\"docmemory\") { DocMemory.shared.bytes }"))
    }

    // ---- the top window's page after a return (finding 2)

    @Test fun theTopWindowsPageTypesAgainAfterAReturn() {
        val top = source("TopWindow.kt")
        val unpark = top.substringAfter("override fun unpark(why: String) {").substringBefore("\n    }\n")
        assertTrue(unpark.contains("if (pageSlot.up) typer.focusable(true)"))
        assertTrue("after the look", unpark.indexOf("look(l)") in 0 until unpark.indexOf("if (pageSlot.up) typer.focusable(true)"))
        val page = top.substringAfter("override fun page(make: ((Context) -> View)?) {").substringBefore("\n    }\n")
        assertTrue("parked: never focusable", page.contains("if (parkedNow && typing) typer.focusable(false)"))
    }

    // ---- an activity of ours over the bottom screen (finding 3)

    @Test fun anActivityOverTheBottomScreenNeverShowsAParkedWindow() {
        val h = host()
        assertTrue(h.contains("fun hostHeld(on: Boolean, cls: Class<*>? = null): Boolean = false"))
        val open = h.substringAfter("private fun openOnBottom(").substringBefore("\n    }\n")
        assertTrue(open.contains("if (!shell.hostHeld(on, cls)) decor?.visibility"))
        assertFalse("never the raw decor shown from the hook", open.contains("ui.post { decor?.visibility = View.VISIBLE"))
        val p = source().substringAfter("private class HostPresentation(")
        val held = p.substringAfter("override fun hostHeld(on: Boolean, cls: Class<*>?): Boolean {").substringBefore("\n    }\n")
        assertTrue(held.contains("if (up && !parkedNow)"))
        val unpark = p.substringAfter("override fun unpark(").substringBefore("\n    }\n")
        assertTrue(unpark.contains("decorView?.visibility = if (held) View.GONE else View.VISIBLE"))
    }

    // ---- a parked host that goes gives the game's UI back (finding 4)

    @Test fun aParkedHostThatGoesGivesTheGamesUiBack() {
        // round 2 (2026-11-26): the other way round - a parked host that goes records "not in view" UNDER the park's hold (nothing tapped
        // on the top screen while nobody sees it), and the hold goes after, whatever the body does (the unpack return included); the game's
        // UI comes back from DsForeverScreen when a return finds no bottom window (BottomSyncRound2Test.aParkedHostThatGoesTapsNothing)
        val stop = host().substringAfter("fun onStop(gameHidden: Boolean = false, giveBack: Boolean = false) {").substringBefore("\n    }\n")
        assertTrue(stop.contains("try { stopBody(gameHidden) } finally { if (!gameHidden) unholdPark() }"))
        val body = host().substringAfter("private fun stopBody(gameHidden: Boolean) {").substringBefore("\n    }\n")
        assertTrue(body.indexOf("if (unpacking) return") in 0 until body.indexOf("if (!gameHidden) GameUi.onVisible(false)"))
        val un = host().substringAfter("private fun unholdPark() {").substringBefore("\n    }\n")
        assertTrue(un.contains("GameUi.holdTaps(tapHoldBefore)") && un.contains("GameInput.away = false") && un.contains("parkedAt = 0L"))
    }

    // ---- F5's look again (finding 5)

    @Test fun theLookAgainNeverMovesALauncherTheUserLeft() {
        assertTrue(DsForeverScreen.moveAgainWanted(finishing = false, destroyed = false, display = 4, target = 0, shownThere = true))
        assertFalse("the user went Home / slept meanwhile", DsForeverScreen.moveAgainWanted(false, false, 4, 0, shownThere = false))
        assertFalse(DsForeverScreen.moveAgainWanted(true, false, 4, 0, true))
        assertFalse(DsForeverScreen.moveAgainWanted(false, true, 4, 0, true))
        assertFalse("on the top screen already", DsForeverScreen.moveAgainWanted(false, false, 0, 0, true))
        val m = source().substringAfter("fun moveToTop(activity: Activity, gameRunning: Boolean): Boolean {").substringBefore("if (move == Move.NONE) return false")
        assertTrue(m.contains("moveAgainWanted(activity.isFinishing, activity.isDestroyed, activity.display?.displayId, target, there)"))
        assertTrue("its DS Forever shown there", m.contains("it.running && !it.hidden"))
        assertTrue("dropped: asked again at the next resume", m.contains("else movedAgain = false"))
    }

    // ---- a page that cannot be made (finding 6)

    @Test fun aPageThatCannotBeMadeNeverTakesTheWindowDown() {
        val f = source()
        for (fn in listOf("private fun openBottom(", "private fun openTop(")) {
            val body = f.substringAfter(fn).substringBefore("\n    }\n")
            val tryPart = body.substringAfter("try {").substringBefore("} catch")
            assertTrue("$fn: only the window's show is tried", tryPart.contains("it.show()"))
            assertFalse("$fn: the board is not in the try", tryPart.contains("board("))
            assertFalse("$fn: the page is not in the try", tryPart.contains("page("))
            assertTrue("$fn: then its add-ons, once it is up", body.substringAfter("} catch").contains("addOns("))
        }
        val add = f.substringAfter("private fun addOns(w: BottomWindow) {").substringBefore("\n    }\n")
        assertTrue("the board on its own", add.contains("runCatching { w.board(boardWanted) }"))
        assertTrue("the page on its own", add.contains("runCatching { w.page(m) }"))
    }

    // ---- a host that goes is let go (finding 12) and Chat's watch while away (finding 13)

    @Test fun aHostThatGoesTakesItsTicksOffTheMainLooper() {
        val h = host()
        val d = h.substringAfter("    fun onDestroy() {").substringBefore("\n    }\n")
        assertTrue(d.contains("for (r in repeatingTicks()) ui.removeCallbacks(r)"))
        val second = h.substringAfter("private val secondTick = object : Runnable {").substringBefore("\n    }\n")
        assertTrue(second.substringAfter("override fun run() {").trimStart().startsWith("if (shell.hostGone()) return"))
        assertTrue(h.substringAfter("private val padOwnerTick = object : Runnable {").substringBefore("\n    }\n").contains("if (shell.hostGone()) return"))
        // Chat's twice-a-second watch stops while away and starts again at the return
        assertTrue(h.substringAfter("    fun park(why: String) {").substringBefore("\n    }\n").contains("chatView.pauseWatch()"))
        assertTrue(h.substringAfter("    fun unpark(why: String) {").substringBefore("\n    }\n").contains("chatView.resumeWatch()"))
        val chat = File(srcDir(), "ChatView.kt").readText()
        val watch = chat.substringAfter("private val watch = object : Runnable {").substringBefore("\n    }\n")
        assertTrue(watch.contains("if (isAttachedToWindow && !watchPaused) postDelayed(this, 500)"))
        assertTrue(chat.substringAfter("override fun onAttachedToWindow() {").substringBefore("\n    }\n").contains("if (!watchPaused) post(watch)"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
    private fun host() = File(srcDir(), "CompanionHost.kt").readText()
    private fun tail() = File(srcDir(), "LogTail.kt").readText()
    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { File(it) }.first { it.canRead() }.readText()
}
