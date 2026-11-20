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
    fun reportLine(last: Pair<Long, String>?, now: Shape?, list: LogList? = null, stamp: (Long) -> String): String =
        (last?.let { (at, l) -> "${stamp(at)}: ${l.removePrefix("Game account: ")}" } ?: "(no Play in this run)") +
            "; game's log: ${list?.words() ?: "no account count read"}; auto-pick off (no timely signal)" +
            "; Config.wtf now: ${now?.words() ?: "missing"}"

    private const val ACCOUNT_NAME = "accountName"

    // ------------------------------------------------------------------------------------------------ the list in the game's log

    /**
     * THE LIST IN THE GAME'S LOG (the user's pick 2026-11-20, "Controller picks the row": first, can the app tell the list is up?).
     * The engine writes, right after the password's answer (measured on WoW Forever 2026-10-09, one game account):
     *   [N][Connection] Battle.net logon completed result="ERROR_OK (0)" numGameAccounts="1"
     *   [N][Connection] Battle.net complete login requested hasGameAccount="true"
     *   [N][Connection] Game account list not saved because accountName CVar is empty      (the saved login: no list is ever saved)
     * With numGameAccounts 2 or more and no account passed, the client shows its list (C_Login.SelectGameAccount from the list's own
     * Accept is the client's "Selecting game account" line). BUT the client writes General.log and Connection.log through a ~48 KiB
     * buffer: the authenticator's line arrived 25-45 s after the game asked (SignInKeyboard, HomeLoginWatch). So this line says THAT
     * the list came, never in time to press anything while it is up: the app only RECORDS it ([noteLog]), with how late it was read
     * and how long the list stayed up. Counts and seconds only; nothing of a line is kept.
     */
    private val COUNT = Regex("""Battle\.net logon completed result="[^"]*" numGameAccounts="(\d{1,2})"""")
    private val CLOCK = Regex("""^\s*\d{1,2}/\d{1,2} (\d\d):(\d\d):(\d\d)\.(\d{1,3})""")
    private const val PICKED = "Selecting game account"
    private const val COMPLETE = "Battle.net complete login requested"

    /** the game's own clock in [line] as ms of the day; null = none. Pure */
    fun clockMs(line: String): Long? = CLOCK.find(line)?.groupValues?.let { g ->
        ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].padEnd(3, '0').toLong()
    }

    /** [line]'s game-account count when it is the sign-in's "logon completed" line; null = another line. Pure */
    fun accountCount(line: String): Int? = COUNT.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** this client run's list, as the log told it (null = no count line yet) */
    data class LogList(val accounts: Int, val shownAtMs: Long?, val readLagMs: Long?, val upMs: Long? = null, val done: Boolean = false) {
        /** "list seen (2 accounts), read 31 s after the game wrote it, up 4 s" / "no list (1 account)". Pure */
        fun words(): String = if (accounts < 2) "no list ($accounts account${if (accounts == 1) "" else "s"})" else
            "list seen ($accounts accounts)" + (readLagMs?.let { ", read ${secs(it)} after the game wrote it" } ?: "") +
                when { upMs != null -> ", up ${secs(upMs)}"; done -> ", picked"; else -> "" }
    }

    @Volatile var logList: LogList? = null
        private set

    /** a new client run (HomeLoginWatch): the last run's list is forgotten */
    fun newRun() { logList = null }

    /**
     * one line of the game's log ([nowMs]: the device's local ms of the day when it was read). Returns the one sign-in-log line to
     * write when this line told something new (the list's count, or its end), else null. Pure apart from [logList]
     */
    fun noteLog(raw: String, nowMs: Long = msOfDay(System.currentTimeMillis())): String? {
        val line = raw.trimEnd('\r')
        accountCount(line)?.let { n ->
            val at = clockMs(line)
            // the same line again (Connection.log carries General.log's lines): the first read stands
            logList?.let { if (it.accounts == n && it.shownAtMs == at) return null }
            val l = LogList(n, at, at?.let { lag(it, nowMs) }, done = n < 2)
            logList = l
            return "Game account list: ${l.words()}"
        }
        val cur = logList ?: return null
        if (cur.done || cur.accounts < 2 || (PICKED !in line && COMPLETE !in line)) return null
        val end = clockMs(line)
        val l = cur.copy(upMs = if (end != null && cur.shownAtMs != null) lag(cur.shownAtMs, end) else null, done = true)
        logList = l
        return "Game account list: ${l.words()}"
    }

    /** [to] - [from] on the clock of one day (a run over midnight wraps); null past an hour (another day's line). Pure */
    internal fun lag(from: Long, to: Long): Long? = ((to - from + DAY_MS) % DAY_MS).takeIf { it <= HOUR_MS }

    private fun secs(ms: Long) = "${(ms + 500) / 1000} s"
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    /** [epochMs] as the device's local ms of the day (the game's log clock is the device's: Wine reads it) */
    fun msOfDay(epochMs: Long): Long = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }.let { c ->
        ((c.get(java.util.Calendar.HOUR_OF_DAY) * 60L + c.get(java.util.Calendar.MINUTE)) * 60 + c.get(java.util.Calendar.SECOND)) * 1000 +
            c.get(java.util.Calendar.MILLISECOND)
    }
}
