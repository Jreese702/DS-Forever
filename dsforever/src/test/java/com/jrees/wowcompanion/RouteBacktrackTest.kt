package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b-route-no-backtrack (the user on test40, Vanilla: "my marker is making me leave stormwind and then re-enter it over and over for my
 * quest to go to westfall"). A stand-in geography, in one frame of yards (x east, y south): Elwynn 3000 x 2000 at (0, 0); Stormwind
 * 1000 x 1000 at (-300, -800), its gate into Elwynn at Elwynn (300, 200); the Westfall quest at (-900, 1400), south-west of both.
 */
class RouteBacktrackTest {
    private val SW = 1453; private val ELWYNN = 1429
    private fun elw(x: Double, y: Double) = doubleArrayOf(x / 3000.0, y / 2000.0)
    private fun sw(x: Double, y: Double) = doubleArrayOf((x + 300.0) / 1000.0, (y + 800.0) / 1000.0)
    private val quest = doubleArrayOf(-900.0, 1400.0)
    private val name: (CityExits.Exit) -> String? = { e -> mapOf(SW to "To Stormwind", ELWYNN to "To Elwynn Forest")[e.toMap] }

    // the learned crossings (on foot): Elwynn -> Stormwind at the gate, Stormwind -> Elwynn at the gate
    private fun wayElwToSw(): CityExits.Way { val g = elw(300.0, 200.0); return CityExits.Way(CityExits.Exit(ELWYNN, "", toMap = SW, learned = true), g[0], g[1], g[0], g[1]) }
    private fun waySwToElw(): CityExits.Way { val g = sw(300.0, 180.0); return CityExits.Way(CityExits.Exit(SW, "", toMap = ELWYNN, learned = true), g[0], g[1], g[0], g[1]) }

    private fun inElwynn(px: Double, py: Double, cameFrom: Int, keep: String? = null): CursorRoute.Route {
        val p = elw(px, py); val f = elw(quest[0], quest[1])
        return CursorRoute.plan(p[0], p[1], 3000.0, 2000.0, emptyList(), f, null, null, emptyList(), ways = listOf(wayElwToSw()),
            inCity = false, wayName = name, cameFrom = cameFrom, keep = keep) { _, _ -> false }!!
    }
    private fun inStormwind(px: Double, py: Double, cameFrom: Int): CursorRoute.Route {
        val p = sw(px, py); val f = sw(quest[0], quest[1])
        return CursorRoute.plan(p[0], p[1], 1000.0, 1000.0, emptyList(), f, null, null, emptyList(), ways = listOf(waySwToElw()),
            inCity = true, wayName = name, cameFrom = cameFrom) { _, _ -> false }!!
    }

    @Test fun stormwindSendsYouOutThroughTheGate() {
        val r = inStormwind(200.0, -300.0, cameFrom = ELWYNN)          // in the city, come in from Elwynn: the gate out still gains
        assertEquals("To Elwynn Forest", r.label)
    }

    @Test fun justThroughTheGateElwynnNeverSendsYouBack() {
        // the reported loop: one step into Elwynn, the gate back is at the player's feet and used to win
        val r = inElwynn(300.0, 210.0, cameFrom = SW)
        assertNotEquals("To Stormwind", r.label)
        assertEquals("edge", r.key)                                     // the edge toward the quest (no game waypoint here)
        assertTrue(r.label!!.startsWith("Exit"))
    }

    @Test fun theWholeWalkNeverFlips() {
        // Stormwind (come from Elwynn) -> through the gate -> across Elwynn toward Westfall: not once "To Stormwind"
        assertEquals("To Elwynn Forest", inStormwind(200.0, -300.0, cameFrom = ELWYNN).label)
        var keep: String? = null
        for (i in 0..10) {
            val t = i / 10.0
            val r = inElwynn(300.0 + (0.0 - 300.0) * t, 210.0 + (1200.0 - 210.0) * t, cameFrom = SW, keep = keep)
            assertNotEquals("step $i", "To Stormwind", r.label)
            keep = r.key
        }
    }

    @Test fun inOpenCountryAWalkDetourLoses() {
        // fresh in Elwynn (no map before), far from the gate: the gate north is a detour to a quest south-west - straight instead
        val r = inElwynn(1500.0, 1500.0, cameFrom = 0)
        assertNotEquals("To Stormwind", r.label)
    }

    @Test fun theChosenWayHoldsUntilAnotherIsClearlyCheaper() {
        val f = doubleArrayOf(1.2, 0.3)
        val a = CityExits.Way(CityExits.Exit(1, "A", toMap = 7), 1.0, 0.45, 1.0, 0.45)
        val b = CityExits.Way(CityExits.Exit(1, "B", toMap = 8), 1.0, 0.55, 1.0, 0.55)
        val keepB = CityExits.key(b)
        // A about 10% cheaper: B, chosen before, stays
        assertEquals("B", CityExits.choose(0.5, 0.48, f, listOf(a, b), 1000.0, 1000.0, keep = keepB).way!!.exit.name)
        // A half the cost: A wins, and the runner-up is B
        val ch = CityExits.choose(0.95, 0.4, f, listOf(a, b), 1000.0, 1000.0, keep = keepB)
        assertEquals("A", ch.way!!.exit.name); assertEquals("B", ch.runnerUp!!.exit.name)
    }

    @Test fun theChoiceSaysWhy() {
        val r = inStormwind(200.0, -300.0, cameFrom = ELWYNN)
        assertTrue(r.why!!, r.why!!.startsWith("To Elwynn Forest (") && "straight" in r.why!!)
    }
}
