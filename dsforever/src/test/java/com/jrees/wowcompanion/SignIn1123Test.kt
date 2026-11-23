package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sign-in fixes of 2026-11-23 (issues 585, 237, 569 and the report's "Login saved" line), off-device:
 *  - issue 585 (the user's ruling): a login saved from the start screen's form while the game waits at its own login screen restarts
 *    the game by itself, as Update login does, and asks first while the game downloads
 *  - issue 237: the Browser login offer after BLZ51914400 is asked before "past the login screen" (a download can set that)
 *  - issue 569: a captcha that ran out of time (325) offers Browser login too
 *  - the problem report's "Login saved" is the device's now, not the start-up screen's last word
 */
class SignIn1123Test {

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val i18n = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
        .map { File(it) }.first { it.isDirectory }
    private fun read(name: String) = File(src, name).readText().replace("\r\n", "\n")

    // ---- issue 585: the save restarts the waiting game

    /** the issue's own case: no saved login at Play, the game's window up at its login screen, nothing signed in */
    private fun waits(gameUp: Boolean = true, restarting: Boolean = false, autoLogin: Boolean = true, browser: Boolean = false,
                      realm: Boolean = false, signingIn: Boolean = false, refused: Boolean = false, read: Boolean = false,
                      pastLogin: Boolean = false, windowUp: Boolean = true) =
        HomeLogin.saveRestarts(gameUp, restarting, autoLogin, browser, realm, signingIn, refused, read, pastLogin, windowUp)

    @Test fun aSaveWhileTheGameWaitsAtItsLoginScreenRestarts() {
        assertTrue("issue 585: no login.txt this run, the login screen up", waits())
        assertTrue("a refused sign-in: the game is back at its login screen, whatever the traffic said", waits(refused = true, read = true, pastLogin = true))
        // never when the restart cannot sign in or would lose something
        assertFalse("no game", waits(gameUp = false))
        assertFalse("a restart already under way", waits(restarting = true))
        assertFalse("Auto login off: no login.txt is written", waits(autoLogin = false))
        assertFalse("Browser login: no login.txt is written", waits(browser = true))
        assertFalse("character select or later", waits(realm = true))
        assertFalse("character select or later, even after a refusal", waits(realm = true, refused = true))
        assertFalse("a sign-in under way", waits(signingIn = true))
        assertFalse("this run read a login.txt and was not refused: it may be at the authenticator", waits(read = true))
        assertFalse("past the login screen (the player signed in by hand)", waits(pastLogin = true))
        assertFalse("the window not up yet: its own write gives the game the new login", waits(windowUp = false))
    }

    @Test fun theFormsSaveRestartsLikeUpdateLogin() {
        val host = read("CompanionHost.kt")
        val save = host.substringAfter("loginPrompt = HomeLoginPrompt(this) { login ->").substringBefore("\n        setupPanel = SetupPanel(this)")
        // Update login / Change login as before, and the start screen's own save when the game waits (inside the one app only)
        assertTrue("val updating = restartOnSave && LauncherHooks.gameUp()" in save)
        assertTrue("LauncherHooks.pkg != null && loginSaveRestarts()" in save)
        assertTrue("both restart through the one path", "loginRestartOrAsk(if (updating) \"login updated\" else" in save)
        // the gate's inputs are the run's own
        val gate = host.substringAfter("private fun loginSaveRestarts(): Boolean {").substringBefore("\n    }\n")
        for (w in listOf("HomeLogin.saveRestarts(", "SetupBridge.restarting", "HomeLoginStore.autoLogin(this)", "Settings.browserLogin || NativeLogin.browserMode",
                "s.realm", "s.signingIn", "s.phase == HomeLogin.Phase.FAILED", "NativeLogin.firstReadAt > 0L", "NativeLogin.pastLogin", "NativeLogin.windowSeen"))
            assertTrue(w, w in gate)
        // a download asks first with #576's card; its Restart anyway restarts for the login, not Settings
        val ask = host.substringAfter("private fun loginRestartOrAsk(why: String) {").substringBefore("\n    }\n")
        assertTrue("ReportNotice.restartAsks(NativeLogin.downloadingNow, LauncherHooks.gameUp())" in ask)
        assertTrue("restartAskLogin = why" in ask && "noticeShow(ReportNotice.State.RestartDownloading)" in ask)
        assertTrue("else loginRestart(why)" in ask)
        val anyway = host.substringAfter("onRestartAnyway = {").substringBefore("\n                }")
        assertTrue("if (login != null) loginRestart(login) else restartFromSettings()" in anyway)
        // Settings' own card restarts Settings' way
        val settingsAsk = host.substringAfter("settingsRest.onRestart = {").substringBefore("restartFromSettings = {")
        assertTrue("restartAskLogin = null" in settingsAsk)
        // the restart itself: the sign-in check's RESTARTING, the restart's line, the launcher's restart
        val restart = host.substringAfter("private fun loginRestart(why: String) {").substringBefore("\n    }\n")
        assertTrue("signInCheck.restarting(" in restart && "restartNotice()" in restart && "SetupBridge.restartGame" in restart)
        // the note: no "next time" while it restarts now; kept while the card asks
        assertTrue("loginNote = if (restartNow && !asks) null else \"Saved. The game signs in by itself the next time it starts.\"" in save)
    }

    // ---- issue 237: the offer before "past the login screen"

    @Test fun theOfferIsAskedBeforePastTheLoginScreen() {
        val row = read("CompanionHost.kt").substringAfter("private fun nativeRow(").substringBefore("private var loginRevealed")
        val realm = row.indexOf("s.realm -> null")
        val offer = row.indexOf("BrowserLogin.offer(")
        val past = row.indexOf("NativeLogin.pastLogin -> null")
        assertTrue("a realm connection first (character select: no row)", realm in 0 until offer)
        assertTrue("issue 237: the offer before the traffic's past-the-login-screen", offer in 0 until past)
        assertFalse("pastLogin no longer shares the first branch", "s.realm || NativeLogin.pastLogin" in row)
    }

    // ---- issue 569: a captcha that ran out of time

    private val captchaRun = listOf(
        "11/9 09:29:50.397  [N][Lua] Switching to screen=\"AccountLogin\" (from \"none\")",
        "11/9 09:29:52.425  [N][Connection] Glue login started loginType=\"(enum)0\" launcherPortal=\"nullopt\" loginPortal=\"us.actual.battle.net:1119\"",
        "11/9 09:29:54.139  [N][Connection] Battle.net logon form received result=\"ERROR_OK (0)\" hasCaptcha=\"true\"",
        "11/9 09:30:54.014  [N][Connection] Fatal login error received result=\"(325)\"",
    )

    @Test fun theCaptchaIsReadFromTheForm() {
        assertEquals(HomeLogin.Event.Captcha, HomeLogin.parse(captchaRun[2]))
        assertEquals(null, HomeLogin.parse("11/9 09:29:54.139  [N][Connection] Battle.net logon form received result=\"ERROR_OK (0)\" hasCaptcha=\"false\""))
        // the submission's answer is still the authenticator's line, never a captcha
        assertEquals(HomeLogin.Event.Authenticator, HomeLogin.parse("11/9 09:30:40.848  [N][Connection] Battle.net logon form submission " +
            "response received result=\"ERROR_OK (0)\" authenticatorRequired=\"true\" legalRequired=\"false\""))
        // Connection.log carries it too
        assertEquals(listOf(HomeLogin.Event.Captcha), HomeLogin.connectionEvents("Battle.net logon form received result=\"ERROR_OK (0)\" hasCaptcha=\"true\"\n"))
    }

    @Test fun aCaptchaTimeoutOffersBrowserLogin() {
        val s = HomeLogin.State()
        for (l in captchaRun) HomeLogin.parse(l)?.let { s.note(it) }
        assertTrue(s.captcha)
        assertEquals(HomeLogin.Phase.FAILED, s.phase)
        assertEquals("(325)", s.reason)
        assertTrue(BrowserLogin.offer(true, s.reason, browserOn = false, restarting = false, captcha = s.captcha))
        assertEquals(BrowserLogin.CAPTCHA_LINE, BrowserLogin.offerLine(s.reason, s.captcha))
        assertEquals("Captcha timed out (325)", BrowserLogin.CAPTCHA_LINE)
        // 325 without a captcha in this sign-in, Browser login already on, a restart under way: no offer
        assertFalse(BrowserLogin.offer(true, "(325)", false, false, captcha = false))
        assertFalse(BrowserLogin.offer(true, "(325)", browserOn = true, restarting = false, captcha = true))
        assertFalse(BrowserLogin.offer(true, "(325)", browserOn = false, restarting = true, captcha = true))
        assertFalse("another code after a captcha", BrowserLogin.offer(true, "(3250)", false, false, captcha = true))
        // BLZ51914400 keeps its own line, captcha or not
        val blz = HomeLogin.reason("Status has no name. (14400)")
        assertTrue(BrowserLogin.offer(true, blz, false, false, captcha = true))
        assertEquals(BrowserLogin.OFFER_LINE, BrowserLogin.offerLine(blz, true))
        // a new sign-in starts without the captcha
        s.note(HomeLogin.parse(captchaRun[1])!!)
        assertFalse(s.captcha)
        s.note(HomeLogin.Event.Captcha); s.reset(); assertFalse(s.captcha)
    }

    @Test fun theOfferRowPassesTheCaptcha() {
        val row = read("CompanionHost.kt").substringAfter("private fun nativeRow(").substringBefore("private var loginRevealed")
        assertTrue("s.captcha) -> StartView.Login.BROWSER to BrowserLogin.offerLine(s.reason, s.captcha)" in row)
    }

    // ---- the report's "Login saved"

    @Test fun theReportsLoginSavedIsTheDevicesNow() {
        assertEquals("Login saved true (the start-up screen's last word: false)", StartupDiag.loginSavedWords(atStart = false, now = true))
        assertEquals("Login saved false", StartupDiag.loginSavedWords(atStart = false, now = false))
        assertEquals("Login saved true", StartupDiag.loginSavedWords(atStart = true, now = true))
        assertEquals("no answer from the store: the start-up screen's", "Login saved true", StartupDiag.loginSavedWords(atStart = true, now = null))
        val diag = read("StartupDiag.kt")
        assertTrue("startScreen(runCatching { HomeLoginStore.has(ctx) }.getOrNull())" in diag)
        assertTrue("\${loginSavedWords(i.loginSaved, savedNow)}, skipped" in diag)
    }

    // ---- the words in Spanish and French

    @Test fun theNewWordsAreTranslated() {
        for (lang in listOf("es", "fr")) {
            val text = File(i18n, "$lang.json").readText(Charsets.UTF_8)
            assertTrue("$lang: CRLF kept", "\r\n" in text)
            Tr.load(lang, text)
            assertTrue(lang, Tr.t(BrowserLogin.CAPTCHA_LINE, lang) != BrowserLogin.CAPTCHA_LINE)
        }
    }
}
