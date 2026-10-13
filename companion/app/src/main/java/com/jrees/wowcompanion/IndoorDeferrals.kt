package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.File

/**
 * DEFERRALS THAT OUTLIVE A RESTART (Track C, T3 run 2 / D's report E:/wc-int-out/d/t3-fix/run2/report.md: after a restart all 34
 * deferred units of run 1 were generated again before any new work, ~74 s plus a native-memory peak on every start, and the number
 * grows with the catalogue; those failures are deterministic for the same input). A building that fails is remembered WITH ITS
 * INPUT IDENTITY - the work's signature (algorithm + key + input digest), the game build, and the app install - and skipped until
 * that identity changes (a game update, a new generator algorithm, a new app install); then it is tried once more. Atomic writes:
 * a kill never loses or corrupts the record. Pure (java.io + org.json).
 */
internal class IndoorDeferrals(private val file: File, private val build: String, private val app: String) {
    data class Entry(val identity: String, val reason: String, val at: Long)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        runCatching {
            if (file.isFile) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) o.optJSONObject(k)?.let { e ->
                    entries[k] = Entry(e.optString("id"), e.optString("why"), e.optLong("at"))
                }
            }
        }
    }

    /** the identity a deferral of [work] is good for */
    fun identity(work: IndoorBakeStore.Work): String = "$build|$app|${work.signature}"

    /** [work] failed before with this very identity: skip it */
    fun skip(work: IndoorBakeStore.Work): Boolean = entries[work.key]?.identity == identity(work)

    /** the deferred entry of [work] whatever its identity (for the log), or null */
    fun entry(work: IndoorBakeStore.Work): Entry? = entries[work.key]

    fun defer(work: IndoorBakeStore.Work, reason: String, now: Long) {
        entries[work.key] = Entry(identity(work), reason.take(200), now)
        save()
    }

    /** [work] baked: its deferral (if any) goes */
    fun clear(work: IndoorBakeStore.Work) { if (entries.remove(work.key) != null) save() }

    /** buildings no longer in the catalogue are forgotten */
    fun prune(catalogue: List<IndoorBakeStore.Work>) {
        val keys = catalogue.mapTo(HashSet()) { it.key }
        if (entries.keys.retainAll(keys)) save()
    }

    val size: Int get() = entries.size

    private fun save() {
        runCatching {
            val o = JSONObject()
            for ((k, e) in entries) o.put(k, JSONObject().put("id", e.identity).put("why", e.reason).put("at", e.at))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".part")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
