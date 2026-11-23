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
        // 2026-11-23: a mark the game did not write anew never replaces the pick
        assertEquals(GameAccountDiag.Learned.KEPT, GameAccountDiag.learned(b, a, fresh = false))
        assertEquals(GameAccountDiag.Learned.SAME, GameAccountDiag.learned(a, a, fresh = false))
        assertEquals(GameAccountDiag.Learned.NONE, GameAccountDiag.learned(null, a, fresh = false))
    }

    /** the client's glue code: only a typed login with a saved login name and a marked list skips the list */
    @Test fun verdictPerRoute() {
        val saved = GameAccountDiag.Shape(GameAccountDiag.ListState.MARKED, 2, true)
        val remembered = GameAccountDiag.Play(ask = false, pickSet = true, learned = GameAccountDiag.Learned.SAME, before = saved, marked = false)
        assertTrue(GameAccountDiag.verdict(remembered, GameAccountDiag.Route.SAVED_LOGIN).startsWith("list expected"))
        // 2026-11-23: Browser login hands the remembered account over (when Battle.net lists it); Ask each time keeps the list
        assertTrue(GameAccountDiag.verdict(remembered, GameAccountDiag.Route.BROWSER).startsWith("the helper hands the remembered game account over"))
        assertTrue(GameAccountDiag.verdict(remembered.copy(ask = true), GameAccountDiag.Route.BROWSER).startsWith("list expected (Ask each time"))
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
        assertEquals("(no Play in this run); game's log: no account count read; hand-over: none; Config.wtf now: missing",
            GameAccountDiag.reportLine(null, null) { "" })
        assertTrue(GameAccountDiag.reportLine(null, null, handover = "handed over (4 characters, region US); ready") { "" }
            .contains("; hand-over: handed over (4 characters, region US); ready;"))
    }

    @Test fun markedAndAskWords() {
        val p = GameAccountDiag.Play(false, true, GameAccountDiag.Learned.NONE, null, true)
        assertTrue(GameAccountDiag.line(p, GameAccountDiag.Route.BROWSER).contains("at Play no Config.wtf; marked this start; route browser login"))
        assertTrue(GameAccountDiag.line(p.copy(ask = true), GameAccountDiag.Route.OWN_SCREEN).contains("ask each time; pick set; learned none marked"))
        assertTrue(GameAccountDiag.line(p.copy(ask = true), GameAccountDiag.Route.OWN_SCREEN).contains("; no mark written;"))
    }

    // ------------------------------------------------------------------------------------------------ the list in the game's log

    /** sample lines in the client's format (WoW Forever 2026-10-09 and the user's Thor 2026-11-07, two game accounts; the order and
     *  the gaps as measured, account names replaced by fakes) */
    private val logonOne = "10/9 14:55:19.712  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\" numGameAccounts=\"1\""
    private val logonTwo = "11/20 09:12:03.250  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\" numGameAccounts=\"2\""
    private val gameInfo = "11/20 09:12:03.430  [N][Connection] Battle.net account service game info received responseType=\"GameInfo\" " +
        "responseTypeValue=\"4\" code=\"ERROR_OK (0)\" name=\"$a\" numGameAccounts=\"2\" numGameAccountNames=\"0\""
    /** 176 ms after the count: no account yet, the game asks (the old parse ended the list here: "up 0 s, picked") */
    private val asks = "11/20 09:12:03.426  [N][Connection] Battle.net complete login requested hasGameAccount=\"false\""
    private val listUp = "11/20 09:12:03.426  [N][Connection] Battle.net login requires additional info infoType=\"(enum)4\""
    private val complete = "11/20 09:12:15.010  [N][Connection] Battle.net complete login requested hasGameAccount=\"true\""
    private val notSaved = "11/20 09:12:15.010  [N][Connection] Game account list not saved because accountName CVar is empty"

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

    /**
     * the list's count, how late the buffered log was read, and how long the list stayed up; never a name. FIX 2026-11-23 (the user's
     * ruling): the real two-account sequence - count, hasGameAccount="false", infoType 4, then hasGameAccount="true" at the pick -
     * reads "up 12 s, picked", not "up 0 s, picked"
     */
    @Test fun aListIsRecordedWithItsTiming() {
        GameAccountDiag.newRun()
        val readAt = GameAccountDiag.clockMs(logonTwo)!! + 31_000L               // the ~48 KiB buffer: read half a minute late
        assertEquals("Game account list: 2 accounts, read 31 s after the game wrote it", GameAccountDiag.noteLog(logonTwo, readAt))
        assertNull(GameAccountDiag.noteLog(logonTwo, readAt + 5_000L))          // Connection.log's copy of the same line: the first read stands
        assertNull(GameAccountDiag.noteLog(gameInfo, readAt))
        assertEquals("Game account list: list seen (2 accounts), read 31 s after the game wrote it", GameAccountDiag.noteLog(asks, readAt))
        assertFalse(GameAccountDiag.logList!!.done)                             // the old bug: "up 0 s, picked" here
        assertNull(GameAccountDiag.noteLog(listUp, readAt))                     // the same list: told once
        assertNull(GameAccountDiag.noteLog(asks, readAt))                       // Connection.log's copy
        assertEquals("Game account list: list seen (2 accounts), read 31 s after the game wrote it, up 12 s, picked",
            GameAccountDiag.noteLog(complete, readAt))
        assertTrue(GameAccountDiag.logList!!.done && GameAccountDiag.logList!!.shown)
        assertNull(GameAccountDiag.noteLog(complete, readAt))                  // told once
        assertNull(GameAccountDiag.noteLog(notSaved, readAt))
        val report = GameAccountDiag.reportLine(null, null, GameAccountDiag.logList) { "" }
        assertTrue(report, report.contains("game's log: list seen (2 accounts), read 31 s after the game wrote it, up 12 s, picked; hand-over:"))
        for (s in listOf(report, GameAccountDiag.logList!!.words())) assertFalse(s, s.contains(a) || s.contains("name="))
        GameAccountDiag.newRun()
        assertNull(GameAccountDiag.logList)
        // the additional-info line alone also says the list is up
        GameAccountDiag.noteLog(logonTwo, readAt)
        assertTrue(GameAccountDiag.noteLog(listUp, readAt)!!.startsWith("Game account list: list seen (2 accounts)"))
        assertTrue(GameAccountDiag.noteLog(complete, readAt)!!.endsWith("up 12 s, picked"))
        GameAccountDiag.newRun()
    }

    /**
     * review 2026-11-23: the user's Thor 2026-11-07 (two game accounts), line for line in the client's own order and clock (the two
     * GameInfo names replaced by placeholders): the list is up from 45.832 to 57.384 - "up 12 s, picked", never "up 0 s"
     */
    @Test fun theRealTwoAccountSequence() {
        GameAccountDiag.newRun()
        val c = "[N][Connection] Battle.net"
        val lines = listOf(
            "11/7 11:52:45.656  $c logon completed result=\"ERROR_OK (0)\" numGameAccounts=\"2\"",
            "11/7 11:52:45.657  $c login waiting for server response",
            "11/7 11:52:45.832  $c account service game info received responseType=\"GameInfo\" responseTypeValue=\"4\" code=\"ERROR_OK (0)\" name=\"WoW2\" numGameAccounts=\"2\" numGameAccountNames=\"0\"",
            "11/7 11:52:45.832  $c account service game info received responseType=\"GameInfo\" responseTypeValue=\"4\" code=\"ERROR_OK (0)\" name=\"WoW1\" numGameAccounts=\"2\" numGameAccountNames=\"1\"",
            "11/7 11:52:45.832  $c complete login requested hasGameAccount=\"false\"",
            "11/7 11:52:45.832  $c login requires additional info infoType=\"(enum)4\"",
            "11/7 11:52:51.482  [N][BleepPinger] Sending ping results from timeout",
            "11/7 11:52:57.384  $c complete login requested hasGameAccount=\"true\"",
            "11/7 11:52:57.384  [N][Connection] Game account list not saved because accountName CVar is empty",
            "11/7 11:52:57.384  $c logon completed in glue")
        val readAt = GameAccountDiag.clockMs(lines[0])!! + 30_000L
        val said = lines.mapNotNull { GameAccountDiag.noteLog(it, readAt) }
        assertEquals(listOf("Game account list: 2 accounts, read 30 s after the game wrote it",
            "Game account list: list seen (2 accounts), read 30 s after the game wrote it",
            "Game account list: list seen (2 accounts), read 30 s after the game wrote it, up 12 s, picked"), said)
        for (l in said) assertFalse(l, l.contains("WoW") || l.contains("name="))
        GameAccountDiag.newRun()
    }

    /** a hand-over that worked (2026-11-23): the first "complete login" already has its account - no list with two accounts */
    @Test fun aPassedAccountIsNoList() {
        GameAccountDiag.newRun()
        val readAt = GameAccountDiag.clockMs(logonTwo)!! + 2_000L
        GameAccountDiag.noteLog(logonTwo, readAt)
        assertEquals("Game account list: no list (2 accounts: the game had its account), read 2 s after the game wrote it",
            GameAccountDiag.noteLog(complete, readAt))
        val l = GameAccountDiag.logList!!
        assertTrue(l.done && !l.shown && l.upMs == null)
        assertNull(GameAccountDiag.noteLog(asks, readAt))                       // after the login has its account: nothing more
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
