package com.jrees.wowcompanion

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.view.Display
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * WATCHES THE GAME'S CLIENT AND LOG for our own Battle.net sign-in (LATER 29). The addons do not run before the world, so LogTail
 * (which reads only the addons' WCB1 lines) knows nothing there: this reads the engine's own lines from the same General.log, through
 * its own root shell, once a second before the world and once every [WORLD_TICK_MS] in it (only the client's state, no log reading).
 *
 * WHICH GAME: of those given ([logs]: log path -> the Android package that runs it), one whose CLIENT runs (a wineserver under the
 * package's own Linux user: Android keeps a closed app's process cached, so the app's pid proves nothing), the one whose log was written
 * last (LogFollow's rule). A game whose client does not run has no login screen, whatever its old log says.
 *
 * A NEW CLIENT RUN = a new wineserver pid. A log that only got SHORTER is LogTail's reclaim (it truncates the log it has read, the
 * review 2026-10-05), not a restart: the reading starts again at 0 and what is known is kept.
 *
 * Reads by seeking (`dd iflag=skip_bytes,count_bytes`: LogTail measured `tail -c +N` reading through a 296 MB mostly-sparse log), at
 * most [MAX_READ] at a time, base64 on one line so the reply needs no framing; every command is time-limited and stderr is silenced
 * for the whole shell, so nothing can leave the reply short or long.
 */
class HomeLoginWatch(
    private val logs: () -> List<Pair<String, String>>,
    /** main thread, whenever what the waiting screen draws changed */
    private val onChange: (HomeLogin.State) -> Unit,
) {
    val state = HomeLogin.State()
    private val thread = HandlerThread("loginwatch").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var shell: Process? = null
    private var out: DataOutputStream? = null
    private var reader: BufferedReader? = null
    @Volatile private var running = false
    private var path: String? = null
    private var client = 0L
    private var clientSeenAt = 0L
    /** the part of the log that is NOT this client run's (a previous run's log not yet emptied); 0 once it is */
    private var runBase = 0L
    private var offset = 0L
    private var carry = ""
    /** polls in a row that saw [HomeLogin.loggedOut] */
    private var outPolls = 0
    /** Connection.log (p-auth-keyboard): read beside General.log until past the login screen; -1 = not placed yet this client run */
    private var connOffset = -1L
    private var connCarry = ""
    private var clientStartSec = 0L

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    /** for good (the Activity ends): the shell closes and the thread goes */
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        handler.post { close(); thread.quitSafely() }
    }

    /** runs [block] on the watcher's thread (the state is changed only there), then tells the main thread */
    fun change(block: (HomeLogin.State) -> Unit) { handler.post { block(state); publish() } }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { poll() } catch (_: Throwable) { close() }
            if (running) handler.postDelayed(this, if (state.inWorld) WORLD_TICK_MS else TICK_MS)
        }
    }

    private fun poll() {
        val d = "$"
        val games = logs()
        // one line per game: "<the client's wineserver pid, 0 = none> <size> <mtime> <realm connections>", in the order given. REALM =
        // established TCP connections of the package's uid to remote port 3724 (0E8C), the WoW realm server: up = signed in (measured
        // 2026-10-05 at character select; none on the login screen). Read from /proc/net, the OS's own table - not the game
        val probe = games.joinToString("; ") { (p, pkg) ->
            "U=${d}(stat -c %U /data/data/$pkg); N=${d}(stat -c %u /data/data/$pkg); " +
                "W=${d}(timeout 1 ps -A -o PID=,USER=,NAME= | grep -E \" ${d}U +wineserver${d}\" | head -1 | tr -s ' ' | sed 's/^ //' | cut -d' ' -f1); " +
                "R=${d}(cat /proc/net/tcp /proc/net/tcp6 | grep -cE \"^ *[0-9]+: [0-9A-F]+:[0-9A-F]+ [0-9A-F]+:0E8C 01 [0-9A-F]+:[0-9A-F]+ [0-9A-F]+:[0-9A-F]+ [0-9A-F]+ +${d}N \"); " +
                "H=${d}(cat /proc/net/tcp /proc/net/tcp6 | grep -cE \"^ *[0-9]+: [0-9A-F]+:[0-9A-F]+ [0-9A-F]+:01BB 01 [0-9A-F]+:[0-9A-F]+ [0-9A-F]+:[0-9A-F]+ [0-9A-F]+ +${d}N \"); " +
                "S=0; [ -n \"${d}W\" ] && S=${d}(stat -c %Y /proc/${d}W || echo 0); " +
                // GAME SERVERS = established connections of the uid to anything but HTTPS and loopback (wine talks to itself there)
                "G=${d}(cat /proc/net/tcp /proc/net/tcp6 | awk -v u=${d}N '${d}8==u && ${d}4==\"01\" {split(${d}3,a,\":\"); " +
                "if (a[2]!=\"01BB\" && a[1] !~ /0100007F${d}/ && a[1]!=\"00000000000000000000000001000000\") c++} END {print c+0}'); " +
                "echo \"${d}{W:-0} ${d}(timeout 1 stat -c '%s %Y' '$p' || echo '-1 0') ${d}{R:-0} ${d}{H:-0} ${d}{S:-0} ${d}{G:-1} ${d}(date +%s)\""
        }
        val lines = if (RootPolicy.allowed) ask(probe, games.size) ?: return else noRoot(games)
        // the game whose client runs and whose log was written last: (index into games, pid, size, mtime, realm connections)
        var best: LongArray? = null
        games.forEachIndexed { i, _ ->
            val f = lines.getOrNull(i)?.trim()?.split(' ')?.mapNotNull { it.toLongOrNull() } ?: return@forEachIndexed
            if (f.size < 3 || f[0] <= 0L || f[1] < 0) return@forEachIndexed
            if (best == null || f[2] > best!![3])
                best = longArrayOf(i.toLong(), f[0], f[1], f[2], f.getOrElse(3) { 0L }, f.getOrElse(4) { 0L }, f.getOrElse(5) { 0L },
                    f.getOrElse(6) { -1L }, f.getOrElse(7) { 0L })
        }
        val b = best
        if (b == null) {                                   // no game client runs: no login screen, no row
            path = null; client = 0L
            if (state.clientUp || state.screen != null || state.phase != HomeLogin.Phase.IDLE) { state.reset(); publish() }
            return
        }
        val (p, pkg) = games[b[0].toInt()]
        val pid = b[1]
        val size = b[2]
        if (p != path || pid != client) {                  // another game, or a new run of its client (its log starts empty)
            path = p; client = pid; carry = ""; state.reset(); clientSeenAt = android.os.SystemClock.elapsedRealtime(); outPolls = 0
            connOffset = -1L; connCarry = ""; clientStartSec = b[6]
            // A LOG OLDER THAN THIS CLIENT is the previous run's (2026-10-05 22:01, b46: the new client was seen ~5 s before it emptied
            // the log, the old in-world log was read, and "in the world" latched for the new run - no Login row): skipped, and it does
            // not count as size, until the client empties it (then everything in it is this run's)
            val started = b[6]
            if (started > 0L && b[3] < started) { offset = size; runBase = size } else { offset = maxOf(0L, size - MAX_READ); runBase = 0L }
        } else if (size < offset) {                        // the same client, a shorter log: emptied at its start, or LogTail's reclaim
            offset = 0L; carry = ""; runBase = 0L
        }
        if (size < runBase) runBase = 0L
        // BACK AT THE LOGIN SCREEN in this same run (a logout and Back, a disconnect): no game server and a quiet log, twice in a row.
        // Everything in the log so far is then the earlier part of the run: skipped, as a previous run's log is
        val age = if (b[8] > 0L && b[3] > 0L) b[8] - b[3] else -1L
        if (state.pastLogin && HomeLogin.loggedOut(b[7], age)) {
            if (++outPolls >= 2 && state.backAtLogin()) { outPolls = 0; offset = size; runBase = size; carry = ""; publish() }
        } else outPolls = 0
        // WoW Forever's row shows from now until the world (its login screen writes nothing); a big log = past it
        // the login screen's signal: the client's first HTTPS connection (measured), or the fallback time since it appeared
        val screen = b[5] > 0L || android.os.SystemClock.elapsedRealtime() - clientSeenAt >= HomeLogin.LOGIN_SCREEN_FALLBACK_MS
        if (state.client(true, pkg in HomeLogin.WF_PKGS || pkg == LauncherHooks.pkg, size - runBase, b[4] > 0L, screen, age >= HomeLogin.LOGIN_AGAIN_QUIET_S))
            publish()
        // the authenticator / a refusal, perhaps sooner from Connection.log than from the block-buffered General.log (p-auth-keyboard)
        if (!state.inWorld && !state.pastLogin) readConnection(p)
        // in the world LogTail reads the log; nothing more to learn here until this client run ends
        if (state.inWorld) { offset = size; carry = ""; return }
        if (size <= offset) return
        val n = minOf(size - offset, MAX_READ)
        val bytes = if (!RootPolicy.allowed) readPlain(p, offset, n) ?: return else {
            val b64 = ask("timeout 2 dd if='$p' iflag=skip_bytes,count_bytes skip=$offset count=$n | base64 -w 0; echo", 1)
                ?.firstOrNull() ?: return
            try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Throwable) { return }
        }
        offset += bytes.size
        val text = carry + String(bytes, Charsets.UTF_8).replace("\u0000", "")
        val cut = text.lastIndexOf('\n')
        carry = if (cut < 0) text.takeLast(4096) else text.substring(cut + 1).takeLast(4096)
        if (cut < 0) return
        var changed = false
        for (line in text.substring(0, cut).split('\n')) {
            val e = HomeLogin.parse(line) ?: continue
            if (e is HomeLogin.Event.Authenticator && state.phase != HomeLogin.Phase.AUTH) NativeLogin.logEvent("sign-in: authenticator line read from General.log")
            if (state.note(e)) changed = true
        }
        if (changed) publish()
    }

    /**
     * CONNECTION.LOG beside General.log (p-auth-keyboard: General.log's authenticatorRequired arrived ~25 s after the read, held in the
     * client's ~48 KiB write buffer). The same "[Connection]" lines, if this file is written sooner; only the authenticator, a refusal
     * and the queue are taken from it (General.log stays the one source of the rest). Plain reads (the launcher has the access).
     */
    private fun readConnection(general: String) {
        val f = java.io.File(general.substringBeforeLast('/') + "/Connection.log")
        if (!f.isFile) return
        val size = f.length()
        if (connOffset < 0L) connOffset = if (clientStartSec > 0L && f.lastModified() / 1000 < clientStartSec) size else 0L
        if (size < connOffset) { connOffset = 0L; connCarry = "" }
        if (size <= connOffset) return
        val bytes = readPlain(f.path, connOffset, minOf(size - connOffset, MAX_READ)) ?: return
        connOffset += bytes.size
        val text = connCarry + String(bytes, Charsets.UTF_8).replace("\u0000", "")
        val cut = text.lastIndexOf('\n')
        connCarry = if (cut < 0) text.takeLast(4096) else text.substring(cut + 1).takeLast(4096)
        if (cut < 0) return
        var changed = false
        for (e in HomeLogin.connectionEvents(text.substring(0, cut))) {
            if (e is HomeLogin.Event.Authenticator && state.phase != HomeLogin.Phase.AUTH) NativeLogin.logEvent("sign-in: authenticator line read from Connection.log")
            if (state.note(e)) changed = true
        }
        if (changed) publish()
    }

    private fun publish() = main.post { onChange(state) }

    /**
     * THE SAME PROBE LINES WITHOUT ROOT (inside the private launcher, S4): the launcher's own game stands in for the wineserver (its
     * start second is the "pid", so a new run is a new client), the log's size / time by plain IO (shared storage, All-files access).
     * No network counts: /proc/net is closed to every app, so the login screen comes from the fallback time and the world from the log.
     */
    private fun noRoot(games: List<Pair<String, String>>): List<String> {
        val now = System.currentTimeMillis() / 1000
        return games.map { (p, pkg) ->
            val up = pkg == LauncherHooks.pkg && LauncherHooks.gameUp()
            val f = java.io.File(p)
            val size = if (f.isFile) f.length() else -1L
            val mtime = if (f.isFile) f.lastModified() / 1000 else 0L
            val run = if (up) LauncherHooks.gameStartSec.coerceAtLeast(1L) else 0L
            "$run $size $mtime 0 0 ${if (up) LauncherHooks.gameStartSec else 0L} -1 $now"
        }
    }

    private fun readPlain(p: String, from: Long, n: Long): ByteArray? = try {
        java.io.RandomAccessFile(p, "r").use { r -> r.seek(from); val b = ByteArray(n.toInt()); val got = r.read(b); if (got <= 0) null else b.copyOf(got) }
    } catch (_: Throwable) { null }

    /** runs [script] in the root shell; [lines] lines of answer, or null (the shell is then rebuilt next time) */
    private fun ask(script: String, lines: Int): List<String>? {
        val o = out ?: open() ?: return null
        val r = reader ?: return null
        o.writeBytes("$script\n"); o.flush()
        val got = ArrayList<String>(lines)
        repeat(lines) { got += r.readLine() ?: run { close(); return null } }
        return got
    }

    private fun open(): DataOutputStream? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master"))   // GameNative's log is in its private /data/data
        shell = p; reader = BufferedReader(InputStreamReader(p.inputStream))
        DataOutputStream(p.outputStream).also { out = it; it.writeBytes("exec 2>/dev/null\n"); it.flush() }
    } catch (_: Throwable) { null }

    private fun close() {
        try { out?.writeBytes("exit\n"); out?.flush() } catch (_: Throwable) {}
        try { shell?.destroy() } catch (_: Throwable) {}
        shell = null; out = null; reader = null
    }

    companion object {
        const val TICK_MS = 1_000L
        const val WORLD_TICK_MS = 5_000L
        const val MAX_READ = 256L * 1024
    }
}

/**
 * TYPES THE SAVED LOGIN INTO THE GAME (LATER 29), once per press: [HomeLogin.script] through one root shell on its own worker. The
 * lines go on `su`'s stdin and are never logged; [onDone] gets null when su took them all, else a short reason (main thread).
 */
object HomeLoginRunner {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var busy = false; private set

    fun run(ctx: Context, login: HomeLoginStore.Login, onDone: (String?) -> Unit) {
        if (busy) return
        val size = gameSize(ctx) ?: run { onDone("The top screen's size is not known"); return }
        busy = true
        val main = Handler(Looper.getMainLooper())
        if (InputSink.sink != null) {
            // INSIDE THE PRIVATE LAUNCHER (S4): the same steps typed through its X server, in this process - no shell, no command line
            worker.execute {
                val why = if (!LauncherHooks.inFront()) "The game is not in front on the top screen"
                    else InputSink.signIn(login.email, login.password, HomeLogin.emailAt, HomeLogin.passwordAt, HomeLogin.logInAt, HomeLogin.CLEAR_KEYS)
                busy = false
                main.post { onDone(why) }
            }
            return
        }
        worker.execute {
            val why = try {
                val p = Runtime.getRuntime().exec("su")
                DataOutputStream(p.outputStream).use { o ->
                    o.writeBytes("exec 2>/dev/null\n")
                    for (line in HomeLogin.script(login.email, login.password, size.first, size.second)) o.writeBytes("$line\n")
                    o.writeBytes("exit\n"); o.flush()
                }
                when (p.waitFor()) {
                    0 -> null
                    HomeLogin.NOT_THE_GAME -> "The game is not in front on the top screen"
                    else -> "Root refused the typing"
                }
            } catch (_: Throwable) { "Root is not available" }
            busy = false
            main.post { onDone(why) }
        }
    }

    /**
     * The top screen in the pixels `input -d 0` uses: the panel's PHYSICAL mode, landscape (TapBridge's measured rule: the per-app
     * metrics of display 0 intermittently answer the bottom screen's size on the Thor). Null when it cannot be trusted.
     */
    fun gameSize(ctx: Context): Pair<Int, Int>? = try {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val mode = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val largest = dm?.displays?.maxOfOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight } ?: 0L
        if (mode == null) null else {
            val w = maxOf(mode.physicalWidth, mode.physicalHeight)
            val h = minOf(mode.physicalWidth, mode.physicalHeight)
            if (w <= 0 || h <= 0 || w.toLong() * h < largest) null else w to h
        }
    } catch (_: Throwable) { null }
}
