package com.jrees.wowcompanion

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** b-setup-report-cursor (1.0.9, the user): "Report a problem" touch only at launch, and a way to back out of the report */
class ReportBackoutTest {

    @Test fun theCursorNeverLandsOnReportAProblem() {
        val s = SetupFlow.screen(SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true))
        val report = s.report!!
        assertEquals(SetupFlow.Action.REPORT, report.action)
        assertFalse(SetupFlow.padTarget(report))                       // touch only
        assertTrue(SetupFlow.padTarget(s.play!!))                      // Play stays the cursor's
        val withPopUp = SetupFlow.screen(SetupFlow.Inputs(path = "/g", folderFound = false))
        val targets = (withPopUp.prompt!!.buttons + listOfNotNull(withPopUp.play, withPopUp.report)).filter(SetupFlow::padTarget)
        assertFalse(targets.any { it.action == SetupFlow.Action.REPORT })   // not first, not by wrapping: never in the list
    }

    @Test fun bAndBackLeaveTheReport() {
        for (k in listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE))
            assertEquals("PAD2", ReportPage.Model.keyName(k))         // the page: keyboard, then preview, then the page itself
    }
}
