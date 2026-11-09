package com.jrees.wowcompanion

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
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
    private const val DUMP_AT_MB = 900L
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

    fun start() {
        if (thread != null) return
        val t = HandlerThread("WCMEM").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        val tick = object : Runnable {
            override fun run() {
                runCatching { val l = line(); Log.i(TAG, l); keep(l) }
                runCatching { maybeDump(lastPssMb) }
                h.postDelayed(this, EVERY_MS)
            }
        }
        h.postDelayed(tick, 5_000L)
    }

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

    @Volatile private var lastPssMb = 0L
    private fun mb(kb: Long) = (kb + 512) / 1024
    private fun mbB(b: Long) = (b + 512 * 1024) / (1024 * 1024)

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
        return "pss ${mb(pss)} MB ($parts) $heap | $caches | $doing"
    }
}
