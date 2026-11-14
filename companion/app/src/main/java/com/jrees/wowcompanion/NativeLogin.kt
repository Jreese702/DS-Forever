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
     * Issue #132 (the user's pick 2026-11-12, "Wait for login screen"): while the game downloads its files before its login screen this
     * cap waits, with a hard cap of 30 minutes from the write ([PreLoginTraffic.capWait]).
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
    /** when [pastLogin] was last set (epoch ms; 0 = not this run): the add-ons' silence clock starts there (AddonSilence.Watch) */
    @Volatile var pastLoginAt = 0L; private set
    /**
     * THE LOGIN TRIAGE (2026-11-13): the game was downloading as it read this run's file ([PreLoginTraffic.busyAtRead]), so neither a
     * burst nor two-way keepalives are its sign-in (a streamed install's data said "past the login screen" at the authenticator,
     * the captcha and the experience screen); only the world's documents set [pastLogin] then. Set at the first read.
     */
    @Volatile var downloadAtRead = false; private set
    /** the last second of at least [PAST_LOGIN_BYTES] before this run's first read (epoch ms; 0 = none) */
    @Volatile private var lastHeavyAt = 0L
    /** this run's first read (epoch ms; 0 = none), set BEFORE [consumed] so the traffic thread's after-read check always has it */
    @Volatile private var readMs = 0L
    /** the "not taken for the sign-in" line once per read */
    @Volatile private var heavyNoted = false
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
    /** issue #231 (diagnostics only): this run's shadow Logs entry, for the add-ons' silence line */
    fun shadowLogsLine(): String = AddonSilence.shadowLogs(flavorDir?.let { File(it, "Logs") },
        { f -> Files.isSymbolicLink(f.toPath()) }, System.currentTimeMillis())
    /** the user relaying a player (2026-11-13): this run's shadow Interface entry ("link" = the game loads the shared add-ons) */
    fun shadowIfaceLine(): String {
        val f = flavorDir?.let { File(it, "Interface") } ?: return "shadow Interface: no shadow this run"
        return runCatching {
            when {
                Files.isSymbolicLink(f.toPath()) -> "shadow Interface: link"
                !f.exists() -> "shadow Interface: missing"
                f.isDirectory -> "shadow Interface: REAL dir (the game loads a private add-on copy)"
                else -> "shadow Interface: a file"
            }
        }.getOrElse { "shadow Interface: unreadable (${it.javaClass.simpleName})" }
    }
    private val main = Handler(Looper.getMainLooper())
    private var lifeMs = MAX_LIFE_MS
    private val timeout = Runnable { capCheck() }

    /**
     * THE GAME DOWNLOADS BEFORE ITS LOGIN SCREEN (issue #132): its traffic before the first read, at least [PreLoginTraffic.BUSY_MB] MB
     * a minute. The unread file's cap waits then, and the start screen says "Game downloading". Main thread.
     */
    @Volatile var downloading = false; private set
    /** the last moment [downloading] was seen (0 = not in this run): the 5-minute cap counts from here once the download ends */
    private var holdFrom = 0L
    /** the "kept past its cap" line once per file */
    private var capHeld = false

    /** the unread file's cap ([PreLoginTraffic.capWait]): deleted now, or asked again later while the game downloads. Main thread. */
    private fun capCheck() {
        if (!active) return
        val now = System.currentTimeMillis()
        val wait = PreLoginTraffic.capWait(now, writtenAt, holdFrom, lifeMs, downloading)
        if (wait > 0L) {
            if (downloading && !capHeld) { capHeld = true; logEvent("login.txt kept past its ${lifeMs / 1000} s cap: the game is downloading before its login screen") }
            main.postDelayed(timeout, wait)
            return
        }
        // issue #132: an unread file at its cap before the world = the client never reached its login screen in that time
        if (!reachedWorld) expiredUnread = true
        val age = now - writtenAt
        logEvent("login.txt not read in ${age / 1000} s${if (age >= PreLoginTraffic.HARD_CAP_MS - 1_000L) " (the 30-minute hard cap)" else ""}, deleting" +
            if (firstReadAt == 0L && preLogin.total > 0) " (the game received ${PreLoginTraffic.mb(preLogin.total)} MB since it started)" else "")
        clear()
    }

    /** issue #132: the game's window appeared in this client run (a login screen needs one; set whatever the file's state) */
    @Volatile var windowSeen = false; private set
    /** issue #132: this run's file was deleted unread at its cap before the world (traffic after that is not a sign-in's) */
    @Volatile private var expiredUnread = false
    /** issues #132 / #42 / #57: what the client received before its first read of login.txt (main thread) */
    internal val preLogin = PreLoginTraffic()
    /** issue #215 (diagnostics only): the client's traffic past its login screen and before the world, a line a busy minute */
    // the login triage: after the read the run may still be signing in (a downloading read never says "past the login screen")
    internal val afterLogin = PreLoginTraffic(phase = "signing in or past the login screen, before the world")
    /** issue #215: what the bottom screen shows (CompanionHost; main thread), added to [afterLogin]'s lines */
    @Volatile var bottomState: (() -> String)? = null

    /** the private shadow root (Wine's G:) */
    fun shadowRoot(ctx: Context) = File(ctx.filesDir, "gameroot")

    /**
     * Builds the shadow folder for [gameRoot] (the shared game folder) and, when a login is saved, writes this run's login.txt into it.
     * Returns the folder Wine's G: should point at. Any failure falls back to... nothing: the caller gets null and keeps the shared
     * folder WITHOUT a login file (no automatic fallback to shared storage, the user's rule).
     */
    fun prepare(ctx: Context, gameRoot: File, flavor: String): File? = try {
        clear()
        consumed = false; firstReadAt = 0L; pastLogin = false; pastLoginAt = 0L; quietFor = 0; quiet = false   // a new client run
        outOfWorld = false; reachedWorld = false; lifeMs = MAX_LIFE_MS; appCtx = ctx.applicationContext; changed()
        playAt = System.currentTimeMillis(); windowArmed = false; windowSeen = false; expiredUnread = false; main.post { preLogin.reset(); afterLogin.reset() }
        downloading = false; holdFrom = 0L; capHeld = false
        downloadAtRead = false; lastHeavyAt = 0L; heavyNoted = false; readMs = 0L
        val root = shadowRoot(ctx).apply { mkdirs() }
        val isLinkF = { f: File -> Files.isSymbolicLink(f.toPath()) }
        // test57: a flavor folder in the shadow that is a LINK into the shared game folder is unlinked first, and one that still resolves
        // into it is refused (ShadowRepair.flavorFolder): the repair below must never walk the shared game's own files
        val fl = ShadowRepair.flavorFolder(root, flavor, gameRoot, FLAVORS, isLinkF)
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
        ShadowRepair.rootLinks(gameRoot, FLAVORS).forEach { e -> link(e, File(root, e.name)) }      // never a shared flavor folder (test57)
        // the user relaying a player (2026-11-13, "waiting for game"): Logs / WTF / Interface exist in the shared folder before the
        // links, so the client never makes them private (General.log out of the companion's reach for the whole run)
        runCatching { ShadowRepair.ensureShared(File(gameRoot, flavor)) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            logEvent("shadow: made shared ${it.joinToString()} first (the game had none yet)"); Log.i(TAG, "shared folders made: ${it.joinToString()}")
        }
        File(gameRoot, flavor).listFiles()?.forEach { e -> if (e.name != FILE) link(e, File(fl, e.name)) }
        flavorDir = fl
        shadowIfaceLine().takeIf { !it.endsWith(": link") }?.let { logEvent(it); Log.w(TAG, it) }
        val login = HomeLoginStore.forAutoLogin(ctx)
        if (login != null) {
            val f = File(fl, FILE)
            f.writeText("${login.email}\n${login.password}\n")
            Os.chmod(f.absolutePath, 384)          // 0600: this app only
            active = true; consumed = false; writtenAt = System.currentTimeMillis(); capHeld = false
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
    private fun signInTraffic(): Boolean = PreLoginTraffic.trafficIsSignIn(consumed, active, windowSeen, expiredUnread,
        if (LauncherHooks.gameStartSec > 0) System.currentTimeMillis() / 1000 - LauncherHooks.gameStartSec else -1L, NO_FILE_GRACE_S,
        downloadAtRead)

    /** one second's received bytes of the game (the launcher's traffic loop): only [pastLogin]; the delete is [AFTER_READ_MS]'s */
    fun onTraffic(rxBytes: Long, txBytes: Long = -1L) {
        // the login triage: a heavy second before this run's read makes that read a downloading one ([downloadAtRead])
        if (firstReadAt == 0L && rxBytes >= PAST_LOGIN_BYTES) lastHeavyAt = System.currentTimeMillis()
        // the verify pass (#271 20:22:41): a heavy second right AFTER the read is not the sign-in either; the run's traffic stops counting
        // (before the burst check below, so this very second never says "past the login screen")
        val now = System.currentTimeMillis()
        if (!pastLogin && consumed && !downloadAtRead && rxBytes >= PAST_LOGIN_BYTES && PreLoginTraffic.heavyRightAfterRead(readMs, now)) {
            downloadAtRead = true; heavyNoted = true
            val ms = now - readMs
            main.post { logEvent("heavy traffic $ms ms after the game's read ($rxBytes B/s): too soon for its sign-in, the run's traffic is not taken for past the login screen") }
        }
        if (!pastLogin && consumed && downloadAtRead && rxBytes >= PAST_LOGIN_BYTES && !heavyNoted) {
            heavyNoted = true
            main.post { logEvent("heavy traffic after the game's read ($rxBytes B/s): heavy traffic at the read, not taken for past the login screen") }
        }
        main.post {
            worldTraffic(rxBytes); onTrafficSample?.invoke(rxBytes, txBytes)
            val counts = PreLoginTraffic.counts(firstReadAt > 0L, pastLogin, reachedWorld)
            if (counts) preLogin.add(rxBytes, System.currentTimeMillis())?.let { logEvent(it) }
            // issue #215: past the login screen and still downloading before the world: the minute's line and the bottom screen's state
            if (PreLoginTraffic.countsAfter(firstReadAt > 0L, pastLogin, reachedWorld)) afterLogin.add(rxBytes, System.currentTimeMillis())?.let {
                logEvent(it + (runCatching { bottomState?.invoke() }.getOrNull()?.let { s -> "; bottom screen: $s" } ?: ""))
            }
            // issue #132: downloading before the login screen holds the unread file's cap and says "Game downloading"
            val d = PreLoginTraffic.downloading(counts, preLogin.busy)
            if (d) holdFrom = System.currentTimeMillis()
            if (d != downloading) {
                downloading = d
                // the login triage: the read itself ends this line's window, not the download (#248 went on at 500 MB a minute)
                logEvent(when {
                    d -> "the game is downloading before its login screen"
                    firstReadAt > 0L -> "the game reached its login screen while downloading (the download may go on past it)"
                    else -> "the game's download before its login screen ended"
                })
                onChange?.invoke()
            }
        }
        if (!pastLogin && rxBytes >= PAST_LOGIN_BYTES && signInTraffic()) main.post {
            if (!pastLogin) {
                pastLogin = true; pastLoginAt = System.currentTimeMillis()
                logEvent("past the login screen ($rxBytes B/s${if (!consumed) ", no login.txt read" else ""})"); onChange?.invoke()
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
                    val readAt = System.currentTimeMillis()
                    // the login triage: this read's download verdict BEFORE [consumed], so the traffic thread's burst check never sees
                    // a read without it
                    if (first) { downloadAtRead = PreLoginTraffic.busyAtRead(downloading, lastHeavyAt, readAt); heavyNoted = false; readMs = readAt }
                    consumed = true
                    if (first) pastLogin = false          // a return's sign-in: past its login screen again only on the next character select
                    Log.i(TAG, "login.txt read by the client")
                    // timed from the FIRST read only: later reads (the form submission) must not push it further out
                    if (first) {
                        firstReadAt = readAt
                        logEvent("login.txt read by the client, ${(firstReadAt - writtenAt) / 1000} s after it was written")
                        // the verify pass: say which (a heavy second may be the login screen's own one-off fetch, not a download)
                        if (downloadAtRead) logEvent(if (downloading) "the game was downloading as it read the saved sign-in: its traffic is not taken for past the login screen"
                            else "a heavy second came just before the game's read (a download or the login screen's own fetch): its traffic is not taken for past the login screen")
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
        val login = HomeLoginStore.forAutoLogin(ctx) ?: run { logEvent("out of the world ($why); no saved login, nothing written"); return }
        try {
            val f = File(fl, FILE)
            f.writeText("${login.email}\n${login.password}\n")
            Os.chmod(f.absolutePath, 384)          // 0600: this app only
        } catch (t: Throwable) { Log.w(TAG, "re-login file failed: ${t.message}"); clear(); return }
        active = true; consumed = false; firstReadAt = 0L; writtenAt = System.currentTimeMillis(); capHeld = false
        downloadAtRead = false; lastHeavyAt = 0L; heavyNoted = false; readMs = 0L         // the login triage: the world's traffic is not a download
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
        pastLogin = true; pastLoginAt = System.currentTimeMillis()
        logEvent("past the login screen ($why)"); onChange?.invoke()
    }

    /**
     * THE GAME'S WINDOW APPEARED (the launcher's X server mapped the client's window: XServerScreen's onMapWindow, the game window): the
     * login screen is not far now, so this run's file is written AGAIN with a fresh 5-minute clock - unless the client already read
     * it. Covers a slow first boot whose file was deleted unread at the cap, or would be soon. Once per client run; the same rules
     * (private 0600, deleted 20 s after the first read). Any thread.
     */
    fun gameWindowUp() = main.post {
        windowSeen = true
        if (windowArmed || consumed || flavorDir == null) return@post
        windowArmed = true
        val ctx = appCtx ?: return@post
        val fl = flavorDir ?: return@post
        val login = HomeLoginStore.forAutoLogin(ctx) ?: return@post
        main.removeCallbacks(timeout)
        try {
            val f = File(fl, FILE)
            f.writeText("${login.email}\n${login.password}\n")
            Os.chmod(f.absolutePath, 384)          // 0600: this app only
        } catch (t: Throwable) { Log.w(TAG, "window file failed: ${t.message}"); clear(); return@post }
        active = true; consumed = false; firstReadAt = 0L; writtenAt = System.currentTimeMillis(); lifeMs = MAX_LIFE_MS; capHeld = false
        downloadAtRead = false; readMs = 0L
        main.postDelayed(timeout, MAX_LIFE_MS)
        logEvent("login.txt written (game window up, ${(writtenAt - playAt) / 1000} s after Play)")
        changed()
    }

    /**
     * The report's "Before the login screen" line (issues #132 / #42 / #57): whether the window is up and the file was read, and what the
     * client received before that read - minutes of megabytes = the game downloading its data; zero with the window up = a login screen
     * that never read the file.
     */
    fun preLoginLine(): String =
        "window up $windowSeen; login.txt written $active, read ${firstReadAt > 0L}, deleted unread at its cap $expiredUnread; " +
            "past the login screen $pastLogin; heavy traffic at the read $downloadAtRead; received before the read: ${PreLoginTraffic.mb(preLogin.total)} MB " +
            "(${PreLoginTraffic.mb(preLogin.lastMinute)} MB in the last whole minute)"

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
        if (downloading) { downloading = false; changed() }
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
        // issue #230 (1.0.28 ANR while the game loaded, right after "sign-in: keyboard (bottom) closed" was logged on the main thread):
        // the append went through FUSE (/sdcard) on the caller's thread, often the main one, while the game read its data through the
        // same FUSE. Now the line is queued and the background log writer appends it (BgAppend, as lat.log / taps.log in 1.0.29)
        runCatching { loginLog.add("${stamp(System.currentTimeMillis())} $s") }
    }

    /** login.log's writer: appends on BgAppend's thread, the cap kept there */
    private val loginLog = BgAppend({ File("/sdcard/WowCompanion/logs/login.log") }, write = { f, lines, _ ->
        f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        f.appendText(lines.joinToString("\n", postfix = "\n"))
        logWrites += lines.size
        if (logWrites >= 25) { logWrites = 0; cap(f) }
    })

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
                val r = runCatching { ShadowRepair.repair(e, File(shared, e.name), bak(), isLink, gameRoot) }.getOrElse { ShadowRepair.Result(e.name, "real (repair failed: ${it.message})") }
                logEvent(r.line()); Log.i(TAG, r.line())
                if (e.name == "WTF" && dir == fl) wtfSaid = true
            }
        }
        // (a folder that is the shared one's is never walked: test57)
        if (!ShadowRepair.inside(root, gameRoot)) each(root, gameRoot, setOf(flavor)) else logEvent("shadow: REFUSED - the shadow root is inside the shared game folder")
        if (!ShadowRepair.inside(fl, gameRoot)) each(fl, File(gameRoot, flavor), setOf(FILE)) else logEvent("shadow $flavor: REFUSED - it is the shared game folder's own")
        if (!wtfSaid) logEvent(ShadowRepair.Result("WTF", "missing").line())
    }

    /** the clients' flavor folders (never linked into the shadow root, never repaired from it) */
    private val FLAVORS = WowProduct.values().map { it.flavorDir }.toSet()

    private fun link(target: File, at: File) {
        if (Files.isSymbolicLink(at.toPath())) at.delete()
        else if (at.exists()) return          // something the client made here itself (private): kept, never replaced
        Os.symlink(target.absolutePath, at.absolutePath)
    }
}
