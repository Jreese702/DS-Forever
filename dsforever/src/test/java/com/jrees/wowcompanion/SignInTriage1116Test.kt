package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sign-in triage of 2026-11-16 (1.0.32 reports #289 #296 #312 #313 #334 #399 #422 #423 #424), off-device: the bottom screen's
 * words while the game is at (or still downloading before) its login screen.
 *  - no saved sign-in file read: the download's own seconds are not "past the login screen" (1.0.32 then said "Waiting for the world.")
 *  - a restart's line goes once the new game run attaches (it stayed for minutes at the login screen)
 *  - "Signing in…" ends at the game's refusal (1.0.32's captcha hold, now retired, kept it up to 3 minutes over "You have been
 *    disconnected"; the file's 20 s still outlast a refusal)
 *  - the sign-in log says why a run has no saved sign-in file
 */
class SignInTriage1116Test {

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val i18n = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
        .map { File(it) }.first { it.isDirectory }

    // ---- no read file: a download is not the sign-in

    @Test fun withNoReadFileTheDownloadIsNotTheSignIn() {
        // #312 18:12:27 "past the login screen (2030639 B/s, no login.txt read)" with the download line on since 18:10:22
        assertFalse(PreLoginTraffic.trafficIsSignIn(consumed = false, active = false, windowSeen = true, expiredUnread = false,
            sinceStartS = 164L, graceS = 60L, downloadingNow = true))
        // #296 14:39:04: the file gone without a read, the game still downloading
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, false, true, false, 557L, 60L, downloadAtRead = false, downloadingNow = true))
        // no download: the no-file grace path as before (a sign-in typed on the game's own screen)
        assertTrue(PreLoginTraffic.trafficIsSignIn(false, false, true, false, 164L, 60L, downloadingNow = false))
        // a read file keeps its own rule (the download at the read is downloadAtRead's)
        assertTrue(PreLoginTraffic.trafficIsSignIn(true, false, true, false, 164L, 60L, downloadAtRead = false, downloadingNow = true))
        assertFalse(PreLoginTraffic.trafficIsSignIn(true, false, true, false, 164L, 60L, downloadAtRead = true, downloadingNow = false))
        // a file still waiting for its read: never, as before
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, true, true, false, 164L, 60L, downloadingNow = false))
    }

    @Test fun aNoFileRunDownloadingForMinutesNeverSaysPastTheLoginScreen() {
        // #399's shape (no saved login): the game started 18:01:21, downloading from 18:01:44 at 5-8 MB/s, the 60 s grace over at
        // 18:02:21 - where 1.0.32 said "past the login screen (8122155 B/s, no login.txt read)". NativeLogin.onTraffic's own steps,
        // one a second from the game's start (the window up)
        val pre = PreLoginTraffic()
        var downloading = false
        var pastLogin = false
        var t = 0L
        fun second(rx: Long) {
            t += 1_000L
            val counts = PreLoginTraffic.counts(read = false, pastLogin = pastLogin, reachedWorld = false)
            if (counts) pre.add(rx, t)
            // the verdict is taken before this second's download word, as on the device (the traffic thread, then the main post)
            if (!pastLogin && rx >= 100_000L && PreLoginTraffic.trafficIsSignIn(false, false, windowSeen = true, expiredUnread = false,
                    sinceStartS = t / 1000, graceS = 60L, downloadingNow = downloading)) pastLogin = true
            downloading = PreLoginTraffic.downloading(counts, pre.busy)
        }
        repeat(23) { second(20_000L) }
        repeat(360) { second(6_000_000L) }
        assertTrue(downloading)
        assertFalse("a download is not the sign-in", pastLogin)
        // the download ends (two quiet whole minutes), then character select's burst after a sign-in typed by hand: as before
        repeat(122) { second(2_000L) }
        assertFalse(downloading)
        second(559_000L)
        assertTrue(pastLogin)
    }

    @Test fun nativeLoginPassesItsDownloadWord() {
        val native = File(src, "NativeLogin.kt").readText()
        assertTrue("downloadingNow = downloading" in native)
    }

    // ---- the restart's line

    @Test fun theRestartsAndTheLastRunsWorldLinesGiveWayAtTheNewRun() {
        // #289 #399 #424 #423: the restart's line
        assertEquals(PreLoginTraffic.WAITING_LINE, PreLoginTraffic.lineAtNewRun(PreLoginTraffic.RESTART_LINE, downloading = false))
        assertEquals(PreLoginTraffic.DOWNLOADING_LINE, PreLoginTraffic.lineAtNewRun(PreLoginTraffic.RESTART_LINE, downloading = true))
        // #312 / #313: the last run's "Waiting for the world." (a new run is past nothing yet)
        assertEquals(PreLoginTraffic.WAITING_LINE, PreLoginTraffic.lineAtNewRun(AddonSilence.WORLD_LINE, downloading = false))
        assertEquals(PreLoginTraffic.WAITING_LINE, PreLoginTraffic.lineAtNewRun(AddonSilence.SIGNED_WORLD_LINE, downloading = false))
        assertEquals(PreLoginTraffic.DOWNLOADING_LINE, PreLoginTraffic.lineAtNewRun(AddonSilence.WORLD_LINE, downloading = true))
        // any other line stays: a sign-in's, a reload's, a note, the waiting lines themselves
        for (l in listOf("Signing in…", "Reloading the game's interface", PreLoginTraffic.WAITING_LINE, PreLoginTraffic.DOWNLOADING_LINE,
                "Save it once and the game signs in by itself.", "Saved. The game signs in by itself the next time it starts."))
            assertEquals(l, PreLoginTraffic.lineAtNewRun(l, downloading = false))
    }

    @Test fun theRowCannotGiveTheLastRunsLineBack() {
        // #313, the host's own steps: the last run's world line under the login note, the restart, the note cleared
        var line = AddonSilence.WORLD_LINE
        var lineBeforeLogin: String? = null
        var lineWritten: String? = null
        // the note over it (loginShow: the line before it is kept to give back)
        lineBeforeLogin = line; line = "Saved. The game signs in by itself the next time it starts."; lineWritten = line
        // the new game run attaches (signInStep's new-run block)
        line = PreLoginTraffic.lineAtNewRun(line, downloading = false)
        lineBeforeLogin = lineBeforeLogin?.let { PreLoginTraffic.lineAtNewRun(it, downloading = false) }
        // the note goes (loginShow: no row, the line before it back)
        lineBeforeLogin?.let { if (line == lineWritten) line = it }
        assertEquals(PreLoginTraffic.WAITING_LINE, line)
        // then the download word swaps it as ever (downloadLine)
        assertEquals(PreLoginTraffic.DOWNLOADING_LINE, PreLoginTraffic.waitingLine(line, downloading = true))
    }

    @Test fun theRestartsLineIsTheTranslatedOne() {
        // the constant is the line es / fr already translate (no new words)
        for (lang in listOf("es", "fr")) assertTrue(lang, "\"${PreLoginTraffic.RESTART_LINE}\":" in File(i18n, "$lang.json").readText())
    }

    @Test fun theNewRunClearsTheRestartsLine() {
        val host = File(src, "CompanionHost.kt").readText()
        // restartNotice writes the constant; the new client run's block puts the waiting line back, on screen and in the row's saved line
        assertTrue("startView.line = PreLoginTraffic.RESTART_LINE" in host)
        val block = host.substringAfter("if (LauncherHooks.gameStartSec != signInRun) {").substringBefore("signInRun = LauncherHooks.gameStartSec")
        assertTrue(block, "PreLoginTraffic.lineAtNewRun(startView.line, NativeLogin.downloading)" in block)
        assertTrue(block, "lineBeforeLogin = lineBeforeLogin?.let { PreLoginTraffic.lineAtNewRun(it, NativeLogin.downloading) }" in block)
    }

    // ---- "Signing in…"

    @Test fun signingInEndsAtTheGamesRefusal() {
        // the client read the file and it is still there (its 20 s after the first read)
        assertTrue(HomeLogin.signingIn(read = true, fileActive = true, phase = HomeLogin.Phase.IDLE))
        assertTrue(HomeLogin.signingIn(true, true, HomeLogin.Phase.RUNNING))
        assertTrue(HomeLogin.signingIn(true, true, HomeLogin.Phase.AUTH))
        // #422 / #423 / #334: Battle.net's refusal ("Fatal login error", BLZ51914400) - the line before it comes back
        assertFalse(HomeLogin.signingIn(true, true, HomeLogin.Phase.FAILED))
        // unread or gone: never (as before)
        assertFalse(HomeLogin.signingIn(false, true, HomeLogin.Phase.IDLE))
        assertFalse(HomeLogin.signingIn(true, false, HomeLogin.Phase.IDLE))
    }

    @Test fun aRefusalThenTheGamesNextTrySaysSigningInAgain() {
        val s = HomeLogin.State()
        s.note(HomeLogin.parse("11/16 16:06:45.866  [N][Connection] Glue login started loginType=\"(enum)0\" launcherPortal=\"nullopt\" loginPortal=\"eu.actual.battle.net:1119\"")!!)
        assertTrue(HomeLogin.signingIn(true, true, s.phase))
        s.note(HomeLogin.parse("11/16 16:07:05.559  [N][Connection] Fatal login error received result=\"Status has no name. (14400)\"")!!)
        assertEquals(HomeLogin.Phase.FAILED, s.phase)
        assertFalse(HomeLogin.signingIn(true, true, s.phase))
        // the dialog closed over the login screen: still the refusal's
        s.note(HomeLogin.Event.Screen(HomeLogin.LOGIN_SCREEN))
        assertFalse(HomeLogin.signingIn(true, true, s.phase))
        // the player's next try
        s.note(HomeLogin.parse("11/16 16:07:29.334  [N][Connection] Glue login started loginType=\"(enum)0\" launcherPortal=\"nullopt\" loginPortal=\"eu.actual.battle.net:1119\"")!!)
        assertTrue(HomeLogin.signingIn(true, true, s.phase))
    }

    @Test fun theRowAsksTheRefusal() {
        val host = File(src, "CompanionHost.kt").readText()
        assertTrue("HomeLogin.signingIn(NativeLogin.firstReadAt > 0L, NativeLogin.active, s.phase) -> StartView.Login.NONE to \"Signing in…\"" in host)
    }

    // ---- why a run has no saved sign-in file

    @Test fun theNoFileLineSaysWhich() {
        assertEquals("no saved login", HomeLogin.noFileWhy(saved = false, autoLogin = true, seeding = false))
        assertEquals("no saved login", HomeLogin.noFileWhy(saved = false, autoLogin = false, seeding = true))
        assertEquals("Auto login is off", HomeLogin.noFileWhy(saved = true, autoLogin = false, seeding = false))
        assertEquals("the install's first start", HomeLogin.noFileWhy(saved = true, autoLogin = true, seeding = true))
        assertEquals("the saved login could not be opened", HomeLogin.noFileWhy(saved = true, autoLogin = true, seeding = false))
        // states only: the words pass the report's privacy pass whole and carry nothing of a login
        val pattern = Redact.namePattern(emptyList())
        for (w in listOf(HomeLogin.noFileWhy(false, true, false), HomeLogin.noFileWhy(true, false, false), HomeLogin.noFileWhy(true, true, true),
                HomeLogin.noFileWhy(true, true, false))) {
            assertEquals(w, StartupDiag.sanitize(w, emptyList()))
            assertEquals(w, Redact.text(w, pattern))
            assertFalse(w, Regex("[@=]").containsMatchIn(w))
        }
        val native = File(src, "NativeLogin.kt").readText()
        assertTrue("HomeLogin.noFileWhy(HomeLoginStore.has(ctx), HomeLoginStore.autoLogin(ctx), SetupBridge.seeding)" in native)
    }

    @Test fun theMenusLoginStepsAreInTheSignInLog() {
        // the verify pass (#399: a login saved at 18:02 was gone by the next start, and nothing in the log said how): the form's save,
        // the Menu's Auto login and Forget login are logged - their names only, never anything of the login
        val host = File(src, "CompanionHost.kt").readText()
        val words = listOf("sign-in: login saved (the login form)", "sign-in: Auto login on (the Menu)", "sign-in: Auto login off (the Menu)",
            "sign-in: the saved login forgotten (the Menu)")
        assertTrue("NativeLogin.logEvent(\"sign-in: login saved (the login form)\")" in host)
        assertTrue("NativeLogin.logEvent(\"sign-in: Auto login \${if (on) \"on\" else \"off\"} (the Menu)\")" in host)
        assertTrue("NativeLogin.logEvent(\"sign-in: the saved login forgotten (the Menu)\")" in host)
        // each in its own handler: the save in the form's callback, the toggle and the forget in the Menu's
        assertTrue(host.substringAfter("StartMenu.onAutoLogin = {").substringBefore("\n").contains("Auto login"))
        assertTrue(host.substringAfter("StartMenu.onForgetLogin = {").substringBefore("\n").contains("forgotten"))
        assertTrue(host.substringAfter("loginPrompt = HomeLoginPrompt(this) { login ->").substringBefore("SetupBridge.loginSaved()")
            .contains("login saved (the login form)"))
        val pattern = Redact.namePattern(emptyList())
        for (w in words) {
            assertEquals(w, StartupDiag.sanitize(w, emptyList()))
            assertEquals(w, Redact.text(w, pattern))
        }
    }
}
