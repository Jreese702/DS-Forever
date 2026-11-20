package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
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

    /**
     * the k6 review (item 3): the per-character entries a Reset took away ([SvReset.Mark]s of [variable]), in [held]'s form
     * "top\u0000Name-Realm". A store a reset cleared counts as HELD by the real key (the reset's own "nothing"), so an orphan is never
     * adopted into it: the reset is never undone by a realm-less session's leftovers.
     */
    fun resetHeld(marks: List<SvReset.Mark>, variable: String): Set<String> =
        marks.filter { it.variable == variable && CharKey.isKey(it.who) }.map { "${it.top}\u0000${it.who}" }.toSet()

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

    // ---------------------------------------------------------------- the app's own per-character preferences (the k6 review, item 8)
    // The realm-less session also left the APP's choices under "Name-" (the D-pad choice, pet recall, hold to talk, the HUD switches,
    // quest watches, flight paths, the Hearthstone's end time, new spells, Polestar's QoL, the chat checklist, the last used chat, the
    // radials' names). The same rule: the real key wins; a choice the real key lacks takes the orphan's (one that holds something); the
    // orphans go; a file where the name holds keys under another realm too is left as it is (never a guess); what goes is kept first.

    /** the per-character preference files a realm-less session wrote "<prefix>Name-" keys in (the key always ENDS with the character) */
    val PREF_FILES = listOf("dpad", "home", "gameui", "wc_itemcd", "questwatch", "taxinet", "newspells", "polestar_qol")
    /** where the folded preferences' removed orphans are kept (the [KEEP] newest per file), app-private */
    const val PREF_BAK = "realm_fix_bak"

    /** one preference file's fold for [real]: [adopt] orphan key -> the real key it fills, [drop] orphan keys the real key already holds
     *  (or that hold nothing) */
    data class PrefPlan(val adopt: Map<String, String>, val drop: Set<String>) {
        val empty get() = adopt.isEmpty() && drop.isEmpty()
    }

    /** [k] split at the character key it ends with: (what comes before "Name-", what follows it), the name after a non-letter/digit */
    private fun tail(k: String, name: String): Pair<String, String>? {
        val pre = "$name-"
        var i = k.indexOf(pre)
        while (i >= 0) {
            if (i == 0 || !k[i - 1].isLetterOrDigit()) return k.substring(0, i) to k.substring(i + pre.length)
            i = k.indexOf(pre, i + 1)
        }
        return null
    }

    /** the fold of one preference file holding [all] (key -> value) for [real] ("Name-Realm"); empty = nothing to do */
    fun prefPlan(all: Map<String, *>, real: String): PrefPlan {
        val none = PrefPlan(emptyMap(), emptySet())
        val name = CharKey.nameOf(real)?.takeIf { CharKey.isKey(real) } ?: return none
        val realm = real.substring(name.length + 1)
        val orphans = ArrayList<Pair<String, String>>()
        for (k in all.keys) {
            val (pre, rest) = tail(k, name) ?: continue
            if (CharKey.isOrphan("$name-$rest")) orphans += k to (pre + real)
            else if (rest != realm && CharKey.realm(rest) != null) return none          // the name under another realm here: left as it is
        }
        if (orphans.isEmpty()) return none
        val adopt = LinkedHashMap<String, String>(); val drop = LinkedHashSet<String>()
        for ((o, r) in orphans.sortedBy { rank("$name-" + tail(it.first, name)!!.second) }) {
            if (r !in all && r !in adopt.values && holds(all[o])) adopt[o] = r else drop += o
        }
        return PrefPlan(adopt, drop)
    }

    private fun jsonHolds(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is JSONObject -> v.length() > 0
        is JSONArray -> v.length() > 0
        is String -> v.isNotEmpty()
        else -> true
    }

    /**
     * a JSON object keyed by character ([RadialMeta]'s "all", [ChatFilter]'s and [TalkKeep]'s stores), folded in place for [real]: the real
     * key wins, a missing one takes the first orphan that holds something; the orphans go. Returns the orphans taken out (their values),
     * or null when there were none or the name holds a key under another realm in it (never a guess: nothing changed).
     */
    fun foldJson(all: JSONObject, real: String): JSONObject? {
        val name = CharKey.nameOf(real)?.takeIf { CharKey.isKey(real) } ?: return null
        val keys = all.keys().asSequence().toList()
        if (keys.any { it != real && CharKey.isKey(it) && CharKey.nameOf(it) == name }) return null
        val orphans = keys.filter { CharKey.isOrphan(it) && CharKey.nameOf(it) == name }.sortedBy { rank(it) }
        if (orphans.isEmpty()) return null
        val out = JSONObject()
        for (o in orphans) {
            val v = all.opt(o)
            if (!all.has(real) && jsonHolds(v)) all.put(real, v)
            out.put(o, v)
            all.remove(o)
        }
        return out
    }

    /** the hosts' backup of what a fold took out of a store ([file], the JSON of the removed values); null in the tests (nothing kept) */
    @Volatile var keepRemoved: ((file: String, removed: String) -> Unit)? = null

    /** [removed] kept in [bak] under [file]: the [KEEP] newest folds, newest first */
    fun keepIn(bak: SharedPreferences, file: String, removed: String, now: Long) {
        val a = runCatching { JSONArray(bak.getString(file, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val out = JSONArray().put(JSONObject().put("at", now).put("removed", JSONObject(removed)))
        for (i in 0 until minOf(a.length(), KEEP - 1)) out.put(a.opt(i))
        bak.edit().putString(file, out.toString()).commit()
    }

    /**
     * the fold of preference file [file] ([p]) for [real]; what goes is kept in [bak] first (none kept: nothing changed). Returns
     * (adopted, dropped). The values keep their own types.
     */
    fun foldPrefs(p: SharedPreferences, file: String, real: String, bak: SharedPreferences, now: Long): Pair<Int, Int> {
        val all = p.all
        val plan = prefPlan(all, real)
        if (plan.empty) return 0 to 0
        val removed = JSONObject()
        for (o in plan.adopt.keys + plan.drop) {
            val v = all[o]
            removed.put(o, JSONObject().put("t", v?.javaClass?.simpleName ?: "null").put("v", if (v is Set<*>) JSONArray(v.toList()) else v ?: JSONObject.NULL))
        }
        if (runCatching { keepIn(bak, file, removed.toString(), now) }.isFailure) return 0 to 0
        val e = p.edit()
        for ((o, r) in plan.adopt) {
            @Suppress("UNCHECKED_CAST")
            when (val v = all[o]) {
                is Boolean -> e.putBoolean(r, v)
                is Int -> e.putInt(r, v)
                is Long -> e.putLong(r, v)
                is Float -> e.putFloat(r, v)
                is String -> e.putString(r, v)
                is Set<*> -> e.putStringSet(r, v as Set<String>)
                else -> {}
            }
        }
        for (o in plan.adopt.keys + plan.drop) e.remove(o)
        e.commit()
        return plan.adopt.size to plan.drop.size
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
        return CombatKeep.Backup(who, (have + add).sortedWith(compareBy({ it.page }, { it.set }, { it.pos })), real?.cls ?: orphan.cls)
    }
}
