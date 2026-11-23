package com.jrees.wowcompanion

/**
 * BROWSER LOGIN, THE SETTING (the user, 2026-11-18: port upstream WoW Foreverywhere's "Browser login", jaredgei/wow-forever-android v2.4.5,
 * OPT-IN). Settings > General > Account > Browser login: Off (the default: the saved login's private login.txt, as before) or On (the
 * game signs in with a Battle.net session from Blizzard's own sign-in page, shown on the BOTTOM screen; no login.txt is ever written).
 * It is also offered when the game's sign-in is refused with BLZ51914400 ([offer]): one button on the start screen, which turns it On and
 * restarts the game. Issue 569: also when a captcha ran out of time (the game's refusal 325 right after a captcha): Blizzard's own page
 * takes the captcha by touch on the bottom screen.
 *
 * The launcher (app.gamenative.ui.screen.wow.auth.BattleNetBrowserLogin) reads [Settings.browserLogin] at each game start and says which
 * way that start signed in ([markLaunched]): the Settings row offers "Restart the game" while the running game started the other way.
 * Nothing of an account, a ticket, a token or an address ever passes through here. Pure, but for the volatile fields the launcher sets.
 */
object BrowserLogin {
    /** the saved choice's key (Settings' prefs) */
    const val KEY = "browserLogin"

    /** the Settings row's own words (SettingsTabs, GeneralTiles) */
    const val ROW_ID = "Browser login"
    val SEGMENTS = listOf("Off", "On")

    /** the start screen's one button after a BLZ51914400 refusal, and the line over it */
    const val OFFER_BUTTON = "Sign in with Blizzard's page"
    const val OFFER_LINE = "Sign-in refused (BLZ51914400)"

    /** issue 569: the line over the button after a captcha ran out of time */
    const val CAPTCHA_LINE = "Captcha timed out (325)"

    /** the game's own result code for BLZ51914400 ("Status has no name. (14400)") */
    const val BLZ_14400 = "14400"

    /** the game's result code when the sign-in timed out (issue 569: exactly 60 s after the captcha came) */
    const val TIMEOUT_325 = "325"

    /**
     * the start screen offers [OFFER_BUTTON]: the game refused this run's sign-in with BLZ51914400, or with 325 after its form asked for
     * a captcha ([captcha], HomeLogin.State.captcha: the captcha ran out of time) ([failed], [reason] = HomeLogin's short reason), browser
     * login is Off, and no restart is already under way. Pure.
     */
    fun offer(failed: Boolean, reason: String?, browserOn: Boolean, restarting: Boolean, captcha: Boolean = false): Boolean =
        failed && !browserOn && !restarting && (isBlz14400(reason) || (captcha && isTimeout325(reason)))

    /** the line over the offer's button: the captcha's when it ran out of time, else BLZ51914400's. Pure. */
    fun offerLine(reason: String?, captcha: Boolean): String =
        if (!isBlz14400(reason) && captcha && isTimeout325(reason)) CAPTCHA_LINE else OFFER_LINE

    /** [reason] is the game's 325 ("(325)", as HomeLogin shortens it) */
    fun isTimeout325(reason: String?): Boolean {
        val r = reason ?: return false
        return Regex("""\(\s*$TIMEOUT_325\s*\)""").containsMatchIn(r)
    }

    /** [reason] is the game's BLZ51914400 ("... (14400)", or the BLZ code itself) */
    fun isBlz14400(reason: String?): Boolean {
        val r = reason ?: return false
        return "BLZ51914400" in r || Regex("""\(\s*$BLZ_14400\s*\)""").containsMatchIn(r)
    }

    /** the saved login for a login.txt ([saved]), or null WITHOUT asking it in browser login ([browser]): NativeLogin's one gate. Pure. */
    fun <T> loginFor(browser: Boolean, saved: () -> T?): T? = if (browser) null else saved()

    // ---- what the running game started with (the launcher, at each game start)

    /** the running game's start used browser login (true), the saved login (false); null = no game started in this process */
    @Volatile var launched: Boolean? = null
        private set

    private val listeners = ArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    private fun changed() { for (l in synchronized(listeners) { listeners.toList() }) runCatching { l() } }

    /** the launcher: this game start signed in the browser way ([browser]) or the saved-login way */
    fun markLaunched(browser: Boolean) { launched = browser; changed() }

    /** the off-device tests' way back to "no game started in this process" */
    fun forgetLaunchForTest() { launched = null }

    /** a restart would change the way the running game signs in */
    fun needsRestart(launched: Boolean?, chosen: Boolean): Boolean = launched != null && launched != chosen

    /** the "Restart the game" row under Browser login: the game runs, it started the other way, and no restart is under way */
    fun showRestart(gameUp: Boolean, restarting: Boolean, launched: Boolean?, chosen: Boolean): Boolean =
        gameUp && !restarting && needsRestart(launched, chosen)

    /** the problem report's line: the choice and what this run's start used (states only) */
    fun reportLine(chosen: Boolean, launched: Boolean?): String =
        "Browser login: ${if (chosen) "On" else "Off"} chosen; " + when (launched) {
            null -> "no game start in this run"
            true -> "this run's game start used it" + if (!chosen) "; a restart is due" else ""
            false -> "this run's game start used the saved login" + if (chosen) "; a restart is due" else ""
        }
}
