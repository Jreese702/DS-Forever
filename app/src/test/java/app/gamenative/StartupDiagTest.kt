package app.gamenative

import android.view.KeyEvent
import com.jrees.wowcompanion.ProblemReport
import com.jrees.wowcompanion.ReportUpload
import com.jrees.wowcompanion.SetupBridge
import com.jrees.wowcompanion.SetupFlow
import com.jrees.wowcompanion.SetupPanel
import com.jrees.wowcompanion.StartupDiag
import com.jrees.wowcompanion.StartupTrace
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * THE REPORT'S START-UP DIAGNOSTICS (the user: "make the APP start up provide WAY more information"; reports #8-#13 held only the
 * player's words): the privacy pass, the size cap, every section in the block, the start-up stages, the upload's field, and the
 * start-up screen's speakerphone by the controller's Select.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupDiagTest {
    @Before fun noLiveReceiver() { ReportUpload.endpoint = ""; StartupTrace.reset() }    // the live receiver is never a test's
    @After fun clean() { SetupBridge.publish(null); SetupBridge.openReport = null; StartupTrace.reset() }

    @Test fun theSanitizerDropsCredentialsAndMasksNamesAndAddresses() {
        val raw = listOf(
            "WCLOGIN: read login.txt for the account",                 // the login file named: the line goes
            "WCLOGIN: password=hunter2 sent",                          // a credential: the line goes
            "AppUpdater: Authorization: Bearer abc.def",               // a header: the line goes
            "WCSETUP: token=abc123 refresh",                           // key=value secret: masked
            "WCSETUP: signed in as player@example.com from 192.168.1.20",
            "WCLOG: Jaina of Stormrage logged in",                     // the known names
            "WCHARVEST: harvest step maps v4 done in 1200 ms",         // a useful fact: kept
        ).joinToString("\n")
        val s = StartupDiag.sanitize(raw, listOf("Jaina", "Stormrage"))
        for (gone in listOf("login.txt", "hunter2", "abc.def", "abc123", "player@example.com", "192.168.1.20", "Jaina", "Stormrage"))
            assertFalse("$gone in: $s", s.contains(gone))
        assertTrue(s, s.contains("harvest step maps v4 done in 1200 ms"))
        assertTrue(s, s.contains("<email>") && s.contains("<ip>") && s.contains("<name>"))
    }

    @Test fun theBlockIsCappedSectionBySectionAndWhole() {
        val big = StartupDiag.Section("Log", List(5_000) { "line $it ".repeat(4) })
        val one = StartupDiag.render(listOf(StartupDiag.Section("App", listOf("DS Forever 1.0.9")), big))
        assertTrue(one, one.contains("=== App ===") && one.contains("[section cut]"))
        assertTrue("${one.length}", one.length <= StartupDiag.MAX)
        // many full sections: the whole stays under the cap, the first ones whole, a note where it stops
        val all = StartupDiag.render(List(10) { StartupDiag.Section("S$it", List(2_000) { "x".repeat(10) }) })
        assertTrue("${all.length}", all.length <= StartupDiag.MAX)
        assertTrue(all.startsWith("=== S0 ==="))
        assertTrue(all, all.contains("[cut: the report's limit]"))
        // an empty section says so
        assertTrue(StartupDiag.render(listOf(StartupDiag.Section("Empty", emptyList()))).contains("(nothing)"))
    }

    @Test fun everySectionIsInTheBlockOnADevice() {
        val ctx = RuntimeEnvironment.getApplication()
        StartupTrace.mark("checks: game version"); StartupTrace.mark("art: 60%", "eplaguelands")
        val text = StartupDiag.text(ctx, emptyList())
        for (t in listOf("App", "Device", "GPU and container", "Game folder", "Start-up screen", "Start-up stages", "Updates", "Game art",
                "Interiors", "Crashes and exits", "Log (our start-up tags"))
            assertTrue("no \"$t\" in:\n$text", text.contains("=== $t"))
        assertTrue(text, text.contains("DS Forever ") && text.contains("Android ") && text.contains("ABIs:") && text.contains("RAM:"))
        assertTrue(text, text.contains("art: 60%  (eplaguelands)"))
        assertTrue(text.length <= StartupDiag.MAX)
    }

    @Test fun theStartUpStagesAreNamedAndTimedOnce() {
        val i = SetupFlow.Inputs(path = "/storage/emulated/0/WoW", folderFound = true)
        assertEquals("checks: game version", StartupTrace.stageOf(i, false).first)
        assertEquals("art: 60%", StartupTrace.stageOf(i.copy(client = "1.60.1", art = SetupFlow.Art.RUNNING, artPercent = 67), false).first)
        assertEquals("art failed", StartupTrace.stageOf(i.copy(client = "x", art = SetupFlow.Art.FAILED, artWhy = "CDN"), false).first)
        assertEquals("ready (Play)", StartupTrace.stageOf(i.copy(client = "x", art = SetupFlow.Art.READY), false).first)
        assertEquals("no game folder", StartupTrace.stageOf(i.copy(folderFound = false), false).first)
        assertEquals("game up", StartupTrace.stageOf(i, true).first)
        StartupTrace.observe(i, false); StartupTrace.observe(i, false); StartupTrace.observe(i.copy(client = "x"), false)
        assertEquals(2, StartupTrace.lines().size)                      // the same stage twice is one
        assertTrue(StartupTrace.lines()[0], StartupTrace.lines()[0].matches(Regex("""\+\d+\.\d s  checks: game version""")))
    }

    @Test fun theReportAndTheUploadCarryTheBlock() {
        val facts = ProblemReport.Facts("1.0.9 (1045)", "set 237", "wow_classic_beta 1.60.1", "AYN Thor, Android 13", emptyList())
        val diag = "=== App ===\nDS Forever 1.0.9 (1045)\n=== Game art ===\nState: FAILED: could not build maps: CDN"
        val r = ProblemReport.build(0L, "stuck on 68% EPL", facts, emptyList(), emptyList(), emptyList(), false, emptyList(), startup = diag)
        assertTrue(r.files.any { it.first == "diagnostics.txt" && it.second.contains("could not build maps") })
        assertTrue(r.preview.contains("===== diagnostics.txt ====="))           // "Show what's included" shows it
        val o = JSONObject(ReportUpload.request(r, "stuck on 68% EPL", facts, false, emptyList()))
        assertTrue(o.getString("diag").contains("=== Game art ==="))
        // a huge block is cut to the receiver's limit
        val huge = ProblemReport.build(0L, "x", facts, emptyList(), emptyList(), emptyList(), false, emptyList(), startup = "d".repeat(80_000))
        assertTrue(JSONObject(ReportUpload.request(huge, "x", facts, false, emptyList())).getString("diag").length <= ReportUpload.MAX_DIAG)
        assertTrue(ReportUpload.request(huge, "x", facts, false, emptyList()).length <= ReportUpload.MAX_REQUEST)
    }

    @Test fun theSpeakerphoneOpensByTheControllersSelectNeverByTheCursor() {
        var opened = 0
        SetupBridge.openReport = { opened++ }
        SetupBridge.publish(SetupFlow.Inputs(path = "/storage/emulated/0/WoW", folderFound = true, client = "x", art = SetupFlow.Art.READY,
            loginSaved = true))
        val panel = SetupPanel(RuntimeEnvironment.getApplication())
        panel.update()
        // the D-pad and A never land on it (b-setup-report-cursor)
        repeat(4) { panel.key(KeyEvent.KEYCODE_DPAD_RIGHT, true) }
        assertEquals(0, opened)
        assertTrue(panel.key(KeyEvent.KEYCODE_BUTTON_SELECT, true))
        assertEquals(1, opened)
        assertTrue("${panel.contentDescription}", panel.contentDescription?.contains("Report a problem") == true)
    }
}
