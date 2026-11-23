package com.jrees.wowcompanion

/**
 * WHAT A PROBLEM REPORT'S logs.txt KEEPS (issue #9, 1.0.8: "crashing on launch", and the report held ~760 lines of CascLib's
 * expected misses and CDN fetches from the indoor-map bake, and nothing about the crash). Pure: the tests drive it.
 * - Ours: our own tags, the launcher's untagged ReleaseTree lines ("I: …", the game's exit status among them), and Android's crash
 *   lines (AndroidRuntime's FATAL EXCEPTION, libc's Fatal signal, the tombstone's DEBUG).
 * - The expected noise (WCCASC "open failed / read failed / opened read-only", WCHARVEST "cdn archives: fetched" / "fetched …") is
 *   SUMMARISED: how many, from when to when, the first and last few.
 * - The important lines (errors, crashes, exits, the setup, the sign-in, the launcher's game start and end) go LAST, after the rest,
 *   so the size trims and the receiver's newest-lines rule keep them; the ordinary lines keep their newest [MAX_ORDINARY].
 */
object LogSieve {
    const val MAX_ORDINARY = 1_500
    const val MAX_IMPORTANT = 400
    const val SAMPLES = 3

    /** a `logcat -v threadtime` line: date, time, pid, tid, level, tag (possibly empty), message */
    private val LINE = Regex("""^(\S+\s+\S+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*:\s?(.*)$""")

    data class Line(val raw: String, val time: String, val pid: String, val level: Char, val tag: String, val msg: String)

    fun parse(raw: String): Line? = LINE.find(raw)?.let { m ->
        val g = m.groupValues
        Line(raw, g[1], g[2], g[4][0], g[5].trim(), g[6])
    }

    private val CRASH_TAGS = setOf("AndroidRuntime", "libc", "DEBUG", "crash_dump64", "crash_dump32", "tombstoned")
    private val SETUP_TAGS = setOf("WCSETUP", "WCLOGIN", "DSFORK", "WCREPORT", "WCMEM")      // WCMEM: the memory watch, never trimmed
    private val IMPORTANT_WORDS = Regex("""(?i)terminated|crash|fatal|exception|\banr\b|exit(ed)?\b|killed|died|error status|out of memory|failed to launch""")

    /** the launcher's own Timber lines in release: no tag, the message starts with its level ("I: …", ReleaseTree) */
    fun releaseTree(l: Line): Boolean = (l.tag.isEmpty() || l.tag == "null") && Regex("""^[IWEA]: """).containsMatchIn(l.msg)

    fun ours(l: Line): Boolean = ProblemReport.ourTag(l.tag) || l.tag in CRASH_TAGS || releaseTree(l)

    /** the expected noise, by kind; null = not noise */
    fun noise(l: Line): String? = when {
        l.tag.startsWith("WCCASC") && (l.msg.startsWith("open failed") || l.msg.startsWith("read failed") || l.msg.startsWith("opened read-only")) ->
            "CascLib's expected misses (a file not on the device: it is fetched next)"
        l.tag.startsWith("WCHARVEST") && (l.msg.startsWith("cdn archives: fetched") || l.msg.startsWith("fetched ")) ->
            "game files fetched from the CDN"
        else -> null
    }

    fun important(l: Line): Boolean = l.level == 'E' || l.level == 'F' || l.level == 'A' || l.tag in CRASH_TAGS ||
        l.tag in SETUP_TAGS || IMPORTANT_WORDS.containsMatchIn(l.msg)

    /** logs.txt's logcat part from the raw lines (oldest first); [prefix] is put before each kept line */
    fun sieve(raw: List<String>, prefix: String = "log   "): String {
        val ordinary = ArrayList<String>()
        val importantLines = ArrayList<String>()
        val noisy = LinkedHashMap<String, MutableList<Line>>()
        for (r in raw) {
            val l = parse(r) ?: continue
            if (!ours(l)) continue
            val n = noise(l)
            when {
                n != null -> noisy.getOrPut(n) { ArrayList() }.add(l)
                important(l) -> importantLines.add(prefix + r)
                else -> ordinary.add(prefix + r)
            }
        }
        val out = StringBuilder()
        if (ordinary.size > MAX_ORDINARY) out.append("[${ordinary.size - MAX_ORDINARY} older lines cut]\n")
        ordinary.takeLast(MAX_ORDINARY).forEach { out.append(it).append('\n') }
        if (noisy.isNotEmpty()) {
            out.append("\n===== repeated lines, summarised =====\n")
            for ((kind, ls) in noisy) {
                val pids = ls.map { it.pid }.distinct()
                out.append("${ls.size} x $kind, ${ls.first().time} .. ${ls.last().time}${if (pids.size > 1) " (processes ${pids.joinToString()})" else ""}\n")
                val shown = if (ls.size <= SAMPLES * 2) ls else ls.take(SAMPLES) + ls.takeLast(SAMPLES)
                shown.forEach { out.append("  ").append(it.raw).append('\n') }
                if (ls.size > SAMPLES * 2) out.append("  … ${ls.size - SAMPLES * 2} more\n")
            }
        }
        if (importantLines.isNotEmpty()) {
            out.append("\n===== errors, crashes, exits, setup and sign-in =====\n")
            if (importantLines.size > MAX_IMPORTANT) out.append("[${importantLines.size - MAX_IMPORTANT} older ones cut]\n")
            importantLines.takeLast(MAX_IMPORTANT).forEach { out.append(it).append('\n') }
        }
        return out.toString().trimEnd()
    }

    /**
     * AN ANR's THREADS, MAIN FIRST (issues #153 #177 #191, 1.0.24 - 1.0.25 "stopped responding": the uploaded crash record is cut at
     * 15 000 characters, and Android's ANR trace opens with ~20 KB of the runtime's own stats, so every report ended inside the "main"
     * thread's header - the one stack that says what blocked). A text with a "---------- Trace ----------" part and a "main" thread:
     * its head, then the main thread, then the threads running our own code (com.jrees / app.gamenative / com.winlator), then the
     * rest as it was. Anything else comes back unchanged. Pure.
     */
    fun traceFirst(text: String): String {
        val mark = text.indexOf(TRACE_MARK)
        if (mark < 0) return text
        val head = text.substring(0, mark + TRACE_MARK.length)
        val lines = text.substring(mark + TRACE_MARK.length).split('\n')
        // the blocks: a thread starts at a line `"name" ... prio=.. tid=..` and ends at the first blank line
        val blocks = ArrayList<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (THREAD_START.containsMatchIn(lines[i])) {
                var j = i
                while (j + 1 < lines.size && lines[j + 1].isNotBlank()) j++
                blocks += i..j; i = j + 1
            } else i++
        }
        val main = blocks.firstOrNull { lines[it.first].startsWith("\"main\"") } ?: return text
        val ours = blocks.filter { b -> b != main && b.any { k -> OUR_FRAME.containsMatchIn(lines[k]) } }
        val moved = HashSet<Int>().apply { (listOf(main) + ours).forEach { addAll(it) } }
        val out = StringBuilder(head).append("\n[the main thread first, then the threads in our code; the full trace follows]\n")
        // #153: the main thread in short first (its callers in our code within the report's first lines), in full at the end
        val mainLines = main.map { lines[it] }
        val short = compactFrames(mainLines)
        for (l in short) out.append(l).append('\n')
        out.append('\n')
        for (b in ours) { for (k in b) out.append(lines[k]).append('\n'); out.append('\n') }
        out.append("[the rest of the trace]\n")
        lines.forEachIndexed { k, l -> if (k !in moved) out.append(l).append('\n') }
        if (short.size < mainLines.size) {
            out.append("\n[the main thread in full]\n")
            for (l in mainLines.drop(1).take(MAIN_FULL_MAX)) out.append(l).append('\n')
        }
        return out.toString().trimEnd() + "\n"
    }

    /**
     * A THREAD'S STACK IN SHORT (#153, 1.0.37: three freeze reports showed only the lock's own frames - Unsafe.park, LockSupport,
     * AbstractQueuedSynchronizer, ReentrantLock - before the report's 12 key lines ran out, so it never said who waited: a touch or a
     * pad button). [block] = the header line, then its lines: the first frame stays (what it is blocked in), every frame in our code
     * stays (com.jrees / app.gamenative / com.winlator, not R8's lambda shims), a run of 2 or more other frames becomes one
     * "at ... (N runtime frames)" line, a frame repeating the one before it (R8's "<init>(SourceFile:0)") goes, native frames past the
     * first [NATIVE_KEEP] become one line, the "|" and "-" lines stay. Pure.
     */
    fun compactFrames(block: List<String>): List<String> {
        if (block.isEmpty()) return block
        val out = ArrayList<String>(block.size)
        out.add(block[0])
        val pending = ArrayList<String>()
        var natives = 0
        var nativeFrom = -1
        var nativeTo = -1
        var nativeIndent = ""
        var lastKey: String? = null
        var frames = 0
        fun indentOf(l: String) = l.substring(0, l.length - l.trimStart().length)
        fun flushRun() {
            if (pending.size == 1) out.add(pending[0])
            else if (pending.size > 1) out.add("${indentOf(pending[0])}at ... (${pending.size} runtime frames)")
            pending.clear()
        }
        fun flushNative() {
            if (nativeFrom >= 0) out.add("${nativeIndent}native: #%02d-#%02d (${nativeTo - nativeFrom + 1} more native frames)".format(nativeFrom, nativeTo))
            nativeFrom = -1
        }
        for (l in block.drop(1)) {
            val t = l.trim()
            val nm = NATIVE_FRAME.find(t)
            if (nm != null) {
                flushRun()
                natives++
                if (natives <= NATIVE_KEEP) out.add(l)
                else { val n = nm.groupValues[1].toInt(); if (nativeFrom < 0) { nativeFrom = n; nativeIndent = indentOf(l) }; nativeTo = n }
                continue
            }
            flushNative()
            if (!t.startsWith("at ")) { flushRun(); out.add(l); continue }
            val key = t.substringBefore('(')
            if (key == lastKey) continue
            frames++
            val ours = OUR_FRAME.containsMatchIn(l) && !SHIM.containsMatchIn(t)
            if (frames == 1 || ours) { flushRun(); out.add(l); lastKey = key } else { pending.add(l); lastKey = key }
        }
        flushNative()
        flushRun()
        return out
    }
    /** native frames kept before the rest become one line */
    const val NATIVE_KEEP = 2
    /** the main thread's full stack at the end, at most this many lines */
    const val MAIN_FULL_MAX = 120
    private val NATIVE_FRAME = Regex("""^native: #(\d+) """)
    /** R8's lambda shims: in our packages, but they say nothing ("$r8$lambda$...", "...$$ExternalSyntheticLambda12.run") */
    private val SHIM = Regex("""r8.lambda.|ExternalSyntheticLambda""")
    private const val TRACE_MARK = "---------- Trace ----------"
    private val THREAD_START = Regex("""^"[^"]*".*\b(prio=\d+|tid=\d+)""")
    private val OUR_FRAME = Regex("""\bat (com\.jrees\.|app\.gamenative\.|com\.winlator\.)""")

    /** a game crash report's head (the time, the command, the exception, the first stack lines): [maxLines] lines, nothing after */
    fun gameCrashHead(text: String, maxLines: Int = 60): String =
        text.lineSequence().take(maxLines).joinToString("\n") + "\n[the rest of the game's report is left out]"
}
