package app.gamenative

import com.jrees.wowcompanion.DiagLog
import com.jrees.wowcompanion.ProblemReport
import com.jrees.wowcompanion.Redact
import com.jrees.wowcompanion.ReportFlow
import com.jrees.wowcompanion.ReportPage
import android.view.KeyEvent
import org.robolectric.RuntimeEnvironment
import com.jrees.wowcompanion.SetupFlow
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** board f-problem-report, off-device: the privacy pass above all, then the log, the report, its file and its GitHub link */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProblemReportTest {
    private val names = listOf("Thrallmar", "Jaina", "Stormrage", "Knights of Azeroth")

    // ---- the privacy pass

    @Test fun loginsAndBattleTagsGo() {
        val t = Redact.text("sign-in as player.one@example.com, tag Jaina#11234 ok", emptyList())
        assertFalse(t, t.contains("example.com")); assertFalse(t, t.contains("11234"))
        assertTrue(t, t.contains("<email>") && t.contains("<battletag>"))
    }

    @Test fun secretsWrittenAsKeyValueGo() {
        for (s in listOf("password=hunter2", "token: abc.def", "Authorization=Bearer xyz", "session=\"q1w2\"", "login: someone"))
            assertFalse(s, Redact.text(s, emptyList()).let { it.contains("hunter2") || it.contains("abc.def") || it.contains("xyz") || it.contains("q1w2") || it.contains("someone") })
    }

    @Test fun characterRealmAndGuildNamesGoAnyCase() {
        val t = Redact.text("party: jaina (Stormrage) joined Knights of Azeroth; THRALLMAR says hi", names)
        for (n in names) assertFalse(t, t.contains(n, ignoreCase = true))
        assertTrue(t, t.contains("<name>"))
    }

    @Test fun namesOnlyAsWholeWords() {
        val t = Redact.text("Jainaville and xJaina stay; Jaina goes", listOf("Jaina"))
        assertEquals("Jainaville and xJaina stay; <name> goes", t)
    }

    @Test fun chatTextGoes() {
        val t = Redact.text("""{"type":"chat","text":"meet me at the bank, my number is 555-0100","ch":4}""", emptyList())
        assertFalse(t, t.contains("bank")); assertFalse(t, t.contains("555"))
        assertTrue(t, t.contains("\"ch\":4"))
        assertFalse(Redact.text("whisper: see you at 8", emptyList()).contains("see you"))
    }

    @Test fun ipAddressesAndUserPathsGo() {
        val t = Redact.text("cdn 203.0.113.7 and 2001:db8:0:0:0:0:2:1, C:\\Users\\jrees\\Desktop, /home/sam/x, /Users/kim/y", emptyList())
        for (s in listOf("203.0.113.7", "2001:db8", "jrees", "sam", "kim")) assertFalse(t, t.contains(s))
    }

    @Test fun urlQueriesGo() =
        assertEquals("https://host/path?<query>", Redact.text("https://host/path?user=bob&key=1", emptyList()))

    @Test fun usefulFactsStay() {
        val s = "10-14 12:00:00.123 WCHARVEST: fetched data/ab/cd/0123456789abcdef0123456789abcdef (4096 bytes); build 1.15.8.64101; set 125"
        assertEquals(s, Redact.text(s, emptyList()))
    }

    @Test fun nameRealmPairsAreLearntBothWays() {
        Redact.forget()
        Redact.know("Jaina-Stormrage")
        assertEquals("<name> of <name>", Redact.text("Jaina of Stormrage"))
        Redact.forget()
    }

    // ---- the add-on's documents: shapes, never words

    @Test fun aDocumentKeepsItsFlagsAndLosesItsWords() {
        val s = DiagLog.summarize(JSONObject("""{"type":"chat","text":"secret words","from":"Jaina","fight":true,"n":3,"bar":{"ok":false,"label":"x"},"slots":[1,2]}"""))
        assertEquals("bar{ok=0} fight=1 n=3 slots[2]", s)
    }

    @Test fun theLogRollsAndKeepsTheWindow() {
        val d = Files.createTempDirectory("diag").toFile()
        DiagLog.useDir(d)
        val t0 = 1_000_000_000_000L
        DiagLog.event("old", "an hour ago", t0 - 3_600_000)
        DiagLog.event("art", "Ready", t0 - 60_000)
        DiagLog.doc(JSONObject("""{"type":"home","fight":false,"from":"Jaina"}"""), t0)
        val lines = DiagLog.lines(ProblemReport.WINDOW_MS, t0)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].endsWith("art: Ready"))
        assertTrue(lines[1], lines[1].endsWith("doc: home fight=0"))
        // the name the document carried is known now, for the report's logcat lines
        assertTrue(Redact.text("Jaina").contains("<name>"))
        Redact.forget()
    }

    @Test fun recordingLogsEveryChangeAndStopsByItself() {
        DiagLog.useDir(Files.createTempDirectory("diag").toFile())
        val t0 = 2_000_000_000_000L
        DiagLog.startRecording(t0)
        assertTrue(DiagLog.recording(t0 + 1000))
        DiagLog.doc(JSONObject("""{"type":"padmap","m":{"fight":true}}"""), t0 + 1000)
        DiagLog.doc(JSONObject("""{"type":"padmap","m":{"fight":false}}"""), t0 + 2000)   // within QUIET_MS: kept while recording
        assertFalse(DiagLog.recording(t0 + DiagLog.RECORD_MS))
        DiagLog.event("x", "after", t0 + DiagLog.RECORD_MS + 1)
        val lines = DiagLog.lines(ProblemReport.WINDOW_MS, t0 + DiagLog.RECORD_MS + 2)
        assertEquals(2, lines.count { it.contains("doc+: padmap") })
        assertTrue(lines.any { it.endsWith("report: recording ended by itself") })
    }

    // ---- the report, its file and its link

    private val facts = ProblemReport.Facts("1.0.6 (106)", "set 125", "wow_classic 1.15.8.64101", "AYN Thor, Android 13",
        listOf("Frame generation: off (no DLL)"))

    @Test fun theReportIsRedactedAndOursOnly() {
        val r = ProblemReport.build(0L, "Jaina's bars went blank in Stormrage, mail me at a@b.co", facts,
            diag = listOf("2026-10-14 12:00:00.000 signin: sign-in: SIGNING"),
            logcat = listOf(
                "10-14 12:00:01.000  100  101 I WCHARVEST: fetched x from 203.0.113.7",
                "10-14 12:00:02.000  100  101 I WCCHAT: Jaina: hello there",
                "10-14 12:00:03.000  100  101 I SomeLibrary: unrelated"),
            crashes = listOf("crash.txt" to "Exception at /home/sam/x for Jaina"), recorded = true, names = listOf("Jaina", "Stormrage"))
        val all = r.preview
        for (s in listOf("Jaina", "Stormrage", "a@b.co", "203.0.113.7", "hello there", "unrelated", "sam")) assertFalse(s, all.contains(s))
        assertTrue(all.contains("Recorded in detail: yes"))
        assertEquals(listOf("report.txt", "logs.txt", "crashes/crash.txt"), r.files.map { it.first })
    }

    @Test fun theZipHoldsTheFilesWhole() {
        val r = ProblemReport.build(0L, "x", facts, emptyList(), emptyList(), emptyList(), false, emptyList())
        val z = ProblemReport.writeZip(Files.createTempDirectory("rep").toFile(), r)
        ZipFile(z).use { f -> assertEquals(setOf("report.txt", "logs.txt"), f.entries().toList().map { it.name }.toSet()) }
        assertFalse(File(z.path + ".part").exists())
    }

    @Test fun theIssueLinkIsPrefilledAndRedacted() {
        val r = ProblemReport.build(0L, "x", facts, emptyList(), emptyList(), emptyList(), false, emptyList())
        val url = ProblemReport.issueUrl(r, "Bars blank for Jaina\nmore", facts, "/sdcard/Documents/DS Forever/reports/r.zip", listOf("Jaina"))
        assertTrue(url.startsWith("https://github.com/Jreese702/DS-Forever/issues/new?title="))
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertFalse(decoded.contains("Jaina"))
        assertTrue(decoded, decoded.contains("Problem report: Bars blank for <name>"))
        assertTrue(decoded.contains("1.0.6 (106)") && decoded.contains("please attach /sdcard/Documents/DS Forever/reports/r.zip"))
    }

    @Test fun sendReportSaysWhereTheFileIs() {
        val opened = ProblemReport.sentMessage("/sdcard/Documents/DS Forever/reports/r.zip", browserOpened = true)
        assertTrue(opened.startsWith("Saved: /sdcard/Documents/DS Forever/reports/r.zip"))
        assertTrue(opened.contains("attach that file"))
        val none = ProblemReport.sentMessage("/x/r.zip", browserOpened = false)
        assertTrue(none.contains("Saved: /x/r.zip") && none.contains("No browser opened") && none.contains("github.com/Jreese702/DS-Forever/issues"))
    }

    // ---- the report page and its flow (the user's rules)

    @Test fun theWordsAreRequired() {
        for (t in listOf("", "   ", "  x  ", "ab", "?!.")) assertFalse(t, ReportFlow.canSend(t))
        for (t in listOf("bug", "bars blank", "  map froze  ", "lag")) assertTrue(t, ReportFlow.canSend(t))   // batch15: one short word lights Record
        assertEquals(listOf(ReportPage.Model.Target.WORDS, ReportPage.Model.Target.INCLUDED), ReportPage.Model.live(canSend = false))
        assertEquals(4, ReportPage.Model.live(canSend = true).size)
    }

    @Test fun theLaunchersKeysAreTheGuestPadsNames() {
        assertEquals("PAD1", ReportPage.Model.keyName(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("PAD2", ReportPage.Model.keyName(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("PADDUP", ReportPage.Model.keyName(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("PADFORWARD", ReportPage.Model.keyName(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals("Recording 3:12 - Stop", ReportPage.Model.recordLabel(true, 192_000))
        assertEquals("Record 5 minutes", ReportPage.Model.recordLabel(false, 0))
    }

    @Test fun theTimersEndSavesOnceAndLeavesANote() {
        val ctx = RuntimeEnvironment.getApplication()
        DiagLog.useDir(Files.createTempDirectory("diag").toFile())
        ReportFlow.init(ctx); ReportFlow.clearPending()
        ReportFlow.description = "bars went blank after a fight"        // kept with the recording (Record after typing)
        var ended = 0
        DiagLog.onEndedByItself = { ended++ }
        val t0 = System.currentTimeMillis()
        DiagLog.startRecording(t0)
        DiagLog.checkEnd(t0 + 1_000)
        assertEquals(0, ended)
        DiagLog.checkEnd(t0 + DiagLog.RECORD_MS)
        DiagLog.checkEnd(t0 + DiagLog.RECORD_MS + 1_000)
        DiagLog.event("x", "later", t0 + DiagLog.RECORD_MS + 2_000)
        assertEquals(1, ended)                                          // once
        DiagLog.onEndedByItself = null
        val f = ReportFlow.autoSave(ctx, t0 + DiagLog.RECORD_MS)!!
        assertTrue(f.isFile)
        val note = ReportFlow.pending!!
        assertEquals(f.path, note.path)
        assertTrue(note.url.startsWith("https://github.com/Jreese702/DS-Forever/issues/new"))
        assertTrue(URLDecoder.decode(note.url, "UTF-8").contains("bars went blank"))
        assertTrue(ReportFlow.noteTitle(note).startsWith("Your problem report was saved ("))
        assertEquals("", ReportFlow.description)                        // used
        ZipFile(f).use { z -> assertTrue(String(z.getInputStream(z.getEntry("report.txt")).readBytes()).contains("Recorded in detail: yes")) }
        ReportFlow.clearPending()
        assertEquals(null, ReportFlow.pending)
    }

    @Test fun theSetupScreenShowsTheSavedNote() {
        val base = SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true,
            reportSaved = "Your problem report was saved (14:32)")
        val p = SetupFlow.screen(base).prompt!!
        assertEquals("Your problem report was saved (14:32)", p.title)
        assertEquals(listOf(SetupFlow.Action.REPORT_OPEN, SetupFlow.Action.REPORT_DISMISS), p.buttons.map { it.action })
        assertEquals("login", SetupFlow.screen(base.copy(loginSaved = false)).prompt!!.id)     // the login asks first
    }

    @Test fun onlyOurTags() {
        assertTrue(ProblemReport.ourTag("WCHARVEST")); assertTrue(ProblemReport.ourTag("DSFORK")); assertTrue(ProblemReport.ourTag("FrameGen"))
        assertFalse(ProblemReport.ourTag("WCCHAT")); assertFalse(ProblemReport.ourTag("ActivityManager"))
        assertEquals("WCINTERIORS", ProblemReport.tagOf("10-14 12:00:00.000  1  2 I WCINTERIORS: heat: ok"))
    }

    @Test fun theSetupScreenHasReportAProblem() {
        val s = SetupFlow.screen(SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true))
        assertEquals(SetupFlow.Action.REPORT, s.report?.action)
    }
}
