package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bags-onerow1 (the user, 2026-11-13: "Bags share one row"): [BagsMoveRoute], the cells of a tap on a bag slot. The shared row "bmv"
 * always goes with its bag's name tap (bagsort 10 + bag) in front, in one batch; an older add-on's per-bag row is still reached; a pad
 * that cannot reach the bag taps nothing (never a guess).
 */
class BagsMoveRouteTest {
    private fun pad(rows: Map<String, Int>, sizes: Map<String, Int>) =
        JSONObject().put("rows", JSONObject(rows)).put("sizes", JSONObject(sizes))

    private val shared = pad(mapOf("bagsort" to 6, "bmvq" to 7, "bmv" to 55), mapOf("bagsort" to 40, "bmvq" to 40, "bmv" to 40))

    @Test fun sharedRowIsNamedFirst() {
        assertEquals(listOf("bagsort" to 10, "bmv" to 0), BagsMoveRoute.cells(shared, 0, 1))
        assertEquals(listOf("bagsort" to 15, "bmv" to 35), BagsMoveRoute.cells(shared, 5, 36))
    }

    @Test fun aDragIsBothNamedTaps() {
        assertEquals(listOf("bagsort" to 10, "bmv" to 2, "bagsort" to 12, "bmv" to 0), BagsMoveRoute.drag(shared, 0, 3, 2, 1))
        // within one bag: the same name cell twice, so the batch is spaced past TapPad's 0.25 s repeat guard (two taps apart)
        assertEquals(listOf("bagsort" to 11, "bmv" to 0, "bagsort" to 11, "bmv" to 4), BagsMoveRoute.drag(shared, 1, 1, 1, 5))
        assertTrue(2 * BagsMoveRoute.GAP_MS > 250)
    }

    @Test fun anOldAddOnKeepsItsPerBagRows() {
        val old = pad(mapOf("bagsort" to 6, "bmv0" to 51, "bmv3" to 54), mapOf("bagsort" to 1, "bmv0" to 40, "bmv3" to 40))
        assertEquals(listOf("bmv3" to 4), BagsMoveRoute.cells(old, 3, 5))
        assertNull(BagsMoveRoute.cells(old, 5, 1))          // bmv5 had no row: nothing
        assertFalse(BagsMoveRoute.reaches(old, 5))
    }

    @Test fun nothingWithoutTheNamingColumn() {
        // a one-cell bagsort (an older add-on) beside a row called bmv: the name column is not there, so nothing is tapped
        val noName = pad(mapOf("bagsort" to 6, "bmv" to 55), mapOf("bagsort" to 1, "bmv" to 40))
        assertNull(BagsMoveRoute.cells(noName, 0, 1))
        val noRow = pad(mapOf("bagsort" to 6), mapOf("bagsort" to 40))           // the smallest UI scale: bmv dropped
        assertNull(BagsMoveRoute.cells(noRow, 0, 1))
        assertFalse(BagsMoveRoute.reaches(noRow, 0))
        assertNull(BagsMoveRoute.cells(null, 0, 1))
    }

    @Test fun badSlotsAndBags() {
        assertNull(BagsMoveRoute.cells(shared, 0, 0))
        assertNull(BagsMoveRoute.cells(shared, -1, 1))
        assertNull(BagsMoveRoute.cells(shared, 30, 1))      // past the row's 40 columns
        assertNull(BagsMoveRoute.drag(shared, 0, 1, 30, 1))
        assertTrue(BagsMoveRoute.reaches(shared, 4))
    }
}
