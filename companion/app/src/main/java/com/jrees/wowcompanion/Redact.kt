package com.jrees.wowcompanion

/**
 * THE PROBLEM REPORT'S PRIVACY PASS (board f-problem-report): everything a report shows or saves goes through [text] first. Nothing
 * personal leaves the device in a report: e-mail addresses (the Battle.net login), BattleTags, the character / realm / guild /
 * party names DS Forever saw this run ([known]), chat text, IP addresses, secrets written as key=value, the user name in a file path.
 * Pure: the off-device tests drive it.
 */
object Redact {
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val BATTLETAG = Regex("""(?<![\p{L}\p{N}])[\p{L}][\p{L}\p{N}]{1,11}#\d{4,6}(?!\d)""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    private val IPV6 = Regex("""(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])""")
    private val SECRET = Regex("""(?i)\b(password|passwd|pwd|pass|token|auth|authorization|secret|session|cookie|ticket|account|login|email)(\s*[=:]\s*)((?i:bearer|basic)\s+\S+|"[^"]*"|'[^']*'|\S+)""")
    /** chat and other typed text, as our logs and the add-on's documents name it */
    private val CHAT = Regex("""(?i)("?(?:text|msg|message|body|chat|whisper|say|note)"?\s*[=:]\s*)("(?:[^"\\]|\\.)*"|'[^']*'|[^,;}\]\n]+)""")
    private val WIN_USER = Regex("""(?i)([A-Z]:\\Users\\)[^\\/\s]+""")
    private val NIX_USER = Regex("""(/home/|/Users/)[^/\s]+""")
    private val URL_QUERY = Regex("""(https?://[^\s?#]+)\?[^\s#]+""")

    /** the run's names (characters, realms, guilds, party, friends): replaced wherever they appear, whole words, any case */
    private val known = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private const val MAX_KNOWN = 300

    /** a name DS Forever saw (from the add-on's documents); 2 characters or more, at most [MAX_KNOWN] kept */
    fun know(name: String?) {
        val n = name?.trim()?.substringBefore('-')?.takeIf { it.length >= 2 } ?: return       // "Name-Realm" = both
        if (known.size < MAX_KNOWN) known.add(n)
        name.trim().substringAfter('-', "").takeIf { it.length >= 2 }?.let { if (known.size < MAX_KNOWN) known.add(it) }
    }

    fun forget() = known.clear()

    /** the names known this run (for a report made on another thread) */
    fun names(): List<String> = synchronized(known) { known.toList() }

    /**
     * The names as ONE pattern (batch16: a report "stuck" at Making the report: a pattern compiled per name per line, with item and
     * quest names learnt too, was minutes of work). Longest first, so "Arthas" goes before "Art"; whole words, any case. Cached for the
     * same list.
     */
    private var cachedNames: List<String>? = null
    private var cachedPattern: Regex? = null
    @Synchronized fun namePattern(names: Collection<String>): Regex? {
        val list = names.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
        if (list.isEmpty()) return null
        if (list == cachedNames) return cachedPattern
        val p = Regex("(?<![\\p{L}\\p{N}])(?:" + list.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        cachedNames = list; cachedPattern = p
        return p
    }

    fun text(s: String, names: Collection<String> = names()): String = text(s, namePattern(names))

    fun text(s: String, names: Regex?): String {
        var t = s
        t = EMAIL.replace(t, "<email>")
        t = BATTLETAG.replace(t, "<battletag>")
        t = SECRET.replace(t) { m -> "${m.groupValues[1]}${m.groupValues[2]}<redacted>" }
        t = CHAT.replace(t) { m -> "${m.groupValues[1]}<text>" }
        t = URL_QUERY.replace(t) { m -> "${m.groupValues[1]}?<query>" }
        t = IPV6.replace(t, "<ip>")
        t = IPV4.replace(t, "<ip>")
        t = WIN_USER.replace(t) { m -> "${m.groupValues[1]}<user>" }
        t = NIX_USER.replace(t) { m -> "${m.groupValues[1]}<user>" }
        if (names != null) t = names.replace(t, "<name>")
        return t
    }
}
