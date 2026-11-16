package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE ADD-ON KERNEL'S SELF-TEST AS ONE REPORT LINE (issue #358, 2026-11-15: "Character names: Cyrillic characters are displayed as empty
 * squares instead of actual letters"; its review, 2026-11-16): Safety.lua sends {"type":"cap","kernel":{...}} once a login, and k5 added
 * what decides #358 on the device - how many bytes 128-255 this client's %c calls a control (cc: 0 = ASCII rules, the k4 escape was
 * harmless; 6 = code page 1252, it broke letters), whether a UTF-8 letter leaves the encoder whole (utf8) and the fragmenter (sink). No
 * report ever carried it: DiagLog keeps one summary line per document type per 10 s, the login's other "cap" documents come first, and
 * a summary drops strings (the version words). Numbers, booleans and our own version words only. Pure: the tests drive it.
 */
fun kernelProbeLine(msg: JSONObject): String? {
    if (msg.optString("type") != "cap") return null
    val k = msg.optJSONObject("kernel") ?: return null
    fun word(key: String) = k.optString(key, "").takeIf { it.isNotEmpty() && it.length <= 16 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: "?"
    val sb = StringBuilder("add-on kernel ").append(word("v"))
    if (k.optBoolean("failed", false)) sb.append(" (its self-test FAILED)")
    sb.append(": utf8 ").append(if (!k.has("utf8")) "?" else if (k.optBoolean("utf8")) "whole" else "BROKEN")
    sb.append(", %c high bytes ").append(if (k.has("cc")) k.optInt("cc", -1).toString() else "?")
    sb.append(", sink ").append(word("sink"))
    return sb.toString()
}
