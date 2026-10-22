package com.jrees.wowcompanion

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * THE GAME'S OWN SIGN-IN, WITH THE CREDENTIAL FILE NEVER ON SHARED STORAGE (the user 2026-10-08: "I do want to adopt the txt login. If
 * there's a way to do it without saving the text file that would be great but the other guy had a point that injecting the keys is
 * not the way to go"; then "(a) Private folder"). The WoW client reads `_classic_beta_/login.txt` next to its exe ON STARTUP and signs in
 * natively (jaredgei's route). Here:
 *  - Wine's drive G: points at a PRIVATE "shadow" game folder in this app's own storage ([prepare]): every real entry of the shared game
 *    folder is a SYMLINK back to it (Data, .build.info, the exe, WTF, Interface, Logs, Cache...), so the game, its logs, settings and
 *    add-ons all stay on shared storage exactly as before. login.txt is the ONLY real file there - other apps cannot read it.
 *  - It is written from DS Forever's encrypted store (HomeLoginStore: AES-256-GCM, Keystore key) right before the client starts and
 *    DELETED the moment the client has read it (a FileObserver: its read's close), with backstops: [clear] on the sign-in watcher's
 *    "signing in", a hard timeout, every app start and the game screen going (crash recovery).
 *  - No key is ever typed (the user's ruling). An authenticator code or a failed login is typed by the user on the game's own screen.
 * Never follows a symlink when cleaning: only the links themselves and login.txt are ever deleted.
 */
object NativeLogin {
    private const val TAG = "WCLOGIN"
    private const val FILE = "login.txt"
    /**
     * The hard backstop for an UNREAD file: the user's rule caps a written file at 5 minutes. TEST12 (2026-10-12 17:38): a fresh
     * install's first boot (prefix build, caches) took over 2 minutes to its login screen; the old 120 s deleted the file first and
     * the first sign-in never happened. Also the clock now restarts when the game's window appears ([gameWindowUp]).
     */
    private const val MAX_LIFE_MS = 300_000L
    /** when Play wrote this run's first file (the window's log line says how long the start took) */
    private var playAt = 0L
    /** this run's file was written again when the game's window appeared (once per client run) */
    @Volatile private var windowArmed = false

    /** a login.txt was written for this client run and not consumed yet */
    @Volatile var active = false; private set
    /** the client read this run's login.txt (the native sign-in is under way) */
    @Volatile var consumed = false; private set
    /** when the client first read this run's file (its login screen was up then); 0 = not yet */
    @Volatile var firstReadAt = 0L; private set
    /**
     * THIS CLIENT RUN IS PAST ITS LOGIN SCREEN (the one app's row hides then; the user 2026-10-08, "Only when needed"): a second of the
     * game's traffic of at least [PAST_LOGIN_BYTES] - character select's realm list and characters. MEASURED 2026-10-08: before the
     * login screen <= 2 KB/s (22:31:08-13), the sign-in itself 6-14 KB/s (22:31:19-25; the failed 22:17 run peaked at 6.7 KB/s),
     * character select 32-38 KB/s (22:31:32-34). No root: /proc/net is closed, so the watcher's realm connection never comes here.
     */
    @Volatile var pastLogin = false; private set
    /** told (main thread) when [firstReadAt], [pastLogin] or [active] changes: the one app's row follows this run */
    var onChange: (() -> Unit)? = null
    /**
     * TEST6 (2026-10-12 12:39:28): 25.6 KB in one second at the login screen after a refusal (the user in the Update login form) said
     * "past the login screen". The good sign-ins' character-select burst (10-09 h1/h2/h3): 37.6 KB then 559 KB, 363 KB, 480 KB in its
     * first seconds - so 100 KB: never the login screen's own traffic, at most a second later on a real sign-in.
     */
    private const val PAST_LOGIN_BYTES = 100_000L

    /**
     * THE RE-LOGIN (the user 2026-10-09: "I did not want to drop the relogin actually. I know that it works because it worked for the
     * other guys build" - WoW Forever's file stayed on shared storage for good and the game signed in again at a return). The client
     * reads login.txt when its login screen APPEARS, so a file for a return must already be there: it is written again ([rearm]) the
     * moment the client run leaves the world (the add-on's logout word, or the world's traffic stopping for 5 s) and goes 20 s after
     * the client's first read, when the world comes back ([worldBack]: the add-on's documents current again), or [REARM_LIFE_MS] after
     * it was written. Character select after a logout holds it too (private, 0600, capped). No log line is needed: the game's own log
     * is block-buffered before the world.
     */
    private const val REARM_LIFE_MS = 300_000L
    /** the client run left the world (a logout, or its traffic stopped) and has not come back: a re-login file may be waiting */
    @Volatile var outOfWorld = false; private set
    /**
     * this client run has been IN THE WORLD (the add-on's documents current: [worldSeen]); only then can leaving it write the re-login
     * file. NOT character select's traffic: measured 2026-10-09 10:30, the first character select (after the sign-in, before the world)
     * went quiet for 5 s, the traffic trigger took it for a disconnect and a file waited 225 s for nothing
     */
    @Volatile private var reachedWorld = false
    private var appCtx: Context? = null
    private var observer: FileObserver? = null
    /** when this run's file was written (its lifetime goes to the log when it is deleted) */
    private var writtenAt = 0L
    private var flavorDir: File? = null
    private val main = Handler(Looper.getMainLooper())
    private var lifeMs = MAX_LIFE_MS
    private val timeout = Runnable { if (active) { logEvent("login.txt not read in ${lifeMs / 1000} s, deleting"); clear() } }

    /** the private shadow root (Wine's G:) */
    fun shadowRoot(ctx: Context) = File(ctx.filesDir, "gameroot")

    /**
     * Builds the shadow folder for [gameRoot] (the shared game folder) and, when a login is saved, writes this run's login.txt into it.
     * Returns the folder Wine's G: should point at. Any failure falls back to... nothing: the caller gets null and keeps the shared
     * folder WITHOUT a login file (no automatic fallback to shared storage, the user's rule).
     */
    fun prepare(ctx: Context, gameRoot: File, flavor: String): File? = try {
        clear()
        consumed = false; firstReadAt = 0L; pastLogin = false; quietFor = 0; quiet = false   // a new client run
        outOfWorld = false; reachedWorld = false; lifeMs = MAX_LIFE_MS; appCtx = ctx.applicationContext; changed()
        playAt = System.currentTimeMillis(); windowArmed = false
        val root = shadowRoot(ctx).apply { mkdirs() }
        val fl = File(root, flavor).apply { mkdirs() }
        // launch support: after a client switch (beta -> launch) the OTHER client's old shadow folder is a real folder of links into the
        // shared one. Its links go first, so the repair below merges only what that client really wrote there (walking a link would
        // reach, and move, the shared files themselves)
        WowProduct.values().map { File(root, it.flavorDir) }
            .filter { it.name != flavor && it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.forEach { removeLinks(it, keep = null) }
        repairShadow(root, fl, gameRoot, flavor)                          // test22: no real game folder stays private (ShadowRepair)
        // test23 (SvKeep, the user's pick): our OWN SavedVariables from the kept copy, before the client starts, when the game has nothing newer
        runCatching {
            val docs = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            // the launch switch: the beta's own DS Forever settings into the launch client's WTF, once (never over newer ones)
            if (flavor == WowProduct.LAUNCH.flavorDir) {
                SvKeep.carryOver(File(gameRoot, "${WowProduct.BETA.flavorDir}/WTF"), File(gameRoot, "$flavor/WTF")) { logEvent(it); Log.i("WCSETUP", it) }
            }
            SvKeep.beforeStart(File(gameRoot, "$flavor/WTF"), File(docs, "DS Forever/settings"),
                File(docs, "WoW-AddOns-backup/settings-" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.US).format(java.util.Date()))) {
                logEvent(it); Log.i("WCSETUP", it)
            }
        }.onFailure { logEvent("settings backup: NOT written (${it.message})") }
        removeLinks(root, keep = flavor)
        removeLinks(fl, keep = null)
        gameRoot.listFiles()?.forEach { e -> if (e.name != flavor) link(e, File(root, e.name)) }
        File(gameRoot, flavor).listFiles()?.forEach { e -> if (e.name != FILE) link(e, File(fl, e.name)) }
        flavorDir = fl
        val login = HomeLoginStore.load(ctx)
        if (login != null) {
            val f = File(fl, FILE)
            f.writeText("${login.email}\n${login.password}\n")
            Os.chmod(f.absolutePath, 384)          // 0600: this app only
            active = true; consumed = false; writtenAt = System.currentTimeMillis()
            watch(fl)
            main.postDelayed(timeout, MAX_LIFE_MS)
            Log.i(TAG, "login.txt written (Play; private shadow folder); waiting for the client's read")
        } else { watch(fl); Log.i(TAG, "no saved login: the game's own login screen") }
        logEvent("client run starts (login.txt ${if (active) "written at Play" else "not written: no saved login"})")
        root
    } catch (t: Throwable) {
        Log.w(TAG, "shadow folder failed: ${t.message}"); clear(); null
    }

    /**
     * WHEN IT GOES (measured 2026-10-08 22:08, the first device test): the client opens login.txt the moment its login screen appears
     * (two reads, 22:08:23.36) but only STARTS the sign-in ~2.2 s later (WoW Forever's own run: AccountLogin 15:44:22.21 -> "Glue login
     * started" 15:44:24.42). Deleting on that first read took it away too early: no sign-in. So after the client's read the file stays
     * until the sign-in's network traffic starts ([onTraffic]: the login screen sits at 0 bytes), or [AFTER_READ_MS] after its LAST read,
     * whichever comes first; the other backstops are unchanged. Still only in the app's private storage.
     */
    /**
     * SECOND DEVICE TEST (22:17): deleting on the sign-in's first traffic was ALSO too early - the client reads the credentials again
     * ~1-2 s into the sign-in, at the logon form's submission (WoW Forever's 15:44 log: "Glue login started" 24.419 -> "logon form
     * submission" 25.739 -> "credentials submission" 26.282 -> "logon completed" 26.613), and hung on "Connecting" without them. So the
     * file now stays [AFTER_READ_MS] after the client's FIRST read (the whole sign-in takes ~2.2 s from there); no traffic trigger.
     */
    private const val AFTER_READ_MS = 20_000L
    private val afterRead = Runnable { if (active) { logEvent("login.txt: ${AFTER_READ_MS / 1000} s after the client's first read, deleting"); clear() } }

    /** each second's received / sent bytes, for the sign-in check (Track C: a refusal = the connection dead, a success = keepalives) */
    @Volatile var onTrafficSample: ((rxBytes: Long, txBytes: Long) -> Unit)? = null

    /**
     * BUG 1 OF TEST3 (2026-10-12 11:27:47): "past the login screen (1641328 B/s)" fired BEFORE the game had read anything - the launcher's
     * own first-boot downloads (graphics wrapper, extra libs, zink: up to 12 MB/s, 4-9 s after the game screen came up) run under the
     * same uid. So the burst counts only once the client has READ this run's login.txt; with no file at all (no saved login) only
     * [NO_FILE_GRACE_S] after the game screen came up. Never while a written file still waits for its read.
     */
    private const val NO_FILE_GRACE_S = 60L
    private fun signInTraffic(): Boolean = when {
        consumed -> true
        active -> false
        else -> LauncherHooks.gameStartSec > 0 && System.currentTimeMillis() / 1000 - LauncherHooks.gameStartSec >= NO_FILE_GRACE_S
    }

    /** one second's received bytes of the game (the launcher's traffic loop): only [pastLogin]; the delete is [AFTER_READ_MS]'s */
    fun onTraffic(rxBytes: Long, txBytes: Long = -1L) {
        main.post { worldTraffic(rxBytes); onTrafficSample?.invoke(rxBytes, txBytes) }
        if (!pastLogin && rxBytes >= PAST_LOGIN_BYTES && signInTraffic()) main.post {
            if (!pastLogin) {
                pastLogin = true
                logEvent("past the login screen ($rxBytes B/s)"); onChange?.invoke()
            }
        }
    }

    private fun changed() { main.post { onChange?.invoke() } }

    /**
     * The client's reads of login.txt (it is kept a moment longer, see above), watched for the whole client run: the re-login writes
     * the file again later in the run ([rearm]). Each step goes to [logEvent] (the lean log; the re-login probe's folder-scan counting
     * is gone, the user's "Merge as tested" + trim, 2026-10-09).
     */
    private fun watch(dir: File) {
        observer?.stopWatching()
        observer = object : FileObserver(dir, CLOSE_NOWRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != FILE) return
                if (event and CLOSE_NOWRITE != 0 && !active) { logEvent("login.txt read while none was written by us"); return }
                if (event and CLOSE_NOWRITE != 0) {
                    val first = !consumed
                    consumed = true
                    if (first) pastLogin = false          // a return's sign-in: past its login screen again only on the next character select
                    Log.i(TAG, "login.txt read by the client")
                    // timed from the FIRST read only: later reads (the form submission) must not push it further out
                    if (first) {
                        firstReadAt = System.currentTimeMillis()
                        logEvent("login.txt read by the client, ${(firstReadAt - writtenAt) / 1000} s after it was written")
                        main.post { main.postDelayed(afterRead, AFTER_READ_MS); onChange?.invoke() }
                    }
                }
            }
        }.also { it.startWatching() }
    }

    /** deletes this run's login.txt (the watching goes on until [end]); safe any time, any number of times */
    fun clear(ctx: Context? = null) {
        val was = active
        main.removeCallbacks(timeout); main.removeCallbacks(afterRead)
        val dirs = listOfNotNull(flavorDir) + (ctx?.let { c -> WowProduct.values().map { File(shadowRoot(c), it.flavorDir) } }.orEmpty())
        for (d in dirs) File(d, FILE).let { f ->
            if (f.exists() && !Files.isSymbolicLink(f.toPath()) && f.delete())
                Log.i(TAG, "login.txt deleted" + (if (writtenAt > 0) " after ${System.currentTimeMillis() - writtenAt} ms" else " (left from an earlier run)"))
        }
        val wroteAt = writtenAt
        writtenAt = 0L
        active = false
        if (was) { logEvent("login.txt deleted after ${(System.currentTimeMillis() - wroteAt) / 1000} s"); changed() }
    }

    /**
     * The client run left the world ([why]): this run's login.txt again, for its return to the login screen (see [REARM_LIFE_MS]).
     * Only after the run had reached the world, once per time out of it, with a saved login and this run's private shadow folder.
     * Main thread.
     */
    fun rearm(why: String) {
        if (active || outOfWorld || !reachedWorld) return
        val fl = flavorDir ?: return
        val ctx = appCtx ?: return
        outOfWorld = true
        val login = HomeLoginStore.load(ctx) ?: run { logEvent("out of the world ($why); no saved login, nothing written"); return }
        try {
            val f = File(fl, FILE)
            f.writeText("${login.email}\n${login.password}\n")
            Os.chmod(f.absolutePath, 384)          // 0600: this app only
        } catch (t: Throwable) { Log.w(TAG, "re-login file failed: ${t.message}"); clear(); return }
        active = true; consumed = false; firstReadAt = 0L; writtenAt = System.currentTimeMillis()
        lifeMs = REARM_LIFE_MS
        main.postDelayed(timeout, REARM_LIFE_MS)
        logEvent("login.txt written again ($why): waiting for the return to the login screen")
        changed()
    }

    /**
     * PAST THE LOGIN SCREEN without a burst (Track C, test10 16:48: a cached character select never reached [PAST_LOGIN_BYTES]): the
     * sign-in check saw the exchange go on into two-way keepalives. Main thread.
     */
    fun markPastLogin(why: String) {
        if (pastLogin) return
        pastLogin = true
        logEvent("past the login screen ($why)"); onChange?.invoke()
    }

    /**
     * THE GAME'S WINDOW APPEARED (the launcher's X server mapped the client's window: XServerScreen's onMapWindow, the game window): the
     * login screen is not far now, so this run's file is written AGAIN with a fresh 5-minute clock - unless the client already read
     * it. Covers a slow first boot whose file was deleted unread at the cap, or would be soon. Once per client run; the same rules
     * (private 0600, deleted 20 s after the first read). Any thread.
     */
    fun gameWindowUp() = main.post {
        if (windowArmed || consumed || flavorDir == null) return@post
        windowArmed = true
        val ctx = appCtx ?: return@post
        val fl = flavorDir ?: return@post
        val login = HomeLoginStore.load(ctx) ?: return@post
        main.removeCallbacks(timeout)
        try {
            val f = File(fl, FILE)
            f.writeText("${login.email}\n${login.password}\n")
            Os.chmod(f.absolutePath, 384)          // 0600: this app only
        } catch (t: Throwable) { Log.w(TAG, "window file failed: ${t.message}"); clear(); return@post }
        active = true; consumed = false; firstReadAt = 0L; writtenAt = System.currentTimeMillis(); lifeMs = MAX_LIFE_MS
        main.postDelayed(timeout, MAX_LIFE_MS)
        logEvent("login.txt written (game window up, ${(writtenAt - playAt) / 1000} s after Play)")
        changed()
    }

    /** the add-on's documents are current: the run is in the world (a re-login may follow a later logout / disconnect) */
    fun worldSeen() {
        // the HARD backstop (test10: in the world with "Sign-in didn't work" still up): the world is past the login screen, always
        markPastLogin("in the world")
        if (!reachedWorld) { reachedWorld = true; logEvent("in the world") }
        worldBack()
    }

    /** the world is back (the add-on's documents current again): a waiting re-login file goes; the next time out may write one again */
    private fun worldBack() {
        if (!outOfWorld) return
        outOfWorld = false; quietFor = 0; quiet = false
        if (active) { logEvent("the world is back: deleting login.txt"); clear() }
        changed()
    }

    /** the game screen went: the file goes and the watching stops */
    fun end(ctx: Context? = null) {
        outOfWorld = false
        clear(ctx)
        observer?.stopWatching(); observer = null
        logEvent("client run ends")
    }

    private var quietFor = 0
    private var quiet = false

    /** the game's traffic stopping after the world (a disconnect: in the world it never drops under ~2 KB/s) writes the re-login file */
    private fun worldTraffic(rx: Long) {
        if (!pastLogin) return
        if (rx < 200) {
            if (++quietFor == 5 && !quiet) { quiet = true; rearm("the world's traffic stopped") }
        }
        else { quietFor = 0; if (rx >= 1_000) quiet = false }
    }

    /**
     * THE LEAN LOG (2026-10-09): the file's steps only - written / read / deleted and why / past the login screen / the run's start and
     * end - in logcat (WCLOGIN) and /sdcard/WowCompanion/logs/login.log (logcat rotates). Never anything of the login itself.
     */
    fun logEvent(s: String) {
        Log.i(TAG, s)
        DiagLog.event("signin", s)            // the sign-in's step names only (never anything typed): the problem report's log
        try {
            val f = File("/sdcard/WowCompanion/logs/login.log")
            f.appendText("${stamp(System.currentTimeMillis())} $s\n")
            if (++logWrites % 25 == 0) cap(f)
        } catch (_: Throwable) {}
    }

    /** the user's ruling 2026-10-12 ("Keep it, capped"): past [LOG_MAX] lines the file keeps its last [LOG_KEEP] (atomic swap) */
    private const val LOG_MAX = 400
    private const val LOG_KEEP = 200
    private var logWrites = 0
    private fun cap(f: File) {
        val lines = f.readLines()
        if (lines.size <= LOG_MAX) return
        val tmp = File(f.path + ".part")
        tmp.writeText(lines.takeLast(LOG_KEEP).joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun stamp(ms: Long) = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ms))

    /** removes the symlinks directly inside [dir] (never their targets) and a stray login.txt; leaves [keep] (the real flavor folder) */
    private fun removeLinks(dir: File, keep: String?) {
        dir.listFiles()?.forEach { e ->
            when {
                Files.isSymbolicLink(e.toPath()) -> e.delete()          // the link only: File.delete never follows a symlink
                e.name == FILE -> e.delete()
                e.name == keep -> {}
            }
        }
    }

    /**
     * test22 (ShadowRepair): every REAL entry the client made in the shadow (it was kept, so WTF's saves stayed private) is merged into
     * the shared game folder first (newer wins, older backed up to Documents/WoW-AddOns-backup/shadow-<date>/), then the links follow.
     * One WCLOGIN line per entry found real, and always one for WTF, so the next approved log read proves it.
     */
    private fun repairShadow(root: File, fl: File, gameRoot: File, flavor: String) {
        var backup: File? = null
        fun bak(): File = backup ?: ShadowRepair.backupRoot(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), System.currentTimeMillis()).also { backup = it }
        val isLink = { f: File -> Files.isSymbolicLink(f.toPath()) }
        var wtfSaid = false
        fun each(dir: File, shared: File, skip: Set<String>) {
            dir.listFiles()?.forEach { e ->
                if (e.name in skip || isLink(e)) { if (e.name == "WTF" && dir == fl) { logEvent(ShadowRepair.Result("WTF", "link").line()); wtfSaid = true }; return@forEach }
                val r = runCatching { ShadowRepair.repair(e, File(shared, e.name), bak(), isLink) }.getOrElse { ShadowRepair.Result(e.name, "real (repair failed: ${it.message})") }
                logEvent(r.line()); Log.i(TAG, r.line())
                if (e.name == "WTF" && dir == fl) wtfSaid = true
            }
        }
        each(root, gameRoot, setOf(flavor))
        each(fl, File(gameRoot, flavor), setOf(FILE))
        if (!wtfSaid) logEvent(ShadowRepair.Result("WTF", "missing").line())
    }

    private fun link(target: File, at: File) {
        if (Files.isSymbolicLink(at.toPath())) at.delete()
        else if (at.exists()) return          // something the client made here itself (private): kept, never replaced
        Os.symlink(target.absolutePath, at.absolutePath)
    }
}
