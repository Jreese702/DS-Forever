package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.BottomSync
import com.jrees.wowcompanion.HomeEditKeep
import com.jrees.wowcompanion.LogTail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK, ROUND 2 OF THE REVIEW (2026-11-26; the user, 2026-11-25: "Coming back after a drop ... DS
 * Forever should come straight back on it, in the same room, already caught up with the game, with no app restart"; "most of the time
 * the drops happen from minimizing the game or closing my thor to sleep"). Pure rules and the source's own lines:
 * the snapshot's age never throws the reader's place away; a minimize never shows the waiting screen, a long sleep does; a relaunch's
 * game is up; only a comeback path counts a rebuild; a refused open builds no host and the watchdog stops; a park is no room exit; an
 * activity of ours holds the window hidden only while it is up; the replay never acts; a parked host that goes taps nothing; a reader
 * never runs while parked; what a rebuild lays back (Home's layer, the room's state, Edit Home).
 */
class BottomSyncRound2Test {
    @After fun clean() { BottomSync.clear() }

    private fun snap(at: Long = 1_000L, atUp: Long = at, slept: Long = 0L, run: Long = 77L, room: String = "BAGS", inWorld: Boolean = true,
                     place: LogTail.Place? = null, ui: BottomSync.HostUi = BottomSync.HostUi()) =
        BottomSync.Snapshot(at, run, room, inWorld, place, "test", atUp, slept, ui)

    /** "A same-run snapshot older than 30 min throws away the reader's place and the DocMemory replay" */
    @Test fun anOldSnapshotStillGivesTheReadersPlace() {
        val p = LogTail.Place("/g/General.log", 9_000L, "", ByteArray(0), byteArrayOf(1, 2))
        BottomSync.keep(snap(place = p, ui = BottomSync.HostUi(homeLayer = "module:MAP", room = mapOf("tab" to "1"))))
        // (round 3: eight hours ASLEEP - uptime stood still; a minimize of any length is BottomSyncRound3Test's)
        val t = BottomSync.take(now = 1_000L + BottomSync.RESTORE_MAX_MS + 8 * 3_600_000L, gameRun = 77L, nowUp = 1_000L)
        assertNotNull("eight hours later: still this run's", t)
        assertEquals("the reader goes on from its place", p, t!!.place)
        assertEquals("no room after 30 min asleep", "HOME", t.room)
        assertFalse("the waiting screen shows (the sleep rule)", BottomSync.waitingGone(t, true, 1_000L + BottomSync.RESTORE_MAX_MS + 8 * 3_600_000L, 1_000L))
        assertEquals("nothing else laid back", BottomSync.HostUi(), t.ui)
        assertNull("once", BottomSync.take(2_000L, 77L, 2_000L))
        // a recent one: all of it
        BottomSync.keep(snap(place = p, ui = BottomSync.HostUi(homeLayer = "module:MAP")))
        assertEquals("module:MAP", BottomSync.take(2_000L, 77L, 2_000L)?.ui?.homeLayer)
        // another game run: nothing
        BottomSync.keep(snap(run = 5L, place = p))
        assertNull(BottomSync.take(2_000L, 77L, 2_000L))
    }

    /** "A snapshot taken while parked marks the game out of the world after 2 min away" */
    @Test fun aMinimizeNeverShowsTheWaitingScreenALongSleepDoes() {
        val min3 = 3 * 60_000L
        // a 3 min minimize: the game ran on (uptime ran with elapsedRealtime) - no waiting screen
        assertTrue(BottomSync.waitingGone(snap(), gameUp = true, now = 1_000L + min3, nowUp = 1_000L + min3))
        // a 3 min sleep: uptime stood still - WoW may have dropped the connection
        assertFalse(BottomSync.waitingGone(snap(), gameUp = true, now = 1_000L + min3, nowUp = 1_000L))
        // a short sleep is no long sleep
        assertTrue(BottomSync.waitingGone(snap(), true, 1_000L + 30_000L, 1_000L))
        // the sleep a parked host had been through before its snapshot counts too
        assertFalse(BottomSync.waitingGone(snap(slept = BottomSync.IN_WORLD_MS), true, 1_000L, 1_000L))
        assertTrue(BottomSync.waitingGone(snap(slept = 60_000L), true, 1_000L, 1_000L))
        assertFalse(BottomSync.waitingGone(snap(inWorld = false), true, 1_000L, 1_000L))
        assertFalse(BottomSync.waitingGone(snap(), false, 1_000L, 1_000L))
        assertFalse(BottomSync.waitingGone(null, true, 1_000L, 1_000L))
        assertEquals(60_000L, BottomSync.asleep(since = 1_000L, sinceUp = 500L, now = 101_000L, nowUp = 40_500L))
        assertEquals(0L, BottomSync.asleep(0L, 0L, 5_000L, 5_000L))
        // the host: in the world measured at the PARK, the park's sleep handed on
        val snapFn = host().substringAfter("    fun snapshot(why: String): BottomSync.Snapshot? {").substringBefore("\n    }\n")
        assertTrue(snapFn, snapFn.contains("BottomSync.inWorld(docLiveAt, if (parkedAt != 0L) parkedWall else System.currentTimeMillis())"))
        assertTrue(snapFn.contains("BottomSync.asleep(parkedElapsed, parkedAt, now, nowUp)"))
        assertTrue(host().substringAfter("    fun park(why: String) {").substringBefore("\n    }\n").contains("parkedElapsed = SystemClock.elapsedRealtime()"))
    }

    /** "During a configuration relaunch the restored host sees the game as not up" */
    @Test fun aRelaunchesGameIsUp() {
        assertFalse(BottomSync.relaunchPending(5_000L))
        BottomSync.relaunching(run = 77L, now = 1_000L)
        assertTrue(BottomSync.relaunchPending(6_000L))
        assertFalse(BottomSync.relaunchPending(1_000L + BottomSync.RELAUNCH_MS))
        assertTrue(BottomSync.attached(run = 90L, now = 2_000L))
        assertFalse("the game screen came: no relaunch under way", BottomSync.relaunchPending(2_500L))
        val create = host().substringAfter("fun onCreate(savedInstanceState: Bundle?) {").substringBefore("\n    fun onStart() {")
        assertTrue(create.contains("BottomSync.waitingGone(r, LauncherHooks.gameUp() || BottomSync.relaunchPending(SystemClock.elapsedRealtime()),"))
        val st = host().substringAfter("private fun startTail() {").substringBefore("\n    }\n")
        assertTrue(st.contains("else if (LauncherHooks.gameUp() || BottomSync.relaunchPending(android.os.SystemClock.elapsedRealtime())) DocMemory.note(msg)"))
    }

    /** "The report line counts single screen switches and Streaming hosts as bottom screen rebuilds"; "misplaces the catch-up time" */
    @Test fun onlyAComebackPathCountsARebuild() {
        BottomSync.hostMade(null)                                   // the run's first host
        BottomSync.hostMade(null); BottomSync.hostMade(null)        // a single screen switch on and off, Streaming, Swap screens' return
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 0"))
        BottomSync.expectRebuild("closed by Android")
        assertEquals("closed by Android", BottomSync.hostMade(null))
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (no snapshot 1)"))
        BottomSync.hostMade(null)
        assertTrue("one mark, one rebuild", BottomSync.line().contains("rebuilt 1 (no snapshot 1)"))
        // a failed open gives the mark back (TopWindow's abandon)
        BottomSync.expectRebuild("moved")
        val m = BottomSync.hostMade(null)
        BottomSync.hostAbandoned(null, m)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (no snapshot 1)"))
        assertEquals("moved", BottomSync.hostMade(null))
        // the return's catch-up and the rebuild's: each in its own place
        BottomSync.back(awayMs = 5_000L, workMs = 2L); BottomSync.caughtUp(100L)
        BottomSync.rebuilt("closed by Android"); BottomSync.caughtUp(300L)
        val l = BottomSync.line()
        assertTrue(l, l.contains("(the last after 5 s away, 2 ms of ours, caught up in 100 ms)"))
        assertTrue(l, l.contains("the last rebuild caught up in 300 ms"))
        // the marks: every comeback path (keepSnapshot, Android's close), never the single screen switch
        val f = source()
        assertTrue(f.substringAfter("private fun keepSnapshot(").substringBefore("\n    }\n").contains("BottomSync.expectRebuild(why)"))
        assertTrue(f.substringAfter("internal fun lost(").substringBefore("\n    }\n").contains("BottomSync.expectRebuild(why)"))
        assertFalse(f.substringAfter("internal fun toggleSingle(").substringBefore("\n    /** one of our activities").contains("expectRebuild"))
        assertFalse(host().contains("hosts > 1"))
    }

    /** "Each failed bottom open builds a full CompanionHost on the main thread and leaks the abandoned Presentation; the watchdog repeats" */
    @Test fun aRefusedOpenBuildsNoHostAndTheWatchdogStops() {
        assertTrue(BottomSync.opensWanted(BottomSync.OPEN_FAILS_MAX - 1))
        assertFalse(BottomSync.opensWanted(BottomSync.OPEN_FAILS_MAX))
        val p = source().substringAfter("private class HostPresentation(")
        val create = p.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {").substringBefore("\n    }\n")
        assertFalse("no host inside Dialog.show (a refused addView costs nothing)", create.contains("CompanionHost("))
        assertTrue(p.substringAfter("fun begin() {").substringBefore("\n    }\n").contains("CompanionHost(context, this)"))
        val ab = p.substringAfter("fun abandon(shownOk: Boolean = false) {").substringBefore("\n    }\n")
        assertTrue("its display listener off (Presentation.onStop: dismiss() returns early on a window never shown)", ab.contains("super.onStop()"))
        assertTrue("never Dialog.isShowing (the decor is VISIBLE already when addView threw)", ab.contains("if (shownOk) {") && !ab.contains("isShowing"))
        val f = source()
        val open = f.substringAfter("private fun openBottom(").substringBefore("\n    }\n")
        assertTrue(open, open.contains("it.show(); shownOk = true; it.begin()"))
        assertTrue(open.contains("made?.abandon(shownOk)"))
        assertTrue(open.contains("openFails++"))
        val soon = f.substringAfter("private fun reopenSoon(").substringBefore("\n    }\n")
        assertTrue(soon.contains("BottomSync.opensWanted(openFails)) return"))
        assertTrue(soon.contains("BottomSync.opensWanted(topFails)) return"))
        val show = f.substringAfter("    fun show() {").substringBefore("\n    }\n")
        assertTrue("a return tries again, the fast steps first", show.contains("reopenTries = 0") && show.contains("openFails = 0") && show.contains("topFails = 0"))
        assertTrue("a display event tries again", f.substringAfter("private val listener = ").substringBefore("\n    }\n").contains("openFails = 0"))
    }

    /** "Watchdog polls update() every 5 s for the whole session while no second screen is listed" */
    @Test fun theWatchdogOnlyWithAScreenPicked() {
        val two = source().substringAfter("private fun twoScreens(").substringBefore("\n    }\n")
        assertTrue(two.contains("if (shown?.alive != true && pick != null) reopenSoon(\"no bottom window up\")"))
    }

    /** "Parking toggles the decor's View visibility, so rooms reset as if left" */
    @Test fun aParkIsNoRoomExit() {
        assertFalse(BottomSync.flipping)
        assertTrue(BottomSync.flip { BottomSync.flipping })
        assertFalse(BottomSync.flipping)
        val p = source().substringAfter("private class HostPresentation(")
        val park = p.substringAfter("override fun park(why: String): Boolean {").substringBefore("\n    }\n")
        assertTrue("the host away first, then the decor", park.indexOf("host?.park(why)") in 0 until park.indexOf("View.GONE"))
        assertTrue(park.contains("BottomSync.flip {"))
        val unpark = p.substringAfter("override fun unpark(why: String) {").substringBefore("\n    }\n")
        assertTrue("the host back first, then the decor", unpark.indexOf("host?.unpark(why)") in 0 until unpark.indexOf("View.VISIBLE"))
        assertTrue(unpark.contains("BottomSync.flip {"))
        val top = source("TopWindow.kt")
        assertTrue(top.substringAfter("override fun park(why: String): Boolean {").substringBefore("\n    }\n").contains("BottomSync.flip {"))
        assertTrue(top.substringAfter("override fun unpark(why: String) {").substringBefore("\n    }\n").contains("BottomSync.flip {"))
        for (v in listOf("ChatView.kt", "ProfessionsView.kt")) {
            val h = File(srcDir(), v).readText().substringAfter("override fun onVisibilityChanged(changedView: View, visibility: Int) {").substringBefore("\n    }\n")
            assertTrue(v, h.contains("if (BottomSync.flipping)"))
        }
    }

    /** "held is cleared only when our activity is destroyed. A picker left in the background keeps the bottom window hidden" */
    @Test fun anActivityHoldsTheWindowHiddenOnlyWhileItIsUp() {
        val p = source().substringAfter("private class HostPresentation(")
        val unpark = p.substringAfter("override fun unpark(why: String) {").substringBefore("\n    }\n")
        assertTrue(unpark.contains("if (held && !heldUp()) held = false"))
        assertTrue(p.substringAfter("private fun heldUp(): Boolean").substringBefore("\n").contains("HeldTasks.shows(context, it)"))
        val shows = source("HeldTasks.kt").substringAfter("fun shows(ctx: Context, cls: String): Boolean {").substringBefore("\n    }\n")
        assertTrue("alive first: none alive = not up", shows.contains("if (tasks.isEmpty()) return false"))
        assertTrue("its task shows on a screen", shows.contains("i.taskId in tasks && i.isVisible"))
        assertTrue("never root, never a permission: our own tasks, read only", shows.contains("am.appTasks") && !shows.contains("moveToFront"))
    }

    /** "DocMemory replay can count the newest documents as current and run current-only actions a second time" */
    @Test fun theReplayNeverActs() {
        val create = host().substringAfter("fun onCreate(savedInstanceState: Bundle?) {").substringBefore("\n    fun onStart() {")
        val loop = create.substringAfter("restored?.let { r ->").substringBefore("resumeAt = r.place")
        assertTrue(loop, loop.contains("replaying = true"))
        assertTrue(loop.contains("GameInput.away = true"))
        assertTrue("the resumed reader's first document starts the gate's rule", loop.indexOf("GameInput.gate.restart()") > loop.indexOf("handleMessage(m)"))
        val hm = host().substringAfter("private fun handleMessage(msg: JSONObject) {").substringBefore("docCurrent = current")
        assertTrue(hm.contains("val current = GameInput.note(msg) && !replaying"))
    }

    /** "A parked host torn down while the user is away now taps the game's UI open, and the new host taps it shut at the return" */
    @Test fun aParkedHostThatGoesTapsNothing() {
        val stop = host().substringAfter("fun onStop(gameHidden: Boolean = false, giveBack: Boolean = false) {").substringBefore("\n    }\n")
        assertTrue("the game's UI recorded under the park's hold (nothing tapped), the hold let go after",
            stop.indexOf("if (!gameHidden) unholdPark()") > stop.indexOf("stopBody(gameHidden)"))
        val body = host().substringAfter("private fun stopBody(gameHidden: Boolean) {").substringBefore("\n    }\n")
        assertTrue(body.contains("if (!gameHidden) GameUi.onVisible(false)"))
        assertFalse(body.contains("unholdPark()"))
        // the game's own UI given back only when a return finds no bottom window to show (and not a relaunch's moment on the bottom)
        val two = source().substringAfter("private fun twoScreens(").substringBefore("\n    }\n")
        assertTrue(two.contains("if (shown == null && pick == null && game == Display.DEFAULT_DISPLAY) gameUiBack()"))
        val back = source().substringAfter("private fun gameUiBack() {").substringBefore("\n    }\n")
        assertTrue(back.contains("GameUi.onVisible(false)") && back.contains("GameUi.tick()"))
    }

    /** "A park before the host's reader starts leaves an unpaused LogTail reading while away" */
    @Test fun aReaderNeverStartsWhileParked() {
        val st = host().substringAfter("private fun startTail() {").substringBefore("\n    }\n")
        assertTrue(st.trimStart().startsWith("// BottomSync (round 2") && st.contains("if (parkedAt != 0L) { tailWanted = true; return }"))
        val unpark = host().substringAfter("    fun unpark(why: String) {").substringBefore("\n    }\n")
        assertTrue(unpark.indexOf("if (tailWanted) { tailWanted = false; startTail() }") in 0 until unpark.indexOf("tail?.resume()"))
    }

    /** "Returning from Cocoon's bottom grid always rebuilds the host": what the snapshot carries, and that it holds no view */
    @Test fun aRebuildLaysBackTheLayerTheRoomAndEditHome() {
        val ui = BottomSync.HostUi(homeLayer = "module:CHAT", room = mapOf("page" to "GUILD"))
        // a settled host: what it shows now
        val s = BottomSync.snapshotOf(null, false, "SOCIAL", null, true, null, 5_000L, 77L, "x", nowUp = 4_000L, sleptNow = 10L, ui = ui)
        assertEquals(ui, s.ui); assertEquals(4_000L, s.atUp); assertEquals(10L, s.slept)
        // an unsettled restore: its room still waits for the adopt rule, and so does that room's state
        val r = snap(at = 100L, atUp = 90L, slept = 7L, room = "SOCIAL", ui = BottomSync.HostUi(room = mapOf("page" to "RAID")))
        val c = BottomSync.snapshotOf(r, settled = false, room = "HOME", restoreRoom = "SOCIAL", inWorldNow = false, place = null, now = 9_000L,
            gameRun = 77L, why = "y", nowUp = 8_000L, ui = BottomSync.HostUi(homeLayer = "module:MAP"))
        assertEquals("SOCIAL", c.room); assertEquals(100L, c.at); assertEquals(90L, c.atUp); assertEquals(7L, c.slept)
        assertEquals(mapOf("page" to "RAID"), c.ui.room)
        assertEquals("module:MAP", c.ui.homeLayer)
        // pure data only
        for (k in listOf(BottomSync.HostUi::class.java, HomeEditKeep::class.java)) for (fld in k.declaredFields)
            for (bad in listOf("android.view.", "android.content.Context", "android.app.", "CompanionHost", "HomeView"))
                assertFalse("${k.simpleName}.${fld.name} holds ${fld.type.name}", fld.type.name.startsWith(bad) || fld.type.name.endsWith(".$bad"))
        // the host: the snapshot carries it, the rebuild lays it back with no tap
        val snapFn = host().substringAfter("    fun snapshot(why: String): BottomSync.Snapshot? {").substringBefore("\n    }\n")
        assertTrue(snapFn.contains("ui = uiKeep()"))
        val keep = host().substringAfter("private fun uiKeep(): BottomSync.HostUi").substringBefore("\n    }\n")
        assertTrue(keep.contains("homeView.layerKeep()") && keep.contains("homeView.editKeep()") && keep.contains("roomKeeper(room)?.keepUi()"))
        val create = host().substringAfter("fun onCreate(savedInstanceState: Bundle?) {").substringBefore("\n    fun onStart() {")
        assertTrue(create.contains("uiRestore(r.ui)"))
        val interact = host().substringAfter("            \"interact\" -> {").substringBefore("\n            \"cappad\"")
        assertTrue("the room's own state laid on the adopted room", interact.contains("roomKeeper(Room.valueOf(it))?.restoreUi(restoreUi)"))
        // the relaunch tears the parked host down with no top-screen tap (the park's hold), and the next host opens restored
        assertTrue(source().substringAfter("    fun stop() {").substringBefore("\n    }\n").contains("keepSnapshot(shown, \"a configuration relaunch\")"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
    private fun host() = File(srcDir(), "CompanionHost.kt").readText()
    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf(File("src/main/java/com/jrees/wowcompanion/fork/$name"), File("dsforever/src/main/java/com/jrees/wowcompanion/fork/$name"))
            .first { it.isFile }.readText()
}
