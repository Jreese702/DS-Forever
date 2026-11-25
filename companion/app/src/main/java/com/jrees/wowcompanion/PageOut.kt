package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import java.io.File

/**
 * IDLE MEMORY INTO THE THOR'S OWN ZRAM (the user's picks, 2026-11-23, after asking how DroidDeck compresses memory: "Wine helpers
 * (Recommended)" = "Only Wine's idle background processes next to WoW (not WoW itself), only when memory runs low; measured in
 * reports", and "Our own app" = "DS Forever's own idle memory when memory runs low"; then, once the research showed DroidDeck pages
 * out WoW itself, "Yes, carefully" for the game client's COLD memory: [GamePageOut], its own cold-only mode, at character select and
 * when memory is critically low in the world). Our own code (DroidDeck is GPL-3.0; nothing of
 * it is used): the kernel's MADV_PAGEOUT on a process's OWN private anonymous pages, which the kernel moves into the swap the firmware
 * already set up (zram on every Thor). No root, no system setting, no permission; the memory's content is never read.
 *
 *  - The Wine helpers (services.exe, winedevice.exe, plugplay.exe, svchost.exe, rpcss.exe, ...): libdsf-pageout.so is preloaded into
 *    the container's Wine processes ([launchEnv], from the next game start). On a request each checks what it is and pages out at most
 *    [CAP_KIB] of its idle heaps (dsf_pageout.h). The game is never sent THIS request ([who] here, and the process's own check there
 *    refuses it); the game client has its own cold-only one ([GamePageOut]). Not wineserver (every call of the game goes through it).
 *  - DS Forever itself: its native allocator's regions only (never the Java heap), at most [CAP_KIB] (NativeHeap.pageOutSelf), never
 *    while a loading screen draws (its pictures are native memory), on MemWatch's thread.
 *
 * WHEN ([pressure]): only near the edge - MemAvailable under [floorMb] (a tenth of the RAM, 600 - 1600 MB: 719 MB on the 8 GB Thors,
 * 1126 on the 12 GB, 1525 on the 16 GB), or the file cache thin with MemAvailable under 1.5 x the floor (what Android's killer acts
 * on), or Android's running-critical / low-memory warning in the last 10 s under 2 x the floor; and only with [SWAP_ROOM_KB] of swap
 * free. At most once per [EVERY_MS] each, doubled up to [MAX_EVERY_MS] while the paged memory comes straight back or there is nothing
 * left to page ([Limiter]). Each request is one mem.log line (the problem report's memory and key lines): every helper's memory before
 * and after, what was paged and came back, the swap used before and after. Settings > Performance > Memory > Compress idle memory:
 * on by default on the 8 GB Thors only ([DeviceTier.pageOutDefault]).
 */
object PageOut {
    const val CAP_KIB = 32 * 1024
    /** a helper holding less private memory than this is not asked */
    const val MIN_ANON_KB = 8L * 1024
    const val EVERY_MS = 30_000L
    const val MAX_EVERY_MS = 240_000L
    /** the helpers' answers are read this long after the request */
    const val COLLECT_MS = 1_500L
    const val SWAP_ROOM_KB = 256L * 1024
    const val WARN_MS = 10_000L
    const val LIB = "libdsf-pageout.so"
    const val ENV_DIR = "DSF_PAGEOUT_DIR"
    const val ENV_GAME = "DSF_PAGEOUT_GAME"
    /** SIGURG (23) in /proc/<pid>/status's SigCgt mask */
    const val SIGURG = 23

    // ---------------------------------------------------------------- which process (pure; the same rules as dsf_pageout.h)

    enum class Who { HELPER, NOT_HELPER, GAME }

    /** Wine's own background processes: an ALLOW list (anything else is left alone); with or without ".exe" */
    val HELPERS = setOf("services", "winedevice", "plugplay", "svchost", "rpcss", "explorer", "conhost", "tabtip", "start", "wineboot",
        "winemenubuilder", "winhandler")
    private val LOADERS = setOf("wine", "wine64", "wine-preloader", "wine64-preloader")

    /** /proc/<pid>/cmdline's NUL-separated arguments */
    fun args(cmdline: String): List<String> = cmdline.split('\u0000').dropLastWhile { it.isEmpty() }

    /** the last path part ('/' or '\') */
    fun base(a: String): String = a.substring(maxOf(a.lastIndexOf('/'), a.lastIndexOf('\\')) + 1)

    /** a name that is (or starts) the game: Wow*.exe of any flavour, Blizzard / Battle.net, or one of [games] */
    fun gameish(name: String, games: Collection<String>): Boolean {
        val n = name.lowercase()
        return n.startsWith("wow") || "blizzard" in n || "battle.net" in n || games.any { it.isNotEmpty() && it.equals(name, ignoreCase = true) }
    }

    /**
     * the process from its arguments and comm: [Who.HELPER] only when the image (the first argument that is not Wine's loader) is a
     * known helper and nothing - no argument, not the comm - names the game (explorer's "/desktop=shell,... Wow-arm64.exe" starts the
     * game: left alone too). Anything unsure is [Who.NOT_HELPER].
     */
    fun who(args: List<String>, comm: String?, games: Collection<String>): Who {
        val c = comm?.trim().orEmpty()
        if (c.isNotEmpty() && gameish(c, games)) return Who.GAME
        var img: String? = null
        for (a in args) {
            val b = base(a)
            if (b.isNotEmpty() && gameish(b, games)) return Who.GAME
            val l = a.lowercase()
            if ("\\wow" in l || "/wow" in l || "world of warcraft" in l || "battle.net" in l || "blizzard" in l) return Who.GAME
            if (img == null && b.isNotEmpty() && b.lowercase() !in LOADERS) img = b
        }
        val i = img?.lowercase() ?: return Who.NOT_HELPER
        return if (i.removeSuffix(".exe") in HELPERS) Who.HELPER else Who.NOT_HELPER
    }

    /** the game's exe names for the preload's own check (WowProduct's, ':'-separated) */
    fun gameNames(): List<String> = WowProduct.values().map { it.exeName }

    // ---------------------------------------------------------------- when (pure)

    /** the edge for a Thor of [totalMb]: a tenth of its RAM, 600 - 1600 MB */
    fun floorMb(totalMb: Long): Long = (totalMb / 10).coerceIn(600L, 1600L)

    /**
     * why memory is short now (the mem.log line's words), null = not short: [availKb] MemAvailable, [fileKb] the file cache (-1 =
     * unknown), [swapFreeKb] free swap (under [SWAP_ROOM_KB] or unknown: null, there is no room to page into), [warnAgoMs] since
     * Android's last running-critical / low-memory warning (-1 = none)
     */
    fun pressure(availKb: Long, fileKb: Long, swapFreeKb: Long, totalMb: Long, warnAgoMs: Long): String? {
        if (totalMb <= 0 || availKb < 0 || swapFreeKb < SWAP_ROOM_KB) return null
        val floor = floorMb(totalMb) * 1024
        return when {
            availKb < floor -> "free ${MemWatch.mb(availKb)} MB < ${floor / 1024}"
            fileKb in 0 until floor / 2 && availKb < floor * 3 / 2 -> "file cache ${MemWatch.mb(fileKb)} MB, free ${MemWatch.mb(availKb)} MB"
            warnAgoMs in 0..WARN_MS && availKb < floor * 2 -> "Android's warning, free ${MemWatch.mb(availKb)} MB"
            else -> null
        }
    }

    /** at most one request per [base] ms; while the memory paged comes straight back (half or more) or there was nothing to page
     *  (under 1 MB), the wait doubles up to [max]; a useful request brings it back to [base]. Pure (uptime ms) */
    class Limiter(private val base: Long = EVERY_MS, private val max: Long = MAX_EVERY_MS) {
        var at = 0L; private set
        var every = base; private set
        private var lastPagedKb = 0L
        fun due(now: Long): Boolean = at == 0L || now - at >= every
        /** a request now that paged [pagedKb] and found [backKb] of the previous one's back in memory */
        fun done(now: Long, pagedKb: Long, backKb: Long) {
            val cameBack = lastPagedKb > 0 && backKb * 2 >= lastPagedKb
            every = if (cameBack || pagedKb < 1024) minOf(every * 2, max) else base
            lastPagedKb = pagedKb; at = now
        }
    }

    // ---------------------------------------------------------------- the answers (pure)

    /** a request's answer (dsf_pageout.h po_result) */
    data class Res(val rc: Int, val regions: Long, val eligibleKb: Long, val residentKb: Long, val pagedKb: Long, val backKb: Long,
                   val hot: Long, val ms: Long) {
        companion object {
            const val OK = 0; const val NOT_HELPER = 1; const val GAME = 2; const val UNSUPPORTED = 3
            /** NativeHeap.pageOutSelf's array */
            fun of(a: LongArray?): Res? = if (a == null || a.size < 8) null else Res(a[0].toInt(), a[1], a[2], a[3], a[4], a[5], a[6], a[7])
        }
    }

    /** "DSFPO1 seq=12 rc=0 regions=14 eligible_kb=.. resident_kb=.. paged_kb=.. back_kb=.. hot=2 ms=7" -> [Res] of request [seq] */
    fun parseRes(line: String, seq: Long): Res? {
        val t = line.trim()
        if (!t.startsWith("DSFPO1 ")) return null
        val kv = t.removePrefix("DSFPO1 ").split(' ').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].toLongOrNull() } }.toMap()
        if (kv["seq"] != seq) return null
        fun v(k: String) = kv[k] ?: -1L
        val rc = kv["rc"] ?: return null
        return Res(rc.toInt(), v("regions"), v("eligible_kb"), v("resident_kb"), v("paged_kb"), v("back_kb"), v("hot"), v("ms"))
    }

    /** a status file's private memory: RssAnon (kB), null = none */
    fun anonKb(status: List<String>): Long? = status.firstOrNull { it.startsWith("RssAnon:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()

    /** the status file's uid (the real one) */
    fun uidOf(status: List<String>): Int? = status.firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()

    /** the process catches [sig] (SigCgt): the preload's handler is in it */
    fun catches(status: List<String>, sig: Int = SIGURG): Boolean {
        val hex = status.firstOrNull { it.startsWith("SigCgt:") }?.substringAfter(':')?.trim() ?: return false
        val v = hex.toULongOrNull(16) ?: return false
        return (v shr (sig - 1)) and 1uL == 1uL
    }

    /** /proc/meminfo: the swap in use (kB), -1 = unknown */
    fun swapUsedKb(meminfo: List<String>): Long {
        fun kb(k: String) = meminfo.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        val t = kb("SwapTotal") ?: return -1L; val f = kb("SwapFree") ?: return -1L
        return t - f
    }

    /** one helper's request: [beforeKb] / [afterKb] its RssAnon, [res] its answer (null = none in time) */
    data class Helper(val pid: Int, val name: String, val beforeKb: Long, val afterKb: Long, val res: Res?)

    /**
     * "pageout (helpers, 3, free 612 MB < 719): services.exe 21 -> 9 MB, winedevice.exe 30 -> 14 MB, plugplay.exe no answer; paged 54
     * MB, came back 2 MB; swap used 2100 -> 2154 MB; 12 ms; loading" (pure). [n] = this run's request count. Helper names only
     * (Wine's own exe names: [who] passed them), never a path or an argument.
     */
    fun helpersLine(n: Int, why: String, hs: List<Helper>, swapBeforeKb: Long, swapAfterKb: Long, loading: Boolean, psi: String = ""): String {
        fun m(kb: Long) = if (kb < 0) "?" else MemWatch.mb(kb).toString()
        val each = hs.joinToString(", ") { h ->
            when {
                h.res == null -> "${h.name} no answer"
                h.res.rc != Res.OK -> "${h.name} rc ${h.res.rc}"
                else -> "${h.name} ${m(h.beforeKb)} -> ${m(h.afterKb)} MB"
            }
        }.ifEmpty { "none ready" }
        val ok = hs.mapNotNull { it.res }.filter { it.rc == Res.OK }
        return buildString {
            append("pageout (helpers, ").append(n).append(", ").append(why).append("): ").append(each)
            append("; paged ").append(m(ok.sumOf { it.pagedKb })).append(" MB, came back ").append(m(ok.sumOf { it.backKb })).append(" MB")
            append("; swap used ").append(m(swapBeforeKb)).append(" -> ").append(m(swapAfterKb)).append(" MB")
            if (ok.isNotEmpty()) append("; ").append(ok.maxOf { it.ms }).append(" ms")
            if (loading) append("; loading")
            if (psi.isNotEmpty()) append("; ").append(psi)
        }
    }

    /** "pageout (ours, 2, free 612 MB < 719): rss anon 612 -> 590 MB; paged 30 MB of 14 regions, came back 2 MB; 12 ms" (pure) */
    fun selfLine(n: Int, why: String, r: Res, beforeKb: Long, afterKb: Long): String {
        fun m(kb: Long) = if (kb < 0) "?" else MemWatch.mb(kb).toString()
        if (r.rc != Res.OK) return "pageout (ours, $n, $why): rc ${r.rc}"
        return "pageout (ours, $n, $why): rss anon ${m(beforeKb)} -> ${m(afterKb)} MB; paged ${m(r.pagedKb)} MB of ${r.regions} regions, " +
            "came back ${m(r.backKb)} MB; ${r.ms} ms"
    }

    /** the problem report's line: "Compress idle memory: on (this Thor's default); Wine helpers: 3 requests, 54 MB paged; ours: 2
     *  requests, 30 MB paged" (pure) */
    fun reportLine(on: Boolean, byDefault: Boolean, armed: Boolean, helperN: Int, helperKb: Long, selfN: Int, selfKb: Long, unsupported: Boolean,
                   gameN: Int = 0, gameKb: Long = 0L): String =
        buildString {
            append("Compress idle memory: ").append(if (on) "on" else "off").append(if (byDefault) " (this Thor's default)" else "")
            if (unsupported) { append("; not supported by this kernel"); return@buildString }
            if (on || helperN > 0 || selfN > 0) {
                append("; Wine helpers: ").append(if (armed) "$helperN requests, ${MemWatch.mb(helperKb)} MB paged" else "not in this game run")
                append("; ours: ").append(selfN).append(" requests, ").append(MemWatch.mb(selfKb)).append(" MB paged")
                append(GamePageOut.reportPart(armed, gameN, gameKb))
            }
        }

    // ---------------------------------------------------------------- on the device

    /** the preload's request folder of this game run, null = the game was started without it */
    @Volatile var armedDir: File? = null; private set
    @Volatile private var seq = 0L
    @Volatile private var unsupported = false
    @Volatile private var collecting = false
    @Volatile private var warnAt = 0L
    private val helperLimit = Limiter()
    private val selfLimit = Limiter()
    @Volatile private var helperN = 0
    @Volatile private var helperKb = 0L
    @Volatile private var selfN = 0
    @Volatile private var selfKb = 0L
    /** helpers that did not answer a request: never asked again in this game run (whatever handles SIGURG in them, it is not ours
     *  or it is stuck: one signal is the most it gets) */
    private val silent: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * THE LAUNCH (BionicProgramLauncherComponent, the container's start): with Compress idle memory on and the library in the APK, the
     * preload's path ("preload") and its two variables ([ENV_DIR] = an empty folder of the app's own, [ENV_GAME] = the game's exe
     * names); null = off (nothing is added to the container: today's launch exactly).
     */
    @JvmStatic fun launchEnv(ctx: Context): Map<String, String>? {
        armedDir = null
        silent.clear()
        val on = runCatching { DeviceTier.init(ctx); Settings.init(ctx); Settings.pageOut }.getOrDefault(false)
        if (!on || unsupported) return null
        val lib = File(ctx.applicationInfo.nativeLibraryDir ?: return null, LIB)
        if (!lib.isFile) return null
        val dir = File(ctx.filesDir, "pageout")
        runCatching { dir.mkdirs(); dir.listFiles()?.forEach { it.delete() } }
        if (!dir.isDirectory) return null
        armedDir = dir
        val game = GamePageOut.on
        MemWatch.note("launch: compress idle memory on (Wine helpers, at most ${CAP_KIB / 1024} MB each per request; " + (if (game)
            "the game's cold memory at character select and when critically low, at most ${GamePageOut.PASS_KIB / 1024} MB a pass)"
            else "the game's own memory: not paged, 1529)"))
        // 1529: the game client's mode only while GamePageOut is on ("0": the preload installs nothing in the game)
        return mapOf("preload" to lib.path, ENV_DIR to dir.path, ENV_GAME to gameNames().joinToString(":"),
            GamePageOut.ENV_MODE to (if (game) "1" else "0"))
    }

    /** Android's warning (MemWatch.trimmed, any thread): running-critical or onLowMemory */
    fun warned(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == MemWatch.LOW_MEMORY) warnAt = SystemClock.uptimeMillis()
    }

    /** MemWatch's 1 s sample (its thread, [h]): a request when memory is short and one is due */
    internal fun tick(s: MemWatch.Lmk, h: Handler) {
        if (unsupported || !NativeHeap.available) return
        if (!runCatching { Settings.pageOut }.getOrDefault(false)) return
        val now = SystemClock.uptimeMillis()
        val warnAgo = if (warnAt == 0L) -1L else now - warnAt
        // the game client's cold-only passes (character select; critically low in the world): their own triggers, before the helpers'
        armedDir?.let { d -> if (GamePageOut.on) runCatching { GamePageOut.tick(s, h, d, warnAgo) } }
        val why = pressure(s.availKb, s.fileKb, s.swapFreeKb, DeviceTier.totalMb, warnAgo) ?: return
        if (armedDir != null && !collecting && helperLimit.due(now) && runCatching { LauncherHooks.gameUp() }.getOrDefault(false))
            runCatching { askHelpers(why, h, now) }.onFailure { collecting = false; helperLimit.done(now, 0, 0) }
        if (!unsupported && selfLimit.due(now) && !GameLife.loadingNow) runCatching { pageSelf(why, now) }
    }

    private class Cand(val pid: Int, val name: String, val beforeKb: Long)

    /** the helpers ready now: our uid, a known helper by [who], the preload's handler in it, at least [MIN_ANON_KB] private */
    private fun helpers(): List<Cand> {
        val me = android.os.Process.myPid(); val uid = android.os.Process.myUid()
        val games = gameNames()
        val out = ArrayList<Cand>()
        for (d in File("/proc").listFiles() ?: emptyArray()) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == me || pid in silent) continue
            val st = runCatching { File(d, "status").readLines() }.getOrNull() ?: continue
            if (uidOf(st) != uid) continue
            val a = args(runCatching { File(d, "cmdline").readText() }.getOrDefault(""))
            val comm = runCatching { File(d, "comm").readText() }.getOrNull()
            if (who(a, comm, games) != Who.HELPER) continue
            if (!catches(st)) continue
            val anon = anonKb(st) ?: continue
            if (anon < MIN_ANON_KB) continue
            val img = a.firstOrNull { base(it).isNotEmpty() && base(it).lowercase() !in LOADERS } ?: continue
            val name = base(img).lowercase().let { if (it.endsWith(".exe")) it else "$it.exe" }
            out += Cand(pid, name, anon)
        }
        return out
    }

    private fun meminfo(): List<String> = runCatching { File("/proc/meminfo").readLines() }.getOrDefault(emptyList())

    private fun askHelpers(why: String, h: Handler, now: Long) {
        val dir = armedDir ?: return
        val cands = helpers()
        if (cands.isEmpty()) { helperLimit.done(now, 0, 0); return }
        val n = ++seq
        val swapBefore = swapUsedKb(meminfo())
        cands.forEach { File(dir, "res-${it.pid}").delete() }
        val tmp = File(dir, "req.tmp")
        tmp.writeText("DSFPO1 $n $CAP_KIB\n")
        if (!tmp.renameTo(File(dir, "req"))) { helperLimit.done(now, 0, 0); return }
        val asked = cands.filter { NativeHeap.pageOutSignalNow(it.pid) == 0 }
        collecting = true
        val loading = runCatching { GameLife.loadingNow }.getOrDefault(false)
        h.postDelayed({
            runCatching {
                val hs = asked.map { c ->
                    val res = runCatching { File(dir, "res-${c.pid}").readText() }.getOrNull()?.let { parseRes(it, n) }
                    val after = runCatching { anonKb(File("/proc/${c.pid}/status").readLines()) }.getOrNull() ?: -1L
                    Helper(c.pid, c.name, c.beforeKb, after, res)
                }
                hs.filter { it.res == null }.forEach { silent += it.pid }
                if (hs.any { it.res?.rc == Res.UNSUPPORTED }) unsupported = true
                val ok = hs.mapNotNull { it.res }.filter { it.rc == Res.OK }
                helperN++; helperKb += ok.sumOf { it.pagedKb }
                helperLimit.done(SystemClock.uptimeMillis(), ok.sumOf { it.pagedKb }, ok.sumOf { it.backKb })
                val l = helpersLine(helperN, why, hs, swapBefore, swapUsedKb(meminfo()), loading, MemWatch.psiNow())
                MemWatch.note(l)
                runCatching { DiagLog.event("memory", l.take(600)) }
                if (unsupported) MemWatch.note("pageout: this kernel has no MADV_PAGEOUT; off for this run")
            }
            collecting = false
        }, COLLECT_MS)
    }

    private fun selfAnonKb(): Long = runCatching { anonKb(File("/proc/self/status").readLines()) }.getOrNull() ?: -1L

    private fun pageSelf(why: String, now: Long) {
        val before = selfAnonKb()
        val r = Res.of(NativeHeap.pageOutSelfNow(CAP_KIB)) ?: return
        if (r.rc == Res.UNSUPPORTED) { unsupported = true; MemWatch.note("pageout: this kernel has no MADV_PAGEOUT; off for this run"); return }
        selfN++; selfKb += r.pagedKb.coerceAtLeast(0L)
        selfLimit.done(now, r.pagedKb, r.backKb)
        val l = selfLine(selfN, why, r, before, selfAnonKb())
        MemWatch.note(l)
        runCatching { DiagLog.event("memory", l.take(600)) }
    }

    /** the problem report's line ([reportLine]) */
    fun reportLine(): String = reportLine(runCatching { Settings.pageOut }.getOrDefault(false), !runCatching { Settings.pageOutSet }.getOrDefault(false),
        armedDir != null, helperN, helperKb, selfN, selfKb, unsupported || GamePageOut.unsupported, GamePageOut.passes, GamePageOut.pagedKb)
}
