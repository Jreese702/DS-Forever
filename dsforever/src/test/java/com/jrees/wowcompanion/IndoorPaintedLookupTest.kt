package com.jrees.wowcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review of 2026-11-23 (engineering): the Home map's painted-city lookup read worldmap/<id>/index.json on the screen-drawing thread, and
 * a missing city's map was read again about every 10 s, every frame after that. [IndoorCityArt.PaintedIndex]: the read runs on the
 * plans' thread, its answer lands on the drawing thread; a miss is remembered and asked again only after [IndoorCityArt.PaintedIndex.MISS_MS].
 */
class IndoorPaintedLookupTest {
    private class Rig {
        val work = ArrayList<Runnable>()
        val posted = ArrayList<Runnable>()
        var t = 0L
        var onDisk = HashMap<Int, DoubleArray>()
        val reads = ArrayList<Pair<Int, String>>()
        var drawing = true
        var arrivals = 0
        val index = IndoorCityArt.PaintedIndex(
            read = { id -> reads.add(id to if (drawing) "draw" else "worker"); onDisk[id]?.let { IndoorCityArt.PaintedIndex.Painted(id, 0, it) } },
            background = { work.add(it) }, post = { posted.add(it) }, now = { t }, onArrive = { arrivals++ })
        /** the plans' thread runs what was queued, then the drawing thread gets the answers */
        fun settle() {
            drawing = false
            while (work.isNotEmpty()) work.removeAt(0).run()
            drawing = true
            while (posted.isNotEmpty()) posted.removeAt(0).run()
        }
    }

    @Test fun theLookupNeverReadsOnTheDrawingThread() {
        val r = Rig()
        r.onDisk[1455] = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertNull("not read yet: nothing this frame", r.index.get(1455))
        assertNull(r.index.get(1455))
        assertEquals("asked once, not per frame", 1, r.work.size)
        assertTrue(r.reads.isEmpty())
        r.settle()
        assertEquals(listOf(1455 to "worker"), r.reads)
        assertEquals(1, r.arrivals)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), r.index.get(1455)!!.rect, 0.0)
        repeat(100) { r.index.get(1455) }
        assertEquals("a hit is never read again", 1, r.reads.size)
    }

    @Test fun aMissIsRememberedAndAskedAgainOnlyLater() {
        val r = Rig()
        r.index.get(1537)
        r.settle()
        assertEquals(1, r.reads.size)
        // frames for the next minute (it was every ~10 s, on the drawing thread)
        for (s in 0 until 60) { r.t += 1_000L; assertNull(r.index.get(1537)); r.settle() }
        assertTrue("at most once more in a minute: ${r.reads}", r.reads.size <= 2)
        assertTrue(r.reads.all { it.second == "worker" })
        // the map arrives (the Map room's art built meanwhile): found at the next ask
        r.onDisk[1537] = doubleArrayOf(5.0, 6.0, 7.0, 8.0)
        r.t += IndoorCityArt.PaintedIndex.MISS_MS
        r.index.get(1537); r.settle()
        assertArrayEquals(doubleArrayOf(5.0, 6.0, 7.0, 8.0), r.index.get(1537)!!.rect, 0.0)
        assertTrue(IndoorCityArt.PaintedIndex.MISS_MS >= 30_000L)
    }
}
