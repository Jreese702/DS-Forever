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
