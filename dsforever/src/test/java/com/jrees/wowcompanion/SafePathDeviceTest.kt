package com.jrees.wowcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * SafePath on the device (E:/safepath-work/docs/DEVICE_INTEGRATION_PLAN.md, steps 1-7): the files the device's harvest writes and
 * reads back (CLB3, FLR1, CAV1), the route sequence the harness and the device share (SafeRoute), the danger per character
 * (DangerInputs), and the route's memory bounds
 */
class SafePathDeviceTest {
    private val org = 32.0 * MapHarvest.ADT_YD
    private val s = Climb.STEP

    /** a hill with a 46 degree lip and a cliff, its faces read (the cells' middles), over world x 200 .. -200, y 200 .. -200 */
    private fun hill(): Climb.Grid {
        fun h(x: Double, y: Double) = when { y > 100 -> 40.0; y > 60 -> (100 - y) * 0.2 + 32.0; else -> 0.0 } + if (x in 0.0..4.0) x * 1.04 else 0.0
        return Climb.build(200.0, -200.0, 200.0, -200.0, { gr, gc -> h(org - gr * s, org - gc * s).toFloat() },
            centre = { gr, gc -> h(org - (gr + 0.5) * s, org - (gc + 0.5) * s).toFloat() })
    }

    private fun temp(name: String) = File.createTempFile(name, ".bin").also { it.deleteOnExit() }

    // ---------------------------------------------------------------- CLB3

    @Test fun aLatticeComesBackFromItsFileWithItsFacesShortStepsAndRooms() {
        val g = hill()
        assertNotNull(g.faces); assertNotNull(g.faces48)
        assertTrue("the hill has 45-48 degree faces", g.faces48!!.any { it.toInt() != 0 })
        // a room 10 x 6 yd round world (-50, -50), turned 30 degrees, on its own ADT tile
        val a = Math.toRadians(30.0)
        val foot = Buildings.Foot(-50.0, -50.0, Math.cos(a), Math.sin(a), -5.0, 5.0, -3.0, 3.0)
        val tc = Math.floor((org - -50.0) / MapHarvest.ADT_YD).toInt(); val tr = Math.floor((org - -50.0) / MapHarvest.ADT_YD).toInt()
        g.rooms = Climb.Rooms(mapOf(Climb.Rooms.tileKey(tc, tr) to listOf(foot)))
        assertTrue(g.solid!!(-50.0, -50.0)); assertFalse(g.solid!!(-50.0, -60.0))
        val f = temp("clb3")
        g.write(f)
        assertFalse("no .part left", File(f.path + ".part").exists())
        val back = Climb.Grid.read(f)!!
        assertEquals(g.gr0, back.gr0); assertEquals(g.gc0, back.gc0); assertEquals(g.nx, back.nx); assertEquals(g.ny, back.ny)
        assertArrayEquals(g.cls, back.cls)
        assertArrayEquals(g.faces, back.faces)
        assertArrayEquals(g.faces48, back.faces48)
        assertEquals(1, back.rooms!!.size)
        assertTrue(back.solid!!(-50.0, -50.0)); assertFalse(back.solid!!(-50.0, -60.0))
        // the rules read the same off the file: every line the original clears, the copy clears
        for (k in 0 until 200) {
            val x0 = 190.0 - k * 1.9; val pa = doubleArrayOf(x0, 150.0); val pb = doubleArrayOf(-x0, -150.0 + k)
            assertEquals(g.lineClear(pa, pb), back.lineClear(pa, pb))
            assertEquals(Climb.walkable(g, pa, pb), Climb.walkable(back, pa, pb))
        }
    }

    @Test fun aLatticeWithoutFacesOrRoomsComesBackWithout() {
        val g = Climb.build(100.0, -100.0, 100.0, -100.0, { _, _ -> 0f })
        val f = temp("clb3bare"); g.write(f)
        val back = Climb.Grid.read(f)!!
        assertNull(back.faces); assertNull(back.faces48); assertNull(back.rooms); assertNull(back.solid)
        assertArrayEquals(g.cls, back.cls)
    }

    @Test fun anOldClb2FileIsNeverRead() {
        // CLB2: the steps and the faces, no 45-48 degree faces, no rooms - made by older rules, so the lattice is made again
        val g = hill(); val f = temp("clb2")
        DataOutputStream(GZIPOutputStream(f.outputStream())).use { o ->
            o.writeInt(Climb.Grid.MAGIC_CLB2); o.writeInt(g.gr0); o.writeInt(g.gc0); o.writeInt(g.nx); o.writeInt(g.ny); o.write(g.cls); o.write(g.faces!!)
        }
        assertNull(Climb.Grid.read(f))
    }

    @Test fun aLatticeOfOtherRulesIsNeverRead() {
        val g = hill(); val f = temp("clb3old")
        DataOutputStream(GZIPOutputStream(f.outputStream())).use { o -> g.write(o) }
        val b = java.util.zip.GZIPInputStream(f.inputStream()).readBytes()
        // the rules version is the second int
        b[7] = (b[7] + 1).toByte()
        val f2 = temp("clb3other")
        GZIPOutputStream(f2.outputStream()).use { it.write(b) }
        assertNull(Climb.Grid.read(f2))
        assertNotNull(Climb.Grid.read(f))
        // a cut file reads as none, never half a lattice
        val f3 = temp("clb3cut"); GZIPOutputStream(f3.outputStream()).use { it.write(java.util.zip.GZIPInputStream(f.inputStream()).readBytes().copyOf(200)) }
        assertNull(Climb.Grid.read(f3))
    }
}
