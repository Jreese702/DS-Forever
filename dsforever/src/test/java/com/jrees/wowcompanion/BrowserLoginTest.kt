package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BROWSER LOGIN, the companion's side (the user, 2026-11-18: upstream v2.4.5's Browser login, OPT-IN; offered after BLZ51914400;
 * Settings > General > Account > "Browser login"), off-device: the BLZ51914400 offer, the Settings row and its restart, the report line,
 * and that in browser mode no login.txt is ever written and the sign-in watchers stand down while Blizzard's page is up.
 * Nothing of an account, ticket or token here.
 */
class BrowserLoginTest {

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private fun read(name: String) = File(src, name).readText().replace("\r\n", "\n")

    @After fun forget() = BrowserLogin.forgetLaunchForTest()

    // ---- the BLZ51914400 offer

    @Test fun offeredOnlyAfterA14400Refusal() {
        val reason = HomeLogin.reason("Status has no name. (14400)")          // the game's own words, as HomeLogin shortens them
        assertTrue(BrowserLogin.offer(failed = true, reason = reason, browserOn = false, restarting = false))
        assertTrue(BrowserLogin.isBlz14400("BLZ51914400"))
        // another refusal, no refusal, Browser login already on, or a restart under way: no offer
        assertFalse(BrowserLogin.offer(true, HomeLogin.reason("Unable to connect. (BLZ51901016)"), false, false))
        assertFalse(BrowserLogin.offer(true, "Status has no name (14401)", false, false))
        assertFalse(BrowserLogin.offer(false, reason, false, false))
        assertFalse(BrowserLogin.offer(true, reason, browserOn = true, restarting = false))
        assertFalse(BrowserLogin.offer(true, reason, browserOn = false, restarting = true))
        assertFalse(BrowserLogin.offer(true, null, false, false))
    }

    @Test fun theOfferRowIsWiredBeforeTheSavedLoginRows() {
        val host = read("CompanionHost.kt")
        val row = host.substringAfter("private fun nativeRow(").substringBefore("private var loginRevealed")
        val offer = row.indexOf("BrowserLogin.offer(")
        // issue 569: the line is BLZ51914400's or the captcha timeout's (BrowserLogin.offerLine)
        assertTrue("the offer row", offer > 0 && "StartView.Login.BROWSER to BrowserLogin.offerLine(s.reason, s.captcha)" in row)
        assertTrue("before the first-time row", offer < row.indexOf("StartView.Login.FIRST"))
        assertTrue("browser login: no saved-login rows", row.indexOf("NativeLogin.browserMode -> null") in offer until row.indexOf("StartView.Login.FIRST"))
        // the button turns Browser login on and restarts the game through the launcher's own restart
        val press = host.substringAfter("startView.onBrowserLogin = {").substringBefore("\n        }\n")
        assertTrue("Settings.browserLogin = true" in press)
        assertTrue("SetupBridge.restartGame" in press && "restart()" in press)
        // the row itself is touch only (the pad's A stays the game's at its login screen)
        val view = read("StartView.kt")
        assertTrue("Login.NONE, Login.BROWSER -> {}" in view)
        assertTrue("CURSOR_BROWSER -> onBrowserLogin?.invoke()" in view)
    }

    // ---- the Settings row

    @Test fun theSettingsRowAndItsRestart() {
        assertEquals("Browser login", BrowserLogin.ROW_ID)
        assertEquals(listOf("Off", "On"), BrowserLogin.SEGMENTS)
        assertFalse("no game started: nothing to restart", BrowserLogin.showRestart(true, false, null, true))
        BrowserLogin.markLaunched(false)
        assertTrue("the running game used the saved login, On chosen", BrowserLogin.showRestart(true, false, BrowserLogin.launched, true))
        assertFalse(BrowserLogin.showRestart(true, false, BrowserLogin.launched, false))
        assertFalse("a restart under way", BrowserLogin.showRestart(true, true, BrowserLogin.launched, true))
        assertFalse("no game", BrowserLogin.showRestart(false, false, BrowserLogin.launched, true))
        assertTrue(GeneralTiles.Row.BROWSER_LOGIN in GeneralTiles.rows(SettingsTabs.Group.ACCOUNT, false, false))
        assertTrue(SettingsTabs.Block.BROWSER_LOGIN.group == SettingsTabs.Group.ACCOUNT)
        // the default is Off (the saved login): Settings' getter falls back to false
        assertTrue("prefs?.getBoolean(BrowserLogin.KEY, false) ?: false" in read("Settings.kt"))
    }

    @Test fun theReportSaysStatesOnly() {
        assertEquals("Browser login: Off chosen; no game start in this run", BrowserLogin.reportLine(false, null))
        assertEquals("Browser login: On chosen; this run's game start used it", BrowserLogin.reportLine(true, true))
        assertEquals("Browser login: On chosen; this run's game start used the saved login; a restart is due", BrowserLogin.reportLine(true, false))
        assertTrue("BrowserLogin.reportLine(" in read("StartupDiag.kt"))
    }

    // ---- login.txt never written in browser mode

    @Test fun theSavedLoginIsNeverAskedInBrowserMode() {
        assertNull(BrowserLogin.loginFor(true) { error("the saved login must not even be opened in browser login") })
        assertEquals("saved", BrowserLogin.loginFor(false) { "saved" })
        val native = read("NativeLogin.kt")
        // the store is read for a login.txt in ONE place, behind the gate; every write (Play, the game's window, the re-login) uses it
        assertEquals(1, Regex("""HomeLoginStore\.forAutoLogin\(""").findAll(native).count())
        assertTrue("BrowserLogin.loginFor(browserMode) { HomeLoginStore.forAutoLogin(ctx) }" in native)
        val writes = Regex("""f\.writeText\("\$\{login\.email}""").findAll(native).count()
        assertEquals("Play, the game's window, the re-login", 3, writes)
        assertEquals("each write's login comes through the gate", 3, Regex("""val login = savedFor\(ctx\)""").findAll(native).count())
        // Play latches the mode before the login is read, and a stray file in the shadow goes
        val prepare = native.substringAfter("fun prepare(ctx: Context, gameRoot: File, flavor: String, browser: Boolean = false)")
        assertTrue(prepare.indexOf("browserMode = browser") < prepare.indexOf("val login = savedFor(ctx)"))
        assertTrue("if (browser) File(fl, FILE).let" in prepare.substringBefore("fun clear("))
    }

    // ---- the watchers stand down while the page is up

    @Test fun theTrafficWatchersStandDownWhileThePageIsUp() {
        val native = read("NativeLogin.kt")
        val onTraffic = native.substringAfter("fun onTraffic(rxBytes: Long, txBytes: Long = -1L) {").trimStart()
        assertTrue("the first thing: no sign-in, download or past-the-login-screen from the page's traffic",
            onTraffic.substringAfter("\n").trimStart().startsWith("if (browserHold) return"))
        // the hold starts at Play in browser mode and ends when the helper started the game (or the attempt stopped)
        assertTrue("browserMode = browser; browserHold = browser; holdEndSec = 0L" in native)
        val started = native.substringAfter("fun browserGameStarted() {").substringBefore("\n    }")
        assertTrue("holdEndSec = System.currentTimeMillis() / 1000" in started && "browserHold = false" in started)
        assertTrue("browserStopped(\"the game screen went\")" in native.substringAfter("fun end(ctx: Context? = null) {"))
    }

    @Test fun theNoFileGraceCountsFromTheGamesRealStart() {
        // the game screen up at 1000, the helper started the game at 1300 (the page took 5 minutes): the grace counts from 1300
        assertEquals(20L, PreLoginTraffic.sinceStartS(1320, 1000, 1300))
        assertEquals("the saved-login way: from the game screen", 320L, PreLoginTraffic.sinceStartS(1320, 1000, 0))
        assertEquals("no game screen yet", -1L, PreLoginTraffic.sinceStartS(1320, 0, 0))
        assertTrue("NO_FILE_GRACE from the helper's start in browser mode", "if (browserMode) holdEndSec else 0L" in read("NativeLogin.kt"))
    }
}
