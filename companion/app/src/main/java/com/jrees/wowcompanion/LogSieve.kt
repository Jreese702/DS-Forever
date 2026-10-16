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
    private val SETUP_TAGS = setOf("WCSETUP", "WCLOGIN", "DSFORK", "WCREPORT")
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

    /** a game crash report's head (the time, the command, the exception, the first stack lines): [maxLines] lines, nothing after */
    fun gameCrashHead(text: String, maxLines: Int = 60): String =
        text.lineSequence().take(maxLines).joinToString("\n") + "\n[the rest of the game's report is left out]"
}
