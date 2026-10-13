package com.jrees.wowcompanion

import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the WoW client's own log and reassembles the addon's JSON messages.
 *
 * WHY POLLING AND NOT FileObserver: the file is written by a Wine process under GameNative and
 * now lives in that app's private data directory, which this app can only reach through `su` --
 * inotify is not available across that boundary at all. Polling is cheap here because the addon
 * only writes when something changed, and each poll asks for the size before any bytes.
 *
 * MEASURED FACTS THIS CLASS EXISTS TO HANDLE:
 *  - General.log is block-buffered at ~48 KiB, so data arrives in BURSTS, not a trickle. A
 *    single read can yield tens of thousands of lines; parse off the UI thread.
 *  - The file is TRUNCATED TO ZERO every time the client launches. Detect that (size < offset)
 *    and resync from 0 rather than reading garbage.
 *  - The engine's own [N][Lua] chatter shares the file, hence the WCB1 magic prefix.
 *  - Long payloads are fragmented as part i/n and must be reassembled before parsing.
 *  - The addon's sequence number increments BEFORE each write, so a gap in seq means a line
 *    was refused (a secret leaked into it) -- surface that rather than silently losing state.
 */
class LogTail(
    initialPath: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    /** the logs to follow (LogFollow), the first read first; null = DEFAULT_PATH's pair (GameNative + WoW Forever's usual folder).
     *  The private share passes the ONE log GameLocator found on the device (Home, 2026-10-02: no hard-coded paths there). */
    follow: List<String>? = null,
) {
    /** the log being read: DEFAULT_PATH follows whichever game wrote last (LogFollow); any other path is read as given */
    private var path = initialPath
    private val candidates = follow?.takeIf { it.isNotEmpty() }
        ?: if (initialPath == DEFAULT_PATH) listOf(DEFAULT_PATH, WOWFOREVER_PATH) else listOf(initialPath)
    private var followAt = 0L
    private val thread = HandlerThread("logtail").apply { start() }
    private val handler = Handler(thread.looper)
    private var offset = 0L
    private var carry = ""                       // partial trailing line between reads
    private var running = false

    private val frags = HashMap<Int, MutableList<String?>>()   // seq of part 1 -> parts
    private var lastSeq = -1
    private var gaps = 0

    fun start() {
        running = true
        handler.post(pump)
    }

    fun stop() {
        running = false
        try { shell?.destroy() } catch (_: Throwable) {}
        shell = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private val pump = object : Runnable {
        override fun run() {
            if (!running) return
            try { follow(); poll() } catch (t: Throwable) { onStatus("tail error: ${t.message}") }
            // smoothness B: every 20 ms inside the launcher (plain IO there is cheap), 50 ms in the app as before
            if (running) handler.postDelayed(this, if (RootPolicy.allowed) POLL_MS else 20L)
        }
    }

    /**
     * The log now lives inside GameNative's private data directory, which this app cannot open
     * with plain file IO however many permissions it holds -- another app's data dir is not
     * reachable without root. So a path under /data/ is read through `su` instead. Root is
     * already a hard requirement here (input injection needs it), so this adds no new one.
     */
    // The second game, the standalone "WoW Forever" app, keeps its game folder on SHARED storage
    // (/storage/emulated/0/WoW Forever/...). This app can open that with plain IO (All files access), but the root reader stays the
    // first choice there too: it alone carries the backlog cap, the seeking read and the reclaim with its kept copies. Plain IO is
    // the fallback when root reads keep failing (tracking: "keep su as a fallback" - here the other way round, for those reasons).
    // RootPolicy: inside the fork (no root there) shared storage is read with plain IO only
    private val needsRoot get() = RootPolicy.allowed && (path.startsWith("/data/") || path.startsWith("/storage/emulated/"))
    private var rootFails = 0
    private var plainSaid = false

    /**
     * Every FOLLOW_MS: the candidates' last-modified times (one line through the same root shell, or plain IO when root reads
     * fail), and a switch to the log written most recently (LogFollow). A switch starts the new log from its end-capped backlog,
     * exactly as a client restart does.
     */
    private fun follow() {
        if (candidates.size < 2) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (followAt != 0L && now - followAt < FOLLOW_MS) return
        followAt = now
        val mt = (if (rootFails < ROOT_GIVE_UP) rootMtimes() else null)
            ?: candidates.associateWith { p -> File(p).let { if (it.exists()) it.lastModified() / 1000 else -1L } }
        val next = LogFollow.pick(path, mt)
        if (next == path) return
        path = next
        offset = 0; carry = ""; frags.clear(); lastSeq = -1
        reclaimedTo = -1; reclaimAt = 0
        onStatus("following the game's log: ${if (next == DEFAULT_PATH) "GameNative" else "WoW Forever"}")
        android.util.Log.i("WCLOG", "following $next (mtimes $mt)")
    }

    private fun rootMtimes(): Map<String, Long>? {
        val p = ensureShell() ?: return null
        val d = "$"
        // bounded (Map's review): a stat hung on busy shared storage must not hang this thread, and with it every document
        val probes = candidates.joinToString(":") { "${d}(timeout 1 stat -c %Y '$it' 2>/dev/null || echo -1)" }
        return try {
            p.outputStream.write("echo \"WCMT:$probes\"\n".toByteArray()); p.outputStream.flush()
            val ins = p.inputStream
            val line = StringBuilder()
            while (true) {
                val b = ins.read()
                if (b < 0) { shell = null; return null }
                if (b == '\n'.code) break
                line.append(b.toChar())
            }
            LogFollow.parse(line.toString(), candidates)
        } catch (t: Throwable) { shell = null; null }
    }

    // ---- ONE PERSISTENT ROOT SHELL, not one per poll.
    // Polling used to spawn `su` every 250 ms. That works, but a fork is ~10-20 ms of real work
    // and the cost scales with the refresh rate -- at the 100 ms poll the map now needs, it is
    // ten Magisk grants a second purely to ask "has the file grown?". One long-lived shell
    // costs a single grant for the whole session and makes the poll almost free, which is what
    // lets the poll interval come down at all.
    //
    // --mount-master is NOT optional. Magisk runs su in the CALLING app's mount namespace
    // ("su: use namespace of pid=..."), and Android's app-data isolation blanks out other
    // packages' /data/data there -- from inside this app's namespace /data/data/app.gamenative
    // does not exist at all. su is granted, the stat simply finds nothing, and the room sits on
    // "waiting for the game" forever.
    private var shell: Process? = null

    private fun ensureShell(): Process? {
        val s = shell
        if (s != null && s.isAlive) return s
        if (!RootPolicy.allowed) return null
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "--mount-master")).also { shell = it }
        } catch (t: Throwable) {
            onStatus("root shell failed: ${t.message}"); shell = null; null
        }
    }

    /**
     * size, then exactly the bytes past [from].
     *
     * The shell announces a BYTE COUNT and then emits exactly that many bytes, so the reply is
     * framed and the reader never has to scan for a sentinel inside the payload -- a sentinel
     * could in principle occur in the log text, and `head -c N` also caps the read if the file
     * grew between the stat and the tail.
     * stderr is silenced on every command: anything it printed would land in the middle of the
     * byte stream and corrupt the very fragments this class exists to reassemble.
     *
     * @return (file size, the offset the bytes ACTUALLY start at, the bytes), or null.
     *
     * TWO THINGS THIS HAS TO GET RIGHT, both learned from a 296 MB log that was 94% hole:
     *
     * THE BACKLOG CAP LIVES IN THE SHELL, NOT AFTER THE READ. This used to probe with the
     * current offset first and apply the MAX_READ skip only afterwards -- so a fresh start
     * (offset 0) against a large log asked for the WHOLE file in one ByteArray. Harmless while
     * the log was small at launch; once the reclaim made the apparent size permanent it was a
     * 296 MB allocation, an OutOfMemoryError on every poll, and a dead map. The shell now
     * moves the start forward itself and reports where it actually began.
     *
     * READ BY SEEKING. `tail -c +N` reads THROUGH everything before N, and after a reclaim most
     * of the file is a sparse hole: measured 407 ms per poll, on a poll meant to run every
     * 50 ms. `dd iflag=skip_bytes,count_bytes` seeks, is byte-exact (checked against tail on
     * the live log) and took 44 ms for 50 KB.
     */
    private fun rootRead(from: Long): Triple<Long, Long, ByteArray>? {
        val p = ensureShell() ?: return null
        val d = "$"          // so the shell's own $ never collides with Kotlin's templates
        val script =
            "S=${d}(timeout 1 stat -c %s '$path' 2>/dev/null || echo -1); F=$from; " +
                "if [ ${d}S -gt ${d}F ]; then N=${d}((S-F)); else N=0; fi; " +
                "if [ ${d}N -gt $MAX_READ ]; then F=${d}((S-$MAX_READ)); N=$MAX_READ; fi; " +
                "echo \"WCSZ:${d}S:${d}N:${d}F\"; " +
                // EXACTLY N bytes, always (2026-10-06 20:49, the user: "the companion app Greys out and won't reconnect until I
                // restart the app"): a reclaim empties the file in the background, and when that lands between the stat above and
                // this read, dd stops early, the reader below waited for bytes that never came and the poll thread hung for good.
                // Short output is padded with NULs (feed() drops them; the next poll sees the shrunken size and resyncs)
                "[ ${d}N -gt 0 ] && { dd if='$path' iflag=skip_bytes,count_bytes " +
                "skip=${d}F count=${d}N bs=65536 2>/dev/null; cat /dev/zero 2>/dev/null; } | head -c ${d}N\n"
        // and a watchdog: any read that has not finished in READ_TIMEOUT_MS kills the shell, so the blocked read returns and the
        // next poll opens a fresh one - a hang can no longer outlive a few seconds
        val dog = watchdog.schedule({ try { p.destroy() } catch (_: Throwable) {} }, READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return try {
            val out = p.outputStream
            out.write(script.toByteArray()); out.flush()
            val ins = p.inputStream

            // header line, read a byte at a time -- it is ~30 bytes and must not over-read
            val hdr = StringBuilder()
            while (true) {
                val b = ins.read()
                if (b < 0) { shell = null; return null }
                if (b == '\n'.code) break
                hdr.append(b.toChar())
            }
            val parts = hdr.toString().trim().removePrefix("WCSZ:").split(':')
            if (parts.size != 3) return null
            val size = parts[0].toLongOrNull() ?: return null
            val n = parts[1].toIntOrNull() ?: return null
            val start = parts[2].toLongOrNull() ?: return null
            if (n <= 0) return Triple(size, start, ByteArray(0))

            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = ins.read(buf, got, n - got)
                if (r < 0) { shell = null; return null }
                got += r
            }
            Triple(size, start, buf)
        } catch (t: Throwable) {
            shell = null
            onStatus("root read failed: ${t.message}"); null
        } finally {
            dog.cancel(false)
        }
    }

    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "logtail-dog").apply { isDaemon = true } }

    private fun poll() {
        // a path plain IO cannot open (another app's /data/data) is root's only; a shared-storage one falls back after ROOT_GIVE_UP
        // failed root reads in a row (su denied or gone), once said in the status line
        if (needsRoot && (rootFails < ROOT_GIVE_UP || path.startsWith("/data/"))) { pollRoot(); return }
        if (needsRoot && !plainSaid) { plainSaid = true; onStatus("root reads failing: reading the log directly") }
        val f = File(path)
        if (!f.exists()) { onStatus("waiting for game"); return }
        val len = f.length()
        if (len < offset) {                       // client relaunched: file truncated
            offset = 0; carry = ""; frags.clear(); lastSeq = -1
            onStatus("client restarted, resyncing")
        }
        if (len == offset) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray((len - offset).coerceAtMost(MAX_READ).toInt())
            val n = raf.read(buf)
            if (n <= 0) return
            offset += n
            feed(buf, n)
        }
    }

    // ---- RECLAIMING THE LOG ---------------------------------------------------------------
    // This file grows while the player moves and the client only wipes it at launch, so a long
    // session used to leave a 65 MB log behind and climbing.
    //
    // Truncating it was tried before and abandoned with the note "DO NOT TRUNCATE THIS LOG".
    // That note had the mechanism right and the DIAGNOSIS wrong, and the difference is the
    // whole fix. WoW does not hold this file with O_APPEND -- it keeps its own file offset --
    // so emptying the file leaves that offset alone and the client's next write lands 65 MB in,
    // springing the APPARENT size straight back. The old code then re-fired on apparent size,
    // truncated again, and did that every poll forever.
    //
    // But a sparse file's hole costs no blocks. The apparent size is a lie and always was; the
    // space really is freed. So trigger on BYTES WRITTEN SINCE THE LAST RECLAIM instead of on
    // apparent size, and the loop cannot happen: each reclaim buys another RECLAIM_BYTES of
    // real growth. Allocated space stays bounded, the apparent size drifts upward harmlessly,
    // and the reader just keeps reading past the hole from the offset it already had.
    private var reclaimedTo = 0L      // apparent length at the last reclaim
    private var reclaimAt = 0L        // when, so a real client restart is still distinguishable
    private val RECLAIM_BYTES = 24L * 1024 * 1024

    /** Free the blocks behind us. The client's write offset is untouched, so new data still
     *  lands at `len` and our own offset stays valid -- nothing needs to resync.
     *
     *  KEEP WHAT IS FREED (tracking's yes, 2026-09-30: a reclaim had erased the minutes a bug happened in): first the last
     *  RECLAIM_BYTES of the log go to KEEP_DIR/General.prev.log (the one before to General.prev2.log), NULs of the hole left out,
     *  THEN the file is emptied. All in a background subshell with every output thrown away: this shell's stdout is the framed
     *  byte stream rootRead parses, and a 24 MB copy must not hold up the next read. A few ms of log written between the copy
     *  and the emptying are not kept. */
    private fun reclaim(len: Long) {
        val p = ensureShell() ?: return
        try {
            p.outputStream.write(("( mkdir -p $KEEP_DIR; mv -f $KEEP_DIR/General.prev.log $KEEP_DIR/General.prev2.log; " +
                "tail -c $RECLAIM_BYTES '$path' | tr -d '\\000' > $KEEP_DIR/General.prev.log; : > '$path' ) " +
                "</dev/null >/dev/null 2>&1 &\n").toByteArray())
            p.outputStream.flush()
            reclaimedTo = len
            offset = len
            reclaimAt = android.os.SystemClock.elapsedRealtime()
            onStatus("log reclaimed at ${len / 1024 / 1024} MB")
        } catch (t: Throwable) { shell = null }
    }

    private fun pollRoot() {
        // Probe with the CURRENT offset so a quiet poll transfers nothing at all.
        val (len, start, buf) = rootRead(offset) ?: run { rootFails++; return }
        rootFails = 0
        if (len < 0) { onStatus("waiting for game"); return }
        if (len < offset) {
            // Just after a reclaim the file reads as 0 until the client's next write refills
            // the hole. That is expected and must NOT be mistaken for a relaunch -- resyncing
            // there would throw away the offset that is about to become correct again.
            val since = android.os.SystemClock.elapsedRealtime() - reclaimAt
            if (reclaimAt != 0L && since < 5_000L) return
            offset = 0; carry = ""; frags.clear(); lastSeq = -1
            // NOT 0: the next read's apparent size (a sparse ~90 MB right after a reclaim) would reclaim AGAIN at once, and again
            // after every 5 s guard - measured 2026-09-30 20:33-20:35, the log read as 0 bytes for 2.5 min and the app saw nothing.
            // "Unknown": the next read takes the length it sees, and the next reclaim needs RECLAIM_BYTES of real growth
            reclaimedTo = -1; reclaimAt = 0
            onStatus("client restarted, resyncing")
            return                                // next poll re-reads from 0
        }
        reclaimAt = 0
        // the baseline comes from the first length the game has written to again (0 = still the emptied file: keep waiting)
        if (reclaimedTo < 0 && len > 0) reclaimedTo = len
        // The shell skipped a backlog bigger than MAX_READ: resume from where it really began.
        if (start != offset) { offset = start; carry = "" }
        if (buf.isEmpty()) return
        offset += buf.size
        feed(buf, buf.size)
        if (reclaimedTo >= 0 && len - reclaimedTo > RECLAIM_BYTES) reclaim(len)
    }

    /** split a freshly read chunk into lines, carrying any partial trailing line forward */
    private fun feed(buf: ByteArray, n: Int) {
        // A read straddling the edge of a reclaimed hole starts with NULs, and a NUL run has no
        // newline, so without this it would glue itself onto the first real line.
        val text = carry + String(buf, 0, n, Charsets.UTF_8).replace("\u0000", "")
        val lines = text.split('\n')
        // the last element is a partial line unless the chunk ended on a newline
        carry = if (text.endsWith("\n")) "" else lines.last()
        for (i in 0 until lines.size - if (carry.isEmpty()) 0 else 1) {
            handleLine(lines[i])
        }
    }

    private fun handleLine(rawIn: String) {
        // The client is a Windows app under Wine, so General.log uses CRLF. Splitting on '\n'
        // leaves a '\r' on the tail of every line -- invisible, and it lands exactly at each
        // fragment boundary, corrupting the reassembled JSON there. Strip it.
        val raw = rawIn.trimEnd('\r', '\n')
        val i = raw.indexOf(MAGIC)
        if (i < 0) return                          // engine chatter, ignore
        val body = raw.substring(i + MAGIC.length) // "<seq>|<part>/<n>|<payload>"
        if (body.startsWith("pad|")) return        // flush padding

        val p1 = body.indexOf('|'); if (p1 < 0) return
        val seq = body.substring(0, p1).toIntOrNull() ?: return
        val p2 = body.indexOf('|', p1 + 1); if (p2 < 0) return
        val partSpec = body.substring(p1 + 1, p2)
        val payload = body.substring(p2 + 1)

        if (lastSeq >= 0 && seq != lastSeq + 1) {
            gaps++
            onStatus("gap at seq $seq (expected ${lastSeq + 1})")
        }
        lastSeq = seq

        val slash = partSpec.indexOf('/')
        if (slash < 0) return
        val idx = partSpec.substring(0, slash).toIntOrNull() ?: return
        val total = partSpec.substring(slash + 1).toIntOrNull() ?: return

        val gt = gameTime(raw, i)
        if (total == 1) { emit(payload, gt); return }

        val key = seq - (idx - 1)                  // all parts of one document share this
        val slots = frags.getOrPut(key) { MutableList(total) { null } }
        if (idx in 1..slots.size) slots[idx - 1] = payload
        if (slots.all { it != null }) {
            frags.remove(key)
            emit(slots.joinToString("") { it!! }, gt)
        }
    }

    /**
     * The line's own timestamp from the log prefix ("9/27 08:41:53.221  [N][Lua] "), as ms on a
     * day-of-year + time-of-day scale, or -1 if it does not parse.
     *
     * WHY THE APP NEEDS THE GAME'S CLOCK. The addon writes a position line every ~107 ms but
     * the log only reaches us when a flush forces it out, ~every 216 ms, so 95% of position
     * lines arrive TWO AT A TIME in one read (measured 2026-09-27 over a real session). Timed
     * by arrival, both lines look simultaneous: the map kept the older one stamped as current
     * and threw most of the newer one away, and the arrow's speed estimate was wrong by half
     * again -- which the prediction turned into overshoot and snap-back. The log stamps every
     * line when the game wrote it, to the millisecond; that is the real spacing.
     * Only differences matter (the map maps it onto its own clock), so the year is not needed;
     * a year boundary is one backwards jump the map treats as a clock reset.
     */
    private fun gameTime(raw: String, magicAt: Int): Long {
        return try {
            val head = raw.substring(0, magicAt).trim()
            val sp = head.indexOf(' ')
            val date = head.substring(0, sp)
            val time = head.substring(sp + 1).trim().substringBefore(' ')
            val slash = date.indexOf('/')
            val mon = date.substring(0, slash).toInt()
            val day = date.substring(slash + 1).toInt()
            val c1 = time.indexOf(':'); val c2 = time.indexOf(':', c1 + 1)
            val h = time.substring(0, c1).toInt()
            val mi = time.substring(c1 + 1, c2).toInt()
            val secMs = (time.substring(c2 + 1).toDouble() * 1000.0).toLong()
            val doy = DAYS_BEFORE[(mon - 1).coerceIn(0, 11)] + day
            ((doy * 24L + h) * 60L + mi) * 60_000L + secMs
        } catch (t: Throwable) { -1L }
    }

    private fun emit(json: String, gt: Long) {
        try {
            val o = JSONObject(json)
            if (gt >= 0) o.put("_gt", gt)
            onMessage(o)
        }
        catch (t: Throwable) { onStatus("bad json (${json.length}b): ${t.message}") }
    }

    fun gapCount() = gaps

    companion object {
        const val MAGIC = "WCB1|"
        private val DAYS_BEFORE = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        /** 100 ms: the addon flushes every 0.15 s, so a slower poll would be the ceiling
         *  rather than the flush. Affordable only because the root shell is persistent. */
        // 50 ms. It was 100 while each read cost 400 ms reading through a sparse hole, so a
        // shorter poll bought nothing. A quiet poll is now one stat (~11 ms) and a flush-sized
        // read ~44 ms, so halving this takes up to 50 ms off the arrow's lag for free.
        const val POLL_MS = 50L
        const val MAX_READ = 8L * 1024 * 1024
        /** a root read longer than this is a hang: the shell is killed and re-opened (an 8 MB read takes well under a second) */
        const val READ_TIMEOUT_MS = 3_000L
        // The install lives INSIDE the container's private storage, not on /sdcard. It was moved
        // there because Battle.net's Agent cannot create a TACT container on the FUSE-backed
        // emulated storage -- create_folder fails with error 2123 -- so the game could never be
        // validated and Battle.net would never hand WoW a login token. Internal storage works.
        // Reading this path needs root, which the app already holds for input injection.
        /** where a reclaim keeps the log it frees (two generations), for reading a bug back afterwards */
        const val KEEP_DIR = "/sdcard/WowCompanion/logs"
        /** root reads failing this many polls in a row (50 ms each: 1 s) on a shared-storage path -> plain IO */
        const val ROOT_GIVE_UP = 20
        /** how often the reader checks which game's log was written last */
        const val FOLLOW_MS = 3_000L
        /** the GameNative install's log: THE MAIN GAME, read first at every start */
        const val DEFAULT_PATH =
            "/data/data/app.gamenative/files/imagefs_shared/home/xuser-CUSTOM_GAME_178249507" +
                "/games/WoW-Forever-beta/_classic_beta_/Logs/General.log"
        // 2026-10-02 (the user via tracking: "I dont want to pivot the whole app to it, I would just like to try it with both"): the
        // standalone "WoW Forever" app (app.wowforever) runs the same client build with its game folder on SHARED storage. Its log is
        // the second candidate, read while ITS game is the one writing (LogFollow); GameNative stays the main target.
        const val WOWFOREVER_PATH = GamePaths.OLD_DEFAULT_LOG
    }
}
