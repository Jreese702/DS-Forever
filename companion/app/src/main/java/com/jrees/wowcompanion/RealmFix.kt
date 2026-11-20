package com.jrees.wowcompanion

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * THE ORPHAN KEYS' FOLD (the 11-20 hunter, test build 1513; [CharKey]): a session where the game gave no realm left this character's
 * settings under partial keys ("Name-", "Name-?", "Name-_", a marker) beside the real "Name-Realm" ones. The add-on's Safety k6
 * (WC.realmFix) folds its live tables at login; this is the same fold for the app's copies of OUR saved tables (the kept copy, the game
 * file before a start, the PC's tables while streaming) and for the In-combat backup files ([combat]).
 *
 * THE RULE (one, everywhere):
 *  - the REAL key always wins and is never overwritten: a store the real key holds (a setting in any of the copies looked at) keeps its
 *    value, and the orphan's is dropped;
 *  - a store the real key LACKS (in every copy) takes the orphan's when the orphan holds a setting (a leaf that is not ""): the orphan
 *    was made by a realm-less session, the newest record of that store there is; an empty orphan is dropped;
 *  - an orphan whose name holds settings under no realm, or under more than one, is LEFT as it is (never a guess between two characters
 *    of one name; nothing reads it any more);
 *  - every file touched is copied first ([backup]: the 2 newest per file name, in sync-backups/realm-fix).
 * Where: a table's own keys and two levels down (the add-on's DB.store["Name-Realm"] and DB.store.sub["Name-Realm"]). Pure but [backup].
 */
object RealmFix {
    /** one orphan taken out: where ([path], no names), whether its store went to the real key */
    data class Item(val path: String, val adopted: Boolean)
    data class Result(val table: Map<Any, Any>, val items: List<Item>) {
        val adopted get() = items.count { it.adopted }
        val dropped get() = items.count { !it.adopted }
    }

    /** the add-on's own bookkeeping of this fold (WowCompanionDB.realmFixBak): never looked into */
    private const val BAK = "realmFixBak"
    /** the add-on's remembered realms (WowCompanionDB.realmOf[name] = { [realm] = true }) */
    private const val REALM_OF = "realmOf"

    /** holds a setting: a leaf that is not an empty string */
    fun holds(v: Any?): Boolean = when (v) {
        null -> false
        is Map<*, *> -> v.values.any { holds(it) }
        is String -> v.isNotEmpty()
        else -> true
    }

    private fun containers(t: Map<*, *>, f: (String, Map<*, *>) -> Unit) {
        f("", t)
        for ((k1, v) in t) {
            if (v !is Map<*, *> || k1 == BAK) continue
            f("$k1", v)
            for ((k2, w) in v) if (w is Map<*, *>) f("$k1.$k2", w)
        }
    }

    /** every realm each name holds settings under in [tables] (their whole keys, and the add-on's realmOf) */
    fun realmsByName(tables: List<Map<*, *>?>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for (t in tables) {
            if (t == null) continue
            containers(t) { _, m ->
                for (k in m.keys) if (CharKey.isKey(k)) {
                    val s = k as String
                    out.getOrPut(s.substring(0, s.indexOf('-'))) { LinkedHashSet() } += s.substring(s.indexOf('-') + 1)
                }
            }
            (t[REALM_OF] as? Map<*, *>)?.forEach { (n, rs) ->
                val name = n as? String ?: return@forEach
                (rs as? Map<*, *>)?.keys?.forEach { r -> CharKey.realm(r as? String)?.let { out.getOrPut(name) { LinkedHashSet() } += it } }
            }
        }
        return out
    }

    /** the real keys that hold a setting in any of [tables], by container path: "path\u0000Name-Realm" */
    fun held(tables: List<Map<*, *>?>): Set<String> {
        val out = HashSet<String>()
        for (t in tables) if (t != null) containers(t) { path, m ->
            for ((k, v) in m) if (CharKey.isKey(k) && holds(v)) out += "$path\u0000$k"
        }
        return out
    }

    /** the orphan suffixes in the add-on's order (WC.realmFix's SUFFIX): "", "?", "_", then a marker */
    private fun rank(k: String): Int = when (k.substring(k.indexOf('-') + 1)) { "" -> 0; "?" -> 1; "_" -> 2; else -> 3 }

    /**
     * [t] with its orphans folded ([realms]: [realmsByName] over every copy; [heldElsewhere]: [held] over the OTHER copies, so a store the
     * real key holds in any copy is never given an orphan's). Unchanged ([Result.items] empty) when there are none to fold.
     */
    fun clean(t: Map<Any, Any>, realms: Map<String, Set<String>>, heldElsewhere: Set<String> = emptySet()): Result {
        val items = ArrayList<Item>()
        fun fix(path: String, m: Map<Any, Any>, depth: Int): Map<Any, Any> {
            var out: LinkedHashMap<Any, Any>? = null
            fun w(): LinkedHashMap<Any, Any> = out ?: LinkedHashMap(m).also { out = it }
            if (depth < 2) for ((k, v) in m) {
                if (v !is Map<*, *> || (depth == 0 && k == BAK)) continue
                @Suppress("UNCHECKED_CAST") val sub = fix(if (path.isEmpty()) "$k" else "$path.$k", v as Map<Any, Any>, depth + 1)
                if (sub !== v) w()[k] = sub
            }
            val orphans = m.keys.filter { CharKey.isOrphan(it) }.map { it as String }.sortedBy { rank(it) }
            for (o in orphans) {
                val name = CharKey.nameOf(o) ?: continue
                val rs = realms[name] ?: continue
                if (rs.size != 1) continue                                         // none or two characters of one name: left as it is
                val real = "$name-${rs.single()}"
                val cur = out ?: m
                val ov = cur[o] ?: continue
                val rv = cur[real]
                val adopt = !holds(rv) && "$path\u0000$real" !in heldElsewhere && holds(ov)
                val o2 = w()
                o2.remove(o)
                if (adopt) {
                    o2[real] = if (rv is Map<*, *> && ov is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST") LinkedHashMap(rv as Map<Any, Any>).apply { putAll(ov as Map<Any, Any>) }
                    } else ov
                }
                items += Item(path, adopt)
            }
            return out ?: m
        }
        val r = fix("", t, 0)
        return Result(r, items)
    }

    /** the backups' folder (beside the kept copies' sync-backups) and how many per file name stay */
    const val DIR = "realm-fix"
    const val KEEP = 2

    /** a dated copy of [f] in [dir] before the fold touches it (the newest [KEEP] of its name stay); true = kept (or nothing to keep) */
    fun backup(f: File, dir: File, now: Long): Boolean {
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

    // ---------------------------------------------------------------- the In-combat backup files (CombatKeep: combat/<who>.json)

    /** the backup file names a realm-less session made for [name]: "Name-.json" ("Name-"), "Name-_.json" ("Name-?", "Name-_", a marker) */
    fun combatOrphanFiles(name: String): List<String> = listOf("$name-.json", "$name-_.json")

    /**
     * the real backup after an orphan one: the REAL entries always stay (never overwritten); an orphan entry (a setting the real backup
     * does not hold: its page / set / button / face) comes in only when the orphan file is NEWER than the real one ([orphanAt] >
     * [realAt]; no real file = older than anything). Null = nothing to write (no entry came in).
     */
    fun combat(real: CombatKeep.Backup?, realAt: Long, orphan: CombatKeep.Backup?, orphanAt: Long, who: String): CombatKeep.Backup? {
        if (orphan == null || orphan.entries.isEmpty()) return null
        if (real != null && real.who == who && orphanAt <= realAt) return null
        val have = real?.takeIf { it.who == who }?.entries.orEmpty()
        val key = { e: CombatKeep.Entry -> listOf(if (e.face) 0 else e.page, e.set, e.pos, if (e.face) 1 else 0) }
        val taken = have.map(key).toSet()
        val add = orphan.entries.filter { key(it) !in taken }
        if (add.isEmpty()) return null
        return CombatKeep.Backup(who, (have + add).sortedWith(compareBy({ it.page }, { it.set }, { it.pos })))
    }
}
