package com.jrees.wowcompanion

/**
 * THE GAME'S LAST WORDS WHEN IT CLOSES AS IT STARTS (#577, 1.0.37, a Spanish report: after an unclean stop every Play ended "with error
 * status 1 after 0 s", 79 - 310 ms in, six times; that is proot / box64 / Wine giving up before the client runs, and the report held none
 * of their output). The guest program's stdout and stderr (ProcessHelper drains them for the game's start, GuestProgramLauncherComponent)
 * end here: the last [MAX_LINES] lines, each cut to [MAX_LEN] characters, kept per game run ([started] clears them). [GameLife.ended]
 * writes them to DS Forever's own log when the game closed within [GameLife.QUICK_EXIT_MS], and the report shows the last such run's
 * ([lastQuickExit]). Pure apart from the lock: the off-device tests drive it.
 */
object GuestOutput {
    const val MAX_LINES = 40
    const val MAX_LEN = 240
    private val ring = ArrayDeque<String>()
    private var dropped = 0

    /** a new game run: the last run's lines go */
    @JvmStatic fun started() = synchronized(ring) { ring.clear(); dropped = 0 }

    /** one line of the guest program's output (any thread) */
    @JvmStatic fun line(s: String?) {
        val l = clean(s ?: return).ifBlank { return }
        synchronized(ring) {
            ring.addLast(l)
            while (ring.size > MAX_LINES) { ring.removeFirst(); dropped++ }
        }
    }

    /** this run's last lines, oldest first, and how many came before them */
    fun tail(): Pair<List<String>, Int> = synchronized(ring) { ring.toList() to dropped }

    /** the report's words for the last run that closed as it started (null = none in this process) */
    @Volatile var lastQuickExit: String? = null
        internal set

    /**
     * a line as the log keeps it: control characters out, [MAX_LEN] at most, and anything shaped like an e-mail address masked (the
     * output comes before the client runs, so it holds paths and loader errors, never a login; this is only a guard)
     */
    internal fun clean(s: String): String =
        s.replace(Regex("[\\p{Cntrl}&&[^\\t]]"), "").replace('\t', ' ')
            .replace(Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""), "[e-mail]")
            .trim().take(MAX_LEN)

    /** the report's block: [lines] with how many came before ([before]); "(no output)" when the program said nothing */
    fun block(lines: List<String>, before: Int): String =
        if (lines.isEmpty()) "(no output)"
        else (if (before > 0) "... ($before earlier lines)\n" else "") + lines.joinToString("\n")
}
