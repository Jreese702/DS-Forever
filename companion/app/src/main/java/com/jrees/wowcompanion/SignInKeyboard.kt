package com.jrees.wowcompanion

/**
 * THE AUTHENTICATOR'S KEYBOARD (board p-auth-keyboard; a player's GitHub issue on 1.0.5: "It instant opens then closes"). The Thor's
 * IMM log (tracking, 2026-10-14, a real authenticator): every "Keyboard" press made TWO calls 300 ms apart, an explicit show that OPENED
 * it and a toggle that CLOSED it again. Now "Keyboard" is SHOW only: the toggle is a fallback for when Android says the keyboard is
 * still hidden, or says nothing ([needToggle]). And "Sign-in didn't work" no longer flashes for the 12-13 s before the game's log
 * says it wants the code ([shown]). Pure: the off-device tests drive both.
 */
object SignInKeyboard {
    // InputMethodManager's showSoftInput results (the ResultReceiver codes)
    const val RESULT_UNCHANGED_SHOWN = 0
    const val RESULT_UNCHANGED_HIDDEN = 1
    const val RESULT_SHOWN = 2
    const val RESULT_HIDDEN = 3

    /** how long the show's answer is waited for before the toggle fallback */
    const val RESULT_WAIT_MS = 600L

    /** after an explicit show: toggle only when it stayed hidden, or no answer came (null) */
    fun needToggle(result: Int?): Boolean = result == null || result == RESULT_UNCHANGED_HIDDEN || result == RESULT_HIDDEN

    /**
     * The Thor with a real authenticator (the user, 2026-10-14): SIGNING at +0 s, the traffic's FAILED at +13 s, the log's
     * authenticatorRequired only at +38-45 s (General.log AND Connection.log: the game writes both late). So the traffic's moment is
     * the one to act on: until [UNSURE_MS] after the login was read, a FAILED only the traffic says is [SetupFlow.SignIn.CHECK] (the
     * keyboard opens by itself, "Type your authenticator code (or password) on the game screen", Keyboard + Update login); the log's
     * AUTH or refusal replaces it as soon as it is read, and "Sign-in didn't work" only after [UNSURE_MS] with neither.
     */
    const val UNSURE_MS = 60_000L

    /** the keyboard opens by itself on entering [now] from [before] (show only: a second open never closes it) */
    fun autoOpen(before: SetupFlow.SignIn, now: SetupFlow.SignIn): Boolean =
        now != before && (now == SetupFlow.SignIn.CHECK || now == SetupFlow.SignIn.AUTH)

    /** what the bottom screen shows for the sign-in check's [raw] answer ([readAt] = when the game read the login, epoch ms; 0 = not yet) */
    fun shown(raw: SetupFlow.SignIn, readAt: Long, now: Long, logSaysFailed: Boolean): SetupFlow.SignIn =
        if (raw == SetupFlow.SignIn.FAILED && !logSaysFailed && readAt > 0 && now - readAt < UNSURE_MS) SetupFlow.SignIn.CHECK
        else raw

    /** the pop-ups' line (the user's pick: "Hint now + block A") */
    const val HINT = "Type with Keyboard; don't press A."

    /**
     * The controller's A does nothing for the game while the code / password pop-up shows (the user's pick; on the Thor A "selects the
     * username behind the authenticator pop up": the game's gamepad cursor stays on the field behind the dialog). Only with the game up,
     * and not while our own login form is open (Update login); it comes back the moment the pop-up goes. B is untouched.
     */
    fun blocksA(signIn: SetupFlow.SignIn, gameUp: Boolean, loginFormOpen: Boolean): Boolean =
        gameUp && !loginFormOpen && (signIn == SetupFlow.SignIn.CHECK || signIn == SetupFlow.SignIn.AUTH)

    /** the simulation file (adb only, no exported entry point): while it exists and the game is up, the sign-in reads AUTH */
    const val SIMULATE_AUTH_FILE = "simulate_auth"
}
