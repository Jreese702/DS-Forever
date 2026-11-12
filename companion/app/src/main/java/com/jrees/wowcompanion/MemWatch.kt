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
                runCatching { checkStall(main) }
                h.postDelayed(this, FAST_MS)
            }
        }
        h.postDelayed(fast, FAST_MS)
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
    /** a trim from Android (the main thread): logged, handed on, and a line at once */
    fun trimmed(level: Int) {
        trimLevel = level; trimAt = SystemClock.uptimeMillis()
        val freed = synchronized(this) { trimmers.toList() }.map { (n, f) -> n to runCatching { f(level) }.getOrDefault(-1L) }
        Log.i(TAG, "trim from Android: ${trimName(level)}" + if (freed.isEmpty()) "" else
            " (let go: ${freed.joinToString { (n, b) -> "$n ${if (b < 0) "failed" else "${mbB(b)} MB"}" }})")
        handler?.post { runCatching { val l = line(); Log.i(TAG, l); keep(l) } }
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
    private fun checkStall(main: Handler) {
        val now = SystemClock.uptimeMillis()
        if (beat == 0L) beat = now
        if (!beatPosted) { beatPosted = true; main.post { beat = SystemClock.uptimeMillis(); beatPosted = false } }
        val blocked = now - beat
        if (stalled(blocked, beat, stallOf)) {
            stallOf = beat
            val l = stallLine(blocked, Looper.getMainLooper().thread.stackTrace.map { it.toString() })
            Log.w(TAG, l); keep(l)
        }
    }
    /** a stall worth its one line: blocked [STALL_MS] or more, and not this beat's stall again */
    fun stalled(blockedMs: Long, beat: Long, reportedBeat: Long): Boolean = blockedMs >= STALL_MS && reportedBeat != beat
    /** "stall: the main thread blocked 3.0 s: at a.b(F.kt:1) <- at c.d(...)": [frames] innermost first, at most [max] */
    fun stallLine(blockedMs: Long, frames: List<String>, max: Int = 24): String =
        "stall: the main thread blocked ${blockedMs / 100 / 10.0} s: " +
            frames.take(max).joinToString(" <- ") { "at $it" } + if (frames.size > max) " <- ..." else ""

    /** the GPU driver's own total (Adreno's kgsl), bytes; null when this device does not let us read it */
    @Volatile private var gpuReadable = true
    private fun gpuBytes(): Long? {
        if (!gpuReadable) return null
        val v = runCatching { java.io.File("/sys/class/kgsl/kgsl/page_alloc").readText().trim().toLong() }.getOrNull()
        if (v == null) gpuReadable = false
        return v
    }

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
    private fun keep(l: String) = runCatching {
        file.parentFile?.mkdirs()
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = (if (file.isFile) file.readLines() else emptyList()) + "$stamp $l"
        file.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
    }
    fun history(): String = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")

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
