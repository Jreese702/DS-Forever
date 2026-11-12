package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * chat33 (#183, 1.0.25: "Not able to send messages in chat/guild/whisper/party/etc"): Chat.lua's login probe {"probe":"ours"} as ONE
 * log line for the problem report. That report's log had every chat key refused and every room's hold "no controller capture", and no
 * Interact document at all, but nothing said why the game had not run DS Forever: Interact. The probe names each of our add-ons the game
 * did not load with the game's own reason (DISABLED, DEP_MISSING, INTERFACE_VERSION, CORRUPT ...), the shared capture's version (0: none)
 * and the first Lua errors from our own files. The Boolean is true (a warning, kept in the report's important lines) when anything is wrong.
 * chat34 (#183 review): "loaded, no capture, no errors" had two causes the line could not tell apart, so the probe also says how far
 * Interact.lua ran (istage: none = never, the game could not read it or the copy is older than i153; top / guard / cap = stopped there;
 * end = ran to its last line), its version (iv) and whether the error handler is still ours (eh: other = a later add-on took it, so a
 * Lua error may not have reached us).
 * Pure: the tests drive it.
 */
fun oursProbeLine(msg: JSONObject): Pair<Boolean, String>? {
    if (msg.optString("probe") != "ours") return null
    val off = msg.optJSONArray("off")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
    val errs = msg.optJSONArray("errs")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
    val capv = msg.optInt("capv", -1)
    val stage = msg.optString("istage", "")
    val iv = msg.optString("iv", "")
    val eh = msg.optString("eh", "")
    val warn = off.isNotEmpty() || errs.isNotEmpty() || capv == 0 || eh == "other" || (stage.isNotEmpty() && stage != "end")
    val sb = StringBuilder("our add-ons: ${msg.optInt("loaded")} loaded")
    if (off.isNotEmpty()) sb.append(", NOT loaded: ").append(off.joinToString(", "))
    sb.append("; controller capture ").append(if (capv == 0) "NONE (Interact never ran: no room can hold the controller)" else "v$capv")
    if (stage.isNotEmpty()) sb.append("; Interact.lua ").append(when (stage) {
        "none" -> "never ran (unreadable or an old copy)"
        "end" -> "ran" + (if (iv.isNotEmpty()) " $iv" else "")
        else -> "stopped after '$stage'"
    })
    if (eh == "other") sb.append("; error handler replaced by another add-on")
    if (errs.isNotEmpty()) sb.append("; Lua errors: ").append(errs.joinToString(" | ") { it.replace('\n', ' ') })
    return warn to sb.toString()
}
