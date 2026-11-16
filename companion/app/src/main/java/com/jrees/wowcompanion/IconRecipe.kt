package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Only fixed companion references and icons actually requested by this build's UI - and (issue #404, the user's pick 2026-11-16) every
 * class's spellbook icons from this build's own tables ([classBook]: ClassBookIcons, kept by IconRequirements per build).
 */
object IconRecipe {
    fun resolve(seed: JSONObject, observed: JSONObject?, build: String, classBook: JSONObject? = null): JSONObject {
        check(seed.optString("source") == "companion-requests") { "unsupported icon recipe source" }
        val ids = sortedSetOf<Int>()
        fun add(array: JSONArray) {
            for (i in 0 until array.length()) {
                val id = array.optInt(i)
                check(id > 0) { "invalid required icon ID" }
                ids.add(id)
            }
        }
        add(seed.optJSONArray("fids") ?: error("fixed icon references missing"))
        if (observed != null && observed.optString("build") == build)
            add(observed.optJSONArray("fids") ?: error("observed icon references missing"))
        if (classBook != null && classBook.optString("build") == build)
            add(classBook.optJSONArray("fids") ?: error("class spellbook icon references missing"))
        check(ids.isNotEmpty()) { "no required companion icons" }
        return JSONObject().put("fids", JSONArray(ids.toList())).put("tile", 64).put("quality", 80)
    }

    fun fingerprint(recipe: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(recipe.getJSONArray("fids").toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
