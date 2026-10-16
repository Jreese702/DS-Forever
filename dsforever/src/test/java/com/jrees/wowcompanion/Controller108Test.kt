package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS Forever 1.0.8, the Controller session's items, off-device: p-swing-timer (batch 12's split) and p-druid-lt-lb (the chord) */
class Controller108Test {

    private val nan = Double.NaN
    private fun line(swingOn: Boolean, now: Double, castStart: Double = nan, castEnd: Double = nan, swingStart: Double = nan,
                     swingDur: Double = nan, flash: Int = 0, flashAge: Long = -1L): SwingLine.Parts =
        SwingLine.parts(swingOn, now, castStart, castEnd, swingStart, swingDur, 0.5,
            flash != 0, flash == 1, flashAge, if (flash == 1) 250L else 600L)

    // ---------------------------------------------------------------- p-swing-timer: the swing off never hides the cast bar

    @Test fun swingOffStillShowsTheCastBar() {
        val p = line(swingOn = false, now = 1_500.0, castStart = 1_000.0, castEnd = 2_500.0)
        assertTrue("a cast in progress is drawn with the swing off", p.cast)
    }

    @Test fun swingOffHidesTheSwing() {
        val p = line(swingOn = false, now = 1_500.0, swingStart = 1_000.0, swingDur = 2_000.0)
        assertFalse(p.cast)
        assertEquals("no swing fill", -1.0, p.swing, 0.0)
        assertEquals("no wake-up for a swing", Long.MAX_VALUE, p.wakeMs)
    }

    @Test fun swingOnShowsTheSwing() {
        val p = line(swingOn = true, now = 1_500.0, swingStart = 1_000.0, swingDur = 2_000.0)
        assertEquals(0.25, p.swing, 1e-9)
    }

    @Test fun swingOffKeepsTheCastFlashButNotTheSwingFlash() {
        assertTrue("a cast's finish flash stays", line(swingOn = false, now = 0.0, flash = 2, flashAge = 100L).flash)
        assertTrue("a cast's fail flash stays", line(swingOn = false, now = 0.0, flash = 3, flashAge = 100L).flash)
        assertFalse("a swing restart's flash goes", line(swingOn = false, now = 0.0, flash = 1, flashAge = 100L).flash)
        assertTrue("with the swing on it is drawn", line(swingOn = true, now = 0.0, flash = 1, flashAge = 100L).flash)
    }

    @Test fun aSwingStartingAheadWakesTheLineOnlyWhenSwingIsOn() {
        assertEquals(400L, line(swingOn = true, now = 600.0, swingStart = 1_000.0, swingDur = 2_000.0).wakeMs)
        assertEquals(Long.MAX_VALUE, line(swingOn = false, now = 600.0, swingStart = 1_000.0, swingDur = 2_000.0).wakeMs)
    }

    @Test fun aCastBeatsTheSwingWithTheSwingOn() {
        val p = line(swingOn = true, now = 1_500.0, castStart = 1_000.0, castEnd = 2_500.0, swingStart = 1_000.0, swingDur = 2_000.0)
        assertTrue(p.cast)
        assertEquals("the cast bar takes the line, as before", -1.0, p.swing, 0.0)
    }

    // ---------------------------------------------------------------- p-druid-lt-lb: LB + LT opens the left class radial (the forms)

    @Test fun lbPlusLtIsTheLeftRadial() {
        assertEquals(ClassChord.LEFT, ClassChord.side(lt = true, rt = false, lb = true, rb = false))
    }

    @Test fun rbPlusRtIsTheRightRadial() {
        assertEquals(ClassChord.RIGHT, ClassChord.side(lt = false, rt = true, lb = false, rb = true))
    }

    @Test fun oneButtonAloneOrTheCrossedPairOpensNothing() {
        assertEquals(ClassChord.NONE, ClassChord.side(lt = true, rt = false, lb = false, rb = false))   // ZL: a set
        assertEquals(ClassChord.NONE, ClassChord.side(lt = false, rt = false, lb = true, rb = false))   // LB: the game's Ctrl
        assertEquals(ClassChord.NONE, ClassChord.side(lt = true, rt = false, lb = false, rb = true))    // LT + RB: crossed
        assertEquals(ClassChord.NONE, ClassChord.side(lt = false, rt = true, lb = true, rb = false))    // RT + LB: crossed
    }

    @Test fun allFourHeldIsTheLeftRadialAsTheGameChecksLeftFirst() {
        assertEquals(ClassChord.LEFT, ClassChord.side(lt = true, rt = true, lb = true, rb = true))
    }
}

/** p-bars-grey (1.0.8, the user's yes): the bars' line reaches the problem report whole, through the privacy pass. Never uploads */
class CastDiagReportTest {

    @org.junit.Before fun noLiveReceiver() { ReportUpload.endpoint = "" }      // the live receiver is never a test's (ProblemReportTest's rule)

    @Test fun theLineIsNumbersOnly() {
        assertEquals("castview8: g=1 s=25000 o=0 m=0 b=1 d=8 u=0",
            CastDiag.format(grey = true, staleMs = 25_000L, ok = false, empty = false, bu = true, eOff = 8, uOff = 0))
    }

    @Test fun theLineIsInTheReportAndSurvivesThePrivacyPass() {
        val line = CastDiag.format(grey = false, staleMs = 1200L, ok = true, empty = false, bu = true, eOff = 0, uOff = 3)
        val facts = ProblemReport.Facts("1.0.8 (108)", "set 130", "WoW Forever 1.60", "AYN Thor, Android 13",
            listOf("Game running: true", line))
        // names the run learnt that look like the line's words (a character / realm / guild could be called that): never touched
        val names = listOf("Tomsot", "Castview", "Grey", "Stale", "Empty", "Ok", "Bu", "True")   // (WoW names never hold a digit: the tag "castview8" is safe)
        val report = ProblemReport.build(0L, "bars look grey", facts, emptyList(), emptyList(), emptyList(), false, names)
        val text = report.files.first { it.first == "report.txt" }.second
        assertTrue("the line is in Settings and state, whole:\n$text", text.contains("  $line"))
    }

    @Test fun beforeTheBarsDrawThereIsNoLine() {
        // a fresh process: nothing noted yet (the object is shared, so only when no draw ran in this JVM)
        val l = CastDiag.line()
        assertTrue(l == null || l.startsWith("castview8: g="))
    }
}

/** b-cast-bar-late (the user on 1.0.8-test2: "The cast bar starts really delayed at like 25%"): the predictive start's rule */
class CastPredictTest {
    @Test fun aCastTimeSpellStartsTheBarAtThePress() = assertTrue(CastPredict.start(ctMs = 2000, casting = false, usable = true))
    @Test fun anInstantSpellNeverStartsOne() = assertFalse(CastPredict.start(ctMs = 0, casting = false, usable = true))
    @Test fun anUnknownCastTimeNeverStartsOne() = assertFalse(CastPredict.start(ctMs = -1, casting = false, usable = true))
    @Test fun notWhileACastRuns() = assertFalse(CastPredict.start(ctMs = 2000, casting = true, usable = true))
    @Test fun notWhenUnusableOrOutOfMana() = assertFalse(CastPredict.start(ctMs = 2000, casting = false, usable = false))
    @Test fun itLivesUntilTheConfirmWindowEnds() {
        assertTrue(CastPredict.alive(pressAt = 1_000L, now = 1_000L))
        assertTrue(CastPredict.alive(pressAt = 1_000L, now = 1_000L + CastPredict.CONFIRM_MS - 1))
        assertFalse("never confirmed: dropped", CastPredict.alive(pressAt = 1_000L, now = 1_000L + CastPredict.CONFIRM_MS))
        assertFalse("no press", CastPredict.alive(pressAt = 0L, now = 5_000L))
    }
    @Test fun thePredictedBarFillsFromThePress() {
        // the line draws a predicted cast like a real one: at 500 ms into a 2 s cast it is a quarter full, and it IS a cast
        val p = SwingLine.parts(true, 1_500.0, 1_000.0, 3_000.0, Double.NaN, Double.NaN, 0.5, false, false, -1L, 600L)
        assertTrue(p.cast)
    }
}

/** f-class-radial rework (the user: "it goes in between the map and chat, animating them ourtwards"; 1.0.8-test5: "make it look like
 *  its sliding in from behind the chat"): the wheel's slot fits, and it starts hidden behind the chat column */
/** f-class-radial, 1.0.9 rework (the user: "make them appear on the right or left sides of the screen, and slide the whole chat away or
 *  back over top when releasing, or the map if its the right rigger") */
class ClassSlotTest {
    private val w = 1240f; private val h = 1000f; private val g = 8f
    private val chat = ClassSlot.Area(g, 216f, w / 2f - 4f, h - g)                  // Home's left column at rest (the diamonds' bars to 208)
    private val map = ClassSlot.Area(w / 2f + 4f, 216f, w - g, h - g)
    @Test fun theLeftChordTakesTheChatColumn() {
        val s = ClassSlot.inColumn(ClassSlot.column(listOf(chat), 208f, h, g, left = true, w = w))
        assertTrue("inside the chat column", s.left >= chat.left - 0.5f && s.left + s.side <= chat.right + 0.5f)
        assertTrue("under the bars and above the bottom", s.top >= 216f - 0.5f && s.top + s.side <= h - g + 0.5f)
        assertEquals("as wide as the column allows", chat.right - chat.left, s.side, 0.5f)
    }
    @Test fun theRightChordTakesTheMapColumn() {
        val s = ClassSlot.inColumn(ClassSlot.column(listOf(map), 208f, h, g, left = false, w = w))
        assertTrue("on the right half", s.left >= w / 2f)
        assertTrue(s.left + s.side <= map.right + 0.5f)
    }
    @Test fun theColumnSlidesWhollyOffItsOwnEdge() {
        assertTrue("the chat's right edge past the left edge", chat.right + ClassSlot.away(true, chat.left, chat.right, w, g) < 0f)
        assertTrue("the map's left edge past the right edge", map.left + ClassSlot.away(false, map.left, map.right, w, g) > w)
    }
    @Test fun aColumnWithNothingShownStillGivesTheWheelItsHalf() {
        val a = ClassSlot.column(emptyList(), 390f, h, g, left = false, w = w)
        assertTrue(a.left >= w / 2f && a.top >= 390f)
    }
    // f-radial-middle: the item / spell wheel between the columns
    @Test fun theItemWheelSitsCentredUnderTheBars() {
        val m = ClassSlot.middle(w, h, 208f, g, minOf(w * 0.5f, chat.right - chat.left))
        assertEquals("centred", w / 2f, m.left + m.side / 2f, 0.5f)
        assertTrue("under the bars, above the bottom", m.top >= 208f + g - 0.5f && m.top + m.side <= h - g + 0.5f)
        assertEquals("the columns part by half of it and a gap", m.side / 2f + g, m.push, 0.01f)
    }
    @Test fun theItemWheelFillsTheHeightUnderTheBars() {
        // test18 ("about the same size as the pause menu radial"): as tall as the space under the bars, within its width cap
        val m = ClassSlot.middle(w, h, 300f, g, w * 0.6f)
        assertEquals(h - g - (300f + g), m.side, 0.5f)
        assertTrue(ClassSlot.middle(w, 2000f, 100f, g, w * 0.6f).side <= w * 0.6f + 0.5f)
    }
}

/** b-radial-exclusive: one overlay at a time - a class radial never opens beside another, nor under any other overlay */
class ClassChordExclusiveTest {
    private val L = ClassChord.LEFT; private val R = ClassChord.RIGHT; private val N = ClassChord.NONE
    @Test fun theOtherChordNeverOpensBesideAnOpenRadial() {
        // LB + LT open, then RB + RT pressed too: the left stays, the right never shows
        assertEquals(L, ClassChord.latch(N, lt = true, rt = false, lb = true, rb = false, blocked = false))
        assertEquals(L, ClassChord.latch(L, lt = true, rt = true, lb = true, rb = true, blocked = false))
        // the right open, then the left chord too: the right stays
        assertEquals(R, ClassChord.latch(R, lt = true, rt = true, lb = true, rb = true, blocked = false))
    }
    @Test fun lettingGoClosesItAndTheOtherNeedsAFreshStart() {
        assertEquals(N, ClassChord.latch(L, lt = false, rt = true, lb = true, rb = true, blocked = false))
        assertEquals(R, ClassChord.latch(N, lt = false, rt = true, lb = false, rb = true, blocked = false))
    }
    @Test fun noClassRadialOpensUnderAnotherOverlay() {
        assertEquals(N, ClassChord.latch(N, lt = true, rt = false, lb = true, rb = false, blocked = true))
        assertEquals(N, ClassChord.latch(N, lt = false, rt = true, lb = false, rb = true, blocked = true))
    }
}

/** b-class-radial-slots: only the places the character has, evenly round the wheel; the stick picks among them */
class ClassRadialSlotsTest {
    @Test fun twoFormsSitOppositeEachOther() {
        assertEquals(0.0, ClassChord.evenAngle(0, 2), 0.001)
        assertEquals(180.0, ClassChord.evenAngle(1, 2), 0.001)
    }
    @Test fun threeStancesAreAThirdApart() {
        assertEquals(120.0, ClassChord.evenAngle(1, 3), 0.001)
        assertEquals(240.0, ClassChord.evenAngle(2, 3), 0.001)
    }
    @Test fun theStickPicksTheNearestOfTheShownSlices() {
        assertEquals(0, ClassChord.nearest(10.0, 3))
        assertEquals(1, ClassChord.nearest(100.0, 3))
        assertEquals(2, ClassChord.nearest(250.0, 3))
        assertEquals(0, ClassChord.nearest(350.0, 3))             // past the last slice: back to the first
        assertEquals(1, ClassChord.nearest(170.0, 2))
        assertEquals(-1, ClassChord.nearest(90.0, 0))
    }
    @Test fun aStickAtRestPointsAtNothing() {
        assertEquals(null, ClassChord.stickDeg(0.1f, -0.2f))
        assertEquals(90.0, ClassChord.stickDeg(1f, 0f)!!, 0.001)    // right
        assertEquals(0.0, ClassChord.stickDeg(0f, -1f)!!, 0.001)    // up
    }
}

/** b-class-takeover: our class wheel's open / pick per side (the defaults: the game's hold the chord, aim, let go) */
class ClassWheelFlowTest {
    private val L = ClassChord.LEFT; private val R = ClassChord.RIGHT; private val N = ClassChord.NONE
    private val no: (Int) -> Boolean = { false }
    private val yes: (Int) -> Boolean = { true }
    private fun open(f: ClassChord.Flow, side: Int, tap: (Int) -> Boolean = no, pickA: (Int) -> Boolean = no) =
        f.chord(side, false, tap, pickA, -1)
    @Test fun holdReleaseCastsWhatTheStickPointsAtOnLettingGo() {
        val f = ClassChord.Flow(); open(f, L)
        assertEquals(L, f.side)
        assertEquals(ClassChord.Flow.Act.Pick(2), f.chord(N, false, no, no, 2))
        assertEquals(N, f.side)
    }
    @Test fun holdReleaseWithNothingPointedJustCloses() {
        val f = ClassChord.Flow(); open(f, R)
        assertEquals(ClassChord.Flow.Act.Close, f.chord(N, false, no, no, -1))
    }
    @Test fun holdAPicksWithAAndLettingGoOnlyCloses() {
        val f = ClassChord.Flow(); open(f, L, pickA = yes)
        assertEquals(ClassChord.Flow.Act.Pick(1), f.a(yes, 1))
        assertEquals(N, f.side)
        val g = ClassChord.Flow(); open(g, L, pickA = yes)
        assertEquals(ClassChord.Flow.Act.Close, g.chord(N, false, no, yes, 3))
    }
    @Test fun tapStaysOpenAndTheStickComingBackCasts() {
        val f = ClassChord.Flow(); open(f, L, tap = yes)
        assertEquals(ClassChord.Flow.Act.None, f.chord(N, false, yes, no, 0))
        assertEquals(L, f.side); assertTrue(f.latched)
        assertEquals(ClassChord.Flow.Act.Pick(0), f.stickBack(no, 0))
        assertEquals(N, f.side)
    }
    @Test fun tapClosesWithBOrTheSameChordAgain() {
        val f = ClassChord.Flow(); open(f, R, tap = yes); f.chord(N, false, yes, no, -1)
        assertEquals(ClassChord.Flow.Act.Close, f.b())
        val g = ClassChord.Flow(); open(g, R, tap = yes); g.chord(N, false, yes, no, -1)
        assertEquals(ClassChord.Flow.Act.Close, g.chord(R, false, yes, no, -1))
        // still held after that close: it does not open again until the chord is let go
        assertEquals(N, g.chord(R, false, yes, no, -1).let { g.side })
        g.chord(N, false, yes, no, -1); g.chord(R, false, yes, no, -1)
        assertEquals(R, g.side)
    }
    @Test fun oneOverlayAtATime() {
        val f = ClassChord.Flow()
        f.chord(L, true, no, no, -1)
        assertEquals(N, f.side)                                   // under another overlay: nothing
        f.chord(N, false, no, no, -1); open(f, L)
        f.chord(R, false, no, no, -1)                            // the other chord alone: the left one let go -> closed, never the right
        assertEquals(N, f.side)
    }
}
