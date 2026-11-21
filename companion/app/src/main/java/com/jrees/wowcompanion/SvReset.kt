package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RESET TO DEFAULTS THAT THE SYNC CANNOT UNDO (the user, 2026-11-19: "a reset must NOT be undone by SvSync / Desktop sync bringing the old
 * values back"). Our two saved tables (WowCompanionDB, WowCompanionSpellsDB) live in three places - the kept copy (Documents/DS Forever/
 * settings), the Thor's game file and the PC's game file - and every merge between them keeps an entry one side lacks and refuses an
 * empty or much thinner value ([SvKeep.fold], [SvMerge.merge]: "nothing is ever dropped"). A reset that only cleared an entry would come
 * straight back from the other copy.
 *
 * So a reset leaves a RESET MARK per entry it cleared ([Mark], resets.json beside the kept copy): the entry (table, top key, and the
 * character "Name-Realm" for a per-character store; null = an account-wide key), the time of the reset, and the values it took away
 * ([Mark.gone]: the kept copy's, the Thor game file's and the two recorded bases' - the PC's last confirmed copy among them). Every merge
 * then treats a copy of that entry as STALE ([strip]: it leaves the merge as if that side had none) when
 *  - the copy holds exactly one of the values the reset took away, or
 *  - the copy comes from a file older than the reset (the Thor's game file at a Local start, the PC's file at a stream session's start;
 *    the kept copy by its own time).
 * A mark RETIRES ([retire]) when the live game - the Thor's add-on, or the PC's while it is confirmed to hold our tables - still holds a
 * reset value [GRACE_MS] after the reset (the player chose it again, or the reset never reached the game: the game is then followed, never
 * fought). At most [MAX] marks are kept, the newest. A reset of a table first keeps a dated copy of the kept file ([backup]: the newest
 * [KEEP_BACKUPS] per file in [BACKUP_DIR]). Pure (java.io + org.json): dsforever's SvResetTest runs it.
 */
object SvReset {
    const val FILE = "resets.json"
    const val BACKUP_DIR = "reset-backups"
    const val KEEP_BACKUPS = 2
    /** a reset value seen in the live game this long after the reset = the player's own choice again (the add-on's 5 s save tick, a
     *  tap's way to the PC and back, with room) */
    const val GRACE_MS = 30_000L
    const val MAX = 300

    /** one entry to reset: [who] = the character's key in a per-character store, null = an account-wide key; [default] = the value it
     *  gets (null = none: the add-on's own default applies) */
    data class Target(val variable: String, val top: String, val who: String?, val default: Any? = null)

    /** a reset entry: the values it took away (each as [SvLua.ser]), and when */
    data class Mark(val variable: String, val top: String, val who: String?, val gone: List<String>, val at: Long) {
        fun same(o: Mark) = variable == o.variable && top == o.top && who == o.who
    }

    fun file(dir: File) = File(dir, FILE)

    fun read(dir: File): List<Mark> = runCatching {
        val a = JSONObject(file(dir).readText()).optJSONArray("marks") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val v = o.optString("var"); val top = o.optString("top")
            if (v !in SvKeep.FILES || top.isEmpty()) return@mapNotNull null
            val g = o.optJSONArray("gone") ?: JSONArray()
            Mark(v, top, o.optString("who").takeIf { o.has("who") && it.isNotEmpty() }, (0 until g.length()).map { g.optString(it) }, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun write(dir: File, marks: List<Mark>) {
        dir.mkdirs()
        val a = JSONArray()
        for (m in marks.sortedBy { it.at }.takeLast(MAX)) a.put(JSONObject().put("var", m.variable).put("top", m.top)
            .apply { if (m.who != null) put("who", m.who) }.put("gone", JSONArray(m.gone)).put("at", m.at))
        val f = file(dir); val tmp = File(f.path + ".part")
        tmp.writeText(JSONObject().put("v", 1).put("marks", a).toString())
        if (f.exists() && !f.delete()) { tmp.delete(); error("could not replace ${f.name}") }
        check(tmp.renameTo(f)) { "could not write ${f.name}" }
    }

    /** [t]'s value of the entry ([top], [who]); null = none */
    fun valueAt(t: Map<Any, Any>, top: String, who: String?): Any? =
        if (who == null) t[top] else (t[top] as? Map<*, *>)?.get(who)

    /** [t] with the entry ([top], [who]) set to [v] (null = removed); a per-character store left empty stays an empty table */
    fun withValue(t: Map<Any, Any>, top: String, who: String?, v: Any?): Map<Any, Any> {
        val out = LinkedHashMap(t)
        if (who == null) { if (v == null) out.remove(top) else out[top] = v; return out }
        @Suppress("UNCHECKED_CAST")
        val sub = LinkedHashMap((t[top] as? Map<Any, Any>)?.takeIf { !SvMerge.isList(it) } ?: emptyMap())
        if (v == null) sub.remove(who) else sub[who] = v
        if (sub.isEmpty() && t[top] == null) return out
        out[top] = sub
        return out
    }

    private fun ser(v: Any?): String? = v?.let { runCatching { SvLua.ser(it) }.getOrNull() }

    /**
     * [t] (a copy of [variable] from a file written at [tableAt]; null = unknown) without the entries [marks] say are stale: a value
     * one of them took away, or any value of theirs when the copy is older than the reset. Returns the table and how many went. Pure.
     */
    fun strip(variable: String, t: Map<Any, Any>, marks: List<Mark>, tableAt: Long?): Pair<Map<Any, Any>, Int> {
        var out = t; var n = 0
        for (m in marks) {
            if (m.variable != variable) continue
            val v = valueAt(out, m.top, m.who) ?: continue
            val old = tableAt != null && tableAt < m.at
            if (old || ser(v) in m.gone) { out = withValue(out, m.top, m.who, null); n++ }
        }
        return out to n
    }

    /** the marks still standing after the live game's [t] of [variable] came at [now]: a reset value held [GRACE_MS] after its reset is
     *  the game's own again (the mark goes). Returns how many retired. */
    fun retire(dir: File, variable: String, t: Map<Any, Any>, now: Long): Int {
        val marks = read(dir)
        if (marks.isEmpty()) return 0
        val keep = marks.filterNot { m -> m.variable == variable && now - m.at > GRACE_MS && ser(valueAt(t, m.top, m.who)) in m.gone }
        if (keep.size != marks.size) runCatching { write(dir, keep) }
        return marks.size - keep.size
    }

    /** a dated copy of [f] in [dir] (the newest [KEEP_BACKUPS] of its name stay); true = kept, or nothing to keep */
    fun backup(f: File, dir: File, now: Long): Boolean {
        if (!f.isFile) return true
        return runCatching {
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
            f.copyTo(File(dir, "${f.name}.$stamp.bak"), overwrite = true)
            dir.listFiles()?.filter { it.name.startsWith(f.name + ".") && it.name.endsWith(".bak") }
                ?.sortedByDescending { it.name }?.drop(KEEP_BACKUPS)?.forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    /** what [apply] did, per table: entries reset (counts only: a key is a character name, never logged) */
    data class Done(val reset: Int, val tables: Int, val refused: List<String>)

    private fun readTable(f: File, variable: String?): Map<Any, Any>? =
        runCatching { if (!f.isFile) null else if (variable == null) SvKeep.fromJson(f.readText())?.let { SvLua.parse(it.lua) } else SvLua.parse(f.readText(), variable) }.getOrNull()

    /** the Thor game file of [variable] (the WTF folder [SvKeep.beforeStart] last saw), or null */
    private fun gameFile(keptDir: File, variable: String): File? {
        val wtf = runCatching { SvKeep.gameWtfFile(keptDir).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return SvKeep.savedVariablesDir(File(wtf))?.let { File(it, SvKeep.FILES.getValue(variable)) }
    }

    /**
     * THE APP'S SIDE OF A RESET: [targets] reset in the kept copy (a dated backup of the file first: none, no change for that table) and
     * a [Mark] each, with every value the entry held in the kept copy, the Thor's game file and the recorded bases ([SvKeep.gameBaseFile],
     * [SvSync.baseFile]: the PC's last confirmed copy). No kept copy yet: it is first made from the game file ([SvKeep.foldGameIn]).
     * The game files are never written here: the next Local start's fold ([SvKeep.foldOne]) and the next stream sync ([SvSync.onFiles])
     * leave the marked entries out. Pure but for the files under [keptDir] and the game file it reads.
     */
    fun apply(keptDir: File, targets: List<Target>, now: Long, log: (String) -> Unit): Done {
        var reset = 0; var tables = 0
        val refused = ArrayList<String>()
        val marks = ArrayList(read(keptDir))
        for ((variable, ts) in targets.groupBy { it.variable }) {
            if (variable !in SvKeep.FILES) continue
            if (!SvKeep.keptFile(keptDir, variable).isFile) runCatching { SvKeep.foldGameIn(keptDir, now, log, variable) }
            val f = SvKeep.keptFile(keptDir, variable)
            val kept = SvKeep.fromJson(runCatching { f.takeIf { it.isFile }?.readText() }.getOrNull())
            val keptT = kept?.let { runCatching { SvLua.parse(it.lua) }.getOrNull() } ?: emptyMap()
            val others = listOfNotNull(gameFile(keptDir, variable)?.let { readTable(it, variable) },
                readTable(SvKeep.gameBaseFile(keptDir, variable), null), readTable(SvSync.baseFile(keptDir, variable), null))
            var t: Map<Any, Any> = keptT
            val mine = ArrayList<Mark>()
            val replaced = ArrayList<Mark>()
            for (tg in ts) {
                val gone = (listOf(keptT) + others).mapNotNull { ser(valueAt(it, tg.top, tg.who)) }.distinct() - setOfNotNull(ser(tg.default))
                val had = valueAt(t, tg.top, tg.who)
                if (gone.isEmpty() && (had == null || SvMerge.same(had, tg.default))) continue
                t = withValue(t, tg.top, tg.who, tg.default)
                // an earlier reset of the same entry: one mark, its values taken away kept too
                val m = Mark(variable, tg.top, tg.who, gone, now)
                val before = marks.filter { it.same(m) }
                replaced += before
                mine += m.copy(gone = (before.flatMap { it.gone } + gone).distinct())
            }
            val n = mine.size
            if (n == 0) continue
            if (!backup(f, File(keptDir, BACKUP_DIR), now)) { refused += variable; log("settings reset: $variable not reset (no backup)"); continue }
            val ok = runCatching {
                val tmp = File(f.path + ".part"); keptDir.mkdirs()
                tmp.writeText(SvKeep.toJson(SvKeep.Doc(variable, SvLua.leaves(t), SvLua.ser(t), now)))
                if (f.exists() && !f.delete()) { tmp.delete(); error("replace") }
                check(tmp.renameTo(f)) { "rename" }
            }.isSuccess
            if (!ok) { refused += variable; log("settings reset: $variable NOT written"); continue }
            marks.removeAll(replaced); marks += mine
            reset += n; tables++
            log("settings reset: $variable: $n entries back to defaults")
        }
        runCatching { write(keptDir, marks) }.onFailure { log("settings reset: marks NOT written (${it.javaClass.simpleName})") }
        return Done(reset, tables, refused)
    }
}
