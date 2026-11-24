package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File

/**
 * DEFERRALS THAT OUTLIVE A RESTART (Track C, T3 run 2 / D's report E:/wc-int-out/d/t3-fix/run2/report.md: after a restart all 34
 * deferred units of run 1 were generated again before any new work, ~74 s plus a native-memory peak on every start, and the number
 * grows with the catalogue; those failures are deterministic for the same input). A building that fails is remembered WITH ITS
 * INPUT IDENTITY - the work's signature (algorithm + key + input digest), the game build, and the builder ([app]: the builder's own
 * identity, IndoorSchedulerStep.builderKey) - and skipped until that identity changes (a game update, a new builder); then it is tried
 * once more. Atomic writes: a kill never loses or corrupts the record. Pure (java.io + org.json).
 *
 * D1 (the user's pick, 2026-11-23: "Done when all mappable built"): only a RULE rejection ([Entry.rule]: the builder's own policy turned
 * the building down, IndoorGenerator.Rejected) is settled and skipped; a transient failure (out of memory, an unreadable file) is kept
 * for Settings' list but tried again on the next run. An entry from before D1 (no "rule" field, its identity with the old [legacyApp])
 * is classified by its reason and, a rule, held for the current builder: those 221 deferrals are not re-tried just for this update.
 */
internal class IndoorDeferrals(private val file: File, private val build: String, private val app: String,
                               private val legacyApp: String? = LEGACY_APP) {
    data class Entry(val identity: String, val reason: String, val at: Long, val rule: Boolean = false, val legacy: Boolean = false)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        runCatching {
            if (file.isFile) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) o.optJSONObject(k)?.let { e ->
                    val why = e.optString("why")
                    entries[k] = if (e.has("rule")) Entry(e.optString("id"), why, e.optLong("at"), e.optBoolean("rule"))
                        else Entry(e.optString("id"), why, e.optLong("at"), rule = IndoorGenerator.isRule(why), legacy = true)
                }
            }
        }
    }

    /** the identity a deferral of [work] is good for */
    fun identity(work: IndoorBakeStore.Work): String = "$build|$app|${work.signature}"

    /** [work] was turned down by the builder's rule with this very identity: settled, skip it */
    fun skip(work: IndoorBakeStore.Work): Boolean {
        val e = entries[work.key] ?: return false
        return e.rule && e.identity == identity(work)
    }

    /** the deferred entry of [work] whatever its identity (for the log), or null */
    fun entry(work: IndoorBakeStore.Work): Entry? = entries[work.key]

    fun defer(work: IndoorBakeStore.Work, reason: String, now: Long, rule: Boolean = false) {
        entries[work.key] = Entry(identity(work), reason.take(200), now, rule)
        save()
    }

    /** [work] baked: its deferral (if any) goes */
    fun clear(work: IndoorBakeStore.Work) { if (entries.remove(work.key) != null) save() }

    /**
     * buildings no longer in the catalogue are forgotten; an entry from before D1 is settled for the current builder when it was a
     * rule with the old identity for this very build and input (saved with its kind, so this happens once)
     */
    fun prune(catalogue: List<IndoorBakeStore.Work>) {
        val keys = catalogue.mapTo(HashSet()) { it.key }
        var dirty = entries.keys.retainAll(keys)
        for (work in catalogue) {
            val e = entries[work.key]?.takeIf { it.legacy } ?: continue
            val same = legacyApp != null && e.identity == "$build|$legacyApp|${work.signature}"
            entries[work.key] = e.copy(identity = if (same) identity(work) else e.identity, rule = e.rule && same, legacy = false)
            dirty = true
        }
        if (dirty) save()
    }

    val size: Int get() = entries.size

    /** every deferred building and its reason, oldest first (Settings' list, f-map-tracker) */
    fun reasons(): List<Pair<String, String>> = entries.map { (k, e) -> k to e.reason }

    private fun save() {
        runCatching {
            val o = JSONObject()
            for ((k, e) in entries) o.put(k, JSONObject().put("id", e.identity).put("why", e.reason).put("at", e.at).put("rule", e.rule))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".part")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        /** the builder identity every deferral was recorded with from 1.0.22 to 1.0.39 (IndoorSchedulerStep's old `app = "gen"`) */
        const val LEGACY_APP = "gen"
    }
}
