package com.jrees.wowcompanion

import android.content.SharedPreferences

/**
 * MOVABLE HOME, WHERE THE LAYOUT IS KEPT (SPEC.txt section 9; the user's D5, 2026-11-20: per character, with an "All characters"
 * button). In the "home" prefs (HomeSettings' file, the LAYOUTS reset bucket):
 *   "homeLayout.<Name-Realm>"  the character's active record (JSON, [HomeLayoutJson])
 *   "homeLayoutDefault"        the record before a character is known; it seeds a character's first login and "All characters" writes it
 *   "homeLayoutSlots"          the three device-wide slots of "Mine"
 *   "homeLayoutMigrated"       the one-time migration mark (NEVER reset: a reset leaves today's keys reset too, so Classic comes back)
 * (The spec's "homeLayout.default" / "homeLayouts" are spelled apart from the per-character prefix so a reset's prefix match never takes
 * them for a character.)
 *
 * MIGRATION: the first read builds Classic from today's app-wide keys ([HomeLayoutMigration]); the old setters (Settings > Bottom screen,
 * CastBarAsk, the Controller room's "Turn on") are facades in [HomeSettings] that write the CURRENT character's record, and still write
 * their old keys for one release (a downgrade reads them; castBarChosen keeps its meaning).
 */
object HomeLayoutStore {
    const val KEY_PREFIX = "homeLayout."
    const val KEY_DEFAULT = "homeLayoutDefault"
    const val KEY_SLOTS = "homeLayoutSlots"
    const val KEY_MIGRATED = "homeLayoutMigrated"

    internal var prefs: SharedPreferences? = null
    /** the character now ("Name-Realm"), null = none known yet (the default record is the active one) */
    @Volatile var who: String? = null
        private set
    private var cacheRaw: String? = null
    private var cache: HomeLayout? = null
    private val listeners = ArrayList<() -> Unit>()
    /** a change of the active record (a preset, a swap, a facade setter, another character): HomeSettings' listeners hear it */
    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; for (l in ls) l() }

    private fun key(w: String?) = if (w.isNullOrEmpty()) KEY_DEFAULT else KEY_PREFIX + w

    /** today's keys, read once (the first run of the new build), as the Classic record */
    private fun migrate(p: SharedPreferences) {
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val today = HomeLayoutMigration.fromToday(
            rest = p.getString("rest", null)?.let { runCatching { HomeRest.valueOf(it) }.getOrNull() } ?: HomeRest.DIAMONDS,
            combatGrow = p.getBoolean("combatGrow", true),
            questOverMap = p.getBoolean("questOverMap", true),
            castSide = CastStripModel.sideOf(p.getString("castBarSide", null), p.takeIf { it.contains("castStrip") }?.getBoolean("castStrip", false)))
        val e = p.edit()
        if (!p.contains(KEY_DEFAULT)) e.putString(KEY_DEFAULT, HomeLayoutJson.json(today))
        e.putBoolean(KEY_MIGRATED, true).commit()
    }

    /** the default record (Classic when none is kept) */
    fun default(): HomeLayout {
        val p = prefs ?: return HomeLayout()
        migrate(p)
        return HomeLayoutJson.parse(p.getString(KEY_DEFAULT, null))
    }

    /** the active record: the character's (seeded from the default at its first login), else the default */
    fun active(): HomeLayout {
        val p = prefs ?: return HomeLayout()
        migrate(p)
        val raw = p.getString(key(who), null) ?: p.getString(KEY_DEFAULT, null)
        if (raw == cacheRaw) cache?.let { return it }
        val l = HomeLayoutJson.parse(raw)
        cacheRaw = raw; cache = l
        return l
    }

    /** the character now: its own record (a first login copies the default); a different record re-lays Home with the glide */
    fun character(w: String?) {
        if (w == who) return
        val before = active()
        who = w
        val p = prefs
        if (p != null && !w.isNullOrEmpty() && !p.contains(key(w))) p.edit().putString(key(w), HomeLayoutJson.json(default())).apply()
        if (active() != before) changed()
    }

    /** writes [l] as the active record (the character's, else the default); [all] = "All characters": the default and every character
     *  the prefs know too. True = something changed */
    fun set(l: HomeLayout, all: Boolean = false): Boolean {
        val p = prefs ?: return false
        migrate(p)
        val rec = l.copy(name = HomePresets.nameOf(l))
        val before = active()
        val e = p.edit()
        e.putString(key(who), HomeLayoutJson.json(rec))
        if (all) {
            e.putString(KEY_DEFAULT, HomeLayoutJson.json(rec))
            for (k in p.all.keys) if (k.startsWith(KEY_PREFIX)) e.putString(k, HomeLayoutJson.json(rec))
        }
        e.apply()
        val now = active()
        if (now != before) { changed(); return true }
        return all
    }

    /** a change of the active record by [f] (the facade setters) */
    fun update(f: (HomeLayout) -> HomeLayout): Boolean {
        val a = active()
        val n = f(a)
        return if (n == a) false else set(n)
    }

    /** the characters with a record of their own */
    fun characters(): List<String> = prefs?.all?.keys.orEmpty().filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }.sorted()

    fun slots(): List<HomeLayout?> = HomeLayoutJson.slots(prefs?.getString(KEY_SLOTS, null))
    /** keeps [l] in slot [i] (0..2): "Save here" */
    fun saveSlot(i: Int, l: HomeLayout) {
        val p = prefs ?: return
        if (i !in 0 until HomePresets.SLOTS) return
        val s = slots().toMutableList()
        s[i] = l.copy(name = "${i + 1}")
        p.edit().putString(KEY_SLOTS, HomeLayoutJson.slotsJson(s)).apply()
    }

    /** the name the Home layout tile shows: a preset's, else a slot the record equals ("1".."3"), else Custom */
    fun nameOf(l: HomeLayout): String {
        val n = HomePresets.nameOf(l)
        if (n != HomePresets.CUSTOM) return n
        val i = slots().indexOfFirst { it != null && it.samePlace(l) }
        return if (i >= 0) "${i + 1}" else n
    }

    /** the off-device tests: these prefs (and no character) */
    internal fun startForTest(p: SharedPreferences?) { prefs = p; who = null; cacheRaw = null; cache = null }
    /** a reset removed keys: the cache goes (HomeSettings.resetDone calls the listeners) */
    fun resetDone() { cacheRaw = null; cache = null }
}
