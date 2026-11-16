package com.jrees.wowcompanion

import com.jrees.wowcompanion.RoomHandOver.Kind
import com.jrees.wowcompanion.RoomHandOver.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A ROOM LEFT NEVER COMES BACK BY ITSELF (the user, 2026-11-16, 1.0.33 code 1476: "after opening the bags from the Dpad select, and then
 * pressing be to close has a weird behavior where it reopens again. this is not new"), off-device. 12:43:55-12:44:01 of the user's log:
 * A on BAGS (bag0:39 at 55.918), B at 57.000 -> Home on the press, the room's close cells put off B_LEAVE_MS -> the game's B let go
 * (57.349) -> Bags' take check, still armed from the open, saw no hold at 57.480 and took again (bmvq 29) -> its ctl.on brought BAGS back
 * -> that show() ran the put-off close, which freed the new hold (bmvq 30 at 57.567) -> BAGS up with nobody holding until the exit guard.
 * Now the leave ends the room's own asks at once on every route (RoomHandOver.stops), and a room shown again inside the wait drops its
 * put-off close (RoomHandOver.dropPutOff). The timeline below replays the log's order with the app's own pieces.
 */
class RoomLeaveStopsTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()
    private val bags = File(src, "BagsView.kt").readText()
    private val character = File(src, "CharacterView.kt").readText()
    private val settings = File(src, "SettingsPad.kt").readText()

    private fun body(text: String, head: String): String {
        val i = text.indexOf(head)
        assertTrue("'$head' not found", i >= 0)
        val end = listOf("\n    /**", "\n    private fun ", "\n    fun ").map { text.indexOf(it, i + head.length) }.filter { it > 0 }.minOrNull()
        return text.substring(i, end ?: text.length)
    }

    // ---------------------------------------------------------------- the model

    @Test fun leavingBagsEndsItsTakeCheck() = assertEquals(listOf(Stop.BAGS_TAKE), RoomHandOver.stops(Kind.BAGS, same = false))

    @Test fun leavingCharacterEndsItsHoldReAskAndItsWindowToOpen() =
        assertEquals(listOf(Stop.CHARACTER_HOLD), RoomHandOver.stops(Kind.CHARACTER, same = false))

    @Test fun leavingSettingsEndsItsHoldReAsk() = assertEquals(listOf(Stop.SETTINGS_HOLD), RoomHandOver.stops(Kind.SETTINGS, same = false))

    @Test fun theSameRoomStopsNothing() = Kind.values().forEach { assertTrue(it.name, RoomHandOver.stops(it, same = true).isEmpty()) }

    @Test fun roomsWithoutTheirOwnReAsksStopNothing() =
        listOf(Kind.SPELLBOOK, Kind.TALENTS, Kind.CONTROLLER, Kind.MAP, Kind.OTHER).forEach { assertTrue(it.name, RoomHandOver.stops(it, false).isEmpty()) }

    @Test fun aRoomShownAgainDropsItsPutOffClose() {
        listOf(Kind.BAGS, Kind.CHARACTER, Kind.SPELLBOOK, Kind.TALENTS, Kind.CONTROLLER).forEach {
            assertTrue(it.name, RoomHandOver.dropPutOff(it, same = true))
            assertFalse(it.name, RoomHandOver.dropPutOff(it, same = false))
        }
        // Professions' put-off leave is its own (leftByB / modeSent book-keeping its enter needs first): it runs as before
        assertFalse(RoomHandOver.dropPutOff(Kind.OTHER, same = true))
    }

    @Test fun bagsTakeIsCheckedOnce() {
        val t = BagsTake()
        assertTrue("not held: the take goes out", t.enter(ctlOn = false))
        assertTrue("no ctl.on at the check: one more take", t.check(ctlOn = false))
        assertFalse("only one", t.check(ctlOn = false))
    }

    @Test fun bagsTakeNothingWhenTheRoomHoldsAlready() {
        val t = BagsTake()
        assertFalse("the game's own bag key: held before the jump", t.enter(ctlOn = true))
        assertFalse(t.check(ctlOn = true))
    }

    @Test fun aStoppedBagsTakeNeverTakesAgain() {
        val t = BagsTake()
        t.enter(ctlOn = false)
        t.stop()
        assertFalse(t.wantIn)
        assertFalse("the room was left: no take for it", t.check(ctlOn = false))
    }

    // ---------------------------------------------------------------- the 1476 timeline, replayed

    /** the host's Bags route as show() / bPressed / onBagsController run it, on a clock; [stops] = RoomHandOver.stops (or 1476's none) */
    private class Sim(val stops: (Kind) -> List<Stop> = { RoomHandOver.stops(it, same = false) },
                      val drop: (Kind, Boolean) -> Boolean = RoomHandOver::dropPutOff) {
        val take = BagsTake()
        var now = 0L
        var room = Room.HOME
        var ctlOn = false
        val taps = ArrayList<Pair<Long, String>>()
        val shows = ArrayList<Pair<Long, Room>>()
        private val events = ArrayList<Pair<Long, () -> Unit>>()
        private var checkToken = 0
        private var putOff: (() -> Unit)? = null
        private var putOffFor: Room? = null

        fun at(t: Long, f: () -> Unit) { events.add(t to f) }
        fun run(until: Long = 5_000L) {
            while (true) {
                val next = events.filter { it.first <= until }.minByOrNull { it.first } ?: break
                events.remove(next); now = next.first; next.second()
            }
        }
        private fun tap(c: String) { taps.add(now to c) }

        // BagsView
        private fun stopAsking() { checkToken++; take.stop() }                          // removeCallbacks(takeCheck) + the model's stop
        private fun touchedIn() {
            checkToken++
            if (!take.enter(ctlOn)) return
            tap("bag0:39")
            val mine = checkToken
            at(now + BagsView.TAKE_CHECK_MS) { if (mine == checkToken && take.check(ctlOn)) { tap("bmvq:29"); at(now + 86) { bagsDoc(true) } } }
        }
        private fun touchedOut() { stopAsking(); if (ctlOn) tap("bmvq:30") }

        // CompanionHost
        private fun flushPutOff(entering: Room?) {
            val f = putOff ?: return
            val made = putOffFor
            putOff = null; putOffFor = null
            if (made != null && entering != null && drop(if (made == Room.BAGS) Kind.BAGS else Kind.OTHER, made == entering)) return
            f()
        }
        fun show(r: Room, byB: Boolean = false) {
            flushPutOff(entering = r)
            shows.add(now to r)
            val leaving = room
            if (leaving == Room.BAGS && r != Room.BAGS) for (s in stops(Kind.BAGS)) if (s == Stop.BAGS_TAKE) stopAsking()
            if (leaving == Room.BAGS && r != Room.BAGS) {
                if (byB) { putOff = { touchedOut() }; putOffFor = leaving; val f = putOff; at(now + 1_000L) { if (putOff === f) flushPutOff(null) } }
                else touchedOut()
            }
            room = r
            if (r == Room.BAGS && leaving != Room.BAGS) touchedIn()
        }
        /** a bags document's ctl.on: onBagsController */
        fun bagsDoc(on: Boolean, why: String? = null) {
            if (on == ctlOn) return
            ctlOn = on
            if (on) { if (room != Room.BAGS) show(Room.BAGS) }
            else if (room == Room.BAGS && why != "room" && why != "free" && why != "character") show(Room.HOME)
        }
        /** the player's B: bPressed (Bags' B leads Home while it holds), then the game's own B lets go [releaseMs] later */
        fun b(releaseMs: Long = 350L) {
            if (room == Room.BAGS && ctlOn) show(Room.HOME, byB = true)
            at(now + releaseMs) { if (ctlOn) bagsDoc(false, "B") }
        }
    }

    /** A on BAGS at 0 (the bar's pick), the hold at 200 ms, B at [bAt] */
    private fun barBagsThenB(bAt: Long, sim: Sim = Sim()): Sim {
        sim.at(0L) { sim.show(Room.BAGS) }
        sim.at(200L) { sim.bagsDoc(true) }
        sim.at(bAt) { sim.b() }
        sim.run()
        return sim
    }

    @Test fun theLogsBStaysHome() {
        // the log: bag0:39 at 55.918, B at 57.000 (+1082 ms), the release at 57.349
        val s = barBagsThenB(1_082L)
        assertEquals(Room.HOME, s.room)
        assertEquals(listOf(0L to Room.BAGS, 1_082L to Room.HOME), s.shows)
        assertEquals("only the open's door: no retake, no close of a retake", listOf(0L to "bag0:39"), s.taps)
    }

    @Test fun noBInTheWindowEverReopensBags() {
        // the window 1476 had: B later than open + 0.5 s (the check before the put-off close) and before the release landed ahead of
        // the check (B < open + ~1.15 s); and every B around it
        for (bAt in 250L..2_400L step 10L) {
            val s = barBagsThenB(bAt)
            assertEquals("B at $bAt: Home", Room.HOME, s.room)
            assertEquals("B at $bAt: one show of Bags", 1, s.shows.count { it.second == Room.BAGS })
            assertFalse("B at $bAt: no take after B", s.taps.any { it.first >= bAt && it.second == "bmvq:29" })
        }
    }

    @Test fun the1476RouteReopenedBagsWithNobodyHolding() {
        // the model replays the bug when the leave stops nothing (1476): proof that the timeline above is the log's
        val s = barBagsThenB(1_082L, Sim(stops = { emptyList() }, drop = { _, _ -> false }))
        assertEquals("BAGS came back", Room.BAGS, s.room)
        assertEquals(listOf(0L to "bag0:39", 1_500L to "bmvq:29", 1_586L to "bmvq:30"), s.taps)
        assertTrue("and the put-off close freed the retake", s.taps.last().second == "bmvq:30")
    }

    @Test fun theGamesBagKeyInsideTheWaitKeepsItsHold() {
        // B at 600, the game's own bag key at 900 (Bags holds again: its jump shows the room): the put-off close is dropped, not run
        val s = Sim()
        s.at(0L) { s.show(Room.BAGS) }
        s.at(200L) { s.bagsDoc(true) }
        s.at(600L) { s.b(releaseMs = 150L) }
        s.at(900L) { s.bagsDoc(true) }
        s.run()
        assertEquals(Room.BAGS, s.room)
        assertTrue(s.ctlOn)
        assertFalse("no close against the new hold", s.taps.any { it.second == "bmvq:30" })
    }

    // ---------------------------------------------------------------- the host's and the views' wiring

    @Test fun showStopsTheLeftRoomsAsksBeforeItsCloseCellsOnEveryRoute() {
        val show = body(host, "private fun show(r: Room")
        val stopAt = show.indexOf("\n        roomStops(leaving, r)\n")
        val cellsAt = show.indexOf("if (!fightLeft) {")
        assertTrue("roomStops at the top level of show(): not under byB, not under the fight's skip", stopAt >= 0)
        assertTrue("before the close cells are put off (B) or skipped (a fight)", cellsAt > stopAt)
        assertTrue("show() first runs (or drops) a put-off leave", show.contains("flushBLeave(entering = r)"))
        assertTrue("the room B left is remembered", show.contains("bLeaveFor = leaving"))
    }

    @Test fun theHostStopsEachRoomsOwnAsks() {
        val stops = body(host, "private fun roomStops(")
        assertTrue(stops.contains("RoomHandOver.stops(handKind(was), same = was == r)"))
        assertTrue(stops.contains("RoomHandOver.Stop.BAGS_TAKE -> bagsView.stopAsking()"))
        assertTrue(stops.contains("RoomHandOver.Stop.CHARACTER_HOLD -> characterView.stopAsking()"))
        assertTrue(stops.contains("RoomHandOver.Stop.SETTINGS_HOLD -> settings.pad.stopAsking()"))
        assertTrue(body(host, "private fun flushBLeave(").contains("RoomHandOver.dropPutOff(handKind(made), same = made == entering)"))
    }

    @Test fun bagsStopEndsTheTakeCheck() {
        val stop = body(bags, "fun stopAsking()")
        assertTrue(stop.contains("removeCallbacks(takeCheck)") && stop.contains("take.stop()"))
        assertTrue("every close still ends it", body(bags, "fun touchedOut()").contains("stopAsking()"))
        assertTrue("the check is the model's", bags.contains("if (!take.check(state.ctlOn)) return@Runnable"))
        assertTrue("the take is the model's", body(bags, "fun touchedIn()").contains("if (!take.enter(state.ctlOn)) return"))
    }

    @Test fun characterStopEndsItsReAskAndItsWindowToOpen() {
        val stop = body(character, "fun stopAsking()")
        assertTrue(stop.contains("roomShown = false") && stop.contains("openWanted = false"))
        assertFalse("holdSent stays for touchedOut's cell", stop.contains("holdSent"))
        assertTrue(body(character, "fun touchedOut()").contains("stopAsking()"))
        // what the flags guard: the re-ask and the window's open
        assertTrue(character.contains("if (!roomShown || held || attempt >= 2) return@postDelayed"))
        assertTrue(character.contains("if (!roomShown || !openWanted || !active) return"))
    }

    @Test fun settingsStopEndsItsHoldReAsk() {
        assertTrue(body(settings, "fun stopAsking()").contains("holdAsk.hidden()"))
        assertTrue(body(settings, "fun leave()").contains("stopAsking()"))
    }
}
