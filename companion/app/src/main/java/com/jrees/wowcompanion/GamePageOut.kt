package com.jrees.wowcompanion

import android.os.Handler
import android.os.SystemClock
import java.io.File

/**
 * THE GAME CLIENT'S COLD MEMORY INTO ZRAM (the user's pick, 2026-11-23, after the research showed DroidDeck pages out WoW itself:
 * "Yes, carefully (Recommended)" = "Only idle (cold) memory, only at character select before Enter World and when memory is critically
 * low in the world; capped; same setting (on for 8 GB). Loads our code into the WoW process (it only tells the kernel what's idle;
 * never reads or changes game memory)."). Our own code (DroidDeck is GPL-3.0; none of it is used).
 *
 * WHAT RUNS IN THE GAME'S PROCESS: libdsf-pageout.so (the same preload as the Wine helpers', from the same switch) with
 * [ENV_MODE] = 1 installs its SIGURG handler in the game client too (Wow-arm64.exe, WowB-ARM64.exe, ...: [client]; Battle.net, its
 * Agent, our sign-in bridge and explorer.exe starting the game stay refused). On DS Forever's GAME request it runs one step of a
 * COLD-ONLY pass (dsf_pageout.h po_g_run): the probe sends a few sample pages of each resident 1 MiB chunk of its private anonymous
 * memory out; seconds later the commit pages out only the chunks none of whose samples came back (untouched: cold). At most
 * [REQ_KIB] a request and [PASS_KIB] a pass, samples included; never GPU / dmabuf / kgsl, file, shared, executable or locked memory,
 * never the stack in use. The code there only reads /proc/self/maps, cmdline and comm, asks mincore(2) and calls madvise(MADV_PAGEOUT)
 * on its own memory: it never reads or writes the game's memory contents and hooks no game function (the ToS note, also in
 * SOURCE_PROVENANCE.md and the C headers).
 *
 * WHEN ([charselectWhy], [criticalWhy]; MemWatch's 1 s sample, its thread):
 *  - ONCE per game run AT CHARACTER SELECT, before Enter World: the game up and not in the world yet (GameLife: no add-on document; the
 *    add-ons do not run before the world), past its login screen (NativeLogin.pastLogin, the character list's traffic; or
 *    [CHARSELECT_FALLBACK_MS] after Play without that signal), its memory between [CHARSELECT_MIN_MB] and the world mark the
 *    add-ons' silence uses (AddonSilence.IN_WORLD_MB) and STEADY for [STEADY_MS] (a loading world climbs by gigabytes), and free memory
 *    under [charselectBelowMb]: the world entry's burst ([WORLD_BURST_MB]) plus the helpers' trigger - on the 8 GB Thors 4303 MB.
 *  - IN THE WORLD ONLY WHEN MEMORY IS CRITICALLY LOW: free memory under half the helpers' trigger (359 MB on the 8 GB Thors), or
 *    Android's running-critical / low-memory warning in the last 10 s with free memory under the trigger itself; never during a loading
 *    screen or while the world is closed (a logout); at most once per [WORLD_EVERY_MS], doubling up to [WORLD_MAX_EVERY_MS] while the
 *    paged memory comes straight back or there is nothing cold to page.
 *  Never without [SWAP_ROOM_KB] of free swap. Each step is checked again before it is sent: a loading screen (or the world, for the
 *  character select pass) ends the pass at once, and so does the game's memory climbing during a character select pass ([climbing]:
 *  Enter World pressed, the world loading before the add-ons can say so).
 *  WHICH THREAD: every request goes to one idle (asleep, least CPU) thread of the game, never to the process: the kernel hands a
 *  process signal to the main thread first, the game's busiest. No idle thread: no pass; the thread gone: one other idle thread.
 *
 * MEASURED: each pass is one mem.log line ([passLine], a report key line: the game's memory before and after, paged, came back, the
 * swap used before and after, its phase) and [CHECK_AFTER_MS] later one more ([checkLine]): what of it came back by then.
 */
object GamePageOut {
    /** a commit request's cap (the preload holds the same: PO_G_REQ_MAX) */
    const val REQ_KIB = 32 * 1024
    /** a pass's cap, its samples included (PO_G_PASS_MAX) */
    const val PASS_KIB = 256 * 1024
    const val ENV_MODE = "DSF_PAGEOUT_GAME_MODE"
    /**
     * THE WORLD ENTRY'S BURST: the 11-22 triage's reports put it at 3 - 4 GB (12 GB Thors: the game 3.8 GB + its GPU memory at world
     * entry; the 8 GB Thors were killed early, the lowest free memory 317 MB). 3.5 GB, the middle
     */
    const val WORLD_BURST_MB = 3_584L
    /** the game's memory at character select is well above this (DroidDeck's WoW measurement: 1,919 MiB resident there) */
    const val CHARSELECT_MIN_MB = 1_024L
    /** with no past-the-login signal (a browser login, a downloading read), Play this long ago counts as past the login screen */
    const val CHARSELECT_FALLBACK_MS = 120_000L
    /** the game's memory has moved by at most [STEADY_KB] over this long: no world is loading */
    const val STEADY_MS = 10_000L
    const val STEADY_KB = 64L * 1024
    /** between the probe and the commit: what stays untouched this long is cold */
    const val CHARSELECT_WAIT_MS = 5_000L
    const val WORLD_WAIT_MS = 3_000L
    /** between two requests of a pass */
    const val STEP_GAP_MS = 150L
    /** a request not answered by then: the pass ends, and the game is not asked again in this run */
    const val ANSWER_MS = 1_500L
    const val POLL_MS = 100L
    const val CHECK_AFTER_MS = 15_000L
    const val WORLD_EVERY_MS = 10_000L
    const val WORLD_MAX_EVERY_MS = 160_000L
    /** the free swap a pass needs: the helpers' room plus a whole pass */
    const val SWAP_ROOM_KB = PageOut.SWAP_ROOM_KB + PASS_KIB
    /** a pass's requests at most: the probe's (PO_G_PROBE_MAX / PO_G_PROBE_REQ = 4, one spare) and the commits (8 of 32 MiB, two spare) */
    const val MAX_PROBES = 5
    const val MAX_COMMITS = 10

    // ---------------------------------------------------------------- which process (pure; the same rules as dsf_pageout.h)

    /** the game client's own exe names, with or without ".exe" (dsf_pageout.h PO_CLIENTS) */
    val CLIENTS = setOf("wow", "wow-arm64", "wowb", "wowb-arm64", "wowt", "wowt-arm64", "wowclassic", "wowclassic-arm64")
    private val LOADERS = setOf("wine", "wine64", "wine-preloader", "wine64-preloader")

    /**
     * the game client itself (po_client_check): its image (the first argument that is not Wine's loader) is a client name or one of
     * [games] (the launcher's), and its comm is still the loader's ("wine...") or the start of that image name (15 characters). Not
     * Battle.net, its Agent, our sign-in bridge, explorer.exe / start.exe starting the game, or anything unsure.
     */
    fun client(args: List<String>, comm: String?, games: Collection<String>): Boolean {
        val img = args.map { PageOut.base(it) }.firstOrNull { it.isNotEmpty() && it.lowercase() !in LOADERS } ?: return false
        val l = img.lowercase()
        val name = if (l.length > 4 && l.endsWith(".exe")) l.dropLast(4) else l
        if (name !in CLIENTS && games.none { it.isNotEmpty() && it.equals(img, ignoreCase = true) }) return false
        val c = comm?.trimEnd('\n', '\u0000').orEmpty()
        return c.isEmpty() || c.lowercase().startsWith("wine") || (c.length <= img.length && img.startsWith(c, ignoreCase = true))
    }

    // ---------------------------------------------------------------- when (pure)

    /** free memory under this at character select: the world entry would not fit ([WORLD_BURST_MB] + the helpers' trigger) */
    fun charselectBelowMb(totalMb: Long): Long = WORLD_BURST_MB + PageOut.floorMb(totalMb)

    /**
     * the character select pass is due now (its mem.log words), null = not: [done] (one per game run), [gameUp], [startedAtMs] Play
     * (epoch ms, 0 = none), [inWorld] (GameLife), [signedIn] past the login screen, [rssKb] the game client's private memory,
     * [steady] ([Trend.steady]), free memory [availKb] / swap [swapFreeKb] of a [totalMb] Thor
     */
    fun charselectWhy(availKb: Long, swapFreeKb: Long, totalMb: Long, gameUp: Boolean, startedAtMs: Long, nowMs: Long, inWorld: Boolean,
                      signedIn: Boolean, rssKb: Long, steady: Boolean, done: Boolean): String? {
        if (done || !gameUp || startedAtMs <= 0L || inWorld) return null
        if (totalMb <= 0 || availKb < 0 || swapFreeKb < SWAP_ROOM_KB) return null
        if (rssKb < CHARSELECT_MIN_MB * 1024 || rssKb >= AddonSilence.IN_WORLD_MB * 1024) return null
        if (!steady) return null
        if (!signedIn && nowMs - startedAtMs < CHARSELECT_FALLBACK_MS) return null
        val below = charselectBelowMb(totalMb)
        if (availKb >= below * 1024) return null
        return "free ${MemWatch.mb(availKb)} MB < $below"
    }

    /**
     * the world's critical pass is due now (its words), null = not: in the world ([inWorld], not [away] after a logout, no loading
     * screen [loading]), and free memory under half the helpers' trigger, or Android's warning [warnAgoMs] ago (-1 = none) with free
     * memory under the trigger itself
     */
    fun criticalWhy(availKb: Long, swapFreeKb: Long, totalMb: Long, warnAgoMs: Long, gameUp: Boolean, inWorld: Boolean, loading: Boolean,
                    away: Boolean): String? {
        if (!gameUp || !inWorld || loading || away) return null
        if (totalMb <= 0 || availKb < 0 || swapFreeKb < SWAP_ROOM_KB) return null
        val floor = PageOut.floorMb(totalMb)
        return when {
            availKb < floor * 1024 / 2 -> "free ${MemWatch.mb(availKb)} MB < ${floor / 2}"
            warnAgoMs in 0..PageOut.WARN_MS && availKb < floor * 1024 -> "Android's warning, free ${MemWatch.mb(availKb)} MB"
            else -> null
        }
    }

    /**
     * THE WORLD ENTRY HAS BEGUN DURING A CHARACTER SELECT PASS (pure): the game's private memory [nowKb] has climbed more than
     * [STEADY_KB] above what the pass left it at (its start [beforeKb] less the [sentKb] it sent out). GameLife only knows the world
     * once the add-ons run, after the world entry's loading screen; Enter World pressed during the pass shows here first, as the climb
     * of the world loading, and the pass stops before its next step (never paging during the world entry's burst)
     */
    fun climbing(beforeKb: Long, nowKb: Long, sentKb: Long): Boolean =
        beforeKb >= 0 && nowKb >= 0 && nowKb - (beforeKb - sentKb.coerceAtLeast(0)) > STEADY_KB

    /** the game client's private memory over the last seconds: [steady] when it moved by at most [STEADY_KB] over [STEADY_MS] (pure) */
    class Trend {
        private val at = ArrayDeque<Pair<Long, Long>>()
        fun add(nowMs: Long, kb: Long) {
            at.addLast(nowMs to kb)
            while (at.size > 1 && nowMs - at[1].first >= STEADY_MS) at.removeFirst()
        }
        fun steady(nowMs: Long): Boolean {
            if (at.isEmpty() || nowMs - at.first().first < STEADY_MS) return false
            return at.maxOf { it.second } - at.minOf { it.second } <= STEADY_KB
        }
        fun reset() = at.clear()
    }

    /** one thread of the game ([state] from its stat, [cpuTicks] utime + stime, [blocksUrg] SIGURG in its SigBlk) */
    data class Th(val tid: Int, val state: Char, val cpuTicks: Long, val blocksUrg: Boolean)

    /** the thread a request goes to: asleep, not blocking SIGURG, not the main thread, the least CPU; 0 = none: no request at all (a
     *  request is never sent to the process, whose signal the kernel hands to the main thread first) (pure) */
    fun pickThread(pid: Int, ts: List<Th>): Int =
        ts.filter { it.tid != pid && it.state == 'S' && !it.blocksUrg }.minWithOrNull(compareBy<Th>({ it.cpuTicks }, { -it.tid }))?.tid ?: 0

    /** a /proc/<pid>/task/<tid>/stat line: (state, utime + stime) (pure) */
    fun parseStat(line: String): Pair<Char, Long>? {
        val f = line.substringAfterLast(')', "").trim().split(' ')
        if (f.size < 13) return null
        val st = f[0].singleOrNull() ?: return null
        val u = f[11].toLongOrNull() ?: return null; val s = f[12].toLongOrNull() ?: return null
        return st to (u + s)
    }

    /** the status file blocks [sig] (SigBlk) */
    fun blocks(status: List<String>, sig: Int = PageOut.SIGURG): Boolean {
        val hex = status.firstOrNull { it.startsWith("SigBlk:") }?.substringAfter(':')?.trim() ?: return false
        val v = hex.toULongOrNull(16) ?: return false
        return (v shr (sig - 1)) and 1uL == 1uL
    }

    // ---------------------------------------------------------------- the answers and the pass (pure)

    /** a game request's answer (dsf_pageout.h po_gresult) */
    data class GRes(val rc: Int, val op: Char, val probed: Long, val sampledKb: Long, val cold: Long, val inuse: Long, val residentKb: Long,
                    val pagedKb: Long, val backKb: Long, val passKb: Long, val more: Boolean, val ms: Long) {
        companion object { const val OK = 0; const val UNSUPPORTED = 3; const val NOT_CLIENT = 6; const val NO_PASS = 7 }
    }

    /** "DSFPO2 seq=5 rc=0 op=c probed=412 sampled_kb=.. cold=.. inuse=.. resident_kb=.. paged_kb=.. back_kb=.. pass_kb=.. more=1 ms=12" */
    fun parseGRes(line: String, seq: Long): GRes? {
        val t = line.trim()
        if (!t.startsWith("DSFPO2 ")) return null
        val kv = t.removePrefix("DSFPO2 ").split(' ').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        if (kv["seq"]?.toLongOrNull() != seq) return null
        val rc = kv["rc"]?.toIntOrNull() ?: return null
        val op = kv["op"]?.singleOrNull() ?: return null
        fun v(k: String) = kv[k]?.toLongOrNull() ?: -1L
        return GRes(rc, op, v("probed"), v("sampled_kb"), v("cold"), v("inuse"), v("resident_kb"), v("paged_kb"), v("back_kb"),
            v("pass_kb"), kv["more"] == "1", v("ms"))
    }

    enum class Phase(val words: String) { CHARSELECT("charselect"), WORLD("world-critical") }

    /** what a pass does next: send [op] with [capKib] after [delayMs], or it is over ([why]) */
    sealed class Step {
        data class Send(val op: Char, val capKib: Int, val delayMs: Long) : Step()
        data class Done(val why: String) : Step()
    }

    /**
     * ONE PASS (pure): 'P' starts it, 'p' while the probe says more (at most [MAX_PROBES] requests), then after [waitMs] the commits
     * of [REQ_KIB] while the answer says more (at most [MAX_COMMITS]); the first missing or refused answer ends it. Its sums feed the
     * mem.log line
     */
    class Pass(val phase: Phase, val waitMs: Long = if (phase == Phase.CHARSELECT) CHARSELECT_WAIT_MS else WORLD_WAIT_MS) {
        var requests = 0; private set
        var probes = 0; private set
        var commits = 0; private set
        var probed = 0L; private set
        var sampledKb = 0L; private set
        var cold = 0L; private set
        var inuse = 0L; private set
        var pagedKb = 0L; private set
        var backKb = 0L; private set
        var passKb = 0L; private set
        var maxMs = 0L; private set
        var rc = GRes.OK; private set

        fun first(): Step = Step.Send('P', 0, 0)

        /** the answer [r] to the last [Step.Send] (null = none in time) -> the next step */
        fun next(r: GRes?): Step {
            if (r == null) return Step.Done("no answer")
            requests++
            maxMs = maxOf(maxMs, r.ms)
            if (r.rc != GRes.OK) { rc = r.rc; return Step.Done("rc ${r.rc}") }
            probed = r.probed; passKb = maxOf(passKb, r.passKb)
            sampledKb += r.sampledKb.coerceAtLeast(0); inuse += r.inuse.coerceAtLeast(0)
            if (r.op == 'P') backKb += r.backKb.coerceAtLeast(0)
            return when (r.op) {
                'P', 'p' -> {
                    probes++
                    when {
                        r.more && probes < MAX_PROBES -> Step.Send('p', 0, STEP_GAP_MS)
                        probed <= 0L -> Step.Done("nothing cold to probe")
                        else -> Step.Send('c', REQ_KIB, waitMs)
                    }
                }
                'c' -> {
                    commits++
                    cold += r.cold.coerceAtLeast(0); pagedKb += r.pagedKb.coerceAtLeast(0)
                    if (r.more && commits < MAX_COMMITS) Step.Send('c', REQ_KIB, STEP_GAP_MS) else Step.Done("done")
                }
                else -> Step.Done("done")
            }
        }

        /** the memory the pass sent out: the commits' paged pages and the probe's samples */
        val totalKb: Long get() = pagedKb + sampledKb
    }

    /**
     * "pageout (game, charselect, 1, free 3900 MB < 4303): rss anon 1890 -> 1650 MB; paged 240 MB cold-only (probed 512 chunks: 230 cold,
     * 140 in use; samples 16 MB); came back 0 MB; swap used 2100 -> 2340 MB; 11 requests, longest 41 ms" (pure); [threadChanged]: the
     * first idle thread ended during the pass and another one took the rest
     */
    fun passLine(n: Int, why: String, p: Pass, rssBeforeKb: Long, rssAfterKb: Long, swapBeforeKb: Long, swapAfterKb: Long, end: String,
                 threadChanged: Boolean = false): String {
        fun m(kb: Long) = if (kb < 0) "?" else MemWatch.mb(kb).toString()
        return buildString {
            append("pageout (game, ").append(p.phase.words).append(", ").append(n).append(", ").append(why).append("): ")
            append("rss anon ").append(m(rssBeforeKb)).append(" -> ").append(m(rssAfterKb)).append(" MB")
            append("; paged ").append(m(p.totalKb)).append(" MB cold-only (probed ").append(p.probed).append(" chunks: ")
            append(p.cold).append(" cold, ").append(p.inuse).append(" in use; samples ").append(m(p.sampledKb)).append(" MB)")
            append("; came back ").append(m(p.backKb)).append(" MB")
            append("; swap used ").append(m(swapBeforeKb)).append(" -> ").append(m(swapAfterKb)).append(" MB")
            append("; ").append(p.requests).append(" requests, longest ").append(p.maxMs).append(" ms")
            if (threadChanged) append("; thread re-picked")
            if (end != "done") append("; ").append(end)
        }
    }

    /** "pageout (game, check after charselect 1): came back 12 MB of 240 MB in 15 s" (pure) */
    fun checkLine(phase: Phase, n: Int, backKb: Long, totalKb: Long, afterMs: Long): String =
        "pageout (game, check after ${phase.words} $n): came back ${MemWatch.mb(backKb.coerceAtLeast(0))} MB of " +
            "${MemWatch.mb(totalKb.coerceAtLeast(0))} MB in ${afterMs / 1000} s"

    // ---------------------------------------------------------------- on the device (MemWatch's thread)

    @Volatile var passes = 0; private set
    @Volatile var pagedKb = 0L; private set
    @Volatile var unsupported = false; private set
    private var runKey = 0L
    private var charselectDone = false
    /** the game did not answer (or refused) in this run: never asked again in it */
    private var silent = false
    private var pid = 0
    private var pidScanAt = 0L
    private val trend = Trend()
    private val worldLimit = PageOut.Limiter(WORLD_EVERY_MS, WORLD_MAX_EVERY_MS)
    private var pass: Pass? = null
    private var passSeq = 0L
    private var checkBackKb = 0L
    /** past the login screen (the one app's NativeLogin; the tests' seam) */
    @Volatile internal var signedIn: () -> Boolean = { NativeLogin.pastLogin }

    private fun readLines(f: File): List<String>? = runCatching { f.readLines() }.getOrNull()

    /** the game client of our uid with the preload's handler: its pid, 0 = none (a /proc scan at most every 5 s) */
    private fun clientPid(now: Long): Int {
        if (pid > 0 && File("/proc/$pid/status").isFile) return pid
        pid = 0
        if (now - pidScanAt < 5_000L && pidScanAt != 0L) return 0
        pidScanAt = now
        val me = android.os.Process.myPid(); val uid = android.os.Process.myUid()
        val games = PageOut.gameNames()
        for (d in File("/proc").listFiles() ?: emptyArray()) {
            val p = d.name.toIntOrNull() ?: continue
            if (p == me) continue
            val st = readLines(File(d, "status")) ?: continue
            if (PageOut.uidOf(st) != uid) continue
            val a = PageOut.args(runCatching { File(d, "cmdline").readText() }.getOrDefault(""))
            val comm = runCatching { File(d, "comm").readText() }.getOrNull()
            if (!client(a, comm, games) || !PageOut.catches(st)) continue
            pid = p; return p
        }
        return 0
    }

    private fun rssKb(p: Int): Long = readLines(File("/proc/$p/status"))?.let { PageOut.anonKb(it) } ?: -1L

    private fun thread(p: Int): Int {
        val ts = (File("/proc/$p/task").listFiles() ?: emptyArray()).mapNotNull { d ->
            val tid = d.name.toIntOrNull() ?: return@mapNotNull null
            val s = runCatching { parseStat(File(d, "stat").readText()) }.getOrNull() ?: return@mapNotNull null
            Th(tid, s.first, s.second, blocks(readLines(File(d, "status")) ?: emptyList()))
        }
        return pickThread(p, ts)
    }

    private fun swapUsed(): Long = PageOut.swapUsedKb(readLines(File("/proc/meminfo")) ?: emptyList())

    /** MemWatch's 1 s sample with Compress idle memory on and the preload armed ([dir]): a pass when one is due */
    internal fun tick(s: MemWatch.Lmk, h: Handler, dir: File, warnAgoMs: Long) {
        if (unsupported) return
        val started = GameLife.startedAtMs
        if (started != runKey) {                                       // a new game run
            runKey = started; charselectDone = false; silent = false; pid = 0; pidScanAt = 0L; trend.reset(); checkBackKb = 0L
        }
        if (silent || pass != null || started == 0L) return
        val gameUp = runCatching { LauncherHooks.gameUp() }.getOrDefault(false)
        if (!gameUp) return
        val now = SystemClock.uptimeMillis()
        val inWorld = GameLife.worldNow
        if (!inWorld && !charselectDone) {
            val p = clientPid(now)
            if (p <= 0) return
            val rss = rssKb(p)
            if (rss >= 0) trend.add(now, rss)
            val why = charselectWhy(s.availKb, s.swapFreeKb, DeviceTier.totalMb, gameUp, started, System.currentTimeMillis(), inWorld,
                runCatching { signedIn() }.getOrDefault(false), rss, trend.steady(now), charselectDone) ?: return
            val tid = thread(p)
            if (tid <= 0) return                                        // no idle thread now: the next sample tries again
            charselectDone = true
            start(Phase.CHARSELECT, why, p, tid, h, dir)
            return
        }
        if (!inWorld) return
        val why = criticalWhy(s.availKb, s.swapFreeKb, DeviceTier.totalMb, warnAgoMs, gameUp, inWorld, GameLife.loadingNow, GameLife.awayNow)
            ?: return
        if (!worldLimit.due(now)) return
        val p = clientPid(now)
        if (p <= 0) return
        val tid = thread(p)
        if (tid <= 0) return
        start(Phase.WORLD, why, p, tid, h, dir)
    }

    /** the pass may go on: the character select one only before the world and while the game's memory is not climbing (the world
     *  entry under way: [climbing]), the world's only in it with no loading screen up */
    private fun gate(ps: Pass, run: Run): String? {
        if (!runCatching { LauncherHooks.gameUp() }.getOrDefault(false)) return "stopped: the game closed"
        return when (run.phase) {
            Phase.CHARSELECT -> when {
                GameLife.worldNow -> "stopped: in the world"
                climbing(run.rssBefore, rssKb(run.pid), ps.totalKb) -> "stopped: the game's memory is climbing (the world loading)"
                else -> null
            }
            Phase.WORLD -> when {
                GameLife.loadingNow -> "stopped: loading screen"
                !GameLife.worldNow || GameLife.awayNow -> "stopped: out of the world"
                else -> null
            }
        }
    }

    private class Run(val phase: Phase, val why: String, val pid: Int, var tid: Int, val dir: File, val rssBefore: Long, val swapBefore: Long) {
        var threadChanged = false
    }

    private fun start(phase: Phase, why: String, p: Int, tid: Int, h: Handler, dir: File) {
        val ps = Pass(phase)
        pass = ps
        val run = Run(phase, why, p, tid, dir, rssKb(p), swapUsed())
        send(ps.first(), ps, run, h)
    }

    /** the request's signal to the pass's idle thread; that thread gone (-3, ESRCH): once more to another idle one. 0 = sent */
    private fun signal(run: Run): Int {
        val sent = NativeHeap.pageOutSignalGameNow(run.pid, run.tid)
        if (sent != -3) return sent
        val t = thread(run.pid)
        if (t <= 0 || t == run.tid) return sent
        run.tid = t; run.threadChanged = true
        return NativeHeap.pageOutSignalGameNow(run.pid, t)
    }

    private fun send(step: Step, ps: Pass, run: Run, h: Handler) {
        when (step) {
            is Step.Done -> finish(ps, run, step.why, h)
            is Step.Send -> h.postDelayed({
                runCatching {
                    // a commit or a further probe only while the pass's moment lasts (the start was checked by the trigger itself)
                    val stop = if (step.op == 'P') null else gate(ps, run)
                    if (stop != null) { finish(ps, run, stop, h); return@runCatching }
                    val n = ++passSeq
                    File(run.dir, "gres-${run.pid}").delete()
                    val tmp = File(run.dir, "greq.tmp")
                    tmp.writeText("DSFPO2 $n ${step.op} ${step.capKib}\n")
                    if (!tmp.renameTo(File(run.dir, "greq"))) { finish(ps, run, "stopped: request not written", h); return@runCatching }
                    val sent = signal(run)
                    if (sent != 0) { finish(ps, run, "stopped: signal $sent", h); return@runCatching }
                    await(n, SystemClock.uptimeMillis(), ps, run, h)
                }.onFailure { finish(ps, run, "stopped: ${it.javaClass.simpleName}", h) }
            }, step.delayMs)
        }
    }

    private fun await(n: Long, since: Long, ps: Pass, run: Run, h: Handler) {
        h.postDelayed({
            runCatching {
                val r = runCatching { File(run.dir, "gres-${run.pid}").readText() }.getOrNull()?.let { parseGRes(it, n) }
                if (r == null && SystemClock.uptimeMillis() - since < ANSWER_MS) { await(n, since, ps, run, h); return@runCatching }
                if (r == null) silent = true
                if (r?.rc == GRes.UNSUPPORTED) unsupported = true
                if (r != null && r.rc != GRes.OK) silent = true
                send(ps.next(r), ps, run, h)
            }.onFailure { finish(ps, run, "stopped: ${it.javaClass.simpleName}", h) }
        }, POLL_MS)
    }

    private fun finish(ps: Pass, run: Run, end: String, h: Handler) {
        if (pass !== ps) return
        pass = null
        val now = SystemClock.uptimeMillis()
        passes++; pagedKb += ps.totalKb
        val n = passes
        // the commits' pages only: a pass that sent out its probe's samples and found nothing cold backs off like one that paged nothing
        if (run.phase == Phase.WORLD) worldLimit.done(now, ps.pagedKb, ps.backKb + checkBackKb)
        checkBackKb = 0L
        val l = passLine(n, run.why, ps, run.rssBefore, rssKb(run.pid), run.swapBefore, swapUsed(), end, run.threadChanged)
        MemWatch.note(l)
        runCatching { DiagLog.event("memory", l.take(600)) }
        if (unsupported) MemWatch.note("pageout: this kernel has no MADV_PAGEOUT; off for this run")
        if (ps.pagedKb <= 0L || silent) return
        // what of it came back: a check that pages nothing, unless a new pass (which measures it itself) began by then
        val total = ps.pagedKb
        h.postDelayed({
            runCatching {
                if (pass != null || passes != n || silent || runKey != GameLife.startedAtMs) return@runCatching
                val m = ++passSeq
                File(run.dir, "gres-${run.pid}").delete()
                val tmp = File(run.dir, "greq.tmp")
                tmp.writeText("DSFPO2 $m k 0\n")
                if (!tmp.renameTo(File(run.dir, "greq")) || signal(run) != 0) return@runCatching
                h.postDelayed({
                    runCatching {
                        val r = File(run.dir, "gres-${run.pid}").readText().let { parseGRes(it, m) } ?: return@runCatching
                        if (r.rc != GRes.OK) return@runCatching
                        checkBackKb += r.backKb.coerceAtLeast(0)
                        val c = checkLine(run.phase, n, r.backKb, total, CHECK_AFTER_MS)
                        MemWatch.note(c)
                        runCatching { DiagLog.event("memory", c.take(600)) }
                    }
                }, ANSWER_MS)
            }
        }, CHECK_AFTER_MS)
    }

    /** the problem report's part: "; WoW (cold only): 2 passes, 400 MB paged" (pure) */
    fun reportPart(armed: Boolean, n: Int, kb: Long): String =
        if (!armed) "" else "; WoW (cold only): $n passes, ${MemWatch.mb(kb)} MB paged"
}
