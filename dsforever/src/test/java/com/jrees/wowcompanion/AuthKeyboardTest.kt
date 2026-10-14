package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** p-auth-keyboard (a player's issue on 1.0.5; the Thor's IMM log with a real authenticator), off-device */
class AuthKeyboardTest {

    /** the system keyboard as the Thor behaved: an explicit show opens it (and answers), a toggle flips it */
    private class Ime {
        var open = false
        fun show(): Int = if (open) SignInKeyboard.RESULT_UNCHANGED_SHOWN else { open = true; SignInKeyboard.RESULT_SHOWN }
        fun toggle() { open = !open }
    }

    /** DS Forever's "Keyboard" now: show, and toggle only when Android says it stayed hidden (or says nothing) */
    private fun keyboardNow(ime: Ime, answer: (Int) -> Int? = { it }) {
        val r = answer(ime.show())
        if (SignInKeyboard.needToggle(r)) ime.toggle()
    }

    @Test fun autoOpenThenTapLeavesItOpen() {
        val ime = Ime()
        keyboardNow(ime)                 // AUTH entry: the auto-open
        assertTrue(ime.open)
        keyboardNow(ime)                 // the player's tap on "Keyboard"
        assertTrue(ime.open)
        keyboardNow(ime)                 // and again
        assertTrue(ime.open)
    }

    @Test fun theNeutralPopUpThenAuthLeavesItOpen() {
        val ime = Ime()
        keyboardNow(ime)                 // "Check the game screen" > Keyboard
        keyboardNow(ime)                 // the log's AUTH arrives: the auto-open
        assertTrue(ime.open)
    }

    @Test fun theOldShowPlusToggleClosedIt() {   // what the Thor logged: "instantly opens then closes"
        val ime = Ime()
        ime.show(); ime.toggle()
        assertFalse(ime.open)
    }

    @Test fun theToggleIsOnlyAFallback() {
        assertFalse(SignInKeyboard.needToggle(SignInKeyboard.RESULT_SHOWN))
        assertFalse(SignInKeyboard.needToggle(SignInKeyboard.RESULT_UNCHANGED_SHOWN))
        assertTrue(SignInKeyboard.needToggle(SignInKeyboard.RESULT_UNCHANGED_HIDDEN))
        assertTrue(SignInKeyboard.needToggle(SignInKeyboard.RESULT_HIDDEN))
        assertTrue(SignInKeyboard.needToggle(null))           // no answer within the wait: test9's device
    }

    // ---- what the bottom screen shows before the log says why

    private val read = 1_000_000L

    @Test fun aTrafficFailureIsNeutralUntilTheLogCanSay() {
        assertEquals(SetupFlow.SignIn.CHECK, SignInKeyboard.shown(SetupFlow.SignIn.FAILED, read, read + 13_000, logSaysFailed = false))
        assertEquals(SetupFlow.SignIn.CHECK, SignInKeyboard.shown(SetupFlow.SignIn.FAILED, read, read + 45_000, logSaysFailed = false))  // the Thor's AUTH
        assertEquals(SetupFlow.SignIn.FAILED, SignInKeyboard.shown(SetupFlow.SignIn.FAILED, read, read + 60_000, logSaysFailed = false))
        assertEquals(SetupFlow.SignIn.FAILED, SignInKeyboard.shown(SetupFlow.SignIn.FAILED, read, read + 5_000, logSaysFailed = true))
        assertEquals(SetupFlow.SignIn.AUTH, SignInKeyboard.shown(SetupFlow.SignIn.AUTH, read, read + 13_000, logSaysFailed = false))
        assertEquals(SetupFlow.SignIn.SIGNING, SignInKeyboard.shown(SetupFlow.SignIn.SIGNING, read, read + 1_000, logSaysFailed = false))
        assertEquals(SetupFlow.SignIn.FAILED, SignInKeyboard.shown(SetupFlow.SignIn.FAILED, 0L, read, logSaysFailed = false))  // never read
    }

    @Test fun theNeutralPopUpOffersKeyboardAndUpdateLogin() {
        val p = SetupFlow.screen(SetupFlow.Inputs(path = "/g", folderFound = true, loginSaved = true, gameRunning = true,
            signIn = SetupFlow.SignIn.CHECK)).prompt!!
        assertEquals("Type your authenticator code (or password) on the game screen", p.title)
        assertEquals(listOf(SetupFlow.Action.ENTER_ON_GAME, SetupFlow.Action.UPDATE_LOGIN), p.buttons.map { it.action })
        assertEquals("Keyboard", p.buttons.first().label)
    }

    @Test fun theKeyboardOpensAtTheTrafficsMomentAndStaysOpenAtAuth() {
        val S = SetupFlow.SignIn
        assertTrue(SignInKeyboard.autoOpen(S.SIGNING, S.CHECK))     // +13 s: open now
        assertTrue(SignInKeyboard.autoOpen(S.CHECK, S.AUTH))        // +38-45 s: open again (show only)
        assertFalse(SignInKeyboard.autoOpen(S.CHECK, S.CHECK))
        assertFalse(SignInKeyboard.autoOpen(S.AUTH, S.AUTH))
        assertFalse(SignInKeyboard.autoOpen(S.CHECK, S.FAILED))
        val ime = Ime()
        keyboardNow(ime)                 // CHECK's auto-open
        keyboardNow(ime)                 // the player's tap
        keyboardNow(ime)                 // AUTH's auto-open
        assertTrue(ime.open)
    }

    // ---- Connection.log: the authenticator line, with or without its tag

    @Test fun connectionLogLinesAreRead() {
        val tagged = "10/14 07:34:17.512  [N][Connection] Battle.net logon form submission response received result=\"\" authenticatorRequired=\"true\" x=1\r\n"
        val bare = "10/14 07:34:17.512  [N] Battle.net logon form submission response received result=\"\" authenticatorRequired=\"true\"\n"
        val noAuth = "10/14 07:34:17.512  [N] Battle.net logon form submission response received result=\"\" authenticatorRequired=\"false\"\n"
        assertEquals(listOf<HomeLogin.Event>(HomeLogin.Event.Authenticator), HomeLogin.connectionEvents(tagged))
        assertEquals(listOf<HomeLogin.Event>(HomeLogin.Event.Authenticator), HomeLogin.connectionEvents(bare))
        assertTrue(HomeLogin.connectionEvents(noAuth).isEmpty())
        assertTrue(HomeLogin.connectionEvents("10/14 07:34:10.000  [N][Connection] Glue login started\n").isEmpty())  // General.log's
    }
}
