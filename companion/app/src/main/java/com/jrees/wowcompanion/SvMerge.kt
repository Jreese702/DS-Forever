package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SETTINGS FOLLOW THE PLAYER, LOCAL <-> STREAMING (the user, 2026-11-18: "one thing that bothers me is that my keybinds and stuff on a
 * character in local does not carry over to that character in streaming"; the pick: "Ours + WoW's server sync"; the user's ruling for build 1489: OURS ONLY - WoW's own sync is already on by default on both installs, so DS Forever never writes its sync CVars). Local (WoW Forever on
 * the Thor) and Streaming (WoW Forever on the PC, through DS Forever Desktop) are two installs, so OUR saved tables (WowCompanionDB,
 * WowCompanionSpellsDB: controller binds, radials, fight spells, toolbar switches ...) were two copies.
 *
 * THE HARD SAFETY RULE (the user, 2026-11-18): "whatever you do please dont delete my current hunters settings on local". So nothing here
 * ever replaces a whole table with the other install's table:
 *  - [SvMerge.merge] works PER ENTRY: a table's own keys, and one level down (the per-character stores are keyed by name-realm, PadMap m36:
 *    DB.store["Name-Realm"]) each entry on its own. A list (keys 1..n) is one entry. An entry on one side only is kept / added, never
 *    dropped (no deletion ever crosses). The entries carry no timestamps of their own, so "newer" is a THREE-WAY test against the BASE (the
 *    copy the PC was last confirmed to hold): only an entry the PC changed since then, while the Thor's is still the base's, is taken
 *    from the PC. With no base (the FIRST sync: the PC was never set up) and in every conflict the Thor's LOCAL entry wins.
 *  - A PC change that empties an entry, or drops more than half of its settings, is refused (a lost or reset PC file, not an edit).
 *  - Before the PC is sent anything, the Thor's OWN game file is folded into the kept copy ([SvKeep.foldGameIn]): the PC gets the
 *    Thor's full settings, never a thin kept copy (the 11-18 hunter case), and the PC file's own skipped keys stay in its file.
 *  - A document that does not parse as a whole is not used at all (nothing written for that table).
 *  - Before every sync write a dated backup is kept beside the file (the last [SvBackup.KEEP]), and the Thor's kept copy as it was
 *    before the very first sync is kept for good ([SvBackup.PRESYNC]).
 * Pure (java.io + org.json): dsforever's SvSyncTest runs it.
 */
object SvLua {
    /** a value of OUR tables: String, Long, Double, Boolean, or a table (LinkedHashMap with String / Long keys) */
    class Bad(msg: String) : Exception(msg)

    /** [text] parsed: a bare table "{...}" (the add-on's `svkeep`) or a SavedVariables file ("Var = {...}"); [variable] picks the
     *  file's assignment. Throws [Bad] on anything that is not a complete, well-formed literal */
    fun parse(text: String, variable: String? = null): Map<Any, Any> {
        val p = P(text)
        p.ws()
        if (p.peek() == '{') {
            val t = p.table(0); p.ws()
            if (!p.end()) throw Bad("text after the table")
            return t
        }
        var found: Map<Any, Any>? = null
        while (!p.end()) {
            val name = p.name() ?: throw Bad("not an assignment at ${p.i}")
            p.ws(); p.expect('='); p.ws()
            val v = p.value(0)
            if (variable == null || name == variable) {
                if (v !is Map<*, *>) throw Bad("$name is not a table")
                @Suppress("UNCHECKED_CAST")
                found = v as Map<Any, Any>
            }
            p.ws()
            if (p.peek() == ';') { p.i++; p.ws() }
        }
        return found ?: throw Bad("no ${variable ?: "table"}")
    }

    /** the add-on's own `ser` (PadMap m51): sorted keys (numbers first), "[k]=v", %q strings; the table's leaf count beside it */
    fun ser(v: Any): String = StringBuilder().also { serTo(it, v) }.toString()

    /** the settings count the add-on sends as `n`: each non-table value 1, a table the sum of its own (an empty table 0) */
    fun leaves(v: Any?): Int = when (v) {
        null -> 0
        is Map<*, *> -> v.values.sumOf { leaves(it) }
        else -> 1
    }

    /** [t] without the top-level keys the add-on never sends (PadMap m51's SKIP: crash-recovery key state, probes, chat lines) */
    fun withoutSkipped(variable: String, t: Map<Any, Any>): Map<Any, Any> {
        val skip = SKIP[variable] ?: return t
        return t.filterKeys { it !in skip }
    }

    val SKIP = mapOf("WowCompanionSpellsDB" to setOf("keyProbe", "faceSwap", "faceCd", "combatFace"), "WowCompanionDB" to setOf("chatTop"))

    private fun keyOrder(a: Any, b: Any): Int = when {
        a is Long && b is Long -> a.compareTo(b)
        a is Long -> -1                                   // "number" < "string", as the add-on's keyLess
        b is Long -> 1
        else -> (a as String).compareTo(b as String)
    }

    private fun serTo(sb: StringBuilder, v: Any) {
        when (v) {
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for (k in v.keys.filterNotNull().sortedWith { a, b -> keyOrder(a, b) }) {
                    val x = v[k] ?: continue
                    if (!first) sb.append(',')
                    first = false
                    sb.append('['); serTo(sb, k); sb.append("]=")
                    serTo(sb, x)
                }
                sb.append('}')
            }
            is String -> quote(sb, v)
            is Long -> sb.append(v)
            is Double -> sb.append(if (v == Math.floor(v) && Math.abs(v) < 9.007199254740992E15) v.toLong().toString() else v.toString())
            is Boolean -> sb.append(v)
            else -> throw Bad("not a setting value")
        }
    }

    /** Lua 5.1's %q */
    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\\n"); '\r' -> sb.append("\\r")
            '\u0000' -> sb.append("\\000")
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class P(val s: String) {
        var i = 0
        fun end() = i >= s.length
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun expect(c: Char) { if (peek() != c) throw Bad("'$c' expected at $i"); i++ }
        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace()) { i++; continue }
                if (c == '-' && i + 1 < s.length && s[i + 1] == '-') {
                    i += 2
                    if (s.startsWith("[[", i)) { val e = s.indexOf("]]", i + 2); if (e < 0) throw Bad("open comment"); i = e + 2 }
                    else { while (i < s.length && s[i] != '\n') i++ }
                    continue
                }
                break
            }
        }
        fun name(): String? {
            val st = i
            if (i < s.length && (s[i].isLetter() || s[i] == '_')) { i++; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++ }
            return if (i > st) s.substring(st, i) else null
        }
        fun table(depth: Int): LinkedHashMap<Any, Any> {
            if (depth > 40) throw Bad("too deep")
            expect('{')
            val t = LinkedHashMap<Any, Any>()
            var pos = 1L
            while (true) {
                ws()
                if (peek() == '}') { i++; return t }
                if (end()) throw Bad("open table")
                val key: Any?; val v: Any?
                if (peek() == '[') {
                    i++; ws(); key = value(depth + 1); ws(); expect(']'); ws(); expect('='); ws(); v = value(depth + 1)
                } else {
                    val save = i
                    val n = name()
                    ws()
                    if (n != null && n != "true" && n != "false" && n != "nil" && peek() == '=' && !s.startsWith("==", i)) {
                        i++; ws(); key = n; v = value(depth + 1)
                    } else { i = save; key = pos++; v = value(depth + 1) }
                }
                val k = when (key) { is Double -> if (key == Math.floor(key)) key.toLong() else throw Bad("odd key"); else -> key }
                if (k != null && v != null) {
                    if (k !is String && k !is Long) throw Bad("odd key")
                    t[k] = v
                }
                ws()
                when (peek()) { ',', ';' -> i++; '}' -> {}; else -> throw Bad("',' expected at $i") }
            }
        }
        fun value(depth: Int): Any? {
            ws()
            val c = peek()
            return when {
                c == '{' -> table(depth)
                c == '"' || c == '\'' -> str(c)
                c == '-' || c == '.' || c.isDigit() -> num()
                else -> when (val n = name()) {
                    "true" -> true; "false" -> false; "nil" -> null
                    else -> throw Bad("value expected at $i")
                }
            }
        }
        fun str(q: Char): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (end()) throw Bad("open string")
                val c = s[i++]
                if (c == q) return sb.toString()
                if (c == '\n') throw Bad("newline in string")
                if (c != '\\') { sb.append(c); continue }
                if (end()) throw Bad("open string")
                val e = s[i++]
                when (e) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'a' -> sb.append('\u0007')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'v' -> sb.append('\u000b')
                    '\\' -> sb.append('\\'); '"' -> sb.append('"'); '\'' -> sb.append('\'')
                    '\n' -> sb.append('\n')
                    '\r' -> { sb.append('\n'); if (peek() == '\n') i++ }
                    else -> if (e.isDigit()) {
                        var d = e - '0'; var k = 1
                        while (k < 3 && peek().isDigit()) { d = d * 10 + (s[i++] - '0'); k++ }
                        if (d > 255) throw Bad("bad escape")
                        sb.append(d.toChar())
                    } else throw Bad("bad escape")
                }
            }
        }
        fun num(): Any {
            val st = i
            if (peek() == '-') i++
            if (s.startsWith("0x", i) || s.startsWith("0X", i)) {
                i += 2; val hs = i
                while (i < s.length && s[i].isLetterOrDigit()) i++
                val v = s.substring(hs, i).toLongOrNull(16) ?: throw Bad("bad number")
                return if (s[st] == '-') -v else v
            }
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))) i++
            val t = s.substring(st, i)
            t.toLongOrNull()?.let { return it }
            val d = t.toDoubleOrNull() ?: throw Bad("bad number '$t'")
            if (d.isNaN() || d.isInfinite()) throw Bad("bad number")
            return if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) d.toLong() else d
        }
    }
}

object SvMerge {
    /** what a merge did (counts only: an entry's key is a character name, never logged) */
    data class Result(val table: Map<Any, Any>, val taken: Int, val added: Int, val refused: Int) {
        val changed get() = taken + added > 0
    }

    /**
     * [local] (the Thor's) with [remote]'s (the PC's) entries in, by the rules in the file's header; [base] = what the PC was last
     * confirmed to hold (null = first sync or not confirmed: [local] wins every conflict, only [remote]-only entries come in). Pure.
     */
    fun merge(local: Map<Any, Any>, remote: Map<Any, Any>, base: Map<Any, Any>?): Result {
        var taken = 0; var added = 0; var refused = 0
        fun unit(l: Any?, r: Any?, b: Any?): Any? {
            if (r == null) return l
            if (l == null) { added++; return r }
            if (same(l, r)) return l
            if (b != null && same(l, b) && !same(r, b)) {
                val ll = SvLua.leaves(l); val rl = SvLua.leaves(r)
                if (ll > 0 && (rl == 0 || (ll >= 4 && rl * 2 < ll))) { refused++; return l }     // empty / much thinner: never
                taken++; return r
            }
            return l
        }
        val out = LinkedHashMap<Any, Any>()
        for (k in LinkedHashSet<Any>(local.keys + remote.keys)) {
            val l = local[k]; val r = remote[k]; val b = base?.get(k)
            val v: Any? = if (l is Map<*, *> && r is Map<*, *> && !isList(l) && !isList(r)) {
                @Suppress("UNCHECKED_CAST") val lm = l as Map<Any, Any>
                @Suppress("UNCHECKED_CAST") val rm = r as Map<Any, Any>
                val bm = b as? Map<*, *>
                val sub = LinkedHashMap<Any, Any>()
                for (k2 in LinkedHashSet<Any>(lm.keys + rm.keys)) unit(lm[k2], rm[k2], bm?.get(k2))?.let { sub[k2] = it }
                sub
            } else unit(l, r, b)
            if (v != null) out[k] = v
        }
        return Result(out, taken, added, refused)
    }

    /** a list: keys exactly 1..n (merged as one entry) */
    fun isList(t: Map<*, *>): Boolean = t.isNotEmpty() && t.keys.all { it is Long } && t.keys.map { it as Long }.sorted() == (1L..t.size).toList()

    fun same(a: Any?, b: Any?): Boolean = a == b || (a != null && b != null && runCatching { SvLua.ser(a) == SvLua.ser(b) }.getOrDefault(false))
}

object SvBackup {
    const val KEEP = 5
    /** the kept copy as it was before the first sync, kept for good (never rotated, never replaced) */
    const val PRESYNC = ".presync"
    const val DIR = "sync-backups"

    /** a dated copy of [f] in [dir] (the newest [KEEP] of its name stay); true = kept (or nothing to keep) */
    fun dated(f: File, dir: File, now: Long = System.currentTimeMillis()): Boolean {
        if (!f.isFile) return true
        return runCatching {
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
            f.copyTo(File(dir, "${f.name}.$stamp.bak"), overwrite = true)
            dir.listFiles()?.filter { it.name.startsWith(f.name + ".") && it.name.endsWith(".bak") }
                ?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
            true
        }.getOrDefault(false)
    }
}

/**
 * THE STREAM SESSION'S SYNC, THOR SIDE (docs/STREAM_BRIDGE_PROTOCOL.md section 7, "sv_read" / "sv_files" / "sv_write" / "sv_written").
 * 1. A welcome with WoW not running on the PC: the Thor sends [readRequest] (its account folder's hash, never its name).
 * 2. Desktop answers "sv_files": our two files from the PC's account folder for that account, as text. [onFiles] merges each into the
 *    Thor's kept copy ([SvMerge], the stored base) - a dated backup first -, and returns the "sv_write" for Desktop.
 * 3. Desktop writes them (only if the PC's file is still what it read, never empty, temp + atomic, its own dated backups) and answers
 *    "sv_written": each table written / same = the PC now holds it ([onWritten]: the base, and the PC's live documents are trusted).
 * While streaming, each `svkeep` from the PC goes through [fromPc] (never SvKeep.store: a whole-table replace).
 */
object SvSync {
    /** our tables the PC is confirmed to hold this run (written or the same at the session's start): its later documents are 3-way */
    private val trusted = HashSet<String>()

    @Synchronized fun trusted(variable: String) = variable in trusted
    @Synchronized fun forget() = trusted.clear()

    fun baseFile(keptDir: File, variable: String) = File(keptDir, "$variable.base.json")
    fun accountFile(keptDir: File) = File(keptDir, "account.id")

    /** the hash Desktop matches the PC's account folder by (the folder names are the same Battle.net account on both installs) */
    fun accountHash(folderName: String): String =
        MessageDigest.getInstance("SHA-256").digest(folderName.lowercase(Locale.ROOT).toByteArray()).joinToString("") { "%02x".format(it) }

    /** "sv_read" (a new sync: the PC's documents are trusted again only once Desktop answers it holds the tables) */
    fun readRequest(keptDir: File): JSONObject = JSONObject().put("t", "sv_read").put("v", 1).also { forget() }
        .put("acct", runCatching { accountFile(keptDir).takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty())

    private fun kept(keptDir: File, variable: String): SvKeep.Doc? =
        SvKeep.fromJson(runCatching { SvKeep.keptFile(keptDir, variable).takeIf { it.isFile }?.readText() }.getOrNull())
    private fun baseOf(keptDir: File, variable: String): Map<Any, Any>? =
        runCatching { SvKeep.fromJson(baseFile(keptDir, variable).readText())?.let { SvLua.parse(it.lua) } }.getOrNull()

    /** the kept copy of [variable] set to [table] (a dated backup first, the pre-sync copy once); one log line */
    private fun keep(keptDir: File, variable: String, table: Map<Any, Any>, now: Long, log: (String) -> Unit): SvKeep.Doc? {
        val f = SvKeep.keptFile(keptDir, variable)
        val presync = File(keptDir, f.name + SvBackup.PRESYNC)
        if (f.isFile && !presync.exists()) runCatching { f.copyTo(presync) }
        if (!SvBackup.dated(f, File(keptDir, SvBackup.DIR), now)) { log("settings sync: $variable not changed (no backup)"); return null }
        log("settings backup kept before sync")
        val d = SvKeep.Doc(variable, SvLua.leaves(table), SvLua.ser(table), now)
        val tmp = File(f.path + ".part")
        tmp.writeText(SvKeep.toJson(d))
        if (f.exists() && !f.delete()) { tmp.delete(); log("settings sync: $variable NOT kept (replace)"); return null }
        if (!tmp.renameTo(f)) { log("settings sync: $variable NOT kept (rename)"); return null }
        return d
    }

    private fun writeBase(keptDir: File, variable: String, lua: String, now: Long) = runCatching {
        val f = baseFile(keptDir, variable)
        val tmp = File(f.path + ".part")
        tmp.writeText(SvKeep.toJson(SvKeep.Doc(variable, 0, lua, now)))
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    /**
     * Desktop's "sv_files": each of our PC files merged into the Thor's kept copy; the "sv_write" to send back (null = Desktop read
     * nothing: no answer is waited for).
     * A PC file that does not parse whole is left alone and not written over (its table: nothing).
     */
    @Synchronized
    fun onFiles(keptDir: File, msg: JSONObject, now: Long = System.currentTimeMillis(), log: (String) -> Unit): JSONObject? {
        val state = msg.optString("state")
        if (state != "ok") { log("settings sync: the PC's files not read ($state)"); return null }
        // the FULL picture first: the Thor's own game file folded into the kept copy (its entries win unless the kept one is newer)
        SvKeep.foldGameIn(keptDir, now, log)
        val files = msg.optJSONArray("files") ?: JSONArray()
        val byVar = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.associateBy { it.optString("var") }
        val out = JSONArray()
        for (variable in SvKeep.FILES.keys) {
            val local = kept(keptDir, variable)
            val localT = local?.let { runCatching { SvLua.parse(it.lua) }.getOrNull() }
            if (local == null || localT == null || local.n == 0) { log("settings sync: $variable: nothing kept on the Thor; the PC's own stays"); continue }
            val f = byVar[variable]
            val text = f?.optString("text").orEmpty()
            val ifHash = f?.optString("hash").orEmpty()
            val full: Map<Any, Any>? = if (f == null || !f.optBoolean("exists", false)) emptyMap() else
                runCatching { SvLua.parse(text, variable) }.getOrNull()
            val remoteRaw = full?.let { SvLua.withoutSkipped(variable, it) }
            if (full == null || remoteRaw == null) { log("settings sync: $variable: the PC's file does not read whole; nothing written"); continue }
            // SvReset (2026-11-19): the PC file's entries a reset on the Thor took away (the file older than the reset, or the old value)
            // are stale: the merge leaves them out, so the PC is written without them
            val resets = SvReset.read(keptDir)
            val pcAt = f?.takeIf { it.has("at") }?.optLong("at")
            val (remote, resetOut) = if (resets.isEmpty()) remoteRaw to 0 else SvReset.strip(variable, remoteRaw, resets, pcAt)
            if (resetOut > 0) log("settings sync: $variable from the PC: $resetOut reset entries left out")
            val base = baseOf(keptDir, variable)
            // RealmFix (the 11-20 hunter): the PC's orphan keys fold first (the real key wins; a store the Thor's real key holds is
            // never given an orphan's), so a realm-less PC session never adds "Name-" / "Name-?" stores to the Thor's kept copy
            // the k6 review round 2 (item C): the name's realms from both tables (the other's kept copy and the PC's other file too)
            val others = SvKeep.otherTables(keptDir, null, variable) + SvKeep.FILES.keys.filter { it != variable }.mapNotNull { v ->
                byVar[v]?.takeIf { it.optBoolean("exists", false) }?.let { o -> runCatching { SvLua.parse(o.optString("text"), v) }.getOrNull() } }
            val m = SvMerge.merge(localT, pcFolded(variable, remote, localT, log, listOf(remoteRaw), resets, others), base)
            log("settings sync: $variable from the PC: ${m.taken} changed, ${m.added} added, ${m.refused} refused" + if (base == null) " (first sync: the Thor's win)" else "")
            val doc = if (m.changed) keep(keptDir, variable, m.table, now, log) ?: continue else local
            if (doc.n == 0) continue
            // the PC file's own keys the add-on never sends (crash-recovery state, learned cooldowns) stay in its file
            val pcOwn = full.filterKeys { it in SvLua.SKIP[variable].orEmpty() }
            val lua = if (pcOwn.isEmpty()) doc.lua else runCatching { SvLua.ser(LinkedHashMap(SvLua.parse(doc.lua)).apply { putAll(pcOwn) }) }.getOrDefault(doc.lua)
            out.put(JSONObject().put("var", variable).put("n", doc.n).put("lua", lua).put("if_hash", ifHash))
        }
        // sent even with no table: Desktop's start waits for this answer, not for its time-out
        return JSONObject().put("t", "sv_write").put("v", 1).put("acct", msg.optString("acct")).put("docs", out)
    }

    /** the PC's table [remote] with its orphan keys folded against the Thor's kept [local] ([RealmFix]); one line when any */
    private fun pcFolded(variable: String, remote: Map<Any, Any>, local: Map<Any, Any>, log: (String) -> Unit,
                         raw: List<Map<Any, Any>?> = emptyList(), resets: List<SvReset.Mark> = emptyList(),
                         others: List<Map<Any, Any>> = emptyList()): Map<Any, Any> {
        // the k6 review (item 3): a store the real key held before a reset (the PC's raw table, a reset mark) is held too: an orphan never
        // brings a reset value back. Round 2 (item C): the realms counted over BOTH our tables ([others]: the other table's copies)
        val r = RealmFix.clean(remote, RealmFix.realmsByName(listOf(local, remote) + others), RealmFix.held(listOf(local) + raw) + RealmFix.resetHeld(resets, variable))
        if (r.items.isNotEmpty()) log("settings sync: $variable from the PC: orphan keys folded (${r.adopted} into the real key, ${r.dropped} dropped)")
        return r.table
    }

    /** Desktop's "sv_written": a table written or already the same = the PC holds it now (the base; its documents trusted) */
    @Synchronized
    fun onWritten(keptDir: File, msg: JSONObject, sent: JSONObject?, now: Long = System.currentTimeMillis(), log: (String) -> Unit) {
        val res = msg.optJSONArray("results") ?: return
        val docs = sent?.optJSONArray("docs") ?: JSONArray()
        val luaOf = (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.associate { it.optString("var") to it.optString("lua") }
        for (i in 0 until res.length()) {
            val r = res.optJSONObject(i) ?: continue
            val variable = r.optString("var"); val st = r.optString("state")
            log("settings sync: $variable on the PC: $st")
            val lua = luaOf[variable] ?: continue
            if (st == "written" || st == "same") {
                val own = runCatching { SvLua.ser(SvLua.withoutSkipped(variable, SvLua.parse(lua))) }.getOrDefault(lua)
                writeBase(keptDir, variable, own, now)
                trusted += variable
            }
        }
    }

    /**
     * A `svkeep` from the PC's add-on while streaming: merged into the Thor's kept copy ([SvMerge]: three-way against the base while the
     * PC is confirmed to hold it, else the Thor's win and only new entries come in); the base follows the PC's document when trusted.
     * Returns the log line ("" = nothing changed).
     */
    @Synchronized
    fun fromPc(keptDir: File, d: SvKeep.Doc, now: Long = System.currentTimeMillis(), log: (String) -> Unit) {
        val remoteIn = runCatching { SvLua.parse(d.lua) }.getOrNull() ?: run { log("settings sync: ${d.variable} from the PC does not read; ignored"); return }
        var local = kept(keptDir, d.variable)
        // no kept copy: the Thor's own game file is it first (when the game's folder is known), so the PC's table only ADDS to it
        if (local == null) { SvKeep.foldGameIn(keptDir, now, log, d.variable); local = kept(keptDir, d.variable) }
        val localT = local?.let { runCatching { SvLua.parse(it.lua) }.getOrNull() }
        val trust = trusted(d.variable)
        // SvReset (2026-11-19): while the PC is confirmed to hold our tables, a reset value it still holds after the grace is the player's
        // own again (the mark retires); otherwise a copy of a reset value is stale and leaves the merge
        var remote = remoteIn
        val resets = SvReset.read(keptDir)
        if (resets.isNotEmpty()) {
            if (trust) SvReset.retire(keptDir, d.variable, remote, now)
            val left = SvReset.read(keptDir)
            val (r, n) = SvReset.strip(d.variable, remote, left, null)
            if (n > 0) { remote = r; log("settings sync: ${d.variable} from the PC: $n reset entries left out") }
        }
        if (local == null || localT == null) {
            // nothing on the Thor at all: the PC's (non-empty) table becomes the first kept copy, MARKED (SvKeep.fromPcMark): the next
            // Local start lets none of its entries replace the game file's own ones (it only adds what the game has not)
            if (d.n > 0) keep(keptDir, d.variable, remote, now, log)?.let {
                runCatching { SvKeep.fromPcMark(keptDir, d.variable).writeText("the PC's table, the Thor had none") }
                log("settings sync: kept ${d.variable} from the PC (the Thor had none)")
            }
            return
        }
        val m = SvMerge.merge(localT, pcFolded(d.variable, remote, localT, log, listOf(remoteIn), SvReset.read(keptDir),
            SvKeep.otherTables(keptDir, null, d.variable)), if (trust) baseOf(keptDir, d.variable) else null)
        val kept = !m.changed || keep(keptDir, d.variable, m.table, now, log) != null
        if (m.changed && kept)
            log("settings sync: ${d.variable} from the PC: ${m.taken} changed, ${m.added} added, ${m.refused} refused" + if (trust) "" else " (not confirmed: the Thor's win)")
        else if (m.refused > 0) log("settings sync: ${d.variable} from the PC: ${m.refused} refused")
        // the base follows what the PC holds now, only once its changes are in the kept copy (a failed write keeps them "changed")
        if (trust && kept && d.n > 0) writeBase(keptDir, d.variable, d.lua, now)
    }
}
