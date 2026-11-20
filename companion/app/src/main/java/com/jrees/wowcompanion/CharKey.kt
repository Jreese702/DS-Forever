package com.jrees.wowcompanion

/**
 * THE CHARACTER'S KEY, "Name-Realm" (the 11-20 hunter, test build 1513: "what happened to my combat bars on my hunter? they are gone?").
 * The game answered GetRealmName() with "" for a whole session; the add-on keyed every per-character store "Name-" / "Name-?" and the
 * app followed it (the combat backup "Name-_.json", the D-pad choice, the radials' kept lists, GameUi.character...). The real
 * "Name-Realm" data was never touched, only looked up under the wrong key.
 *
 * THE RULE (the add-on's Safety k6 WC.charKey, the same here): a key is built ONLY from a non-empty name and a real realm. A realm is
 * NOT real when it is null, blank, "?", "_" or holds a control character (the add-on's S() markers \1 \2 \3); then there is no key
 * (null) and nothing per character is read, created, saved, backed up or synced. The add-on now sends the realm only when it knows it
 * (the game's, else the one the character already holds settings under), so a null here means "not known yet".
 *
 * [isOrphan]: a key a realm-less session made ("Name-", "Name-?", "Name-_", "Name-" + a marker); [RealmFix] folds them away.
 * Pure (no Android).
 */
object CharKey {
    /** [r] as a realm, or null when it is not one */
    fun realm(r: String?): String? = r?.takeIf { s -> s.isNotBlank() && s != "?" && s != "_" && s.none { it.isISOControl() } }

    /** "Name-Realm", or null when either is not known */
    fun of(name: String?, realm: String?): String? {
        val n = name?.takeIf { s -> s.isNotBlank() && s.none { it.isISOControl() || it == '-' } } ?: return null
        val r = realm(realm) ?: return null
        return "$n-$r"
    }

    /** the name of a character key (the part before the first '-': a character's name never holds one), or null */
    fun nameOf(key: Any?): String? = (key as? String)?.let { k -> k.indexOf('-').takeIf { it > 0 }?.let { k.substring(0, it) } }

    /** a whole "Name-Realm" key */
    fun isKey(key: Any?): Boolean {
        val k = key as? String ?: return false
        val i = k.indexOf('-')
        return i > 0 && of(k.substring(0, i), k.substring(i + 1)) == k
    }

    /** a partial key a realm-less session made: a character's name, '-', then nothing / "?" / "_" / a marker. The k6 review (item 9):
     *  only a name the game can give a character counts - letters only, never starting with a lower-case one - so a saved table's own
     *  "word-" key ("lay-", "v2-") is never taken for one */
    fun isOrphan(key: Any?): Boolean {
        val k = key as? String ?: return false
        val i = k.indexOf('-')
        if (i <= 0 || !nameLike(k.substring(0, i))) return false
        val r = k.substring(i + 1)
        return r.isEmpty() || r == "?" || r == "_" || (r.length == 1 && r[0].isISOControl())
    }

    /** a name the game gives a character: letters only, the first not lower-case (CJK / Hangul names have no case) */
    fun nameLike(n: String): Boolean = n.isNotEmpty() && n.all { it.isLetter() } && !n[0].isLowerCase()

    /** the orphan keys of [name] a realm-less session makes, in the add-on's order (WC.realmFix's SUFFIX): "Name-", "Name-?", "Name-_" */
    fun orphansOf(name: String): List<String> = listOf("$name-", "$name-?", "$name-_")
}
