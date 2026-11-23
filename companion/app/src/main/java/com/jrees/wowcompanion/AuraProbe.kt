package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * Issue #181 ("Buffs and Debuffs for target and player disappears in top screen DS frame during combat"): the add-on's probe
 * "aurasec" (Trail.lua prd16 P.auraProbe), once per kind and unit a login, says what a fight's aura reads gave the top screen's HUD
 * row ("player") and target bar ("target"): the read's route (index / ids / none, one per filter), how many auras it saw and showed,
 * the secret tables / fields, the throws and the first error. One plain line for the log (WCLOG, which the issue report carries).
 */
object AuraProbe {
    fun line(msg: JSONObject): String {
        val lock = if (msg.has("lock")) msg.optBoolean("lock").toString() else "?"
        val err = msg.optString("err", "").take(120)
        return "aura read ${msg.optString("u", "?")} (${msg.optString("v", "?")}): fight ${msg.optBoolean("fight")} secret ${lock} " +
            "via ${msg.optString("via", "?")} seen ${msg.optInt("seen")} shown ${msg.optInt("shown")} " +
            "secret tables ${msg.optInt("st")} secret fields ${msg.optInt("sf")} threw ${msg.optInt("th")}" +
            if (err.isNotEmpty()) " ($err)" else ""
    }
    /**
     * Issues 558 / 476 (11-23, logging only): whether the game made a piece's fight-aura container on the top screen (Trail.lua ab1's
     * `cap` probe "aurabox", once per piece and outcome a login: key = the piece, made, groups, steps = what a step said, fight = made
     * in a fight). "aura container hud: made true, groups 2, steps ok, out of a fight". Our words and the add-on's plain codes only.
     */
    fun boxLine(msg: JSONObject): String {
        val steps = msg.optJSONObject("steps")
        val said = steps?.keys()?.asSequence()?.sorted()?.joinToString("; ") { k -> "$k: ${steps.optString(k).take(80)}" }.orEmpty()
        return "aura container ${plain(msg.optString("key", "?"))} (${plain(msg.optString("v", "?"))}): made ${msg.optBoolean("made")}, " +
            "groups ${msg.optInt("groups")}, steps ${said.ifEmpty { "ok" }}, ${if (msg.optBoolean("fight")) "in a fight" else "out of a fight"}"
    }

    /**
     * the hub document's aura-container word (Hub.lua hub71's abox = Trail's A.word(): { v, b = { piece = "ok" / "none" / "step: error" },
     * n = buttons the game has made so far }) in one line, the pieces sorted: "aura containers (ab1): hud ok, target ok, tot none;
     * buttons made yes"; null = no word. The count itself moves with every aura, so the line says only whether any were made (logged
     * when the line changes).
     */
    fun wordLine(abox: JSONObject?): String? {
        abox ?: return null
        val b = abox.optJSONObject("b") ?: return null
        val pieces = b.keys().asSequence().sorted().joinToString(", ") { k -> "${plain(k)} ${b.optString(k).take(80)}" }
        return "aura containers (${plain(abox.optString("v", "?"))}): ${pieces.ifEmpty { "none yet" }}; buttons made ${if (abox.optInt("n", 0) > 0) "yes" else "no"}"
    }

    private fun plain(s: String): String = s.filter { it.isLetterOrDigit() || it in "_-.:" }.take(40).ifEmpty { "?" }
}
