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
import kotlin.math.hypot

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
        // EDGE CLEARANCE's drops (the hill's 40 yd cliff): kept, so the lattice read back costs a rim's lip as the one made did
        assertTrue("the cliff has a lip", g.dropD!!.any { (it.toInt() and 0xFF) < 255 })
        assertArrayEquals(g.dropD, back.dropD)
        val legA = Climb.route(g, doubleArrayOf(150.0, 130.0), doubleArrayOf(-150.0, 120.0))!!; val legB = Climb.route(back, doubleArrayOf(150.0, 130.0), doubleArrayOf(-150.0, 120.0))!!
        assertEquals(legA.pts.map { it.toList() }, legB.pts.map { it.toList() })
        assertEquals(1, back.rooms!!.size)
        assertTrue(back.solid!!(-50.0, -50.0)); assertFalse(back.solid!!(-50.0, -60.0))
        // the rules read the same off the file: every line the original clears, the copy clears
        for (k in 0 until 200) {
            val x0 = 190.0 - k * 1.9; val pa = doubleArrayOf(x0, 150.0); val pb = doubleArrayOf(-x0, -150.0 + k)
            assertEquals(g.lineClear(pa, pb), back.lineClear(pa, pb))
            assertEquals(Climb.walkable(g, pa, pb), Climb.walkable(back, pa, pb))
        }
    }

    // ---------------------------------------------------------------- SafeRoute

    private val flat by lazy { Climb.build(200.0, -200.0, 200.0, -200.0, { _, _ -> 0f }) }
    private val noNet = SafePath.Net(1, 0, "", emptyList(), emptyList(), emptyList())

    @Test fun aLongWadeIsRoutedAgainWithNoWading() {
        // a river 1.8 yd deep across the way (world y 20 .. -20), with a ford at world x below -150: the first leg wades it (the narrow
        // stream's depth, but 40 yd on end), so the route is planned again with no wading and crosses at the ford
        val water: (Double, Double) -> Float = { x, y -> if (y in -20.0..20.0) (if (x < -150) 1.0f else 1.8f) else Float.NaN }
        val said = ArrayList<String>()
        val sr = SafeRoute(flat, { _, _ -> 0f }, { _, _ -> 0f }, 8.0, { _, _ -> false }, water, { _, _ -> false }, null)
        val r = sr.plan(noNet, doubleArrayOf(100.0, 150.0), doubleArrayOf(100.0, -150.0), { _, _ -> true }, emptyList(), "A") { said.add(it) }
        assertTrue(said.joinToString(), said.any { it.startsWith("WADE route A") })
        assertTrue(r.reached)
        assertTrue("no wade over the ford's depth: ${sr.longestWade(r.pts)}", sr.longestWade(r.pts) == 0.0)
        assertTrue("crosses at the ford", r.pts.any { it[0] < -140 })
        assertEquals(doubleArrayOf(100.0, -150.0).toList(), r.pts.last().toList())
    }

    @Test fun aGoalNoWalkReachesIsSaidSo() {
        // the goal on a plateau 40 yd up, cliffs all round
        val g = Climb.build(200.0, -200.0, 200.0, -200.0, { gr, gc -> val x = org - gr * s; val y = org - gc * s
            (if (hypot(x - 100, y + 100) < 40) 40f else 0f) })
        val sr = SafeRoute(g, { _, _ -> 0f }, { _, _ -> 0f }, 8.0, { _, _ -> false }, { _, _ -> Float.NaN }, { _, _ -> false }, null)
        val r = sr.plan(noNet, doubleArrayOf(-100.0, 100.0), doubleArrayOf(100.0, -100.0), { _, _ -> true }, emptyList())
        assertFalse(r.reached)
        for ((a, b) in r.pts.zipWithNext()) assertTrue(Climb.walkable(g, a, b))
    }

    @Test fun aLatticesFloorsComeBackFromItsFile() {
        // a platform 6 yd over flat ground (world x 20 .. -20, y 20 .. -20) with a ramp up from the east: the floor a walker is on at its
        // vertices (Grid.deckZ, its high edge Grid.deckHigh) rules lines over it, so the file keeps it
        val deck: (Double, Double) -> Float = { x, y -> if (x in -20.0..20.0 && y in -20.0..20.0) 6f else if (x in -4.0..4.0 && y in -60.0..-20.0) (6 + (y + 20) * 0.15).toFloat() else Float.NaN }
        val g = Climb.build(100.0, -100.0, 100.0, -100.0, { _, _ -> 0f }, centre = { _, _ -> 0f }, deck = deck)
        assertNotNull(g.deckZ)
        val f = temp("clb3floors"); g.write(f)
        val back = Climb.Grid.read(f)!!
        assertEquals(g.deckZ!!.count { !it.isNaN() }, back.deckZ!!.count { !it.isNaN() })
        for (k in g.deckZ!!.indices) { assertEquals(g.deckZ!![k].isNaN(), back.deckZ!![k].isNaN()); if (!g.deckZ!![k].isNaN()) assertEquals(g.deckZ!![k], back.deckZ!![k], 0f) }
        assertArrayEquals(g.deckHigh, back.deckHigh)
        for (k in 0 until 120) {
            val pa = doubleArrayOf(-50.0 + k, 50.0 - k * 0.7); val pb = doubleArrayOf(50.0 - k * 0.3, -50.0 + k * 0.9)
            assertEquals(Climb.walkable(g, pa, pb), Climb.walkable(back, pa, pb))
        }
    }

    // ---------------------------------------------------------------- memory and cancellation

    @Test fun theZoneFilesKeepThreeAtMost() {
        val lru = RouteWork.Lru<Int, String>(3)
        for (m in 1..3) lru[m] = "zone $m"
        lru[1]                                         // the first one used again: the second is now the oldest
        lru[4] = "zone 4"
        assertEquals(setOf(1, 3, 4), lru.keys)
        lru[5] = "zone 5"
        assertEquals(setOf(1, 4, 5), lru.keys)
    }

    /** flat ground over world x [half] .. -[half], y the same */
    private fun plain(half: Double) = Climb.build(half, -half, half, -half, { _, _ -> 0f })

    @Test fun aLegTooBigForItsWindowIsNarrowedOrRefused() {
        val g = plain(2000.0)                                  // 961 x 961 vertices
        val a = doubleArrayOf(1500.0, 1500.0); val b = doubleArrayOf(-1500.0, -1500.0)
        val free = Climb.route(g, a, b)!!
        // the whole lattice is its window (over a million vertices with the margin): narrowed to fit 800k, the same way on flat ground
        val narrowed = Climb.route(g, a, b, limits = Climb.Limits(maxWindow = 800_000))!!
        assertTrue(narrowed.reached)
        assertEquals(SafeRoute.length(free.pts), SafeRoute.length(narrowed.pts), 1.0)
        assertTrue("the scratch is the narrowed window's", Climb.Scratch.sizeHere() >= 961 * 961)
        // a window no margin makes small enough: no leg
        assertNull(Climb.route(g, a, b, limits = Climb.Limits(maxWindow = 100_000)))
        // under the cap, nothing changes
        val capped = Climb.route(g, doubleArrayOf(100.0, 100.0), doubleArrayOf(-100.0, -100.0), limits = Climb.Limits())!!
        val plainLeg = Climb.route(g, doubleArrayOf(100.0, 100.0), doubleArrayOf(-100.0, -100.0))!!
        assertEquals(plainLeg.pts.map { it.toList() }, capped.pts.map { it.toList() })
    }

    @Test fun theScratchIsReusedAndLetGo() {
        val g = plain(2000.0)
        Climb.Scratch.release()
        Climb.route(g, doubleArrayOf(1500.0, 1500.0), doubleArrayOf(-1500.0, -1500.0))
        val big = Climb.Scratch.sizeHere()
        assertTrue(big > 0)
        // a shorter leg reuses the same arrays, and comes out the same as on fresh ones
        val a = Climb.route(g, doubleArrayOf(50.0, 50.0), doubleArrayOf(-50.0, 30.0))!!
        assertEquals(big, Climb.Scratch.sizeHere())
        Climb.Scratch.release()
        val b = Climb.route(g, doubleArrayOf(50.0, 50.0), doubleArrayOf(-50.0, 30.0))!!
        assertTrue("let go: the next leg's arrays are its own size", Climb.Scratch.sizeHere() < big)
        assertEquals(a.pts.map { it.toList() }, b.pts.map { it.toList() })
    }

    @Test fun aCancelledLegStopsWithinTenMilliseconds() {
        // a goal on a plateau no step reaches: the A* searches the whole 800 yd window round it until it is told to stop
        val g = Climb.build(2800.0, -2800.0, 2800.0, -2800.0, { gr, gc -> val x = org - gr * s; val y = org - gc * s
            if (hypot(x, y) < 30) 50f else 0f })
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        var stopAt = 0L; var endAt = 0L; var threw = false
        val t = Thread {
            try { Climb.route(g, doubleArrayOf(2500.0, 2500.0), doubleArrayOf(0.0, 0.0), limits = Climb.Limits(cancelled = { stop.get() })) }
            catch (e: Climb.Cancelled) { threw = true }
            endAt = System.nanoTime()
        }
        t.start(); Thread.sleep(100)
        assertTrue("still searching after 100 ms (the test's premise)", t.isAlive)
        stopAt = System.nanoTime(); stop.set(true)
        t.join(2000)
        assertTrue(threw)
        val ms = (endAt - stopAt) / 1e6
        assertTrue("stopped in $ms ms", ms < 10.0)
    }

    @Test fun aLatticeWithoutFacesOrRoomsComesBackWithout() {
        val g = Climb.build(100.0, -100.0, 100.0, -100.0, { _, _ -> 0f })
        val f = temp("clb3bare"); g.write(f)
        val back = Climb.Grid.read(f)!!
        assertNull(back.faces); assertNull(back.faces48); assertNull(back.rooms); assertNull(back.solid); assertNull(back.deckZ)
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
