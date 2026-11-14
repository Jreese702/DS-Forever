package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * THE CAPTCHA HOLD (the user, 2026-11-13: "yes", an approved exception to the 20 s rule for the private saved sign-in file; #271 #247
 * #235): while the bottom screen's sign-in Keyboard is open or the game's log says it asks for a captcha, a read login.txt stays until
 * the client's next read + 20 s, at most 3 minutes after its first read; the realm connection, the world and the game's close delete it
 * at once. Every deletion path of [LoginHold], on a virtual clock. Timings only: no file and no credential is ever touched here.
 */
class CaptchaHoldTest {
    /** a virtual clock and timer: [post]ed runnables run in time order (ties in post order) as [to] moves the clock */
    private class Clock {
        var t = 0L
        private val queue = ArrayList<Pair<Long, Runnable>>()
        fun post(r: Runnable, ms: Long) { queue += (t + ms) to r }
        fun cancel(r: Runnable) { queue.removeAll { it.second === r } }
        fun pending() = queue.size
        fun to(end: Long) {
            while (true) {
                val next = queue.minByOrNull { it.first } ?: break
                if (next.first > end) break
                queue.remove(next); t = next.first; next.second.run()
            }
            t = end
        }
    }

    private val s = 1000L
    private val clock = Clock()
    private var reason: String? = null
    /** (when, why) of each deletion; NativeLogin's delete = clear(), which stops the hold */
    private val deleted = ArrayList<Pair<Long, String>>()
    private val logs = ArrayList<String>()
    private lateinit var hold: LoginHold

    @Before fun setUp() {
        hold = LoginHold({ clock.t }, clock::post, clock::cancel, { reason }, { why -> deleted += clock.t to why; hold.stop() }, { logs += it })
    }

    private fun readAt(t: Long) { clock.to(t); hold.read() }

    @Test fun noReasonGoesTwentySecondsAfterTheFirstReadAsBefore() {
        hold.firstRead()
        // the login screen's burst of reads and the form's submission ~2 s later never push it out (the 10-08 device tests)
        readAt(300L); readAt(2 * s)
        clock.to(19 * s); assertTrue(deleted.isEmpty())
        clock.to(10 * 60 * s)
        assertEquals(listOf(20 * s to "20 s after the client's first read"), deleted)
        assertFalse(hold.holding); assertEquals(0, clock.pending())
        assertTrue("no hold line without a reason", logs.isEmpty())
    }

    @Test fun theKeyboardOpenKeepsItForTheNextReadPlusTwenty() {
        hold.firstRead()
        reason = "the Keyboard is open"
        clock.to(20 * s)
        assertTrue("kept at its 20 s", deleted.isEmpty()); assertTrue(hold.holding); assertTrue(hold.waitingRead)
        clock.to(31 * s); reason = null                  // the player closes the Keyboard and presses Log in on the game
        readAt(32 * s)                                    // the form's submission reads it again
        clock.to(51 * s); assertTrue(deleted.isEmpty())
        clock.to(10 * 60 * s)
        assertEquals(listOf(52 * s to "20 s after the client's read in the hold"), deleted)
        assertEquals(0, clock.pending())
    }

    @Test fun theGamesCaptchaAskHoldsItTheSameWay() {
        hold.firstRead()
        reason = "the game asks for a captcha"
        clock.to(20 * s); assertTrue(hold.holding)
        assertTrue(logs.single(), logs.single().startsWith("login.txt kept past its 20 s (the game asks for a captcha)"))
        clock.to(39 * s); reason = null                  // the trail: "the sign-in form is sent"
        readAt(40 * s)
        clock.to(10 * 60 * s)
        assertEquals(listOf(60 * s to "20 s after the client's read in the hold"), deleted)
    }

    @Test fun aReasonStillThereAtTheNextDeadlineHoldsAgain() {
        hold.firstRead()
        reason = "the game asks for a captcha"
        clock.to(20 * s)
        readAt(32 * s)                                    // a wrong captcha: the game asks for another
        clock.to(52 * s); assertTrue("held again at the read's 20 s", deleted.isEmpty()); assertTrue(hold.waitingRead)
        reason = null
        readAt(70 * s)
        clock.to(10 * 60 * s)
        assertEquals(listOf(90 * s to "20 s after the client's read in the hold"), deleted)
    }

    @Test fun readsBeforeTheTwentySecondsDoNotCountAsTheNextRead() {
        hold.firstRead()
        reason = "the Keyboard is open"
        readAt(1 * s); readAt(2 * s); readAt(15 * s)
        clock.to(20 * s); assertTrue(hold.waitingRead)
        clock.to(60 * s); assertTrue("waits for a read after the 20 s", deleted.isEmpty())
        reason = null
        readAt(61 * s)
        clock.to(10 * 60 * s)
        assertEquals(listOf(81 * s to "20 s after the client's read in the hold"), deleted)
    }

    @Test fun aReasonThatEndsWhileWaitingStillWaitsForTheRead() {
        hold.firstRead()
        reason = "the Keyboard is open"
        clock.to(20 * s)
        clock.to(25 * s); reason = null                  // the Keyboard closes before the game sends the form
        clock.to(44 * s); assertTrue("kept for the read it waits for", deleted.isEmpty())
        readAt(45 * s)
        clock.to(10 * 60 * s)
        assertEquals(listOf(65 * s to "20 s after the client's read in the hold"), deleted)
    }

    @Test fun neverPastThreeMinutesAfterTheFirstRead() {
        assertEquals(180 * s, LoginHold.CAP_MS)
        hold.firstRead()
        reason = "the Keyboard is open"                   // left open for ever, and no read
        clock.to(179 * s); assertTrue(deleted.isEmpty())
        clock.to(10 * 60 * s)
        assertEquals(listOf(180 * s to "180 s after the client's first read (the hold's cap)"), deleted)
        assertEquals(0, clock.pending())
    }

    @Test fun theCapWinsOverReadsThatKeepComing() {
        hold.firstRead()
        reason = "the game asks for a captcha"            // a captcha after captcha, a read each time
        clock.to(20 * s)
        var t = 30 * s
        while (t < 175 * s) { readAt(t); t += 25 * s }
        readAt(175 * s)                                   // its 20 s would pass the cap: the cap's moment
        clock.to(10 * 60 * s)
        assertEquals(1, deleted.size)
        assertEquals(180 * s, deleted.single().first)
        assertEquals(0, clock.pending())
    }

    @Test fun aReasonAtTheCapsMomentHoldsNothing() {
        hold.firstRead()
        reason = "the Keyboard is open"
        clock.to(20 * s)
        readAt(159_500L)                                  // its 20 s end half a second before the cap: no new hold then
        clock.to(10 * 60 * s)
        assertEquals(listOf(179_500L to "20 s after the client's read in the hold"), deleted)
        assertEquals(0, clock.pending())
    }

    @Test fun theRealmConnectionDeletesAtOnce() {
        hold.firstRead()
        clock.to(5 * s); hold.realmUp("the game's log")
        assertEquals(listOf(5 * s to "the realm connection is up (the game's log)"), deleted)
        // and while held
        deleted.clear(); hold.firstRead(); reason = "the game asks for a captcha"
        clock.to(30 * s); assertTrue(hold.holding)
        clock.to(70 * s); hold.realmUp("the game's log")
        assertEquals(listOf(70 * s to "the realm connection is up (the game's log)"), deleted)
        clock.to(10 * 60 * s); assertEquals(1, deleted.size); assertEquals(0, clock.pending())
    }

    @Test fun theWorldDeletesAtOnce() {
        hold.firstRead(); reason = "the Keyboard is open"
        clock.to(20 * s); readAt(40 * s); clock.to(60 * s)          // held again (the Keyboard still open)
        clock.to(95 * s); hold.world()
        assertEquals(listOf(95 * s to "in the world"), deleted)
        clock.to(10 * 60 * s); assertEquals(1, deleted.size); assertEquals(0, clock.pending())
    }

    @Test fun theGamesCloseAndANewRunStopEverything() {
        // NativeLogin.end (the game screen going) and prepare (a new run) delete the file themselves (clear) and stop the hold
        hold.firstRead(); reason = "the game asks for a captcha"
        clock.to(20 * s); assertTrue(hold.holding)
        clock.to(50 * s); hold.stop()
        assertFalse(hold.holding); assertFalse(hold.reading); assertEquals(0, clock.pending())
        readAt(60 * s); hold.world(); hold.realmUp("the game's log")
        clock.to(10 * 60 * s)
        assertTrue("nothing more after the close: $deleted", deleted.isEmpty())
    }

    @Test fun nothingIsTimedBeforeTheFirstRead() {
        // an unread file is the unread cap's (NativeLogin.capCheck), never the hold's
        hold.read(); hold.world(); hold.realmUp("the game's log")
        reason = "the Keyboard is open"; clock.to(10 * 60 * s)
        assertTrue(deleted.isEmpty()); assertEquals(0, clock.pending())
    }

    @Test fun aNewFirstReadStartsAFreshHold() {
        // a re-login's file (NativeLogin.rearm): its own first read, its own 20 s and cap
        hold.firstRead(); reason = "the Keyboard is open"; clock.to(20 * s)
        clock.to(100 * s); hold.stop()                    // the world came back (worldBack -> clear)
        reason = null
        clock.to(1000 * s); hold.firstRead()
        clock.to(10 * 60 * s + 1000 * s)
        assertEquals(listOf(1020 * s to "20 s after the client's first read"), deleted)
    }

    @Test fun theHoldsWordsSurviveTheReportsPrivacyPassAndNameNothing() {
        hold.firstRead(); reason = "the game asks for a captcha"
        clock.to(20 * s); readAt(32 * s); clock.to(52 * s); reason = null; readAt(60 * s); clock.to(200 * s)
        assertTrue(logs.size >= 3)
        val pattern = Redact.namePattern(emptyList())
        for (l in logs + deleted.map { "login.txt: ${it.second}, deleting" }) {
            assertEquals(l, Redact.text(l, pattern))
            assertFalse(l, Regex("[@=]").containsMatchIn(l))
        }
    }

    @Test fun theHoldNeverTouchesAFile() {
        // presence and timing only: the hold has no file access at all, and NativeLogin never reads the file it writes
        val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
            .map { File(it) }.first { it.isDirectory }
        val hold = File(src, "LoginHold.kt").readText()
        for (w in listOf("java.io", "java.nio", "File(", "readText", "readBytes", "readLines", "InputStream", "Reader"))
            assertFalse("LoginHold.kt: $w", w in hold)
        val native = File(src, "NativeLogin.kt").readLines()
        val reads = native.filter { l -> listOf("readText", "readBytes", "InputStream", "bufferedReader", "readLines").any { it in l } }
        // the one read there is the sign-in LOG's own cap (login.log), never login.txt
        assertEquals(reads.toString(), 1, reads.size)
        assertTrue(reads.single(), "val lines = f.readLines()" in reads.single())
    }

    // ---- the game's log lines (the formats of LoginTriageTest's flow): the captcha ask, the steps after it, the realm connection

    @Test fun theCaptchaWordFromTheGamesLog() {
        assertEquals(true, HomeLogin.captcha("10/6 21:09:10.240  [N][Connection] Battle.net logon form received result=\"ERROR_OK (0)\" hasCaptcha=\"true\""))
        assertEquals(false, HomeLogin.captcha("11/13 07:34:17.512  [N] Battle.net logon form received result=\"ERROR_OK (0)\" hasCaptcha=\"false\"\r"))
        for (l in listOf(
            "10/6 21:09:40.240  [N][Connection] Battle.net logon form submission requested",
            "10/6 21:09:40.848  [N][Connection] Battle.net logon form submission response received result=\"ERROR_OK (0)\" authenticatorRequired=\"true\" legalRequired=\"false\"",
            "10/6 21:09:50.961  [N][Connection] Battle.net credentials response received result=\"ERROR_OK (0)\"",
            "10/6 21:09:51.178  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\" numGameAccounts=\"2\"",
            "11/13 07:34:17.512  [N] Fatal login error received result=\"Status has no name. (14400)\"",
            "10/6 21:09:57.590  [N][Connection] Realm connection connected realmAddress=\"70-1-2\"",
        )) assertEquals(l, false, HomeLogin.captcha(l))
        for (l in listOf(
            "10/6 21:09:10.240  [N][Connection] Battle.net logon form received result=\"ERROR_OK (0)\"",      // no flag: nothing said
            "10/6 21:09:09.651  [N][Connection] Battle.net logon form requested",
            "10/6 21:08:48.585  [N][Lua] Switching to screen=\"AccountLogin\" (from \"none\")",
            "10/6 21:09:08.861  [N][Connection] Glue login started loginPortal=\"eu.actual.battle.net:1119\"",
            "10/6 21:09:51.344  [N][Connection] Battle.net logon completed in glue",
            "",
        )) assertNull(l, HomeLogin.captcha(l))
    }

    @Test fun theRealmConnectionFromTheGamesLog() {
        assertTrue(HomeLogin.realmUp("10/6 21:09:57.590  [N][Connection] Realm connection connected realmAddress=\"70-1-2\""))
        assertFalse(HomeLogin.realmUp("10/6 21:09:51.178  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\""))
        assertFalse(HomeLogin.realmUp("10/6 21:09:59.408  [N][Lua] Switching to screen=\"CharacterSelect\" (from \"login\")"))
    }
}
