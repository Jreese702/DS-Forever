package com.jrees.wowcompanion

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-interiors-stutter, off-device: the indoor maps build only while the player stands still and the CPU is cool */
class IndoorIdleTest {
    private fun pos(x: Double, y: Double, c: Int = 0) = IndoorBakeQueue.Position(c, x, y)

    // ---- moving / standing still

    @Test fun noPositionYetIsNotMoving() = assertFalse(IndoorIdle.Motion().moving(10_000))

    @Test fun aMovePausesForThreeSecondsAfterTheLastStep() {
        val m = IndoorIdle.Motion()
        m.note(pos(0.0, 0.0), 1_000)                  // the first "pos" document: the add-on sends them while the player moves
        assertTrue(m.moving(1_000))
        m.note(pos(5.0, 0.0), 2_000)
        assertTrue(m.moving(4_999))
        assertFalse(m.moving(5_000))                  // 3 s still
    }

    @Test fun theSamePlaceAgainIsStandingStill() {
        val m = IndoorIdle.Motion()
        m.note(pos(10.0, 10.0), 0)
        m.note(pos(10.0, 10.0), 15_000)               // the 15 s snapshot while standing
        m.note(pos(10.2, 10.1), 16_000)               // jitter under half a yard
        assertFalse(m.moving(16_000))
    }

    @Test fun aNewContinentIsAMove() {
        val m = IndoorIdle.Motion()
        m.note(pos(10.0, 10.0, c = 0), 0)
        m.note(pos(10.0, 10.0, c = 1), 20_000)
        assertTrue(m.moving(20_000))
    }

    // ---- the heat cut-off

    @Test fun heatHasHysteresis() {
        assertFalse(IndoorIdle.Heat.hot(false, 84.9f))
        assertTrue(IndoorIdle.Heat.hot(false, 85f))
        assertTrue(IndoorIdle.Heat.hot(true, 80f))    // still above the resume line
        assertTrue(IndoorIdle.Heat.hot(true, 75.1f))
        assertFalse(IndoorIdle.Heat.hot(true, 75f))
        assertFalse(IndoorIdle.Heat.hot(true, null))  // unreadable: this test says nothing (PowerManager's still count)
    }

    @Test fun cpuTemperatureFromThermalZones() {
        val root = Files.createTempDirectory("thermal").toFile()
        fun zone(n: Int, type: String, temp: String) = File(root, "thermal_zone$n").apply {
            mkdirs(); File(this, "type").writeText("$type\n"); File(this, "temp").writeText("$temp\n")
        }
        zone(0, "cpuss-0", "86000")
        zone(1, "cpu-1-3", "95300")
        zone(2, "gpuss-0", "99000")                   // not a CPU zone
        zone(3, "battery", "40000")
        zone(4, "cpufreq-x", "120000")                // not a temperature sensor of the CPU
        assertEquals(95.3f, IndoorIdle.CpuThermal(root).maxC()!!, 0.01f)
        assertNull(IndoorIdle.CpuThermal(Files.createTempDirectory("none").toFile()).maxC())
    }

    @Test fun temperatureParsing() {
        assertEquals(45f, IndoorIdle.CpuThermal.parseC("45000")!!, 0.01f)
        assertEquals(45f, IndoorIdle.CpuThermal.parseC("45")!!, 0.01f)
        assertNull(IndoorIdle.CpuThermal.parseC("junk"))
        assertNull(IndoorIdle.CpuThermal.parseC("-273000"))
        assertTrue(IndoorIdle.CpuThermal.isCpuZone("cpuss-2"))
        assertFalse(IndoorIdle.CpuThermal.isCpuZone("gpuss-1"))
    }

    // ---- the gate's reasons

    private class FakeSignals(var busy: Boolean = false, var moving: Boolean = false, var hot: Boolean = false) : IndoorScheduler.Signals {
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = busy
        override fun moving() = moving
        override fun hot() = hot
        override fun stop() = false
        override fun now() = 0L
        override fun sleep(ms: Long) {}
    }

    @Test fun movingPausesTheBake() {
        val s = FakeSignals()
        assertNull(IndoorScheduler.pauseReason(s))
        s.moving = true
        assertEquals("player moving", IndoorScheduler.pauseReason(s))
        s.hot = true
        assertEquals("player moving", IndoorScheduler.pauseReason(s))   // the cheapest reason first
        s.moving = false
        assertEquals("device warm", IndoorScheduler.pauseReason(s))
        s.busy = true
        assertEquals("game busy", IndoorScheduler.pauseReason(s))
    }

    @Test fun aSignalsWithoutMovementNeverPausesForIt() {
        val old = object : IndoorScheduler.Signals {
            override fun position(): IndoorBakeQueue.Position? = null
            override fun busy() = false
            override fun hot() = false
            override fun stop() = false
            override fun now() = 0L
            override fun sleep(ms: Long) {}
        }
        assertNull(IndoorScheduler.pauseReason(old))
    }
}
