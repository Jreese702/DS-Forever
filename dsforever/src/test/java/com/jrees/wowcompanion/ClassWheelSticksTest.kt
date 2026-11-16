package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * f-class-wheel-sticks (the user 2026-11-16: "one other thing to note on this would be its awkward only working from the right stick. I
 * think that either stick should work those wheels."; pick: "Both sticks aim, no walking"): either stick aims the class wheels; a stick
 * held from before the open waits for the middle; a hand-over casts nothing; the free waits for both sticks. [Wheel] is CastView's glue
 * (classStick / noteClassOpen) over the pure parts, on a four-place wheel (0 up, 1 right, 2 down, 3 left)
 */
class ClassWheelSticksTest {
    private val LEFT = 0; private val RIGHT = 1

    /** CastView's classStick / noteClassOpen, the radial being four even places; [casts] = the places Tap + Release cast */
    private class Wheel(val tap: Boolean) {
        val aim = ClassAim(CastView.CLASS_NEUTRAL)
        val flow = ClassChord.Flow()
        var lastPointed = -1
        val casts = mutableListOf<Int>()
        fun pointed(): Int {
            if (flow.side == ClassChord.NONE) return -1
            val deg = ClassChord.stickDeg(aim.aimX, aim.aimY) ?: return -1
            return ClassChord.nearest(deg, 4)
        }
        fun act(a: ClassChord.Flow.Act) { if (a is ClassChord.Flow.Act.Pick) casts += a.place }
        fun open() {
            flow.chord(ClassChord.RIGHT, false, { tap }, { false }, pointed())
            aim.open(); lastPointed = -1
            if (tap) flow.chord(ClassChord.NONE, false, { tap }, { false }, pointed())   // Tap: the chord let go, the wheel stays
        }
        fun release() = act(flow.chord(ClassChord.NONE, false, { tap }, { false }, pointed()))
        fun stick(st: Int, x: Float, y: Float) {
            val ended = aim.move(st, x, y)
            if (flow.side == ClassChord.NONE) return
            val p = pointed()
            if (p >= 0) { lastPointed = p; return }
            if (!ended) return
            val was = lastPointed; lastPointed = -1
            if (was >= 0) act(flow.stickBack({ false }, was))
        }
    }

    // ---------------------------------------------------------------- the aim: either stick, the one moved last

    @Test fun theLeftStickAimsAsTheRightDoes() {
        val w = Wheel(tap = true); w.open()
        w.stick(LEFT, 1f, 0f)
        assertEquals(LEFT, w.aim.aim); assertEquals(1, w.pointed())
        val v = Wheel(tap = true); v.open()
        v.stick(RIGHT, 0f, 1f)
        assertEquals(RIGHT, v.aim.aim); assertEquals(2, v.pointed())
    }

    @Test fun theStickMovedLastAims() {
        val w = Wheel(tap = true); w.open()
        w.stick(LEFT, 1f, 0f)                       // the left points right
        w.stick(RIGHT, 0f, 0.9f)                    // then the right points down: it takes the aim
        assertEquals(RIGHT, w.aim.aim); assertEquals(2, w.pointed())
        w.stick(LEFT, 0.95f, 0.1f)                  // the left wobbling, still out: the right keeps the aim
        assertEquals(RIGHT, w.aim.aim)
    }

    // ---------------------------------------------------------------- Tap + Release with each stick

    @Test fun tapReleaseCastsWithTheRightStick() {
        val w = Wheel(tap = true); w.open()
        w.stick(RIGHT, 0f, -1f); w.stick(RIGHT, 0f, 0f)
        assertEquals(listOf(0), w.casts); assertEquals(ClassChord.NONE, w.flow.side)
    }

    @Test fun tapReleaseCastsWithTheLeftStick() {
        val w = Wheel(tap = true); w.open()
        w.stick(LEFT, -1f, 0f); w.stick(LEFT, 0f, 0f)
        assertEquals(listOf(3), w.casts); assertEquals(ClassChord.NONE, w.flow.side)
    }

    @Test fun holdReleaseCastsWhatTheLeftStickPoints() {
        val w = Wheel(tap = false); w.open()
        w.stick(LEFT, 0f, 1f)
        w.release()
        assertEquals(listOf(2), w.casts)
    }

    // ---------------------------------------------------------------- the hand-over: no cast

    @Test fun handingTheAimOverCastsNothingThenTheLastStickBackCasts() {
        val w = Wheel(tap = true); w.open()
        w.stick(RIGHT, 1f, 0f)                      // right aims place 1
        w.stick(LEFT, 0f, 1f)                       // the left takes over: place 2
        w.stick(RIGHT, 0f, 0f)                      // the right back: a hand-over, no cast
        assertTrue(w.casts.isEmpty()); assertEquals(LEFT, w.aim.aim); assertEquals(ClassChord.RIGHT, w.flow.side)
        w.stick(LEFT, 0f, 0f)                       // the left back too, nothing else out: the cast, the left's place
        assertEquals(listOf(2), w.casts)
    }

    @Test fun theOlderStickBackFirstLeavesTheAimAndCastsNothing() {
        val w = Wheel(tap = true); w.open()
        w.stick(LEFT, -1f, 0f)                      // left aims 3
        w.stick(RIGHT, 0f, -1f)                     // right takes it: 0
        w.stick(LEFT, 0f, 0f)                       // the left (not aiming) back: nothing
        assertTrue(w.casts.isEmpty()); assertEquals(RIGHT, w.aim.aim)
        w.stick(RIGHT, 0f, 0f)
        assertEquals(listOf(0), w.casts)
    }

    @Test fun theAimHandedBackToTheStickStillOutFollowsIt() {
        val w = Wheel(tap = true); w.open()
        w.stick(LEFT, 1f, 0f)                       // left 1
        w.stick(RIGHT, 0f, 1f)                      // right 2
        w.stick(RIGHT, 0f, 0f)                      // right back: the left (still out) aims again, place 1, no cast
        assertTrue(w.casts.isEmpty()); assertEquals(LEFT, w.aim.aim); assertEquals(1, w.pointed())
        w.stick(LEFT, 0f, 0f)
        assertEquals(listOf(1), w.casts)
    }

    // ---------------------------------------------------------------- SAFETY: a stick out at the open

    @Test fun aStickHeldFromBeforeTheOpenNeitherAimsNorCasts() {
        val w = Wheel(tap = true)
        w.stick(LEFT, 0f, -1f)                      // walking forward
        w.open()
        assertEquals(-1, w.aim.aim); assertEquals(-1, w.pointed())
        w.stick(LEFT, 0.1f, -0.98f)                 // still held, moving: nothing
        assertEquals(-1, w.pointed())
        w.stick(LEFT, 0f, 0f)                       // back in the middle: no cast (it never aimed)
        assertTrue(w.casts.isEmpty()); assertEquals(ClassChord.RIGHT, w.flow.side)
        w.stick(LEFT, 1f, 0f)                       // armed now: it aims
        assertEquals(1, w.pointed())
        w.stick(LEFT, 0f, 0f)
        assertEquals(listOf(1), w.casts)
    }

    @Test fun holdReleaseWithTheCameraStickStillHeldCastsNothing() {
        val w = Wheel(tap = false)
        w.stick(RIGHT, 1f, 0f)                      // turning the camera
        w.open()
        w.release()                                 // the chord let go with no aim after the open
        assertTrue(w.casts.isEmpty())
    }

    @Test fun aHeldWalkStickDoesNotHoldBackTheOtherSticksCast() {
        val w = Wheel(tap = true)
        w.stick(LEFT, 0f, -1f)                      // walking, held through the open
        w.open()
        w.stick(RIGHT, 0f, 1f); w.stick(RIGHT, 0f, 0f)
        assertEquals(listOf(2), w.casts)
    }

    @Test fun aStickHeldFromBeforeTheOpenNeverTakesTheAim() {
        val w = Wheel(tap = true)
        w.stick(RIGHT, 0f, 1f)
        w.open()
        w.stick(LEFT, -1f, 0f)                      // aims 3
        w.stick(RIGHT, 1f, 0f)                      // the old right stick moved, never centred: not the aim
        assertEquals(LEFT, w.aim.aim); assertEquals(3, w.pointed())
    }

    @Test fun aPointBeforeTheOpenIsForgotten() {
        val w = Wheel(tap = true); w.open()
        w.stick(RIGHT, 1f, 0f)
        w.flow.b()                                  // B closed it while pointing
        w.stick(RIGHT, 0f, 0f)
        w.open()                                    // a new open: nothing pending
        assertEquals(-1, w.lastPointed)
        w.stick(LEFT, 0.1f, 0f)                     // a twitch inside the middle
        assertTrue(w.casts.isEmpty())
    }

    // ---------------------------------------------------------------- the free: both sticks back

    @Test fun theFreeWaitsForBothSticks() {
        val a = ClassAim(CastView.CLASS_NEUTRAL)
        assertTrue(a.allBack())
        a.move(LEFT, 0f, -1f)
        assertFalse(a.allBack())
        a.move(RIGHT, 1f, 0f); a.move(LEFT, 0f, 0f)
        assertFalse(a.allBack())                    // the right still out
        a.move(RIGHT, 0.2f, 0f)                     // inside the middle
        assertTrue(a.allBack())
    }

    @Test fun aStickNotArmedStillHoldsTheFree() {
        val a = ClassAim(CastView.CLASS_NEUTRAL)
        a.move(LEFT, 0f, -1f); a.open()             // held through the open: it never aims ...
        assertEquals(-1, a.aim)
        assertFalse(a.allBack())                    // ... yet the closed wheel's free waits for it (or CLASS_FREE_MS), as for the right
        a.move(LEFT, 0f, 0f)
        assertTrue(a.allBack())
    }

    @Test fun otherStickNumbersAreIgnored() {
        val a = ClassAim(CastView.CLASS_NEUTRAL)
        assertFalse(a.move(2, 1f, 1f)); assertTrue(a.allBack()); assertEquals(-1, a.aim)
    }
}
