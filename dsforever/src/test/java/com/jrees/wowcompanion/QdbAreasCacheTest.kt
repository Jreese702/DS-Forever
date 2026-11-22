package com.jrees.wowcompanion

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #153 (1.0.37: an ANR with the UI thread in QdbAreas.groups, from MapView.deriveQuestAreas on a quests document): the grouping
 * compares only neighbouring spots (the same groups as every pair), the groups are cached per objective and scale, and a big
 * objective is worked out on the areas thread, the asker told when it is ready. Measured: the old pairwise grouping against the new
 * one on the same spots.
 */
class QdbAreasCacheTest {
    /** [n] spots in camps of ~[camp] spots, deterministic (a zone's spread of mob spawns, x / y in 0..1) */
    private fun spots(n: Int, camp: Int = 25, seed: Long = 7L): List<DoubleArray> {
        val r = java.util.Random(seed)
        val out = ArrayList<DoubleArray>(n)
        var cx = 0.0; var cy = 0.0
        for (i in 0 until n) {
            if (i % camp == 0) { cx = r.nextDouble(); cy = r.nextDouble() }
            out.add(doubleArrayOf((cx + r.nextGaussian() * 0.01).coerceIn(0.0, 1.0), (cy + r.nextGaussian() * 0.01).coerceIn(0.0, 1.0)))
        }
        return out
    }
    private val ax = 3_500.0; private val ay = 2_300.0           // a zone's yards across / down

    private fun same(a: List<QdbAreas.Group>, b: List<QdbAreas.Group>) {
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].n, b[i].n)
            assertEquals(a[i].outline.size, b[i].outline.size)
            for (k in a[i].outline.indices) {
                assertEquals(a[i].outline[k][0], b[i].outline[k][0], 1e-12); assertEquals(a[i].outline[k][1], b[i].outline[k][1], 1e-12)
            }
        }
    }

    @Test fun theNeighbourGroupingGivesThePairwiseGroups() {
        for ((n, camp) in listOf(1 to 1, 2 to 2, 40 to 5, 300 to 30, 1_500 to 60, 900 to 900)) {
            val p = spots(n, camp, seed = n.toLong())
            same(QdbAreas.groupsPairwise(p, ax, ay), QdbAreas.groups(p, ax, ay))
        }
        // spots just inside JOIN_YD apart chain into one group across the cell edges, a hair further apart do not
        val d = QdbAreas.JOIN_YD / ax
        val chain = (0 until 10).map { doubleArrayOf(0.1 + it * d * 0.999, 0.5) }
        assertEquals(1, QdbAreas.groups(chain, ax, ay).size)
        val apart = (0 until 10).map { doubleArrayOf(0.1 + it * d * 1.0001, 0.5) }
        assertEquals(QdbAreas.groupsPairwise(apart, ax, ay).size, QdbAreas.groups(apart, ax, ay).size)
    }

    @Test fun measuredFasterOnABigObjective() {
        val p = spots(4_000, 40)
        repeat(2) { QdbAreas.groups(p, ax, ay); QdbAreas.groupsPairwise(p, ax, ay) }     // warm the JIT
        var t = System.nanoTime(); val old = QdbAreas.groupsPairwise(p, ax, ay); val oldMs = (System.nanoTime() - t) / 1e6
        t = System.nanoTime(); val new = QdbAreas.groups(p, ax, ay); val newMs = (System.nanoTime() - t) / 1e6
        println("QdbAreasCacheTest: 4000 spots grouped pairwise in %.1f ms, by neighbours in %.1f ms".format(oldMs, newMs))
        same(old, new)
        assertTrue("neighbours $newMs ms vs pairwise $oldMs ms", newMs < oldMs)
    }

    @Test fun cachedPerObjectiveAndScale() {
        val o = QdbAreas.Obj(1, 1, spots(100, 10))
        assertNull(QdbAreas.cached(o, ax, ay))
        val g = QdbAreas.groupsOf(o, ax, ay)
        assertSame(g, QdbAreas.cached(o, ax, ay))
        assertSame(g, QdbAreas.groupsOf(o, ax, ay))
        QdbAreas.groupsOf(o, ax * 2, ay)                          // the Map room's own scale: both kept
        assertSame(g, QdbAreas.cached(o, ax, ay))
        QdbAreas.groupsOf(o, ax * 3, ay)                          // a third one: the oldest goes
        assertNull(QdbAreas.cached(o, ax, ay))
    }

    @Test fun aSmallQuestAtOnceABigOneOnTheAreasThread() {
        val small = QdbAreas.Obj(10, 1, spots(50, 10))
        val big = QdbAreas.Obj(20, 1, spots(QdbAreas.INLINE_MAX + 500, 50))
        val a = QdbAreas.Areas(1429, 3, listOf(small, big))
        var told = 0
        assertNotNull(QdbAreas.groupsFor(a, 10, ax, ay) { told++ })     // small: worked out where it is asked
        val ready = CountDownLatch(1)
        val caller = Thread.currentThread()
        var readyOn: Thread? = null
        assertNull("the big one is not worked out on the asker's thread", QdbAreas.groupsFor(a, 20, ax, ay) { readyOn = Thread.currentThread(); ready.countDown() })
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        assertTrue(readyOn != null && readyOn !== caller)
        val g = QdbAreas.groupsFor(a, 20, ax, ay) { told++ }
        assertNotNull(g)
        same(QdbAreas.groups(big.pts, ax, ay), g!!)
        assertEquals(0, told)
        assertTrue(QdbAreas.lastPrepareMs >= 0)
        // a document already worked out: prepare tells at once
        var at = 0; QdbAreas.prepare(a, ax, ay) { at++ }; assertEquals(1, at)
    }

    @Test fun theViewsAskTheCacheNotTheGrouping() {
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (f in listOf("MapView.kt", "WorldMapRoomView.kt")) {
            val t = java.io.File(src, f).readText()
            assertTrue(f, t.contains("QdbAreas.groupsFor("))
            assertTrue(f, !t.contains("QdbAreas.groups(it.pts"))
        }
    }
}
