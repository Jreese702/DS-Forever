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
}
