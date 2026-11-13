package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEVERAL SPELL RADIALS (the user's picks 2026-11-08; Radial.lua q2): the even layout ("Flat top": 2 = left / right, 4 = the diagonals,
 * odd = first at the top), the stick's aim on it, the q2 document, Flow on an even wheel, and the bar commands' radial slots.
 */
class SpellRadialsTest {
    private fun near(a: Double, b: Double) = assertEquals(b, ((a % 360.0) + 360.0) % 360.0, 1e-9)

    @Test fun twoAreLeftAndRight() { near(RadialModel.evenCenter(0, 2), 270.0); near(RadialModel.evenCenter(1, 2), 90.0) }
    @Test fun fourAreTheDiagonals() { listOf(315.0, 45.0, 135.0, 225.0).forEachIndexed { i, d -> near(RadialModel.evenCenter(i, 4), d) } }
    @Test fun threeHaveTheFirstOnTop() { listOf(0.0, 120.0, 240.0).forEachIndexed { i, d -> near(RadialModel.evenCenter(i, 3), d) } }
    @Test fun eightAreFlatTop() { near(RadialModel.evenCenter(0, 8), 337.5); near(RadialModel.evenCenter(1, 8), 22.5) }
    @Test fun oneIsOnTop() = near(RadialModel.evenCenter(0, 1), 0.0)

    @Test fun theStickAimsAtTheNearestPlace() {
        // two: left half = 0, right half = 1 (x right +, y up +)
        assertEquals(0, RadialModel.aimEven(-1.0, 0.0, -1, 2))
        assertEquals(1, RadialModel.aimEven(1.0, 0.0, -1, 2))
        assertEquals(1, RadialModel.aimEven(0.2, 0.9, -1, 2))            // a little right of up: the right one
        assertEquals(-1, RadialModel.aimEven(0.1, 0.1, -1, 2))           // inside the dead zone
        // three: up, lower right, lower left
        assertEquals(0, RadialModel.aimEven(0.0, 1.0, -1, 3))
        assertEquals(1, RadialModel.aimEven(0.8, -0.5, -1, 3))
        assertEquals(2, RadialModel.aimEven(-0.8, -0.5, -1, 3))
        // one: anywhere
        assertEquals(0, RadialModel.aimEven(0.0, -1.0, -1, 1))
    }

    @Test fun anAimHoldsPastTheEdgeByTheHysteresis() {
        // four (diagonals): 0 = up-left; straight up is the 0 / 1 edge; held on 0 a little past it, it stays 0
        val a = Math.toRadians(5.0)
        assertEquals(0, RadialModel.aimEven(kotlin.math.sin(a), kotlin.math.cos(a), 0, 4))
        assertEquals(1, RadialModel.aimEven(kotlin.math.sin(a), kotlin.math.cos(a), -1, 4))
    }

    private val q2 = JSONObject("""{"type":"sradial","v":"q2","r":{"list":[
        {"u":1,"items":[{"id":403,"col":0,"n":"Lightning Bolt","i":136048},{"id":331,"col":1,"n":"Healing Wave","i":136052}]},
        {"u":3,"items":[]}],"max":6,"per":8,"row":"sradial","cells":40,"pad":true}}""")

    @Test fun theDocumentGivesEachRadialBySlot() {
        val sr = RadialModel.parseSpellRadials(q2)!!
        assertEquals(listOf(1, 3), sr.slots)
        val one = sr.of(1)!!
        assertTrue(one.even)
        assertEquals(listOf(403, 331), one.items.map { it.id })
        assertEquals(2, RadialModel.sliceCount(one))
        assertEquals(1, RadialModel.pages(one))
        assertEquals(0, sr.of(3)!!.items.size)
        assertNull(sr.of(2))
        // parse() of a q2 document: the first radial (an older caller)
        assertEquals(listOf(403, 331), RadialModel.parse(q2)!!.items.map { it.id })
    }

    @Test fun flowOnAnEvenWheel() {
        val d = RadialModel.parseSpellRadials(q2)!!.of(1)!!
        val f = RadialModel.Flow().apply { holdOpen = true; pickA = false }
        // push right (slice 1 = Healing Wave), let go: used
        assertEquals(RadialModel.Act.None, f.vector(d, 1.0, 0.0, 1000L))
        assertEquals(RadialModel.Act.Use(1, 331), f.vector(d, 0.0, 0.0, 1200L))
        // the D-pad steps round the two places only
        val g = RadialModel.Flow()
        g.step(+1, RadialModel.sliceCount(d)); g.step(+1, RadialModel.sliceCount(d)); g.step(+1, RadialModel.sliceCount(d))
        assertEquals(0, g.pointed)
        // a finger past the places is nothing
        assertEquals(RadialModel.Act.None, RadialModel.Flow().touch(d, 5))
    }

    @Test fun barCommandsKnowTheirRadial() {
        assertEquals(1, RadialMeta.slotOf("DSSRADIAL"))
        assertEquals(4, RadialMeta.slotOf("DSSRADIAL4"))
        assertNull(RadialMeta.slotOf("DSSRADIAL7"))
        assertNull(RadialMeta.slotOf("DSRADIAL"))
        assertNull(RadialMeta.slotOf(null))
    }
}
