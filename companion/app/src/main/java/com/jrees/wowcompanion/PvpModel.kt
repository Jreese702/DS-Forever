package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The PvP data: the rank the game shows as tab 4 of its Character window (`PVPRankFrame`), and the battleground
 * queues its minimap "eye" shows. WoW: Forever has no PvP window of its own (retail's is switched off for it), so
 * this is all there is to mirror. The user's answers (2026-09-28): the rank is a TAB OF THE CHARACTER ROOM, whose
 * contents the pvp session builds ([PvpRankTab]); the queue shows as status + Leave (the eye, later).
 *
 * Fed by the pvp addon's `{"type":"pvp","pvp":{...}}` document (`addon/WowCompanionPvp/Pvp.lua`), sent on a change
 * and again on the core's 15 s heartbeat. Every text in [Rank.lines] is the game's own string, formatted by the addon
 * exactly as the game formats it, so nothing here words anything itself. A field the game kept secret does not
 * arrive, and its line is simply left out. Nothing here sends anything to the game.
 */
object PvpModel {

    /** One reward of the next rank that has one: its icon (fileDataID, 0 = none) and the game's words for it. */
    data class Reward(val icon: Int, val text: String)

    /** The next rank that carries a reward; [header] is the game's "Next reward at Rank 5". */
    data class Next(val rank: Int, val header: String, val rewards: List<Reward>)

    /**
     * The rank. [ok] false = the game has no rank data for this character (the track is off, or not reached):
     * the game's tab then shows only [unavailable]. [faction] "Alliance" / "Horde" / "" picks the badge's colours.
     * [earned] / [threshold] are the rank points of this rank ([fill]). The texts, "" when the game gave none:
     * [countdown] "Season ends in 40 Days", [season] "Season 1", [rankLine] "Rank 3: Sergeant" or "Unranked",
     * [progress] "250 / 1000", [title] the rank's name, [number] "Rank 3" (none at rank 0), [desc] the season's
     * rank-up sentence, [seasonProgress], [weeklyCap], [vendor].
     */
    data class Rank(
        val ok: Boolean, val why: String, val faction: String, val unavailable: String,
        val level: Int, val earned: Long?, val threshold: Long?, val max: Int?,
        val countdown: String, val season: String, val rankLine: String, val progress: String,
        val title: String, val number: String, val desc: String, val seasonProgress: String, val weeklyCap: String,
        val next: Next?, val vendor: String,
    ) {
        /** 0..1, how much of this rank's points are earned (the ring round the badge); full when there is no threshold */
        val fill: Float get() = when {
            earned == null -> 0f
            threshold == null || threshold <= 0 -> 1f
            else -> (earned.toFloat() / threshold).coerceIn(0f, 1f)
        }
    }

    /**
     * One battleground queue, as the game's eye lists it. [status] "queued" / "confirm" / "active". The times are
     * as they were when the document was read ([at], ms of this device's clock): [waitedMs] and [estimateMs] while
     * queued, [expiresS] = seconds left to enter once the queue pops, [runTimeMs] = time in the battleground.
     */
    data class Queue(
        val index: Int, val status: String, val map: String,
        val waitedMs: Long?, val estimateMs: Long?, val expiresS: Long?, val runTimeMs: Long?, val at: Long,
    )

    /** null until the first document */
    @Volatile var rank: Rank? = null
        private set
    @Volatile var queues: List<Queue> = emptyList()
        private set
    /** the addon's version ("p2"), shown nowhere yet; "" until the first document */
    @Volatile var version: String = ""
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** [fn] runs after the rank or the queues changed, on the thread that called [update] (MainActivity's UI thread). */
    fun listen(fn: () -> Unit) { listeners += fn }

    private fun tell() { for (fn in listeners.toList()) fn() }

    /** Take a `pvp` document. True when something shown changed. */
    fun update(msg: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        val p = msg.optJSONObject("pvp") ?: return false
        version = p.optString("v", version)
        var changed = false
        p.optJSONObject("rank")?.let { j ->
            val next = readRank(j)
            if (next != rank) { rank = next; changed = true }
        }
        p.optJSONArray("queue")?.let { a ->
            val next = readQueues(a, now)
            // the times move every second; a change is a queue that came, went or changed status
            if (next.map { Triple(it.index, it.status, it.map) } != queues.map { Triple(it.index, it.status, it.map) }) changed = true
            queues = next
        }
        if (changed) tell()
        return changed
    }

    /** Forget everything (tests; a new game session). */
    fun reset() { rank = null; queues = emptyList(); version = "" }

    /** One run of text in one colour (ARGB); [colour] null = the colour of the line it stands in. */
    data class Span(val text: String, val colour: Int?)

    /**
     * The game's own strings carry its text codes (MEASURED on the Thor 2026-09-30: "Rank Points:
     * |cnHIGHLIGHT_FONT_COLOR:0 / 750|r", the vendor line wrapped in |cFFFFFFFF...|r). This reads them as the game draws
     * them: `|cAARRGGBB` colours a run until `|r`; `|n` is a line break; `||` is a bar. The addon (p3 on) turns the game's
     * NAMED colours into the hex form; a named one that still arrives (`|cnNAME:`, an older addon) is dropped with its
     * text kept, as are textures (`|T...|t`, `|A...|a`) and link wrappers (`|H...|h`).
     */
    fun spans(s: String): List<Span> {
        val out = mutableListOf<Span>()
        val stack = ArrayDeque<Int?>()
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            val colour = stack.lastOrNull()
            if (out.isNotEmpty() && out.last().colour == colour) out[out.size - 1] = Span(out.last().text + run, colour)
            else out += Span(run.toString(), colour)
            run.setLength(0)
        }
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '|' || i + 1 >= s.length) { run.append(ch); i++; continue }
            val code = s[i + 1]
            when {
                code == '|' -> { run.append('|'); i += 2 }
                code == 'n' -> { run.append('\n'); i += 2 }
                code == 'c' && i + 2 < s.length && s[i + 2] == 'n' -> {
                    val end = s.indexOf(':', i + 3)
                    flush(); stack.addLast(null)
                    i = if (end < 0) s.length else end + 1
                }
                code == 'c' && i + 10 <= s.length && s.substring(i + 2, i + 10).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> {
                    flush(); stack.addLast(s.substring(i + 2, i + 10).toLong(16).toInt()); i += 10
                }
                code == 'r' -> { flush(); stack.removeLastOrNull(); i += 2 }
                code == 'T' || code == 'A' -> {
                    val close = if (code == 'T') "|t" else "|a"
                    val end = s.indexOf(close, i + 2)
                    i = if (end < 0) s.length else end + 2
                }
                code == 'H' -> { val end = s.indexOf("|h", i + 2); i = if (end < 0) s.length else end + 2 }
                code == 'h' -> i += 2
                else -> { run.append(ch); i++ }
            }
        }
        flush()
        return out
    }

    /** The words alone, every code taken out. */
    fun plain(s: String): String = spans(s).joinToString("") { it.text }

    // The addon never sends null: a value the game hid is left out of the document, and a number field only ever
    // carries a number (its secret / threw markers go to strings the addon drops). So presence is the whole test.
    private fun JSONObject.str(k: String): String = if (has(k)) optString(k, "") else ""
    private fun JSONObject.long(k: String): Long? = if (has(k)) optLong(k, 0L) else null

    private fun readRank(j: JSONObject): Rank {
        val lines = j.optJSONObject("lines") ?: JSONObject()
        val next = j.optJSONObject("next")?.let { n ->
            val items = n.optJSONArray("rewards") ?: JSONArray()
            val list = (0 until items.length()).mapNotNull { i ->
                val r = items.optJSONObject(i) ?: return@mapNotNull null
                // the game's reward pane shows the description; a reward without one is left out, as the game does
                val text = r.str("description").ifEmpty { r.str("name") }
                if (text.isEmpty()) null else Reward(r.long("icon")?.toInt() ?: 0, text)
            }
            Next(n.long("rank")?.toInt() ?: 0, n.str("header"), list)
        }
        return Rank(
            ok = j.optBoolean("ok", false), why = j.str("why"), faction = j.str("faction"), unavailable = j.str("unavailable"),
            level = j.long("level")?.toInt() ?: 0, earned = j.long("earned"), threshold = j.long("threshold"),
            max = j.long("max")?.toInt(),
            countdown = j.str("seasonEndsText"), season = lines.str("season"), rankLine = lines.str("rank"),
            progress = lines.str("progress"), title = j.str("title"), number = lines.str("number"), desc = lines.str("desc"),
            seasonProgress = lines.str("seasonProgress"), weeklyCap = lines.str("weeklyCap"),
            next = next?.takeIf { it.rewards.isNotEmpty() || it.header.isNotEmpty() }, vendor = j.str("vendor"),
        )
    }

    private fun readQueues(a: JSONArray, now: Long): List<Queue> = (0 until a.length()).mapNotNull { i ->
        val q = a.optJSONObject(i) ?: return@mapNotNull null
        val status = q.str("status")
        if (status.isEmpty()) null
        else Queue(q.long("i")?.toInt() ?: (i + 1), status, q.str("map"),
            q.long("waited"), q.long("estimate"), q.long("expires"), q.long("runTime"), now)
    }
}
