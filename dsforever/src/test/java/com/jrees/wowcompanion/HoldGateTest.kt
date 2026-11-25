package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOLD TO CAST, THE GATE (PadMap m190's app half; the user, 2026-11-24: "hold to cast on a button so that we can have 2 spells per
 * button"; picks: "At the hold time, Settings: 0.3/0.4/0.55 s", "Every button incl. B"). Off the device, on a made-up clock: a tap goes to
 * the game on release, a hold at the hold time with the Guide marker first, every other button passes in the same call, the triggers as
 * at the press, nothing ever stays down.
 */
class HoldGateTest {
    private val A = 1 shl HoldGate.A
    private val Bb = 1 shl HoldGate.B
    private val X = 1 shl HoldGate.X
    private val Y = 1 shl HoldGate.Y
    private val LB = 1 shl 4
    private val UP = 1 shl HoldGate.UP
    private val G = 1 shl HoldGate.GUIDE

    private fun gate(vararg keys: Int, on: Boolean = true) = HoldGate().apply { configure(on, keys.toSet(), 0L) }
    private val bareA = HoldGate.key(0, 8)

    /** runs the gate from [from] to [to] every ms with [raw], returning (time, out) of every change of the output */
    private fun HoldGate.run(raw: Int, from: Long, to: Long, lt: Float = 0f, rt: Float = 0f): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        var last = this.out
        for (t in from..to) { val o = update(raw, lt, rt, t); if (o != last) { out += t to o; last = o } }
        return out
    }

    @Test fun aButtonWithoutAHoldSpellPassesAtOnce() {
        val g = gate(bareA)
        assertEquals("X (no hold spell) goes down in the same call", X, g.update(X, 0f, 0f, 1000))
        assertEquals(0, g.update(0, 0f, 0f, 1010))
        assertEquals("the shoulders are never gated", LB or Y, g.update(LB or Y, 0f, 0f, 1020))
        assertEquals("nothing pending: no next moment", Long.MAX_VALUE, g.nextAt())
    }

    @Test fun offEverythingPasses() {
        val g = gate(bareA, on = false)
        assertEquals(A, g.update(A, 0f, 0f, 1000))
        assertFalse(g.busy())
    }

    @Test fun aTapGoesOnReleaseForTapMs() {
        val g = gate(bareA)
        assertEquals("held back while pressed", 0, g.update(A, 0f, 0f, 1000))
        assertEquals(1400L, g.nextAt())
        assertEquals("still held back at 250 ms", 0, g.update(A, 0f, 0f, 1250))
        assertEquals("let go at 300 ms: the press goes now, no marker", A, g.update(0, 0f, 0f, 1300))
        assertEquals(1300L + HoldGate.TAP_MS, g.nextAt())
        assertEquals(A, g.update(0, 0f, 0f, 1300 + HoldGate.TAP_MS - 1))
        assertEquals("and up after TAP_MS", 0, g.update(0, 0f, 0f, 1300 + HoldGate.TAP_MS))
        assertFalse(g.busy())
        assertEquals(8 to 1300L, g.lastTap)
    }

    @Test fun aHoldFiresAtTheHoldTimeStillHeld() {
        val g = gate(bareA)
        g.update(A, 0f, 0f, 1000)
        val seen = g.run(A, 1001, 1600)
        assertEquals("the Guide marker first, at 400 ms", 1400L to G, seen[0])
        assertEquals("the button LEAD_MS later, the marker still down", (1400L + HoldGate.LEAD_MS) to (G or A), seen[1])
        assertEquals("the marker up TAIL_MS after the button; the button stays down while held",
            (1400L + HoldGate.LEAD_MS + HoldGate.TAIL_MS) to A, seen[2])
        assertEquals(3, seen.size)
        assertEquals(8 to 1400L, g.lastFire)
        assertEquals("let go: up at once", 0, g.update(0, 0f, 0f, 2000))
        assertFalse(g.busy())
    }

    @Test fun releaseExactlyAtTheThresholdIsTheHold() {
        val g = gate(bareA)
        g.update(A, 0f, 0f, 1000)
        assertEquals("released at 399 ms: a tap", A, gate(bareA).also { it.update(A, 0f, 0f, 1000) }.update(0, 0f, 0f, 1399))
        // released at exactly 400 ms (the timer not run yet): the hold, the marker first and the press kept down TAP_MS
        assertEquals(G, g.update(0, 0f, 0f, 1400))
        assertEquals(G or A, g.update(0, 0f, 0f, 1400 + HoldGate.LEAD_MS))
        assertEquals(A, g.update(0, 0f, 0f, 1400 + HoldGate.LEAD_MS + HoldGate.TAIL_MS))
        assertEquals(0, g.update(0, 0f, 0f, 1400 + HoldGate.LEAD_MS + HoldGate.TAP_MS))
        assertFalse(g.busy())
    }

    @Test fun theHoldTimeIsTheSetting() {
        for (ms in listOf(HoldGate.SHORT_MS, HoldGate.NORMAL_MS, HoldGate.LONG_MS)) {
            val g = gate(bareA).apply { holdMs = ms }
            g.update(A, 0f, 0f, 0)
            assertEquals(0, g.update(A, 0f, 0f, ms - 1))
            assertEquals("fires at $ms", G, g.update(A, 0f, 0f, ms))
        }
        assertEquals(listOf(300L, 400L, 550L), CastSettings.HoldTime.values().map { it.ms })
    }

    @Test fun twoButtonsAtOnce() {
        val ky = HoldGate.key(0, 6)
        val g = gate(bareA, ky)
        g.update(A or Y, 0f, 0f, 1000)
        // Y let go at 200 ms (a tap), A held on (its hold)
        assertEquals("Y's tap goes, A still held back", Y, g.update(A, 0f, 0f, 1200))
        val seen = g.run(A, 1201, 1500)
        assertTrue("A's marker never shares a moment with Y's tap", seen.none { (_, o) -> o and G != 0 && o and Y != 0 })
        assertTrue(seen.any { (_, o) -> o == (G or A) })
    }

    @Test fun aTapNeverReadsAsAHold() {
        val ky = HoldGate.key(0, 6)
        val g = gate(bareA, ky)
        g.update(A, 0f, 0f, 1000)
        g.update(A or Y, 0f, 0f, 1300)
        g.update(A or Y, 0f, 0f, 1400)                                  // A fires: the marker is down
        assertEquals(G, g.out)
        val up = g.update(A, 0f, 0f, 1410)                              // Y let go now: its tap must wait for the marker to go
        assertEquals("Y not sent with the marker down", 0, up and Y)
        val seen = g.run(A, 1411, 1700)
        val yAt = seen.first { (_, o) -> o and Y != 0 }
        assertEquals("Y alone, after the marker", 0, yAt.second and G)
        assertTrue(yAt.first >= 1400 + HoldGate.LEAD_MS + HoldGate.TAIL_MS + HoldGate.GAP_MS)
    }

    @Test fun aHoldFiringDefersATapNotSentYet() {
        val ky = HoldGate.key(0, 6); val kx = HoldGate.key(0, 5)
        val g = gate(bareA, ky, kx)
        g.update(Y, 0f, 0f, 1000)
        g.update(Y or X, 0f, 0f, 1050)
        g.update(X, 0f, 0f, 1400)                                       // Y fires (held to 400 ms) and is let go at once: marker
        g.update(0, 0f, 0f, 1420)                                       // X let go at 370 ms of its press: a tap, after the marker
        val seen = g.run(0, 1421, 1700)
        assertTrue(seen.none { (_, o) -> o and G != 0 && o and X != 0 })
        assertTrue(seen.any { (_, o) -> o and X != 0 })
    }

    @Test fun theTriggerIsRememberedAtThePress() {
        val ltA = HoldGate.key(1, 8)
        val g = gate(ltA)
        assertEquals("LT + A held back", 0, g.update(A, 1f, 0f, 1000))
        g.update(A, 0f, 0f, 1200)                                       // LT let go before A
        assertEquals(G, g.update(A, 0f, 0f, 1400))
        assertEquals("the trigger sent as held at the press", 1f, g.ltOut, 0f)
        g.update(A, 0f, 0f, 1400 + HoldGate.LEAD_MS)
        assertEquals(G or A, g.out)
        assertEquals("still held as A goes down", 1f, g.ltOut, 0f)
        g.run(A, 1401 + HoldGate.LEAD_MS, 1600)
        assertEquals("the real trigger once the press is in", 0f, g.ltOut, 0f)
        // a trigger pulled AFTER a bare press does not move a tap onto the LT layer
        val g2 = gate(bareA)
        g2.update(A, 0f, 0f, 1000)
        g2.update(A, 1f, 0f, 1100)
        assertEquals(A, g2.update(0, 1f, 0f, 1200))
        assertEquals("sent with no trigger, as at the press", 0f, g2.ltOut, 0f)
        g2.run(0, 1201, 1300, lt = 1f)
        assertEquals("then the real trigger", 1f, g2.ltOut, 0f)
    }

    @Test fun theLayerDecidesWhatIsGated() {
        val g = gate(HoldGate.key(1, 8))                               // a hold spell on LT + A only
        assertEquals("a bare A passes", A, g.update(A, 0f, 0f, 1000))
        g.update(0, 0f, 0f, 1010)
        assertEquals("LT + A is held back", 0, g.update(A, 1f, 0f, 1020))
        val r = gate(HoldGate.key(3, 2))                               // LT + RT + D-pad up
        assertEquals(0, r.update(UP, 1f, 1f, 1000))
        assertEquals("the trigger's 35 % / 30 % thresholds", UP, gate(HoldGate.key(1, 2)).update(UP, 0.3f, 0f, 1000))
    }

    @Test fun aShoulderChordIsNeverHeldBack() {
        val g = gate(bareA)
        assertEquals("LB + A (LB is the game's emulated Ctrl: another binding): straight through", LB or A, g.update(LB or A, 0f, 0f, 1000))
        assertFalse(g.busy())
        g.update(0, 0f, 0f, 1100)
        assertEquals("Start + A too", (1 shl 7) or A, g.update((1 shl 7) or A, 0f, 0f, 1200))
        g.update(0, 0f, 0f, 1300)
        assertEquals("A alone: held back as always", 0, g.update(A, 0f, 0f, 1400))
    }

    @Test fun bAndTheDpadToo() {
        val g = gate(HoldGate.key(0, 7), HoldGate.key(0, 2))
        assertEquals(0, g.update(Bb or UP, 0f, 0f, 1000))
        assertEquals(Bb or UP, g.update(0, 0f, 0f, 1100))
    }

    @Test fun aHoldDuringALoadingScreen() {
        val g = gate(bareA)
        g.update(A, 0f, 0f, 1000)
        g.configure(false, emptySet(), 1100)                           // the add-on: loading screen (its map off)
        assertEquals("the waiting press goes to the game at once, no marker", A, g.update(A, 0f, 0f, 1100))
        assertEquals("held on: it stays down, never a marker", A, g.update(A, 0f, 0f, 1600))
        assertEquals(0, g.update(0, 0f, 0f, 1700))
        assertFalse(g.busy())
        // and the place losing its hold spell (a page change) does the same
        val g2 = gate(bareA)
        g2.update(A, 0f, 0f, 1000)
        g2.configure(true, setOf(HoldGate.key(0, 6)), 1050)
        assertEquals(A, g2.update(A, 0f, 0f, 1050))
    }

    @Test fun theAppLosingFocusMidHold() {
        val g = gate(bareA)
        g.update(A, 0f, 0f, 1000)
        g.update(A, 0f, 0f, 1400)
        g.update(A, 0f, 0f, 1400 + HoldGate.LEAD_MS)
        assertEquals(G or A, g.out)
        g.reset()
        assertEquals("nothing of ours stays down", 0, g.update(A, 0f, 0f, 1500))
        assertEquals("the finger still on A: ignored until let go", 0, g.update(A, 0f, 0f, 2500))
        assertEquals(0, g.update(0, 0f, 0f, 2600))
        assertFalse(g.busy())
        assertEquals(0, g.update(A, 0f, 0f, 2700))                      // a new press is a new press (held back again)
        assertTrue(g.busy())
        // focus lost while a press waits: dropped, not sent
        val g2 = gate(bareA)
        g2.update(A, 0f, 0f, 1000)
        g2.reset()
        assertEquals(0, g2.update(0, 0f, 0f, 1100))
        assertEquals(Long.MAX_VALUE, g2.nextAt())
    }

    @Test fun pressedAgainDuringTheTapStillCounts() {
        val g = gate(bareA)
        g.update(A, 0f, 0f, 1000)
        g.update(0, 0f, 0f, 1100)                                       // tap: sent 1100..1170
        g.update(A, 0f, 0f, 1130)                                       // pressed again inside the tap's pulse
        g.run(A, 1131, 1600)
        assertEquals("the second press became a hold, timed from its own press", 8 to (1130L + 400L), g.lastFire)
    }

    @Test fun theProbesPulseIsTheMarkerAlone() {
        val g = gate()
        assertTrue(g.probePulse(1000))
        assertEquals(G, g.update(0, 0f, 0f, 1000))
        assertEquals(G, g.update(0, 0f, 0f, 1000 + HoldGate.PROBE_MS - 1))
        assertEquals(0, g.update(0, 0f, 0f, 1000 + HoldGate.PROBE_MS))
        val busy = gate(bareA); busy.update(A, 0f, 0f, 1000)
        assertFalse("never while a press is held back", busy.probePulse(1001))
    }

    /** the 1528 audit: a tap let go during the probe's pulse waits for the pulse to end - with the Guide down it read as the hold */
    @Test fun aTapNeverGoesWithTheProbesPulse() {
        val g = gate(bareA)
        assertTrue(g.probePulse(1000))                                  // the marker alone 1000..1200
        assertEquals("pressed in the pulse: held back", G, g.update(A, 0f, 0f, 1010))
        val seen = g.run(0, 1050, 1500)                                 // let go at 1050: a tap
        for ((t, o) in seen) assertFalse("A with the marker at $t: $seen", o and A != 0 && o and G != 0)
        assertEquals("the tap goes GAP_MS after the pulse", 1000L + HoldGate.PROBE_MS + HoldGate.GAP_MS, seen.first { it.second and A != 0 }.first)
        assertFalse(g.busy())
    }

    /** the 1528 audit: a quick second tap, pressed AND let go while the first still goes to the game, is not lost (mashing) */
    @Test fun aQuickSecondTapInsideTheFirstsPulseIsNotLost() {
        val g = gate(bareA)
        g.update(A, 0f, 0f, 1000)
        assertEquals("tap: sent 1080..1150", A, g.update(0, 0f, 0f, 1080))
        g.update(A, 0f, 0f, 1110)                                       // pressed again inside it
        val seen = g.run(0, 1140, 1500)                                 // and let go at 1140, before the first one's pulse ends
        assertEquals("the first goes up, the second follows GAP_MS later for TAP_MS",
            listOf(1150L to 0, (1150L + HoldGate.GAP_MS) to A, (1150L + HoldGate.GAP_MS + HoldGate.TAP_MS) to 0), seen)
        assertFalse(g.busy())
    }

    @Test fun theAddOnsWords() {
        assertEquals(HoldGate.key(0, 8), HoldGate.keyOf("bare", 8))
        assertEquals(HoldGate.key(3, 1), HoldGate.keyOf("ltrt", 1))
        assertEquals(null, HoldGate.keyOf("bare", 9))
        assertEquals(null, HoldGate.keyOf("x", 1))
    }
}
