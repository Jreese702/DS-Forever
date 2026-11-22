package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #584 ("when the map is open, moving the cursor with the left stick also moves the character"): the map's claim on the controller.
 * The cursor moves only while the map holds (the add-on said so, or a press reached it) and the game's stick catch shows; every ask
 * and answer is logged; a hold that drops while the map shows is asked again (at once, or by the exit guard when it names a holder).
 */
class MapHoldClaimTest {
    private val lines = ArrayList<String>()
    private fun claim() = MapHoldClaim("map room") { lines.add(it) }

    @Test fun nothingMovesTheCursorBeforeTheHoldIsAnswered() {
        val c = claim()
        assertFalse("off: the sticks are the game's", c.cursorLive())
        c.shown()
        assertEquals(MapHoldClaim.St.ASKING, c.st)
        assertFalse("asked, not answered: the cursor waits (#584's 25 s)", c.cursorLive())
        c.asked(1, HoldAsk.MAX, sent = true, how = "the room's row")
        c.asked(2, HoldAsk.MAX, sent = true, how = "the room's row")
        c.roundOver(4)
        assertEquals(MapHoldClaim.St.UNANSWERED, c.st)
        assertFalse(c.cursorLive())
        assertTrue("every ask is logged", lines.any { it.contains("hold ask 1/4 sent (the room's row)") } && lines.any { it.contains("hold ask 2/4") })
        assertTrue("the round's end is logged", lines.any { it.contains("no hold after 4 asks") })
    }

    @Test fun aHeldAnswerGivesTheSticksToTheMap() {
        val c = claim()
        c.shown()
        assertFalse(c.answer(ok = true, why = ""))
        assertEquals(MapHoldClaim.St.HELD, c.st)
        assertTrue("held, no catch ever seen (an add-on that cannot catch): the hold alone", c.cursorLive())
        assertTrue(lines.any { it.contains("hold answered: held") })
    }

    @Test fun theAddOnsStateAndAPressHoldToo() {
        val c = claim()
        c.shown()
        assertFalse(c.win(held = true, pend = false))
        assertTrue(c.held)
        val d = claim()
        d.shown()
        d.press()
        assertTrue("only the holder gets presses", d.held)
    }

    @Test fun pendingAndQueuedAreNotHeld() {
        val c = claim()
        c.shown()
        assertFalse(c.answer(ok = true, why = "pending"))
        assertEquals(MapHoldClaim.St.PENDING, c.st)
        assertFalse("a fight's ENTER still to land: the stick is the game's", c.cursorLive())
        c.win(held = true, pend = true)
        assertFalse(c.cursorLive())
        val q = claim()
        q.shown()
        q.answer(ok = true, why = null, waits = true)
        assertEquals(MapHoldClaim.St.PENDING, q.st)
        q.win(held = true, pend = false)
        assertTrue("the queue granted it", q.cursorLive())
    }

    @Test fun aRefusalIsLoggedAndAskedAgainOutOfAFight() {
        val c = claim()
        c.shown()
        assertTrue("refused out of a fight: the round goes on", c.answer(ok = false, why = "busy"))
        assertEquals(MapHoldClaim.St.REFUSED, c.st)
        assertFalse(c.cursorLive())
        assertTrue(lines.any { it.contains("hold refused (busy)") })
        assertFalse("refused in a fight: the round ends (the guard asks after it)", c.answer(ok = false, why = "combat"))
        c.roundOver(4)
        assertEquals("a refusal stays a refusal", MapHoldClaim.St.REFUSED, c.st)
    }

    @Test fun theStickCatchGatesTheCursorOnceTheAddOnHasShownIt() {
        val c = claim()
        c.shown()
        c.answer(ok = true, why = "")
        c.stick(true)
        assertTrue(c.catchSeen && c.cursorLive())
        c.stick(false)
        assertFalse("held but the catch hid: the stick walks the character, so the cursor stays", c.cursorLive())
        assertTrue("... unless no catch is asked for (the guest route)", c.cursorLive(needCatch = false))
        c.stick(true)
        assertTrue(c.cursorLive())
        assertTrue(lines.any { it.contains("stick catch on") } && lines.any { it.contains("stick catch off") })
    }

    @Test fun aCatchSeenWhileTheMapIsClosedDoesNotCount() {
        val c = claim()
        c.stick(true)                                      // another map's catch (Home's), this one off
        assertFalse(c.catchSeen)
        assertTrue("followed all the same: a catch already up when the map opens", c.caught)
        c.stick(false)
        c.shown(); c.answer(true, "")
        assertTrue("never seen while shown: the hold alone", c.cursorLive())
    }

    @Test fun aDropIsAskedAgainAtOnceUnlessItNamesAHolder() {
        val c = claim()
        c.shown(); c.answer(true, "")
        assertTrue("dropped with no reason: ask again", c.win(held = false, pend = false))
        assertEquals(MapHoldClaim.St.LOST, c.st)
        assertFalse(c.cursorLive())
        c.again("the hold dropped")
        c.answer(true, "")
        assertTrue("lost for an unexplained reason: ask again", c.lost("lost"))
        c.answer(true, "")
        for (why in MapHoldClaim.WAIT_WHYS) {
            c.answer(true, "")
            assertFalse("lost ($why): left to the exit guard", c.lost(why))
            assertFalse(c.cursorLive())
        }
        assertTrue(lines.any { it.contains("hold lost (taken)") && it.contains("exit guard") })
    }

    @Test fun aHoldThatKeepsDroppingIsLeftToTheGuard() {
        val c = claim()
        c.shown()
        repeat(MapHoldClaim.MAX_DROPS) { c.answer(true, ""); assertTrue(c.lost("lost")) }
        c.answer(true, "")
        assertFalse("no endless asking", c.lost("lost"))
        c.shown()
        c.answer(true, "")
        assertTrue("a new show starts afresh", c.lost("lost"))
    }

    @Test fun theExitGuardsNobodyEndsABelievedHoldAndAsksAgain() {
        val c = claim()
        c.shown(); c.answer(true, "")
        assertTrue(c.nobody())
        assertEquals(MapHoldClaim.St.LOST, c.st)
        assertFalse(c.cursorLive())
        c.again("the exit guard")
        assertEquals(MapHoldClaim.St.ASKING, c.st)
        c.hidden()
        assertFalse("off: nothing asked", c.nobody())
    }

    @Test fun answersAfterTheMapClosedChangeNothing() {
        val c = claim()
        c.shown(); c.hidden()
        assertFalse(c.answer(true, ""))
        assertFalse(c.win(held = true, pend = false))
        c.press()
        assertEquals(MapHoldClaim.St.OFF, c.st)
        assertFalse(c.cursorLive())
        assertTrue(lines.any { it.contains("claim off (asking)") })
    }

    @Test fun aRefusalWhileHeldKeepsTheHold() {
        val c = claim()
        c.shown(); c.answer(true, "")
        assertFalse(c.answer(false, "busy"))
        assertTrue(c.held)
    }

    @Test fun holdAskCountsItsAsksAndSaysWhenTheRoundIsSpent() {
        val a = HoldAsk()
        a.shown()
        assertFalse(a.spent())
        repeat(HoldAsk.MAX) { assertTrue(a.due()); a.asked(true) }
        assertEquals(HoldAsk.MAX, a.asksSent)
        assertTrue("every ask out, none answered", a.spent())
        a.answered()
        assertFalse(a.spent())
    }

    // ---- #584's review: the exit guard's re-asks are bounded

    @Test fun theExitGuardLeavesARoundStillOutAlone() {
        val c = claim()
        c.shown()
        assertEquals(MapHoldClaim.Guard.ROUND, c.guard(roundAsking = true))
        assertEquals("nothing counted, nothing restarted", 0, c.guardAsks)
        assertEquals(MapHoldClaim.St.ASKING, c.st)
        assertEquals(MapHoldClaim.Guard.OFF, claim().guard(roundAsking = false))
    }

    @Test fun aTakeThatOnlyEverWaitsIsAskedAFewTimesThenLeft() {
        val c = claim()
        c.shown()
        c.answer(ok = true, why = "", waits = true)                    // the stale pend: "ok, waits" every time
        for (i in 1..MapHoldClaim.MAX_GUARD_ASKS) {
            assertEquals(MapHoldClaim.Guard.ASK, c.guard(roundAsking = false))
            assertEquals(i, c.guardAsks)
            c.again("the exit guard")
            assertFalse(c.answer(ok = true, why = "", waits = true))
            assertEquals(MapHoldClaim.St.PENDING, c.st)
        }
        assertEquals(MapHoldClaim.Guard.SPENT, c.guard(roundAsking = false))
        assertEquals(MapHoldClaim.St.UNANSWERED, c.st)
        assertFalse(c.cursorLive())
        assertEquals(MapHoldClaim.Guard.SPENT, c.guard(roundAsking = false))
        assertEquals("said once", 1, lines.count { it.contains("exit-guard asks") })
        // a hold lands (the add-on's state): the count starts again
        c.win(held = true, pend = false)
        assertTrue(c.held)
        assertEquals(0, c.guardAsks)
        assertEquals(MapHoldClaim.Guard.ASK, c.guard(roundAsking = false))
        assertEquals("the guard's re-ask: the capture names nobody, not held", MapHoldClaim.St.LOST, c.st)
        // a new show starts it again too
        c.hidden(); c.shown()
        assertEquals(0, c.guardAsks)
    }

    @Test fun aRefusalThatWillNotChangeIsNotAskedAgain() {
        for (w in listOf("no controller capture", "no way in", "bad spec")) {
            val c = claim()
            c.shown()
            c.answer(ok = false, why = w)
            assertEquals(MapHoldClaim.Guard.FIXED, c.guard(roundAsking = false))
            assertEquals(MapHoldClaim.Guard.FIXED, c.guard(roundAsking = false))
            assertEquals(w, 0, c.guardAsks)
            assertEquals(MapHoldClaim.St.REFUSED, c.st)
        }
        assertEquals("logged once a claim", 3, lines.count { it.contains("not asked again by the exit guard") })
        val b = claim()
        b.shown()
        b.answer(ok = false, why = "busy")                             // another holder: the guard asks once nobody holds
        assertEquals(MapHoldClaim.Guard.ASK, b.guard(roundAsking = false))
    }

    @Test fun homesReaderWaitsWhileAnotherHolderDrivesThePad() {
        val home = src("HomeView.kt")
        assertTrue("an NPC window's (a room's) loss: no B reader of Home's while it holds",
            home.contains("if (waitLoss && !nobody) mapFallbackOff(keepWatch = true)"))
        assertTrue("the note fits the case", home.contains("mapNoteSaid = if (fight) FIGHT_MAP_NOTE else KEPT_MAP_NOTE"))
        val lua = File(listOf("../companion/app/src/main/assets/companion/addons/WowCompanion/Map.lua",
            "companion/app/src/main/assets/companion/addons/WowCompanion/Map.lua").first { File(it).isFile }).readText()
        assertTrue("Map.lua: a stale pend takes for real (tools/test_map_stale_pend.py)", lua.contains("function mr.stalePend()") &&
            lua.contains("if mr.stalePend() then mr.held, mr.pend = false, false end"))
    }

    // the wiring, read off the sources (the room and Home's module both gate their sticks on the claim)
    private fun src(n: String) = File(listOf("../companion/app/src/main/java/com/jrees/wowcompanion/$n", "companion/app/src/main/java/com/jrees/wowcompanion/$n")
        .first { File(it).isFile }).readText()

    @Test fun theRoomAndHomeGateTheirSticksOnTheClaim() {
        val room = src("WorldMapRoomView.kt")
        assertTrue(room.contains("override fun sticksLive() = isShown && !modal() && claimLive()"))
        assertTrue("the list's stick steps too", room.contains("region != MapRoomKeys.Region.LIST || !claimLive()"))
        assertTrue("the exit guard's re-ask", room.contains("fun guardReask()"))
        val map = src("MapView.kt")
        assertTrue(map.contains("!pmenu.open && sticksClaim()"))
        val home = src("HomeView.kt")
        assertTrue(home.contains("mapView.sticksClaim = { mapSticksLive() }"))
        val host = src("CompanionHost.kt")
        assertTrue(host.contains("worldMapRoom.guardReask()") && host.contains("homeView.onMapStick("))
    }

    @Test fun theNoteIsTranslated() {
        for (f in listOf("es.json", "fr.json")) {
            val t = File(listOf("../companion/app/src/main/assets/companion/i18n/$f", "companion/app/src/main/assets/companion/i18n/$f")
                .first { File(it).isFile }).readText()
            for (n in listOf(WorldMapRoomView.NO_HOLD_NOTE, HomeView.KEPT_MAP_NOTE, HomeView.FIGHT_MAP_NOTE)) assertTrue("$f: $n", t.contains("\"$n\": "))
        }
    }
}
