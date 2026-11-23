package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * WHERE THE CONTROLLER'S PRESSES WENT (issues 580 / 539, 11-23, logging only: "the bar doesn't switch to the In combat bindings",
 * "War Stomp on ZR+Y does nothing" - the reports could not say where a press landed). The add-on (Spells' PadMap) already sends:
 * - `facecast`: each face / D-pad press that reached our button, with what it did (kind) and where (at: the button it clicked);
 *   the app dropped it. The last [KEEP] key-downs are kept for the problem report's "Controller" section, and a press that did
 *   nothing (kind "none") is one WCPAD line (at most one per position every [NONE_EVERY_MS]).
 * - `padack` with ok false: the add-on refused one of the app's changes (only the toolbar's was logged). Each refusal is one WCLOG
 *   line (the same op and reason again within [REFUSED_EVERY_MS] is only counted) and kept, the last [KEEP_REFUSED].
 * - `faceswap`: whether the fight buttons are ours, at a fight's start and end. One compact WCPAD line when it changes.
 * Our own words and the add-on's plain codes only (positions, kinds, button frame names, counts): never a spell, macro or
 * character name. Any thread (synchronized).
 */
object PressLog {
    const val KEEP = 30
    const val KEEP_REFUSED = 10
    const val NONE_EVERY_MS = 10_000L
    const val REFUSED_EVERY_MS = 30_000L

    private val presses = ArrayDeque<String>()
    private val refusals = ArrayDeque<String>()
    private val noneAt = HashMap<String, Long>()
    private val refusedAt = HashMap<String, Long>()
    private val refusedCount = HashMap<String, Int>()
    private var swapLast: String? = null

    private fun hms(ms: Long) = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))
    /** a plain code from the add-on: letters, digits and _-.: only, at most 40 (a frame name, a kind) */
    private fun code(s: String?): String? = s?.filter { it.isLetterOrDigit() || it in "_-.:" }?.take(40)?.ifEmpty { null }

    /** "pos 3 slotfight at MultiBarBottomLeftButton5 fight" (pure) */
    fun pressWords(msg: JSONObject): String = buildString {
        append("pos ").append(code(msg.opt("pos")?.toString()) ?: "?")
        append(' ').append(code(msg.optString("kind", "")) ?: "kind?")
        code(msg.optString("at", ""))?.let { append(" at ").append(it) }
        if (msg.optBoolean("fight", false)) append(" fight")
        code(msg.optString("holder", ""))?.let { append(" held by ").append(it) }
        if (msg.optBoolean("pf", false)) append(" pet-follow")
    }

    /** a `facecast` (key-downs only): kept; the WCPAD line for a press that did nothing, else null */
    @Synchronized fun press(msg: JSONObject, nowMs: Long): String? {
        if (!msg.optBoolean("down", false)) return null
        val w = pressWords(msg)
        presses.addLast("${hms(nowMs)} $w"); while (presses.size > KEEP) presses.removeFirst()
        if (msg.optString("kind", "") != "none") return null
        val pos = code(msg.opt("pos")?.toString()) ?: "?"
        val last = noneAt[pos]
        if (last != null && nowMs - last < NONE_EVERY_MS) return null
        noneAt[pos] = nowMs
        return "press did nothing: $w"
    }

    /** a refused padack: kept; its WCLOG line, or null when the same op and reason came within [REFUSED_EVERY_MS] (counted) */
    @Synchronized fun refused(op: String, why: String?, slot: Int?, idx: Int?, kind: String?, nowMs: Long): String? {
        val key = "${code(op) ?: "?"}|${code(why) ?: ""}"
        val words = buildString {
            append(code(op) ?: "?")
            append(" (").append(why?.take(60)?.filter { it.code in 32..126 }?.ifEmpty { null } ?: "no reason").append(")")
            slot?.let { append(" slot ").append(it) }
            idx?.let { append(" idx ").append(it) }
            code(kind)?.let { append(" kind ").append(it) }
        }
        val last = refusedAt[key]
        if (last != null && nowMs - last < REFUSED_EVERY_MS) { refusedCount[key] = (refusedCount[key] ?: 0) + 1; return null }
        val again = refusedCount.remove(key) ?: 0
        refusedAt[key] = nowMs
        refusals.addLast("${hms(nowMs)} $words"); while (refusals.size > KEEP_REFUSED) refusals.removeFirst()
        return "the game's add-on refused $words" + if (again > 0) "; $again more like it since" else ""
    }

    /**
     * a `faceswap` in short: "fight buttons ours: on (fight-start), 4 keys, 4 ours, 0 given back, in a fight, refused 1"; null =
     * the same as the last one (pure apart from that memory)
     */
    @Synchronized fun swap(msg: JSONObject): String? {
        val k = msg.optJSONObject("k")
        var keys = 0; var ours = 0; var given = 0
        k?.keys()?.forEach { p -> keys++; val e = k.optJSONObject(p); if (e?.optBoolean("ours") == true) ours++; if (e?.optBoolean("given") == true) given++ }
        val l = buildString {
            append("fight buttons ours: ").append(if (msg.optBoolean("on", false)) "on" else "off")
            code(msg.optString("why", ""))?.let { append(" (").append(it).append(")") }
            append(", $keys keys, $ours ours, $given given back")
            if (msg.optBoolean("fight", false)) append(", in a fight")
            if (msg.has("bound")) append(", bound ").append(msg.optInt("bound"))
            val r = msg.opt("refused")
            if (r != null && r != JSONObject.NULL && r != false) append(", refused ").append(code(r.toString()) ?: "yes")
            code(msg.optString("aside", ""))?.let { append(", aside ").append(it) }
        }
        if (l == swapLast) return null
        swapLast = l
        return l
    }

    /** the problem report's section: the last presses, then the last refusals, oldest first */
    @Synchronized fun lines(): List<String> = buildList {
        add("Presses that reached our buttons (the last ${presses.size} of at most $KEEP, key-downs, oldest first):")
        if (presses.isEmpty()) add("  (none this run)") else presses.forEach { add("  $it") }
        add("Changes the game's add-on refused (the last ${refusals.size}):")
        if (refusals.isEmpty()) add("  (none this run)") else refusals.forEach { add("  $it") }
    }

    /** tests */
    @Synchronized internal fun reset() { presses.clear(); refusals.clear(); noneAt.clear(); refusedAt.clear(); refusedCount.clear(); swapLast = null }
}
