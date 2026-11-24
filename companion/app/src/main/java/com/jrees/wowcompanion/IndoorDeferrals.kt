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
 *
 * "Give up after 3 tries" (the user's pick, 2026-11-23: "After 3 failed runs it counts as 'can't be mapped', so the build can finish.
 * It's tried again after a game or builder update."): a transient failure keeps its count ([Entry.tries]) with its identity; the
 * [MAX_TRIES]th failed RUN for the same identity settles it like a rule ([skip]). One failure per run counts once ([Entry.run]);
 * another identity (a game update, a new builder) starts the count over. A run killed inside a building (Android's low-memory kill, a
 * native crash) leaves its [ATTEMPT_FILE] behind: the next run counts it as that run's failure ([killed]).
 */
internal class IndoorDeferrals(private val file: File, private val build: String, private val app: String,
                               private val legacyApp: String? = LEGACY_APP) {
    /** [tries]: the failed runs counted for [identity] (a rule: 0); [run]: the run that counted last (one failure per run) */
    data class Entry(val identity: String, val reason: String, val at: Long, val rule: Boolean = false, val legacy: Boolean = false,
                     val tries: Int = if (rule) 0 else 1, val run: String = "")

    private val entries = LinkedHashMap<String, Entry>()

    init {
        runCatching {
            if (file.isFile) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) o.optJSONObject(k)?.let { e ->
                    val why = e.optString("why")
                    val rule = e.optBoolean("rule")
                    // an entry without a count (1.0.22 - this build): one failed run for a transient failure
                    entries[k] = if (e.has("rule")) Entry(e.optString("id"), why, e.optLong("at"), rule,
                            tries = e.optInt("tries", if (rule) 0 else 1).coerceAtLeast(0), run = e.optString("run"))
                        else Entry(e.optString("id"), why, e.optLong("at"), rule = IndoorGenerator.isKnownRule(why), legacy = true)
                }
            }
        }
    }

    /** the identity a deferral of [work] is good for */
    fun identity(work: IndoorBakeStore.Work): String = "$build|$app|${work.signature}"

    /** [work] was turned down by the builder's rule, or failed on [MAX_TRIES] runs, with this very identity: settled, skip it */
    fun skip(work: IndoorBakeStore.Work): Boolean {
        val e = entries[work.key] ?: return false
        return e.identity == identity(work) && (e.rule || e.tries >= MAX_TRIES)
    }

    /** the deferred entry of [work] whatever its identity (for the log), or null */
    fun entry(work: IndoorBakeStore.Work): Entry? = entries[work.key]

    /**
     * [work] failed in run [run]: a rule is settled at once; anything else counts one more failed run for the same identity (not again
     * for the same [run]; another identity starts over at 1). The entry as saved
     */
    fun defer(work: IndoorBakeStore.Work, reason: String, now: Long, rule: Boolean = false, run: String = ""): Entry {
        val id = identity(work)
        val prev = entries[work.key]?.takeIf { !rule && !it.rule && it.identity == id }
        val tries = when {
            rule -> 0
            prev == null -> 1
            run.isNotEmpty() && prev.run == run -> prev.tries
            else -> prev.tries + 1
        }
        return Entry(id, reason.take(200), now, rule, tries = tries, run = run).also { entries[work.key] = it; save() }
    }

    // ------------------------------------------------------------------ killed mid-build

    private val attemptFile = File(file.parentFile ?: File("."), ATTEMPT_FILE)

    /** [work] is being built now by run [run] (written before the building, gone after it whatever happened, unless the process died) */
    fun attempting(work: IndoorBakeStore.Work, run: String, now: Long) {
        runCatching {
            attemptFile.parentFile?.mkdirs()
            val tmp = File(attemptFile.path + ".part")
            tmp.writeText(JSONObject().put("key", work.key).put("id", identity(work)).put("run", run).put("at", now).toString())
            if (!tmp.renameTo(attemptFile)) { attemptFile.delete(); tmp.renameTo(attemptFile) }
        }
    }

    /** the building ended in this process (built, failed, stopped, let go for the game): no kill */
    fun attempted() { runCatching { attemptFile.delete() } }

    /**
     * the building a run was killed in (the marker left behind), with that run's id, when it is still this catalogue's with the same
     * identity; the marker is gone afterwards either way. The caller counts it ([defer] with that run) unless it was built meanwhile
     */
    fun killed(catalogue: List<IndoorBakeStore.Work>): Pair<IndoorBakeStore.Work, String>? {
        if (!attemptFile.isFile) return null
        val o = runCatching { JSONObject(attemptFile.readText()) }.getOrNull()
        attempted()
        o ?: return null
        val work = catalogue.firstOrNull { it.key == o.optString("key") } ?: return null
        if (o.optString("id") != identity(work)) return null
        return work to o.optString("run").ifEmpty { "killed@${o.optLong("at")}" }
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
            // a transient one from before starts its count of failed runs here (tries 0: its next failure is the first counted)
            entries[work.key] = e.copy(identity = if (same) identity(work) else e.identity, rule = e.rule && same, legacy = false, tries = 0)
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
            for ((k, e) in entries) o.put(k, JSONObject().put("id", e.identity).put("why", e.reason).put("at", e.at).put("rule", e.rule)
                .put("tries", e.tries).put("run", e.run))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".part")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        /** the builder identity every deferral was recorded with from 1.0.22 to 1.0.39 (IndoorSchedulerStep's old `app = "gen"`) */
        const val LEGACY_APP = "gen"
        /** the user's pick (2026-11-23): "Give up after 3 tries" - the failed runs that settle a failure that may pass */
        const val MAX_TRIES = 3
        /** the building being built now and its run (interiors_sched/attempt.json): left behind only by a kill */
        const val ATTEMPT_FILE = "attempt.json"
    }
}
