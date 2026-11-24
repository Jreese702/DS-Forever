package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BATTLE.NET SIGN-IN ON THE MENU, AND THE ADVICE (the user, 2026-11-24: "Maybe we add a battlenet sign in setting to the menu and we can
 * advise people with other login errors to try it?"; earlier the same day "Offer, never switch by itself (Recommended)"), off-device:
 * the Menu's button and Settings' row are one setting with the same words, the advice comes after which refusals (and never when
 * Blizzard's page is already the way), the Restart game line while the running game signed in the other way, and nothing restarts or
 * switches by itself. The Menu's drawing and touch / pad are SignInMenuRoomTest's (app module).
 */
class SignInMenuTest {

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private fun read(name: String) = File(src, name).readText().replace("\r\n", "\n")

    @After fun forget() = BrowserLogin.forgetLaunchForTest()

    // ---- one setting, the same words

    @Test fun theSettingsRowAndTheMenuReadTheSame() {
        assertEquals("Battle.net sign-in", BrowserLogin.ROW_ID)
        assertEquals(listOf("Saved login", "Blizzard's page"), BrowserLogin.SEGMENTS)
        assertEquals("Saved login", BrowserLogin.words(false))
        assertEquals("Blizzard's page", BrowserLogin.words(true))
        assertEquals("the key is kept: a choice made before stays", "browserLogin", BrowserLogin.KEY)
        // Settings' row: its segments are these words, lit and written through Settings.browserLogin
        val tiles = read("GeneralTiles.kt")
        assertTrue("GeneralTiles.Row.BROWSER_LOGIN -> seg(c, cs, BrowserLogin.SEGMENTS, if (Settings.browserLogin) 1 else 0)" in tiles)
        assertTrue("out += Control(r, b, { Settings.browserLogin = k == 1 }) }" in tiles)
        // the Menu's button: the same name and value, read from and written to the same Settings.browserLogin
        val menu = read("StartMenu.kt")
        assertTrue("line(Tr.t(BrowserLogin.ROW_ID)" in menu)
        assertTrue("line(Tr.t(BrowserLogin.words(Settings.browserLogin))" in menu)
        val press = menu.substringAfter("SIGNIN -> {").substringBefore("\n")
        assertTrue(press, "Settings.browserLogin = !Settings.browserLogin; onSignIn?.invoke(Settings.browserLogin)" in press)
        // the Menu's press never restarts, never shuts the Menu (as ABXY and Glyphs): a restart is the Menu's own Restart game
        assertFalse(press, "estart" in press || "open = false" in press)
        val host = read("CompanionHost.kt")
        val hook = host.substringAfter("StartMenu.onSignIn = { on ->").substringBefore("\n        }\n")
        assertFalse("the host's hook restarts nothing: $hook", "estart" in hook || "browserLogin =" in hook)
        assertTrue(hook, "loginShow()" in hook)
    }

    @Test fun theMenuKeepsExitGameAndItsRegion() {
        val menu = read("StartMenu.kt")
        val row = menu.substringAfter("// the bottom row: Region and the Battle.net sign-in").substringBefore("\n    }\n")
        // both menus: Region, then the sign-in; the waiting screen: Exit game after them, unchanged in what it does
        assertTrue(row, "put(if (game) third(0) else half(false), rWord, REGION, true)" in row)
        assertTrue(row, "val sign = if (game) third(1) else half(true)" in row && "hits += sign to SIGNIN" in row)
        assertTrue(row, "if (game) put(third(2), \"Exit game\", EXIT, canExit())" in row)
        assertTrue("EXIT -> { open = false; onExit?.invoke() }" in menu)
        assertEquals(18, StartMenu.SIGNIN)
        // the chooser's pad: right from Region is the sign-in beside it (same line), not Glyphs up and to the right of Region
        val places = listOf(Triple(StartMenu.SIGNIN, 872f, 803f), Triple(14, 420f, 654f), Triple(13, 420f, 525f))
        assertEquals(StartMenu.SIGNIN, StartMenu.nearest(places, 340f, 803f, 1, 0))
        assertEquals("up from the sign-in: off to the side as before", 14, StartMenu.nearest(places, 872f, 803f, 0, -1))
        assertTrue("no clash with the region list's rows", StartMenu.SIGNIN < StartMenu.ROW0)
    }

    // ---- which refusals get the advice

    @Test fun otherRefusalsGetTheAdvice() {
        fun advised(raw: String, captcha: Boolean = false) =
            BrowserLogin.advise(failed = true, reason = HomeLogin.reason(raw), browserOn = false, restarting = false, captcha = captcha, saved = true)
        assertTrue("Unable to connect (BLZ51901016)", advised("Unable to connect. (BLZ51901016)"))
        assertTrue("a near-miss of 14400", advised("Status has no name. (14401)"))
        assertTrue("a time-out with NO captcha", advised("(325)"))
        assertTrue("a Battle.net ERROR_* code", advised("ERROR_DENIED (3)"))
        assertTrue(advised("ERROR_TIMED_OUT (2)"))
        assertTrue(advised("ERROR_INTERNAL (1)"))
        assertTrue("an unknown refusal", advised("Something went wrong. (BLZ51903003)"))
    }

    @Test fun theOffersAndTheWrongLoginGetNoAdvice() {
        fun advised(raw: String, captcha: Boolean = false) =
            BrowserLogin.advise(true, HomeLogin.reason(raw), false, false, captcha, true)
        // the two that keep their button (BrowserLogin.offer)
        assertFalse("BLZ51914400 has the button", advised("Status has no name. (14400)"))
        assertFalse(advised("BLZ51914400"))
        assertFalse("the captcha's 325 has the button", advised("(325)", captcha = true))
        // the login itself is wrong: the fix is the saved login (Change login) or the right code, not the other sign-in way
        assertFalse("14500: a wrong email or password (measured 2026-10-05)", advised("WOW_ERROR (14500)"))
        assertFalse(advised("(14500)"))
        assertFalse(advised("Incorrect password"))
        assertFalse(advised("ERROR_BAD_CREDENTIALS (12)"))
        assertFalse(advised("ERROR_INVALID_AUTHENTICATOR (40)"))
        // the account cannot play: no sign-in way helps
        for (r in listOf("ERROR_GAME_ACCOUNT_BANNED (3003)", "ERROR_GAME_ACCOUNT_SUSPENDED (3004)", "ERROR_ACCOUNT_LOCKED (3005)",
                "ERROR_GAME_ACCOUNT_CANCELLED (3006)", "ERROR_PARENTAL_CONTROL_RESTRICTION (3007)", "ERROR_NO_GAME_ACCOUNT (3002)",
                "ERROR_LICENSE_NOT_FOUND (3010)", "Subscription expired"))
            assertFalse(r, advised(r))
        // no refusal, no reason, a restart under way, no saved login (the first-time Save row keeps the place)
        assertFalse(BrowserLogin.advise(false, "ERROR_DENIED (3)", false, false))
        assertFalse(BrowserLogin.advise(true, null, false, false))
        assertFalse(BrowserLogin.advise(true, "  ", false, false))
        assertFalse(BrowserLogin.advise(true, "ERROR_DENIED (3)", false, restarting = true))
        assertFalse(BrowserLogin.advise(true, "ERROR_DENIED (3)", false, false, saved = false))
        assertEquals("Sign-in failed: Unable to connect (BLZ51901016)", BrowserLogin.failedLine(HomeLogin.reason("Unable to connect. (BLZ51901016)")))
    }

    @Test fun neverWhenBlizzardsPageIsOn() {
        for (r in listOf("Unable to connect (BLZ51901016)", "(325)", "ERROR_DENIED (3)", "Status has no name (14401)")) {
            assertFalse(r, BrowserLogin.advise(true, r, browserOn = true, restarting = false))
            // chosen and this run's way: no line at all
            BrowserLogin.markLaunched(true)
            assertNull(r, BrowserLogin.startHint(true, r, false, browserOn = true, restarting = false, saved = true,
                gameUp = true, launched = BrowserLogin.launched, chosen = true))
            BrowserLogin.forgetLaunchForTest()
            // chosen in the Menu while this run used the saved login: the Restart game line, never the advice
            assertEquals(r, BrowserLogin.RESTART_PAGE, BrowserLogin.startHint(true, r, false, browserOn = true, restarting = false,
                saved = true, gameUp = true, launched = false, chosen = true))
        }
    }

    // ---- the Restart game line (needsRestart), nothing restarts by itself

    @Test fun theRestartLineFollowsTheRunningGame() {
        fun hint(launched: Boolean?, chosen: Boolean, gameUp: Boolean = true, restarting: Boolean = false, failed: Boolean = false) =
            BrowserLogin.startHint(failed, "ERROR_DENIED (3)", false, chosen, restarting, true, gameUp, launched, chosen)
        assertNull("no game started in this run", hint(null, true))
        assertNull("the saved login, as the game started", hint(false, false))
        assertEquals("Blizzard's page chosen in the Menu", BrowserLogin.RESTART_PAGE, hint(false, true))
        assertEquals("back to the saved login while the game runs Blizzard's", BrowserLogin.RESTART_SAVED, hint(true, false))
        assertNull("no game up", hint(false, true, gameUp = false))
        assertNull("a restart under way", hint(false, true, restarting = true))
        // the restart line comes first; without it, the advice (saved login chosen and used, the game refused)
        assertEquals(BrowserLogin.ADVICE_LINE, hint(false, false, failed = true))
        assertEquals(BrowserLogin.RESTART_SAVED, hint(true, false, failed = true))
        assertTrue(BrowserLogin.needsRestart(false, true) && !BrowserLogin.needsRestart(false, false))
        assertEquals("Try Battle.net sign-in: Blizzard's page (Menu)", BrowserLogin.ADVICE_LINE)
    }

    // ---- the start screen's wiring

    @Test fun theStartScreenShowsTheErrorAndTheAdviceUnderIt() {
        val host = read("CompanionHost.kt")
        val row = host.substringAfter("private fun nativeRow(").substringBefore("private var loginRevealed")
        val offer = row.indexOf("BrowserLogin.offer(")
        val advise = row.indexOf("BrowserLogin.advise(")
        // after the button's two refusals, before the past-login / browser / first-time rows (as the offer: issue 237)
        assertTrue(offer in 1 until advise)
        assertTrue(advise < row.indexOf("NativeLogin.pastLogin -> null") && advise < row.indexOf("StartView.Login.FIRST"))
        assertTrue("the error line, no button", "HomeLoginStore.has(this)) -> StartView.Login.NONE to BrowserLogin.failedLine(s.reason)" in row)
        assertTrue("never while Blizzard's page is chosen or this run's way",
            "BrowserLogin.advise(s.phase == HomeLogin.Phase.FAILED, s.reason, Settings.browserLogin || NativeLogin.browserMode," in row)
        // the hint under it: computed on every loginShow, the one app only, the same advice test and the restart's
        val show = host.substringAfter("private fun loginShow() {").substringBefore("val native =")
        assertTrue(show, "signInHint(s)" in show)
        val hint = host.substringAfter("private fun signInHint(s: HomeLogin.State) {").substringBefore("\n    }\n")
        assertTrue(hint, "LauncherHooks.pkg == null || reloadAt != 0L || s.realm) null else BrowserLogin.startHint(" in hint)
        assertTrue(hint, "browserOn = Settings.browserLogin || NativeLogin.browserMode" in hint && "launched = BrowserLogin.launched" in hint)
        assertTrue(hint, "saved = HomeLoginStore.has(this)" in hint)
        // StartView draws it under the line in the line's own panel; the add-ons' card wins
        val view = read("StartView.kt")
        assertTrue("if (card != null) drawCard(c, panel, card)\n        else if (under != null) drawHint(c, panel, under)" in view)
    }

    @Test fun theWordsAreTranslated() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }.first { it.isDirectory }
        val words = listOf(BrowserLogin.ROW_ID) + BrowserLogin.SEGMENTS +
            listOf(BrowserLogin.ADVICE_LINE, BrowserLogin.RESTART_PAGE, BrowserLogin.RESTART_SAVED, GeneralTiles.REMEMBERED)
        for (l in listOf("es", "fr")) {
            val raw = File(dir, "$l.json").readText()
            assertEquals("$l.json keeps CRLF", raw.count { it == '\n' }, Regex("\r\n").findAll(raw).count())
            val o = org.json.JSONObject(raw)
            val plain = o.getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w) && plain.getString(w) != w)
            assertTrue("$l: the error line's template", o.getJSONObject("templates").has("Sign-in failed: %s"))
            assertFalse("$l: the old Skip words go", plain.has("Skip (Browser login)"))
        }
        assertEquals("Skip (Blizzard's page)", GeneralTiles.REMEMBERED)
    }
}
