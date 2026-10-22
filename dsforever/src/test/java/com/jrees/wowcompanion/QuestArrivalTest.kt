package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** test60 (the user: "the arrow is staying up ... even when I am in the quests blue zone"): inside the area = arrived, no flicker */
class QuestArrivalTest {
    // a 1000 x 1000 yd map: 0.1 map units = 100 yd
    private val ax = 1000.0; private val ay = 1000.0
    private val square = QuestArrival.Shape.Poly(floatArrayOf(0.4f, 0.6f, 0.6f, 0.4f), floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f))
    private val circle = QuestArrival.Shape.Circle(0.2, 0.2, 50.0)

    @Test fun insideIsNegativeOutsidePositiveInYards() {
        assertEquals(-100.0, QuestArrival.outside(square, 0.5, 0.5, ax, ay), 0.01)     // the middle: 100 yd in from each side
        assertEquals(30.0, QuestArrival.outside(square, 0.63, 0.5, ax, ay), 0.01)       // 30 yd east of it
        assertEquals(-50.0, QuestArrival.outside(circle, 0.2, 0.2, ax, ay), 1e-6)
        assertEquals(10.0, QuestArrival.outside(circle, 0.26, 0.2, ax, ay), 1e-6)
        assertEquals(-50.0, QuestArrival.outside(listOf(square, circle), 0.2, 0.2, ax, ay), 0.01)   // the nearest shape counts
    }

    @Test fun insideTheAreaIsArrivedOutsideItIsNot() {
        val g = QuestArrival.Gate()
        assertFalse(g.look(listOf(square), 0.3, 0.5, ax, ay))                          // 100 yd outside: the arrow
        assertTrue(g.look(listOf(square), 0.45, 0.5, ax, ay))                          // inside: no arrow
        assertTrue(QuestArrival.Gate().look(listOf(circle), 0.21, 0.2, ax, ay))        // a point's circle the same
        assertFalse(QuestArrival.Gate().look(emptyList(), 0.5, 0.5, ax, ay))           // no area: never arrived
    }

    @Test fun theEdgeDoesNotFlicker() {
        val g = QuestArrival.Gate()
        assertTrue(g.look(listOf(square), 0.599, 0.5, ax, ay))                         // 1 yd inside the east side
        // walking along the edge, a few yards out and back: still arrived
        for (x in listOf(0.602, 0.598, 0.605, 0.601, 0.6055)) assertTrue("at $x", g.look(listOf(square), x, 0.5, ax, ay))
        assertFalse(g.look(listOf(square), 0.607, 0.5, ax, ay))                        // 7 yd out: left
        // and in again only once inside the shape (not at 3 yd out)
        assertFalse(g.look(listOf(square), 0.603, 0.5, ax, ay))
        assertTrue(g.look(listOf(square), 0.5995, 0.5, ax, ay))
    }
}
