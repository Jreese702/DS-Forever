package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-route-unknown-taxi, off-device: a flight only between known points a flight master showed connected */
class FlightNetTest {
    private class Mem : TaxiNet.Store {
        val m = HashMap<String, String>()
        override fun get(k: String) = m[k]
        override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v }
    }

    // a 3000 x 2000 yd map; the player at the west edge, the quest at the east edge; known points near both
    private val ydX = 3000.0; private val ydY = 2000.0
    private val nodes = listOf(doubleArrayOf(0.06, 0.5), doubleArrayOf(0.94, 0.5), doubleArrayOf(0.5, 0.1))
    private fun pick(conn: (Int, Int) -> Boolean) = FlightRoute.pick(0.05, 0.5, 0.95, 0.5, nodes, ydX, ydY, conn)

    @Test fun noKnownConnectionWalks() = assertNull(pick { _, _ -> false })   // the user's case: known points, none connected

    @Test fun aKnownConnectionFlies() = assertEquals(0, pick { a, b -> setOf(a, b) == setOf(0, 1) })

    @Test fun onlyTheConnectedPairIsUsed() {
        // only 0 <-> 2 connect: flying to the middle north point is not clearly faster than walking: walk
        assertNull(pick { a, b -> setOf(a, b) == setOf(0, 2) })
    }

    @Test fun shortWalksStayWalks() =
        assertNull(FlightRoute.pick(0.05, 0.5, 0.1, 0.5, nodes, ydX, ydY) { _, _ -> true })

    @Test fun groupsMergeAcrossFlightMasters() {
        val m = TaxiNet.Model(Mem()); m.character("Thrall-Forever")
        m.onTaxiNet(1, listOf(2, 3))
        assertTrue(m.connected(1, 3) && m.connected(2, 3))
        assertFalse(m.connected(1, 9))
        m.onTaxiNet(9, listOf(10))                  // another network (e.g. an island): apart
        assertFalse(m.connected(3, 10))
        m.onTaxiNet(3, listOf(9))                   // a flight master that links them: one network now
        assertTrue(m.connected(1, 10))
    }

    @Test fun keptPerCharacter() {
        val s = Mem()
        val m = TaxiNet.Model(s); m.character("Thrall-Forever"); m.onTaxiNet(1, listOf(2))
        val again = TaxiNet.Model(s); again.character("Thrall-Forever")
        assertTrue(again.connected(1, 2))             // kept across app lives
        again.character("Jaina-Forever")
        assertFalse(again.connected(1, 2))            // another character knows its own network
    }

    @Test fun nothingBeforeACharacter() {
        val m = TaxiNet.Model(Mem())
        m.onTaxiNet(1, listOf(2))
        assertFalse(m.connected(1, 2))
    }
}
