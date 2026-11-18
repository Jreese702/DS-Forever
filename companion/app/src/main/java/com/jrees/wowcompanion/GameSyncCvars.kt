package com.jrees.wowcompanion

import java.io.File

/**
 * WOW'S OWN SERVER SYNC OF KEYBINDS / SETTINGS (settings follow, the user's pick 2026-11-18: "Ours + WoW's server sync"). A deliberate,
 * user-ruled exception to leave-no-trace: the user asked for these specific game settings to be changed. ONLY the keys in [KEYS] are
 * ever written, and only while Settings.gameSync is on (Settings > General, "Sync game keybinds & macros"; OFF by default: the user's
 * hard rule "dont delete my current hunters settings on local" - with sync newly on, the server's older copy can replace a client's
 * local bindings at its next login).
 *
 * The names were checked in WoW Forever's own client (its Wow.exe, 2026-11-18): synchronizeBindings, synchronizeConfig and
 * synchronizeChatFrames exist; synchronizeSettings and synchronizeMacros do NOT. Chat frames are not asked for, so not touched.
 *
 * On: each key's value before DS Forever's first write is recorded ONCE beside Config.wtf ([PRIOR]: "key=value", or "key" alone for a
 * key the file did not have), then the keys are "1". Off with a record: each key goes back to its recorded value (a recorded-missing key
 * is taken out again), and the record goes. Off with no record: nothing. Never restored after a stream (it is the player's setting, not a
 * stream's). Pure (java.io).
 */
object GameSyncCvars {
    val KEYS = listOf("synchronizeBindings", "synchronizeConfig")
    const val PRIOR = ".dsf-sync-prior"

    private fun keyOf(line: String): Pair<String, String>? {
        val p = line.trim().takeIf { it.startsWith("SET ", ignoreCase = true) }?.drop(4)?.trim()?.split(" ", limit = 2) ?: return null
        val k = KEYS.firstOrNull { it.equals(p[0], ignoreCase = true) } ?: return null
        return k to (p.getOrNull(1)?.trim() ?: "")
    }

    /** the keys' values now in [lines] ([KEYS]' spelling -> the value as written, quotes and all; a missing key is absent) */
    fun values(lines: List<String>): Map<String, String> = lines.mapNotNull(::keyOf).toMap()

    /** [lines] with every key of [want] set (value as written) and every key of [drop] taken out. Pure */
    fun apply(lines: List<String>, want: Map<String, String>, drop: Set<String> = emptySet()): List<String> {
        val done = HashSet<String>()
        val out = lines.mapNotNull { l ->
            val kv = keyOf(l) ?: return@mapNotNull l
            when {
                kv.first in drop -> null
                kv.first in want -> if (done.add(kv.first)) "SET ${kv.first} ${want.getValue(kv.first)}" else null
                else -> l
            }
        }
        return out + want.filterKeys { it !in done }.map { (k, v) -> "SET $k $v" }
    }

    /** the record's text for [now] ([values] of the file before the first write) */
    fun priorText(now: Map<String, String>): String = KEYS.joinToString("\n") { k -> now[k]?.let { "$k=$it" } ?: k } + "\n"

    /** the record read back: key -> the value as it was (null = the file did not have the key) */
    fun parsePrior(text: String): Map<String, String?> = text.lines().filter { it.isNotBlank() }.mapNotNull { l ->
        val k = l.substringBefore('=').trim()
        KEYS.firstOrNull { it == k }?.let { it to (if ('=' in l) l.substringAfter('=') else null) }
    }.toMap()

    /** [configWtf] (the game's Config.wtf) for [on]; what was done, for the log (key names only). A missing file: the game makes its own */
    fun ensure(configWtf: File, on: Boolean): String {
        val prior = File(configWtf.parentFile, PRIOR)
        if (!configWtf.isFile) return "game sync: no Config.wtf yet"
        val lines = configWtf.readLines()
        val now = values(lines)
        val updated: List<String>
        if (on) {
            if (!prior.exists()) WowSwitch.replace(prior, priorText(now).toByteArray())
            updated = apply(lines, KEYS.associateWith { "\"1\"" })
        } else {
            if (!prior.exists()) return "game sync: off"
            val was = parsePrior(prior.readText())
            updated = apply(lines, was.filterValues { it != null }.mapValues { it.value!! }, was.filterValues { it == null }.keys)
        }
        if (updated != lines) WowSwitch.replace(configWtf, (updated.joinToString("\n") + "\n").toByteArray())
        if (!on) prior.delete()
        return if (on) "game sync: on (${KEYS.joinToString(" ")})" else "game sync: off (${KEYS.joinToString(" ")} as they were)"
    }
}
