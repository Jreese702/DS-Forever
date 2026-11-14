package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * WHERE DS FOREVER'S MEMORY GOES (the user 2026-11-08, after the 1.0.17 crash reports: every one an Android LOW_MEMORY kill of
 * DS Forever's own process at 1.0 - 1.3 GB while the game ran; the Thor's own snapshot in the world: 974 MB native, 146 MB graphics).
 * Every [EVERY_MS] one WCMEM line: the process's PSS by Android's own split (Java, native, graphics, code), the native heap the
 * allocator holds, each registered picture cache's bytes, and what is running (the art step, the game). Crash reports carry the
 * killed run's last lines (LogSieve keeps WCMEM), so a kill says what was big. Reads only; ~a few ms per line on its own thread.
 */
object MemWatch {
    private const val TAG = "WCMEM"
    // 2026-11-08 (the 8 GB test on test16: +550 MB native in under 30 s, then the kill): every 5 s, so a burst is seen while it happens
    private const val EVERY_MS = 5_000L
    private val sources = LinkedHashMap<String, () -> Long>()
    @Volatile private var thread: HandlerThread? = null

    /** a picture cache (or anything holding memory) reports its bytes under [name]; the last registration of a name wins */
    @Synchronized fun source(name: String, bytes: () -> Long) { sources[name] = bytes }

    /** what DS Forever shows now (the host's room), for the line */
    @Volatile var room: String = "-"

    /**
     * ONE HEAP DUMP per app run, test builds only (the user's yes 2026-11-08): the first time the PSS passes [DUMP_AT_MB], DS Forever's
     * own Java heap (every Bitmap object with its size and who holds it; no game data) goes to /sdcard/WowCompanion/logs/heap-<time>.hprof
     * for the PC to read. It pauses the app a few seconds once.
     */
    private const val DUMP_AT_MB = 650L                              // test18: the 8 GB test killed it at ~750 MB: catch the ~560 MB baseline
    @Volatile private var dumped = false
    @Volatile var testBuild = false
    private fun maybeDump(pssMb: Long) {
        if (!testBuild || dumped || pssMb < DUMP_AT_MB) return
        dumped = true
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = java.io.File("/sdcard/WowCompanion/logs/heap-$stamp.hprof")
        runCatching {
            f.parentFile?.mkdirs()
            Log.i(TAG, "heap dump at pss $pssMb MB -> ${f.path}")
            Debug.dumpHprofData(f.path)
            Log.i(TAG, "heap dump written (${f.length() / (1024 * 1024)} MB)")
        }.onFailure { Log.i(TAG, "heap dump failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun start(ctx: Context? = null) {
        if (thread != null) return
        val t = HandlerThread("WCMEM").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val tick = object : Runnable {
            override fun run() {
                runCatching { val l = line(); Log.i(TAG, l); keep(l) }
                runCatching { maybeDump(lastPssMb) }
                h.postDelayed(this, EVERY_MS)
            }
        }
        h.postDelayed(tick, 5_000L)
        // 1.0.28 (the 1.0.25 / 1.0.26 low-memory reports #159 - #193): every second the device's free memory (the low between two lines:
        // #193's last line, 5 s before the kill, said 4.8 GB free) and the main thread's beat (the ANRs #177 #191: what blocked it)
        val main = Handler(Looper.getMainLooper())
        val fast = object : Runnable {
            override fun run() {
                runCatching { sampleFree() }
                h.postDelayed(this, FAST_MS)
            }
        }
        h.postDelayed(fast, FAST_MS)
        // issue #230 (1.0.28): the beat shared this thread with the line's own work (Debug.getMemoryInfo, the game's /proc walk, the
        // FUSE write), so a slow line could hide a stall. Now its own thread, every [BEAT_MS]; the stall line is written by this one
        val b = Handler(HandlerThread("WCBEAT").also { it.start() }.looper)
        val beatTick = object : Runnable {
            override fun run() {
                runCatching { checkStall(main, h) }
                b.postDelayed(this, BEAT_MS)
            }
        }
        b.postDelayed(beatTick, BEAT_MS)
        TouchLag.beatAge = { if (beat == 0L) -1L else SystemClock.uptimeMillis() - beat }
        ctx?.applicationContext?.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { trimmed(level) }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { trimmed(LOW_MEMORY) }
        })
    }
    @Volatile private var handler: Handler? = null

    // ---------------------------------------------------------------- 1.0.28: the kills' missing facts (issues #159 - #193, #125, #177 #191)
    private const val FAST_MS = 1_000L

    /** the lowest MemAvailable (KB) since the last line, -1 = none read yet */
    @Volatile private var lowKb = -1L
    private val SPACES = Regex("\\s+")
    private fun sampleFree() {
        val kb = java.io.File("/proc/meminfo").useLines { ls -> ls.firstOrNull { it.startsWith("MemAvailable:") } }
            ?.split(SPACES)?.getOrNull(1)?.toLongOrNull() ?: return
        lowKb = lowest(lowKb, kb)
    }
    /** the low-water mark: [kb] when none yet ([was] < 0) or lower */
    fun lowest(was: Long, kb: Long): Long = if (was < 0 || kb < was) kb else was

    /**
     * THE SYSTEM'S OWN WARNINGS (onTrimMemory): logged at once with the level, and handed to what registered a trim ([onTrim]: the Map
     * room's pictures while it is not on screen). Whether Android warns DS Forever before its low-memory kills at all is what the next
     * reports say ("trim ... ago" on every line after one).
     */
    const val LOW_MEMORY = 1000
    private val trimmers = LinkedHashMap<String, (Int) -> Long>()
    /** [f] gives memory back for a trim level and says how many bytes it let go; the last registration of a name wins */
    @Synchronized fun onTrim(name: String, f: (Int) -> Long) { trimmers[name] = f }
    /** a cache of something not on screen lets go at Android's running-low or worse (and at every background level) */
    fun hiddenLetsGo(level: Int, shown: Boolean): Boolean = !shown && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    @Volatile private var trimLevel = -1
    @Volatile private var trimAt = 0L
    /** a trim from Android (the main thread): handed on, then (this watch's thread) logged with the pressure, kept, a collection at
     *  running-low or worse ([collects]) and a line at once */
    fun trimmed(level: Int) {
        trimLevel = level; trimAt = SystemClock.uptimeMillis()
        val freed = letGo(level)
        val h = handler
        if (h == null) { Log.i(TAG, trimLine("trim from Android: ${trimName(level)}", freed, "", "")); return }
        h.post {
            runCatching {
                val psi = psiNow()
                val gc = if (collects(level)) collect() else ""
                val t = trimLine("trim from Android: ${trimName(level)}", freed, gc, psi)
                Log.i(TAG, t); keep(t)
            }
            runCatching { val l = line(); Log.i(TAG, l); keep(l) }
        }
    }

    /** every trim registration at [level] (the main thread): what each let go, -1 = it failed */
    private fun letGo(level: Int): List<Pair<String, Long>> =
        synchronized(this) { trimmers.toList() }.map { (n, f) -> n to runCatching { f(level) }.getOrDefault(-1L) }

    // ---------------------------------------------------------------- issue triage 2026-11-14: relief before the game's own big allocation
    /**
     * RELIEF BEFORE THE GAME'S OWN BIG ALLOCATION (issue triage 2026-11-14, the 1.0.29 - 1.0.31 low-memory cluster #236 - #276; the
     * user, 2026-11-13: "yes on the triage things"). 24 reports from 18 Thors (8, 12 and 16 GB): Android's low-memory killer closed
     * DS Forever - and the game with it, the game runs as our child, so the killer can only name us - while the GAME loaded: its own
     * memory jumped 1 - 2 GB within seconds of Play, the world or a zone, while DS Forever held 230 - 620 MB and flat in its last minute.
     * What DS Forever can do is hold less at exactly those moments. [relieve]: when the game's screen comes up (Play) and when it reaches
     * the world (GameLife), every trim registration lets go at [RELIEF_LEVEL] (what is not on show: the World map room's pictures, the
     * Settings miniatures, the loading pictures once no loading page draws them), then ONE Java collection on this watch's thread (the
     * reports' Java heap swung 20 -> 150 MB of garbage between the runtime's own collections). Android's own running-low / critical
     * trims get the same collection ([collects]). At most one collection per [GC_EVERY_MS]. Each relief and trim is kept in mem.log
     * with the kernel's memory pressure ([psiNow]: what the killer acts on), so the next report says what the moment was.
     */
    const val RELIEF_LEVEL = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    const val GC_EVERY_MS = 10_000L
    @Volatile private var gcAt = Long.MIN_VALUE / 2

    /** a collection may run now: none in the last [GC_EVERY_MS] (pure: the tests) */
    fun gcDue(now: Long, last: Long): Boolean = now - last >= GC_EVERY_MS
    /** a trim worth a collection: running-low or worse while shown, any background level, Android's onLowMemory */
    fun collects(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    /** [why] the game is about to take memory: let go and collect (any thread), after [delayMs] (GameLife's world relief waits for the
     *  start screen to stop drawing the loading pictures); nothing before [start] (the tests, no watch) */
    fun relieve(why: String, delayMs: Long = 0L) {
        val h = handler ?: return
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                val freed = letGo(RELIEF_LEVEL)
                h.post { runCatching { val l = trimLine("relief ($why)", freed, collect(), psiNow()); Log.i(TAG, l); keep(l) } }
            }, delayMs.coerceAtLeast(0L))
        }
    }

    /** one Java collection when due (this watch's thread): "java 140 -> 31 MB", "" = not due */
    private fun collect(): String {
        val now = SystemClock.uptimeMillis()
        if (!gcDue(now, gcAt)) return ""
        gcAt = now
        val rt = Runtime.getRuntime()
        val before = rt.totalMemory() - rt.freeMemory()
        rt.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        return "java ${mbB(before)} -> ${mbB(after)} MB"
    }

    /** "relief (world): let go worldroom 43 MB, scenes 0 MB; java 140 -> 31 MB; psi some 2.10 full 0.40" (pure: the tests) */
    fun trimLine(head: String, freed: List<Pair<String, Long>>, gc: String, psi: String): String = buildString {
        append(head)
        val kept = freed.filter { it.second != 0L }
        if (kept.isNotEmpty()) append(": let go ").append(kept.joinToString { (n, b) -> "$n ${if (b < 0) "failed" else "${mbB(b)} MB"}" })
        if (gc.isNotEmpty()) append("; ").append(gc)
        if (psi.isNotEmpty()) append("; ").append(psi)
    }

    /**
     * THE KERNEL'S MEMORY PRESSURE (/proc/pressure/memory, what Android's low-memory killer acts on), read only at a trim or a relief:
     * "psi some 2.10 full 0.40" (the last 10 s averages, %). ONE failed read and it is never read again this process, as [gpuBytes]
     * (an enforcing device refuses it; the Thor logs a denial even when a read works, so never on every line).
     */
    internal var psiRead: () -> String? = { runCatching { java.io.File("/proc/pressure/memory").readText() }.getOrNull() }
    @Volatile internal var psiDead = false
    internal fun psiNow(): String {
        if (psiDead) return ""
        val t = runCatching { psiRead() }.getOrNull()
        val n = t?.let { psiNote(it) }.orEmpty()
        if (n.isEmpty()) psiDead = true
        return n
    }
    /** "some avg10=2.10 ...\nfull avg10=0.40 ..." -> "psi some 2.10 full 0.40", "" = not that file (pure: the tests) */
    fun psiNote(text: String): String {
        fun avg10(kind: String) = text.lineSequence().firstOrNull { it.startsWith("$kind ") }
            ?.split(' ')?.firstOrNull { it.startsWith("avg10=") }?.removePrefix("avg10=")?.toDoubleOrNull()
        val some = avg10("some") ?: return ""
        val full = avg10("full")
        return "psi some ${"%.2f".format(java.util.Locale.US, some)}" + (full?.let { " full ${"%.2f".format(java.util.Locale.US, it)}" } ?: "")
    }
    fun trimName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "running-moderate"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "running-low"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "running-critical"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "ui-hidden"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
        LOW_MEMORY -> "low-memory"
        else -> "level $level"
    }

    /**
     * THE MAIN THREAD'S BEAT: a stamp posted to it every second; when the stamp is older than [STALL_MS] the main thread's own stack is
     * logged once for that stall (before Android's 5 s ANR; the ANR traces in the reports were cut before the main thread).
     */
    const val STALL_MS = 2_500L
    @Volatile private var beat = 0L
    @Volatile private var stallOf = -1L
    @Volatile private var beatPosted = false
    /** issue #230: the beat's checks run every [BEAT_MS] on their own thread (WCBEAT) */
    const val BEAT_MS = 250L
    /** issue #230: a second stack of the same stall at this age, just before Android's 5 s (what it was doing when the ANR fired) */
    const val STALL2_MS = 4_250L
    @Volatile private var stall2Of = -1L
    /** the longest beat gap since the last line (issue #230: a stall under [STALL_MS] said nothing at all) */
    @Volatile private var beatMax = 0L
    private fun checkStall(main: Handler, writer: Handler) {
        val now = SystemClock.uptimeMillis()
        if (beat == 0L) beat = now
        if (!beatPosted) { beatPosted = true; main.post { beat = SystemClock.uptimeMillis(); beatPosted = false } }
        val blocked = now - beat
        if (blocked > beatMax) beatMax = blocked
        val first = stalled(blocked, beat, stallOf)
        val second = !first && blocked >= STALL2_MS && stall2Of != beat
        if (first || second) {
            if (first) stallOf = beat else stall2Of = beat
            val l = stallLine(blocked, Looper.getMainLooper().thread.stackTrace.map { it.toString() })
            Log.w(TAG, l)
            runCatching { DiagLog.event("stall", l.take(1500)) }          // queued (the report's log), never waits here
            writer.post { keep(l) }                                       // mem.log is WCMEM's to write
        }
    }
    /** "main beat max 1.4 s" when the main thread was [BEAT_NOTE_MS] or more late to its beat since the last line, else "" (pure) */
    fun beatNote(maxMs: Long): String = if (maxMs >= BEAT_NOTE_MS) "main beat max ${maxMs / 100 / 10.0} s" else ""
    const val BEAT_NOTE_MS = 1_000L
    /** a stall worth its one line: blocked [STALL_MS] or more, and not this beat's stall again */
    fun stalled(blockedMs: Long, beat: Long, reportedBeat: Long): Boolean = blockedMs >= STALL_MS && reportedBeat != beat
    /** "stall: the main thread blocked 3.0 s: at a.b(F.kt:1) <- at c.d(...)": [frames] innermost first, at most [max] */
    fun stallLine(blockedMs: Long, frames: List<String>, max: Int = 24): String =
        "stall: the main thread blocked ${blockedMs / 100 / 10.0} s: " +
            frames.take(max).joinToString(" <- ") { "at $it" } + if (frames.size > max) " <- ..." else ""

    /**
     * the GPU driver's own total (Adreno's kgsl), bytes; null when this device does not let us read it. 1.0.29 key-lag review: the
     * Thor logged an SELinux denial for this file every 5 s. ONE failed read and it is never read again this process ([gpuDead]); a
     * read that works is taken at most every [GPU_EVERY_MS] (a permissive device logs its denial even when the read works).
     */
    internal var gpuRead: () -> Long? = { runCatching { java.io.File("/sys/class/kgsl/kgsl/page_alloc").readText().trim().toLong() }.getOrNull() }
    @Volatile internal var gpuDead = false
    @Volatile private var gpuAt = Long.MIN_VALUE / 2
    @Volatile private var gpuLast: Long? = null
    const val GPU_EVERY_MS = 60_000L
    internal fun gpuBytes(now: Long = SystemClock.uptimeMillis()): Long? {
        if (gpuDead) return null
        if (now - gpuAt < GPU_EVERY_MS) return gpuLast
        gpuAt = now
        val v = runCatching { gpuRead() }.getOrNull()
        if (v == null) gpuDead = true
        gpuLast = v
        return v
    }
    /** tests: the GPU read as at the process's start */
    internal fun gpuReset() { gpuDead = false; gpuAt = Long.MIN_VALUE / 2; gpuLast = null }

    /** the line's last part: "low 317 MB, jheap 133/512 MB, gpu 880 MB, trim running-low 12 s ago" (pure: the tests) */
    fun extras(lowKb: Long, jUsed: Long, jMax: Long, gpu: Long?, trimLevel: Int, trimAgoMs: Long): String = buildList {
        if (lowKb >= 0) add("low ${mb(lowKb)} MB")
        add("jheap ${mbB(jUsed)}/${mbB(jMax)} MB")
        gpu?.let { add("gpu ${mbB(it)} MB") }
        if (trimLevel >= 0) add("trim ${trimName(trimLevel)} ${trimAgoMs / 1000} s ago")
    }.joinToString(", ")

    /** the history in its own file (the 8 GB test, 2026-11-08: the low-memory killer's thousands of log lines pushed WCMEM out of the
     *  log), newest last, at most [KEEP] lines; crash reports attach it (ProblemReport's memory.txt) */
    private const val KEEP = 720                                    // an hour at 5 s
    val file = java.io.File("/sdcard/WowCompanion/logs/mem.log")
    // 1.0.29 key-lag review: the whole 720-line file was read and rewritten every 5 s through FUSE (/sdcard). Now one appended line per
    // sample, cut back to KEEP lines only every 10 minutes or past twice KEEP (CappedLog); on this watch's own thread
    private val memLog by lazy { CappedLog(file, KEEP) }
    private fun keep(l: String) = runCatching {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        memLog.append("$stamp $l", SystemClock.uptimeMillis())
    }
    /** the newest [KEEP] lines (the file may hold up to twice that between its trims) */
    fun history(): String = runCatching { CappedLog.lastLines(file, KEEP) }.getOrDefault("")

    /**
     * THE KILLED RUN'S OWN LINES (review of the 1.0.28 crash cluster: #164 #167 #171 #182 were reported 8 min - 4 h after the restart,
     * so mem.log's newest lines were the NEW run's, and #182's killed run had left the 1 h file altogether): the last [max] lines of
     * [history] stamped at or before [whenMs] (an exit record's time; the stamp is to the second, so that second counts). The stamps carry
     * no year: one more than a day after [whenMs] is last year's (a kill just after New Year). Pure (the tests).
     */
    const val BEFORE_LINES = 72
    fun linesUpTo(history: String, whenMs: Long, max: Int = BEFORE_LINES): List<String> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = whenMs }
        val year = cal.get(java.util.Calendar.YEAR)
        val fmt = java.text.SimpleDateFormat("yyyy MM-dd HH:mm:ss", java.util.Locale.US).apply { isLenient = false }
        val limit = whenMs - whenMs % 1000 + 999
        return history.lineSequence().filter { it.length > 14 }.filter { l ->
            val t = runCatching { fmt.parse("$year " + l.substring(0, 14))!!.time }.getOrNull() ?: return@filter false
            val at = if (t > whenMs + 86_400_000L) runCatching { fmt.parse("${year - 1} " + l.substring(0, 14))!!.time }.getOrDefault(t) else t
            at <= limit
        }.toList().takeLast(max)
    }

    @Volatile private var lastPssMb = 0L
    internal fun mb(kb: Long) = (kb + 512) / 1024
    internal fun mbB(b: Long) = (b + 512 * 1024) / (1024 * 1024)

    /** one line: "pss 1187 MB (java 29, native 974, gfx 146, code 19) heap 1061/1179 MB | minimap 22, icons 9, ... | art kit, game up" */
    fun line(): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        fun stat(k: String) = mi.getMemoryStat(k)?.toLongOrNull() ?: 0L
        val pss = mi.totalPss.toLong()
        lastPssMb = mb(pss)
        val parts = "java ${mb(stat("summary.java-heap"))}, native ${mb(stat("summary.native-heap"))}, gfx ${mb(stat("summary.graphics"))}, " +
            "code ${mb(stat("summary.code"))}"
        val heap = "heap ${mbB(Debug.getNativeHeapAllocatedSize())}/${mbB(Debug.getNativeHeapSize())} MB"
        val caches = synchronized(this) { sources.toList() }.joinToString(", ") { (n, f) ->
            "$n ${runCatching { mbB(f()) }.getOrDefault(-1)}"
        }
        val doing = buildList {
            add("room $room")
            ArtHarvest.currentStep?.let { add("art $it") }
            runCatching { IndoorSchedulerStep.status?.let { s -> add("indoor ${s.phase.name.lowercase()}${if (s.now.isNotEmpty()) " " + s.now.take(24) else ""}") } }
            add(if (LauncherHooks.gameUp()) "game up" else "game down")
        }.joinToString(", ")
        val rt = Runtime.getRuntime()
        val low = lowKb; lowKb = -1L                                  // the low since the last line, then the next window
        val more = extras(low, rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), gpuBytes(), trimLevel,
            if (trimAt > 0L) SystemClock.uptimeMillis() - trimAt else 0L)
        val bm = beatMax; beatMax = 0L                                // issue #230: the main thread's worst beat and touch since the last line
        val lag = listOf(beatNote(bm), runCatching { TouchLag.take() }.getOrDefault("")).filter { it.isNotEmpty() }.joinToString(", ")
        if (lag.isNotEmpty()) return "pss ${mb(pss)} MB ($parts) $heap | ${device()} | $caches | $doing | $more, $lag"
        return "pss ${mb(pss)} MB ($parts) $heap | ${device()} | $caches | $doing | $more"
    }

    /**
     * 1.0.19 (the 1.0.18 reports: 12 GB Thors still closed for memory with DS Forever itself at ~600 MB; on the Thor the game's own process
     * is ~4.6 GB): THE GAME'S MEMORY AND THE DEVICE'S. The game's Wine processes run under OUR uid (children of this app), so their
     * /proc/<pid>/smaps_rollup is ours to read: their PSS summed (every [GAME_EVERY_MS]: the read walks a 4 GB process's pages). The
     * device's MemAvailable and free swap every line (/proc/meminfo). The GPU part of the game's memory is not in smaps (memtrack): the
     * device's free RAM covers it. "game 2850 MB (13), free 6480 MB, swap 3204/4096 MB"
     */
    @Volatile private var gameAt = 0L
    @Volatile private var gameMb = -1L
    @Volatile private var gameN = 0
    private const val GAME_EVERY_MS = 30_000L
    private fun device(): String {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - gameAt >= GAME_EVERY_MS || gameAt == 0L) { gameAt = now; runCatching { gameNow() } }
        val mem = runCatching { java.io.File("/proc/meminfo").readLines() }.getOrDefault(emptyList())
        fun kb(k: String) = mem.firstOrNull { it.startsWith("$k:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        val avail = kb("MemAvailable"); val swapT = kb("SwapTotal"); val swapF = kb("SwapFree")
        val game = if (gameMb >= 0) "game $gameMb MB ($gameN)" else "game ?"
        val free = avail?.let { "free ${mb(it)} MB" } ?: "free ?"
        val swap = if (swapT != null && swapF != null) "swap ${mb(swapF)}/${mb(swapT)} MB" else "swap ?"
        return "$game, $free, $swap"
    }
    private fun gameNow() {
        val me = android.os.Process.myPid(); val uid = android.os.Process.myUid()
        var kb = 0L; var n = 0
        for (d in java.io.File("/proc").listFiles() ?: emptyArray()) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val st = runCatching { java.io.File(d, "status").readLines() }.getOrNull() ?: continue
            val u = st.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            if (u != uid) continue
            val pss = runCatching { java.io.File(d, "smaps_rollup").readLines() }.getOrNull()
                ?.firstOrNull { it.startsWith("Pss:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: continue
            kb += pss; n++
        }
        gameMb = mb(kb); gameN = n
    }
}

/**
 * A LOG FILE OF AT MOST ABOUT [keep] LINES THAT IS ONLY APPENDED TO (MemWatch's mem.log, 1.0.29): one line per [append]; the file is cut
 * back to its newest [keep] lines (temp + rename: a crash never leaves it half written) only when it passes twice [keep] or every
 * [trimEveryMs] while it is over [keep]. Pure java.io (the tests).
 */
class CappedLog(private val file: java.io.File, private val keep: Int, private val trimEveryMs: Long = 10 * 60_000L) {
    private var lines = -1
    private var trimmedAt = 0L
    /** the trims done (tests) */
    var trims = 0; private set

    @Synchronized fun append(line: String, now: Long) {
        file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        if (lines < 0) { lines = if (file.isFile) file.useLines { it.count() } else 0; trimmedAt = now }
        java.io.FileOutputStream(file, true).use { it.write((line.replace('\n', ' ') + "\n").toByteArray()) }
        lines++
        if (lines > 2 * keep || (lines > keep && now - trimmedAt >= trimEveryMs)) trim(now)
    }

    private fun trim(now: Long) {
        val kept = file.readLines().takeLast(keep)
        val tmp = java.io.File(file.path + ".part")
        tmp.writeText(kept.joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        lines = kept.size; trimmedAt = now; trims++
    }

    companion object {
        /** the newest [max] lines of [f], newline-ended; "" = none */
        fun lastLines(f: java.io.File, max: Int): String {
            if (!f.isFile) return ""
            val ls = f.readLines().filter { it.isNotEmpty() }.takeLast(max)
            return if (ls.isEmpty()) "" else ls.joinToString("\n", postfix = "\n")
        }
    }
}
