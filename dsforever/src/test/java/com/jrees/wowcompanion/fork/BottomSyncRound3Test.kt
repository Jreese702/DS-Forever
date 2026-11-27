package com.jrees.wowcompanion.fork

import com.jrees.wowcompanion.BottomSync
import com.jrees.wowcompanion.LogTail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE BOTTOM SCREEN COMES STRAIGHT BACK, ROUND 3 OF THE REVIEW (2026-11-26; the user, 2026-11-25: "Coming back after a drop ... DS
 * Forever should come straight back on it, in the same room, already caught up with the game, with no app restart"; "most of the time
 * the drops happen from minimizing the game or closing my thor to sleep"). Pure rules and the source's own lines:
 * - a snapshot ages by the time the Thor SLEPT, never by the time away: a long minimize (a memory release 40 min before the return)
 *   keeps the room, Home's layer, Edit Home and "in the world" - no waiting screen;
 * - only a comeback path counts a rebuild (the host's own start over - single screen mode ending with the game, the unpack - never);
 * - a parked window Android closes with its display gone gives the game's UI back at once; every open refused gives it back too; a
 *   return with no bottom window reads on until the game's UI is back;
 * - Chat, Professions, the Spellbook, the Controller room and Bags keep their own state across a rebuild; Professions' keyboard is
 *   told off when its room is not laid back;
 * - an Edit Home session handed over is the new Home's alone.
 */
class BottomSyncRound3Test {
    @After fun clean() { BottomSync.clear() }

    private val min40 = 40 * 60_000L

    private fun snap(at: Long = 1_000L, atUp: Long = at, slept: Long = 0L, run: Long = 77L, room: String = "BAGS", inWorld: Boolean = true,
                     place: LogTail.Place? = null, ui: BottomSync.HostUi = BottomSync.HostUi()) =
        BottomSync.Snapshot(at, run, room, inWorld, place, "let go for memory", atUp, slept, ui)

    /** "A memory release more than 30 min before the return still shows the waiting screen and lands on Home after a minimize where the
     *  game never left the world" / "A rebuild from a snapshot older than 30 min lands on Home, drops Home's layer and Edit Home" */
    @Test fun aLongMinimizeKeepsTheRoomTheLayerAndTheWorld() {
        val p = LogTail.Place("/g/General.log", 9_000L, "", ByteArray(0), byteArrayOf(1, 2))
        val ui = BottomSync.HostUi(homeLayer = "module:MAP", room = mapOf("clean" to "1"))
        // the release while away, then 40 min with the screen on (uptime ran with elapsedRealtime): all of it comes back
        BottomSync.keep(snap(place = p, ui = ui))
        val t = BottomSync.take(now = 1_000L + min40, gameRun = 77L, nowUp = 1_000L + min40)
        assertNotNull(t)
        assertEquals("the room (the adopt rule decides with the game's holder)", "BAGS", t!!.room)
        assertEquals("Home's layer and the room's state", ui, t.ui)
        assertTrue("in the world as at the park", t.inWorld)
        assertEquals(p, t.place)
        assertTrue("no waiting screen: the game ran on", BottomSync.waitingGone(t, gameUp = true, now = 1_000L + min40, nowUp = 1_000L + min40))
        // 40 min ASLEEP (uptime stood still): Home, nothing laid back, the reader's place kept; the waiting screen by the sleep rule
        BottomSync.keep(snap(place = p, ui = ui))
        val s = BottomSync.take(now = 1_000L + min40, gameRun = 77L, nowUp = 1_000L)!!
        assertEquals("HOME", s.room)
        assertEquals(BottomSync.HostUi(), s.ui)
        assertEquals("the place whatever the sleep", p, s.place)
        assertFalse("the waiting screen after a long sleep", BottomSync.waitingGone(s, true, 1_000L + min40, 1_000L))
        // the sleep a parked host had been through before its snapshot counts too
        BottomSync.keep(snap(slept = BottomSync.RESTORE_MAX_MS, ui = ui))
        assertEquals("HOME", BottomSync.take(2_000L, 77L, 2_000L)!!.room)
        val take = File(srcDir(), "BottomSync.kt").readText().substringAfter("    fun take(now: Long, gameRun: Long, nowUp: Long): Snapshot? {")
            .substringBefore("\n    }\n")
        assertTrue(take, take.contains("sleptSince(s, now, nowUp) < RESTORE_MAX_MS"))
        assertFalse("never 'out of the world' by age", take.contains("inWorld = false"))
        for ((name, s2) in listOf("DsForeverScreen.kt" to source(), "TopWindow.kt" to source("TopWindow.kt"))) {
            val restore = s2.substringAfter("override fun hostRestore() = ").substringBefore("\n    override")
            assertTrue(name, restore.contains("android.os.SystemClock.uptimeMillis())"))
        }
    }

    /** "Report still counts non-drops as rebuilds: rebuild() marks expectRebuild for single screen mode ending with the game and for the
     *  first-run unpack recreate" */
    @Test fun onlyAComebackPathCountsARebuild() {
        // the host's own start over with the game up: its snapshot is laid back, never counted
        BottomSync.keep(snap())
        assertEquals(null, BottomSync.hostMade(BottomSync.take(2_000L, 77L, 2_000L)))
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 0"))
        // the game off (single screen mode ending with the game): no snapshot, no mark - nothing
        BottomSync.hostMade(null)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 0"))
        // a comeback path: counted, by its snapshot's reason or "no snapshot"
        BottomSync.expectRebuild("closed by Android"); BottomSync.keep(snap().copy(why = "closed by Android"))
        val mark = BottomSync.hostMade(BottomSync.take(2_000L, 77L, 2_000L))
        assertEquals("closed by Android", mark)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (closed by Android 1)"))
        // an abandoned host with no mark takes nothing back off the count
        BottomSync.hostAbandoned(snap(), null)
        assertTrue(BottomSync.line(), BottomSync.line().contains("rebuilt 1 (closed by Android 1)"))
        // the source: the host's own start over keeps its snapshot uncounted; every other keep marks it
        val src = source()
        assertTrue(src.contains("internal fun rebuild() { main.post { keepSnapshot(shown, \"the host asked to start over\", counts = false)"))
        val keep = src.substringAfter("    private fun keepSnapshot(w: BottomWindow?, why: String, counts: Boolean = true) {").substringBefore("\n    }\n")
        assertTrue(keep, keep.contains("if (counts) com.jrees.wowcompanion.BottomSync.expectRebuild(why)"))
        assertEquals("only rebuild() passes counts = false", 1, Regex("counts = false").findAll(src).count())
    }

    /** "A parked host torn down while away leaves the game's own UI hidden when no new bottom window comes" / "gameUiBack cannot give
     *  the game's UI back: GameInput is not live after a park longer than 25 s, and with no host nothing ticks GameUi again" */
    @Test fun theGamesUiComesBackWhenNoBottomWindowWill() {
        val src = source()
        val stop = src.substringAfter("    override fun onStop() {\n        val byAndroid = !closing").substringBefore("\n    }\n")
        assertTrue(stop, stop.contains("val giveBack = byAndroid && parkedNow && displayGone()"))
        assertTrue(stop.contains("host?.let { it.onStop(giveBack = giveBack); it.onDestroy() }"))
        assertTrue(src.contains("private fun displayGone(): Boolean = !display.isValid ||"))
        val h = host()
        val onStop = h.substringAfter("    fun onStop(gameHidden: Boolean = false, giveBack: Boolean = false) {").substringBefore("\n    }\n")
        assertTrue("the hold goes FIRST: the stop's GameUi.onVisible(false) may tap", onStop.trimStart().startsWith("if (giveBack && !gameHidden) unholdPark()"))
        // every open refused: the game's own UI back
        val open = src.substringAfter("    private fun openBottom(target: Display, game: Int) {").substringBefore("\n    }\n")
        assertTrue(open, open.contains("else if (!com.jrees.wowcompanion.BottomSync.opensWanted(openFails) && game == Display.DEFAULT_DISPLAY) gameUiBack()"))
        // a return with no window and the gate not live: the give-back reader (the gate and the hub document only), until in place
        val back = src.substringAfter("    private fun gameUiBack() {").substringBefore("\n    }\n")
        assertTrue(back, back.contains("if (!com.jrees.wowcompanion.GameUi.inPlace()) giveBackRead()"))
        val doc = src.substringAfter("    private fun giveBackDoc(m: org.json.JSONObject) {").substringBefore("\n    }\n")
        assertTrue(doc, doc.contains("val current = com.jrees.wowcompanion.GameInput.note(m)"))
        assertTrue(doc.contains("if (m.optString(\"type\") == \"hub\") com.jrees.wowcompanion.GameUi.onHub(m, current)"))
        assertFalse("never a tap of its own: GameUi's reconcile (the live gate) does the taps", doc.contains("tap("))
        val tick = src.substringAfter("    private val giveBackTick: Runnable = Runnable {").substringBefore("\n    }\n")
        assertTrue(tick, tick.contains("com.jrees.wowcompanion.GameUi.inPlace() || shown != null || hidden || !running || !gameOn"))
        assertTrue("a window that opens ends it", open.contains("if (shown != null) { openFails = 0; stopGiveBack() }"))
        assertTrue("the launcher going ends it", src.substringAfter("    fun hide(park: Boolean = true) {").substringBefore("\n    }\n").contains("stopGiveBack()"))
        assertTrue(src.contains("const val GIVE_BACK_MS = 120_000L"))
    }

    /** "The Cocoon bottom-grid return still rebuilds the host, and most of the lens rooms come back without their state" */
    @Test fun theRoomsKeepTheirOwnStateAcrossARebuild() {
        val h = host()
        val keeper = h.substringAfter("    private fun roomKeeper(r: Room): RoomKeep? = when (r) {").substringBefore("\n    }\n")
        for (r in listOf("CHAT" to "chatView", "PROFESSIONS" to "professionsView", "SPELLBOOK" to "spellbookView", "CONTROLLER" to "controllerView",
                "BAGS" to "bagsView", "QUESTS" to "questsView", "SOCIAL" to "socialView", "GROUPFINDER" to "groupFinderView",
                "CALENDAR" to "calendarView", "TALENTS" to "talentsView"))
            assertTrue(r.first, keeper.contains("Room.${r.first} -> if (::${r.second}.isInitialized) ${r.second} else null"))
        for (v in listOf("ChatView", "ProfessionsView", "SpellbookView", "ControllerView", "BagsView"))
            File(srcDir(), "$v.kt").readText().let { t ->
                assertTrue(v, t.contains(", RoomKeep {") && t.contains("override fun keepUi(): Map<String, String>") &&
                    t.contains("override fun restoreUi(k: Map<String, String>)"))
            }
        // Professions' keyboard was up and its room is not laid back: the add-on told "nokbd" once
        assertTrue(h.contains("if (got == null && want == Room.PROFESSIONS.name && restoreUi[\"kb\"] == \"1\") ProfessionsView.kbdOff(this)"))
        val prof = File(srcDir(), "ProfessionsView.kt").readText()
        assertTrue(prof.contains("if (k[\"kb\"] == \"1\") openKb(voice = false)"))
    }

    /** "HomeEditKeep hands the SAME mutable HomeEditModel to the new HomeView while the dead host's HomeView still holds it" */
    @Test fun anEditSessionHandedOverIsTheNewHomesAlone() {
        val home = File(srcDir(), "HomeView.kt").readText()
        assertTrue(home.contains("edit?.takeIf { !it.parked }?.let { editHanded = true; HomeEditKeep(it, editOwner, editPersisted, editLate) }"))
        val letGo = home.substringAfter("    fun editLetGo() {").substringBefore("\n    }\n")
        assertTrue(letGo, letGo.contains("edit = null; editOwner = null; editPersisted = null; editLate = null"))
        assertFalse("no guest tap, no write", letGo.contains("editGuest") || letGo.contains("editEnd") || letGo.contains("save"))
        assertTrue(host().substringAfter("    fun onDestroy() {").substringBefore("\n    }\n").contains("if (::homeView.isInitialized) homeView.editLetGo()"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
    private fun host(): String = File(srcDir(), "CompanionHost.kt").readText()
    private fun source(name: String = "DsForeverScreen.kt"): String =
        listOf(File("src/main/java/com/jrees/wowcompanion/fork/$name"), File("dsforever/src/main/java/com/jrees/wowcompanion/fork/$name"))
            .first { it.isFile }.readText()
}
