package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * BROWSER LOGIN HANDS THE GAME ACCOUNT TO THE GAME (the user's ruling 2026-11-23, picker: "Browser login hands it over (Recommended)";
 * the chat: "we still aren't able to skip the account select part of the log in"). The way the PC Battle.net app does it: beside the
 * launcher credential the helper writes GAME_ACCOUNT and REGION in the Wine registry (HKCU\Software\Blizzard Entertainment\Battle.net\
 * Launch Options\WoW), and the game skips its "Select an account" list itself. No key presses, no Config.wtf or CVar write.
 *
 *  - ONLY Browser login (opt-in), ONLY with Game account = "Remembered" (shown "Skip (Blizzard's page)"); "Ask each time" hands nothing
 *    over (the list shows, as before). The saved login (login.txt) is the game's kiosk login: it never takes an account, so it still
 *    shows the list.
 *  - WHICH ACCOUNT: the player's remembered one (Settings.gameAccountPick), and only when Battle.net lists it for THIS login
 *    (BgsClient.gameAccounts, the same lookup the game's own list makes): its name and region exactly as Battle.net gives them. A name
 *    not on this login, one game account, an unread list: nothing handed over, the game asks.
 *  - LEARNED, never typed: from a "!" mark the game wrote ANEW in Config.wtf ([markIsNew]: a stale mark never undoes a newer pick), and
 *    on Browser login from the row the player picked in the game's list: the game's log says the list was answered ([observe]), and at
 *    the next Browser login the account folder the game wrote since then (WTF/Account/<folder>; the game's own files only, never DS
 *    Forever's) names it among this login's accounts ([learn]: the folder of that name, or "...#N" for "WoWN", the rule DS Forever
 *    Desktop's settings follow uses).
 *  - FALLS BACK, NEVER LOOPS: the outcome is read in the game's log. The game still showed its list after a hand-over = HELD (no more
 *    hand-overs until the pick or the Game account setting changes); a hand-over is GOOD only once the realm accepted the account
 *    ([realmAccepted], review 2026-11-23); one whose good outcome never reached the log (a refused or dropped login, a skipped list
 *    then a disconnect) = held too, so an unknown format or a refused account costs at most ONE start. Held or not, the player can
 *    always pick in the list as before; nothing is pressed, nothing retried.
 *  - THE FORMAT IS NOT YET CONFIRMED on a PC (the user approved one masked read-only look later): it lives in ONE function,
 *    [registryValues]. The helper writes whatever it is given.
 *  - PRIVACY: names are compared here and handed to the helper only; logs, reports and diagnostics say lengths, regions and states.
 */
object GameAccountHandover {
    /** a game account of this Battle.net login, as Battle.net names it ([id]: the handle's game-account id, null = not given; kept so
     *  [registryValues] can build another format if the PC look needs one). No generated toString: the name and id are account data */
    class Account(val name: String, val region: String, val id: Long? = null) {
        override fun toString() = "Account(${name.length} characters, $region)"
    }

    sealed class Choice {
        class Hand(val account: Account) : Choice() { override fun toString() = "Hand($account)" }
        data class None(val why: String) : Choice()
    }

    // ------------------------------------------------------------------------------------------------ THE FORMAT (one function)

    /**
     * THE VALUES THE HELPER WRITES, GAME_ACCOUNT to REGION, for [a]; null = unusable (nothing is handed over). As nyrda/scuffle
     * (5efccb44, DESIGN.md "WoW account", MIT) documents the Battle.net app's own write: GAME_ACCOUNT = the game account's name as
     * GetGameAccountInfo gives it (the name the game's own list shows, e.g. "WoW1"), REGION = the account's region in capitals
     * ("US", "EU", "KR", "TW", "CN"). TO CONFIRM on the PC (account_skip_pc_look.md): only this function changes if it differs.
     */
    fun registryValues(a: Account): Pair<String, String>? {
        val region = a.region.trim().uppercase(Locale.ROOT)
        if (!REGION.matches(region) || !launchName(a.name)) return null
        return a.name to region
    }

    /** the helper's game-account frame for [choice]: "<REGION>:<name>", or "-" for none (tools/wow-session-bridge/protocol.h). Pure */
    fun frame(choice: Choice): ByteArray =
        ((choice as? Choice.Hand)?.let { registryValues(it.account) }?.let { (name, region) -> "$region:$name" } ?: NONE)
            .toByteArray(Charsets.US_ASCII)

    /** what may go in GAME_ACCOUNT: 1-64 printable ASCII characters, no leading or trailing space (Scuffle's launch_name). Pure */
    fun launchName(name: String): Boolean = name.length in 1..64 && name.trim() == name && name.all { it.code in 32..126 }

    // ------------------------------------------------------------------------------------------------ the choice

    /**
     * this Browser login's hand-over: [ask] (Ask each time), the [remembered] pick, whether hand-overs are [held], and this login's
     * game [accounts] (null = unread). Pure
     */
    fun choose(ask: Boolean, remembered: String?, held: Boolean, accounts: List<Account>?): Choice = when {
        ask -> Choice.None("Ask each time")
        accounts == null -> Choice.None("this login's game accounts unread")
        accounts.isEmpty() -> Choice.None("no WoW game account listed")
        accounts.size == 1 -> Choice.None("one game account: no list")
        remembered == null -> Choice.None("none remembered yet")
        held -> Choice.None("held after an earlier hand-over (the list showed, or no outcome was seen)")
        else -> accounts.filter { it.name.equals(remembered, ignoreCase = true) }.singleOrNull()?.let { Choice.Hand(it) }
            ?: Choice.None("the remembered one is not on this login")
    }

    /** [choice] for the logs: a length, a region and a reason only. Pure */
    fun words(choice: Choice): String = when (choice) {
        is Choice.Hand -> registryValues(choice.account)?.let { (n, r) -> "handed over (${n.length} characters, region $r)" }
            ?: "none handed over (an unusable name): the game asks"
        is Choice.None -> "none handed over (${choice.why}): the game asks"
    }

    // ------------------------------------------------------------------------------------------------ learning the pick

    /** [folder] (a WTF/Account folder's name) is the game account [name]: the same name, or "...#N" for "WoWN". Pure */
    fun folderIs(folder: String, name: String): Boolean =
        folder.equals(name, ignoreCase = true) || (WOW_N.matchEntire(name)?.let { folder.endsWith("#" + it.groupValues[1]) } ?: false)

    /** the one of [names] that [folder] is, else null (none, or more than one). Pure */
    fun learn(folder: String?, names: List<String>): String? = folder?.let { f -> names.filter { folderIs(f, it) }.singleOrNull() }

    /** the WTF/Account folder ([accountDir]) the game wrote last, after [afterMs]; null = none written since then (or unreadable) */
    fun newestFolder(accountDir: File, afterMs: Long): String? = runCatching {
        accountDir.listFiles()?.filter { it.isDirectory && !it.name.equals("SavedVariables", ignoreCase = true) }
            ?.map { d -> d.name to written(d) }?.filter { it.second > afterMs }?.maxByOrNull { it.second }?.first
    }.getOrNull()

    /**
     * the newest write THE GAME made in an account folder (review 2026-11-23): only the files the client writes when it logs out or
     * exits - the account's cache files and its SavedVariables - never DS Forever's own writes into the same folders, which happen at
     * Play BEFORE the next Browser login decides and go into the folder used last (SvKeep's WowCompanion*.lua, stamped with the
     * table's save time; PsSync's Polestar.lua; LauncherSetup's AddOns.txt and its temp copies), and never a folder's own time (a
     * rename inside changes it). Else a game Android closed before it saved would "learn" the previous account
     */
    private fun written(d: File): Long {
        val top = d.listFiles().orEmpty().filter { it.isFile && it.name.lowercase(Locale.ROOT) in GAME_FILES }
        val sv = File(d, "SavedVariables").listFiles().orEmpty().filter { f ->
            val n = f.name.lowercase(Locale.ROOT)
            f.isFile && (n.endsWith(".lua") || n.endsWith(".lua.bak")) && OURS.none { n == it || n == "$it.bak" }
        }
        return (top + sv).maxOfOrNull { it.lastModified() } ?: 0L
    }

    // ------------------------------------------------------------------------------------------------ the state (one private pref)

    /** [held]: no hand-over until the pick or the setting changes; [strikes]: hand-overs whose outcome never reached the log, in a
     *  row; [unseen]: the last hand-over's outcome is not known yet; [learnAfter]: a Browser-login pick to learn from folders written
     *  after this time (0 = none); [mark]: a hash of the last "!" mark seen in Config.wtf ("" = none); [good]: a hash of the pick the
     *  realm has accepted after a hand-over ("" = none yet) */
    data class State(val held: Boolean = false, val strikes: Int = 0, val unseen: Boolean = false, val learnAfter: Long = 0L,
                     val mark: String = "", val good: String = "") {
        fun json(): String = JSONObject().put("held", held).put("strikes", strikes).put("unseen", unseen).put("learnAfter", learnAfter)
            .put("mark", mark).put("good", good).toString()
        companion object {
            fun of(json: String?): State = runCatching {
                JSONObject(json ?: return State()).let {
                    State(it.optBoolean("held"), it.optInt("strikes"), it.optBoolean("unseen"), it.optLong("learnAfter"), it.optString("mark"),
                        it.optString("good"))
                }
            }.getOrDefault(State())
        }
    }

    const val KEY = "gameAccountHandover"
    /** a hand-over whose good outcome (the realm's acceptance) never reached the game's log: held (a refused login may never write it) */
    const val MAX_STRIKES = 1
    /** the same for a pick the realm already accepted after a hand-over (review 2026-11-23): one early exit, whose log lines never
     *  reached the disk, does not hold it; two in a row do (an account refused since then costs two starts at most) */
    const val MAX_STRIKES_GOOD = 2

    @Synchronized fun state(): State = State.of(Settings.gameAccountHandover)
    @Synchronized private fun save(s: State) { Settings.gameAccountHandover = s.json() }

    /** the Game account setting changed, or the pick did: hand-overs may be tried again */
    @Synchronized fun liftHold() { state().let { if (it.held || it.strikes > 0 || it.unseen) save(it.copy(held = false, strikes = 0, unseen = false)) } }

    /** the saved login forgotten: nothing held or waiting to be learned for the next login */
    @Synchronized fun forget() { save(State(mark = state().mark)) }                  // [State.good] goes too: another login's pick

    /** [label] remembered as the pick (a change lifts a hold: the new one is tried once) */
    @Synchronized fun remember(label: String) {
        val before = Settings.gameAccountPick
        Settings.gameAccountPick = label
        if (Settings.gameAccountPick != before) liftHold()
    }

    /**
     * Play's "!" mark ([learned]: GameConfigWtf.accountPickIn, null = none): true when the game wrote it anew since the last Play (its
     * hash changed), so only a fresh mark replaces the pick; a stale one never undoes a pick learned on Browser login. No mark = the
     * hash goes (a later mark, even the same name, is fresh again).
     */
    @Synchronized fun markIsNew(learned: String?): Boolean {
        val s = state()
        val h = learned?.let { hash(it) } ?: ""
        if (h != s.mark) save(s.copy(mark = h))
        return learned != null && h != s.mark
    }

    private fun hash(label: String): String = MessageDigest.getInstance("SHA-256").digest(label.lowercase(Locale.ROOT).toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------------------------------------ this run (process memory)

    /** this game start signs in with Browser login (latched at Play: [beginRun]) */
    @Volatile var browserRun = false
        private set
    /** the helper was handed an account this run */
    @Volatile var handedThisRun = false
        private set
    /** this run's list outcome was already taken */
    @Volatile private var observed = false
    /** this run's realm acceptance was already taken */
    @Volatile private var settled = false
    /** the last decision's words, for the problem report */
    @Volatile var lastWords: String? = null
        private set

    /** Play: a new game start, [browser] = Browser login */
    fun beginRun(browser: Boolean) { browserRun = browser; handedThisRun = false; observed = false; settled = false }

    /**
     * BROWSER LOGIN, after Battle.net issued the session ([accounts]: this login's game accounts, null = unread): learn a pick waiting
     * from the game's list, then choose. [accountDir] = the game's WTF/Account. Returns the choice; logs nothing itself.
     */
    @Synchronized fun decide(accountDir: File?, accounts: List<Account>?, now: Long = System.currentTimeMillis()): Choice {
        var s = state()
        // the last hand-over's outcome never reached the log: a strike ([MAX_STRIKES]: held)
        val limit = if (s.good.isNotEmpty() && Settings.gameAccountPick?.let { hash(it) } == s.good) MAX_STRIKES_GOOD else MAX_STRIKES
        if (s.unseen) s = s.copy(unseen = false, strikes = s.strikes + 1).let { if (it.strikes >= limit) it.copy(held = true) else it }
        if (s.learnAfter > 0L && accounts != null) {
            val picked = accountDir?.let { newestFolder(it, s.learnAfter) }?.let { learn(it, accounts.map { a -> a.name }) }
            s = s.copy(learnAfter = 0L)
            if (picked != null && picked != Settings.gameAccountPick) {
                Settings.gameAccountPick = picked
                s = s.copy(held = false, strikes = 0)
            }
        }
        val choice = choose(Settings.gameAccountAsk, Settings.gameAccountPick, s.held, accounts)
        if (choice is Choice.Hand) s = s.copy(unseen = true)
        save(s)
        handedThisRun = choice is Choice.Hand
        lastWords = words(choice)
        return choice
    }

    /**
     * the game's log said what became of this run's list ([list]: GameAccountDiag.logList, after a line that told something new).
     * Returns the one sign-in-log line to write, else null. Once per run, Browser login only.
     */
    @Synchronized fun observe(list: GameAccountDiag.LogList?, now: Long = System.currentTimeMillis()): String? {
        if (!browserRun || observed || list == null || list.accounts < 2 || !list.done) return null
        observed = true
        var s = state()
        val line: String
        if (list.shown) {
            // the player picked a row: learned at the next Browser login from the folder the game writes
            s = s.copy(learnAfter = now)
            line = if (handedThisRun) { s = s.copy(held = true, unseen = false); "Game account hand-over: the game still showed its list: held (the player picks as before)" }
                else "Game account hand-over: the list was answered; the pick is learned at the next Browser login"
        } else {
            // review 2026-11-23: a skipped list alone is not yet a good sign-in (a game account the servers refuse would skip it too,
            // then fail at every start): the outcome stays unseen until the realm accepts the account ([realmAccepted])
            line = if (handedThisRun) "Game account hand-over: the game skipped its list (the realm's answer is awaited)"
                else "Game account hand-over: no list (the game had its account)"
        }
        save(s)
        return line
    }

    /** [line] of the game's log is the realm's acceptance of the game account (the user's Thor 2026-11-07:
     *  `Realm authentication completed result="ERROR_OK (0)"`). Pure */
    fun realmOk(line: String): Boolean = REALM_OK in line

    /**
     * the realm accepted this run's game account (the game's log, [realmOk]): a hand-over this run is a GOOD one only now, so its
     * outcome is seen and the strikes go. A hand-over whose list was skipped but whose sign-in then failed (refused, disconnected
     * before the realm) stays unseen and holds at the next Browser login, so the list shows then: never a loop of failed starts.
     * Returns the one sign-in-log line, else null. Once per run, Browser login only.
     */
    @Synchronized fun realmAccepted(): String? {
        if (!browserRun || !handedThisRun || settled) return null
        settled = true
        val s = state()
        if (!s.unseen) return null                                            // the list showed after it (held): nothing changes
        save(s.copy(unseen = false, strikes = 0, good = Settings.gameAccountPick?.let { hash(it) } ?: ""))
        return "Game account hand-over: the realm accepted the handed-over account"
    }

    /** the problem report's words: the last decision in this process and the state now. No name */
    fun reportWords(): String {
        val s = runCatching { state() }.getOrDefault(State())
        return (lastWords ?: "no Browser login in this run") + "; " +
            listOfNotNull(if (s.held) "held" else null, if (s.strikes > 0) "${s.strikes} unseen" else null,
                if (s.unseen) "outcome pending" else null, if (s.learnAfter > 0L) "a pick to learn" else null,
                if (s.good.isNotEmpty() && Settings.gameAccountPick?.let { hash(it) } == s.good) "the realm accepted it before" else null).ifEmpty { listOf("ready") }.joinToString(", ")
    }

    private const val NONE = "-"
    private const val REALM_OK = "Realm authentication completed result=\"ERROR_OK"
    /** the account folder's own files the client writes at logout / exit */
    private val GAME_FILES = setOf("config-cache.wtf", "bindings-cache.wtf", "macros-cache.txt")
    /** SavedVariables files DS Forever itself writes before a start (SvKeep, PsSync): never a sign of the game's own save */
    private val OURS: List<String> get() = (SvKeep.FILES.values + PsSync.FILE).map { it.lowercase(Locale.ROOT) }
    private val REGION = Regex("^[A-Z]{2}$")
    private val WOW_N = Regex("^wow(\\d+)$", RegexOption.IGNORE_CASE)
}
