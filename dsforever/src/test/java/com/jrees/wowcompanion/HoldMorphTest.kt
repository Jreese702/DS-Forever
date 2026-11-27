package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * feat/hold-morph (the user, 2026-11-27: "I want there to be a slick transition on a button hold, I want the button hold to transition that
 * small circle into the big one and then back after the cast is complete or canceled"): THE MORPH's timing and state, off-device.
 * (1) [HoldMorph]: 0 -> 1 over [HoldMorph.IN_MS] (an ease-out: monotonic, quick off the mark), back over [HoldMorph.OUT_MS]; a turn
 * mid-way starts from the value shown (no jump); (2) the takeover's morph: in from its start, back from its end - the cast and GCD over,
 * a fail / cancel, a "none" - landing at 0 exactly when the takeover lets go; a late word that moves the end on turns it back in
 * smoothly; a new press on the same button mid-way back retargets from where it is; a press on ANOTHER button sends the old one home as a
 * ghost (and a ghost pressed again is handed back from its own value); (3) it never touches the fire: the cast-at-the-fill timing is the
 * launcher's (HoldRelease) and nothing in it reads the morph; (4) the drawing's wiring pins.
 */
class HoldMorphTest {
    private val root = listOf(File("../companion"), File("companion")).first { it.isDirectory }.parentFile!!.absoluteFile
    private fun kt(name: String) = File(root, "companion/app/src/main/java/com/jrees/wowcompanion/$name").readText()

    /** the time on the way in (from 0) at which the morph shows [v] */
    private fun inAt(v: Float): Long = Math.round(HoldMorph.IN_MS * (1.0 - Math.cbrt(1.0 - v)))
    /** ... on the way back (from 1) */
    private fun outAt(v: Float): Long = Math.round(HoldMorph.OUT_MS * (1.0 - Math.cbrt(v.toDouble())))

    // ---------------------------------------------------------------- (1) the clock

    @Test fun theTimingsAreInTheBriefsWindow() {
        assertTrue("in: 160-220 ms", HoldMorph.IN_MS in 160L..220L)
        assertTrue("back: 160-220 ms", HoldMorph.OUT_MS in 160L..220L)
        assertEquals("back lands as the takeover lets go (its own ease)", HoldCast.EASE_MS, HoldMorph.OUT_MS)
        assertEquals(0f, HoldMorph.ease(0f), 0f); assertEquals(1f, HoldMorph.ease(1f), 0f)
        assertTrue("an ease-out: past half way at a third of the time", HoldMorph.ease(1f / 3f) > 0.66f)
    }

    @Test fun inThenBackIsSmoothAndMonotonic() {
        val m = HoldMorph()
        assertEquals("at rest: the badge", 0f, m.value(0), 0f)
        m.toward(true, 1000)
        assertEquals("no jump at the start", 0f, m.value(1000), 0f)
        var last = -1f
        for (t in 1000L..1000L + HoldMorph.IN_MS) { val v = m.value(t); assertTrue("monotonic in ($t)", v >= last); last = v }
        assertEquals("in at IN_MS", 1f, m.value(1000 + HoldMorph.IN_MS), 0f)
        assertFalse(m.moving(1000 + HoldMorph.IN_MS))
        for (v in listOf(0.25f, 0.5f, 0.75f)) assertEquals("$v at its time", v, m.value(1000 + inAt(v)), 0.01f)
        // no frame-to-frame jump bigger than the ease's own first frame (16 ms at 60 fps)
        var maxStep = 0f; var prev = 0f
        for (t in 1000L..1000L + HoldMorph.IN_MS step 16) { val v = m.value(t); maxStep = maxOf(maxStep, v - prev); prev = v }
        assertTrue("no jump: the biggest 16 ms step ${maxStep}", maxStep < 0.3f)
        m.toward(false, 2000)
        assertEquals("no jump at the turn", 1f, m.value(2000), 0f)
        last = 2f
        for (t in 2000L..2000L + HoldMorph.OUT_MS) { val v = m.value(t); assertTrue("monotonic back ($t)", v <= last); last = v }
        assertEquals("home at OUT_MS", 0f, m.value(2000 + HoldMorph.OUT_MS), 0f)
        for (v in listOf(0.75f, 0.5f, 0.25f)) assertEquals("back: $v at its time", v, m.value(2000 + outAt(v)), 0.01f)
        assertEquals("at rest again", 0f, m.value(5000), 0f)
    }

    @Test fun aTurnMidWayStartsFromTheValueShown() {
        val m = HoldMorph()
        m.toward(true, 0)
        val mid = m.value(60)
        assertTrue(mid > 0.3f && mid < 1f)
        m.toward(false, 60)                                         // cancelled mid-way in
        assertEquals("no jump at the turn", mid, m.value(60), 1e-6f)
        assertTrue("then back down", m.value(80) < mid)
        assertEquals("home within the part of the way left", 0f, m.value(60 + (HoldMorph.OUT_MS * mid).toLong() + 1), 0f)
        m.toward(true, 300)                                         // pressed again once home
        assertEquals("from 0 (home already)", 0f, m.value(300), 1e-6f)
        val m2 = HoldMorph()
        m2.toward(true, 0); m2.toward(false, HoldMorph.IN_MS)
        val back = m2.value(HoldMorph.IN_MS + 50)
        m2.toward(true, HoldMorph.IN_MS + 50)                       // a new press mid-way back: retarget in
        assertEquals("no jump", back, m2.value(HoldMorph.IN_MS + 50), 1e-6f)
        assertTrue("up again", m2.value(HoldMorph.IN_MS + 70) > back)
        assertEquals("in within the part of the way left", 1f, m2.value(HoldMorph.IN_MS + 50 + (HoldMorph.IN_MS * (1f - back)).toLong() + 1), 0f)
        m2.toward(true, 9999)
        assertEquals("already heading in: nothing changes", 1f, m2.value(9999), 0f)
    }

    @Test fun theLooksShareOfTheMovingCircle() {
        assertEquals("all badge at the start", 0f, HoldMorph.faceShare(0f), 0f)
        assertEquals("all checked face at the end", 1f, HoldMorph.faceShare(1f), 0f)
        assertTrue(HoldMorph.faceShare(0.4f) in 0.2f..0.8f)
        assertEquals(1f, HoldMorph.tapScale(0f), 0f); assertEquals(1f - HoldMorph.TAP_SHRINK, HoldMorph.tapScale(1f), 1e-6f)
        assertEquals(1f, HoldMorph.tapAlpha(0f), 0f); assertEquals(0f, HoldMorph.tapAlpha(1f), 0f)
    }

    // ---------------------------------------------------------------- (2) the takeover's morph

    @Test fun theTakeoverMorphsInAndBackAfterTheCastAndGcd() {
        val t = HoldCast.Takeover()
        assertEquals(0f, t.morph(0), 0f)
        t.start(8, 0, 1000)
        assertEquals("the fill: the badge still in its corner", 0f, t.morph(1000), 0f)
        assertTrue(t.morphing(1000))
        assertEquals("in", 1f, t.morph(1000 + HoldMorph.IN_MS), 0f)
        t.cast(1800, 1050); t.gcd(1050, 1500)
        assertEquals("mid cast: the big face", 1f, t.morph(2500), 0f)
        assertFalse(t.morphing(2500))
        assertEquals("the end: no jump", 1f, t.morph(2850), 0f)
        assertEquals("back half way", 0.5f, t.morph(2850 + outAt(0.5f)), 0.01f)
        assertEquals("home as the takeover lets go", 0f, t.morph(2850 + HoldCast.EASE_MS), 0f)
        assertEquals(0f, t.alpha(2850 + HoldCast.EASE_MS), 0f)
        assertEquals(-1, t.pos)
        assertEquals("cleared: nothing", 0f, t.morph(9000), 0f)
    }

    @Test fun aFailMidMorphTurnsBackFromWhereItIs() {
        val t = HoldCast.Takeover()
        t.start(8, 0, 1000)
        val v = t.morph(1060)
        assertTrue(v > 0.3f && v < 1f)
        t.fail(1060)                                                // refused at once (a cancel / an interrupt)
        assertEquals("no jump at the fail", v, t.morph(1060), 1e-6f)
        assertTrue("going back", t.morph(1080) < v)
        assertEquals("home within the ease", 0f, t.morph(1060 + HoldCast.EASE_MS), 0f)
        // "none" (an instant with no GCD) and cancel too
        t.start(8, 0, 5000); t.morph(5000 + HoldMorph.IN_MS)
        t.none(5300)
        assertEquals(1f, t.morph(5300), 0f)
        assertTrue(t.morph(5350) < 1f)
        assertEquals(0f, t.morph(5300 + HoldCast.EASE_MS), 0f)
        t.start(8, 0, 8000); t.morph(8100); t.cancel(8400)
        assertEquals(1f, t.morph(8400), 0f)
        assertEquals(0f, t.morph(8400 + HoldCast.EASE_MS), 0f)
    }

    @Test fun aLateWordTurnsItBackInSmoothly() {
        val t = HoldCast.Takeover()
        t.start(8, 0, 1000)
        t.morph(1000 + HoldMorph.IN_MS)
        // no word at all: the 2 s fallback ends it; then a late cast word moves the end on
        val e = 1000 + HoldCast.FALLBACK_MS
        val back = t.morph(e + 60)
        assertTrue(back < 1f && back > 0f)
        t.cast(1500, e + 60)
        assertEquals("no jump", back, t.morph(e + 60), 1e-6f)
        assertTrue("in again", t.morph(e + 80) > back)
        assertEquals(1f, t.morph(e + 60 + HoldMorph.IN_MS), 0f)
    }

    @Test fun aNewPressOnTheSameButtonRetargets() {
        val t = HoldCast.Takeover()
        t.start(8, 0, 1000); t.morph(1000 + HoldMorph.IN_MS); t.ok(1300)
        val back = t.morph(1600 + 50)                               // ok + 0.3 s = 1600: half way back
        assertTrue(back in 0.05f..0.95f)
        t.start(8, 0, 1650)                                         // pressed again (its fill) mid-way back
        assertEquals("no jump", back, t.morph(1650), 1e-6f)
        assertTrue("in again", t.morph(1670) > back)
        // a second fire on a takeover in full: stays in full (no flicker)
        t.start(8, 0, 1650 + HoldMorph.IN_MS); t.morph(1650 + HoldMorph.IN_MS)
        t.start(8, 0, 1700 + HoldMorph.IN_MS)
        assertEquals("no flicker: still the big face", 1f, t.morph(1700 + HoldMorph.IN_MS), 0f)
    }

    @Test fun anotherButtonSendsTheOldOneHomeAsAGhost() {
        val t = HoldCast.Takeover()
        t.start(8, 0, 1000); t.morph(1000 + HoldMorph.IN_MS)
        t.start(6, 0, 1500)                                         // Y's hold fires while A's still has its face
        assertEquals("A goes home from the big face: no jump", 1f, t.morphAt(8, 0, 1500), 0f)
        assertTrue("A is the ghost now", t.ghostAt(8, 0) && t.ghostPos == 8)
        assertEquals("Y starts at the badge", 0f, t.morphAt(6, 0, 1500), 0f)
        assertTrue(t.morphing(1500))
        assertTrue("A on its way home", t.morphAt(8, 0, 1560) < 1f)
        assertEquals("A home", 0f, t.morphAt(8, 0, 1500 + HoldMorph.OUT_MS), 0f)
        assertEquals("forgotten", -1, t.ghostPos)
        // A pressed again while its ghost is mid-way: handed back from its own value
        t.start(8, 0, 3000); t.morph(3000 + HoldMorph.IN_MS)
        t.start(6, 0, 3500)
        val g = t.morphAt(8, 0, 3560)
        t.start(8, 0, 3560)
        assertEquals("no jump: the ghost's value carries on", g, t.morphAt(8, 0, 3560), 1e-6f)
        assertFalse(t.ghostAt(8, 0))
        assertTrue("Y (the old current) goes home as the new ghost", t.ghostPos == 6)
        assertTrue("ruling A for the ghost: an LT + Y hold's ghost on the bare layer", HoldCast.Takeover().let { x ->
            x.start(6, 1, 0); x.morph(HoldMorph.IN_MS); x.start(8, 0, 500); x.ghostShowsOn(0) && x.ghostShowsOn(1) && !x.ghostShowsOn(2) })
        t.clear()
        assertEquals("cleared: no ghost", -1, t.ghostPos)
        assertEquals(0f, t.morphAt(8, 0, 4000), 0f)
    }

    @Test fun theBandIsAskedForFramesWhileItMoves() {
        val t = HoldCast.Takeover()
        assertEquals(Long.MAX_VALUE, t.morphNextMs(0))
        t.start(8, 0, 1000)
        assertEquals("moving: every frame", 16L, t.morphNextMs(1010))
        t.cast(1800, 1050)
        assertTrue("held: a look by the end", t.morphNextMs(2000) in 1L..33L)
        assertEquals("right at the end: the next frame", 1L, t.morphNextMs(2849))
    }

    // ---------------------------------------------------------------- (3) it never touches the fire

    @Test fun theMorphNeverTouchesTheFire() {
        val rel = kt("HoldRelease.kt")
        assertFalse("the launcher's fire reads nothing of the morph", rel.contains("morph", ignoreCase = true))
        val hc = kt("HoldCast.kt")
        val fireNow = hc.substringAfter("fun fireNow(").substringBefore("\n    }\n")
        assertFalse(fireNow.contains("morph", ignoreCase = true))
        val padIn = hc.substringAfter("fun padIn(").substringBefore("\n    }\n")
        assertFalse("the pad's edges: no morph", padIn.contains("morph", ignoreCase = true))
        // 1537's cast at the fill, as it is
        assertEquals("F = 3 x (200 + 17) + 17 + 10", 678L, HoldRelease.fireMs(3, 17))
    }

    // ---------------------------------------------------------------- (4) the drawing's wiring

    @Test fun theDrawingRidesTheMovingCircle() {
        val cast = kt("CastView.kt")
        val hold = cast.substringAfter("private fun drawHold(").substringBefore("\n    }\n")
        assertTrue("the badge is the moving circle: from its corner into the middle, growing to the face's size",
            hold.contains("val bx = cbx * (1f - tm)") && hold.contains("val by = cby * (1f - tm)") && hold.contains("val r = cr + (iconHalf - cr) * tm"))
        assertTrue("its charge ring (gold, glowing) follows it, inside its fade",
            hold.indexOf("HoldArt.chargeRing(c, bx, by, r, ch, charged)") in hold.indexOf("HoldArt.badge(c, bx, by, r") until hold.indexOf("if (bs >= 0) c.restoreToCount(bs)"))
        val take = cast.substringAfter("private fun drawTakeover(").substringBefore("\n    }\n")
        assertTrue("the checked face rides the same circle", take.contains("c.scale(ms, ms)") && take.contains("HoldMorph.faceShare(m)")
            && take.contains("t.ghostAt(src + 1, li)"))
        assertTrue("the tap shrinks and fades", cast.contains("val tm = tapMorph(src, li, now)") && cast.contains("HoldMorph.tapScale(tm)")
            && cast.contains("HoldMorph.tapAlpha(tm)"))
    }
}
