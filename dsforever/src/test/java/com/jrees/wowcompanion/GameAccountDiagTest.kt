package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE GAME-ACCOUNT LINE (bug 2026-11-20, test 1513: Remembered did not skip the game's account list; the launch line had rolled out of
 * logcat). States and counts only, never a label or the login name; the verdict per sign-in route follows the client's own glue code
 * (GameAccountDiag's notes). Fake labels only.
 */
class GameAccountDiagTest {
    private val a = "FAKEACCT1"
    private val b = "FAKEACCT2"
    private val mail = "player@example.invalid"
    private val us = mapOf("Branch" to "us")

    @Test fun shapeCountsAndMarksWithoutValues() {
        val none = GameAccountDiag.shape(listOf("SET gxApi \"D3D11\""))
        assertEquals(GameAccountDiag.Shape(GameAccountDiag.ListState.NONE, 0, false), none)
        assertEquals("accountList none, saved login name no", none.words())
        assertEquals(GameAccountDiag.Shape(GameAccountDiag.ListState.NONE, 0, false), GameAccountDiag.shape(listOf("SET accountList \"\"")))
        val unmarked = GameAccountDiag.shape(listOf("SET accountList \"$a|$b|\"", "SET accountName \"$mail\""))
        assertEquals(GameAccountDiag.Shape(GameAccountDiag.ListState.UNMARKED, 2, true), unmarked)
        assertEquals("accountList unmarked (2), saved login name yes", unmarked.words())
        val marked = GameAccountDiag.shape(listOf("SET accountList \"$a|$b|\"", "SET accountList \"$a|!$b|\"", "SET accountName \"\""))
        assertEquals(GameAccountDiag.Shape(GameAccountDiag.ListState.MARKED, 2, false), marked)       // the last line wins; "" = none saved
        for (s in listOf(none, unmarked, marked)) assertFalse(s.words().contains(a) || s.words().contains(b) || s.words().contains(mail))
    }

    @Test fun shapeOfTheFilePlayReads() {
        val root = Files.createTempDirectory("acct-diag").toFile()
        assertNull(GameAccountDiag.shape(GameAccountDiag.configWtf(root, WowProduct.LAUNCH)))
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        val f = GameAccountDiag.configWtf(root, WowProduct.LAUNCH)
        assertEquals(File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/Config.wtf"), f)
        f.appendText("SET accountList \"!$a|$b|\"\r\nSET accountName \"$mail\"\r\n")              // the game writes CRLF
        assertEquals(GameAccountDiag.Shape(GameAccountDiag.ListState.MARKED, 2, true), GameAccountDiag.shape(f))
    }

    @Test fun learnedWords() {
        assertEquals(GameAccountDiag.Learned.NONE, GameAccountDiag.learned(null, a))
        assertEquals(GameAccountDiag.Learned.SAME, GameAccountDiag.learned(a, a))
        assertEquals(GameAccountDiag.Learned.NEW, GameAccountDiag.learned(b, a))
        assertEquals(GameAccountDiag.Learned.NEW, GameAccountDiag.learned(a, null))
    }

    /** the client's glue code: only a typed login with a saved login name and a marked list skips the list */
    @Test fun verdictPerRoute() {
        val saved = GameAccountDiag.Shape(GameAccountDiag.ListState.MARKED, 2, true)
        val remembered = GameAccountDiag.Play(ask = false, pickSet = true, learned = GameAccountDiag.Learned.SAME, before = saved, marked = false)
        assertTrue(GameAccountDiag.verdict(remembered, GameAccountDiag.Route.SAVED_LOGIN).startsWith("list expected"))
        assertTrue(GameAccountDiag.verdict(remembered, GameAccountDiag.Route.BROWSER).startsWith("list expected"))
        assertEquals("a typed login preselects the marked account", GameAccountDiag.verdict(remembered, GameAccountDiag.Route.OWN_SCREEN))
        val noName = remembered.copy(before = saved.copy(list = GameAccountDiag.ListState.UNMARKED, savedName = false), marked = true)
        assertTrue(GameAccountDiag.verdict(noName, GameAccountDiag.Route.OWN_SCREEN).contains("no saved login name"))
        val nothing = remembered.copy(pickSet = false, before = null)
        assertTrue(GameAccountDiag.verdict(nothing, GameAccountDiag.Route.OWN_SCREEN).contains("no marked account"))
        assertTrue(GameAccountDiag.verdict(remembered.copy(ask = true), GameAccountDiag.Route.OWN_SCREEN).contains("Ask each time"))
    }

    @Test fun theLineAndTheReportNameNoLabel() {
        val root = Files.createTempDirectory("acct-line").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        val f = GameAccountDiag.configWtf(root, WowProduct.LAUNCH)
        f.appendText("SET accountList \"$a|!$b|\"\nSET accountName \"$mail\"\n")
        val before = GameAccountDiag.shape(f)
        val play = GameAccountDiag.Play(false, true, GameAccountDiag.learned(GameConfigWtf.accountPickIn(root, WowProduct.LAUNCH), a), before, false)
        val logged = ArrayList<String>()
        val l = GameAccountDiag.record(play, GameAccountDiag.Route.SAVED_LOGIN, now = 1_000L) { logged += it }
        assertEquals(listOf(l), logged)
        assertEquals(1_000L to l, GameAccountDiag.last)
        assertEquals("Game account: remembered; pick set; learned new; at Play accountList marked (2), saved login name yes; " +
            "accountList unchanged; route saved login (login.txt); list expected (the login.txt sign-in never reads accountList)", l)
        val report = GameAccountDiag.reportLine(GameAccountDiag.last, GameAccountDiag.shape(f)) { "T$it" }
        assertTrue(report, report.startsWith("T1000: remembered; pick set;"))
        assertTrue(report.endsWith("; Config.wtf now: accountList marked (2), saved login name yes"))
        for (s in listOf(l, report)) assertFalse(s, s.contains(a) || s.contains(b) || s.contains(mail) || s.contains("example"))
        assertEquals("(no Play in this run); game's log: no account count read; auto-pick off (no timely signal); Config.wtf now: missing",
            GameAccountDiag.reportLine(null, null) { "" })
    }

    @Test fun markedAndAskWords() {
        val p = GameAccountDiag.Play(false, true, GameAccountDiag.Learned.NONE, null, true)
        assertTrue(GameAccountDiag.line(p, GameAccountDiag.Route.BROWSER).contains("at Play no Config.wtf; marked this start; route browser login"))
        assertTrue(GameAccountDiag.line(p.copy(ask = true), GameAccountDiag.Route.OWN_SCREEN).contains("ask each time; pick set; learned none marked"))
        assertTrue(GameAccountDiag.line(p.copy(ask = true), GameAccountDiag.Route.OWN_SCREEN).contains("; no mark written;"))
    }

    // ------------------------------------------------------------------------------------------------ the list in the game's log

    /** sample lines in the client's format (WoW Forever 2026-10-09; account names replaced by fakes) */
    private val logonOne = "10/9 14:55:19.712  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\" numGameAccounts=\"1\""
    private val logonTwo = "11/20 09:12:03.250  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\" numGameAccounts=\"2\""
    private val gameInfo = "11/20 09:12:03.430  [N][Connection] Battle.net account service game info received responseType=\"GameInfo\" " +
        "responseTypeValue=\"4\" code=\"ERROR_OK (0)\" name=\"$a\" numGameAccounts=\"2\" numGameAccountNames=\"0\""
    private val selecting = "11/20 09:12:07.900  [N][Connection] Selecting game account"
    private val complete = "11/20 09:12:08.010  [N][Connection] Battle.net complete login requested hasGameAccount=\"true\""

    @Test fun theLogonLineGivesTheCountAndTheClock() {
        assertEquals(1, GameAccountDiag.accountCount(logonOne))
        assertEquals(2, GameAccountDiag.accountCount(logonTwo + "\r"))
        assertNull(GameAccountDiag.accountCount(gameInfo))                   // the game info line also says numGameAccounts: not this one
        assertNull(GameAccountDiag.accountCount("10/9 14:55:18.063  [N][Connection] Battle.net logon response received result=\"ERROR_OK (0)\""))
        assertEquals(((14 * 60 + 55) * 60 + 19) * 1000L + 712, GameAccountDiag.clockMs(logonOne))
        assertNull(GameAccountDiag.clockMs("no clock here"))
        assertEquals(2_000L, GameAccountDiag.lag(86_399_000L, 1_000L))      // over midnight
        assertNull(GameAccountDiag.lag(0L, 2 * 3_600_000L))                 // another day's line
    }

    @Test fun oneAccountIsNoList() {
        GameAccountDiag.newRun()
        assertEquals("Game account list: no list (1 account)", GameAccountDiag.noteLog(logonOne, GameAccountDiag.clockMs(logonOne)!! + 1_000L))
        assertTrue(GameAccountDiag.logList!!.done)
        assertNull(GameAccountDiag.noteLog(complete))                         // no list: nothing more to tell
    }

    /** the list's count, how late the buffered log was read, and how long the list stayed up; never a name */
    @Test fun aListIsRecordedWithItsTiming() {
        GameAccountDiag.newRun()
        val readAt = GameAccountDiag.clockMs(logonTwo)!! + 31_000L               // the ~48 KiB buffer: read half a minute late
        assertEquals("Game account list: list seen (2 accounts), read 31 s after the game wrote it", GameAccountDiag.noteLog(logonTwo, readAt))
        assertNull(GameAccountDiag.noteLog(logonTwo, readAt + 5_000L))          // Connection.log's copy of the same line: the first read stands
        assertNull(GameAccountDiag.noteLog(gameInfo, readAt))
        assertEquals("Game account list: list seen (2 accounts), read 31 s after the game wrote it, up 5 s",
            GameAccountDiag.noteLog(selecting, readAt))
        assertNull(GameAccountDiag.noteLog(complete, readAt))                  // told once
        val report = GameAccountDiag.reportLine(null, null, GameAccountDiag.logList) { "" }
        assertTrue(report, report.contains("game's log: list seen (2 accounts), read 31 s after the game wrote it, up 5 s; auto-pick off"))
        for (s in listOf(report, GameAccountDiag.logList!!.words())) assertFalse(s, s.contains(a) || s.contains("name="))
        GameAccountDiag.newRun()
        assertNull(GameAccountDiag.logList)
        // without a "Selecting" line, the login going on ends the list too
        GameAccountDiag.noteLog(logonTwo, readAt)
        assertTrue(GameAccountDiag.noteLog(complete, readAt)!!.endsWith("up 5 s"))
        GameAccountDiag.newRun()
    }

    /** Play writes no "!" any more (the user's pick 2026-11-20): the pick is learned only; Ask each time still clears */
    @Test fun playWritesNoMark() {
        val src = listOf("../app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt", "app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
            .map { File(it) }.first { it.isFile }.readText()
        val ensure = src.indexOf("val accountKeys = com.jrees.wowcompanion.GameConfigWtf.ensure(")
        val call = src.substring(ensure, src.indexOf("lowMemCuts())", ensure))
        assertTrue(call.contains("accountPick = null,"))
        assertFalse(call.contains("accountPick = pick"))
        // what that call does to a file the game marked itself: kept as the game wrote it, never re-marked
        val root = Files.createTempDirectory("acct-nomark").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        val f = GameAccountDiag.configWtf(root, WowProduct.LAUNCH)
        f.appendText("SET accountList \"$a|$b|\"\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        val before = f.readText()
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, accountPick = null))
        assertEquals(before, f.readText())
        f.appendText("SET accountList \"!$a|\"\n")                                   // a list the game marked itself
        assertEquals(a, GameConfigWtf.accountPickIn(root, WowProduct.LAUNCH))          // still learned
        assertEquals(listOf("accountList"), GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, askAccount = true))   // Ask clears it
    }

    /** Play: the shape is read before any write, and the line is logged once the sign-in route is known (after NativeLogin.prepare) */
    @Test fun playReadsBeforeWritingAndLogsWithTheRoute() {
        val src = listOf("../app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt", "app/src/main/java/app/gamenative/ui/screen/wow/WoWForeverScreen.kt")
            .map { File(it) }.first { it.isFile }.readText()
        val shape = src.indexOf("val shapeBefore = com.jrees.wowcompanion.GameAccountDiag.shape(")
        val ensure = src.indexOf("val accountKeys = com.jrees.wowcompanion.GameConfigWtf.ensure(")
        val prepare = src.indexOf("val wineRoot = com.jrees.wowcompanion.NativeLogin.prepare(")
        val record = src.indexOf("com.jrees.wowcompanion.GameAccountDiag.record(")
        assertTrue(shape in 1 until ensure)
        assertTrue(prepare in (ensure + 1) until record)
        val block = src.substring(record, src.indexOf("\n    }\n", record))
        assertTrue(block.contains("browser -> com.jrees.wowcompanion.GameAccountDiag.Route.BROWSER"))
        assertTrue(block.contains("NativeLogin.active -> com.jrees.wowcompanion.GameAccountDiag.Route.SAVED_LOGIN"))
    }
}
