package com.jrees.wowcompanion

import java.io.File

/**
 * THE GAME-ACCOUNT LINE (bug 2026-11-20, test 1513: "Remembered" did not skip the game's "Select an account" list; the launch line was
 * only in logcat, which had rolled over). One "Game account:" line per Play, in logcat (WCLOGIN), the problem report's log (DiagLog)
 * and the kept /sdcard/WowCompanion/logs/login.log ([record]), and the report's Game folder section ([reportLine]).
 *
 * PRIVACY: states and counts only - never a label, a name or the Battle.net login. The account keys' VALUES are read here only to
 * count names and see the "!" mark ([shape]); nothing of a value leaves this file.
 *
 * WHY THE LIST SHOWS (the client's own login screen, Blizzard_GlueXML mainline AccountLogin.lua, the WoW Forever client's glue code;
 * the accountList CVar's own text "List of wow accounts for saved Blizzard account" in the client):
 *  - accountList's "!" is read ONLY by the typed login (AccountLogin_Login): with a saved login name AND a saved list the screen shows
 *    the account dropdown, preselects the "!" one and calls C_Login.SelectGameAccount with it after C_Login.Login.
 *  - The saved login (login.txt) is the client's kiosk auto-login (Kiosk.GetKioskLoginInfo; the 1.75 s AUTO_LOGIN_WAIT_TIME is the
 *    ~2 s NativeLogin measured): AccountLogin_CheckAutoLogin calls C_Login.Login alone - no SelectGameAccount, no saved login name -
 *    so with several game accounts the game always shows WoWAccountSelectDialog (first row selected).
 *  - Browser login is the launcher sign-in (C_Login.LauncherLogin): the game account comes from the registry's GAME_ACCOUNT ("Attempt
 *    to launcher login, but no game account"), which the bundled helper deletes before the start ("allowing account choice").
 * So no Config.wtf write can skip the list on either of DS Forever's sign-ins ([verdict]).
 */
object GameAccountDiag {
    enum class ListState(val word: String) { NONE("none"), UNMARKED("unmarked"), MARKED("marked") }

    /** the account keys' shape in one Config.wtf: accountList's state and name count, and whether a login name is saved. No value */
    data class Shape(val list: ListState, val names: Int, val savedName: Boolean) {
        fun words(): String = "accountList ${list.word}" + (if (list != ListState.NONE) " ($names)" else "") +
            ", saved login name ${if (savedName) "yes" else "no"}"
    }

    enum class Learned(val word: String) { NONE("none marked"), SAME("same"), NEW("new") }

    /** this start's sign-in: the saved login (login.txt, the kiosk auto-login), Browser login (the helper), or the game's own screen */
    enum class Route(val word: String) { SAVED_LOGIN("saved login (login.txt)"), BROWSER("browser login"), OWN_SCREEN("the game's own login screen") }

    /** what Play did: Ask each time or Remembered, the stored pick set, what was learned, the file before any write, the mark written */
    data class Play(val ask: Boolean, val pickSet: Boolean, val learned: Learned, val before: Shape?, val marked: Boolean)

    /** [lines]' shape (the last line of each key, as the game reads it). Pure */
    fun shape(lines: List<String>): Shape {
        var list: String? = null
        var name: String? = null
        for (line in lines) {
            val parts = line.trim().takeIf { it.startsWith("SET ", ignoreCase = true) }?.drop(4)?.trim()?.split(" ", limit = 2) ?: continue
            val v = parts.getOrElse(1) { "" }.trim().trim('"')
            when {
                parts[0].equals(GameConfigWtf.ACCOUNT_LIST, ignoreCase = true) -> list = v
                parts[0].equals(ACCOUNT_NAME, ignoreCase = true) -> name = v
            }
        }
        val names = GameConfigWtf.accountNames(list)
        val state = when {
            list == null || names.isEmpty() -> ListState.NONE
            list!!.split('|').any { it.trim().startsWith("!") } -> ListState.MARKED
            else -> ListState.UNMARKED
        }
        return Shape(state, names.size, !name.isNullOrBlank())
    }

    /** [configWtf]'s shape; null = no file or unreadable */
    fun shape(configWtf: File): Shape? = runCatching { configWtf.takeIf { it.isFile }?.readLines()?.let(::shape) }.getOrNull()

    /** [root]'s Config.wtf for [product] (the file Play reads and writes) */
    fun configWtf(root: File, product: WowProduct): File = File(File(root, product.flavorDir), "WTF/Config.wtf")

    /** what the learn step found against the stored pick (labels compared here, only the word leaves). Pure */
    fun learned(learned: String?, before: String?): Learned = when { learned == null -> Learned.NONE; learned == before -> Learned.SAME; else -> Learned.NEW }

    /**
     * Will the game skip its account list on [route] after [play]? Only a typed login with a saved login name and a marked list does
     * (the dropdown's preselection); see the object's notes. Pure
     */
    fun verdict(play: Play, route: Route): String {
        val markedNow = !play.ask && (play.marked || play.before?.list == ListState.MARKED)
        return when (route) {
            Route.SAVED_LOGIN -> "list expected (the login.txt sign-in never reads accountList)"
            Route.BROWSER -> "list expected (the helper clears GAME_ACCOUNT)"
            Route.OWN_SCREEN -> when {
                play.ask -> "list expected (Ask each time)"
                !markedNow -> "list expected (no marked account)"
                play.before?.savedName != true -> "list expected (no saved login name: no account dropdown)"
                else -> "a typed login preselects the marked account"
            }
        }
    }

    /** the "Game account:" line for [play] on [route]. Pure; states and counts only */
    fun line(play: Play, route: Route): String =
        "Game account: ${if (play.ask) "ask each time" else "remembered"}; pick ${if (play.pickSet) "set" else "unset"}; " +
            "learned ${play.learned.word}; at Play ${play.before?.words() ?: "no Config.wtf"}; " +
            "${when { play.ask -> "no mark written"; play.marked -> "marked this start"; else -> "accountList unchanged" }}; " +
            "route ${route.word}; ${verdict(play, route)}"

    /** this process's last Play line, with its time (the report) */
    @Volatile var last: Pair<Long, String>? = null
        private set

    /** keeps [play]'s line for the report and logs it ([log]: logcat, the report's log, login.log) */
    fun record(play: Play, route: Route, now: Long = System.currentTimeMillis(), log: (String) -> Unit = NativeLogin::logEvent): String {
        val l = line(play, route)
        last = now to l
        runCatching { log(l) }
        return l
    }

    /** the report's line: the last Play in this process (or none) and the file's shape now (what the game left at its exit). Pure */
    fun reportLine(last: Pair<Long, String>?, now: Shape?, stamp: (Long) -> String): String =
        (last?.let { (at, l) -> "${stamp(at)}: ${l.removePrefix("Game account: ")}" } ?: "(no Play in this run)") +
            "; Config.wtf now: ${now?.words() ?: "missing"}"

    private const val ACCOUNT_NAME = "accountName"
}
