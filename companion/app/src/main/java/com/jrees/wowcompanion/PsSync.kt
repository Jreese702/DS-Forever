package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * POLESTAR PROGRESS FOLLOWS BETWEEN LOCAL AND STREAMING (the user's pick 2026-11-18). Polestar keeps its own progress in its own saved
 * tables (its .toc: "SavedVariables: PolestarDB", "SavedVariablesPerCharacter: PolestarCharDB"; both files are Polestar.lua): the
 * account's WTF/Account/<account>/SavedVariables/Polestar.lua and each character's .../<realm>/<character>/SavedVariables/Polestar.lua.
 * Local (the Thor's game) and Streaming (the PC's, through DS Forever Desktop 0.2.42: PolestarSvSync.cs) are two installs, so the
 * check-offs, skips, pins and route choices of one never reached the other.
 *
 * At a stream session's start (Desktop's welcome with the PC's WoW not running) and again when the PC's WoW stops, the Thor asks for the
 * PC's files ("ps_read" -> "ps_files"), merges each with its own game file and writes both sides ("ps_write" -> "ps_written"; its own
 * file directly), with the SAME SAFE RULES as our SvKeep / SvSync tables:
 *  - PER ENTRY ([merge]: a table's own keys and, one level down, each entry - manual[guideId], skipped[guideId], pins[guideId] ...);
 *    nothing is ever dropped; the Thor's entry wins the FIRST sync (no base) and every conflict; three-way against the base (what both
 *    sides were last confirmed to hold) an entry only the PC changed comes from the PC;
 *  - NEVER EMPTY OR THINNER WINS, either way: an entry that is empty or holds less than half the other side's settings never replaces
 *    it (a lost / reset / never-saved file, not progress);
 *  - backups before every write: the Thor's file dated (sync-backups, the newest [SvBackup.KEEP]) and as it was before its first
 *    sync write kept for good (.presync); the PC keeps its own the same way (Desktop);
 *  - only while WoW isn't running on the side written (the Thor's game: [Ctx.gameRunning]; the PC's: Desktop refuses);
 *  - only Polestar's two tables in Polestar.lua; no other add-on's file is read or written. A file that does not read whole is left
 *    alone (that key: nothing written either way). Characters cross as hashes of "realm/character" ([charKey]), never names, and the
 *    log says counts only.
 * Pure (java.io + org.json): dsforever's PsSyncTest runs it.
 */
object PsSync {
    const val FILE = "Polestar.lua"
    const val ACCT_VAR = "PolestarDB"
    const val CHAR_VAR = "PolestarCharDB"
    const val ACCT = "acct"
    /** one "ps_write" message's docs at most (Desktop reads messages up to 1 MB) */
    const val MAX_MSG = 900_000

    /** the Thor's side: its game's WTF folder, where the sync keeps its bases and backups, and whether its game runs now */
    class Ctx(val wtf: File, val keptDir: File, val gameRunning: () -> Boolean)

    fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun charKey(realm: String, character: String) = "c:" + sha("$realm/$character".lowercase(Locale.ROOT))
    fun varOf(key: String) = if (key == ACCT) ACCT_VAR else CHAR_VAR

    /** the Thor's account folder (the one SvKeep writes into), or null */
    fun accountDir(wtf: File): File? = SvKeep.savedVariablesDir(wtf)?.parentFile

    /** every key of the Thor's account and its Polestar.lua (the account's, then each character folder with a SavedVariables) */
    fun files(acct: File): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        out[ACCT] = File(acct, "SavedVariables/$FILE")
        acct.listFiles()?.filter { it.isDirectory && it.name != "SavedVariables" }?.sortedBy { it.name }?.forEach { realm ->
            realm.listFiles()?.filter { File(it, "SavedVariables").isDirectory }?.sortedBy { it.name }?.forEach { ch ->
                out[charKey(realm.name, ch.name)] = File(ch, "SavedVariables/$FILE")
            }
        }
        return out
    }

    /** "ps_read" (null = no account folder on the Thor yet: nothing to sync) */
    fun readRequest(ctx: Ctx): JSONObject? {
        val acct = accountDir(ctx.wtf) ?: return null
        return JSONObject().put("t", "ps_read").put("v", 1).put("acct", SvSync.accountHash(acct.name))
    }

    /** what a merge did (counts only) */
    data class Result(val table: Map<Any, Any>, val taken: Int, val added: Int, val refused: Int)

    private fun thin(a: Any?, than: Any?): Boolean {
        val t = SvLua.leaves(than); val x = SvLua.leaves(a)
        return t > 0 && (x == 0 || (t >= 4 && x * 2 < t))
    }

    /**
     * [local] (the Thor's) with [remote] (the PC's) in, by the rules above; [base] = what both last held (null = the first sync). Pure.
     */
    fun merge(local: Map<Any, Any>, remote: Map<Any, Any>, base: Map<Any, Any>?): Result {
        var taken = 0; var added = 0; var refused = 0
        fun unit(l: Any?, r: Any?, b: Any?): Any? {
            if (r == null) return l
            if (l == null) { added++; return r }
            if (SvMerge.same(l, r)) return l
            if (b != null && SvMerge.same(l, b)) {                          // only the PC changed it
                if (thin(r, l)) { refused++; return l }
                taken++; return r
            }
            if (thin(l, r)) { refused++; return r }                // the Thor's is empty / much thinner: never wins
            return l                                                        // the Thor changed it, or both did, or the first sync
        }
        val out = LinkedHashMap<Any, Any>()
        for (k in LinkedHashSet<Any>(local.keys + remote.keys)) {
            val l = local[k]; val r = remote[k]; val b = base?.get(k)
            val v: Any? = if (l is Map<*, *> && r is Map<*, *> && !SvMerge.isList(l) && !SvMerge.isList(r)) {
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

    private fun stem(key: String) = if (key == ACCT) "acct" else key.removePrefix("c:").take(16)
    private fun dir(ctx: Ctx) = File(ctx.keptDir, "polestar")
    fun baseFile(ctx: Ctx, key: String) = File(dir(ctx), "${stem(key)}.base.lua")
    private fun backups(ctx: Ctx) = File(dir(ctx), SvBackup.DIR)

    private fun baseOf(ctx: Ctx, key: String): Map<Any, Any>? =
        runCatching { baseFile(ctx, key).takeIf { it.isFile }?.readText()?.let { SvLua.parse(it) } }.getOrNull()

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        tmp.writeText(text)
        if (f.exists() && !f.delete()) { tmp.delete(); error("could not replace ${f.name}") }
        check(tmp.renameTo(f)) { "could not write ${f.name}" }
    }

    /** the Thor's game file of [key] set to [table]: a dated backup and the presync copy first (no backup, no write) */
    private fun writeThor(ctx: Ctx, key: String, f: File, table: Map<Any, Any>, now: Long): Boolean = runCatching {
        val b = backups(ctx).also { it.mkdirs() }
        if (f.isFile) {
            val pre = File(b, "${stem(key)}.$FILE${SvBackup.PRESYNC}")
            if (!pre.exists()) f.copyTo(pre)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
            f.copyTo(File(b, "${stem(key)}.$FILE.$stamp.bak"), overwrite = true)
            b.listFiles()?.filter { it.name.startsWith("${stem(key)}.$FILE.") && it.name.endsWith(".bak") }
                ?.sortedByDescending { it.name }?.drop(SvBackup.KEEP)?.forEach { it.delete() }
        }
        writeAtomic(f, "\n${varOf(key)} = ${SvLua.ser(table)}\n")
        true
    }.getOrDefault(false)

    /** a key's merge waiting for Desktop's word: the table, and whether the Thor holds it */
    private class Pending(val lua: String, val thorHolds: Boolean)
    private val pending = HashMap<String, Pending>()

    /**
     * Desktop's "ps_files": each key merged, the Thor's file written (when its game isn't running), and the "ps_write" messages for the
     * PC (none = nothing to send; each at most [MAX_MSG]).
     */
    @Synchronized
    fun onFiles(ctx: Ctx, msg: JSONObject, now: Long = System.currentTimeMillis(), log: (String) -> Unit): List<JSONObject> {
        pending.clear()
        val state = msg.optString("state")
        if (state != "ok") { log("polestar sync: the PC's files not read ($state)"); return emptyList() }
        val acct = accountDir(ctx.wtf) ?: run { log("polestar sync: no account folder on the Thor"); return emptyList() }
        val thor = files(acct)
        val arr = msg.optJSONArray("files") ?: JSONArray()
        val pc = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associateBy { it.optString("key") }.filterKeys { it == ACCT || it.startsWith("c:") }
        val running = ctx.gameRunning()
        var thorWrites = 0; var pcDocs = 0; var skipped = 0; var taken = 0; var added = 0; var refused = 0
        val docs = ArrayList<JSONObject>()
        for (key in LinkedHashSet(thor.keys + pc.keys)) {
            val variable = varOf(key)
            val f = pc[key]
            if (f != null && (f.optBoolean("big") || f.optString("hash") == "unreadable" || (f.optBoolean("exists") && !f.has("text")))) { skipped++; continue }
            val remote: Map<Any, Any>? = if (f == null || !f.optBoolean("exists")) emptyMap() else runCatching { SvLua.parse(f.optString("text"), variable) }.getOrNull()
            val tf = thor[key]
            val local: Map<Any, Any>? = if (tf == null || !tf.isFile) emptyMap() else runCatching { SvLua.parse(tf.readText(), variable) }.getOrNull()
            if (remote == null || local == null) { skipped++; continue }              // a file that does not read whole: left alone
            if (remote.isEmpty() && local.isEmpty()) continue
            val m = merge(local, remote, baseOf(ctx, key))
            taken += m.taken; added += m.added; refused += m.refused
            if (SvLua.leaves(m.table) == 0) continue
            val lua = SvLua.ser(m.table)
            // the Thor's side: only a folder the Thor has, only while its own game isn't running
            var thorHolds = SvMerge.same(m.table, local)
            if (!thorHolds && tf != null && !running) {
                thorHolds = writeThor(ctx, key, tf, m.table, now)
                if (thorHolds) thorWrites++ else log("polestar sync: a Thor file NOT written (no backup or no write)")
            }
            // the PC's side: only a key Desktop listed (an existing folder there)
            if (f != null && !SvMerge.same(m.table, remote)) {
                pending[key] = Pending(lua, thorHolds)
                docs += JSONObject().put("key", key).put("var", variable).put("n", SvLua.leaves(m.table)).put("lua", lua).put("if_hash", f.optString("hash"))
                pcDocs++
            } else if (f != null && thorHolds) runCatching { writeAtomic(baseFile(ctx, key), lua) }
        }
        log("polestar sync: ${thor.size} Thor / ${pc.size} PC files; $taken from the PC, $added added, $refused refused; $thorWrites written on the Thor, $pcDocs to the PC" +
            (if (skipped > 0) ", $skipped left alone (do not read whole)" else "") + if (running && thorWrites == 0) " (the Thor's game runs: nothing written here)" else "")
        // into messages Desktop can read
        val out = ArrayList<JSONObject>()
        var cur = JSONArray(); var size = 0
        for (d in docs) {
            val len = d.toString().length
            if (len > MAX_MSG) { log("polestar sync: a file too big for the PC (${len / 1024} KB): not sent"); pending.remove(d.optString("key")); continue }
            if (size + len > MAX_MSG && cur.length() > 0) { out += write(msg, cur); cur = JSONArray(); size = 0 }
            cur.put(d); size += len
        }
        if (cur.length() > 0) out += write(msg, cur)
        return out
    }

    private fun write(msg: JSONObject, docs: JSONArray) = JSONObject().put("t", "ps_write").put("v", 1).put("acct", msg.optString("acct")).put("docs", docs)

    /** Desktop's "ps_written": a key written / the same there while the Thor holds it too = the new base for the next sync */
    @Synchronized
    fun onWritten(ctx: Ctx, msg: JSONObject, log: (String) -> Unit) {
        val res = msg.optJSONArray("results") ?: return
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until res.length()) {
            val r = res.optJSONObject(i) ?: continue
            val key = r.optString("key"); val st = r.optString("state")
            counts[st] = (counts[st] ?: 0) + 1
            val p = pending.remove(key) ?: continue
            if ((st == "written" || st == "same") && p.thorHolds) runCatching { writeAtomic(baseFile(ctx, key), p.lua) }
        }
        log("polestar sync: on the PC " + counts.entries.joinToString(", ") { "${it.value} ${it.key}" })
    }
}
