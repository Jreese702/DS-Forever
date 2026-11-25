package com.jrees.wowcompanion

/**
 * BROWSER LOGIN, THE SETTING (the user, 2026-11-18: port upstream WoW Foreverywhere's "Browser login", jaredgei/wow-forever-android v2.4.5,
 * OPT-IN). Settings > General > Account > Browser login: Off (the default: the saved login's private login.txt, as before) or On (the
 * game signs in with a Battle.net session from Blizzard's own sign-in page, shown on the BOTTOM screen; no login.txt is ever written).
 * It is also offered when the game's sign-in is refused with BLZ51914400 ([offer]): one button on the start screen, which turns it On and
 * restarts the game. Issue 569: also when a captcha ran out of time (the game's refusal 325 right after a captcha): Blizzard's own page
 * takes the captcha by touch on the bottom screen.
 *
 * THE MENU AND THE ADVICE (the user, 2026-11-24): the row is now "Battle.net sign-in: Saved login / Blizzard's page", the same setting
 * as the start screen Menu's button (StartMenu.SIGNIN). After any OTHER refusal it could fix ([advise]), the start screen says so in one
 * line under the error ([ADVICE_LINE]); never a button, never a switch by itself.
 *
 * The launcher (app.gamenative.ui.screen.wow.auth.BattleNetBrowserLogin) reads [Settings.browserLogin] at each game start and says which
 * way that start signed in ([markLaunched]): the Settings row offers "Restart the game" while the running game started the other way.
 * Nothing of an account, a ticket, a token or an address ever passes through here. Pure, but for the volatile fields the launcher sets.
 */
object BrowserLogin {
    /** the saved choice's key (Settings' prefs) */
    const val KEY = "browserLogin"

    /**
     * the Settings row's own words (SettingsTabs, GeneralTiles) AND the start screen Menu's button (StartMenu.SIGNIN): ONE saved setting,
     * read the same in both places (the user, 2026-11-24: "Maybe we add a battlenet sign in setting to the menu"). Off = "Saved login",
     * On = "Blizzard's page"; the key ([KEY]) is the old one, so a choice made before keeps
     */
    const val ROW_ID = "Battle.net sign-in"
    val SEGMENTS = listOf("Saved login", "Blizzard's page")

    /** a choice's words: [SEGMENTS] (English: the drawing translates) */
    fun words(browser: Boolean): String = SEGMENTS[if (browser) 1 else 0]

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

    // ---- the advice after another refusal (the user, 2026-11-24: "we can advise people with other login errors to try it"; picked
    // "Offer, never switch by itself"): one line under the error on the start screen, no button, nothing switched

    /** the line under the error: where the choice is (the start screen's Menu) */
    const val ADVICE_LINE = "Try Battle.net sign-in: Blizzard's page (Menu)"

    /** the line under the start screen's line while the running game started the other way ([showRestart]): the Menu's Restart game */
    const val RESTART_PAGE = "Restart game (Menu) to use Blizzard's page"
    const val RESTART_SAVED = "Restart game (Menu) to use the saved login"

    /** the game's result code for a wrong email or password (measured 2026-10-05 on the wrong-password path: "WOW_ERROR (14500)") */
    const val WRONG_LOGIN_14500 = "14500"

    /**
     * a refusal the other sign-in way cannot fix, so no advice: the login itself is wrong (14500, a password / credential / authenticator
     * word: the fix is Change login or the right code, not Blizzard's page), or the account cannot play (banned, suspended, locked,
     * cancelled, parental controls, no game account, licence or subscription words). Pure.
     */
    fun notTheWay(reason: String): Boolean {
        if (Regex("""\(\s*$WRONG_LOGIN_14500\s*\)""").containsMatchIn(reason)) return true
        val r = reason.lowercase()
        return NOT_THE_WAY.any { it in r }
    }
    private val NOT_THE_WAY = listOf("password", "credential", "authenticator", "incorrect", "banned", "suspend", "locked", "cancel",
        "parental", "no_game_account", "no game account", "license", "licence", "subscription")

    /**
     * the start screen advises Blizzard's page ([ADVICE_LINE] under [failedLine]): the game refused this run's sign-in ([failed]) for a
     * reason ([reason], HomeLogin's short one) other than the two that offer the button ([offer]: BLZ51914400, the captcha's 325) and
     * other than one the other way cannot fix ([notTheWay]); the choice is the saved login ([browserOn] = false: never while Blizzard's
     * page is chosen or this run uses it), a saved login exists ([saved]: else the first-time Save row keeps the place) and no restart is
     * under way. Pure.
     */
    fun advise(failed: Boolean, reason: String?, browserOn: Boolean, restarting: Boolean, captcha: Boolean = false, saved: Boolean = true): Boolean {
        if (!failed || browserOn || restarting || !saved) return false
        val r = reason?.takeIf { it.isNotBlank() } ?: return false
        return !offer(true, r, false, false, captcha) && !notTheWay(r)
    }

    /** the start screen's line for an advised refusal (the game's short reason; English: the drawing translates). Pure. */
    fun failedLine(reason: String): String = "Sign-in failed: $reason"

    /**
     * the one line under the start screen's line, or null: [RESTART_PAGE] / [RESTART_SAVED] while the running game started the other
     * way ([showRestart]: the Menu's Restart game is the way, nothing restarts by itself), else [ADVICE_LINE] when [advise]. Pure.
     */
    fun startHint(failed: Boolean, reason: String?, captcha: Boolean, browserOn: Boolean, restarting: Boolean, saved: Boolean,
                  gameUp: Boolean, launched: Boolean?, chosen: Boolean): String? = when {
        showRestart(gameUp, restarting, launched, chosen) -> if (chosen) RESTART_PAGE else RESTART_SAVED
        advise(failed, reason, browserOn, restarting, captcha, saved) -> ADVICE_LINE
        else -> null
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
